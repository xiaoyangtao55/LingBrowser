"""校验会话恢复的接线是否完整、且没有踩到几个已知陷阱。

背景：`tabs` 表与 `TabSnapshot` 早就存在，但 `toTabSnapshot()` **从未
被任何代码调用** —— 整套持久化是死代码，标签从来没有真正被恢复过。
本脚本防止这种"看起来实现了、实际没接上"的状态再次出现。

逐条校验的设计决策（每条都对应一个真实会出错的地方）：
  1. 保存挂在 onStop 而不是 onDestroy（进程被回收时 onDestroy 不保证调用）
  2. persistSession 用独立协程而非 viewModelScope（后者会被一起取消）
  3. 恢复必须异步，且"开主页"的兜底要在 restore 失败之后
  4. 无痕标签必须被过滤（隐私承诺）
  5. 清除数据必须连会话快照一起清
  6. attachHost 要能补挂尚未创建的 WebView
"""
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)

problems = []


def bad(m):
    problems.append(m)
    print("  FAIL " + m)


def ok(m):
    print("  OK   " + m)


def read(rel):
    return open(os.path.join(ROOT, rel), encoding="utf-8").read()


SRC = "app/src/main/java/com/ling/browser"
main = read(f"{SRC}/MainActivity.kt")
vm = read(f"{SRC}/ui/BrowserViewModel.kt")
mgr = read(f"{SRC}/web/WebTabManager.kt")
models = read(f"{SRC}/data/db/Models.kt")
session = read(f"{SRC}/data/repo/SessionRepository.kt")
container = read(f"{SRC}/data/AppContainer.kt")
store = read(f"{SRC}/data/db/LingStore.kt")

# ---------- 1. 不再有死代码 ----------
print("1. 持久化是否真的接上了（此前是死代码）")
if "toTabSnapshot()" in store:
    # 只在 Cursor 扩展定义处出现 = 没人调用
    uses = len(re.findall(r"toTabSnapshot\(\)", store))
    if uses == 1 and "toTabSnapshot" not in session:
        bad("toTabSnapshot() 只有定义没有调用 —— tabs 表仍未被读回")
    else:
        ok("toTabSnapshot() 被仓库层调用")
else:
    bad("tabs 表的行映射 toTabSnapshot 丢失")

if re.search(r"class SessionRepository", session):
    ok("存在 SessionRepository")
else:
    bad("缺少 SessionRepository")

if "SessionRepository(database)" in container:
    ok("SessionRepository 已注册进 AppContainer")
else:
    bad("AppContainer 未注册 SessionRepository")

# ---------- 2. 保存时机 ----------
print("\n2. 保存时机（onStop 而非 onDestroy）")
if re.search(r"override fun onStop\(\)[\s\S]{0,200}?persistSession\(\)", main):
    ok("在 onStop 中保存会话")
else:
    bad("未在 onStop 保存 —— 进程被回收时 onDestroy 不保证调用，会丢标签")

if re.search(r"override fun onDestroy\(\)[\s\S]{0,120}?persistSession", main):
    bad("在 onDestroy 中保存（此时可能已来不及写盘）")
else:
    ok("未依赖 onDestroy 保存")

# onStop 需要拿到 ViewModel
if re.search(r"by viewModels\(\)", main):
    ok("Activity 用 by viewModels() 持有实例（与 setContent 内共享同一实例）")
else:
    bad("Activity 未持有 ViewModel，onStop 里拿不到它")

# ---------- 3. 协程作用域 ----------
print("\n3. 保存用的协程作用域")
# 必须**限定在函数体内**再判断。先前写成
#     fun persistSession()[\s\S]{0,400}?CoroutineScope\(Dispatchers\.IO\)
# 是错的：后面的 clearSession() 里也有同样的 CoroutineScope，
# 于是把 persistSession 换成 viewModelScope 后，这个正则仍能匹配到
# clearSession 的那一处，检查恒为通过（已实测确认这个假阳性）。
m_persist = re.search(r"fun persistSession\(\)[\s\S]*?\n    \}", vm)
if not m_persist:
    bad("找不到 persistSession 函数体")
