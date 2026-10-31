package com.androidmcphub.app.protocol

import com.androidmcphub.app.capability.CapabilityDispatcher
import com.androidmcphub.app.capability.ToolDispatchOutcome
import com.androidmcphub.app.core.DiagCloseSource
import com.androidmcphub.app.core.HubConfig
import com.androidmcphub.app.core.LinkEvent
import com.androidmcphub.app.core.LinkEventKind
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
 *  - [Revoked] → 设备凭据已被服务端撤销
 *  - [Error] → 出错
 */
sealed interface LinkState {
    data object Idle : LinkState
    data object Connecting : LinkState
    data class Connected(val generation: Long) : LinkState
    data class Reconnecting(val attempt: Int, val delayMs: Long, val deadlineMs: Long) : LinkState
    data class AuthFailed(val detail: String) : LinkState

    /**
     * ★ 集中审计问题 4：服务端**明确撤销了这台设备的凭据**，连接必须到此为止。
     *
     * 为什么它必须与 [AuthFailed]、[Reconnecting] 分开：
     *  - 与 [Reconnecting] 分开：这是"重试多少次都会被同样拒绝"的**终态**，
     *    继续自动重连只会把"有人把凭据撤了"伪装成"网络不稳定"；
     *  - 与 [AuthFailed] 分开：401/403 是**握手**被拒（凭据从一开始就没通过），
     *    而这里是**已经连上之后**服务端主动终结（`error{device_revoked}` +
     *    close 4002）。两者的排查动作相同（改令牌），但证据不同，
     *    所以状态与文案都分开，日志里能直接区分"没连上"与"连上后被撤"。
     */
    data class Revoked(val detail: String) : LinkState
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
    /**
     * Gate 2：能力分发器。
     *
     * **默认是一个空注册表** —— 也就是说，除非调用方显式注册了能力，
     * 否则任何 `tool_request` 都会得到 `unsupported_tool`。
     * 这是刻意的默认值：忘记接线时的后果是"功能不可用"，而不是"意外多了一个能用的能力"。
     */
    val dispatcher: CapabilityDispatcher = CapabilityDispatcher(emptyList(), clock = clock),
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

    /** 最近一次收到 heartbeat_ack 的时刻；0 表示本次进程里还没收到过。 */
    @Volatile
    var lastHeartbeatAckAtMs: Long = 0L
        private set

    /**
     * 最近一次**发出**应用层心跳的时刻；0 表示本次连接里还没发过。
     *
     * V1.6.2：诊断页要能回答"心跳到底发出去了没有"。只显示"最近心跳成功"
     * 时，如果之后再也没有成功过，用户无法区分"没发出去"和"发出去了但没回"。
     */
    @Volatile
    var lastHeartbeatSentAtMs: Long = 0L
        private set

    var onStateChanged: ((LinkState) -> Unit)? = null

    var onPing: ((PingOutcome) -> Unit)? = null

    /**
     * 结构化的连接事件回调（诊断页消费）。
     *
     * 与 [onStateChanged] 的区别：状态只描述"现在是什么"，事件描述"刚刚发生了什么"。
     * 例如心跳超时之后状态同样是 `Reconnecting`，但只有事件能把它和"服务端主动关闭"
     * 区分开。回调在持有内部锁时同步触发，实现必须非阻塞且不得回头调用本类。
     */
    var onEvent: ((LinkEvent) -> Unit)? = null

    /** 收到 heartbeat_ack 时回调（诊断页的"最近心跳成功"）。 */
    var onHeartbeatAck: ((Long) -> Unit)? = null

    /** 发出应用层心跳时回调（诊断页的"最近心跳发送"）。 */
    var onHeartbeatSent: ((Long) -> Unit)? = null

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

    /**
     * 下一次应用层心跳的计时基准（socket 打开时刻，或上一次真正发心跳的时刻）。
     *
     * 必须与 [lastHeartbeatSentAtMs] 分开：后者是给诊断页看的"真的发过一帧心跳"，
     * 而 socket 刚打开时还没发过。把两者合并会让诊断页显示一条并不存在的发送记录。
     */
    private var heartbeatBaselineMs: Long = 0L

