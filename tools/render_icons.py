"""离线渲染 LingIcons 的路径，用来在编译前核对图标形状。

Compose 的 ImageVector 在运行时才渲染，肉眼又无法在构建阶段检查，
所以这里用 Python 复刻一遍路径语义并栅格化，把每个图标打成 ASCII，
便于逐点核对几何是否符合预期。

只实现本项目用到的指令：M/m 移动、L/l 直线、H/h 水平、V/v 垂直、
C/c 三次贝塞尔、A/a 圆弧、Z/z 闭合，以及 Stroke 描边近似。
"""
import math
import re
import sys

VIEW = 24.0
SIZE = 24          # 栅格分辨率（世界坐标 24 -> SIZE 像素）
SS = 3             # 超采样倍数
PX = SIZE * SS     # 世界坐标 -> 像素 的比例
W = int(VIEW * PX) # 画布边长（正方形）

# 从 Kotlin 源里抽取 —— 这里维护一份等价坐标，改图标时同步更新。
# 每个图标: (名称, [(指令, 参数...), ...])
ICONS = {
    "ArrowBack": [
        ("M", 19, 12), ("H", 5.5), ("M", 11, 5.5), ("L", 4.5, 12), ("L", 11, 18.5),
    ],
    "ArrowForward": [
        ("M", 5, 12), ("H", 18.5), ("M", 13, 5.5), ("L", 19.5, 12), ("L", 13, 18.5),
    ],
    "Close": [
        ("M", 6, 6), ("L", 18, 18), ("M", 18, 6), ("L", 6, 18),
    ],
    "Refresh": [
        # 圆环：-60° -> 240°
        ("ARC", 12, 12, 7, -60, 240, 24),
        ("M", 14.2, 3.2), ("L", 19.0, 5.6), ("L", 16.6, 10.4),
    ],
    "Home": [
        ("M", 3.5, 10.8), ("L", 12, 3.8), ("L", 20.5, 10.8),
        ("M", 5.8, 9.2), ("V", 19.5), ("L", 18.2, 19.5), ("V", 9.2),
        ("M", 9.8, 19.5), ("V", 14.2), ("L", 14.2, 14.2), ("V", 19.5),
    ],
    "Layers": [
        ("M", 12, 3.5), ("L", 21, 8), ("L", 12, 12.5), ("L", 3, 8), ("Z",),
        ("M", 3, 12.5), ("L", 12, 17), ("L", 21, 12.5),
        ("M", 3, 16.5), ("L", 12, 21), ("L", 21, 16.5),
    ],
    "Add": [
        ("M", 12, 5), ("V", 19), ("M", 5, 12), ("H", 19),
    ],
    "BookmarkBorder": [
        ("M", 6.5, 4), ("H", 17.5), ("A", 1, 1, 0, 0, 1, 18.5, 5),
        ("V", 20), ("L", 12, 15.8), ("L", 5.5, 20), ("V", 5),
        ("A", 1, 1, 0, 0, 1, 6.5, 4), ("Z",),
    ],
    "History": [
        ("ARC", 12, 12, 8.5, 200, 520, 20),
        ("M", 3.5, 5), ("V", 10), ("H", 8.5),
        ("M", 12, 8), ("V", 12.4), ("L", 15.2, 14.4),
    ],
    "Search": [
        ("ARC", 10.8, 10.8, 6.8, 0, 360, 24),
        ("M", 15.8, 15.8), ("L", 20, 20),
    ],
    "Nightlight": [
        ("ARC", 20.5, 14.5, 8.5, 0, 360, 24),
    ],
    "Settings": [
        ("ARC", 12, 12, 3.2, 0, 360, 20),
        ("ARC", 12, 12, 9, 0, 360, 24),
    ],
    "PrivacyTip": [
        ("M", 12, 3.2), ("L", 19.5, 6.2), ("V", 11.5),
        ("C", 19.5, 16.2, 16.3, 19.6, 12, 20.8),
        ("C", 7.7, 19.6, 4.5, 16.2, 4.5, 11.5),
        ("V", 6.2), ("Z",),
        ("M", 12, 10.6), ("V", 15.6), ("M", 12, 8), ("V", 8.1),
    ],
    "DesktopWindows": [
        ("M", 3.5, 5), ("H", 20.5), ("A", 1, 1, 0, 0, 1, 21.5, 6),
        ("V", 15), ("A", 1, 1, 0, 0, 1, 20.5, 16), ("H", 3.5),
        ("A", 1, 1, 0, 0, 1, 2.5, 15), ("V", 6), ("A", 1, 1, 0, 0, 1, 3.5, 5), ("Z",),
        ("M", 8.5, 20), ("H", 15.5), ("M", 12, 16), ("V", 20),
    ],
    "Public": [
        ("ARC", 12, 12, 8.5, 0, 360, 28),
        ("M", 3.5, 12), ("H", 20.5),
        ("ARC", 12, 12, 8.5, 0, 360, 28),
    ],
    "Javascript": [
        ("M", 4, 4), ("H", 20), ("V", 20), ("H", 4), ("Z",),
        ("M", 9.6, 10), ("V", 15.6),
        ("M", 17.4, 11.2), ("L", 15, 12.2), ("L", 16.4, 13.6), ("L", 15.2, 16.2),
    ],
    "Image": [
        ("M", 4.5, 5), ("H", 19.5), ("A", 1, 1, 0, 0, 1, 20.5, 6),
        ("V", 18), ("A", 1, 1, 0, 0, 1, 19.5, 19), ("H", 4.5),
        ("A", 1, 1, 0, 0, 1, 3.5, 18), ("V", 6), ("A", 1, 1, 0, 0, 1, 4.5, 5), ("Z",),
        ("ARC", 8.8, 10.6, 1.4, 0, 360, 12),
        ("M", 3.8, 17), ("L", 9.5, 12.5), ("L", 13.5, 15.8), ("L", 16.5, 13.2), ("L", 20.2, 16.4),
    ],
    "DeleteOutline": [
        ("M", 4.5, 7), ("H", 19.5),
        ("M", 9.5, 7), ("V", 4.8), ("A", 0.8, 0.8, 0, 0, 1, 10.3, 4),
        ("H", 13.7), ("A", 0.8, 0.8, 0, 0, 1, 14.5, 4.8), ("V", 7),
        ("M", 6.5, 7), ("V", 19.2), ("A", 0.8, 0.8, 0, 0, 0, 7.3, 20),
        ("H", 16.7), ("A", 0.8, 0.8, 0, 0, 0, 17.5, 19.2), ("V", 7),
        ("M", 10.2, 11), ("V", 16), ("M", 13.8, 11), ("V", 16),
    ],
    "DeleteSweep": [
        ("M", 3.5, 6.5), ("H", 13.5),
        ("M", 6, 10.5), ("H", 13.5),
        ("M", 8.5, 14.5), ("H", 13.5),
        ("M", 16.5, 5), ("L", 20.5, 9),
        ("M", 20.5, 5), ("L", 16.5, 9),
    ],
    "Lock": [
        ("M", 6.5, 10.5), ("H", 17.5), ("A", 1, 1, 0, 0, 1, 18.5, 11.5),
        ("V", 19.5), ("A", 1, 1, 0, 0, 1, 17.5, 20.5), ("H", 6.5),
        ("A", 1, 1, 0, 0, 1, 5.5, 19.5), ("V", 11.5), ("A", 1, 1, 0, 0, 1, 6.5, 10.5), ("Z",),
        ("M", 8.5, 10.5), ("V", 7.5), ("ARC", 12, 7.5, 0, 0, 0, 1),
    ],
    "MoreVert": [
        ("ARC", 12, 5, 1.6, 0, 360, 12),
        ("ARC", 12, 12, 1.6, 0, 360, 12),
        ("ARC", 12, 19, 1.6, 0, 360, 12),
    ],
    "AutoAwesome": [
        ("M", 12, 2.5), ("L", 13.9, 8.6), ("L", 20, 10.5), ("L", 13.9, 12.4),
        ("L", 12, 18.5), ("L", 10.1, 12.4), ("L", 4, 10.5), ("L", 10.1, 8.6), ("Z",),
        ("M", 18.8, 15.4), ("L", 19.5, 17.5), ("L", 21.6, 18.2), ("L", 19.5, 18.9),
        ("L", 18.8, 21), ("L", 18.1, 18.9), ("L", 16, 18.2), ("L", 18.1, 17.5), ("Z",),
    ],
}


