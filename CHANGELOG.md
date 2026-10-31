# Changelog

## [1.12.0](https://github.com/xiaoyangtao55/LingBrowser/compare/v1.11.0...v1.12.0) (2026-10-31)


### Features

* **web:** 标签实例池按内存压力动态调整，回收时保存历史栈与滚动位置 ([fadabdb](https://github.com/xiaoyangtao55/LingBrowser/commit/fadabdbe5375e47fad98232cab9e59098ae9412e))


### Bug Fixes

* **web:** 修复多标签切换时的 SIGSEGV 崩溃与白页 ([3858bfb](https://github.com/xiaoyangtao55/LingBrowser/commit/3858bfb9e99e20f5386b80ee4b0fba4a14d3cb04))
* **web:** 修正实例池在并发、生命周期与状态恢复上的四处缺陷 ([3927dbd](https://github.com/xiaoyangtao55/LingBrowser/commit/3927dbd4feff08fc1218b2a78f3b487729fa0993))
* **web:** 修正滚动位置抓取时机、恢复重试与 destroyAll 生命周期 ([7f10650](https://github.com/xiaoyangtao55/LingBrowser/commit/7f10650194d831fbdafe8d431913ab49417a6246))
* **web:** 回收候选排除加载中标签、无痕不存历史栈，并补齐内存分档 ([c722113](https://github.com/xiaoyangtao55/LingBrowser/commit/c7221132d5906de80fb049140f1223cb05fdf395))

## [1.11.0](https://github.com/xiaoyangtao55/LingBrowser/compare/v1.10.0...v1.11.0) (2026-10-29)


### Features

* **ui:** 界面改为 Material 3 Expressive 风格 ([e77ed25](https://github.com/xiaoyangtao55/LingBrowser/commit/e77ed25a7d7f5b7170b01c38b28a21c00ab1f882))

## [1.10.0](https://github.com/xiaoyangtao55/LingBrowser/compare/v1.9.1...v1.10.0) (2026-10-29)


### Features

* 内置主页改为 Compose 原生渲染 ([1bdd684](https://github.com/xiaoyangtao55/LingBrowser/commit/1bdd6841e44fb6ca6a18f4aae3f49e5015b2d3f8))

## [1.9.1](https://github.com/xiaoyangtao55/LingBrowser/compare/v1.9.0...v1.9.1) (2026-10-25)


### Bug Fixes

* 修复低版本系统上的 NewApi lint 错误 ([e92530b](https://github.com/xiaoyangtao55/LingBrowser/commit/e92530bc24844eb9277626759b0882a6bb70e282))

## [1.9.0](https://github.com/xiaoyangtao55/LingBrowser/compare/v1.8.0...v1.9.0) (2026-10-25)


### Features

* 下载列表按文件类型显示图标并按时间分段 ([94eb71c](https://github.com/xiaoyangtao55/LingBrowser/commit/94eb71c03104ebe9bce46c9cff04121ffcace804))
* 下载支持 Cookie/UA、完成通知、并发与网络限制 ([d85f03f](https://github.com/xiaoyangtao55/LingBrowser/commit/d85f03f4c93606725bc3462cb1904ffae0017342))
* 下载支持单文件分块并发与断点续传 ([c2131e7](https://github.com/xiaoyangtao55/LingBrowser/commit/c2131e740f0ff9f59f140d4f4e5da1de9a33c3f8))
* 下载文件名按 Content-Disposition、URL、MIME 三级解析 ([9a2e949](https://github.com/xiaoyangtao55/LingBrowser/commit/9a2e9497bb81f38ae3772ca8cfbec604b3a45fdd))
* 二级页面改为卡片层次过渡并支持预测性返回 ([5014518](https://github.com/xiaoyangtao55/LingBrowser/commit/50145182600e1a91b7d2aa910e7610bafe4e09ee))
* 新增六套主题配色预设，低版本隐藏动态取色开关 ([eb9eb0c](https://github.com/xiaoyangtao55/LingBrowser/commit/eb9eb0cd823168297ce00ab9eb6aa71a574410fd))
* 设置增强（自定义搜索引擎/UA、历史导入、选择性清除）并修复输入法上方空白 ([a724dc6](https://github.com/xiaoyangtao55/LingBrowser/commit/a724dc65ae620abd5488a8dd5dbf841721d8340e))


### Bug Fixes

* 下载列表打开文件不再静默失败 ([6e3d152](https://github.com/xiaoyangtao55/LingBrowser/commit/6e3d15294d8037867c29438fcbb56d6a93c21053))
* 图标造型与 Material Symbols 官方源对齐 ([36ae00e](https://github.com/xiaoyangtao55/LingBrowser/commit/36ae00e26f244420755dc5c6a7312879d6d1d120))
* 收口用户脚本身份泄露与下载文件名路径穿越 ([faef6f4](https://github.com/xiaoyangtao55/LingBrowser/commit/faef6f421d1062827cc26fc15b2b7741c486766a))
* 标题栏与内容区在明暗切换时同帧换色 ([b0fe3d1](https://github.com/xiaoyangtao55/LingBrowser/commit/b0fe3d14d0b2e5ce6bb16053a587b8f3d7a96583))
* 预测性返回在收尾动画期间被打断时会多退一层 ([3382b35](https://github.com/xiaoyangtao55/LingBrowser/commit/3382b35f6ffb456922314ff8f6a7fdef4c7fdb3e))

## [1.8.0](https://github.com/xiaoyangtao55/LingBrowser/compare/v1.7.0...v1.8.0) (2026-10-23)


### Features

* 安装用户脚本前展示 [@match](https://github.com/match) 生效范围 ([ce18f43](https://github.com/xiaoyangtao55/LingBrowser/commit/ce18f439be89b94845bedf18e877bf5abec0655d))
* 支持 [@require](https://github.com/require) 与客户端证书，证书错误改为统一弹窗 ([97e980f](https://github.com/xiaoyangtao55/LingBrowser/commit/97e980f52f96b188d1cce2bc9ee37147450c9dad))
* 补齐 GM API 的参数深度并修复注入垫片 ([10ad52d](https://github.com/xiaoyangtao55/LingBrowser/commit/10ad52d28babce78b031614f060fa67a1c3c4cf6))
* 补齐 GM 存储、资源与 XHR 能力 ([5682fec](https://github.com/xiaoyangtao55/LingBrowser/commit/5682fec91256af1126e5790850ef5f719658a40d))
* 补齐 GM_info 元数据、window.close 与 GM_getTab 家族 ([709e92d](https://github.com/xiaoyangtao55/LingBrowser/commit/709e92d6015fdea1419927b670cf4b4e8c9041a5))
* 补齐 GM.* 命名空间、脚本开标签/下载与安全浏览提示 ([83191f7](https://github.com/xiaoyangtao55/LingBrowser/commit/83191f7d561194e6790aef5b57655e6cf1a7e082))
* 补齐用户脚本菜单、值同步、[@connect](https://github.com/connect) 与通知 ([56f0692](https://github.com/xiaoyangtao55/LingBrowser/commit/56f0692364304ded10946a5a62bbdd2260d7d6c6))


### Bug Fixes

* target="_blank" 链接改为页面内拦截并继承无痕状态 ([89415f2](https://github.com/xiaoyangtao55/LingBrowser/commit/89415f29e517bd168c818e04f3f2e570259162ca))

## [1.7.0](https://github.com/xiaoyangtao55/LingBrowser/compare/v1.6.0...v1.7.0) (2026-10-22)


### Features

* 支持 HTTP 认证、网页定位与设备权限请求 ([2a153c7](https://github.com/xiaoyangtao55/LingBrowser/commit/2a153c7bf78bcae1958ec205f4ad340ca934ca1c))

## [1.6.0](https://github.com/xiaoyangtao55/LingBrowser/compare/v1.5.1...v1.6.0) (2026-10-22)


### Features

* 网页全屏视频、渲染进程崩溃恢复与弹窗手势过滤 ([63a688e](https://github.com/xiaoyangtao55/LingBrowser/commit/63a688eedfca4e4574160f9241bde5ce6014c298))

## [1.5.1](https://github.com/xiaoyangtao55/LingBrowser/compare/v1.5.0...v1.5.1) (2026-10-22)


### Bug Fixes

* 消除 minSdk 23 下的 API 级别 lint 错误（CI lint 关卡恢复通过） ([7421856](https://github.com/xiaoyangtao55/LingBrowser/commit/74218564ee01541e5ec9d8cc30e8d693761cdf76))

## [1.5.0](https://github.com/xiaoyangtao55/LingBrowser/compare/v1.4.0...v1.5.0) (2026-10-22)


### Features

* 支持网页原生 JS 对话框与 window.open 新标签 ([79af68a](https://github.com/xiaoyangtao55/LingBrowser/commit/79af68a5788bf9b77f9eca02d09b220862ba3a2b))

## [1.4.0](https://github.com/xiaoyangtao55/LingBrowser/compare/v1.3.0...v1.4.0) (2026-10-21)


### Features

* 支持用户脚本（Tampermonkey 风格） ([6d3d522](https://github.com/xiaoyangtao55/LingBrowser/commit/6d3d522680dbc1501f6ff47a6569a97079a6c1d9))

## [1.3.0](https://github.com/xiaoyangtao55/LingBrowser/compare/v1.2.0...v1.3.0) (2026-10-20)


### Features

* 网页长按链接/图片弹出上下文菜单 ([8755e2c](https://github.com/xiaoyangtao55/LingBrowser/commit/8755e2c2b84ca83709e3f9f5410b81112f0622e3))


### Bug Fixes

* 书签菜单贴右展开、下载完成后可点击打开、下载确认支持复制链接 ([4b02f05](https://github.com/xiaoyangtao55/LingBrowser/commit/4b02f056e2a82307a073cd59234eadf7561db4fe))

## [1.2.0](https://github.com/xiaoyangtao55/LingBrowser/compare/v1.1.0...v1.2.0) (2026-10-20)


### Features

* 广告拦截域名表升级为可订阅（Hosts / AdGuard 域名格式） ([85bee3a](https://github.com/xiaoyangtao55/LingBrowser/commit/85bee3a731664d5c644958328b2d7fde6d0ca94d))
* 广告拦截累计计数（跨重启） ([9f90849](https://github.com/xiaoyangtao55/LingBrowser/commit/9f90849df430159206ecb50f1e2eb3caaca46853))

## [1.1.0](https://github.com/xiaoyangtao55/LingBrowser/compare/v1.0.0...v1.1.0) (2026-10-19)


### Features

* 「更多」/「标签页」改用 BottomSheet；主页支持自定义图片背景 ([ac24038](https://github.com/xiaoyangtao55/LingBrowser/commit/ac24038bcb20eb065c2bafedab1531b473d55142))
* 「用外部 App 打开链接」开关（默认关） ([f951fe4](https://github.com/xiaoyangtao55/LingBrowser/commit/f951fe48b7e3d411a1db58eb21b92ae728314859))
* 「翎」浏览器首个可用版本 ([69ba83f](https://github.com/xiaoyangtao55/LingBrowser/commit/69ba83fda71cd8e25b3513492e17e835acf6cc6c))
* Add Release Please workflow configuration ([470147c](https://github.com/xiaoyangtao55/LingBrowser/commit/470147c9a31325b71ea02fbe28dc1cde632bc54e))
* Modify release workflow for versioning and permissions ([4116f3a](https://github.com/xiaoyangtao55/LingBrowser/commit/4116f3a9613385f647cffae7309cfdaeacd96ca9))
* 下载前弹出确认框 ([1c0c551](https://github.com/xiaoyangtao55/LingBrowser/commit/1c0c551957309e7a7ff5928e5336043c618a239a))
* 下载管理 ([4d8ef73](https://github.com/xiaoyangtao55/LingBrowser/commit/4d8ef73fce4121d2d35e25c477963bc9484c4475))
* 主页搜索框；阅读模式收藏/会话快照改用对外地址 ([5c1e1fe](https://github.com/xiaoyangtao55/LingBrowser/commit/5c1e1fe49b9110d9b405fab79cee139621b63630))
* 书签文件夹 + 「恢复上次浏览页面」开关 + 添加书签图标修正 ([957233f](https://github.com/xiaoyangtao55/LingBrowser/commit/957233fc170344c3540645cafa74dd2fc90c6262))
* 会话恢复（onStop 保存 + 冷启动重建，无痕不入库） ([afc0d1f](https://github.com/xiaoyangtao55/LingBrowser/commit/afc0d1f46f364be84c631855005e83babd69b3fc))
* 内建下载器（实时进度 / 断点续传 / 暂停恢复 / 通知栏控制） ([784c65c](https://github.com/xiaoyangtao55/LingBrowser/commit/784c65c9b27a0ed5522d095ad802ecbdd0d803a6))
* 启动图标改为主页同款纯色圆底，去掉外环 ([a7f20e8](https://github.com/xiaoyangtao55/LingBrowser/commit/a7f20e8cb2480f2697d5cfb14dee7926ea32e088))
* 启动图标改用主页同款描边羽毛，主题化图标跟随取色 ([9fd2c92](https://github.com/xiaoyangtao55/LingBrowser/commit/9fd2c92322a5282ec35836bf943ba054755c9e99))
* 图标全部换成官方 Material Symbols Outlined ([a376bb9](https://github.com/xiaoyangtao55/LingBrowser/commit/a376bb93b506d7c18790ab4218291e30105e480d))
* 地址栏显示网页标题 + 其它应用「用翎打开链接」真的会打开 ([fba6599](https://github.com/xiaoyangtao55/LingBrowser/commit/fba65996e7d32182f323fd3185b57d7099fc1921))
* 地址栏连接安全提示与证书错误「继续访问」 ([81f5030](https://github.com/xiaoyangtao55/LingBrowser/commit/81f503085dd5cae13e18ff234708d1901b0d83e9))
* 广告拦截（域名级，shouldInterceptRequest 资源级拦截） ([92eb9db](https://github.com/xiaoyangtao55/LingBrowser/commit/92eb9db74ba2b7353b0dd9df9086dee33cf074ee))
* 广告拦截自定义规则（二级菜单增删域名） ([b7e0385](https://github.com/xiaoyangtao55/LingBrowser/commit/b7e0385d8ae00a359ee5b20f7f7ef7d356f70084))
* 新增「备份与恢复」板块，支持书签导入导出 ([ef087e6](https://github.com/xiaoyangtao55/LingBrowser/commit/ef087e6f8af2875e39e44180d27c632f909e3ac3))
* 更多面板改图标网格、二级页面加过渡动画，并移除标签页「全屏」档 ([b57681d](https://github.com/xiaoyangtao55/LingBrowser/commit/b57681dee608cc7f998265af6428fbf1d2e38c57))
* 替换启动图标为 mipmap + Adaptive Icon + Monochrome 资源 ([bd29615](https://github.com/xiaoyangtao55/LingBrowser/commit/bd296150cbbf1e31faccf9eb1ce794a9dd78674f))
* 标签页 favicon 缩略图 + 长按拖拽排序 ([2c55b48](https://github.com/xiaoyangtao55/LingBrowser/commit/2c55b48d70820a267041546d72ab1c34cd0e6037))
* 标签页面板加入场动画，底色透明度 0.82 → 0.92 ([c6dbf00](https://github.com/xiaoyangtao55/LingBrowser/commit/c6dbf00adf7cad957d769b6f5e85579471d3af37))
* 标签页面板去掉 1/4 档，默认改为一半 ([71bba3c](https://github.com/xiaoyangtao55/LingBrowser/commit/71bba3c637e4d9d0805c3edb06ca5b0d7bede99c))
* 标签页面板改为半透明叠加层，可透出后方网页 ([25159d7](https://github.com/xiaoyangtao55/LingBrowser/commit/25159d7801eb865339b17a4dda6ab381c7ee6190))
* 网页 &lt;input type=file&gt; 走系统文件选择器 ([18c47e8](https://github.com/xiaoyangtao55/LingBrowser/commit/18c47e8fdcbb3fc3ee170601d1c2fbaf56433cb5))
* 网页加载进度条改为确定态 ([d46f659](https://github.com/xiaoyangtao55/LingBrowser/commit/d46f6592b894657b6504943a52f2961a3078d3aa))
* 补齐官方图标；收藏状态改用实心/空心区分 ([0192012](https://github.com/xiaoyangtao55/LingBrowser/commit/01920120ba0ab451a6f502e9ac8426edd6e10814))
* 设置拆分为分组子页，新增「关于」页 ([34c8075](https://github.com/xiaoyangtao55/LingBrowser/commit/34c8075155b911fa259cf1bb02e531001b797cdd))
* 资源嗅探（注入 JS 扫描媒体资源） ([4ee8fe7](https://github.com/xiaoyangtao55/LingBrowser/commit/4ee8fe7470e4db4a5be2750d13c17a2852679f9f))
* 阅读模式（正文提取 + 中文适配） ([9aaddbd](https://github.com/xiaoyangtao55/LingBrowser/commit/9aaddbd4a5a4e4e5a8ef8a34ad8a8b7c8821dc2f))


### Bug Fixes

* Add master branch to ReleasePlease workflow triggers ([aa0ba34](https://github.com/xiaoyangtao55/LingBrowser/commit/aa0ba349f2da80d1cd3e193577c670b381003502))
* apksigner 通配符展开成多参数导致签名校验失败 ([3327ee9](https://github.com/xiaoyangtao55/LingBrowser/commit/3327ee9d4f6aeae7bd09e45c9df49911ed9e5b9e))
* CI 产出未签名 APK —— 密钥库路径基准目录不一致 ([42f3a9f](https://github.com/xiaoyangtao55/LingBrowser/commit/42f3a9f2983927173bf2b0d66345659870bfd114))
* Delete .github/workflow directory ([c4a14d3](https://github.com/xiaoyangtao55/LingBrowser/commit/c4a14d3ec8c9844ff750d259ebd76cd7c327a401))
* shouldOverrideUrlLoading 未接管自定义协议导致"一直闪" ([4f4254c](https://github.com/xiaoyangtao55/LingBrowser/commit/4f4254ce03998e1640a13dbbbc93f95215389250))
* 主页/阅读视图的 5 处静默失效 + 2 处状态带偏 ([ce73ad6](https://github.com/xiaoyangtao55/LingBrowser/commit/ce73ad6f773e29cbb37c19c7a99216f0d4401afb))
* 主页不跟随夜间模式；默认搜索引擎改为必应 ([7b75969](https://github.com/xiaoyangtao55/LingBrowser/commit/7b75969f88e284f5462b7e9f0680f18f6c173a88))
* 主页两处体验问题 ([8235ba7](https://github.com/xiaoyangtao55/LingBrowser/commit/8235ba792459483a451bea063a96f722489359b4))
* 修 CI 编译错误（可空链传给非空形参），并把这类错误变成静态判据 ([907157d](https://github.com/xiaoyangtao55/LingBrowser/commit/907157d07a5e22190f5740dc5fa13669ec058021))
* 修复空字符串导致 Gradle 配置阶段崩溃 ([83d9b55](https://github.com/xiaoyangtao55/LingBrowser/commit/83d9b55cc3d55f76148846fe27c421570f0235b6))
* 修正 JUnit 断言的参数顺序，测试源文件无法编译 ([56f250f](https://github.com/xiaoyangtao55/LingBrowser/commit/56f250fde4bf127e4835d338b2892bdbe1aa1326))
* 切换主页背景模式不自动刷新 + 时钟在图片背景上可读性 ([c95c953](https://github.com/xiaoyangtao55/LingBrowser/commit/c95c953c98f3852fe65ea220602b6d8665df27e4))
* 地址栏提交后释放焦点；书签重命名/删除确认；书签网站图标 ([83f9312](https://github.com/xiaoyangtao55/LingBrowser/commit/83f9312fb2d483d2f5c373544c614fec4a12643a))
* 地址栏焦点归还给网页（真正的根因） ([5ec3e27](https://github.com/xiaoyangtao55/LingBrowser/commit/5ec3e2740e87394d8160f746b4c3e76bede80da4))
* 夜间模式三处体验改进 ([d44d600](https://github.com/xiaoyangtao55/LingBrowser/commit/d44d600757d6e48418f87b3afb7cef91fc7d0eec))
* 底部工具栏底色铺满手势条区域，适配底部小白条 ([4f96615](https://github.com/xiaoyangtao55/LingBrowser/commit/4f96615e91088e261a8e08298513599966cb7d5b))
* 无痕历史/Cookie 策略、系统明暗重算、阅读字号（进程级状态与文档重建） ([7665af9](https://github.com/xiaoyangtao55/LingBrowser/commit/7665af9dd49f56b4af79aa17662cb47aa9f803ea))
* 消除主页冷启动白帧，并修复切换背景后主页白屏 ([1e5be28](https://github.com/xiaoyangtao55/LingBrowser/commit/1e5be28c18fe44c087352c73edf07a3b8ece68ff))
* 点地址栏文字进编辑态时编辑的是标题（非编辑态不该挂输入框） ([1c78ca2](https://github.com/xiaoyangtao55/LingBrowser/commit/1c78ca2e87e171d7dfb0b2a05e435f3c5282bd53))
* 自定义协议导致 ERR_UNKNOWN_URL_SCHEME（知乎等 App 内嵌页） ([b0a7a08](https://github.com/xiaoyangtao55/LingBrowser/commit/b0a7a08675f77f35ac1f8eeac0f3cc38ae6b2de6))
* 资源嗅探两处体验改进 ([e92cb7e](https://github.com/xiaoyangtao55/LingBrowser/commit/e92cb7ea0ce4af8a6636c70db2e877d3fb48ee26))
* 阅读模式与既有功能撞车的四个静默失效点 + 主题跟随 ([ff0e621](https://github.com/xiaoyangtao55/LingBrowser/commit/ff0e621404d852a567c35ca2e8a5967cda56e953))
* 阅读模式正文提取时净化 on* 事件属性与 javascript:/vbscript: 伪协议 ([c48d701](https://github.com/xiaoyangtao55/LingBrowser/commit/c48d701f376452c3354efe30adf093ba8630fa4a))

## 1.0.0 (2026-10-19)


### Features

* 「更多」/「标签页」改用 BottomSheet；主页支持自定义图片背景 ([ac24038](https://github.com/xiaoyangtao55/LingBrowser/commit/ac24038bcb20eb065c2bafedab1531b473d55142))
* 「用外部 App 打开链接」开关（默认关） ([f951fe4](https://github.com/xiaoyangtao55/LingBrowser/commit/f951fe48b7e3d411a1db58eb21b92ae728314859))
* 「翎」浏览器首个可用版本 ([69ba83f](https://github.com/xiaoyangtao55/LingBrowser/commit/69ba83fda71cd8e25b3513492e17e835acf6cc6c))
* Add Release Please workflow configuration ([470147c](https://github.com/xiaoyangtao55/LingBrowser/commit/470147c9a31325b71ea02fbe28dc1cde632bc54e))
* Modify release workflow for versioning and permissions ([4116f3a](https://github.com/xiaoyangtao55/LingBrowser/commit/4116f3a9613385f647cffae7309cfdaeacd96ca9))
* 下载前弹出确认框 ([1c0c551](https://github.com/xiaoyangtao55/LingBrowser/commit/1c0c551957309e7a7ff5928e5336043c618a239a))
* 下载管理 ([4d8ef73](https://github.com/xiaoyangtao55/LingBrowser/commit/4d8ef73fce4121d2d35e25c477963bc9484c4475))
* 主页搜索框；阅读模式收藏/会话快照改用对外地址 ([5c1e1fe](https://github.com/xiaoyangtao55/LingBrowser/commit/5c1e1fe49b9110d9b405fab79cee139621b63630))
* 书签文件夹 + 「恢复上次浏览页面」开关 + 添加书签图标修正 ([957233f](https://github.com/xiaoyangtao55/LingBrowser/commit/957233fc170344c3540645cafa74dd2fc90c6262))
* 会话恢复（onStop 保存 + 冷启动重建，无痕不入库） ([afc0d1f](https://github.com/xiaoyangtao55/LingBrowser/commit/afc0d1f46f364be84c631855005e83babd69b3fc))
* 内建下载器（实时进度 / 断点续传 / 暂停恢复 / 通知栏控制） ([784c65c](https://github.com/xiaoyangtao55/LingBrowser/commit/784c65c9b27a0ed5522d095ad802ecbdd0d803a6))
* 启动图标改为主页同款纯色圆底，去掉外环 ([a7f20e8](https://github.com/xiaoyangtao55/LingBrowser/commit/a7f20e8cb2480f2697d5cfb14dee7926ea32e088))
* 启动图标改用主页同款描边羽毛，主题化图标跟随取色 ([9fd2c92](https://github.com/xiaoyangtao55/LingBrowser/commit/9fd2c92322a5282ec35836bf943ba054755c9e99))
* 图标全部换成官方 Material Symbols Outlined ([a376bb9](https://github.com/xiaoyangtao55/LingBrowser/commit/a376bb93b506d7c18790ab4218291e30105e480d))
* 地址栏显示网页标题 + 其它应用「用翎打开链接」真的会打开 ([fba6599](https://github.com/xiaoyangtao55/LingBrowser/commit/fba65996e7d32182f323fd3185b57d7099fc1921))
* 地址栏连接安全提示与证书错误「继续访问」 ([81f5030](https://github.com/xiaoyangtao55/LingBrowser/commit/81f503085dd5cae13e18ff234708d1901b0d83e9))
* 广告拦截（域名级，shouldInterceptRequest 资源级拦截） ([92eb9db](https://github.com/xiaoyangtao55/LingBrowser/commit/92eb9db74ba2b7353b0dd9df9086dee33cf074ee))
* 广告拦截自定义规则（二级菜单增删域名） ([b7e0385](https://github.com/xiaoyangtao55/LingBrowser/commit/b7e0385d8ae00a359ee5b20f7f7ef7d356f70084))
* 新增「备份与恢复」板块，支持书签导入导出 ([ef087e6](https://github.com/xiaoyangtao55/LingBrowser/commit/ef087e6f8af2875e39e44180d27c632f909e3ac3))
* 更多面板改图标网格、二级页面加过渡动画，并移除标签页「全屏」档 ([b57681d](https://github.com/xiaoyangtao55/LingBrowser/commit/b57681dee608cc7f998265af6428fbf1d2e38c57))
* 替换启动图标为 mipmap + Adaptive Icon + Monochrome 资源 ([bd29615](https://github.com/xiaoyangtao55/LingBrowser/commit/bd296150cbbf1e31faccf9eb1ce794a9dd78674f))
* 标签页 favicon 缩略图 + 长按拖拽排序 ([2c55b48](https://github.com/xiaoyangtao55/LingBrowser/commit/2c55b48d70820a267041546d72ab1c34cd0e6037))
* 标签页面板加入场动画，底色透明度 0.82 → 0.92 ([c6dbf00](https://github.com/xiaoyangtao55/LingBrowser/commit/c6dbf00adf7cad957d769b6f5e85579471d3af37))
* 标签页面板去掉 1/4 档，默认改为一半 ([71bba3c](https://github.com/xiaoyangtao55/LingBrowser/commit/71bba3c637e4d9d0805c3edb06ca5b0d7bede99c))
* 标签页面板改为半透明叠加层，可透出后方网页 ([25159d7](https://github.com/xiaoyangtao55/LingBrowser/commit/25159d7801eb865339b17a4dda6ab381c7ee6190))
* 网页 &lt;input type=file&gt; 走系统文件选择器 ([18c47e8](https://github.com/xiaoyangtao55/LingBrowser/commit/18c47e8fdcbb3fc3ee170601d1c2fbaf56433cb5))
* 网页加载进度条改为确定态 ([d46f659](https://github.com/xiaoyangtao55/LingBrowser/commit/d46f6592b894657b6504943a52f2961a3078d3aa))
* 补齐官方图标；收藏状态改用实心/空心区分 ([0192012](https://github.com/xiaoyangtao55/LingBrowser/commit/01920120ba0ab451a6f502e9ac8426edd6e10814))
* 设置拆分为分组子页，新增「关于」页 ([34c8075](https://github.com/xiaoyangtao55/LingBrowser/commit/34c8075155b911fa259cf1bb02e531001b797cdd))
* 资源嗅探（注入 JS 扫描媒体资源） ([4ee8fe7](https://github.com/xiaoyangtao55/LingBrowser/commit/4ee8fe7470e4db4a5be2750d13c17a2852679f9f))
* 阅读模式（正文提取 + 中文适配） ([9aaddbd](https://github.com/xiaoyangtao55/LingBrowser/commit/9aaddbd4a5a4e4e5a8ef8a34ad8a8b7c8821dc2f))


### Bug Fixes

* Add master branch to ReleasePlease workflow triggers ([aa0ba34](https://github.com/xiaoyangtao55/LingBrowser/commit/aa0ba349f2da80d1cd3e193577c670b381003502))
* apksigner 通配符展开成多参数导致签名校验失败 ([3327ee9](https://github.com/xiaoyangtao55/LingBrowser/commit/3327ee9d4f6aeae7bd09e45c9df49911ed9e5b9e))
* CI 产出未签名 APK —— 密钥库路径基准目录不一致 ([42f3a9f](https://github.com/xiaoyangtao55/LingBrowser/commit/42f3a9f2983927173bf2b0d66345659870bfd114))
* Delete .github/workflow directory ([c4a14d3](https://github.com/xiaoyangtao55/LingBrowser/commit/c4a14d3ec8c9844ff750d259ebd76cd7c327a401))
* shouldOverrideUrlLoading 未接管自定义协议导致"一直闪" ([4f4254c](https://github.com/xiaoyangtao55/LingBrowser/commit/4f4254ce03998e1640a13dbbbc93f95215389250))
* 主页/阅读视图的 5 处静默失效 + 2 处状态带偏 ([ce73ad6](https://github.com/xiaoyangtao55/LingBrowser/commit/ce73ad6f773e29cbb37c19c7a99216f0d4401afb))
* 主页不跟随夜间模式；默认搜索引擎改为必应 ([7b75969](https://github.com/xiaoyangtao55/LingBrowser/commit/7b75969f88e284f5462b7e9f0680f18f6c173a88))
* 主页两处体验问题 ([8235ba7](https://github.com/xiaoyangtao55/LingBrowser/commit/8235ba792459483a451bea063a96f722489359b4))
* 修 CI 编译错误（可空链传给非空形参），并把这类错误变成静态判据 ([907157d](https://github.com/xiaoyangtao55/LingBrowser/commit/907157d07a5e22190f5740dc5fa13669ec058021))
* 修复空字符串导致 Gradle 配置阶段崩溃 ([83d9b55](https://github.com/xiaoyangtao55/LingBrowser/commit/83d9b55cc3d55f76148846fe27c421570f0235b6))
* 修正 JUnit 断言的参数顺序，测试源文件无法编译 ([56f250f](https://github.com/xiaoyangtao55/LingBrowser/commit/56f250fde4bf127e4835d338b2892bdbe1aa1326))
* 切换主页背景模式不自动刷新 + 时钟在图片背景上可读性 ([c95c953](https://github.com/xiaoyangtao55/LingBrowser/commit/c95c953c98f3852fe65ea220602b6d8665df27e4))
* 地址栏提交后释放焦点；书签重命名/删除确认；书签网站图标 ([83f9312](https://github.com/xiaoyangtao55/LingBrowser/commit/83f9312fb2d483d2f5c373544c614fec4a12643a))
* 地址栏焦点归还给网页（真正的根因） ([5ec3e27](https://github.com/xiaoyangtao55/LingBrowser/commit/5ec3e2740e87394d8160f746b4c3e76bede80da4))
* 夜间模式三处体验改进 ([d44d600](https://github.com/xiaoyangtao55/LingBrowser/commit/d44d600757d6e48418f87b3afb7cef91fc7d0eec))
* 底部工具栏底色铺满手势条区域，适配底部小白条 ([4f96615](https://github.com/xiaoyangtao55/LingBrowser/commit/4f96615e91088e261a8e08298513599966cb7d5b))
* 无痕历史/Cookie 策略、系统明暗重算、阅读字号（进程级状态与文档重建） ([7665af9](https://github.com/xiaoyangtao55/LingBrowser/commit/7665af9dd49f56b4af79aa17662cb47aa9f803ea))
* 消除主页冷启动白帧，并修复切换背景后主页白屏 ([1e5be28](https://github.com/xiaoyangtao55/LingBrowser/commit/1e5be28c18fe44c087352c73edf07a3b8ece68ff))
* 点地址栏文字进编辑态时编辑的是标题（非编辑态不该挂输入框） ([1c78ca2](https://github.com/xiaoyangtao55/LingBrowser/commit/1c78ca2e87e171d7dfb0b2a05e435f3c5282bd53))
* 自定义协议导致 ERR_UNKNOWN_URL_SCHEME（知乎等 App 内嵌页） ([b0a7a08](https://github.com/xiaoyangtao55/LingBrowser/commit/b0a7a08675f77f35ac1f8eeac0f3cc38ae6b2de6))
* 资源嗅探两处体验改进 ([e92cb7e](https://github.com/xiaoyangtao55/LingBrowser/commit/e92cb7ea0ce4af8a6636c70db2e877d3fb48ee26))
* 阅读模式与既有功能撞车的四个静默失效点 + 主题跟随 ([ff0e621](https://github.com/xiaoyangtao55/LingBrowser/commit/ff0e621404d852a567c35ca2e8a5967cda56e953))
* 阅读模式正文提取时净化 on* 事件属性与 javascript:/vbscript: 伪协议 ([c48d701](https://github.com/xiaoyangtao55/LingBrowser/commit/c48d701f376452c3354efe30adf093ba8630fa4a))
