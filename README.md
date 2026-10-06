# 翎 · 极简浏览器

> 轻巧、干净、无推送。一枚羽毛的重量，装得下整个 Web。

「翎」是一款参照 **Via 浏览器**理念实现的 Android 浏览器：把界面压到最简、
把速度提到最快，不做新闻流、不做信息流干扰，只专心把「打开网页」这件事做好。

- **包名**：`com.ling.browser`
- **版本**：1.0.0 (versionCode 1)
- **体积**：release APK **约 1.4 MB**
- **兼容**：Android 6.0 (API 23) ~ Android 16 (API 36)
- **UI**：Jetpack Compose + **Material You 3**（Android 12+ 自动跟随壁纸动态取色）

---

## 一、当前实现的功能

### 核心浏览
| 功能 | 说明 |
|---|---|
| 多标签页 | 每个标签页保留独立 WebView 实例，切换不丢页面状态；超过 6 个实例按 LRU 回收；favicon 缩略图 + 长按拖拽排序 |
| 智能地址栏 | 自动区分「网址」与「搜索词」；聚焦时全选，输入时给出书签/历史联想 |
| 书签 | 一键收藏/取消，独立管理页；支持**网站图标**、重命名、删除确认、单层文件夹归档 |
| 历史记录 | 自动去重 + 次数累加，按「今天/昨天/更早」分组，支持单条删除与清空 |
| 无痕模式 | 独立标签页；不写历史、关闭 DOM storage 与 Cookie |
| 前进/后退/刷新/停止 | 刷新常驻在地址栏右侧；底部工具栏保留前进/后退/主页/标签页/更多 |
| **内置主页** | `ling://home` 由原生渲染，**离线可用**；跟随 Material You 动态取色，展示书签快捷入口 |

### 页面适配
| 功能 | 说明 |
|---|---|
| 夜间模式 | 跟随系统 / 始终开启 / 始终关闭 三态；**内置主页同步跟随** |
| 网页强制夜间 | Android 10+ 用 `setForceDark`/`setAlgorithmicDarkeningAllowed`，页面加载完成后再注入兜底 CSS |
| 电脑模式 | 切换为桌面版 Safari UA，触发站点桌面布局（有顶部提示条） |
| 无图模式 | 关闭图片加载，省流量 |
| JavaScript 开关 | 可关闭以提速、去干扰 |
| 自定义主页 | 填入任意网址；**留空则使用「翎」的内置主页** |
| 标签页面板高度 | 全屏 / 一半 两档，默认一半；面板半透明，可透出后方网页 |

### 搜索引擎
内置 百度 / 必应 / Google / DuckDuckGo 四家，可随时切换。**默认必应**。

---

## 二、工程结构

```
LingBrowser/
├── .github/workflows/release.yml  # CI：推代码自动跑测试 + 出 release APK
├── app/
│   ├── build.gradle.kts           # 模块配置（含签名、R8、localeFilters）
│   ├── proguard-rules.pro         # 混淆规则（保留 JS 接口与 Room/Compose 元数据）
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── java/com/ling/browser/
│       │   │   ├── LingApplication.kt        # 依赖容器入口
│       │   │   ├── MainActivity.kt           # 唯一 Activity + 显式返回栈
│       │   │   ├── data/
│       │   │   │   ├── AppContainer.kt       # 手工 DI 容器
│       │   │   │   ├── db/                   # SQLiteOpenHelper + 实体
│       │   │   │   ├── prefs/SettingsStore.kt# DataStore 偏好设置
│       │   │   │   └── repo/                 # 书签 / 历史仓库（StateFlow 暴露）
│       │   │   ├── ui/
│       │   │   │   ├── BrowserViewModel.kt   # 状态与业务逻辑
│       │   │   │   ├── components/           # 地址栏、底部工具栏
│       │   │   │   ├── screens/              # 浏览/标签/书签/历史/设置
│       │   │   │   └── theme/                # Material You 3 主题 + Material Symbols 图标
│       │   │   ├── util/UrlUtils.kt          # 地址栏解析（含单元测试）
│       │   │   └── web/
│       │   │       ├── WebTabManager.kt      # 标签页 + WebView 实例池
│       │   │       ├── LingWebView.kt        # WebView 子类（设置/UA/夜间）
│       │   │       └── HomePage.kt           # 内置主页 HTML（离线自包含）
│       │   └── res/                          # 图标、主题、字符串
│       └── test/java/com/ling/browser/
│           ├── util/UrlUtilsTest.kt          # 地址栏解析
│           ├── web/HomePageTest.kt           # 内置主页
│           ├── data/prefs/LingSettingsTest.kt# 设置项默认值与枚举
│           └── ui/theme/LingIconsTest.kt     # 图标几何回归
├── keystore/
│   ├── README.md                  # 签名与 Secrets 配置说明
│   └── ling-release.jks           # 发布签名密钥（已 .gitignore）
└── tools/                         # 不依赖编译的静态自检脚本
    ├── static_check.py
    ├── check_calls.py
    ├── check_home_html.py
    ├── import_material_icons.py   # 官方图标 -> Compose 矢量代码
    ├── emit_ling_icons.py         # 组装 LingIcons.kt
    ├── ascii_icons.py             # 图标渲染成 ASCII，肉眼校验造型
    ├── check_icon_fidelity.py     # 生成物与源文件逐点比对
    ├── check_session.py           # 会话恢复接线校验
    ├── check_bookmark_folders.py  # 书签文件夹校验
    ├── check_ux_fixes.py          # 焦点/书签编辑/图标校验
    └── make_icons.py              # 生成 API<26 的传统位图图标
```

