"""把 HomePage.kt 里的 HTML 模板实例化并做结构/样式自检。

因为设备编译太慢，这里直接从 Kotlin 源码抽取 HTML 模板，
替换掉 $变量 后交给解析器检查，能在不编译的前提下发现：
  - 标签未闭合
  - SVG 属性里误用 var()
  - 引用了不存在的外部资源
  - CSS 变量定义了但没用 / 用了但没定义
"""
import os
import re
import html.parser
import sys

KOTLIN = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "app", "src", "main", "java", "com", "ling", "browser", "web", "HomePage.kt",
)

SAMPLE = {
    "background": "#FFFBFE",
    "onBackground": "#1C1B1F",
    "primary": "#1B6C4B",
    "onPrimary": "#FFFFFF",
    "primaryContainer": "#A8F2CB",
    "onPrimaryContainer": "#00210F",
    "dark": "false",
    "quickLinks": (
        '<div class="links" id="ling-quick-links">'
        '<a class="link" href="https://www.baidu.com">'
        '<span class="avatar">百</span>'
        '<span class="link-label">百度</span></a>'
        "</div>"
    ),
}


class Checker(html.parser.HTMLParser):
    VOID = {"meta", "br", "hr", "img", "input", "link", "source"}

    def __init__(self):
        super().__init__()
        self.stack = []
        self.errors = []
        self.tags = []

    def handle_starttag(self, tag, attrs):
        self.tags.append(tag)
        if tag not in self.VOID:
            self.stack.append((tag, self.getpos()[0]))

    def handle_startendtag(self, tag, attrs):
        self.tags.append(tag)

    def handle_endtag(self, tag):
        if tag in self.VOID:
            return
        if not self.stack:
            self.errors.append(f"第 {self.getpos()[0]} 行：多余的 </{tag}>")
            return
        top, line = self.stack.pop()
        if top != tag:
            self.errors.append(
                f"第 {self.getpos()[0]} 行：</{tag}> 与第 {line} 行的 <{top}> 不匹配"
            )


def extract_template(src):
    """取出 html() 里的三引号字符串。"""
    m = re.search(r'return\s+"""\n(.*?)\n"""\.trimIndent\(\)', src, re.S)
    if not m:
        raise SystemExit("找不到 HTML 模板")
    return m.group(1)


def main():
    src = open(KOTLIN, encoding="utf-8").read()
    tpl = extract_template(src)

    # 先替换 $var 模板占位
    body = tpl
    for k, v in SAMPLE.items():
        body = body.replace("$" + k, v)

    # 找出仍未替换的模板变量。
    #
    # 关键：`${...}` 里是**任意 Kotlin 表达式**，不只是变量名。
    # 例如 `${if (dark) "dark" else "light"}` 是合法的内联表达式，
    # 早期版本用 `\$\{?(\w+)\}?` 去匹配，会把 `if` 当成"未替换的变量"
    # 报假警。这里改成：
    #   - `$name`        → 简单变量插值，必须已在 SAMPLE 里
    #   - `${...}`       → 只要里面有非标识符字符（空格、括号、点等），
    #                      就认定是表达式，跳过；纯 `${name}` 才算变量。
    left = []
    for m in re.finditer(r"\$(\w+)|\$\{([^}]*)\}", body):
        simple, expr = m.group(1), m.group(2)
        if simple is not None:
            left.append(simple)
        elif expr is not None and re.fullmatch(r"\w+", expr.strip()):
            # 纯 ${name}：仍是变量插值
            left.append(expr.strip())
        # 其余 ${...} 视为 Kotlin 表达式，不属于"未替换变量"

    problems = []
    if left:
        problems.append(f"未替换的模板变量: {sorted(set(left))}")

    # 1) 标签配平
    c = Checker()
    c.feed(body)
    problems += c.errors
    for tag, line in c.stack:
        problems.append(f"第 {line} 行的 <{tag}> 未闭合")

    # 2) SVG 表现属性里不能用 var()
    for m in re.finditer(r"<(?:svg|path|circle|rect|line|polyline|g)\b[^>]*>", body):
        seg = m.group(0)
        if "var(--" in seg:
            problems.append(f"SVG 表现属性里使用了 var()，浏览器不会解析：{seg[:80]}")

    # 3) 外部资源
    if re.search(r'<(?:link|script|img)\b', body):
        problems.append("引用了外部资源，离线会白屏")

    # 4) CSS 变量定义/使用配对
    defined = set(re.findall(r"(--[\w-]+)\s*:", body))
    used = set(re.findall(r"var\((--[\w-]+)", body))
    undefined = used - defined
    if undefined:
        problems.append(f"使用了未定义的 CSS 变量: {sorted(undefined)}")
    unused = defined - used
    if unused:
        problems.append(f"(提示) 定义了但未使用的 CSS 变量: {sorted(unused)}")

    # 5) 关键元素存在
    for need, desc in [
        ('<meta charset="utf-8">', "字符集声明"),
        ("viewport", "viewport 声明"),
        ("<h1>翎</h1>", "品牌标题"),
        ('class="mark"', "品牌图标容器"),
        ('class="hint"', "底部提示"),
        ("env(safe-area-inset-bottom)", "刘海屏安全区适配"),
    ]:
        if need not in body:
            problems.append(f"缺少{desc}（{need}）")

    # 6) 布局：内容高于视口时顶部不能被裁掉。
    #    body 固定 height:100% + flex 的 justify-content:center 时，溢出的内容会被
    #    顶到滚动区**上方**，滚不上去 —— logo 与「翎」直接看不见（横屏 / 分屏 /
    #    小屏 + 8 个快捷入口都会触发）。必须用 min-height 让 body 随内容长高。
    if re.search(r"body\s*\{[^}]*?(?<!min-)height:\s*100%", body):
        problems.append("body 固定了 height:100% —— 内容高于视口时顶部会被裁掉且滚不上去")
    if "min-height: 100%" not in body:
        problems.append("body 缺少 min-height:100% —— 内容高于视口时布局会出问题")

    # 7) 快捷入口只能靠 **id** 定位。
    #    主题刷新脚本（themeUpdateJs）也会跑在阅读视图上，而阅读视图的正文来自
    #    第三方网页，里面完全可能有 class="links" 的元素（"相关链接"这类）——
    #    用 class 选择器会把正文里那个元素当成快捷入口删掉，直接损坏文章内容。
    if 'const val QUICK_LINKS_ID = "ling-quick-links"' not in src:
        problems.append("快捷入口缺少固定的 DOM id 常量（QUICK_LINKS_ID）")
    if 'id="$QUICK_LINKS_ID"' not in src:
        problems.append("快捷入口区块没有带上 QUICK_LINKS_ID")
    if "getElementById('$QUICK_LINKS_ID')" not in src:
        problems.append("主题刷新脚本没有用 id 定位快捷入口")
    if "querySelector('.links')" in src:
        problems.append("主题刷新脚本用了 class 选择器 —— 会误删阅读视图正文里的 class=links 元素")

    print(f"模板 {len(tpl)} 字符，实例化后 {len(body)} 字符")
    print(f"标签总数 {len(c.tags)}：{', '.join(sorted(set(c.tags)))}")
    print(f"CSS 变量：定义 {len(defined)} 个，使用 {len(used)} 个")
    print()
    if problems:
        print(f"发现 {len(problems)} 个问题：")
        for p in problems:
            print("  ! " + p)
        return 1
    print("主页 HTML 自检通过。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
