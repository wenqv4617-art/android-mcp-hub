package com.androidmcphub.app.capability

import com.androidmcphub.app.protocol.RequestIdLru
import com.androidmcphub.app.protocol.ToolResultErrorCode
import com.androidmcphub.app.protocol.encodeToolResult
import org.json.JSONObject
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

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

    /**
     * 这个能力的元数据（说明、分类、参数表、权限声明）。
     *
     * 它是**展示与契约**用的，不是执行依据 —— 能执行什么只由注册表决定。
     * `metadata.name` 必须与 [name] 相等，构造 [CapabilityDispatcher] 时会强制检查；
     * 这样"界面上显示的工具"与"真正能执行的工具"就不可能各说各话。
     */
    val metadata: CapabilityMetadata

    /** 允许出现的参数名。这是**显式白名单**：多出任何一个键都会被拒绝。 */
    val allowedArguments: Set<String>

    /**
     * 参数取值校验。[arguments] 只会包含 [allowedArguments] 里的键。
     *
     * @return 拒绝理由（我们自己的文案）；null 表示通过。
     */
    fun validate(arguments: JSONObject): String? = null

    /**
     * 高影响操作的**明确确认**检查（删除/覆盖已有数据）。
     *
     * 返回非 null 时分发器回 `confirmation_required`，并且**不执行**这个能力。
     * 默认 null = 不需要确认。约定：实现方要求调用方在参数里传 `confirm=true`
     * （由各能力的 [allowedArguments] 显式列入白名单）。
     *
     * 这不能替代用户在设置里逐工具授权：确认只是让"这一次"有明确意图，
     * 工具本身仍必须先被用户打开。
     */
    fun confirmationProblem(arguments: JSONObject): String? = null

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
    /** 要回发的 `tool_result` 帧；[duplicate] 或 [deferred] 时为 null。 */
    val frame: String?,
    /**
     * 已受理，结果稍后**异步**交付（见 [CapabilityDispatcher.deferredSink]）。
     *
     * 只有聚合命令会走这条：第三方工具是网络 I/O，不能在 WebSocket 读线程上执行
     * （那会把 `phone_ping` 一起堵住 —— 实测的并发超时就是这么来的）。
     * [deferred] 为 true 时调用方**不要**回帧，也不要把它当成失败：
     * 一次真正到达的结果会以另一条独立的 outcome 交回来。
     */
    val deferred: Boolean = false,
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
    /**
     * Gate 3：聚合命令（第三方 MCP 工具）的路由器。
     *
     * 默认 `null` = **这台设备上不存在第三方工具这条路**，行为与 Gate 2.5 完全一致
     * （老测试一条都不用改，也证明这条分支是纯增量的）。
     * 传入实现之后，`hub_list_tools` / `hub_get_tool_schema` / `hub_call_tool`
     * 这几个**保留命令名**会在下面的第 2 步被它接走，第三方工具名则继续走原来的
     * 白名单路径 —— 永远不可能借道绕过本机白名单。
     */
    private val hubCommands: HubCommandRouter? = null,
    /**
     * 执行聚合命令的执行器（V1 性能专项 5.5）。
     *
     * `null`（默认）意味着**同步执行**，这也是所有既有测试的语义。生产接线时传一个
     * 小线程池，让第三方网络 I/O 离开 `WebSocket` 读线程：读线程被一次目录刷新占用
     * 期间，连 `phone_ping` 的帧都读不出来，服务端看到的就是"整条链路卡死"。
     *
     * 只有[聚合命令][HubCommandRouter]会被丢到这里；本机能力（电池、网络等）全是
     * 本地同步调用，走执行器只会白添一层调度延迟。
     */
    private val hubExecutor: Executor? = null,
) {

    /**
     * 异步结果的落地口（只在 [hubExecutor] 非 null 时使用）。
     *
     * 由持有连接的一侧（`PhoneLinkClient`）设置：它在**自己的调度线程**上把帧发出去，
     * 而不是在刷新的工作线程上直接碰 socket。没设置时退回同步执行 ——
     * 结果宁可慢一点，也不能因为"没人接"而丢掉。
     */
    @Volatile
    var deferredSink: ((ToolDispatchOutcome) -> Unit)? = null

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
        require(registry.values.all { it.metadata.name == it.name }) {
            "能力元数据的 name 必须与 Capability.name 一致（否则界面会显示一个不存在的工具）"
        }
    }

    /** 注册的能力数量（单测用）。 */
    val size: Int get() = registry.size

    /** 注册的能力名（有序，便于断言与界面展示）。 */
    fun names(): List<String> = registry.keys.sorted()

    /**
     * 注册表里每个能力的**元数据**（按工具名有序）。
     *
     * UI 的工具目录与"Worker / Android / 目录三者名称一致"的契约测试都读它，
     * 数据源只有一个：构造时注册进来的能力对象。
     */
    fun catalog(): List<CapabilityMetadata> = registry.values.sortedBy { it.name }.map { it.metadata }

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

            // 1.5. Gate 3：**保留命令**在进白名单之前被接走。
            //      位置是刻意的：去重先做（聚合命令同样不许重放），
            //      而**白名单查找之后**再处理就晚了 —— 那几个名字不在注册表里，
            //      会被直接判成 unsupported_tool。
            //      注意这里只认路由器自己声明的保留名；第三方工具的真实名字
            //      一个都进不来（见 [HubCommandRouter.handles] 的契约）。
            val hub = hubCommands?.takeIf { it.handles(toolName) }
            if (hub != null) {
                // 去重是**同步**做完的（就在上面）：同一个 request_id 并发到达两次时，
                // 第二次必须在第一次受理的那一刻就判重，而不是等结果回来再判 ——
                // 否则"异步"就等于把重放窗口打开。
                val executor = hubExecutor
                val sink = deferredSink
                if (executor != null && sink != null) {
                    val task = Runnable {
                        val outcome = runHub(hub, requestId, toolName, arguments, startedAt)
                        // sink 自己承诺不抛；再包一层是因为它的实现在连接生命周期里，
                        // 一个异常不该让刷新线程静默退出。
                        runCatching { sink(outcome) }
                    }
                    return@synchronized try {
                        executor.execute(task)
                        ToolDispatchOutcome(
                            requestId = requestId,
                            toolName = toolName,
                            ok = false,
                            errorCode = null,
                            elapsedMs = clock() - startedAt,
                            duplicate = false,
                            frame = null,
                            deferred = true,
                        )
                    } catch (_: RejectedExecutionException) {
                        // 执行器已关闭（服务正在销毁）：就地把这一条跑完。
                        // 用"不回复"来换"不阻塞读线程"在这里是不划算的 ——
                        // 服务端会一直等到超时，而用户看到的是"手机没反应"。
                        runHub(hub, requestId, toolName, arguments, startedAt)
                    }
                }
                return@synchronized runHub(hub, requestId, toolName, arguments, startedAt)
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
                    message = "本机已关闭该能力的调用（用户设置）",
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

            // 5.5 高影响操作的明确确认。排在参数取值校验之前：调用方连"要删东西"
            //     这件事都还没确认，不该先因为别的参数细节拿到一个无关的错误。
            val confirmation = try {
                capability.confirmationProblem(arguments)
            } catch (t: Throwable) {
                "确认检查失败（${t.javaClass.simpleName}）"
            }
            if (confirmation != null) {
                return@synchronized failure(
                    requestId = requestId,
                    toolName = capability.name,
                    code = ToolResultErrorCode.CONFIRMATION_REQUIRED,
                    message = confirmation,
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

            settle(
                requestId = requestId,
                toolName = capability.name,
                result = result,
                startedAt = startedAt,
            )
        }

    /**
     * 跑一条聚合命令并收口成 outcome。
     *
     * **不持锁**：这是本类唯一会离开内部锁的路径，因为它必然包含网络 I/O。
     * 它读写的东西都是局部变量或线程安全的存储（路由器自己有锁），
     * 所以不需要也不应该把 [lock] 带过去。
     */
    private fun runHub(
        hub: HubCommandRouter,
        requestId: String,
        toolName: String,
        arguments: JSONObject,
        startedAt: Long,
    ): ToolDispatchOutcome {
        val hubResult = try {
            hub.routeWithId(requestId, toolName, arguments)
        } catch (t: Throwable) {
            // 兜底：路由器自己承诺不抛，但远端数据不可信，这里不赌它守约。
            CapabilityResult.Failure(
                code = ToolResultErrorCode.TOOL_FAILED,
                message = "第三方工具调用失败（${t.javaClass.simpleName}）",
            )
        }
        // 保留命令名是我们自己的常量，回显它是安全的（它已经过 handles 判定）。
        return settle(
            requestId = requestId,
            toolName = toolName,
            result = hubResult,
            startedAt = startedAt,
        )
    }

    /** 释放聚合路由器（线程池、目录缓存）。幂等；本机能力没有任何需要释放的东西。 */
    fun close() {
        hubCommands?.close()
    }

    /**
     * 把一次执行的结论变成 [ToolDispatchOutcome]（含要回发的帧）。
     *
     * Gate 3 抽出这个函数，是为了让**本机能力**与**聚合命令**走同一段收口逻辑：
     * 剔除信封保留键、结果字节上限、错误码收敛到闭集 —— 三条都一样。
     * 两段各写一遍的话，迟早只有一边被修。
     */
    private fun settle(
        requestId: String,
        toolName: String,
        result: CapabilityResult,
        startedAt: Long,
    ): ToolDispatchOutcome =
        when (result) {
            is CapabilityResult.Success -> {
                val payload = stripReservedKeys(result.payload)
                val frame = encodeToolResult(requestId = requestId, ok = true, result = payload)
                if (frame.toByteArray(Charsets.UTF_8).size > maxResultBytes) {
                    failure(
                        requestId = requestId,
                        toolName = toolName,
                        code = ToolResultErrorCode.TOOL_FAILED,
                        message = "结果过大，已被本机丢弃",
                        startedAt = startedAt,
                    )
                } else {
                    ToolDispatchOutcome(
                        requestId = requestId,
                        toolName = toolName,
                        ok = true,
                        errorCode = null,
                        elapsedMs = clock() - startedAt,
                        duplicate = false,
                        frame = frame,
                    )
                }
            }

            is CapabilityResult.Failure -> {
                // 能力/路由器自己回的错误：同样在本地收敛一遍 code。
                val code = if (result.code in ToolResultErrorCode.ALL) {
                    result.code
                } else {
                    ToolResultErrorCode.TOOL_FAILED
                }
                failure(
                    requestId = requestId,
                    toolName = toolName,
                    code = code,
                    message = result.message,
                    startedAt = startedAt,
                )
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
