"""校验下载进度的节流与速度采样没有退化成"毫秒级抖动"。

背景（真机 bug：下载的「剩余时间」一直跳变）：

`DownloadEngine.writeBody` 里原本只有一个 `lastPublish` 变量，它存的是
**已写字节数**，却被拿去和 `now`（时间戳）做差：

    if (written - lastPublish >= PROGRESS_INTERVAL_BYTES ||
        now - lastPublish >= PROGRESS_INTERVAL_MS)

`now` 是 epoch 毫秒（约 1.7e12），`lastPublish` 是字节数 —— 这个减法**恒大于 300**，
于是"每 300ms 刷一次"的节流永远成立，实际变成**每读一个 8 KiB 缓冲就刷一次**。
进度刷新频率高到毫秒级，`measureSpeed` 便在几毫秒的窗口里算"瞬时速度"，
网络分片级的抖动被直接放大到界面上 —— 速度数字乱蹦，剩余时间跟着上下弹。

两处都必须钉住：
  1. 字节进度与时间戳要用**两个不同的变量**（同一个变量同时当字节和时刻用，
     编译器不会报错，单测也覆盖不到）；
  2. 速度采样的窗口要有下限（窗口太短算出来的只是噪声，磨平它反而更糟）。

这类错误既不崩溃也不报错，只是"数字看着不对"，因此只能靠静态检查盯着。
"""
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
ENGINE = os.path.join(
    ROOT, "app", "src", "main", "java", "com", "ling", "browser",
    "download", "DownloadEngine.kt",
)

problems = []


def bad(m):
    problems.append(m)
    print("  FAIL " + m)


def ok(m):
    print("  OK   " + m)


def extract(src, name):
    """抓取 `fun name(...) { ... }` 的函数体（按大括号配平）。"""
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


def strip_comments(src):
    """去掉注释再查代码特征。

    踩过的坑：说明这个 bug 的注释里就含 `now - lastPublish` 这种原文，
    直接 in/regex 判断会被注释骗过 —— 把真正的写法改错了，检查照样通过。
    """
    src = re.sub(r"//[^\n]*", "", src)
    return re.sub(r"/\*.*?\*/", "", src, flags=re.S)


print("下载进度节流与速度采样检查")
print()

if not os.path.exists(ENGINE):
    bad("找不到 DownloadEngine.kt：%s" % ENGINE)
    sys.exit(1)

raw = open(ENGINE, encoding="utf-8").read()
code = strip_comments(raw)

print("1. 进度节流：字节进度与时间戳不能共用一个变量")
body = extract(code, "writeBody")
if body is None:
    bad("取不到 writeBody 的函数体")
else:
    # 与 now 比较的标识符（应当只有时间戳），与 written 比较的标识符（应当只有字节数）
    ts_vars = set(re.findall(r"\bnow\s*-\s*(\w+)", body))
    byte_vars = set(re.findall(r"\bwritten\s*-\s*(\w+)", body))

    if not ts_vars:
        bad("节流条件里没有「now - 时间戳」—— 时间维度的节流可能被整个删掉了")
    if not byte_vars:
        bad("节流条件里没有「written - 字节数」—— 字节维度的节流可能被整个删掉了")

    overlap = ts_vars & byte_vars
    if overlap:
        bad(
            "同一个变量同时当字节数和时间戳用（%s）—— "
            "这类减法恒成立，节流形同虚设，进度会按毫秒级刷新" % "、".join(sorted(overlap))
        )
    else:
        ok("时间戳（%s）与字节数（%s）用的是不同变量"
           % ("、".join(sorted(ts_vars)) or "-", "、".join(sorted(byte_vars)) or "-"))

    # 两个变量都要在两个维度上真的被刷新，否则第二次比较就永远成立/不成立
    for var in sorted(ts_vars | byte_vars):
        if not re.search(r"\b%s\s*=" % re.escape(var), body):
            bad("节流变量 %s 在函数体里没有被赋值 —— 节流会失效" % var)
    if ts_vars and byte_vars and not overlap:
        ok("节流变量都在刷新（每次越过阈值就推进基准）")

    if "PROGRESS_INTERVAL_BYTES" in body and "PROGRESS_INTERVAL_MS" in body:
        ok("字节与时间两个阈值都在用")
    else:
        bad("缺 PROGRESS_INTERVAL_BYTES 或 PROGRESS_INTERVAL_MS")

