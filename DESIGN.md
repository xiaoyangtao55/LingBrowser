# 翎 · 设计取舍与技术笔记

> 本文是 [README.md](README.md) 的配套文档，记录「翎」在实现过程中做过的
> 关键取舍、踩过的坑，以及每个反直觉决定背后的原因。

README 只保留「是什么 / 怎么用 / 怎么构建」，设计细节全部收在这里 ——
两者分开是因为：**新用户读 README 需要的是快速上手，而这里的每一条都是
改代码前应该知道的背景**，混在一起会让 README 长到没法读。

## 目录

| # | 主题 |
|---|---|
| 1 | 为什么不用 Room |
| 2 | 图标：矢量代码而非字体 |
| 3 | WebView 实例复用与回收 |
| 4 | 主页由 Compose 渲染 |
| 5 | SVG 主题色必须走 CSS |
| 6 | 历史记录用 UPDATE 而非 REPLACE |
| 7 | 返回栈用显式开关 |
| 8 | 内部页的「逻辑地址」 |
| 9 | 「定义了但没人调用」最危险 |
| 10 | 主页配色不再需要抢跑 |
| 11 | 阅读视图主题原地刷新 |
| 12 | 内部页被误伤的三条路 |
| 13 | 进程级状态与文档重建 |
| 14 | 两个「入口」需求 |
| 15 | CI 抓到的第一处编译错误 |
| 16 | 主页搜索框 |
| 17 | 内部页的「对外地址」 |

---
## 1. 为什么不用 Room？
Room 依赖 KSP 注解处理，而 **AGP 9.x 内置了 Kotlin 支持**，与独立 KSP 插件在
source set 注册上冲突（`Using kotlin.sourceSets DSL to add Kotlin sources is not
allowed with built-in Kotlin`）。本项目只有 3 张结构简单的表，直接用平台自带的
`SQLiteOpenHelper` 手写 SQL，少一层构建复杂度，也更贴合轻量定位。

## 2. 图标：为什么不用字体，也不用 material-icons-extended？

三种方案的实测代价对比：

| 方案 | 代价 |
|---|---|
| `material-icons-extended` | 上万个图标全部编进 dex，单个 `classes.dex` 达 **40+ MB**，debug 包 **18 MB** |
| Material Symbols TTF | 单文件 **948 KB**（Filled 版 1.4 MB），几乎等于整个 APK |
| **矢量代码（本方案）** | 44 个图标约 **90 KB** 源码 |

Via 这类浏览器整个安装包才几百 KB。**一个图标字体就顶得上当前 APK（1.4 MB）
的 68%**，为二十几个图标翻倍不划算。而且字体图标无法参与 Compose 的
tint 与交互动画，取字形还要按 Unicode 码点逐个核对。

因此改为把官方图标**转成 Compose 的 ImageVector 代码**：
造型是 Material Symbols 的原始坐标（一位不差），只是换了个表达形式。

### 坐标网格：保留官方的 960，不缩放到 24
官方导出的是 **960×960** 视口。转换时**不做缩放** —— 960 是 Google 的原始
设计网格，保留它意味着坐标能与官方文件逐位对照，出问题可直接 diff；
缩放反而引入浮点误差。渲染尺寸由 `defaultWidth/Height = 24.dp` 控制，
与视口无关。

### 又踩了一次 `arcTo` 的坑……这次是它的"命名兄弟"
早期版本用手绘描边图标，用 `arcTo` + `largeArc` 画圆弧，当起点与终点恰好
落在圆的**直径两端**时圆弧退化成半圆饼，刷新图标被画成"水桶"、锁梁被画成
实心矩形。现在官方源文件只含 `M`/`L`/`Q`/`Z`，转换器**遇到其它命令直接报错**，
不会静默丢数据。

转换时另有一个纯粹的 API 命名坑：SVG `pathData` 里的 `Q`，在 Compose
`PathBuilder` 上叫 **`quadTo`** —— 直觉会写成 `quadraticTo` 或
`quadraticBezierTo`，**两个都不存在**。正确做法是 `javap` 查一遍：
`moveTo` / `lineTo` / `quadTo` / `curveTo` / `arcTo` / `close`。

### 收藏状态：靠「实心 / 空心」区分，不靠加号
| 状态 | 图标 | 官方源文件 |
|---|---|---|
| 未收藏 | 空心书签 | `bookmark` |
| 已收藏 | **实心**书签带加号 | `bookmark_added_fill` |

⚠️ 极易搞错的是 **`bookmark_added`（不带 `_fill`）其实是空心版**，
只比 `bookmark` 多一个加号，在 24dp 下两种状态几乎分不出来。
区分靠的是**填充**，不是有没有加号。`LingIconsTest` 里有一条用例
专门锁定这个区分（实心版坐标数必须少于空心版）。

> 之前两个状态都用同一个空心图标，用户只能读菜单文字才知道当前
> 是否已收藏 —— 顺手一并修了。

### 校验工具链

| 文件 | 作用 |
|---|---|
| `tools/import_material_icons.py` | 解析官方 VectorDrawable，转成 Compose 路径代码（缺图标会明确报错） |
| `tools/emit_ling_icons.py` | 组装成完整的 `LingIcons.kt` |
| `tools/ascii_icons.py` | 扫描线填充（even-odd）渲染成 ASCII，编译前即可肉眼核对 |
| `tools/check_icon_fidelity.py` | **重新解析源文件，与生成物逐点比对**，防止"源改了、生成物没重跑"的漂移 |
| `app/src/test/.../LingIconsTest.kt` | 10 个用例：禁止 `arcTo`、坐标不越界、填充可见、视口与默认尺寸一致、收藏两态形状不同 |

> `check_icon_fidelity.py` 是这里最有用的一环：图标漂移**极难用肉眼发现**，
> 少一个控制点、坐标差 40，渲染出来看着都"差不多"。已验证把某个坐标
> 改掉 40 单位后它会精确报出「#14 源=480.0 生成=520.0」。

> 全部 44 个图标均来自官方文件，**没有合成图标**。
> （第 28 个是阅读模式用的 `article`，由用户从 fonts.google.com
> 下载官方 Outlined 版后导入，仍走 `check_icon_fidelity.py` 逐点核对。）

> **三个书签图标容易搞混**，按用途对齐：
>
> | 用途 | 图标 | Material 名 | 形状 |
> |---|---|---|---|
> | 未收藏（状态/入口） | `BookmarkBorder` | `bookmark` | 空心书签 |
> | 已收藏（状态） | `Bookmark` | `bookmark_added_fill` | **实心**书签 |
> | 添加书签（**动作**） | `BookmarkAdd` | `bookmark_add` | 空心书签 **+ 加号** |
>
> ①区分"已收藏/未收藏"靠的是**填充**，不是有没有加号 ——
> `bookmark_added`（不带 `_fill`）也是**空心**的，只比 `bookmark` 多一个
> 加号，在 24dp 下几乎分不出来。
>
> ②"添加书签"是个**动作**，必须用带加号的 `bookmark_add`。
> 原先它用的是纯 `bookmark`，于是和菜单下面那个「书签」入口（跳转书签列表）
> **图标一模一样**，用户分不出哪个是动作、哪个是跳转。

> 旧的 `render_icons_kotlin.py` / `render_icons.py` 解析的是手绘描边的
> `arcPolyline` API，换成官方图标后已解析不出任何图形，故删除。

## 3. WebView 实例复用
WebView 创建代价高（每个实例约数 MB 原生内存）。`WebTabManager` 为每个标签页
保留长期存活的实例，切换标签只是把对应 View 挂到宿主 `FrameLayout`；
实例数超过 6 个时按 LRU 回收最久未使用的非激活实例。

## 3.1 标签缩略图：favicon，不是网页截图
选择 favicon 而非整页截图，理由是**内存**：

| 方案 | 单张成本 | 6 个标签 |
|---|---|---|
| 整页截图（`WebView.draw` 到全屏 Bitmap） | 1080×2000×4B ≈ **8.6 MB** | **~50 MB** |
| favicon（16~64px） | 数 KB | 可忽略 |

中低端机上 50 MB 常驻随时可能 OOM；而且离屏 WebView 的内容不保证完整，
截出来常常是空白。favicon 一眼能认出站点，符合轻量定位。

**两个来源都要接**：`WebChromeClient.onReceivedIcon` 只在 WebView 真正拿到
图标时触发，很多站点要等解析到 `<link rel="icon">` 才给；而
`WebViewClient.onPageStarted(view, url, favicon)` 的第三个参数在加载一开始
就有值。后者此前被**直接丢弃**，是缩略图一直空着的原因。两者都汇到
`applyFavicon()`，逻辑只有一份。

**Bitmap 必须显式回收**：像素是非托管内存，GC 只收得回 Java 对象壳。
因此 `closeTab` / `destroyAll` 都会 `recycle()`，且回收前必须查
`isRecycled` —— 同一个 Bitmap 可能被多个 `TabState` 共享
（`copy()` 复制的是引用不是数据），重复回收会抛异常。

