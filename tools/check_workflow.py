"""校验 .github/workflows/release.yml 的结构（无 PyYAML，手写轻量检查）。

重点验证：
  1. 缩进层级合法（YAML 靠缩进，改坏了 GitHub 直接报 invalid workflow）
  2. 每个 step 要么有 uses、要么有 run
  3. run 块的 shell 语法：引号配平、for/do/done 配平、if/fi 配平
  4. 不再引用 setup-android（它装的 `tools` 包已下架，是本次故障根因）
  5. 顶层关键字齐全
"""
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
WF = os.path.join(os.path.dirname(HERE), ".github", "workflows", "release.yml")

problems = []
lines = open(WF, encoding="utf-8").read().split("\n")


def bad(msg):
    problems.append(msg)
    print("  FAIL " + msg)


def ok(msg):
    print("  OK   " + msg)


# ---------- 1. 基础 ----------
print("1. 文件基础")
if not os.path.exists(WF):
    bad("workflow 文件不存在")
    sys.exit(1)
ok(f"存在，{len(lines)} 行")

# 制表符是 YAML 大忌
tab_lines = [i + 1 for i, ln in enumerate(lines) if "\t" in ln]
if tab_lines:
    bad(f"含制表符（YAML 不允许）行号：{tab_lines}")
else:
    ok("无制表符")

# 缩进必须是 2 的倍数
odd = []
for i, ln in enumerate(lines):
    if not ln.strip():
        continue
    indent = len(ln) - len(ln.lstrip(" "))
    if indent % 2 != 0:
        odd.append(i + 1)
if odd:
    bad(f"缩进不是 2 的倍数：行 {odd}")
else:
    ok("缩进均为 2 的倍数")

# ---------- 2. 顶层关键字 ----------
print("\n2. 顶层关键字")
top_keys = [ln.split(":")[0] for ln in lines if ln and not ln[0].isspace() and ":" in ln]
for k in ("name", "on", "jobs"):
    if k in top_keys:
        ok(f"含 {k}")
    else:
        bad(f"缺少顶层关键字 {k}")

# ---------- 3. 步骤完整性 ----------
print("\n3. 步骤完整性")
step_idx = [i for i, ln in enumerate(lines) if re.match(r"^      - ", ln)]
ok(f"共 {len(step_idx)} 个 step")
for n, i in enumerate(step_idx):
    end = step_idx[n + 1] if n + 1 < len(step_idx) else len(lines)
    block = "\n".join(lines[i:end])
    has_uses = "uses:" in block
    # run 有两种写法：`run: |` 多行块，以及 `run: ./gradlew ...` 单行
    has_run = re.search(r"^\s+run:\s*\S", block, re.M) is not None \
        or re.search(r"^\s+run:\s*\|\s*$", block, re.M) is not None
    name = re.search(r"- name: (.+)", lines[i])
    label = name.group(1) if name else f"step#{n+1}"
    if not (has_uses or has_run):
        bad(f"step「{label}」既无 uses 也无 run")
if not problems:
    ok("每个 step 都有 uses 或 run")

# ---------- 4. shell 块配平 ----------
print("\n4. run 块的 shell 配平")


def shell_blocks():
    """抽出每个 run: | 后面的缩进块。"""
    out = []
    i = 0
    while i < len(lines):
        m = re.match(r"^(\s+)run: \|\s*$", lines[i])
        if m:
            base = len(m.group(1))
            j = i + 1
            buf = []
            while j < len(lines):
                ln = lines[j]
                if ln.strip() and (len(ln) - len(ln.lstrip(" "))) <= base:
                    break
                buf.append(ln)
                j += 1
            out.append((i + 1, "\n".join(buf)))
            i = j
        else:
            i += 1
    return out


blocks = shell_blocks()
ok(f"找到 {len(blocks)} 个 shell 块")
for start, body in blocks:
    for kw, closer in (("for ", "done"), ("if ", "fi"), ("while ", "do")):
        pass
    n_for = len(re.findall(r"\bfor\b.*?\bin\b", body))
    n_do = len(re.findall(r"\bdo\b", body))
    n_done = len(re.findall(r"\bdone\b", body))
    n_if = len(re.findall(r"^\s*if\b", body, re.M))
    n_fi = len(re.findall(r"^\s*fi\b", body, re.M))
    if n_for and n_done and n_for != n_done:
        bad(f"行 {start} 块：for={n_for} 但 done={n_done}")
    if n_if != n_fi:
        bad(f"行 {start} 块：if={n_if} 但 fi={n_fi}")
    # 单双引号配平（逐行，忽略注释）
    for k, ln in enumerate(body.split("\n")):
        code = ln.split("#")[0] if not ln.strip().startswith("#") else ""
        if code.count('"') % 2:
            bad(f"行 {start+k+1} 双引号不成对：{ln.strip()}")