---

## 三、构建

### 环境要求
- JDK 21（Android Studio 自带 JBR 即可）
- Android SDK Platform 36 +
- Gradle 9.4.1（随 wrapper 分发，无需另装）

### 命令

```bash
# Debug 包（包名带 .debug 后缀，便于与正式版共存）
./gradlew :app:assembleDebug

# Release 包（R8 压缩 + 资源裁剪 + 签名）
./gradlew :app:assembleRelease

# 单元测试
./gradlew :app:testDebugUnitTest

# 安装到已连接设备
./gradlew :app:installRelease
```

产物路径：`app/build/outputs/apk/release/app-release.apk`

### 签名说明
`app/build.gradle.kts` 会按以下优先级寻找密钥库并完成签名：

1. 环境变量 `LING_KEYSTORE_PATH` 指向的文件（CI 用）
2. 仓库内 `keystore/ling-release.jks`（本地开发）

两者都不存在时，release 退化为 unsigned 包（仍可用于验证编译）。
口令来源优先级：Gradle 属性 → 环境变量 → 本地默认值。

```bash
./gradlew :app:assembleRelease \
  -PLING_STORE_PASSWORD=xxx \
  -PLING_KEY_ALIAS=ling \
  -PLING_KEY_PASSWORD=xxx
```

> ⚠️ `keystore/` 已在 `.gitignore` 中排除，密钥库**不会**进入仓库。
> 详见 [keystore/README.md](keystore/README.md)。

### CI 自动构建

本地设备编译较慢，因此提供了 GitHub Actions 工作流
[.github/workflows/release.yml](.github/workflows/release.yml)：

- **push / PR** → 自动跑单元测试 + 构建 release APK，产物在该次运行的 Artifacts 里下载
- **打 `v*` 标签** → 额外自动创建 GitHub Release 并附上 APK
- 签名密钥通过仓库 Secrets 注入（不配也能出未签名包）：

| Secret | 说明 |
|---|---|
| `LING_KEYSTORE_BASE64` | 密钥库的 Base64 编码 |
| `LING_STORE_PASSWORD` | 密钥库口令 |
| `LING_KEY_ALIAS` | 密钥别名 |
| `LING_KEY_PASSWORD` | 密钥口令 |

---

## 四、几个关键设计取舍

### 1. 为什么不用 Room？
Room 依赖 KSP 注解处理，而 **AGP 9.x 内置了 Kotlin 支持**，与独立 KSP 插件在
source set 注册上冲突（`Using kotlin.sourceSets DSL to add Kotlin sources is not
allowed with built-in Kotlin`）。本项目只有 3 张结构简单的表，直接用平台自带的
`SQLiteOpenHelper` 手写 SQL，少一层构建复杂度，也更贴合轻量定位。

### 2. 图标：为什么不用字体，也不用 material-icons-extended？

三种方案的实测代价对比：

| 方案 | 代价 |
|---|---|
| `material-icons-extended` | 上万个图标全部编进 dex，单个 `classes.dex` 达 **40+ MB**，debug 包 **18 MB** |
| Material Symbols TTF | 单文件 **948 KB**（Filled 版 1.4 MB），几乎等于整个 APK |
| **矢量代码（本方案）** | 27 个图标约 **46 KB** 源码 |