Compose 侧绘制前同样要查 `isRecycled`：我们会主动回收，而 Compose 可能
还拿着同一个引用再画一帧，直接绘制会抛
`Canvas: trying to use a recycled bitmap`。

> 无 favicon 时显示站点首字母。这里有个坑：不能直接用 `UrlUtils.hostOf`，
> 它在解析失败时会**退回原始 URL 字符串**，于是 `about:blank` 得到 "A"、
> `ling://home` 得到 "L"，看着像站点名实则毫无意义。主页与无法解析的
> 地址统一给中性占位符 `•`。`www.` 前缀也会跳过，否则满屏都是 "W"。

## 3.2 书签的网站图标：自己抓，存字节

书签的图标**不能**复用 `onReceivedIcon`：书签往往很久以后才需要显示，
那时页面早已关闭、回调早过去了，也没有系统级图标服务可用。只能按 URL
自己去取 `/favicon.ico`（取不到再看主页 HTML 里的 `<link rel=icon>`）。

三条硬约束，都是为省流量和避免卡顿：

| 约束 | 原因 |
|---|---|
| 只跟随标准位置，不做完整 HTML 解析 | 为一个 16px 图标引入 HTML 解析器不划算 |
| 边读边数字节，超过 512 KB 放弃 | 有些站点返回几十 MB 的图，不拦会打爆内存 |
| 存库前缩到 96px 并压成 WebP | 原始 PNG 几十 KB，压缩后几 KB；上百条书签差距很大 |

**存字节（`ByteArray`）而不是 `Bitmap`**。`Bitmap` 的像素在非托管内存里，
需要手动 `recycle`，而书签列表随时可能重建、同一个对象可能被多处引用 ——
一旦某处回收掉，别处就会抛 `Canvas: trying to use a recycled bitmap`。
存字节完全绕开生命周期问题，代价只是显示时解码一次（按 id 缓存）。
含 `ByteArray` 的 data class 还必须**手写 `equals`/`hashCode`**：
自动生成的版本比较的是数组**引用**，每次从库里读出来都是新数组，
会被误判成"内容变了"而反复重组。

**失败要写占位**：抓不到图标时存一个**空数组**而不是留 `null`。
空数组表示"试过了但失败"，`urlsMissingFavicon` 只把 `null` 当作缺失。
不区分的话，每次打开书签页都会重新请求同一批注定失败的站点，白耗流量。

**升级不能丢数据**。这一版把数据库从 2 升到 3（新增 `favicon` 列）。
原先的 `onUpgrade` 是**无条件 `DROP TABLE` 重建**，注释写着"尚未正式发版
即可"——但真机上已经装了带书签/历史的版本，重建就是把收藏全抹掉。
现改为 `if (oldVersion < 3) ALTER TABLE ...` 增量迁移，且按版本区间判断，
保证能从任意旧版本逐级升上来。

> **一个差点犯错的地方**：收藏时的图标优先复用页面已加载的那张
> （`TabState.favicon`，缩略图用的就是它），省掉一次网络请求；
> 拿不到才走后台抓取。

## 3.3 拖拽排序：长按才能拖
用 `detectDragGesturesAfterLongPress` 而非普通拖拽 —— 短按要留给
「点击切换标签」，普通拖拽还会和列表滚动打架。

两个容易踩的 Compose 陷阱：

**① `pointerInput` 的 key 不能含 index。** 换位后列表重排、index 变化，
key 一变手势识别器就被重启，**拖动中途会断掉**。只用 `tab.id` 作 key。

**② 手势回调里不能用闭包捕获的 index。** `pointerInput` 的 lambda 在首次
组合时就被捕获，之后 `tabs` 变了它仍指向旧实例。拖拽会实时改变顺序，
用旧列表算下标必然错位。所以用一个 `remember` 的持有者（每次重组写入最新
列表），`onDragStart` 时**现查**下标。

位移换算：手指数跨过一整行（`rowHeightPx` 实测值，不硬编码）才换位，
换位后必须把**已消耗的位移扣掉**，否则拖动会累积成连续换位而失控。

> `moveTab` 的语义是 remove + add 而**不是 swap**：`[A,B,C,D].move(0,2)`
> 得到 `[B,C,A,D]`——被拖的项落到目标下标，其余项依次让位。
> 另外 Kotlin 会**先求值全部实参**再调用 `add`，所以
> `add(to, removeAt(from))` 里 `to` 已是正确下标，**不需要**任何 ±1 补偿
> （曾以为需要，实测 `move(0,3)` 得到 `[B,C,D,A]` 才发现补偿反而错位）。

## 3.4 会话恢复：四个必须想清楚的点

**① 保存挂在 `onStop`，不是 `onDestroy`。** 进程被系统回收时
`onDestroy` **不保证被调用**（这正是低内存杀后台的常见路径），
而 `onStop` 一定会走到。用 `onDestroy` 会在"切到别的 App 后被回收"
这一最常见场景下丢掉全部标签。

**② 保存不能用 `viewModelScope`。** `onStop` 之后 Activity 可能很快销毁，
`viewModelScope` 随之被取消 —— 写库写到一半就被打断。所以
`persistSession()` 用独立的 `CoroutineScope(Dispatchers.IO)` 保证跑完。

**③ 恢复是异步的，开主页的兜底必须排在它之后。** 数据库读取是挂起操作，
不能同步判断 `tabs.isEmpty()` 就开主页——那样会先开一个主页、
再被恢复的标签叠加，用户看到一个多余的空标签。正确顺序是
`load()` → `restore()` → 失败才 `newTab()`。

**④ 无痕标签绝不落盘。** 无痕模式的意义就是"关掉不留痕"，
把 URL 写进 SQLite 会让用户下次启动看到上次无痕浏览的网站。
`TabSnapshot.isPersistable` 过滤一次，`SessionRepository.save` 再过滤一次
（双保险），并有一条单元测试专门钉住这条承诺。

其他细节：
- 快照读完**立刻清掉**。否则用户"清除数据"后重启，遗留的快照会把
  标签页复活，看起来像清除没生效。
- 写入用**事务**：中途失败时要么全是旧数据、要么全是新数据，
  不会出现"删了旧的、还没写新的"的空窗（那会让标签全丢）。
- 只恢复**第一个**标签的 WebView，其余等用户切过去再懒加载
  （复用已有的 LRU 机制）。一次建 N 个 WebView 会让冷启动明显变卡。

> **一个差点埋下的坑**：`tabs` 表、`TabSnapshot`、`toTabSnapshot()`
> 其实早就写好了 —— 但 `toTabSnapshot()` **从未被任何代码调用**，
> 整套持久化是死代码，标签从来没有真正恢复过。`check_session.py`
> 第一项就是防这个："只有定义没有调用"会直接报错。

**设置项：恢复上次浏览页面**（默认开）。关掉后每次启动都是一个干净的主页。

关闭时**连保存也一起停掉**，不是只跳过读取——选择"不恢复"通常意味着
不想让浏览记录留在磁盘上，继续写盘违背这个预期。关闭的瞬间还会把
已存的快照清掉：否则用户关掉开关、重启一次、再打开开关，会看到
**关闭之前**那一批早就该被遗忘的标签，既意外又像 bug。

## 3.5 书签文件夹：不建 folders 表

只有**一层**文件夹（进入文件夹后看到的就是书签，不能再往下钻）。
这是刻意取舍：手机屏幕上的多层树需要面包屑 + 深度指示，
收益远不如实现与心智成本，Chrome 手机版同样是扁平布局。

关键决定是**不建 folders 表**，文件夹名从"哪些书签归属于它"派生：

```kotlin
internal fun deriveFolders(bookmarks: List<Bookmark>): List<String> =
    bookmarks.mapNotNull { it.folder?.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .sortedWith(String.CASE_INSENSITIVE_ORDER)
```

单独建表会引入两个真相来源——删掉最后一个书签后，表里还留着一个空壳，
用户看到点进去什么都没有的**幽灵文件夹**。派生法天然不会出现这种状态。

代价是重命名文件夹要一条 `UPDATE` 批量改所有归属书签（而不是逐条 move，
那样中途失败会留下半新半旧的文件夹名）。书签量级很小，完全可以接受。

几个容易写错的规则，都由 `BookmarkFolderTest` 钉住：
- 名字先 `trim`：`" 新闻 "` 和 `"新闻"` 是同一个，否则显示成两个看起来
  完全一样的条目
- 空串/纯空白**不算**文件夹（早期 insert 可能写过空串，当未分类处理）
- 去重**大小写敏感**（`News` 与 `news` 算两个）：中文场景无影响，
  而英文用户确实可能想要两个不同的文件夹
- 用 `CASE_INSENSITIVE_ORDER` 排序：否则 `Z`(90) 会排在 `a`(97) 前面，
  用户看到"大写开头的全挤在小写前面"这种莫名其妙的分组

**两个必须有的兜底**：

1. **删除文件夹不删书签**，只把归属置为 `null`（退回"未分类"）。
   用户说"删掉这个文件夹"时，几乎不会是要把收藏一起丢掉。
   确认框里也明确写了这一点——这是用户最担心的。