print("\n2. 速度采样：窗口要有下限，平滑权重不能丢")
ms = extract(code, "measureSpeed")
if ms is None:
    bad("取不到 measureSpeed 的函数体")
else:
    if re.search(r"SPEED_SAMPLE_MIN_MS", ms) and re.search(
        r"deltaMs\s*<\s*SPEED_SAMPLE_MIN_MS", ms
    ):
        ok("窗口不足 SPEED_SAMPLE_MIN_MS 时不产生新样本（否则算的是分片级抖动）")
    else:
        bad("速度采样没有最短窗口 —— 几毫秒的窗口会把抖动直接搬到界面上")

    if re.search(r"\*\s*3\s*\+\s*instant\s*\)\s*/\s*4", ms):
        ok("瞬时速度仍走 1/4 权重的指数滑动平均（保留平滑）")
    else:
        bad("指数滑动平均被改坏了 —— 速度会按采样点原样跳动")

    # 窗口不足时必须是**沿用上次速度**，而不是返回 0/重算。
    # Kotlin 里 `if (...) return x` 没有分号，所以不能拿 `;` 收尾。
    early = re.search(
        r"if\s*\(\s*deltaMs\s*<\s*SPEED_SAMPLE_MIN_MS\s*\)\s*([^\n]+)", ms
    )
    if early and "return" in early.group(1):
        ok("窗口不足时沿用上次速度（不重算，也不归零）")
    else:
        bad("窗口不足时没有提前返回 —— 短窗口仍会参与采样")

    # 窗口不足时不能顺手把采样点推进（否则窗口永远累不够）
    if early and re.search(r"\bprev\.(bytes|time)\s*=", early.group(1)):
        bad("窗口不足时推进了采样点 —— 窗口永远累不够，采样频率依旧过高")
    else:
        ok("窗口不足时不推进采样点（下次仍以累计窗口为准）")


def const_value(name, seen=()):
    """解析常量的数值，支持 `A = B` 这种别名写法。

    SPEED_SAMPLE_MIN_MS = PROGRESS_INTERVAL_MS 是别名 —— 只认字面量的话
    会报"找不到定义"，把那行本来正确的写法误判成问题（写这个脚本时踩过）。
    """
    if name in seen:
        return None
    m = re.search(r"const val " + re.escape(name) + r"\s*=\s*([^\n]+)", code)
    if not m:
        return None
    expr = m.group(1).strip()
    if re.fullmatch(r"\d+L?", expr):
        return int(expr.rstrip("L"))
    ref = re.fullmatch(r"(\w+)", expr)
    if ref:
        return const_value(ref.group(1), seen + (name,))
    return None


val = const_value("SPEED_SAMPLE_MIN_MS")
if val is None:
    bad("找不到 SPEED_SAMPLE_MIN_MS 的定义（或它的值解析不出来）")
elif val >= 100:
    ok("最短窗口 = %dms（够覆盖一次网络往返抖动）" % val)
else:
    bad("最短窗口只有 %dms —— 太短，等于没限" % val)

print("\n3. 采样结果真的喂给了界面上的速度/剩余时间")
if re.search(r"speedBytesPerSec\s*=\s*speed", body or ""):
    ok("采样结果写进 DownloadEntry.speedBytesPerSec")
else:
    bad("measureSpeed 的返回值没有写回条目 —— 界面拿不到速度，剩余时间也算不出来")

screen = os.path.join(
    ROOT, "app", "src", "main", "java", "com", "ling", "browser",
    "ui", "screens", "DownloadsScreen.kt",
)
screen_src = open(screen, encoding="utf-8").read()
if "entry.etaSeconds" in screen_src and "entry.speedBytesPerSec" in screen_src:
    ok("列表副标题同时用了速度与剩余时间（都源自这次采样）")
else:
    bad("列表副标题没用 etaSeconds / speedBytesPerSec")

print()
print("=" * 55)
if problems:
    print("%d 个问题：" % len(problems))
    for p in problems:
        print("  ! " + p)
    sys.exit(1)
print("下载进度节流与速度采样校验通过")
