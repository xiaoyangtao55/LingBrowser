"""交叉核对：本工程自定义 composable 的"调用点具名参数"是否都存在于声明里。

这是静态检查里最有价值的一项 —— 重构时最容易犯的错就是
改了函数签名却漏改某个调用点，编译器会报错，但这里能提前发现。

做法：
  1. 扫描所有 `fun Name(...)` 声明，解析出参数名集合
  2. 扫描所有 `Name(` 调用点，取出具名实参名
  3. 报告"调用点传了声明里没有的参数"以及"声明有必填参数但调用点没传"
"""
import os
import re
import sys

ROOT = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "app", "src", "main", "java",
)


def strip_code(code):
    out = []
    i, n = 0, len(code)
    while i < n:
        if code.startswith("//", i):
            j = code.find("\n", i)
            j = n if j < 0 else j
            out.append(" " * (j - i)); i = j
        elif code.startswith("/*", i):
            j = code.find("*/", i + 2)
            j = n if j < 0 else j + 2
            out.append("".join(c if c == "\n" else " " for c in code[i:j])); i = j
        elif code.startswith('"""', i):
            j = code.find('"""', i + 3)
            j = n if j < 0 else j + 3
            out.append("".join(c if c == "\n" else " " for c in code[i:j])); i = j
        elif code[i] == '"':
            j = i + 1
            while j < n and code[j] != '"':
                j += 2 if code[j] == "\\" else 1
            j = min(j + 1, n)
            out.append(" " * (j - i)); i = j
        else:
            out.append(code[i]); i += 1
    return "".join(out)


def balanced_slice(code, start, open_ch="(", close_ch=")"):
    depth = 0
    i = start
    while i < len(code):
        if code[i] == open_ch:
            depth += 1
        elif code[i] == close_ch:
            depth -= 1
            if depth == 0:
                return code[start + 1:i]
        i += 1
    return ""


def split_top_level(text):
    """按顶层逗号切分。

    注意：不能把 '<' '>' 当作括号深度 —— 泛型、比较运算符、函数类型
    `(Boolean) -> Unit` 都会误伤。只跟踪 () [] {} 三种真括号。
    """
    parts, depth, cur = [], 0, ""
    for ch in text:
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1
        if ch == "," and depth == 0:
            parts.append(cur); cur = ""
        else:
            cur += ch
    if cur.strip():
        parts.append(cur)
    return [p.strip() for p in parts if p.strip()]


def strip_annotations(text):
    """移除参数上的注解，如 @Composable 和 @Suppress("x")。"""
    out, i, n = [], 0, len(text)
    while i < n:
        if text[i] == "@":
            m = re.match(r"@\w+", text[i:])
            if m:
                i += m.end()
                # 跳过注解自带的实参
                while i < n and text[i] in " \t\n":
                    i += 1
                if i < n and text[i] == "(":
                    depth = 0
                    while i < n:
                        if text[i] == "(":
                            depth += 1
                        elif text[i] == ")":
                            depth -= 1
                            if depth == 0:
                                i += 1
                                break
                        i += 1
                continue
        out.append(text[i]); i += 1
    return "".join(out)


def parse_decl(text):
    """解析声明参数，返回 (参数名集合, 必填参数名集合)。"""
    names, required = set(), set()
    for p in split_top_level(strip_annotations(text)):
        m = re.match(r"(?:vararg\s+)?(\w+)\s*:", p)
        if not m:
            continue
        name = m.group(1)
        names.add(name)
        # 顶层出现 '=' 即为有默认值（可选参数）
        if "=" not in p:
            required.add(name)
    return names, required


def main():
    files = []
    for base, _, fs in os.walk(ROOT):
        for f in fs:
            if f.endswith(".kt"):
                files.append(os.path.join(base, f))

    # 声明按「文件 -> 函数名」建立索引，避免不同文件里的同名函数互相覆盖。
    # 每个函数还会被登记到全局表里，因为跨文件调用很常见。
    per_file = {}     # path -> {name: (all, required)}
    global_decls = {} # name -> (all, required)，同名冲突时保留参数最多的那份
    sources = {}

    for path in files:
        raw = open(path, encoding="utf-8").read()
        code = strip_code(raw)
        sources[path] = code
        local = {}
        for m in re.finditer(r"\bfun\s+(?:<[^>]*>\s+)?(\w+)\s*\(", code):
            name = m.group(1)
            params = balanced_slice(code, m.end() - 1)
            parsed = parse_decl(params)
            local[name] = parsed
            prev = global_decls.get(name)
            if prev is None or len(parsed[0]) > len(prev[0]):
                global_decls[name] = parsed
        per_file[path] = local

    problems = []

    for path, code in sources.items():
        rel = os.path.basename(path)
        local = per_file[path]
        # 检查本文件里定义的（或全局已知的）参数较多的函数
        candidates = {
            n for n, (a, _) in local.items() if len(a) >= 4
        } | {
            n for n, (a, _) in global_decls.items() if len(a) >= 5
        }

        for name in candidates:
            allp, req = local.get(name) or global_decls[name]
            decl_pos = {
                m.end() - 1
                for m in re.finditer(
                    r"\bfun\s+(?:<[^>]*>\s+)?" + re.escape(name) + r"\s*\(", code
                )
            }
            for m in re.finditer(r"\b" + re.escape(name) + r"\s*\(", code):
                op = m.end() - 1
                if op in decl_pos:
                    continue
                args = balanced_slice(code, op)
                passed = set()
                for a in split_top_level(args):
                    am = re.match(r"(\w+)\s*=", a)
                    if am:
                        passed.add(am.group(1))

                # 只在"本文件定义了该函数"时才判定未知参数，
                # 否则可能因为同名函数而误报。
                if name in local:
                    unknown = passed - allp
                    if unknown:
                        line = code[:m.start()].count("\n") + 1
                        problems.append(
                            f"{rel}:{line} {name}() 传入了声明中不存在的参数 {sorted(unknown)}"
                        )

                positional = [a for a in split_top_level(args) if not re.match(r"\w+\s*=", a)]
                missing = {x for x in req if x not in passed}
                if name in local and len(positional) < len(missing):
                    line = code[:m.start()].count("\n") + 1
                    problems.append(
                        f"{rel}:{line} {name}() 可能缺少必填参数 {sorted(missing)}"
                    )

    if problems:
        print(f"发现 {len(problems)} 个调用点问题：\n")
        for p in sorted(set(problems)):
            print("  ! " + p)
        return 1
    print("核对了自定义函数的调用点：参数名与必填项全部匹配。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
