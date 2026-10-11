package com.androidmcphub.app.protocol

import com.androidmcphub.app.capability.Capability
import com.androidmcphub.app.capability.CapabilityDispatcher
import com.androidmcphub.app.capability.CapabilityResult
import com.androidmcphub.app.core.HubConfig
import com.androidmcphub.app.core.LogLevel
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * 连接状态机的行为测试：R1–R6 与连接生命周期。
 *
 * 时钟与随机源都注入，退避抖动设为 0，因此所有断言都是确定性的。
 */
class PhoneLinkTest {

    private val baseConfig = HubConfig(
        serverUrl = "wss://example.test/phone/ws",
        deviceId = "test-phone-1",
        pingTimeoutMs = 10_000L,
        heartbeatIntervalMs = 30_000L,
        reconnectInitialDelayMs = 1_000L,
        reconnectMaxDelayMs = 5_000L,
        reconnectJitterRatio = 0.0,
    )

    /** 固定时钟 + 记录日志与状态的最小测试夹具。 */
    private inner class Harness(
        private val config: HubConfig = baseConfig,
        /**
         * Gate 2：注入到状态机里的能力注册表。默认是**空表** ——
         * 也就是说默认情况下任何 `tool_request` 都应当得到 `unsupported_tool`。
         */
        capabilities: List<Capability> = emptyList(),
    ) {
        var now: Long = 1_760_000_000_000L
        val logs = mutableListOf<Pair<LogLevel, String>>()
        val states = mutableListOf<LinkState>()
        val pings = mutableListOf<PingOutcome>()

        val link = PhoneLink(
            config = config,
            random = Random(20241201),
            clock = { now },
            log = { level, text -> logs += level to text },
            dispatcher = CapabilityDispatcher(capabilities, clock = { now }),
        )

        init {
            link.onStateChanged = { states += it }
            link.onPing = { pings += it }
        }

        fun welcomeFrame(
            generation: Long = 3L,
            heartbeatMs: Long = 30_000L,
            pingTimeoutMs: Long = 10_000L,
            protocolVersion: String = PROTOCOL_VERSION,
        ): String = JSONObject()
            .put("type", "welcome")
            .put("device_id", config.deviceId)
            .put("connection_generation", generation)
            .put("server_time", now)
            .put("heartbeat_interval_ms", heartbeatMs)
            .put("ping_timeout_ms", pingTimeoutMs)
            .put("max_pending_requests", 16)
            .put("protocol_version", protocolVersion)
            .toString()

        fun pingFrame(id: String, timestamp: Long = now): String = JSONObject()
            .put("type", "phone_ping")
            .put("request_id", id)
            .put("timestamp", timestamp)
            .toString()

        /** 走完 start → open → welcome，进入「已连接（代次 3）」。 */
        fun connect() {
            link.start()
            link.onSocketOpened(androidSdk = 35)
            link.onTextFrame(welcomeFrame())
        }

        fun logTexts(): String = logs.joinToString("\n") { it.second }
    }

    // ---- 建连与握手 --------------------------------------------------------

    @Test
    fun `start 之后进入连接中并请求打开连接`() {
        val h = Harness()

        val actions = h.link.start()

        assertEquals(listOf(LinkAction.Open), actions)
        assertEquals(LinkState.Connecting, h.link.state)
        assertTrue(!h.link.userStopped)
    }

    @Test
    fun `onSocketOpened 发送 hello 帧且内容符合契约`() {
        val h = Harness()
        // 真实的 onOpen 只可能发生在 start() 发起的连接上；先 start 才会进 Connecting。
        h.link.start()

        val actions = h.link.onSocketOpened(androidSdk = 35)

        val send = actions.filterIsInstance<LinkAction.Send>().single()
        val hello = JSONObject(send.frame)
        assertEquals("hello", hello.getString("type"))
        assertEquals("test-phone-1", hello.getString("device_id"))
        assertEquals(PROTOCOL_VERSION, hello.getString("protocol_version"))
        assertEquals(APP_VERSION, hello.getString("app_version"))
        assertEquals(35, hello.getInt("android_sdk"))
        // 还没收到 welcome，不能算已连接
        assertEquals(LinkState.Connecting, h.link.state)
    }

