package com.androidmcphub.app.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.androidmcphub.app.MainActivity
import com.androidmcphub.app.R
import com.androidmcphub.app.aggregation.McpHubRouter
import com.androidmcphub.app.capability.BuiltInCapabilities
import com.androidmcphub.app.capability.CapabilityDispatcher
import com.androidmcphub.app.capability.CapabilityMetadata
import com.androidmcphub.app.core.HubLog
import com.androidmcphub.app.core.HubPrefs
import com.androidmcphub.app.core.LinkEvent
import com.androidmcphub.app.core.LinkEventKind
import com.androidmcphub.app.core.LogEntry
import com.androidmcphub.app.core.LogLevel
import com.androidmcphub.app.core.NetworkKind
import com.androidmcphub.app.core.networkKind
import com.androidmcphub.app.mcp.McpStores
import com.androidmcphub.app.core.SocketFailure
import com.androidmcphub.app.core.hostOf
import com.androidmcphub.app.net.PhoneLinkClient
import com.androidmcphub.app.protocol.LinkState
import com.androidmcphub.app.protocol.PingOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 进程内状态总线：前台服务往里写，界面（ViewModel/Compose）从里读。
 *
 * 只用 StateFlow 做「最新值 + 变化通知」，不引任何事件总线库。
 * 进程被杀时一起消失，这是可接受的 —— 状态本来就只对当前进程有意义。
 */
object LinkBus {

    /** 环形日志（最多 200 条，最新在前）。 */
    val log: HubLog = HubLog()

