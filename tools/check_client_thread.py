"""校验 shouldInterceptRequest 绝不触碰 WebView 实例。

背景：这是一个**真机崩溃**的回归防线。

`WebViewClient.shouldInterceptRequest` 运行在**后台线程**（Android 文档原话：
"clients should exercise caution when accessing private data or the view system"）。
而 WebView 的每个方法（哪怕只是 `view.url`）内部都有 `checkThread()`，在创建
线程之外调用会抛 `Throwable: A WebView method was called on thread ...`。
更要命的是它抛在后台线程上，默认的未捕获异常处理器会**直接终止进程** ——
用户看到的就是"网页加载到一半应用闪退"。

曾经的写法在检测混合内容时读了 `view?.url`，于是 http 页面每遇到一个
`http://` 子资源就崩一次。现在主框架协议改从 `currentUrl()`（读的是线程安全的
StateFlow）取。本脚本守住两条：方法体内不得出现 `view`、必须用 `currentUrl()`。
"""
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
CLIENT = os.path.join(
    os.path.dirname(HERE), "app", "src", "main", "java", "com", "ling", "browser",
    "web", "LingWebViewClient.kt",
)

problems = []


def bad(m):
    problems.append(m)
    print("  FAIL " + m)


def ok(m):
    print("  OK   " + m)


print("shouldInterceptRequest 线程安全校验")
print()

if not os.path.exists(CLIENT):
    print("  FAIL 找不到 LingWebViewClient.kt")
    sys.exit(1)

src = open(CLIENT, encoding="utf-8").read()

# ---- 1. 抽出方法体（按大括号配平） ----
m = re.search(r"override\s+fun\s+shouldInterceptRequest\s*\(", src)
if not m:
    bad("没找到 shouldInterceptRequest")
    sys.exit(1)
i = src.find("{", m.end())
if i < 0:
    bad("方法签名后没有函数体")
    sys.exit(1)
depth = 0
start = i
while i < len(src):
    if src[i] == "{":
        depth += 1
    elif src[i] == "}":
        depth -= 1
        if depth == 0:
            break
    i += 1
body = src[start + 1:i]
ok(f"抽出方法体（{body.count(chr(10)) + 1} 行）")

# 去掉注释再检查：注释里提到 view 是允许的（本文件正好在注释里解释了为什么不能碰）
code = re.sub(r"//[^\n]*", "", body)
code = re.sub(r"/\*.*?\*/", "", code, flags=re.S)

# ---- 2. 方法体里不得出现 view ----
hits = [ln.strip() for ln in code.split("\n") if re.search(r"\bview\b", ln)]
if hits:
    bad(f"方法体里出现了 view —— 后台线程碰 WebView 会 checkThread() 闪退（共 {len(hits)} 处）：")
    for h in hits[:5]:
        bad("    " + h)
else:
    ok("方法体未引用 view（不会触发 checkThread 崩溃）")

# ---- 3. view 参数必须还在（防止有人靠删参数来"通过"本检查） ----
sig = src[m.start():i]
if re.search(r"\bview\s*:\s*WebView", sig):
    ok("签名仍保留 view: WebView? 参数（没有靠删参数绕过本检查）")
else:
    bad("签名里的 view 参数不见了 —— 不要用删参数的方式绕过本检查")

# ---- 4. 主框架协议必须来自 currentUrl() ----
if "currentUrl()" in code:
    ok("混合内容判定使用 currentUrl()（线程安全的 StateFlow）")
else:
    bad("没看到 currentUrl() —— 主框架协议从哪来？不要退回 view.url")

# ---- 5. 每页只上报一次的闸门仍在 ----
if "mixedReported" in code:
    ok("保留了 mixedReported 闸门（不会重复上报）")
else:
    bad("mixedReported 闸门不见了，混合内容会被重复上报上千次")

# ---- 6. 广告拦截的 early-return 仍在检测之后 ----
mix_at = code.find("mixedReported = true")
ad_at = code.find("AdBlocker.shouldBlock")
if mix_at >= 0 and ad_at >= 0 and mix_at < ad_at:
    ok("混合内容检测排在广告拦截 early-return 之前（关掉广告拦截也会检测）")
elif mix_at >= 0 and ad_at < 0:
    ok("混合内容检测保持在方法前半段")
else:
    bad("混合内容检测被挤到广告拦截 early-return 之后了")

print()
print("=" * 55)
if problems:
    print(f"{len(problems)} 个问题：")
    for p in problems:
        print("  ! " + p)
    sys.exit(1)
print("shouldInterceptRequest 线程安全校验通过")