Via 这类浏览器整个安装包才几百 KB。**一个图标字体就顶得上当前 APK（1.4 MB）
的 68%**，为二十几个图标翻倍不划算。而且字体图标无法参与 Compose 的
tint 与交互动画，取字形还要按 Unicode 码点逐个核对。

因此改为把官方图标**转成 Compose 的 ImageVector 代码**：
造型是 Material Symbols 的原始坐标（一位不差），只是换了个表达形式。

#### 坐标网格：保留官方的 960，不缩放到 24
官方导出的是 **960×960** 视口。转换时**不做缩放** —— 960 是 Google 的原始
设计网格，保留它意味着坐标能与官方文件逐位对照，出问题可直接 diff；
缩放反而引入浮点误差。渲染尺寸由 `defaultWidth/Height = 24.dp` 控制，
与视口无关。

#### 又踩了一次 `arcTo` 的坑……这次是它的"命名兄弟"
早期版本用手绘描边图标，用 `arcTo` + `largeArc` 画圆弧，当起点与终点恰好
落在圆的**直径两端**时圆弧退化成半圆饼，刷新图标被画成"水桶"、锁梁被画成
实心矩形。现在官方源文件只含 `M`/`L`/`Q`/`Z`，转换器**遇到其它命令直接报错**，
不会静默丢数据。

转换时另有一个纯粹的 API 命名坑：SVG `pathData` 里的 `Q`，在 Compose
`PathBuilder` 上叫 **`quadTo`** —— 直觉会写成 `quadraticTo` 或
`quadraticBezierTo`，**两个都不存在**。正确做法是 `javap` 查一遍：
`moveTo` / `lineTo` / `quadTo` / `curveTo` / `arcTo` / `close`。

#### 收藏状态：靠「实心 / 空心」区分，不靠加号
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

#### 校验工具链

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

> 全部 27 个图标均来自官方文件，**没有合成图标**。

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

### 3. WebView 实例复用
WebView 创建代价高（每个实例约数 MB 原生内存）。`WebTabManager` 为每个标签页
保留长期存活的实例，切换标签只是把对应 View 挂到宿主 `FrameLayout`；
实例数超过 6 个时按 LRU 回收最久未使用的非激活实例。

### 3.1 标签缩略图：favicon，不是网页截图
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

### 3.2 书签的网站图标：自己抓，存字节

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

### 3.3 拖拽排序：长按才能拖
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

### 3.4 会话恢复：四个必须想清楚的点

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

### 3.5 书签文件夹：不建 folders 表

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

### 3.6 地址栏提交后必须真的释放焦点

### 3.6 地址栏提交后必须把焦点**交还**给网页

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

### 4. 主页由 WebView 渲染，不用 Compose 覆盖层
早期实现把主页做成 Compose 覆盖层（`HomeScreen`），有两个问题：主页不进入
前进/后退历史；且切页时 `AndroidView` 会被销毁重建，打断正在加载的页面。

现在改为由 WebView 直接渲染：`loadUrl` 拦截主页地址 → `loadDataWithBaseURL`
注入一段自包含的 HTML。主页因此自然参与历史，`AndroidView` 也始终挂载。

**两个必须注意的坑**：

1. **逻辑地址与 baseUrl 必须分开**。地址栏判断用 `HomePage.URL`（`ling://home`），
   而交给 `loadDataWithBaseURL` 的 baseUrl 用 `HomePage.BASE_URL`
   （`https://home.ling.local/`）—— baseUrl 的 scheme 会参与相对路径解析与
   同源判定，传 WebView 不认识的自定义 scheme 属于未定义行为。主页 HTML 完全
   自包含（无外链、无相对路径），所以 baseUrl 只起占位作用。
2. **不能用 `about:home`**。WebView 只认识 `about:blank`，早期版本把
   `about:home` 直接喂给它，结果触发 `onReceivedError` 白屏 —— 这正是
   "默认主页打不开"的根因。`isHomeUrl()` 仍兼容该旧值。

### 5. SVG 主题色必须走 CSS，不能写在表现属性里
`stroke="var(--on-pc)"` 作为 SVG **表现属性**是无效的 —— 浏览器不会解析其中的
`var()`，图标会渲染成透明。必须改为 CSS 规则 `.mark svg path { stroke: var(--on-pc); }`。

