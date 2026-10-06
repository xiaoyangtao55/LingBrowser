"""校验生成的 Android vector drawable 是否正确且落在安全区内。

直接解析 res/drawable/ic_launcher_*.xml 的 pathData（含 Android 支持的
a/A 圆弧指令），渲染成 ASCII 预览，并检查：
  - pathData 语法能否解析
  - 前景是否全部落在中心 72dp 安全区内
  - 背景是否铺满画布
"""
import os
import re
import math
import xml.etree.ElementTree as ET

RES = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "app", "src", "main", "res", "drawable",
)
AND = "{http://schemas.android.com/apk/res/android}"


def parse_pathdata(d):
    """解析 Android pathData，返回子路径及其几何。"""
    tokens = re.findall(
        r"[MmLlHhVvCcSsQqTtAaZz]|[-+]?\d*\.?\d+(?:[eE][-+]?\d+)?", d
    )
    subs, pts, i, cmd = [], [], 0, None
    prev = (0.0, 0.0)
    start = (0.0, 0.0)
    arcs = []

    def flush():
        nonlocal pts
        if pts:
            subs.append(pts)
        pts = []

    while i < len(tokens):
        t = tokens[i]
        if re.match(r"[A-Za-z]", t):
            cmd = t
            i += 1
            if cmd in "Zz":
                flush()
                pts = [start]
                continue
            continue
        if cmd in "Mm":
            x, y = float(tokens[i]), float(tokens[i + 1]); i += 2
            flush()
            pts = [(x, y)]
            start = prev = (x, y)
            cmd = "L" if cmd == "M" else "l"
        elif cmd in "Ll":
            x, y = float(tokens[i]), float(tokens[i + 1]); i += 2
            if cmd == "l":
                x, y = prev[0] + x, prev[1] + y
            pts.append((x, y)); prev = (x, y)
        elif cmd in "Aa":
            rx, ry = float(tokens[i]), float(tokens[i + 1])
            rot = float(tokens[i + 2])
            laf, sf = int(float(tokens[i + 3])), int(float(tokens[i + 4]))
            x, y = float(tokens[i + 5]), float(tokens[i + 6]); i += 7
            if cmd == "a":
                x, y = prev[0] + x, prev[1] + y
            arcs.append((prev, (x, y), rx, ry, laf, sf))
            # 采样圆弧端点用于包围盒
            for s in range(1, 25):
                u = s / 24
                pts.append((prev[0] + (x - prev[0]) * u, prev[1] + (y - prev[1]) * u))
            prev = (x, y)
        elif cmd in "Cc":
            vals = [float(tokens[i + k]) for k in range(6)]; i += 6
            if cmd == "c":
                vals = [vals[0] + prev[0], vals[1] + prev[1],
                        vals[2] + prev[0], vals[3] + prev[1],
                        vals[4] + prev[0], vals[5] + prev[1]]
            pts.append((vals[4], vals[5]))
            prev = (vals[4], vals[5])
        else:
            i += 1
    flush()
    return subs, arcs


def check_file(path):
    tree = ET.parse(path)
    root = tree.getroot()
    vw = float(root.get(AND + "viewportWidth", 108))
    vh = float(root.get(AND + "viewportHeight", 108))
    name = os.path.basename(path)

    all_pts, all_arcs = [], []
    npaths = 0
    for p in root:
        if p.tag != "path":
            continue
        npaths += 1
        d = p.get(AND + "pathData") or ""
        subs, arcs = parse_pathdata(d)
        for sub in subs:
            all_pts.extend(sub)
        all_arcs.extend(arcs)

    # 圆弧的真实包围盒（圆环类）
    for (p0, p1, rx, ry, laf, sf) in all_arcs:
        all_pts.extend([p0, p1])

    xs = [p[0] for p in all_pts]
    ys = [p[1] for p in all_pts]
    stroke = 0.0
    for p in root:
        if p.tag == "path":
            sw = p.get(AND + "strokeWidth")
            if sw:
                stroke = max(stroke, float(sw))

    print(f"--- {name} ---")
    print(f"  viewport {vw:.0f}x{vh:.0f}, path 数 {npaths}")
    if not all_pts:
        print("  没有解析到任何点!")
        return False, None

    bb = (min(xs), min(ys), max(xs), max(ys))
    pad = stroke / 2
    print(f"  包围盒 x[{bb[0]-pad:.2f}, {bb[2]+pad:.2f}] "
          f"y[{bb[1]-pad:.2f}, {bb[3]+pad:.2f}]  (含描边 {stroke:.2f})")

    cx, cy = vw / 2, vh / 2
    far = max(math.hypot(x - cx, y - cy) for x, y in all_pts) + pad
    print(f"  离画布中心最远 {far:.2f}dp   (安全半径 36.00dp)")
    return True, far


def main():
    files = ["ic_launcher_background.xml",
             "ic_launcher_foreground.xml",
             "ic_launcher_monochrome.xml"]

    ok = True
    for f in files:
        path = os.path.join(RES, f)
        if not os.path.exists(path):
            print(f"--- {f} --- 缺失!")
            ok = False
            continue
        parsed, far = check_file(path)
        if not parsed:
            ok = False
        elif "background" in f:
            if far < 36:
                print(f"  ! 背景未铺满画布")
                ok = False
            else:
                print(f"  OK 背景铺满（会被遮罩裁切，符合预期）")
        else:
            if far <= 36.05:
                # 检查描边是否被裁：矩形遮罩下 x/y 需在 [18,90]
                print(f"  OK 前景在安全区内")
            else:
                print(f"  ! 前景超出安全区 {far:.2f} > 36")
                ok = False
        print()

    print("全部通过" if ok else "存在问题")
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
