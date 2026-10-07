"""量化分析：纯色圆底 + 描边羽毛的构图是否协调。

启动图标现在与主页 logo 同款：**纯色圆底（primaryContainer）+ 描边羽毛
（onPrimaryContainer），没有外环**。这里用数字确认：底色圆确实是纯色、
没有残留外环、羽毛没有越出圆底、占比也在合理区间 —— 避免"看着差不多"就提交。
"""
import os
import sys
import math

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from svg_preview import parse_svg, parse_path, flatten, XML

view, grad, items = parse_svg(XML)
circles = [i for i in items if i["type"] == "circle"]
paths = [i for i in items if i["type"] == "path"]

problems = []


def bad(m):
    problems.append(m)
    print("  FAIL " + m)


def ok(m):
    print("  OK   " + m)


print("=== 造型：纯色圆底 + 描边羽毛（无外环）===")

bg = [c for c in circles if c["fill"] and not c["stroke"]]
rings = [c for c in circles if c["stroke"]]

if len(bg) == 1:
    # 必须是 #RRGGBB 纯色：写成 url(#g) 之类（渐变/引用）即使没定义渐变，
    # 也会让"纯色圆底"这个设计前提失效，必须挡住。
    fill = bg[0]["fill"]
    if fill.startswith("#"):
        ok(f"底色是 1 个纯色圆 {fill}")
    else:
        bad(f"底色不是纯色：{fill}（应为 #RRGGBB，不使用渐变/引用）")
else:
    bad(f"底色圆数量异常：{len(bg)} 个（应为 1）")
if rings:
    bad(f"仍有 {len(rings)} 个描边圆 —— 外环应已去掉")
else:
    ok("没有外环（无描边圆）")
if grad:
    bad(f"背景仍在使用渐变：{grad}")
else:
    ok("背景为纯色（无渐变）")

if not bg:
    print()
    print("=" * 55)
    print("没有底色圆，无法继续")
    sys.exit(1)

bgc = bg[0]
feather = paths

print()
print("=== 羽毛与底色圆的关系 ===")
sw = max(p["sw"] for p in feather)
pts = []
for p in feather:
    for sub, _ in flatten(parse_path(p["d"]), steps=48, matrix=p.get("matrix")):
        pts.extend(sub)

rcx, rcy, rr = bgc["cx"], bgc["cy"], bgc["r"]
maxd = max(math.hypot(x - rcx, y - rcy) for x, y in pts) + sw / 2

print(f"  羽毛 {len(feather)} 条描边路径，采样点 {len(pts)} 个，stroke-width {sw}")
print(f"  底色圆 圆心({rcx:.0f},{rcy:.0f}) r={rr:.0f}")
print(f"  羽毛描边外沿离圆心最远 {maxd:.1f}  (占圆半径 {maxd / rr * 100:.0f}%)")

if maxd > rr:
    bad(f"羽毛描边越出底色圆 {maxd - rr:.1f} 单位")
else:
    ok("羽毛描边完全在底色圆内")

ratio = maxd / rr
if ratio < 0.5:
    bad(f"羽毛只占圆半径 {ratio * 100:.0f}%，偏小（至少 50%）")
elif ratio > 0.95:
    bad(f"羽毛占圆半径 {ratio * 100:.0f}%，太贴边（应 <= 95%）")
else:
    ok(f"羽毛占比 {ratio * 100:.0f}%，落在 50%~95% 之间")

print()
print("=" * 55)
if problems:
    print(f"{len(problems)} 个问题：")
    for p in problems:
        print("  ! " + p)
    sys.exit(1)
print("图标构图校验通过")
