package com.androidmcphub.app.core

import com.androidmcphub.app.protocol.APP_VERSION

/**
 * 手机 → 云端的一条连接诊断事件（V1.6.3）。
 *
 * 设计原则与 `worker/src/phone/diagnostics.ts` 一一对应：
 *  - `event` / `category` / `network` / `screen` / `local_close_source` 全部来自
 *    **闭集**，服务端还会再校验一遍；手机侧不发送任何自由文本；
 *  - 没有任何字段可以承载令牌、Authorization、Cookie、服务器地址、工具参数、
 *    通知正文或聊天内容 —— 这个数据类**没有**那样的字段；
 *  - 断网时事件排队、下次连上后补发，因此 [clientAtMs] 可能早于服务端收到它的
 *    时刻。合并时间线必须同时看两个时间戳，不能假设手机时钟与服务端一致。
 */
data class DiagReport(
    /** 闭集事件名，见 [DiagClientEvent]。 */
    val event: String,
    /** 手机侧事件时刻（epoch ms，手机时钟）。 */
    val clientAtMs: Long,
    val category: String? = null,
    val closeCode: Int? = null,
    val generation: Long? = null,
    val network: String? = null,
    val vpn: Boolean? = null,
    val screen: String? = null,
    val pid: Int? = null,
    val processUptimeMs: Long? = null,
    val appVersion: String = APP_VERSION,
    val androidSdk: Int? = null,
    val localCloseSource: String? = null,
    val heartbeatAgeMs: Long? = null,
    /** 触发失败的异常类名（不是 message），见 [normalizeExceptionClass]。 */
    val exceptionClass: String? = null,
    val attempt: Int? = null,
)

/** `diag_event` 的 `event` 闭集（与服务端 `CLIENT_EVENTS` 逐字一致）。 */
object DiagClientEvent {
    const val CONNECTED = "connected"
    const val SERVER_CLOSE = "server_close"
    const val SOCKET_FAILURE = "socket_failure"
    const val HANDSHAKE_TIMEOUT = "handshake_timeout"
    const val RECONNECTING = "reconnecting"
    const val NETWORK_CHANGED = "network_changed"
    const val HEARTBEAT_TIMEOUT = "heartbeat_timeout"
    const val AUTHENTICATION_FAILED = "authentication_failed"
    const val USER_DISCONNECTED = "user_disconnected"
    const val SERVICE_DESTROYED = "service_destroyed"
    const val SERVICE_RESTARTED = "service_restarted"

    val ALL: Set<String> = setOf(
        CONNECTED,
        SERVER_CLOSE,
        SOCKET_FAILURE,
        HANDSHAKE_TIMEOUT,
        RECONNECTING,
        NETWORK_CHANGED,
        HEARTBEAT_TIMEOUT,
        AUTHENTICATION_FAILED,
        USER_DISCONNECTED,
        SERVICE_DESTROYED,
        SERVICE_RESTARTED,
    )
}

/**
 * 本地主动关闭连接的**真实来源**闭集（与服务端 `LOCAL_CLOSE_SOURCES` 一致）。
 *
 * 只有真的由本机发起了关闭动作才填；服务端关闭、握手失败这类"我们没关"的情况
 * 一律留空，避免把服务端行为记成本机行为。
 */
object DiagCloseSource {
    const val UI_BUTTON = "ui_button"
    const val NOTIFICATION_ACTION = "notification_action"
    const val UNKNOWN = "unknown"
    const val SERVICE_DESTROYED = "service_destroyed"
    const val NETWORK_LOST = "network_lost"
    const val WATCHDOG = "watchdog"
    const val TRANSPORT_CLOSE = "transport_close"
    const val APP_RESTORE = "app_restore"
    const val REVOKED = "revoked"
    const val SERVER_CLOSE = "server_close"

    val ALL: Set<String> = setOf(
        UI_BUTTON,
        NOTIFICATION_ACTION,
        UNKNOWN,
        SERVICE_DESTROYED,
        NETWORK_LOST,
        WATCHDOG,
        TRANSPORT_CLOSE,
        APP_RESTORE,
        REVOKED,
        SERVER_CLOSE,
    )

    /** 收敛到闭集；来自 `PhoneLink.stop(source=…)` 的字符串不保证合法。 */
    fun normalize(source: String): String = if (source in ALL) source else UNKNOWN
}

/** 网络粗分类的线上取值（与服务端 `NETWORKS` 一致）。 */
object DiagNetwork {
    const val NONE = "none"
    const val WIFI = "wifi"
    const val CELLULAR = "cellular"
    const val ETHERNET = "ethernet"
    const val VPN = "vpn"
    const val OTHER = "other"

    val ALL: Set<String> = setOf(NONE, WIFI, CELLULAR, ETHERNET, VPN, OTHER)
}

fun NetworkKind.diagNetwork(): String = when (this) {
    NetworkKind.NONE -> DiagNetwork.NONE
    NetworkKind.WIFI -> DiagNetwork.WIFI
    NetworkKind.CELLULAR -> DiagNetwork.CELLULAR
    NetworkKind.ETHERNET -> DiagNetwork.ETHERNET
    NetworkKind.VPN -> DiagNetwork.VPN
    NetworkKind.OTHER -> DiagNetwork.OTHER
}

