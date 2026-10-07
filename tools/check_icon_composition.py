"""量化分析：描边羽毛与外环的几何关系是否协调。

从 ASCII 预览看构图，这里用数字确认羽毛（描边）有没有压到外环、
视觉占比是否合理，避免凭感觉判断。
"""
import os
import sys
import math

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from svg_preview import parse_svg, parse_path, flatten, XML

view, grad, items = parse_svg(XML)
circles = [i for i in items if i["type"] == "circle"]
paths = [i for i in items if i["type"] == "path"]

ring = next(c for c in circles if c["stroke"])
feather = paths

rcx, rcy, rr, rsw = ring["cx"], ring["cy"], ring["r"], ring["sw"]
outer = rr + rsw / 2
inner = rr - rsw / 2

print("=== 外环参数 ===")
print(f"  圆心 ({rcx}, {rcy})  半径 {rr}  线宽 {rsw}")
print(f"  环内沿半径 {inner:.1f}   环外沿半径 {outer:.1f}")
print()

print("=== 羽毛各点到环心的距离（含描边半宽）===")
pts = []
sw = max(p["sw"] for p in feather)
for p in feather:
    for sub, _ in flatten(parse_path(p["d"]), steps=48,
                          matrix=p.get("matrix")):
        pts.extend(sub)

inside = outside = 0
maxd = 0
for x, y in pts:
    d = math.hypot(x - rcx, y - rcy) + sw / 2
    maxd = max(maxd, d)
    if d <= outer:
        inside += 1
    else:
        outside += 1

total = len(pts)
print(f"  采样点 {total} 个，stroke-width {sw}")
print(f"  描边外沿在环外沿内: {inside}  ({inside/total*100:.1f}%)")
print(f"  描边外沿超出环外沿: {outside} ({outside/total*100:.1f}%)")
print(f"  描边外沿离环心最远 : {maxd:.1f}  (环外沿 {outer:.1f}，内沿 {inner:.1f})")
print()

if maxd > outer:
    over = maxd - outer
    print(f"  ! 羽毛描边有 {over:.1f} 单位超出外环 ({over/outer*100:.1f}% 的环半径)")
    print(f"    超出部分会穿出环，破坏「浏览器」的视觉隐喻")
    sys.exit(1)
elif maxd > inner + 0.5:
    print("  ! 羽毛描边压到了环内沿（间隙不足），低分辨率下会粘连")
    sys.exit(1)
else:
    print("  OK 羽毛描边完全落在环内沿以内")

# 羽毛相对整圆的占比
print()
print("=== 视觉占比 ===")
fx = [p[0] for p in pts]; fy = [p[1] for p in pts]
print(f"  羽毛包围盒 x[{min(fx):.0f},{max(fx):.0f}] y[{min(fy):.0f},{max(fy):.0f}]")
print(f"  整圆直径 256，外环外沿直径 {outer*2:.0f}")
print(f"  羽毛宽度 {max(fx)-min(fx):.0f}，占环直径 {(max(fx)-min(fx))/(outer*2)*100:.0f}%")
