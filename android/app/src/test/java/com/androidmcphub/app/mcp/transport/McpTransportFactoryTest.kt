package com.androidmcphub.app.mcp.transport

import com.androidmcphub.app.mcp.McpClientException
import com.androidmcphub.app.mcp.McpErrorKind
import com.androidmcphub.app.mcp.McpHttpHeader
import com.androidmcphub.app.mcp.model.McpServerConfig
import com.androidmcphub.app.mcp.model.McpTransportType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** [McpTransportFactory] 的测试：只造传输，不发任何请求。 */
class McpTransportFactoryTest {

    private fun config(
        transport: McpTransportType = McpTransportType.StreamableHttp,
        url: String = "https://mcp.example.com/mcp",
    ): McpServerConfig = McpServerConfig(
        id = "server-1",
        name = "示例服务器",
        url = url,
        transport = transport,
        headers = listOf(McpHttpHeader("X-Tenant", "team-a")),
    )

    @Test
    fun `StreamableHttp 会造出 StreamableHttpTransport`() {
        val transport = McpTransportFactory.create(config())

        assertTrue(transport is StreamableHttpTransport)
        assertEquals("server-1", transport.serverId)
    }

    @Test
    fun `默认传输类型是 Streamable HTTP`() {
        val config = McpServerConfig(id = "server-1", name = "示例", url = "https://mcp.example.com/mcp")

        assertEquals(McpTransportType.StreamableHttp, config.transport)
        assertTrue(McpTransportFactory.create(config) is StreamableHttpTransport)
    }

    @Test
    fun `旧版 HTTP+SSE 明确报错而不是假装支持`() {
        try {
            McpTransportFactory.create(config(transport = McpTransportType.LegacyHttpSse))
            throw AssertionError("期望抛 McpClientException，但没有")
        } catch (error: McpClientException) {
            assertEquals(McpErrorKind.PROTOCOL_ERROR, error.kind)
            assertEquals("旧版 HTTP+SSE 传输还没实现", error.message)
        }
    }

    @Test
    fun `回环 http 地址也能造出传输`() {
        val transport = McpTransportFactory.create(config(url = "http://127.0.0.1:8765/mcp"))

        assertTrue(transport is StreamableHttpTransport)
        assertSame(McpTransportType.StreamableHttp, config().transport)
    }
}
