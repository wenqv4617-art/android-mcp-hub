package com.androidmcphub.app.mcp.model

import com.androidmcphub.app.mcp.McpHttpHeader

/** 传输层类型。目前只有 [StreamableHttp] 是真实现了的，见 `McpTransportFactory`。 */
enum class McpTransportType(val label: String) {
    StreamableHttp("Streamable HTTP"),
    LegacyHttpSse("旧版 HTTP+SSE"),
}

/**
 * 一台 MCP 服务器的用户配置。
 *
 * 这是**内存里**的形态：secret 请求头的值就在 [headers] 里（明文）。
 * 落盘时值会被拆到加密的 blob 里，见 `McpServerStore`。
 *
 * @param id 本地生成的稳定标识（不依赖 URL：用户改地址不该丢授权记录）。
 * @param group 界面上的分组名，空串表示未分组。
 */
data class McpServerConfig(
    val id: String,
    val name: String,
    val group: String = "",
    val url: String,
    val transport: McpTransportType = McpTransportType.StreamableHttp,
    val headers: List<McpHttpHeader> = emptyList(),
    val enabled: Boolean = true,
    val createdAtMs: Long = 0L,
    val updatedAtMs: Long = 0L,
)
