package com.ling.browser.ui

import com.ling.browser.data.repo.BookmarkRepository
import com.ling.browser.util.FaviconFetcher
import com.ling.browser.util.UrlUtils
import com.ling.browser.web.InternalPage
import com.ling.browser.web.TabState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 书签域对宿主（[BrowserViewModel]）的最小依赖。
 *
 * 收藏要看当前标签与阅读视图的原文地址，结果要回写 UI 的收藏态、
 * 错误要经一次性提示回报 —— 这些仍由宿主持有。
 */
internal interface BookmarkHost {
    val bookmarks: BookmarkRepository
    val scope: CoroutineScope

    fun activeTab(): TabState?

    /** 阅读视图对应的原文地址；不在阅读视图时为 null。 */
    fun readerOriginalUrl(): String?

    fun showMessage(text: String)

    fun setBookmarked(value: Boolean)
}

/**
 * 书签域：收藏/取消、图标补齐、重命名、移动与文件夹管理。
 *
 * 从 [BrowserViewModel] 里拆出的功能簇。书签的持久化与派生文件夹仍在
 * [BookmarkRepository]；这里只负责"当前页面能不能收藏"与用户操作的提示。
 * 导入/导出留在宿主里，因为它们与历史记录共用读写文件的辅助函数。
 */
internal class BookmarksDelegate(private val host: BookmarkHost) {

    /**
     * 当前标签「可收藏」的地址与标题。
     *
     * 阅读视图必须换成**原文地址**：它的逻辑地址是合成的 `ling://reader`，
     * 收藏下来就是一个点不开的书签。
     * 主页则没有收藏价值（返回 null）。
     */
    private fun bookmarkTarget(): BookmarkTarget? {
        val tab = host.activeTab() ?: return null
        val url = InternalPage.externalUrl(tab.url, host.readerOriginalUrl()) ?: return null
        return BookmarkTarget(url, tab.displayTitle)
    }

    private data class BookmarkTarget(val url: String, val title: String)

    fun toggleBookmark() {
        val tab = host.activeTab() ?: return
        val target = bookmarkTarget()
        if (target == null) {
            // 两种"没有可收藏地址"要分开说，否则用户不知道为什么点了没反应
            host.showMessage(
                if (UrlUtils.isHome(tab.url)) {
                    "主页无需收藏"
                } else {
                    "找不到这篇文章的原网页地址，无法收藏"
                },
            )
            return
        }
        host.scope.launch {
            val now = host.bookmarks.toggle(target.title, target.url)
            host.setBookmarked(now)
            host.showMessage(if (now) "已加入书签" else "已取消收藏")
            if (now) {
                // 页面刚打开过，WebView 已经把图标交给我们了（缩略图用的
                // 就是它），直接复用省一次网络请求；没有再走后台抓取。
                val bytes = tab.favicon
                    ?.takeIf { !it.isRecycled }
                    ?.let { encodeIcon(it) }
                if (bytes != null) {
                    host.bookmarks.setFavicon(target.url, bytes)
                } else {
                    fetchMissingFavicons()
                }
            }
        }
    }

    /**
     * 给还没有图标的书签补图标。
     *
     * 分批（每次最多 8 个）且逐个串行：图标是锦上添花的东西，
     * 同时发起几十个连接既没必要也容易被站点限流。
     * 每次成功后仓库会 refresh，下一次调用自然取到下一批，
     * 所以这里只跑一批就够了 —— 不必写循环。
     */
    fun fetchMissingFavicons() {
        val pending = host.bookmarks.urlsMissingFavicon(limit = 8)
        if (pending.isEmpty()) return
        host.scope.launch {
            pending.forEach { url ->
                val bytes = FaviconFetcher.fetch(url)
                if (bytes != null) {
                    // setFavicon 内部会 refresh，书签流随之更新
                    host.bookmarks.setFavicon(url, bytes)
                } else {
                    // 取不到就写一个空数组占位，表示"试过了"。
                    // 不这样做的话每次进书签页都会重新去请求同一批失败站点，
                    // 白白耗流量。
                    host.bookmarks.setFavicon(url, ByteArray(0))
                }
            }
        }
    }

    private fun encodeIcon(bitmap: android.graphics.Bitmap): ByteArray? = runCatching {
        val out = java.io.ByteArrayOutputStream()
        @Suppress("DEPRECATION")
        bitmap.compress(android.graphics.Bitmap.CompressFormat.WEBP, 80, out)
        out.toByteArray().takeIf { it.isNotEmpty() }
    }.getOrNull()

    fun refreshBookmarkState() {
        // 用 bookmarkTarget()：阅读视图要按**原文地址**查收藏态，否则
        // 明明收藏过这篇文章，菜单里还显示"添加书签"。
        // 主页没有收藏态可言，直接置 false（不能提前 return —— 那会把上一个
        // 页面的状态留在界面上）。
        val url = bookmarkTarget()?.url
        host.scope.launch {
            host.setBookmarked(url != null && host.bookmarks.isBookmarked(url))
        }
    }

    fun deleteBookmark(id: Long) {
        host.scope.launch { host.bookmarks.delete(id) }
    }

    /**
     * 重命名书签（只改标题，不动 URL 与所属文件夹）。
     *
     * 名字留空时**保留原样**而不是存一个空标题：列表里一条没有文字的书签
     * 完全无法辨认，比不改更糟。
     */
    fun renameBookmark(id: Long, title: String) {
        val clean = title.trim()
        if (clean.isEmpty()) {
            host.showMessage("名称不能为空")
            return
        }
        val current = host.bookmarks.bookmarks.value.firstOrNull { it.id == id } ?: return
        host.scope.launch {
            host.bookmarks.rename(id, clean, current.folder)
            host.showMessage("已重命名")
        }
    }

    /** 把书签移动到某文件夹；[folder] 为 null 表示移出到"未分类"。 */
    fun moveBookmarkToFolder(id: Long, folder: String?) {
        host.scope.launch {
            host.bookmarks.move(id, folder?.trim()?.takeIf { it.isNotEmpty() })
            host.showMessage(if (folder.isNullOrBlank()) "已移出文件夹" else "已移到「$folder」")
        }
    }

    /** 重命名文件夹。名字重复或为空时给出提示并放弃。 */
    fun renameBookmarkFolder(from: String, to: String) {
        val clean = to.trim()
        when {
            clean.isEmpty() -> host.showMessage("文件夹名不能为空")
            // 只有改成**别的**已存在名字才算冲突；改回自己（含大小写变化）应放行
            !clean.equals(from, ignoreCase = false) && host.bookmarks.folderExists(clean) ->
                host.showMessage("已存在同名文件夹")
            else -> host.scope.launch {
                host.bookmarks.renameFolder(from, clean)
                host.showMessage("已重命名为「$clean」")
            }
        }
    }

    /** 删除文件夹，其中的书签退回"未分类"（不连带删除）。 */
    fun deleteBookmarkFolder(folder: String) {
        host.scope.launch {
            host.bookmarks.deleteFolder(folder)
            host.showMessage("已删除文件夹，其中的书签移到了未分类")
        }
    }

    fun renameBookmark(id: Long, title: String, folder: String?) {
        host.scope.launch { host.bookmarks.rename(id, title, folder) }
    }
}
