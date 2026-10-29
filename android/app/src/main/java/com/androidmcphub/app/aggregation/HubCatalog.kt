package com.androidmcphub.app.aggregation

import com.androidmcphub.app.mcp.store.McpAuthorizationState
import org.json.JSONArray
import org.json.JSONObject

/**
 * 目录里出现的**状态词**。
 *
 * 这些是服务端与手机之间的约定词，`worker/src/mcp/hub.ts` 只认这几个值：
 * 工具的 `authorization_state` 不是 `authorized`、或 `availability` 不是 `online`，
 * 那一项**就不会**被转发给 ChatGPT。所以这里的字符串不是显示文案，
 * 改一个字母就会静默地把工具从模型眼前拿走。
 */
object HubStates {

    const val AVAILABILITY_ONLINE = "online"
    const val AVAILABILITY_OFFLINE = "offline"

    const val AUTHORIZATION_AUTHORIZED = "authorized"
    const val AUTHORIZATION_NOT_AUTHORIZED = "not_authorized"
    const val AUTHORIZATION_STALE = "stale"

    /** 把授权存储的三态映射成约定词。 */
    fun authorizationOf(state: McpAuthorizationState): String = when (state) {
        McpAuthorizationState.Granted -> AUTHORIZATION_AUTHORIZED
        McpAuthorizationState.NotGranted -> AUTHORIZATION_NOT_AUTHORIZED
        McpAuthorizationState.Stale -> AUTHORIZATION_STALE
    }
}

/**
 * 目录里的一台服务器。
 *
 * [availability] 是"这次刷新时它能不能用"，不是"它一直能不能用" —— 目录是快照，
 * 不维护任何后台轮询状态。这样"手机上显示在线"永远对应着"刚刚真的连上过"。
 */
data class HubCatalogServer(
    val serverId: String,
    val serverName: String,
    val availability: String,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("server_id", serverId)
        put("server_name", serverName)
        put("availability", availability)
    }
}

/**
 * 目录里的一个工具。
 *
 * [fingerprint] 是**授权指纹**（见 `McpToolFingerprint`），必须按服务器的**原始**
 * schema 计算；界面靠它做"授权/撤销"。界面自己拿可能被截断的 schema 重算会算出
 * 错误的指纹，而错误的指纹只有一个后果：用户被要求反复重新授权，或者更糟 ——
 * 以为授权的是旧定义。
 *
 * [inputSchema] 保留**原始**结构；是否随目录下发由 `McpHubRouter.listTools` 的
 * `include_schema` 与 `maxSchemaChars` 决定（超限的那一条只丢自己的 schema，
 * 并在 `schemas_dropped` 里如实计数）。
 */
data class HubCatalogTool(
    val serverId: String,
    val toolName: String,
    val displayName: String,
    val description: String,
    val inputSchema: JSONObject?,
    val authorizationState: String,
    val availability: String,
    val fingerprint: String,
) {
    /**
     * 目录里的一条工具。**默认不带 schema，也不带指纹**。
     *
     * 35 个工具 × 4 KiB schema 会直接把 16 KiB 的帧撑爆，于是整份目录被丢弃 ——
     * 这正是"音叙 35 个工具"那一轮的真实根因。schema 改为按需（`hub_get_tool_schema`
     * 或显式 `include_schema`），指纹只在本机授权判定里用，远端没有必要看到。
     */
    fun toListJson(descriptionLimit: Int, schema: JSONObject? = null): JSONObject = JSONObject().apply {
        put("server_id", serverId)
        put("tool_name", toolName)
        put("display_name", displayName)
        put("description", if (description.length <= descriptionLimit) description else description.substring(0, descriptionLimit))
        // 注意：schema 为 null 时**不放**这个键（而不是放 JSON null）——
        // 服务端读到的 `undefined` 与 `null` 都能处理，但少放一个键更不容易被误读成
        // "这个工具没有参数"。
        if (schema != null) put("input_schema", schema)
        put("authorization_state", authorizationState)
        put("availability", availability)
    }
}

/**
 * 一次目录刷新的完整结果。
 *
 * [otherToolsCount] 是"存在但你这次没看到"的工具数量：未授权的、所在服务器离线的、
 * 因为超限被丢掉的，全都算在里面。这个数字必须如实给出 —— 它是"没有偷偷藏东西"
 * 的唯一可验证形式：模型看到一个 `other_tools_count: 3` 就知道还有三件它不能碰的东西，
 * 而不是以为这台手机只有两个工具。
 */
data class HubCatalog(
    val servers: List<HubCatalogServer>,
    /** 这一页的工具。 */
    val tools: List<HubCatalogTool>,
    val otherToolsCount: Int,
    /** 本次扫描里"已授权且在线"的工具总数（不受本页大小影响）。 */
    val totalTools: Int = tools.size,
    val offset: Int = 0,
    val limit: Int = tools.size,
    val hasMore: Boolean = false,
    val nextOffset: Int? = null,
    /** 调用了 include_schema，但 schema 太大被省略的工具数。 */
    val schemasDropped: Int = 0,
    /** 有条目因为字符预算被挤到下一页（而不是工具本身超限）。 */
    val truncatedByBudget: Boolean = false,
    /** 本次只查询服务器（`servers_only`），没有枚举工具。 */
    val serversOnly: Boolean = false,
) {
    fun toJson(
        descriptionLimit: Int = Int.MAX_VALUE,
        schemaFor: (HubCatalogTool) -> JSONObject? = { null },
    ): JSONObject = JSONObject().apply {
        put("servers", JSONArray().apply { servers.forEach { put(it.toJson()) } })
        put("tools", JSONArray().apply { tools.forEach { put(it.toListJson(descriptionLimit, schemaFor(it))) } })
        put("total_tools", totalTools)
        put("returned", tools.size)
        put("offset", offset)
        put("limit", limit)
        put("has_more", hasMore)
        if (nextOffset != null) put("next_offset", nextOffset)
        put("other_tools_count", otherToolsCount)
        put("schemas_dropped", schemasDropped)
        if (serversOnly) put("servers_only", true)
        put("truncated", hasMore || truncatedByBudget || schemasDropped > 0)
    }
}
