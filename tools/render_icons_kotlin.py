"""直接解析 LingIcons.kt 并栅格化每个图标，用于在编译前核对形状。

不复制坐标 —— 而是读真实源码，避免"验证的和发布的不一致"。
支持：moveTo / lineTo / horizontalLineTo / verticalLineTo / close /
arcPolyline(cx,cy,r,startDeg,endDeg,steps) / solidIcon 填充。

用法:
    python render_icons_kotlin.py                 # 全部图标
    python render_icons_kotlin.py Refresh Lock    # 指定图标
"""
import math
import os
import re
import sys

KOTLIN = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "app", "src", "main", "java", "com", "ling", "browser", "ui", "theme", "LingIcons.kt",
)

VIEW = 24.0
SIZE = 24
SS = 3
PX = SIZE * SS
W = int(VIEW * PX)


# ---------------------------------------------------------------- 解析源码

def parse_icons(src):
    """返回 {name: {"solid": bool, "cmds": [(op, args...)]}}"""
    icons = {}
    # 匹配 val Name: ImageVector = icon("...") { ... } / solidIcon(...) { ... }
    pat = re.compile(
        r'val\s+(\w+)\s*:\s*ImageVector\s*=\s*(solidIcon|icon)\s*\(\s*"[^"]*"\s*\)\s*\{',
    )
    for m in pat.finditer(src):
        name = m.group(1)
        solid = m.group(2) == "solidIcon"
        body = extract_block(src, m.end() - 1)
        icons[name] = {"solid": solid, "cmds": parse_body(body)}
    return icons


def extract_block(src, open_idx):
    """从 '{' 开始提取配平的代码块内容。"""
    depth = 0
    i = open_idx
    while i < len(src):
        c = src[i]
        if c == '{':
            depth += 1
        elif c == '}':
            depth -= 1
            if depth == 0:
                return src[open_idx + 1:i]
        i += 1
    raise ValueError("unbalanced braces")


def strip_comments(code):
    code = re.sub(r'/\*.*?\*/', '', code, flags=re.S)
    code = re.sub(r'//[^\n]*', '', code)
    return code


def parse_body(body):
    """把 Kotlin DSL 调用翻译成指令序列。"""
    body = strip_comments(body)
    # 展开 repeat(3) { i -> ... } —— 本项目只在 MoreVert 用
    body = expand_repeat(body)

    cmds = []
    env = local_vals(body)
    # 逐条匹配调用；lineTo/horizontalLineTo 等可能一行多条
    call_re = re.compile(
        r'\b(moveTo|lineTo|horizontalLineTo|verticalLineTo|close|arcPolyline'
        r'|curveTo|reflectiveCurveTo|quadTo)\s*\(([^)]*)\)'
    )
    for m in call_re.finditer(body):
        op = m.group(1)
        raw = m.group(2).strip()
        args = []
        if raw:
            for a in split_args(raw):
                args.append(eval_num(a, env))
        cmds.append((op, args))
    return cmds


def split_args(raw):
    """按逗号切分，忽略命名参数名。"""
    parts = []
    depth = 0
    cur = ""
    for ch in raw:
        if ch == '(':
            depth += 1
        elif ch == ')':
            depth -= 1
        if ch == ',' and depth == 0:
            parts.append(cur)
            cur = ""
        else:
            cur += ch
    if cur.strip():
        parts.append(cur)
    out = []
    for p in parts:
        p = p.strip()
        # 去掉 "cx = " 这类命名参数前缀
        if '=' in p and not p.startswith('='):
            p = p.split('=', 1)[1].strip()
        out.append(p)
    return out


def eval_num(expr, env=None):
    """求值 Kotlin 数值表达式（可能是 5f / cy - 1.6f / i * 7f）。"""
    e = expr.strip()
    e = re.sub(r'(\d)f\b', r'\1', e)     # 5f -> 5
    e = e.replace('f', '')
    return float(eval(e, {"__builtins__": {}}, env or {}))


def local_vals(body):
    """收集块内的 `val name = expr` 局部绑定。"""
    env = {}
    for m in re.finditer(r'\bval\s+(\w+)\s*=\s*([^\n;]+)', body):
        name, expr = m.group(1), m.group(2).strip()
        try:
            env[name] = eval_num(expr, env)
        except Exception:
            pass
    return env


def expand_repeat(body):
    """把 repeat(N) { i -> BODY } 展开成 N 份（i 用具体值代入）。"""
    out = body
    for _ in range(10):  # 最多嵌套 10 层
        m = re.search(r'repeat\s*\(\s*(\d+)\s*\)\s*\{', out)
        if not m:
            break
        n = int(m.group(1))
        blk = extract_block(out, m.end() - 1)
        # 形如 " i -> ..."
        inner = blk
        arrow = re.match(r'\s*(\w+)\s*->', blk)
        var = arrow.group(1) if arrow else "i"
        if arrow:
            inner = blk[arrow.end():]
        expanded = " ".join(
            re.sub(r'\b%s\b' % re.escape(var), str(i), inner) for i in range(n)
        )
        out = out[:m.start()] + expanded + out[m.end() + len(blk) + 1:]
    return out


# ---------------------------------------------------------------- 栅格化

def cubic_points(p0, p1, p2, p3, n=16):
    pts = []
    for i in range(n + 1):
        t = i / n
        mt = 1 - t
        x = mt**3*p0[0] + 3*mt*mt*t*p1[0] + 3*mt*t*t*p2[0] + t**3*p3[0]
        y = mt**3*p0[1] + 3*mt*mt*t*p1[1] + 3*mt*t*t*p2[1] + t**3*p3[1]
        pts.append((x, y))
    return pts


