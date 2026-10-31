"""校验「设置搜索索引」与设置界面的实际行保持一致。

为什么需要它：总览页的搜索框靠 `settingsSearchIndex` 索引子页里的设置项。
新增一个开关却忘了加索引，那个开关就**搜不到** —— 而用户很难判断
"搜不到"是"没这个功能"还是"索引漏了"。这类静默不一致只能靠脚本抓。

反向也查：索引里写了界面上并不存在的项，点进去会落到一个没有该设置的分组。

约定：
  - 索引里的每一项必须能在界面上找到同名行；
  - 界面上的每一行要么在索引里，要么属于「弹窗内的勾选项」
    （这类选项不是独立设置行，作为关键词挂在宿主行上即可）。
"""
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
SRC = os.path.join(
    ROOT, "app", "src", "main", "java", "com", "ling", "browser",
    "ui", "screens", "SettingsScreen.kt",
)

# 弹窗内的勾选项：它们没有独立的设置行，作为 keywords 挂在「清除浏览数据」上。
DIALOG_ONLY = {"历史记录", "Cookie 与站点数据", "缓存", "标签页会话"}

problems = []


def bad(m):
    problems.append(m)
    print("  FAIL " + m)


def ok(m):
    print("  OK   " + m)


def main():
    if not os.path.exists(SRC):
        sys.exit(f"找不到 {SRC}")
    text = open(SRC, encoding="utf-8").read()

    # (1) 索引区
    start = text.find("private val settingsSearchIndex")
    if start < 0:
        sys.exit("找不到 settingsSearchIndex")
    end = text.find("private fun settingsEntries", start)
    if end < 0:
        end = len(text)
    index_block = text[start:end]

    indexed = re.findall(r'SettingsSearchItem\(\s*\n?\s*"([^"]+)"', index_block)
    if not indexed:
        sys.exit("settingsSearchIndex 里没解析到任何项 —— 格式变了？")
    print(f"索引项：{len(indexed)} 个")

    # (2) 界面上的行标题
    rows = re.findall(
        r'(?:SettingRow|SwitchRow|ClearOptionRow)\(\s*\n\s*[^)]*?title = "([^"]+)"',
        text,
        re.S,
    )
    rows = list(dict.fromkeys(rows))
    print(f"界面行：{len(rows)} 个")
    print()

    # (3) 索引 -> 界面
    missing_in_ui = [t for t in indexed if t not in rows]
    if missing_in_ui:
        bad("索引里有、界面上找不到：" + "、".join(missing_in_ui))
    else:
        ok("索引里的每一项都能在界面上找到")

    # (4) 界面 -> 索引
    not_indexed = [t for t in rows if t not in indexed and t not in DIALOG_ONLY]
    if not_indexed:
        bad("界面上有、但没加进搜索索引：" + "、".join(not_indexed)
            + "  —— 用户搜不到它")
    else:
        ok("界面上的每一项都已被索引覆盖（弹窗选项除外）")

    # (5) 重复项
    dup = [t for t in set(indexed) if indexed.count(t) > 1]
    if dup:
        bad("索引里有重复项：" + "、".join(dup))
    else:
        ok("索引没有重复项")

    # (6) 每一项都带 group（漏了 group 会编译不过，这里只查格式漂移）
    if index_block.count("SettingsSearchItem(") != len(indexed):
        bad("SettingsSearchItem 的数量与解析出的标题数不一致 —— 可能有换行格式问题")
    else:
        ok("每一个 SettingsSearchItem 都解析到了标题")

    print()
    if problems:
        print("=" * 55)
        print(f"{len(problems)} 个问题：")
        for p in problems:
            print("  ! " + p)
        sys.exit(1)
    print("=" * 55)
    print("设置搜索索引校验通过")


if __name__ == "__main__":
    main()
