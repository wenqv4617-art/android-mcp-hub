package com.androidmcphub.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
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
import com.androidmcphub.app.MainActivity
import com.androidmcphub.app.R
import com.androidmcphub.app.core.HubLog
import com.androidmcphub.app.core.HubPrefs
import com.androidmcphub.app.core.LogEntry
import com.androidmcphub.app.core.LogLevel
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

    private val _serviceRunning = MutableStateFlow(false)
    val serviceRunning: StateFlow<Boolean> = _serviceRunning.asStateFlow()

    init {
        log.setListener { entries -> _logs.value = entries }
    }

    fun publishState(state: LinkState) {
        _state.value = state
    }

    fun publishPing(outcome: PingOutcome?) {
        _lastPing.value = outcome
    }

    fun publishServiceRunning(running: Boolean) {
        _serviceRunning.value = running
    }

    /** 服务停止后把「连接相关」的展示状态复位（日志保留，方便用户回看刚才发生了什么）。 */
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
        goForeground(buildNotification(LinkState.Connecting))

        val link = client ?: PhoneLinkClient(
            configProvider = { prefs.load() },
            tokenProvider = { prefs.loadToken() },
            androidSdk = Build.VERSION.SDK_INT,
            log = { level, text -> LinkBus.log.add(level, text) },
            onStateChanged = { state ->
                LinkBus.publishState(state)
                updateNotification(state)
            },
            onPing = { outcome -> if (!outcome.duplicate) LinkBus.publishPing(outcome) },
        ).also { created ->
            client = created
            startedAtMs = System.currentTimeMillis()
        }

        LinkBus.log.info("用户点击「连接」")
        link.start()
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
            .onFailure { LinkBus.log.warn("注册网络回调失败：${it.message}") }
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
        LinkBus.publishState(LinkState.Error("会话超时，已停止连接"))
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

    private fun goForeground(notification: Notification) {
        runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        }.onFailure {
            // 例如系统限制后台启动前台服务（本应用只在用户前台点击时进入这里）
            LinkBus.log.error("进入前台服务失败：${it.javaClass.simpleName}: ${it.message}")
        }
    }

    private fun updateNotification(state: LinkState) {
        // 用户拒绝 POST_NOTIFICATIONS 时这次 notify 不会显示，但服务与连接照常工作
        runCatching {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(state))
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
