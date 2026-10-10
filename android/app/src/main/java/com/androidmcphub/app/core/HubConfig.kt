package com.androidmcphub.app.core

/**
 * 连接生命周期配置。
 *
 * **设备令牌不在本类里**：令牌单独存放于 SharedPreferences（见 [HubPrefs]），
 * 因此本类的 [toString] 永远不会带出凭据，也不会被写进日志。
 *
 * 本类不依赖任何 Android / Compose 类型，可在 JVM 单元测试里直接构造。
 */
data class HubConfig(
    /** 例如 wss://example.workers.dev/phone/ws */
    val serverUrl: String = "",
    val deviceId: String = "test-phone-1",
    val pingTimeoutMs: Long = 10_000L,
    val heartbeatIntervalMs: Long = 30_000L,
    val reconnectInitialDelayMs: Long = 1_000L,
    val reconnectMaxDelayMs: Long = 60_000L,
    val reconnectJitterRatio: Double = 0.2,
    val maxPendingRequests: Int = 16,
    val maxFrameBytes: Int = 65_536,
) {

    /**
     * 校验是否具备发起连接的最低条件。
     * @return null 表示通过；否则返回中文错误原因（直接展示给用户）。
     */
    fun validate(): String? {
        val url = serverUrl.trim()
        if (url.isEmpty()) return "服务器 URL 不能为空"
        val normalized = normalizeWebSocketUrl(url)
            ?: return "服务器 URL 必须以 wss://、ws://、https:// 或 http:// 开头"

        // 只填主机名 —— 也就是 `wrangler deploy` 直接打印出来的那个地址 —— 是最常见的错法。
        // 它本来会通过校验，然后客户端去连根路径 `/`：Worker 在 `/` 上返回的是文本、
        // 不是 WebSocket 升级，握手必然失败，而用户看到的是一个和"地址少写了一段"完全
        // 看不出关系的错误。所以在**发起连接之前**就拦下来，并把该写成什么样直接告诉他。
        val pathStart = normalized.indexOf('/', normalized.indexOf("://") + 3)
        if (pathStart < 0 || normalized.substring(pathStart) == "/") {
            return "服务器 URL 要写到路径 /phone/ws（例如 wss://<主机>/phone/ws）"
        }
        if (deviceId.isBlank()) return "设备 ID 不能为空"
        if (heartbeatIntervalMs <= 0L) return "心跳周期必须大于 0"
        if (pingTimeoutMs <= 0L) return "ping 超时必须大于 0"
        if (reconnectInitialDelayMs < 0L) return "初始重连间隔不能为负"
        if (reconnectMaxDelayMs < reconnectInitialDelayMs) return "最大重连间隔不能小于初始间隔"
        if (reconnectJitterRatio < 0.0 || reconnectJitterRatio > 1.0) return "抖动比例必须在 0 到 1 之间"
        if (maxPendingRequests <= 0) return "最大挂起请求数必须大于 0"
        if (maxFrameBytes < 1024) return "单帧上限不能小于 1024 字节"
        return null
    }

    /** 序列化成「纯字符串」的表，便于 SharedPreferences 落盘与单测往返。 */
    fun toMap(): Map<String, String> = mapOf(
        KEY_SERVER_URL to serverUrl,
        KEY_DEVICE_ID to deviceId,
        KEY_PING_TIMEOUT_MS to pingTimeoutMs.toString(),
        KEY_HEARTBEAT_INTERVAL_MS to heartbeatIntervalMs.toString(),
        KEY_RECONNECT_INITIAL_DELAY_MS to reconnectInitialDelayMs.toString(),
        KEY_RECONNECT_MAX_DELAY_MS to reconnectMaxDelayMs.toString(),
        KEY_RECONNECT_JITTER_RATIO to reconnectJitterRatio.toString(),
        KEY_MAX_PENDING_REQUESTS to maxPendingRequests.toString(),
        KEY_MAX_FRAME_BYTES to maxFrameBytes.toString(),
    )

    companion object {
        const val KEY_SERVER_URL = "server_url"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_PING_TIMEOUT_MS = "ping_timeout_ms"
        const val KEY_HEARTBEAT_INTERVAL_MS = "heartbeat_interval_ms"
        const val KEY_RECONNECT_INITIAL_DELAY_MS = "reconnect_initial_delay_ms"
        const val KEY_RECONNECT_MAX_DELAY_MS = "reconnect_max_delay_ms"
        const val KEY_RECONNECT_JITTER_RATIO = "reconnect_jitter_ratio"
        const val KEY_MAX_PENDING_REQUESTS = "max_pending_requests"
        const val KEY_MAX_FRAME_BYTES = "max_frame_bytes"

        /**
         * 从键值表还原配置。缺失或非法的项一律回落到默认值，
         * 因此旧版本写入的不完整数据不会让应用起不来。
         */
        fun fromMap(map: Map<String, String?>): HubConfig {
            val d = HubConfig()
            return HubConfig(
                serverUrl = map[KEY_SERVER_URL] ?: d.serverUrl,
                deviceId = map[KEY_DEVICE_ID] ?: d.deviceId,
                pingTimeoutMs = map[KEY_PING_TIMEOUT_MS]?.toLongOrNull() ?: d.pingTimeoutMs,
                heartbeatIntervalMs = map[KEY_HEARTBEAT_INTERVAL_MS]?.toLongOrNull()
                    ?: d.heartbeatIntervalMs,
                reconnectInitialDelayMs = map[KEY_RECONNECT_INITIAL_DELAY_MS]?.toLongOrNull()
                    ?: d.reconnectInitialDelayMs,
                reconnectMaxDelayMs = map[KEY_RECONNECT_MAX_DELAY_MS]?.toLongOrNull()
                    ?: d.reconnectMaxDelayMs,
                reconnectJitterRatio = map[KEY_RECONNECT_JITTER_RATIO]?.toDoubleOrNull()
                    ?: d.reconnectJitterRatio,
                maxPendingRequests = map[KEY_MAX_PENDING_REQUESTS]?.toIntOrNull()
                    ?: d.maxPendingRequests,
                maxFrameBytes = map[KEY_MAX_FRAME_BYTES]?.toIntOrNull() ?: d.maxFrameBytes,
            )
        }
    }
}