    private val _state = MutableStateFlow<LinkState>(LinkState.Idle)
    val state: StateFlow<LinkState> = _state.asStateFlow()

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    private val _lastPing = MutableStateFlow<PingOutcome?>(null)
    val lastPing: StateFlow<PingOutcome?> = _lastPing.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)

    /**
     * 最近一次错误（用户可读文案），与当前状态**解耦**。
     *
     * 为什么单独存一份：服务在 `onDestroy` 里会把连接状态复位成 [LinkState.Idle]，
     * 如果错误只活在 `state` 里，用户看到的就是"莫名其妙变回未连接"，
     * 「最近一次错误」那一栏也永远是空的。审计 R3 要求失败必须反映到界面状态上，
     * 所以错误要能活过状态复位。这里是内存态，不落盘。
     */
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _serviceRunning = MutableStateFlow(false)
    val serviceRunning: StateFlow<Boolean> = _serviceRunning.asStateFlow()

    /** 结构化连接事件（最新在前，最多 [MAX_EVENTS] 条）。 */
    private val _events = MutableStateFlow<List<LinkEvent>>(emptyList())
    val events: StateFlow<List<LinkEvent>> = _events.asStateFlow()

    private val _diagnostics = MutableStateFlow(LinkDiagnostics())
    val diagnostics: StateFlow<LinkDiagnostics> = _diagnostics.asStateFlow()

    init {
        log.setListener { entries -> _logs.value = entries }
    }

    fun publishState(state: LinkState) {
        _state.value = state
        // 只在错误类状态上写入；连接成功/重连中**不**清空它 ——
        // 否则用户一重连就再也看不到上一次失败的原因了。
        when (state) {
            is LinkState.Error -> _lastError.value = state.message
            is LinkState.AuthFailed -> _lastError.value = state.detail
            // ★ 问题 4：被撤销也写进「最近一次错误」。这是**终态**，界面不会再自动重连，
            // 用户只有看到这条原因才知道该去改令牌，而不是等一个永远不来的重连。
            is LinkState.Revoked -> _lastError.value = state.detail
            else -> Unit
        }
    }

    fun publishPing(outcome: PingOutcome?) {
        _lastPing.value = outcome
    }

    fun publishServiceRunning(running: Boolean) {
        _serviceRunning.value = running
    }

    /** 收到 heartbeat_ack 时刷新"最近心跳成功"。 */
    fun publishHeartbeatAck(atMs: Long) {
        if (atMs <= 0L) return
        _diagnostics.value = _diagnostics.value.copy(lastHeartbeatAckAtMs = atMs)
    }

    /** 发出应用层心跳时刷新"最近心跳发送"。 */
    fun publishHeartbeatSent(atMs: Long) {
        if (atMs <= 0L) return
        _diagnostics.value = _diagnostics.value.copy(lastHeartbeatSentAtMs = atMs)
    }

    /** 同步用户落盘的连接意图（`HubPrefs.linkDesired`），供诊断页判断"是不是还该连着"。 */
    fun publishLinkDesired(desired: Boolean) {
        _diagnostics.value = _diagnostics.value.copy(linkDesired = desired)
    }

    /** 记录一次前台服务停止的原因；服务重新启动时清空。 */
    fun publishServiceStop(reason: String?) {
        _diagnostics.value = _diagnostics.value.copy(lastServiceStopReason = reason)
    }

    /** 当前默认网络类型（粗分类，不含 SSID/IP）。 */
    fun publishNetworkKind(kind: NetworkKind) {
        _diagnostics.value = _diagnostics.value.copy(networkLabel = kind.label)
    }

    fun recordEvent(event: LinkEvent) {
        _events.value = (listOf(event) + _events.value).take(MAX_EVENTS)
        _diagnostics.value = when (event.kind) {
            LinkEventKind.CONNECTED -> _diagnostics.value.copy(lastConnectedAtMs = event.atMs)

            LinkEventKind.DISCONNECTED,
            LinkEventKind.HEARTBEAT_TIMEOUT,
            LinkEventKind.AUTHENTICATION_FAILED,
            LinkEventKind.USER_DISCONNECTED,
            LinkEventKind.SERVICE_DESTROYED,
            -> _diagnostics.value.copy(
                lastDisconnectAtMs = event.atMs,
                lastDisconnectReason = event.detail.ifBlank { event.kind.label },
                lastDisconnectSource = disconnectSourceOf(event),
            )

            LinkEventKind.RECONNECTING ->
                _diagnostics.value.copy(reconnectCount = _diagnostics.value.reconnectCount + 1)

            LinkEventKind.NETWORK_CHANGED,
            LinkEventKind.SERVICE_RESTARTED,
            -> _diagnostics.value
        }
    }

    fun recordEvent(kind: LinkEventKind, detail: String = "", atMs: Long = System.currentTimeMillis()) {
        recordEvent(LinkEvent(kind = kind, atMs = atMs, detail = detail))
    }

    /**
     * 把一条断开类事件收敛成"谁引发的"闭集标签。
     *
     * 诊断页曾经只有一句自由文本的 [LinkDiagnostics.lastDisconnectReason]，
     * 而那句话在 V1.6.1 及以前由服务销毁路径写成了「用户主动断开」。
     * 现在来源单独成字段，且只允许下列前缀，界面不再需要从中文里猜。
     */
    private fun disconnectSourceOf(event: LinkEvent): String = when (event.kind) {
        LinkEventKind.USER_DISCONNECTED ->
            "user:" + event.detail.removePrefix("source=").ifBlank { "unknown" }

        LinkEventKind.SERVICE_DESTROYED -> "service_destroyed"
        LinkEventKind.HEARTBEAT_TIMEOUT -> "heartbeat_timeout"
        LinkEventKind.AUTHENTICATION_FAILED -> "auth_failed"

        LinkEventKind.DISCONNECTED -> when {
            event.detail.startsWith("服务端关闭") -> "remote_close"
            event.detail.startsWith("握手超时") -> "handshake_timeout"
            event.detail.contains("category=") ->
                "network:" + event.detail.substringAfter("category=").substringBefore(' ')

            else -> "network_failure"
        }

        else -> event.kind.name.lowercase()
    }

    /** 用户主动清空「最近一次错误」（界面上的「清空日志」顺带做这件事）。 */
    fun clearLastError() {
        _lastError.value = null
    }

    /** 服务停止后把「连接相关」的展示状态复位（日志、事件与最近错误保留，方便用户回看刚才发生了什么）。 */
    fun resetLink() {
        _state.value = LinkState.Idle
        _lastPing.value = null
    }

    /** 测试/诊断页「清空事件」用：不影响当前连接状态。 */
    fun clearEvents() {
        _events.value = emptyList()
    }

    /** 测试用：把统计与事件一起复位。运行期没有调用点。 */
    fun resetDiagnostics() {
        _events.value = emptyList()
        _diagnostics.value = LinkDiagnostics()
    }

    private const val MAX_EVENTS = 50
}

/**
 * 诊断页展示的连接统计。
 *
 * 全是**内存态**：进程被杀后归零是预期行为，因为真实状态本来就只对当前进程有意义。
 * 跨进程/重启的"用户是否希望连接"由 `HubPrefs.linkDesired()` 负责，两者不要混为一谈。
 */
data class LinkDiagnostics(
    val lastConnectedAtMs: Long? = null,
    /** 最近一次**发出**应用层心跳；与 [lastHeartbeatAckAtMs] 一起用来区分"没发"与"没回"。 */
    val lastHeartbeatSentAtMs: Long? = null,
    val lastHeartbeatAckAtMs: Long? = null,
    val lastDisconnectAtMs: Long? = null,
    val lastDisconnectReason: String? = null,
    /**
     * 断开是谁引发的（V1.6.2）。
     *
     * 取值来自闭集：`user:ui_button`、`user:notification_action`、`remote_close`、
     * `network_reset`、`heartbeat_timeout`、`auth_failed`、`service_destroyed`。
     * 诊断页据此回答"用户到底有没有主动断开"。
     */
    val lastDisconnectSource: String? = null,
    val reconnectCount: Int = 0,
    val networkLabel: String = NetworkKind.NONE.label,
    /** 用户落盘的连接意图；与当前 socket 状态分开看。 */
    val linkDesired: Boolean = false,
    /** 最近一次前台服务被停止的原因（用户断开 / 系统停止 / 启动失败 / 无启动意图）。 */
    val lastServiceStopReason: String? = null,
)

