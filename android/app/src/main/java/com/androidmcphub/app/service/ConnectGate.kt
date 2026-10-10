package com.androidmcphub.app.service

import androidx.annotation.StringRes
import com.androidmcphub.app.R

/**
 * 「进入前台服务」的结果。
 *
 * 之所以不用 `Boolean`：失败时**必须**带一个固定类别，好让日志与界面在不接触异常原文的
 * 前提下说清发生了什么。Gate 0.1 审计的 R2（日志里不得出现未经处理的异常字符串）
 * 与 R3（前台启动失败要终止连接流程）都要求这个类别是**闭集**。
 */
sealed interface ForegroundResult {

    /** 已经成功进入前台，可以继续建连。 */
    data object Started : ForegroundResult

    /**
     * 进入前台失败。
     *
     * [category] 一定取自 [ForegroundFailure]，是固定字符串，**不含**任何原始异常文本。
     */
    data class Failed(val category: String) : ForegroundResult
}

/**
 * 进入前台失败的原因类别。
 *
 * 写进日志的永远只有这里面的固定值 —— 不使用 `Throwable.message`，
 * 因为它可能夹带系统拼进来的包名、路径，甚至（在别的调用点）用户数据的原文。
 */
object ForegroundFailure {

    /** Android 12+ 的后台前台服务启动限制（`ForegroundServiceStartNotAllowedException`）。 */
    const val START_NOT_ALLOWED = "fgs_start_not_allowed"

    /** Android 14+ 要求声明具体前台服务类型，缺了会抛 `MissingForegroundServiceTypeException`。 */
    const val MISSING_TYPE = "fgs_missing_type"

    /** 权限不足（`SecurityException`）。 */
    const val SECURITY = "security_exception"

    /** 服务状态不对，例如在 `onCreate` 之前就被要求进入前台。 */
    const val ILLEGAL_STATE = "illegal_state"

    /** 其它 `RuntimeException`。 */
    const val RUNTIME = "runtime_exception"

    /** 认不出来的异常。 */
    const val UNKNOWN = "unknown_exception"

    /** 全部合法类别。任何写进日志的类别都必须在这个集合里。 */
    val ALL: Set<String> = setOf(START_NOT_ALLOWED, MISSING_TYPE, SECURITY, ILLEGAL_STATE, RUNTIME, UNKNOWN)

    /**
     * **只按异常的类名**分类。
     *
     * 参数是类名而不是 `Throwable`，有两个原因：
     *  1. 分类逻辑必须能被 JVM 单测覆盖 —— `ForegroundServiceStartNotAllowedException` 与
     *     `MissingForegroundServiceTypeException` 只在 API 31/34 的 android.jar 里存在，
     *     在 JVM 单测里造不出真实例（而且 `is` 判断在低版本设备上会去加载不存在的类）；
     *  2. 明确禁止读 `message`：这是"日志里不出现未经处理的异常字符串"最直接的落实方式。
     */
    fun classify(exceptionName: String): String = when (exceptionName) {
        "ForegroundServiceStartNotAllowedException" -> START_NOT_ALLOWED
        "MissingForegroundServiceTypeException" -> MISSING_TYPE
        "SecurityException" -> SECURITY
        "IllegalStateException" -> ILLEGAL_STATE
        "RuntimeException" -> RUNTIME
        else -> UNKNOWN
    }

    /** [classify] 的便利重载：只取类名，绝不触碰 message。 */
    fun classify(error: Throwable): String = classify(error.javaClass.simpleName)

    /**
     * 该类别的用户可读说明。
     *
     * 认不出的类别也必须有文案 —— 否则界面会显示空白，用户以为应用卡住了。
     */
    @StringRes
    fun userMessageRes(category: String): Int = when (category) {
        START_NOT_ALLOWED -> R.string.fgs_error_start_not_allowed
        MISSING_TYPE -> R.string.fgs_error_missing_type
        SECURITY -> R.string.fgs_error_security
        else -> R.string.fgs_error_generic
    }
}

/**
 * 「用户点了连接」之后、真正建连之前的准入判定。
 *
 * 审计 R3 指出的缺陷是：`goForeground()` 失败后代码**仍然继续** `link.start()`，
 * 于是应用会在一个系统已经拒绝的前台服务里偷偷建 WebSocket。把这一步抽成纯逻辑
 * （`object` + 纯函数，不碰 Android framework）之后，这条不变量就能被单测钉死：
 *
 *   > 前台服务没进入前台，**就绝不能**调用 `PhoneLinkClient.start()`。
 */
sealed interface ConnectDecision {

    /** 允许建连。 */
    data object Proceed : ConnectDecision

    /**
     * 终止本次启动流程。
     *
     * [category] 取自 [ForegroundFailure]，固定闭集。
     *
     * 注意这里**没有**「是否允许重连」这种字段：终止发生在其后任何一次连接尝试之前，
     * 允许重连就等于在同样的条件下反复失败，变成用户看不见的循环。
     * 唯一的恢复路径是用户回到前台再点一次「连接」。
     */
    data class Abort(val category: String) : ConnectDecision
}

/** 准入判定的唯一入口。 */
object ConnectGate {

    /**
     * 拿到前台启动结果后决定要不要建连。
     *
     * 这是 `PhoneLinkService.handleConnect()` 里唯一决定"是否调用 start()"的地方 ——
     * 把判断放在这里而不是散在 Service 里，是为了让它可被单测覆盖。
     */
    fun afterForeground(result: ForegroundResult): ConnectDecision = when (result) {
        ForegroundResult.Started -> ConnectDecision.Proceed
        is ForegroundResult.Failed -> ConnectDecision.Abort(result.category)
    }
}

/**
 * 「进入前台 → 是否建连」这段**时序**本身。
 *
 * 光有 [ConnectGate] 还不够：R3 的缺陷不是"判断错了"，而是**判断根本没做** ——
 * `goForeground()` 的失败被吞掉之后代码继续往下走。所以真正要钉住的是
 * 「[startLink] 只在 [ForegroundResult.Started] 之后才可能被调用」这条顺序约束。
 *
 * 把三个动作作为构造参数注入，Service 侧就只剩一句 `run()`，
 * 而顺序约束可以在 JVM 单测里用假的动作实现**按调用次序**验证
 * （不需要 Robolectric，也不需要真机）。
 */
class ConnectStartupSequence(
    private val enterForeground: () -> ForegroundResult,
    private val startLink: () -> Unit,
    private val onAbort: (String) -> Unit,
) {

    /**
     * 执行一次启动流程。
     *
     * @return 是否真的启动了连接。`false` 表示已经走 [onAbort] 收尾，
     *   调用方不得再补一次建连。
     */
    fun run(): Boolean = when (val decision = ConnectGate.afterForeground(enterForeground())) {
        ConnectDecision.Proceed -> {
            startLink()
            true
        }

        is ConnectDecision.Abort -> {
            onAbort(decision.category)
            false
        }
    }
}
