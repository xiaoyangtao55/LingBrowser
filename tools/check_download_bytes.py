"""校验体积格式化函数的两份实现完全一致。

背景：`formatBytes` 定义在 UI 层（DownloadsScreen.kt），而单元测试
不能直接引用它（会连带拉起 Compose 依赖），因此测试文件里复制了一份。

复制就有漂移风险 —— 改了 UI 那份、忘了测试那份，测试依然全绿，
但测的已经不是线上跑的代码。本脚本逐 token 比对两处实现体。
"""
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)

UI = os.path.join(
    ROOT, "app", "src", "main", "java", "com", "ling", "browser",
    "ui", "screens", "DownloadsScreen.kt",
)
TEST = os.path.join(
    ROOT, "app", "src", "test", "java", "com", "ling", "browser",
    "data", "db", "DownloadTest.kt",
)

problems = []


def bad(m):
    problems.append(m)
    print("  FAIL " + m)


def ok(m):
    print("  OK   " + m)


def normalize(src):
    """取出函数体并归一化：去注释、去空白、去类型标注差异。"""
    src = re.sub(r"//[^\n]*", "", src)
    src = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
    # 去掉函数签名那一段，只比主体
    lines = [ln.strip() for ln in src.split("\n")]
    lines = [ln for ln in lines if ln]
    return lines


def extract(src, name):
    """抓取 `fun name(...): String { ... }` 的函数体（按大括号配平）。"""
    m = re.search(r"fun\s+" + name + r"\s*\(", src)
    if not m:
        return None
    i = src.find("{", m.end())
    if i < 0:
        return None
    depth = 0
    start = i
    while i < len(src):
        if src[i] == "{":
            depth += 1
        elif src[i] == "}":
            depth -= 1
            if depth == 0:
                return src[start + 1:i]
        i += 1
    return None


print("体积格式化实现一致性检查")
print()

for label, path in (("UI 版", UI), ("测试版", TEST)):
    if not os.path.exists(path):
        bad(f"{label} 文件不存在：{path}")
if problems:
    sys.exit(1)

ui_src = open(UI, encoding="utf-8").read()
test_src = open(TEST, encoding="utf-8").read()

ui_body = extract(ui_src, "formatBytes")
test_body = extract(test_src, "format")

if ui_body is None:
    bad("DownloadsScreen.kt 里没找到 formatBytes")
if test_body is None:
    bad("DownloadTest.kt 里没找到 format")
if problems:
    print()
    for p in problems:
        print("  ! " + p)
    sys.exit(1)

ok("两处都找到实现")

# 归一化后逐行比对
ui_lines = normalize(ui_body)
test_lines = normalize(test_body)

if ui_lines == test_lines:
    ok(f"实现体逐行一致（{len(ui_lines)} 行）")
else:
    bad("两份实现已经不一致 —— 测试测的不再是线上代码")
    only_ui = [l for l in ui_lines if l not in test_lines]
    only_test = [l for l in test_lines if l not in ui_lines]
    for l in only_ui[:6]:
        bad(f"  仅 UI 版有：{l}")
    for l in only_test[:6]:
        bad(f"  仅测试版有：{l}")

# 关键常量：1024 进制不能变成 1000
if "1000" in ui_body.replace("1024", ""):
    bad("formatBytes 里出现了 1000，可能被误改成十进制")
else:
    ok("使用 1024 进制")

if 'units.lastIndex' in ui_body:
    ok("单位索引有边界保护（不会越界）")
else:
    bad("缺 units.lastIndex 边界判断，超大体积会索引越界")

# 测试确实覆盖了边界
for case in ("1023 B", "1.0 KB", "TB"):
    if case in test_src:
        ok(f"测试覆盖 {case}")
    else:
        bad(f"测试缺少 {case} 的用例")

print()
print("=" * 55)
if problems:
    print(f"{len(problems)} 个问题：")
    for p in problems:
        print("  ! " + p)
    sys.exit(1)
print("体积格式化一致性校验通过")