/**
 * 前台服务：持有 WebSocket 长连接。
 *
 * 生命周期只由用户动作驱动：
 *  - 用户在前台点「连接」→ [ACTION_CONNECT] → `startForegroundService`（唯一入口，不做开机自启）；
 *  - 用户点「断开」或通知栏的「断开」按钮 → [ACTION_DISCONNECT] → 停前台、`stopSelf()`。
 *
 * 前台服务类型是 `specialUse`，理由见 `README-ANDROID.md`。
 */
class PhoneLinkService : Service() {

    private lateinit var prefs: HubPrefs
    private var client: PhoneLinkClient? = null
    private var connectivity: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /** 最近一次默认网络；用来过滤"旧网络"迟到的 onLost，见 [registerNetworkCallback]。 */
    private var lastDefaultNetwork: Network? = null

    /** 同一个网络短时间内重复上报 onAvailable 的抑制窗口。 */
    private var lastAvailableNetwork: Network? = null
    private var lastAvailableAtMs: Long = 0L

    /**
     * 本次服务实例**为什么**要停止（仅在本机主动 `stopSelf()` 前写入）。
     *
     * `null` 表示停止不是本服务的任何一条主动路径发出的 —— `onDestroy()` 会把它
     * 判为"系统停止前台服务"，并记录 [LinkEventKind.SERVICE_DESTROYED]，
     * **绝不**记成用户断开。这是 V1.6.2 对"用户没点断开却显示用户主动断开"的修复核心。
     */
    private var stopInProgress: String? = null

    /**
     * 连接期间持有的一把**非引用计数**的部分唤醒锁。
     *
     * 为什么需要：后台/息屏后 CPU 会进入休眠，`ScheduledExecutorService` 的 1 秒 tick
     * 与 30 秒心跳都依赖它。没有唤醒锁时，系统可能长时间不调度这个线程，
     * 服务端 90 秒收不到心跳就判定手机离线 —— 表现正是"通知还在、ChatGPT 调不通"。
     *
     * 边界：部分唤醒锁只保证 CPU 不休眠，**不能**绕过 Doze/厂商省电策略的网络限制；
     * 真要长时间稳定，还需要用户在系统里把本应用的电池优化设为"不限制"。
     * 只在用户希望保持连接时持有，断开/终态/服务销毁都会释放。
     */
    private var wakeLock: PowerManager.WakeLock? = null

    /**
     * 能力元数据按工具名索引：授权策略需要知道"这一条是不是写入类"。
     *
     * 数据源仍然是 `BuiltInCapabilities` 那份唯一注册清单 —— 这里只是建索引，
     * 不是第二份清单。查不到的名字按"未知"处理，策略里会保守地按只读放行，
     * 但分发器第 3 步的白名单查找会先把它拦下，所以这条分支实际不可达。
     */
    private val capabilityMetadataByName: Map<String, CapabilityMetadata> =
        BuiltInCapabilities.metadata.associateBy { it.name }

    /**
     * 聚合命令（第三方 MCP 工具）的工作线程池。
     *
     * 为什么要单独一组线程：`tool_request` 是在 **WebSocket 读线程**上被处理的
     * （`PhoneLinkClient.onMessage` → `PhoneLink.onTextFrame`）。一次目录刷新要连
     * 几台第三方服务器，把它放在读线程上，期间连心跳帧都读不出来 —— 实测里
     * 并发的 `phone_ping` 超时就是这么发生的。线程数刻意很小（2）：聚合命令在
     * 协议上是低频动作，多开线程不会更快，只会多出几个并发重连。
     */
    private var hubWorkers: ExecutorService? = null

    private val handler = Handler(Looper.getMainLooper())

    /** 服务启动时刻，用于会话僵死看门狗（还没收到过任何服务端帧时的兜底起点）。 */
    private var startedAtMs: Long = 0L

