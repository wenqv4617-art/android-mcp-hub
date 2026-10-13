package com.androidmcphub.app.aggregation

/**
 * 第三方工具的**统一身份**。
 *
 * 服务端（ChatGPT）看到的工具名必须是一个能唯一定位"哪台服务器上的哪个工具"的字符串，
 * 否则两台服务器上各有一个 `search` 时，模型点哪一个都是掷骰子。规则：
 *
 * ```
 * mcp:<serverId>:<toolName>
 * ```
 *
 * `serverId` 是本机生成的稳定标识（不是 URL —— 用户改地址不该换身份，否则已经
 * 授权的工具会突然变成"新工具"需要重新授权，而重新授权正是钓鱼最想要的效果）。
 *
 * 两个正则与 `worker/src/mcp/hub.ts` 里的 `HUB_SERVER_ID_PATTERN` /
 * `HUB_TOOL_NAME_PATTERN` **逐字一致**：服务端与手机必须对"什么样的名字是合法的"
 * 有同一种判断，否则会出现"服务端放行、手机拒绝"这种只在真机上才暴露的错配。
 */
object HubIdentity {

    /** serverId：小写字母/数字开头，其余为小写字母、数字、下划线、短横线，长度 1–64。 */
    val SERVER_ID_PATTERN = Regex("^[a-z0-9][a-z0-9_-]{0,63}$")

    /** 工具名：字母、数字、下划线、短横线，长度 1–128（MCP 工具名大小写敏感）。 */
    val TOOL_NAME_PATTERN = Regex("^[A-Za-z0-9_-]{1,128}$")

    /** 统一工具名的前缀。 */
    const val TOOL_ID_PREFIX = "mcp"

    fun toolId(serverId: String, toolName: String): String = "$TOOL_ID_PREFIX:$serverId:$toolName"

    fun isValidServerId(value: String): Boolean = SERVER_ID_PATTERN.matches(value)

    fun isValidToolName(value: String): Boolean = TOOL_NAME_PATTERN.matches(value)
}
