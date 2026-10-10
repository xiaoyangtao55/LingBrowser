"""静态检查 Kotlin 源码：在无法编译的环境下尽量提前发现问题。

这不是编译器，但它能抓住重构中最容易犯的几类错误：
  1. 花括号/圆括号配平（剔除注释与字符串后逐字符扫描）
  2. import 了但没用到的符号
  3. 本工程自定义 composable 的**调用点**是否漏传必需具名参数
  4. 重构后应消失的旧 API 是否还有残留

关于"调用点"识别：必须排除函数声明本身。做法是先找出所有
`fun Name(` 的位置，检查实参时跳过这些位置。
"""
import os
import re
import sys

ROOT = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "app", "src",
)

# 重构后不应再出现的符号（出现即说明有地方漏改）
# 注意：允许出现在 HomePage.kt 里的兼容常量与测试里
FORBIDDEN = {
    "action=\"about:home\"": "旧主页地址",
}

# 这些 import 在本工程里允许"看起来没用"（扩展函数/运算符/委托）
IMPORT_WHITELIST_SUFFIX = (
    ".getValue",
    ".setValue",
    ".update",
    "kotlin.math.",
    ".Color",
)

# 本工程自定义 composable 的必需具名参数（重构重点）
SIGNATURES = {
    "BottomToolbar": ["canGoBack", "moreExpanded", "onMoreToggle", "onMoreDismiss"],
    "AddressBar": ["onReloadOrStop", "suggestions", "onSuggestionClick"],
    "TabsScreen": ["tabsHeight", "onTabsHeightChange"],
    "SettingsScreen": ["onTabsHeight"],
}


def kotlin_files():
    for base, _, files in os.walk(ROOT):
        for f in files:
            if f.endswith(".kt"):
                yield os.path.join(base, f)


def strip_comments_and_strings(code, keep_strings=False):
    """剔除注释与字符串字面量，返回等长的占位文本（保持行号不变）。

    `keep_strings=True` 时保留字符串内容（注释仍然剔除）。import 检查要用这个模式：
    Kotlin 的字符串模板 `${UrlUtils.hostOf(url)}` 是**会执行**的代码，
    把整段字符串抹掉会让这些符号被误判成"import 未使用"。
    """
    out = []
    i = 0
    n = len(code)
    while i < n:
        # 行注释
        if code.startswith("//", i):
            j = code.find("\n", i)
            j = n if j < 0 else j
            out.append(" " * (j - i))
            i = j
        # 块注释
        elif code.startswith("/*", i):
            j = code.find("*/", i + 2)
            j = n if j < 0 else j + 2
            chunk = code[i:j]
            out.append("".join(c if c == "\n" else " " for c in chunk))
            i = j
        # 原始字符串 """
        elif code.startswith('"""', i):
            j = code.find('"""', i + 3)
            j = n if j < 0 else j + 3
            chunk = code[i:j]
            out.append(chunk if keep_strings
                       else "".join(c if c == "\n" else " " for c in chunk))
            i = j
        # 普通字符串
        elif code[i] == '"':
            j = i + 1
            while j < n and code[j] != '"':
                j += 2 if code[j] == "\\" else 1
            j = min(j + 1, n)
            out.append(code[i:j] if keep_strings else " " * (j - i))
            i = j
        # 字符字面量
        elif code[i] == "'":
            j = i + 1
            while j < n and code[j] != "'":
                j += 2 if code[j] == "\\" else 1
            j = min(j + 1, n)
            out.append(" " * (j - i))
            i = j
        else:
            out.append(code[i])
            i += 1
    return "".join(out)


def check_balance(rel, raw, problems):
    code = strip_comments_and_strings(raw)
    pairs = {")": "(", "]": "[", "}": "{"}
    stack = []
    line = 1
    for ch in code:
        if ch == "\n":
            line += 1
        elif ch in "([{":
            stack.append((ch, line))
        elif ch in ")]}":
            if not stack:
                problems.append(f"{rel}:{line} 多余的 '{ch}'")
                return
            open_ch, open_line = stack.pop()
            if open_ch != pairs[ch]:
                problems.append(
                    f"{rel}:{line} '{ch}' 与第 {open_line} 行的 '{open_ch}' 不匹配"
                )
                return
    if stack:
        ch, ln = stack[-1]
        problems.append(f"{rel}: 第 {ln} 行的 '{ch}' 未闭合（剩 {len(stack)} 个）")


