"""把 tools/icon.xml 栅格化并做几何自检。

没有 cairo，所以这里用一个最小 SVG 渲染器（只支持本文件用到的元素：
circle / path，含 M C L Z 指令与线性渐变），配合 4x 超采样输出预览。

主要目的是**用数字回答**：
  - 图形有没有超出安全区（自适应图标会被裁切）
  - 羽毛是否落在外环里（视觉重心是否合理）
  - 各元素的实际占比
"""
import os
import re
import math
import xml.etree.ElementTree as ET

XML = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "tools", "icon.xml",
)

SS = 4          # 超采样倍数
OUT = 64        # 最终预览尺寸


# ---------------------------------------------------------------- 解析
def parse_transform(s):
    """解析 translate/scale 变换，返回 2x3 仿射矩阵 (a,b,c,d,e,f)。

    仅支持本工程用到的 translate(x[,y]) 与 scale(s[,s])，按书写顺序右乘。
    """
    m = (1.0, 0.0, 0.0, 1.0, 0.0, 0.0)   # a b c d e f
    for name, args in re.findall(r"(translate|scale|matrix)\s*\(([^)]*)\)", s or ""):
        v = [float(x) for x in re.split(r"[,\s]+", args.strip()) if x]
        if name == "translate":
            tx = v[0] if v else 0.0
            ty = v[1] if len(v) > 1 else 0.0
            n = (1.0, 0.0, 0.0, 1.0, tx, ty)
        elif name == "scale":
            sx = v[0] if v else 1.0
            sy = v[1] if len(v) > 1 else sx
            n = (sx, 0.0, 0.0, sy, 0.0, 0.0)
        else:  # matrix(a,b,c,d,e,f)
            n = tuple((v + [0] * 6)[:6])
        # 右乘：m = m * n
        a1, b1, c1, d1, e1, f1 = m
        a2, b2, c2, d2, e2, f2 = n
        m = (
            a1 * a2 + c1 * b2, b1 * a2 + d1 * b2,
            a1 * c2 + c1 * d2, b1 * c2 + d1 * d2,
            a1 * e2 + c1 * f2 + e1, b1 * e2 + d1 * f2 + f1,
        )
    return m


def apply_matrix(m, x, y):
    a, b, c, d, e, f = m
    return (a * x + c * y + e, b * x + d * y + f)


def parse_svg(path):
    tree = ET.parse(path)
    root = tree.getroot()
    vb = root.get("viewBox", "0 0 256 256").split()
    vx, vy, vw, vh = (float(v) for v in vb)

    grad = None
    for g in root.iter():
        if g.tag.endswith("linearGradient"):
            stops = []
            for s in g:
                if s.tag.endswith("stop"):
                    stops.append((
                        float(s.get("offset", 0)),
                        s.get("stop-color", "#000000"),
                    ))
            stops.sort()
            grad = stops

    items = []

    def walk(el, matrix, inherited=None):
        inherited = dict(inherited or {})
        # <g fill="..."> 之类的表现属性会被子元素继承
        for attr in ("fill", "stroke", "stroke-width", "opacity", "fill-rule"):
            if el.get(attr) is not None:
                inherited[attr] = el.get(attr)

        for child in el:
            tag = child.tag.split("}")[-1]
            m = matrix
            if child.get("transform"):
                m = parse_transform(child.get("transform"))
                if matrix != (1, 0, 0, 1, 0, 0):
                    pm = matrix
                    a1, b1, c1, d1, e1, f1 = pm
                    a2, b2, c2, d2, e2, f2 = m
                    m = (
                        a1 * a2 + c1 * b2, b1 * a2 + d1 * b2,
                        a1 * c2 + c1 * d2, b1 * c2 + d1 * d2,
                        a1 * e2 + c1 * f2 + e1, b1 * e2 + d1 * f2 + f1,
                    )

            def attr(name, default):
                v = child.get(name)
                if v is None:
                    v = inherited.get(name)
                return default if v is None else v

            if tag == "circle":
                items.append({
                    "type": "circle",
                    "cx": float(child.get("cx", 0)),
                    "cy": float(child.get("cy", 0)),
                    "r": float(child.get("r", 0)),
                    "fill": attr("fill", "none"),
                    "stroke": attr("stroke", None),
                    "sw": float(attr("stroke-width", 0)),
                    "opacity": float(attr("opacity", 1)),
                    "matrix": m,
                })
            elif tag == "path":
                items.append({
                    "type": "path",
                    "d": child.get("d", ""),
                    "fill": attr("fill", "none"),
                    "fill-rule": attr("fill-rule", "nonzero"),
                    "stroke": attr("stroke", None),
                    "sw": float(attr("stroke-width", 0)),
                    "opacity": float(attr("opacity", 1)),
                    "matrix": m,
                })
            elif tag in ("g", "svg"):
                walk(child, m, inherited)

    walk(root, (1.0, 0.0, 0.0, 1.0, 0.0, 0.0))
    return (vx, vy, vw, vh), grad, items


