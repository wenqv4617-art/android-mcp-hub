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
 * schema 计算 —— 即使 [inputSchema] 因为超限被置为 `null`，指纹也照样回传。
 * 界面靠它做"授权/撤销"，界面自己拿可能被截断的 schema 重算会算出错误的指纹，
 * 而错误的指纹只有一个后果：用户被要求反复重新授权，或者更糟 —— 以为授权的是旧定义。
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
    fun toJson(): JSONObject = JSONObject().apply {
        put("server_id", serverId)
        put("tool_name", toolName)
        put("display_name", displayName)
        put("description", description)
        // 注意：schema 为 null 时**不放**这个键（而不是放 JSON null）——
        // 服务端读到的 `undefined` 与 `null` 都能处理，但少放一个键更不容易被误读成
        // "这个工具没有参数"。
        if (inputSchema != null) put("input_schema", inputSchema)
        put("authorization_state", authorizationState)
        put("availability", availability)
        put("fingerprint", fingerprint)
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
    val tools: List<HubCatalogTool>,
    val otherToolsCount: Int,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("servers", JSONArray().apply { servers.forEach { put(it.toJson()) } })
        put("tools", JSONArray().apply { tools.forEach { put(it.toJson()) } })
        put("other_tools_count", otherToolsCount)
    }
}
