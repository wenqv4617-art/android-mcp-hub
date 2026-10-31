package com.androidmcphub.app.service

import com.androidmcphub.app.core.LinkEvent
import com.androidmcphub.app.core.LinkEventKind
import com.androidmcphub.app.core.LogLevel
import com.androidmcphub.app.core.NetworkKind
import com.androidmcphub.app.protocol.LinkState
import com.androidmcphub.app.protocol.PingOutcome
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「服务 → 界面」这条粘合链路的单测（Gate 1 补测）。
 *
 * 为什么专门测 [LinkBus]：Gate 1 的唯一验收标准里有一条是
 * **"手机端能显示对应 Ping 记录"**，而界面拿到的每一个值都必须经过这个对象。
 * `PhoneLinkService` / `HubViewModel` / `HubScreen` 都依赖 Android framework，
 * 在 JVM 单测里跑不起来；[LinkBus] 是这条链路里唯一纯 Kotlin 的部分，
 * 所以把"界面能看到什么"的规则钉在这里。
 *
 * [ConnectGateTest] 已经覆盖了 `Error`/`AuthFailed` 写入最近错误、以及
 * `resetLink()` 不能让错误消失这几点，这里**不重复**，只补它没覆盖的：
 * Ping 记录的写入与覆盖、前台服务运行标志、状态透传、`Reconnecting`/`Idle`
 * 对最近错误的影响、以及日志监听与 200 条上限。
 *
 * 边界（写清楚，别让这份测试显得比它实际能证明的更多）：
 *  - 它证明的是**状态总线自己的规则**，不是真机上界面真的把字画出来了；
 *    Compose 的渲染、ViewModel 的订阅仍然需要真机验证（见 TEST_REPORT.md 的 NOT RUN 小节）。
 *  - [LinkBus] 是全局 `object`，测试之间共用同一份状态，所以每个用例后都要复位。
 */
class LinkBusTest {

    @After
    fun tearDown() {
        LinkBus.log.clear()
        LinkBus.clearLastError()
        LinkBus.resetLink()
        LinkBus.resetDiagnostics()
    }

    private fun outcome(requestId: String, rttMs: Long, frame: String? = """{"type":"phone_pong"}""") =
        PingOutcome(
            requestId = requestId,
            serverTimestampMs = 10_000L,
            receivedAtMs = 10_000L + rttMs / 2,
            oneWayMs = rttMs / 2,
            rttMs = rttMs,
            frame = frame,
        )

    // ---- 最近一次 Ping：验收标准里"界面显示 Ping 记录"读的就是这里 --------

    @Test
    fun `publishPing 会把这次 Ping 完整写进 lastPing`() {
        val outcome = outcome("req-0001", rttMs = 84L)

        LinkBus.publishPing(outcome)

        val shown = LinkBus.lastPing.value
        assertEquals("req-0001", shown?.requestId)
        assertEquals(84L, shown?.rttMs)
        assertEquals(42L, shown?.oneWayMs)
        // 界面要显示"这次是重复 request_id、按 R2 没回 pong"也靠这个字段
        assertFalse(shown?.duplicate ?: true)
    }

    @Test
    fun `第二次 Ping 覆盖第一次 —— 界面显示的是最近一次，不是第一次`() {
        LinkBus.publishPing(outcome("req-0001", rttMs = 100L))
        LinkBus.publishPing(outcome("req-0002", rttMs = 55L))

        assertEquals("req-0002", LinkBus.lastPing.value?.requestId)
        assertEquals(55L, LinkBus.lastPing.value?.rttMs)
    }

    @Test
    fun `重复 request_id 的 Ping 也照样刷新展示（去重只决定回不回 pong）`() {
        val duplicate = outcome("req-0009", rttMs = 70L, frame = null)

        LinkBus.publishPing(duplicate)

        assertEquals("req-0009", LinkBus.lastPing.value?.requestId)
        assertTrue("frame == null 就代表被判定为重复", LinkBus.lastPing.value?.duplicate ?: false)
    }

    @Test
    fun `publishPing(null) 会清掉最近一次 Ping 记录`() {
        LinkBus.publishPing(outcome("req-0001", rttMs = 30L))

        LinkBus.publishPing(null)

        assertNull(LinkBus.lastPing.value)
    }

    @Test
    fun `收到 Ping 不会顺带改动连接状态`() {
        LinkBus.publishState(LinkState.Connected(generation = 4))

        LinkBus.publishPing(outcome("req-0001", rttMs = 12L))

        assertEquals(LinkState.Connected(generation = 4), LinkBus.state.value)
    }

    // ---- 前台服务运行标志 --------------------------------------------------

    @Test
    fun `publishServiceRunning 反映前台服务是否在跑`() {
        LinkBus.publishServiceRunning(true)
        assertTrue(LinkBus.serviceRunning.value)

        LinkBus.publishServiceRunning(false)
        assertFalse(LinkBus.serviceRunning.value)
    }

