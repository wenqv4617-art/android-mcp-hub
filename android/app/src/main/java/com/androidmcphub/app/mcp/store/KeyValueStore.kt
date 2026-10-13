package com.androidmcphub.app.mcp.store

import android.content.SharedPreferences

/**
 * 一个最小的键值存储抽象。
 *
 * 存在的理由只有一个：让 `McpServerStore` / `McpAuthorizationStore` 能在**纯 JVM 单测**里
 * 跑起来。如果直接依赖 `SharedPreferences`，每个测试都得拖上 Robolectric（慢），
 * 而这里要测的东西（JSON 往返、坏数据容错、敏感值分流）跟 Android 一点关系都没有。
 */
interface KeyValueStore {

    fun getString(key: String): String?

    fun putString(key: String, value: String)

    fun remove(key: String)

    fun contains(key: String): Boolean

    fun keys(): Set<String>
}

/**
 * 真实的实现：落在 `SharedPreferences` 上。
 *
 * 用 `commit()` 而不是 `apply()`：保存服务器配置是用户按了「保存」之后立刻要看到结果的
 * 显式操作，同步落盘换来的是"杀进程也不丢"。这里没有高频写，不担心卡顿。
 */
class PrefsKeyValueStore(private val prefs: SharedPreferences) : KeyValueStore {

    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).commit()
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).commit()
    }

    override fun contains(key: String): Boolean = prefs.contains(key)

    override fun keys(): Set<String> = prefs.all.keys.toSet()
}
