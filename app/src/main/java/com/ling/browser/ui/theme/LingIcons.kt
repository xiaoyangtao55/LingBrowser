package com.ling.browser.ui.theme

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Nightlight
import androidx.compose.material.icons.filled.AutoAwesomeMotion
import androidx.compose.material.icons.filled.Javascript
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Android
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 「翎」的图标集。
 *
 * 早期这里是 44 个手写坐标的 `ImageVector`（由官方 VectorDrawable 转成
 * Compose 代码，960 视口）。现在改为直接引用 **material-icons-extended**，
 * 保留同名属性只是为了调用点不用改（`LingIcons.Search` 之类的写法照旧）。
 *
 * ## 为什么改（实测数据）
 *
 * 早先担心的是"上万个图标编进 dex"，但那是 **debug 包**的数字：
 *
 * | 场景 | 体积 |
 * |---|---|
 * | release · 手写 44 个图标 | 1.97 MB |
 * | release · 图标库 + 引用 40 个 | 1.99 MB（**+17 KB**） |
 * | release · 图标库 + 0 引用 | 1.97 MB（+569 B） |
 * | debug · 手写 44 个图标 | 13.24 MB |
 * | debug · 图标库 + 仅 5 个 | **24.25 MB（+11 MB）** |
 *
 * 也就是说 **R8 在 release 里会把没引用到的图标全部裁掉**，44 个图标只花
 * 19 KB；代价全落在 debug 包上（debug 不裁剪，11000 个类全进 dex）。
 * 这个代价是可以接受的 —— 换来的是加图标不用再跑 Python 转换管线。
 *
 * ## 两套命名对照
 *
 * 调用点用的是本文件里的名字，右边是库里的官方名。差异主要在三处：
 *  - 语义命名（`Bookmark` = 实心收藏，对应 `Bookmark`；`BookmarkBorder`
 *    对应 `BookmarkBorder`；`BookmarkAdd` 对应 `BookmarkAdd`）；
 *  - 文件类型图标原本是 Material Symbols 的语义名（`file_pdf`），库里没有，
 *    改用最接近的（`PictureAsPdf` / `Description` / `TableChart` …）；
 *  - `AutoAwesome` 原名 `auto_awesome_motion`，库里叫 `AutoAwesomeMotion`。
 */
object LingIcons {

    // ------------------------------------------------------------ 基础导航
    /** 返回。*/
    val ArrowBack: ImageVector = Icons.Filled.ArrowBack
    /** 前进。*/
    val ArrowForward: ImageVector = Icons.Filled.ArrowForward
    /** 关闭。*/
    val Close: ImageVector = Icons.Filled.Close
    /** 暂停。*/
    val Pause: ImageVector = Icons.Filled.Pause
    /** 播放。*/
    val PlayArrow: ImageVector = Icons.Filled.PlayArrow
    /** 刷新。*/
    val Refresh: ImageVector = Icons.Filled.Refresh
    /** 主页。*/
    val Home: ImageVector = Icons.Filled.Home
    /** 标签页面板。*/
    val Layers: ImageVector = Icons.Filled.Layers
    /** 更多（竖排三点）。*/
    val MoreVert: ImageVector = Icons.Filled.MoreVert
    /** 添加。*/
    val Add: ImageVector = Icons.Filled.Add

    // ------------------------------------------------------------ 书签 / 历史
    /** 未收藏（空心书签）。*/
    val BookmarkBorder: ImageVector = Icons.Filled.BookmarkBorder
    /** 已收藏（**实心**书签）。区分靠填充，不靠加号。*/
    val Bookmark: ImageVector = Icons.Filled.Bookmark
    /** 添加书签（**动作**，空心 + 加号）。与上面两个不同：这是动词。*/
    val BookmarkAdd: ImageVector = Icons.Filled.BookmarkAdd
    /** 历史记录。*/
    val History: ImageVector = Icons.Filled.History
    /** 删除单条（垃圾桶轮廓）。*/
    val DeleteOutline: ImageVector = Icons.Filled.Delete
    /** 清空（扫帚）。*/
    val DeleteSweep: ImageVector = Icons.Filled.DeleteSweep

    // ------------------------------------------------------------ 浏览 / 设置
    /** 搜索。*/
    val Search: ImageVector = Icons.Filled.Search
    /** 下载。*/
    val Download: ImageVector = Icons.Filled.Download
    /** 文件夹。*/
    val Folder: ImageVector = Icons.Filled.Folder
    /** 打开的文件夹（导入）。*/
    val FolderOpen: ImageVector = Icons.Filled.FolderOpen
    /** 锁（安全连接）。*/
    val Lock: ImageVector = Icons.Filled.Lock
    /** 地球（普通连接 / 公共）。*/
    val Public: ImageVector = Icons.Filled.Public
    /** 夜间模式。*/
    val Nightlight: ImageVector = Icons.Filled.Nightlight
    /** 外观 / 动态取色。*/
    val AutoAwesome: ImageVector = Icons.Filled.AutoAwesomeMotion
    /** JavaScript 开关。*/
    val Javascript: ImageVector = Icons.Filled.Javascript
    /** 图片。*/
    val Image: ImageVector = Icons.Filled.Image
    /** 隐私。*/
    val PrivacyTip: ImageVector = Icons.Filled.PrivacyTip
    /** 电脑模式。*/
    val DesktopWindows: ImageVector = Icons.Filled.DesktopWindows
    /** 设置。*/
    val Settings: ImageVector = Icons.Filled.Settings
    /** 阅读模式 / 关于。*/
    val Article: ImageVector = Icons.Filled.Article
    /** 拦截（禁止）。*/
    val Block: ImageVector = Icons.Filled.Block
    /** 视频。*/
    val Movie: ImageVector = Icons.Filled.Movie
    /** 主题配色。*/
    val Palette: ImageVector = Icons.Filled.Palette

    // -------------------------------- 下载列表：文件类型图标
    //
    // 库里的命名是"材料语义"（PictureAsPdf、Description…），而原始设计用的是
    // Material Symbols 的 `file_* / image` 语义名，库里没有对应项，因此取最接近的。

    /** 图片（jpg/png/gif/webp/svg）。*/
    val FileImage: ImageVector = Icons.Filled.Image
    /** 视频。*/
    val FileVideo: ImageVector = Icons.Filled.VideoFile
    /** 音频。*/
    val FileAudio: ImageVector = Icons.Filled.AudioFile
    /** 压缩包。*/
    val FileArchive: ImageVector = Icons.Filled.FolderZip
    /** APK。*/
    val FileApk: ImageVector = Icons.Filled.Android
    /** PDF。*/
    val FilePdf: ImageVector = Icons.Filled.PictureAsPdf
    /** 文档。*/
    val FileDoc: ImageVector = Icons.Filled.Description
    /** 表格。*/
    val FileSheet: ImageVector = Icons.Filled.TableChart
    /** 幻灯片。*/
    val FileSlide: ImageVector = Icons.Filled.Slideshow
    /** 代码。*/
    val FileCode: ImageVector = Icons.Filled.Code
    /** 未知类型。*/
    val FileUnknown: ImageVector = Icons.Filled.InsertDriveFile
}
