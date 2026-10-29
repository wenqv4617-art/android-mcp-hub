package com.androidmcphub.app.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 网关地址准入：只允许 HTTPS 根地址（本机回环例外），且不携带任何凭据/查询串。 */
class GatewayUrlsTest {

    @Test
    fun `https 根地址被规范化为不带尾斜杠`() {
        assertEquals(
            "https://android-mcp-hub.causticrry.workers.dev",
            GatewayUrls.normalizeBase("  https://android-mcp-hub.causticrry.workers.dev/  ").getOrThrow(),
        )
    }

    @Test
    fun `本机回环允许 http`() {
        assertEquals("http://127.0.0.1:8787", GatewayUrls.normalizeBase("http://127.0.0.1:8787").getOrThrow())
        assertEquals("http://localhost:8787", GatewayUrls.normalizeBase("http://localhost:8787").getOrThrow())
    }

    @Test
    fun `远端 http 被拒绝`() {
        val result = GatewayUrls.normalizeBase("http://hub.example.com")
        assertTrue(result.isFailure)
        assertEquals(
            "只有本机回环地址可以使用 http",
            (result.exceptionOrNull() as GatewayAddressException).userText,
        )
    }

    @Test
    fun `带路径的地址被拒绝`() {
        val result = GatewayUrls.normalizeBase("https://hub.example.com/mcp")
        assertTrue(result.isFailure)
    }

    @Test
    fun `带用户名密码的地址被拒绝`() {
        val result = GatewayUrls.normalizeBase("https://user:pass@hub.example.com")
        assertTrue(result.isFailure)
        assertTrue((result.exceptionOrNull() as GatewayAddressException).userText.contains("用户名"))
    }

    @Test
    fun `带查询串或片段的地址被拒绝`() {
        assertTrue(GatewayUrls.normalizeBase("https://hub.example.com?token=x").isFailure)
        assertTrue(GatewayUrls.normalizeBase("https://hub.example.com#x").isFailure)
    }

    @Test
    fun `空地址与非 http 协议被拒绝`() {
        assertTrue(GatewayUrls.normalizeBase("   ").isFailure)
        assertTrue(GatewayUrls.normalizeBase("ftp://hub.example.com").isFailure)
    }

    @Test
    fun `端点拼接固定`() {
        val base = "https://hub.example.com"
        assertEquals("$base/gateway/pair", GatewayUrls.pair(base))
        assertEquals("$base/gateway/android", GatewayUrls.admin(base))
        assertEquals("$base/mcp", GatewayUrls.mcp(base))
    }

    @Test
    fun `错误码映射为固定中文且不回显响应体`() {
        assertEquals("配对口令不正确", gatewayErrorText("pairing_denied", 401))
        assertEquals("工具定义已变化，请重新发现后再授权", gatewayErrorText("definition_changed", 409))
        assertEquals("请求失败（HTTP 500）", gatewayErrorText(null, 500))
        assertTrue(gatewayErrorText("something_else", 500).contains("something_else"))
    }
}
