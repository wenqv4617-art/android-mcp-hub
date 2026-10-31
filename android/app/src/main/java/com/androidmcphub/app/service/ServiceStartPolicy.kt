package com.androidmcphub.app.service

/**
 * `onStartCommand` 收到一次启动请求后该做什么。
 *
 * 抽成纯函数是因为这段规则有两个容易写错的分支：
 *  - 系统重建服务时 `intent == null`，旧实现把它当"异常调用"忽略，于是
 *    `START_STICKY` 也救不回连接；
 *  - 用户已经断开（`linkDesired == false`）时，系统重建**不能**擅自再连。
 *
 * [PhoneLinkService] 只负责把结果变成前台通知/连接动作；这里可以在 JVM 上逐条钉住。
 */
enum class ServiceStartDecision {
    /** 建立（或恢复）连接。 */
    CONNECT,

    /** 用户主动断开。 */
    DISCONNECT,

    /** 没有可执行的意图：记日志并停止自己。 */
    IGNORE,
}

object ServiceStartPolicy {

    fun decide(action: String?, linkDesired: Boolean): ServiceStartDecision = when (action) {
        PhoneLinkService.ACTION_CONNECT -> ServiceStartDecision.CONNECT
        PhoneLinkService.ACTION_DISCONNECT -> ServiceStartDecision.DISCONNECT

        // 系统重建：只有用户此前明确希望保持连接时才恢复。
        null, "" -> if (linkDesired) ServiceStartDecision.CONNECT else ServiceStartDecision.IGNORE

        // 未知 action（升级期间残留的 PendingIntent 等）：不猜用户意图。
        else -> ServiceStartDecision.IGNORE
    }

    /**
     * 服务被系统杀掉后要不要重建。
     *
     * 只有"用户希望保持连接"时才粘性；用户断开后进程退出就是退出，
     * 不能被系统以 `START_STICKY` 的名义重新拉起。
     */
    fun shouldReturnSticky(linkDesired: Boolean): Boolean = linkDesired
}