def seg_line(p0, p1):
    return [p0, p1]


def cubic_points(p0, p1, p2, p3, n=16):
    pts = []
    for i in range(n + 1):
        t = i / n
        mt = 1 - t
        x = mt**3*p0[0] + 3*mt*mt*t*p1[0] + 3*mt*t*t*p2[0] + t**3*p3[0]
        y = mt**3*p0[1] + 3*mt*mt*t*p1[1] + 3*mt*t*t*p2[1] + t**3*p3[1]
        pts.append((x, y))
    return pts


def arc_from_to(x1, y1, rx, ry, large, sweep, x2, y2, n=24):
    """端点式圆弧 -> 折线（SVG 语义）。"""
    if rx == 0 or ry == 0 or (x1 == x2 and y1 == y2):
        return [(x1, y1), (x2, y2)]
    rx, ry = abs(rx), abs(ry)
    dx2 = (x1 - x2) / 2.0
    dy2 = (y1 - y2) / 2.0
    lam = dx2*dx2/(rx*rx) + dy2*dy2/(ry*ry)
    if lam > 1:
        s = math.sqrt(lam)
        rx *= s
        ry *= s
    sign = -1 if large == sweep else 1
    num = rx*rx*ry*ry - rx*rx*dy2*dy2 - ry*ry*dx2*dx2
    den = rx*rx*dy2*dy2 + ry*ry*dx2*dx2
    co = sign * math.sqrt(max(0.0, num/den)) if den else 0.0
    cxp = co * rx * dy2 / ry
    cyp = -co * ry * dx2 / rx
    cx = cxp + (x1 + x2) / 2.0
    cy = cyp + (y1 + y2) / 2.0

    def ang(ux, uy, vx, vy):
        d = (ux*vx + uy*vy) / (math.hypot(ux, uy) * math.hypot(vx, vy))
        d = max(-1.0, min(1.0, d))
        a = math.acos(d)
        return -a if (ux*vy - uy*vx) < 0 else a

    th1 = ang(1, 0, (dx2-cxp)/rx, (dy2-cyp)/ry)
    dth = ang((dx2-cxp)/rx, (dy2-cyp)/ry, (-dx2-cxp)/rx, (-dy2-cyp)/ry)
    if not sweep and dth > 0:
        dth -= 2*math.pi
    elif sweep and dth < 0:
        dth += 2*math.pi
    pts = []
    for i in range(n + 1):
        t = th1 + dth * i / n
        pts.append((cx + rx*math.cos(t), cy + ry*math.sin(t)))
    return pts


