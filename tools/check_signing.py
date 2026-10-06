"""校验 Gradle 构建脚本里对「环境变量路径」的处理。

背景（真实故障）：
  CI 里写了
      LING_KEYSTORE_PATH: ${{ ... && 'keystore/ling-release.jks' || '' }}
  未配置签名 Secrets 时它求值成**空字符串**。而空字符串不是 null，
  `System.getenv("X")?.let { file(it) }` 会走进 let 分支并抛出

      IllegalArgumentException: Cannot convert '' to File

  Gradle 在**配置阶段**就崩，连单元测试都跑不到。

本脚本静态拦截这类写法，避免再犯。
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


# ---------- 1. Kotlin DSL 里的 getenv 用法 ----------
# 找出所有 System.getenv("...")...file(...) 的调用，要求含 isNotBlank/ifEmpty 守卫
targets = []
for rel in ("app/build.gradle.kts", "build.gradle.kts"):
    p = os.path.join(ROOT, rel)
    if os.path.exists(p):
        targets.append((rel, open(p, encoding="utf-8").read()))

print("1. System.getenv 的 file() 调用是否有空值守卫")
for rel, src in targets:
    lines = src.split("\n")
    for i, ln in enumerate(lines):
        if "System.getenv(" not in ln:
            continue
        # 把这条语句连同后续续行拼起来（Kotlin 的 ?.let 常常换行）
        stmt = "\n".join(lines[i:i + 4])
        stmt = stmt.split("\n\n")[0]
        if "file(" not in stmt:
            continue
        guarded = ("isNotBlank" in stmt) or ("isNotEmpty" in stmt) \
            or ("ifEmpty" in stmt) or ("takeIf" in stmt)
        loc = f"{rel}:{i+1}"
        if guarded:
            ok(f"{loc} 有空值守卫")
        else:
            bad(f"{loc} 直接 file(getenv(...))，空字符串会抛 "
                f"Cannot convert '' to File")

# ---------- 2. workflow 里不要用 && || 传空串 ----------
print("\n2. workflow 是否用 `cond && 'x' || ''` 传路径")
wf = os.path.join(ROOT, ".github", "workflows", "release.yml")
wfsrc = open(wf, encoding="utf-8").read()
active = "\n".join(ln for ln in wfsrc.split("\n")
                   if not ln.strip().startswith("#"))
# 匹配 ... && '...' || ''  这种三元
hits = re.findall(r"&&\s*'[^']*'\s*\|\|\s*''", active)
if hits:
    for h in hits:
        bad(f"出现空串回退表达式：{h}")
    bad("请改为固定传路径，或干脆不设置该环境变量")
else:
    ok("未使用会把值求成空串的三元表达式")

# ---------- 3. 签名降级逻辑仍然存在 ----------
print("\n3. 签名降级逻辑")
app = open(os.path.join(ROOT, "app", "build.gradle.kts"), encoding="utf-8").read()
for kw, desc in (
    ("signingConfigs", "定义了 signingConfigs"),
    ("hasKeystore", "有 hasKeystore 判定"),
    ('signingConfig = if (hasKeystore)', "无密钥时降级为 unsigned"),
):
    if kw in app:
        ok(desc)
    else:
        bad(f"缺少：{desc}")

print()
print("=" * 55)
if problems:
    print(f"{len(problems)} 个问题：")
    for p in problems:
        print("  ! " + p)
    sys.exit(1)
print("构建脚本签名配置校验通过")
