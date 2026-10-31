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
 */
enum class LinkEventKind(val label: String) {
    CONNECTED("连接成功"),
    DISCONNECTED("连接断开"),
    RECONNECTING("开始重连"),
    NETWORK_CHANGED("网络变化"),
    HEARTBEAT_TIMEOUT("心跳超时"),
    AUTHENTICATION_FAILED("认证失败"),
    USER_DISCONNECTED("用户断开"),
    SERVICE_RESTARTED("服务恢复"),
}

data class LinkEvent(
    val kind: LinkEventKind,
    val atMs: Long,
    val detail: String = "",
)