2. **当前文件夹消失后退回根目录**。删掉文件夹里最后一个书签后，
   `deriveFolders` 里就没有它了；不兜底的话用户会卡在一个标题还在、
   内容永远为空的页面上出不去。

> **一个容易忽略的地方**：文件夹由书签派生，所以"把书签放进一个新文件夹"
> 是**唯一**能创建文件夹的路径。移动菜单里必须有「新建文件夹…」这一项，
> 否则**永远建不出第一个文件夹**。`check_bookmark_folders.py` 专门盯着它。

## 3.6 地址栏提交后必须把焦点**交还**给网页

一个真机上发现的 bug：地址栏输入后按回车，页面加载了，但**网页里的输入框
唤不起输入法**。这个问题**修了两次才对**，两次的思路差异值得记下来。

**第一版（错）**：以为问题是"地址栏没放开焦点"，于是加 `clearFocus()`：

```kotlin
focusManager.clearFocus()   // 光标确实消失了
keyboard?.hide()
onFocusChange(false)
```

真机实测：**光标消失了，但网页输入框依然唤不起输入法**。

**用户的复现步骤给出了决定性线索**：

| 观察 | 说明 |
|---|---|
| 回车后光标消失 | `clearFocus()` **生效了** |
| 网页输入框仍唤不起输入法 | 问题不在"地址栏有没有放开焦点" |
| 点地址栏键盘能起来 | IME 本身正常 |
| 回到网页，**光标又出现了** | 有东西把焦点抢回去了 |

最后一条是关键：焦点不是"还在地址栏"，而是**悬空了**。

**真正的根因**：Compose 的 `clearFocus()` 只是让焦点消失，
**并不会**把焦点交回 WebView。没有任何 View 持有焦点时，用户点网页输入框，
IME 认为没有可输入的焦点，键盘就不弹。而"光标又出现"正是**焦点没有归属**
的表现 —— 剩下唯一可聚焦的就是地址栏，于是它又被选中。

> 教训：第一版做的是**减法**（释放焦点），但真正需要的是**加法**（交还焦点）。
> 更麻烦的是，当时的静态检查只盯着「有没有 `clearFocus()`」——
> 第一版**满足了检查却没解决问题**。这类"检查覆盖不到真正根因"比漏检更危险，
> 因此 `check_ux_fixes.py` 第 1 节已改为检查「有没有把焦点**交还**回去」。

修法是四步，缺一不可：

```kotlin
focusManager.clearFocus()   // 1. 释放焦点，光标随之消失
keyboard?.hide()            // 2. 收起软键盘
onFocusChange(false)        // 3. ViewModel 状态与真实焦点保持一致
onReleaseFocus()            // 4. ★ 把焦点交给 WebView（前三个都做了也不够）
```

第 4 步背后是三件事（`WebTabManager.focusWebContent()`）：

1. **宿主 `FrameLayout` 必须显式可聚焦** —— 它默认 `focusable = false`，
   而 WebView 是被 `addView` 进去的子 View；父容器不可聚焦时，
   触摸能到 WebView，但焦点无法在 View 树里正常流转
2. **`requestFocus()` 要 post 到下一帧** —— 它与 `clearFocus()` 在同一帧里
   被调用，而 Android 的焦点变化要等这帧 View 树遍历结束才生效，
   紧接着 requestFocus 可能被丢弃（表现为"偶尔灵、偶尔不灵"）
3. **用 JS 把焦点下沉到 `body`** —— View 层拿到焦点**不等于**网页内的
   `input` 拿到焦点，后者由 Blink 内部管理，需要 JS 帮一把

另加一道保险：`setOnTouchListener` 里若 WebView 未聚焦则取回
（只观察不消费，返回 `false`，不影响滚动与点击）。

> ⚠️ 刻意**不加** `setOnFocusChangeListener -> focusWebContent`：
> 那会在 `focusWebContent` 内部 `requestFocus` 时再次触发监听器，
> 形成**无限递归**。`check_ux_fixes.py` 专门盯着这一条。

真机确认日志（四个值全为 `true`，说明焦点确实落到了 WebView 而非悬空）：

```
D LingFocus: focusWebContent host=true webview=true wvHasFocus=true hostHasFocus=true
```

确认修复后该诊断日志已移除。

**顺带修掉两个连带问题**：

- `onFocusChange` 这个参数**从未被调用过** —— 它声明在 `AddressBar` 的参数表里，
  但没有任何地方接上 `Modifier.onFocusChanged`，编辑态完全靠手动同步。
  现已补上观察者。
- **失焦竞态**：失焦回调会调 `syncAddressFromActiveTab()`，而那一刻
  `tabManager.loadUrl` 可能还没把 `activeTab().url` 更新过来（WebView 的 URL
  要等页面真正开始加载才变），于是地址栏被清空 —— 表现为"回车后闪一下空白"。
  现在 `submitAddress` 显式锁定地址栏文本。

## 3.7 阅读模式：移植 Readability 的核心，并做中文适配

**不引入 Readability.js**：它约 90 KB，而整个 APK 才 1.5 MB —— 引进来会让
包体积增加 6%，与「轻量」定位直接冲突。这里的重写版不到 11 KB。

算法保留 Readability 的**打分**精髓（不是随便抓一个 `<div>`）：

1. 给每个 `<p>` 按长度与标点数量打分，分数**向上累加给两级祖先**
   —— 正文段落本身长，且它们的共同祖先才是正文容器
2. 累加时按标签给不同起点分：`div`/`section` 是通用容器（5 分），
   `article`/`main` 语义上更可能是正文（10 分）
3. 用**链接密度**剔除导航与相关阅读：整块都是链接的不算正文
   （段落阈值 0.25，容器阈值 0.33）
4. 按 class/id 语义直接淘汰明显不是正文的节点（`comment`/`sidebar`/`related`…）

### 一个必须解决的中文问题

Readability 原文用 `textLength >= 100` 判断段落是否够长，**那个阈值是为英文调的**。
中文一个字承载的信息量远大于一个字母，100 个汉字的篇幅约等于 300+ 英文单词。

实测后果很严重：三段正常的中文段落（65 / 82 / 72 字）
**全部不达标**，整篇文章会被判成「没有正文」，也就是**中文站点几乎全部失效**。

修法是按 CJK 折算加权长度（CJK 字符 × 2.5 + 其余），三处阈值统一使用：
可读性判断、段落最低长度、最终正文长度校验。标点正则也必须含中文标点
（`，。；！？、`），否则中文段落逗号数为 0、分数被严重低估。

> 这两点都不是"想到了所以加上"，而是**写完 JS 用 Node 真实跑了一遍**
> 才暴露出来的 —— 见下面的验证方式。

### 原文地址：不能信 `location.href`

真机报过：

```
位于 zhihu://answers/2090101772673085808?mcid=... 的网页无法加载，因为：
net::ERR_UNKNOWN_URL_SCHEME
```

知乎用**自定义协议**做内部跳转，`location.href` 就是那个地址。它先被记进
标签状态，退出阅读模式时又被回填给 `wv.loadUrl()` —— WebView 不认识
`zhihu://`，于是白屏。（提取脚本原本直接 `url: location.href`，
"查看原网页"同样指向一个打不开的地址。）

修法是**三层防线**，缺一层都还漏：

| 层 | 做法 |
|---|---|
| 取地址 | 优先 `link[rel=canonical]`，退回 `og:url`；只采纳能解析成 http(s) 的 |
| 记状态 | 回调里自定义协议**不写回** `TabState.url`（它已被交给系统应用，页面并没真导航过去） |
| 加载 | `loadUrl` 入口按协议白名单拒绝；`exitReaderMode` 先校验再回填，不可加载时退回历史 → 主页 |

顺带一个反直觉的点：**相对 canonical 的 base 也得净化**。
`zhihu://answers/1` + `/post/2` 会解析成 `zhihu://answers/post/2` ——
仍是自定义协议，等于白读了 canonical。所以 base 要先用
`document.baseURI`（文档真实来源）兜底。这个 bug 是检查脚本的
"相对路径"场景抓出来的，不是想出来的。

> 协议判定抽到了 [UrlScheme.kt](<app/src/main/java/com/ling/browser/web/UrlScheme.kt>)，
> 因为留在 `WebTabManager` 里就得构造 WebView/Context 才能测，实际等于测不了。

### `shouldOverrideUrlLoading` 的返回值语义（"一直闪"的根因）

App 内嵌页里的自定义协议导航，`handleUri` 原本写成：

```kotlin
else -> onExternalScheme(uri)   // ← 错
```

`onExternalScheme` 在系统没有能处理该协议的应用时返回 `false`，而
**`shouldOverrideUrlLoading` 返回 `false` 的意思是"我没处理，WebView 你来加载"**。
于是 WebView 去加载 `zhihu://...`，得到 `net::ERR_UNKNOWN_URL_SCHEME`；
页面若反复发起同一次导航，就在"尝试加载 → 报错"之间循环 ——
真机上表现就是**回答页一直闪**。

正确写法是**无条件接管**：

```kotlin
if (!UrlScheme.shouldTakeOver(uri.toString())) return false
if (scheme == "ling") return true
onExternalScheme(uri)
return true   // 系统能打开就打开，打不开就安静地什么都不做
```

