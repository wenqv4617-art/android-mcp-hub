package com.androidmcphub.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gate 0.1 审计 R4 的单元测试：令牌存储。
 *
 * 这里**不碰** Android Keystore —— 那部分在 JVM 单测里跑不到，见 TEST_REPORT 的 NOT RUN 清单。
 * 但存储层真正容易出错的地方（三态区分、畸形密文、失败不吞）全在这里，用假 [SecretCipher]
 * 和假 [SecretStorage] 把每条路径都走一遍。
 */
class DeviceTokenStoreTest {

    // ── 假实现 ────────────────────────────────────────────────────────────────

    private class FakeStorage(private var value: String? = null) : SecretStorage {
        override fun read(): String? = value
        override fun write(encoded: String) {
            value = encoded
        }

        override fun clear() {
            value = null
        }

        fun raw(): String? = value
    }

    private class ExplodingStorage : SecretStorage {
        override fun read(): String? = null
        override fun write(encoded: String) = throw IllegalStateException("storage unavailable")

        override fun clear() = Unit
    }

    /** 假"加密"：异或 + 补一个固定长度的假标签，长度满足 GCM 下限。 */
    private class FakeCipher(
        private val failEncrypt: Boolean = false,
        private val failDecrypt: Boolean = false,
    ) : SecretCipher {

        override fun encrypt(plain: ByteArray): EncryptedSecret {
            if (failEncrypt) throw IllegalStateException("keystore unavailable")
            val body = ByteArray(plain.size) { (plain[it].toInt() xor KEY).toByte() }
            return EncryptedSecret(
                SecretCodec.CURRENT_VERSION,
                ByteArray(12) { (it + 7).toByte() },
                body + ByteArray(SecretCodec.MIN_CIPHERTEXT_BYTES) { (it + 1).toByte() },
            )
        }

        override fun decrypt(secret: EncryptedSecret): ByteArray {
            if (failDecrypt) throw SecretDecryptionException("AEADBadTagException")
            val bodySize = secret.ciphertext.size - SecretCodec.MIN_CIPHERTEXT_BYTES
            val body = secret.ciphertext.copyOf(bodySize)
            return ByteArray(body.size) { (body[it].toInt() xor KEY).toByte() }
        }

        private companion object {
            const val KEY = 0x5A
        }
    }

    /** 直接返回指定字节的"解密"，用来构造"解出来不是合法 UTF-8"这类情形。 */
    private class ReturnCipher(private val bytes: ByteArray) : SecretCipher {
        override fun encrypt(plain: ByteArray): EncryptedSecret =
            EncryptedSecret(SecretCodec.CURRENT_VERSION, ByteArray(12), ByteArray(16))

        override fun decrypt(secret: EncryptedSecret): ByteArray = bytes
    }

    private val token = "gate0-local-test-device-token-not-a-secret"

    private fun store(
        storage: SecretStorage = FakeStorage(),
        cipher: SecretCipher = FakeCipher(),
    ) = SecureTokenStore(cipher, storage)

    // ── 正常路径 ──────────────────────────────────────────────────────────────

    @Test
    fun `存进去再读出来得到同一个令牌`() {
        val s = store()
        assertEquals(TokenSaveResult.Ok, s.save(token))
        assertEquals(TokenLoadResult.Present(token), s.load())
    }

    @Test
    fun `从没存过是 Absent 而不是错误`() {
        assertEquals(TokenLoadResult.Absent, store().load())
    }

    @Test
    fun `保存空白串等于清除`() {
        val storage = FakeStorage()
        val s = store(storage)
        s.save(token)
        assertEquals(TokenSaveResult.Ok, s.save("   "))
        assertNull(storage.raw())
        assertEquals(TokenLoadResult.Absent, s.load())
    }

    // ── 明文不落盘 ────────────────────────────────────────────────────────────

