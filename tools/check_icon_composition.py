"""量化分析：羽毛与外环的几何关系是否协调。

从 ASCII 预览看，羽毛右侧的弧线明显超出了外环。
这里用数字确认，避免凭感觉判断。
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
dot = next(c for c in circles if c["fill"].startswith("#") and not c["stroke"])
feather = paths[0]

rcx, rcy, rr, rsw = ring["cx"], ring["cy"], ring["r"], ring["sw"]
outer = rr + rsw / 2
inner = rr - rsw / 2

print("=== 外环参数 ===")
print(f"  圆心 ({rcx}, {rcy})  半径 {rr}  线宽 {rsw}")
print(f"  环内沿半径 {inner:.1f}   环外沿半径 {outer:.1f}")
print()

print("=== 羽毛各点到环心的距离 ===")
pts = []
# 注意：必须传入 matrix，否则 <g transform> 不会生效
for sub, _ in flatten(parse_path(feather["d"]), steps=48,
                      matrix=feather.get("matrix")):
    pts.extend(sub)

inside = outside = 0
maxd = 0
for x, y in pts:
    d = math.hypot(x - rcx, y - rcy)
    maxd = max(maxd, d)
    if d <= outer:
        inside += 1
    else:
        outside += 1

total = len(pts)
print(f"  采样点 {total} 个")
print(f"  在环内/环上: {inside}  ({inside/total*100:.1f}%)")
print(f"  超出环外   : {outside} ({outside/total*100:.1f}%)")
print(f"  离环心最远 : {maxd:.1f}  (环外沿 {outer:.1f})")
print()

if outside:
    over = maxd - outer
    print(f"  ! 羽毛有 {over:.1f} 单位超出外环 ({over/outer*100:.1f}% 的环半径)")
    print(f"    超出部分会穿出环，破坏「浏览器」的视觉隐喻")
else:
    print("  OK 羽毛完全在外环内")
print()

# 高光点是否压住羽毛
dcx, dcy, dr = dot["cx"], dot["cy"], dot["r"]
mind = min(math.hypot(x - dcx, y - dcy) for x, y in pts)
print("=== 高光点 ===")
print(f"  圆心 ({dcx}, {dcy})  半径 {dr}")
print(f"  离羽毛最近距离 {mind:.1f}")
print("  " + ("OK 不与羽毛重叠" if mind > dr else "高光点压在羽毛上"))

# 羽毛相对整圆的占比
print()
print("=== 视觉占比 ===")
fx = [p[0] for p in pts]; fy = [p[1] for p in pts]
print(f"  羽毛包围盒 x[{min(fx):.0f},{max(fx):.0f}] y[{min(fy):.0f},{max(fy):.0f}]")
print(f"  整圆直径 256，外环外沿直径 {outer*2:.0f}")
print(f"  羽毛宽度 {max(fx)-min(fx):.0f}，占环直径 {(max(fx)-min(fx))/(outer*2)*100:.0f}%")
