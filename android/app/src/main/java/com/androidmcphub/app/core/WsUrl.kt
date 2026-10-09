package com.androidmcphub.app.core

/**
 * 把用户填写的地址规范化成 OkHttp 可以接受的 HTTP(S) 形式。
 *
 * 为什么需要这一步：
 *  - 协议约定连接形如 `wss://<host>/phone/ws`，而 `ws://` / `wss://` 不是
 *    `HttpUrl` 的合法 scheme，必须先映射成 `http://` / `https://`；
 *  - 协议规定建连时**不带 query**，这里顺手把 `?` 之后和 `#` 之后的内容去掉，
 *    避免用户从别处复制来的地址带上一串参数；
 *  - 正常返回形如 `https://example.workers.dev/phone/ws`。
 *
 * @return null 表示不是合法地址。
 */
fun normalizeWebSocketUrl(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null

    val sep = trimmed.indexOf("://")
    if (sep <= 0) return null

    val scheme = trimmed.substring(0, sep).lowercase()
    val secure = when (scheme) {
        "wss", "https" -> true
        "ws", "http" -> false
        else -> return null
    }

    // 去掉 query 与 fragment：协议约定不带 query
    var rest = trimmed.substring(sep + 3)
    rest = rest.substringBefore('?').substringBefore('#')
    if (rest.isEmpty()) return null
    if (rest.any { it.isWhitespace() }) return null

    val hostPort = rest.substringBefore('/')
    if (hostPort.isEmpty()) return null

    val path = if (rest.contains('/')) rest.substringAfter('/') else ""

    return buildString {
        append(if (secure) "https://" else "http://")
        append(hostPort)
        if (path.isNotEmpty()) {
            append('/')
            append(path)
        }
    }
}

/** 从地址里取出主机名，仅用于界面与通知显示。 */
fun hostOf(url: String): String {
    val sep = url.indexOf("://")
    val rest = if (sep >= 0) url.substring(sep + 3) else url
    val noQuery = rest.substringBefore('?').substringBefore('#')
    return noQuery.substringBefore('/')
}
