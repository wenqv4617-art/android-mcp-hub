package com.androidmcphub.app.ui.model

import com.androidmcphub.app.protocol.LinkState

/**
 * 状态"语气"。
 *
 * 它是展示模型与主题之间的**唯一契约**：映射函数只决定语气，主题只决定颜色。
 * 这样"换配色/加大字号"不会碰到任何一个 `when (LinkState)`。
 */
enum class StatusTone {
    Idle,
    Connecting,
    Connected,
    Reconnecting,
    Failed,
}

/**
 * 连接状态的**纯展示模型**。
 *
 * 为什么要有这一层（改版前的做法是页面里三个 `when (LinkState)`）：
 *  * 首页、设置页、诊断页都要显示同一份状态，穷举逻辑必须只有一份；
 *  * `LinkState` 是**协议**类型，UI 不该在多个文件里重复解释它；
 *  * 它是纯数据，可以在 JVM 单测里把 7 种状态逐一钉死。
 *
 * 时间相关字段一律是**调用方传进来的**时间戳（[titleAt]/[remainingSeconds] 的 `nowMs`），
 * 模型自己不读 `System.currentTimeMillis()` —— 否则倒计时断言会随机器时间抖动。
 */
data class LinkStatusView(
    /** 与时间无关的基础标题，例如「重连等待」「已连接」。完整标题用 [titleAt]。 */
    val title: String,
    /** 一句话解释当前状态（含失败原因）。 */
    val detail: String,
    val tone: StatusTone,
    /** 连接代次（只有 Connected 有）。 */
    val generation: Long? = null,
    /** 重连截止时刻（只有 Reconnecting 有），用于倒计时。 */
    val retryAtMs: Long? = null,
    /** 第几次重连尝试（只有 Reconnecting 有）。 */
    val attempt: Int? = null,
) {

    /** 距离下次重连还有几秒（向上取整，最小 0，永不出现负数）。非重连状态返回 null。 */
    fun remainingSeconds(nowMs: Long): Long? {
        val deadline = retryAtMs ?: return null
        return ((deadline - nowMs).coerceAtLeast(0L) + 999L) / 1000L
    }

    /** 界面上真正显示的标题：代次/倒计时都拼在这里，页面不做字符串加工。 */
    fun titleAt(nowMs: Long): String {
        val remain = remainingSeconds(nowMs)
        return when {
            remain != null -> "$title（第 ${attempt ?: 0} 次，${remain}s 后）"
            generation != null -> "$title（代次 $generation）"
            else -> title
        }
    }
}

/**
 * 协议状态 → 展示模型。
 *
 * [serviceRunning] 只影响 Idle 的解释：同样是"未连接"，
 * "前台服务在跑但没连"和"服务根本没起来"是两种不同的排查方向。
 */
fun LinkState.toStatusView(serviceRunning: Boolean): LinkStatusView = when (this) {
    is LinkState.Idle -> LinkStatusView(
        title = "已断开",
        detail = if (serviceRunning) "前台服务在运行，当前没有连接" else "前台服务未运行",
        tone = StatusTone.Idle,
    )

    is LinkState.Connecting -> LinkStatusView(
        title = "连接中",
        detail = "正在建立 WebSocket 连接并等待 welcome",
        tone = StatusTone.Connecting,
    )

    is LinkState.Connected -> LinkStatusView(
        title = "已连接",
        detail = "前台服务在运行，保持应用层心跳",
        tone = StatusTone.Connected,
        generation = generation,
    )

    is LinkState.Reconnecting -> LinkStatusView(
        title = "重连等待",
        detail = "按指数退避 + 抖动重连，成功后计数归零",
        tone = StatusTone.Reconnecting,
        retryAtMs = deadlineMs,
        attempt = attempt,
    )

    is LinkState.AuthFailed -> LinkStatusView(
        title = "认证失败",
        detail = "凭据被拒绝（$detail），需到「设置」修改设备令牌后手动重连",
        tone = StatusTone.Failed,
    )

    // 终态：必须说清"为什么不会自己好"，并给出唯一出口（改令牌后重新连接）。
    is LinkState.Revoked -> LinkStatusView(
        title = "凭据已被撤销",
        detail = "服务端撤销了本机凭据（$detail），已停止自动重连；" +
            "请到「设置」更新设备令牌或重新配对后手动重连",
        tone = StatusTone.Failed,
    )

    is LinkState.Error -> LinkStatusView(
        title = "出错",
        detail = message,
        tone = StatusTone.Failed,
    )
}