系统打不开时"什么也不发生"，远好于渲染一个必然失败的错误页。

顺带修了 `intent://`：它必须走 `Intent.parseUri` 还原成目标 Intent，
直接 `ACTION_VIEW` 包一个 `intent:` 地址是没有应用能接的，会**静默失败**
（用户看到"点了没反应"）。

### 「用外部 App 打开链接」开关（默认关）

即使把返回值语义修对，**把非 http(s) 链接交给系统这条路本身也常常没有意义**。
真机日志（Xiaomi MI 8 / Android 14，抓自开阅读模式的时刻）：

```
cr_WebViewApkApp: version=125.0.6422.165 ... processName=com.android.webview:sandboxed_process0
chromium: [WARNING:sync_reader.cc(175)] ASR: No room in socket buffer.: Broken pipe (32)
```

两点值得注意：

1. **整段日志里没有一条 `ActivityManager: START ... act=android.intent.action.VIEW`** ——
   说明 `startActivity` 根本没成功。本机没装知乎 App（或它没有 `zhihu://` 过滤器），
   "交给系统"是空转。
2. 同时出现 `sync_reader.cc … Broken pipe` 与一个**全新的 sandboxed renderer 进程**，
   说明渲染进程被拆掉重建过 —— 页面卡在中间态是这么来的。

所以加了这个开关，**默认关**：关掉时连 `onExternalScheme` 都不调用，
这类导航被静默吃掉（返回 `true`，不放行给 WebView）。
打开时才走 `ACTION_VIEW` 转发，行为与主流浏览器一致。

> 为什么默认关而不是默认开：App 内嵌页用自定义协议做的多半是**内部跳转**
> （「在 App 中打开」之类），而浏览器用户在自己手机的文件管理器里打开
> 一个浏览器，通常并不是想被弹去另一个 App。加上本机可能没装那个 App，
> 默认关掉是更稳的选择。设置项里写清了原因，免得被当成漏做的功能。

> **仍未定位**：回答页上进入阅读模式这件事本身还没修好（不闪了，但也不行）。
> 上面那两条日志只能说明"转发失败 + 渲染进程重建"，**不足以断定**阅读模式
> 失败的根因。需要带 `chromium`/`ActivityManager` 标签的完整日志，
> 且最好是打开开关与关闭开关各抓一次，才能对比出是哪条路径的问题。

### 页面没加载完就进阅读模式

真机上"回答页加载完成前开阅读模式"会出问题，原因有两个：

- 提取脚本跑在**半成品 DOM** 上，正文可能只有一半；
- `canonical` / `og:url` 往往还没解析到，于是原文地址退回 `location.href`，
  又绕回自定义协议那个坑。

所以 `enterReaderMode` 现在用 `TabState.isLoading || wv.progress < 100`
双重判据拦住，并给出"页面还在加载，请稍候再试"。用两个判据是因为
`isLoading` 在部分站点会因跳转时序提前变 `false`，而 `progress` 是
WebView 自己报的、更贴近真实渲染进度。

另外提取是**异步**的，回调回来时用户可能已经切走，因此加了
`_activeId != id` 校验 —— 否则会把 A 页的正文渲染到 B 页上。

### 分层与三个现实约束

正文提取必须注入 JS 到原页面执行（DOM 只在那边），渲染则回到原生侧生成
自包含 HTML（与主页同一套路）。由此带来三个必须处理的现实情况：

| 情况 | 处理 |
|---|---|
| 用户关掉了 JavaScript | 提取脚本根本不会执行。明确提示"需要 JavaScript"，因为**用户自己能解决**，不能笼统说"不支持" |
| 页面本就不适合阅读（首页/视频页/图片站） | 属于正常情况，提示要温和，不能像报错 |
| 主页与阅读视图自身 | 直接拒绝，否则会递归提取 |

**必须缓存已提取的正文**：进入阅读视图后原 DOM 已被替换，此时用户调字号若
重新提取只会得到"没有正文"。所以缓存一份结果用于重排版，并在关闭标签时清掉。

**调字号要把新值显式传下去**，不能让它去读 `settings.readerFontSize` ——
设置是异步落盘的，此刻读到的还是旧值，表现为"改了字号没反应，要再点一次"。

### 一个差点漏掉的 Compose 陷阱

`readerActive` 最初写成 `val readerActive: Boolean get() = tabManager.isReaderActive()`。
它不是 Compose 状态，**进入阅读模式后菜单文案不会刷新**，会一直显示
"阅读模式"而不是"退出阅读模式"。已改为从 `tabs` 派生的 `StateFlow` 并 `collect`。
`check_reader.py` 专门盯着这一条。

### 与既有功能撞车的四个静默失效点

阅读视图是**原生渲染的内部页面**（主页已改为 Compose，WebView 里只剩
`about:blank` 占位）。主页当初踩过的坑，阅读视图一个不落全都会再踩一遍 ——
而且**错了一样不报错、不崩，只是"看着没生效"**：

| 失效点 | 不处理的后果 |
|---|---|
| 切主题时不重绘 | 阅读视图配色烘在 HTML 里，切夜间模式后不原地刷新，配色**停在旧值**，要退出重进才更新 |
| 强制夜间模式的反色 CSS | `injectDarkMode` 只在 `isInternal` 时跳过。阅读视图本来就是深色，再套一层 `invert+hue-rotate` 会**反成亮底白字**，正好与预期相反；主页的 `about:blank` 占位没有地址可认，同一个坑会以"背景变白"的形式出现（`html{background:#111}` 被 invert 成近白色，把原生背景层糊掉） |
| 回调改写逻辑地址 | `onPageStarted`/`onPageFinished` 会把 `ling://reader` 改写成 `ReaderPage.BASE_URL`，破坏返回栈与阅读态识别 |
| 合成地址进历史 | `ling://reader` 记进历史会留下**点不开**的条目 |

修法是统一判定：阅读视图走 `InternalPage`（`isInternal` / `logicalUrl`），
主页占位走 `WebTabManager.isHomePlaceholder`，各层共用同一口径。
顺带明确：**历史只记真实网址** —— 原文地址在进入阅读模式前就已记过，跳过不会漏记。

> 这四条是分两次才补全的。第一遍只想到"切主题要重绘"，写检查脚本时
> 逐条对着代码推演，才发现反色 CSS 会把深色阅读视图反成白的 ——
> 那条**只有在强制夜间模式开启时**才触发，光看代码很容易划过去。

### 一条自我否定的测试

为"可调主题"写了个断言：`ReaderPage` 里定义的每个 CSS 变量都必须被 `var()` 用到。
它当场抓出 `--on-primary` **定义了却没人用** —— 阅读视图里 `primary` 只作为
页面底色上的文字色（链接），从来没有"primary 实心块上的字"。
已删掉该变量与对应的 `onPrimary` 参数。

这类"定义了却不使用"的代码最隐蔽：页面看着完全正常，换主题时它纹丝不动。

> 检查脚本本身也修了两个**误报/漏报**：`onPageFinished` 是两参数签名
> （`{ url, title ->`），按 `{ url` 匹配永远找不到它 —— 变成**永远报失败**；
> 而"数一数有几处 `isInternal`"的写法会让其中一个回调退化后仍能凑够数量，
> 属于**漏报**。改成按回调名切片、逐个独立校验才可靠。

### 验证方式：用 Node 真实运行算法

JS 是**字符串常量**嵌在 Kotlin 里，Kotlin 编译器完全不检查它 ——
写错一个括号，编译照样通过，只有真机点开阅读模式才会白屏。
但只做 `node --check` 语法检查也不够：**算法写错了照样能通过语法检查**。

所以 `tools/check_reader.py` 额外做两件事：

1. 从 Kotlin 文件里抽出 JS，用 `node --check` 校验语法
2. 用一段**迷你 DOM 桩**搭出典型中文文章页（导航 + 三段正文 + 侧边栏 +
   评论区 + 页脚），真实运行算法，断言它**提取到正文、且剔除掉垃圾块**

第 2 步才是关键。上面那个中文阈值 bug 正是它抓出来的。
反向验证 4 个模拟回归（阈值退回英文、去掉 CJK 折算、语义淘汰失效、
垃圾节点不移除）**全部被抓到**。

> 写这个桩的过程本身也踩了三个坑，都记在脚本注释里：漏 `nodeType`
> 导致打分全被跳过、`innerHTML` 不序列化子树导致"提取成功但 html 为空"、
> 以及**脚本自带 IIFE 却又被套了一层**导致返回值恒为 `undefined`。
> 这三个都表现为"算法没输出"，很容易误判成算法本身有问题。

## 3.8 广告拦截：域名级，先不做元素级

广告拦截发生在 `LingWebViewClient.shouldInterceptRequest`，拦截时返回一个
**空响应**（204/空体）—— 把广告脚本/跟踪像素的请求整个吞掉，比"加载完再
隐藏"更干净、更省流量。

**为什么先做域名级、不做元素级（EasyList）**：