else:
    body = m_persist.group(0)
    if "CoroutineScope(Dispatchers.IO)" in body:
        ok("persistSession 用独立 CoroutineScope（不会被 viewModelScope 取消）")
    elif "viewModelScope" in body:
        bad("persistSession 用了 viewModelScope —— onStop 后它会被取消，写盘可能中断")
    else:
        bad("persistSession 没有起独立协程")

# ---------- 4. 恢复流程 ----------
print("\n4. 恢复流程")
if "container.session.load()" in vm:
    ok("启动时读取会话快照")
else:
    bad("启动时没有读取会话快照")

if re.search(r"if \(!ok\)[\s\S]{0,200}?newTab\(", vm):
    ok("开主页作为兜底，且排在 restore 之后（不会先开一个空标签）")
else:
    bad("开主页的兜底没排在 restore 之后，可能出现多余的空标签")

if "tabManager.restore(" in vm and re.search(r"fun restore\(", mgr):
    ok("WebTabManager.restore 已实现并被调用")
else:
    bad("restore 未实现或未被调用")

# restore 结束时必须把第一个标签切过去，switchTo 才负责挂载 WebView。
# 这里截取 restore 函数体来查（不用固定长度窗口：函数里注释长短会变，
# 写死 {0,600} 会因注释变长而漏判 —— 先前就这么误报过一次）。
m_restore = re.search(r"fun restore\([\s\S]*?\n    \}", mgr)
if not m_restore:
    bad("找不到 restore 函数体")
elif "switchTo(" in m_restore.group(0):
    ok("恢复后通过 switchTo 挂载（复用宿主挂载逻辑）")
else:
    bad("恢复后未挂载 WebView，界面会是空白")
# 恢复后快照要清掉，避免清除数据后被复活
if re.search(r"container\.session\.clear\(\)", vm):
    ok("读取后清掉快照（避免清除数据后被旧快照复活）")
else:
    bad("读取后未清空快照")

# ---------- 5. 隐私 ----------
print("\n5. 隐私：无痕标签绝不落盘")
if re.search(r"isPersistable", models):
    ok("TabSnapshot 有 isPersistable 判定")
else:
    bad("TabSnapshot 缺少 isPersistable")

if re.search(r"isPersistable[\s\S]{0,160}?!isIncognito", models):
    ok("isPersistable 排除了无痕标签")
else:
    bad("isPersistable 未排除无痕 —— 无痕 URL 会被写进磁盘")

# 仓库层与快照层都要过滤（双保险）
if re.search(r"filterNot \{ it\.isIncognito \}", session):
    ok("SessionRepository.save 也过滤一次（双保险）")
else:
    bad("SessionRepository.save 未过滤无痕")

if re.search(r"fun snapshots\(\)[\s\S]{0,200}?isPersistable", mgr):
    ok("snapshots() 导出时按 isPersistable 过滤")
else:
    bad("snapshots() 未过滤")

# ---------- 6. 清除数据 ----------
print("\n6. 清除数据是否连会话一起清")
if re.search(r"fun clearAllData\(\)[\s\S]{0,600}?clearSession\(\)", vm):
    ok("clearAllData 会清会话快照")
else:
    bad("clearAllData 未清会话快照 —— 清除数据后重启标签会复活")

# ---------- 7. attachHost 的补挂 ----------
print("\n7. attachHost 是否能在 WebView 尚未创建时补挂")
if re.search(r"fun attachHost\([\s\S]{0,400}?obtainWebView\(", mgr):
    ok("attachHost 用 obtainWebView（冷启动恢复时 WebView 可能还没建）")
else:
    bad("attachHost 只查 webViews 缓存 —— 冷启动恢复会出现'有标签但屏幕空白'")

# ---------- 8. 事务 ----------
print("\n8. save 的原子性")
if re.search(r"beginTransaction\(\)", session) and re.search(r"setTransactionSuccessful", session):
    ok("save 使用事务（不会出现删了旧的还没写新的的空窗）")
else:
    bad("save 未用事务，中途失败会导致标签全丢")

# ---------- 9. 「恢复上次浏览页面」开关 ----------
print("\n9. 「恢复上次浏览页面」设置项")
prefs = read(f"{SRC}/data/prefs/SettingsStore.kt")
settings_ui = read(f"{SRC}/ui/screens/SettingsScreen.kt")

