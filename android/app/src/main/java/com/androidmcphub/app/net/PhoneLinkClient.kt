package com.androidmcphub.app.net

import com.androidmcphub.app.capability.CapabilityDispatcher
import com.androidmcphub.app.core.DeviceSnapshot
import com.androidmcphub.app.core.DiagReport
import com.androidmcphub.app.core.HubConfig
import com.androidmcphub.app.core.LinkEvent
import com.androidmcphub.app.core.LogLevel
import com.androidmcphub.app.core.TokenLoadResult
import com.androidmcphub.app.core.buildDiagReport
import com.androidmcphub.app.core.hostOf
import com.androidmcphub.app.core.SocketFailure
import com.androidmcphub.app.core.normalizeWebSocketUrl
import com.androidmcphub.app.protocol.CLIENT_VERSION
import com.androidmcphub.app.protocol.encodeDiagEvent
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
    /**
     * 读设备令牌（Gate 0.1 审计 R4）。
     *
     * 返回三态而不是字符串：**"没设过"和"存过但解不开"必须分开处理**。
     * 前者是"用户还没填"，后者是"存储坏了"，两者给用户的提示完全不同；
     * 而把后者压成空串再往下走，就变成"静默用空凭据重试"。
     */
    private val credentialProvider: () -> TokenLoadResult,
    private val androidSdk: Int,
    private val log: (LogLevel, String) -> Unit = { _, _ -> },
    private val onStateChanged: (LinkState) -> Unit = {},
    private val onPing: (PingOutcome) -> Unit = {},
    private val onEvent: (LinkEvent) -> Unit = {},
    private val onHeartbeatAck: (Long) -> Unit = {},
    private val onHeartbeatSent: (Long) -> Unit = {},
    private val random: Random = Random.Default,
    private val clock: () -> Long = System::currentTimeMillis,
    private val httpClient: OkHttpClient = defaultHttpClient(),
    /**
     * Gate 2：能力注册表（显式白名单）。
     *
     * 默认**空注册表**：不显式接线时任何 `tool_request` 都回 `unsupported_tool`。
     * 这样"忘记接线"的后果是功能缺失，而不是悄悄多出一个可被远程调用的能力。
     */
    private val capabilityDispatcher: CapabilityDispatcher = CapabilityDispatcher(emptyList(), clock = clock),
    /**
     * V1.6.3：事件发生时的本机上下文（网络类型、VPN、屏幕状态、PID、进程运行时长）。
     *
     * 由前台服务注入；默认是空快照 —— 这样 JVM 单测不需要 Android 框架也能构造
     * 客户端，而生产环境必然拿到真实值。**不得**在这里放任何用户数据。
     */
    private val deviceSnapshotProvider: () -> DeviceSnapshot = { DeviceSnapshot() },
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
        dispatcher = capabilityDispatcher,
    ).also { l ->
        l.onStateChanged = { state ->
            log(LogLevel.INFO, "状态 → ${describeState(state)}")
            onStateChanged(state)
            // 连上之后把断网期间排队的诊断事件补发出去。
            if (state is LinkState.Connected) scheduleReportFlush()
        }
        l.onPing = { outcome ->
            if (!outcome.duplicate) {
                log(LogLevel.INFO, "ping request_id=${outcome.requestId} RTT≈${outcome.rttMs}ms")
            }
            onPing(outcome)
        }
        l.onEvent = { event ->
            onEvent(event)
            enqueueDiagReport(event)
        }
        l.onHeartbeatAck = { atMs -> onHeartbeatAck(atMs) }
        l.onHeartbeatSent = { atMs -> onHeartbeatSent(atMs) }
        // 聚合命令的结果从工作线程回来：**不在这里碰 socket**，而是投回本类的
        // 单线程调度器，让"发帧"与其它动作（心跳、重连）排在同一条时间线上。
        l.onToolResultReady = { outcome ->
            val frame = outcome.frame
            if (frame == null) {
                log(LogLevel.WARN, "聚合命令结果没有可发送的帧，已丢弃")
            } else {
                scheduler.execute { runCatching { sendFrame(frame) } }
            }
        }
        // 告诉分发器"异步结果送到这里来"。设置它就是开启异步聚合执行的前提
        // （见 CapabilityDispatcher.hubExecutor）。
        capabilityDispatcher.deferredSink = { outcome -> l.deliverToolResult(outcome) }
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

    // ---- V1.6.3 客户端诊断上报 ---------------------------------------------

    /**
     * 待上报的 `diag_event`：断网/重连期间事件发不出去，先排队，连上后补发。
     *
     * 为什么必须排队而不是"发不出去就算了"：要回答的正是"连接为什么反复断开"，
     * 而断线那一刻的事件恰恰是最关键的证据。丢掉它们，云端只会看到一条条
     * `device_online`，看不到手机那一侧同时发生了什么。
     */
    private val reportQueue = DiagReportQueue(
        limit = REPORT_QUEUE_LIMIT,
        maxAgeMs = REPORT_MAX_AGE_MS,
        clock = clock,
    )

    /** 记录一条事件，并在合适的时候补发队列。可以在 PhoneLink 的锁里被调用：只做入队。 */
    private fun enqueueDiagReport(event: LinkEvent) {
        reportQueue.enqueue(buildDiagReport(event, deviceSnapshotProvider()))
        // 已连接时立刻发；否则等 onStateChanged(Connected) 或下一次事件触发补发。
        if (link.state is LinkState.Connected) scheduleReportFlush()
    }

    private fun scheduleReportFlush() {
        try {
            scheduler.execute { runCatching { flushPendingReports() } }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // 调度器已经关闭（用户断开/服务销毁）：队列随客户端一起丢弃即可。
        }
    }

    /** 在 [scheduler] 线程上把队列里的诊断事件按发生顺序发出去。 */
    private fun flushPendingReports() {
        if (socket == null) return
        // 太旧的证据由队列丢弃（它们已不能解释"刚刚"发生的事）。
        val expired = reportQueue.drain { trySendReport(it) }
        if (expired > 0) {
            log(LogLevel.WARN, "丢弃 $expired 条过期诊断事件（超过 ${REPORT_MAX_AGE_MS / 60_000} 分钟）")
        }
    }

    /** @return true 表示已发送或已确定无需重试；false 表示当前发不出去。 */
    private fun trySendReport(report: DiagReport): Boolean {
        val ws = synchronized(socketLock) { socket } ?: return false
        val frame = runCatching { encodeDiagEvent(report) }.getOrNull()
            // 编码失败是本地 bug，重试也不会变好：直接丢弃这一条，不阻塞后续事件。
            ?: return true
        return ws.send(frame)
    }

    private fun clearPendingReports() {
        reportQueue.clear()
    }

    /** 排队中的诊断事件数（测试与诊断用）。 */
    val pendingDiagReportCount: Int get() = reportQueue.size

    /** 因容量/过期而丢失的诊断事件数（测试与诊断用）。**不**上报，避免自我引用。 */
    val droppedDiagReportCount: Int get() = reportQueue.droppedCount

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

    /** 最近一次收到 heartbeat_ack 的时刻；0 表示本进程尚未收到过。 */
    val lastHeartbeatAckAtMs: Long get() = link.lastHeartbeatAckAtMs

    /** 最近一次发出应用层心跳的时刻；0 表示本次连接里尚未发出过。 */
    val lastHeartbeatSentAtMs: Long get() = link.lastHeartbeatSentAtMs

    /** 用户点「连接」。 */
    fun start() {
        val cfg = configProvider()
        val url = normalizeWebSocketUrl(cfg.serverUrl)
        if (url == null) {
            execute(link.onLocalError("服务器地址无效，请在「高级设置」里填写形如 wss://<host>/phone/ws 的地址"))
            return
        }
        credentialProblem()?.let { problem ->
            execute(link.onLocalError(problem))
            return
        }
        cfg.validate()?.let { problem ->
            execute(link.onLocalError(problem))
            return
        }
        // 用户主动发起的新会话：去重窗口（R2）清空重来
        link.responder.reset()
        // 工具去重窗口同理：新会话里旧 request_id 不该再被当成"重复"
        link.dispatcher.reset()
        execute(link.start())
    }

    /**
     * 用户点「断开」：置 userStopped，禁止任何自动重连。
     *
     * 与 [shutdown] 的区别只在事件语义：这里会产生一条 `USER_DISCONNECTED`（带真实来源），
     * 而 [shutdown] 不会。两者都会释放线程与聚合线程池 —— 用户断开后服务随即销毁，
     * 留着每秒一次的 tick 线程只会白耗电量。
     */
    fun stop(source: String = "unknown") {
        reconnectToken.incrementAndGet()
        execute(link.stop(source = source))
        clearPendingReports()
        runCatching { capabilityDispatcher.close() }
        scheduler.shutdown()
    }

    /** 网络恢复（由前台服务注册的 ConnectivityManager 回调驱动）。 */
    fun onNetworkAvailable() {
        execute(link.onNetworkAvailable())
    }

    /** 默认网络断开：主动判死当前连接，等网络恢复后立即重连。 */
    fun onNetworkLost() {
        execute(link.onNetworkLost())
    }

    /**
     * 前台服务看门狗：长时间没有任何服务端帧时强制重连。
     *
     * **不停止服务** —— 后台断网/系统冻结之后，用户仍然需要自动恢复的机会。
     */
    fun onWatchdogTimeout(silentMs: Long) {
        execute(link.onWatchdogTimeout(silentMs))
    }

    /**
     * 服务销毁时调用。
     *
     * V1.6.2：**不再复用 [stop]**。`stop()` 的默认原因是「用户主动断开」并会记一条
     * `USER_DISCONNECTED`；服务销毁（包括系统回收、ColorOS 停止服务）复用它，
     * 就会把系统行为写成用户行为。这里走 [PhoneLink.abandon]，只禁止重连、只记
     * 服务停止原因，不产生用户事件。
     */
    fun shutdown(reason: String = "前台服务停止（非用户断开）") {
        reconnectToken.incrementAndGet()
        execute(link.abandon(reason))
        clearPendingReports()
        // 先关聚合路由器（它的刷新线程池与目录缓存），再关调度器：
        // 反过来的话，在飞的结果会撞上一个已经不收任务的调度器。
        runCatching { capabilityDispatcher.close() }
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

    /**
     * 判断凭据"能不能拿去建连"。返回 `null` 表示可以用。
     *
     * 三种不能用的情况必须给出**不同**的话，用户才知道该去填、该去重新保存、还是该检查输入。
     * 其中 [TokenLoadResult.Unreadable] 是最要紧的一种：它看起来最像"没有令牌"，
     * 但也最不能当成"没有令牌"处理。
     */
    private fun credentialProblem(): String? = credentialProblem(credentialProvider())

    private fun credentialProblem(result: TokenLoadResult): String? = when (result) {
        is TokenLoadResult.Present ->
            if (result.token.isBlank()) "设备令牌为空，请在「高级设置」里重新填写" else null

        TokenLoadResult.Absent ->
            "还没有配置设备令牌，请在「高级设置」里填写服务端下发的令牌"

        // reason 取自 TokenFailure 的闭集，不含令牌内容，可以安全地显示与记录。
        is TokenLoadResult.Unreadable ->
            "设备令牌无法解密（${result.reason}），请重新填写并保存"
    }

    private fun openSocket() {
        val cfg = configProvider()
        val url = normalizeWebSocketUrl(cfg.serverUrl)
        if (url == null) {
            execute(link.onLocalError("服务器地址无效，无法建连"))
            return
        }
        // 每次真正建连前都重新读一次凭据：它是可能在这期间被改坏或失效的。
        // 读不出来就**不建连**，而不是拿空令牌去撞一次 401。
        val credential = credentialProvider()
        val problem = credentialProblem(credential)
        if (problem != null) {
            execute(link.onLocalError(problem))
            return
        }
        val token = (credential as TokenLoadResult.Present).token

        // V1.6.2：把「关旧 socket → 分配世代 → 建新 socket → 记下句柄」放在同一把锁里。
        // 没有这一步时，网络恢复回调（主线程）和重连定时器（调度线程）可以同时进来：
        // 两个 socket 都会被建出来，先建的那个在 onOpen 时才被 cancel，期间会多一条
        // 无主的连接注册到服务端（服务端按代次替换，但手机侧会凭空多一次建连）。
        synchronized(socketLock) {
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
            // Gate 0.3 审计发现项 1：只记主机名。`$url` 里带着 path，而且用户从别处
            // 复制来的地址可能带查询参数；主机名足够定位问题，且不承载任何凭据。
            log(LogLevel.INFO, "发起 WebSocket 连接 host=${hostOf(url)} deviceId=${cfg.deviceId}")

            socket = httpClient.newWebSocket(request, Listener(myEpoch))
        }
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
            // 对端发起关闭：按协议回一个关闭帧，之后会收到 onClosed。
            // 过期世代的 socket 不再回应 —— 那可能是上一轮连接延迟送达的关闭帧，
            // 回应它只会多一次无意义的写。
            if (!isCurrent(epoch)) return
            webSocket.close(NORMAL_CLOSURE, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!isCurrent(epoch)) return
            execute(link.onSocketClosed(code, reason))
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!isCurrent(epoch)) return
            // Gate 0.3 审计发现项 1：**不把 `t.message` 往下传**。
            // 只按异常类名给一个闭集里的类别（见 SocketFailure.classify），
            // 同时把类名本身作为结构化证据带上（上报前还会再收敛形状）。
            execute(
                link.onSocketFailure(
                    category = SocketFailure.classify(t.javaClass.simpleName),
                    httpStatus = response?.code,
                    exceptionClass = t.javaClass.simpleName,
                ),
            )
        }
    }

    companion object {
        /** 正常关闭码。 */
        const val NORMAL_CLOSURE: Int = 1000

        /** 调度器节拍：1 秒。 */
        private const val TICK_MS = 1_000L

        /** 待上报诊断事件的上限；超出时丢弃最旧的（断线风暴不能无限占内存）。 */
        const val REPORT_QUEUE_LIMIT: Int = 50

        /** 超过这个时长的排队事件不再补发 —— 它们已经解释不了"刚刚"发生了什么。 */
        const val REPORT_MAX_AGE_MS: Long = 15 * 60 * 1000L

        /** 界面状态的中文描述，同时用于日志。 */
        fun describeState(state: LinkState): String = when (state) {
            is LinkState.Idle -> "已断开"
            is LinkState.Connecting -> "连接中"
            is LinkState.Connected -> "已连接（代次 ${state.generation}）"
            is LinkState.Reconnecting -> "重连等待（第 ${state.attempt} 次）"
            is LinkState.AuthFailed -> "认证失败（${state.detail}）"
            is LinkState.Revoked -> "凭据已被撤销（${state.detail}）"
            is LinkState.Error -> "出错（${state.message}）"
        }

        /**
         * WebSocket 协议层 ping 周期（秒）。
         *
         * V1.6.2：后台断线的实测形态是「连接建立约 30 秒后，等下一次应用层心跳期间
         * 收到 TCP reset」，50–60 秒一个循环。应用层心跳 30 秒一次，两次心跳之间
         * 链路完全静默；VPN/NAT 的闲置映射超时、以及半开连接，都会在这个静默窗口里
         * 把 TCP 流掐掉。
         *
         * 这里用 OkHttp 的**协议层 ping**做保活：每 15 秒一个 ping 帧，收到 pong 即
         * 证明链路双向可达；连续一个周期收不到 pong 就由 OkHttp 主动判死并触发既有
         * 的重连，而不是等 60 秒应用层超时或操作系统的 TCP 重传超时。
         *
         * 为什么对 Cloudflare 安全：Workers/Durable Objects 运行时**自动**回应
         * 协议层 ping，且不唤醒休眠中的 Durable Object（见 Cloudflare 文档
         * "Automatic ping/pong handling"）。这是控制帧，不会进入 `webSocketMessage`，
         * 也不影响服务端的应用层存活判定。
         */
        const val PING_INTERVAL_SECONDS: Long = 15L

        /**
         * 默认 OkHttp 客户端。
         *
         * 读超时显式设为 0：WebSocket 是长连接，空闲是正常的，链路已死由应用层心跳
         * 超时（见 [PhoneLink.onTick]）来判定，不能让读超时把健康的空闲连接掐掉。
         * 链路"是否还通"由上面的协议层 ping 更早发现。
         */
        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(PING_INTERVAL_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
