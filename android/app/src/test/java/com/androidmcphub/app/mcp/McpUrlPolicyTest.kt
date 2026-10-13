package com.androidmcphub.app.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [McpUrlPolicy] 的穷举测试。
 *
 * 这个文件值得写得啰嗦一点：地址策略是"用户配置"和"我们真的会往哪儿发请求头"之间
 * 唯一的一道闸门。漏掉一个 scheme、漏掉一种写法，代价是凭据被明文发到局域网/公网上。
 */
class McpUrlPolicyTest {

    private fun ok(raw: String): McpUrlCheck.Ok {
        val result = McpUrlPolicy.validate(raw)
        assertTrue("期望地址通过校验：$raw，实际是 $result", result is McpUrlCheck.Ok)
        return result as McpUrlCheck.Ok
    }

    private fun rejected(raw: String): String {
        val result = McpUrlPolicy.validate(raw)
        assertTrue("期望地址被拒绝：$raw，实际是 $result", result is McpUrlCheck.Rejected)
        return (result as McpUrlCheck.Rejected).reason
    }

    // ---- 空 / 非法绝对地址 ----

    @Test
    fun `空地址被拒绝并提示填写`() {
        assertEquals(McpUrlPolicy.REASON_EMPTY, rejected(""))
    }

    @Test
    fun `只有空白的地址被拒绝`() {
        assertEquals(McpUrlPolicy.REASON_EMPTY, rejected("   \t  "))
    }

    @Test
    fun `没有 scheme 的相对地址被拒绝`() {
        assertEquals(McpUrlPolicy.REASON_NOT_ABSOLUTE, rejected("example.com/mcp"))
        assertEquals(McpUrlPolicy.REASON_NOT_ABSOLUTE, rejected("//example.com/mcp"))
        assertEquals(McpUrlPolicy.REASON_NOT_ABSOLUTE, rejected("/mcp"))
    }

    @Test
    fun `没有主机名被拒绝`() {
        assertEquals(McpUrlPolicy.REASON_NO_HOST, rejected("https://"))
        assertEquals(McpUrlPolicy.REASON_NO_HOST, rejected("https:///mcp"))
        assertEquals(McpUrlPolicy.REASON_NO_HOST, rejected("https://?q=1"))
        assertEquals(McpUrlPolicy.REASON_NO_HOST, rejected("https://#section"))
    }

    // ---- scheme ----

    @Test
    fun `非 http 协议被拒绝`() {
        for (raw in listOf(
            "ws://example.com/mcp",
            "wss://example.com/mcp",
            "file:///etc/passwd",
            "ftp://example.com/mcp",
            "javascript://example.com/%0aalert(1)",
        )) {
            val reason = rejected(raw)
            assertTrue("$raw 的理由应该说明只支持 https，实际是：$reason", reason.contains("只支持 https"))
        }
    }

    @Test
    fun `https 允许任意主机且不是明文`() {
        val result = ok("https://mcp.example.com/mcp")
        assertEquals("https", result.scheme)
        assertEquals("mcp.example.com", result.host)
        assertEquals(443, result.port)
        assertFalse(result.cleartext)
        assertFalse(result.loopback)
    }

    // ---- userinfo ----

    @Test
    fun `地址里写用户名密码被拒绝`() {
        assertEquals(McpUrlPolicy.REASON_USERINFO, rejected("https://user:pass@mcp.example.com/mcp"))
        assertEquals(McpUrlPolicy.REASON_USERINFO, rejected("https://token@mcp.example.com/mcp"))
    }

    // ---- 控制字符 ----

    @Test
    fun `含控制字符或空格的地址被拒绝`() {
        assertEquals(McpUrlPolicy.REASON_CONTROL, rejected("https://mcp.example.com/a b"))
        assertEquals(McpUrlPolicy.REASON_CONTROL, rejected("https://mcp.example.com\r\nX-Injected: 1"))
        assertEquals(McpUrlPolicy.REASON_CONTROL, rejected("https://mcp.example.com/mcp\n/x"))
        assertEquals(McpUrlPolicy.REASON_CONTROL, rejected("https://mcp.example.com\t/x"))
        assertEquals(McpUrlPolicy.REASON_CONTROL, rejected("https://mcp.example.com\u0000"))
    }

    // ---- fragment ----

    @Test
    fun `含 fragment 的地址被拒绝`() {
        assertEquals(McpUrlPolicy.REASON_FRAGMENT, rejected("https://mcp.example.com/mcp#token=abc"))
    }

    // ---- 明文 http ----

    @Test
    fun `明文 http 允许本机回环地址`() {
        for (raw in listOf("http://127.0.0.1:8765/mcp", "http://localhost/mcp", "http://LOCALHOST:8080")) {
            val result = ok(raw)
            assertTrue("$raw 应该是明文", result.cleartext)
            assertTrue("$raw 应该是回环", result.loopback)
        }
    }