    @Test
    fun `服务停止复位连接状态时，前台运行标志由服务自己收尾`() {
        // resetLink() 只碰"连接相关"的展示状态，它**不**负责这个标志 ——
        // 这条用例把这个分工钉住，免得以后有人以为 resetLink 会顺手清掉它。
        LinkBus.publishServiceRunning(true)

        LinkBus.resetLink()

        assertTrue(LinkBus.serviceRunning.value)
    }

    // ---- 连接状态透传与最近错误的生命周期 ----------------------------------

    @Test
    fun `publishState 原样透传连接状态（界面按它决定显示什么）`() {
        LinkBus.publishState(LinkState.Connected(generation = 7))
        assertEquals(LinkState.Connected(generation = 7), LinkBus.state.value)

        val reconnecting = LinkState.Reconnecting(attempt = 2, delayMs = 4_000L, deadlineMs = 64_000L)
        LinkBus.publishState(reconnecting)
        assertEquals(reconnecting, LinkBus.state.value)
    }

    @Test
    fun `重连中状态不清掉最近错误（用户看得见上次为什么断的）`() {
        LinkBus.publishState(LinkState.Error("连接被拒绝"))

        LinkBus.publishState(LinkState.Reconnecting(attempt = 1, delayMs = 1_000L, deadlineMs = 30_000L))

        assertEquals("连接被拒绝", LinkBus.lastError.value)
    }

    @Test
    fun `回到 Idle 也不清最近错误 —— 只有显式清空与 resetLink 的语义之外才会消失`() {
        LinkBus.publishState(LinkState.AuthFailed("设备令牌无效"))

        LinkBus.publishState(LinkState.Idle)

        assertEquals(LinkState.Idle, LinkBus.state.value)
        assertEquals("设备令牌无效", LinkBus.lastError.value)
    }

    // ---- 日志：服务写、界面读 ----------------------------------------------

    @Test
    fun `日志写入会同步进 logs 流，级别与顺序都对`() {
        LinkBus.log.info("开始连接")
        LinkBus.log.warn("第一次重连")
        LinkBus.log.error("连接失败")

        val logs = LinkBus.logs.value
        assertEquals(3, logs.size)
        // 最新的在最前
        assertEquals("连接失败", logs[0].text)
        assertEquals(LogLevel.ERROR, logs[0].level)
        assertEquals("第一次重连", logs[1].text)
        assertEquals(LogLevel.WARN, logs[1].level)
        assertEquals("开始连接", logs[2].text)
        assertEquals(LogLevel.INFO, logs[2].level)
    }

    @Test
    fun `logs 流与环形缓冲一样封顶 200 条，且丢掉的是最老的`() {
        repeat(250) { LinkBus.log.info("第${it + 1}条") }

        val logs = LinkBus.logs.value
        assertEquals(200, logs.size)
        assertEquals("第250条", logs.first().text)
        assertFalse("最老的条目应该已经被挤出去", logs.any { it.text == "第1条" })
        assertEquals("第51条", logs.last().text)
    }

    @Test
    fun `清空日志会让 logs 流立刻变空`() {
        LinkBus.log.info("一些日志")

        LinkBus.log.clear()

        assertTrue(LinkBus.logs.value.isEmpty())
    }

    // ---- V1.6.1：结构化事件与诊断统计 --------------------------------------

    @Test
    fun `事件按时间倒序排列且封顶 50 条`() {
        repeat(60) { i ->
            LinkBus.recordEvent(LinkEventKind.NETWORK_CHANGED, detail = "第${i + 1}条", atMs = 1_000L + i)
        }

        val events = LinkBus.events.value
        assertEquals(50, events.size)
        assertEquals("第60条", events.first().detail)
        assertFalse("最老的条目应该被挤出去", events.any { it.detail == "第10条" })
    }

    @Test
    fun `连接成功与断开分别刷新诊断页的时间与原因`() {
        LinkBus.recordEvent(LinkEventKind.CONNECTED, atMs = 10_000L)
        LinkBus.recordEvent(LinkEventKind.DISCONNECTED, detail = "服务端关闭 code=1006", atMs = 20_000L)

        val d = LinkBus.diagnostics.value
        assertEquals(10_000L, d.lastConnectedAtMs)
        assertEquals(20_000L, d.lastDisconnectAtMs)
        assertEquals("服务端关闭 code=1006", d.lastDisconnectReason)
    }

    @Test
    fun `重连次数只由 RECONNECTING 事件累加`() {
        LinkBus.recordEvent(LinkEventKind.CONNECTED)
        LinkBus.recordEvent(LinkEventKind.RECONNECTING, detail = "第 1 次")
        LinkBus.recordEvent(LinkEventKind.RECONNECTING, detail = "第 2 次")

        assertEquals(2, LinkBus.diagnostics.value.reconnectCount)
    }

    @Test
    fun `心跳成功与网络类型会写进诊断页`() {
        LinkBus.publishHeartbeatAck(0L)
        assertNull("0 是「没有数据」，不能显示成 1970 年", LinkBus.diagnostics.value.lastHeartbeatAckAtMs)

        LinkBus.publishHeartbeatAck(1_700_000_000_000L)
        LinkBus.publishNetworkKind(NetworkKind.WIFI)

        assertEquals(1_700_000_000_000L, LinkBus.diagnostics.value.lastHeartbeatAckAtMs)
        assertEquals("Wi-Fi", LinkBus.diagnostics.value.networkLabel)
    }

