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
    # 主页背景恒透明（模板里写的是 $TRANSPARENT_BG）：背景由原生背景层
    # 在 WebView 之下供色，见第 7c 节的反向检查。
    "TRANSPARENT_BG": "transparent",
    "onBackground": "#1C1B1F",
    "primaryContainer": "#A8F2CB",
    "onPrimaryContainer": "#00210F",
    "dark": "false",
    # 与 Kotlin 的 HomePage.SEARCH_URL 必须一致：下面有专门的漂移检查
    "SEARCH_URL": "ling://search",
    # 时钟节点 id 与初始时间（由原生侧生成，主页是零脚本 HTML）
    "CLOCK_ID": "ling-clock",
    "clock": "12:00",
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
    #    主页已极简到只有「时钟 + 搜索框」：既检查该在的在，
    #    也（见第 7 节）确认不该在的（logo / 品牌标题 / 快捷入口）不在。
    for need, desc in [
        ('<meta charset="utf-8">', "字符集声明"),
        ("viewport", "viewport 声明"),
        ('class="clock"', "时钟节点"),
        ('id="ling-clock"', "时钟 DOM id"),
        ('class="search"', "主页搜索框"),
        ('name="q"', "搜索框参数名"),
        ("env(safe-area-inset-bottom)", "刘海屏安全区适配"),
    ]:
        if need not in body:
            problems.append(f"缺少{desc}（{need}）")

    # 5b) 搜索框：action 必须是**内部地址**，且与 Kotlin 常量一致。
    #     这里最容易出的是"两边各写一份"的漂移：HTML 改了 action、Kotlin 的
    #     searchQueryOf 没跟着改，输入就交不回来（点了没反应，且很难查）。
    m_action = re.search(r'<form[^>]*\baction="([^"]+)"', body)
    m_const = re.search(r'const val SEARCH_URL = "([^"]+)"', src)
    if not m_action:
        problems.append("主页没有搜索框表单（缺少 action）")
    elif not m_const:
        problems.append("HomePage 缺少 SEARCH_URL 常量 —— 搜索框提交地址没有唯一来源")
    elif m_action.group(1) != m_const.group(1):
        problems.append(
            f"搜索框 action（{m_action.group(1)}）与 SEARCH_URL（{m_const.group(1)}）"
            f"不一致 —— 提交上来的地址解析不出来"
        )
    elif not m_action.group(1).startswith("ling://"):
        problems.append("搜索框 action 不是内部地址 —— 会被 WebView 当普通网址加载")
    if 'method="get"' not in body:
        problems.append("搜索框不是 GET 提交 —— 查询串带不回来")

    # 6) 布局：内容高于视口时顶部不能被裁掉。
    #    body 固定 height:100% + flex 的 justify-content:center 时，溢出的内容会被
    #    顶到滚动区**上方**，滚不上去 —— 时钟与搜索框直接看不见（横屏 / 分屏 /
    #    小屏 + 大号时钟都会触发）。必须用 min-height 让 body 随内容长高。
    if re.search(r"body\s*\{[^}]*?(?<!min-)height:\s*100%", body):
        problems.append("body 固定了 height:100% —— 内容高于视口时顶部会被裁掉且滚不上去")
    if "min-height: 100%" not in body:
        problems.append("body 缺少 min-height:100% —— 内容高于视口时布局会出问题")

    # 7) 极简约束的反向检查：主页只该有「时钟 + 搜索框」。
    #    早期版本有一堆 logo / 品牌标题 / 书签快捷入口，重构后已移除；
    #    这里立项防止它们日后被"顺手加回来"。
    #    注意：快捷入口曾用固定 DOM id（QUICK_LINKS_ID）做主题刷新，那段逻辑
    #    随快捷入口一并删除 —— 若 src 里又出现 QUICK_LINKS_ID，说明被加回来了。
    for bad, desc in [
        ("<h1", "品牌标题"),
        ('class="mark"', "品牌图标容器"),
        ('class="links"', "快捷入口区块"),
        ('class="link-label"', "快捷入口文字"),
        ("QUICK_LINKS_ID", "快捷入口的 DOM id 常量"),
        ("linksHtml", "快捷入口 HTML 片段生成器"),
    ]:
        if bad in body or bad in src:
            problems.append(f"主页已极简，不应再有{desc}（{bad}）")

    # 7b) 主页是**零脚本** HTML：不能自带 <script>，时钟由原生侧注入刷新。
    if "<script" in body:
        problems.append("主页不应内嵌 <script> —— 时钟等动态内容由原生侧注入")

    # 7c) 主页背景必须**恒透明**：背景一律由原生背景层在 WebView 之下供色
    #     （纯色档画主题色、自定义图片档叠照片）。
    #     若把实色写回 --bg，就回到"WebView 异步首帧 vs 页面自绘背景"两套颜色，
    #     冷启动必然闪一段（先白后加载）；立此防止回退。
    if "--bg: transparent" not in body:
        problems.append(
            "--bg 不是 transparent —— 主页背景必须由原生层供色（见 HomePage.TRANSPARENT_BG）"
        )

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