    @Test
    fun `收到 welcome 后进入已连接并记录代次（R6）`() {
        val h = Harness()
        h.link.start()
        h.link.onSocketOpened(35)

        val actions = h.link.onTextFrame(h.welcomeFrame(generation = 7L))

        assertEquals(emptyList<LinkAction>(), actions)
        assertEquals(LinkState.Connected(7L), h.link.state)
        assertEquals(7L, h.link.connectionGeneration)
        assertTrue("必须记日志", h.logTexts().contains("收到 welcome"))
    }

    @Test
    fun `welcome 会覆盖心跳与超时参数`() {
        val h = Harness()
        h.link.start()
        h.link.onSocketOpened(35)

        h.link.onTextFrame(h.welcomeFrame(heartbeatMs = 5_000L, pingTimeoutMs = 2_000L))

        assertEquals(5_000L, h.link.heartbeatIntervalMs)
        assertEquals(2_000L, h.link.pingTimeoutMs)
    }

    @Test
    fun `协议版本不一致只记警告不影响连接`() {
        val h = Harness()
        h.link.start()
        h.link.onSocketOpened(35)

        h.link.onTextFrame(h.welcomeFrame(protocolVersion = "phone-link-v2"))

        assertEquals(LinkState.Connected(3L), h.link.state)
        assertTrue(h.logTexts().contains("协议版本不一致"))
    }

    // ---- R1 / R2 -----------------------------------------------------------

    @Test
    fun `R1 收到 phone_ping 用同一个 request_id 回 phone_pong`() {
        val h = Harness()
        h.connect()

        val actions = h.link.onTextFrame(h.pingFrame("uuid-1"))

        val send = actions.filterIsInstance<LinkAction.Send>().single()
        val pong = JSONObject(send.frame)
        assertEquals("phone_pong", pong.getString("type"))
        assertEquals("uuid-1", pong.getString("request_id"))
        assertEquals("timestamp 用本机当前时间", h.now, pong.getLong("timestamp"))
    }

    @Test
    fun `R1 request_id 逐字符保持原样（含特殊字符）`() {
        val h = Harness()
        h.connect()
        val id = "550e8400-e29b-41d4-a716-446655440000"

        val actions = h.link.onTextFrame(h.pingFrame(id))

        val pong = JSONObject(actions.filterIsInstance<LinkAction.Send>().single().frame)
        assertEquals(id, pong.getString("request_id"))
    }

    @Test
    fun `R2 同一个 request_id 第二次不再回复`() {
        val h = Harness()
        h.connect()

        val first = h.link.onTextFrame(h.pingFrame("uuid-1"))
        val second = h.link.onTextFrame(h.pingFrame("uuid-1"))

        assertEquals(1, first.filterIsInstance<LinkAction.Send>().size)
        assertEquals(emptyList<LinkAction>(), second)
        assertTrue(h.logTexts().contains("重复的 request_id"))
    }

    @Test
    fun `重复的 ping 也会刷新最近一次 ping 的展示数据`() {
        val h = Harness()
        h.connect()
        val sentAt = h.now
        h.link.onTextFrame(h.pingFrame("uuid-1", timestamp = sentAt))
        h.now += 40
        h.link.onTextFrame(h.pingFrame("uuid-1", timestamp = sentAt))

        // 去重只决定「要不要回 pong」，不决定「要不要刷新展示」：
        // 两帧都会产生 PingOutcome，第二帧 duplicate=true 且 frame=null（所以没有任何 Send 动作）。
        assertEquals(2, h.pings.size)
        assertTrue("第一帧不是重复", !h.pings[0].duplicate)
        assertTrue("第二帧是重复", h.pings[1].duplicate)
        assertTrue("重复帧不带 pong 帧", h.pings[1].frame == null)
        assertEquals("uuid-1", h.link.lastPing?.requestId)
        assertEquals("重复帧仍要刷新 RTT 展示", 80L, h.link.lastPing?.rttMs)
    }

    @Test
    fun `不同 request_id 各自都要回`() {
        val h = Harness()
        h.connect()

        val a = h.link.onTextFrame(h.pingFrame("a"))
        val b = h.link.onTextFrame(h.pingFrame("b"))

        assertEquals("a", JSONObject(a.filterIsInstance<LinkAction.Send>().single().frame).getString("request_id"))
        assertEquals("b", JSONObject(b.filterIsInstance<LinkAction.Send>().single().frame).getString("request_id"))
    }

