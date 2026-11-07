package com.ling.browser.data.repo

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.ling.browser.data.db.LingDatabase
import com.ling.browser.data.db.PasswordEntry
import com.ling.browser.data.db.credentialValues
import com.ling.browser.data.db.toPasswordEntry
import com.ling.browser.data.security.PasswordCipher
import com.ling.browser.data.security.SealedSecret
import com.ling.browser.data.security.SiteMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

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
