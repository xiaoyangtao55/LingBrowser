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

print()
print("=" * 55)
if problems:
    print(f"{len(problems)} 个问题：")
    for p in problems:
        print("  ! " + p)
    sys.exit(1)
print("workflow 结构校验通过")
