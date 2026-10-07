"""模拟 LauncherIconTest.kt 的断言，确认它们对当前资源成立。

（设备编译慢，用这个代替跑一遍测试。逻辑与 Kotlin 测试一一对应。）
"""
import os
import re
import math

RES = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "app", "src", "main", "res",
)

problems = []


def read(rel):
    p = os.path.join(RES, rel)
    if not os.path.exists(p):
        problems.append(f"文件不存在: {rel}")
        return ""
    return open(p, encoding="utf-8").read()


def path_data(xml):
    return re.findall(r'android:pathData="([^"]+)"', xml)


def endpoints(d):
    """解析 pathData，返回所有端点坐标。

    不能把数字两两配对 —— 圆弧 `a rx,ry rot laf,sf dx,dy` 里的
    半径与标志位不是坐标，混进来会算出离谱的包围盒。
    这里按指令逐个消费参数，只保留真正的端点。
    """
    toks = re.findall(r"[MmLlHhVvCcSsQqTtAaZz]|[-+]?\d*\.?\d+(?:[eE][-+]?\d+)?", d)
    out = []
    i = 0
    cmd = " "
    cur = (0.0, 0.0)

    def num():
        nonlocal i
        v = float(toks[i]); i += 1
        return v

    while i < len(toks):
        t = toks[i]
        if len(t) == 1 and t.isalpha():
            cmd = t; i += 1; continue
        if cmd in "Mm":
            x, y = num(), num()
            cur = (cur[0] + x, cur[1] + y) if cmd == "m" else (x, y)
            out.append(cur)
            cmd = "L" if cmd == "M" else "l"
        elif cmd in "Ll":
            x, y = num(), num()
            cur = (cur[0] + x, cur[1] + y) if cmd == "l" else (x, y)
            out.append(cur)
        elif cmd in "Hh":
            x = num()
            cur = (cur[0] + x, cur[1]) if cmd == "h" else (x, cur[1])
            out.append(cur)
        elif cmd in "Vv":
            y = num()
            cur = (cur[0], cur[1] + y) if cmd == "v" else (cur[0], y)
            out.append(cur)
        elif cmd in "Cc":
            n = [num() for _ in range(6)]
            cur = (cur[0] + n[4], cur[1] + n[5]) if cmd == "c" else (n[4], n[5])
            out.append(cur)
        elif cmd in "Aa":
            num(); num(); num(); num(); num()
            dx, dy = num(), num()
            cur = (cur[0] + dx, cur[1] + dy) if cmd == "a" else (dx, dy)
            out.append(cur)
        else:
            i += 1
    return out


def max_radius(paths, center=54.0):
    pts = [p for d in paths for p in endpoints(d)]
    if not pts:
        return 0.0
    return max(math.hypot(x - center, y - center) for x, y in pts)


def bbox_ratio(paths):
    """前景层所有端点的包围盒长宽比（对应 Kotlin 的『羽毛保持细长比例』）。"""
    pts = [p for d in paths for p in endpoints(d)]
    if not pts:
        return 0.0
    xs = [p[0] for p in pts]
    ys = [p[1] for p in pts]
    w = max(xs) - min(xs)
    h = max(ys) - min(ys)
    return max(w, h) / min(w, h), w, h


def check(cond, msg):
    if not cond:
        problems.append(msg)
    print(("  OK   " if cond else "  FAIL ") + msg)


# ---- 1. 三层文件存在 ----
print("1. 三层图标文件存在")
for f in ("drawable/ic_launcher_background.xml",
          "drawable/ic_launcher_foreground.xml",
          "drawable/ic_launcher_monochrome.xml",
          "mipmap-anydpi-v26/ic_launcher.xml"):
    check(os.path.exists(os.path.join(RES, f)), f)

# ---- 2. 自适应图标引用三层 ----
print("\n2. 自适应图标引用三层")
adaptive = read("mipmap-anydpi-v26/ic_launcher.xml")
check("@drawable/ic_launcher_background" in adaptive, "引用 background")
check("@drawable/ic_launcher_foreground" in adaptive, "引用 foreground")
check("monochrome" in adaptive, "含 monochrome")

