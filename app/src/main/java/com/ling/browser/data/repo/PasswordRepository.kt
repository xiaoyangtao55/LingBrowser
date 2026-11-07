package com.ling.browser.data.repo

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.ling.browser.data.db.LingDatabase
import com.ling.browser.data.db.PasswordEntry
import com.ling.browser.data.db.credentialValues
import com.ling.browser.data.db.toPasswordEntry
import com.ling.browser.data.security.PasswordBackup
import com.ling.browser.data.security.PasswordBackupEntry
import com.ling.browser.data.security.PasswordCipher
import com.ling.browser.data.security.SealedSecret
import com.ling.browser.data.security.SiteMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** 加密导出的结果：[skipped] 是解密失败、被跳过的条数。 */
data class PasswordExportResult(
    val bytes: ByteArray,
    val exported: Int,
    val skipped: Int,
)

/** 导入结果：新增 / 覆盖更新 / 跳过的条数。 */
data class PasswordImportResult(
    val imported: Int,
    val updated: Int,
    val skipped: Int,
)

/** 捕获到的登录信息与库里已有记录的关系。 */
enum class PasswordMatch {
    /** 该站点还没有这个用户名。 */
    NONE,

    /** 已有记录且密码一致 —— 不必再打扰用户。 */
    SAME,

    /** 已有记录但密码不同 —— 提示「更新密码？」。 */
    DIFFERENT,
}

/**
 * 密码库仓库。
 *
 * 与其它仓库最大的区别：**对外只暴露元信息**（[PasswordEntry] 不含密码）。
 * 明文只在 [decrypt] 这一条路径上按需解出，由调用方立刻用完即弃，
 * 不进入任何 Flow / UI 状态。
 *
 * 所有会碰密码的方法都在 IO 线程执行：Keystore 加解密在低端机上
 * 可能耗时几十毫秒，不能压在主线程。
 */
