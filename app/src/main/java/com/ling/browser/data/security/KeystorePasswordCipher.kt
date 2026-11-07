package com.ling.browser.data.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * 使用 Android Keystore 保存主密钥的 [PasswordCipher]。
 *
 * 密钥在 Keystore 内部生成，**不可导出**（应用进程拿不到原始字节），
 * 只能用来做加解密。即使有人把 `ling.db` 拷走，没有这台设备上的
 * Keystore 密钥也解不出密码。
 *
 * 按产品取舍，本版密钥**不绑定**系统用户认证（`setUserAuthenticationRequired`
 * 保持关闭）：解锁门禁做在 App 层（见密码库解锁页），这样 API 23–36
 * 行为一致，也不会因为用户改锁屏密码导致密钥永久失效、密码全部解不开。
 *
 * 首次加解密时才生成密钥（[delegate] 懒加载），不在应用启动时做 Keystore 操作。
 */
class KeystorePasswordCipher : PasswordCipher {

    private val delegate: AesGcmCipher by lazy {
        AesGcmCipher(loadOrCreateKey())
    }

    override fun encrypt(plaintext: String): SealedSecret = delegate.encrypt(plaintext)

    override fun decrypt(sealed: SealedSecret): String = delegate.decrypt(sealed)

    private fun loadOrCreateKey(): SecretKey = synchronized(LOCK) {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null)
        if (existing is SecretKey) return@synchronized existing

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                .build()
        )
        generator.generateKey()
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "ling.password.v1"
        const val KEY_SIZE_BITS = 256

        /** 生成密钥要读 Keystore，串行化避免并发首用时的重复生成。 */
        val LOCK = Any()
    }
}