# ---- 3. 前景在安全区内 ----
print("\n3. 前景层落在 72dp 安全区内")
fg = read("drawable/ic_launcher_foreground.xml")
fpaths = path_data(fg)
check(len(fpaths) > 0, "前景层有 path")
maxr = max_radius(fpaths) if fpaths else 0
check(maxr <= 36.5, f"前景最远 {maxr:.2f}dp <= 36.5dp")

# ---- 4. monochrome 在安全区内 ----
print("\n4. monochrome 层落在安全区内")
mono = read("drawable/ic_launcher_monochrome.xml")
mpaths = path_data(mono)
check(len(mpaths) > 0, "monochrome 有 path")
mmaxr = max_radius(mpaths) if mpaths else 0
check(mmaxr <= 36.5, f"monochrome 最远 {mmaxr:.2f}dp <= 36.5dp")

# ---- 5. 背景是纯色圆底 ----
print("\n5. 背景层是纯色圆底（铺满画布）")
bg = read("drawable/ic_launcher_background.xml")
check("<gradient" not in bg, "不再使用渐变（改为纯色）")
check("android:fillColor" in bg, "有纯色填充")
check('android:fillColor="#A8F2CB"' in bg, "底色 = 主页 primaryContainer #A8F2CB")
check("M0,0h108v108h-108z" in bg, "纯色铺满 108dp 画布（外侧交给遮罩裁切）")

# ---- 6. 颜色取自主页 logo ----
print("\n6. 颜色取自主页 logo 的取色")
check("#A8F2CB" in bg, "圆底 = --pc（primaryContainer）")
check("#00210F" in fg, "羽毛描边 = --on-pc（onPrimaryContainer）")

# ---- 7. 前景只有描边羽毛（无外环）----
print("\n7. 前景层只有描边羽毛，没有外环")
check("a30.19" not in fg and "a30" not in fg, "不含外环圆弧（外环已去掉）")
check('android:strokeColor="#00210F"' in fg, "羽毛用主页的描边色 #00210F")
check('android:fillColor="#00000000"' in fg, "羽毛 fill 为空（描边线画）")
check('android:strokeLineCap="round"' in fg, "羽毛用圆角端帽")
check('android:strokeLineJoin="round"' in fg, "羽毛用圆角拐角")

# ---- 7b. 羽毛是多条描边路径 ----
print("\n7b. 羽毛是多条描边路径（羽片 + 羽轴 + 羽枝缝）")
for label, xml, color in (("前景层", fg, "#00210F"), ("单色层", mono, "#FFFFFF")):
    n_stroke = xml.count(f'android:strokeColor="{color}"')
    check(n_stroke >= 3, f"{label}有描边路径 {color}（{n_stroke} 条）")
    check(xml.count("M") >= 6, f"{label}含多个 M 子路径（{xml.count('M')} 个）")

# ---- 7c. 羽毛比例与大小 ----
print("\n7c. 羽毛比例与大小合理")
ratio, w, h = bbox_ratio(fpaths)
check(ratio >= 1.1, f"羽毛包围盒 {w:.1f}x{h:.1f}，长宽比 {ratio:.2f} >= 1.1")
reach = max_radius(fpaths)
check(20.0 <= reach <= 34.5, f"羽毛最远触达 {reach:.2f}dp，落在 20~34.5dp")

# ---- 8. PNG 齐备 ----
print("\n8. 五档 PNG 图标齐备")
for d in ("mipmap-mdpi", "mipmap-hdpi", "mipmap-xhdpi",
          "mipmap-xxhdpi", "mipmap-xxxhdpi"):
    check(os.path.exists(os.path.join(RES, d, "ic_launcher.png")), f"{d}/ic_launcher.png")

# ---- 9. 不再引用已删除的纯色 ----
print("\n9. 不再引用已删除的纯色资源")
colors = read("values/colors.xml")
check('name="ic_launcher_background"' not in colors,
      "colors.xml 不再定义 ic_launcher_background")
for f in ("mipmap-anydpi-v26/ic_launcher.xml",
          "drawable/ic_launcher_background.xml",
          "drawable/ic_launcher_foreground.xml"):
    check("@color/ic_launcher_background" not in read(f), f"{f} 不引用旧颜色")

print()
print("=" * 55)
if problems:
    print(f"{len(problems)} 个断言失败：")
    for p in problems:
        print("  ! " + p)
    raise SystemExit(1)
print("全部断言通过 —— LauncherIconTest 的断言与当前资源一致")