    /** 看门狗：没有任何服务端帧超过这个时长就主动停掉自己。 */
    private val watchdog = object : Runnable {
        override fun run() {
            checkStaleSession()
            handler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = HubPrefs(this)
        startedAtMs = System.currentTimeMillis()
        createNotificationChannel()
        registerNetworkCallback()
        updateNetworkKind()
        LinkBus.publishServiceRunning(true)
        LinkBus.publishLinkDesired(prefs.linkDesired())
        LinkBus.publishServiceStop(null)
        handler.postDelayed(watchdog, WATCHDOG_INTERVAL_MS)
        LinkBus.log.info("前台服务已创建")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val desired = prefs.linkDesired()
        when (ServiceStartPolicy.decide(intent?.action, desired)) {
            ServiceStartDecision.CONNECT -> {
                stopInProgress = null
                pendingUserDisconnectSource = null
                if (intent?.action == null) {
                    // 系统在进程被杀后重建服务：这是用户看不见的恢复路径，必须有日志与事件。
                    LinkBus.log.info("系统重建前台服务，按用户此前的连接意图恢复")
                    LinkBus.recordEvent(LinkEventKind.SERVICE_RESTARTED, "系统重建服务")
                    handleConnect(trigger = "系统恢复连接")
                } else {
                    handleConnect()
                }
            }

            ServiceStartDecision.DISCONNECT -> {
                // V1.6.2：断开来源必须跟着 Intent 走。只有真实用户动作（界面按钮 /
                // 通知栏按钮）会发出 ACTION_DISCONNECT；没有来源的旧版 PendingIntent
                // 也按 unknown 记录，绝不冒充其它原因。
                val source = intent?.getStringExtra(EXTRA_DISCONNECT_SOURCE) ?: SOURCE_UNKNOWN
                handleDisconnect(source)
            }

            ServiceStartDecision.IGNORE -> {
                // 用户已断开或收到未知 action：不建连，也不让服务空转。
                stopInProgress = "no_actionable_intent"
                LinkBus.log.warn("收到无可执行意图的启动请求（action=${intent?.action ?: "null"}），停止服务")
                stopForegroundService()
            }
        }
        // 只有"用户希望保持连接"时才粘性重建；用户主动断开后进程退出就是退出。
        return if (ServiceStartPolicy.shouldReturnSticky(prefs.linkDesired())) {
            START_STICKY
        } else {
            START_NOT_STICKY
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 从最近任务划掉不应断线：服务是 stopWithTask=false 的前台服务，
        // 这里只留一条证据，方便诊断页解释"为什么划掉后还在跑"。
        LinkBus.log.info("应用任务被移除，前台服务与连接继续")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        handler.removeCallbacks(watchdog)
        unregisterNetworkCallback()
        releaseWakeLock()

        val origin = stopInProgress ?: pendingUserDisconnectSource?.let { "user:$it" }
        pendingUserDisconnectSource = null
        val desired = prefs.linkDesired()
        if (origin == null) {
            // V1.6.2 核心修复：服务被销毁，但本服务没有任何一条主动停止路径被调用。
            // 这不可能是"用户主动断开" —— 记录成独立事件，并保留用户的连接意图。
            val detail = "系统停止前台服务（非用户操作，连接意图=$desired）"
            LinkBus.log.warn(detail)
            LinkBus.recordEvent(LinkEventKind.SERVICE_DESTROYED, detail)
            LinkBus.publishServiceStop(detail)
        } else {
            LinkBus.log.info("前台服务停止（origin=$origin）")
            if (!origin.startsWith("user:")) LinkBus.publishServiceStop(origin)
        }

        // 只禁止重连、只关 socket；用户断开事件在 handleDisconnect 里已经记过，
        // 系统停止则刚刚记成 SERVICE_DESTROYED。这里绝不再产生 USER_DISCONNECTED。
        client?.shutdown("前台服务销毁（origin=${origin ?: "system"}）")
        client = null
        // 在工作线程上的聚合命令会各自跑到自己的超时后退出；这里不等待它们，
        // 只保证不会再有新的任务被提交进来。
        hubWorkers?.shutdown()
        hubWorkers = null
        LinkBus.publishLinkDesired(desired)
        LinkBus.publishServiceRunning(false)
        LinkBus.resetLink()
        LinkBus.log.info("前台服务已销毁")
        super.onDestroy()
    }

    // ---- 用户动作 ----------------------------------------------------------

    private fun handleConnect(trigger: String = "用户点击「连接」") {
        // ★ 审计 R3 的核心修复：用 ConnectStartupSequence 把「进入前台 → 是否建连」
        //   的顺序固定下来。旧实现直接调用 goForeground(...) 并忽略结果，失败之后
        //   代码继续往下走建了 WebSocket —— 那是系统已经拒绝的路径。
        //   顺序约束本身由 ConnectGateTest 在 JVM 上验证。
        prefs.setLinkDesired(true)
        LinkBus.publishLinkDesired(true)
        LinkBus.publishServiceStop(null)
        stopInProgress = null
        LinkBus.log.info(trigger)
        ConnectStartupSequence(
            enterForeground = { goForeground(buildNotification(LinkState.Connecting)) },
            startLink = { startLink() },
            onAbort = { category -> abortStartup(category) },
        ).run()
    }

    /** 真正创建并启动连接。**只可能**由 [ConnectStartupSequence] 在进入前台成功后调用。 */
    private fun startLink() {
        // 进入前台成功后立刻拿住 CPU：心跳/重连定时器都跑在本进程的调度线程上。
        acquireWakeLock()
        val link = client ?: PhoneLinkClient(
            configProvider = { prefs.load() },
            credentialProvider = { prefs.loadTokenResult() },
            androidSdk = Build.VERSION.SDK_INT,
            log = { level, text -> LinkBus.log.add(level, text) },
            onStateChanged = { state ->
                LinkBus.publishState(state)
                updateNotification(state)
                // 终态下不会再自动重连，没必要继续占着唤醒锁；用户手动重连会重新获取。
                when (state) {
                    is LinkState.AuthFailed,
                    is LinkState.Revoked,
                    is LinkState.Error,
                    is LinkState.Idle,
                    -> releaseWakeLock()

                    else -> acquireWakeLock()
                }
            },
            onPing = { outcome -> if (!outcome.duplicate) LinkBus.publishPing(outcome) },
            onEvent = { event -> LinkBus.recordEvent(event) },
            onHeartbeatAck = { atMs -> LinkBus.publishHeartbeatAck(atMs) },
            onHeartbeatSent = { atMs -> LinkBus.publishHeartbeatSent(atMs) },
            // Gate 2 工作包 D/E、Gate 2.5：能力分发层。
            //   · 白名单在这里、也**只**在这里建立：能被执行的能力由
            //     `capability/BuiltInCapabilities.kt` 那份唯一注册清单派生
            //     （元数据与可执行实例是同一份数据的两个视图）。
            //   · 授权策略每次调用都重读用户设置，所以用户在界面上开关之后
            //     **不需要重连**就生效。
            capabilityDispatcher = CapabilityDispatcher(
                capabilities = BuiltInCapabilities.androidCapabilities(this),
                // 三道闸门，每次调用都重读设置（用户改完不需要重连）：
                //   1. 总开关（只读/写共用）；
                //   2. 写入类能力还要写入总开关（默认关）；
                //   3. 每一条工具自己的开关（默认开，写入类默认关 —— 见 metadata.readOnly）。
                // 运行期系统权限（日历、使用情况访问）不在这里判：那由能力自己在
                // execute 里检查并回 permission_denied，界面负责告诉用户去哪开。
                policy = { toolName ->
                    val meta = capabilityMetadataByName[toolName]
                    prefs.capabilitiesEnabled() &&
                        prefs.isCapabilityToolEnabled(toolName) &&
                        when {
                            meta == null -> true
                            meta.readOnly -> true
                            else -> prefs.writeCapabilitiesEnabled() && prefs.isWriteCapabilityEnabled(toolName)
                        }
                },
                // Gate 3：聚合入口。保留命令名（hub_list_tools / hub_get_tool_schema / hub_call_tool）
                // 由这里接走，**不进**上面那份本机能力白名单 —— 第三方工具不是本机能力，
                // 它的参数校验、授权判定、执行都在 McpHubRouter 里自己完成。
                // 执行总开关复用同一个用户设置：用户关掉「允许服务端调用本机能力」，
                // 第三方工具也一起停。
                hubCommands = McpHubRouter(
                    serverStore = McpStores.serverStore(this),
                    authorizationStore = McpStores.authorizationStore(this),
                    enabled = { prefs.capabilitiesEnabled() },
                    // 分阶段耗时写进界面日志：目录请求慢的时候，第一条要看的就是
                    // "这 13 秒花在哪一段"。日志里只有 request_id/阶段名/耗时，
                    // 没有令牌、地址、参数值或工具描述。
                    log = { line -> LinkBus.log.add(LogLevel.INFO, line) },
                ),
                // 5.5：聚合命令离开读线程。
                hubExecutor = hubWorkerPool(),
            ),
        ).also { created ->
            client = created
            startedAtMs = System.currentTimeMillis()
        }

        link.start()
    }

    /** [hubWorkers] 的懒创建；服务销毁时统一关闭。 */
    private fun hubWorkerPool(): ExecutorService = hubWorkers ?: Executors.newFixedThreadPool(HUB_WORKERS) {
        Thread(it, "hub-work").apply { isDaemon = true }
    }.also { hubWorkers = it }

    /**
     * 前台服务启动失败后的收尾（审计 R3）。
     *
     * 关键点：
     *  - 不创建、不启动 [PhoneLinkClient]（调用方已经 `return`，这里是兜底清理）；
     *  - 把已有的连接对象丢掉，不留下一个"没进前台却在后台重连"的状态；
     *  - 撤掉前台状态与通知，然后 `stopSelf()`，让服务不要挂在那里；
     *  - **不安排任何重连**：失败发生在任何一次连接尝试之前，自动重试只会在
     *    完全相同的条件下再失败一遍，变成用户看不见的循环。唯一的恢复路径是
     *    用户回到前台再点一次「连接」。
     */
    private fun abortStartup(category: String) {
        LinkBus.log.error("前台服务启动失败，已取消连接（errorCode=$category）")
        // V1.6.2：**不**清除连接意图。系统的拒绝不是用户改主意 —— 只有真实用户动作
        // 可以清除 `linkDesired`。保留意图后，用户下次打开应用会再次尝试，并能看到
        // 这条错误；清掉意图反而会让"用户明明点过连接"变成"应用忘了"。
        stopInProgress = "startup_failed:$category"
        LinkBus.publishServiceStop("前台服务启动失败（$category）")
        LinkBus.publishLinkDesired(prefs.linkDesired())
        releaseWakeLock()
        client?.shutdown("前台服务启动失败（$category）")
        client = null
        LinkBus.publishState(LinkState.Error(getString(ForegroundFailure.userMessageRes(category))))
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * 真实用户断开。`source` 只允许来自界面按钮或通知栏按钮。
     *
     * V1.6.2：这是**唯一**允许清除 `linkDesired` 的路径。来源同时进日志、事件与
     * 诊断页，回答"到底是谁断开的"。
     */
    private fun handleDisconnect(source: String) {
        val label = disconnectSourceLabel(source)
        LinkBus.log.info("用户主动断开（source=$source，$label），停止自动重连")
        // 先落盘"用户不要连接了"：即使下一行关闭立刻失败，系统重建服务也不会偷偷再连。
        prefs.setLinkDesired(false)
        LinkBus.publishLinkDesired(false)
        stopInProgress = "user:$source"
        LinkBus.publishServiceStop("用户主动断开（$label）")
        client?.stop(source = source)
        client = null
        releaseWakeLock()
        stopForegroundService()
    }

    private fun disconnectSourceLabel(source: String): String = when (source) {
        SOURCE_UI_BUTTON -> "界面按钮"
        SOURCE_NOTIFICATION_ACTION -> "通知栏按钮"
        else -> "来源未知"
    }

    private fun stopForegroundService() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---- 唤醒锁 ------------------------------------------------------------

    /**
     * 取锁时不带超时，是**有意的**：这把锁的生命周期与"连接"绑定，而不是与某一次
     * 具体工作绑定。连接可能持续几小时，任何固定超时都会在用户仍然希望保持在线时
     * 悄悄失效；而锁的释放点已经被三种明确事件覆盖（用户断开、终态、服务销毁），
     * 不存在"忘了释放"的路径。lint 的 WakelockTimeout 针对的是后台干活式的用法，
     * 这里是用户可见的前台服务，故显式抑制并写明理由。
     */
    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        val lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
        }
        runCatching { lock.acquire() }
            .onSuccess {
                wakeLock = lock
                LinkBus.log.info("已获取部分唤醒锁（保持心跳调度）")
            }
            .onFailure {
                LinkBus.log.warn("获取唤醒锁失败 category=${SocketFailure.classify(it.javaClass.simpleName)}")
            }
    }

    private fun releaseWakeLock() {
        val lock = wakeLock ?: return
        wakeLock = null
        runCatching { if (lock.isHeld) lock.release() }
        LinkBus.log.info("已释放部分唤醒锁")
    }

    // ---- 网络回调 ----------------------------------------------------------

    private fun registerNetworkCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        connectivity = cm
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val now = System.currentTimeMillis()
                if (!NetworkCallbackPolicy.shouldProcessAvailable(
                        network = network,
                        lastAvailable = lastAvailableNetwork,
                        lastAvailableAtMs = lastAvailableAtMs,
                        nowMs = now,
                    )
                ) {
                    LinkBus.log.info("忽略同一默认网络的重复可用回调")
                    return
                }
                lastAvailableNetwork = network
                lastAvailableAtMs = now
                lastDefaultNetwork = network
                updateNetworkKind()
                LinkBus.log.info("默认网络可用")
                LinkBus.recordEvent(LinkEventKind.NETWORK_CHANGED, "网络可用")
                client?.onNetworkAvailable()
            }

