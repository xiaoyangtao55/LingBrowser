"""校验书签备份（导入/导出）功能的接线与几个关键取舍。

备份板块看着只是"读一个文件、写一个文件"，但真正会出事的是下面这些点，
任何一条被破坏，用户都会以"备份没用"的方式感知到：

  - 导出用私有 JSON      -> 文件只有本 App 认，换浏览器/换手机就废了
  - 导入前先清空/覆盖     -> 用户攒的收藏被"导入"顺手删掉（最不可原谅）
  - 逐条 autocommit       -> 几百条导入慢到肉眼可见，且中途失败留半成品
  - 用 CONFLICT_REPLACE   -> 重复的条目连 favicon 一起被删重建，图标消失
  - 让 javascript: 入库   -> 书签里埋了个"点一下即执行"的入口
  - 先整读再判大小         -> 超大文件照读不误，内存直接打爆
  - 定义了却不接线         -> 代码全在，UI 上找不到入口（本脚本的核心目的）
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
backup = read(f"{SRC}/data/repo/BookmarkBackup.kt")
repo = read(f"{SRC}/data/repo/BookmarkRepository.kt")
vm = read(f"{SRC}/ui/BrowserViewModel.kt")
settings = read(f"{SRC}/ui/screens/SettingsScreen.kt")
main = read(f"{SRC}/MainActivity.kt")

# ---------- 1. 纯函数层：可脱离 Android 单测 ----------
print("1. 编解码是纯函数（不碰 Android API）")
for name in [
    "data class ImportedBookmark(",
    "data class ImportPlan(",
    "const val MAX_BACKUP_BYTES",
    "fun toNetscapeHtml(",
    "fun parseNetscapeHtml(",
    "fun planImport(",
]:
    if name in backup:
        ok(f"存在 {name}")
    else:
        bad(f"缺少 {name}")

if re.search(r"^import\s+android", backup, re.M):
    bad("BookmarkBackup.kt 引入了 android.* —— 就不再是纯函数，JVM 单测跑不起来")
else:
    ok("没有引入任何 android.* 依赖（可单测）")

# ---------- 2. 导出格式是浏览器通用的 Netscape HTML ----------
print("\n2. 导出格式")
if "NETSCAPE-Bookmark-file-1" in backup:
    ok("导出的 DOCTYPE 是 Netscape 书签（Chrome/Firefox/Safari 都能读）")
else:
    bad("缺 NETSCAPE-Bookmark-file-1 声明 —— 别的浏览器可能不认这个文件")

if re.search(r"charset=UTF-8", backup, re.I):
    ok("声明了 UTF-8 —— 否则中文标题在别的浏览器里会乱码")
else:
    bad("未声明 charset=UTF-8")

if "ADD_DATE" in backup:
    ok("写入 ADD_DATE（收藏时间）")
else:
    bad("未写 ADD_DATE，导入后收藏时间会全变成导入时刻")

# ---------- 3. 导入必须拒绝伪协议 ----------
print("\n3. 导入拒绝 javascript:/vbscript:/data: 伪协议")
for scheme in ["javascript:", "vbscript:", "data:"]:
    if scheme in backup:
        ok(f"拒绝 {scheme}")
    else:
        bad(f"未拒绝 {scheme} —— 会埋下「点击即执行」的入口")

# ---------- 4. 仓库层批量写入 ----------
print("\n4. 仓库层 addAll（事务 + IGNORE）")
m = re.search(r"suspend fun addAll\([\s\S]*?\n    \}", repo)
body = m.group(0) if m else ""
if not body:
    bad("找不到 addAll 函数体")
else:
    if "beginTransaction()" in body and "setTransactionSuccessful()" in body:
        ok("整批包在一个事务里（逐条 autocommit 会慢且可能写一半）")
    else:
        bad("addAll 未用事务包裹")
    if "CONFLICT_IGNORE" in body:
        ok("用 CONFLICT_IGNORE（重复条目原样保留，不碰已有 favicon）")
    else:
        bad("addAll 未用 CONFLICT_IGNORE")
    if re.search(r"CONFLICT_REPLACE", body):
        bad("addAll 用了 CONFLICT_REPLACE —— 会把已有那条连 favicon 一起删重建")
    if "refresh()" in body:
        ok("写库后 refresh()，否则界面不更新")
    else:
        bad("addAll 未刷新快照")

# ---------- 5. ViewModel 导出/导入 ----------
print("\n5. ViewModel 入口")
for name in ["fun exportBookmarks(", "fun importBookmarks(", "readTextCapped("]:
    if name in vm:
        ok(f"有 {name}")
    else:
        bad(f"缺少 {name}")

m = re.search(r"fun exportBookmarks\([\s\S]*?\n    \}", vm)
if m and "toNetscapeHtml" in m.group(0):
    ok("exportBookmarks 调用 toNetscapeHtml")
else:
    bad("exportBookmarks 未调用 toNetscapeHtml")

m = re.search(r"fun importBookmarks\([\s\S]*?\n    \}", vm)
body = m.group(0) if m else ""
if not body:
    bad("找不到 importBookmarks 函数体")
else:
    for need, desc in [
        ("parseNetscapeHtml", "解析文件"),
        ("planImport", "算合并计划（去重）"),
        ("container.bookmarks.addAll", "批量写库"),
    ]:
        if need in body:
            ok(f"importBookmarks 用到了{desc}")
        else:
            bad(f"importBookmarks 未用到{desc}")
    # 导入路径绝不能出现删除/清空：宁可跳过多几条，也不能丢用户的书签
    if re.search(r"delete|clear|DELETE", body, re.I):
        bad("importBookmarks 里出现了删除/清空 —— 导入绝不能删已有书签")
    else:
        ok("导入路径不含任何删除/清空（合并语义）")

if re.search(r"readTextCapped[\s\S]{0,700}?MAX_BACKUP_BYTES", vm):
    ok("读文件时用 MAX_BACKUP_BYTES 边读边卡上限")
else:
    bad("读文件未用 MAX_BACKUP_BYTES，超大文件会把内存打爆")
if re.search(r"readTextCapped[\s\S]{0,700}?readBytes\(", vm):
    bad("用了 readBytes() —— 它会把整个流读完，没有上限")
else:
    ok("没有用无边界的 readBytes()")

# ---------- 6. UI：分组页与文件选择器 ----------
print("\n6. 设置页与文件选择器")
if re.search(r"BACKUP\(", settings):
    ok("SettingsGroup 里有 BACKUP 分组")
else:
    bad("SettingsGroup 缺 BACKUP")
if re.search(r"SettingsGroup\.BACKUP", settings):
    ok("首页列出了「备份与恢复」入口")
else:
    bad("首页没有指向备份页的入口 —— 功能等于不存在")
if "fun BackupSettingsScreen(" in settings:
    ok("存在 BackupSettingsScreen")
else:
    bad("缺少 BackupSettingsScreen")
if "CreateDocument" in settings:
    ok("导出用 CreateDocument（SAF，无需存储权限）")
else:
    bad("导出未用系统选择器")
if "OpenDocument" in settings:
    ok("导入用 OpenDocument")
else:
    bad("导入未用系统选择器")

# ---------- 7. 路由接线 ----------
print("\n7. MainActivity 路由接线")
if re.search(r"SettingsGroup\.BACKUP\s*->\s*Route\.SettingsBackup", main):
    ok("分组映射到 Route.SettingsBackup")
else:
    bad("SettingsGroup.BACKUP 没有映射到路由")
if re.search(r"Route\.SettingsBackup\s*->\s*BackupSettingsScreen\(", main):
    ok("Route.SettingsBackup 渲染 BackupSettingsScreen")
else:
    bad("路由没有渲染 BackupSettingsScreen")
if re.search(r"onExport\s*=\s*viewModel::exportBookmarks", main):
    ok("导出按钮接到 ViewModel.exportBookmarks")
else:
    bad("导出没有接到 ViewModel")
if re.search(r"onImport\s*=\s*viewModel::importBookmarks", main):
    ok("导入按钮接到 ViewModel.importBookmarks")
else:
    bad("导入没有接到 ViewModel")
if re.search(r"data object SettingsBackup\s*:\s*Route", main):
    ok("Route.SettingsBackup 已声明")
else:
    bad("缺 Route.SettingsBackup 声明")

# ---------- 8. 单测 ----------
print("\n8. 单测覆盖")
test_file = os.path.join(
    ROOT, "app", "src", "test", "java", "com", "ling", "browser",
    "data", "repo", "BookmarkBackupTest.kt",
)
if os.path.exists(test_file):
    t = open(test_file, encoding="utf-8").read()
    ok("存在 BookmarkBackupTest.kt")
    for need, desc in [
        ("toNetscapeHtml", "导出"),
        ("parseNetscapeHtml", "解析"),
        ("planImport", "合并去重"),
        ("javascript:", "伪协议拒绝"),
    ]:
        if need in t:
            ok(f"单测覆盖了{desc}")
        else:
            bad(f"单测未覆盖{desc}")
else:
    bad("缺少 BookmarkBackupTest.kt")

print("\n" + "=" * 55)
if problems:
    print(f"{len(problems)} 个问题：")
    for p in problems:
        print("  ! " + p)
    sys.exit(1)
print("书签备份功能接线与取舍校验通过。")
