package com.androidmcphub.app.ui.model

/**
 * 服务器地址 → 首页/设置入口上可以显示的一小段。
 *
 * 首页只显示主机（含端口），完整 URL 留给「连接详情」和设置页 ——
 * 一条 60 字符的 `wss://…` 会把卡片撑成三行。
 *
 * 解析失败（用户还在输入、或者用了自定义 scheme）时**原样返回**，
 * 不做静默截断：宁可显示得丑一点，也不要显示一个误导性的主机名。
 *
 * 纯函数，所以能直接在 JVM 单测里钉住行为。
 */
fun serverHostLabel(serverUrl: String): String {
    val trimmed = serverUrl.trim()
    if (trimmed.isEmpty()) return "（未填写）"
    return try {
        val uri = java.net.URI(trimmed)
        val host = uri.host
        when {
            host.isNullOrBlank() -> trimmed
            uri.port > 0 -> "$host:${uri.port}"
            else -> host
        }
    } catch (e: Exception) {
        // java.net.URI 对未转义的国际域名等输入会抛异常；这里不该让界面崩掉。
        trimmed
    }
}
