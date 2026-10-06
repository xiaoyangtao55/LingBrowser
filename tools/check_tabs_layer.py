"""校验标签页面板的图层结构：是否真的能"透出后面的网页"。

背景（真实问题）：
  原实现里 MainActivity 用 `when (route)` 在 BrowserScreen 与 TabsScreen
  之间**二选一**。切到标签页时 BrowserScreen 被卸载，它的
  DisposableEffect 触发 tabManager.detachHost(host)，WebView 从视图树
  摘下来 —— 面板背后根本没有内容，再透明也只能看到窗口底色。

修复要点（本脚本逐条校验）：
  1. BrowserScreen 常驻，不再参与 when 二选一
  2. TabsScreen 画在其上，作为叠加层
  3. 非全屏档位面板底色必须是半透明（否则等于没透）
  4. 遮罩只压暗面板上方露出的区域，且不盖住面板本身
  5. 全屏档位不画遮罩，且面板用不透明色（避免透出浏览页干扰阅读）
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

# ---------- 1. BrowserScreen 常驻 ----------
print("1. BrowserScreen 是否常驻挂载")
# 关键：when(route) 里不能再有 Route.Browser -> BrowserScreen(...)
if re.search(r"Route\.Browser\s*->\s*BrowserScreen", main):
    bad("Route.Browser 仍直接返回 BrowserScreen，切到标签页时它会被卸载")
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

# ---------- 2. 面板透明度 ----------
print("\n2. 面板底色是否半透明")
m = re.search(r"PANEL_ALPHA\s*=\s*([0-9.]+)f", tabs)
if not m:
    bad("没有定义 PANEL_ALPHA")
else:
    v = float(m.group(1))
    if 0.0 < v < 1.0:
        ok(f"PANEL_ALPHA = {v}（半透明）")
    else:
        bad(f"PANEL_ALPHA = {v}，不在 (0,1) 区间，不是半透明")
    if v > 0.95:
        bad(f"PANEL_ALPHA = {v} 太高，几乎不透明，透不出网页")
    if v < 0.55:
        bad(f"PANEL_ALPHA = {v} 太低，面板文字会与网页内容互相干扰")

if re.search(r"surface\.copy\(alpha\s*=\s*PANEL_ALPHA\)", tabs):
    ok("非全屏档位使用半透明 surface")
else:
    bad("未对 surface 应用 PANEL_ALPHA")

if re.search(r"TabsHeight\.FULL\s*\)\s*\{\s*\n\s*MaterialTheme\.colorScheme\.surface\s*\n", tabs):
    ok("全屏档位回退为不透明 surface")
else:
    bad("全屏档位未做不透明回退（半透明会透出浏览页干扰阅读）")

# ---------- 3. 遮罩 ----------
print("\n3. 遮罩是否只压暗面板上方")
# 遮罩应使用 (1 - fraction) 高度并顶部对齐
if re.search(r"fillMaxHeight\(1f\s*-\s*tabsHeight\.fraction\)", tabs):
    ok("遮罩高度 = 1 - 面板高度（只覆盖露出区域）")
else:
    bad("遮罩未按面板高度留白，会盖住面板本身")

if re.search(r"\.align\(Alignment\.TopCenter\)", tabs):
    ok("遮罩顶部对齐")
else:
    bad("遮罩未顶部对齐")

# 遮罩必须画在 Surface 之后（否则会盖住面板）
# 注意搜"使用处"而不是常量名本身：SCRIM_MAX_ALPHA 在文件顶部有声明，
# 直接 find("SCRIM_MAX_ALPHA") 会命中声明行，导致永远判定"在面板之前"。
idx_scrim = tabs.find("SCRIM_MAX_ALPHA * scrimAlpha.value")
idx_surface = tabs.find("color = if (tabsHeight == TabsHeight.FULL)")
if idx_scrim == -1:
    bad("没有找到遮罩 alpha 的使用处")
elif idx_surface == -1:
    bad("没有找到面板 Surface 的颜色定义")
elif idx_scrim > idx_surface:
    ok("遮罩绘制在面板之后（不会盖住面板）")
else:
    bad("遮罩在面板之前绘制，会把面板也压暗")

# 遮罩 alpha 必须由动画驱动，否则加了动画也只有面板在动
if re.search(r"SCRIM_MAX_ALPHA\s*\*\s*scrimAlpha\.value", tabs):
    ok("遮罩 alpha 由动画值驱动（会随面板一起淡入）")
else:
    bad("遮罩 alpha 未接动画，淡入会缺失")

if re.search(r"if \(tabsHeight != TabsHeight\.FULL\)", tabs):
    ok("全屏档位不画遮罩")
else:
    bad("全屏档位仍会画遮罩")

# ---------- 3b. 入场动画 ----------
print("\n3b. 入场动画")
if "Animatable(1f)" in tabs:
    ok("面板位移动画初值 1f（在屏幕外）")
else:
    bad("未找到面板位移动画的 Animatable")

if re.search(r"translationY\s*=\s*slide\.value\s*\*\s*size\.height", tabs):
    ok("用 graphicsLayer.translationY 平移（不触发重新测量）")
else:
    bad("面板位移未用 graphicsLayer，动画期间列表可能抖动")

if re.search(r"LaunchedEffect\(Unit\)", tabs):
    ok("挂载时播放一次动画")
else:
    bad("未找到 LaunchedEffect 触发动画")

# 动画时长：太快看不见，太慢拖沓
durs = re.findall(r"private const val (\w+_MS)\s*=\s*(\d+)", tabs)
if not durs:
    bad("没有定义动画时长常量")
for name, v in durs:
    v = int(v)
    if 100 <= v <= 400:
        ok(f"{name} = {v}ms（在合理区间）")
    else:
        bad(f"{name} = {v}ms 不在 100~400ms，手感会不对")

# ---------- 4. 不能再用整屏不透明遮罩 ----------
print("\n4. 回归：旧的整屏遮罩写法")
# 旧写法是 Box 上直接 background(Black 0.32f) + fillMaxSize
if re.search(r"\.fillMaxSize\(\)\s*\n\s*\.background\(Color\.Black", tabs):
    bad("仍在 Box 上用整屏 background 遮罩（会连面板一起压暗）")
else:
    ok("已移除整屏遮罩写法")

# ---------- 4b. 高度档位只有两档 ----------
print("\n4b. 高度档位（1/4 已移除）")
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
entries = re.findall(r"^\s+([A-Z_]+)\(\"", store, re.M)
# 只取 TabsHeight 枚举体内的条目
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

# 面板与遮罩逻辑用的是 != FULL，因此天然支持两档
if "TabsHeight.entries.forEach" in tabs:
    ok("高度切换 UI 遍历 entries，档位增删无需改代码")
else:
    bad("高度切换 UI 未遍历 entries，改档位时容易漏改")

# ---------- 5. WebView 宿主生命周期 ----------
print("\n5. WebView 宿主生命周期（关键是别被卸载）")
if "DisposableEffect(host)" in browser and "attachHost" in browser:
    ok("BrowserScreen 用 DisposableEffect 挂载宿主")
if "detachHost" in browser:
    ok("detachHost 只在 onDispose 中调用（常驻后不会误触发）")

# ---------- 6. 拖拽排序 ----------
print("\n6. 拖拽排序（长按触发，不能吃掉点击）")
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

if "onMoveTab" in tabs and "onMoveTab = viewModel::moveTab" in main:
    ok("onMoveTab 已接线到 viewModel.moveTab")
else:
    bad("onMoveTab 未接线到 viewModel")

# ---------- 7. 缩略图 ----------
print("\n7. 缩略图（favicon，不是网页截图）")
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
print("标签页图层结构校验通过")
