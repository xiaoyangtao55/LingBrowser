"""把 Material Symbols 的 Android VectorDrawable 转成 Compose ImageVector 代码。

为什么要转换而不是直接用：
  1. 项目刻意不引入 material-icons-extended —— 它会把上万个图标编进 dex，
     debug 包体积 18 MB，与「翎」的极简定位相悖。
  2. 字体方案（Material Symbols TTF）需要引入 ttf 资源 + 运行时按码点取字形，
     每个 ttf 约 300 KB～1 MB，为 25 个图标背这个体积不划算，而且字体图标
     没法参与 Compose 的 tint/动画。
  3. 转成 ImageVector 后每个图标只有几百字节的 Kotlin 代码，且能被现有的
     LingIconsTest 直接检查（路径节点、无 arcTo 等约定）。

输入：Material Symbols 导出的 960x960 视口、纯 fill 的 VectorDrawable
输出：Compose `path { moveTo/lineTo/quadraticBezierTo/close }` 调用

坐标系处理：直接把 960 视口交给 ImageVector.Builder，不缩放到 24。
   缩放会引入浮点误差，且 960 是官方原始网格，保留它便于日后核对。
"""
import os
import re
import sys
import glob

SRC = r"C:\Users\QiYan\Downloads\material_icon"
# 官方未下载的 4 个图标由 synth_missing_icons.py 按 Material 官方规格合成，
# 放在这个子目录里；转换时优先查 SRC，找不到再查这里。
SYNTH = os.path.join(SRC, "_synthesized")
OUT = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "app", "src", "main", "java", "com", "ling", "browser", "ui", "theme",
    "LingIcons.kt",
)

SYNTHESIZED = set()   # 官方文件已补齐，不再需要合成

# 中间产物写到临时目录，不进源码树 —— 它是可再生的，
# 提交进仓库只会变成需要人工同步的第二份真相。
GEN_FILE = os.path.join(
    os.environ.get("TEMP", os.path.dirname(os.path.dirname(os.path.abspath(__file__)))),
    "ling_generated_icons.txt",
)


def find_source(file_name):
    """按 SRC -> SYNTH 顺序找图标文件。"""
    for d in (SRC, SYNTH):
        p = os.path.join(d, file_name + "_24px.xml")
        if os.path.exists(p):
            return p, (file_name in SYNTHESIZED)
    return None, False

# 图标名映射：Compose 里的名字 -> 下载的文件名（不含 _24px.xml）
# 找不到完全同名时用语义最接近的替代，并在注释里说明。
MAPPING = [
    ("ArrowBack", "arrow_back"),
    ("ArrowForward", "arrow_forward"),
    ("Close", "close"),
    ("Refresh", "refresh"),
    ("Home", "home"),
    ("Layers", "layers"),
    ("MoreVert", "more_vert"),
    ("Add", "add"),
    # 收藏相关的三个图标，容易搞混，按用途对齐：
    #   bookmark              空心书签           -> BookmarkBorder（未收藏状态）
    #   bookmark_added_fill   **实心**书签带加号  -> Bookmark（已收藏状态）
    #   bookmark_add          空心书签带加号     -> BookmarkAdd（"添加书签"动作）
    #
    # ⚠️ 陷阱：`bookmark_added`（不带 _fill）也是**空心**的，只比 bookmark
    # 多一个加号。区分"已收藏/未收藏"靠**填充**，不是有没有加号。
    #
    # 而"添加书签"这个**动作**必须用带加号的图标，否则它和下面那个
    # 纯"书签"入口（都指向书签列表）图标一模一样，用户分不出哪个是动作。
    ("BookmarkBorder", "bookmark"),
    ("Bookmark", "bookmark_added_fill"),
    ("BookmarkAdd", "bookmark_add"),
    ("History", "history"),
    ("DeleteOutline", "delete"),
    ("DeleteSweep", "delete_sweep"),
    ("Search", "search"),
    ("Download", "download"),
    ("Folder", "folder"),
    ("FolderOpen", "folder_open"),
    ("Lock", "lock"),
    ("Public", "public"),
    ("Nightlight", "nightlight"),
    ("AutoAwesome", "auto_awesome_motion"),
    ("Javascript", "javascript"),
    ("Image", "image"),
    ("PrivacyTip", "privacy_tip"),
    ("DesktopWindows", "desktop_windows"),
    ("Settings", "settings"),
    ("Article", "article"),
    ("Block", "block"),
]

# 这 4 个在用户下载的 30 个文件里不存在，需要单独补齐。
# 缺失时脚本会明确报错并列出，而不是悄悄用别的图标顶上 ——
# 那样做出来的界面看着"能用"，但造型与 Material 官方不一致，
# 事后极难发现（正是本轮要修的问题）。
REQUIRED = [n for n, _ in MAPPING]

NUM = re.compile(r"-?\d+(?:\.\d+)?")
TOKEN = re.compile(r"([MLQZmlqz])([^MLQZmlqz]*)")


def parse_path(data):
    """把 pathData 解析成 (命令, [数字...]) 列表。只支持 M/L/Q/Z。"""
    out = []
    for cmd, args in TOKEN.findall(data):
        nums = [float(n) for n in NUM.findall(args)]
        out.append((cmd.upper(), nums))
    return out


def fmt(v):
    """格式化数字：整数不带小数点，避免生成 240.0 这种噪音。"""
    if v == int(v):
        return str(int(v))
    return repr(round(v, 3))


