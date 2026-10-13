package com.androidmcphub.app.mcp

/**
 * 服务器地址的准入策略（安全关键，纯函数）。
 *
 * 两条不能让步的规则：
 *  1. **明文 HTTP 只允许手机本机回环地址**（`127.0.0.1` / `localhost` / `::1`）。
 *     手机上跑一个本地 MCP 服务器是常见用法，走回环不经过网卡，明文可接受；
 *     而 `http://192.168.x.x` 是把请求头和工具参数明文广播到局域网，
 *     `http://公网域名` 更是直接暴露。这两类一律拒绝，让用户改用 https。
 *  2. **地址里不许写 userinfo**（`https://user:pass@host/`）。OkHttp 会把 userinfo
 *     转成 `Authorization: Basic ...`，用户以为"写在地址里更省事"，实际上凭据
 *     会出现在 URL 里 —— URL 会进日志、进崩溃报告、进 `Referer`。要求改配请求头。
 *
 * 另外拒绝：非 http(s) 协议（`ws`/`wss`/`file`/`ftp`/`javascript` …）、
 * 含控制字符/空格的地址（会撕裂请求行）、含 fragment 的地址（fragment 不会被发送，
 * 留着只会让用户以为它生效了）。
 *
 * 不做的事：不查 DNS、不发请求、不访问网络 —— 这是个纯字符串判定，能在 JVM 单测里穷举。
 */
object McpUrlPolicy {

    private const val SCHEME_HTTPS = "https"
    private const val SCHEME_HTTP = "http"

    private const val DEFAULT_HTTPS_PORT = 443
    private const val DEFAULT_HTTP_PORT = 80

    const val REASON_EMPTY = "请填写服务器地址"
    const val REASON_NOT_ABSOLUTE = "地址必须是完整的绝对地址，例如 https://example.com/mcp"
    const val REASON_NO_HOST = "地址里没有主机名"
    const val REASON_BAD_HOST = "地址里的主机名不合法（IPv6 地址请写成 [::1] 这种形式）"
    const val REASON_USERINFO = "不要在地址里写用户名密码，请在请求头里配置"
    const val REASON_CONTROL = "地址里不能包含空格或控制字符"
    const val REASON_FRAGMENT = "地址里不能包含 # 片段"
    const val REASON_BAD_PORT = "端口号不合法（必须是 1-65535）"
    const val REASON_CLEARTEXT = "明文 HTTP 只允许手机本机回环地址；局域网或公网地址请用 https"

    /**
     * 校验并规范化地址。
     *
     * 规范化只做三件事：scheme 转小写、丢掉 fragment、端口写成十进制数字。
     * **不会**擅自把空路径补成 `/` —— 用户填 `https://example.com` 时返回的 url 就是
     * `https://example.com`，补一个斜杠会让"用户填的地址"和"我们存的地址"对不上。
     */
    fun validate(raw: String): McpUrlCheck {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return McpUrlCheck.Rejected(REASON_EMPTY)

        // 控制字符与空白：CRLF 能把一条请求行劈成两条，空格会让 URL 边界产生歧义。
        // trim() 已经去掉了首尾空白，这里抓的是**内部**的。
        if (trimmed.any { it.isWhitespace() || it.code < 0x20 || it.code == 0x7F }) {
            return McpUrlCheck.Rejected(REASON_CONTROL)
        }

        val schemeEnd = schemeEnd(trimmed) ?: return McpUrlCheck.Rejected(REASON_NOT_ABSOLUTE)
        val scheme = trimmed.substring(0, schemeEnd).lowercase()
        if (!trimmed.startsWith("://", schemeEnd)) return McpUrlCheck.Rejected(REASON_NOT_ABSOLUTE)
        if (scheme != SCHEME_HTTPS && scheme != SCHEME_HTTP) {
            return McpUrlCheck.Rejected("只支持 https（本机回环可以用 http），不支持 $scheme://")
        }

        val rest = trimmed.substring(schemeEnd + 3)
        if (rest.startsWith("/") || rest.startsWith("?") || rest.startsWith("#") || rest.isEmpty()) {
            return McpUrlCheck.Rejected(REASON_NO_HOST)
        }

        // authority = scheme:// 之后、路径/query/fragment 之前的那一段
        val authority = rest.substringBefore('/').substringBefore('?').substringBefore('#')
        if (authority.isEmpty()) return McpUrlCheck.Rejected(REASON_NO_HOST)
        if (authority.contains('@')) return McpUrlCheck.Rejected(REASON_USERINFO)
        if (trimmed.contains('#')) return McpUrlCheck.Rejected(REASON_FRAGMENT)

        val host: String
        val portText: String?
        if (authority.startsWith("[")) {
            // IPv6 字面量：host 在方括号里，括号后只能跟 :port
            val close = authority.indexOf(']')
            if (close <= 1) return McpUrlCheck.Rejected(REASON_BAD_HOST)
            host = authority.substring(1, close)
            val after = authority.substring(close + 1)
            portText = when {
                after.isEmpty() -> null
                after.startsWith(":") -> after.substring(1)
                else -> return McpUrlCheck.Rejected(REASON_BAD_HOST)
            }
            if (!host.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }) {
                return McpUrlCheck.Rejected(REASON_BAD_HOST)
            }
        } else {
            val colon = authority.indexOf(':')
            if (colon < 0) {
                host = authority
                portText = null
            } else {
                if (authority.indexOf(':', colon + 1) >= 0) return McpUrlCheck.Rejected(REASON_BAD_HOST)
                host = authority.substring(0, colon)
                portText = authority.substring(colon + 1)
            }
            if (host.isEmpty()) return McpUrlCheck.Rejected(REASON_NO_HOST)
            if (!host.all { it.isLetterOrDigit() || it == '-' || it == '.' || it == '_' }) {
                return McpUrlCheck.Rejected(REASON_BAD_HOST)
            }
        }

