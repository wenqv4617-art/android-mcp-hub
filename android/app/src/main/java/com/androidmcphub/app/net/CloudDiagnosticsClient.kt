package com.androidmcphub.app.net

import com.androidmcphub.app.core.HubConfig
import com.androidmcphub.app.core.TokenLoadResult
import com.androidmcphub.app.core.normalizeWebSocketUrl
import com.androidmcphub.app.protocol.CLIENT_VERSION
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 云端连接诊断的 HTTPS 查询客户端（V1.6.3）。
 *
 * 为什么用**独立的** [OkHttpClient]：
 *  - 让查询与主 WebSocket 彻底解耦。一次诊断查询超时/失败，不允许影响主连接，
 *    更不允许触发重连（那是排查对象，不是手段）；
 *  - 主连接用的客户端 `readTimeout(0)` 是为长连接准备的；短请求用它不合适；
 *  - 主连接断开时（HTTPS 仍可达）依然能查云端已有事件 —— 这正是本功能的意义。
 *
 * 线程模型：本类的方法**阻塞**执行，调用方（ViewModel）负责丢到 IO 线程。
 * 不抛异常：所有失败都收敛成 [CloudDiagnosticsResult.Failure]，且不携带异常原文
 * （异常 message 可能包含主机名、URL 或响应内容）。
 *
 * 安全：只发 GET；只带 `Authorization: Bearer <设备令牌>` 与 `X-Device-Id`；
 * 不接受 URL 参数注入（[diagnosticsUrlOf] 只返回状态码 200 的固定路径）。
 * **不记录**请求头、URL 或响应体到任何日志。
 */
