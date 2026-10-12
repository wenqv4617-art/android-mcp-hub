package com.androidmcphub.app.ui.model

import com.androidmcphub.app.protocol.LinkState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 展示模型映射的 JVM 单测。
 *
 * 覆盖 `LinkState` 的**全部 7 个变体**（sealed interface，漏一个编译期就会红）。
 * 所有时间断言都用固定 `nowMs`，不读系统时钟。
 */
class LinkStatusViewTest {

    /** 固定的"现在"。倒计时断言全部相对它计算。 */
    private val now = 1_700_000_000_000L

    @Test
    fun `Idle 且前台服务在跑时说明服务还活着`() {
        val view = LinkState.Idle.toStatusView(serviceRunning = true)
        assertEquals("已断开", view.title)
        assertEquals("前台服务在运行，当前没有连接", view.detail)
        assertEquals(StatusTone.Idle, view.tone)
        assertNull(view.generation)
        assertNull(view.retryAtMs)
        assertEquals("已断开", view.titleAt(now))
    }

    @Test
    fun `Idle 且前台服务没跑时说明服务没起来`() {
        val view = LinkState.Idle.toStatusView(serviceRunning = false)
        assertEquals("前台服务未运行", view.detail)
        assertEquals(StatusTone.Idle, view.tone)
    }

    @Test
    fun `Connecting 说明在等 welcome`() {
        val view = LinkState.Connecting.toStatusView(serviceRunning = true)
        assertEquals("连接中", view.title)
        assertTrue(view.detail.contains("welcome"))
        assertEquals(StatusTone.Connecting, view.tone)
        assertNull(view.generation)
    }

    @Test
    fun `Connected 带出连接代次`() {
        val view = LinkState.Connected(generation = 7L).toStatusView(serviceRunning = true)
        assertEquals("已连接", view.title)
        assertEquals(StatusTone.Connected, view.tone)
        assertEquals(7L, view.generation)
        assertEquals("已连接（代次 7）", view.titleAt(now))
        assertNull(view.retryAtMs)
    }

    @Test
    fun `Reconnecting 给出第几次与倒计时`() {
        val view = LinkState.Reconnecting(attempt = 3, delayMs = 2_000L, deadlineMs = now + 2_500L)
            .toStatusView(serviceRunning = true)

        assertEquals("重连等待", view.title)
        assertEquals(StatusTone.Reconnecting, view.tone)
        assertEquals(3, view.attempt)
        assertEquals(now + 2_500L, view.retryAtMs)
        // 2500ms 向上取整到 3s（宁可多说 1 秒，也不要显示"0s 后"却还在等）
        assertEquals(3L, view.remainingSeconds(now))
        assertEquals("重连等待（第 3 次，3s 后）", view.titleAt(now))
        assertTrue(view.detail.contains("退避"))
    }

    @Test
    fun `Reconnecting 的倒计时不会变成负数`() {
        val view = LinkState.Reconnecting(attempt = 1, delayMs = 1_000L, deadlineMs = now - 5_000L)
            .toStatusView(serviceRunning = false)
        assertEquals(0L, view.remainingSeconds(now))
        assertEquals("重连等待（第 1 次，0s 后）", view.titleAt(now))
    }

    @Test
    fun `非重连状态没有倒计时`() {
        assertNull(LinkState.Idle.toStatusView(true).remainingSeconds(now))
        assertNull(LinkState.Connected(1L).toStatusView(true).remainingSeconds(now))
        assertNull(LinkState.Connecting.toStatusView(true).remainingSeconds(now))
    }

    @Test
    fun `AuthFailed 带出原因并指向设置页`() {
        val view = LinkState.AuthFailed(detail = "401 unauthorized").toStatusView(serviceRunning = true)
        assertEquals("认证失败", view.title)
        assertEquals(StatusTone.Failed, view.tone)
        assertTrue(view.detail.contains("401 unauthorized"))
        assertTrue(view.detail.contains("设置"))
    }

    @Test
    fun `Revoked 是终态并且给出唯一出口`() {
        val view = LinkState.Revoked(detail = "device_revoked").toStatusView(serviceRunning = true)
        assertEquals("凭据已被撤销", view.title)
        assertEquals(StatusTone.Failed, view.tone)
        assertTrue(view.detail.contains("device_revoked"))
        assertTrue(view.detail.contains("已停止自动重连"))
        assertTrue(view.detail.contains("重新配对"))
    }

    @Test
    fun `Error 直接展示服务给的消息`() {
        val view = LinkState.Error(message = "前台服务启动失败").toStatusView(serviceRunning = false)
        assertEquals("出错", view.title)
        assertEquals("前台服务启动失败", view.detail)
        assertEquals(StatusTone.Failed, view.tone)
    }

    @Test
    fun `每个状态都有非空标题与说明`() {
        val all: List<LinkState> = listOf(
            LinkState.Idle,
            LinkState.Connecting,
            LinkState.Connected(1L),
            LinkState.Reconnecting(1, 1_000L, now + 1_000L),
            LinkState.AuthFailed("x"),
            LinkState.Revoked("y"),
            LinkState.Error("z"),
        )
        // 显式断言"7 个变体都被覆盖"，将来加变体时这条会提醒补用例
        assertEquals(7, all.size)
        all.forEach { state ->
            val view = state.toStatusView(serviceRunning = true)
            assertTrue(view.title.isNotBlank())
            assertTrue(view.detail.isNotBlank())
            assertNotNull(view.tone)
            assertFalse(view.titleAt(now).contains("null"))
        }
    }
}