### 6. 历史记录用 UPDATE 而非 INSERT OR REPLACE
`CONFLICT_REPLACE` 会换掉整行、导致 `id` 变化，UI 上正在展示的那条记录的
删除按钮就会失效。因此改为：存在则 `UPDATE`，不存在才 `INSERT`。

### 7. 返回栈用显式开关，不依赖 BackHandler 注册顺序
Compose 中多个 `BackHandler` 同时启用时，**后注册的优先**
（`OnBackPressedDispatcher` 逆序遍历回调队列，取第一个 enabled 的）。
让二级页面依赖这个隐式顺序很脆弱：只要组合结构一变
（例如把 `BrowserScreen` 提到 `when` 之外），返回键就会被浏览页抢走 ——
表现出来正是"在设置页按返回却直接退出应用"。

因此改为**显式划分归属**：`BrowserScreen` 接收 `canHandleBack` 参数，
`LingApp` 在栈里只有浏览页时传 `true`，否则传 `false`。
两个处理器永远不会同时启用，与组合顺序无关。

### 8. 内部页面的 URL 必须与 baseUrl 解耦
`loadDataWithBaseURL` 加载的页面，WebView 回调（`onPageStarted` /
`onPageFinished` / `onReceivedTitle`）报回来的是 **baseUrl**，而不是我们
写进 `TabState` 的逻辑地址。若不拦住，会导致：

- `TabState.url` 被改写成 baseUrl，`refreshHome()` 的过滤漏掉该页；
- `canGoBack` / `canGoForward` 被重置成 WebView 的实时值，返回键闪烁；
- 标签标题被页面的 `<title>翎</title>` 覆盖，丢掉「主页」这个语义标题。

处理方式是在回调里用 `HomePage.isHomeUrl(url)` 判断，命中则保留原有状态，
并且**不写入历史记录**（内部页面不该出现在历史里）。

### 9. 「定义了但没人调用」的函数最危险
项目里同一个坑踩了两次，症状都是**功能静默失效**：

| 死掉的函数 | 后果 |
|---|---|
| `HomePage.prefersDarkCss()` | 主页 `dark` 参数完全不起作用，不跟随夜间模式 |
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

### 10. 主页配色必须早于 WebView 创建就绪
冷启动时 `newTab` 会先建好主页 WebView，随后 `applyHomeTheme` 才写入
配色。早先的 `refreshHome()` 写法是：

```kotlin
webViews[tab.id]?.let { loadHome(tab.id, it) }   // WebView 不存在就静默跳过
```

`?.let` 在"显示主页但 WebView 尚未创建"时**什么都不做** —— 而这恰恰是
冷启动时的常态，于是主页停在默认浅色上。现在统一走
`ensureHomeRendered()`，内部用 `obtainWebView` 把 WebView 建出来再渲染，
直接消灭这条会静默失效的路径。

---

## 五、启动图标

图标源自 `tools/icon.xml`：**渐变圆底（青 #37E0C8 → 蓝 #1E88E5）+ 白色浏览器外环 +
一枚带裸羽柄的羽毛 + 高光点**。

羽毛的造型目标是"在 48px 下也一眼认得出是羽毛"，而不是"渐变圆里有个白色叶片"：
根部要露出裸羽柄、羽轴要弯、羽枝要一片片分开。

Android 的自适应图标有一个容易踩的坑：**画布 108dp，但只有中心 72dp 直径的
圆形区域对所有遮罩形状都可见**（圆形、方形、圆角、水滴…）。原图整圆半径占满
画布，直接用作自适应图标会有约 33% 被裁掉。

因此拆成三层：

| 层 | 内容 | 为什么 |
|---|---|---|
| `ic_launcher_background` | 渐变方底（铺满 108dp） | 背景层被裁是**预期**的，铺满才不会露边 |
| `ic_launcher_foreground` | 外环 + 羽毛 + 高光（缩到 72dp 内） | 前景必须完整可见，任何遮罩都不能裁 |
| `ic_launcher_monochrome` | 环 + 羽毛剪影 | Android 13+ 主题图标由系统重新着色，细节多了会糊 |

前景缩放系数 **0.9176**（白环外沿恰好贴住安全区边界）。这个系数不再写死 ——
`tools/gen_launcher_icon.py` 从 `icon.xml` 里实测的圆环参数算出来，环一改就自动重算。

### 羽毛造型：五条硬约束

羽毛不是"画得像就行"——它最终要在 **48px** 的图标里被认出来。造型经过
`tools/emit_icon_xml.py` 反复迭代，收敛到五条硬约束：

