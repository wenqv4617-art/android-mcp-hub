package com.androidmcphub.app.core

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Base64

/**
 * 设备令牌的落盘保护（Gate 0.1 审计 R4）。
 *
 * 威胁模型很朴素：SharedPreferences 的 XML 在已 root 的设备上、以及某些厂商的
 * "应用数据迁移"通道里都是可读的。所以令牌**不以明文落盘** —— 用 Android Keystore 里
 * 不可导出的 AES 密钥做 AES-GCM 加密，只把密文写进 prefs。
 *
 * 分层：
 *  - [SecretCipher] / [SecretStorage] 是两个窄接口，Android 实现见 `AndroidSecretCipher.kt`；
 *  - [SecureTokenStore] 只做编排（读 → 解码 → 解密 → UTF-8），因此能在 JVM 单测里
 *    用假实现把**每一条失败路径**都走一遍；
 *  - 失败**不吞**：一律返回 [TokenLoadResult.Unreadable]，由调用方决定怎么告诉用户。
 *    "解密失败就当没设过、拿空令牌继续连"是被明确禁止的行为。
 *
 * 已知边界（诚实声明）：解出来的明文在 JVM 里是 `String`，而 `String` 无法被擦除，
 * 只能把中间态的 `ByteArray` 清零。真正要防的是"令牌躺在磁盘上"，这一点做到了。
 */

/** 一次加密的产物：版本号 + 随机 IV + 密文（GCM 的认证标签在密文尾部）。 */
data class EncryptedSecret(
    val version: Int,
    val iv: ByteArray,
    val ciphertext: ByteArray,
)

/** 解密失败。**不携带**原始异常的任何文本，只带一个固定分类。 */
class SecretDecryptionException(val classifier: String) :
    RuntimeException("secret decryption failed: $classifier")

/** 加解密。实现必须不可导出密钥。 */
interface SecretCipher {
    fun encrypt(plain: ByteArray): EncryptedSecret

    /** 失败时**必须**抛 [SecretDecryptionException]。 */
    fun decrypt(secret: EncryptedSecret): ByteArray
}

/** 密文的落盘位置（Android 实现是 SharedPreferences 的一个键）。 */
interface SecretStorage {
    fun read(): String?

    fun write(encoded: String)

    fun clear()
}

/**
 * 读令牌的结果。
 *
 * 三态而不是"返回 String/空串"是这整个文件存在的理由：把
 * [Unreadable] 压成 [Absent]（或空串）会让应用拿着一个空令牌去连服务器，
 * 用户只会看到"认证失败"，永远不知道是自己那串令牌根本没被读出来。
 */
sealed interface TokenLoadResult {

    /** 读到了可用的令牌。 */
    data class Present(val token: String) : TokenLoadResult

    /** 从没存过，或用户清空了。这不是错误。 */
    data object Absent : TokenLoadResult

    /**
     * 存过东西，但读不出来。
     *
     * [reason] 一定取自 [TokenFailure]，是固定字符串，**不含**密文、密钥别名或异常原文。
     */
    data class Unreadable(val reason: String) : TokenLoadResult
}

/** 令牌读不出来的原因类别（闭集）。 */
object TokenFailure {

    /** 落盘的密文格式不对（被截断、被别的程序改过）。 */
    const val MALFORMED_BLOB = "token_blob_malformed"

    /** 密文格式没问题，但解不开（密钥被系统作废、标签校验失败）。 */
    const val DECRYPT_FAILED = "token_decrypt_failed"

    /** 解出来的字节不是合法 UTF-8。 */
    const val BAD_ENCODING = "token_not_utf8"

    /** 解出来是空的 —— 与 [TokenLoadResult.Absent] 不同，这是"存过东西但内容不可用"。 */
    const val EMPTY = "token_empty"

    /** Gate 0 的老版本把明文存在 `device_token` 里，迁移失败。 */
    const val MIGRATION_FAILED = "token_migration_failed"

    /** 全部合法类别。 */
    val ALL: Set<String> = setOf(MALFORMED_BLOB, DECRYPT_FAILED, BAD_ENCODING, EMPTY, MIGRATION_FAILED)
}

/**
 * 密文的文本表示：`v1.<base64url(iv)>.<base64url(密文)>`。
 *
 * 纯函数，无 IO、无 Android 依赖 —— 编码/解码的每条畸形输入都能在 JVM 单测里穷举。
 */
