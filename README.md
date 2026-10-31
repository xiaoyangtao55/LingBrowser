# 翎 · 极简浏览器

> 轻巧、干净、无推送。一枚羽毛的重量，装得下整个 Web。

「翎」是一款参照 **Via 浏览器**理念实现的 Android 浏览器：把界面压到最简、
把速度提到最快，不做新闻流、不做信息流干扰，只专心把「打开网页」这件事做好。

- **包名**：`com.ling.browser`
- **兼容**：Android 6.0 (API 23) ~ Android 16 (API 36)
- **UI**：Jetpack Compose + **Material You 3**（Android 12+ 自动跟随壁纸动态取色）

---

## 一、当前实现的功能

### 核心浏览
| 功能 | 说明 |
|---|---|
| 多标签页 | 每个标签页保留独立 WebView 实例，切换不丢页面状态；实例上限按内存压力动态调整（正常 10 / 吃紧 6 / 危急 3），超出才按 LRU 回收；被回收的标签切回来会恢复**历史栈与滚动位置**；favicon 缩略图 + 长按拖拽排序 |
| 智能地址栏 | 自动区分「网址」与「搜索词」；**加载完成后显示网页标题**，聚焦时切回真实网址可编辑；聚焦时全选，输入时给出书签/历史联想 |
| 书签 | 一键收藏/取消，独立管理页；支持**网站图标**、重命名、删除确认、单层文件夹归档；**阅读模式下收藏的是原文地址** |
| 阅读模式 | **正文提取**（Readability 算法 + 中文适配），去除广告/导航/评论区；可调四档字号（原地改变量，不丢滚动位置），明暗跟随夜间模式与动态取色 |
| 资源嗅探 | 注入 JS 扫描 DOM，抓取视频/音频/图片/可下载文件；结果二级页按类型分组，可打开或直接下载 |
| 历史记录 | 自动去重 + 次数累加，按「今天/昨天/更早」分组，支持单条删除与清空 |
| 无痕模式 | 独立标签页；不写历史、关闭 DOM storage 与 Cookie；Cookie 策略跟随当前标签，改设置不会清掉它的前进/后退历史 |
| 从其它应用打开 | 别的 App「用翎打开链接」→ 直接打开该网页（当前标签在主页就复用它，否则新开标签）；**分享纯文本**则当作搜索词 |
| 长按菜单 | 长按链接 → 新标签页打开 / 复制链接地址；长按图片 → 打开 / 新标签页打开 / 保存 / 复制图片地址；长按正文仍是系统的文字选择 |
| 前进/后退/刷新/停止 | 刷新常驻在地址栏右侧；底部工具栏保留前进/后退/主页/标签页/更多 |
| **内置主页** | `ling://home` 由 **Compose 原生渲染**（WebView 里只有 `about:blank` 占位，返回键仍能回到主页），**离线可用**；跟随 Material You 动态取色；**只有一个时钟 + 一个搜索框**（网址/搜索词自动判定，与地址栏同一套逻辑）；背景由原生背景层（纯色 / 自定义图片）供色 —— 消除冷启动白帧 |

### 页面适配
| 功能 | 说明 |
|---|---|
| 夜间模式 | 跟随系统 / 始终开启 / 始终关闭 三态（**设置里可选三态**）；**内置主页同步跟随** |
| 网页强制夜间 | **跟随夜间模式暗化**（默认开，可关）；Android 10+ 用 `setForceDark`/`setAlgorithmicDarkeningAllowed`，切换时立即注入/移除兜底 CSS，组件同步切换 |
| 电脑模式 | 切换为桌面版 Safari UA，触发站点桌面布局（有顶部提示条） |
| 无图模式 | 关闭图片加载，省流量 |
| 广告拦截 | 域名级拦截常见广告/跟踪域名与路径，`shouldInterceptRequest` 资源级拦截；主文档永不误杀；**内置精简表（140 条）** 开箱即用；**支持自定义规则**（二级菜单增删域名）；**支持订阅名单**（从网址或本地导入 Hosts / AdGuard 域名格式）；**「浏览」设置页显示累计拦截计数**（跨重启累加，可清零），默认开 |
| JavaScript 开关 | 可关闭以提速、去干扰 |
| 自定义主页 | 填入任意网址；**留空则使用「翎」的内置主页** |
| 标签页面板高度 | 全屏 / 一半 两档，默认一半；面板半透明，可透出后方网页 |

### 搜索引擎
内置 百度 / 必应 / Google / DuckDuckGo 四家，可随时切换。**默认必应**。

---

## 二、工程结构

