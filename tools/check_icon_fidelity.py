"""校验 LingIcons.kt 里的每个图标与源文件逐点一致。

为什么需要这个：图标是**生成**的，但生成物一旦提交进仓库，就有了
「源文件改了、生成物没重新生成」的漂移风险。而图标漂移极难用肉眼
发现 —— 少一个曲率控制点、坐标差 40，渲染出来看着都"差不多"，只有
放大对比才看得出来。

本脚本反过来做：重新解析一遍源文件的 pathData，与 LingIcons.kt 里
实际写着的坐标逐个比对，完全一致才通过。

顺带校验：
  - 每个图标都通过 materialIcon() 构建（统一视口与填充色）
  - 源文件里不含 M/L/Q/Z 以外的命令（转换器不支持会静默丢数据）
  - 曲线控制点数量与源文件一致（防止 Q 被降级成 L）
"""
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
KOTLIN = os.path.join(
    ROOT, "app", "src", "main", "java", "com", "ling", "browser",
    "ui", "theme", "LingIcons.kt",
)
SRC = r"C:\Users\QiYan\Downloads\material_icon"
SYNTH = os.path.join(SRC, "_synthesized")

problems = []


def bad(m):
    problems.append(m)
    print("  FAIL " + m)


def ok(m):
    print("  OK   " + m)


# Compose 里的名字 -> 源文件名
MAPPING = [
    ("ArrowBack", "arrow_back"), ("ArrowForward", "arrow_forward"),
    ("Close", "close"), ("Refresh", "refresh"), ("Home", "home"),
    ("Layers", "layers"), ("MoreVert", "more_vert"), ("Add", "add"),
    ("BookmarkBorder", "bookmark"), ("Bookmark", "bookmark_added_fill"),
    ("BookmarkAdd", "bookmark_add"),
    ("History", "history"), ("DeleteOutline", "delete"),
    ("DeleteSweep", "delete_sweep"), ("Search", "search"),
    ("Download", "download"), ("Folder", "folder"),
    ("FolderOpen", "folder_open"), ("Lock", "lock"), ("Public", "public"),
    ("Nightlight", "nightlight"), ("AutoAwesome", "auto_awesome_motion"),
    ("Javascript", "javascript"), ("Image", "image"),
    ("PrivacyTip", "privacy_tip"), ("DesktopWindows", "desktop_windows"),
    ("Settings", "settings"),
]

NUM = re.compile(r"-?\d+(?:\.\d+)?")


def find(file_name):
    for d in (SRC, SYNTH):
        p = os.path.join(d, file_name + "_24px.xml")
        if os.path.exists(p):
            return p
    return None


def source_points(path):
    """把源文件的 pathData 拆成纯数字序列（忽略命令字母）。"""
    s = open(path, encoding="utf-8").read()
    datas = re.findall(r'android:pathData="([^"]*)"', s)
    pts = []
    for d in datas:
        pts += [float(x) for x in NUM.findall(d)]
    return pts


def kotlin_points(kotlin, name):
    """从 LingIcons.kt 里取出指定图标的坐标序列。"""
    m = re.search(
        r"val " + re.escape(name) + r": ImageVector by lazy \{\s*"
        r"materialIcon\(\"" + re.escape(name) + r"\"\) \{(.*?)\n        \}",
        kotlin, re.S,
    )
    if not m:
        return None
    body = m.group(1)
    pts = []
    for cm in re.finditer(r"(?:moveTo|lineTo|quadTo)\(([^)]*)\)", body):
        pts += [float(x.strip().rstrip("f")) for x in cm.group(1).split(",")
                if x.strip()]
    return pts


def main():
    if not os.path.exists(KOTLIN):
        sys.exit(f"找不到 {KOTLIN}")
    kotlin = open(KOTLIN, encoding="utf-8").read()

    print("图标与源文件一致性校验")
    print()

    n_ok = 0
    for name, fname in MAPPING:
        path = find(fname)
        if path is None:
            bad(f"{name}: 源文件 {fname}_24px.xml 不存在")
            continue

        # 源文件命令白名单
        s = open(path, encoding="utf-8").read()
        for d in re.findall(r'android:pathData="([^"]*)"', s):
            cmds = set(re.findall(r"[A-Za-z]", d))
            extra = cmds - {"M", "L", "Q", "Z"}
            if extra:
                bad(f"{fname}: 源文件含不支持的命令 {sorted(extra)}")

        want = source_points(path)
        got = kotlin_points(kotlin, name)
        if got is None:
            bad(f"{name}: LingIcons.kt 里找不到该图标")
            continue

        if len(want) != len(got):
            bad(f"{name}: 坐标数量不符 源={len(want)} 生成={len(got)}")
            continue

        diffs = [(i, a, b) for i, (a, b) in enumerate(zip(want, got))
                 if abs(a - b) > 1e-6]
        if diffs:
            i, a, b = diffs[0]
            bad(f"{name}: {len(diffs)} 处坐标不一致，首个 "
                f"#{i} 源={a} 生成={b}")
            continue

        # Q 命令数量必须一致，防止曲线被降级成直线
        want_q = sum(len(re.findall(r"[Qq]", d))
                     for d in re.findall(r'android:pathData="([^"]*)"', s))
        want_l = sum(len(re.findall(r"[Ll]", d))
                     for d in re.findall(r'android:pathData="([^"]*)"', s))
        body = re.search(
            r"val " + re.escape(name) + r": ImageVector by lazy \{(.*?)\n        \}",
            kotlin, re.S,
        ).group(1)
        got_q = len(re.findall(r"quadTo\(", body))
        got_l = len(re.findall(r"lineTo\(", body))
        if want_q != got_q:
            bad(f"{name}: 曲线段数不符 源Q={want_q} 生成quadTo={got_q}")
            continue
        if want_l != got_l:
            bad(f"{name}: 直线段数不符 源L={want_l} 生成lineTo={got_l}")
            continue

        n_ok += 1

    ok(f"{n_ok}/{len(MAPPING)} 个图标与源文件逐点一致")

    # 统一构建入口
    if "private fun materialIcon(" in kotlin:
        ok("使用统一的 materialIcon() 构建入口")
    else:
        bad("缺少 materialIcon() 统一构建入口")

    if "viewportWidth = 960f" in kotlin and "viewportHeight = 960f" in kotlin:
        ok("视口为官方 960x960")
    else:
        bad("视口不是 960x960")

    if "arcTo(" in kotlin:
        bad("出现了 arcTo —— 官方源文件只有 M/L/Q/Z，不该有弧线指令")
    else:
        ok("未使用 arcTo")

    print()
    print("=" * 55)
    if problems:
        print(f"{len(problems)} 个问题：")
        for p in problems:
            print("  ! " + p)
        sys.exit(1)
    print("图标一致性校验通过")


if __name__ == "__main__":
    main()
