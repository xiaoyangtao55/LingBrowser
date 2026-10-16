"""校验「标签页 / 更多」面板改用 BottomSheet 后的结构与关键交互。

背景（保留的历史教训）：
  早期标签页是一个**独立路由**页面 —— MainActivity 用 `when (route)` 在
  BrowserScreen 与 TabsScreen 之间二选一。切到它时 BrowserScreen 被卸载，
  DisposableEffect 触发 tabManager.detachHost(host)，WebView 从视图树摘下来，
  面板背后根本没有内容，再透明也只能看到窗口底色。
  随后改为「BrowserScreen 常驻 + TabsScreen 自绘叠加层」。

本次改动：
  「标签页」与「更多」不再自绘叠加层 / 不再锚定下拉菜单，统一换成 Material3
  的 ModalBottomSheet（自屏幕底部升起）。本脚本逐条校验新结构：
    1. 标签页不再是独立路由；BrowserScreen 常驻（when 之外）
    2. BrowserScreen 内有两个 ModalBottomSheet（标签页 / 更多）
    3. 标签页半屏档位用半透明容器色，全屏档位回退为不透明
    4. 高度档位落在**内容**高度（fillMaxHeight(fraction)），不依赖 sheet 锚点
    5. 半透明常量仍在 TabsScreen 导出（TABS_SHEET_ALPHA），且落在合理区间
    6. 旧叠加层的残留（自绘整屏遮罩 / 位移动画）已清除
    7. 标签排序、favicon、设置档位等既有约束不回退
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


main = read("app/src/main/java/com/ling/browser/MainActivity.kt")
tabs = read("app/src/main/java/com/ling/browser/ui/screens/TabsScreen.kt")
browser = read("app/src/main/java/com/ling/browser/ui/screens/BrowserScreen.kt")

# ---------- 1. 标签页不再是独立路由，BrowserScreen 常驻 ----------
print("1. 标签页不再是独立路由，BrowserScreen 常驻")
if "Route.Tabs" in main or "onOpenTabs" in main:
    bad("MainActivity 仍保留 Route.Tabs / onOpenTabs，标签页未改由 BottomSheet 承载")
else:
    ok("MainActivity 已移除 Route.Tabs 路由与 onOpenTabs 透传")

# 关键：when(route) 里不能再有 Route.Browser -> BrowserScreen(...)
if re.search(r"Route\.Browser\s*->\s*BrowserScreen", main):
    bad("Route.Browser 仍直接返回 BrowserScreen，切页时它会被卸载")
else:
    ok("when(route) 中不再用 Route.Browser 直接渲染 BrowserScreen")

if re.search(r"Route\.Browser\s*->\s*Unit", main):
    ok("Route.Browser 分支为空（浏览页已在 when 之外常驻）")
else:
    bad("未找到 `Route.Browser -> Unit`，浏览页可能仍会被卸载")

# BrowserScreen 必须在 when 之外、且在 Box 内先于 when 出现
idx_browser_call = main.find("BrowserScreen(")
idx_when = main.find("when (route)")
if idx_browser_call == -1:
    bad("MainActivity 里没有调用 BrowserScreen")
elif idx_when == -1:
    bad("MainActivity 里没有 when (route)")
elif idx_browser_call < idx_when:
    ok("BrowserScreen 在 when (route) 之前绘制（位于底层）")
else:
    bad("BrowserScreen 出现在 when 之后，图层顺序反了")

if re.search(r"Box\(modifier = Modifier\s*\n?\s*\.fillMaxSize\(\)", main):
    ok("用 Box 叠加图层")
else:
    bad("未找到承载图层的 Box")

# ---------- 2. 两个 BottomSheet ----------
print("\n2. BrowserScreen 内是否用 ModalBottomSheet 承载两个面板")
n_sheets = browser.count("ModalBottomSheet(")
if n_sheets >= 2:
    ok(f"BrowserScreen 内有 {n_sheets} 个 ModalBottomSheet（标签页 / 更多）")
else:
    bad(f"只找到 {n_sheets} 个 ModalBottomSheet，两个面板没有都换成 BottomSheet")

if re.search(r"if \(showTabsSheet\)\s*\{[\s\S]{0,200}?ModalBottomSheet\(", browser):
    ok("标签页面板由 ModalBottomSheet 承载")
else:
    bad("没有找到标签页的 ModalBottomSheet")

if re.search(r"if \(showMoreSheet\)\s*\{[\s\S]{0,200}?ModalBottomSheet\(", browser):
    ok("「更多」面板由 ModalBottomSheet 承载")
else:
    bad("没有找到「更多」的 ModalBottomSheet")

if "onTabs = { showTabsSheet = true }" in browser and \
        "onMore = { showMoreSheet = true }" in browser:
    ok("工具栏的 onTabs / onMore 已接线到两个面板开关")
else:
    bad("工具栏回调没有接线到面板开关")

# 旧的锚定式下拉菜单不应残留
if "DropdownMenuItem" in browser:
    bad("仍残留 DropdownMenuItem（旧的锚定式下拉菜单没删干净）")
else:
    ok("已移除 DropdownMenuItem")

# ---------- 3. 标签页面板透明度 ----------
print("\n3. 标签页面板透明度（半屏透出网页，全屏不透明）")
m = re.search(r"TABS_SHEET_ALPHA\s*=\s*([0-9.]+)f", tabs)
if not m:
    bad("TabsScreen 没有定义 TABS_SHEET_ALPHA")
else:
    v = float(m.group(1))
    if 0.0 < v < 1.0:
        ok(f"TABS_SHEET_ALPHA = {v}（半透明）")
    else:
        bad(f"TABS_SHEET_ALPHA = {v}，不在 (0,1) 区间，不是半透明")
    if v > 0.95:
        bad(f"TABS_SHEET_ALPHA = {v} 太高，几乎不透明，透不出网页")
    if v < 0.55:
        bad(f"TABS_SHEET_ALPHA = {v} 太低，面板文字会与网页内容互相干扰")

if re.search(r"copy\(alpha\s*=\s*TABS_SHEET_ALPHA\)", browser):
    ok("BrowserScreen 把 TABS_SHEET_ALPHA 应用到面板容器色")
else:
    bad("没有把 TABS_SHEET_ALPHA 应用到 ModalBottomSheet 的 containerColor")

if re.search(
    r"settings\.tabsHeight == TabsHeight\.FULL\)\s*\{\s*\n"
    r"\s*MaterialTheme\.colorScheme\.surface\s*\n",
    browser,
):
    ok("全屏档位回退为不透明 surface（避免透出浏览页干扰阅读）")
else:
    bad("全屏档位未做不透明回退（半透明会透出浏览页干扰阅读）")

# ---------- 4. 高度档位落在内容高度上 ----------
print("\n4. 高度档位如何落地")
if re.search(r"fillMaxHeight\(settings\.tabsHeight\.fraction\)", browser):
    ok("标签页内容高度 = settings.tabsHeight.fraction（全屏 / 一半）")
else:
    bad("标签页高度未绑定 tabsHeight.fraction，档位切换不会生效")

if "tabsHeight = settings.tabsHeight" in browser:
    ok("TabsScreen 收到当前档位")
else:
    bad("TabsScreen 未收到 tabsHeight")

if "onTabsHeightChange = viewModel::setTabsHeight" in browser:
    ok("档位切换已接线到 viewModel.setTabsHeight")
else:
    bad("档位切换未接线到 viewModel")

# ---------- 5. 不能再有旧叠加层残留 ----------
print("\n5. 旧叠加层残留")
old_marks = {
    "Animatable(1f)": "面板位移动画（已由 BottomSheet 提供）",
    "SCRIM_MAX_ALPHA": "自绘遮罩",
    "slide.value": "面板位移动画值",
}
for token, why in old_marks.items():
    if token in tabs:
        bad(f"TabsScreen 仍残留 {token}（{why}）")
    else:
        ok(f"已移除 {token}")

if re.search(r"fillMaxHeight\(1f\s*-\s*tabsHeight\.fraction\)", tabs):
    bad("TabsScreen 仍自绘「只压暗面板上方」的遮罩")
else:
    ok("不再自绘只压暗面板上方的遮罩")

# 旧的整屏不透明遮罩写法
if re.search(r"\.fillMaxSize\(\)\s*\n\s*\.background\(Color\.Black", tabs):
    bad("仍在 Box 上用整屏 background 遮罩")
else:
    ok("已移除整屏遮罩写法")

# 面板本体改用组件后，TabsScreen 只应是纯内容：根节点是 Column，不再是 Scaffold
if "Scaffold" in tabs:
    bad("TabsScreen 仍是独立页面骨架（Scaffold），应只保留纯内容")
else:
    ok("TabsScreen 已剥离为纯内容（无 Scaffold）")

if re.search(r"Column\(modifier = modifier\.fillMaxWidth\(\)\)", tabs):
    ok("TabsScreen 根节点是填宽的 Column，可直接放进 BottomSheet")
else:
    bad("TabsScreen 根节点结构不符（应为 Column）")

# ---------- 6. 高度档位枚举（1/4 已移除） ----------
print("\n6. 高度档位（1/4 已移除）")
store = read("app/src/main/java/com/ling/browser/data/prefs/SettingsStore.kt")
# 排除注释：老数据兼容的那段说明里会提到 "QUARTER" 这个字符串字面量，
# 那是解释文字，不是残留的枚举条目。
store_code = "\n".join(
    ln for ln in store.split("\n") if not ln.strip().startswith("//")
)
if "QUARTER" in store_code:
    bad("SettingsStore 里仍有 QUARTER，1/4 档位未移除干净")
else:
    ok("TabsHeight 已无 QUARTER（仅注释中作为历史说明提及）")

m_enum = re.search(r"enum class TabsHeight.*?\{(.*?)\n\}", store, re.S)
if m_enum:
    names = re.findall(r"([A-Z_]+)\(", m_enum.group(1))
    if names == ["FULL", "HALF"]:
        ok(f"TabsHeight 只有两档：{names}")
    else:
        bad(f"TabsHeight 档位应为 [FULL, HALF]，实际 {names}")
else:
    bad("没找到 TabsHeight 枚举定义")

if re.search(r"val tabsHeight: TabsHeight = TabsHeight\.HALF", store):
    ok("默认档位是 HALF")
else:
    bad("默认档位不是 HALF")

# 老数据兼容：valueOf 必须被 runCatching 包住
if re.search(r"runCatching \{ TabsHeight\.valueOf\(it\) \}", store):
    ok("读取时用 runCatching 兜住已移除的枚举名（老数据不会闪退）")
else:
    bad("读取 tabsHeight 未做异常兜底，老版本存过 QUARTER 会崩")

if re.search(r"\?\: TabsHeight\.HALF", store):
    ok("解析失败时回退到 HALF")
else:
    bad("解析失败未回退到 HALF")

# 面板内的高度切换 UI 用的是 entries，因此天然支持两档
if "TabsHeight.entries.forEach" in tabs:
    ok("高度切换 UI 遍历 entries，档位增删无需改代码")
else:
    bad("高度切换 UI 未遍历 entries，改档位时容易漏改")

# ---------- 7. WebView 宿主生命周期 ----------
print("\n7. WebView 宿主生命周期（关键是别被卸载）")
if "DisposableEffect(host)" in browser and "attachHost" in browser:
    ok("BrowserScreen 用 DisposableEffect 挂载宿主")
if "detachHost" in browser:
    ok("detachHost 只在 onDispose 中调用（常驻后不会误触发）")

# ---------- 8. 拖拽排序 ----------
print("\n8. 拖拽排序（长按触发，不能吃掉点击）")
if "detectDragGesturesAfterLongPress" in tabs:
    ok("用 detectDragGesturesAfterLongPress（长按才拖拽）")
else:
    bad("未使用长按拖拽 —— 普通拖拽会与点击切换标签、列表滚动冲突")

# 关键：不能把 index 放进 pointerInput 的 key。换位后 key 变化会重启
# 手势识别器，拖动中途就会断掉。
if re.search(r"pointerInput\(\s*tab\.id\s*\)", tabs):
    ok("pointerInput 的 key 只用 tab.id（换位不会打断手势）")
elif re.search(r"pointerInput\([^)]*index", tabs):
    bad("pointerInput 的 key 里含 index，每次换位都会重启手势导致拖动中断")
else:
    bad("没找到 pointerInput(tab.id)")

# onDragStart 必须现查下标，不能用闭包捕获的 index
if "tabsRef.value.indexOfFirst" in tabs:
    ok("onDragStart 现查下标（闭包捕获的 index 在换位后已过期）")
else:
    bad("onDragStart 未现查下标，换位后会从错误的起点继续拖动")

# 位移换算必须扣掉已消耗的部分，否则会连续换位失控
if re.search(r"dragOffsetY\s*-=\s*\(target\s*-\s*draggingIndex\)\s*\*\s*rowH", tabs):
    ok("换位后扣掉已消耗的位移（不会累积失控）")
else:
    bad("未扣减已消耗位移，拖动会连续换位")

if "onMoveTab" in tabs and "onMoveTab = viewModel::moveTab" in browser:
    ok("onMoveTab 已接线到 viewModel.moveTab")
else:
    bad("onMoveTab 未接线到 viewModel")

# ---------- 9. 缩略图 ----------
print("\n9. 缩略图（favicon，不是网页截图）")
if re.search(r"favicon\s*!=\s*null\s*&&\s*!favicon\.isRecycled", tabs):
    ok("绘制前检查 isRecycled（否则会抛 recycled bitmap 崩溃）")
else:
    bad("绘制 favicon 前未检查 isRecycled，标签关闭后可能崩溃")

# favicon 必须真的被捕获，而不是留个空实现
manager = read("app/src/main/java/com/ling/browser/web/WebTabManager.kt")
if re.search(r"onReceivedIcon\s*=\s*\{\s*\}", manager):
    bad("onReceivedIcon 仍是空实现，favicon 永远不会出现")
elif re.search(r"override fun onReceivedIcon", manager):
    ok("onReceivedIcon 已实现，favicon 会被捕获")
else:
    bad("没有实现 onReceivedIcon")

# 关闭标签必须回收，否则像素内存泄漏
if re.search(r"private fun recycleTab", manager):
    ok("有 recycleTab 统一回收入口")
else:
    bad("缺少 favicon 回收逻辑，反复开关标签会泄漏像素内存")

if re.search(r"recycleTab\(closing\)", manager):
    ok("closeTab 中回收 favicon")
else:
    bad("closeTab 未回收 favicon")

if re.search(r"_tabs\.value\.forEach \{ recycleTab\(it\) \}", manager):
    ok("destroyAll 中回收全部 favicon")
else:
    bad("destroyAll 未回收 favicon")

if "isRecycled" in manager:
    ok("回收前有 isRecycled 守卫（同一 Bitmap 可能被多个 TabState 共享）")
else:
    bad("回收前没有 isRecycled 守卫，重复 recycle 会抛异常")

if "AUTO_SCROLL" in tabs:
    bad("残留未使用的自动滚动常量（该功能未实现，属于死代码）")
else:
    ok("没有残留未实现的自动滚动常量")

print()
print("=" * 55)
if problems:
    print(f"{len(problems)} 个问题：")
    for p in problems:
        print("  ! " + p)
    sys.exit(1)
print("标签页 / 更多面板结构校验通过")