/**
 * 异常类名收敛：只接受"类名形状"的字符串。
 *
 * 为什么不是闭集：真实抛出的异常来自 OkHttp、Okio、TLS 栈乃至厂商定制，
 * 枚举不完；但**类名**是稳定的标识，可以白名单化成形状约束
 * （字母/数字/`_`/`$`/`.`，最长 64，不能以数字开头）。
 *
 * 这与"原始异常消息的安全摘要"是两件事：message 可能含服务器地址、令牌片段，
 * 一律不采集；类名能让诊断页判断"是 DNS、TLS，还是对端 reset"。
 */
fun normalizeExceptionClass(raw: String?): String? =
    raw?.takeIf { it.matches(Regex("^[A-Za-z_$][A-Za-z0-9_$.]{0,63}$")) }

/** 屏幕状态闭集（与服务端 `SCREENS` 一致）。 */
object DiagScreen {
    const val ON = "on"
    const val OFF = "off"
    const val UNKNOWN = "unknown"

    val ALL: Set<String> = setOf(ON, OFF, UNKNOWN)
}

/**
 * 事件发生时**本机**的静态上下文。
 *
 * 这些字段只在开关机/切网/亮熄屏时才变，但它们对判断"是不是息屏/VPN 导致的
 * 断连"很关键，所以每条事件都带上一份当时的值，而不是只看最新值。
 */
data class DeviceSnapshot(
    val network: String? = null,
    val vpn: Boolean? = null,
    val screen: String? = DiagScreen.UNKNOWN,
    val pid: Int? = null,
    val processUptimeMs: Long? = null,
    val appVersion: String = APP_VERSION,
    val androidSdk: Int? = null,
)

/** 事件类别 → 线上事件名。一对一的闭集映射，没有字符串嗅探。 */
fun diagEventName(kind: LinkEventKind): String = when (kind) {
    LinkEventKind.CONNECTED -> DiagClientEvent.CONNECTED
    LinkEventKind.SERVER_CLOSED -> DiagClientEvent.SERVER_CLOSE
    LinkEventKind.SOCKET_FAILURE -> DiagClientEvent.SOCKET_FAILURE
    LinkEventKind.HANDSHAKE_TIMEOUT -> DiagClientEvent.HANDSHAKE_TIMEOUT
    LinkEventKind.RECONNECTING -> DiagClientEvent.RECONNECTING
    LinkEventKind.NETWORK_CHANGED -> DiagClientEvent.NETWORK_CHANGED
    LinkEventKind.HEARTBEAT_TIMEOUT -> DiagClientEvent.HEARTBEAT_TIMEOUT
    LinkEventKind.AUTHENTICATION_FAILED -> DiagClientEvent.AUTHENTICATION_FAILED
    LinkEventKind.USER_DISCONNECTED -> DiagClientEvent.USER_DISCONNECTED
    LinkEventKind.SERVICE_DESTROYED -> DiagClientEvent.SERVICE_DESTROYED
    LinkEventKind.SERVICE_RESTARTED -> DiagClientEvent.SERVICE_RESTARTED
}

/**
 * 把一条本地事件转换成可上报的 [DiagReport]。
 *
 * 这是**唯一**的构造入口：任何不在闭集里的值在这里被丢弃（而不是交给服务端去拒），
 * 所以即使上层将来不小心传进自由文本，也进不了网络帧。
 */
fun buildDiagReport(event: LinkEvent, snapshot: DeviceSnapshot): DiagReport {
    val category = event.category?.let { SocketFailure.normalize(it) }
        // 只有"连接失败"事件才带类别；别的类别字段即使有值也不会被承认。
        ?.takeIf { event.kind == LinkEventKind.SOCKET_FAILURE }

    return DiagReport(
        event = diagEventName(event.kind),
        clientAtMs = event.atMs,
        category = category,
        closeCode = event.closeCode?.takeIf { it in 0..4999 },
        generation = event.generation?.takeIf { it >= 0L },
        // 即使调用方传进来的快照带了自由文本，也在这里收敛回闭集。
        network = snapshot.network?.takeIf { it in DiagNetwork.ALL },
        vpn = snapshot.vpn,
        screen = snapshot.screen?.takeIf { it in DiagScreen.ALL } ?: DiagScreen.UNKNOWN,
        pid = snapshot.pid,
        processUptimeMs = snapshot.processUptimeMs,
        appVersion = snapshot.appVersion,
        androidSdk = snapshot.androidSdk,
        localCloseSource = event.source?.let { DiagCloseSource.normalize(it) },
        heartbeatAgeMs = event.heartbeatAgeMs?.takeIf { it >= 0L },
        // 只有网络层失败（含由 onFailure 升级成的认证失败）才带异常类；
        // 别的路径即使误传，也不会被上报。
        exceptionClass = event.exceptionClass
            ?.takeIf {
                event.kind == LinkEventKind.SOCKET_FAILURE ||
                    event.kind == LinkEventKind.AUTHENTICATION_FAILED
            }
            ?.let(::normalizeExceptionClass),
        attempt = event.attempt?.takeIf { it in 0..10_000 },
    )
}