object SecretCodec {

    const val CURRENT_VERSION = 1

    private const val PREFIX = "v1"

    /** GCM 的认证标签固定 16 字节，所以密文至少这么长。 */
    const val MIN_CIPHERTEXT_BYTES = 16

    private val encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder: Base64.Decoder = Base64.getUrlDecoder()

    fun encode(secret: EncryptedSecret): String {
        require(secret.version == CURRENT_VERSION) { "unsupported secret version: ${secret.version}" }
        require(secret.iv.isNotEmpty()) { "iv must not be empty" }
        require(secret.ciphertext.size >= MIN_CIPHERTEXT_BYTES) { "ciphertext too short" }
        return "$PREFIX.${encoder.encodeToString(secret.iv)}.${encoder.encodeToString(secret.ciphertext)}"
    }

    /**
     * 解析密文。**任何**异常输入都返回 `null`，绝不抛出 ——
     * 这个方法会读磁盘上的内容，而磁盘上的内容是可以被外力改写的。
     */
    fun decode(text: String?): EncryptedSecret? {
        if (text == null) return null
        val parts = text.split('.')
        if (parts.size != 3 || parts[0] != PREFIX) return null
        val iv = decodePart(parts[1]) ?: return null
        val ciphertext = decodePart(parts[2]) ?: return null
        if (iv.isEmpty() || ciphertext.size < MIN_CIPHERTEXT_BYTES) return null
        return EncryptedSecret(CURRENT_VERSION, iv, ciphertext)
    }

    private fun decodePart(part: String): ByteArray? {
        if (part.isEmpty()) return null
        return try {
            decoder.decode(part)
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

/** 严格 UTF-8 解码：不合法的字节序列返回 `null`，不做替换。 */
internal fun decodeUtf8Strict(bytes: ByteArray): String? = try {
    Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
} catch (_: CharacterCodingException) {
    null
}

/** 保存的结果。失败**不抛异常**，由调用方决定怎么提示用户。 */
sealed interface TokenSaveResult {
    data object Ok : TokenSaveResult

    /** [reason] 取自 [TokenFailure] 或 `"token_store_failed"`，不含异常原文。 */
    data class Failed(val reason: String) : TokenSaveResult
}

/**
 * 令牌存储的编排层：把 [SecretCipher] 与 [SecretStorage] 拼起来，并**只**在这里
 * 决定"什么算读出来了"。没有 Android 依赖，因此可以完整单测。
 */
class SecureTokenStore(
    private val cipher: SecretCipher,
    private val storage: SecretStorage,
) {

    /** 加密密钥不可用（Keystore 被禁用、没有安全硬件等）。 */
    private val storeFailed = "token_store_failed"

    /**
     * 保存令牌。传空白串等价于**清除**（用户把输入框清空并保存）。
     */
    fun save(token: String): TokenSaveResult {
        if (token.isBlank()) {
            clear()
            return TokenSaveResult.Ok
        }
        return try {
            val secret = cipher.encrypt(token.toByteArray(Charsets.UTF_8))
            storage.write(SecretCodec.encode(secret))
            TokenSaveResult.Ok
        } catch (error: RuntimeException) {
            // 不把异常文本带出去；失败就是失败，调用方必须让用户知道写失败了。
            TokenSaveResult.Failed(storeFailed)
        }
    }

    fun load(): TokenLoadResult {
        val raw = storage.read() ?: return TokenLoadResult.Absent

        val secret = SecretCodec.decode(raw)
            ?: return TokenLoadResult.Unreadable(TokenFailure.MALFORMED_BLOB)

        val plain = try {
            cipher.decrypt(secret)
        } catch (_: SecretDecryptionException) {
            return TokenLoadResult.Unreadable(TokenFailure.DECRYPT_FAILED)
        }

        val token = decodeUtf8Strict(plain)
        // 明文是中间态，用完立刻清零（String 本身无法擦除，见文件头声明）。
        plain.fill(0)
        if (token == null) return TokenLoadResult.Unreadable(TokenFailure.BAD_ENCODING)
        if (token.isBlank()) return TokenLoadResult.Unreadable(TokenFailure.EMPTY)

        return TokenLoadResult.Present(token)
    }

    fun clear() {
        storage.clear()
    }
}