if not any("for=" in p or "if=" in p or "引号" in p for p in problems):
    ok("for/done、if/fi、引号均配平")

# ---------- 5. 关键回归 ----------
print("\n5. 关键回归（本次故障根因）")
full = "\n".join(lines)
# 只看**生效的** YAML，排除注释 —— 否则解释性注释里的 setup-android
# 会被误判成"仍在引用"。
active = "\n".join(
    ln for ln in lines if not ln.strip().startswith("#")
)
if "setup-android" in active:
    bad("仍在引用 android-actions/setup-android（它会装已下架的 tools 包）")
else:
    ok("已移除 android-actions/setup-android（仅注释中提及，用于说明原因）")
if "sdkmanager" in active:
    ok("改为直接调用 sdkmanager")
if re.search(r'sdkmanager"?\s+"tools"', active):
    bad("仍在安装已下架的 tools 包")
else:
    ok("未安装已下架的 tools 包")
for pkg in ("platform-tools", "platforms;android-36", "build-tools"):
    if pkg in active:
        ok(f"包含组件 {pkg}")
for step in ("assembleRelease", "testDebugUnitTest", "upload-artifact"):
    if step in active:
        ok(f"保留步骤 {step}")
    else:
        bad(f"丢失步骤 {step}")

# ---------- 6. 工具路径不能用通配符直接当命令执行 ----------
#
# 真实故障：写
#     "$ANDROID_HOME"/build-tools/*/apksigner verify --verbose "$APK"
# runner 上装了多个 build-tools（35.0.0 / 36.0.0 ...），通配符展开成多个
# 参数，实际执行变成
#     apksigner <路径2> <路径3> verify ...
# apksigner 把第一个多余路径当成子命令，报
#     Unsupported command: .../build-tools/35.0.0/apksigner
#
# 而且这种错**只在装了多个版本的机器上出现** —— 本地只有一个版本时
# 通配符恰好展开成一个，命令是对的。所以必须靠静态检查拦住。
print("\n6. 工具路径是否避免了多值通配符")
# 要抓的是"通配符路径**被当作命令直接执行**"，例如
#     "$ANDROID_HOME"/build-tools/*/apksigner verify ...
# 而不是"通配符出现在收集路径的命令里"，例如正确写法
#     APKSIGNER=$(ls -d "$ANDROID_HOME"/build-tools/*/apksigner | sort -V | tail -n1)
#
# 区分方法：把 `$(...)` 子shell 内容先挖掉再判断 —— 那些位置的通配符
# 是喂给 ls 的，合法。之前不做这个区分，会把正确写法也报错。
stripped = re.sub(r"\$\([^)]*\)", "SUB", active)
wild_cmd = re.findall(
    r'(?m)^\s*.*(?:\$\w+|\$\{\w+\})[^ \t]*/\*/[^ \t;|&]+', stripped)
if wild_cmd:
    for w in wild_cmd:
        bad(f"命令位置直接用了通配符路径（多版本时会展开成多个参数）：{w.strip()}")
    bad("请先用 ls|sort -V|tail -n1 选出唯一路径再执行")
else:
    ok("未把多值通配符直接放在命令位置")
    # 已知局限：通配符若写在 $(...) 子 shell **内部**且不经 ls 收集，
    # 本检查不会报（已挖掉子 shell 内容）。那种写法同样会展开成多参数，
    # 但静态区分"合法的 glob 收集"与"非法的多参数展开"需要真跑 shell，
    # 代价不值得。这里只保证最常见的那种错误能被拦住。
    print("       （注：$(...) 内部的通配符不检查）")

# apksigner 的调用方式：必须经由变量，且要有"找不到就报错"的守卫
if "apksigner" in active:
    if re.search(r'"\$APKSIGNER"\s+verify', active):
        ok("apksigner 经变量调用（可确保只有一个路径）")
    else:
        bad("apksigner 未经变量调用，多版本环境下可能失败")
    if "找不到可执行的 apksigner" in active:
        ok("有 apksigner 缺失时的显式报错")
    else:
        bad("缺少 apksigner 缺失时的守卫")

# 签名校验失败必须是 error 而不是 warning，否则会静默产出未签名包
if re.search(r"apksigner.*校验失败", active) and "::error::apksigner 校验失败" in active:
    ok("apksigner 校验失败按 error 处理（不再静默通过）")
else:
    bad("apksigner 校验失败未按 error 处理")

if "grep -q \"unsigned\"" in active:
    ok("会检查产物名是否含 unsigned")
else:
    bad("未检查产物名是否含 unsigned —— 未签名包可能蒙混过关")

print()
print("=" * 55)
if problems:
    print(f"{len(problems)} 个问题：")
    for p in problems:
        print("  ! " + p)
    sys.exit(1)
print("workflow 结构校验通过")
