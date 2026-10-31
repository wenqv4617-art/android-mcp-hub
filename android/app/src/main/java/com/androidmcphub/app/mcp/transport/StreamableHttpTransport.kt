package com.androidmcphub.app.mcp.transport

import com.androidmcphub.app.BuildConfig
import com.androidmcphub.app.mcp.McpClientException
import com.androidmcphub.app.mcp.McpErrorKind
import com.androidmcphub.app.mcp.McpHeaderPolicy
import com.androidmcphub.app.mcp.McpHttpHeader
import com.androidmcphub.app.mcp.McpLimits
import com.androidmcphub.app.mcp.McpUrlCheck
import com.androidmcphub.app.mcp.McpUrlPolicy
import com.androidmcphub.app.mcp.json.JsonRpcMessage
import com.androidmcphub.app.mcp.json.encodeNotification
import com.androidmcphub.app.mcp.json.encodeRequest
import com.androidmcphub.app.mcp.json.extractSseEvents
import com.androidmcphub.app.mcp.json.parseMessage
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * MCP 的 Streamable HTTP 传输（规范版本 2025-06-18）。
 *
 * 这个类是整个 Gate 里唯一真的会碰网络的地方，所以它把"安全"当成主要职责：
 *
 *  1. **不跟随重定向**（`followRedirects = false`，`followSslRedirects = false`）。
 *     一个 3xx 最坏的情况是把 `Authorization` 头原样送到攻击者的地址上。
 *     代价是有些服务器的 URL 少个斜杠就连不上 —— 这个代价必须付，写成 3xx → SERVER_REJECTED。
 *  2. **不覆盖 TLS**：不设置 `hostnameVerifier`、不设置 `sslSocketFactory`，
 *     证书校验永远走系统默认。
 *  3. **异常里不放敏感信息**：错误消息只有中文说明 + HTTP 状态码 + RPC 错误码，
 *     没有请求头的值、没有令牌、没有带查询串的完整 URL。
 *  4. **响应体有上限**：`peekBody` 只取 maxResponseBytes + 1 字节，超一点就报
 *     RESULT_TOO_LARGE，不给"服务器（或中间人）用一个无限流把手机内存吃光"的机会。
 *
 * 并发：一个实例内部的四个方法**串行**（[lock]）。MCP 的一次会话本身就是有状态的
 * （初始化 → 会话 id → 请求），客户端并发发请求不会更快，只会更难排错。
 *
 * @param serverId 本地服务器配置的 id，用于日志与错误归因（不参与协议）。
 * @param url 已经过 [McpUrlPolicy] 校验的地址。
 * @param headers 用户在设置里配的请求头；disabled 的会被忽略，非法的直接抛。
 * @param client 可注入，便于测试与统一连接池；默认 [defaultMcpHttpClient]。
 */
