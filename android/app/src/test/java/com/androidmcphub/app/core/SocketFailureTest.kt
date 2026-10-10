package com.androidmcphub.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * [SocketFailure] 的类别闭集（Gate 0.3 审计发现项 1）。
 *
 * 这个文件守的是一条**可断言的性质**：无论外面丢进来什么字符串，
 * 能流进日志和界面状态的类别、描述都只可能是本文件里写死的那些。
 */
class SocketFailureTest {

    @Test
    fun `闭集里恰好是十个类别，且值都是小写下划线风格`() {
        assertEquals(10, SocketFailure.ALL.size)
        for (category in SocketFailure.ALL) {
            assertTrue(
                "类别值必须是 [a-z_]+ 的固定字面量：$category",
                Regex("^[a-z][a-z_]*$").matches(category),
            )
        }
        // 十个不同的值
        assertEquals(10, SocketFailure.ALL.toSet().size)
    }

    @Test
    fun `常见的网络异常类名都能分到预期类别`() {
        assertEquals(SocketFailure.DNS, SocketFailure.classify("UnknownHostException"))
        assertEquals(SocketFailure.TIMEOUT, SocketFailure.classify("SocketTimeoutException"))
        assertEquals(SocketFailure.TIMEOUT, SocketFailure.classify("InterruptedIOException"))
        assertEquals(SocketFailure.REFUSED, SocketFailure.classify("ConnectException"))
        assertEquals(SocketFailure.UNREACHABLE, SocketFailure.classify("NoRouteToHostException"))
        assertEquals(SocketFailure.UNREACHABLE, SocketFailure.classify("PortUnreachableException"))
        assertEquals(SocketFailure.TLS, SocketFailure.classify("SSLHandshakeException"))
        assertEquals(SocketFailure.TLS, SocketFailure.classify("SSLPeerUnverifiedException"))
        assertEquals(SocketFailure.TLS, SocketFailure.classify("SSLException"))
        assertEquals(SocketFailure.RESET, SocketFailure.classify("SocketException"))
        assertEquals(SocketFailure.IO, SocketFailure.classify("IOException"))
        assertEquals(SocketFailure.IO, SocketFailure.classify("EOFException"))
        assertEquals(SocketFailure.PROTOCOL, SocketFailure.classify("ProtocolException"))
        assertEquals(SocketFailure.RUNTIME, SocketFailure.classify("IllegalStateException"))
        assertEquals(SocketFailure.UNKNOWN, SocketFailure.classify("SomethingNewException"))
    }

    @Test
    fun `认不出来的类名一律收敛到 unknown，绝不原样返回`() {
        val junk = listOf(
            "",
            " ",
            "未知异常",
            "wss://example.test/phone/ws?token=SECRET",
            // 故意拼出来而不是写成字面量：`worker/test/security.test.ts` 会全文扫描
            // 仓库里有没有 GitHub 令牌形状的字符串，写成字面量会把那条测试弄红。
            // 修法是"别在源码里放这个形状"，不是给它开白名单。
            "ghp_" + "A".repeat(32),
            "UnknownHostException: 无法解析 wss://host/x?token=abc",
            "java.net.UnknownHostException",
            "null",
            "\u0000\u0001",
        )
        for (name in junk) {
            val category = SocketFailure.classify(name)
            assertTrue("「$name」分类出了闭集外的值：$category", category in SocketFailure.ALL)
        }
    }

    @Test
    fun `分类结果里不可能带上冒号、空格或原始片段`() {
        val samples = listOf(
            "UnknownHostException",
            "java.net.UnknownHostException: host=example.test",
            "SSLHandshakeException: trust anchor",
            "随便一个字符串 with spaces and : colons",
        )
        for (name in samples) {
            val category = SocketFailure.classify(name)
            assertTrue("类别里不该有冒号：$category", !category.contains(":"))
            assertTrue("类别里不该有空格：$category", !category.contains(" "))
            assertTrue("类别不该等于输入：$category", category != name)
        }
    }

    @Test
    fun `每个类别都有一句固定的中文描述，且互不相同`() {
        val described = SocketFailure.ALL.associateWith { SocketFailure.describe(it) }
        for ((category, text) in described) {
            assertTrue("$category 的描述为空", text.isNotEmpty())
            assertTrue("$category 的描述不该含冒号或换行：$text", !text.contains(":") && !text.contains("\n"))
        }
        assertEquals("描述必须两两不同，否则界面分不清原因", described.size, described.values.toSet().size)
    }

    @Test
    fun `描述对同一个类别是稳定的（不会拼进任何运行时数据）`() {
        for (category in SocketFailure.ALL) {
            assertEquals(SocketFailure.describe(category), SocketFailure.describe(category))
        }
        // 闭集外的输入也要给出一句固定兜底，而不是抛异常
        assertEquals(SocketFailure.describe(SocketFailure.UNKNOWN), SocketFailure.describe("不存在的类别"))
    }

    @Test
    fun `normalize 只放行闭集内的值`() {
        for (category in SocketFailure.ALL) {
            assertEquals(category, SocketFailure.normalize(category))
        }
        assertEquals(SocketFailure.UNKNOWN, SocketFailure.normalize(""))
        assertEquals(SocketFailure.UNKNOWN, SocketFailure.normalize("DNS_FAILURE"))
        assertEquals(SocketFailure.UNKNOWN, SocketFailure.normalize(" dns_failure"))
        assertEquals(SocketFailure.UNKNOWN, SocketFailure.normalize("dns_failure "))
        assertEquals(SocketFailure.UNKNOWN, SocketFailure.normalize("dns_failure\n"))
        assertEquals(SocketFailure.UNKNOWN, SocketFailure.normalize("token=SECRET"))
    }

    @Test
    fun `分类与描述都不会抛异常：随机输入 fuzz`() {
        val random = Random(20250301)
        repeat(500) {
            val length = random.nextInt(0, 40)
            val name = buildString {
                repeat(length) { append(random.nextInt(32, 127).toChar()) }
            }
            val category = SocketFailure.classify(name)
            assertTrue(category in SocketFailure.ALL)
            assertTrue(SocketFailure.describe(category).isNotEmpty())
            assertTrue(SocketFailure.normalize(category) in SocketFailure.ALL)
        }
    }

    @Test
    fun `不同类别不会被描述成同一句话（抽查最容易混的两对）`() {
        assertNotEquals(SocketFailure.describe(SocketFailure.DNS), SocketFailure.describe(SocketFailure.TIMEOUT))
        assertNotEquals(SocketFailure.describe(SocketFailure.TLS), SocketFailure.describe(SocketFailure.RESET))
    }
}
