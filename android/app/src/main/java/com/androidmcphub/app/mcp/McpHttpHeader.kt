package com.androidmcphub.app.mcp

/**
 * 用户为某台 MCP 服务器手配的一条请求头。
 *
 * [secret] 的语义是"这个值需要加密落盘、且任何界面/日志/toString 都不许打出来"。
 * 典型是 `Authorization: Bearer ...`。它**不是**"这条头要不要发" —— 那是 [enabled]。
 */
data class McpHttpHeader(
    val name: String,
    val value: String,
    val enabled: Boolean = true,
    val secret: Boolean = false,
) {

    /**
     * data class 自动生成的 `toString()` 会把 [value] 原样打出来 —— 对 secret 头就是泄漏
     * （一条 `Log.d("$header")` 就够）。这里显式覆盖，让"顺手打日志"不再危险。
     */
    override fun toString(): String = McpHeaderPolicy.safeHeaderSummary(this)
}

/**
 * 请求头的**准入策略**。纯函数，没有 Android 依赖，所以每条规则都能被单测穷举。
 *
 * 三类拒绝理由，各自的威胁模型不同：
 *  1. 名字不是 RFC 7230 的 token —— 有些字符（空格、冒号、换行）会让 HTTP 报文
 *     结构歧义，属于**请求走私**类问题，必须在拼请求之前就拦住；
 *  2. 值是受控头（Host / Content-Length / Transfer-Encoding / Mcp-Session-Id …）
 *     —— 这些头由 OkHttp 或本实现自己管理，用户再写一份只会把协议搞坏，
 *     或者伪造出客户端没打算发的语义（比如自己声明 Content-Length）；
 *  3. 值里有 CR/LF/NUL —— 经典的**响应/请求头注入**，一行 `\r\n` 就能加出一整条
 *     新的请求。
 */
object McpHeaderPolicy {

    /** 请求头名字上限（字符）。RFC 没规定长度，这里是防呆值。 */
    const val MAX_NAME_CHARS = 64

    /** 请求头值上限（字符）。 */
    const val MAX_VALUE_CHARS = 2048

    private const val SECRET_PLACEHOLDER = "<已设置>"

    /**
     * RFC 7230 的 token 字符集。
     *
     * 注意 `$` 在 Kotlin 字符串里要转义，否则会被当成模板起始符。
     */
    private val TOKEN = Regex("^[!#\$%&'*+.^_`|~0-9A-Za-z-]+\$")

    /**
     * 受控头（忽略大小写）。前 9 个是 HTTP 自身或中间件层面的，
     * 后 2 个是 MCP 协议层面的：会话与协议版本由 [com.androidmcphub.app.mcp.transport.StreamableHttpTransport] 自己维护，
     * 用户手写会让客户端和服务器的认知不一致。
     */
    private val FORBIDDEN: Set<String> = setOf(
        "host",
        "content-length",
        "connection",
        "transfer-encoding",
        "upgrade",
        "expect",
        "te",
        "trailer",
        "mcp-session-id",
        "mcp-protocol-version",
    )

    /** 请求头名字前缀形式的禁区（`Proxy-*` 系列）。 */
    private const val FORBIDDEN_PREFIX = "proxy-"

    /**
     * 单条请求头能不能发。
     *
     * @return `null` 表示合法；否则返回**中文**原因（只含名字，不含值 —— 值可能是凭据）。
     *
     * 重名不在这里处理：`problem` 只看单条，去重是调用方的事（历史配置里可能残留两条同名头）。
     */
    fun problem(header: McpHttpHeader): String? {
        val name = header.name
        if (name.isEmpty()) return "请求头名称不能为空"
        if (name.length > MAX_NAME_CHARS) return "请求头名称太长（最多 $MAX_NAME_CHARS 个字符）"
        if (!TOKEN.matches(name)) return "请求头名称「$name」含有非法字符"

        val lower = name.lowercase()
        if (lower in FORBIDDEN || lower.startsWith(FORBIDDEN_PREFIX)) {
            return "「$name」由客户端自己管理，不能手动配置"
        }

        val value = header.value
        if (value.length > MAX_VALUE_CHARS) return "请求头「$name」的值太长（最多 $MAX_VALUE_CHARS 个字符）"
        if (value.any { it == '\r' || it == '\n' || it == '\u0000' }) {
            return "请求头「$name」的值不能包含换行或空字符"
        }
        return null
    }

    /**
     * 可以随便往日志/界面/`toString` 里塞的一行摘要。
     *
     * secret 头**永远**只回 `名称: <已设置>` —— 不看 [McpHttpHeader.value] 到底是不是空，
     * 因为"空/非空"的差异本身就是一次信息泄漏，而摘要不需要这个信息。
     */
    fun safeHeaderSummary(header: McpHttpHeader): String =
        if (header.secret) "${header.name}: $SECRET_PLACEHOLDER" else "${header.name}: ${header.value}"
}