1. **根部必须露出一截裸羽柄。** 这是最强的"这是羽毛"信号：羽片之外还有一截光杆。
   最初那版羽轴整条都埋在羽片里，轮廓是个封闭梭形，读起来就是**叶子**
   （实测：三个斜切缺口被读成"虫咬的洞"，羽轴被读成"叶脉"）。
2. **羽轴要弯，不能是直轴。** 直轴的对称梭形 + 两头尖，无论加多少装饰都还是叶子。
   现在羽轴按 `t^2.6` 侧弯（近尖加速，成钩），凸侧饱满、凹侧近尖内收。
3. **羽片根部要"截断"、且左右不对称。** 从一点慢慢张开的窄根是叶子的画法；
   羽毛的羽片在根部就是张开的，右下 12.0 / 左上 5.5，整体偏在羽轴一侧。
4. **羽枝缝要细口、深进、朝根部斜切 —— 而且必须写进轮廓，不能靠"宽度包络"。**
   这是最费劲的一条：用"包络线 × 深度系数"的写法，缝在几何上**只能垂直于羽轴**，
   渲染出来是一排等距方齿，像拉链或梳子。改成在轮廓里显式插入折线
   （走到缝口 → 向内朝根部斜切到谷底 → 折回缝口另一端）之后才读成"一片片羽枝"。
   缝口只有 0.012~0.020 宽，谷底却吃掉该处 30%~70% 的宽度。
5. **羽轴用负空间挖，不能叠白线；而且不能压到外环。**
   白压白在视觉上等于没画，正确做法是在同一条路径内用 `fillType="evenOdd"`
   挖一条细槽 —— 注意 Android 拼写是驼峰 `evenOdd`，SVG 是小写 `evenodd`，
   写错了子路径会被填成实心。每道缝的谷底都留出"槽半宽 + 1.3"的余量，
   缝不会把羽轴切断（`emit_icon_xml.py` 会逐段复算并在超界时报出来）。
   羽毛离环心最远 66.4，环内沿 71.0，留 4.6 间隙：两个白块一旦相切，
   在低分辨率下会粘连成一坨。

> 这些全是"看起来"的问题，静态检查抓不到，只能靠预览逐轮比对：
> `python tools/emit_icon_xml.py` 会打印自身长宽比、缝的深度/缝口、自交检查、
> 与环的间隙；`tools/svg_preview.py` 与 `tools/preview_launcher_png.py`
> 则把结果栅格化成 ASCII，在终端里就能看构图。
> `LauncherIconTest` 把其中可量化的部分（长宽比、evenOdd、安全区）固化成了回归测试。

### 生成与校验工具链

```bash
python tools/emit_icon_xml.py          # 羽毛造型定稿 -> 生成 tools/icon.xml
python tools/check_icon_svg.py         # SVG 安全区 + ASCII 预览
python tools/gen_launcher_icon.py      # icon.xml -> 3 层 vector drawable
python tools/make_icons.py             # 生成 API<26 的 5 档 PNG
python tools/check_launcher_icons.py   # 校验矢量层 + 安全区
python tools/check_icon_composition.py # 校验构图（羽毛是否压环、高光是否被遮）
python tools/check_launcher_png.py     # 校验 PNG
python tools/preview_launcher_png.py mipmap-xxxhdpi   # ASCII 预览
python tools/verify_icon_assertions.py # 复算 LauncherIconTest 的全部断言
```

> `make_icons.py` 画位图时必须按 SVG 的 **evenodd 规则异或**各子路径。
> 早先它逐个子路径填白，把负空间的羽轴槽也填成了白色 ——
> 矢量层有 `fillType="evenOdd"` 兜着，所以只有 API 23~25 的 PNG fallback
> 上悄悄没了羽轴，属于"检查全绿但图是错的"。

> 工具链里 `svg_preview.py` 是一个不依赖 cairo 的最小 SVG 光栅化器
> （支持 circle / path / 贝塞尔 / 线性渐变 / `<g transform>` 与属性继承），
> 因为本机装不上 cairo，而图标造型必须能"看见"才能调。

> API 23~25 不支持自适应图标，故保留 `mipmap-*/ic_launcher.png` 位图 fallback。

### 主页 logo：与启动图标同一造型，但媒介不同

主页头部那枚圆形 logo（`HomePage.kt` 里的内联 SVG）画的是同一只羽毛，走的是
另一套媒介约束 —— 这个差别值得写清楚，否则很容易"照抄启动图标"把图标画糊：