| 方案 | 代价 |
|---|---|
| 域名黑名单（本方案） | 内嵌几十 KB 源码，随 APK 发布，离线可用 |
| EasyList 元素级 | 规则文本几百 KB + 解析结构，接近 APK 体积；还依赖在线订阅源 |

域名级已经能去掉绝大多数广告子请求（banner、脚本、跟踪像素），元素级只是
再补上"页面里残留的空位"——对「翎」的极简定位不划算。

**判定算法**（[AdBlocker.kt](<app/src/main/java/com/ling/browser/web/AdBlocker.kt>)，纯 Kotlin 可单测）：

1. **域名命中**：host 逐级缩短查表（`a.b.doubleclick.net` → `doubleclick.net`），
   任意深度子域名都被兜住。
2. **路径命中**：域名没拦、但 path 含 `/ads/`、`/adserver/` 等片段也拦，
   因为很多站点把广告放在自家域名的 `/ads/` 目录。
3. **主文档保护**：以上只作用于**子资源**。`shouldInterceptRequest` 对主文档
   也会回调，若不加 `isForMainFrame` 判断，用户点开广告落地页会整页白屏。

**抽成纯 Kotlin 的理由**：判定逻辑是"这条 URL 该不该拦"的字符串规则，
跟 WebView 无关。留在 `WebViewClient` 里就得构造 `WebResourceRequest` 才能测，
等于测不了。抽出来之后 host/path 解析、大小写、端口、userinfo、主文档保护
这些边界一次覆盖干净（`AdBlockerTest` 11 个用例）。

> 开关（默认开）用 **lambda** 现读设置，不是传值：`WebViewClient` 是建
> WebView 时一次性构造的，传值会让"改完要重启才生效"——这和"用外部 App
> 打开链接"开关是同一个坑。

### 自定义规则：二级菜单 + 保存前清洗

设置里「广告拦截」开关下方是「自定义规则」入口，点进一个独立的二级页
（`AdBlockRulesScreen`）：列出已有规则、可删除、右上角「添加」弹输入框。

规则语义与内置黑名单完全一致——**裸域名**，命中即拦、任意深度子域名被兜住。
所以用户只需输入 `ad.example.com` 就能拦掉 `static.ad.example.com`。

三个设计点：

1. **保存前必须清洗**（`AdBlocker.normalizeRule`）。用户可能直接粘贴整段
   `https://ad.example.com/path?x=1`，我们只取 `ad.example.com`。这条规则
   被抽成纯函数，既在 ViewModel 里调用，也被单测覆盖各种脏输入。
2. **拒绝单段域名**（`localhost`、纯数字）：单段没有拦截意义，且极易误伤。
3. **新增失败就地提示**（`onAdd` 返回错误原因），不关对话框——用户看到
   "该规则已存在"或"无效的域名"和输入框在同一上下文，改起来最顺。

自定义规则同样走 **lambda 现读**，改完立即生效。

### 订阅名单：内置精简表 + 可订阅的大名单

内置表只是"开箱能用"的起点 —— 广告域名天天在长，光靠内置只能等发版。因此
域名表从"APK 内置"升级成**可订阅**：内置表只保留一批核心广告/跟踪域名
（`AdBlocker.builtinCount`，140 条），真正的大名单由用户从**网址**或**本地文件**
导入（`BlockListRepository` 负责存储，`BlockRules` 负责解析）。

三种最常见的写法被**归一成同一种裸域名集合**：

- **Hosts**：`0.0.0.0 ad.doubleclick.net` / `127.0.0.1 mm.admob.com` —— 只取
  第二个字段（域名），IP 丢弃（我们不做 DNS 劫持，语义只是"拦这个域名的请求"）。
- **AdGuard 域名规则**：`||ad.doubleclick.net^` —— 去掉 `||` 与 `^`。
- **AdGuard domains-only**：一行一个裸域名 `ad.doubleclick.net`。

三者语义统一为"命中即拦，且任意深度子域名被兜住"，与内置表、自定义规则完全
一致。元素规则（`##`）、例外（`@@`）、路径规则需要的是另一套引擎（且例外还会
与内置表打架），一律**静默跳过并计数**——UI 如实报出"生效多少条、跳过多少行"，
否则用户看到"文件 20 万行、只生效 3 千条"会以为功能坏了。

存储上有几处刻意的取舍：

- 一份订阅拆成 `adblock/<id>.meta` + `<id>.domains` 两个文件：元信息（启停、
  条数）频繁改，域名列表几十年写一次，合并会让"点一下开关"变成"重写十几万行"。
- 内存里存两份副本：`id → 域名集合` 与**启用中订阅的并集**。拦截发生在
  `shouldInterceptRequest`（每个子资源一次）的热路径，那里绝不能现并十万条集合
  ——并集在导入/启停/删除时算好，热路径只做集合查询。
- 网址订阅用网址哈希当 id：同一网址重复导入即**更新**，不在列表里堆重复条目。
- 本地导入**不持久化** SAF 的 `content://` 地址：那条读权限只在本次会话有效，
  留着它只会让下次"更新"抛权限异常。本地名单想更新就重新选一次文件。
- 单源硬上限 8 MiB / 20 万条：超大名单直接报"已放弃导入"，避免畸形文件打爆内存。

> 更新方式是**手动**的：订阅页每条网址订阅有一个「更新」按钮，不引入后台周期性
> 拉取（省电、可控，也与"极简"保持一致）。本地导入的名单没有稳定的远端地址，
> 因此不显示更新按钮。

## 3.9 资源嗅探：注入 JS 读 DOM，不改页面

与阅读模式同一套注入套路（`evaluateJavascript` 跑一段字符串脚本），但语义
相反：阅读模式**改造**页面（提取正文替换视图），嗅探**只读** DOM —— 拿到
结果就走，不碰当前页面，因此没有"进入/退出"状态、也不需要缓存。

几个设计点：

1. **为什么必须用 JS 而非解析 HTML**：DOM 只在 WebView 那边，且很多站是
   JS 动态渲染的，静态 HTML 里根本没有 `<video>` 标签。必须等页面加载完、
   DOM 就绪后从**活的 DOM**里读。
2. **相对地址要解析成绝对地址**（`new URL(src, baseURI)`），否则列表里
   一堆 `/media/clip.mp4`，既不能打开也不能下载。base 优先用 `document.baseURI`
   而非 `location.href` —— 这与阅读模式修 `ERR_UNKNOWN_URL_SCHEME` 是同一个坑。
3. **剔除 data: 与 1x1 占位图**：很多站用 1x1 gif 当 tracking pixel，
   不筛掉会刷出一堆无意义的"图片"。
4. **结果解析继续手写迷你解析器**（`SniffResult`）：嗅探返回的是**数组**，
   比阅读模式的扁平对象复杂，但 `org.json` 在本地 JVM 单测里是空壳（抛
   `Stub!`），所以照旧手写——顺带把"括号不配对返回 null"这类边界也测到了。
5. **下载动作复用网页下载的确认流程**：下载是有副作用的重动作（流量、
   存储，视频动辄几十 MB），即使用户点了「下载」也弹确认框 + 探测大小，
   让用户看清"要下什么、大概多大"再决定。
6. **打开后自动回浏览页**：新标签页已就位，留在嗅探页没有意义，
   让用户多按一次返回是负体验。

## 3.10 夜间模式：网页暗化跟随，切换同步

早期夜间模式有三个体验问题，根因是**两套真相源**：

- Compose 界面由 `nightMode`（三态）+ 系统暗色决定；
- 网页暗化却由独立的 `forceDarkWebPages` 布尔（默认关）决定，
  且只在下一次页面加载时才注入。

于是：设置里不能选「跟随系统」（二态开关丢了第三态）；夜间模式开了、
网页还是白的（forceDark 独立默认关）；切换时界面秒变、网页慢半拍。

统一方案是把「是否暗」收敛成**一个解析值** `resolvedDark()`：

```
resolvedDark = 跟随系统 ? 系统暗色 : (始终开启 ? true : false)
网页暗化    = forceDarkWebPages && resolvedDark
```

几个关键改动：

1. **设置里夜间模式改成三态对话框**（跟随系统/始终开启/始终关闭），
   与「更多」菜单的循环切换等价，不再丢失第三态。
2. **`forceDarkWebPages` 默认改 `true`**，语义重定义为「网页跟随夜间模式
   暗化」——夜间模式是"整套变暗"（界面 + 网页一起），而不是只暗界面。
   关掉它表示"只要深色界面、网页保持原色"。
3. **`applySettings` 接收解析后的 `darkTheme`**，系统级暗化（`setForceDark`/
   `isAlgorithmicDarkeningAllowed`）和兜底 CSS 都用它；且切换时**立即**
   注入/移除 CSS，而不是等下次加载。
4. **`injectDarkMode(false)` 要移除**已注入的 `__ling_dark__` 样式：
   早期只注入不移除，切回浅色模式网页仍是暗的，是"切换不同步"的另一根因。

