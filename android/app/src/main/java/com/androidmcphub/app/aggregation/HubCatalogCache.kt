package com.androidmcphub.app.aggregation

import com.androidmcphub.app.mcp.model.McpServerConfig
import com.androidmcphub.app.mcp.transport.McpRemoteTool

/**
 * 缓存里的一个工具：服务器给的**原始**定义，加上拉取当时算出的授权指纹。
 *
 * 为什么要把指纹一起存下来：授权记录绑定的是 (服务器, 工具名, 指纹) 三元组。
 * 每次目录请求都要用当前授权表重新判一遍 —— 这样用户**撤销**授权之后，
 * 缓存里的条目下一次请求就会立刻消失（不需要重新联网），而服务器**改了定义**
 * 的那条会因为指纹不匹配变成 `stale`，同样在本地就能判定。
 */
data class HubCachedTool(
    val tool: McpRemoteTool,
    val fingerprint: String,
)

/**
 * 一台服务器最近一次刷新的结果。
 *
 * [tools] 为 null 有两种含义，都如实记录：从未成功枚举过；或者只做过"探活"
 * （`servers_only` 的服务器状态查询**不枚举工具**，所以它不能把已有目录清空）。
 *
 * [configKey] 是服务器配置的形状（地址 + 传输方式）。用户改了地址之后，旧目录
 * 必须立刻失效 —— 那些工具属于另一台服务器，继续展示它们就是在展示幻觉。
 */
data class HubServerSnapshot(
    val configKey: String,
    val availability: String,
    val checkedAtMs: Long,
    val tools: List<HubCachedTool>?,
    val fetchedAtMs: Long,
    /** 最近一次刷新失败的时刻；成功后清空。用于失败退避，避免对离线服务器反复重连。 */
    val failedAtMs: Long?,
)

/**
 * 第三方工具目录与服务器可用性的**本地缓存**（V1 性能专项 5.1/5.2）。
 *
 * 只有这一份状态，且全部在内存里：目录是可以随时重建的派生数据，落盘只会带出
 * "磁盘里的旧目录与配置对不上"这种更难查的问题。进程重启后的第一请求会重新拉，
 * 而那次请求同样受 [HubLimits.catalogDeadlineMs] 约束。
 *
 * 线程模型：所有方法都在内部锁里读写；返回的 [HubServerSnapshot] 是不可变的，
 * 调用方拿到之后可以放心在别的线程上用（其中的 [McpRemoteTool] 只读不写）。
 */
class HubCatalogCache(
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val lock = Any()

    private val entries = HashMap<String, HubServerSnapshot>()

    /** 服务器配置的形状；变了就意味着缓存里的工具不属于这台服务器了。 */
    fun configKey(config: McpServerConfig): String = "${config.url}|${config.transport}"

    /** 读一台服务器的快照；配置变了或从未刷过都返回 null。 */
    fun snapshotFor(config: McpServerConfig): HubServerSnapshot? = synchronized(lock) {
        val entry = entries[config.id] ?: return null
        if (entry.configKey != configKey(config)) return null
        entry
    }

    /** 只读：这份快照里的工具数据是否还在有效期内。 */
    fun isFresh(config: McpServerConfig, ttlMs: Long): Boolean {
        val entry = snapshotFor(config) ?: return false
        if (entry.tools == null) return false
        return clock() - entry.fetchedAtMs in 0..ttlMs
    }

    /** 可用性是否还在有效期内（探活与枚举都会更新它）。 */
    fun isAvailabilityFresh(config: McpServerConfig, ttlMs: Long): Boolean {
        val entry = snapshotFor(config) ?: return false
        return clock() - entry.checkedAtMs in 0..ttlMs
    }

    /** 距离上次失败过去了多久；从未失败返回 null。 */
    fun failedAgoMs(config: McpServerConfig): Long? {
        val entry = snapshotFor(config) ?: return null
        val failedAt = entry.failedAtMs ?: return null
        return clock() - failedAt
    }

    /**
     * 记一次成功。
     *
     * @param tools 非 null 表示这次**枚举**了工具（更新目录）；null 表示只探活
     *   （`servers_only` 的后台探测），此时保留已有目录不动。
     */
    fun recordOnline(config: McpServerConfig, tools: List<HubCachedTool>?) = synchronized(lock) {
        val now = clock()
        val key = configKey(config)
        val previous = entries[config.id]?.takeIf { it.configKey == key }
        entries[config.id] = if (tools == null) {
            HubServerSnapshot(
                configKey = key,
                availability = HubStates.AVAILABILITY_ONLINE,
                checkedAtMs = now,
                tools = previous?.tools,
                fetchedAtMs = previous?.fetchedAtMs ?: 0L,
                failedAtMs = null,
            )
        } else {
            HubServerSnapshot(
                configKey = key,
                availability = HubStates.AVAILABILITY_ONLINE,
                checkedAtMs = now,
                tools = tools,
                fetchedAtMs = now,
                failedAtMs = null,
            )
        }
    }

    /**
     * 记一次失败。
     *
     * 目录条目**保留**（下次成功刷新可以复用旧数据的年龄信息），但可用性变成离线：
     * 离线服务器上的工具不会出现在目录里 —— 模型看不到它就不能调它，这比让模型
     * 拿着一个调不通的工具去试要好。
     */
    fun recordFailure(config: McpServerConfig) = synchronized(lock) {
        val now = clock()
        val key = configKey(config)
        val previous = entries[config.id]?.takeIf { it.configKey == key }
        entries[config.id] = HubServerSnapshot(
            configKey = key,
            availability = HubStates.AVAILABILITY_OFFLINE,
            checkedAtMs = now,
            tools = previous?.tools,
            fetchedAtMs = previous?.fetchedAtMs ?: 0L,
            failedAtMs = now,
        )
    }

    /** 只保留仍然存在的服务器（用户删掉的配置不该在内存里留一份目录）。 */
    fun retainOnly(serverIds: Set<String>) = synchronized(lock) {
        entries.keys.retainAll(serverIds)
    }

    /** 清空（单测与"用户清空全部配置"时用）。 */
    fun clear() = synchronized(lock) { entries.clear() }

    /** 当前缓存的服务器数（单测用）。 */
    fun size(): Int = synchronized(lock) { entries.size }
}
