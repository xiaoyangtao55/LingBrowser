"""检查「定义了但从未被调用」的公开函数（死代码）。

背景：这个项目里同一个坑踩了两次 ——
  1. `HomePage.prefersDarkCss()` 定义了却从未被 HTML 调用，
     导致主页的 dark 参数完全不起作用；
  2. `LingWebView.injectDarkMode()` 定义了却从未被调用，
     导致强制网页夜间模式的 CSS 兜底从未生效。

两次都是「主页/网页不跟随夜间模式」这类难查的问题，而且更糟的是：
单元测试**直接调用**了它们，于是测试全绿，给人"这块有覆盖"的错觉，
线上却根本没走这条路。

设计取舍 —— 宁可漏报，不可误报：
  - 只检查名字以特定前缀开头、语义明确的「动作型」函数
    （inject*/prefers*/apply* 等），不检查 set*/get*/to* 这类
    会被当函数引用传递、或作为扩展函数调用的名字。
  - 上一版把 `c.toBookmark()`、`viewModel::setNightMode` 全判成
    死代码，产生 30 条误报 —— 那样的检查没人会看，等于没有。
"""
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
MAIN = os.path.join(ROOT, "app", "src", "main", "java")
TEST = os.path.join(ROOT, "app", "src", "test", "java")

problems = []


def bad(m):
    problems.append(m)
    print("  FAIL " + m)


def ok(m):
    print("  OK   " + m)


def strip_code(code):
    code = re.sub(r"/\*.*?\*/", " ", code, flags=re.S)
    code = re.sub(r"//[^\n]*", " ", code)
    code = re.sub(r'"""(?:.|\n)*?"""', '""', code)
    code = re.sub(r'"(?:\\.|[^"\\])*"', '""', code)
    return code


def collect(root):
    out = {}
    for base, _, names in os.walk(root):
        for n in names:
            if n.endswith(".kt"):
                p = os.path.join(base, n)
                out[p] = strip_code(open(p, encoding="utf-8").read())
    return out


main_files = collect(MAIN)
test_src = "\n".join(collect(TEST).values()) if os.path.isdir(TEST) else ""

# 只检查这些前缀：语义明确「必须有人调用才生效」的动作函数。
# 不含 set*/get*/to*/is*/has* —— 它们常以 `::setNightMode` 这种
# 函数引用形式传递，或作为扩展函数以 `x.toY()` 形式调用，
# 静态文本匹配抓不到，会制造大量误报。
WATCH_PREFIXES = (
    "inject", "prefers", "ensure", "apply", "render", "sync", "refresh",
    "load", "attach", "detach", "enforce",
)

# 明确豁免：由框架回调、外部入口或测试驱动
WHITELIST = {
    "applySettings",     # 由 WebTabManager 调用（同名多处，匹配复杂）
    "applyHomeTheme",    # 由 BrowserScreen 调用
    "attachHost", "detachHost",  # 由 BrowserScreen 的 DisposableEffect
    "loadUrl", "loadHome",       # 同上，调用点分散
}
# 注意：不要为了"消除告警"随手往这里加名字。
# refreshHome 曾一度被加进来，结果 ensureHomeRendered 的缺失被掩盖 ——
# 白名单本身就是这个检查最大的失效来源。

print("死代码检查：动作型函数是否有 main 调用点")
print()

DEF_RE = re.compile(
    r"fun\s+(?:<[^>]+>\s*)?(\w+)\s*\(",
)

defs = {}
for path, src in main_files.items():
    rel = os.path.relpath(path, ROOT).replace("\\", "/")
    for m in DEF_RE.finditer(src):
        name = m.group(1)
        if not name.startswith(WATCH_PREFIXES):
            continue
        if name in WHITELIST:
            continue
        # 跳过 private
        line_start = src.rfind("\n", 0, m.start()) + 1
        if "private" in src[line_start:m.start()]:
            continue
        defs.setdefault(name, []).append((rel, src[:m.start()].count("\n") + 1))

if not defs:
    print("  (没有找到需要检查的函数)")
    sys.exit(0)

checked = 0
for name, places in sorted(defs.items()):
    # 统计调用点（排除定义行）。三种调用形式都要认：
    #   1. 裸调用          foo(...)
    #   2. 限定接收者调用  x.foo(...)      ← 上一版漏了这种，误报 injectDarkMode
    #   3. 函数引用        ::foo           ← 漏了这种，误报 refreshDownloads
    calls = 0
    pattern = re.compile(
        r"(?:(?<![\w.])(?:::)?" + re.escape(name) + r"\s*\()"  # 裸调用 / ::foo
        r"|(?:\.\s*" + re.escape(name) + r"\s*\()"             # x.foo(
        r"|(?:::" + re.escape(name) + r"(?![\w(]))"            # viewModel::foo
    )
    for src in main_files.values():
        for m in pattern.finditer(src):
            line_start = src.rfind("\n", 0, m.start()) + 1
            # 定义行：`fun name(`
            if re.search(r"fun\s+\w*\s*$", src[line_start:m.start()]):
                continue
            calls += 1
    checked += 1
    if calls == 0:
        in_test = bool(re.search(r"(?<![\w.])" + re.escape(name) + r"\s*\(", test_src))
        loc = f"{places[0][0]}:{places[0][1]}"
        if in_test:
            bad(f"{loc} {name}() 在 main 中没有调用点，只在测试里被调用 "
                f"—— 测试全绿但线上不走这条路")
        else:
            bad(f"{loc} {name}() 没有任何调用点")

print(f"  共检查 {checked} 个动作型函数")
print()
print("=" * 55)
if problems:
    print(f"{len(problems)} 个问题：")
    for p in problems:
        print("  ! " + p)
    sys.exit(1)
print("死代码检查通过")