> ~~遗留：跟随系统时，若 App 运行中系统切换了暗/亮，网页侧不会自动重算
> （`resolvedDark` 只在设置变化或页面加载时求值）。需要监听
> `onConfigurationChanged` 才能补上，目前未做——真机长期使用后如觉必要再补。~~
>
> **已补（见 §13）**：系统明暗变化不经过 settings 流，因此由 UI 在解析出的
> 明暗变化时调一次 `refreshWebDarkMode()`。不需要监听 `onConfigurationChanged` ——
> Compose 的 `isSystemInDarkTheme()` 已经把变化带进重组了，跟着它走比再挂一个
> 系统回调更不容易漏。

## 3.11 标题栏必须瞬变：Material3 的 `TopAppBar` 内部自带颜色动画

从「更多」面板循环切换夜间模式时，会发现**设置页顶部标题栏**与**标题栏下方的
设置项列表**变色的时间点对不上：下方列表「啪」地一下换色，标题栏却还在慢慢渐变。

根因不在我们的代码，而在 Material3 内部。`TopAppBar` 的容器色走了一次
`animateColorAsState`（`AppBar.kt`）：

```kotlin
val appBarContainerColor = animateColorAsState(
    targetColor,
    animationSpec = MotionSchemeKeyTokens.DefaultEffects.value(),
)
```

`MotionSchemeKeyTokens.DefaultEffects` 是 `spring(dampingRatio = 1f, stiffness = 1600)`，
于是标题栏底色有约 200~500ms 的渐变；而标题栏下方内容区来自 `Scaffold` 的
`containerColor`（`colorScheme.background`），`Surface` 自身**不做**颜色动画，
是瞬时切换的。两者一快一慢，视觉上就"脱节"了。

> 注意：这不是 `TopAppBarDefaults.topAppBarColors()` 的问题 —— 它的文档明确写了
> 「The default implementation **animates** between the provided colors」，
> 这是 Material Design 规范要求的行为，我们改不掉，只能绕开。

**解法：把标题栏容器色设为 `Color.Transparent`。**

`animateColorAsState` 的目标值恒为 `Transparent`（明暗切换前后不变），
动画就根本不会启动；标题栏区域改为透出 `Scaffold` 的 `background`，
与下方列表严格同帧瞬变。

这个改法成立的前提是 **`surface == background`**：

- 静态色板 `FallbackLight` / `FallbackDark` 中两者取值相同；
- Material You 动态色板中 `background` 与 `surface` 同为
  `neutralVariant98`（浅色）/ `neutralVariant6`（深色）。

所以视觉结果与改动前完全等价，零风险。该前提由单元测试
`TopBarColorEquivalenceTest` 钉住，将来若给两者分配了不同色值会立刻失败。

统一封装在 `ui/components/LingTopBar.kt` 的 `lingTopBarColors()`，
替换了全部 9 处二级页面的标题栏配色（设置页及其 6 个分组子页，
以及关于、广告拦截规则、广告拦截订阅、书签、下载、历史、资源嗅探、用户脚本）。

> 顺带一提：底部工具栏没有这个问题 —— 它用的是 `Surface` 直接取色，
> 而 `Surface` 不做颜色动画，天然是瞬变的。

## 4. 主页由 Compose 渲染，WebView 里只留 `about:blank` 占位
主页经历了两代实现：最早是 Compose 覆盖层，因为"主页不进历史、切页时
`AndroidView` 被销毁重建会打断加载"而改成 WebView 用 `loadDataWithBaseURL`
渲染自包含 HTML。现在改回 Compose（`ui/screens/HomeScreen.kt`），但把
**前景**和**历史**拆开，避开了第一代的两个坑：

- **前景**：时钟 + 搜索框全部是 Compose，直接吃 Material You 动态色，配色不再
  "烘"进 HTML，文档重建（reload、渲染进程被回收、历史回退）也不会掉回旧配色；
  搜索框直接调 `submitAddress`，不必再绕"表单提交 → 拦截 `ling://search` →
  解码"这一圈。
- **历史**：WebView 里放一个 `about:blank` 占位文档，主页因此仍是历史里的一帧。
- **挂载**：`HomeScreen` 是叠加在 `AndroidView` **之上**的一层，`AndroidView`
  始终挂载，切页不会再打断正在加载的页面。

**为什么非要在 WebView 里留占位，而不能只盖一层**：

1. **返回键**：从主页打开的网页，按返回要能回到主页。历史帧由 WebView 维护，
   历史里不放假文档就回不去；
2. **换掉上一页文档**：只盖不换文档，上一页的媒体播放与定时器仍在跑 ——
   离开视频页回主页，声音会继续响。

**怎么认出「这是主页的占位」**：`about:blank` 不是主页专属 —— `window.open`
的占位、页面自己跳 `about:blank` 都会得到同样地址。因此
`WebTabManager.homePlaceholderTabs` 记下"哪个标签的主页占位是我们装的"，
`isHomePlaceholder(id, url)` 同时要求**地址是 `about:blank`** 且**该标签在集合里**。
`loadHome` 只在 WebView **已经落在占位上**时早退（`isHomePlaceholder(id, wv.url)`）：
反复点「主页」不会往历史里堆重复条目，而从主页打开网页后再点「主页」仍会真正回去。
WebView 实例被 LRU 回收后切回主页标签，`switchTo` 会重新装一次占位
（回收时同时把该标签从 `homePlaceholderTabs` 里摘掉，避免身份残留在新实例上）。

**WebView 自身的底必须透明**：背景由原生 `HomeBackgroundLayer`（纯色 / 自定义
图片）在 WebView **之下**供色，主页期间 WebView 若保留默认白底会把背景整个盖住。
因此 `loadHome` 里调 `setOpaqueBackground(false)`，普通网页在 `loadUrl` 里再设回
不透明白底。

> `about:home` 仍然不能用：WebView 只认识 `about:blank`，早期版本把
> `about:home` 直接喂给它，触发了 `onReceivedError` 白屏 —— 这正是
> "默认主页打不开"的根因。`isHomeUrl()` 仍兼容该旧值。

> 用户**自定义主页**时是普通网页，走正常 `loadUrl`，不挂 `HomeScreen`
> （见 `BrowserScreen` 里 `homeBackgroundVisible` 那个条件）。

## 5. SVG 主题色必须走 CSS，不能写在表现属性里
`stroke="var(--on-pc)"` 作为 SVG **表现属性**是无效的 —— 浏览器不会解析其中的
`var()`，图标会渲染成透明。必须改为 CSS 规则 `.mark svg path { stroke: var(--on-pc); }`。

## 6. 历史记录用 UPDATE 而非 INSERT OR REPLACE
`CONFLICT_REPLACE` 会换掉整行、导致 `id` 变化，UI 上正在展示的那条记录的
删除按钮就会失效。因此改为：存在则 `UPDATE`，不存在才 `INSERT`。

## 7. 返回栈用显式开关，不依赖 BackHandler 注册顺序
Compose 中多个 `BackHandler` 同时启用时，**后注册的优先**
（`OnBackPressedDispatcher` 逆序遍历回调队列，取第一个 enabled 的）。
让二级页面依赖这个隐式顺序很脆弱：只要组合结构一变
（例如把 `BrowserScreen` 提到 `when` 之外），返回键就会被浏览页抢走 ——
表现出来正是"在设置页按返回却直接退出应用"。

因此改为**显式划分归属**：`BrowserScreen` 接收 `canHandleBack` 参数，
`LingApp` 在栈里只有浏览页时传 `true`，否则传 `false`。
两个处理器永远不会同时启用，与组合顺序无关。

## 8. 内部页的地址以「逻辑地址」为准，不跟着 WebView 回调走
阅读视图是 `loadDataWithBaseURL` 加载的，WebView 回调（`onPageStarted` /
`onPageFinished` / `onReceivedTitle`）报回来的是 **baseUrl** 或
`data:text/html;base64,…`；主页则是 `about:blank` 占位。若不拦住，会导致：

- `TabState.url` 被改写成 baseUrl / data URL / `about:blank`；
- `canGoBack` / `canGoForward` 被重置成 WebView 的实时值，返回键闪烁；
- 标签标题被页面的 `<title>` 覆盖，丢掉「主页」这个语义标题。

处理方式是在回调里统一过 `InternalPage`：命中则保留原有状态，并且**不写入
历史记录**（内部页面不该出现在历史里）。

> 但"命中就保留原 url"还有一个坑：**回到主页时**（历史前进/后退触发），
> 原 url 是上一个网页的网址，保留它会让旧网址残留在地址栏。因此回调里的
> url 回写改成三路 `when`：主页占位显式回写 `HomePage.URL`，阅读视图/自定义
> 协议保留逻辑地址，普通页面写回真实 url。

## 9. 「定义了但没人调用」的函数最危险
项目里同一个坑踩了两次，症状都是**功能静默失效**：

| 死掉的函数 | 后果 |
|---|---|
| `HomePage.prefersDarkCss()`（旧实现，已随主页改 Compose 删除） | 主页 `dark` 参数完全不起作用，不跟随夜间模式 |
| `LingWebView.injectDarkMode()` | 强制网页夜间的 CSS 兜底从未生效，漏网页面仍是白底 |

两者的共同特征是：**单元测试直接调用了它们**，所以测试全绿，
给人"这块有覆盖"的错觉，而线上根本没走这条路。