```
LingBrowser/
├── .github/workflows/release.yml  # CI：PR 跑静态检查 / Lint / 测试并出 release APK
├── app/
│   ├── build.gradle.kts           # 模块配置（含签名、R8、localeFilters）
│   ├── proguard-rules.pro         # 混淆规则（保留 JS 接口与 Room/Compose 元数据）
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── java/com/ling/browser/
│       │   │   ├── LingApplication.kt        # 依赖容器入口 + onTrimMemory 转发
│       │   │   ├── MainActivity.kt           # 唯一 Activity + 显式返回栈
│       │   │   ├── data/
│       │   │   │   ├── AppContainer.kt       # 手工 DI 容器
│       │   │   │   ├── db/                   # SQLiteOpenHelper + 实体
│       │   │   │   ├── prefs/SettingsStore.kt# DataStore 偏好设置
│       │   │   │   └── repo/                 # 书签 / 历史仓库（StateFlow 暴露）
│       │   │   ├── ui/
│       │   │   │   ├── BrowserViewModel.kt   # 状态与业务逻辑（按域委托）
│       │   │   │   ├── UserScriptCoordinator.kt # 用户脚本安装、GM 桥、菜单与通知
│       │   │   │   ├── DownloadDelegate.kt   # 下载确认、探测、权限与队列
│       │   │   │   ├── BookmarksDelegate.kt  # 收藏、图标、重命名与文件夹
│       │   │   │   ├── components/           # 地址栏、底部工具栏、四个底部面板
│       │   │   │   ├── screens/              # 浏览/标签/书签/历史/设置
│       │   │   │   └── theme/                # Material You 3 主题 + Material Symbols 图标
│       │   │   ├── util/UrlUtils.kt          # 地址栏解析（含单元测试）
│       │   │   └── web/
│       │   │       ├── WebTabManager.kt      # 标签页 + WebView 实例池
│       │   │       ├── LingWebView.kt        # WebView 子类（设置/UA/夜间）
│       │   │       ├── ReaderController.kt   # 阅读模式提取与重排版
│       │   │       ├── ScriptBridge.kt       # 用户脚本注入与 GM 桥
│       │   │       ├── CertPromptQueue.kt    # 证书错误裁决队列
│       │   │       ├── SafeBrowsingQueue.kt  # Safe Browsing 裁决队列
│       │   │       ├── InternalPage.kt       # 内部页判定 + 地址归一化
│       │   │       └── HomePage.kt           # 内置主页地址常量与判定
│       │   └── res/                          # 图标、主题、字符串
│       └── test/java/com/ling/browser/
│           ├── util/UrlUtilsTest.kt          # 地址栏解析
│           ├── web/HomePageTest.kt           # 主页地址判定
│           ├── web/InternalPageTest.kt       # 内部页判定与地址归一化
│           ├── data/prefs/LingSettingsTest.kt# 设置项默认值与枚举
│           └── ui/theme/LingIconsTest.kt     # 图标几何回归
├── keystore/
│   ├── README.md                  # 签名与 Secrets 配置说明
│   └── ling-release.jks           # 发布签名密钥（已 .gitignore）
└── tools/                         # 不依赖编译的静态自检脚本
    ├── static_check.py
    ├── check_calls.py
    ├── import_material_icons.py   # 官方图标 -> Compose 矢量代码
    ├── emit_ling_icons.py         # 组装 LingIcons.kt
    ├── ascii_icons.py             # 图标渲染成 ASCII，肉眼校验造型
    ├── check_icon_fidelity.py     # 生成物与源文件逐点比对
    ├── check_session.py           # 会话恢复接线校验
    ├── check_bookmark_folders.py  # 书签文件夹校验
    ├── check_bookmark_backup.py   # 书签导入导出校验
    ├── check_ux_fixes.py          # 焦点/书签编辑/图标/文件上传校验
    ├── check_reader.py            # 阅读模式提取与接线校验
    ├── check_adblock_subscription.py # 广告拦截订阅（格式兼容/存储/热路径/接线）校验
    └── check_client_thread.py     # 校验 shouldInterceptRequest 不碰 WebView（后台线程会闪退）
```

---

## 三、构建

### 环境要求
- JDK 21
- Android SDK Platform 36 +
- Gradle 9.4.1
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

### CI 自动构建

[.github/workflows/release.yml](.github/workflows/release.yml)：

- **PR 到 main/master** → 先跑 `static-checks`（文本级检查，不需要 Android SDK），再跑 Android Lint、单元测试并构建 release APK，产物在该次运行的 Artifacts 里下载
- **打 `v*` 标签 / 手动触发** → 同样先过检查，再构建；打标签时额外自动创建 GitHub Release 并附上 APK
- 签名密钥通过仓库 Secrets 注入（不配也能出未签名包）：