    // ---- 输入事件 ----------------------------------------------------------

    /** 用户点「连接」。 */
    fun start(): List<LinkAction> = synchronized(lock) {
        // ★ 问题 4：这里是终态（AuthFailed / Revoked / Error）**唯一**的出口。
        // 用户在「高级设置」改完设备令牌（或重新配对）后点「连接」，状态先回到
        // [LinkState.Connecting]，[isTerminal] 随即不再成立，重连逻辑恢复工作。
        userStopped = false
        consecutiveFailures = 0
        reconnectScheduled = false
        attemptInFlight = true
        socketOpen = false
        lastServerFrameAtMs = 0L
        lastHeartbeatSentAtMs = 0L
        lastHeartbeatAckAtMs = 0L
        heartbeatBaselineMs = 0L
        attemptStartedAtMs = clock()
        // Gate 0.3 审计发现项 1：**不记完整 URL**。用户填的地址可能带查询参数
        // （`wss://host/phone/ws?token=…`），那是令牌最容易泄漏的位置。只记主机名。
        log(LogLevel.INFO, "开始连接 host=${hostOf(config.serverUrl)}")
        setState(LinkState.Connecting)
        listOf(LinkAction.Open)
    }

    /**
     * 用户点「断开」：置位 [userStopped]，之后任何意外断开都不会再触发重连。
     *
     * [source] 必须标识**真实来源**（`ui_button` / `notification_action`）。
     * 只有这条路会产生 [LinkEventKind.USER_DISCONNECTED] —— 服务销毁、启动失败、
     * 系统回收都不得借用它，否则诊断页会把系统行为说成用户行为（V1.6.2 修复点）。
     */
    fun stop(
        reason: String = "用户主动断开",
        source: String = "unknown",
    ): List<LinkAction> = synchronized(lock) {
        val wasStopped = userStopped
        userStopped = true
        reconnectScheduled = false
        attemptInFlight = false
        socketOpen = false
        consecutiveFailures = 0
        log(LogLevel.INFO, "$reason（source=$source）")
        // 重复调用（例如服务随后销毁）不该在诊断里留下第二条"用户断开"。
        if (!wasStopped) emit(LinkEventKind.USER_DISCONNECTED, "source=$source", source = source)
        setState(LinkState.Idle)
        listOf(LinkAction.Close)
    }

    /**
     * 非用户原因停止（服务被系统销毁、前台服务启动失败、无有效启动意图）。
     *
     * 语义与 [stop] 的区别只有一点，但很关键：**不产生 `USER_DISCONNECTED`**。
     * 重连同样被禁止（服务都没了，重连没有意义），状态回到 [LinkState.Idle]，
     * 但事件与日志会明确写成服务停止，而不是用户断开。
     */
    fun abandon(reason: String): List<LinkAction> = synchronized(lock) {
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
        // 只是建连成功，还没发出心跳：计时基准从这里开始，但**不**伪造一条"已发送"。
        heartbeatBaselineMs = now
        log(LogLevel.INFO, "WebSocket 已建立，发送 hello")
        listOf(LinkAction.Send(encodeHello(config.deviceId, androidSdk)))
    }

