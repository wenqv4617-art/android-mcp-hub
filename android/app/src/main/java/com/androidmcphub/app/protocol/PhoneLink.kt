package com.androidmcphub.app.protocol

import com.androidmcphub.app.core.HubConfig
import com.androidmcphub.app.core.LogLevel
import com.androidmcphub.app.core.SocketFailure
import com.androidmcphub.app.core.backoffDelayMs
import com.androidmcphub.app.core.hostOf
import kotlin.math.max
import kotlin.random.Random

/**
 * 对外可见的连接状态。
 *
 * 界面文案（HubScreen）直接映射这几种：
 *  - [Idle] → 已断开
 *  - [Connecting] → 连接中
 *  - [Connected] → 已连接（代次 N）
 *  - [Reconnecting] → 重连等待（第 N 次，Xs 后）
 *  - [AuthFailed] → 认证失败
 *  - [Error] → 出错
 */
sealed interface LinkState {
    data object Idle : LinkState
    data object Connecting : LinkState
    data class Connected(val generation: Long) : LinkState
    data class Reconnecting(val attempt: Int, val delayMs: Long, val deadlineMs: Long) : LinkState
    data class AuthFailed(val detail: String) : LinkState
    data class Error(val message: String) : LinkState
}

/** [PhoneLink] 交给网络层去执行的动作。 */
sealed interface LinkAction {
    /** 发起一次 WebSocket 连接。 */
    data object Open : LinkAction

    /** 关闭当前 WebSocket（幂等）。 */
    data object Close : LinkAction

    /** 发送一帧文本。 */
    data class Send(val frame: String) : LinkAction

    /** 在 [delayMs] 毫秒后回调 [PhoneLink.onReconnectTimerFired]。 */
    data class ScheduleReconnect(val delayMs: Long) : LinkAction
}

/**
 * 连接生命周期的**纯逻辑状态机**。
 *
 * 设计约定：
 *  - 本类不碰 OkHttp、不碰 Android 框架，只做「输入事件 → 状态 + 动作」的推导，
 *    因此可以在 JVM 单测里把时间线完整跑一遍（[clock] 注入假时钟）；
 *  - 所有方法在内部锁保护下执行，返回**待执行的动作列表**，由调用方在锁外执行；
 *  - [onStateChanged] / [onPing] 回调在持有内部锁时同步触发，实现必须非阻塞，
 *    并且不得回头调用 [PhoneLink] 的方法。
 */