| Secret | 说明 |
|---|---|
| `LING_KEYSTORE_BASE64` | 密钥库的 Base64 编码 |
| `LING_STORE_PASSWORD` | 密钥库口令 |
| `LING_KEY_ALIAS` | 密钥别名 |
| `LING_KEY_PASSWORD` | 密钥口令 |

---

## 四、设计取舍

每一条反直觉的实现选择、以及踩过的坑，都记在 **[DESIGN.md](DESIGN.md)**。

那里不是「功能说明」，而是**改代码前应该知道的背景** —— 比如：

- 为什么不用 Room、不用图标字体；
- WebView 实例池为什么这么设计，回收顺序错一步就会在 Chromium 内部空指针崩溃；
- 阅读模式的 `shouldOverrideUrlLoading` 返回值语义，写错会让页面「一直闪」；
- 为什么 `shouldInterceptRequest` 里不能碰 WebView（后台线程会闪退）；
- 会话恢复、地址栏焦点、内部页地址归一化各自的约束。

README 只保留「是什么 / 怎么用 / 怎么构建」，细节看那边。

---


## 五、启动图标


| 资源 | 内容 |
|---|---|
| `mipmap-anydpi-v26/ic_launcher.xml` | 自适应图标（API 26+）：`@color/ic_launcher_background` 背景 + `@mipmap/ic_launcher_foreground` 前景 + `@mipmap/ic_launcher_monochrome` 单色层 |
| `mipmap-anydpi-v26/ic_launcher_round.xml` | 圆形自适应图标；`AndroidManifest` 的 `roundIcon` 指向它 |
| `values/ic_launcher_background.xml` | 背景纯色 `#FFFFFF` |
| `mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.png` | API 23~25 的传统位图 fallback（不支持自适应图标） |
| `mipmap-*/ic_launcher_round.png` | 传统圆形位图 fallback |
| `mipmap-*/ic_launcher_foreground.png` | 自适应图标前景层（位图，各密度一份） |
| `mipmap-*/ic_launcher_monochrome.png` | Android 13+ 主题图标单色层 |

关于"跟随取色"：启动图标是**静态资源**，无法在运行时读到壁纸色；会随壁纸变色的是
Android 13+ 的**主题化图标** —— 系统读 `ic_launcher_monochrome` 单色层并重新着色。

> 自适应图标的画布是 108dp，但只有中心 72dp 直径对所有遮罩形状可见。这套资源已
> 按该规则裁好前景与单色层，无需再手工缩放。

---

## 六、静态自检工具

设备编译太慢（真机跑 Gradle 要数分钟），因此 `tools/` 下提供了一批不依赖
编译的检查脚本，改动后先跑它们能挡掉大部分低级错误：

| 脚本 | 作用 |
|---|---|
| `tools/static_check.py` | 括号配平、未使用 import、自定义 composable 调用点的必填参数、残留旧 API |
| `tools/check_calls.py` | 交叉核对所有函数调用点的具名参数与声明是否一致（改签名后最易漏改） |
| `tools/check_dead_code.py` | 找出「定义了但没人调用」的动作型函数（见 [DESIGN.md](DESIGN.md) §9，此坑踩过两次） |
| `tools/check_download_bytes.py` | 比对 `formatBytes` 在 UI 层与测试层的两份实现，防止测试测的是旧逻辑 |
| `tools/check_download_progress.py` | 校验下载进度的节流与速度采样（字节/时间不能用同一个变量、采样窗口要有下限，见 §四.12） |
| `tools/check_tabs_layer.py` | 校验标签面板的层级与动画约束（遮罩必须画在面板之后、拖拽手势不吞点击等） |
| `tools/check_session.py` | 校验会话恢复接线：保存挂在 onStop、协程不被 viewModelScope 取消、无痕不落盘 |
| `tools/check_bookmark_folders.py` | 校验文件夹不建表、删文件夹不连带删书签、导出规则与新建入口 |
| `tools/check_bookmark_backup.py` | 校验 Netscape HTML 编解码往返一致、导入**只增不删**（重复网址跳过）、拒绝 `javascript:` / `vbscript:` / `data:` 伪协议 |
| `tools/check_ux_fixes.py` | 校验地址栏真的释放焦点、书签重命名/删除确认、图标存储与迁移不丢数据、网页 `<input type=file>` 走 `onShowFileChooser` |
| `tools/check_reader.py` | 校验阅读模式提取脚本语法与算法（用 Node 真实运行）、以及各层接线完整 |
| `tools/check_client_thread.py` | 校验 `shouldInterceptRequest` 方法体不引用 WebView —— 它跑在后台线程，`view.url` 会触发 `checkThread()` 抛异常并让进程闪退 |
| `tools/check_js_dialog.py` | 校验 JS 对话框四个子类型（alert/confirm/prompt/beforeunload）的委托声明、override、接线与穷尽处理，以及 `window.open` 新标签 |
| `tools/check_settings_index.py` | 校验设置搜索索引与界面行一致 —— 新增开关忘了加索引，用户就搜不到它，这类静默不一致只能靠脚本抓 |
| `tools/check_adblock_subscription.py` | 校验订阅名单：解析器是纯函数、Hosts/AdGuard 三格式归一、元素/例外/路径规则跳过并计数、双文件存储、gzip 魔数兜底、拦截热路径读预合并集、各层接线完整 |
| `tools/check_junit_args.py` | 抓 JUnit 参数顺序写反（应为 `(message, value)`） |
| `tools/check_workflow.py` | 校验 GitHub Actions YAML 结构 |
| `tools/check_signing.py` | 抓签名配置里"空字符串被当成路径"的经典崩溃 |
| `tools/check_icon_fidelity.py` | **重新解析官方源文件，与 `LingIcons.kt` 逐点比对**，抓"源改了、生成物没重跑"的漂移 |
| `tools/ascii_icons.py` | 把图标按 even-odd 扫描线填充渲染成 ASCII，编译前肉眼校验造型 |
| `tools/import_material_icons.py` | 官方 VectorDrawable → Compose 矢量代码 |
| `tools/emit_ling_icons.py` | 组装 `LingIcons.kt` |

