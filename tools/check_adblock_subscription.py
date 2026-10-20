"""校验广告拦截订阅名单（域名表从"APK 内置"升级为"可订阅"）的接线与取舍。

这一块看着只是"读个文件、存个集合"，但下面每一条被破坏都会以**用户可感知**
的方式出问题：

  - 解析器碰了 Android API   -> JVM 单测跑不起来，脏输入就只能靠真机碰运气
  - 元素规则 `##` 被 `#` 截断 -> 得到"封死整个站点"的域名规则（最危险的雷）
  - `@@` 例外被当成域名      -> 把"放行"理解成"拦截"，行为与用户的预期相反
  - 路径规则截成域名         -> "只拦某路径"变成"封死整个站点"
  - 先整读再判大小           -> 超大文件照读不误，内存直接打爆
  - 每次启停重写十几万行     -> 点一下开关卡住主线程（故拆 .meta / .domains 两个文件）
  - 只在导入时合并域名       -> 热路径每次请求并一遍十万条集合，白白烧 CPU
  - 定义了却不接线           -> 代码全在，UI 上找不到入口（本脚本的核心目的）

纯静态文本检查，不依赖编译。
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


def read(rel):
    return open(os.path.join(ROOT, rel), encoding="utf-8").read()


SRC = "app/src/main/java/com/ling/browser"
rules = read(f"{SRC}/web/BlockRules.kt")
blocker = read(f"{SRC}/web/AdBlocker.kt")
repo = read(f"{SRC}/data/repo/BlockListRepository.kt")
container = read(f"{SRC}/data/AppContainer.kt")
wvtm = read(f"{SRC}/web/WebTabManager.kt")
vm = read(f"{SRC}/ui/BrowserViewModel.kt")
main = read(f"{SRC}/MainActivity.kt")
browsing = read(f"{SRC}/ui/screens/SettingsScreen.kt")
screen = read(f"{SRC}/ui/screens/AdBlockSubscriptionsScreen.kt")

# ---------- 1. 解析器是纯函数 ----------
print("1. 解析器可脱离 Android 单测")
for name in [
    "object BlockRules",
    "fun parse(",
    "internal fun normalizeDomain(",
    "const val MAX_RULE_BYTES",
    "const val MAX_RULES",
]:
    if name in rules:
        ok(f"存在 {name}")
    else:
        bad(f"缺少 {name}")

if re.search(r"^import\s+android", rules, re.M):
    bad("BlockRules.kt 引入了 android.* —— 就不再是纯函数，JVM 单测跑不起来")
else:
    ok("没有引入任何 android.* 依赖（可单测）")

# ---------- 2. 支持约定的三种格式 ----------
print("\n2. 格式兼容：Hosts / AdGuard domains-only / ||domain^")
if re.search(r"fields\.size\s*>=\s*2", rules):
    ok("Hosts 格式取第二个字段（丢弃 IP）")
else:
    bad("未处理 Hosts 的『IP 域名』两字段形式")
if "removePrefix(\"||\")" in rules:
    ok("解析 ||domain^ 域名规则（去掉 || 与前缀）")
else:
    bad("未处理 AdGuard 的 ||domain^ 写法")
if re.search(r"label\.all\s*\{", rules) or "contains('.')" in rules:
    ok("对候选字段做严格域名校验（拒 IP / Unicode / 非法标签）")
else:
    bad("缺少域名合法性校验 —— 会把 IP 或脏数据存进表里")

# ---------- 3. 有意跳过的写法必须被跳过（而不是截成域名） ----------
print("\n3. 元素规则 / 例外 / 路径规则一律跳过")
if "COSMETIC_MARKER" in rules and "containsMatchIn" in rules:
    ok("先识别元素隐藏标记（## / #@# / …），避免被 `#` 截成域名")
else:
    bad("未在剥注释前识别元素规则 —— 会把 example.com##.ad 变成 example.com")
if re.search(r"startsWith\(\"@@\"\)", rules):
    ok("例外白名单 @@ 计入 skipped（不放行、不误拦）")
else:
    bad("未跳过 @@ 例外规则")
if re.search(r"contains\('/'\)", rules):
    ok("路径规则计入 skipped（不截成域名）")
else:
    bad("未跳过路径规则")
if re.search(r"skipped\+\+", rules):
    ok("跳过的行数如实累计（供 UI 报『丢了多少』）")
else:
    bad("未累计 skipped 计数")

# ---------- 4. 存储层的几个关键取舍 ----------
print("\n4. 存储层：双文件、gzip 魔数、上限、IO 调度")
for name, desc in [
    ("META_SUFFIX", "元信息单独一个文件"),
    ("DOMAINS_SUFFIX", "域名列表单独一个文件"),
]:
    if name in repo:
        ok(desc)
    else:
        bad(f"缺少 {name} —— {desc}的目的达不到")
if "GZIP_MAGIC_0" in repo and "GZIP_MAGIC_1" in repo:
    ok("用 gzip 魔数兜底识别压缩内容（不看 Content-Encoding 头）")
else:
    bad("未用魔数兜底 gzip —— 无条件发 gzip 的 CDN 会让导入拿到乱码")
if "MAX_RULE_BYTES" in repo:
    ok("读取时复用 MAX_RULE_BYTES 边读边卡上限")
else:
    bad("读取未卡上限，超大文件会把内存打爆")
if "Dispatchers.IO" in repo and "withContext" in repo:
    ok("文件/网络 IO 一律切到 IO 调度")
else:
    bad("存在未切 IO 调度的 IO 操作")
if re.search(r"url\s*=\s*\"\"", repo):
    ok("本地导入不持久化 SAF 地址（那权限只在本次会话有效）")
else:
    bad("本地导入似乎持久化了 content:// 地址 —— 下次更新会抛权限异常")
if "sha1" in repo:
    ok("网址订阅用哈希当 id（同一网址重复导入即更新，不堆重复条目）")
else:
    bad("网址订阅未用稳定 id —— 重复导入会堆出多条同名订阅")

# ---------- 5. 拦截热路径：预合并而不是现并 ----------
print("\n5. 拦截热路径不现并十万条集合")
if "applyBlockSubscriptions" in wvtm:
    ok("WebTabManager 提供订阅域名注入入口")
else:
    bad("缺少 applyBlockSubscriptions —— 订阅域名进不了拦截判定")
if "mergedBlockRules" in wvtm and re.search(
    r"adBlockRules\s*=\s*\{\s*this@WebTabManager\.mergedBlockRules\s*\}", wvtm
):
    ok("拦截 lambda 读预先算好的并集（不在热路径里现并）")
else:
    bad("拦截 lambda 未读预合并集 —— 每次子资源请求都可能现并十万条")
if re.search(r"applySettings[\s\S]{0,400}?rebuildBlockRules", wvtm):
    ok("设置变化（自定义规则）后重算并集")
else:
    bad("applySettings 未重算并集 —— 改自定义规则后并集是旧的")

# ---------- 6. 仓库与业务层接线 ----------
print("\n6. AppContainer / ViewModel 接线")
if re.search(r"blockList[^\n]*=\s*BlockListRepository\(", container):
    ok("AppContainer 注册了 BlockListRepository")
else:
    bad("AppContainer 未注册 BlockListRepository —— 订阅功能无处取用")
for name in [
    "val blockSubscriptions",
    "fun importBlockSubscriptionFromUrl(",
    "fun importBlockSubscriptionFromLocal(",
    "fun refreshBlockSubscription(",
    "fun setBlockSubscriptionEnabled(",
    "fun removeBlockSubscription(",
]:
    if name in vm:
        ok(f"ViewModel 暴露 {name}")
    else:
        bad(f"ViewModel 缺少 {name}")
if re.search(r"blockList\.enabledDomains\.collect[\s\S]{0,200}?applyBlockSubscriptions", vm):
    ok("ViewModel 订阅 enabledDomains 并送进 WebTabManager")
else:
    bad("ViewModel 未把启用域名送进拦截 —— 订阅与拦截之间断线")

# ---------- 7. UI：设置入口、订阅页、路由 ----------
print("\n7. UI 入口与路由")
if "订阅名单" in browsing and "onOpenBlockSubscriptions" in browsing:
    ok("「浏览」设置页有『订阅名单』入口")
else:
    bad("设置页没有订阅入口 —— 功能等于不存在")
if "fun AdBlockSubscriptionsScreen(" in screen:
    ok("存在 AdBlockSubscriptionsScreen")
else:
    bad("缺少 AdBlockSubscriptionsScreen")
if "OpenDocument" in screen:
    ok("本地导入用 OpenDocument（SAF，无需存储权限）")
else:
    bad("本地导入未用系统选择器")
if re.search(r"data object AdBlockSubscriptions\s*:\s*Route", main):
    ok("Route.AdBlockSubscriptions 已声明")
else:
    bad("缺 Route.AdBlockSubscriptions 声明")
if re.search(r"onOpenBlockSubscriptions\s*=\s*\{\s*push\(Route\.AdBlockSubscriptions\)\s*\}", main):
    ok("设置页入口 push 到订阅页路由")
else:
    bad("设置页入口没有接到订阅页路由")
if re.search(r"Route\.AdBlockSubscriptions\s*->", main) and "AdBlockSubscriptionsScreen(" in main:
    ok("路由渲染 AdBlockSubscriptionsScreen")
else:
    bad("路由没有渲染 AdBlockSubscriptionsScreen")
for need, desc in [
    (r"onImportUrl\s*=\s*viewModel::importBlockSubscriptionFromUrl", "网址导入"),
    (r"onImportLocal\s*=\s*viewModel::importBlockSubscriptionFromLocal", "本地导入"),
    (r"onRefresh\s*=\s*viewModel::refreshBlockSubscription", "更新"),
    (r"onToggle\s*=\s*viewModel::setBlockSubscriptionEnabled", "启停"),
    (r"onRemove\s*=\s*viewModel::removeBlockSubscription", "删除"),
]:
    if re.search(need, main):
        ok(f"{desc}已接到 ViewModel")
    else:
        bad(f"{desc}没有接到 ViewModel")

# ---------- 8. 内置表：精简且条数可查 ----------
print("\n8. 内置精简表")
if "BLOCKED_DOMAINS" in blocker and "val builtinCount" in blocker:
    ok("内置表暴露 builtinCount 供订阅页展示")
else:
    bad("内置表未暴露条数")
m = re.search(r"BLOCKED_DOMAINS:\s*Set<String>\s*=\s*setOf\(([\s\S]*?)\n\s*\)", blocker)
count = len(re.findall(r'"[^"]+"', m.group(1))) if m else 0
if 40 <= count <= 500:
    ok(f"内置表 {count} 条 —— 落在『精简』区间（大名单交给订阅）")
else:
    bad(f"内置表 {count} 条，超出精简区间（40~500）")

# ---------- 9. 单测 ----------
print("\n9. 单测覆盖")
test_file = os.path.join(
    ROOT, "app", "src", "test", "java", "com", "ling", "browser", "web", "BlockRulesTest.kt",
)
if os.path.exists(test_file):
    t = open(test_file, encoding="utf-8").read()
    ok("存在 BlockRulesTest.kt")
    for need, desc in [
        ("0.0.0.0", "Hosts 格式（丢弃 IP）"),
        ("||", "AdGuard 域名规则"),
        ("##", "元素规则跳过"),
        ("@@", "例外规则跳过"),
        ("maxRules", "截断上限"),
        ("normalizeDomain", "域名归一化"),
    ]:
        if need in t:
            ok(f"单测覆盖了{desc}")
        else:
            bad(f"单测未覆盖{desc}")
else:
    bad("缺少 BlockRulesTest.kt")

print("\n" + "=" * 55)
if problems:
    print(f"{len(problems)} 个问题：")
    for p in problems:
        print("  ! " + p)
    sys.exit(1)
print("广告拦截订阅功能的接线与取舍校验通过。")
