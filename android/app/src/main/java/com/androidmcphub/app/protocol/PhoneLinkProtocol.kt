package com.androidmcphub.app.protocol

import org.json.JSONException
import org.json.JSONObject

/** 协议版本号，服务端与手机端必须一致。 */
const val PROTOCOL_VERSION = "phone-link-v1"

/** 应用版本，进入 hello 帧；与 build.gradle.kts 的 versionName 保持一致。 */
const val APP_VERSION = "0.1.4-gate1"

/** 握手时上报的客户端标识：`android-mcp-hub/<version>`。 */
const val CLIENT_VERSION = "android-mcp-hub/$APP_VERSION"

/** 去重窗口大小（R2）。 */
const val PING_DEDUP_CAPACITY = 256

/**
 * 服务端 → 手机的帧。
 *
 * [Unknown] 同时承担三类「按 R4 忽略」的情况：未知 type、非法 JSON、缺必需字段。
 * 它们一律只记日志、不回错误帧。
 */
sealed interface IncomingFrame {

    /** 建连成功后服务端下发的第一帧。 */
    data class Welcome(
        val deviceId: String,
        val connectionGeneration: Long,
        val serverTime: Long,
        val heartbeatIntervalMs: Long?,
        val pingTimeoutMs: Long?,
        val maxPendingRequests: Int?,
        val protocolVersion: String?,
    ) : IncomingFrame

    /** 保活探针：必须用**同一个** request_id 回 phone_pong（R1）。 */
    data class Ping(
        val requestId: String,
        val timestamp: Long,
    ) : IncomingFrame

    /** 对我们发出的应用层心跳的确认（R3 的回应）。 */
    data class HeartbeatAck(
        val serverTime: Long,
    ) : IncomingFrame

    /** 服务端错误帧。 */
    data class ServerError(
        val code: String,
        val message: String,
    ) : IncomingFrame

    /** 未知 / 非法 / 缺字段：忽略并记日志，不回错误。 */
    data class Unknown(
        val type: String,
        val reason: String,
    ) : IncomingFrame
}

/**
 * [IncomingFrame.ServerError.code] 的**已知**取值。
 *
 * 为什么要有这个闭集（Gate 0.3 审计发现项 1）：`code` 与 `message` 都是**对端**给的
 * 自由文本，一台被改造过的手机可以把令牌塞在里面喂给我们的日志。所以日志里只允许出现
 * 这个闭集里的值（认识的）或 [UNRECOGNIZED]（不认识的），`message` 一律不记。
 */
object ServerErrorCode {
    const val MESSAGE_TOO_LARGE = "message_too_large"
    const val MALFORMED_MESSAGE = "malformed_message"
    const val UNSUPPORTED_TYPE = "unsupported_type"
    const val UNKNOWN_REQUEST_ID = "unknown_request_id"

    /** 认不出来的 code —— 只记这个，不记原文。 */
    const val UNRECOGNIZED = "unrecognized_error_code"

    val ALL: Set<String> = setOf(MESSAGE_TOO_LARGE, MALFORMED_MESSAGE, UNSUPPORTED_TYPE, UNKNOWN_REQUEST_ID)

    /** 认识的 code 原样返回，其余一律收敛成 [UNRECOGNIZED]。 */
    fun normalize(code: String): String = if (code in ALL) code else UNRECOGNIZED
}

/** [IncomingFrame.Unknown.reason] 的取值。 */
object UnknownReason {
    const val INVALID_JSON = "invalid_json"
    const val MISSING_TYPE = "missing_type"
    const val MISSING_REQUEST_ID = "missing_request_id"
    const val UNSUPPORTED_TYPE = "unsupported_type"
}

/**
 * 解析一帧服务端文本。
 *
 * 该函数**不会抛异常**：任何解析失败都归到 [IncomingFrame.Unknown]。
 */
fun parseIncoming(raw: String): IncomingFrame {
    val obj: JSONObject = try {
        JSONObject(raw)
    } catch (_: JSONException) {
        return IncomingFrame.Unknown(type = "", reason = UnknownReason.INVALID_JSON)
    }

    val type = obj.optString("type", "")
    if (type.isEmpty()) {
        return IncomingFrame.Unknown(type = "", reason = UnknownReason.MISSING_TYPE)
    }

    return when (type) {
        "welcome" -> IncomingFrame.Welcome(
            deviceId = obj.optString("device_id", ""),
            connectionGeneration = obj.optLong("connection_generation", 0L),
            serverTime = obj.optLong("server_time", 0L),
            heartbeatIntervalMs = obj.optLongOrNull("heartbeat_interval_ms"),
            pingTimeoutMs = obj.optLongOrNull("ping_timeout_ms"),
            maxPendingRequests = obj.optIntOrNull("max_pending_requests"),
            protocolVersion = if (obj.has("protocol_version")) {
                obj.optString("protocol_version", "")
            } else {
                null
            },
        )

        "phone_ping" -> {
            // request_id 是回包的唯一凭据，缺了就无法回，只能忽略（R4）
            val requestId = obj.optString("request_id", "")
            if (requestId.isEmpty()) {
                IncomingFrame.Unknown(type, UnknownReason.MISSING_REQUEST_ID)
            } else {
                IncomingFrame.Ping(
                    requestId = requestId,
                    timestamp = obj.optLong("timestamp", 0L),
                )
            }
        }

        "heartbeat_ack" -> IncomingFrame.HeartbeatAck(
            serverTime = obj.optLong("server_time", 0L),
        )

        "error" -> IncomingFrame.ServerError(
            code = obj.optString("code", ""),
            message = obj.optString("message", ""),
        )

        // R4：未知 type 忽略，不回错误
        else -> IncomingFrame.Unknown(type, UnknownReason.UNSUPPORTED_TYPE)
    }
}

/**
 * 手机 → 服务端的 phone_pong。
 *
 * [requestId] 必须与收到的 phone_ping 逐字符相同（R1）：
 * 这里直接用 org.json 编码，转义规则与解析端一致，不会出现「看起来一样但字节不同」。
 */
fun encodePong(requestId: String, ts: Long): String = JSONObject()
    .put("type", "phone_pong")
    .put("request_id", requestId)
    .put("timestamp", ts)
    .toString()

/** 手机 → 服务端的应用层心跳（R3）。 */
fun encodeHeartbeat(ts: Long): String = JSONObject()
    .put("type", "heartbeat")
    .put("timestamp", ts)
    .toString()

/** 手机 → 服务端的握手帧。 */
fun encodeHello(
    deviceId: String,
    androidSdk: Int,
    appVersion: String = APP_VERSION,
): String = JSONObject()
    .put("type", "hello")
    .put("device_id", deviceId)
    .put("protocol_version", PROTOCOL_VERSION)
    .put("app_version", appVersion)
    .put("android_sdk", androidSdk)
    .toString()

/** 只取数字，字符串形式的数字也接受；其它一律 null。 */
private fun JSONObject.optLongOrNull(name: String): Long? {
    if (!has(name) || isNull(name)) return null
    return when (val v = opt(name)) {
        is Number -> v.toLong()
        is String -> v.toLongOrNull()
        else -> null
    }
}

private fun JSONObject.optIntOrNull(name: String): Int? {
    if (!has(name) || isNull(name)) return null
    return when (val v = opt(name)) {
        is Number -> v.toInt()
        is String -> v.toIntOrNull()
        else -> null
    }
}