```bash
python tools/static_check.py
python tools/check_calls.py
python tools/check_dead_code.py
python tools/check_download_bytes.py
python tools/check_download_progress.py
python tools/check_client_thread.py
python tools/check_adblock_subscription.py
python tools/check_icon_fidelity.py

# 图标管线（改了图标才需要，产物会写回 LingIcons.kt）
python tools/import_material_icons.py
python tools/emit_ling_icons.py
python tools/ascii_icons.py Download Layers          # 渲染指定图标
```

---

## 七、已验证内容

| 项目 | 结果 |
|---|---|
| `:app:assembleDebug` | ✅ 通过（换用新图标资源包后重新验证） |
| `:app:testDebugUnitTest` | ✅ **370 个用例全部通过**（`UrlUtilsTest` 20 / `FaviconFetcherTest` 12 / `ReaderPageTest` 22 / `ReaderResultTest` 15 / `UrlSchemeTest` 18 / `AdBlockerTest` 16 / `SniffResultTest` 12 / `HomePageTest` 4 / `InternalPageTest` 11 / `TabOrderTest` 15 / `BookmarkFolderTest` 14 / `BookmarkBackupTest` 17 / `HistoryExportTest` 9 / `LingSettingsTest` 17 / `LingIconsTest` 11 / `TopBarColorEquivalenceTest` 3 / `DownloadTest` 14 / `DownloadDestinationTest` 6 / `DownloadFileNameTest` 17 / `ChunkPlanTest` 8 / `ResumePlanTest` 7 / `PendingDownloadTest` 5 / `DownloadSectionsTest` 4 / `SessionSnapshotTest` 9 / `TabInitialTest` 6 / `TabAddressBarTest` 6 / `LingWebViewClientTest` 2 / `SecurityStateTest` 3 / `BlockRulesTest` 16 / `UserScriptTest` 43 / `HitTargetTest` 8） |
| `:app:assembleRelease`（R8 压缩） | ✅ 通过，产物约 1.4 MB |
| APK 签名校验 | ✅ v1 + v2 方案均通过 |
| 真机安装（Xiaomi MI 8 / Android 14） | ✅ `adb install` 成功 |
| 真机启动 | ✅ 无崩溃，`Displayed MainActivity: +884ms` |
| WebView 进程 | ✅ sandboxed_process 正常拉起 |
| View 层级 | ✅ Compose 树与 WebView 宿主 `FrameLayout` 布局正确 |
| 静态自检（17 项） | ✅ 全部通过 |
| 真机多标签压测（MI 8） | ✅ 10 标签来回切不重载、不崩溃；回收后历史栈与滚动位置均恢复 |


> **多标签实例池**（本轮新增）：正常内存下上限 10，超过才按 LRU 回收；
> 被回收的标签切回来会恢复历史栈与滚动位置。已在 Xiaomi MI 8 上验证：
> 开 10 个标签来回切**不重载**，回收后切回**滚动位置正确**。
>
> 回收路径本身也验证过：`enforceCacheLimit()` 回收实例时先摘 document-start
> 句柄再 `destroy()` —— 顺序写反会在 Chromium 内部空指针崩溃（历史上 13 次
> 崩溃的根因），详见 [DESIGN.md](DESIGN.md) §3。

