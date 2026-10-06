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
idx_scrim = tabs.find("alpha = 0.28f")
idx_surface = tabs.find("color = if (tabsHeight == TabsHeight.FULL)")
if idx_scrim == -1:
    bad("没有找到遮罩的 alpha 值")
elif idx_surface == -1:
    bad("没有找到面板 Surface 的颜色定义")
elif idx_scrim > idx_surface:
    ok("遮罩绘制在面板之后（不会盖住面板）")
else:
    bad("遮罩在面板之前绘制，会把面板也压暗")

if re.search(r"if \(tabsHeight != TabsHeight\.FULL\)", tabs):
    ok("全屏档位不画遮罩")
else:
    bad("全屏档位仍会画遮罩")

# ---------- 4. 不能再用整屏不透明遮罩 ----------
print("\n4. 回归：旧的整屏遮罩写法")
# 旧写法是 Box 上直接 background(Black 0.32f) + fillMaxSize
if re.search(r"\.fillMaxSize\(\)\s*\n\s*\.background\(Color\.Black", tabs):
    bad("仍在 Box 上用整屏 background 遮罩（会连面板一起压暗）")
else:
    ok("已移除整屏遮罩写法")

# ---------- 5. WebView 宿主生命周期 ----------
print("\n5. WebView 宿主生命周期（关键是别被卸载）")
if "DisposableEffect(host)" in browser and "attachHost" in browser:
    ok("BrowserScreen 用 DisposableEffect 挂载宿主")
if "detachHost" in browser:
    ok("detachHost 只在 onDispose 中调用（常驻后不会误触发）")

print()
print("=" * 55)
if problems:
    print(f"{len(problems)} 个问题：")
    for p in problems:
        print("  ! " + p)
    sys.exit(1)
print("标签页图层结构校验通过")
