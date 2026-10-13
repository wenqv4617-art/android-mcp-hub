package com.androidmcphub.app.mcp.transport

import com.androidmcphub.app.mcp.McpClientException
import com.androidmcphub.app.mcp.McpErrorKind
import com.androidmcphub.app.mcp.McpHttpHeader
import com.androidmcphub.app.mcp.model.McpServerConfig
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * 跨进程互操作：Android 的 [StreamableHttpTransport] 对一台**真实**的 MCP 服务器。
 *
 * 为什么非要有这个测试：
 *   `StreamableHttpTransportTest` 用 MockWebServer 回放**我们自己写的**响应 ——
 *   它能证明"代码按我理解的协议走"，但证明不了"我理解的协议就是别人实现的协议"。
 *   真实服务器会做的事，MockWebServer 不会做：自己协商协议版本、给出会话 id、
 *   用 `nextCursor` 分页、把 `inputSchema` 原样吐回来、以及**用真的 HTTP 会话状态**
 *   约束每一条请求。
 *
 *   所以这里用 `tools/mcp-test-server/server.mjs`（仓库里零依赖的 Node 实现，
 *   不是替身）起一台真服务器，再让 Kotlin 客户端去连它。两侧是不同语言、
 *   不同人写的协议栈 —— 只有它们对上了，才叫"真的实现了 MCP 客户端"。
 *
 * ⚠ 这里跑在 JVM 上，不是 Android 运行时；真实的手机 / 模拟器验收仍然是独立的一项
 * （本环境没有模拟器）。跨进程互操作解决的是"协议对不对"，不是"设备上跑不跑得起来"。
 */
class StreamableHttpInteropTest {

    private val processes = mutableListOf<Process>()
    private lateinit var repoRoot: File
    private var baseUrl: String = ""
    private val transports = mutableListOf<McpTransport>()

    @Before
    fun startRealServer() {
        repoRoot = findRepoRoot()
        assumeTrue(
            "找不到 tools/mcp-test-server/server.mjs，跳过互操作测试",
            File(repoRoot, "tools/mcp-test-server/server.mjs").isFile,
        )
        assumeTrue("没有 node，跳过互操作测试", which("node") != null)

        // 每页 3 个工具：8 个工具必然要翻页，分页逻辑没写对就拿不全。
        baseUrl = startServer("--name", "interop-server", "--page-size", "3")
    }

    @After
    fun stopServer() {
        transports.forEach { runCatching { it.close() } }
        processes.forEach { process ->
            process.destroy()
            if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly()
        }
    }

    /** 起一台真实服务器，返回它真正监听的地址。 */
    private fun startServer(vararg args: String): String {
        val node = which("node")!!
        val process = ProcessBuilder(
            listOf(node.absolutePath, File(repoRoot, "tools/mcp-test-server/server.mjs").absolutePath, "--port", "0") + args,
        )
            .directory(repoRoot)
            .redirectErrorStream(true)
            .start()
        processes += process
        return readListeningUrl(process)
    }

    private fun transportFor(
        name: String = "interop",
        url: String = baseUrl,
        headers: List<McpHttpHeader> = emptyList(),
    ): McpTransport {
        val transport = McpTransportFactory.create(
            McpServerConfig(id = "srv_interop", name = name, url = url, headers = headers),
        )
        transports += transport
        return transport
    }

    @Test
    fun `握手拿到服务器自报身份与协商出的协议版本`() {
        val handshake = transportFor().connect()

        // 协议版本是**协商**出来的，不是客户端指定的：必须落在双方都认的集合里。
        assertTrue(
            "协议版本应属于受支持集合，实际=${handshake.protocolVersion}",
            handshake.protocolVersion in setOf("2025-06-18", "2025-03-26", "2024-11-05"),
        )
        assertEquals("interop-server", handshake.serverName)
        assertNotNull("真实服务器会发会话 id", handshake.sessionId)
    }

