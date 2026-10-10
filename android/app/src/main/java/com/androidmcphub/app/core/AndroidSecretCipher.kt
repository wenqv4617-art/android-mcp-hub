package com.androidmcphub.app.core

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * [SecretCipher] 的 Android 实现：密钥生在 Android Keystore 里，**不可导出**，
 * 加密用 AES-256-GCM（带认证标签，密文被改动就无法通过校验）。
 *
 * 为什么不用 `EncryptedSharedPreferences`（androidx.security）：
 *  - 那个库在 1.0.0 之后长期停留在 alpha，官方明确不保证 API 稳定；
 *  - 它自己也是"AES-GCM + Keystore 里的主密钥"，用二十几行就能写清楚，
 *    而且写清楚之后每一条失败路径都能被单测点名（见 `DeviceTokenStore.kt` 的分层）。
 * 这里刻意少一个依赖。
 *
 * 文件头声明：本文件的代码路径**没有**在任何 Android 设备或模拟器上执行过 ——
 * JVM 单测跑不到 Keystore。见 TEST_REPORT 的 NOT RUN 清单（R4）。
 */
class AndroidSecretCipher(
    private val alias: String = DEFAULT_ALIAS,
    private val keyStoreProvider: () -> KeyStore = { defaultKeyStore() },
) : SecretCipher {

    private val keyStore: KeyStore by lazy { keyStoreProvider() }

    override fun encrypt(plain: ByteArray): EncryptedSecret {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // ENCRYPT_MODE 下由 Keystore 自己生成随机 IV，我们只把它带出去存起来。
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plain)
        return EncryptedSecret(SecretCodec.CURRENT_VERSION, iv, ciphertext)
    }

    override fun decrypt(secret: EncryptedSecret): ByteArray {
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, secret.iv))
            cipher.doFinal(secret.ciphertext)
        } catch (error: GeneralSecurityException) {
            // Keystore 里的密钥可能已经被系统作废：用户改了锁屏密码、恢复出厂设置、
            // 或某些厂商的"数据迁移"把密文搬到了新设备上（密钥不会跟着走）。
            // 这种情况下密钥**再也用不了**，唯一正确的处置是删掉它，让下一次保存
            // 生成一把新的。密文本身已经解不开了，所以删密钥不会损失任何还能用的数据。
            forgetKey()
            throw SecretDecryptionException(error.javaClass.simpleName)
        }
    }

    /**
     * 删除 Keystore 里的这把密钥。
     *
     * 只在这里和 [decrypt] 的失败路径上被调用 —— 注意：**不会**被"用户清空令牌"调用。
     * 令牌被清空不代表密钥有问题，留着它下次还能用，重建密钥是不必要的成本。
     */
    fun forgetKey() {
        runCatching { keyStore.deleteEntry(alias) }
    }

    private fun secretKey(): SecretKey {
        val existing = keyStore.getKey(alias, null)
        if (existing is SecretKey) return existing
        if (existing != null) {
            // 键存在但不是 SecretKey：别名被别的用途占用了。不含任何凭据内容。
            throw IllegalStateException("keystore entry is not a secret key")
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                // 不要求用户认证：连接是用户点一下就要建立的，不能每次都弹指纹。
                // 代价是"能解锁手机的人就能解密"，这个前提写在 SECURITY.md 里。
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {

        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"

        /** GCM 认证标签长度（bit）。 */
        const val TAG_BITS = 128
        const val KEY_BITS = 256

        /** 专用别名，不与其他用途共享。 */
        const val DEFAULT_ALIAS = "android-mcp-hub.device-token.v1"

        private fun defaultKeyStore(): KeyStore =
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }
}

/**
 * [SecretStorage] 的 Android 实现：密文文本存在 SharedPreferences 的一个键里。
 *
 * 用 `commit()` 而不是 `apply()`：`apply()` 只保证内存里的值立刻可见，磁盘写入是异步的，
 * 进程被杀就可能丢。凭据这种东西宁可让调用方多等几毫秒，也要确认它真的落盘了。
 */
class PrefsSecretStorage(
    private val prefs: SharedPreferences,
    private val key: String = KEY_TOKEN_BLOB,
) : SecretStorage {

    override fun read(): String? = prefs.getString(key, null)

    override fun write(encoded: String) {
        prefs.edit().putString(key, encoded).commit()
    }

    override fun clear() {
        prefs.edit().remove(key).commit()
    }

    companion object {

        /** 只放密文。旧的明文键在 `HubPrefs` 里迁移后会被删除。 */
        const val KEY_TOKEN_BLOB = "device_token_v1"
    }
}