        val port = if (portText == null) {
            if (scheme == SCHEME_HTTPS) DEFAULT_HTTPS_PORT else DEFAULT_HTTP_PORT
        } else {
            if (portText.isEmpty() || !portText.all { it.isDigit() }) return McpUrlCheck.Rejected(REASON_BAD_PORT)
            val parsed = portText.toIntOrNull() ?: return McpUrlCheck.Rejected(REASON_BAD_PORT)
            if (parsed < 1 || parsed > 65_535) return McpUrlCheck.Rejected(REASON_BAD_PORT)
            parsed
        }

        val loopback = isLoopbackHost(host)
        val cleartext = scheme == SCHEME_HTTP
        if (cleartext && !loopback) return McpUrlCheck.Rejected(REASON_CLEARTEXT)

        val pathAndQuery = rest.substring(authority.length)
        val normalized = buildString {
            append(scheme)
            append("://")
            if (authority.startsWith("[")) {
                append('[').append(host).append(']')
            } else {
                append(host)
            }
            // 只保留**显式**端口；缺省端口不写出来，避免制造"看起来是自定义端口"的噪音
            if (portText != null) append(':').append(port)
            append(pathAndQuery)
        }

        return McpUrlCheck.Ok(
            url = normalized,
            scheme = scheme,
            host = host,
            port = port,
            cleartext = cleartext,
            loopback = loopback,
        )
    }

    /**
     * 主机名是不是本机回环。
     *
     * 只认这三个字面量：`127.0.0.1` / `localhost` / `::1`。
     * 不把整个 `127.0.0.0/8` 当回环 —— 那是"同一台机器的其它地址"，
     * 语义上仍然可能被本机上的其它服务监听，白名单越窄越安全。
     */
    fun isLoopbackHost(host: String): Boolean {
        val normalized = host.trim().removePrefix("[").removeSuffix("]").lowercase()
        return normalized == "127.0.0.1" || normalized == "localhost" || normalized == "::1"
    }

    /**
     * 取出主机名，**只用于界面显示**。
     *
     * 与 [validate] 分开是有意的：界面要在用户还没填完、地址还不合法的时候
     * 就能显示一个"大概是这台机器"的提示。所以这里只做宽松解析，
     * 解析不出来就返回空串，绝不抛异常。
     */
    fun hostOf(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        if (trimmed.any { it.isWhitespace() || it.code < 0x20 || it.code == 0x7F }) return ""
        val schemeEnd = schemeEnd(trimmed) ?: return ""
        val scheme = trimmed.substring(0, schemeEnd).lowercase()
        if (scheme != SCHEME_HTTPS && scheme != SCHEME_HTTP) return ""
        if (!trimmed.startsWith("://", schemeEnd)) return ""
        val rest = trimmed.substring(schemeEnd + 3)
        val authority = rest.substringBefore('/').substringBefore('?').substringBefore('#')
        if (authority.isEmpty()) return ""
        // 有 userinfo 时取最后一个 @ 之后的部分（host 里不允许出现 @）
        val hostAndPort = authority.substringAfterLast('@')
        if (hostAndPort.startsWith("[")) {
            val close = hostAndPort.indexOf(']')
            return if (close > 1) hostAndPort.substring(1, close) else ""
        }
        return hostAndPort.substringBefore(':')
    }

    /** 找出 `scheme:` 里冒号的位置；找不到 scheme 就返回 `null`。 */
    private fun schemeEnd(text: String): Int? {
        val colon = text.indexOf(':')
        if (colon <= 0) return null
        val scheme = text.substring(0, colon)
        if (!scheme[0].isLetter()) return null
        if (!scheme.all { it.isLetterOrDigit() || it == '+' || it == '-' || it == '.' }) return null
        return colon
    }
}

/** [McpUrlPolicy.validate] 的结果。 */
sealed interface McpUrlCheck {

    /**
     * 地址可用。
     *
     * @param url 规范化后的完整地址（scheme 小写、丢掉 fragment、保留显式端口）。
     * @param host 解析出的主机名（IPv6 不带方括号）。
     * @param port 实际使用的端口（显式端口或 scheme 缺省端口）。
     * @param cleartext 是否明文传输（`http`）。
     * @param loopback 主机是否是本机回环（与 [cleartext] 无关：`https://127.0.0.1` 也是回环）。
     */
    data class Ok(
        val url: String,
        val scheme: String,
        val host: String,
        val port: Int,
        val cleartext: Boolean,
        val loopback: Boolean,
    ) : McpUrlCheck

    /** @param reason 中文原因，可直接显示给用户（不含用户填的内容之外的任何数据）。 */
    data class Rejected(val reason: String) : McpUrlCheck
}
