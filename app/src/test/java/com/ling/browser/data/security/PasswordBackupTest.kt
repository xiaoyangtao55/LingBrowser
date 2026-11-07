package com.ling.browser.data.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** 加密导出的往返与防篡改测试（纯 JVM，无需 Android 环境）。 */
class PasswordBackupTest {

    private val passphrase = "correct horse battery".toCharArray()

    private fun entry(
        site: String = "example.com",
        username: String = "alice",
        password: String = "p@ss\tword\n第二行 🔐",
        title: String? = "示例站点",
    ) = PasswordBackupEntry(
        site = site,
        username = username,
        password = password,
        title = title,
        createdAt = 1_700_000_000_000,
        updatedAt = 1_700_000_100_000,
    )

    @Test
    fun `导出再导入内容一致`() {
        val original = listOf(
            entry(),
            entry(site="example.co.uk", username="bob", password="another", title=null),
        )
        val restored = PasswordBackup.import(PasswordBackup.export(original, passphrase), passphrase)
        assertEquals(original.sortedBy { it.site }, restored)
    }

    @Test
    fun `空列表也能往返`() {
        val restored = PasswordBackup.import(PasswordBackup.export(emptyList(), passphrase), passphrase)
        assertTrue(restored!!.isEmpty())
    }

    @Test
    fun `口令错误返回 null`() {
        val bytes = PasswordBackup.export(listOf(entry()), passphrase)
        assertNull(PasswordBackup.import(bytes, "wrong passphrase".toCharArray()))
    }

    @Test
    fun `密文被篡改返回 null`() {
        val bytes = PasswordBackup.export(listOf(entry()), passphrase)
        val text = bytes.toString(Charsets.UTF_8)
        // 改 data 段最后一个可见字符，保持 base64 结构大致合法
        val marker = "data:"
        val start = text.indexOf(marker) + marker.length
        val end = text.indexOf('\n', start)
        val data = text.substring(start, end)
        val tampered = data.dropLast(4) + (if (data.last() == 'A') "BBBB" else "AAAA")
        val broken = text.substring(0, start) + tampered + text.substring(end)
        assertNull(PasswordBackup.import(broken.toByteArray(Charsets.UTF_8), passphrase))
    }

    @Test
    fun `文件头不对返回 null`() {
        val text = PasswordBackup.export(listOf(entry()), passphrase)
            .toString(Charsets.UTF_8)
            .replaceFirst("LINGPASS1", "LINGPASS2")
        assertNull(PasswordBackup.import(text.toByteArray(Charsets.UTF_8), passphrase))
    }

    @Test
    fun `每次导出的盐与 IV 都不同`() {
        val a = PasswordBackup.export(listOf(entry()), passphrase).toString(Charsets.UTF_8)
        val b = PasswordBackup.export(listOf(entry()), passphrase).toString(Charsets.UTF_8)
        assertNotEquals(a, b)
        // 但两者都能正常解回同样的内容
        assertEquals(
            PasswordBackup.import(a.toByteArray(Charsets.UTF_8), passphrase),
            PasswordBackup.import(b.toByteArray(Charsets.UTF_8), passphrase),
        )
    }

    @Test
    fun `过短的口令拒绝导出`() {
        assertThrows(IllegalArgumentException::class.java) {
            PasswordBackup.export(listOf(entry()), "1234567".toCharArray())
        }
    }

    @Test
    fun `过短的口令无法导入`() {
        val bytes = PasswordBackup.export(listOf(entry()), passphrase)
        assertNull(PasswordBackup.import(bytes, "1234567".toCharArray()))
    }

    @Test
    fun `导出文件是可读的文本而不是二进制`() {
        val text = PasswordBackup.export(listOf(entry()), passphrase).toString(Charsets.UTF_8)
        assertTrue(text.startsWith("LINGPASS1\n"))
        assertTrue(text.contains("salt:"))
        assertTrue(text.contains("iv:"))
        assertTrue(text.contains("data:"))
        assertFalse(text.contains("p@ss"))
    }
}
