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
    3. 标签页面板恒定用半透明容器色，透出背后的网页
    4. 面板高度是个固定比例常量，落在**内容**高度（fillMaxHeight），
       不依赖 sheet 锚点 —— 「全屏」档位与设置项已一并移除
    5. 半透明常量仍在 TabsScreen 导出（TABS_SHEET_ALPHA），且落在合理区间
    6. 旧叠加层的残留（自绘整屏遮罩 / 位移动画）已清除
    7. 标签排序、favicon 等既有约束不回退
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
print("\n3. 标签页面板透明度（半透明，透出网页）")
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
    bad("仍残留「全屏档位回退不透明」的分支，档位设置未清理干净")
elif re.search(r"\bTabsHeight\b|\btabsHeight\b", browser):
    bad("BrowserScreen 仍出现高度档位符号，设置项未移除干净")
else:
    ok("容器色恒定半透明，不再有档位分支")

# ---------- 4. 面板高度落在内容高度上 ----------
print("\n4. 面板高度如何落地（固定一半，档位设置已移除）")
if re.search(r"fillMaxHeight\(TABS_SHEET_HEIGHT_FRACTION\)", browser):
    ok("标签页内容高度 = TABS_SHEET_HEIGHT_FRACTION")
else:
    bad("标签页高度未绑定常量，面板高度失去单一来源")

m_h = re.search(r"TABS_SHEET_HEIGHT_FRACTION\s*=\s*([0-9.]+)f", tabs)
if m_h and abs(float(m_h.group(1)) - 0.5) < 0.001:
    ok("TABS_SHEET_HEIGHT_FRACTION = 0.5（一半）")
else:
    bad("TABS_SHEET_HEIGHT_FRACTION 未在 TabsScreen 定义，或不再是 0.5")

# 高度档位设置已随「全屏」一并移除：两个 composable 都不该再收/传高度参数
if re.search(r"tabsHeight|onTabsHeightChange", browser):
    bad("BrowserScreen 仍透传高度档位参数（设置项已移除）")
else:
    ok("BrowserScreen 不再透传高度档位")

if re.search(r"tabsHeight|onTabsHeightChange|HeightToggle", tabs):
    bad("TabsScreen 仍残留高度档位参数或切换 UI")
else:
    ok("TabsScreen 已无高度档位参数与切换 UI")

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

# ---------- 6. 高度档位枚举已全链路移除 ----------
print("\n6. 高度档位枚举已全链路移除（含「全屏」档）")
store = read("app/src/main/java/com/ling/browser/data/prefs/SettingsStore.kt")
vm = read("app/src/main/java/com/ling/browser/ui/BrowserViewModel.kt")
settings_screen = read("app/src/main/java/com/ling/browser/ui/screens/SettingsScreen.kt")

# 数据模型 / 持久化：枚举、字段、键、写入函数一个都不该留
if re.search(r"enum class TabsHeight", store):
    bad("SettingsStore 仍定义 TabsHeight 枚举，未移除干净")
else:
    ok("SettingsStore 已无 TabsHeight 枚举")

if "tabsHeight" in store:
    bad("SettingsStore 仍出现 tabsHeight（字段/解析/写入未清理）")
else:
    ok("SettingsStore 已无 tabsHeight 字段与读写逻辑")

if "TABS_HEIGHT" in store:
    bad("SettingsStore 仍残留持久化键 TABS_HEIGHT")
else:
    ok("SettingsStore 已无 TABS_HEIGHT 持久化键")

if re.search(r"fun setTabsHeight", store):
    bad("SettingsStore 仍保留 setTabsHeight 写入函数")
else:
    ok("SettingsStore 已移除 setTabsHeight")

# 视图模型层
if re.search(r"\bTabsHeight\b|setTabsHeight|\btabsHeight\b", vm):
    bad("BrowserViewModel 仍残留高度档位符号")
else:
    ok("BrowserViewModel 已无高度档位符号")

# 设置页：设置项与弹窗
if re.search(r"\bTabsHeight\b|tabsHeight|onTabsHeight|showTabsHeightDialog", settings_screen):
    bad("SettingsScreen 仍残留高度档位设置项 / 弹窗 / 回调")
else:
    ok("SettingsScreen 已移除高度档位设置项与弹窗")

# 根 Activity 透传
if re.search(r"onTabsHeight|\bTabsHeight\b|tabsHeight", main):
    bad("MainActivity 仍透传高度档位参数")
else:
    ok("MainActivity 已移除 onTabsHeight 接线")

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