# ---------------------------------------------------------------- 路径
def parse_path(d):
    """解析 M / m / C / c / L / l / Z 为子路径点列 + 贝塞尔段。"""
    tokens = re.findall(r"[MmLlCcZzHhVv]|-?\d*\.?\d+(?:[eE][-+]?\d+)?", d)
    subs, cur, start = [], [], None
    i, cmd = 0, None
    nums = lambda: float(tokens[i])

    def flush():
        nonlocal cur, start
        if cur:
            subs.append({"pts": cur, "closed": False})
        cur, start = [], None

    while i < len(tokens):
        t = tokens[i]
        if re.match(r"[A-Za-z]", t):
            cmd = t
            i += 1
            if cmd in "Zz":
                if cur:
                    subs.append({"pts": list(cur), "closed": True})
                    if start:
                        cur = [start]
                continue
            continue
        if cmd in "Mm":
            x, y = float(tokens[i]), float(tokens[i + 1])
            i += 2
            if cmd == "m" and cur:
                x += cur[-1][0]; y += cur[-1][1]
            flush()
            cur = [(x, y)]
            start = (x, y)
            cmd = "L" if cmd == "M" else "l"
        elif cmd in "Ll":
            x, y = float(tokens[i]), float(tokens[i + 1])
            i += 2
            if cmd == "l" and cur:
                x += cur[-1][0]; y += cur[-1][1]
            cur.append((x, y))
        elif cmd in "Cc":
            p = [float(tokens[i + k]) for k in range(6)]
            i += 6
            if cmd == "c" and cur:
                bx, by = cur[-1]
                p = [p[0] + bx, p[1] + by, p[2] + bx, p[3] + by, p[4] + bx, p[5] + by]
            cur.append(("C", p[0], p[1], p[2], p[3], p[4], p[5]))
        elif cmd in "Hh":
            x = float(tokens[i]); i += 1
            if cmd == "h" and cur:
                x += cur[-1][0]
            cur.append((x, cur[-1][1]))
        elif cmd in "Vv":
            y = float(tokens[i]); i += 1
            if cmd == "v" and cur:
                y += cur[-1][1]
            cur.append((cur[-1][0], y))
        else:
            i += 1
    flush()
    return subs


def flatten(subs, steps=24, matrix=None):
    """把子路径转成多边形点列（贝塞尔采样），可选应用仿射变换。"""
    polys = []
    for sp in subs:
        pts = []
        for item in sp["pts"]:
            if isinstance(item, tuple) and len(item) == 7 and item[0] == "C":
                _, x1, y1, x2, y2, x3, y3 = item
                p0 = pts[-1] if pts else (x1, y1)
                for s in range(1, steps + 1):
                    t = s / steps
                    mt = 1 - t
                    x = (mt**3 * p0[0] + 3 * mt**2 * t * x1
                         + 3 * mt * t**2 * x2 + t**3 * x3)
                    y = (mt**3 * p0[1] + 3 * mt**2 * t * y1
                         + 3 * mt * t**2 * y2 + t**3 * y3)
                    pts.append((x, y))
            else:
                pts.append(item)
        if matrix:
            pts = [apply_matrix(matrix, x, y) for x, y in pts]
        if len(pts) >= 3:
            polys.append((pts, sp["closed"]))
    return polys