    /**
     * 连接失败。[category] **必须**是 `SocketFailure` 闭集里的固定类别，不是异常原文；
     * [httpStatus] 非空表示是从 WebSocket 升级响应里拿到的状态码，
     * 401 / 403 视为认证失败：凭据错了重试没有意义，交给用户改令牌后手动重连。
     * [exceptionClass] 只允许异常**类名**（见 `normalizeExceptionClass`），
     * 不传 message —— 那里面可能混有地址或凭据片段。
     */
    fun onSocketFailure(
        category: String,
        httpStatus: Int?,
        exceptionClass: String? = null,
    ): List<LinkAction> =
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
                // 终态就得把**还挂着的**重连定时器一并作废，否则一个在认证失败之前
                // 排下的定时器到点后会把连接重新拉起来，绕过"不自动重连"这条规则。
                reconnectScheduled = false
                emit(
                    LinkEventKind.AUTHENTICATION_FAILED,
                    "HTTP $httpStatus，不自动重连",
                    generation = generationOrNull(),
                    category = safeCategory,
                    exceptionClass = exceptionClass,
                )
                setState(LinkState.AuthFailed("HTTP $httpStatus"))
                return@synchronized emptyList()
            }
            emit(
                LinkEventKind.SOCKET_FAILURE,
                "连接失败 category=$safeCategory",
                generation = generationOrNull(),
                category = safeCategory,
                exceptionClass = exceptionClass,
            )
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

        // ★ 集中审计问题 4：4002 是**服务端明确撤销了这台设备的凭据**
        // （`worker/src/phone/messages.ts` 的 `CLOSE_CODE_REVOKED`）。
        // 这不是"网络抖了一下"：拿同一份凭据重连多少次，都会在升级阶段被 403 拒掉。
        // 所以这里进入**可诊断的终态**并停止自动重连 —— 否则一个必须由人来处理的
        // 配置问题（凭据被撤/被轮换）会被无限重连伪装成"网络不稳定"。
        if (code == CLOSE_CODE_REVOKED) {
            enterRevoked(code = code)
            return@synchronized emptyList()
        }

        // 只记录**实际收到**的关闭码；welcome 之前就断开的连接没有服务端代次。
        emit(
            LinkEventKind.SERVER_CLOSED,
            "服务端关闭 code=$code",
            generation = generationOrNull(),
            closeCode = code,
        )
        // V1.6.3：`reason` 是对端自由文本，可能被改造过的服务端塞进任意内容
        // （包括令牌形状的字符串）。只记关闭码；原因交给云端按**自己的**
        // 关闭意图来判定（服务端知道是不是自己关的、为什么关）。
        scheduleReconnect("服务端关闭连接（code=$code）")
    }

    /** 重连定时器到点。 */
    fun onReconnectTimerFired(): List<LinkAction> = synchronized(lock) {
        if (userStopped) return@synchronized emptyList()
        if (!reconnectScheduled) return@synchronized emptyList()
        // ★ 问题 4：终态之后任何残留的定时器都必须失效 —— 它是"被撤销"与
        // "用户改完令牌重新连接（start）"之间唯一可能偷偷复活连接的东西。
        if (isTerminal(state)) {
            reconnectScheduled = false
            return@synchronized emptyList()
        }
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

        // ★ 问题 4：终态不因为"网络恢复了"而复活 —— 网络与"凭据被撤销"这个结论
        // 毫无关系，自动重连只会再被同样地拒一次，还会把界面上的原因覆盖掉。
        if (isTerminal(state)) return@synchronized emptyList()

        if (reconnectScheduled) {
            // 定时器还在等退避，提前唤醒
            reconnectScheduled = false
        }
        consecutiveFailures = 0
        attemptInFlight = true
        attemptStartedAtMs = clock()
        log(LogLevel.INFO, "网络已恢复，立即发起重连（退避计数归零）")
        emit(LinkEventKind.NETWORK_CHANGED, "网络恢复，立即重连")
        setState(LinkState.Connecting)
        listOf(LinkAction.Close, LinkAction.Open)
    }

    /**
     * 默认网络断开（`ConnectivityManager.onLost`）。
     *
     * 不能只等心跳超时：信号丢失后 TCP 往往不会立刻报错，等 60 秒静默阈值再重连
     * 会让"网络回来"这段时间白白浪费。这里把当前连接判死并进入退避；紧接着的
     * [onNetworkAvailable] 会取消退避、立即重连。两个事件都在网络层的单线程调度器上
     * 串行执行，不会产生两条并发连接。
     */
    fun onNetworkLost(): List<LinkAction> = synchronized(lock) {
        if (userStopped) return@synchronized emptyList()
        if (isTerminal(state)) return@synchronized emptyList()

        // 网络断开时若 socket 还开着，本机会主动关掉它 —— 这是**本机**的关闭动作，
        // 来源如实记成 network_lost，而不是等云端猜"为什么 30 秒后没了连接"。
        emit(
            LinkEventKind.NETWORK_CHANGED,
            "默认网络已断开",
            source = DiagCloseSource.NETWORK_LOST,
        )
        if (!socketOpen && !attemptInFlight) return@synchronized emptyList()

        socketOpen = false
        attemptInFlight = false
        scheduleReconnect("默认网络已断开")
    }

    /**
     * 前台服务看门狗：连接看起来还在，但已经很久没有收到任何服务端帧。
     *
     * 旧实现会在 5 分钟后把服务**永久停掉**，后台断网时用户再也没法自动恢复。
     * 现在只做一次强制重连：已经有 [onTick] 的心跳超时在 60 秒级别兜底，
     * 这里处理的是"调度线程被系统冻结、恢复后 tick 计时已经失真"的极端情况。
     */
    fun onWatchdogTimeout(silentMs: Long): List<LinkAction> = synchronized(lock) {
        if (userStopped) return@synchronized emptyList()
        if (isTerminal(state)) return@synchronized emptyList()
        if (!socketOpen && !attemptInFlight) return@synchronized emptyList()

        log(LogLevel.WARN, "看门狗：连续 ${silentMs / 1000} 秒没有服务端帧，强制重连（不停止服务）")
        socketOpen = false
        attemptInFlight = false
        emit(
            LinkEventKind.HEARTBEAT_TIMEOUT,
            "看门狗判定 ${silentMs / 1000}s 无服务端帧",
            source = DiagCloseSource.WATCHDOG,
            heartbeatAgeMs = silentMs,
        )
        scheduleReconnect("看门狗判定会话僵死（${silentMs}ms 无服务端帧）")
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

            is IncomingFrame.ToolRequest -> handleToolRequest(frame)

            is IncomingFrame.ToolRequestMalformed -> handleMalformedToolRequest(frame)

            is IncomingFrame.HeartbeatAck -> {
                lastHeartbeatAckAtMs = now
                onHeartbeatAck?.invoke(now)
                log(LogLevel.INFO, "收到 heartbeat_ack（server_time=${frame.serverTime}）")
                emptyList()
            }

            is IncomingFrame.ServerError -> {
                val safeCode = ServerErrorCode.normalize(frame.code)
                if (safeCode == ServerErrorCode.DEVICE_REVOKED) {
                    // ★ 问题 4：服务端在这里之后**通常**会紧接着以 4002 关掉 socket，
                    // 但关闭回调不一定总能到达（进程被杀、网络黑洞、连接被中间设备吞掉）。
                    // 所以终态在**收到错误帧的这一刻**就置上：让"凭据被撤销"这件事
                    // 无论如何都能被看到，且不会被任何还挂着的重连定时器复活。
                    enterRevoked(code = null)
                    return@synchronized listOf(LinkAction.Close)
                }
                log(LogLevel.ERROR, "服务端错误帧 code=$safeCode")
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
                emit(LinkEventKind.HANDSHAKE_TIMEOUT, "握手超时（${waited}ms）")
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
            emit(
                LinkEventKind.HEARTBEAT_TIMEOUT,
                "${silent}ms 无服务端帧",
                generation = generationOrNull(),
                heartbeatAgeMs = silent,
            )
            return@synchronized scheduleReconnect("心跳超时（${silent}ms 无任何服务端帧）")
        }

        if (now - heartbeatBaselineMs >= heartbeatIntervalMs) {
            heartbeatBaselineMs = now
            lastHeartbeatSentAtMs = now
            onHeartbeatSent?.invoke(now)
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
        emit(LinkEventKind.CONNECTED, "代次 ${w.connectionGeneration}", generation = w.connectionGeneration)
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

    /**
     * 处理一帧 `tool_request`（Gate 2）。调用前必须已持有 [lock]。
     *
     * 日志纪律：**只记工具名与结论，绝不记参数值**。参数里可能有服务端透传下来的
     * 用户数据，而日志会被写进界面与 logcat；工具名已经在分发器里做过形状收敛
     * （不在白名单的会变成 `<unknown>`），所以记它是安全的。
     */
    private fun handleToolRequest(request: IncomingFrame.ToolRequest): List<LinkAction> {
        val outcome = dispatcher.handle(request.requestId, request.toolName, request.arguments)

        if (outcome.duplicate) {
            // 与 R2 同款语义：同一个 request_id 只执行一次、只回一次。
            log(LogLevel.WARN, "重复的 tool_request request_id=${request.requestId}，不再执行也不再回复")
            return emptyList()
        }

        if (outcome.deferred) {
            // 已受理，结果稍后异步交付（见 [onToolResultReady]）。
            // 这里**必须**立刻返回空动作：聚合命令是网络 I/O，读线程上等它就等于
            // 让整条连接（连心跳）一起停摆。
            log(LogLevel.INFO, "工具调用 tool=${outcome.toolName} 已受理，结果稍后回发")
            return emptyList()
        }

        logOutcome(outcome)
        val frame = outcome.frame ?: return emptyList()
        return listOf(LinkAction.Send(frame))
    }

    /**
     * 异步（聚合命令）结果的落地口。
     *
     * 由传输层设置：必须在**它自己的调度线程**上把帧发出去，不得在本回调里直接碰
     * socket，也不得回头调用 [PhoneLink] 的任何方法（这条回调是从工作线程来的）。
     */
    var onToolResultReady: ((ToolDispatchOutcome) -> Unit)? = null

    /**
     * 把一条**已结束**的聚合命令结果交给上层发送。由工作线程调用。
     *
     * 只做日志与转发；不读写任何连接状态，因此不需要 [lock]。
     */
    fun deliverToolResult(outcome: ToolDispatchOutcome) {
        logOutcome(outcome)
        onToolResultReady?.invoke(outcome)
    }

    private fun logOutcome(outcome: ToolDispatchOutcome) {
        if (outcome.ok) {
            log(LogLevel.INFO, "工具调用 tool=${outcome.toolName} 成功，耗时 ${outcome.elapsedMs}ms")
        } else {
            log(
                LogLevel.WARN,
                "工具调用 tool=${outcome.toolName} 失败 code=${outcome.errorCode} 耗时 ${outcome.elapsedMs}ms",
            )
        }
    }

    /**
     * 信封完好但内容不合法的 `tool_request`（目前只有"`arguments` 不是对象"）。
     *
     * 和 [IncomingFrame.Unknown] 的处理不同：这里**要回帧**。回不了会让服务端
     * 一直等到超时，把"参数写错了"说成"手机可能死了"。
     *
     * 刻意不占用去重窗口：这条路径没有执行任何能力，也没有消耗一个"真正的工作请求"。
     */
    private fun handleMalformedToolRequest(request: IncomingFrame.ToolRequestMalformed): List<LinkAction> {
        log(LogLevel.WARN, "拒绝格式不合法的 tool_request request_id=${request.requestId} reason=${request.reason}")

        val frame = encodeToolResult(
            requestId = request.requestId,
            ok = false,
            errorCode = ToolResultErrorCode.INVALID_ARGUMENTS,
            errorMessage = "请求格式不合法（${request.reason}）",
        )
        return listOf(LinkAction.Send(frame))
    }

    /** 调用前必须已持有 [lock]。 */
    private fun scheduleReconnect(why: String): List<LinkAction> {
        if (userStopped) return emptyList()
        // ★ 问题 4：终态没有"再试一次"这条路（见 [isTerminal]）。这一道是兜底，
        // 防止将来新增的调用点绕过各回调里的显式判断。
        if (isTerminal(state)) return emptyList()
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
        emit(LinkEventKind.RECONNECTING, "第 $humanAttempt 次，${delay}ms 后", attempt = humanAttempt)
        setState(LinkState.Reconnecting(attempt = humanAttempt, delayMs = delay, deadlineMs = deadline))
        return listOf(LinkAction.Close, LinkAction.ScheduleReconnect(delay))
    }

    private fun setState(next: LinkState) {
        if (state == next) return
        state = next
        onStateChanged?.invoke(next)
    }

    /**
     * 记录一条结构化事件。调用前必须已持有 [lock]。
     *
     * [detail] 由本类拼装，只含代次/次数/固定类别/耗时；回调实现必须非阻塞。
     *
     * V1.6.3 起额外带结构化证据（代次、关闭码、失败类别、本地关闭来源、心跳静默
     * 时长）。这些字段是**事件发生那一刻**的事实快照，不是"当前值" ——
     * 例如 `SERVER_CLOSED` 里的代次就是被关闭的那条连接代次。
     */
    private fun emit(
        kind: LinkEventKind,
        detail: String,
        generation: Long? = null,
        closeCode: Int? = null,
        category: String? = null,
        source: String? = null,
        attempt: Int? = null,
        heartbeatAgeMs: Long? = null,
        exceptionClass: String? = null,
    ) {
        onEvent?.invoke(
            LinkEvent(
                kind = kind,
                atMs = clock(),
                detail = detail,
                generation = generation,
                closeCode = closeCode,
                category = category,
                source = source,
                attempt = attempt,
                heartbeatAgeMs = heartbeatAgeMs,
                exceptionClass = exceptionClass,
            ),
        )
    }

    /** 已经收到过 welcome 才有一个真实的服务端代次；否则是 null，不是 0。 */
    private fun generationOrNull(): Long? = connectionGeneration.takeIf { it > 0L }

    /**
     * ★ 集中审计问题 4：进入"凭据被服务端撤销"的终态。调用前必须已持有 [lock]。
     *
     * 幂等：无论先到的是 `error{device_revoked}` 帧还是 close 4002，结果都一样，
     * 重复调用不会产生第二条日志、也不会改变状态。
     *
     * [code] 为 null 表示"从错误帧进入"，非 null 表示"从关闭码进入"——分开记日志，
     * 是为了排查时能一眼看出**哪条证据先到**。
     */
    private fun enterRevoked(code: Int?) {
        reconnectScheduled = false
        attemptInFlight = false
        socketOpen = false
        if (state is LinkState.Revoked) return

        val evidence = if (code == null) "error 帧 code=device_revoked" else "close code=$code"
        log(
            LogLevel.ERROR,
            "服务端撤销了本机凭据（$evidence）；**不自动重连**。" +
                "请到「高级设置」更新设备令牌（或重新配对）后手动点「连接」",
        )
        emit(
            LinkEventKind.AUTHENTICATION_FAILED,
            "凭据已被撤销（$evidence）",
            closeCode = code,
            // 错误帧路径是**本机**随后关掉 socket；4002 路径是服务端先关的，
            // 不能记成本机主动关闭。
            source = if (code == null) DiagCloseSource.REVOKED else null,
        )
        setState(LinkState.Revoked(REVOKED_DETAIL))
    }

    /**
     * 终态：这些状态下**绝不**自动重连，也绝不被残留的定时器 / 网络恢复事件复活。
     *
     * 为什么把三种状态放在一起：[AuthFailed]（握手被拒）、[Revoked]（连上后被撤）、
     * [Error]（本地配置不合法）都是"重试不会让结果变好，必须由人改点什么"。
     * 唯一的出口是用户点「连接」——[start] 会把状态重置为 [LinkState.Connecting]。
     */
    private fun isTerminal(current: LinkState): Boolean = when (current) {
        is LinkState.AuthFailed, is LinkState.Revoked, is LinkState.Error -> true
        is LinkState.Idle, is LinkState.Connecting, is LinkState.Connected, is LinkState.Reconnecting -> false
    }

    companion object {
        /** 建连看门狗：超过这个时间还没握上手就当这次尝试失败，交给退避重连。 */
        const val HANDSHAKE_TIMEOUT_MS: Long = 20_000L

        /**
         * ★ 集中审计问题 4：服务端的"设备凭据已撤销"关闭码。
         *
         * 必须与 `worker/src/phone/messages.ts` 的 `CLOSE_CODE_REVOKED` 保持一致
         * （Worker 测试 `worker/test/phone-session.test.ts` 也断言 4002）。
         * 它属于 4000–4999 的"应用私有"区间，不会和 WebSocket 标准码撞车。
         */
        const val CLOSE_CODE_REVOKED: Int = 4002

        /**
         * 终态文案。刻意**不含**任何对端给的自由文本（服务端的 `message` 不落地、
         * 不入日志），只描述手机侧该做什么。
         */
        const val REVOKED_DETAIL: String = "设备凭据已被服务端撤销"
    }
}
