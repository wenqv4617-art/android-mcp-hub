package com.androidmcphub.app.mcp

import android.content.Context
import com.androidmcphub.app.mcp.store.KeyValueStore
import com.androidmcphub.app.mcp.store.McpAuthorizationStore
import com.androidmcphub.app.mcp.store.McpServerStore
import com.androidmcphub.app.mcp.store.PrefsKeyValueStore

/**
 * 第三方 MCP 服务器配置与授权的**唯一**装配点。
 *
 * 为什么要有这个文件（而不是让每个用到的地方自己 `new` 一遍）：
 * `PrefsKeyValueStore` 的实例只对"同一个 `SharedPreferences` 文件"才是同一份数据。
 * 如果前台服务与界面各自 `getSharedPreferences("mcp_servers", …)` 之外再写错一个文件名，
 * 结果就是**用户加的服务界面上看得见、实际执行时找不到** —— 这种 bug 不会崩、不会报错，
 * 只会表现为"我明明授权过的工具它说没有"，极难排查。
 *
 * 所以：
 *  - prefs 文件名只出现在 [McpServerStore.PREF_FILE] 一个地方（不给调用方传文件名的机会）；
 *  - 敏感请求头的加密密钥别名只在 [McpServerStore.HEADER_ALIAS] 一个地方；
 *  - 服务与界面拿到的都是"读同一份磁盘数据"的两个对象，而不是两份配置。
 *
 * 注意这里**不缓存**实例：`SharedPreferences` 自己有内存缓存，这里再缓存只会多出一份
 * 可能与磁盘不同步的状态。每次调用返回一个新的轻量包装对象是刻意的。
 */
object McpStores {

    /** 服务器配置（非敏感 JSON + 敏感 header 密文）。 */
    fun serverStore(context: Context): McpServerStore = McpServerStore.android(context)

    /**
     * 工具授权记录。
     *
     * 与服务器配置**共用同一个 prefs 文件**：两者是同一份数据的两半（配置没了，
     * 指向它的授权记录就只是垃圾），放一起才能保证备份/清理时行为一致。
     * 键名不同（`servers_v1` / `grants_v1`），不会互相覆盖。
     */
    fun authorizationStore(context: Context): McpAuthorizationStore =
        McpAuthorizationStore(keyValueStore(context))

    /** 服务器配置所在的键值存储（给需要直接读写同一份 prefs 的调用方）。 */
    fun keyValueStore(context: Context): KeyValueStore = PrefsKeyValueStore(
        context.applicationContext.getSharedPreferences(McpServerStore.PREF_FILE, Context.MODE_PRIVATE),
    )
}
