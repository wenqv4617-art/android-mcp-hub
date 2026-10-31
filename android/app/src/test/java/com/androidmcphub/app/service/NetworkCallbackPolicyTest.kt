package com.androidmcphub.app.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V1.6.2：默认网络回调的过滤规则。
 *
 * 真机上"没点断开却自己断"的一个独立成因是：`registerDefaultNetworkCallback` 不保证
 * 回调顺序。Wi-Fi → 蜂窝（或 VPN 隧道重建）时，旧网络的 `onLost` 可能在新连接已经
 * 建立之后才到；旧实现见到任何 `onLost` 就 `client.onNetworkLost()`，于是把健康的
 * 新 socket 关掉、再重连一次。规则本身是纯函数，所以这里用字符串当网络身份，
 * 不需要 Robolectric 去构造 `android.net.Network`。
 */
class NetworkCallbackPolicyTest {

    @Test
    fun `迟到的旧网络 onLost 不得关掉已经建立在新网络上的连接`() {
        // 当前默认网络是 "cell"；"wifi" 是切换前的旧网络，它的 lost 迟到。
        assertFalse(
            NetworkCallbackPolicy.shouldProcessLost(
                lost = "wifi",
                lastDefault = "cell",
                active = "cell",
            ),
        )
    }

    @Test
    fun `当前默认网络的 onLost 必须处理`() {
        assertTrue(
            NetworkCallbackPolicy.shouldProcessLost(
                lost = "cell",
                lastDefault = "cell",
                active = "cell",
            ),
        )
    }

    @Test
    fun `已经处理过一次 lost 之后，系统仍有别的网络时忽略后续迟到回调`() {
        // lastDefault 已被上一条 lost 置空；active 是系统刚切过去的新网络。
        assertFalse(
            NetworkCallbackPolicy.shouldProcessLost(
                lost = "wifi",
                lastDefault = null,
                active = "cell",
            ),
        )
    }

    @Test
    fun `系统确实没有默认网络时必须处理 —— 这是真正的断网`() {
        assertTrue(
            NetworkCallbackPolicy.shouldProcessLost(
                lost = "cell",
                lastDefault = null,
                active = null,
            ),
        )
    }

    @Test
    fun `同一条网络在抑制窗口内的重复 onAvailable 被忽略`() {
        assertFalse(
            NetworkCallbackPolicy.shouldProcessAvailable(
                network = "wifi",
                lastAvailable = "wifi",
                lastAvailableAtMs = 10_000L,
                nowMs = 10_500L,
            ),
        )
    }

    @Test
    fun `抑制窗口过去之后同一条网络的 onAvailable 重新处理`() {
        assertTrue(
            NetworkCallbackPolicy.shouldProcessAvailable(
                network = "wifi",
                lastAvailable = "wifi",
                lastAvailableAtMs = 10_000L,
                nowMs = 10_000L + NetworkCallbackPolicy.DEFAULT_QUIET_MS,
            ),
        )
    }

    @Test
    fun `换网（网络身份变了）永远不受抑制窗口影响`() {
        assertTrue(
            NetworkCallbackPolicy.shouldProcessAvailable(
                network = "cell",
                lastAvailable = "wifi",
                lastAvailableAtMs = 10_000L,
                nowMs = 10_001L,
            ),
        )
    }

    @Test
    fun `时钟回拨时宁可多处理一次，也不吞掉真实恢复`() {
        assertTrue(
            NetworkCallbackPolicy.shouldProcessAvailable(
                network = "wifi",
                lastAvailable = "wifi",
                lastAvailableAtMs = 10_000L,
                nowMs = 9_000L,
            ),
        )
    }

    @Test
    fun `第一次 onAvailable 一定处理`() {
        assertTrue(
            NetworkCallbackPolicy.shouldProcessAvailable(
                network = "wifi",
                lastAvailable = null,
                lastAvailableAtMs = 0L,
                nowMs = 5L,
            ),
        )
    }
}