    // ---- R4 / R5 -----------------------------------------------------------

    @Test
    fun `R4 未知 type 只记日志不回任何帧`() {
        val h = Harness()
        h.connect()

        val actions = h.link.onTextFrame("""{"type":"phone_screenshot","request_id":"x"}""")

        assertEquals(emptyList<LinkAction>(), actions)
        assertTrue(h.logTexts().contains("忽略无法处理的帧"))
    }

    @Test
    fun `R4 非法 JSON 只记日志不回任何帧`() {
        val h = Harness()
        h.connect()

        val actions = h.link.onTextFrame("这不是 JSON")

        assertEquals(emptyList<LinkAction>(), actions)
        assertTrue(h.logTexts().contains("invalid_json"))
    }

    @Test
    fun `heartbeat_ack 不需要回帧`() {
        val h = Harness()
        h.connect()

        val actions = h.link.onTextFrame("""{"type":"heartbeat_ack","server_time":${h.now}}""")

        assertEquals(emptyList<LinkAction>(), actions)
    }

    @Test
    fun `服务端错误帧只记日志`() {
        val h = Harness()
        h.connect()

        val actions = h.link.onTextFrame("""{"type":"error","code":"message_too_large","message":"太大"}""")

        assertEquals(emptyList<LinkAction>(), actions)
        assertTrue(h.logTexts().contains("服务端错误帧"))
    }

    @Test
    fun `R5 超过 maxFrameBytes 的帧不回帧并记警告`() {
        val small = baseConfig.copy(maxFrameBytes = 512)
        val h = Harness(small)
        h.link.start()
        h.link.onSocketOpened(35)
        h.link.onTextFrame(h.welcomeFrame())
        val oversized = """{"type":"phone_ping","request_id":"uuid-1","timestamp":1,"pad":"${"x".repeat(800)}"}"""
        assertTrue(oversized.toByteArray(Charsets.UTF_8).size > small.maxFrameBytes)

        val actions = h.link.onTextFrame(oversized)

        assertEquals("R5：超长帧一律不回", emptyList<LinkAction>(), actions)
        assertTrue(h.logTexts().contains("超大帧"))
    }

    @Test
    fun `R5 按 UTF-8 字节数而不是字符数判断`() {
        // 每个汉字 3 字节：200 个汉字 = 600 字节，字符数只有 200
        val h = Harness(baseConfig.copy(maxFrameBytes = 512))
        h.link.start()
        h.link.onSocketOpened(35)
        h.link.onTextFrame(h.welcomeFrame())
        val frame = """{"type":"phone_ping","request_id":"${"汉".repeat(200)}","timestamp":1}"""
        assertTrue(frame.length < 300 && frame.toByteArray(Charsets.UTF_8).size > 512)

        assertEquals(emptyList<LinkAction>(), h.link.onTextFrame(frame))
    }

    // ---- R3 与心跳超时 -----------------------------------------------------

    @Test
    fun `R3 到达心跳间隔时发送应用层心跳`() {
        val h = Harness()
        h.connect()

        h.now += 30_000L
        val actions = h.link.onTick()

        val heartbeat = JSONObject(actions.filterIsInstance<LinkAction.Send>().single().frame)
        assertEquals("heartbeat", heartbeat.getString("type"))
        assertEquals(h.now, heartbeat.getLong("timestamp"))
    }

    @Test
    fun `未到心跳间隔时不发任何帧`() {
        val h = Harness()
        h.connect()

        h.now += 29_000L

        assertEquals(emptyList<LinkAction>(), h.link.onTick())
    }

    @Test
    fun `心跳按间隔周期性地发`() {
        val h = Harness()
        h.connect()

        h.now += 30_000L
        val first = h.link.onTick().filterIsInstance<LinkAction.Send>().size
        h.now += 30_000L
        val second = h.link.onTick().filterIsInstance<LinkAction.Send>().size

        assertEquals(1, first)
        assertEquals(1, second)
    }

    @Test
    fun `连续两个心跳周期没有任何服务端帧则主动重连`() {
        val h = Harness()
        h.connect()

        // 阈值 = max(2 * 心跳间隔, ping 超时) = 60_000ms
        h.now += 60_001L
        val actions = h.link.onTick()

        assertTrue(h.link.state is LinkState.Reconnecting)
        assertEquals(LinkAction.Close, actions.first())
        assertTrue(h.logTexts().contains("判定链路已死"))
    }

