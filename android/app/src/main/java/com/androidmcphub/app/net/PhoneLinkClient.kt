package com.androidmcphub.app.net

import com.androidmcphub.app.core.HubConfig
import com.androidmcphub.app.core.LogLevel
import com.androidmcphub.app.core.hostOf
import com.androidmcphub.app.core.normalizeWebSocketUrl
import com.androidmcphub.app.protocol.CLIENT_VERSION
import com.androidmcphub.app.protocol.LinkAction
import com.androidmcphub.app.protocol.LinkState
import com.androidmcphub.app.protocol.PhoneLink
import com.androidmcphub.app.protocol.PingOutcome
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * OkHttp WebSocket 传输层：把 [PhoneLink] 推导出来的 [LinkAction] 变成真实的网络动作，
 * 再把网络回调喂回 [PhoneLink]。
 *
 * 线程模型：所有可能修改 [PhoneLink] 状态的操作都丢到本类内部的单线程调度器上执行，
 * 这样「动作 → 状态」的时序是线性的，不会出现两个 OkHttp 回调线程同时改状态。
 *
 * 本类**不依赖 Android 框架**（[androidSdk] 由调用方传入），因此可以在 JVM 单测里
 * 直接对着 MockWebServer 跑真实的 WebSocket 链路。
 */