def check_imports(rel, raw, problems):
    # 保留字符串内容：字符串模板里的符号是真在用的（见 strip_comments_and_strings）
    code = strip_comments_and_strings(raw, keep_strings=True)
    imports = re.findall(r"^import\s+([\w.]+)(?:\s+as\s+(\w+))?", raw, flags=re.M)
    body = "\n".join(
        l for l in code.splitlines()
        if not l.lstrip().startswith(("import ", "package "))
    )
    for full, alias in imports:
        if any(full.endswith(s) or s in full for s in IMPORT_WHITELIST_SUFFIX):
            continue
        simple = alias or full.split(".")[-1]
        if not re.search(r"\b" + re.escape(simple) + r"\b", body):
            problems.append(f"{rel}: import 未使用 -> {full}")


def declaration_offsets(code, name):
    """`fun Name(` 或 `fun <T> Name(` 的 '(' 位置集合，用于跳过声明。"""
    offsets = set()
    for m in re.finditer(r"\bfun\s+(?:<[^>]*>\s+)?(?:\w+\.)?" + re.escape(name) + r"\s*\(", code):
        offsets.add(m.end() - 1)
    return offsets


def extract_args(code, open_paren):
    """从 '(' 处提取配平的实参文本。"""
    depth = 0
    i = open_paren
    while i < len(code):
        if code[i] == "(":
            depth += 1
        elif code[i] == ")":
            depth -= 1
            if depth == 0:
                return code[open_paren + 1:i]
        i += 1
    return ""


def check_calls(rel, raw, problems):
    code = strip_comments_and_strings(raw)
    for name, required in SIGNATURES.items():
        decls = declaration_offsets(code, name)
        for m in re.finditer(r"\b" + re.escape(name) + r"\s*\(", code):
            open_paren = m.end() - 1
            if open_paren in decls:
                continue  # 这是函数声明，不是调用
            args = extract_args(code, open_paren)
            missing = [
                r for r in required
                if not re.search(r"\b" + re.escape(r) + r"\s*=", args)
            ]
            if missing:
                line = code[:m.start()].count("\n") + 1
                problems.append(
                    f"{rel}:{line} 调用 {name}() 缺少具名参数 {missing}"
                )


# 形参是**非空** String 的函数：实参里出现安全调用（`?.`）就会编译不过。
#
# 这一条是 CI 真实抓到的错误促成的（commit fba6599）：
#   `if (UrlUtils.isHome(activeTab()?.url))` —— isHome(url: String) 是非空形参。
# "括号配平 / import 一致 / 具名参数完整"三项都发现不了它，18 个脚本全绿而 CI 红。
# 判据：实参里有 `?.`，且**没有**兜底（`?:` 或 `!!`）—— 有兜底时类型已经非空，
# 报出来就是误报，而"没人看的检查等于没有检查"。
NON_NULL_STRING_PARAMS = (
    "UrlUtils.isHome",
    "UrlUtils.prettify",
    "UrlUtils.hostOf",
    "UrlUtils.toUrl",
    "UrlScheme.isNavigable",
    "UrlScheme.shouldTakeOver",
)


def check_nullable_args(rel, raw, problems):
    code = strip_comments_and_strings(raw)
    for fn in NON_NULL_STRING_PARAMS:
        for m in re.finditer(re.escape(fn) + r"\s*\(", code):
            args = extract_args(code, m.end() - 1)
            if "?." in args and "?:" not in args and "!!" not in args:
                line = code[:m.start()].count("\n") + 1
                problems.append(
                    f"{rel}:{line} {fn}() 的实参是可空链（`?.`）却没有兜底 —— "
                    f"该形参是非空 String，编译会报 type mismatch"
                )


def main():
    problems = []
    files = list(kotlin_files())
    print(f"扫描 {len(files)} 个 Kotlin 文件\n")

    for path in files:
        rel = os.path.relpath(path, ROOT)
        with open(path, encoding="utf-8") as fh:
            raw = fh.read()
        check_balance(rel, raw, problems)
        if os.sep + "test" + os.sep not in rel:
            check_imports(rel, raw, problems)
            check_calls(rel, raw, problems)
            check_nullable_args(rel, raw, problems)
        for bad, why in FORBIDDEN.items():
            if bad in raw:
                problems.append(f"{rel}: 残留 `{bad}` —— {why}")

    if problems:
        print(f"发现 {len(problems)} 个问题：\n")
        for p in problems:
            print("  ! " + p)
        return 1
    print("静态检查通过：括号配平、import 一致、调用参数完整、可空实参、无残留旧 API。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
