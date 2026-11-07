package com.ling.browser.data.security

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** 导出/导入时的一条明文记录（只在内存里短暂存在）。 */
data class PasswordBackupEntry(
    val site: String,
    val username: String,
    val password: String,
    val title: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * 密码库的加密导出/导入。
 *
 * 为什么不直接导出 `ling.db`：数据库里的密文由**本机 Keystore 密钥**加密，
 * 换一台设备根本解不开，导了也没用。这里改为用用户口令派生密钥重新加密，
 * 文件可以跨设备恢复，同时保证离开设备后仍是密文。
 *
 * 文件格式（纯 ASCII 文本，扩展名 `.lingpass`）：
 * ```
 * LINGPASS1
 * salt:<base64>
 * iv:<base64>
 * data:<base64>
 * ```
 *  - 密钥：PBKDF2-HMAC-SHA256，210000 轮，16 字节随机盐；
 *  - 数据：AES-256-GCM，随机 IV，`LINGPASS1` 作为 AAD 绑定版本；
 *  - 明文：每行一条记录，字段用 `\t` 分隔、逐字段 Base64（避免密码里的
 *    换行/制表符破坏格式），最后两列是创建/更新时间戳。
 *
 * 口令错误或文件被篡改时 GCM 校验失败，[import] 返回 null —— 不区分
 * "口令错"与"文件坏"，避免给暴力破解者额外信息。
 */
object PasswordBackup {

    const val MIN_PASSPHRASE_LENGTH = 8

    private const val MAGIC = "LINGPASS1"
    private const val SALT_BYTES = 16
    private const val TAG_BITS = 128
    private const val ITERATIONS = 210_000
    private const val KEY_BITS = 256
    private const val FIELD_SEPARATOR = '\t'

    @OptIn(ExperimentalEncodingApi::class)
    fun export(entries: List<PasswordBackupEntry>, passphrase: CharArray): ByteArray {
        require(passphrase.size >= MIN_PASSPHRASE_LENGTH) { "passphrase too short" }

        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val key = deriveKey(passphrase, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(MAGIC.toByteArray(Charsets.UTF_8))

        val plaintext = entries
            .sortedWith(compareBy({ it.site }, { it.username }))
            .joinToString("\n") { encodeEntry(it) }
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        return buildString {
            appendLine(MAGIC)
            appendLine("salt:${Base64.encode(salt)}")
            appendLine("iv:${Base64.encode(cipher.iv)}")
            appendLine("data:${Base64.encode(ciphertext)}")
        }.toByteArray(Charsets.UTF_8)
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun import(bytes: ByteArray, passphrase: CharArray): List<PasswordBackupEntry>? = runCatching {
        require(passphrase.size >= MIN_PASSPHRASE_LENGTH)

        val lines = bytes.toString(Charsets.UTF_8).lines()
        if (lines.size < 4 || lines[0] != MAGIC) return@runCatching null
        val salt = Base64.decode(lines[1].removePrefix("salt:"))
        val iv = Base64.decode(lines[2].removePrefix("iv:"))
        val data = Base64.decode(lines[3].removePrefix("data:"))

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            deriveKey(passphrase, salt),
            GCMParameterSpec(TAG_BITS, iv),
        )
        cipher.updateAAD(MAGIC.toByteArray(Charsets.UTF_8))
        val plaintext = cipher.doFinal(data).toString(Charsets.UTF_8)
        if (plaintext.isEmpty()) return@runCatching emptyList()

        plaintext.lines().map { decodeEntry(it) ?: return@runCatching null }
    }.getOrNull()

    private fun deriveKey(passphrase: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, ITERATIONS, KEY_BITS)
        return try {
            val encoded = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec)
                .encoded
            SecretKeySpec(encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun encodeEntry(entry: PasswordBackupEntry): String = listOf(
        Base64.encode(entry.site.toByteArray(Charsets.UTF_8)),
        Base64.encode(entry.username.toByteArray(Charsets.UTF_8)),
        Base64.encode(entry.password.toByteArray(Charsets.UTF_8)),
        Base64.encode(entry.title.orEmpty().toByteArray(Charsets.UTF_8)),
        entry.createdAt.toString(),
        entry.updatedAt.toString(),
    ).joinToString(FIELD_SEPARATOR.toString())

    @OptIn(ExperimentalEncodingApi::class)
    private fun decodeEntry(line: String): PasswordBackupEntry? {
        if (line.isEmpty()) return null
        val parts = line.split(FIELD_SEPARATOR)
        if (parts.size != 6) return null
        return PasswordBackupEntry(
            site = Base64.decode(parts[0]).toString(Charsets.UTF_8),
            username = Base64.decode(parts[1]).toString(Charsets.UTF_8),
            password = Base64.decode(parts[2]).toString(Charsets.UTF_8),
            title = Base64.decode(parts[3]).toString(Charsets.UTF_8).ifEmpty { null },
            createdAt = parts[4].toLongOrNull() ?: return null,
            updatedAt = parts[5].toLongOrNull() ?: return null,
        )
    }
}
