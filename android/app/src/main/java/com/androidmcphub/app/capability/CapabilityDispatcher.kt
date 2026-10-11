package com.androidmcphub.app.capability

import com.androidmcphub.app.protocol.RequestIdLru
import com.androidmcphub.app.protocol.ToolResultErrorCode
import com.androidmcphub.app.protocol.encodeToolResult
import org.json.JSONObject

/** Gate 2：工具去重窗口大小（与 `PING_DEDUP_CAPACITY` 同量级）。 */
const val TOOL_DEDUP_CAPACITY = 256

/**
 * 工具名允许的形状。
 *
 * 为什么要有这个上限（而不是"注册表里找不到就回 unsupported_tool"就够了）：
 *  * 未知工具名最终会被写进**日志**，而它是**对端**给的自由文本。只允许小写字母、
 *    数字、下划线，就连换行和控制字符都进不了日志（与 [ServerErrorCode] 的收敛同一个理由）；
 *  * 顺带挡住超长名字占内存。
 */
private val TOOL_NAME_PATTERN = Regex("^[a-z][a-z0-9_]{0,63}$")

/**
 * 结果对象里**不允许**出现的键。
 *
 * 这些键属于**信封**，不属于结果。服务端也会把它们从 `result` 里剔掉
 * （`tool_result` 的 reserved-key 处理），这里做同一件事是**纵深防御**：
 * 一个写错的能力不可能构造出一个自相矛盾的帧（例如 result 里塞一个 `ok:false`）。
 */
private val RESERVED_RESULT_KEYS = setOf("type", "ok", "request_id", "device_id")

/** 一次能力调用的结果。 */
sealed interface CapabilityResult {

    /** 成功。[payload] 会被放进 `tool_result.result`。 */
    data class Success(val payload: JSONObject) : CapabilityResult

    /**
     * 失败。
     *
     * [code] 必须取自 [ToolResultErrorCode]；[message] 是给人看的**我们自己的**文案，
     * 绝不能把系统异常原文、文件路径、设备标识之类的东西塞进来。
     */
    data class Failure(val code: String, val message: String) : CapabilityResult
}

/**
 * 一个**只读**能力。
 *
 * 契约（由 [CapabilityDispatcher] 强制，实现者不必自己重复检查）：
 *  * 只有 [allowedArguments] 里列出的键会被传给 [execute]；
 *  * [execute] 抛异常不会把连接搞崩，会被转成 `tool_failed`；
 *  * [execute] 返回的 payload 里如果带了信封保留键，那些键会被剔除。
 *
 * 实现者**必须**保证：无副作用、不读用户文件、不上传设备标识符、不修改系统状态。
 */
interface Capability {

    /** 工具名（小写 + 下划线，与 `TOOL_NAME_PATTERN` 一致）。 */
    val name: String

    /** 允许出现的参数名。这是**显式白名单**：多出任何一个键都会被拒绝。 */
    val allowedArguments: Set<String>

    /**
     * 参数取值校验。[arguments] 只会包含 [allowedArguments] 里的键。
     *
     * @return 拒绝理由（我们自己的文案）；null 表示通过。
     */
    fun validate(arguments: JSONObject): String? = null

    /** 执行。[arguments] 只包含通过校验的、白名单里的键。 */
    fun execute(arguments: JSONObject): CapabilityResult
}

/** 一次 `tool_request` 的处理结论（纯数据，便于断言）。 */
data class ToolDispatchOutcome(
    val requestId: String,
    /** 归一后的工具名；未知/非法名字一律是 [UNKNOWN_TOOL_LABEL]，**不回显原文**。 */
    val toolName: String,
    val ok: Boolean,
    val errorCode: String?,
    val elapsedMs: Long,
    /** 重复的 request_id：帧为 null，且**没有**执行任何能力。 */
    val duplicate: Boolean,
    /** 要回发的 `tool_result` 帧；[duplicate] 时为 null。 */
    val frame: String?,
)

/** 未知/非法工具名在日志与返回值里的统一占位符。 */
const val UNKNOWN_TOOL_LABEL = "<unknown>"

/**
 * **本地授权策略**：这台手机此刻是否愿意执行某个能力（Gate 2 工作包 D 第 5 步）。
 *
 * 与"白名单"是两件事，刻意分开：
 *  * 白名单（[CapabilityDispatcher] 的注册表）是**编译期**的，"这个能力在这个版本里存在"；
 *  * 策略是**运行期**的，"用户此刻允许不允许用"。用户关掉总开关之后，
 *    能力仍然注册着（协议上必须认得这个名字，才能回一个明确的 `not_permitted`），
 *    但一条都不会被执行。
 *
 * 做成 `fun interface` 是为了让它能直接由一处用户设置（`HubPrefs.capabilitiesEnabled()`）
 * 以 lambda 的形式提供，而不必再引一层适配类。
 */