| | 启动图标 | 主页 logo |
|---|---|---|
| 画布 | 108dp 自适应图标（安全区 72dp） | 24×24 viewBox，显示 40px |
| 媒介 | **填充**白色剪影 + `evenOdd` 负空间 | **描边**线画，`stroke-width` 1.6 |
| 羽枝缝 | 在轮廓上挖细口（缝口 0.012~0.020） | 羽片内部画短线（画不出那么细的口） |
| 羽片长宽比 | 3.5 | 2.35 |

两条硬约束：

1. **羽片必须够宽，否则羽枝线会糊成一片。** 描边宽 1.6 时，羽轴与边缘之间
   要留出 ≥ 描边宽度的空隙；实测羽片半宽 < 4 单位时三条线就并成一条白块
   （早期版本试过 5.4 单位宽，凸侧羽枝直接和羽轴粘死）。旧 logo 能看，
   正是因为它那条月牙有 ≈ 8 单位宽。
2. **羽片轮廓要用两条"开放"曲线，不能闭合成一个圈。** 闭合轮廓 + 羽轴 + 羽枝线
   在 40px 下会读成一整块白色实心（enclose 出来的区域视觉上被填掉了），
   旧 logo 的两条开放曲线才是对的画法。

改版保留了旧 logo 的开放曲线骨架，只把造型换成启动图标的性格：根部露出裸羽柄
（占全长 17%）、羽轴按 `t^2.4` 侧弯、羽片左右不对称（凸侧 4.7 / 平侧 3.2）、
3 道羽枝缝一律朝根部斜切。

```bash
python tools/preview_home_mark.py      # 当前造型 -> build/home-mark*.png（200px + 40px）
python tools/preview_home_mark.py old  # 改版前的造型，用于对照
```

---

## 六、静态自检工具

设备编译太慢（真机跑 Gradle 要数分钟），因此 `tools/` 下提供了一批不依赖
编译的检查脚本，改动后先跑它们能挡掉大部分低级错误：

| 脚本 | 作用 |
|---|---|
| `tools/static_check.py` | 括号配平、未使用 import、自定义 composable 调用点的必填参数、残留旧 API |
| `tools/check_calls.py` | 交叉核对所有函数调用点的具名参数与声明是否一致（改签名后最易漏改） |
| `tools/check_home_html.py` | 从 `HomePage.kt` 抽出 HTML 模板实例化，检查标签配平、CSS 变量配对、SVG 里误用 `var()`、外部资源引用 |
| `tools/verify_home_assertions.py` | 对着 `HomePage.kt` 复算 `isHomeUrl` 的全部断言，确认测试与实现一致 |
| `tools/check_launcher_icons.py` | 校验生成的 3 层自适应图标矢量：pathData 可解析、前景是否落在 72dp 安全区内 |
| `tools/check_launcher_png.py` | 校验 API<26 的 PNG 图标：圆形遮罩、渐变方向、白色元素占比 |
| `tools/check_icon_composition.py` | 量化图标构图：羽毛是否压在环的笔画上、高光点是否被羽毛遮住 |
| `tools/check_dead_code.py` | 找出「定义了但没人调用」的动作型函数（见 §四.9，此坑踩过两次） |
| `tools/check_download_bytes.py` | 比对 `formatBytes` 在 UI 层与测试层的两份实现，防止测试测的是旧逻辑 |
| `tools/check_tabs_layer.py` | 校验标签面板的层级与动画约束（遮罩必须画在面板之后、拖拽手势不吞点击等） |
| `tools/check_session.py` | 校验会话恢复接线：保存挂在 onStop、协程不被 viewModelScope 取消、无痕不落盘 |
| `tools/check_bookmark_folders.py` | 校验文件夹不建表、删文件夹不连带删书签、导出规则与新建入口 |
| `tools/check_ux_fixes.py` | 校验地址栏真的释放焦点、书签重命名/删除确认、图标存储与迁移不丢数据 |
| `tools/check_junit_args.py` | 抓 JUnit 参数顺序写反（应为 `(message, value)`） |
| `tools/check_workflow.py` | 校验 GitHub Actions YAML 结构 |
| `tools/check_signing.py` | 抓签名配置里"空字符串被当成路径"的经典崩溃 |
| `tools/check_icon_fidelity.py` | **重新解析官方源文件，与 `LingIcons.kt` 逐点比对**，抓"源改了、生成物没重跑"的漂移 |
| `tools/ascii_icons.py` | 把图标按 even-odd 扫描线填充渲染成 ASCII，编译前肉眼校验造型 |
| `tools/import_material_icons.py` | 官方 VectorDrawable → Compose 矢量代码 |
| `tools/emit_ling_icons.py` | 组装 `LingIcons.kt` |
| `tools/preview_home_mark.py` | 把 `HomePage.kt` 里的主页 logo（描边线画）渲染成 PNG，终端里看不到图形时用它 |
| `tools/preview_launcher_png.py` | 把 PNG 图标渲染成 ASCII 预览（终端里就能看构图） |

