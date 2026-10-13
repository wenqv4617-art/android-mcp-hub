package com.androidmcphub.app.mcp.transport

import com.androidmcphub.app.mcp.McpClientException
import com.androidmcphub.app.mcp.McpErrorKind
import com.androidmcphub.app.mcp.McpHttpHeader
import com.androidmcphub.app.mcp.McpLimits
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * [StreamableHttpTransport] 的测试，全部用 MockWebServer 打真实的 HTTP。
 *
 * 每个用例的请求顺序都是（除非用例自己说明）：initialize → notifications/initialized → 业务请求。
 * 每个"期望成功"的用例都必须把这两条响应都排好队 —— QueueDispatcher 在没有响应时会**挂住**请求，
 * 漏排一条就会让用例超时而不是失败得明明白白。
 */
class StreamableHttpTransportTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // ---- 脚手架 ----

    private fun transport(
        limits: McpLimits = McpLimits(),
        headers: List<McpHttpHeader> = emptyList(),
    ): StreamableHttpTransport = StreamableHttpTransport(
        serverId = "server-1",
        url = server.url("/mcp").toString(),
        headers = headers,
        client = StreamableHttpTransport.defaultMcpHttpClient(limits),
        limits = limits,
    )

    private fun jsonResponse(body: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private fun resultFor(id: Long, resultJson: String): MockResponse =
        jsonResponse("""{"jsonrpc":"2.0","id":$id,"result":$resultJson}""")

    private fun errorFor(id: Long, code: Int, message: String): MockResponse =
        jsonResponse("""{"jsonrpc":"2.0","id":$id,"error":{"code":$code,"message":"$message"}}""")

    private fun initializeResponse(
        id: Long = 1L,
        protocolVersion: String? = "2025-06-18",
        sessionId: String? = null,
        serverInfo: String? = """{"name":"demo","version":"1.2.3"}""",
    ): MockResponse {
        val result = JSONObject()
        if (protocolVersion != null) result.put("protocolVersion", protocolVersion)
        if (serverInfo != null) result.put("serverInfo", JSONObject(serverInfo))
        val response = jsonResponse("""{"jsonrpc":"2.0","id":$id,"result":$result}""")
        if (sessionId != null) response.setHeader("Mcp-Session-Id", sessionId)
        return response
    }

    private fun accepted(): MockResponse = MockResponse().setResponseCode(202).setBody("")

    private fun takeRequest(): RecordedRequest =
        server.takeRequest(2, TimeUnit.SECONDS) ?: throw AssertionError("没有等到请求")

    private fun bodyOf(request: RecordedRequest): JSONObject = JSONObject(request.body.readUtf8())

    private fun failure(block: () -> Unit): McpClientException {
        try {
            block()
        } catch (error: McpClientException) {
            return error
        }
        throw AssertionError("期望抛 McpClientException，但没有")
    }

    // ---- 默认客户端 ----

    @Test
    fun `默认客户端不跟随重定向且超时可控`() {
        val limits = McpLimits(connectTimeoutMs = 1234, readTimeoutMs = 4321)
        val client = StreamableHttpTransport.defaultMcpHttpClient(limits)

        assertEquals(1234L, client.connectTimeoutMillis.toLong())
        assertEquals(4321L, client.readTimeoutMillis.toLong())
        assertEquals(15_000L, client.writeTimeoutMillis.toLong())
        assertFalse("绝不跟随重定向", client.followRedirects)
        assertFalse("绝不跟随重定向", client.followSslRedirects)
        assertFalse("重试由上层决定，底层不偷偷重发", client.retryOnConnectionFailure)
        assertEquals("不能自定义主机名校验", okhttp3.OkHttpClient().hostnameVerifier, client.hostnameVerifier)
    }

    // ---- 初始化 ----

    @Test
    fun `connect 发出 initialize 请求并带上协议头与自定义头`() {
        server.enqueue(initializeResponse())
        server.enqueue(accepted())

        val handshake = transport(
            headers = listOf(
                McpHttpHeader("X-Tenant", "team-a"),
                McpHttpHeader("X-Off", "1", enabled = false),
            ),
        ).connect()

        assertEquals("2025-06-18", handshake.protocolVersion)
        assertEquals("demo", handshake.serverName)
        assertEquals("1.2.3", handshake.serverVersion)
        assertNull(handshake.sessionId)

        val init = takeRequest()
        assertEquals("POST", init.method)
        assertEquals("/mcp", init.path)
        assertEquals("application/json; charset=utf-8", init.getHeader("content-type"))
        assertEquals("application/json, text/event-stream", init.getHeader("accept"))
        assertEquals("team-a", init.getHeader("X-Tenant"))
        assertNull("disabled 的头不该发出去", init.getHeader("X-Off"))
        assertNull("服务器还没给 session id", init.getHeader("Mcp-Session-Id"))

        val message = bodyOf(init)
        assertEquals("2.0", message.getString("jsonrpc"))
        assertEquals(1L, message.getLong("id"))
        assertEquals("initialize", message.getString("method"))
        val params = message.getJSONObject("params")
        assertEquals("2025-06-18", params.getString("protocolVersion"))
        assertEquals(0, params.getJSONObject("capabilities").length())
        assertEquals("android-mcp-hub", params.getJSONObject("clientInfo").getString("name"))
        assertTrue(params.getJSONObject("clientInfo").getString("version").isNotEmpty())
    }

    @Test
    fun `connect 之后会发 notifications_initialized 且不带 id`() {
        server.enqueue(initializeResponse())
        server.enqueue(accepted())

        transport().connect()

        takeRequest()
        val notification = takeRequest()
        assertEquals("POST", notification.method)
        assertEquals("/mcp", notification.path)
        val message = bodyOf(notification)
        assertEquals("2.0", message.getString("jsonrpc"))
        assertEquals("notifications/initialized", message.getString("method"))
        assertFalse("通知不该带 id", message.has("id"))
    }

    @Test
    fun `通知返回 500 也不影响连接成功`() {
        server.enqueue(initializeResponse())
        server.enqueue(MockResponse().setResponseCode(500).setBody("服务器不喜欢这条通知"))

        assertEquals("2025-06-18", transport().connect().protocolVersion)
    }

    @Test
    fun `没有 serverInfo 时服务器名与版本为空`() {
        server.enqueue(initializeResponse(serverInfo = null))
        server.enqueue(accepted())

        val handshake = transport().connect()
        assertNull(handshake.serverName)
        assertNull(handshake.serverVersion)
        assertEquals("2025-06-18", handshake.protocolVersion)
    }

    @Test
    fun `initialize 没有返回协议版本时报 PROTOCOL_ERROR`() {
        server.enqueue(initializeResponse(protocolVersion = null))
        server.enqueue(accepted())

        val error = failure { transport().connect() }
        assertEquals(McpErrorKind.PROTOCOL_ERROR, error.kind)
        assertTrue(error.message!!.contains("协议版本"))
    }

    @Test
    fun `响应 id 与请求不匹配时报 PROTOCOL_ERROR`() {
        server.enqueue(initializeResponse(id = 99L))
        server.enqueue(accepted())

        val error = failure { transport().connect() }
        assertEquals(McpErrorKind.PROTOCOL_ERROR, error.kind)
        assertTrue(error.message!!.contains("id"))
    }

    @Test
    fun `请求 id 从 1 开始自增`() {
        server.enqueue(initializeResponse())
        server.enqueue(accepted())
        server.enqueue(resultFor(2L, """{"tools":[]}"""))

        val transport = transport()
        transport.connect()
        transport.listTools()

        assertEquals(1L, bodyOf(takeRequest()).getLong("id"))
        takeRequest()
        assertEquals(2L, bodyOf(takeRequest()).getLong("id"))
    }

    // ---- 会话 ----

    @Test
    fun `服务器给了 session id 之后每次请求都带上并在 close 时 DELETE`() {
        server.enqueue(initializeResponse(sessionId = "sess-123"))
        server.enqueue(accepted())
        server.enqueue(resultFor(2L, """{"tools":[]}"""))
        server.enqueue(MockResponse().setResponseCode(500).setBody("删除失败也无所谓"))

        val transport = transport()
        val handshake = transport.connect()
        assertEquals("sess-123", handshake.sessionId)

        assertNull("initialize 时服务器还没给 session id", takeRequest().getHeader("Mcp-Session-Id"))
        assertEquals("通知要带上刚拿到的 session id", "sess-123", takeRequest().getHeader("Mcp-Session-Id"))

        transport.listTools()
        assertEquals("sess-123", takeRequest().getHeader("Mcp-Session-Id"))

        transport.close()
        val delete = takeRequest()
        assertEquals("DELETE", delete.method)
        assertEquals("sess-123", delete.getHeader("Mcp-Session-Id"))
    }

    @Test
    fun `服务器没给 session id 时 close 不发请求`() {
        server.enqueue(initializeResponse())
        server.enqueue(accepted())

        val transport = transport()
        transport.connect()
        takeRequest()
        takeRequest()

        transport.close()
        assertNull("不该有第三个请求", server.takeRequest(300, TimeUnit.MILLISECONDS))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `还没初始化就调用会报还没有完成初始化`() {
        val transport = transport()

        val listError = failure { transport.listTools() }
        assertEquals(McpErrorKind.SERVER_UNAVAILABLE, listError.kind)
        assertEquals("还没有完成 MCP 初始化", listError.message)

        val callError = failure { transport.callTool("get_weather", JSONObject()) }
        assertEquals(McpErrorKind.SERVER_UNAVAILABLE, callError.kind)

        assertEquals("不该发出任何请求", 0, server.requestCount)
    }

    // ---- 工具列表 ----

    @Test
    fun `listTools 解析工具的各个字段`() {
        server.enqueue(initializeResponse())
        server.enqueue(accepted())
        server.enqueue(
            resultFor(
                2L,
                """{"tools":[{"name":"get_weather","title":"查天气","description":"查某个城市的天气",
                   "inputSchema":{"type":"object"},"annotations":{"readOnlyHint":true}}]}""",
            ),
        )

        val transport = transport()
        transport.connect()
        val tools = transport.listTools()

        assertEquals(1, tools.size)
        val tool = tools.single()
        assertEquals("get_weather", tool.name)
        assertEquals("查天气", tool.title)
        assertEquals("查某个城市的天气", tool.description)
        assertEquals("object", tool.inputSchema!!.getString("type"))
        assertTrue(tool.annotations!!.getBoolean("readOnlyHint"))
        assertTrue(tool.rawJson.contains("get_weather"))
        assertEquals(0, transport.lastSkippedTools)

        takeRequest() // initialize
        takeRequest() // notifications/initialized
        val listRequest = takeRequest()
        assertEquals("tools/list", bodyOf(listRequest).getString("method"))
    }

    @Test
    fun `listTools 缺少可选字段时给 null`() {
        server.enqueue(initializeResponse())
        server.enqueue(accepted())
        server.enqueue(resultFor(2L, """{"tools":[{"name":"t","description":"d"}]}"""))

        val transport = transport()
        transport.connect()
        val tool = transport.listTools().single()

        assertNull(tool.title)
        assertNull(tool.inputSchema)
        assertNull(tool.annotations)
        assertEquals("d", tool.description)
    }

    @Test
    fun `listTools 跟着 nextCursor 翻页`() {
        server.enqueue(initializeResponse())
        server.enqueue(accepted())
        server.enqueue(resultFor(2L, """{"tools":[{"name":"a","description":"1"}],"nextCursor":"c1"}"""))
        server.enqueue(resultFor(3L, """{"tools":[{"name":"b","description":"2"}]}"""))

        val transport = transport()
        transport.connect()
        val tools = transport.listTools()

        assertEquals(listOf("a", "b"), tools.map { it.name })

        takeRequest()
        takeRequest()
        val firstPage = bodyOf(takeRequest()).getJSONObject("params")
        assertFalse("第一页不该带 cursor", firstPage.has("cursor"))
        val secondPage = bodyOf(takeRequest()).getJSONObject("params")
        assertEquals("c1", secondPage.getString("cursor"))
    }

    @Test
    fun `listTools 遇到重复游标时报 PROTOCOL_ERROR`() {
        server.enqueue(initializeResponse())
        server.enqueue(accepted())
        server.enqueue(resultFor(2L, """{"tools":[],"nextCursor":"c1"}"""))
        server.enqueue(resultFor(3L, """{"tools":[],"nextCursor":"c1"}"""))

        val transport = transport()
        transport.connect()
        val error = failure { transport.listTools() }

        assertEquals(McpErrorKind.PROTOCOL_ERROR, error.kind)
        assertTrue(error.message!!.contains("游标"))
    }

    @Test
    fun `listTools 页数超过上限时报 PROTOCOL_ERROR`() {
        val limits = McpLimits(maxToolPages = 2)
        server.enqueue(initializeResponse())
        server.enqueue(accepted())
        server.enqueue(resultFor(2L, """{"tools":[],"nextCursor":"c1"}"""))
        server.enqueue(resultFor(3L, """{"tools":[],"nextCursor":"c2"}"""))
        server.enqueue(resultFor(4L, """{"tools":[],"nextCursor":"c3"}"""))

        val transport = transport(limits)
        transport.connect()
        val error = failure { transport.listTools() }

        assertEquals(McpErrorKind.PROTOCOL_ERROR, error.kind)
        assertTrue(error.message!!.contains("最多 2 页"))
    }

    @Test
    fun `名字不合规的工具被跳过并计数`() {
        server.enqueue(initializeResponse())
        server.enqueue(accepted())
        server.enqueue(
            resultFor(
                2L,
                """{"tools":[{"name":"ok.name-1","description":"d"},{"name":"坏名字","description":"d"},
                   {"name":"","description":"d"}]}""",
            ),
        )

        val transport = transport()
        transport.connect()
        val tools = transport.listTools()

        assertEquals(listOf("ok.name-1"), tools.map { it.name })
        assertEquals(2, transport.lastSkippedTools)
    }

    @Test
    fun `工具数量超过上限时报 PROTOCOL_ERROR`() {
        val limits = McpLimits(maxTools = 1)
        server.enqueue(initializeResponse())
        server.enqueue(accepted())
        server.enqueue(resultFor(2L, """{"tools":[{"name":"a","description":"1"},{"name":"b","description":"2"}]}"""))

        val transport = transport(limits)
        transport.connect()
        val error = failure { transport.listTools() }

        assertEquals(McpErrorKind.PROTOCOL_ERROR, error.kind)
        assertTrue(error.message!!.contains("最多 1 个"))
    }

    @Test
    fun `description 会被截断到上限`() {
        val limits = McpLimits(maxDescriptionChars = 5)
        server.enqueue(initializeResponse())
        server.enqueue(accepted())
        server.enqueue(resultFor(2L, """{"tools":[{"name":"a","description":"1234567890"}]}"""))

        val transport = transport(limits)
        transport.connect()
        assertEquals("12345", transport.listTools().single().description)
    }

    @Test
    fun `inputSchema 过大时置空并追加说明`() {
        val limits = McpLimits(maxSchemaChars = 30)
        server.enqueue(initializeResponse())
        server.enqueue(accepted())
        server.enqueue(
            resultFor(
                2L,
                """{"tools":[{"name":"a","description":"d",
                   "inputSchema":{"type":"object","properties":{"a":{"type":"string","description":"很长很长很长"}}}}]}""",
            ),
        )

        val transport = transport(limits)
        transport.connect()
        val tool = transport.listTools().single()

        assertNull(tool.inputSchema)
        assertTrue("要告诉用户 schema 被丢掉了：${tool.description}", tool.description.endsWith("（Schema 过大，未保留）"))
    }

    @Test
    fun `tools 数组缺失时报 PROTOCOL_ERROR`() {
        server.enqueue(initializeResponse())
        server.enqueue(accepted())
        server.enqueue(resultFor(2L, """{"别的":1}"""))

        val transport = transport()
        transport.connect()
        val error = failure { transport.listTools() }

        assertEquals(McpErrorKind.PROTOCOL_ERROR, error.kind)
        assertTrue(error.message!!.contains("tools"))
    }

    // ---- 调用工具 ----

    @Test
    fun `callTool 拼接文本并解析结构化内容`() {
        server.enqueue(initializeResponse())
        server.enqueue(accepted())
        server.enqueue(
            resultFor(
                2L,
                """{"content":[{"type":"text","text":"晴"},{"type":"resource","resource":{"uri":"x"}},
                   {"type":"text","text":"23 度"}],"structuredContent":{"temp":23},"isError":true}""",
            ),
        )

        val transport = transport()
        transport.connect()
        val result = transport.callTool("get_weather", JSONObject().put("city", "北京"))

        assertTrue(result.isError)
        assertEquals("晴\n23 度", result.text)
        assertEquals(23, result.structured!!.getInt("temp"))
        assertTrue(result.rawResultJson.contains("structuredContent"))

        takeRequest()
        takeRequest()
        val request = bodyOf(takeRequest())
        assertEquals("tools/call", request.getString("method"))
        val params = request.getJSONObject("params")
        assertEquals("get_weather", params.getString("name"))
        assertEquals("北京", params.getJSONObject("arguments").getString("city"))
    }

    @Test
    fun `callTool 的 isError 缺省为 false 且没有文本时给 null`() {
        server.enqueue(initializeResponse())
        server.enqueue(accepted())
        server.enqueue(resultFor(2L, """{"content":[{"type":"image","data":"xx"}],"structuredContent":"不是对象"}"""))

        val transport = transport()
        transport.connect()
        val result = transport.callTool("take_photo", JSONObject())

        assertFalse(result.isError)
        assertNull(result.text)
        assertNull("structuredContent 不是对象就当没有", result.structured)
        assertTrue(result.rawResultJson.isNotEmpty())
    }

    @Test
    fun `callTool 的结果超过大小上限时报 RESULT_TOO_LARGE`() {
        val limits = McpLimits(maxResponseBytes = 200)
        server.enqueue(initializeResponse())
        server.enqueue(accepted())
        server.enqueue(resultFor(2L, """{"content":[{"type":"text","text":"${"x".repeat(400)}"}]}"""))

        val transport = transport(limits)
        transport.connect()
        val error = failure { transport.callTool("big", JSONObject()) }

        assertEquals(McpErrorKind.RESULT_TOO_LARGE, error.kind)
        assertTrue(error.message!!.contains("上限"))
    }

    // ---- 响应形态 ----

    @Test
    fun `SSE 响应只取本次请求的那一条事件`() {
        server.enqueue(initializeResponse())
        server.enqueue(accepted())
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    """
                    event: message
                    data: {"jsonrpc":"2.0","id":99,"result":{"tools":[{"name":"别的","description":"d"}]}}

                    data: {"jsonrpc":"2.0","method":"notifications/message","params":{}}

                    data: {"jsonrpc":"2.0","id":2,"result":{"tools":[{"name":"target","description":"d"}]}}

                    """.trimIndent(),
                ),
        )

        val transport = transport()
        transport.connect()
        val tools = transport.listTools()

        assertEquals(listOf("target"), tools.map { it.name })
    }

    @Test
    fun `SSE 里没有本次请求的响应时报 PROTOCOL_ERROR`() {
        server.enqueue(initializeResponse())
        server.enqueue(accepted())
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream; charset=utf-8")
                .setBody("data: {\"jsonrpc\":\"2.0\",\"id\":99,\"result\":{}}\n\n"),
        )

        val transport = transport()
        transport.connect()
        val error = failure { transport.listTools() }

        assertEquals(McpErrorKind.PROTOCOL_ERROR, error.kind)
        assertTrue(error.message!!.contains("事件流"))
    }

    @Test
    fun `200 但响应体为空时报 PROTOCOL_ERROR`() {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json"))

        val error = failure { transport().connect() }
        assertEquals(McpErrorKind.PROTOCOL_ERROR, error.kind)
        assertEquals("服务器返回了空内容", error.message)
    }

    @Test
    fun `响应不是 JSON 时报 PROTOCOL_ERROR`() {
        server.enqueue(jsonResponse("<html>这不是 JSON</html>"))

        val error = failure { transport().connect() }
        assertEquals(McpErrorKind.PROTOCOL_ERROR, error.kind)
    }

    @Test
    fun `服务器返回 error 时报 SERVER_REJECTED 并带上 rpc 码`() {
        server.enqueue(errorFor(1L, -32000, "服务器内部错误"))

        val error = failure { transport().connect() }
        assertEquals(McpErrorKind.SERVER_REJECTED, error.kind)
        assertEquals(-32000, error.rpcCode)
        assertTrue(error.message!!.contains("服务器内部错误"))
    }

    @Test
    fun `参数不符合 schema 的 error 归类为 INVALID_ARGUMENTS`() {
        server.enqueue(initializeResponse())
        server.enqueue(accepted())
        server.enqueue(errorFor(2L, -32602, "缺参数"))

        val transport = transport()
        transport.connect()
        val error = failure { transport.callTool("get_weather", JSONObject()) }

        assertEquals(McpErrorKind.INVALID_ARGUMENTS, error.kind)
        assertEquals(-32602, error.rpcCode)
    }

    // ---- HTTP 状态 ----

    @Test
    fun `401 报 NOT_AUTHORIZED 且消息里不出现请求头的值`() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"unauthorized"}"""))

        val transport = transport(headers = listOf(McpHttpHeader("Authorization", "Bearer SUPER-SECRET", secret = true)))
        val error = failure { transport.connect() }

        assertEquals(McpErrorKind.NOT_AUTHORIZED, error.kind)
        assertEquals(401, error.httpStatus)
        assertFalse("异常消息里绝不能出现令牌", error.message!!.contains("SUPER-SECRET"))
        assertFalse("异常消息里不该出现头名", error.message!!.contains("Authorization"))
    }

    @Test
    fun `403 报 NOT_AUTHORIZED`() {
        server.enqueue(MockResponse().setResponseCode(403).setBody("nope"))

        val error = failure { transport().connect() }
        assertEquals(McpErrorKind.NOT_AUTHORIZED, error.kind)
        assertEquals(403, error.httpStatus)
    }

    @Test
    fun `4xx 与 5xx 报 HTTP_ERROR 并带上状态码`() {
        server.enqueue(MockResponse().setResponseCode(400).setBody("bad"))
        val badRequest = failure { transport().connect() }
        assertEquals(McpErrorKind.HTTP_ERROR, badRequest.kind)
        assertEquals(400, badRequest.httpStatus)

        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        val serverError = failure { transport().connect() }
        assertEquals(McpErrorKind.HTTP_ERROR, serverError.kind)
        assertEquals(500, serverError.httpStatus)
    }

    @Test
    fun `3xx 报 SERVER_REJECTED 且绝不跟随重定向`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(301)
                .setHeader("Location", "https://evil.example.com/mcp"),
        )

        val error = failure { transport().connect() }

        assertEquals(McpErrorKind.SERVER_REJECTED, error.kind)
        assertTrue(error.message!!.contains("重定向"))
        assertFalse("重定向地址不该出现在消息里", error.message!!.contains("evil.example.com"))
        assertEquals("只该发一个请求", 1, server.requestCount)
    }

    // ---- 网络异常 ----

    @Test
    fun `读超时报 TIMEOUT`() {
        val limits = McpLimits(connectTimeoutMs = 500, readTimeoutMs = 300)
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

        val error = failure { transport(limits).connect() }
        assertEquals(McpErrorKind.TIMEOUT, error.kind)
        assertEquals("请求超时", error.message)
    }

    @Test
    fun `连不上服务器时报 SERVER_UNAVAILABLE 且不泄漏地址`() {
        val dead = MockWebServer()
        dead.start()
        val url = dead.url("/mcp").toString()
        dead.shutdown()

        val limits = McpLimits(connectTimeoutMs = 500, readTimeoutMs = 500)
        val transport = StreamableHttpTransport(
            serverId = "server-1",
            url = url,
            client = StreamableHttpTransport.defaultMcpHttpClient(limits),
            limits = limits,
        )

        val error = failure { transport.connect() }
        assertEquals(McpErrorKind.SERVER_UNAVAILABLE, error.kind)
        assertEquals("连不上服务器或服务器不可用", error.message)
    }

    // ---- 构造期校验 ----

    @Test
    fun `非法地址在构造时就报 INVALID_ARGUMENTS`() {
        val error = failure { StreamableHttpTransport("server-1", "http://192.168.1.10/mcp") }
        assertEquals(McpErrorKind.INVALID_ARGUMENTS, error.kind)
        assertTrue(error.message!!.contains("地址"))
    }

    @Test
    fun `受控请求头在构造时就报 INVALID_ARGUMENTS`() {
        val error = failure { transport(headers = listOf(McpHttpHeader("Content-Length", "10"))) }
        assertEquals(McpErrorKind.INVALID_ARGUMENTS, error.kind)
        assertTrue(error.message!!.contains("请求头"))
    }

    @Test
    fun `请求头的值带换行时在构造就被拦住`() {
        val error = failure { transport(headers = listOf(McpHttpHeader("X-Bad", "a\r\nX-Injected: 1"))) }
        assertEquals(McpErrorKind.INVALID_ARGUMENTS, error.kind)
    }
}