fun interface CapabilityPolicy {

    /** @return 是否允许执行 [toolName]。不允许时分发器回 `not_permitted`。 */
    fun isPermitted(toolName: String): Boolean

    companion object {
        /** 默认策略：允许（与"默认空注册表"配合，等价于没有任何能力可用）。 */
        val AllowAll: CapabilityPolicy = CapabilityPolicy { true }
    }
}

/**
 * 能力分发器：把一条 `tool_request` 变成一条 `tool_result`。
 *
 * 这是手机侧**唯一**一条"外部请求 → 本地能力"的路径。协议里刻意没有
 * shell / intent / reflection / file_path / url 这些字段，也不存在"按名字反射调用"
 * 的兜底分支 —— 能被执行的东西**只有**构造时注册进 [registry] 的那些。
 *
 * 校验顺序是刻意的（先便宜的、先与具体工具无关的）：
 *  1. **去重**：同一个 request_id 只执行一次，且重复帧不会被执行第二次；
 *  2. **工具名形状**：不合法 → `unsupported_tool`（不回显原文）；
 *  3. **注册表命中**：不在白名单 → `unsupported_tool`；
 *  4. **本地授权策略**：[policy] 说不允许 → `not_permitted`（能力**不执行**）；
 *  5. **多余参数**：出现白名单外的键 → `invalid_arguments`；
 *  6. **参数取值**：[Capability.validate] → `invalid_arguments`；
 *  7. **执行**：抛异常 → `tool_failed`（只记异常类名）。
 *
 * 纯 Kotlin（[clock] 可注入），可在 JVM 单测里完整跑。
 */
