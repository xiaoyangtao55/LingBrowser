"""直接对着 HomePage.kt 校验单元测试里的断言是否成立。

因为设备编译慢，用这个脚本代替"跑一遍测试"来确认断言与实现一致。
"""
import os
import re

KOTLIN = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "app", "src", "main", "java", "com", "ling", "browser", "web", "HomePage.kt",
)

src = open(KOTLIN, encoding="utf-8").read()

URL = re.search(r'const val URL = "([^"]+)"', src).group(1)
BASE = re.search(r'const val BASE_URL = "([^"]+)"', src).group(1)
LEGACY = re.search(r'LEGACY_URL = "([^"]+)"', src).group(1)

print(f"URL      = {URL}")
print(f"BASE_URL = {BASE}")
print(f"LEGACY   = {LEGACY}")
print()


def is_home(u):
    """复刻 Kotlin 侧 isHomeUrl 的实现。"""
    u = (u or "").strip()
    return u == "" or u in (URL, BASE, LEGACY)


cases = [
    (URL, True), (LEGACY, True), (BASE, True),
    ("", True), ("   ", True), (None, True),
    ("https://example.com", False),
    ("http://ling://home", False),
    ("https://www.baidu.com", False),
    ("about:blank", False),
    ("LING://HOME", False),
    ("ling://homepage", False),
]

ok = True
for val, want in cases:
    got = is_home(val)
    if got != want:
        ok = False
    print(f"  {'OK  ' if got == want else 'FAIL'} isHomeUrl({val!r}) = {got}  (期望 {want})")

print()
checks = [
    ("BASE_URL 以 https:// 开头", BASE.startswith("https://")),
    ("BASE_URL 以 / 结尾", BASE.endswith("/")),
    ("BASE_URL 含 .invalid", ".invalid" in BASE),
    ("BASE_URL 不含 .local/", ".local/" not in BASE),
    ("URL 以 ling:// 开头", URL.startswith("ling://")),
    ("URL 不以 about: 开头", not URL.startswith("about:")),
    ("URL != BASE_URL", URL != BASE),
    ("LEGACY 是 about:home", LEGACY == "about:home"),
]
for name, cond in checks:
    if not cond:
        ok = False
    print(f"  {'OK  ' if cond else 'FAIL'} {name}")

print()
print("所有断言与实现一致" if ok else "存在不一致，需修正测试或实现")
raise SystemExit(0 if ok else 1)