    @Test
    fun `方括号形式的 IPv6 回环地址被接受且主机名不带方括号`() {
        val result = ok("http://[::1]:9000/mcp")
        assertEquals("::1", result.host)
        assertEquals(9000, result.port)
        assertTrue(result.cleartext)
        assertTrue(result.loopback)
    }

    @Test
    fun `明文 http 拒绝局域网与公网地址`() {
        for (raw in listOf("http://192.168.1.5:8765/mcp", "http://10.0.0.7/mcp", "http://mcp.example.com/mcp")) {
            assertEquals("$raw 应被明文策略拒绝", McpUrlPolicy.REASON_CLEARTEXT, rejected(raw))
        }
    }

    @Test
    fun `https 的回环地址也是回环但不属于明文`() {
        val result = ok("https://127.0.0.1:8443/mcp")
        assertTrue(result.loopback)
        assertFalse(result.cleartext)
    }

    // ---- 端口 ----

    @Test
    fun `缺省端口按 scheme 取 443 与 80`() {
        assertEquals(443, ok("https://mcp.example.com").port)
        assertEquals(80, ok("http://localhost").port)
    }

    @Test
    fun `显式端口被保留并写回地址`() {
        val result = ok("https://mcp.example.com:8443/mcp")
        assertEquals(8443, result.port)
        assertEquals("https://mcp.example.com:8443/mcp", result.url)
    }

    @Test
    fun `非法端口被拒绝`() {
        assertEquals(McpUrlPolicy.REASON_BAD_PORT, rejected("https://mcp.example.com:0/mcp"))
        assertEquals(McpUrlPolicy.REASON_BAD_PORT, rejected("https://mcp.example.com:65536/mcp"))
        assertEquals(McpUrlPolicy.REASON_BAD_PORT, rejected("https://mcp.example.com:abc/mcp"))
        assertEquals(McpUrlPolicy.REASON_BAD_PORT, rejected("https://mcp.example.com:/mcp"))
    }

    // ---- 规范化 ----

    @Test
    fun `路径被保留且空路径不被补成斜杠`() {
        assertEquals("https://mcp.example.com/mcp", ok("https://mcp.example.com/mcp").url)
        assertEquals("https://mcp.example.com", ok("https://mcp.example.com").url)
    }

    @Test
    fun `查询串被保留`() {
        assertEquals("https://mcp.example.com/mcp?v=1", ok("https://mcp.example.com/mcp?v=1").url)
    }

    @Test
    fun `scheme 大小写被规范化`() {
        assertEquals("https", ok("HTTPS://mcp.example.com/mcp").scheme)
        assertEquals("https://mcp.example.com/mcp", ok("HTTPS://mcp.example.com/mcp").url)
    }

    @Test
    fun `首尾空白被去掉`() {
        assertEquals("https://mcp.example.com/mcp", ok("  https://mcp.example.com/mcp  ").url)
    }

    // ---- 辅助函数 ----

    @Test
    fun `isLoopbackHost 只认三种回环写法`() {
        assertTrue(McpUrlPolicy.isLoopbackHost("127.0.0.1"))
        assertTrue(McpUrlPolicy.isLoopbackHost("localhost"))
        assertTrue(McpUrlPolicy.isLoopbackHost("LocalHost"))
        assertTrue(McpUrlPolicy.isLoopbackHost("::1"))
        assertTrue(McpUrlPolicy.isLoopbackHost("[::1]"))
        assertFalse(McpUrlPolicy.isLoopbackHost("127.0.0.2"))
        assertFalse(McpUrlPolicy.isLoopbackHost("192.168.1.1"))
        assertFalse(McpUrlPolicy.isLoopbackHost("localhost.example.com"))
        assertFalse(McpUrlPolicy.isLoopbackHost(""))
    }

    @Test
    fun `hostOf 宽松解析主机名`() {
        assertEquals("mcp.example.com", McpUrlPolicy.hostOf("https://mcp.example.com:8443/mcp"))
        assertEquals("127.0.0.1", McpUrlPolicy.hostOf("http://127.0.0.1:8765"))
        assertEquals("::1", McpUrlPolicy.hostOf("http://[::1]:8765/mcp"))
        assertEquals("mcp.example.com", McpUrlPolicy.hostOf("https://user:pass@mcp.example.com/mcp"))
    }

    @Test
    fun `hostOf 解析失败返回空串而不是抛异常`() {
        assertEquals("", McpUrlPolicy.hostOf(""))
        assertEquals("", McpUrlPolicy.hostOf("mcp.example.com"))
        assertEquals("", McpUrlPolicy.hostOf("ws://mcp.example.com"))
        assertEquals("", McpUrlPolicy.hostOf("https://mcp example.com"))
    }
}