class PasswordRepository(
    private val db: LingDatabase,
    private val cipher: PasswordCipher,
) {

    private val _entries = MutableStateFlow<List<PasswordEntry>>(emptyList())
    val entries: StateFlow<List<PasswordEntry>> = _entries.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _entries.value = queryAll()
    }

    /** 某站点下的全部账号，按用户名排序。 */
    fun forSite(site: String): List<PasswordEntry> {
        val normalized = site.trim().lowercase()
        if (normalized.isEmpty()) return emptyList()
        return _entries.value.filter { it.site == normalized }
    }

    /**
     * 新增或更新一条密码。
     *
     * 同一 (site, username) 已存在时走更新：捕获到"改了密码"时不会
     * 留下两条新旧并存的记录。站点不是 HTTPS / 无法归属、密码为空时
     * 返回 null，调用方据此不弹"已保存"。
     */
    suspend fun save(
        site: String,
        username: String,
        password: String,
        title: String? = null,
    ): Long? = withContext(Dispatchers.IO) {
        val normalized = SiteMatcher.normalizeSite(site) ?: return@withContext null
        if (password.isEmpty()) return@withContext null
        val user = username.trim()

        val sealed = runCatching { cipher.encrypt(password) }.getOrNull()
            ?: return@withContext null
        val now = System.currentTimeMillis()

        runCatching {
            val existing = findId(normalized, user)
            if (existing != null) {
                val values = ContentValues().apply {
                    put("password_cipher", sealed.ciphertext)
                    put("password_iv", sealed.iv)
                    if (title != null) put("title", title)
                    put("updated_at", now)
                }
                db.writableDatabase.update(
                    "credentials", values, "id = ?", arrayOf(existing.toString()),
                )
                existing
            } else {
                db.writableDatabase.insertWithOnConflict(
                    "credentials",
                    null,
                    credentialValues(normalized, user, sealed.ciphertext, sealed.iv, title, now),
                    SQLiteDatabase.CONFLICT_ABORT,
                ).takeIf { it != -1L }
            }
        }.getOrNull().also { refresh() }
    }

    /**
     * 修改一条记录。[password] 为 null 表示只改用户名/标题，不动密码。
     *
     * 返回 false 表示记录不存在、用户名与同站点另一条冲突，
     * 或加解密失败 —— 此时不写库。
     */
    suspend fun update(
        id: Long,
        username: String,
        password: String? = null,
        title: String? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        val existing = entryById(id) ?: return@withContext false
        val user = username.trim()
        val duplicate = findId(existing.site, user)
        if (duplicate != null && duplicate != id) return@withContext false

        val sealed = password?.takeIf { it.isNotEmpty() }?.let {
            runCatching { cipher.encrypt(it) }.getOrNull() ?: return@withContext false
        }

        runCatching {
            val values = ContentValues().apply {
                put("username", user)
                if (sealed != null) {
                    put("password_cipher", sealed.ciphertext)
                    put("password_iv", sealed.iv)
                }
                if (title == null) putNull("title") else put("title", title)
                put("updated_at", System.currentTimeMillis())
            }
            db.writableDatabase.update(
                "credentials", values, "id = ?", arrayOf(id.toString()),
            ) > 0
        }.getOrDefault(false).also { refresh() }
    }

    suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
        runCatching {
            db.writableDatabase.delete("credentials", "id = ?", arrayOf(id.toString()))
        }
        refresh()
    }

    /**
     * 判断一次新捕获的登录信息与已有记录的关系。
     *
     * 只解密**同一站点同一用户名**这一条，不做全库比对；解密失败
     * （密钥失效/密文损坏）按 DIFFERENT 处理，让用户有机会用新密码覆盖。
     */
    suspend fun match(site: String, username: String, password: String): PasswordMatch =
        withContext(Dispatchers.IO) {
            val normalized = SiteMatcher.normalizeSite(site) ?: return@withContext PasswordMatch.NONE
            val user = username.trim()
            val sealed = runCatching {
                db.readableDatabase.rawQuery(
                    "SELECT password_cipher, password_iv FROM credentials " +
                        "WHERE site = ? AND username = ? LIMIT 1",
                    arrayOf(normalized, user),
                ).use { c ->
                    if (!c.moveToFirst()) {
                        null
                    } else {
                        SealedSecret(
                            ciphertext = c.getBlob(c.getColumnIndexOrThrow("password_cipher")),
                            iv = c.getBlob(c.getColumnIndexOrThrow("password_iv")),
                        )
                    }
                }
            }.getOrNull() ?: return@withContext PasswordMatch.NONE
            val existing = runCatching { cipher.decrypt(sealed) }.getOrNull()
            if (existing == password) PasswordMatch.SAME else PasswordMatch.DIFFERENT
        }

    // ------------------------------------------------------------ 加密导入导出

    /**
     * 用用户口令重新加密整个密码库。
     *
     * 不能直接导出 `ling.db`：库里的密文绑定本机 Keystore，换设备解不开。
     * 这里逐条解密后用 [PasswordBackup] 的口令派生密钥重新加密。
     * 个别记录解密失败时跳过并在结果里报告，不因一条坏数据拒绝导出全部。
     */
    suspend fun exportEncrypted(passphrase: CharArray): PasswordExportResult? =
        withContext(Dispatchers.IO) {
            if (passphrase.size < PasswordBackup.MIN_PASSPHRASE_LENGTH) return@withContext null
            val rows = runCatching {
                db.readableDatabase.rawQuery(
                    "SELECT * FROM credentials " +
                        "ORDER BY site COLLATE NOCASE ASC, username COLLATE NOCASE ASC",
                    null,
                ).use { c ->
                    buildList {
                        while (c.moveToNext()) {
                            val meta = c.toPasswordEntry()
                            add(
                                meta to SealedSecret(
                                    ciphertext = c.getBlob(
                                        c.getColumnIndexOrThrow("password_cipher"),
                                    ),
                                    iv = c.getBlob(c.getColumnIndexOrThrow("password_iv")),
                                ),
                            )
                        }
                    }
                }
            }.getOrNull() ?: return@withContext null

            var skipped = 0
            val entries = ArrayList<PasswordBackupEntry>(rows.size)
            for ((meta, sealed) in rows) {
                val plain = runCatching { cipher.decrypt(sealed) }.getOrNull()
                if (plain == null) {
                    skipped++
                    continue
                }
                entries += PasswordBackupEntry(
                    site = meta.site,
                    username = meta.username,
                    password = plain,
                    title = meta.title,
                    createdAt = meta.createdAt,
                    updatedAt = meta.updatedAt,
                )
            }

            val bytes = runCatching { PasswordBackup.export(entries, passphrase) }.getOrNull()
                ?: return@withContext null
            PasswordExportResult(bytes = bytes, exported = entries.size, skipped = skipped)
        }

    /**
     * 从加密备份恢复。
     *
     * 返回 null 表示口令错误或文件损坏；返回结果里的 [PasswordImportResult.skipped]
     * 是站点无效（非 HTTPS/无法归属）或密码为空而被丢掉的条数。
     * 站点在写库前重新归一，不信任文件里的字符串。
     */
    suspend fun importEncrypted(
        bytes: ByteArray,
        passphrase: CharArray,
    ): PasswordImportResult? = withContext(Dispatchers.IO) {
        if (passphrase.size < PasswordBackup.MIN_PASSPHRASE_LENGTH) return@withContext null
        val entries = PasswordBackup.import(bytes, passphrase) ?: return@withContext null

        var imported = 0
        var updated = 0
        var skipped = 0
        for (entry in entries) {
            val site = SiteMatcher.normalizeSite(entry.site)
            if (site == null || entry.password.isEmpty()) {
                skipped++
                continue
            }
            val existed = findId(site, entry.username.trim()) != null
            val id = runCatching {
                save(site, entry.username, entry.password, entry.title)
            }.getOrNull()
            when {
                id == null -> skipped++
                existed -> updated++
                else -> imported++
            }
        }
        refresh()
        PasswordImportResult(imported = imported, updated = updated, skipped = skipped)
    }

    /** 按需解出明文；记录不存在或密文损坏时返回 null。 */
    suspend fun decrypt(id: Long): String? = withContext(Dispatchers.IO) {
        val sealed = runCatching {
            db.readableDatabase.rawQuery(
                "SELECT password_cipher, password_iv FROM credentials WHERE id = ? LIMIT 1",
                arrayOf(id.toString()),
            ).use { c ->
                if (!c.moveToFirst()) {
                    null
                } else {
                    SealedSecret(
                        ciphertext = c.getBlob(c.getColumnIndexOrThrow("password_cipher")),
                        iv = c.getBlob(c.getColumnIndexOrThrow("password_iv")),
                    )
                }
            }
        }.getOrNull() ?: return@withContext null
        runCatching { cipher.decrypt(sealed) }.getOrNull()
    }

    /** 记录一次填充，仅用于列表排序/展示，不参与任何安全判断。 */
    suspend fun markUsed(id: Long) = withContext(Dispatchers.IO) {
        runCatching {
            val values = ContentValues().apply { put("last_used_at", System.currentTimeMillis()) }
            db.writableDatabase.update(
                "credentials", values, "id = ?", arrayOf(id.toString()),
            )
        }
        refresh()
    }

    // ------------------------------------------------------------ 内部查询

    private fun queryAll(): List<PasswordEntry> = runCatching {
        db.readableDatabase.rawQuery(
            "SELECT * FROM credentials " +
                "ORDER BY site COLLATE NOCASE ASC, username COLLATE NOCASE ASC",
            null,
        ).use { c -> buildList { while (c.moveToNext()) add(c.toPasswordEntry()) } }
    }.getOrDefault(emptyList())

    private fun entryById(id: Long): PasswordEntry? = runCatching {
        db.readableDatabase.rawQuery(
            "SELECT * FROM credentials WHERE id = ? LIMIT 1",
            arrayOf(id.toString()),
        ).use { c -> if (c.moveToFirst()) c.toPasswordEntry() else null }
    }.getOrNull()

    private fun findId(site: String, username: String): Long? = runCatching {
        db.readableDatabase.rawQuery(
            "SELECT id FROM credentials WHERE site = ? AND username = ? LIMIT 1",
            arrayOf(site, username),
        ).use { c -> if (c.moveToFirst()) c.getLong(0) else null }
    }.getOrNull()
}