class CloudDiagnosticsClient(
    private val configProvider: () -> HubConfig,
    private val credentialProvider: () -> TokenLoadResult,
    private val clock: () -> Long = System::currentTimeMillis,
    private val httpClient: OkHttpClient = defaultHttpClient(),
) {

    /** 查询最近 [limit] 条云端诊断事件。上限由服务端再收敛一次。 */
    fun fetch(limit: Int = DEFAULT_LIMIT): CloudDiagnosticsResult {
        val config = configProvider()
        val url = diagnosticsUrlOf(config.serverUrl)
            ?: return CloudDiagnosticsResult.Failure(CloudDiagFailure.INVALID_URL)
        if (config.deviceId.isBlank()) {
            return CloudDiagnosticsResult.Failure(CloudDiagFailure.NOT_CONFIGURED)
        }
        val token = (credentialProvider() as? TokenLoadResult.Present)?.token
        if (token.isNullOrBlank()) {
            return CloudDiagnosticsResult.Failure(CloudDiagFailure.NOT_CONFIGURED)
        }

        val safeLimit = limit.coerceIn(1, MAX_LIMIT)
        val request = Request.Builder()
            .url("$url?limit=$safeLimit")
            .header("Authorization", "Bearer $token")
            .header("X-Device-Id", config.deviceId)
            .header("X-Client-Version", CLIENT_VERSION)
            .header("Accept", "application/json")
            .get()
            .build()

        val fetchedAt = clock()
        return try {
            httpClient.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> {
                        val body = response.body?.string().orEmpty()
                        parseDiagnostics(body, fetchedAt)
                    }

                    response.code == 401 -> CloudDiagnosticsResult.Failure(
                        CloudDiagFailure.UNAUTHORIZED,
                        response.code,
                    )

                    response.code == 403 -> CloudDiagnosticsResult.Failure(
                        CloudDiagFailure.FORBIDDEN,
                        response.code,
                    )

                    response.code == 404 -> CloudDiagnosticsResult.Failure(
                        CloudDiagFailure.ENDPOINT_MISSING,
                        response.code,
                    )

                    else -> CloudDiagnosticsResult.Failure(
                        CloudDiagFailure.SERVER_ERROR,
                        response.code,
                    )
                }
            }
        } catch (_: java.net.SocketTimeoutException) {
            CloudDiagnosticsResult.Failure(CloudDiagFailure.TIMEOUT)
        } catch (_: java.io.IOException) {
            CloudDiagnosticsResult.Failure(CloudDiagFailure.NETWORK)
        } catch (_: RuntimeException) {
            // 解析/运行时异常不携带原文：它可能把响应内容带进来。
            CloudDiagnosticsResult.Failure(CloudDiagFailure.BAD_RESPONSE)
        }
    }

    private fun parseDiagnostics(body: String, fetchedAtMs: Long): CloudDiagnosticsResult {
        val root = runCatching { JSONObject(body) }.getOrNull()
            ?: return CloudDiagnosticsResult.Failure(CloudDiagFailure.BAD_RESPONSE)
        if (!root.optBoolean("ok", false)) {
            return CloudDiagnosticsResult.Failure(CloudDiagFailure.BAD_RESPONSE)
        }
        val serverTime = root.optLong("server_time", 0L)
        if (serverTime <= 0L) {
            // 没有服务端时刻就无法校正两端时钟差；合并时间线不能靠猜，
            // 所以这份响应按"不可用"处理，而不是假装时钟一致。
            return CloudDiagnosticsResult.Failure(CloudDiagFailure.BAD_RESPONSE)
        }

        val events = mutableListOf<CloudDiagEvent>()
        val rawEvents = root.optJSONArray("events")
        if (rawEvents != null) {
            for (i in 0 until rawEvents.length()) {
                val obj = rawEvents.optJSONObject(i) ?: continue
                events += parseEvent(obj) ?: continue
            }
        }

        return CloudDiagnosticsResult.Success(
            CloudDiagnostics(
                deviceId = root.optStringOrNull("device_id"),
                serverTimeMs = serverTime,
                fetchedAtMs = fetchedAtMs,
                online = root.optBoolean("online", false),
                socketOpen = root.optBoolean("socket_open", false),
                stale = root.optBoolean("stale", false),
                connectionGeneration = root.optLong("connection_generation", 0L),
                lastPhoneActivityAtMs = root.optLongOrNull("last_phone_activity_at"),
                events = events,
                summary = parseSummary(root.optJSONObject("summary")),
                buffer = parseBuffer(root.optJSONObject("buffer")),
                closeCodes = parseCloseCodes(root.optJSONObject("close_codes")),
            ),
        )
    }

    private fun parseEvent(obj: JSONObject): CloudDiagEvent? {
        val type = obj.optString("type", "")
        if (type.isEmpty()) return null
        val at = obj.optLong("at", 0L)
        if (at <= 0L) return null
        return CloudDiagEvent(
            seq = obj.optLong("seq", 0L),
            atMs = at,
            clientAtMs = obj.optLongOrNull("client_at"),
            source = obj.optString("source", "unknown"),
            type = type,
            generation = obj.optLongOrNull("generation"),
            closeCode = obj.optIntOrNull("close_code"),
            closeReason = obj.optStringOrNull("close_reason"),
            serverInitiated = if (obj.has("server_initiated") && !obj.isNull("server_initiated")) {
                obj.optBoolean("server_initiated")
            } else {
                null
            },
            durationMs = obj.optLongOrNull("duration_ms"),
            detail = CloudDiagDetail.parse(obj.optJSONObject("detail")),
        )
    }

    private fun parseSummary(obj: JSONObject?): CloudDiagSummary = CloudDiagSummary(
        lastOnlineAtMs = obj?.optLongOrNull("last_online_at"),
        lastOfflineAtMs = obj?.optLongOrNull("last_offline_at"),
        lastOfflineReason = obj?.optStringOrNull("last_offline_reason"),
        lastOfflineCloseCode = obj?.optIntOrNull("last_offline_close_code"),
        lastServerInitiatedCloseAtMs = obj?.optLongOrNull("last_server_initiated_close_at"),
        replacedEvents = obj?.optInt("replaced_events", 0) ?: 0,
        revocations = obj?.optInt("revocations", 0) ?: 0,
        livenessTimeouts = obj?.optInt("liveness_timeouts", 0) ?: 0,
        authFailures = obj?.optInt("auth_failures", 0) ?: 0,
        clientReports = obj?.optInt("client_reports", 0) ?: 0,
    )

    private fun parseBuffer(obj: JSONObject?): CloudDiagBuffer = CloudDiagBuffer(
        size = obj?.optInt("size", 0) ?: 0,
        returned = obj?.optInt("returned", 0) ?: 0,
        dropped = obj?.optInt("dropped", 0) ?: 0,
        firstAtMs = obj?.optLongOrNull("first_at"),
        lastAtMs = obj?.optLongOrNull("last_at"),
        maxEvents = obj?.optInt("max_events", 0) ?: 0,
        maxAgeMs = obj?.optLong("max_age_ms", 0L) ?: 0L,
    )

    private fun parseCloseCodes(obj: JSONObject?): Map<Int, String> {
        if (obj == null) return emptyMap()
        val out = mutableMapOf<Int, String>()
        for (key in obj.keys()) {
            val code = key.toIntOrNull() ?: continue
            val label = obj.optString(key, "")
            if (label.isNotEmpty() && label.length <= 64) out[code] = label
        }
        return out
    }

    companion object {
        const val DEFAULT_LIMIT: Int = 100
        const val MAX_LIMIT: Int = 200

        /**
         * 把 WebSocket 地址换算成诊断查询地址。纯函数，JVM 可直接测。
         *
         * `wss://host/phone/ws` → `https://host/phone/diagnostics`
         *
         * 为什么不保留原 path 直接拼：服务端路由固定在 `/phone/diagnostics`；
         * 也不保留 query —— 用户从别处复制来的地址可能带 `?token=…`，
         * 那是最不该出现在第二个请求里的东西。
         */
        fun diagnosticsUrlOf(serverUrl: String): String? {
            val normalized = normalizeWebSocketUrl(serverUrl) ?: return null
            val schemeEnd = normalized.indexOf("://")
            if (schemeEnd <= 0) return null
            val authorityStart = schemeEnd + 3
            val authorityEnd = normalized.indexOf('/', authorityStart)
            val origin = if (authorityEnd < 0) normalized else normalized.substring(0, authorityEnd)
            if (origin.length <= authorityStart) return null
            val rawPath = if (authorityEnd < 0) "/" else normalized.substring(authorityEnd)
            // 容忍用户手填的尾部斜杠：`/phone/ws/` 与 `/phone/ws` 指向同一个入口。
            val path = if (rawPath.length > 1) rawPath.trimEnd('/') else rawPath
            val newPath = when {
                path == "/" || path.isEmpty() -> "/phone/diagnostics"
                path.endsWith("/phone/ws") -> path.removeSuffix("/phone/ws") + "/phone/diagnostics"
                path.endsWith("/ws") -> path.removeSuffix("/ws") + "/diagnostics"
                // 非标准路径：按服务端固定路由追加，而不是猜一个"看起来对"的地址。
                else -> path.trimEnd('/') + "/phone/diagnostics"
            }
            return origin + newPath
        }

        /**
         * 专用短请求客户端：8 秒超时、不跟随重定向、不设置 WebSocket ping。
         *
         * 重定向一律不跟：诊断接口不该把凭据转发到另一个地址。
         */
        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()
    }
}

