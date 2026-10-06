"""把生成出来的 Compose 图标代码栅格化成 ASCII，肉眼校验造型。

为什么需要它：这个环境的模型看不到图片，用 PNG 预览没有任何意义。
ASCII 是唯一能"看"到形状的方式。

做法是真正的扫描线填充（even-odd 规则）：
  1. 解析 _generated_icons.txt 里的 moveTo/lineTo/quadraticBezierTo/close
  2. 把二次贝塞尔离散成折线
  3. 用扫描线 + even-odd 判断每个采样点是否在图形内

用 even-odd 而不是 nonzero：Material 图标靠"挖洞"表达内部细节
（比如 download 的托盘、folder 的开合），两者规则不同会导致
填充结果不同，even-odd 更接近这些图标的实际渲染。
"""
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
DEFS = os.path.join(
    os.environ.get("TEMP", os.path.dirname(HERE)), "ling_generated_icons.txt",
)

W, H = 44, 22   # 输出字符网格


def parse_icons(text):
    """抽出 每个图标名 -> [(命令, [数字])] 的列表（可能多段子路径）。"""
    icons = {}
    for m in re.finditer(
        r"val (\w+): ImageVector by lazy \{\s*materialIcon\(\"[^\"]+\"\) \{(.*?)\n        \}",
        text, re.S,
    ):
        name, body = m.group(1), m.group(2)
        subpaths = []
        cur = []
        for cm in re.finditer(
            r"(moveTo|lineTo|quadTo|close)\(([^)]*)\)", body
        ):
            cmd, args = cm.group(1), cm.group(2)
            nums = [float(x.strip().rstrip("f"))
                    for x in args.split(",") if x.strip()]
            if cmd == "moveTo":
                if cur:
                    subpaths.append(cur)
                cur = [("M", nums)]
            else:
                cur.append(({"lineTo": "L", "quadTo": "Q",
                             "close": "Z"}[cmd], nums))
        if cur:
            subpaths.append(cur)
        icons[name] = subpaths
    return icons


def flatten(subpaths, steps=14):
    """把子路径转成折线点列，二次贝塞尔用 steps 段折线近似。"""
    polys = []
    for sp in subpaths:
        pts = []
        start = None
        cur = None
        for cmd, nums in sp:
            if cmd == "M":
                cur = (nums[0], nums[1])
                start = cur
                pts.append(cur)
            elif cmd == "L":
                for i in range(0, len(nums) - 1, 2):
                    cur = (nums[i], nums[i + 1])
                    pts.append(cur)
            elif cmd == "Q":
                for i in range(0, len(nums) - 3, 4):
                    p0 = cur
                    c = (nums[i], nums[i + 1])
                    p1 = (nums[i + 2], nums[i + 3])
                    for s in range(1, steps + 1):
                        t = s / steps
                        mt = 1 - t
                        x = mt * mt * p0[0] + 2 * mt * t * c[0] + t * t * p1[0]
                        y = mt * mt * p0[1] + 2 * mt * t * c[1] + t * t * p1[1]
                        pts.append((x, y))
                    cur = p1
            elif cmd == "Z":
                if start and pts and pts[-1] != start:
                    pts.append(start)
        if len(pts) >= 3:
            polys.append(pts)
    return polys


def inside(polys, x, y):
    """even-odd 规则：穿过多少次边界，奇数则在内部。"""
    cross = 0
    for pts in polys:
        for i in range(len(pts) - 1):
            x1, y1 = pts[i]
            x2, y2 = pts[i + 1]
            if (y1 > y) != (y2 > y):
                xin = x1 + (y - y1) * (x2 - x1) / (y2 - y1)
                if xin > x:
                    cross += 1
    return cross % 2 == 1


def render(name, polys, w=W, h=H):
    print(f"--- {name} ---")
    for row in range(h):
        line = ""
        for col in range(w):
            # 960 网格，留一点边距
            x = 40 + col * (880 / (w - 1))
            y = 40 + row * (880 / (h - 1))
            line += "#" if inside(polys, x, y) else "."
        print("  " + line)
    print()


def main():
    if not os.path.exists(DEFS):
        sys.exit(f"缺少 {DEFS}，请先运行 tools/import_material_icons.py")
    text = open(DEFS, encoding="utf-8").read()
    icons = parse_icons(text)
    print(f"解析到 {len(icons)} 个图标：{', '.join(sorted(icons))}\n")

    want = sys.argv[1:] if len(sys.argv) > 1 else sorted(icons)[:2]
    for name in want:
        if name not in icons:
            print(f"  ?? 没有图标 {name}")
            continue
        render(name, flatten(icons[name]))


if __name__ == "__main__":
    main()
