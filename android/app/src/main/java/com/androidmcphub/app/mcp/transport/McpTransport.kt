package com.androidmcphub.app.mcp.transport

import org.json.JSONObject

/**
 * 一个 MCP 传输。
 *
 * 接口刻意窄：连接、列工具、调工具、关闭。上层（UI / 授权层）不该知道
 * 这次是 Streamable HTTP 还是别的什么，也不该看到任何 HTTP 细节 ——
 * 因为协议头、会话 id、分页游标这些东西一旦漏出去，就会在 UI 里长出各种特例。
 */
interface McpTransport {

    val serverId: String

    /** 完成 MCP 初始化握手。可以重复调用（每次都会重新初始化）。 */
    fun connect(): McpServerHandshake

    /** 列出全部工具（内部处理分页）。必须在 [connect] 之后调用。 */
    fun listTools(): List<McpRemoteTool>

    fun callTool(toolName: String, arguments: JSONObject): McpToolCallResult

    /** 关闭并清理会话。重复调用无副作用，不抛异常。 */
    fun close()
}

/** 握手结果。[serverName] / [serverVersion] 允许为空：不是所有服务器都填 serverInfo。 */
data class McpServerHandshake(
    val protocolVersion: String,
    val serverName: String?,
    val serverVersion: String?,
    val sessionId: String?,
)

/**
 * 远端工具。
 *
 * [rawJson] 保留服务器给的原始 JSON：授权指纹要对它取哈希，
 * 界面要"看原始定义"时也不用再去请求一次。
 */
data class McpRemoteTool(
    val name: String,
    val title: String?,
    val description: String,
    val inputSchema: JSONObject?,
    val annotations: JSONObject?,
    val rawJson: String,
)

/**
 * 工具调用结果。
 *
 * [text] 为 `null` 与 `""` 有区别：`null` 表示服务器**没给**文本内容块，
 * `""` 表示服务器明确给了一个空文本块。界面据此决定显示"（无返回内容）"还是空行。
 */
data class McpToolCallResult(
    val isError: Boolean,
    val text: String?,
    val structured: JSONObject?,
    val rawResultJson: String,
)