    @Test
    fun `落盘的是密文，明文令牌一个字节都不在`() {
        val storage = FakeStorage()
        store(storage).save(token)

        val raw = storage.raw()
        assertNotNull(raw)
        assertFalse("磁盘上的内容里出现了明文令牌", raw!!.contains(token))
        assertTrue("密文应当带版本前缀", raw.startsWith("v1."))
        assertNotNull("落盘的内容必须能被解码器解回来", SecretCodec.decode(raw))
    }

    @Test
    fun `令牌里的每个可读片段都不出现在密文里`() {
        val storage = FakeStorage()
        val tricky = "token-abcdefghijklmnop-0123456789"
        store(storage).save(tricky)
        val raw = storage.raw()!!
        for (piece in listOf("token", "abcdefghijklmnop", "0123456789")) {
            assertFalse("密文里出现了明文片段：$piece", raw.contains(piece))
        }
    }

    // ── 失败路径：必须区分，必须不吞 ──────────────────────────────────────────

    @Test
    fun `密文格式不对是 Unreadable 而不是 Absent`() {
        val malformed = listOf(
            "",
            "   ",
            "not-a-ciphertext",
            "v1.abc",
            "v1.abc.def",
            "v1..AAAAAAAAAAAAAAAAAAAAAA",
            "v1.AAAAAAAAAAAAAAAAAAAAAA.",
            "v2.AAAAAAAAAAAAAAAAAAAAAA.AAAAAAAAAAAAAAAAAAAAAAAA",
            "v1.!!!not-base64!!!.AAAAAAAAAAAAAAAAAAAAAAAA",
            // 密文太短（GCM 的标签就 16 字节）
            "v1.AAAAAAAAAAAAAAAAAAAAAA.AAAA",
        )
        for (raw in malformed) {
            val result = store(FakeStorage(raw)).load()
            assertTrue(
                "输入「$raw」应当被判为 Unreadable，实际是 $result",
                result is TokenLoadResult.Unreadable,
            )
            assertEquals(
                TokenFailure.MALFORMED_BLOB,
                (result as TokenLoadResult.Unreadable).reason,
            )
        }
    }

    @Test
    fun `解密失败是 Unreadable 且类别是 decrypt_failed`() {
        val storage = FakeStorage()
        store(storage, FakeCipher()).save(token)
        val result = store(storage, FakeCipher(failDecrypt = true)).load()
        assertEquals(TokenLoadResult.Unreadable(TokenFailure.DECRYPT_FAILED), result)
    }

    @Test
    fun `解出来不是合法 UTF-8 时是 Unreadable 而不是替换成乱码`() {
        val storage = FakeStorage()
        store(storage).save(token)
        // 0xC3 后面跟 0x28 是非法 UTF-8 序列；宽松解码会静默替换成 U+FFFD，
        // 那样用户会拿到一串"看起来像令牌"的乱码去连服务器。
        val result = store(storage, ReturnCipher(byteArrayOf(0xC3.toByte(), 0x28))).load()
        assertEquals(TokenLoadResult.Unreadable(TokenFailure.BAD_ENCODING), result)
    }

    @Test
    fun `解出来是空白时是 Unreadable 而不是 Absent`() {
        val storage = FakeStorage()
        store(storage).save(token)
        val result = store(storage, ReturnCipher("   ".toByteArray())).load()
        assertEquals(TokenLoadResult.Unreadable(TokenFailure.EMPTY), result)
    }

    @Test
    fun `加密失败时返回 Failed 且什么都不写`() {
        val storage = FakeStorage()
        val result = store(storage, FakeCipher(failEncrypt = true)).save(token)
        assertTrue(result is TokenSaveResult.Failed)
        assertNull("加密失败就不该有东西落盘", storage.raw())
    }

    @Test
    fun `落盘失败时返回 Failed 而不是假装成功`() {
        val result = store(ExplodingStorage()).save(token)
        assertEquals(TokenSaveResult.Failed("token_store_failed"), result)
    }

    @Test
    fun `保存失败的信息里不含令牌内容`() {
        val result = store(ExplodingStorage()).save(token) as TokenSaveResult.Failed
        assertFalse(result.reason.contains(token))
        assertFalse(result.reason.contains("Bearer"))
    }