/** 一次云端诊断查询的结果。 */
sealed interface CloudDiagnosticsResult {
    data class Success(val payload: CloudDiagnostics) : CloudDiagnosticsResult
    data class Failure(
        val kind: CloudDiagFailure,
        val httpStatus: Int? = null,
    ) : CloudDiagnosticsResult
}

/** 查询失败的**闭集**原因；界面据此给出一条可操作的说明。 */
enum class CloudDiagFailure {
    /** 服务器地址无效（或不是 ws(s)/http(s)）。 */
    INVALID_URL,

    /** 还没填设备 ID / 设备令牌。 */
    NOT_CONFIGURED,

    /** 401：令牌不被接受（可能已被撤销/轮换）。 */
    UNAUTHORIZED,

    /** 403：网关拒绝（部署/路由配置问题）。 */
    FORBIDDEN,

    /** 404：服务端还没有这个接口（旧版本 Worker 未部署 V1.6.3）。 */
    ENDPOINT_MISSING,

    /** 5xx 或其它非 2xx。 */
    SERVER_ERROR,

    /** 连接失败 / DNS / TLS / 被重置。 */
    NETWORK,

    /** 8 秒内没有响应。 */
    TIMEOUT,

    /** 响应不是预期结构，或缺少服务端时间。 */
    BAD_RESPONSE,
}

/** 云端对这台设备的诊断快照。 */
data class CloudDiagnostics(
    val deviceId: String?,
    /** 服务端记录本响应的时刻（服务端时钟）。 */
    val serverTimeMs: Long,
    /** 手机收到响应的时刻（手机时钟）。 */
    val fetchedAtMs: Long,
    val online: Boolean,
    val socketOpen: Boolean,
    /** socket 还注册着，但超过判活阈值没有任何应用层活动。 */
    val stale: Boolean,
    val connectionGeneration: Long,
    val lastPhoneActivityAtMs: Long?,
    val events: List<CloudDiagEvent>,
    val summary: CloudDiagSummary,
    val buffer: CloudDiagBuffer,
    val closeCodes: Map<Int, String>,
) {
    /**
     * 服务端时钟 − 手机时钟（估算值，含一次单程网络延迟）。
     *
     * 合并时间线把服务端时间戳 + 这个偏移映射到手机时间轴。误差来源（RTT）
     * 会在界面上如实说明，不假装两端时钟完全一致。
     */
    val clockOffsetMs: Long get() = serverTimeMs - fetchedAtMs

    /** 把服务端时间戳映射到手机时间轴。 */
    fun serverToLocalAxis(serverMs: Long): Long = serverMs + clockOffsetMs

    /** 关闭码的语义标签；服务端没给就返回 null，不本地臆造。 */
    fun closeCodeLabel(code: Int?): String? = code?.let { closeCodes[it] }
}

