"""校验网页 JS 对话框（alert/confirm/prompt/onbeforeunload）与 window.open 的接线一致性。

本工程的 JS 对话框走「WebTabManager 委托 lambda -> BrowserScreen Compose UI」的模式，
和 onShowFileChooser 一致。这条链路里任何一环漏接都会导致：
  - 页面 alert/confirm 卡死（JsResult 没兑现）
  - 回退到系统默认 Dialog（标题固定 "JavaScript"，与应用风格脱节）
  - window.open 在当前标签覆盖加载而不是开新标签

本脚本静态核对以下契约，防止重构时漏改：

1. WebTabManager 声明了四个委托：onJsAlert / onJsConfirm / onJsPrompt / onJsBeforeUnload
2. WebChromeClient 重写了对应的四个 onJs* 方法，且每个都调用 this@WebTabManager.onJs*
3. BrowserScreen 的 DisposableEffect 里给四个委托都赋了处理函数，onDispose 里都置 null
4. JsDialogRequest 的四个子类型（Alert/Confirm/Prompt/BeforeUnload）在对话框渲染的
   when 分支里被穷尽处理（confirm / dismiss / onDismiss 三处都要覆盖）
5. LingWebView 开了 setSupportMultipleWindows(true)，WebTabManager 重写了
   onCreateWindow 且内部调用 newTab（window.open 走新标签）
"""
import os
import re
import sys

ROOT = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "app", "src", "main", "java",
)

DIALOG_TYPES = ["Alert", "Confirm", "Prompt", "BeforeUnload"]
# 委托属性名 -> JsDialogRequest 子类型名
DELEGATE_TO_SUBTYPE = {
    "onJsAlert": "Alert",
    "onJsConfirm": "Confirm",
    "onJsPrompt": "Prompt",
    "onJsBeforeUnload": "BeforeUnload",
}
# WebChromeClient override 方法名
CHROME_METHODS = ["onJsAlert", "onJsConfirm", "onJsPrompt", "onJsBeforeUnload"]


def strip_code(code):
    """移除注释和字符串字面量，避免误匹配。"""
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


def read(path):
    return strip_code(open(path, encoding="utf-8").read())


def main():
    problems = []

    tab_mgr = os.path.join(ROOT, "com", "ling", "browser", "web", "WebTabManager.kt")
    browser = os.path.join(ROOT, "com", "ling", "browser", "ui", "screens", "BrowserScreen.kt")
    ling_wv = os.path.join(ROOT, "com", "ling", "browser", "web", "LingWebView.kt")

    for p, name in [(tab_mgr, "WebTabManager"), (browser, "BrowserScreen"), (ling_wv, "LingWebView")]:
        if not os.path.exists(p):
            problems.append(f"找不到 {name} 源文件：{p}")

    if problems:
        for p in problems:
            print("  ! " + p)
        return 1

    tm_code = read(tab_mgr)
    br_code = read(browser)
    wv_code = read(ling_wv)

    # ---- 1. WebTabManager 委托声明 ----
    for delegate in DELEGATE_TO_SUBTYPE:
        if not re.search(rf"\bvar\s+{re.escape(delegate)}\s*:", tm_code):
            problems.append(
                f"WebTabManager 未声明委托 `var {delegate}`（JS 对话框无 UI 接管入口）"
            )

    # ---- 2. WebChromeClient 四个 onJs* override 且调用对应委托 ----
    for method in CHROME_METHODS:
        if not re.search(rf"override\s+fun\s+{method}\s*\(", tm_code):
            problems.append(f"WebChromeClient 未重写 {method}()（会回退系统默认 Dialog）")
            continue
        # 取该方法体（从 fun 到下一个同级 override fun / }），检查是否调用了 this@WebTabManager.onJsX
        m = re.search(rf"override\s+fun\s+{method}\s*\(", tm_code)
        body = tm_code[m.start(): m.start() + 1200]
        delegate = method  # 方法名和委托名一致
        if f"this@WebTabManager.{delegate}" not in body:
            problems.append(
                f"WebChromeClient.{method}() 未调用 this@WebTabManager.{delegate}（委托未生效）"
            )
        if "return true" not in body:
            problems.append(
                f"WebChromeClient.{method}() 未 return true（WebView 会回退系统默认 Dialog）"
            )

    # ---- 3. BrowserScreen DisposableEffect 接线 ----
    for delegate, subtype in DELEGATE_TO_SUBTYPE.items():
        # 赋值：viewModel.tabManager.onJsX = { ... }
        if not re.search(
            rf"tabManager\.{re.escape(delegate)}\s*=\s*\{{", br_code
        ):
            problems.append(
                f"BrowserScreen 未给 tabManager.{delegate} 赋处理函数（对话框无法弹出）"
            )
        # 处理函数里要构造对应的 JsDialogRequest.Subtype
        if not re.search(
            rf"JsDialogRequest\.{subtype}\s*\(", br_code
        ):
            problems.append(
                f"BrowserScreen 未构造 JsDialogRequest.{subtype}（{delegate} 的请求没进 state）"
            )
        # onDispose 里置 null
        if not re.search(
            rf"tabManager\.{re.escape(delegate)}\s*=\s*null", br_code
        ):
            problems.append(
                f"BrowserScreen onDispose 未把 tabManager.{delegate} 置 null（泄漏回调）"
            )

    # ---- 4. JsDialogRequest 四子类型在 when 中穷尽处理 ----
    # 对话框渲染里有三处 when：onDismissRequest、confirmButton、dismissButton。
    # 每处都应覆盖四个子类型，否则该类型对话框会崩溃或行为错误。
    when_blocks = re.findall(r"when\s*\(req\)\s*\{", br_code)
    if len(when_blocks) < 3:
        problems.append(
            f"BrowserScreen 对话框渲染只找到 {len(when_blocks)} 处 when(req)，"
            f"期望至少 3 处（onDismiss / confirm / dismiss）"
        )
    for subtype in DIALOG_TYPES:
        count = len(re.findall(rf"is\s+JsDialogRequest\.{subtype}\b", br_code))
        if count < 3:
            problems.append(
                f"JsDialogRequest.{subtype} 在 BrowserScreen 的 when 分支只出现 {count} 次，"
                f"期望至少 3 次（onDismiss / confirm / dismiss 各一次）"
            )

    # ---- 5. window.open 接线 ----
    if "setSupportMultipleWindows(true)" not in wv_code:
        problems.append(
            "LingWebView 未调用 setSupportMultipleWindows(true)（window.open 不会触发 onCreateWindow）"
        )
    if not re.search(r"override\s+fun\s+onCreateWindow\s*\(", tm_code):
        problems.append("WebTabManager 未重写 onCreateWindow()（window.open 无法开新标签）")
    else:
        m = re.search(r"override\s+fun\s+onCreateWindow\s*\(", tm_code)
        body = tm_code[m.start(): m.start() + 2000]
        if "newTab(" not in body:
            problems.append("onCreateWindow() 未调用 newTab()（window.open 没开成新标签）")
        if "transport.webView" not in body:
            problems.append("onCreateWindow() 未给 transport.webView 赋值（WebView 契约不满足）")

    if problems:
        print(f"发现 {len(problems)} 个 JS 对话框接线问题：\n")
        for p in sorted(set(problems)):
            print("  ! " + p)
        return 1

    print(
        "JS 对话框接线校验通过：4 个委托声明 + 4 个 WebChromeClient override + "
        "BrowserScreen 赋值/置空 + JsDialogRequest 四子类型穷尽处理 + window.open 新标签接线。"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