> **阅读模式**：逻辑层与接线已通过单测与静态检查，但中文站点的实际提取
> 效果依赖具体站点的 DOM 结构，需要实测若干站点后反馈。

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
| 书签网站图标 | ✅ 图标正常显示 |
| **覆盖安装后数据完整保留** | ✅ 书签 / 历史 / 文件夹均在 |

地址栏焦点修复的真机确认日志（`focusWebContent` 每一步都成功）：

```
D LingFocus: focusWebContent host=true webview=true wvHasFocus=true hostHasFocus=true
```

四个值全为 `true` 说明：宿主取回焦点、WebView 取回焦点、且最终两级
`hasFocus()` 都为真 —— 焦点确实落到了 WebView 这一层，而不是悬空。
确认修复后该诊断日志已移除（见 [DESIGN.md](DESIGN.md) §3.6）。

**覆盖安装的验证意义**：这一版把 `onUpgrade` 从「无条件 `DROP TABLE` 重建」
改成了 `ALTER TABLE` 增量迁移（见 [DESIGN.md](DESIGN.md) §3.2）。已确认**不卸载、直接
`adb install -r` 覆盖安装后，原有书签/历史/文件夹完整保留**，
即迁移路径正确、不会再因升级丢数据。

> 至此本轮改动**全部通过真机验证**，无待确认项。

### 启动图标资源包改版的验证记录

| 项目 | 结果 |
|---|---|
| `:app:processDebugResources` | ✅ 通过 —— AAPT2 接受新的自适应图标（`@color` 背景 + PNG 前景/单色层）与 5 档传统位图 |
| `:app:assembleDebug` | ✅ 通过，产出新图标 APK |

旧羽毛造型管线（`emit_icon_xml` → `gen_launcher_icon` → `make_icons` 及其校验脚本）已随本次换图标一并退役，相关记录不再维护。

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

## 八、功能清单

参照 Via 的完整能力，下列功能均已实现：

| 功能 | 实现要点 |
|---|---|
| **用户脚本（Tampermonkey 风格）** | `@match`/`@require`/`@connect` 元数据解析、document-start 注入、完整 GM API（存储 / 请求 / 菜单 / 通知 / 开标签）、安装前展示生效范围 |
| **网页原生对话框** | `alert`/`confirm`/`prompt`/`beforeunload` 四种，`window.open` 新标签（带手势过滤） |
| **HTTP 认证与设备权限** | 401 基础认证、客户端证书、网页定位、摄像头/麦克风 |
| **网页全屏视频** | HTML5 全屏按钮接管 + 渲染进程崩溃恢复 |
| **多标签实例池** | 上限按内存压力动态调整（10/6/3）；回收后恢复历史栈与滚动位置 |
| 下载管理 | 系统 `DownloadManager` + 下载前确认；状态回查同步 |
| 标签页缩略图与拖拽排序 | favicon 缩略图（非截图，省 50 MB 内存）+ 长按拖拽 |
| 会话恢复 | `onStop` 保存 + 冷启动重建；无痕不入库；设置里可开关 |
| 书签文件夹 | 单层文件夹；**不建表**，由书签归属派生（无幽灵文件夹） |
| 书签网站图标 | 按 URL 自行抓取 `/favicon.ico`；存压缩字节而非 `Bitmap` |
| 书签备份与恢复 | 导出 **Netscape 书签 HTML**（Chrome/Edge/Firefox/Safari 互认）；导入按 URL 去重**合并**、只增不删、不覆盖已有图标；拒绝 `javascript:` 等伪协议；边读边卡 8 MiB 上限 |
| 阅读模式 | 移植 Readability 打分算法（<11 KB）+ **中文阈值适配**；可调四档字号 |
| 广告拦截 | `shouldInterceptRequest` 资源级拦截，域名黑名单 + 路径关键字；抽 `AdBlocker` 纯 Kotlin 可单测；主文档保护；默认开；**支持自定义域名规则（二级菜单增删）** |
| 资源嗅探 | 注入 JS 扫描 DOM，抓视频/音频/图片/下载链接；结果二级页分组展示，可打开或下载 |
| 外部链接开关 | 非 http(s) 链接是否交给外部 App，**默认关**（本机没装该 App 时更稳定） |


---

## 九、许可

本项目为学习与技术演示用途。「翎」与 Via 浏览器无任何关联，
未使用 Via 的任何代码或资源。