    @Test
    fun `清空事件不影响当前连接状态与统计`() {
        LinkBus.publishState(LinkState.Connected(generation = 5))
        LinkBus.recordEvent(LinkEventKind.CONNECTED, atMs = 10_000L)

        LinkBus.clearEvents()

        assertTrue(LinkBus.events.value.isEmpty())
        assertEquals(LinkState.Connected(generation = 5), LinkBus.state.value)
        assertEquals("统计是「发生过什么」，清列表不该把它抹掉", 10_000L, LinkBus.diagnostics.value.lastConnectedAtMs)
    }

    // ---- V1.6.2：断开来源必须能被诊断页区分 --------------------------------

    @Test
    fun `V162 服务销毁记为独立来源，绝不写成用户断开`() {
        LinkBus.publishLinkDesired(true)

        LinkBus.recordEvent(
            LinkEventKind.SERVICE_DESTROYED,
            detail = "系统停止前台服务（非用户操作，连接意图=true）",
            atMs = 30_000L,
        )

        val d = LinkBus.diagnostics.value
        assertEquals(30_000L, d.lastDisconnectAtMs)
        assertEquals("service_destroyed", d.lastDisconnectSource)
        assertTrue("断开原因要能解释为用户没点断开", d.lastDisconnectReason.orEmpty().contains("非用户操作"))
        assertTrue("连接意图仍为 true —— 系统停止不是用户改主意", d.linkDesired)
        assertFalse(
            "服务销毁不得产生 USER_DISCONNECTED：${LinkBus.events.value.map { it.kind }}",
            LinkBus.events.value.any { it.kind == LinkEventKind.USER_DISCONNECTED },
        )
    }

    @Test
    fun `V162 用户断开的事件细节带真实来源`() {
        LinkBus.recordEvent(LinkEventKind.USER_DISCONNECTED, detail = "source=ui_button")

        assertEquals("user:ui_button", LinkBus.diagnostics.value.lastDisconnectSource)
    }

    @Test
    fun `V162 网络类失败按闭集类别落成来源标签`() {
        LinkBus.recordEvent(LinkEventKind.DISCONNECTED, detail = "连接失败 category=connection_reset")
        assertEquals("network:connection_reset", LinkBus.diagnostics.value.lastDisconnectSource)

        LinkBus.recordEvent(LinkEventKind.DISCONNECTED, detail = "连接失败 category=dns_failure")
        assertEquals("network:dns_failure", LinkBus.diagnostics.value.lastDisconnectSource)
    }

    @Test
    fun `V162 服务端关闭、握手超时、心跳超时与认证失败各有独立来源`() {
        LinkBus.recordEvent(LinkEventKind.DISCONNECTED, detail = "服务端关闭 code=4000")
        assertEquals("remote_close", LinkBus.diagnostics.value.lastDisconnectSource)

        LinkBus.recordEvent(LinkEventKind.DISCONNECTED, detail = "握手超时（21000ms）")
        assertEquals("handshake_timeout", LinkBus.diagnostics.value.lastDisconnectSource)

        LinkBus.recordEvent(LinkEventKind.HEARTBEAT_TIMEOUT, detail = "90000ms 无服务端帧")
        assertEquals("heartbeat_timeout", LinkBus.diagnostics.value.lastDisconnectSource)

        LinkBus.recordEvent(LinkEventKind.AUTHENTICATION_FAILED, detail = "HTTP 401，不自动重连")
        assertEquals("auth_failed", LinkBus.diagnostics.value.lastDisconnectSource)
    }

    @Test
    fun `V162 心跳发送时间单独记录，且 0 不显示成 1970`() {
        LinkBus.publishHeartbeatSent(0L)
        assertNull(LinkBus.diagnostics.value.lastHeartbeatSentAtMs)

        LinkBus.publishHeartbeatSent(1_700_000_000_500L)

        assertEquals(1_700_000_000_500L, LinkBus.diagnostics.value.lastHeartbeatSentAtMs)
        assertNull("发送不等于是成功，两者不能互相顶替", LinkBus.diagnostics.value.lastHeartbeatAckAtMs)
    }

    @Test
    fun `V162 连接意图与服务停止原因可以独立更新`() {
        LinkBus.publishLinkDesired(true)
        assertTrue(LinkBus.diagnostics.value.linkDesired)

        LinkBus.publishServiceStop("前台服务启动失败（foreground_denied）")
        assertEquals("前台服务启动失败（foreground_denied）", LinkBus.diagnostics.value.lastServiceStopReason)

        // 服务重新启动时清空停止原因，但不得改动连接意图。
        LinkBus.publishServiceStop(null)
        assertNull(LinkBus.diagnostics.value.lastServiceStopReason)
        assertTrue(LinkBus.diagnostics.value.linkDesired)
    }
}
