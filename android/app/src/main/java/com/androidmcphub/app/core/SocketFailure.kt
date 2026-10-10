package com.androidmcphub.app.core

/**
 * WebSocket / 网络层失败的原因类别 —— **闭集**。
 *
 * 为什么需要这个文件（Gate 0.3 审计发现项 1）：
 *
 *  在这之前，网络层把 `"${t.javaClass.simpleName}: ${t.message ?: "无详情"}"` 这串东西
 *  一路传给 `PhoneLink`，于是它同时进了**日志**和**界面状态**。`Throwable.message`
 *  是未经处理的异常字符串：OkHttp 会把 URL、主机名、有时还有整段响应头拼进去，
 *  而"URL 上带查询参数"正是设备令牌最容易泄漏的位置（`wss://host/phone/ws?token=…`）。
 *
 *  现在改成两道约束：
 *   1. [classify] **只按异常的类名**分类，返回的字符串必定落在 [ALL] 里；
 *   2. [describe] 把类别映射到一句**写死的**中文，界面用这句，不再有原文。
 *
 *  [classify] 的入参是类名而不是 `Throwable`，原因与 `ForegroundFailure.classify` 一致：
 *  这样分类逻辑能被 JVM 单测直接覆盖，而且从签名上就**没有**拿到 `message` 的途径。
 */
object SocketFailure {

    /** 域名解析不出来（`UnknownHostException`）。 */
    const val DNS = "dns_failure"

    /** 连接或握手超时（`SocketTimeoutException`）。 */
    const val TIMEOUT = "connect_timeout"

    /** 对端拒绝连接（`ConnectException`）。 */
    const val REFUSED = "connect_refused"

    /** 没有到目标网段的路由（`NoRouteToHostException`），常见于切网瞬间。 */
    const val UNREACHABLE = "network_unreachable"

    /** TLS 握手/校验失败（`SSLException` 及其子类）。 */
    const val TLS = "tls_failure"

    /** 连接被对端或系统重置（`SocketException`：reset / broken pipe / socket closed）。 */
    const val RESET = "connection_reset"

    /** 其它 IO 失败（`IOException`、`EOFException`）。 */
    const val IO = "io_failure"

    /** 协议层异常（`java.net.ProtocolException`）。 */
    const val PROTOCOL = "protocol_failure"

    /** 其它运行时异常。 */
    const val RUNTIME = "runtime_exception"

    /** 认不出来的异常。 */
    const val UNKNOWN = "unknown_exception"

    /** 全部合法类别。任何写进日志的类别都必须在这个集合里。 */
    val ALL: Set<String> = setOf(
        DNS, TIMEOUT, REFUSED, UNREACHABLE, TLS, RESET, IO, PROTOCOL, RUNTIME, UNKNOWN,
    )

    /**
     * **只按异常的类名**分类，永不返回 [ALL] 之外的值。
     *
     * 这里刻意不接收 `Throwable`：签名上就拿不到 `message`，"日志里不出现未经处理的
     * 异常字符串"这条约束就不依赖任何人的自觉。
     */
    fun classify(exceptionName: String): String = when (exceptionName) {
        "UnknownHostException" -> DNS
        "SocketTimeoutException", "InterruptedIOException", "TimeoutException" -> TIMEOUT
        "ConnectException" -> REFUSED
        "NoRouteToHostException", "PortUnreachableException" -> UNREACHABLE
        "SSLHandshakeException", "SSLPeerUnverifiedException", "SSLException",
        "SSLProtocolException", "CertificateException",
        -> TLS
        "SocketException" -> RESET
        "IOException", "EOFException", "FileNotFoundException" -> IO
        "ProtocolException" -> PROTOCOL
        "RuntimeException", "IllegalStateException", "IllegalArgumentException" -> RUNTIME
        else -> UNKNOWN
    }

    /** 类别的**固定**中文描述，用于界面与日志的人可读部分。不含任何原始异常文本。 */
    fun describe(category: String): String = when (category) {
        DNS -> "服务器地址解析不了"
        TIMEOUT -> "连接超时"
        REFUSED -> "服务器拒绝连接"
        UNREACHABLE -> "网络不可达"
        TLS -> "TLS 握手失败"
        RESET -> "连接被重置"
        IO -> "网络读写失败"
        PROTOCOL -> "协议错误"
        RUNTIME -> "连接过程出错"
        else -> "连接失败"
    }

    /**
     * 把任意输入收敛回 [ALL] 里的一个值。
     *
     * 用途：`onSocketFailure` 的入参来自网络层，万一传进来的不是已知类别，
     * 也要变成 [UNKNOWN] 而不是原样进日志。
     */
    fun normalize(category: String): String = if (category in ALL) category else UNKNOWN
}