    @Test
    fun `收到任意服务端帧都会重置静默计时`() {
        val h = Harness()
        h.connect()

        h.now += 50_000L
        h.link.onTextFrame("""{"type":"heartbeat_ack","server_time":${h.now}}""")
        h.now += 50_000L

        val actions = h.link.onTick()
        assertTrue("静默计时被 ack 刷新过，不该重连", h.link.state is LinkState.Connected)
        assertTrue(actions.none { it is LinkAction.ScheduleReconnect || it is LinkAction.Close })
    }

    // ---- 重连退避 ---------------------------------------------------------

    @Test
    fun `意外断开按指数退避安排重连且次数递增`() {
        val h = Harness()
        h.link.start()

        val first = h.link.onSocketFailure("连接被拒绝", httpStatus = null)
        assertEquals(1_000L, (h.link.state as LinkState.Reconnecting).delayMs)
        assertEquals(1, (h.link.state as LinkState.Reconnecting).attempt)
        assertEquals(listOf(LinkAction.Close, LinkAction.ScheduleReconnect(1_000L)), first)

        h.now += 1_000L
        assertEquals(listOf(LinkAction.Open), h.link.onReconnectTimerFired())

        val second = h.link.onSocketFailure("连接被拒绝", httpStatus = null)
        assertEquals(listOf(LinkAction.Close, LinkAction.ScheduleReconnect(2_000L)), second)
        assertEquals(2_000L, (h.link.state as LinkState.Reconnecting).delayMs)
        assertEquals(2, (h.link.state as LinkState.Reconnecting).attempt)

        h.now += 2_000L
        h.link.onReconnectTimerFired()
        h.link.onSocketFailure("连接被拒绝", httpStatus = null)
        assertEquals(4_000L, (h.link.state as LinkState.Reconnecting).delayMs)

        h.now += 4_000L
        h.link.onReconnectTimerFired()
        h.link.onSocketFailure("连接被拒绝", httpStatus = null)
        assertEquals("超过上限后封顶", 5_000L, (h.link.state as LinkState.Reconnecting).delayMs)
    }

    @Test
    fun `重连成功后退避计数归零`() {
        val h = Harness()
        h.link.start()
        h.link.onSocketFailure("失败", null)
        h.now += 1_000L
        h.link.onReconnectTimerFired()
        h.link.onSocketOpened(35)
        h.link.onTextFrame(h.welcomeFrame())

        // 连上之后再次掉线，退避应当重新从第 1 次、1000ms 开始
        h.link.onSocketClosed(code = 1006, reason = "异常关闭")

        val state = h.link.state as LinkState.Reconnecting
        assertEquals(1, state.attempt)
        assertEquals(1_000L, state.delayMs)
    }

    @Test
    fun `服务端主动关闭连接也会触发重连`() {
        val h = Harness()
        h.connect()

        val actions = h.link.onSocketClosed(code = 1001, reason = "服务端下线")

        assertEquals(LinkAction.Close, actions.first())
        assertTrue(h.link.state is LinkState.Reconnecting)
    }

    @Test
    fun `401 视为认证失败且不自动重连`() {
        val h = Harness()
        h.link.start()

        val actions = h.link.onSocketFailure("Unauthorized", httpStatus = 401)

        assertEquals(emptyList<LinkAction>(), actions)
        assertEquals(LinkState.AuthFailed("HTTP 401"), h.link.state)
        assertTrue(h.logTexts().contains("认证失败"))
    }

    @Test
    fun `403 同样视为认证失败`() {
        val h = Harness()
        h.link.start()

        h.link.onSocketFailure("Forbidden", httpStatus = 403)

        assertEquals(LinkState.AuthFailed("HTTP 403"), h.link.state)
    }

    @Test
    fun `本地配置错误进入出错状态且不重连`() {
        val h = Harness()
        h.link.start()

        val actions = h.link.onLocalError("服务器地址无效")

        assertEquals(listOf(LinkAction.Close), actions)
        assertEquals(LinkState.Error("服务器地址无效"), h.link.state)
        assertEquals(emptyList<LinkAction>(), h.link.onTick())
    }

