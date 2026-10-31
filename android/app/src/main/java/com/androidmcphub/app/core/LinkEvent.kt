package com.androidmcphub.app.core

/**
 * 连接生命周期事件（诊断页用）。
 *
 * 为什么不复用日志：日志是给人读的自由文本，界面要按类别展示、要能一眼数出
 * "重连了几次"，就必须有一个**闭集**。这里的每个枚举值对应一种稳定含义，
 * 新增类别要显式加在这里，而不是在调用点随手拼一句中文。
 *
 * [detail] 只允许放固定类别、代次、次数、耗时这类非敏感信息；
 * **不得**放设备令牌、配对码、服务端自由文本或请求参数。
 *
 * V1.6.3：一条事件不再只有一句人读文本。断线的**结构化证据**（服务端关闭码、
 * 网络错误类别、连接代次、本地关闭来源、心跳静默时长）各自有字段 ——
 * 这样上报给云端的 `diag_event` 不必从中文里 substring，界面也不必靠猜。
 */
enum class LinkEventKind(val label: String) {
    CONNECTED("连接成功"),

    /*
     * V1.6.3：原来的单一 `DISCONNECTED` 拆成三类。
     *
     * 为什么必须拆：V1.6.2 及以前，"服务端关闭 code=4001"、"连接失败
     * category=connection_reset"、"握手超时" 都记成同一种 `DISCONNECTED`，
     * 只有 detail 字符串不同。排查"4001 到底是谁关的"时，界面、上报与筛选
     * 都只能对着中文前缀做字符串匹配 —— 那正是最容易把结论搞错的地方。
     * 现在三类各有闭集名称，与服务端诊断事件的语义一一对应。
     */

    /** 服务端发来关闭帧（我们没有主动关）。 */
    SERVER_CLOSED("服务端关闭"),

    /** 传输层失败（DNS / 连接被重置 / TLS …），类别见 [SocketFailure]。 */
    SOCKET_FAILURE("连接失败"),

    /** WebSocket 握手在 [com.androidmcphub.app.protocol.PhoneLink.HANDSHAKE_TIMEOUT_MS] 内没有完成。 */
    HANDSHAKE_TIMEOUT("握手超时"),

    RECONNECTING("开始重连"),
    NETWORK_CHANGED("网络变化"),
    HEARTBEAT_TIMEOUT("心跳超时"),
    AUTHENTICATION_FAILED("认证失败"),
    USER_DISCONNECTED("用户断开"),

    /**
     * 前台服务被销毁，但**不是**用户点「断开」。
     *
     * V1.6.2 新增：V1.6.1 及以前，服务 `onDestroy()` 复用 `PhoneLink.stop()` 收尾，
     * 而后者默认原因写死为「用户主动断开」并记一条 [USER_DISCONNECTED]。于是系统
     * 回收服务（ColorOS 省电策略、系统 stopService）在诊断页里被显示成"用户主动断开"，
     * 与实际发生的事完全相反。服务销毁必须有自己的一类事件。
     */
    SERVICE_DESTROYED("服务停止"),
    SERVICE_RESTARTED("服务恢复"),
}

/**
 * 一条结构化连接事件。
 *
 * 所有附加字段都是**可选的结构化证据**，不是自由文本：
 *  - [generation] 来自 welcome 里的 `connection_generation`（服务端分配的代次）；
 *  - [closeCode] 是服务端关闭帧里**实际收到**的码；没收到就是 null，绝不填 1006 冒充；
 *  - [category] 只能是 [SocketFailure.ALL] 里的固定类别；
 *  - [source] 是本地主动关闭的真实来源（见 [DiagCloseSource]）；
 *  - [heartbeatAgeMs] 是"判定链路已死时，已有多久没收到任何服务端帧"；
 *  - [exceptionClass] 是触发失败的**异常类名**（如 `ProtocolException`），
 *    不是 `message`。类名是稳定的、可用于归类的证据，message 里则可能混进
 *    服务端地址、令牌片段等自由文本 —— 后者一律不采集。
 *
 * [exceptionClass] 在进入上报帧前还会经 [normalizeExceptionClass] 收敛，
 * 只允许类名形状的字符串通过。
 */
data class LinkEvent(
    val kind: LinkEventKind,
    val atMs: Long,
    val detail: String = "",
    val generation: Long? = null,
    val closeCode: Int? = null,
    val category: String? = null,
    val source: String? = null,
    val attempt: Int? = null,
    val heartbeatAgeMs: Long? = null,
    val exceptionClass: String? = null,
)