    @Test
    fun `每一种 Unreadable 的类别都在闭集内且不含令牌内容`() {
        val storage = FakeStorage()
        store(storage).save(token)
        val results = listOf(
            store(FakeStorage("garbage")).load(),
            store(storage, FakeCipher(failDecrypt = true)).load(),
            store(storage, ReturnCipher(byteArrayOf(0xC3.toByte(), 0x28))).load(),
            store(storage, ReturnCipher(" ".toByteArray())).load(),
        )
        for (result in results) {
            val unreadable = result as TokenLoadResult.Unreadable
            assertTrue("类别 ${unreadable.reason} 不在闭集内", unreadable.reason in TokenFailure.ALL)
            assertFalse(unreadable.reason.contains(token))
        }
    }

    // ── 编码器本身（会被外部改写磁盘内容，所以必须只认严格格式） ──────────────

    @Test
    fun `编码器解不开的东西一律返回 null 且不抛异常`() {
        val invalid = listOf(
            null, "", " ", ".", "..", "v1", "v1.", "v1..", "v1.a.b.c",
            "V1.AAAA.BBBBBBBBBBBBBBBBBBBBBBBB",     // 版本前缀必须小写
            "v1.AAAA.BBBB",                          // 密文比 GCM 标签还短
            "v1..BBBBBBBBBBBBBBBBBBBBBBBB",          // IV 为空
            "v1.AAAA.",                              // 密文为空
            "v1.AAAA.BB!BBBBBBBBBBBBBBBBBBBBB",      // 含非 base64url 字符
            "\u0000", "v1.\n.\n", "v1.===.AAAA",
        )
        for (input in invalid) {
            assertNull("输入「$input」应判为不可解析", SecretCodec.decode(input))
        }
    }

    @Test
    fun `长度刚好合法的密文可以被解析`() {
        // 24 个 base64 字符 = 18 字节 ≥ 16（GCM 标签），是合法输入
        val decoded = SecretCodec.decode("v1.AAAA.BBBBBBBBBBBBBBBBBBBBBBBB")
        assertNotNull(decoded)
        assertEquals(18, decoded!!.ciphertext.size)
    }

    @Test
    fun `合法的密文能被解码器解回来`() {
        val secret = EncryptedSecret(
            SecretCodec.CURRENT_VERSION,
            ByteArray(12) { 1 },
            ByteArray(SecretCodec.MIN_CIPHERTEXT_BYTES) { 2 },
        )
        val decoded = SecretCodec.decode(SecretCodec.encode(secret))
        assertNotNull(decoded)
        assertEquals(secret.iv.toList(), decoded!!.iv.toList())
        assertEquals(secret.ciphertext.toList(), decoded.ciphertext.toList())
        assertEquals(SecretCodec.CURRENT_VERSION, decoded.version)
    }

    @Test
    fun `编码器拒绝不合规的输入`() {
        // 版本号不对
        assertThrows(IllegalArgumentException::class.java) {
            SecretCodec.encode(EncryptedSecret(2, ByteArray(12), ByteArray(16)))
        }
        // IV 为空
        assertThrows(IllegalArgumentException::class.java) {
            SecretCodec.encode(EncryptedSecret(1, ByteArray(0), ByteArray(16)))
        }
        // 密文比 GCM 标签还短
        assertThrows(IllegalArgumentException::class.java) {
            SecretCodec.encode(EncryptedSecret(1, ByteArray(12), ByteArray(4)))
        }
    }

    @Test
    fun `fuzz 一批随机字符串都不会让解码器抛异常`() {
        val random = kotlin.random.Random(42)
        repeat(500) {
            val length = random.nextInt(0, 64)
            val text = buildString {
                repeat(length) {
                    append((random.nextInt(32, 127)).toChar())
                }
            }
            // 只要求"不抛"，返回值是 null 还是对象都行
            SecretCodec.decode(text)
        }
    }
}