    @Test
    fun `建连握手超时也会重连`() {
        val h = Harness()
        h.link.start()

        // Open 发出后既没有 onOpen 也没有 onFailure
        h.now += PhoneLink.HANDSHAKE_TIMEOUT_MS + 1L
        val actions = h.link.onTick()

        assertTrue(h.link.state is LinkState.Reconnecting)
        assertEquals(1_000L, (h.link.state as LinkState.Reconnecting).delayMs)
        assertEquals(LinkAction.Close, actions.first())
        assertTrue(h.logTexts().contains("建连超时"))
    }

    @Test
    fun `网络恢复时重置退避并立即重连`() {
        val h = Harness()
        h.link.start()
        h.link.onSocketFailure("没有网络", null)
        h.link.onSocketFailure("没有网络", null) // 已被终态去重，计数不再增长
        h.now += 1_000L
        h.link.onReconnectTimerFired()
        h.link.onSocketFailure("没有网络", null)
        assertEquals(2, (h.link.state as LinkState.Reconnecting).attempt)

        val actions = h.link.onNetworkAvailable()

        assertEquals(listOf(LinkAction.Close, LinkAction.Open), actions)
        assertEquals(LinkState.Connecting, h.link.state)

        // 退避已归零：下一次失败又是第 1 次、1000ms
        h.link.onSocketFailure("又断了", null)
        val state = h.link.state as LinkState.Reconnecting
        assertEquals(1, state.attempt)
        assertEquals(1_000L, state.delayMs)
    }

    @Test
    fun `已连上时网络回调不打断现有连接`() {
        val h = Harness()
        h.connect()

        assertEquals(emptyList<LinkAction>(), h.link.onNetworkAvailable())
        assertEquals(LinkState.Connected(3L), h.link.state)
    }

    // ---- 用户主动断开 -----------------------------------------------------

    @Test
    fun `用户断开后不再自动重连`() {
        val h = Harness()
        h.connect()

        val actions = h.link.stop()

        assertEquals(listOf(LinkAction.Close), actions)
        assertEquals(LinkState.Idle, h.link.state)
        assertTrue(h.link.userStopped)
    }

    @Test
    fun `用户断开之后所有意外事件都被忽略`() {
        val h = Harness()
        h.connect()
        h.link.stop()

        assertEquals(emptyList<LinkAction>(), h.link.onSocketClosed(1006, "异常"))
        assertEquals(emptyList<LinkAction>(), h.link.onSocketFailure("失败", null))
        assertEquals(emptyList<LinkAction>(), h.link.onTextFrame(h.pingFrame("uuid-1")))
        h.now += 120_000L
        assertEquals(emptyList<LinkAction>(), h.link.onTick())
        assertEquals(LinkState.Idle, h.link.state)
    }

    @Test
    fun `重复的失败回调不会重复安排重连`() {
        val h = Harness()
        h.link.start()

        h.link.onSocketFailure("失败", null)
        val again = h.link.onSocketFailure("失败", null)
        val byClose = h.link.onSocketClosed(1006, "异常")

        assertEquals(emptyList<LinkAction>(), again)
        assertEquals(emptyList<LinkAction>(), byClose)
        assertEquals(1, (h.link.state as LinkState.Reconnecting).attempt)
    }

    @Test
    fun `状态变化会通过回调通知界面`() {
        val h = Harness()

        h.connect()

        assertEquals(
            listOf(LinkState.Connecting, LinkState.Connected(3L)),
            h.states,
        )
    }

    @Test
    fun `掉线重连的 deadline 等于当前时间加延迟`() {
        val h = Harness()
        h.link.start()

        h.link.onSocketFailure("失败", null)

        assertEquals(h.now + 1_000L, (h.link.state as LinkState.Reconnecting).deadlineMs)
    }

    // ---- C3：重连定时器与网络恢复事件的竞争 ---------------------------------

