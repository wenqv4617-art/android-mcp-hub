package com.androidmcphub.app.mcp.store

import android.content.SharedPreferences
import com.androidmcphub.app.core.EncryptedSecret
import com.androidmcphub.app.core.SecretCipher

/**
 * 可逆的假加密器：密文 = 明文 + 16 字节固定标签。
 *
 * 追着 16 这个数字不是随手写的：[com.androidmcphub.app.core.SecretCodec] 会拒绝短于
 * 16 字节的密文，假加密器要是返回 4 字节，"密文不落明文键"这条断言就会在编码阶段
 * 先炸掉，测不到真正想测的东西。
 */
class FakeSecretCipher : SecretCipher {

    override fun encrypt(plain: ByteArray): EncryptedSecret = EncryptedSecret(
        version = 1,
        iv = ByteArray(IV_SIZE) { (it + 1).toByte() },
        ciphertext = plain + TAG,
    )

    override fun decrypt(secret: EncryptedSecret): ByteArray {
        if (secret.version != 1) throw IllegalStateException("版本不支持")
        val body = secret.ciphertext
        val tagStart = body.size - TAG.size
        if (tagStart < 0 || !body.copyOfRange(tagStart, body.size).contentEquals(TAG)) {
            throw IllegalStateException("密文被改坏了")
        }
        return body.copyOfRange(0, tagStart)
    }

    private companion object {
        const val IV_SIZE = 12
        val TAG = ByteArray(16) { 0x5A.toByte() }
    }
}

/** 把明文原样收在内存里的 [SecretBlobStore]（密文键在真机上由它负责加解密）。 */
class FakeSecretBlobStore : SecretBlobStore {

    private var stored: String? = null

    /** 测试辅助：当前"密文"里存的内容。 */
    val plainText: String?
        get() = stored

    override fun read(): String? = stored

    override fun write(plainText: String) {
        stored = plainText
    }

    override fun clear() {
        stored = null
    }
}

/**
 * 内存版 [SharedPreferences]。
 *
 * 有了它，[PrefsKeyValueStore] 和 [AndroidSecretBlobStore]（真实实现）都能在纯 JVM 里跑，
 * 不必引入 Robolectric：这个仓库既有的 347 条测试里没有一条用 Robolectric，而 Robolectric
 * 的 android-all 需要联网取，为了几条存储测试把整套测试拖进网络依赖不划算。
 */
class FakeSharedPreferences : SharedPreferences {

    private val values = LinkedHashMap<String, Any?>()
    private val listeners = mutableListOf<SharedPreferences.OnSharedPreferenceChangeListener>()

    /** 测试辅助：当前所有键值。 */
    val raw: Map<String, Any?>
        get() = LinkedHashMap(values)

    override fun getAll(): MutableMap<String, *> = LinkedHashMap(values)

    override fun getString(key: String, defValue: String?): String? = values[key] as? String ?: defValue

    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        @Suppress("UNCHECKED_CAST")
        (values[key] as? MutableSet<String>) ?: defValues

    override fun getInt(key: String, defValue: Int): Int = (values[key] as? Number)?.toInt() ?: defValue

    override fun getLong(key: String, defValue: Long): Long = (values[key] as? Number)?.toLong() ?: defValue

    override fun getFloat(key: String, defValue: Float): Float = (values[key] as? Number)?.toFloat() ?: defValue

    override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue

    override fun contains(key: String): Boolean = values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) {
        if (listener != null && listener !in listeners) listeners.add(listener)
    }

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) {
        listeners.remove(listener)
    }

    private fun applyChanges(changes: Map<String, Any?>, removals: Set<String>, clearFirst: Boolean) {
        if (clearFirst) values.clear()
        removals.forEach { values.remove(it) }
        changes.forEach { (key, value) -> values[key] = value }
        val touched = changes.keys + removals
        touched.forEach { key -> listeners.toList().forEach { it.onSharedPreferenceChanged(this, key) } }
    }

    private inner class Editor : SharedPreferences.Editor {

        private val changes = LinkedHashMap<String, Any?>()
        private val removals = mutableSetOf<String>()
        private var clearRequested = false

        override fun putString(key: String, value: String?): SharedPreferences.Editor = set(key, value)

        override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor =
            set(key, values?.toMutableSet())

        override fun putInt(key: String, value: Int): SharedPreferences.Editor = set(key, value)

        override fun putLong(key: String, value: Long): SharedPreferences.Editor = set(key, value)

        override fun putFloat(key: String, value: Float): SharedPreferences.Editor = set(key, value)

        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = set(key, value)

        override fun remove(key: String): SharedPreferences.Editor {
            changes.remove(key)
            removals.add(key)
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            clearRequested = true
            return this
        }

        override fun commit(): Boolean {
            applyChanges(changes, removals, clearRequested)
            return true
        }

        override fun apply() {
            applyChanges(changes, removals, clearRequested)
        }

        private fun set(key: String, value: Any?): SharedPreferences.Editor {
            if (value == null) {
                changes.remove(key)
                removals.add(key)
            } else {
                removals.remove(key)
                changes[key] = value
            }
            return this
        }
    }
}