def build_polylines(cmds):
    polys = []
    cur = None
    start = None
    pen = (0.0, 0.0)
    for op, a in cmds:
        if op == "moveTo":
            if cur and len(cur) > 1:
                polys.append(cur)
            pen = (a[0], a[1])
            start = pen
            cur = [pen]
        elif op == "lineTo":
            pen = (a[0], a[1])
            if cur is None:
                cur = [pen]
            cur.append(pen)
        elif op == "horizontalLineTo":
            pen = (a[0], pen[1])
            if cur is None:
                cur = [pen]
            cur.append(pen)
        elif op == "verticalLineTo":
            pen = (pen[0], a[0])
            if cur is None:
                cur = [pen]
            cur.append(pen)
        elif op == "close":
            if cur is None:
                cur = []
            if start:
                cur.append(start)
                pen = start
            if len(cur) > 1:
                polys.append(cur)
            cur = None
        elif op == "curveTo":
            p1 = (a[0], a[1]); p2 = (a[2], a[3]); p3 = (a[4], a[5])
            pts = cubic_points(pen, p1, p2, p3)
            if cur is None:
                cur = [pen]
                start = pen
            cur.extend(pts[1:])
            pen = p3
        elif op == "quadTo":
            p1 = (a[0], a[1]); p2 = (a[2], a[3])
            # 二次贝塞尔提升为三次
            c1 = (pen[0] + 2/3*(p1[0]-pen[0]), pen[1] + 2/3*(p1[1]-pen[1]))
            c2 = (p2[0] + 2/3*(p1[0]-p2[0]), p2[1] + 2/3*(p1[1]-p2[1]))
            pts = cubic_points(pen, c1, c2, p2)
            if cur is None:
                cur = [pen]
                start = pen
            cur.extend(pts[1:])
            pen = p2
        elif op == "arcPolyline":
            cx, cy, r, a0, a1, steps = a[0], a[1], a[2], a[3], a[4], int(a[5])
            pts = []
            for i in range(steps + 1):
                t = math.radians(a0 + (a1 - a0) * i / steps)
                pts.append((cx + r * math.cos(t), cy + r * math.sin(t)))
            if cur is None:
                cur = []
                start = pts[0]
            cur.extend(pts[1:])
            pen = pts[-1]
    if cur and len(cur) > 1:
        polys.append(cur)
    return polys


def rasterize_stroke(polys, lw=1.8):
    img = [[0.0] * W for _ in range(W)]
    half = lw / 2.0
    for pl in polys:
        for i in range(len(pl) - 1):
            x1, y1 = pl[i]
            x2, y2 = pl[i + 1]
            minx = int(max(0, min(x1, x2) - lw - 1) * PX)
            maxx = int(min(VIEW, max(x1, x2) + lw + 1) * PX)
            miny = int(max(0, min(y1, y2) - lw - 1) * PX)
            maxy = int(min(VIEW, max(y1, y2) + lw + 1) * PX)
            vx, vy = x2 - x1, y2 - y1
            L2 = vx * vx + vy * vy
            for py in range(max(0, miny), min(W - 1, maxy) + 1):
                wy = py / PX
                for pxx in range(max(0, minx), min(W - 1, maxx) + 1):
                    wx = pxx / PX
                    if L2 == 0:
                        d = math.hypot(wx - x1, wy - y1)
                    else:
                        t = max(0.0, min(1.0, ((wx - x1) * vx + (wy - y1) * vy) / L2))
                        d = math.hypot(wx - (x1 + t * vx), wy - (y1 + t * vy))
                    if d <= half:
                        img[py][pxx] = 1.0
    return img


def rasterize_fill(polys):
    """奇偶填充规则，用于 solidIcon。"""
    img = [[0.0] * W for _ in range(W)]
    for py in range(W):
        wy = (py + 0.5) / PX
        xs = []
        for pl in polys:
            n = len(pl)
            for i in range(n):
                x1, y1 = pl[i]
                x2, y2 = pl[(i + 1) % n]
                if (y1 > wy) != (y2 > wy):
                    xin = x1 + (wy - y1) * (x2 - x1) / (y2 - y1)
                    xs.append(xin)
        xs.sort()
        for i in range(0, len(xs) - 1, 2):
            a = int(max(0, xs[i] * PX))
            b = int(min(W - 1, xs[i + 1] * PX))
            for pxx in range(a, b + 1):
                img[py][pxx] = 1.0
    return img


def to_ascii(img, out_w=46, out_h=46):
    lines = []
    for r in range(out_h):
        line = ""
        for c in range(out_w):
            y0 = int(r * W / out_h); y1 = int((r + 1) * W / out_h)
            x0 = int(c * W / out_w); x1 = int((c + 1) * W / out_w)
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
    with open(KOTLIN, encoding="utf-8") as fh:
        src = fh.read()
    icons = parse_icons(src)
    wanted = sys.argv[1:] or list(icons.keys())
    print(f"解析到 {len(icons)} 个图标: {', '.join(icons.keys())}\n")
    for name in wanted:
        if name not in icons:
            print(f"!! 未找到图标 {name}")
            continue
        spec = icons[name]
        polys = build_polylines(spec["cmds"])
        img = rasterize_fill(polys) if spec["solid"] else rasterize_stroke(polys)
        kind = "实心" if spec["solid"] else "描边"
        print(f"===== {name} ({kind}) =====")
        for l in to_ascii(img):
            print(l)
        print()


if __name__ == "__main__":
    main()
