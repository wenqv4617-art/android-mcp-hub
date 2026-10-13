package com.androidmcphub.app.mcp.store

import com.androidmcphub.app.core.EncryptedSecret
import com.androidmcphub.app.core.SecretCipher
import com.androidmcphub.app.core.SecretCodec
import com.androidmcphub.app.core.SecretStorage
import com.androidmcphub.app.core.decodeUtf8Strict

/**
 * 一"格"加密字符串。
 *
 * 为什么不让 `McpServerStore` 直接用 `SecretCipher` + `SecretStorage`：
 * 那样每个用到它的测试类都要自己写一遍"加密 → SecretCodec 编码 → 落盘"和反向的
 * "解码 → 解密 → 严格 UTF-8"这五步，而且很容易漏掉其中任何一步的错误处理。
 * 这里把这块固定下来，`McpServerStore` 只需要关心自己的 JSON 结构。
 *
 * 契约：**读永不抛异常**（磁盘上的东西是可以被外力改写的，包括密钥被系统换掉），
 * 读不出来就返回 `null`，调用方按"没有敏感值"处理。
 */
interface SecretBlobStore {

    /** 读出明文。密文缺失、格式损坏、解密失败、不是合法 UTF-8 → 一律 `null`。 */
    fun read(): String?

    /** 写入明文（内部负责加密）。 */
    fun write(plainText: String)

    fun clear()
}

/**
 * 真实实现：明文经 [SecretCipher] 加密、[SecretCodec] 编码后交给 [SecretStorage]。
 *
 * 注意"先解码再解密失败"是**正常路径**：用户可能在别处清了 Keystore（重装、改锁屏），
 * 那时密文还在但解不开。这里不把异常抛给上层 —— 一条读不出来的 header 值，
 * 应该表现为"这个请求头没了"，而不是"打开设置页就崩"。
 */
class AndroidSecretBlobStore(
    private val cipher: SecretCipher,
    private val storage: SecretStorage,
) : SecretBlobStore {

    override fun read(): String? {
        val encoded = storage.read() ?: return null
        val secret: EncryptedSecret = SecretCodec.decode(encoded) ?: return null
        val plain = try {
            cipher.decrypt(secret)
        } catch (_: Exception) {
            return null
        }
        return decodeUtf8Strict(plain)
    }

    override fun write(plainText: String) {
        val bytes = plainText.toByteArray(Charsets.UTF_8)
        storage.write(SecretCodec.encode(cipher.encrypt(bytes)))
    }

    override fun clear() {
        storage.clear()
    }
}