    @Test
    fun `C3 网络恢复之后，那次已经被唤醒的过期重连定时器不得再开一次连接`() {
        val h = Harness()
        h.link.start()

        // 断线：安排重连，此刻定时器已经在飞（真实实现里 ScheduledExecutorService
        // 不会因为逻辑上撤销了安排就消失，到点仍然会回调一次）。
        h.link.onSocketFailure("没有网络", null)
        assertTrue(h.link.state is LinkState.Reconnecting)

        // 网络在退避到点之前恢复：立即重连
        val eager = h.link.onNetworkAvailable()
        assertEquals(listOf(LinkAction.Close, LinkAction.Open), eager)

        // 定时器"到点"了
        val late = h.link.onReconnectTimerFired()

        assertEquals("过期的重连定时器必须什么都不做", emptyList<LinkAction>(), late)
        assertEquals("状态不得被过期定时器改变", LinkState.Connecting, h.link.state)
    }

    @Test
    fun `C3 定时器先到点、网络恢复随后到达时，动作里必须带一次 Close`() {
        val h = Harness()
        h.link.start()
        h.link.onSocketFailure("没有网络", null)

        h.now += 1_000L
        assertEquals(listOf(LinkAction.Open), h.link.onReconnectTimerFired())

        val recovery = h.link.onNetworkAvailable()

        // 缺了这次 Close，就会同时留着两条连接。
        assertEquals(
            "网络恢复必须先把上一条连接关掉，否则会同时存在两条",
            listOf(LinkAction.Close, LinkAction.Open),
            recovery,
        )
    }

    // ---- Gate 2：tool_request 的接线 ---------------------------------------

    /** 一个只读的假能力，用来验证「外部请求 → 本地能力」这条路径本身。 */
    private class FakeTool(
        override val name: String,
        override val allowedArguments: Set<String> = emptySet(),
        private val payload: org.json.JSONObject = org.json.JSONObject().put("battery_level_percent", 42),
    ) : Capability {
        var calls: Int = 0
            private set

        override fun execute(arguments: org.json.JSONObject): CapabilityResult {
            calls += 1
            return CapabilityResult.Success(payload)
        }
    }

    private fun toolFrame(
        id: String,
        tool: String = "battery_get_status",
        arguments: String? = "{}",
    ): String {
        val args = arguments ?: "null"
        return """{"type":"tool_request","request_id":"$id","tool_name":"$tool","arguments":$args}"""
    }

    @Test
    fun `Gate2 tool_request 走白名单并回一条 tool_result`() {
        val tool = FakeTool(name = "battery_get_status")
        val h = Harness(capabilities = listOf(tool))
        h.connect()

        val actions = h.link.onTextFrame(toolFrame("tr-1"))

        assertEquals("应当正好回一帧", 1, actions.size)
        val frame = (actions.single() as LinkAction.Send).frame
        val obj = JSONObject(frame)
        assertEquals("tool_result", obj.getString("type"))
        assertEquals("tr-1", obj.getString("request_id"))
        assertTrue(obj.getBoolean("ok"))
        assertEquals(42, obj.getJSONObject("result").getInt("battery_level_percent"))
        assertEquals("能力应当被调用一次", 1, tool.calls)
    }

    @Test
    fun `Gate2 没有注册能力时 tool_request 回 unsupported_tool，而不是静默丢弃`() {
        val h = Harness() // 空注册表
        h.connect()

        val actions = h.link.onTextFrame(toolFrame("tr-2", tool = "read_any_file"))

        // 关键：请求必须得到一个**明确的否定答复**。静默丢弃会让服务端拖满超时，
        // 而"超时"传达的是"不知道"，这里明明"知道不支持"。
        val obj = JSONObject((actions.single() as LinkAction.Send).frame)
        assertEquals("tr-2", obj.getString("request_id"))
        assertTrue(!obj.getBoolean("ok"))
        assertEquals("unsupported_tool", obj.getJSONObject("error").getString("code"))
        assertTrue("不得回显对端给的工具名", !(actions.single() as LinkAction.Send).frame.contains("read_any_file"))
    }

    @Test
    fun `Gate2 重复的 tool_request 只执行一次、只回一次`() {
        val tool = FakeTool(name = "battery_get_status")
        val h = Harness(capabilities = listOf(tool))
        h.connect()

        val first = h.link.onTextFrame(toolFrame("tr-3"))
        val second = h.link.onTextFrame(toolFrame("tr-3"))

        assertEquals(1, first.size)
        assertEquals("重复的请求不得再回一帧", emptyList<LinkAction>(), second)
        assertEquals("重复的请求不得再次执行", 1, tool.calls)
        assertTrue(
            "应当留下一条 WARN 说明为什么没回",
            h.logs.any { it.first == LogLevel.WARN && it.second.contains("重复的 tool_request") },
        )
    }