/**
 * 「高级设置」表单的原始文本。
 *
 * 表单里用户会一边输一边改，中途必然出现 "1", "1.", "-" 这类半成品；
 * 直接在 [HubConfig] 上做双向绑定会导致输入被吃掉。所以草稿全部按字符串保存，
 * 只在「保存 / 连接」时用 [toConfig] 一次性解析，解析失败就回落到默认值。
 *
 * 纯 Kotlin，无 Android 依赖，可 JVM 单测。
 */
data class HubDraft(
    val serverUrl: String = "",
    val deviceId: String = "test-phone-1",
    /** 设备令牌：只用于请求头，永远不会被写进日志或 HubConfig。 */
    val token: String = "",
    val pingTimeoutMs: String = "10000",
    val heartbeatIntervalMs: String = "30000",
    val reconnectInitialDelayMs: String = "1000",
    val reconnectMaxDelayMs: String = "60000",
    val reconnectJitterRatio: String = "0.2",
    val maxPendingRequests: String = "16",
    val maxFrameBytes: String = "65536",
) {

    /** 解析成配置；非法数字回落到 [HubConfig] 的默认值。 */
    fun toConfig(): HubConfig {
        val d = HubConfig()
        return HubConfig(
            serverUrl = serverUrl.trim(),
            deviceId = deviceId.trim(),
            pingTimeoutMs = pingTimeoutMs.trim().toLongOrNull() ?: d.pingTimeoutMs,
            heartbeatIntervalMs = heartbeatIntervalMs.trim().toLongOrNull()
                ?: d.heartbeatIntervalMs,
            reconnectInitialDelayMs = reconnectInitialDelayMs.trim().toLongOrNull()
                ?: d.reconnectInitialDelayMs,
            reconnectMaxDelayMs = reconnectMaxDelayMs.trim().toLongOrNull()
                ?: d.reconnectMaxDelayMs,
            reconnectJitterRatio = reconnectJitterRatio.trim().toDoubleOrNull()
                ?: d.reconnectJitterRatio,
            maxPendingRequests = maxPendingRequests.trim().toIntOrNull() ?: d.maxPendingRequests,
            maxFrameBytes = maxFrameBytes.trim().toIntOrNull() ?: d.maxFrameBytes,
        )
    }

    companion object {
        /** 从已落盘的配置 + 令牌构造草稿。 */
        fun from(config: HubConfig, token: String): HubDraft = HubDraft(
            serverUrl = config.serverUrl,
            deviceId = config.deviceId,
            token = token,
            pingTimeoutMs = config.pingTimeoutMs.toString(),
            heartbeatIntervalMs = config.heartbeatIntervalMs.toString(),
            reconnectInitialDelayMs = config.reconnectInitialDelayMs.toString(),
            reconnectMaxDelayMs = config.reconnectMaxDelayMs.toString(),
            reconnectJitterRatio = config.reconnectJitterRatio.toString(),
            maxPendingRequests = config.maxPendingRequests.toString(),
            maxFrameBytes = config.maxFrameBytes.toString(),
        )
    }
}