# ---------------------------------------------------------------- 光栅化
def hex_rgb(h):
    h = h.lstrip("#")
    if len(h) == 3:
        h = "".join(c * 2 for c in h)
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def grad_color(grad, x, y, vw, vh, skew=0.5):
    """对角线线性渐变（x1,y1 -> x2,y2 均为 0..1 的对角）。"""
    t = (x / vw + y / vh) / 2.0
    t = max(0.0, min(1.0, t))
    if not grad:
        return (0, 0, 0)
    lo = grad[0]
    for k in range(len(grad) - 1):
        a, b = grad[k], grad[k + 1]
        if a[0] <= t <= b[0]:
            span = (b[0] - a[0]) or 1
            u = (t - a[0]) / span
            ca, cb = hex_rgb(a[1]), hex_rgb(b[1])
            return tuple(int(ca[j] + (cb[j] - ca[j]) * u) for j in range(3))
    return hex_rgb(grad[-1][1])


def point_in_poly(px, py, pts):
    inside = False
    n = len(pts)
    for i in range(n):
        x1, y1 = pts[i]
        x2, y2 = pts[(i + 1) % n]
        if (y1 > py) != (y2 > py):
            xin = x1 + (py - y1) * (x2 - x1) / ((y2 - y1) or 1e-9)
            if px < xin:
                inside = not inside
    return inside


def render(items, grad, view, W, ss=SS, bg=None):
    vx, vy, vw, vh = view
    S = W * ss
    scale = S / vw
    img = [[bg for _ in range(S)] for _ in range(S)] if bg else \
          [[(0, 0, 0, 0) for _ in range(S)] for _ in range(S)]

    flat = []
    for it in items:
        entry = dict(it)
        if it["type"] == "path":
            entry["polys"] = flatten(
                parse_path(it["d"]), matrix=entry.get("matrix")
            )
        flat.append(entry)

    for py in range(S):
        for px in range(S):
            wx = (px + 0.5) / scale + vx
            wy = (py + 0.5) / scale + vy
            col = img[py][px]
            for it in flat:
                hit = False
                if it["type"] == "circle":
                    cxx, cyy = apply_matrix(
                        it.get("matrix") or (1, 0, 0, 1, 0, 0), it["cx"], it["cy"]
                    )
                    d = math.hypot(wx - cxx, wy - cyy)
                    if it["stroke"]:
                        if abs(d - it["r"]) <= it["sw"] / 2:
                            hit = True
                            c = hex_rgb(it["stroke"]) + (int(255 * it["opacity"]),)
                    elif d <= it["r"]:
                        hit = True
                        c = (grad_color(grad, wx, wy, vw, vh)
                             if it["fill"].startswith("url")
                             else hex_rgb(it["fill"])) + (int(255 * it["opacity"]),)
                else:
                    if it.get("fill-rule") == "evenodd":
                        # even-odd：统计落在几个子路径内，奇数才填。
                        # 这是"负空间挖缝"能生效的前提，否则羽轴会被填成实心。
                        n = sum(
                            1 for pts, closed in it["polys"]
                            if point_in_poly(wx, wy, pts)
                        )
                        hit = (n % 2) == 1
                    else:
                        for pts, closed in it["polys"]:
                            if point_in_poly(wx, wy, pts):
                                hit = True
                                break
                    if hit:
                        c = hex_rgb(it["fill"]) + (int(255 * it["opacity"]),)
                if hit:
                    a = c[3] / 255
                    col = tuple(int(col[j] * (1 - a) + c[j] * a) for j in range(3)) \
                        + (max(col[3] if len(col) > 3 else 255, c[3]),)
            img[py][px] = col
    return img, S