因此新增 `tools/check_dead_code.py`，扫描 `main` 源集里语义明确的
「动作型」函数（`inject*` / `prefers*` / `ensure*` 等），确认至少有一处
调用点；若只在测试里被调用，会当作危险信号报出来。

> 该脚本有意**只检查少数前缀**，不检查 `set*` / `to*` 这类名字 ——
> 它们常以函数引用（`viewModel::setNightMode`）或扩展函数
> （`cursor.toBookmark()`）形式调用，纯文本匹配抓不到，强行检查会产出
> 几十条误报。**没人看的检查等于没有检查**，宁可漏报不可误报。

## 10. 主页配色不再需要"抢在 WebView 创建前就绪"
上一代实现里，主页配色是写进 HTML 的：冷启动时 `newTab` 会先建好主页 WebView、
`applyHomeTheme` 才写入配色，于是要用 `ensureHomeRendered()` 包一层
`obtainWebView`；否则"WebView 尚未创建"时 `?.let` 会静默跳过，主页停在默认浅色。

主页改由 Compose 渲染后，这条时序问题**整体消失**：配色就是当前
`MaterialTheme.colorScheme`，Compose 自己会重组，不存在"文档先渲染、颜色后到"。
`applyHomeTheme` / `ensureHomeRendered` 一并删除，配色入口只剩
`applyInternalColors` —— 它现在**只服务阅读视图**（阅读视图的配色仍烘在 HTML 里）。

## 11. 阅读视图的主题切换要原地刷新，不能重导航
阅读视图是 `loadDataWithBaseURL` 渲染的，**每次调用都会往 WebView 历史里压入
一个新条目**。早期主题变化时直接重渲染，结果：切深色后按返回键，会弹回
**切换前那个浅色阅读页**（历史里残留的旧条目）。

改成"原地刷新"：已渲染的阅读视图，用 `evaluateJavascript` 直接改 `:root` 上的
CSS 变量（`--bg`/`--fg`/…/`color-scheme`，见 `ReaderPage.themeUpdateJs`）。
不触发导航、不压历史，深浅色切换即时生效且返回键行为不变。

一个边界要守住：**只有"已渲染"才原地刷新** —— 用 `ReaderPage.isReaderUrl(wv.url)`
判断 WebView 是否真的落在了阅读视图的 baseUrl（跳转途中还是上一个页面的地址，
注入会打错文档），否则仍走完整渲染。

> 主页已经没有这个问题：它是 Compose 渲染，配色跟着 `MaterialTheme` 走，
> 不需要任何"原地改 CSS 变量"的动作。

---

## 12. 内部页被"网页那一套逻辑"误伤的三条路

阅读视图的配色是**原生算好、写进 HTML / CSS 变量**的，本不该参与网页侧的
夜间模式、标题与 URL 处理。但它和普通网页共用同一个 WebView、同一批回调，
下面三条路都会绕过"这是内部页"这个前提，症状都是**静默失效**
（用户看到的是"主页/文章又坏了"，而单测全绿）：

| # | 路径 | 症状 | 修法 |
|---|---|---|---|
| 1 | `applySettings` 用 `resolvedDark()`（只看夜间模式）而不是 `shouldDarkenPages()`（还要 `&& forceDarkWebPages`） | 关掉「网页跟随夜间模式暗化」后，**任何一次设置变化**都会把网页重新暗化一遍 —— 开关看上去"关了没用" | 判据改成 `shouldDarkenPages()`（与 `LingWebView.applySettings` 的 `@param` 约定一致） |
| 2 | `LingWebView.applySettings` 无条件 `injectDarkMode()`，而"内部页不注入"的保护只写在 `onPageFinished` 里 | `applySettings` 会遍历**所有** WebView：阅读视图文档一旦已加载，兜底 `invert(1) hue-rotate(180deg)` 就注进去 —— 深色 `--bg:#111412` 被反成亮底 `#EEEBED`；主页的 `about:blank` 占位同理，它的 `html{background:#111!important}` 被 invert 翻成 **近白色**，把纯色 / 自定义图片背景层糊成一片白（切换夜间模式时才出现，因为注入只发生在这条路上） | 判据下沉到唯一入口：`val enabled = darken && !isHomePlaceholderDocument && !InternalPage.isInternal(url)`；about:blank 没有地址身份，由 `WebTabManager` 在装占位 / 离开占位时维护该标记 |
| 3 | 阅读视图配色"烘"在 HTML 字符串里，重新着色只由 `applyInternalColors` 触发 | 任何**不经过 UI** 的文档重建都会掉回旧配色：地址栏刷新（reload 重放同一份 data URL）、渲染进程被系统回收后重建、历史回退到旧条目 | `onPageFinished` 里补一个 `if (isInternal)` 分支做原地刷新 |

同一类"状态被带偏"还有两处：

- **重复回主页会攒历史**：「主页」是常驻按钮，`loadHome` 每次调用都会 `loadUrl`，
  而 `loadUrl` 会压一个历史条目。现在 `loadHome` 先判
  `isHomePlaceholder(id, wv.url)`，已经停在占位上就直接返回；`reload` 也直接
  短路回主页，不在主页上重放占位文档。
- **`syncNavState` 把实时地址写进 `TabState`**：切换标签后主页标签的 `url` 会变成
  `about:blank`（或上一页地址），标题也会被占位文档的空白标题盖掉。现在先判
  `isHomePlaceholder` 写回 `HomePage.URL`，其余统一过 `InternalPage.logicalUrl()`
  归一化，内部页标题保持不变。

判定收敛在 `InternalPage` 一处 —— 唯一入口 `typeOf` 处理全部地址形态，
`isInternal` / `isHomeDocument` / `isReaderDocument` / `logicalUrl` 都由它派生，
日后新增内部页只改它。主页占位的身份另由 `WebTabManager.homePlaceholderTabs`
判定（要 WebView 上下文，单测覆盖不到），并在 WebView 实例上留下
`isHomePlaceholderDocument` 标记供 `injectDarkMode` 使用（`about:blank`
没有地址可认，只能靠标记）：`check_reader.py` 盯住上面这些
**调用点**，`HomePageTest` / `InternalPageTest` 覆盖纯逻辑。

**主页改 Compose 后自然消失的两处**：`loadDataWithBaseURL` 每次调用压历史、
主页 HTML `body` 固定高度导致小幅溢出被裁切 —— Compose 自己测量布局，不再有
这两个问题。阅读视图仍走 `loadDataWithBaseURL`，约束不变。

---

## 13. 进程级状态与文档重建：三处"改一次设置就出问题"

同一类坑的第二批。共同特征是**动作本身没错，错在时机**：进程级 / 文档级的操作被放进了
"每次设置变化都会遍历所有 WebView"的那条路径里。

| # | 问题 | 成因 | 修法 |
|---|---|---|---|
| 1 | 无痕标签**改一次设置就丢前进/后退历史**（返回键失效），缓存也被清空 → 整页重新下载 | `clearHistory/clearCache/clearFormData` 写在 `LingWebView.applySettings` 里，而它会在每次设置变化时对**所有** WebView 跑一遍 | 挪进 `resetForIncognito()`，只在**创建 WebView** 时调用一次 |
| 2 | 普通标签**丢登录态**，或者无痕标签又接受了 Cookie（隐私承诺静默失效） | `CookieManager` 是**进程级单例**，却在 applySettings 的遍历里按每个 WebView 各设一次 —— 等于"最后一个 WebView 决定全局"，策略随创建顺序漂移 | 收敛成 `LingWebView.applyCookiePolicy(incognito)`，并让策略**跟着当前激活标签走**：创建（仅当它就是激活标签）与切换标签时各对齐一次（`syncCookiePolicy`） |
| 3 | 「跟随系统」时运行中切系统暗/亮 → **界面变深、网页还是亮的**（§3 记的那条遗留） | 系统明暗变化不经过 settings 流，`resolvedDark` 只在设置变化或页面加载时求值 | UI 侧在解析出的明暗（`uiDark`）变化时调 `refreshWebDarkMode()` 重算一次；不必再挂 `onConfigurationChanged` |

**顺带修掉阅读视图的一处同类问题**：调字号原来走"用缓存正文重渲染"
（`loadDataWithBaseURL`），而它每次调用都往历史里压一个条目 —— 反复调字号会让返回键在
**同一篇文章的不同字号**之间来回，重渲染还会把**滚动位置拉回顶部**（读到一半调字号，
位置全丢）。阅读视图的排版本来就以 `--font-size` 为唯一来源，现在改成原地改变量
（`ReaderPage.fontSizeJs`）：立即生效、不压历史、滚动位置不变。

判据同样进了 `check_reader.py`（"进程级状态与文档重建"一节，6 条），并逐条做了反向验证：
把修复改回去，对应判据全部报错。

---

## 14. 两个"入口"需求：地址栏显示标题 + 从其它应用打开链接

### 14.1 加载完成后在地址栏显示网页标题

规则收敛在 `TabState.addressBarText`（纯逻辑，有单测）：加载完成显示标题，
**加载中**与**没有标题**退回网址，主页给空串。两个退化场景都不能省：