if "restoreSession" in prefs:
    ok("LingSettings 有 restoreSession 字段")
else:
    bad("设置里缺少 restoreSession")

if re.search(r"RESTORE_SESSION\s*=\s*booleanPreferencesKey", prefs):
    ok("已声明 DataStore key")
else:
    bad("缺少 RESTORE_SESSION 持久化 key")

if re.search(r"restoreSession\s*=\s*p\[Keys\.RESTORE_SESSION\]\s*\?\:\s*true", prefs):
    ok("默认值为 true（切走再回来应看到原样，是浏览器主流行为）")
else:
    bad("restoreSession 默认值不是 true")

if re.search(r"fun setRestoreSession", prefs):
    ok("有 setRestoreSession 写入方法")
else:
    bad("缺少 setRestoreSession")

if re.search(r"wantRestore[\s\S]{0,300}?session\.load\(\)", vm):
    ok("启动时按开关决定是否读取快照")
else:
    bad("启动时未判断开关，关掉也照样恢复")

# 保存端也要尊重：关掉开关通常意味着"不想让浏览记录留在磁盘上"
if re.search(r"fun persistSession\(\)[\s\S]{0,400}?restoreSession[\s\S]{0,80}?return", vm):
    ok("persistSession 在开关关闭时不写盘")
else:
    bad("persistSession 未判断开关，关掉后仍会写盘")

if re.search(r"fun setRestoreSession[\s\S]{0,400}?clearSession\(\)", vm):
    ok("关闭开关时清掉旧快照（避免重新打开后看到本该遗忘的标签）")
else:
    bad("关闭开关时未清旧快照")

if re.search(r"onRestoreSession\s*:\s*\(Boolean\)\s*->\s*Unit", settings_ui):
    ok("SettingsScreen 声明了 onRestoreSession 回调")
else:
    bad("SettingsScreen 缺少回调参数")

if re.search(r"onCheckedChange\s*=\s*onRestoreSession", settings_ui):
    ok("开关已接到回调上")
else:
    bad("开关没有接到回调")

if "onRestoreSession = viewModel::setRestoreSession" in main:
    ok("MainActivity 已接线")
else:
    bad("MainActivity 未接线")

# 副标题要说明无痕不受影响，否则用户会误解。
#
# ⚠️ 这个判断写过两版都是错的，记下来免得再犯：
#   1) 先写成 `restoreSession[\s\S]{0,240}?无痕` —— 只朝**后**找，
#      而"无痕"其实在 checked= 的**上方**（注释和 subtitle 都可能在上），
#      对正确代码误报。
#   2) 再改成 `SwitchRow\([\s\S]*?checked = settings.restoreSession` ——
#      惰性匹配从**第一个** SwitchRow（夜间模式）就开始了，一路扫到
#      restoreSession 那一行，把中间所有行都吞进来。于是块里必然含有
#      "无痕"（来自它自己的注释），哪怕真删掉 subtitle 也照样通过 ——
#      检查是恒真的。
# 正确做法：先定位到 restoreSession 那一行，再**向上**回退到最近的
# SwitchRow 起点，取两者之间的片段。
m_line = re.search(r"^[^\n]*checked\s*=\s*settings\.restoreSession[^\n]*$", settings_ui, re.M)
if not m_line:
    bad("找不到「恢复上次浏览页面」的 SwitchRow")
else:
    starts = [m.start() for m in re.finditer(r"SwitchRow\(", settings_ui)
              if m.start() < m_line.start()]
    if not starts:
        bad("restoreSession 之前找不到 SwitchRow 起点")
    else:
        block = settings_ui[starts[-1]:m_line.end()]
        if "无痕" in block:
            ok("副标题说明了无痕不受影响")
        else:
            bad("副标题未说明无痕不受影响，用户可能误以为关掉它连无痕也会被保存")

print()
print("=" * 55)
if problems:
    print(f"{len(problems)} 个问题：")
    for p in problems:
        print("  ! " + p)
    sys.exit(1)
print("会话恢复接线校验通过")
