package com.ling.browser.data.db

/** 书签。 */
data class Bookmark(
    val id: Long = 0,
    val title: String,
    val url: String,
    /** 归属文件夹，null 表示根目录。极简版只支持一层。 */
    val folder: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

/** 浏览历史。 */
data class HistoryEntry(
    val id: Long = 0,
    val title: String,
    val url: String,
    val visitedAt: Long = System.currentTimeMillis(),
    /** 同一 URL 的累计访问次数。 */
    val visitCount: Int = 1,
)

/** 会话恢复用的标签页快照。 */
data class TabSnapshot(
    val id: String,
    val url: String,
    val title: String,
    val position: Int,
    val isIncognito: Boolean = false,
)