- **加载中**：`title` 还是**上一个页面**的（WebView 要等解析到 `<title>` 才更新），
  拿它显示就像"点了没反应"；
- **没有标题**：少数页面不给 `<title>`。

聚焦进入编辑态时切成真实网址（`editableAddress()`）。**阅读视图要特别处理**：
它的逻辑地址是合成的 `ling://reader`，既不能复制也没法改，拿去提交还会让 WebView
报 `ERR_UNKNOWN_URL_SCHEME` 白屏 —— 编辑态改为显示缓存里的**原文地址**
（`WebTabManager.readerOriginalUrl`）。这条以前是隐患：地址栏里就明晃晃写着
`ling://reader`，用户一聚焦一回车就白屏。

> **真机反馈（已修）**：这一改还顺带暴露了一个更早的问题 ——
> **"点在文字上进去编辑的是标题，点文字旁边空白才编辑 URL"**。
>
> 成因是非编辑态一直挂着一个 `BasicTextField`：点文字会被输入框自己吃掉 ——
> 它把点击当成**移动光标**，`onValueChange` 带出来的是**标题**，把刚切过来的 URL
> 又覆盖回去；而点空白那条路径先完成了聚焦、标题没被回写，所以看起来"只有空白点得对"。
> 这也说明"只读外观"当初只做在了视觉上，控件层面并没有只读。
>
> 修法三处：
> 1. 非编辑态只渲染**只读 `Text`**，编辑态才挂输入框（编辑态是唯一会 `onTextChange` 的来源）；
> 2. 非编辑态**整条地址栏**可点进编辑态（不只是文字那一小块，行为一致）；
> 3. `BrowserViewModel.onAddressChanged` 再加一道兜底：非编辑态的上报一律忽略 ——
>    以后有人把输入框加回非编辑态，也不会再出现"显示文案覆盖真实地址"。

### 14.2 从其它应用「用翎打开链接」

manifest 里 VIEW / SEND 两个 filter **一直都有**，但 `MainActivity` 从来没读过
`intent` —— 所以用户在别的 App 选「用翎打开」，效果只是把翎拉到前台，网页根本没动。
三个必须处理的点：

| 点 | 不处理的后果 | 做法 |
|---|---|---|
| Activity 真的读 `intent.data` | 选了没反应 | `handleExternalIntent()`，onCreate 与 onNewIntent 共用 |
| 冷启动的**竞态** | 链接被"恢复会话 / 新建主页"覆盖，仍表现为点了没反应 | `pendingExternalUrl` 排队，等 init 决定完标签页再打开 |
| `singleTask` + 拉回浏览页 | 出现两个翎（标签页各自独立）；或用户停在设置页、网页在后台开了他却看不见 | manifest 已是 singleTask；外部链接进来时把返回栈清回浏览页（`showBrowser`） |

落地标签的约定：当前标签还停在主页就复用它，否则新开一个。不支持的 scheme
（`content://`、`intent://`）明确提示"不支持打开这个链接"，而不是交给 WebView 白屏。

### 顺带修掉的一条通路缺失：所有一次性提示都被静默丢弃

`BrowserViewModel._message`（"已加入书签""主页无需收藏""阅读模式：JS 未开启"…）
**从来没有消费方** —— `consumeMessage()` 无人调用、UI 也不 collect，于是全部丢掉。
修外部链接时正需要它给出反馈，就一并接上了：`LingApp` 里 collect + Toast +
`consumeMessage()`。这是"定义了但没人用"的第三种形态：不是函数没人调，而是
**数据没人读** —— `check_dead_code.py` 按函数名扫描抓不到它，所以判据加在
`check_ux_fixes.py` 里。

---

## 15. CI 抓到的第一处编译错误，以及为它补的判据

`fba6599` 在 CI 上编译失败，只有一行：

```
BrowserViewModel.kt:278:29 Argument type mismatch: actual type is 'String?', but 'String' was expected.
```

成因是把**可空链直接传给了非空形参**：`UrlUtils.isHome(activeTab()?.url)` ——
`isHome(url: String)` 的形参是非空的。而 18 个静态检查全绿，因为它们查的是
"括号配平 / import 一致 / 具名参数完整 / 文本接线"，**没有类型系统**。

修法：先取 `val active = activeTab()`，再判 `active == null || UrlUtils.isHome(active.url)`。

更重要的是把这类错误变成可检查的：`static_check.py` 新增 `check_nullable_args()` ——
对一批**已知形参非空**的函数（`UrlUtils.isHome` / `prettify` / `hostOf` / `toUrl`、
`UrlScheme.isNavigable` / `shouldTakeOver`）检查实参里是否出现 `?.` 且**没有兜底**
（`?:` / `!!`）。已验证：把上面那行改回去，脚本立刻报
`isHome() 的实参是可空链（?.）却没有兜底 —— 该形参是非空 String，编译会报 type mismatch`。
带兜底的写法会被跳过 —— 不这样做就是误报，而误报多的检查没人看。

> **教训：静态文本检查不能替代编译器。** 这类改动必须在 CI（或本地
> `./gradlew :app:compileDebugKotlin`）上过一遍才敢说"通过"。本仓库是 public 的，
> 推送后用 GitHub API 读 `repos/…/commits/<sha>/check-runs` 就能确认结论 ——
> 本地没有 Android SDK 时，这是最省事的编译验证路径。

本批（`907157d`）推送后的 CI 结论：**success** —— `:app:testDebugUnitTest`
（254 个用例）与 `:app:assembleRelease` 全部通过，
run: https://github.com/xiaoyangtao55/LingBrowser/actions/runs/38042019419

> 该 run 的 7 条 annotation 都是工作流环境警告，与代码无关：
> Node.js 20 / `actions/setup-java@v4` 的弃用提醒、`ubuntu-latest` 迁移公告，
> 以及 `platforms;android-36.1`、`build-tools;36.0.0`、`platform-tools` 这几个
> SDK 包 "仓库里没有这个包名" —— 装不上但不影响构建（runner 镜像自带）。
> 有空时值得顺手清一下 `release.yml` 里的包名列表，否则 runner 镜像一变就会真失败。

---

## 16. 主页搜索框：输入直接交给原生

主页搜索框是 Compose 的 `BasicTextField`（`HomeScreen` 里），`ImeAction.Go`
触发提交，一路接到 `BrowserViewModel.submitAddress`：

```
BasicTextField(onGo) → HomeScreen.onSearch(query)
                     → BrowserScreen 的 viewModel::submitAddress
                     → UrlUtils.toUrl(query, 搜索引擎) → navigate()
```

三个刻意的选择：

| 选择 | 为什么 |
|---|---|
| 输入不出 Compose，一条直达链路 | 输入可能是**网址**也可能是**搜索词**，判定逻辑（`UrlUtils.toUrl`）在原生侧且只该有一份 |
| 不再用内部地址 `ling://search` 中转 | 上一代主页是 HTML 表单，必须靠"提交到内部地址 → 拦截 → 解码"把文字交回原生；主页改成 Compose 后这层中转整体删除 |
| 提交后清空输入并收起键盘 | 主页是"每次回来都干净"的起点；不收键盘会在页面跳转后仍挡住内容 |

历史上这条链路是「HTML 表单提交到 `ling://search?q=…` → `shouldOverrideUrlLoading`
→ `handleUri` → `HomePage.searchQueryOf()` 解码 → `onSearchRequested`」。主页改
Compose 后，`SEARCH_URL` / `searchQueryOf` / `onSearchRequested` / CLIENT 里的
拦截分支**全部删除** —— 少一层跨进程字符串编码，也顺手消灭了"表单编码要解码一次、
否则中文被二次编码"那个坑。

页面上那条"在地址栏输入网址或搜索内容"的底部提示随之删掉了 —— 有了搜索框它就是废话。

## 17. 内部页的"对外地址"：收藏与会话快照

内部页的逻辑地址（`ling://home` / `ling://reader`）是**对内**的：主页是 Compose
渲染、阅读视图靠"正文缓存 + 重新渲染"得到。凡是需要把地址**交出去**的地方，都得先换算一遍，
否则会得到两个报障：

| 场景 | 之前的症状 | 现在 |
|---|---|---|
| 阅读模式里点收藏 | 存下 `ling://reader` —— 一个点不开的书签 | 存**原文地址**（提取时缓存的 canonical）；查收藏态也用同一口径，否则"收藏过了还显示添加书签" |
| 会话快照（`toSnapshot`） | 存 `ling://reader`，重启恢复时交给 WebView → **错误页** | 存原文地址 → 恢复后直接打开那篇文章 |
| `loadUrl` 收到合成地址（旧快照） | 同上，错误页 | 兜底回主页，至少是个能用的页面 |

判定收敛在 `InternalPage.externalUrl(url, readerOriginalUrl)`：主页返回 null
（没有对外地址）、阅读视图换成原文、普通页面就是自己。**拿不到原文地址时返回 null**
而不是退回合成地址 —— 宁可"这次不能收藏"，也不要存下一个点不开的书签。

---
