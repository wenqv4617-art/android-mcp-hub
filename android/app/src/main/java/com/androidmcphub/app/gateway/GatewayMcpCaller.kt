package com.androidmcphub.app.gateway

import com.androidmcphub.app.mcp.McpClientException
import com.androidmcphub.app.mcp.McpHttpHeader
import com.androidmcphub.app.mcp.model.McpServerConfig
import com.androidmcphub.app.mcp.model.McpTransportType
import com.androidmcphub.app.mcp.transport.McpTransport
import com.androidmcphub.app.mcp.transport.McpTransportFactory
import org.json.JSONObject

/** 通过网关调用工具（MCP 客户端侧）。抽成接口便于 JVM 单测。 */
interface GatewayCaller {
    fun call(
        baseUrl: String,
        clientToken: String,
        gatewayToolName: String,
        arguments: JSONObject,
        confirmation: String?,
    ): GatewayCallResult
}

/**
 * 真实现：把网关当成一台普通的 Streamable HTTP MCP 服务器。
 *
 * 这不是"另写一套 MCP 引擎"——它复用了与「本机直连」完全相同的
 * [McpTransportFactory] / Streamable HTTP 客户端。区别只有两点：
 * Authorization 用配对换来的 `hub_client_*`，工具名用网关的 `ext_*`。
 *
 * 令牌只放进请求头，且 header 标记为 secret（其 `toString()` 会脱敏）。
 * 每次调用后立即关闭会话，避免跨操作复用会话。
 */
class TransportGatewayCaller(
    private val factory: (McpServerConfig) -> McpTransport = { McpTransportFactory.create(it) },
) : GatewayCaller {

    override fun call(
        baseUrl: String,
        clientToken: String,
        gatewayToolName: String,
        arguments: JSONObject,
        confirmation: String?,
    ): GatewayCallResult {
        val config = McpServerConfig(
            id = "gateway",
            name = "Cloud gateway",
            url = GatewayUrls.mcp(baseUrl),
            transport = McpTransportType.StreamableHttp,
            headers = listOf(
                McpHttpHeader(name = "Authorization", value = "Bearer $clientToken", secret = true),
            ),
            enabled = true,
        )
        val transport = factory(config)
        return try {
            transport.connect()
            val meta = confirmation?.let { JSONObject().put("hub/confirmation", it) }
            val result = transport.callToolWithMeta(gatewayToolName, arguments, meta)
            GatewayCallResult(isError = result.isError, text = result.text)
        } catch (error: McpClientException) {
            val status = error.httpStatus?.let { "（HTTP $it）" }.orEmpty()
            GatewayCallResult(isError = true, text = "${error.kind.label}$status")
        } finally {
            runCatching { transport.close() }
        }
    }
}
