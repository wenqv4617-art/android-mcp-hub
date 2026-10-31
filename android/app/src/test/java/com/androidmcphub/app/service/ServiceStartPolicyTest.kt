package com.androidmcphub.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V1.6.1：系统重建前台服务时该不该恢复连接。
 *
 * 旧实现把 `intent == null`（系统重建）当成"异常调用"直接忽略，同时返回
 * `START_NOT_STICKY` —— 于是 **进程被系统回收之后，无论用户多想保持连接，
 * 服务都不会再起来**。这个文件把新的判定规则钉死，尤其是两条容易被"顺手改坏"的边界：
 *
 *  1. 系统重建**必须**恢复连接（用户此前点过「连接」）；
 *  2. 用户点过「断开」之后，系统重建**绝不能**偷偷再连。
 */
class ServiceStartPolicyTest {

    @Test
    fun `用户点连接时启动服务就连接`() {
        assertEquals(
            ServiceStartDecision.CONNECT,
            ServiceStartPolicy.decide(PhoneLinkService.ACTION_CONNECT, linkDesired = false),
        )
    }

    @Test
    fun `用户点断开时启动服务就断开`() {
        assertEquals(
            ServiceStartDecision.DISCONNECT,
            ServiceStartPolicy.decide(PhoneLinkService.ACTION_DISCONNECT, linkDesired = true),
        )
    }

    @Test
    fun `系统重建（intent 为空）时按用户此前的连接意图恢复`() {
        assertEquals(
            "系统回收进程后重建服务，必须恢复用户明确要求过的连接",
            ServiceStartDecision.CONNECT,
            ServiceStartPolicy.decide(action = null, linkDesired = true),
        )
    }

    @Test
    fun `空 action 与 null 等价`() {
        assertEquals(ServiceStartDecision.CONNECT, ServiceStartPolicy.decide("", linkDesired = true))
        assertEquals(ServiceStartDecision.IGNORE, ServiceStartPolicy.decide("", linkDesired = false))
    }

    @Test
    fun `用户此前断开过时，系统重建不得自动连接`() {
        assertEquals(
            "用户已主动断开：重建服务只能停止，不能偷偷再连",
            ServiceStartDecision.IGNORE,
            ServiceStartPolicy.decide(action = null, linkDesired = false),
        )
    }

    @Test
    fun `未知 action 不猜用户意图`() {
        assertEquals(
            ServiceStartDecision.IGNORE,
            ServiceStartPolicy.decide("com.androidmcphub.app.action.UNKNOWN_FROM_OLD_VERSION", linkDesired = true),
        )
    }

    @Test
    fun `只有想保持连接时服务才粘性`() {
        assertTrue(
            "想保持连接就必须粘性，否则被系统杀掉后不会重建",
            ServiceStartPolicy.shouldReturnSticky(linkDesired = true),
        )
        assertFalse(
            "用户断开后进程退出就是退出，不能被 START_STICKY 拉回来",
            ServiceStartPolicy.shouldReturnSticky(linkDesired = false),
        )
    }
}
