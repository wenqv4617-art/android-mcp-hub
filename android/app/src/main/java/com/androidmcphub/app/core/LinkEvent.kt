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

data class LinkEvent(
    val kind: LinkEventKind,
    val atMs: Long,
    val detail: String = "",
)
