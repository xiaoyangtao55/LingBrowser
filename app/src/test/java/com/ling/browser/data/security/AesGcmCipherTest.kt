package com.ling.browser.data.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.GeneralSecurityException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * AES-GCM 加解密测试。
 *
 * 这里测的是 [AesGcmCipher]（密钥由调用方给），因此能在 JVM 上跑；
 * [KeystorePasswordCipher] 只是把密钥换成 Keystore 里的不可导出密钥，
 * 加解密逻辑完全一致。
 */
class AesGcmCipherTest {

    private fun newKey(): SecretKey =
        KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test
    fun `中文与 emoji 往返一致`() {
        val cipher = AesGcmCipher(newKey())
        val plaintext = "口令 P@ssw0rd 🔐 你好"
        val sealed = cipher.encrypt(plaintext)
        assertEquals(plaintext, cipher.decrypt(sealed))
    }

    @Test
    fun `空字符串也能往返`() {
        val cipher = AesGcmCipher(newKey())
        assertEquals("", cipher.decrypt(cipher.encrypt("")))
    }

    @Test
    fun `每次加密都使用不同的 IV`() {
        val cipher = AesGcmCipher(newKey())
        val a = cipher.encrypt("same-password")
        val b = cipher.encrypt("same-password")
        // GCM 下 IV 复用会直接毁掉机密性与完整性，绝不能省
        assertNotEquals(a.iv.contentHashCode(), b.iv.contentHashCode())
    }

    @Test
    fun `密文被篡改时解密抛异常而不是返回乱码`() {
        val cipher = AesGcmCipher(newKey())
        val sealed = cipher.encrypt("secret")
        sealed.ciphertext[0] = (sealed.ciphertext[0].toInt() xor 0x01).toByte()
        assertThrows(GeneralSecurityException::class.java) {
            cipher.decrypt(sealed)
        }
    }

    @Test
    fun `换一把密钥无法解密`() {
        val sealed = AesGcmCipher(newKey()).encrypt("secret")
        assertThrows(GeneralSecurityException::class.java) {
            AesGcmCipher(newKey()).decrypt(sealed)
        }
    }

    @Test
    fun `IV 被篡改时解密失败`() {
        val cipher = AesGcmCipher(newKey())
        val sealed = cipher.encrypt("secret")
        val badIv = sealed.iv.copyOf().also { it[0] = (it[0].toInt() xor 0x01).toByte() }
        assertThrows(GeneralSecurityException::class.java) {
            cipher.decrypt(SealedSecret(sealed.ciphertext, badIv))
        }
    }
}
