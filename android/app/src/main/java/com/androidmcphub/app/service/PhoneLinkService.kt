package com.androidmcphub.app.service

import android.Manifest
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
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.androidmcphub.app.MainActivity
import com.androidmcphub.app.R
import com.androidmcphub.app.capability.AndroidBatterySource
import com.androidmcphub.app.capability.BatteryCapability
import com.androidmcphub.app.capability.CapabilityDispatcher
import com.androidmcphub.app.core.HubLog
import com.androidmcphub.app.core.HubPrefs
import com.androidmcphub.app.core.LogEntry
import com.androidmcphub.app.core.LogLevel
import com.androidmcphub.app.core.SocketFailure
import com.androidmcphub.app.core.hostOf
import com.androidmcphub.app.net.PhoneLinkClient
import com.androidmcphub.app.protocol.LinkState
import com.androidmcphub.app.protocol.PingOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

    /** 用户主动清空「最近一次错误」（界面上的「清空日志」顺带做这件事）。 */
    fun clearLastError() {
        _lastError.value = null
    }

    /** 服务停止后把「连接相关」的展示状态复位（日志与最近错误保留，方便用户回看刚才发生了什么）。 */
    fun resetLink() {
        _state.value = LinkState.Idle
        _lastPing.value = null
    }
}

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
        LinkBus.publishServiceRunning(true)
        handler.postDelayed(watchdog, WATCHDOG_INTERVAL_MS)
        LinkBus.log.info("前台服务已创建")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> handleConnect()

            ACTION_DISCONNECT -> handleDisconnect()

            else -> {
                // 没有 action 说明是系统重投或异常调用，不建连
                LinkBus.log.warn("收到没有 action 的启动请求，忽略")
            }
        }
        // 不粘性：进程被杀后系统不要偷偷把服务拉起来（本应用只允许用户前台触发连接）
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(watchdog)
        unregisterNetworkCallback()
        client?.shutdown()
        client = null
        LinkBus.publishServiceRunning(false)
        LinkBus.resetLink()
        LinkBus.log.info("前台服务已销毁")
        super.onDestroy()
    }

    // ---- 用户动作 ----------------------------------------------------------

    private fun handleConnect() {
        // ★ 审计 R3 的核心修复：用 ConnectStartupSequence 把「进入前台 → 是否建连」
        //   的顺序固定下来。旧实现直接调用 goForeground(...) 并忽略结果，失败之后
        //   代码继续往下走建了 WebSocket —— 那是系统已经拒绝的路径。
        //   顺序约束本身由 ConnectGateTest 在 JVM 上验证。
        LinkBus.log.info("用户点击「连接」")
        ConnectStartupSequence(
            enterForeground = { goForeground(buildNotification(LinkState.Connecting)) },
            startLink = { startLink() },
            onAbort = { category -> abortStartup(category) },
        ).run()
    }

    /** 真正创建并启动连接。**只可能**由 [ConnectStartupSequence] 在进入前台成功后调用。 */
    private fun startLink() {
        val link = client ?: PhoneLinkClient(
            configProvider = { prefs.load() },
            credentialProvider = { prefs.loadTokenResult() },
            androidSdk = Build.VERSION.SDK_INT,
            log = { level, text -> LinkBus.log.add(level, text) },
            onStateChanged = { state ->
                LinkBus.publishState(state)
                updateNotification(state)
            },
            onPing = { outcome -> if (!outcome.duplicate) LinkBus.publishPing(outcome) },
            // Gate 2 工作包 D/E：能力分发层。
            //   · 白名单在这里、也**只**在这里建立：能被执行的能力就是下面这个列表。
            //   · 授权策略每次调用都重读用户设置，所以用户在界面上开关之后
            //     **不需要重连**就生效。
            capabilityDispatcher = CapabilityDispatcher(
                capabilities = listOf(BatteryCapability(AndroidBatterySource(this))),
                policy = { prefs.capabilitiesEnabled() },
            ),
        ).also { created ->
            client = created
            startedAtMs = System.currentTimeMillis()
        }

        link.start()
    }

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
        client?.shutdown()
        client = null
        LinkBus.publishState(LinkState.Error(getString(ForegroundFailure.userMessageRes(category))))
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun handleDisconnect() {
        LinkBus.log.info("用户点击「断开」，停止自动重连")
        client?.stop()
        client = null
        stopForegroundService()
    }

    private fun stopForegroundService() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---- 网络回调 ----------------------------------------------------------

    private fun registerNetworkCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        connectivity = cm
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                LinkBus.log.info("默认网络可用")
                client?.onNetworkAvailable()
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

    private fun unregisterNetworkCallback() {
        val cm = connectivity ?: return
        val callback = networkCallback ?: return
        runCatching { cm.unregisterNetworkCallback(callback) }
        networkCallback = null
    }

    // ---- 会话看门狗 --------------------------------------------------------

    /**
     * 如果连接期间连续 [STALE_SESSION_TIMEOUT_MS] 没收到服务端**任何**帧，
     * 说明这条会话实质上已经僵死（心跳超时逻辑还会自己重连，但用户可能早就走了），
     * 就写一条日志并停掉自己，避免无意义地占着前台服务。
     */
    private fun checkStaleSession() {
        val link = client ?: return
        val last = maxOf(link.lastServerFrameAtMs, startedAtMs)
        val silent = System.currentTimeMillis() - last
        if (silent < STALE_SESSION_TIMEOUT_MS) return

        LinkBus.log.warn("连续 ${silent / 1000} 秒没有服务端帧，判定会话已僵死，主动停止前台服务")
        LinkBus.publishState(LinkState.Error(getString(R.string.link_error_stale_session)))
        client?.stop()
        client = null
        stopForegroundService()
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
            Intent(this, PhoneLinkService::class.java).setAction(ACTION_DISCONNECT),
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

        const val ACTION_CONNECT = "com.androidmcphub.app.action.CONNECT"
        const val ACTION_DISCONNECT = "com.androidmcphub.app.action.DISCONNECT"

        /** 由界面调用：进入前台服务并开始连接。 */
        fun connect(context: Context) {
            val intent = Intent(context, PhoneLinkService::class.java).setAction(ACTION_CONNECT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** 由界面调用：用户主动断开。 */
        fun disconnect(context: Context) {
            val intent = Intent(context, PhoneLinkService::class.java).setAction(ACTION_DISCONNECT)
            runCatching { context.startService(intent) }
        }
    }
}