    @Test
    fun `分页取回全部工具，字段逐字来自服务器`() {
        val transport = transportFor()
        transport.connect()
        val tools = transport.listTools()

        // 服务器有 8 个工具、每页 3 个 → 客户端必须跟随 nextCursor 翻到底。
        assertEquals(
            "工具名=" + tools.map { it.name }.sorted(),
            8,
            tools.size,
        )
        val echo = tools.single { it.name == "echo_text" }
        assertEquals("回显文本", echo.title)
        assertEquals("把输入原样回显。测试用，无副作用。", echo.description)
        val schema = echo.inputSchema
        assertNotNull("真实服务器给了 inputSchema", schema)
        assertEquals("object", schema!!.optString("type"))
        assertEquals("string", schema.optJSONObject("properties")!!.optJSONObject("text")!!.optString("type"))
        assertEquals("text", schema.optJSONArray("required")!!.getString(0))

        // 没有 annotations 的工具必须是 null，而不是被我们补一个"看起来安全"的空对象。
        assertEquals(null, tools.single { it.name == "no_annotations" }.annotations)
        // 有副作用的工具必须如实带上 destructiveHint（授权层靠它挡人）。
        assertEquals(
            true,
            tools.single { it.name == "destructive_write" }.annotations!!.optBoolean("destructiveHint"),
        )
    }

    @Test
    fun `调用真实工具并把文本与结构化内容都带回来`() {
        val transport = transportFor()
        transport.connect()

        val echo = transport.callTool("echo_text", JSONObject().put("text", "interop-你好"))
        assertTrue("不该是工具级错误", !echo.isError)
        assertEquals("echo:interop-你好", echo.text)
        assertEquals("interop-你好", echo.structured!!.optString("echoed"))
        assertEquals("interop-server", echo.structured!!.optString("server"))

        // 服务器自己算的算式 —— 这个结果客户端编不出来。
        val sum = transport.callTool("sum_numbers", JSONObject().put("a", 2).put("b", 40))
        assertEquals("2 + 40 = 42", sum.text)
        assertEquals(42, sum.structured!!.optInt("sum"))
    }

    @Test
    fun `第三方工具报的 isError 不会被当成传输失败吞掉`() {
        val transport = transportFor()
        transport.connect()

        val boom = transport.callTool("boom", JSONObject())
        assertTrue("工具级失败要如实标记", boom.isError)
        assertEquals("boom: 这是工具自己报的失败", boom.text)
    }

    @Test
    fun `服务器上不存在的工具是协议错误，不是空结果`() {
        val transport = transportFor()
        transport.connect()

        val error = runCatching { transport.callTool("no_such_tool", JSONObject()) }.exceptionOrNull()
        assertNotNull("未知工具必须抛错，而不是回一个空结果", error)
    }

    @Test
    fun `自定义请求头真的上了线：没有它服务器就拒绝`() {
        // 这台服务器要求 X-API-Key: secret，缺了直接 401。
        val gated = startServer("--name", "gated-server", "--require-header", "X-API-Key=secret")

        // 不带凭据：连不上，而且错在授权上（不是"服务器挂了"）。
        val anon = transportFor(url = gated)
        val error = runCatching { anon.connect() }.exceptionOrNull()
        assertTrue("缺凭据应该报 NOT_AUTHORIZED，实际=$error", error is McpClientException)
        assertEquals(McpErrorKind.NOT_AUTHORIZED, (error as McpClientException).kind)

        // 带上凭据：握手、列工具、调用都要能过 —— 这才证明头是真发出去了。
        val authorized = transportFor(
            url = gated,
            headers = listOf(McpHttpHeader("X-API-Key", "secret", secret = true)),
        )
        assertEquals("gated-server", authorized.connect().serverName)
        assertTrue(authorized.listTools().any { it.name == "echo_text" })
        assertEquals("echo:header-ok", authorized.callTool("echo_text", JSONObject().put("text", "header-ok")).text)
    }

    // ---- 工具方法 ---------------------------------------------------------

    /** 从仓库根往下找 `tools/mcp-test-server/server.mjs`（Gradle 的工作目录不保证）。 */
    private fun findRepoRoot(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            if (File(dir, "tools/mcp-test-server/server.mjs").isFile) return dir
            dir = dir.parentFile
        }
        return File(".").absoluteFile
    }

    private fun which(command: String): File? =
        (System.getenv("PATH") ?: "").split(File.pathSeparator).asSequence()
            .map { File(it, command) }
            .firstOrNull { it.canExecute() }

    /** 读服务器的启动日志，拿到它**真正绑上**的端口（`--port 0` 由内核挑）。 */
    private fun readListeningUrl(process: Process): String {
        val reader = BufferedReader(InputStreamReader(process.inputStream))
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            val line = reader.readLine() ?: break
            val match = Regex("listening on (http://\\S+)").find(line)
            if (match != null) return match.groupValues[1]
        }
        throw AssertionError("等不到真实 MCP 服务器监听地址（进程还活着=${process.isAlive}）")
    }
}
