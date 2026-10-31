package com.androidmcphub.app.net

import com.androidmcphub.app.core.HubConfig
import com.androidmcphub.app.core.LinkEvent
import com.androidmcphub.app.core.LinkEventKind
import com.androidmcphub.app.core.TokenLoadResult
import com.androidmcphub.app.protocol.LinkState
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Gate 1.5 阶段 1A · 任务 C：连接可靠性补测（C1–C4、C6、C8）。
 *
 * 这里补的是 **「网络层 ↔ 状态机」交界处**的几条时序，而不是协议规则本身：
 *
 *  - C1 用户主动断开后禁止自动重连 —— [com.androidmcphub.app.protocol.PhoneLinkTest]
 *    已经在纯状态机层覆盖；这里补的是**真实 OkHttp 回调驱动下**的同一件事。
 *  - C2/C3 重连定时器与网络恢复事件的竞争 —— 状态机层与调度器层各有一道防线
 *    （`PhoneLink.reconnectScheduled` 与 `PhoneLinkClient.reconnectToken`）。
 *  - C4 旧 WebSocket 回调晚于新连接 —— 这道防线**只在传输层**
 *    （`PhoneLinkClient.epochCounter` / `currentEpoch` / `isCurrent`），
 *    纯状态机层的 [PhoneLink] 里没有"世代"这个概念，所以 `PhoneLinkTest` 覆盖不到它。
 *  - C8 服务端静默但 TCP 尚未关闭 —— 必须由**静默超时**判定链路已死，
 *    而不是等服务端关闭；判定之后要主动重连，并主动关掉那条已经判死的连接。
 *
 * 两条「阳性对照」是刻意写进来的：任务书要求"每条断言都要能真的变红"。
 * 一条"永远不会红的断言"不是证据 —— 所以凡是断言"不得出现 X"的地方，
 * 都配一条"在同样布景下 X 确实会出现"的对照，证明那条阴性断言不是空断言。
 *
 * 全部断言都不依赖生产服务、生产凭据或真机；时钟用的是真实的
 * `System.currentTimeMillis`，但退避与静默阈值都被压到百毫秒量级，
 * **不靠 sleep 碰运气**：凡是等某个事件，都用 `awaitState` / `waitUntil` 轮询
 * 到事件真的发生为止；只有在"要证明某件事在足够长的时间里都不发生"时，
 * 才用一次有明确目的的固定等待，并且紧跟着断言。
 */
class PhoneLinkReliabilityTest {

    /** 服务端侧观测到的一条 WebSocket 连接。 */
    private class Conn(val index: Int) {
        @Volatile
        var socket: WebSocket? = null

        /** 服务端已经看到这条连接的打开。 */
        val opened = CountDownLatch(1)

        /** 服务端侧这条连接已经结束（无论谁先关）。 */
        @Volatile
        var closed = false
    }

    private val server = MockWebServer()
    private val conns = CopyOnWriteArrayList<Conn>()
    private val states = CopyOnWriteArrayList<LinkState>()
    private val logs = CopyOnWriteArrayList<String>()
    private val events = CopyOnWriteArrayList<LinkEvent>()
    private val heartbeatAcks = CopyOnWriteArrayList<Long>()

    /** 给服务端用的"持续发帧"定时器，模拟一条活着的连接。 */
    private val timers: ScheduledExecutorService = Executors.newScheduledThreadPool(2) { r ->
        Thread(r, "test-server-keepalive").apply { isDaemon = true }
    }

    private var client: PhoneLinkClient? = null
    private var config: HubConfig = HubConfig()

    @After
    fun tearDown() {
        runCatching { client?.shutdown() }
        runCatching { server.shutdown() }
        runCatching { timers.shutdownNow() }
    }

    // ---- 夹具 --------------------------------------------------------------

    private fun welcomeJson(generation: Long): String =
        """{"type":"welcome","device_id":"test-phone-1","connection_generation":$generation,""" +
            """"server_time":${System.currentTimeMillis()},"max_pending_requests":16,""" +
            """"protocol_version":"phone-link-v1"}"""