def emit(commands, indent="            "):
    """生成 Compose PathBuilder 调用。"""
    lines = []
    i = 0
    for cmd, nums in commands:
        if cmd == "M":
            x, y = nums[0], nums[1]
            lines.append(f"{indent}moveTo({fmt(x)}f, {fmt(y)}f)")
            # M 后面若跟多组坐标，等价于 lineTo
            rest = nums[2:]
            for k in range(0, len(rest) - 1, 2):
                lines.append(f"{indent}lineTo({fmt(rest[k])}f, {fmt(rest[k+1])}f)")
        elif cmd == "L":
            for k in range(0, len(nums) - 1, 2):
                lines.append(f"{indent}lineTo({fmt(nums[k])}f, {fmt(nums[k+1])}f)")
        elif cmd == "Q":
            for k in range(0, len(nums) - 3, 4):
                # 方法名是 quadTo。踩过的坑：SVG pathData 里叫 Q，
                # 直觉会写成 quadraticTo / quadraticBezierTo，都不存在。
                # 完整列表用 javap 查 PathBuilder 确认：
                #   moveTo / lineTo / quadTo / curveTo / arcTo / close
                lines.append(
                    f"{indent}quadTo("
                    f"{fmt(nums[k])}f, {fmt(nums[k+1])}f, "
                    f"{fmt(nums[k+2])}f, {fmt(nums[k+3])}f)"
                )
        elif cmd == "Z":
            lines.append(f"{indent}close()")
        i += 1
    return lines


def main():
    if not os.path.isdir(SRC):
        sys.exit(f"找不到图标目录：{SRC}")

    problems = []
    blocks = []
    used = set()

    for compose_name, file_name in MAPPING:
        path, is_synth = find_source(file_name)
        if path is None:
            problems.append(f"{compose_name} -> {file_name}_24px.xml 不存在")
            continue
        used.add(file_name)
        src = open(path, encoding="utf-8").read()

        vp = re.search(r'android:viewportWidth="([^"]*)"', src)
        vh = re.search(r'android:viewportHeight="([^"]*)"', src)
        if not vp or not vh:
            problems.append(f"{file_name}: 缺 viewport")
            continue
        if vp.group(1) != vh.group(1):
            problems.append(f"{file_name}: viewport 非正方形 {vp.group(1)}x{vh.group(1)}")

        # 必须是纯 fill，出现 stroke 说明选错了导出版本
        if "strokeColor" in src or "strokeWidth" in src:
            problems.append(f"{file_name}: 含 stroke，本转换器只处理纯 fill 图标")

        datas = re.findall(r'android:pathData="([^"]*)"', src)
        if not datas:
            problems.append(f"{file_name}: 没有 pathData")
            continue

        all_cmds = []
        for d in datas:
            all_cmds.extend(parse_path(d))

        # 只允许 M/L/Q/Z —— 出现 C/A 等说明解析器没覆盖，必须显式报错而不是静默丢数据
        bad = {c for c, _ in all_cmds} - {"M", "L", "Q", "Z"}
        if bad:
            problems.append(f"{file_name}: 含未支持的命令 {sorted(bad)}")
            continue

        note = ""
        if compose_name == "DeleteOutline":
            note = "  // 官方 delete（实心垃圾桶）"
        elif compose_name == "AutoAwesome":
            note = "  // 官方 auto_awesome_motion（auto_awesome 未提供，语义相近）"
        if is_synth:
            note += ("  // 官方文件未提供，按 Material Symbols 规格合成"
                     "（见 tools/synth_missing_icons.py）")

        body = "\n".join(emit(all_cmds))
        src_tag = "官方" if not is_synth else "合成·按官方规格"
        blocks.append(
            f"    /** {compose_name}（Material Symbols Outlined: {file_name}，{src_tag}）。 */{note}\n"
            f"    val {compose_name}: ImageVector by lazy {{\n"
            f"        materialIcon(\"{compose_name}\") {{\n"
            f"{body}\n"
            f"        }}\n"
            f"    }}\n"
        )
        print(f"  {compose_name:20s} <- {file_name:22s} {len(all_cmds):4d} 条命令"
              + ("  [合成]" if is_synth else ""))

    extra = {os.path.basename(f).replace("_24px.xml", "")
             for f in glob.glob(os.path.join(SRC, "*.xml"))} - used

    print()
    if extra:
        print(f"下载了但未使用（{len(extra)} 个）：{sorted(extra)}")

    # 先写出已成功的部分，这样即使还缺图标也能立刻验证转换质量
    with open(GEN_FILE, "w", encoding="utf-8") as f:
        f.write("\n".join(blocks))
    print(f"\n已写出 {len(blocks)} 个图标到 {GEN_FILE}")

    if problems:
        print()
        print(f"{len(problems)} 个问题（需要补齐源文件）：")
        for p in problems:
            print("  ! " + p)
        missing = [p.split(" ")[0] for p in problems if "不存在" in p]
        if missing:
            print()
            print(f"请从 fonts.google.com 下载这些图标的 24px XML，"
                  f"放到 {SRC}：")
            for m in missing:
                print(f"    {m}  ->  需要 {[f for n, f in MAPPING if n == m][0]}_24px.xml")
        sys.exit(1)

    print(f"\n共 {len(blocks)} 个图标全部转换成功")
    print("接着运行 tools/emit_ling_icons.py 组装 LingIcons.kt")


if __name__ == "__main__":
    main()
