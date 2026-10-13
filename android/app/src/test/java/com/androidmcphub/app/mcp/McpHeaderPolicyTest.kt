package com.androidmcphub.app.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [McpHeaderPolicy] 的测试：准入规则 + "secret 的值永远不出现在任何字符串里"。 */
class McpHeaderPolicyTest {

    private val token = "Bearer super-secret-token-value"

    @Test
    fun `普通的自定义请求头通过`() {
        assertNull(McpHeaderPolicy.problem(McpHttpHeader("X-Api-Key", "abc123")))
        assertNull(McpHeaderPolicy.problem(McpHttpHeader("Authorization", token)))
        assertNull(McpHeaderPolicy.problem(McpHttpHeader("X-Custom-1", "")))
    }

    @Test
    fun `空名字被拒绝`() {
        val problem = McpHeaderPolicy.problem(McpHttpHeader("", "x"))
        assertNotNull(problem)
        assertTrue(problem!!.contains("不能为空"))
    }

    @Test
    fun `名字超过 64 字符被拒绝`() {
        val ok = "X".repeat(64)
        val tooLong = "X".repeat(65)
        assertNull(McpHeaderPolicy.problem(McpHttpHeader(ok, "v")))
        assertNotNull(McpHeaderPolicy.problem(McpHttpHeader(tooLong, "v")))
    }

    @Test
    fun `名字含非 token 字符被拒绝`() {
        for (name in listOf("X Api Key", "X-Api:Key", "X-Api\nKey", "X-Api\"Key", "X-Api(Key)")) {
            val problem = McpHeaderPolicy.problem(McpHttpHeader(name, "v"))
            assertNotNull("$name 应该被拒绝", problem)
            assertTrue("$name 的理由应说明非法字符：$problem", problem!!.contains("非法字符"))
        }
    }

    @Test
    fun `受控头被拒绝且忽略大小写`() {
        val forbidden = listOf(
            "Host",
            "host",
            "Content-Length",
            "connection",
            "Transfer-Encoding",
            "Upgrade",
            "Expect",
            "TE",
            "Trailer",
            "Mcp-Session-Id",
            "MCP-Protocol-Version",
            "Proxy-Authorization",
            "proxy-connection",
        )
        for (name in forbidden) {
            val problem = McpHeaderPolicy.problem(McpHttpHeader(name, "v"))
            assertNotNull("$name 应该被拒绝", problem)
            assertTrue("$name 的理由应说明由客户端管理：$problem", problem!!.contains("由客户端自己管理"))
        }
    }

    @Test
    fun `值里的换行或空字符被拒绝`() {
        for (value in listOf("a\r\nX-Injected: 1", "a\nb", "a\rb", "a\u0000b")) {
            val problem = McpHeaderPolicy.problem(McpHttpHeader("X-Test", value))
            assertNotNull("值 $value 应该被拒绝", problem)
            assertTrue(problem!!.contains("不能包含换行或空字符"))
        }
    }

    @Test
    fun `值超过 2048 字符被拒绝`() {
        assertNull(McpHeaderPolicy.problem(McpHttpHeader("X-Test", "a".repeat(2048))))
        assertNotNull(McpHeaderPolicy.problem(McpHttpHeader("X-Test", "a".repeat(2049))))
    }

    @Test
    fun `safeHeaderSummary 对 secret 头只显示已设置`() {
        val summary = McpHeaderPolicy.safeHeaderSummary(
            McpHttpHeader("Authorization", token, secret = true),
        )
        assertEquals("Authorization: <已设置>", summary)
        assertFalse("摘要里绝不允许出现 secret 的值", summary.contains("super-secret-token-value"))
    }

    @Test
    fun `safeHeaderSummary 对非 secret 头显示值`() {
        val summary = McpHeaderPolicy.safeHeaderSummary(McpHttpHeader("X-Tenant", "acme"))
        assertEquals("X-Tenant: acme", summary)
    }

    @Test
    fun `secret 头即使值是空的也只显示已设置`() {
        assertEquals(
            "Authorization: <已设置>",
            McpHeaderPolicy.safeHeaderSummary(McpHttpHeader("Authorization", "", secret = true)),
        )
    }

    @Test
    fun `data class 的 toString 也不会泄漏 secret 值`() {
        val header = McpHttpHeader("Authorization", token, secret = true)
        assertFalse("toString 里出现了 secret 值", header.toString().contains(token))
        assertTrue(header.toString().contains("<已设置>"))
    }

    @Test
    fun `toString 与 safeHeaderSummary 结果一致`() {
        val header = McpHttpHeader("X-Tenant", "acme")
        assertEquals(McpHeaderPolicy.safeHeaderSummary(header), header.toString())
    }
}
