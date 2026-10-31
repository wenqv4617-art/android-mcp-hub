package com.androidmcphub.app.protocol

import org.json.JSONException
import org.json.JSONObject

/** 协议版本号，服务端与手机端必须一致。 */
const val PROTOCOL_VERSION = "phone-link-v1"

/** 应用版本，进入 hello 帧；与 build.gradle.kts 的 versionName 保持一致。 */
const val APP_VERSION = "1.6.2"

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

    /**
     * Gate 2：通用工具调用。
     *
     * [toolName] 只是**请求**的名字，它不代表这台手机答应执行 ——
     * 能不能执行完全由 [com.androidmcphub.app.capability.CapabilityDispatcher]
     * 的注册表（显式白名单）决定，不在表里的一律回 `unsupported_tool`。
     *
     * [arguments] 已经归一成 [JSONObject]：**缺** `arguments` 字段等价于「没有参数」，
     * 但 `arguments` 存在且不是对象（数组 / 字符串 / 数字）属于**格式错误**，
     * 走 [Unknown]，不会退化成"按没有参数执行" —— 用空参数去执行一个本该带参数的
     * 工具，会产出一个**看起来成功但其实答错了**的结果，这比明确失败更糟。
     */
    data class ToolRequest(
        val requestId: String,
        val toolName: String,
        val arguments: JSONObject,
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

    /**
     * Gate 2：`tool_request` 的**信封完好、内容不合法**（目前只有 `arguments` 不是对象）。
     *
     * 为什么单独一类，而不是并进 [Unknown]：这里 `request_id` 是**拿得到**的，
     * 所以可以立刻回一条 `tool_result{invalid_arguments}`。并进 [Unknown] 的话，
     * 服务端会一直等到超时（默认 15s）才拿到 `phone_timeout` —— 把一个明确的
     * "你参数写错了"说成了"手机可能死了"，那是最容易误导排查的一种结果。
     *
     * 缺 `request_id` 的帧仍然走 [Unknown]：回不了。
     */
    data class ToolRequestMalformed(
        val requestId: String,
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

    /**
     * Gate 1.5 / A1：服务端已撤销这台设备的凭据，连接必须结束。
     *
     * 手机侧**有**特殊动作（集中审计问题 4）：`PhoneLink.onTextFrame` 收到它、
     * 以及 `onSocketClosed` 收到 close 4002 时，都会进入终态 [LinkState.Revoked]
     * 并**停止自动重连** —— 因为拿同一份凭据重连只会被升级阶段同样地拒掉。
     * 把它列进闭集同时也保证日志里出现的是这个可读名字，而不是 unrecognized。
     */
    const val DEVICE_REVOKED = "device_revoked"

    /** 认不出来的 code —— 只记这个，不记原文。 */
    const val UNRECOGNIZED = "unrecognized_error_code"

    val ALL: Set<String> =
        setOf(MESSAGE_TOO_LARGE, MALFORMED_MESSAGE, UNSUPPORTED_TYPE, UNKNOWN_REQUEST_ID, DEVICE_REVOKED)

    /** 认识的 code 原样返回，其余一律收敛成 [UNRECOGNIZED]。 */
    fun normalize(code: String): String = if (code in ALL) code else UNRECOGNIZED
}

/**
 * `tool_result.error.code` 的闭集（与服务端 `worker/src/phone/messages.ts` 保持一致）。
 *
 * 手机侧**只会**发出这里面的 code；服务端认不出来时会归一成 `tool_failed`，
 * 所以这个集合是"我们承诺的语义"，不是"对端可以随便填的字段"。
 */
object ToolResultErrorCode {
    /** 白名单里没有这个工具名。 */
    const val UNSUPPORTED_TOOL = "unsupported_tool"

    /** 参数不符合工具的声明（缺字段 / 类型不对 / 多出字段）。 */
    const val INVALID_ARGUMENTS = "invalid_arguments"

    /** 执行期失败（拿不到系统服务、API 抛异常）。 */
    const val TOOL_FAILED = "tool_failed"

    /** 工具存在但当前不可用（例如系统版本不支持）。 */
    const val TOOL_UNAVAILABLE = "tool_unavailable"

    /** 本地授权策略拒绝（用户没打开这个能力）。 */
    const val NOT_PERMITTED = "not_permitted"

    /**
     * 系统运行期权限没有授予（例如使用情况访问、日历读写）。
     *
     * 与 [NOT_PERMITTED] 刻意分开：后者是**用户在本应用里**关掉了开关，
     * 前者是 Android 系统层面的授权缺失 —— 两者的修复路径完全不同，
     * 界面与服务端给出的下一步指引也不应该一样。
     */
    const val PERMISSION_DENIED = "permission_denied"

    /**
     * 高影响操作缺少明确确认（调用方没有传 `confirm=true`）。
     *
     * 这不是错误用法：模型应当先看到这个结果，再决定是否真的执行删除。
     */
    const val CONFIRMATION_REQUIRED = "confirmation_required"

    val ALL: Set<String> = setOf(
        UNSUPPORTED_TOOL,
        INVALID_ARGUMENTS,
        TOOL_FAILED,
        TOOL_UNAVAILABLE,
        NOT_PERMITTED,
        PERMISSION_DENIED,
        CONFIRMATION_REQUIRED,
    )
}

/** [IncomingFrame.Unknown.reason] 的取值。 */
object UnknownReason {
    const val INVALID_JSON = "invalid_json"
    const val MISSING_TYPE = "missing_type"
    const val MISSING_REQUEST_ID = "missing_request_id"
    const val UNSUPPORTED_TYPE = "unsupported_type"

    /** Gate 2：`tool_request` 缺 `tool_name`（或为空）。 */
    const val MISSING_TOOL_NAME = "missing_tool_name"

    /** Gate 2：`tool_request` 的 `arguments` 存在但不是对象。 */
    const val BAD_ARGUMENTS = "bad_arguments"
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

        "tool_request" -> {
            // request_id 同样是回包的唯一凭据，缺了就没法回（R4）
            val requestId = obj.optString("request_id", "")
            if (requestId.isEmpty()) {
                IncomingFrame.Unknown(type, UnknownReason.MISSING_REQUEST_ID)
            } else {
                val toolName = obj.optString("tool_name", "")
                if (toolName.isEmpty()) {
                    IncomingFrame.Unknown(type, UnknownReason.MISSING_TOOL_NAME)
                } else {
                    // 「没有 arguments 字段」= 没有参数；「arguments 不是对象」= 格式错误。
                    // 这两者绝不能混为一谈（见 ToolRequest 的注释）。
                    val hasArguments = obj.has("arguments") && !obj.isNull("arguments")
                    val arguments = if (hasArguments) obj.optJSONObject("arguments") else JSONObject()
                    if (hasArguments && arguments == null) {
                        // request_id 拿得到 → 立刻回 invalid_arguments，不让服务端白等超时。
                        IncomingFrame.ToolRequestMalformed(requestId, UnknownReason.BAD_ARGUMENTS)
                    } else {
                        IncomingFrame.ToolRequest(
                            requestId = requestId,
                            toolName = toolName,
                            arguments = arguments ?: JSONObject(),
                        )
                    }
                }
            }
        }

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

/**
 * 手机 → 服务端的 `tool_result`（Gate 2）。
 *
 * 两个不变量由**这一个函数**保证，调用方无从违反：
 *  1. `request_id` 原样回传（服务端靠它配对待处理请求，写错就变成"无人认领"）；
 *  2. 成功帧**不带** `error`、失败帧**不带** `result` —— 服务端会把同时带两者的帧
 *     判成 `tool_result_conflicting_*` 直接丢弃，那等于让请求白白超时。
 *
 * [errorCode] 必须是 [ToolResultErrorCode.ALL] 里的值；给别的值会被服务端归一成
 * `tool_failed`（不丢帧，但语义就丢了），所以这里在**本地**就把它收敛掉。
 */
fun encodeToolResult(
    requestId: String,
    ok: Boolean,
    result: JSONObject? = null,
    errorCode: String? = null,
    errorMessage: String? = null,
): String {
    val obj = JSONObject()
        .put("type", "tool_result")
        .put("request_id", requestId)
        .put("ok", ok)

    if (ok) {
        obj.put("result", result ?: JSONObject())
    } else {
        val code = if (errorCode != null && errorCode in ToolResultErrorCode.ALL) {
            errorCode
        } else {
            ToolResultErrorCode.TOOL_FAILED
        }
        obj.put(
            "error",
            JSONObject()
                .put("code", code)
                // message 是给人看的；服务端只透传、不解析。空消息补一句兜底文案，
                // 免得日志里出现一个没有理由的失败。
                .put("message", errorMessage?.takeIf { it.isNotEmpty() } ?: "工具未能完成"),
        )
    }

    return obj.toString()
}

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
