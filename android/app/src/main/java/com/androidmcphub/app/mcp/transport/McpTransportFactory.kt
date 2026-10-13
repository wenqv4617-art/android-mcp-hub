package com.androidmcphub.app.mcp.transport

import com.androidmcphub.app.mcp.McpClientException
import com.androidmcphub.app.mcp.McpErrorKind
import com.androidmcphub.app.mcp.McpLimits
import com.androidmcphub.app.mcp.model.McpServerConfig
import com.androidmcphub.app.mcp.model.McpTransportType

/**
 * 按配置造传输。
 *
 * 旧版 HTTP+SSE（MCP 2024-11-05 时代的 transport）在这里**明确报错**，不假装支持：
 * 那种传输的连接建立方式、事件语义、错误处理全都不同，用一个假的实现糊过去，
 * 用户看到的会是"能连上一半然后工具列表是空的"，比直接说"没实现"难排错得多。
 */
object McpTransportFactory {

    fun create(config: McpServerConfig, limits: McpLimits = McpLimits()): McpTransport =
        when (config.transport) {
            McpTransportType.StreamableHttp -> StreamableHttpTransport(
                serverId = config.id,
                url = config.url,
                headers = config.headers,
                client = StreamableHttpTransport.defaultMcpHttpClient(limits),
                limits = limits,
            )

            McpTransportType.LegacyHttpSse -> throw McpClientException(
                McpErrorKind.PROTOCOL_ERROR,
                "旧版 HTTP+SSE 传输还没实现",
            )
        }
}