            override fun onLost(network: Network) {
                // V1.6.2：旧网络迟到的 onLost 不得关掉已经建立在新网络上的连接。
                val active = connectivity?.activeNetwork
                if (!NetworkCallbackPolicy.shouldProcessLost(
                        lost = network,
                        lastDefault = lastDefaultNetwork,
                        active = active,
                    )
                ) {
                    LinkBus.log.info("忽略非当前默认网络的断开回调")
                    return
                }
                lastDefaultNetwork = null
                lastAvailableNetwork = null
                LinkBus.publishNetworkKind(NetworkKind.NONE)
                LinkBus.log.warn("默认网络已断开")
                LinkBus.recordEvent(LinkEventKind.NETWORK_CHANGED, "网络断开")
                client?.onNetworkLost()
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                lastDefaultNetwork = network
                LinkBus.publishNetworkKind(kindOf(capabilities))
            }
        }
        networkCallback = callback
        runCatching { cm.registerDefaultNetworkCallback(callback) }
            // Gate 0.3 审计发现项 1：不记 `it.message`（未经处理的异常字符串），
            // 只记 SocketFailure 闭集里的类别。
            .onFailure {
                LinkBus.log.warn("注册网络回调失败 category=${SocketFailure.classify(it.javaClass.simpleName)}")
            }
    }

    /** 把当前默认网络收敛成诊断页的粗分类；没有网络时显示"无网络"。 */
    private fun updateNetworkKind() {
        val cm = connectivity ?: return
        val capabilities = cm.getNetworkCapabilities(cm.activeNetwork)
        LinkBus.publishNetworkKind(if (capabilities == null) NetworkKind.NONE else kindOf(capabilities))
    }

    private fun kindOf(capabilities: NetworkCapabilities): NetworkKind = networkKind(
        wifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
        cellular = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
        ethernet = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
        vpn = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
        other = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_USB) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_LOWPAN),
    )

    private fun unregisterNetworkCallback() {
        val cm = connectivity ?: return
        val callback = networkCallback ?: return
        runCatching { cm.unregisterNetworkCallback(callback) }
        networkCallback = null
    }

    // ---- 会话看门狗 --------------------------------------------------------

    /**
     * 服务层兜底看门狗：连接期间连续 [STALE_SESSION_TIMEOUT_MS] 没收到服务端**任何**帧，
     * 就把当前 socket 判死并走一次正常重连。
     *
     * V1.6.1 之前这里会 `stop()` + `stopForegroundService()`，把服务永久停掉：
     * 后台断网超过 5 分钟时，用户回来看到的是"通知没了、连接也没了"，
     * 这与"意外断线后自动恢复"正好相反。现在**不停止服务**，只强制重连；
     * 真正的终态（认证失败/被撤销）仍由 [PhoneLink] 自己判定。
     *
     * 幂等性：`PhoneLink.onWatchdogTimeout` 在没有 socket 也没有在飞的建连尝试时
     * 返回空动作列表，所以重连退避期间这个看门狗不会把退避节奏打乱。
     */
    private fun checkStaleSession() {
        val link = client ?: return
        val last = maxOf(link.lastServerFrameAtMs, startedAtMs)
        val silent = System.currentTimeMillis() - last
        if (silent < STALE_SESSION_TIMEOUT_MS) return

        LinkBus.log.warn("连续 ${silent / 1000} 秒没有服务端帧，强制重连（服务保持运行）")
        link.onWatchdogTimeout(silent)
    }

    // ---- 通知 --------------------------------------------------------------

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notif_channel_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    /**
     * 进入前台（审计 R3：返回**明确结果**，而不是把异常吞掉）。
     *
     * 失败时只带回一个固定类别（[ForegroundFailure]），
     * 异常的 `message` 与堆栈都不进日志 —— 它们可能夹带与用户数据相关的原文。
     */
    private fun goForeground(notification: Notification): ForegroundResult = try {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        ForegroundResult.Started
    } catch (error: RuntimeException) {
        // ForegroundServiceStartNotAllowedException（API 31+）与
        // MissingForegroundServiceTypeException（API 34+）都是 RuntimeException 的子类，
        // 这里不需要为它们写 `is` 判断 —— 那样在低版本设备上会去加载不存在的类。
        ForegroundResult.Failed(ForegroundFailure.classify(error))
    }

    /**
     * 更新前台服务通知。
     *
     * Gate 0.3 审计发现项 2：这里原来只有 `runCatching` —— 它能吞掉 Android 13+ 因缺少
     * POST_NOTIFICATIONS 抛出的 `SecurityException`，但 lint 的 `MissingPermission` **不认**
     * "被 catch 住了"这件事，所以它一直报 Error；而当时 `abortOnError = false` 又把这条
     * Error 静音了。现在补一个 lint 认得的守卫，于是 `abortOnError` 可以改回 true。
     *
     * **权限检查必须内联在这里**：一开始我把它抽成了独立的 `canPostNotifications()`，
     * lint 的流分析不跟踪这种间接守卫，`:app:lintDebug` 照样报同一条 Error
     * （实测报在 PhoneLinkService.kt:340）。所以这里写成内联判断 + 显式 catch 双保险。
     *
     * 行为不变：用户拒绝通知权限时，这次 notify 不显示，但服务与连接照常工作
     * （前台服务本身不依赖通知权限）。
     */
    private fun updateNotification(state: LinkState) {
        // Android 13（API 33）以下没有 POST_NOTIFICATIONS，一律按"可以发"处理。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(state))
        } catch (e: SecurityException) {
            // 权限在两次判断之间被撤销的竞态窗口。不记异常原文。
            LinkBus.log.warn("更新通知失败 category=permission_denied")
        }
    }

    private fun buildNotification(state: LinkState): Notification {
        val host = hostOf(safeServerUrl())

        val text = when (state) {
            is LinkState.Idle -> getString(R.string.notif_idle)
            is LinkState.Connecting -> getString(R.string.notif_connecting)
            is LinkState.Connected -> getString(R.string.notif_connected, host)
            is LinkState.Reconnecting -> getString(R.string.notif_reconnecting, state.attempt)
            is LinkState.AuthFailed -> getString(R.string.notif_auth_failed)
            is LinkState.Revoked -> getString(R.string.notif_revoked)
            is LinkState.Error -> getString(R.string.notif_error, state.message)
        }

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val disconnectIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, PhoneLinkService::class.java)
                .setAction(ACTION_DISCONNECT)
                .putExtra(EXTRA_DISCONNECT_SOURCE, SOURCE_NOTIFICATION_ACTION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_link)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(contentIntent)
            .addAction(0, getString(R.string.action_disconnect), disconnectIntent)
            .setOngoing(true)
            .setShowWhen(false)
            .setSilent(true)
            // 连接状态不是「紧急信息」，用 LOW 渠道，不打扰用户
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun safeServerUrl(): String = runCatching { prefs.load().serverUrl }.getOrDefault("")

    companion object {
        private const val CHANNEL_ID = "phone_link"
        private const val NOTIFICATION_ID = 1001

        /** 没有服务端帧多久就判定会话僵死（毫秒）。 */
        private const val STALE_SESSION_TIMEOUT_MS = 5 * 60 * 1000L

        private const val WATCHDOG_INTERVAL_MS = 60 * 1000L

        /**
         * 部分唤醒锁的标签（`<包名>::<用途>`）。
         *
         * 只用于系统 `dumpsys power` 里辨认持有者；不承载任何用户数据。
         */
        private const val WAKE_LOCK_TAG = "AndroidMcpHub::PhoneLink"

        /**
         * 聚合命令的并发线程数。
         *
         * 2 个足够：协议上服务端一次只会等一条 `tool_request` 的结果，多开的线程
         * 只会在"用户手快点了两次"时各跑各的，而不是把一次目录刷新拆得更快。
         */
        private const val HUB_WORKERS = 2

        const val ACTION_CONNECT = "com.androidmcphub.app.action.CONNECT"
        const val ACTION_DISCONNECT = "com.androidmcphub.app.action.DISCONNECT"

        /**
         * 断开来源（V1.6.2）。
         *
         * 只有真实用户动作会带这个 extra：界面上的「断开」按钮，或通知栏的断开按钮。
         * 服务在 `handleDisconnect` 里把它写进日志/事件/诊断页，回答"是谁断开的"。
         */
        const val EXTRA_DISCONNECT_SOURCE = "com.androidmcphub.app.extra.DISCONNECT_SOURCE"
        const val SOURCE_UI_BUTTON = "ui_button"
        const val SOURCE_NOTIFICATION_ACTION = "notification_action"

        /** 升级前残留的 PendingIntent 不会带 extra；如实记为未知，不冒充任何一种用户动作。 */
        const val SOURCE_UNKNOWN = "unknown"

        /**
         * 用户点「断开」的旁路记录。
         *
         * 正常路径是 `startService(ACTION_DISCONNECT)`，来源随 Intent 进服务；但投递失败时
         * 会直接 `stopService`，服务实例拿不到 Intent。这里留下来源，`onDestroy` 才能
         * 如实写成"用户断开"而不是"系统停止"。只在 CONNECT 时清除。
         */
        @Volatile
        private var pendingUserDisconnectSource: String? = null

        /**
         * 由界面调用：进入前台服务并开始连接。
         *
         * **先落盘用户意图再启动服务**：进程可能在 `onStartCommand` 之前就被系统回收，
         * 那时服务内的 `setLinkDesired(true)` 根本来不及执行，而 `START_STICKY` 重建
         * 又依赖这个值。启动抛异常时回滚，避免留下"想连但永远没连"的脏意图。
         */
        fun connect(context: Context) {
            val app = context.applicationContext
            pendingUserDisconnectSource = null
            HubPrefs(app).setLinkDesired(true)
            LinkBus.publishLinkDesired(true)
            val intent = Intent(context, PhoneLinkService::class.java).setAction(ACTION_CONNECT)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure {
                // V1.6.2：启动失败不清除连接意图（只有真实用户动作可以清除）。
                // 意图留在磁盘上，用户下次打开应用会重试并看到失败原因。
                LinkBus.log.warn(
                    "启动前台服务失败 category=${SocketFailure.classify(it.javaClass.simpleName)}" +
                        "；保留此前的连接意图",
                )
            }
        }

        /**
         * 由界面调用：用户主动断开。
         *
         * 同样先落盘 "false"：即使断开指令投递失败，系统重建服务也不会偷偷再连。
         * 投递失败时直接 `stopService` 兜底，别让前台通知与连接一起挂着。
         */
        fun disconnect(context: Context, source: String = SOURCE_UI_BUTTON) {
            val app = context.applicationContext
            HubPrefs(app).setLinkDesired(false)
            LinkBus.publishLinkDesired(false)
            pendingUserDisconnectSource = source
            val intent = Intent(context, PhoneLinkService::class.java)
                .setAction(ACTION_DISCONNECT)
                .putExtra(EXTRA_DISCONNECT_SOURCE, source)
            runCatching { context.startService(intent) }
                .onFailure {
                    runCatching { context.stopService(Intent(context, PhoneLinkService::class.java)) }
                    LinkBus.log.warn(
                        "投递断开指令失败 category=${SocketFailure.classify(it.javaClass.simpleName)}",
                    )
                }
        }
    }
}