data class CloudDiagEvent(
    val seq: Long,
    val atMs: Long,
    val clientAtMs: Long?,
    val source: String,
    val type: String,
    val generation: Long?,
    val closeCode: Int?,
    val closeReason: String?,
    val serverInitiated: Boolean?,
    val durationMs: Long?,
    val detail: CloudDiagDetail,
)

data class CloudDiagSummary(
    val lastOnlineAtMs: Long?,
    val lastOfflineAtMs: Long?,
    val lastOfflineReason: String?,
    val lastOfflineCloseCode: Int?,
    val lastServerInitiatedCloseAtMs: Long?,
    val replacedEvents: Int,
    val revocations: Int,
    val livenessTimeouts: Int,
    val authFailures: Int,
    val clientReports: Int,
)

data class CloudDiagBuffer(
    val size: Int,
    val returned: Int,
    val dropped: Int,
    val firstAtMs: Long?,
    val lastAtMs: Long?,
    val maxEvents: Int,
    val maxAgeMs: Long,
)

/**
 * 服务端诊断事件 `detail` 的白名单视图。
 *
 * 只保留服务端 `DETAIL_RULES` 里存在的键，并且只接受标量；字符串截断到 64
 * 字符并去掉控制字符。未知键**整个丢弃** —— 即使服务端将来多写了字段，
 * 也不会在手机上变成一段可展示的自由文本。
 */
data class CloudDiagDetail(val values: Map<String, String>) {

    operator fun get(key: String): String? = values[key]

    val cause: String? get() = values["cause"]
    val caller: String? get() = values["caller"]
    val network: String? get() = values["network"]
    val vpn: Boolean? get() = values["vpn"]?.toBooleanStrictOrNull()
    val screen: String? get() = values["screen"]
    val pid: Int? get() = values["pid"]?.toIntOrNull()
    val processUptimeMs: Long? get() = values["process_uptime_ms"]?.toLongOrNull()
    val appVersion: String? get() = values["app_version"]
    val androidSdk: Int? get() = values["android_sdk"]?.toIntOrNull()
    val localCloseSource: String? get() = values["local_close_source"]
    val heartbeatAgeMs: Long? get() = values["heartbeat_age_ms"]?.toLongOrNull()
    val exceptionClass: String? get() = values["exception_class"]
    val attempt: Int? get() = values["attempt"]?.toIntOrNull()
    val clientGeneration: Long? get() = values["client_generation"]?.toLongOrNull()
    val replaced: Boolean? get() = values["replaced"]?.toBooleanStrictOrNull()

    /** 按固定顺序输出，便于界面/导出稳定 diff。 */
    fun ordered(): List<Pair<String, String>> = KEY_ORDER.mapNotNull { key ->
        values[key]?.let { key to it }
    }

    companion object {
        /** 与服务端 `DETAIL_RULES` 的键集合保持一致；顺序只影响展示。 */
        private val KEY_ORDER = listOf(
            "cause",
            "caller",
            "client_event",
            "category",
            "exception_class",
            "local_close_source",
            "network",
            "vpn",
            "screen",
            "pid",
            "process_uptime_ms",
            "app_version",
            "android_sdk",
            "client_generation",
            "replaced",
            "replaced_generation",
            "old_generation",
            "new_generation",
            "open_sockets",
            "replaced_count",
            "silent_ms",
            "liveness_timeout_ms",
            "heartbeat_interval_ms",
            "heartbeat_age_ms",
            "attempt",
            "pending_requests",
            "auth_reason",
            "auth_scope",
            "auth_source",
            "protocol_version",
        )

        fun parse(obj: JSONObject?): CloudDiagDetail {
            if (obj == null) return CloudDiagDetail(emptyMap())
            val out = LinkedHashMap<String, String>()
            for (key in KEY_ORDER) {
                if (!obj.has(key) || obj.isNull(key)) continue
                val rendered = when (val raw = obj.opt(key)) {
                    is String -> raw.filter { !it.isISOControl() }.take(64)
                    is Number -> raw.toString()
                    is Boolean -> raw.toString()
                    else -> null
                } ?: continue
                if (rendered.isNotEmpty()) out[key] = rendered
            }
            return CloudDiagDetail(out)
        }
    }
}

private fun JSONObject.optStringOrNull(name: String): String? {
    if (!has(name) || isNull(name)) return null
    val value = optString(name, "")
    return value.ifEmpty { null }
}

private fun JSONObject.optLongOrNull(name: String): Long? {
    if (!has(name) || isNull(name)) return null
    return when (val value = opt(name)) {
        is Number -> value.toLong()
        is String -> value.toLongOrNull()
        else -> null
    }
}

private fun JSONObject.optIntOrNull(name: String): Int? {
    if (!has(name) || isNull(name)) return null
    return when (val value = opt(name)) {
        is Number -> value.toInt()
        is String -> value.toIntOrNull()
        else -> null
    }
}