class StreamableHttpTransport(
    override val serverId: String,
    url: String,
    headers: List<McpHttpHeader> = emptyList(),
    private val client: OkHttpClient = defaultMcpHttpClient(),
    private val limits: McpLimits = McpLimits(),
) : McpTransport {

    private val lock = Any()

    /** 规范化后的地址（来自 [McpUrlPolicy]：去 fragment、保留显式端口）。 */
    private val endpoint: String = when (val check = McpUrlPolicy.validate(url)) {
        is McpUrlCheck.Ok -> check.url
        is McpUrlCheck.Rejected ->
            throw McpClientException(McpErrorKind.INVALID_ARGUMENTS, "服务器地址不可用：${check.reason}")
    }

    /**
     * 已按名字去重、且全部通过 [McpHeaderPolicy] 的请求头。
     *
     * 非法头**直接抛**而不是悄悄丢掉：用户手写了一条 `Content-Length` 或者带换行的值，
     * 静默忽略只会让他在下次调用时困惑"为什么我的头没生效"。
     * 同名（忽略大小写）后者覆盖前者。
     */
    private val effectiveHeaders: List<McpHttpHeader> = run {
        val accepted = LinkedHashMap<String, McpHttpHeader>()
        for (header in headers) {
            if (!header.enabled) continue
            val problem = McpHeaderPolicy.problem(header)
            if (problem != null) {
                throw McpClientException(McpErrorKind.INVALID_ARGUMENTS, "请求头配置有问题：$problem")
            }
            accepted[header.name.lowercase()] = header
        }
        accepted.values.toList()
    }

    private val requestIds = AtomicLong(0L)

    private var sessionId: String? = null

    private var initialized = false

    /**
     * 上一次 [listTools] 里被**跳过**的工具数（名字不合规）。
     *
     * 单个工具定义坏掉不该让整台服务器的工具都列不出来 —— 那时候用户连
     * "是这台服务器坏了还是网络坏了"都分不清。跳过 + 让界面显示"N 个工具被忽略"，
     * 用户至少知道发生了什么事。
     */
    var lastSkippedTools: Int = 0
        private set

    override fun connect(): McpServerHandshake = synchronized(lock) {
        val params = JSONObject().apply {
            put("protocolVersion", PROTOCOL_VERSION)
            put("capabilities", JSONObject())
            put(
                "clientInfo",
                JSONObject().apply {
                    put("name", CLIENT_NAME)
                    put("version", clientVersion())
                },
            )
        }

        val id = nextId()
        val exchange = post(id, "initialize", params)
        val result = requireResult(exchange, id)

        // sessionId 必须在发 notifications/initialized **之前**记下来：
        // 那条通知也要带 Mcp-Session-Id，否则服务器会把会话当成未初始化。
        sessionId = exchange.sessionId

        val protocolVersion = result.opt("protocolVersion")
        if (protocolVersion !is String || protocolVersion.isEmpty()) {
            throw McpClientException(
                McpErrorKind.PROTOCOL_ERROR,
                "服务器没有返回协议版本（result.protocolVersion）",
            )
        }

        // 通知失败不回滚整个连接：初始化本身已经成功，会话是可用的。
        // 但网络层面的异常（超时/连不上）要照常抛 —— 那说明这个会话其实已经不可用了。
        postNotification("notifications/initialized", null)

        initialized = true

        val serverInfo = result.optJSONObject("serverInfo")
        return McpServerHandshake(
            protocolVersion = protocolVersion,
            serverName = serverInfo?.optString("name", "")?.takeIf { it.isNotEmpty() },
            serverVersion = serverInfo?.optString("version", "")?.takeIf { it.isNotEmpty() },
            sessionId = sessionId,
        )
    }

    override fun listTools(): List<McpRemoteTool> = synchronized(lock) {
        requireInitialized()

        val collected = mutableListOf<McpRemoteTool>()
        val seenCursors = mutableSetOf<String>()
        var skipped = 0
        var cursor: String? = null
        var page = 0

        while (true) {
            if (page >= limits.maxToolPages) {
                throw McpClientException(
                    McpErrorKind.PROTOCOL_ERROR,
                    "工具列表页数超过上限（最多 ${limits.maxToolPages} 页）",
                )
            }
            page++

            val params = JSONObject()
            if (cursor != null) params.put("cursor", cursor)

            val id = nextId()
            val result = requireResult(post(id, "tools/list", params), id)

            val tools = result.optJSONArray("tools")
                ?: throw McpClientException(
                    McpErrorKind.PROTOCOL_ERROR,
                    "tools/list 的响应里没有 tools 数组",
                )

            for (index in 0 until tools.length()) {
                val item = tools.optJSONObject(index) ?: continue
                val tool = parseTool(item)
                if (tool == null) skipped++ else collected += tool
            }

            if (collected.size > limits.maxTools) {
                throw McpClientException(
                    McpErrorKind.PROTOCOL_ERROR,
                    "工具数量超过上限（最多 ${limits.maxTools} 个）",
                )
            }

            val nextCursor = result.opt("nextCursor")
            if (nextCursor !is String || nextCursor.isEmpty()) break

            // 游标重复 = 服务器让我们转圈。不拦就是一个无限循环 + 无限流量。
            if (!seenCursors.add(nextCursor)) {
                throw McpClientException(
                    McpErrorKind.PROTOCOL_ERROR,
                    "服务器返回了重复的分页游标，停止拉取",
                )
            }
            cursor = nextCursor
        }

        lastSkippedTools = skipped
        collected
    }

    override fun callTool(toolName: String, arguments: JSONObject): McpToolCallResult =
        callToolWithMeta(toolName, arguments, null)

    override fun callToolWithMeta(
        toolName: String,
        arguments: JSONObject,
        meta: JSONObject?,
    ): McpToolCallResult = synchronized(lock) {
        requireInitialized()

        val params = JSONObject().apply {
            put("name", toolName)
            put("arguments", arguments)
            if (meta != null) put("_meta", meta)
        }

        val id = nextId()
        val result = requireResult(post(id, "tools/call", params), id)

        val raw = result.toString()
        val bytes = raw.toByteArray(Charsets.UTF_8).size
        if (bytes > limits.maxResponseBytes) {
            throw McpClientException(
                McpErrorKind.RESULT_TOO_LARGE,
                "工具返回的结果太大（$bytes 字节，上限 ${limits.maxResponseBytes} 字节）",
            )
        }

        val isError = result.optBoolean("isError", false)
        val structured = result.optJSONObject("structuredContent")

        val texts = mutableListOf<String>()
        val content = result.optJSONArray("content")
        if (content != null) {
            for (index in 0 until content.length()) {
                val item = content.optJSONObject(index) ?: continue
                if (item.optString("type", "") != "text") continue
                texts += item.optString("text", "")
            }
        }

        McpToolCallResult(
            isError = isError,
            text = if (texts.isEmpty()) null else texts.joinToString("\n"),
            structured = structured,
            rawResultJson = raw,
        )
    }

    override fun close() {
        val sid = synchronized(lock) {
            val current = sessionId
            sessionId = null
            initialized = false
            current
        }
        if (sid == null) return

        // 关闭动作的失败**只吞掉**：用户已经关了，这时候弹一个"关闭失败"毫无意义。
        try {
            val request = Request.Builder()
                .url(endpoint)
                .delete()
                .applySession(sid)
                .build()
            val call = client.newCall(request)
            call.timeout().timeout(limits.readTimeoutMs, TimeUnit.MILLISECONDS)
            call.execute().use { it.peekBody(SHORT_BODY_BYTES).string() }
        } catch (_: Exception) {
            // 有意静默
        }
    }

    // ---- 请求 ----

    private fun requireInitialized() {
        if (!initialized) {
            throw McpClientException(McpErrorKind.SERVER_UNAVAILABLE, "还没有完成 MCP 初始化")
        }
    }

    private fun nextId(): Long = requestIds.incrementAndGet()

    private fun post(id: Long, method: String, params: JSONObject?): HttpExchange {
        val json = encodeRequest(id, method, params)
        return execute(json)
    }

    /**
     * 发通知。**不检查状态码**：服务器不喜欢这条通知（404/400）是它的事，
     * 初始化已经成功了，不该因为一条 best-effort 通知把连接判死。
     * 网络层异常依旧抛出，由 [mapNetworkFailure] 翻译。
     */
    private fun postNotification(method: String, params: JSONObject?) {
        val json = encodeNotification(method, params)
        execute(json)
    }

    private fun execute(json: String): HttpExchange {
        val request = Request.Builder()
            .url(endpoint)
            .post(json.toRequestBody(JSON_MEDIA_TYPE))
            .applySession(sessionId)
            .build()
        val call = client.newCall(request)
        // 单次调用的总超时：连接成功了但服务器一直不吐完响应体，也该有个上限。
        call.timeout().timeout(limits.readTimeoutMs, TimeUnit.MILLISECONDS)
        return try {
            readExchange(call.execute())
        } catch (e: IOException) {
            throw mapNetworkFailure(e)
        }
    }

    private fun Request.Builder.applySession(sid: String?): Request.Builder {
        header("content-type", JSON_MEDIA_TYPE.toString())
        header("accept", ACCEPT)
        for (header in effectiveHeaders) header(header.name, header.value)
        if (sid != null) header(SESSION_HEADER, sid)
        return this
    }

    /** 响应体读取：先请求上限 + 1 字节，超了就报错，绝不把整个响应体读进内存。 */
    private fun readExchange(response: Response): HttpExchange = response.use { r ->
        val limit = limits.maxResponseBytes.toLong()
        val body = r.peekBody(limit + 1).string()
        if (body.toByteArray(Charsets.UTF_8).size > limit) {
            throw McpClientException(
                McpErrorKind.RESULT_TOO_LARGE,
                "服务器返回的内容超过上限（${limits.maxResponseBytes} 字节）",
            )
        }
        HttpExchange(
            code = r.code,
            contentType = r.header("content-type"),
            sessionId = r.header(SESSION_HEADER),
            body = body,
        )
    }

    // ---- 响应 ----

    private fun requireResult(exchange: HttpExchange, expectedId: Long): JSONObject {
        ensureSuccess(exchange)

        if (exchange.body.isBlank()) {
            throw McpClientException(McpErrorKind.PROTOCOL_ERROR, "服务器返回了空内容")
        }

        val contentType = exchange.contentType.orEmpty().lowercase()
        val message = if (contentType.contains("text/event-stream")) {
            findMatchingEvent(exchange.body, expectedId)
        } else {
            parseMessage(exchange.body)
        }

        return when (message) {
            is JsonRpcMessage.Result -> {
                if (message.id != null && message.id != expectedId) {
                    throw McpClientException(
                        McpErrorKind.PROTOCOL_ERROR,
                        "服务器返回的响应 id 与请求不匹配",
                    )
                }
                message.result
            }

            is JsonRpcMessage.Failure -> throw failureOf(message)

            is JsonRpcMessage.Notification -> throw McpClientException(
                McpErrorKind.PROTOCOL_ERROR,
                "服务器返回了通知，而不是请求的结果",
            )

            is JsonRpcMessage.Malformed -> throw McpClientException(
                McpErrorKind.PROTOCOL_ERROR,
                "服务器返回的内容不是合法的 MCP 响应：${message.reason}",
            )
        }
    }

    /**
     * 事件流里找**第一个** id 匹配当前请求的 Result/Failure。
     *
     * 不匹配的事件（别的请求的响应、心跳、`[DONE]` 之类的非 JSON data）全部忽略：
     * 服务器一次连接里塞多条事件是允许的，客户端不该因为看到不认识的东西就失败。
     */
    private fun findMatchingEvent(body: String, expectedId: Long): JsonRpcMessage {
        for (event in extractSseEvents(body)) {
            val text = event.trim()
            if (text.isEmpty()) continue
            when (val parsed = parseMessage(text)) {
                is JsonRpcMessage.Result ->
                    if (parsed.id == null || parsed.id == expectedId) return parsed

                is JsonRpcMessage.Failure ->
                    if (parsed.id == null || parsed.id == expectedId) return parsed

                else -> Unit // 忽略
            }
        }
        return JsonRpcMessage.Malformed("事件流里没有本次请求的响应")
    }

    private fun ensureSuccess(exchange: HttpExchange) {
        val code = exchange.code
        when {
            code in 200..299 -> Unit

            code == 401 || code == 403 -> throw McpClientException(
                McpErrorKind.NOT_AUTHORIZED,
                "服务器拒绝了认证（HTTP $code），请检查请求头里的凭据",
                httpStatus = code,
            )

            code in 300..399 -> throw McpClientException(
                McpErrorKind.SERVER_REJECTED,
                "服务器要求重定向到其它地址，已拒绝（不会把认证头带到别的地址）",
                httpStatus = code,
            )

            else -> throw McpClientException(
                McpErrorKind.HTTP_ERROR,
                "服务器返回了 HTTP $code",
                httpStatus = code,
            )
        }
    }

    /** JSON-RPC 错误 → 客户端异常。`-32602`（参数非法）单独分类，其余归为"服务器拒绝"。 */
    private fun failureOf(failure: JsonRpcMessage.Failure): McpClientException {
        val kind = if (failure.code == RPC_INVALID_PARAMS) {
            McpErrorKind.INVALID_ARGUMENTS
        } else {
            McpErrorKind.SERVER_REJECTED
        }
        return McpClientException(
            kind = kind,
            message = "服务器拒绝了请求：${failure.message}",
            rpcCode = failure.code,
        )
    }

    /** 网络异常 → 分类。消息里**不含**异常原文（可能带上地址细节），只给中文结论。 */
    private fun mapNetworkFailure(e: IOException): McpClientException = when (e) {
        is SocketTimeoutException -> McpClientException(McpErrorKind.TIMEOUT, "请求超时")
        is InterruptedIOException -> McpClientException(McpErrorKind.TIMEOUT, "请求超时")
        else -> McpClientException(McpErrorKind.SERVER_UNAVAILABLE, "连不上服务器或服务器不可用")
    }

    // ---- 工具解析 ----

    /** 解析单个工具。返回 `null` 表示"这个名字不合规，跳过"。 */
    private fun parseTool(item: JSONObject): McpRemoteTool? {
        val name = item.optString("name", "")
        if (!TOOL_NAME.matches(name)) return null

        val title = item.optString("title", "").takeIf { it.isNotEmpty() }

        var description = item.optString("description", "").take(limits.maxDescriptionChars)

        var inputSchema = item.optJSONObject("inputSchema")
        if (inputSchema != null && inputSchema.toString().length > limits.maxSchemaChars) {
            // 丢掉整个 schema 而不是截断：截断的 schema 是**错误**的 schema，
            // 界面可能拿它去渲染一个参数表单，宁可显示"没保留"。
            inputSchema = null
            description = "$description（Schema 过大，未保留）"
        }

        return McpRemoteTool(
            name = name,
            title = title,
            description = description,
            inputSchema = inputSchema,
            annotations = item.optJSONObject("annotations"),
            rawJson = item.toString(),
        )
    }

    private class HttpExchange(
        val code: Int,
        val contentType: String?,
        val sessionId: String?,
        val body: String,
    )

    companion object {

        /** 实现所用的 MCP 规范版本。 */
        const val PROTOCOL_VERSION = "2025-06-18"

        const val CLIENT_NAME = "android-mcp-hub"

        /** `BuildConfig` 拿不到版本号时的兜底（例如某些测试环境）。 */
        const val FALLBACK_CLIENT_VERSION = "1.6.2"

        const val SESSION_HEADER = "Mcp-Session-Id"

        private const val ACCEPT = "application/json, text/event-stream"

        private const val RPC_INVALID_PARAMS = -32602

        private const val SHORT_BODY_BYTES = 64L

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /** 工具名必须匹配的字符集（MCP 允许的保守子集）。 */
        private val TOOL_NAME = Regex("^[a-zA-Z0-9_.-]{1,128}$")

        /** 客户端上报的版本号；空串时用常量兜底。 */
        fun clientVersion(): String =
            BuildConfig.VERSION_NAME.ifBlank { FALLBACK_CLIENT_VERSION }

        /**
         * 默认客户端。
         *
         * 四个决定，每一个都有理由：
         *  - **不跟随重定向**：认证头不能跟着 3xx 走到别人家去；
         *  - **不重试连接**：`retryOnConnectionFailure = false` 让"失败"就是失败，
         *    而不是被 OkHttp 悄悄重发一次（对非幂等的 tools/call 是危险的，
         *    对用户也很难解释"为什么点了两次");
         *  - **超时可注入**：由 [McpLimits] 决定，测试里能压到几百毫秒；
         *  - **不动 TLS**：这里没有任何 hostnameVerifier / sslSocketFactory，
         *    证书校验永远走系统默认实现。
         */
        fun defaultMcpHttpClient(limits: McpLimits = McpLimits()): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(limits.connectTimeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(limits.readTimeoutMs, TimeUnit.MILLISECONDS)
                .writeTimeout(15_000L, TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(false)
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
    }
}