    /**
     * 排队一次「升级成功」的连接。
     *
     * 刻意**不带** `heartbeat_interval_ms` / `ping_timeout_ms`：让客户端用自己配置里的值，
     * 测试才能把静默阈值压到百毫秒量级。
     */
    private fun enqueueUpgrade(onOpen: (WebSocket) -> Unit = {}) {
        val conn = Conn(conns.size)
        conns += conn
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        conn.socket = webSocket
                        conn.opened.countDown()
                        onOpen(webSocket)
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        conn.closed = true
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        conn.closed = true
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        conn.closed = true
                    }
                },
            ),
        )
    }

    /** 让服务端每隔 [periodMs] 发一帧，客户端据此认为这条连接还活着。 */
    private fun keepAlive(webSocket: WebSocket, periodMs: Long = 200L) {
        timers.scheduleWithFixedDelay(
            {
                runCatching {
                    webSocket.send(
                        """{"type":"heartbeat_ack","server_time":${System.currentTimeMillis()}}""",
                    )
                }
                Unit
            },
            periodMs,
            periodMs,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun configWith(
        heartbeatIntervalMs: Long = 30_000L,
        pingTimeoutMs: Long = 10_000L,
        reconnectDelayMs: Long = 300L,
    ): HubConfig = HubConfig(
        serverUrl = server.url("/phone/ws").toString(),
        deviceId = "test-phone-1",
        pingTimeoutMs = pingTimeoutMs,
        heartbeatIntervalMs = heartbeatIntervalMs,
        reconnectInitialDelayMs = reconnectDelayMs,
        reconnectMaxDelayMs = reconnectDelayMs,
        reconnectJitterRatio = 0.0,
    )

    private fun startClient() {
        val cfg = config
        client = PhoneLinkClient(
            configProvider = { cfg },
            credentialProvider = { TokenLoadResult.Present("test-token-not-a-secret") },
            androidSdk = 35,
            log = { level, text -> logs += "$level $text" },
            onStateChanged = { states += it },
            onEvent = { events += it },
            onHeartbeatAck = { heartbeatAcks += it },
        )
    }

    /** 轮询到**出现过**满足条件的状态为止。 */
    private fun awaitState(timeoutMs: Long = 10_000L, predicate: (LinkState) -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (states.any(predicate)) return true
            Thread.sleep(20)
        }
        return states.any(predicate)
    }

    /** 轮询到条件成立为止。 */
    private fun waitUntil(timeoutMs: Long, predicate: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return true
            Thread.sleep(20)
        }
        return predicate()
    }

    // ---- C1：用户主动断开后禁止自动重连 --------------------------------------

    @Test
    fun `C1 用户按下断开之后，服务端再关闭连接也不得触发任何重连`() {
        enqueueUpgrade { it.send(welcomeJson(1)) }
        server.start()
        config = configWith()
        startClient()

        client!!.start()
        assertTrue("首条连接应当建立", conns[0].opened.await(10, TimeUnit.SECONDS))
        assertTrue("握手后应当进入已连接", awaitState { it is LinkState.Connected })

        client!!.stop()
        assertTrue(
            "断开之后必须进入已断开状态",
            awaitState(timeoutMs = 2_000L) { it is LinkState.Idle },
        )

        // 服务端随后关闭这条连接 —— 从客户端看，这就是一次标准的"意外断开"。
        conns[0].socket!!.close(1001, "server going away")
        assertTrue("服务端侧确实看到了关闭", waitUntil(3_000) { conns[0].closed })

        // 跨过整个退避窗口。
        Thread.sleep(1_200)

        assertEquals("用户已主动断开，不得再发起任何新连接", 1, server.requestCount)
        assertEquals("最终状态必须停在已断开", LinkState.Idle, states.last())
        val afterIdle = states.dropWhile { it !is LinkState.Idle }
        assertTrue(
            "断开之后不得再出现重连等待：$afterIdle",
            afterIdle.none { it is LinkState.Reconnecting },
        )
    }

    @Test
    fun `C1 阳性对照：同一场景下没有按断开时，服务端的关闭确实会触发重连`() {
        enqueueUpgrade { it.send(welcomeJson(1)) }
        server.start()
        config = configWith()
        startClient()

        client!!.start()
        assertTrue(conns[0].opened.await(10, TimeUnit.SECONDS))
        assertTrue(awaitState { it is LinkState.Connected })

        conns[0].socket!!.close(1001, "server going away")

        assertTrue(
            "没有按断开时必须进入重连等待 —— 否则上一条'不得重连'就是空断言：$states",
            awaitState(timeoutMs = 5_000L) { it is LinkState.Reconnecting },
        )
    }

    // ---- C2 / C3：重连定时器与网络恢复的竞争 --------------------------------

    @Test
    fun `C2 C3 网络恢复抢在重连定时器之前时，只发生一次新连接、过期的定时器被丢弃`() {
        server.enqueue(MockResponse().setResponseCode(500))
        enqueueUpgrade { it.send(welcomeJson(2)) }
        server.start()
        // 退避给 1s：这样"抢在定时器之前"是说得清的
        config = configWith(reconnectDelayMs = 1_000L)
        startClient()

        client!!.start()
        assertTrue("HTTP 500 之后状态机应当安排重连", awaitState { it is LinkState.Reconnecting })

        client!!.onNetworkAvailable()

        assertTrue(
            "网络恢复必须立即重连，而不是继续等退避到点",
            conns[0].opened.await(10, TimeUnit.SECONDS),
        )
        assertTrue(
            "重连后应当进入已连接（代次 2）",
            awaitState { it is LinkState.Connected && it.generation == 2L },
        )

        // 跨过最初那次退避的到点时刻：真实调度器到点仍会回调一次。
        Thread.sleep(1_500)

        assertEquals("过期的重连定时器不得再造一条连接", 2, server.requestCount)
    }

    @Test
    fun `C3 重连定时器先到点、网络恢复随后到达时，不得打断已经建立的那条连接`() {
        server.enqueue(MockResponse().setResponseCode(500))
        enqueueUpgrade { it.send(welcomeJson(2)) }
        server.start()
        config = configWith(reconnectDelayMs = 300L)
        startClient()

        client!!.start()
        assertTrue(awaitState { it is LinkState.Reconnecting })

        assertTrue(
            "定时器到点后应当连上第二条连接",
            conns[0].opened.await(10, TimeUnit.SECONDS),
        )
        // ⚠ 必须等**客户端也处理完 welcome**，这条连接才算真的"已连上"。
        //   只等 `conns[0].opened`（服务端侧看到 socket）是不够的：那时客户端
        //   可能还没跑完 onOpen，`socketOpen` 仍是 false，于是网络恢复回调会
        //   走"立即重连"分支 —— 这条测试就会变成看运气的。
        assertTrue(
            "应当进入已连接（代次 2）",
            awaitState { it is LinkState.Connected && it.generation == 2L },
        )

        val before = states.size
        client!!.onNetworkAvailable()
        Thread.sleep(1_200)

        assertEquals("已经连上时，网络恢复回调不得引起任何状态变化", before, states.size)
        assertEquals("已经连上时，网络恢复回调不得再建连接", 2, server.requestCount)
        assertFalse("已经连上的连接不得被关掉", conns[0].closed)
    }

    @Test
    fun `C4 重复发起连接会替换掉旧连接，旧连接的迟到回调不得干扰新连接`() {
        enqueueUpgrade { it.send(welcomeJson(1)) }
        enqueueUpgrade {
            it.send(welcomeJson(2))
            keepAlive(it)
        }
        server.start()
        config = configWith()
        startClient()

        client!!.start()
        assertTrue(conns[0].opened.await(10, TimeUnit.SECONDS))
        assertTrue(
            "应当先进入已连接（代次 1）",
            awaitState { it is LinkState.Connected && it.generation == 1L },
        )

        // 用户又点了一次「连接」：旧连接必须被替换掉。
        client!!.start()

        assertTrue("第二次连接应当建立", conns[1].opened.await(10, TimeUnit.SECONDS))
        assertTrue(
            "应当进入已连接（代次 2）",
            awaitState { it is LinkState.Connected && it.generation == 2L },
        )
        assertTrue("被替换掉的旧连接必须被关掉", waitUntil(3_000) { conns[0].closed })

        // 旧连接的 onClosed 到这里才到齐；它必须被 `isCurrent(epoch)` 丢掉。
        Thread.sleep(800)
        val lastConnected = states.indexOfLast { it is LinkState.Connected }
        assertTrue(
            "新连接建立之后状态不得再变化（旧连接的迟到回调必须被丢弃）：${states.drop(lastConnected + 1)}",
            states.drop(lastConnected + 1).isEmpty(),
        )
        assertFalse("新连接不得被顺带关掉", conns[1].closed)
        assertEquals("只允许两次建连", 2, server.requestCount)
    }

    // ---- C4 / C8：旧连接回调晚于新连接；服务端静默但 TCP 未关 -----------------

    @Test
    fun `C4 C8 服务端静默但 TCP 未关时主动重连，且新连接建立后旧连接的迟到回调不得改变状态`() {
        // 第 1 条连接：握手成功、发出 welcome 之后就再也不说话 —— socket 一直开着。
        // 这正是"服务端静默但 TCP 尚未关闭"的形状。
        enqueueUpgrade { it.send(welcomeJson(1)) }
        // 第 2 条连接：持续发帧，证明它是活的。
        enqueueUpgrade {
            it.send(welcomeJson(2))
            keepAlive(it)
        }
        server.start()
        // 静默阈值 = max(2 × 200ms, 300ms) = 400ms，远小于 1s 一次的 tick，
        // 因此一两个 tick 之内就能判定。
        config = configWith(
            heartbeatIntervalMs = 200L,
            pingTimeoutMs = 300L,
            reconnectDelayMs = 200L,
        )
        startClient()

        client!!.start()
        assertTrue(conns[0].opened.await(10, TimeUnit.SECONDS))
        assertTrue(
            "应当先进入已连接（代次 1）",
            awaitState { it is LinkState.Connected && it.generation == 1L },
        )

        assertTrue(
            "应当在静默超时后主动重连，而不是干等：$states",
            awaitState(timeoutMs = 15_000L) { it is LinkState.Connected && it.generation == 2L },
        )

        // 这次判定必须来自"静默"，而不是"对端关闭" ——
        // 服务端从头到尾没有关过任何一条连接。
        assertTrue("必须由静默超时判定链路已死：$logs", logs.any { it.contains("未收到服务端任何帧") })
        assertTrue("日志里应当出现心跳超时的判定：$logs", logs.any { it.contains("心跳超时") })
        assertTrue("被判定已死的旧连接必须被主动关掉", waitUntil(3_000) { conns[0].closed })
        assertEquals("只允许两条连接：原来的 + 重连的", 2, server.requestCount)

        // 旧连接的 onClosed 很可能在新连接建立之后才到达。它必须被 `isCurrent(epoch)` 丢掉。
        Thread.sleep(1_500)
        val lastConnected = states.indexOfLast { it is LinkState.Connected }
        assertTrue("states 里应当至少有一次已连接", lastConnected >= 0)
        val tail = states.drop(lastConnected + 1)
        assertTrue(
            "新连接建立之后状态不得再变化（旧连接的迟到回调必须被丢弃）：$tail",
            tail.isEmpty(),
        )
    }

    // ---- V1.6.1：网络断开恢复与看门狗 --------------------------------------

    @Test
    fun `V161 默认网络断开后主动判死连接，网络恢复时立即重连`() {
        enqueueUpgrade {
            it.send(welcomeJson(1))
            keepAlive(it)
        }
        enqueueUpgrade {
            it.send(welcomeJson(2))
            keepAlive(it)
        }
        server.start()
        // 退避故意给到 5s：证明"网络恢复立即连回"是 onNetworkAvailable 驱动的，
        // 不是刚好等到定时器到点。
        config = configWith(reconnectDelayMs = 5_000L)
        startClient()

        client!!.start()
        assertTrue("首条连接应当建立", conns[0].opened.await(10, TimeUnit.SECONDS))
        assertTrue(
            "应当先进入已连接（代次 1）",
            awaitState { it is LinkState.Connected && it.generation == 1L },
        )

        client!!.onNetworkLost()

        assertTrue("网络断开必须立即进入重连等待：$states", awaitState { it is LinkState.Reconnecting })
        assertTrue("被判死的旧连接必须被主动关掉", waitUntil(3_000) { conns[0].closed })

        client!!.onNetworkAvailable()

        assertTrue("网络恢复必须立即连回，而不是干等退避", conns[1].opened.await(10, TimeUnit.SECONDS))
        assertTrue(
            "重连后应当进入已连接（代次 2）",
            awaitState { it is LinkState.Connected && it.generation == 2L },
        )
        assertEquals("只允许两条连接：原来的 + 重连的", 2, server.requestCount)
        assertTrue(
            "事件里必须留下网络变化证据：${events.map { it.kind }}",
            events.any { it.kind == LinkEventKind.NETWORK_CHANGED },
        )
    }

    @Test
    fun `V161 看门狗超时只强制重连，不得把连接当成用户断开`() {
        // 第 1 条：握手成功、发完 welcome 就沉默，但 TCP 一直开着。
        enqueueUpgrade { it.send(welcomeJson(1)) }
        // 第 2 条：正常说话，证明重连后的连接是活的。
        enqueueUpgrade {
            it.send(welcomeJson(2))
            keepAlive(it)
        }
        server.start()
        // 心跳/静默阈值保持 30s/10s：确保这次重连只能由看门狗触发，
        // 而不是被 onTick 的心跳超时抢先触发。
        config = configWith(reconnectDelayMs = 300L)
        startClient()

        client!!.start()
        assertTrue(conns[0].opened.await(10, TimeUnit.SECONDS))
        assertTrue(awaitState { it is LinkState.Connected && it.generation == 1L })

        client!!.onWatchdogTimeout(600_000L)

        assertTrue(
            "看门狗必须强制重连：$states",
            awaitState(timeoutMs = 15_000L) { it is LinkState.Connected && it.generation == 2L },
        )
        assertTrue(
            "事件里必须留下心跳超时证据：${events.map { it.kind }}",
            events.any { it.kind == LinkEventKind.HEARTBEAT_TIMEOUT },
        )
        assertFalse(
            "看门狗不是用户断开，不得记录 USER_DISCONNECTED",
            events.any { it.kind == LinkEventKind.USER_DISCONNECTED },
        )
        assertTrue(
            "看门狗不得把连接拖回 Idle（旧实现会在这里停掉整个服务）：$states",
            states.none { it is LinkState.Idle },
        )
        assertEquals("只允许两条连接：原来的 + 重连的", 2, server.requestCount)
    }

    @Test
    fun `V161 心跳成功会回传时间戳供诊断页展示`() {
        enqueueUpgrade { it.send(welcomeJson(1)) }
        server.start()
        config = configWith()
        startClient()

        client!!.start()
        assertTrue(conns[0].opened.await(10, TimeUnit.SECONDS))
        assertTrue(awaitState { it is LinkState.Connected })
        conns[0].socket!!.send(
            """{"type":"heartbeat_ack","server_time":${System.currentTimeMillis()}}""",
        )

        assertTrue(
            "收到 heartbeat_ack 后必须刷新最近心跳时间：$heartbeatAcks",
            waitUntil(5_000) { heartbeatAcks.isNotEmpty() },
        )
        assertTrue("最近心跳时间必须可读", client!!.lastHeartbeatAckAtMs > 0L)
    }

    @Test
    fun `C4 阳性对照：当前那条连接的关闭确实会立刻把状态拖回重连`() {
        enqueueUpgrade { it.send(welcomeJson(1)) }
        server.start()
        config = configWith()
        startClient()

        client!!.start()
        assertTrue(conns[0].opened.await(10, TimeUnit.SECONDS))
        assertTrue(awaitState { it is LinkState.Connected })

        // 这一条是**当前**连接，它的关闭是合法输入，必须产生重连。
        conns[0].socket!!.close(1001, "server going away")

        assertTrue(
            "当前连接的关闭必须触发重连 —— 否则上一条'状态不得再变化'是空断言：$states",
            awaitState(timeoutMs = 5_000L) { it is LinkState.Reconnecting },
        )
    }
}
