package com.androidmcphub.app.service

/**
 * 默认网络回调的去重/过滤规则。
 *
 * 为什么需要：`registerDefaultNetworkCallback` 在网络切换时**不保证**回调顺序。
 * Wi-Fi → 蜂窝时，完全可能先收到新网络的 `onAvailable`，几秒后才收到旧网络的
 * `onLost`。旧实现把任何 `onLost` 都当成"当前连接的网络没了"，于是会在新连接
 * 已经建立之后，用一条迟到的旧网络事件把它关掉、再重连一次 —— 叠加 VPN 的
 * 网络重建，表现就是"每隔一会儿自己断一次"。
 *
 * 抽成泛型纯函数（不出现 `android.net.Network`），这样 JVM 单测可以直接用
 * 字符串当网络身份，不需要 Robolectric 去构造系统对象。
 */
object NetworkCallbackPolicy {

    /**
     * 这条 `onLost` 是否应该触发"当前连接的网络已断开"。
     *
     * - `lastDefault` 非空且不是这条网络：它是旧网络迟到的回调，忽略；
     * - `lastDefault` 为空（已经处理过一条 lost）且系统仍有别的默认网络：同样是迟到回调；
     * - 其余情况（正是当前默认网络，或系统已无默认网络）：处理。
     */
    fun <T> shouldProcessLost(lost: T, lastDefault: T?, active: T?): Boolean {
        if (lastDefault != null) return lost == lastDefault
        return active == null || active == lost
    }

    /**
     * 同一条网络在 [quietMs] 内的重复 `onAvailable` 是否应该忽略。
     *
     * 重复上报会让"网络已恢复"分支反复取消退避并立刻重连，退避窗口形同虚设。
     * 只抑制**同一条网络**的短时间重复；真正换网（网络身份变了）不受影响。
     */
    fun <T> shouldProcessAvailable(
        network: T,
        lastAvailable: T?,
        lastAvailableAtMs: Long,
        nowMs: Long,
        quietMs: Long = DEFAULT_QUIET_MS,
    ): Boolean {
        if (network != lastAvailable) return true
        // 时间回拨（测试假时钟/系统校时）时不抑制，宁可多处理一次也不吞掉真实恢复。
        if (nowMs < lastAvailableAtMs) return true
        return nowMs - lastAvailableAtMs >= quietMs
    }

    /** 重复 onAvailable 的抑制窗口：足够覆盖同一网络的能力刷新，又不影响真实换网。 */
    const val DEFAULT_QUIET_MS: Long = 2_000L
}