class PhoneLinkClient(
    private val configProvider: () -> HubConfig,
    private val tokenProvider: () -> String,
    private val androidSdk: Int,
    private val log: (LogLevel, String) -> Unit = { _, _ -> },
    private val onStateChanged: (LinkState) -> Unit = {},
    private val onPing: (PingOutcome) -> Unit = {},
    private val random: Random = Random.Default,
    private val clock: () -> Long = System::currentTimeMillis,
    private val httpClient: OkHttpClient = defaultHttpClient(),
) {

    private val scheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "phone-link").apply { isDaemon = true }
        }

    private val link: PhoneLink = PhoneLink(
        config = configProvider(),
        random = random,
        clock = clock,
        log = { level, text -> log(level, text) },
        responder = com.androidmcphub.app.protocol.PingResponder(clock = clock),
    ).also { l ->
        l.onStateChanged = { state ->
            log(LogLevel.INFO, "状态 → ${describeState(state)}")
            onStateChanged(state)
        }
        l.onPing = { outcome ->
            if (!outcome.duplicate) {
                log(LogLevel.INFO, "ping request_id=${outcome.requestId} RTT≈${outcome.rttMs}ms")
            }
            onPing(outcome)
        }
    }

    private val socketLock = Any()

    @Volatile
    private var socket: WebSocket? = null

    /** 每次 [openSocket] 分配一个新世代号；[currentEpoch] 只认最新的那个，旧 socket 的回调全部丢弃。 */
    private val epochCounter = AtomicInteger(0)

    @Volatile
    private var currentEpoch: Int = 0

    /** 已排队的重连定时器世代号，用于丢弃过期的定时器。 */
    private val reconnectToken = AtomicInteger(0)

    init {
        // 每秒一次：驱动应用层心跳（R3）与心跳超时/握手超时检测
        scheduler.scheduleWithFixedDelay(
            { runCatching { execute(link.onTick()) } },
            TICK_MS,
            TICK_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    /** 当前状态（界面用）。 */
    val state: LinkState get() = link.state

    /** 最近一次 ping 结果。 */
    val lastPing: PingOutcome? get() = link.lastPing

    /** 最近一次收到服务端任何帧的时刻，供服务超时看门狗判断会话是否已经僵死。 */
    val lastServerFrameAtMs: Long get() = link.lastServerFrameAtMs

    /** 用户点「连接」。 */
    fun start() {
        val cfg = configProvider()
        val url = normalizeWebSocketUrl(cfg.serverUrl)
        if (url == null) {
            execute(link.onLocalError("服务器地址无效，请在「高级设置」里填写形如 wss://<host>/phone/ws 的地址"))
            return
        }
        if (tokenProvider().isBlank()) {
            execute(link.onLocalError("设备令牌为空，请在「高级设置」里填写服务端下发的令牌"))
            return
        }
        cfg.validate()?.let { problem ->
            execute(link.onLocalError(problem))
            return
        }
        // 用户主动发起的新会话：去重窗口（R2）清空重来
        link.responder.reset()
        execute(link.start())
    }

    /** 用户点「断开」：置 userStopped，禁止任何自动重连。 */
    fun stop() {
        reconnectToken.incrementAndGet()
        execute(link.stop())
    }

    /** 网络恢复（由前台服务注册的 ConnectivityManager 回调驱动）。 */
    fun onNetworkAvailable() {
        execute(link.onNetworkAvailable())
    }

    /** 服务销毁时调用。 */
    fun shutdown() {
        stop()
        scheduler.shutdownNow()
    }

    // ---- 动作执行 ----------------------------------------------------------

    private fun execute(actions: List<LinkAction>) {
        for (action in actions) {
            when (action) {
                is LinkAction.Open -> openSocket()

                is LinkAction.Close -> closeSocket()

                is LinkAction.Send -> sendFrame(action.frame)

                is LinkAction.ScheduleReconnect -> scheduleReconnect(action.delayMs)
            }
        }
    }

    private fun scheduleReconnect(delayMs: Long) {
        val token = reconnectToken.incrementAndGet()
        scheduler.schedule(
            {
                if (reconnectToken.get() != token) return@schedule
                runCatching { execute(link.onReconnectTimerFired()) }
            },
            delayMs.coerceAtLeast(0L),
            TimeUnit.MILLISECONDS,
        )
    }

    private fun openSocket() {
        val cfg = configProvider()
        val token = tokenProvider()
        val url = normalizeWebSocketUrl(cfg.serverUrl)
        if (url == null) {
            execute(link.onLocalError("服务器地址无效，无法建连"))
            return
        }
        if (token.isBlank()) {
            execute(link.onLocalError("设备令牌为空，无法建连"))
            return
        }

        closeSocket()

        val request = Request.Builder()
            .url(url)
            // 令牌只放在请求头里，绝不写日志、绝不进 HubConfig
            .header("Authorization", "Bearer $token")
            .header("X-Device-Id", cfg.deviceId)
            .header("X-Client-Version", CLIENT_VERSION)
            .build()

        val myEpoch = epochCounter.incrementAndGet()
        currentEpoch = myEpoch
        log(LogLevel.INFO, "发起 WebSocket 连接：$url（host=${hostOf(url)}，设备 ID=${cfg.deviceId}）")

        val ws = httpClient.newWebSocket(request, Listener(myEpoch))
        synchronized(socketLock) { socket = ws }
    }

    private fun closeSocket() {
        // 先让世代号失效，旧 socket 后续的 onFailure/onClosed 都会被当成过期回调丢弃
        currentEpoch = 0
        val old = synchronized(socketLock) {
            socket.also { socket = null }
        }
        old?.close(NORMAL_CLOSURE, "client closing")
    }

    private fun sendFrame(frame: String) {
        val ws = synchronized(socketLock) { socket }
        if (ws == null) {
            log(LogLevel.WARN, "连接已关闭，丢弃待发帧")
            return
        }
        if (!ws.send(frame)) {
            log(LogLevel.WARN, "发送队列已满或连接已关闭，发送失败")
        }
    }

    private fun isCurrent(epoch: Int): Boolean = currentEpoch == epoch

    // ---- OkHttp 回调 -------------------------------------------------------

    private inner class Listener(private val epoch: Int) : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (!isCurrent(epoch)) {
                webSocket.cancel()
                return
            }
            execute(link.onSocketOpened(androidSdk))
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (!isCurrent(epoch)) return
            execute(link.onTextFrame(text))
        }

        override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
            // 协议只约定文本帧；二进制帧按「无法处理」记日志忽略，不回错误（R4 的同款处理）
            if (!isCurrent(epoch)) return
            log(LogLevel.WARN, "收到二进制帧（${bytes.size} 字节），协议只支持文本帧，已忽略")
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            // 对端发起关闭：按协议回一个关闭帧，之后会收到 onClosed
            webSocket.close(NORMAL_CLOSURE, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!isCurrent(epoch)) return
            execute(link.onSocketClosed(code, reason))
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!isCurrent(epoch)) return
            execute(link.onSocketFailure(describe(t), response?.code))
        }
    }

    companion object {
        /** 正常关闭码。 */
        const val NORMAL_CLOSURE: Int = 1000

        /** 调度器节拍：1 秒。 */
        private const val TICK_MS = 1_000L

        private fun describe(t: Throwable): String =
            "${t.javaClass.simpleName}: ${t.message ?: "无详情"}"

        /** 界面状态的中文描述，同时用于日志。 */
        fun describeState(state: LinkState): String = when (state) {
            is LinkState.Idle -> "已断开"
            is LinkState.Connecting -> "连接中"
            is LinkState.Connected -> "已连接（代次 ${state.generation}）"
            is LinkState.Reconnecting -> "重连等待（第 ${state.attempt} 次）"
            is LinkState.AuthFailed -> "认证失败（${state.detail}）"
            is LinkState.Error -> "出错（${state.message}）"
        }

        /**
         * 默认 OkHttp 客户端。
         *
         * 读超时显式设为 0：WebSocket 是长连接，空闲是正常的，链路已死由应用层心跳
         * 超时（见 [PhoneLink.onTick]）来判定，不能让读超时把健康的空闲连接掐掉。
         */
        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
