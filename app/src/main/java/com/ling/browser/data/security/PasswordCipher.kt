package com.ling.browser.data.security

import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 一段 AES-GCM 密文及其随机 IV。
 *
 * GCM 的 IV 绝不能重复，因此每次加密都由 [Cipher] 生成新 IV 并一起保存。
 * IV 不是秘密，与密文同存；真正的秘密只有 Keystore 里的密钥。
 */
data class SealedSecret(
    val ciphertext: ByteArray,
    val iv: ByteArray,
) {
    // data class 含数组时必须手写 equals/hashCode，否则比较的是数组引用。
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SealedSecret) return false
        return ciphertext.contentEquals(other.ciphertext) && iv.contentEquals(other.iv)
    }

    override fun hashCode(): Int =
        31 * ciphertext.contentHashCode() + iv.contentHashCode()

    override fun toString(): String =
        "SealedSecret(ciphertext=${ciphertext.size}B, iv=${iv.size}B)"
}

/**
 * 密码加解密接口。
 *
 * 抽成接口有两个目的：
 *  1. JVM 单测可以直接用 [AesGcmCipher] + 普通 [SecretKey]，不需要 Android 环境；
 *  2. 仓库层只依赖这个接口，不关心密钥到底来自 Keystore 还是别处。
 *
 * 约定：实现只处理 UTF-8 字符串，**任何实现都不得把明文写日志/落盘**。
 */
interface PasswordCipher {
    fun encrypt(plaintext: String): SealedSecret

    /** 密文被篡改、密钥不匹配时抛异常（AEAD 校验失败），不会返回乱码。 */
    fun decrypt(sealed: SealedSecret): String
}

/**
 * 纯 JVM 可运行的 AES-GCM 实现。
 *
 * 密钥由调用方提供：[KeystorePasswordCipher] 传入 Android Keystore 里的
 * 不可导出密钥，单测则传入内存里随机生成的密钥。
 */
class AesGcmCipher(private val key: SecretKey) : PasswordCipher {

    override fun encrypt(plaintext: String): SealedSecret {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        // ENCRYPT 模式下由实现生成随机 IV，必须一起保存才能解密。
        return SealedSecret(ciphertext, cipher.iv)
    }

    override fun decrypt(sealed: SealedSecret): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, sealed.iv))
        return String(cipher.doFinal(sealed.ciphertext), Charsets.UTF_8)
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
    }
}
