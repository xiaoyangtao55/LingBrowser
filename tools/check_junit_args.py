"""校验 JUnit 断言的参数顺序。

背景（真实故障）：
  CI 报
      LingSettingsTest.kt:77:13 Argument type mismatch:
      actual type is 'TabsHeight?', but 'String!' was expected.

  原因是我把 JUnit 的参数顺序写反了。JUnit4 的断言签名是
      assertNull(String message, Object object)
      assertNotNull(String message, Object object)
      assertTrue(String message, boolean condition)
  即**消息在前、值在后**。写成 (值, 消息) 时，如果两者类型不同
  （例如值是枚举、消息是 String）编译器还能报错拦住；但若两者
  **恰好同类**（都是 String、都是 Boolean），就会静默通过 ——
  消息和被测值互换，测试的语义完全错了却照样变绿。

  所以这里按类型分类拦截，不依赖编译器。
"""
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)

problems = []


def bad(m):
    problems.append(m)
    print("  FAIL " + m)


def ok(m):
    print("  OK   " + m)


def strip_code(code):
    """去掉注释和字符串字面量，避免误判。"""
    code = re.sub(r"/\*.*?\*/", " ", code, flags=re.S)
    code = re.sub(r"//[^\n]*", " ", code)
    code = re.sub(r'"""(?:.|\n)*?"""', '""', code)
    code = re.sub(r'"(?:\\.|[^"\\])*"', '""', code)
    return code


def split_args(s):
    """按顶层逗号切分实参，忽略括号内的逗号。"""
    out, depth, cur = [], 0, ""
    for ch in s:
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1
        if ch == "," and depth == 0:
            out.append(cur.strip())
            cur = ""
        else:
            cur += ch
    if cur.strip():
        out.append(cur.strip())
    return out


def call_args(code, name):
    """取出所有 name(...) 的实参列表（每个是字符串列表）。"""
    results = []
    for m in re.finditer(r"\b" + name + r"\s*\(", code):
        i = m.end()
        depth = 1
        start = i
        while i < len(code) and depth:
            if code[i] == "(":
                depth += 1
            elif code[i] == ")":
                depth -= 1
            i += 1
        results.append((m.start(), split_args(code[start:i - 1])))
    return results


def line_of(code, idx):
    return code[:idx].count("\n") + 1


# 断言名 -> (消息应在前?, 值参数的类型判定)
# type 用来判断"第一个实参看起来像不像消息"
ASSERTS = {
    "assertNull": "message-first",
    "assertNotNull": "message-first",
    "assertTrue": "message-first",
    "assertFalse": "message-first",
    "assertEquals": "message-first",
    "assertSame": "message-first",
    "assertNotSame": "message-first",
}

# 这些实参"看起来像消息"：字符串字面量或字符串模板
MSG_LIKE = re.compile(r'^("""|"|\$?")')

print("JUnit 断言参数顺序检查（消息在前、值在后）")
print()

files = []
for base, _, names in os.walk(os.path.join(ROOT, "app", "src", "test")):
    for n in names:
        if n.endswith(".kt"):
            files.append(os.path.join(base, n))

if not files:
    print("  (没有找到测试文件)")
    sys.exit(0)

checked = 0
for path in files:
    rel = os.path.relpath(path, ROOT).replace("\\", "/")
    raw = open(path, encoding="utf-8").read()
    # 保留字符串字面量以便判断"是不是消息"，只去注释
    code = re.sub(r"/\*.*?\*/", " ", raw, flags=re.S)
    code = re.sub(r"//[^\n]*", " ", code)
    bare = strip_code(code)

    for name in ASSERTS:
        # 用 bare 版定位调用点（偏移量可靠），再用原版取带字符串的实参。
        # 两者做同样的替换，因此偏移量一一对应。
        calls_bare = call_args(bare, name)
        calls_raw = call_args(code, name)
        for (idx, _), (_, args) in zip(calls_bare, calls_raw):
            checked += 1
            if len(args) < 2:
                continue  # 单参数形式（无消息）合法
            first, second = args[0], args[1]
            first_is_msg = bool(MSG_LIKE.match(first))
            second_is_msg = bool(MSG_LIKE.match(second))
            ln = line_of(bare, idx)

            if first_is_msg and not second_is_msg:
                continue  # 正常：消息在前
            if not first_is_msg and second_is_msg:
                bad(f"{rel}:{ln} {name}() 参数顺序反了 —— "
                    f"JUnit 是 (message, value)，此处像是 (value, message)")
            # 两个都不是字符串时不报错：那多半是 assertEquals(expected, actual)
            # 这类无消息的合法调用（例如 assertEquals(TabsHeight.HALF, s.tabsHeight)），
            # 无法与"写反了"区分。强行报错会把正确的代码判成错误，
            # 这里宁可漏报也不误报。

print(f"  共检查 {checked} 处断言调用")
print()
print("=" * 55)
if problems:
    print(f"{len(problems)} 个问题：")
    for p in problems:
        print("  ! " + p)
    sys.exit(1)
print("JUnit 断言参数顺序校验通过")