```bash
python tools/static_check.py
python tools/check_calls.py
python tools/check_home_html.py
python tools/verify_home_assertions.py
python tools/check_launcher_icons.py
python tools/check_launcher_png.py
python tools/check_icon_composition.py
python tools/check_dead_code.py
python tools/check_download_bytes.py
python tools/check_icon_fidelity.py

# 图标管线（改了图标才需要，产物会写回 LingIcons.kt）
python tools/import_material_icons.py
python tools/emit_ling_icons.py
python tools/ascii_icons.py Download Layers          # 渲染指定图标
python tools/preview_launcher_png.py mipmap-xxxhdpi  # 预览启动图标
```

---

## 七、已验证内容

| 项目 | 结果 |
|---|---|
| `:app:assembleDebug` | ✅ 通过（图标改版后重新验证） |
| `:app:testDebugUnitTest` | ✅ **147 个用例全部通过**（`UrlUtilsTest` 20 / `FaviconFetcherTest` 12 / `HomePageTest` 19 / `TabOrderTest` 15 / `BookmarkFolderTest` 14 / `LingSettingsTest` 14 / `LauncherIconTest` 12 / `LingIconsTest` 11 / `DownloadTest` 10 / `SessionSnapshotTest` 9 / `TabInitialTest` 6 / `PendingDownloadTest` 5） |
| `:app:assembleRelease`（R8 压缩） | ✅ 通过，产物 1.4 MB（图标改版前） |
| APK 签名校验 | ✅ v1 + v2 方案均通过 |
| 真机安装（Xiaomi MI 8 / Android 14） | ✅ `adb install` 成功 |
| 真机启动 | ✅ 无崩溃，`Displayed MainActivity: +884ms` |
| WebView 进程 | ✅ sandboxed_process 正常拉起 |
| View 层级 | ✅ Compose 树与 WebView 宿主 `FrameLayout` 布局正确 |
| 静态自检（18 个脚本） | ✅ 全部通过 |

> 图标已全部替换为官方 Material Symbols（27 个，见 §四.2），
> 并通过 `check_icon_fidelity.py` 与源文件逐点核对，
> 真机观感已确认。

### 真机逐项验证记录

以下功能均在 Xiaomi MI 8 / Android 14 上实机确认通过：

| 功能 | 状态 |
|---|---|
| 下载管理 | ✅ |
| 标签页缩略图（favicon） | ✅ |
| 标签页长按拖拽排序 | ✅ |
| 夜间模式 | ✅ |
| 官方图标观感 | ✅ |
| 会话恢复（含关闭开关后的表现） | ✅ |
| 书签文件夹（移动 / 重命名 / 删除确认） | ✅ |
| 地址栏提交后焦点归还网页 | ✅ 见下方日志 |

地址栏焦点修复的真机确认日志（`focusWebContent` 每一步都成功）：

```
D LingFocus: focusWebContent host=true webview=true wvHasFocus=true hostHasFocus=true
```

四个值全为 `true` 说明：宿主取回焦点、WebView 取回焦点、且最终两级
`hasFocus()` 都为真 —— 焦点确实落到了 WebView 这一层，而不是悬空。
确认修复后该诊断日志已移除（见 §三.6）。

**尚待在真机确认**（本轮改动）：

| 功能 | 需要重点看什么 |
|---|---|
| 书签网站图标 | 进书签页后图标是否逐步出现（需联网） |
| 升级保留数据 | **覆盖安装**（`adb install -r`）后书签/历史/文件夹是否完整 |

> ⚠️ 最后一项必须**覆盖安装**验证，不能先卸载 —— 卸载会清掉数据库，
> 那样测不出增量迁移是否正确，而这一版恰好把 `onUpgrade` 从
> "无条件 DROP 重建"改成了 `ALTER TABLE`（见 §三.1）。

### 羽毛造型改版的验证记录

