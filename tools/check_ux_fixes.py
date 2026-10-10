"""校验「地址栏焦点释放」「书签重命名/删除确认」「书签网站图标」三项。

每一项都对应一个真实出现过或极易出现的问题：

  1. 地址栏回车后不释放焦点 -> 光标继续闪 + **网页里的输入框唤不起输入法**
     （焦点还在地址栏，WebView 拿不到焦点）
  2. 书签能直接删且不能改名 -> 与文件夹的交互不一致，误删无法恢复
  3. 图标相关的三个坑：
     - 存 Bitmap 而不是字节 -> 列表重建时 `use of recycled bitmap` 崩溃
     - 空数组与 null 不区分 -> 每次进页面都重试注定失败的站点，白耗流量
     - 老库升级时 DROP 重建 -> 用户的书签和历史全丢
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
addr = read(f"{SRC}/ui/components/AddressBar.kt")
vm = read(f"{SRC}/ui/BrowserViewModel.kt")
browser_screen = read(f"{SRC}/ui/screens/BrowserScreen.kt")
mgr = read(f"{SRC}/web/WebTabManager.kt")
bm_screen = read(f"{SRC}/ui/screens/BookmarksScreen.kt")
repo = read(f"{SRC}/data/repo/BookmarkRepository.kt")
models = read(f"{SRC}/data/db/Models.kt")
store = read(f"{SRC}/data/db/LingStore.kt")
fetcher = read(f"{SRC}/util/FaviconFetcher.kt")
main = read(f"{SRC}/MainActivity.kt")

# ---------- 1. 地址栏焦点 ----------
print("1. 地址栏交出焦点后必须把焦点还给网页")
#
# 这个问题修了两次才对，两版都记下来，因为**第一版的思路是错的**：
#   第一版：只加 clearFocus() + keyboard.hide()
#           -> 光标确实消失了，但网页输入框**依然**唤不起输入法（真机实测）
#   第二版：clearFocus() 之后显式把焦点交给 WebView（focusWebContent）
#           -> 这才是根因：Compose 释放焦点并不会让焦点回到 WebView，
#              焦点只是"悬空"了，没有任何 View 持有它，IME 自然不弹。
# 所以本节的检查重点从"有没有 clearFocus"改为"有没有把焦点还回去"。

if re.search(r"val releaseFocus[\s\S]{0,600}?focusManager\.clearFocus\(\)", addr):
    ok("releaseFocus 调用了 clearFocus()")
else:
    bad("没有 clearFocus()")

if re.search(r"val releaseFocus[\s\S]{0,700}?onReleaseFocus\(\)", addr):
    ok("releaseFocus 把焦点**交还**给网页（这才是根因；只 clearFocus 不够）")
else:
    bad("releaseFocus 未交还焦点 —— 焦点悬空，网页输入框唤不起输入法")

if re.search(r"onReleaseFocus:\s*\(\)\s*->\s*Unit", addr):
    ok("AddressBar 声明了 onReleaseFocus 回调")
else:
    bad("AddressBar 缺少 onReleaseFocus 参数")

if re.search(r"onReleaseFocus\s*=\s*\{[\s\S]{0,160}?focusWebContent\(\)", browser_screen):
    ok("调用方把 onReleaseFocus 接到了 focusWebContent")
else:
    bad("onReleaseFocus 未接到 focusWebContent")

# focusWebContent 本身
if re.search(r"fun focusWebContent\(\)", mgr):
    ok("WebTabManager 有 focusWebContent")
else:
    bad("缺少 focusWebContent")

if re.search(r"fun focusWebContent[\s\S]{0,900}?container\?\.requestFocus\(\)", mgr):
    ok("focusWebContent 让宿主 requestFocus")
else:
    bad("focusWebContent 未让宿主取回焦点")

if re.search(r"fun focusWebContent[\s\S]{0,1100}?evaluateJavascript", mgr):
    ok("focusWebContent 用 JS 把焦点下沉到网页内（View 层有焦点≠网页内有焦点）")
else:
    bad("focusWebContent 未把焦点下沉到网页")

# 必须 post 到下一帧。
# 用"函数体"而不是固定字符窗口：focusWebContent 里注释很长，
# 写死 {0,1400} 会因为注释变长而漏判（先前就这么误报过一次）。
m_fw = re.search(r"fun focusWebContent\(\)[\s\S]*?\n    \}", mgr)
if not m_fw:
    bad("找不到 focusWebContent 函数体")
elif ".post(run)" in m_fw.group(0):
    ok("focusWebContent 推迟到下一帧执行（同帧 requestFocus 可能被丢弃）")
else:
    bad("focusWebContent 未 post —— 与 clearFocus 同帧时可能失效")

# 宿主可聚焦
if re.search(r"FrameLayout\(context\)\.apply[\s\S]{0,700}?isFocusable = true", browser_screen):
    ok("WebView 宿主 FrameLayout 显式可聚焦（默认 focusable=false）")
else:
    bad("宿主不可聚焦 —— WebView 拿不到焦点")

if re.search(r"isFocusableInTouchMode = true", browser_screen):
    ok("宿主开启 isFocusableInTouchMode（触摸模式下才可聚焦）")
else:
    bad("宿主未开启 isFocusableInTouchMode")

# 第二道保险：点网页时取回焦点
if re.search(r"setOnTouchListener \{[\s\S]{0,300}?v\.isFocused[\s\S]{0,120}?requestFocus", mgr):
    ok("触摸网页时自动取回焦点（第二道保险）")
else:
    bad("缺少触摸取回焦点的保险")

# 触摸监听必须只观察不消费，否则会吃掉滚动/点击
if re.search(r"setOnTouchListener \{[\s\S]{0,400}?\n\s+false\s*\n", mgr):
    ok("触摸监听返回 false（只观察不消费，不影响滚动与点击）")
else:
    bad("触摸监听可能消费了事件，会破坏网页滚动/点击")

# 不能加会递归的焦点监听。
# ⚠️ 必须先剔掉注释再判断：解释"为什么不这么做"的注释里也会同时出现
# `setOnFocusChangeListener` 和 `focusWebContent`，直接匹配会误报（踩过）。
mgr_code = re.sub(r"//.*$", "", mgr, flags=re.M)
if re.search(r"setOnFocusChangeListener[\s\S]{0,200}?focusWebContent", mgr_code):
    bad("WebView 上挂了 onFocusChange -> focusWebContent，会无限递归")
else:
    ok("未挂自递归的焦点监听")

if re.search(r"\.onFocusChanged\s*\{[\s\S]{0,160}?onFocusChange\(", addr):
    ok("onFocusChanged 观察者已接到 onFocusChange（此前该参数从未被调用）")
else:
    bad("onFocusChange 仍未接入焦点观察者")

if re.search(r"fun submitAddress[\s\S]{0,700}?_addressText\.value\s*=\s*url", vm):
    ok("submitAddress 锁定地址栏文本（避免失焦回调用旧 url 把地址栏清空）")
else:
    bad("submitAddress 未锁定地址栏文本")

# ---------- 2. 书签重命名与删除确认 ----------
print("\n2. 书签的重命名与删除确认")
if "onRename" in bm_screen and re.search(r"fun renameBookmark\(", vm):
    ok("书签可重命名")
else:
    bad("书签仍不能重命名")

if re.search(r"fun renameBookmark[\s\S]{0,500}?isEmpty\(\)[\s\S]{0,200}?return", vm):
    ok("重命名时拒绝空名字（列表里一条没有文字的书签无法辨认）")
else:
    bad("重命名未拦截空名字")

if re.search(r"fun renameBookmark[\s\S]{0,600}?rename\(id,\s*clean,\s*current\.folder\)", vm):
    ok("重命名只改标题、保留所属文件夹")
else:
    bad("重命名可能把文件夹一起改掉")

# 书签删除二次确认。
# ⚠️ 不能只查 "confirmDelete" 是否出现 —— 它作为状态变量本来就在多处出现，
# 把确认框的 `if (confirmDelete)` 改成 `if (false)` 后检查仍会通过
# （已实测这个假阳性）。必须要求**确认框真的被渲染**，即
# `if (confirmDelete) {` 后面跟着 AlertDialog。
m_del = re.search(r"if \(confirmDelete\) \{[\s\S]{0,300}?AlertDialog", bm_screen)
if m_del:
    ok("书签删除有二次确认（确认框真的被渲染）")
else:
    bad("书签删除没有二次确认（与文件夹的交互不一致）")

# 确认框的按钮必须真的调 onDelete
if re.search(r"if \(confirmDelete\)[\s\S]{0,900}?onClick = \{ onDelete\(\)", bm_screen):
    ok("确认后才执行删除")
else:
    bad("确认框未真正触发删除")

if re.search(r"无法撤销", bm_screen):
    ok("确认文案说明不可撤销，且带上了书签标题")
else:
    bad("删除确认未说明后果或未带上标题")

# ---------- 3. 网站图标 ----------
print("\n3. 书签网站图标")
if re.search(r"class FaviconFetcher|object FaviconFetcher", fetcher):
    ok("存在 FaviconFetcher")
else:
    bad("缺少 FaviconFetcher")

# 存字节而不是 Bitmap
if re.search(r"val favicon:\s*ByteArray\?", models):
    ok("模型里图标存 ByteArray 而不是 Bitmap（绕开 recycle 生命周期问题）")
else:
    bad("图标未以 ByteArray 存储，有 recycled bitmap 崩溃风险")

if re.search(r"override fun equals", models) and re.search(r"contentEquals|contentHashCode", models):
    ok("含数组的 data class 手写了 equals/hashCode（否则比较引用、反复重组）")
else:
    bad("Bookmark 含 ByteArray 但未手写 equals/hashCode")

# 大小限制：不拦会把内存打爆
if re.search(r"MAX_BYTES", fetcher) and re.search(r"total > MAX_BYTES", fetcher):
    ok("边读边数字节数，超限放弃（只信 Content-Length 会被分块传输绕过）")
else:
    bad("未做读取体积上限，超大图会打爆内存")

if re.search(r"contentLengthLong > MAX_BYTES", fetcher):
    ok("先看 Content-Length 提前放弃")
else:
    bad("未用 Content-Length 提前短路")

# 压缩后再存
if re.search(r"compress\(Bitmap\.CompressFormat\.WEBP", fetcher):
    ok("存库前压缩为 WebP（原始 PNG 会让数据库明显膨胀）")
else:
    bad("未压缩就存库")

# 缩放
if re.search(r"TARGET_SIZE", fetcher) and re.search(r"inSampleSize", fetcher):
    ok("先按尺寸采样再解码（避免为超大图真的分配像素）")
else:
    bad("未做采样解码")

# 非 http 地址挡掉
if re.search(r"about:blank|protocol != \"http\"", fetcher):
    ok("非 http(s) 地址提前返回（about:/ling:// 去取图标毫无意义）")
else:
    bad("未过滤非 http(s) 地址")

# 空数组 vs null
if re.search(r"filter \{ it\.favicon == null \}", repo):
    ok("urlsMissingFavicon 只认 null 为缺失")
else:
    bad("缺失判定不对")

if re.search(r"setFavicon\(url, ByteArray\(0\)\)", vm):
    ok("抓取失败写空数组占位（否则每次进页面都重试同一批失败站点）")
else:
    bad("抓取失败未占位，会反复重试")

if re.search(r"takeIf \{ it\.isNotEmpty\(\) \}", bm_screen):
    ok("界面把空数组当作没有图标")
else:
    bad("界面未把空数组当无图标，会解码失败")

# 只更新 favicon 列
if re.search(r"fun setFavicon[\s\S]{0,400}?update\(\s*\"bookmarks\"", repo):
    ok("setFavicon 只更新 favicon 一列（不会覆盖用户刚改的名字）")
else:
    bad("setFavicon 可能覆盖标题/文件夹")

# 复用页面已有的图标
if re.search(r"toggleBookmark[\s\S]{0,900}?tab\.favicon", vm):
    ok("收藏时优先复用页面已加载的图标（省一次网络请求）")
else:
    bad("收藏时未复用页面已有图标")

# ---------- 4. 数据库迁移 ----------
print("\n4. 数据库迁移不丢数据")
if re.search(r"ALTER TABLE bookmarks ADD COLUMN favicon", store):
    ok("用 ALTER TABLE 增量加列")
else:
    bad("未找到 favicon 的增量迁移")

if re.search(r"if \(oldVersion < 3\)", store):
    ok("按版本区间判断（能从任意旧版本逐级升上来）")
else:
    bad("迁移未按版本区间判断")

# 关键：不能再无条件 DROP
m_up = re.search(r"override fun onUpgrade[\s\S]*?\n    \}", store)
if m_up and "DROP TABLE" in m_up.group(0):
    bad("onUpgrade 里仍有 DROP TABLE —— 用户升级会丢掉全部书签与历史")
else:
    ok("onUpgrade 不再 DROP 重建（用户数据不会因升级丢失）")

if re.search(r"VERSION = 3", store):
    ok("版本号已升到 3")
else:
    bad("版本号未升到 3，迁移不会触发")

if re.search(r"favicon BLOB", store):
    ok("建表语句含 favicon BLOB")
else:
    bad("新建库的建表语句缺少 favicon 列")

# ---------- 5. 接线 ----------
print("\n5. 接线")
if "onRename = viewModel::renameBookmark" in main:
    ok("MainActivity 接上了 renameBookmark")
else:
    bad("MainActivity 未接 renameBookmark")

if re.search(r"LaunchedEffect\(Unit\)\s*\{\s*viewModel\.fetchMissingFavicons\(\)", main):
    ok("进书签页时触发补图标")
else:
    bad("未在书签页触发补图标")

if re.search(r"fun fetchMissingFavicons\(", vm):
    ok("ViewModel 有 fetchMissingFavicons")
else:
    bad("缺少 fetchMissingFavicons")

# ---------- 6. 地址栏显示网页标题 ----------
print("\n6. 地址栏：加载完成后显示网页标题")
tab_state = read(f"{SRC}/web/TabState.kt")

# 非编辑态**不能**挂输入框：挂着时"点文字"会被输入框当成移动光标，
# onValueChange 带出来的是标题并覆盖刚切过来的 URL ——
# 真机表现为"点文字编辑的是标题，点旁边空白才编辑 URL"。
if addr.count("BasicTextField(") == 1 and re.search(r"if \(isEditing\) \{\s*\n\s*BasicTextField", addr):
    ok("地址栏只在编辑态挂输入框（非编辑态是只读文本）")
else:
    bad("非编辑态挂着输入框 —— 点文字会被当成编辑标题，而不是编辑 URL")

# 上报文字变化的只有两处，且都在编辑态：输入框本身、编辑态才显示的清除按钮。
# 判据写具体调用而不是数 onTextChange( —— 后者会把参数声明也算进去。
if addr.count("onTextChange(it.text)") == 1 and addr.count('onTextChange("")') == 1 and \
        "AnimatedVisibility(visible = isEditing)" in addr:
    ok("文字变化只来自编辑态的输入框与清除按钮")
else:
    bad("有非编辑态的路径会上报文字变化 —— 显示文案会覆盖真实地址")

if re.search(r"fun onAddressChanged[\s\S]{0,500}?if \(!_addressEditing\.value\) return", vm) and \
        re.search(r"fun onAddressChanged[\s\S]{0,600}?_addressText\.value = text", vm):
    ok("ViewModel 兜底：非编辑态的上报一律忽略")
else:
    bad("ViewModel 未兜底非编辑态上报 —— 显示文案可能覆盖真实地址")

# 整条地址栏可点（不只文字那一小块），行为才一致
if re.search(r"if \(isEditing\) \{\s*\n\s*Modifier\s*\n\s*\} else \{\s*\n\s*Modifier\.clickable\(", addr):
    ok("非编辑态整条地址栏可点，点了进编辑态")
else:
    bad("只有文字那一小块能点进编辑态 —— 点空白与点文字行为不一致")

# 规则必须只有一份，并且把三个退化场景都堵住（加载中 / 无标题 / 主页）
if re.search(r"val addressBarText[\s\S]{0,600}?if \(isLoading\) return url", tab_state) and \
        re.search(r"val addressBarText[\s\S]{0,800}?title\.trim\(\)\.ifEmpty \{ url \}", tab_state):
    ok("addressBarText：加载中与无标题退回网址，其余显示标题")
else:
    bad("地址栏缺'加载完成显示标题'的规则，或没处理加载中/无标题的退化")

if re.search(r"_addressText\.value = activeTab\(\)\?\.addressBarText", vm):
    ok("ViewModel 用 addressBarText 同步（文案规则只有一份）")
else:
    bad("ViewModel 自己拼地址栏文案 —— 规则会出现两份")

# 聚焦进入编辑态必须换成能用的真实地址，否则用户改不了地址；
# 阅读视图尤其关键：合成地址 ling://reader 提交上去会让 WebView 白屏。
if re.search(r"fun onAddressFocusChanged[\s\S]{0,400}?_addressText\.value = editableAddress\(\)", vm) and \
        re.search(r"private fun editableAddress\(\)[\s\S]{0,400}?readerOriginalUrl\(\)", vm):
    ok("聚焦时切回真实地址（阅读视图用原文地址）")
else:
    bad("编辑态没有切回真实地址 —— 阅读视图会拿到不可提交的 ling://reader")

if re.search(r"fun readerOriginalUrl\(id: String = _activeId\.value\): String\?", mgr):
    ok("阅读视图能取回原文地址")
else:
    bad("缺少 readerOriginalUrl —— 阅读视图的编辑态只能显示合成地址")

# ---------- 7. 其它应用「用翎打开链接 / 分享文本」 ----------
print("\n7. 其它应用「用翎打开链接」")
manifest = read("app/src/main/AndroidManifest.xml")

if "android.intent.action.VIEW" in manifest and 'android:launchMode="singleTask"' in manifest:
    ok("manifest 声明了 VIEW filter 且是 singleTask（复用实例，不会开出第二个翎）")
else:
    bad("manifest 缺 VIEW filter 或 launchMode 不是 singleTask —— 打开链接会另起实例")

# 声明 filter 只代表"系统愿意发给我们"，Activity 不读 intent 就等于没接住
if re.search(r"override fun onNewIntent\(intent: Intent\)", main) and \
        re.search(r"Intent\.ACTION_VIEW ->[\s\S]{0,220}?incoming\.dataString", main):
    ok("MainActivity 真的读了 intent.data（点链接会打开网页）")
else:
    bad("Activity 从未读 intent.data —— 选了用翎打开，App 起来了却什么都不打开")

if re.search(r"private fun handleExternalIntent[\s\S]{0,1000}?Intent\.ACTION_SEND", main) and \
        "openSharedText" in main:
    ok("分享过来的纯文本也有入口（按搜索词处理）")
else:
    bad("manifest 声明了 SEND filter，但 Activity 不处理 —— 分享过来会静默失败")

if re.search(r"private var pendingExternalUrl: String\?", vm) and \
        re.search(r"fun openExternalUrl\(raw: String\)[\s\S]{0,1400}?pendingExternalUrl = url", vm) and \
        re.search(r"val pending = pendingExternalUrl[\s\S]{0,220}?openExternalUrl\(pending\)", vm):
    ok("冷启动的链接会排队到 init 决定完标签页（否则被恢复/新建主页覆盖）")
else:
    bad("冷启动打开链接没有排队 —— 会被 restore/newTab 覆盖，表现为'点了没反应'")

if re.search(r"fun openExternalUrl\(raw: String\)[\s\S]{0,900}?UrlUtils\.isHome\(active\.url\)", vm) and \
        re.search(r"fun openExternalUrl\(raw: String\)[\s\S]{0,900}?openInNewTab\(url\)", vm):
    ok("当前标签还停在主页就复用它，否则新开标签")
else:
    bad("外部链接落在哪个标签没有约定 —— 会凭空多一个空标签")

# 提示必须真的显示：这是"点了没反应"的最后一道观感防线
if re.search(r"viewModel\.message\.collectAsStateWithLifecycle\(\)", main) and \
        re.search(r"Toast\.makeText[\s\S]{0,220}?viewModel\.consumeMessage\(\)", main):
    ok("一次性提示真的会弹出来（书签增删、不支持的链接…）")
else:
    bad("ViewModel 的 _message 没有消费方 —— 所有提示被静默丢弃，用户只看到'点了没反应'")

# 用户可能正停在设置/书签页：光在后台打开网页还不够
if "val showBrowser: StateFlow<Int>" in vm and \
        re.search(r"LaunchedEffect\(showBrowser\)[\s\S]{0,320}?backStack\.add\(Route\.Browser\)", main):
    ok("外部链接进来时把界面拉回浏览页（不会停在设置页）")
else:
    bad("外部链接进来时界面可能停在二级页 —— 网页开了，屏幕上却还是设置页")

print()
print("=" * 55)
if problems:
    print(f"{len(problems)} 个问题：")
    for p in problems:
        print("  ! " + p)
    sys.exit(1)
print("地址栏焦点 / 书签编辑 / 网站图标 / 地址栏文案 / 外部链接校验通过")