def build_polylines(cmds):
    polys = []
    cur = None
    start = None
    pen = (0.0, 0.0)
    for c in cmds:
        op = c[0]
        if op == "M":
            if cur and len(cur) > 1:
                polys.append(cur)
            pen = (c[1], c[2]); start = pen; cur = [pen]
        elif op == "L":
            pen = (c[1], c[2]); cur.append(pen)
        elif op == "H":
            pen = (c[1], pen[1]); cur.append(pen)
        elif op == "V":
            pen = (pen[0], c[1]); cur.append(pen)
        elif op == "Z":
            if start: cur.append(start); pen = start
            if cur and len(cur) > 1: polys.append(cur)
            cur = None
        elif op == "C":
            pts = cubic_points(pen, (c[1], c[2]), (c[3], c[4]), (c[5], c[6]))
            cur.extend(pts[1:]); pen = (c[5], c[6])
        elif op == "A":
            _, rx, ry, rot, large, sweep, x2, y2 = c
            pts = arc_from_to(pen[0], pen[1], rx, ry, large, sweep, x2, y2)
            cur.extend(pts[1:]); pen = (x2, y2)
        elif op == "ARC":
            # 圆心式：cx, cy, r, startDeg, endDeg, steps
            _, cx, cy, r, a0, a1, steps = c
            pts = []
            for i in range(steps + 1):
                t = math.radians(a0 + (a1 - a0) * i / steps)
                pts.append((cx + r*math.cos(t), cy + r*math.sin(t)))
            if cur is None:
                cur = []
                start = pts[0]
            cur.extend(pts[1:]); pen = pts[-1]
    if cur and len(cur) > 1:
        polys.append(cur)
    return polys


def rasterize(polys, lw=1.8):
    img = [[0.0]*W for _ in range(W)]
    half = lw / 2.0
    for pl in polys:
        for i in range(len(pl) - 1):
            x1, y1 = pl[i]; x2, y2 = pl[i+1]
            minx = int(max(0, min(x1, x2) - lw - 1) * PX)
            maxx = int(min(VIEW, max(x1, x2) + lw + 1) * PX)
            miny = int(max(0, min(y1, y2) - lw - 1) * PX)
            maxy = int(min(VIEW, max(y1, y2) + lw + 1) * PX)
            for py in range(miny, maxy + 1):
                wy = py / PX
                for px_ in range(minx, maxx + 1):
                    wx = px_ / PX
                    vx, vy = x2 - x1, y2 - y1
                    L2 = vx*vx + vy*vy
                    if L2 == 0:
                        d = math.hypot(wx - x1, wy - y1)
                    else:
                        t = max(0.0, min(1.0, ((wx-x1)*vx + (wy-y1)*vy) / L2))
                        d = math.hypot(wx - (x1 + t*vx), wy - (y1 + t*vy))
                    if d <= half:
                        img[py][px_] = 1.0
    return img


def to_ascii(img, out_w=48, out_h=48):
    """按字符宽高比 1:2 输出，视觉上接近正方形（终端字符高约为宽的两倍）。"""
    lines = []
    for r in range(out_h):
        line = ""
        for c in range(out_w):
            y0 = int(r * W / out_h); y1 = int((r+1) * W / out_h)
            x0 = int(c * W / out_w); x1 = int((c+1) * W / out_w)
            cov = 0; tot = 0
            for yy in range(y0, y1, 2):
                for xx in range(x0, x1, 2):
                    tot += 1
                    cov += img[yy][xx]
            f = cov / tot if tot else 0
            line += "#" if f > 0.5 else ("+" if f > 0.15 else ("." if f > 0.02 else " "))
        lines.append(line)
    return lines


def main():
    names = sys.argv[1:] or list(ICONS.keys())
    for name in names:
        cmds = ICONS[name]
        polys = build_polylines(cmds)
        img = rasterize(polys)
        print(f"===== {name} =====")
        for l in to_ascii(img):
            print(l)
        print()


if __name__ == "__main__":
    main()
