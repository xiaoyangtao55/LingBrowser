"""校验书签文件夹功能的接线与几个关键取舍。

文件夹**不单独建表**，而是从"哪些书签归属于它"派生。这个决定带来
一连串必须保持一致的约束，任何一条被破坏都会产生用户可见的怪现象：

  - 建了 folders 表   -> 删光书签后残留"点进去什么都没有"的幽灵文件夹
  - 移动没刷新        -> 界面上的归属标签不跟着变
  - 删文件夹连带删书签 -> 用户丢收藏（这是最严重的一种）
  - 忘了"新建文件夹"入口 -> 永远建不出第一个文件夹（因为文件夹由书签派生）
  - 当前文件夹被删空后不退回根目录 -> 卡在空页面出不去
  - 图标混用          -> "添加书签"和"书签"入口长得一模一样
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
repo = read(f"{SRC}/data/repo/BookmarkRepository.kt")
screen = read(f"{SRC}/ui/screens/BookmarksScreen.kt")
vm = read(f"{SRC}/ui/BrowserViewModel.kt")
main = read(f"{SRC}/MainActivity.kt")
browser = read(f"{SRC}/ui/screens/BrowserScreen.kt")
icons = read(f"{SRC}/ui/theme/LingIcons.kt")

# ---------- 1. 不建 folders 表 ----------
print("1. 文件夹由书签派生，不另建表")
if re.search(r"CREATE TABLE[^;]*folders", read(f"{SRC}/data/db/LingStore.kt"), re.I):
    bad("建了 folders 表 —— 删光书签后会残留幽灵文件夹")
else:
    ok("没有 folders 表（不存在真空文件夹）")

if re.search(r"internal fun deriveFolders\(", repo):
    ok("存在 deriveFolders 纯函数（可脱离 Android 单测）")
else:
    bad("缺少 deriveFolders")

# ---------- 2. 推导规则 ----------
print("\n2. 推导规则")
# 用"从函数头到文件末尾"取片段：deriveFolders 在文件最后，
# 后面没有空行也没有下一个函数，[\s\S]*?\n\s*\n 会取不到（先前就踩了这个）。
m = re.search(r"internal fun deriveFolders[\s\S]*", repo)
body = m.group(0) if m else ""
if not body:
    bad("取不到 deriveFolders 函数体")
else:
    if "trim()" in body:
        ok("名字先 trim（否则「 新闻 」与「新闻」会显示成两个相同条目）")
    else:
        bad("deriveFolders 未 trim，重复名会显示两次")
    if "isNotEmpty()" in body:
        ok("空串/纯空白被排除（历史脏数据当未分类）")
    else:
        bad("deriveFolders 未排除空串")
    if "distinct()" in body:
        ok("去重")
    else:
        bad("deriveFolders 未去重")
    if "CASE_INSENSITIVE_ORDER" in body:
        ok("按忽略大小写排序（否则大写名会全挤在小写名之前）")
    else:
        bad("deriveFolders 未用 CASE_INSENSITIVE_ORDER 排序")

# ---------- 3. 仓库方法 ----------
print("\n3. 仓库层方法")
for name, desc in [
    ("fun folders()", "文件夹列表"),
    ("fun inFolder(", "按文件夹筛选"),
    ("suspend fun move(", "移动到文件夹"),
    ("suspend fun renameFolder(", "重命名文件夹"),
    ("suspend fun deleteFolder(", "删除文件夹"),
    ("fun folderExists(", "重名检测"),
]:
    if name in repo:
        ok(f"有 {desc}：{name}")
    else:
        bad(f"缺少 {desc}：{name}")

# 重命名用批量 UPDATE 而不是逐条 move
if re.search(r"fun renameFolder[\s\S]{0,400}?update\(\s*\"bookmarks\"[\s\S]{0,120}?\"folder = \?\"", repo):
    ok("renameFolder 用一条批量 UPDATE（逐条会在中途失败时留下半新半旧）")
else:
    bad("renameFolder 未用批量 UPDATE")

# 删文件夹必须保留书签
if re.search(r"fun deleteFolder\(folder: String\)\s*=\s*renameFolder\(folder,\s*null\)", repo):
    ok("deleteFolder = 把归属置 null（书签退回未分类，不连带删除）")
else:
    bad("deleteFolder 未走 renameFolder(folder, null) —— 有连带删书签的风险")

if re.search(r"fun deleteFolder[\s\S]{0,200}?DELETE FROM bookmarks", repo, re.I):
    bad("deleteFolder 里出现了 DELETE，会连书签一起删掉")

# ---------- 4. 写操作都要 refresh ----------
print("\n4. 写操作后刷新")
for fn in ["move", "renameFolder"]:
    mm = re.search(rf"suspend fun {fn}\([\s\S]*?\n    \}}", repo)
    if mm and "refresh()" in mm.group(0):
        ok(f"{fn} 写库后调用了 refresh()")
    else:
        bad(f"{fn} 未刷新快照，界面不会更新")

# ---------- 5. ViewModel ----------
print("\n5. ViewModel 派生文件夹流")
if re.search(r"val bookmarkFolders[\s\S]{0,200}?deriveFolders", vm):
    ok("bookmarkFolders 从书签流派生（书签一变自动跟随）")
else:
    bad("bookmarkFolders 未从书签流派生，可能与书签不同步")

for name in ["moveBookmarkToFolder", "renameBookmarkFolder", "deleteBookmarkFolder"]:
    if f"fun {name}(" in vm:
        ok(f"有 {name}")
    else:
        bad(f"缺少 {name}")

# 重命名要拦重名
if re.search(r"fun renameBookmarkFolder[\s\S]{0,500}?folderExists", vm):
    ok("重命名时检测重名")
else:
    bad("重命名未检测重名，会出现两个同名文件夹")

# 删文件夹的提示要说清书签还在
if re.search(r"fun deleteBookmarkFolder[\s\S]{0,400}?未分类", vm):
    ok("删除文件夹的提示说明书签移到了未分类")
else:
    bad("删除文件夹未提示书签去向，用户会以为收藏丢了")

# ---------- 6. UI ----------
print("\n6. 界面接线")
if "folders = bookmarkFolders" in main:
    ok("MainActivity 传入了 folders")
else:
    bad("MainActivity 未传 folders")

for cb in ["onMoveToFolder", "onRenameFolder", "onDeleteFolder"]:
    if f"{cb} = viewModel::" in main:
        ok(f"{cb} 已接线")
    else:
        bad(f"{cb} 未接线")

if "val bookmarkFolders by viewModel.bookmarkFolders.collectAsStateWithLifecycle()" in main:
    ok("MainActivity collect 了 bookmarkFolders")
else:
    bad("MainActivity 未 collect bookmarkFolders")

# ---------- 7. 「新建文件夹」入口 ----------
print("\n7. 「新建文件夹」入口（没有它永远建不出第一个文件夹）")
if "新建文件夹" in screen:
    ok("移动菜单里有「新建文件夹」")
else:
    bad("没有新建文件夹入口 —— 文件夹由书签派生，缺了它永远建不出第一个")

if re.search(r"新建文件夹[\s\S]{0,500}?onMoveToFolder", screen):
    ok("新建 = 把该书签移进去（文件夹随之产生）")
else:
    bad("新建文件夹没有走移动逻辑")

# ---------- 8. 空文件夹时退回根目录 ----------
print("\n8. 当前文件夹消失后的兜底")
if re.search(r"currentFolder\s*!in\s*folders[\s\S]{0,160}?currentFolder\s*=\s*null", screen):
    ok("当前文件夹已不存在时退回根目录（否则卡在空页面出不去）")
else:
    bad("当前文件夹被删空后未退回根目录，用户会卡在空页面")

# ---------- 9. 删文件夹的确认框 ----------
print("\n9. 删除文件夹的二次确认")
if re.search(r"confirmDelete|删除文件夹", screen):
    ok("有删除确认")
else:
    bad("删除文件夹没有二次确认")

if re.search(r"不会被删除|会移到未分类", screen):
    ok("确认框说明书签不会被删（用户最担心的点）")
else:
    bad("确认框未说明书签不会被删")

# ---------- 10. 图标不混用 ----------
print("\n10. 书签相关图标各司其职")
if "BookmarkAdd" in icons:
    ok("存在 BookmarkAdd 图标")
else:
    bad("缺少 BookmarkAdd 图标")

# 「添加书签」动作必须用带加号的图标，否则与书签入口图标完全相同
if re.search(r"isBookmarked\s*->\s*LingIcons\.Bookmark\b[\s\S]{0,200}?else\s*->\s*LingIcons\.BookmarkAdd", browser):
    ok("添加书签用 BookmarkAdd、已收藏用 Bookmark（两者形状不同）")
else:
    bad("「添加书签」未使用 BookmarkAdd，会与纯书签入口图标一模一样")

# import_material_icons 的映射
imp = read("tools/import_material_icons.py")
if re.search(r'\("BookmarkAdd",\s*"bookmark_add"\)', imp):
    ok("BookmarkAdd 映射到官方 bookmark_add")
else:
    bad("BookmarkAdd 未映射到 bookmark_add")

if re.search(r'\("Bookmark",\s*"bookmark_added_fill"\)', imp):
    ok("Bookmark（已收藏）仍是实心的 bookmark_added_fill")
else:
    bad("已收藏图标映射被改动")

print()
print("=" * 55)
if problems:
    print(f"{len(problems)} 个问题：")
    for p in problems:
        print("  ! " + p)
    sys.exit(1)
print("书签文件夹校验通过")
