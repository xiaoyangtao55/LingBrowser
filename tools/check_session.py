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

print()
print("=" * 55)
if problems:
    print(f"{len(problems)} 个问题：")
    for p in problems:
        print("  ! " + p)
    sys.exit(1)
print("会话恢复接线校验通过")