    @Test
    fun `Gate2 tool_request 缺 tool_name 或缺 request_id 时忽略并记日志，不回帧也不崩`() {
        val h = Harness(capabilities = listOf(FakeTool(name = "battery_get_status")))
        h.connect()

        val noToolName = h.link.onTextFrame("""{"type":"tool_request","request_id":"tr-4","arguments":{}}""")
        val noRequestId = h.link.onTextFrame("""{"type":"tool_request","tool_name":"battery_get_status"}""")

        assertEquals(emptyList<LinkAction>(), noToolName)
        assertEquals(emptyList<LinkAction>(), noRequestId)
        assertTrue(
            "应当记下缺字段的原因",
            h.logs.any { it.second.contains(UnknownReason.MISSING_TOOL_NAME) },
        )
        assertTrue(
            "应当记下缺 request_id 的原因",
            h.logs.any { it.second.contains(UnknownReason.MISSING_REQUEST_ID) },
        )
    }

    @Test
    fun `Gate2 arguments 不是对象时属于格式错误，不得退化成「没有参数」去执行`() {
        val tool = FakeTool(name = "battery_get_status")
        val h = Harness(capabilities = listOf(tool))
        h.connect()

        val actions = h.link.onTextFrame(toolFrame("tr-5", arguments = """["a","b"]"""))

        assertEquals("格式错误时绝不能执行能力", 0, tool.calls)
        assertTrue(h.logs.any { it.second.contains(UnknownReason.BAD_ARGUMENTS) })

        // 不再"静默丢弃"：request_id 拿得到，就必须回一条明确的失败帧。
        // 否则服务端要等到超时才知道 —— 把"你参数写错了"说成"手机可能死了"。
        assertEquals(1, actions.size)
        val frame = (actions.single() as LinkAction.Send).frame
        val json = org.json.JSONObject(frame)
        assertEquals("tool_result", json.getString("type"))
        assertEquals("tr-5", json.getString("request_id"))
        assertEquals(false, json.getBoolean("ok"))
        assertEquals("invalid_arguments", json.getJSONObject("error").getString("code"))
        assertEquals(false, json.has("result"))
    }

    @Test
    fun `Gate2 工具参数绝不进日志`() {
        val h = Harness(capabilities = listOf(FakeTool(name = "battery_get_status")))
        h.connect()

        h.link.onTextFrame(toolFrame("tr-6", arguments = """{"secret":"SUPER-SECRET-VALUE"}"""))

        val all = h.logs.joinToString("\n") { it.second }
        assertTrue("参数值绝不能出现在日志里", !all.contains("SUPER-SECRET-VALUE"))
    }

    @Test
    fun `Gate2 tool_request 不影响同一连接上的 phone_ping`() {
        val tool = FakeTool(name = "battery_get_status")
        val h = Harness(capabilities = listOf(tool))
        h.connect()

        val pingActions = h.link.onTextFrame(h.pingFrame("p-1"))
        val toolActions = h.link.onTextFrame(toolFrame("tr-7"))

        assertEquals("ping 与工具各自回一帧", 1, pingActions.size)
        assertEquals(1, toolActions.size)
        assertEquals("phone_pong", JSONObject((pingActions.single() as LinkAction.Send).frame).getString("type"))
        assertEquals("tool_result", JSONObject((toolActions.single() as LinkAction.Send).frame).getString("type"))
        // 两条通道的 request_id 空间是分开的：ping 的 id 不该让工具的 id 变成"重复"。
        assertEquals("p-1", JSONObject((pingActions.single() as LinkAction.Send).frame).getString("request_id"))
        assertEquals("tr-7", JSONObject((toolActions.single() as LinkAction.Send).frame).getString("request_id"))
    }

    @Test
    fun `Gate2 用户断开后收到的 tool_request 一律被忽略`() {
        val tool = FakeTool(name = "battery_get_status")
        val h = Harness(capabilities = listOf(tool))
        h.connect()
        h.link.stop()

        val actions = h.link.onTextFrame(toolFrame("tr-8"))

        assertEquals(emptyList<LinkAction>(), actions)
        assertEquals("断开之后不得再执行任何能力", 0, tool.calls)
    }
}