| 项目 | 结果 |
|---|---|
| 9 个自检脚本（`check_icon_svg` / `check_launcher_icons` / `check_launcher_png` / `check_icon_composition` / `verify_icon_assertions` / `check_home_html` / `verify_home_assertions` / `static_check` / `check_calls`） | ✅ 全部通过 |
| `:app:processDebugResources` | ✅ 通过 —— AAPT2 接受新的 3 层矢量图与 5 档 PNG |
| 生成管线幂等性 | ✅ 重跑 `emit_icon_xml` → `gen_launcher_icon` → `make_icons`，8 个产物逐字节一致 |
| `:app:testDebugUnitTest` / `:app:assembleDebug` | ✅ 通过（修掉下面两个编译错误之后） |
| 主页 logo 造型 | ✅ 用 `tools/preview_home_mark.py` 把 `HomePage.kt` 里的内联 SVG 描边渲染成 PNG 逐轮比对（该 SVG 是描边线画，`svg_preview.py` 只支持填充图形，渲染不了它） |

顺带修掉两处让工程编译不过的错误（与造型无关，但会挡住任何真机验证）：

| 位置 | 问题 | 修法 |
|---|---|---|
| `BrowserScreen.kt:124` | 用了 `UrlUtils.hostOf()` 却没 import | 补 `import com.ling.browser.util.UrlUtils` |
| `BottomToolbar.kt:53` | `modifier: Modifier = Modifier` 排在插槽 `moreContent` **之后**，Kotlin 的尾随 lambda 只能绑定最后一个形参，于是调用方的 `BottomToolbar(...) { 菜单项 }` 被当成 modifier 传入，`moreContent` 反而漏传 | 把 `modifier` 挪到 `moreContent` 之前（也符合 Compose 约定：modifier 是第一个可选参数，插槽 lambda 排最后） |

以及修掉 `static_check.py` 的一个**误报**：它做 import 检查前会把字符串字面量一起抹掉，
而上面第一处的 `UrlUtils` 恰好写在字符串模板 `${UrlUtils.hostOf(url)}` 里 ——
字符串模板是**会执行**的代码，抹掉之后就被判成"import 未使用"。
现在 import 检查改用"只剔注释、保留字符串"的模式（其余检查仍然剔除字符串，
否则括号配平会被 HTML 模板里的括号带偏）。


### 单元测试覆盖的解析场景
```
example.com                -> https://example.com
192.168.1.1:8080           -> http://192.168.1.1:8080
about:blank                -> about:blank          (不带 // 的 scheme)
C:\Users\a.html            -> file://C:\Users\a.html (盘符不被误判)
hello world                -> 必应搜索（默认引擎）
中文搜索                    -> 必应搜索
例子.中国                   -> https://例子.中国
```

> 测试最初抓出两个真实 bug 并已修复：`about:blank` 被误送搜索、
> `C:\Users\a.html` 因 `.html` 被当成顶级域而误判为网址。

---

## 八、后续可扩展方向

参照 Via 的完整能力，以下功能尚未实现，按优先级排列：

1. **广告拦截** —— 通过 `shouldInterceptRequest` 做资源级拦截，内置规则 + 自定义规则
2. **资源嗅探** —— 注入 JS 扫描页面媒体链接，抓取视频/音频/图片
3. **插件脚本扩展** —— 用户脚本注入机制
4. **阅读模式** —— 正文提取，去除广告与导航

### 已完成的清单（原「后续方向」）

| 功能 | 实现要点 |
|---|---|
| 下载管理 | 系统 `DownloadManager` + 下载前确认；状态回查同步 |
| 标签页缩略图与拖拽排序 | favicon 缩略图（非截图，省 50 MB 内存）+ 长按拖拽 |
| 会话恢复 | `onStop` 保存 + 冷启动重建；无痕不入库；设置里可开关 |
| 书签文件夹 | 单层文件夹；**不建表**，由书签归属派生（无幽灵文件夹） |
| 书签网站图标 | 按 URL 自行抓取 `/favicon.ico`；存压缩字节而非 `Bitmap` |

> 早先下载的 9 个未使用官方图标（`help` / `language` / `license` 等）
> 可对应「关于页 / 翻译 / 许可」等后续功能，需要时直接映射即可，
> 无需重新下载。

---

## 九、许可

本项目为学习与技术演示用途。「翎」与 Via 浏览器无任何关联，
未使用 Via 的任何代码或资源。