class PhoneLink(
    private val config: HubConfig,
    private val random: Random = Random.Default,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (LogLevel, String) -> Unit = { _, _ -> },
    val responder: PingResponder = PingResponder(clock = clock),
) {

    private val lock = Any()

    /** 当前状态。 */
    @Volatile
    var state: LinkState = LinkState.Idle
        private set

    /** 用户是否按下了「断开」。为 true 时禁止一切自动重连。 */
    @Volatile
    var userStopped: Boolean = false
        private set

    /** 实际生效的心跳周期，welcome 可以覆盖配置里的值。 */
    @Volatile
    var heartbeatIntervalMs: Long = config.heartbeatIntervalMs
        private set

    /** 实际生效的 ping 超时，welcome 可以覆盖配置里的值。 */
    @Volatile
    var pingTimeoutMs: Long = config.pingTimeoutMs
        private set

    /** 最近一次 ping 的处理结果，供界面展示。 */
    @Volatile
    var lastPing: PingOutcome? = null
        private set

    /** 已经收到的 welcome 里的连接代次。 */
    @Volatile
    var connectionGeneration: Long = 0L
        private set

    var onStateChanged: ((LinkState) -> Unit)? = null

    var onPing: ((PingOutcome) -> Unit)? = null

    // ---- 内部计时 / 连接标志 ------------------------------------------------

    /** 已经有一个连接尝试在飞（Open 之后、终态回调之前）。 */
    private var attemptInFlight: Boolean = false

    /** 当前 WebSocket 处于打开状态（收到 onOpen 之后、终态回调之前）。 */
    private var socketOpen: Boolean = false

    /** 是否已经安排了重连定时器。 */
    private var reconnectScheduled: Boolean = false

    /** 连续失败次数，用于指数退避；连接成功后归零。 */
    private var consecutiveFailures: Int = 0

    /** 最近一次收到服务端任何帧的时刻，0 表示本次连接还没收到过。服务端静默时长据此判断。 */
    @Volatile
    var lastServerFrameAtMs: Long = 0L
        private set

    /** 本次建连尝试的开始时刻，用于握手看门狗。 */
    private var attemptStartedAtMs: Long = 0L

    private var lastHeartbeatSentAtMs: Long = 0L

    // ---- 输入事件 ----------------------------------------------------------

    /** 用户点「连接」。 */
    fun start(): List<LinkAction> = synchronized(lock) {
        userStopped = false
        consecutiveFailures = 0
        reconnectScheduled = false
        attemptInFlight = true
        socketOpen = false
        lastServerFrameAtMs = 0L
        lastHeartbeatSentAtMs = 0L
        attemptStartedAtMs = clock()
        // Gate 0.3 审计发现项 1：**不记完整 URL**。用户填的地址可能带查询参数
        // （`wss://host/phone/ws?token=…`），那是令牌最容易泄漏的位置。只记主机名。
        log(LogLevel.INFO, "开始连接 host=${hostOf(config.serverUrl)}")
        setState(LinkState.Connecting)
        listOf(LinkAction.Open)
    }

    /** 用户点「断开」：置位 [userStopped]，之后任何意外断开都不会再触发重连。 */
    fun stop(reason: String = "用户主动断开"): List<LinkAction> = synchronized(lock) {
        userStopped = true
        reconnectScheduled = false
        attemptInFlight = false
        socketOpen = false
        consecutiveFailures = 0
        log(LogLevel.INFO, reason)
        setState(LinkState.Idle)
        listOf(LinkAction.Close)
    }

    /** WebSocket 已建立：立刻发 hello，但状态仍是「连接中」，等 welcome 才算连上。 */
    fun onSocketOpened(androidSdk: Int): List<LinkAction> = synchronized(lock) {
        if (userStopped) return@synchronized listOf(LinkAction.Close)
        attemptInFlight = false
        socketOpen = true
        val now = clock()
        lastServerFrameAtMs = now
        lastHeartbeatSentAtMs = now
        log(LogLevel.INFO, "WebSocket 已建立，发送 hello")
        listOf(LinkAction.Send(encodeHello(config.deviceId, androidSdk)))
    }

    /**
     * 连接失败。[category] **必须**是 `SocketFailure` 闭集里的固定类别，不是异常原文；
     * [httpStatus] 非空表示是从 WebSocket 升级响应里拿到的状态码，
     * 401 / 403 视为认证失败：凭据错了重试没有意义，交给用户改令牌后手动重连。
     */
    fun onSocketFailure(category: String, httpStatus: Int?): List<LinkAction> =
        synchronized(lock) {
            if (!attemptInFlight && !socketOpen) {
                // 已经处理过的终态回调，忽略
                return@synchronized emptyList()
            }
            attemptInFlight = false
            socketOpen = false
            if (userStopped) return@synchronized emptyList()

            // Gate 0.3 审计发现项 1：[category] 是**闭集**里的固定值（见 SocketFailure），
            // 不是异常原文。这里先收敛一次，保证任何调用方都塞不进自由文本。
            val safeCategory = SocketFailure.normalize(category)
            val text = SocketFailure.describe(safeCategory)

            if (httpStatus == 401 || httpStatus == 403) {
                log(
                    LogLevel.ERROR,
                    "认证失败（HTTP $httpStatus）category=$safeCategory；不自动重连，请检查设备令牌与设备 ID",
                )
                setState(LinkState.AuthFailed("HTTP $httpStatus"))
                return@synchronized emptyList()
            }
            scheduleReconnect("连接失败（category=$safeCategory：$text）")
        }

    /**
     * 本地原因导致无法建连（例如服务器地址为空、令牌为空、配置非法）。
     *
     * 与 [onSocketFailure] 的区别：这类问题重试多少次都一样，所以**不安排重连**，
     * 直接把状态置为 [LinkState.Error] 等用户去「高级设置」里改。
     */
    fun onLocalError(message: String): List<LinkAction> = synchronized(lock) {
        attemptInFlight = false
        socketOpen = false
        reconnectScheduled = false
        log(LogLevel.ERROR, message)
        setState(LinkState.Error(message))
        listOf(LinkAction.Close)
    }

    /** 服务端关闭了连接（我们没有主动关）。 */
    fun onSocketClosed(code: Int, reason: String): List<LinkAction> = synchronized(lock) {
        if (!attemptInFlight && !socketOpen) return@synchronized emptyList()
        attemptInFlight = false
        socketOpen = false
        if (userStopped) return@synchronized emptyList()
        scheduleReconnect("服务端关闭连接（code=$code, reason=$reason）")
    }

    /** 重连定时器到点。 */
    fun onReconnectTimerFired(): List<LinkAction> = synchronized(lock) {
        if (userStopped) return@synchronized emptyList()
        if (!reconnectScheduled) return@synchronized emptyList()
        reconnectScheduled = false
        attemptInFlight = true
        attemptStartedAtMs = clock()
        setState(LinkState.Connecting)
        listOf(LinkAction.Open)
    }

    /**
     * 默认网络恢复。若当前是「用户已连接但没连上」，立刻重试并重置退避。
     */
    fun onNetworkAvailable(): List<LinkAction> = synchronized(lock) {
        if (userStopped) return@synchronized emptyList()
        if (socketOpen) return@synchronized emptyList()

        if (reconnectScheduled) {
            // 定时器还在等退避，提前唤醒
            reconnectScheduled = false
        }
        consecutiveFailures = 0
        attemptInFlight = true
        attemptStartedAtMs = clock()
        log(LogLevel.INFO, "网络已恢复，立即发起重连（退避计数归零）")
        setState(LinkState.Connecting)
        listOf(LinkAction.Close, LinkAction.Open)
    }

    /**
     * 收到一帧文本。
     *
     * 顺序很关键：**先做超长帧检查（R5），再解析**。超长帧不回任何东西，只记日志。
     */
    fun onTextFrame(raw: String): List<LinkAction> = synchronized(lock) {
        if (userStopped) return@synchronized emptyList()

        val now = clock()
        lastServerFrameAtMs = now

        // R5：按 UTF-8 字节数判断
        val bytes = raw.toByteArray(Charsets.UTF_8).size
        if (bytes > config.maxFrameBytes) {
            log(
                LogLevel.WARN,
                "收到超大帧（$bytes 字节 > 上限 ${config.maxFrameBytes} 字节），按 R5 忽略且不回帧",
            )
            return@synchronized emptyList()
        }

        return@synchronized when (val frame = parseIncoming(raw)) {
            is IncomingFrame.Welcome -> handleWelcome(frame)

            is IncomingFrame.Ping -> handlePing(frame)

            is IncomingFrame.HeartbeatAck -> {
                log(LogLevel.INFO, "收到 heartbeat_ack（server_time=${frame.serverTime}）")
                emptyList()
            }

            is IncomingFrame.ServerError -> {
                log(LogLevel.ERROR, "服务端错误帧 code=${ServerErrorCode.normalize(frame.code)}")
                emptyList()
            }

            // R4：未知 type / 非法 JSON / 缺字段 → 忽略并记日志，不回错误
            is IncomingFrame.Unknown -> {
                log(
                    LogLevel.WARN,
                    "忽略无法处理的帧：typeLen=${frame.type.length} reason=${frame.reason}",
                )
                emptyList()
            }
        }
    }

    /**
     * 定时心跳（网络层每 1 秒调一次，本方法自己判断到没到点）。
     *
     *  - R3：每 [heartbeatIntervalMs] 发一个 `{"type":"heartbeat"}`；
     *  - 心跳超时：连续 2 个心跳周期没收到服务端**任何**帧 → 判定连接已死，主动重连。
     */
    fun onTick(): List<LinkAction> = synchronized(lock) {
        if (userStopped) return@synchronized emptyList()

        val now = clock()

        // 握手看门狗：Open 发出去后迟迟没有 onOpen/onFailure（例如 SYN 黑洞、
        // 或被中间设备静默丢弃），不能一直挂在「连接中」，超时就当失败处理。
        if (!socketOpen && attemptInFlight) {
            if (attemptStartedAtMs > 0L && now - attemptStartedAtMs > HANDSHAKE_TIMEOUT_MS) {
                val waited = now - attemptStartedAtMs
                attemptInFlight = false
                return@synchronized scheduleReconnect("建连超时（${waited}ms 内未完成 WebSocket 握手）")
            }
            return@synchronized emptyList()
        }

        if (!socketOpen) return@synchronized emptyList()

        val timeoutMs = max(2L * heartbeatIntervalMs, pingTimeoutMs)

        if (lastServerFrameAtMs > 0L && now - lastServerFrameAtMs > timeoutMs) {
            val silent = now - lastServerFrameAtMs
            log(LogLevel.WARN, "${silent}ms 未收到服务端任何帧（阈值 ${timeoutMs}ms），判定链路已死")
            socketOpen = false
            attemptInFlight = false
            return@synchronized scheduleReconnect("心跳超时（${silent}ms 无任何服务端帧）")
        }

        if (now - lastHeartbeatSentAtMs >= heartbeatIntervalMs) {
            lastHeartbeatSentAtMs = now
            log(LogLevel.INFO, "发送应用层心跳")
            return@synchronized listOf(LinkAction.Send(encodeHeartbeat(now)))
        }

        return@synchronized emptyList()
    }

    // ---- 内部实现 ----------------------------------------------------------

    /** 调用前必须已持有 [lock]。 */
    private fun handleWelcome(w: IncomingFrame.Welcome): List<LinkAction> {
        connectionGeneration = w.connectionGeneration

        w.heartbeatIntervalMs?.let { if (it > 0L) heartbeatIntervalMs = it }
        w.pingTimeoutMs?.let { if (it > 0L) pingTimeoutMs = it }

        if (w.serverTime > 0L) {
            responder.noteServerTime(w.serverTime)
            log(
                LogLevel.INFO,
                "本机与服务端时钟差约 ${responder.clockOffsetMs}ms（仅诊断，不用于修 RTT）",
            )
        }

        if (!w.protocolVersion.isNullOrEmpty() && w.protocolVersion != PROTOCOL_VERSION) {
            log(
                LogLevel.WARN,
                "协议版本不一致：服务端 ${w.protocolVersion}，本端 $PROTOCOL_VERSION",
            )
        }

        consecutiveFailures = 0
        reconnectScheduled = false

        log(
            LogLevel.INFO,
            "收到 welcome：device_id=${w.deviceId} 代次=${w.connectionGeneration} " +
                "心跳=${heartbeatIntervalMs}ms 超时=${pingTimeoutMs}ms " +
                "max_pending=${w.maxPendingRequests ?: config.maxPendingRequests}",
        )
        setState(LinkState.Connected(w.connectionGeneration))
        return emptyList()
    }

    /** 调用前必须已持有 [lock]。 */
    private fun handlePing(ping: IncomingFrame.Ping): List<LinkAction> {
        val outcome = responder.handle(ping)
        lastPing = outcome
        onPing?.invoke(outcome)

        if (outcome.duplicate) {
            // R2：同一个 request_id 只回一次
            log(
                LogLevel.WARN,
                "重复的 request_id=${outcome.requestId}，按 R2 不再回复",
            )
            return emptyList()
        }

        val frame = outcome.frame ?: return emptyList()
        log(
            LogLevel.INFO,
            "收到 phone_ping request_id=${outcome.requestId}，回 phone_pong（估算 RTT ${outcome.rttMs}ms）",
        )
        return listOf(LinkAction.Send(frame))
    }

    /** 调用前必须已持有 [lock]。 */
    private fun scheduleReconnect(why: String): List<LinkAction> {
        if (userStopped) return emptyList()
        if (reconnectScheduled) return emptyList()

        val attemptIndex = consecutiveFailures
        val delay = backoffDelayMs(attemptIndex, config, random)
        consecutiveFailures += 1
        reconnectScheduled = true
        attemptInFlight = false
        socketOpen = false

        val humanAttempt = attemptIndex + 1
        val deadline = clock() + delay
        log(LogLevel.WARN, "$why；第 $humanAttempt 次重连将在 ${delay}ms 后")
        setState(LinkState.Reconnecting(attempt = humanAttempt, delayMs = delay, deadlineMs = deadline))
        return listOf(LinkAction.Close, LinkAction.ScheduleReconnect(delay))
    }

    private fun setState(next: LinkState) {
        if (state == next) return
        state = next
        onStateChanged?.invoke(next)
    }

    companion object {
        /** 建连看门狗：超过这个时间还没握上手就当这次尝试失败，交给退避重连。 */
        const val HANDSHAKE_TIMEOUT_MS: Long = 20_000L
    }
}
