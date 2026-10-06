package com.ling.browser.data.db

/** 书签。 */
data class Bookmark(
    val id: Long = 0,
    val title: String,
    val url: String,
    /** 归属文件夹，null 表示根目录。极简版只支持一层。 */
    val folder: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    /**
     * 网站图标的原始字节（PNG/JPEG/WebP）。
     *
     * 刻意存 `ByteArray` 而不是 `Bitmap`：`Bitmap` 的像素在非托管内存里，
     * 需要手动 `recycle`，而书签列表随时可能重建、同一个对象可能被多处
     * 引用 —— 一旦某处回收掉，别处就会抛
     * `Canvas: trying to use a recycled bitmap`。存字节完全绕开生命周期
     * 问题，代价只是显示时解码一次（列表里数量少，且有按 id 的缓存）。
     */
    val favicon: ByteArray? = null,
) {
    // data class 含数组时必须手写 equals/hashCode：自动生成的版本比较的是
    // 数组**引用**，每次从库里读出来都是新数组，会被误判成"内容变了"
    // 而触发无谓的重组。
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Bookmark) return false
        return id == other.id &&
            title == other.title &&
            url == other.url &&
            folder == other.folder &&
            createdAt == other.createdAt &&
            (favicon?.contentEquals(other.favicon) ?: (other.favicon == null))
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + title.hashCode()
        result = 31 * result + url.hashCode()
        result = 31 * result + (folder?.hashCode() ?: 0)
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + (favicon?.contentHashCode() ?: 0)
        return result
    }
}

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
) {
    /**
     * 这个快照是否值得写进磁盘。
     *
     * 无痕标签**绝不落盘**：无痕模式的意义就是"关掉不留痕"，
     * 把 URL 存进 SQLite 会让用户下次启动看到上次无痕浏览的网站。
     *
     * 另外跳过空白地址：主页有固定的逻辑地址（ling://home）应当保存，
     * 但空字符串是脏数据，恢复它只会得到一个打不开的标签。
     */
    val isPersistable: Boolean
        get() = !isIncognito && url.isNotBlank()
}

/**
 * 一条下载记录。
 *
 * 与系统 DownloadManager 的关系：真实下载由系统服务负责（断点续传、
 * 通知栏进度、网络切换重试都由它兜底），本表只保存「翎」需要展示的
 * 元信息 —— 文件名、大小、状态、本地路径。两边用 [downloadId]（系统
 * 返回的 ID）关联，状态变化时再回查系统补齐进度。
 *
 * 这样做的代价是需要两边同步，好处是不必自己实现下载线程与通知。
 */
data class DownloadEntry(
    val id: Long = 0,
    /** 系统 DownloadManager 的 ID，用于查询进度/取消。 */
    val downloadId: Long,
    val fileName: String,
    val url: String,
    /** 字节数；系统尚未给出时是 0 或 -1。 */
    val totalBytes: Long = 0,
    val downloadedBytes: Long = 0,
    val mimeType: String? = null,
    val status: DownloadStatus = DownloadStatus.PENDING,
    /** 本地文件绝对路径，完成后才有意义。 */
    val localPath: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
) {
    /** 进度 0f..1f；总大小未知时返回 0。 */
    val progress: Float
        get() = if (totalBytes <= 0L) 0f
        else (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)

    /** 是否还在进行中（可以取消）。 */
    val isRunning: Boolean
        get() = status == DownloadStatus.PENDING || status == DownloadStatus.RUNNING
}

/**
 * 下载状态。
 *
 * 刻意不复用系统 `DownloadManager.STATUS_*` 常量：那些是 Int，
 * 直接存进数据库后一旦系统改了取值就全乱；用枚举名持久化更稳。
 */
enum class DownloadStatus(val label: String) {
    PENDING("等待中"),
    RUNNING("下载中"),
    PAUSED("已暂停"),
    SUCCESS("已完成"),
    FAILED("失败"),
}
