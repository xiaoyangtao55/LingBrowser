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

# ---------- 3. 密钥库路径必须用 rootProject.file 解析 ----------
#
# 背景（真实故障，比上面的空串更隐蔽）：
#   app/build.gradle.kts 是 **app 模块**的脚本，裸写
#       file("keystore/ling-release.jks")
#   会解析成 <root>/app/keystore/...，而 CI 把密钥库还原到 <root>/keystore/...
#   两者差一层目录，exists() 恒为 false —— 构建**成功**，产物却是
#   app-release-unsigned.apk，日志与退出码全绿。
#
#   而且它只在设置了 LING_KEYSTORE_PATH 时才发作：本地不设该变量会走
#   `?:` 后面的 rootProject.file(...) 分支而正常。这种"本地好、CI 坏"
#   的不对称让它极难发现。
print("\n3. 密钥库路径是否统一用 rootProject.file 解析")
app_raw = open(os.path.join(ROOT, "app", "build.gradle.kts"), encoding="utf-8").read()

# 去注释后再检查：本文件的注释里**故意**引用了错误写法作为反面教材
# （"裸写 file(...) 会解析成 app/..."），不过滤掉会自己报警自己。
app_lines = app_raw.split("\n")
app = "\n".join(re.sub(r"//.*$", "", ln) for ln in app_lines)

# 只看与 keystore 有关的行，避免误伤其它 file() 用法
ks_lines = [(i + 1, ln) for i, ln in enumerate(app.split("\n"))
            if "keystore" in ln.lower()]
bare = [(n, ln.strip()) for n, ln in ks_lines
        if re.search(r"(?<!\w)file\(", ln) and "rootProject.file(" not in ln]
if bare:
    for n, ln in bare:
        bad(f"app/build.gradle.kts:{n} 用了裸 file()，会相对 app/ 解析而非仓库根：{ln}")
    bad("请改为 rootProject.file(...)，否则 CI 会产出未签名包且不报错")
else:
    ok("keystore 路径均通过 rootProject.file 解析")

# 两处判定（signingConfigs 与 buildTypes.release）必须基准一致。
# 用正则精确匹配**裸 file(...) 调用**（前面不是 . 也不是字母），
# 而不是简单子串 —— 否则注释里引用 `file(it).exists()` 当反面教材
# 会被误判（本文件的注释里正好有这么一句）。
bare_call = re.search(r"(?<![\w.])file\s*\(", app)
if app.count("rootProject.file(") >= 2 and not bare_call:
    ok("signingConfigs 与 release 两处的判定基准一致")
elif bare_call:
    ln = app[:bare_call.start()].count("\n") + 1
    bad(f"app/build.gradle.kts:{ln} 仍有裸 file( 调用，与 rootProject.file 基准不一致")

# ---------- 4. 声明了要签名却找不到密钥库时必须失败 ----------
print("\n4. 「要签名但找不到密钥库」是否会静默降级")
if "GradleException" in app and "LING_KEYSTORE_PATH=$requested" in app:
    ok("显式指定路径却不存在时会抛异常，不会静默产出未签名包")
else:
    bad("指定了 LING_KEYSTORE_PATH 但文件不存在时会静默退化成 unsigned —— "
        "CI 全绿却产出未签名包，这种「成功但做错了事」最难查")

# ---------- 5. 签名降级逻辑仍然存在 ----------
print("\n5. 签名降级逻辑")
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
