package com.androidmcphub.app.mcp.store

/**
 * 内存版 [KeyValueStore]，只给单测用。
 *
 * 放在测试目录而不是 main：产品代码里没有第二个实现需要它，把它带上 APK 只会让
 * "哪份存储是真的"这个问题更难回答。
 */
class InMemoryKeyValueStore : KeyValueStore {

    private val values = LinkedHashMap<String, String>()

    override fun getString(key: String): String? = values[key]

    override fun putString(key: String, value: String) {
        values[key] = value
    }

    override fun remove(key: String) {
        values.remove(key)
    }

    override fun contains(key: String): Boolean = values.containsKey(key)

    override fun keys(): Set<String> = values.keys.toSet()

    /** 测试辅助：看一眼键下到底存了什么，用来断言"明文值/密文都没写进明文键"。 */
    fun raw(): Map<String, String> = LinkedHashMap(values)
}