class CapabilityDispatcher(
    capabilities: List<Capability>,
    private val clock: () -> Long = System::currentTimeMillis,
    capacity: Int = TOOL_DEDUP_CAPACITY,
    /** 单条结果的字节上限；超了就在本地失败，而不是发出去让服务端拒。 */
    private val maxResultBytes: Int = 16 * 1024,
    /** 运行期授权策略；默认允许。见 [CapabilityPolicy]。 */
    private val policy: CapabilityPolicy = CapabilityPolicy.AllowAll,
) {

    /**
     * 显式白名单。构造时一次性建立，**没有**运行期注册/注销接口 ——
     * 能执行的能力在编译期就定下来了。
     */
    private val registry: Map<String, Capability> = capabilities.associateBy { it.name }

    private val dedup = RequestIdLru(capacity)

    private val lock = Any()

    init {
        require(capabilities.size == registry.size) { "能力名重复：注册表里出现了同名能力" }
        require(registry.keys.all { TOOL_NAME_PATTERN.matches(it) }) { "能力名必须匹配 $TOOL_NAME_PATTERN" }
    }

    /** 注册的能力数量（单测用）。 */
    val size: Int get() = registry.size

    /** 注册的能力名（有序，便于断言与界面展示）。 */
    fun names(): List<String> = registry.keys.sorted()

    /** 清空去重窗口（用户主动发起新会话时调用，与 `PingResponder.reset()` 对称）。 */
    fun reset() {
        synchronized(lock) { dedup.clear() }
    }

    /** 已记录的 request_id 条数（单测用）。 */
    fun seenCount(): Int = synchronized(lock) { dedup.size() }

    fun handle(requestId: String, toolName: String, arguments: JSONObject): ToolDispatchOutcome =
        synchronized(lock) {
            val startedAt = clock()

            // 1. 去重：重复的 request_id 一律不再执行（R2 的同款语义）。
            if (!dedup.firstSeen(requestId)) {
                return@synchronized ToolDispatchOutcome(
                    requestId = requestId,
                    toolName = normalizeName(toolName),
                    ok = false,
                    errorCode = ToolResultErrorCode.TOOL_FAILED,
                    elapsedMs = 0L,
                    duplicate = true,
                    frame = null,
                )
            }

            // 2/3. 工具名：形状不合法或不在白名单，都收敛成 unsupported_tool。
            //      注意**不**回显对端给的名字（它可能是一段被塞进来的任意文本）。
            val capability = if (TOOL_NAME_PATTERN.matches(toolName)) registry[toolName] else null
            if (capability == null) {
                return@synchronized failure(
                    requestId = requestId,
                    toolName = UNKNOWN_TOOL_LABEL,
                    code = ToolResultErrorCode.UNSUPPORTED_TOOL,
                    message = if (registry.isEmpty()) {
                        "这台手机当前没有启用任何能力"
                    } else {
                        // 只列**我们自己的**名字，绝不拼接对端给的字符串。
                        "这台手机不支持该工具，已知能力：${names().joinToString(", ")}"
                    },
                    startedAt = startedAt,
                )
            }

            // 4. 本地授权策略：用户关掉总开关（或将来按能力分别关）时，
            //    能力**不执行**，回一个明确的 not_permitted。
            //    排在参数校验之前是刻意的：不允许执行的能力不该因为
            //    "参数不合法"而漏出任何关于它自己的信息。
            val permitted = try {
                policy.isPermitted(capability.name)
            } catch (t: Throwable) {
                // 策略本身出错时**拒绝**，不默认放行。
                false
            }
            if (!permitted) {
                return@synchronized failure(
                    requestId = requestId,
                    toolName = capability.name,
                    code = ToolResultErrorCode.NOT_PERMITTED,
                    message = "本机已关闭只读能力调用（用户设置）",
                    startedAt = startedAt,
                )
            }

            // 5. 多余参数：出现白名单外的键就拒绝。
            //    message 里只说"多了几个"，不回显那些键名（同样是对端文本）。
            val extraKeys = arguments.keys().asSequence().filter { it !in capability.allowedArguments }.toList()
            if (extraKeys.isNotEmpty()) {
                return@synchronized failure(
                    requestId = requestId,
                    toolName = capability.name,
                    code = ToolResultErrorCode.INVALID_ARGUMENTS,
                    message = "参数不合法：多出 ${extraKeys.size} 个不支持的字段" +
                        allowedSuffix(capability),
                    startedAt = startedAt,
                )
            }

            // 6. 参数取值校验。
            val problem = try {
                capability.validate(arguments)
            } catch (t: Throwable) {
                "参数校验失败（${t.javaClass.simpleName}）"
            }
            if (problem != null) {
                return@synchronized failure(
                    requestId = requestId,
                    toolName = capability.name,
                    code = ToolResultErrorCode.INVALID_ARGUMENTS,
                    message = problem,
                    startedAt = startedAt,
                )
            }

            // 7. 执行。任何异常都变成 tool_failed，**只带异常类名**。
            val result = try {
                capability.execute(arguments)
            } catch (t: Throwable) {
                CapabilityResult.Failure(
                    code = ToolResultErrorCode.TOOL_FAILED,
                    message = "工具执行失败（${t.javaClass.simpleName}）",
                )
            }

            when (result) {
                is CapabilityResult.Success -> {
                    val payload = stripReservedKeys(result.payload)
                    val frame = encodeToolResult(requestId = requestId, ok = true, result = payload)
                    if (frame.toByteArray(Charsets.UTF_8).size > maxResultBytes) {
                        failure(
                            requestId = requestId,
                            toolName = capability.name,
                            code = ToolResultErrorCode.TOOL_FAILED,
                            message = "结果过大，已被本机丢弃",
                            startedAt = startedAt,
                        )
                    } else {
                        ToolDispatchOutcome(
                            requestId = requestId,
                            toolName = capability.name,
                            ok = true,
                            errorCode = null,
                            elapsedMs = clock() - startedAt,
                            duplicate = false,
                            frame = frame,
                        )
                    }
                }

                is CapabilityResult.Failure -> {
                    // 能力自己回的错误：同样在本地收敛一遍 code。
                    val code = if (result.code in ToolResultErrorCode.ALL) {
                        result.code
                    } else {
                        ToolResultErrorCode.TOOL_FAILED
                    }
                    failure(
                        requestId = requestId,
                        toolName = capability.name,
                        code = code,
                        message = result.message,
                        startedAt = startedAt,
                    )
                }
            }
        }

    private fun failure(
        requestId: String,
        toolName: String,
        code: String,
        message: String,
        startedAt: Long,
    ): ToolDispatchOutcome = ToolDispatchOutcome(
        requestId = requestId,
        toolName = toolName,
        ok = false,
        errorCode = code,
        elapsedMs = clock() - startedAt,
        duplicate = false,
        frame = encodeToolResult(
            requestId = requestId,
            ok = false,
            errorCode = code,
            errorMessage = message,
        ),
    )

    private fun allowedSuffix(capability: Capability): String =
        if (capability.allowedArguments.isEmpty()) {
            "（该工具不接受任何参数）"
        } else {
            "（允许的参数：${capability.allowedArguments.sorted().joinToString(", ")}）"
        }

    private fun normalizeName(toolName: String): String =
        if (TOOL_NAME_PATTERN.matches(toolName)) toolName else UNKNOWN_TOOL_LABEL

    /**
     * 剔除信封保留键，**并且**把 payload 深拷一份。
     *
     * 深拷不是洁癖：能力可能把自己缓存的 [JSONObject] 直接返回，
     * 后续对它的修改会悄悄改变一帧已经被发出去的内容。
     */
    private fun stripReservedKeys(payload: JSONObject): JSONObject {
        val clean = JSONObject()
        for (key in payload.keys()) {
            if (key in RESERVED_RESULT_KEYS) continue
            clean.put(key, payload.get(key))
        }
        return clean
    }
}
