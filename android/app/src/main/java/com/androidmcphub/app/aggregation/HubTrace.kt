package com.androidmcphub.app.aggregation

/**
 * 聚合层的**分阶段耗时**记录（V1 目录发现性能专项 4.1）。
 *
 * 它要回答的问题只有一个：一次 `hub_list_tools` 的 13 秒到底花在哪一段上。
 * 所以每一段都记两个数：
 *  - `at_ms`：从请求进入路由器算起的**偏移**（便于把一条请求的时间线按顺序读）；
 *  - `dur_ms`：从上一个阶段到这一个阶段的**耗时**（真正的"谁慢"在这里）。
 *
 * 三条纪律（与 `LogRedactionTest` 的静态扫描一致）：
 *  1. **单调时钟**：用 [now]（生产是 [System.nanoTime] 派生的毫秒），不受系统改时间影响；
 *  2. **只用已有的 request_id 关联**，不新造关联 ID，也不记录参数值、请求头、
 *     令牌、URL、工具描述 —— 那些要么是凭据，要么是第三方不可信文本；
 *  3. **有界**：每个请求最多几十行，且只在聚合命令这条路上产生。
 *
 * 这不是指标系统，是一份"能贴进排查记录"的证词：它必须能被关掉（[emit] 传空），
 * 也不能影响任何判定（所有 stage 调用都不返回值、不抛异常）。
 *
 * @param requestId `tool_request` 里服务端给的 request_id（原样记录，它可以安全地出现在日志里）。
 * @param now 单调时钟（毫秒）。
 * @param emit 一行日志的落地函数。
 */
class HubTrace(
    private val requestId: String,
    private val now: () -> Long,
    private val emit: (String) -> Unit,
) {

    private val startedAtMs = now()
    private var lastMarkMs = startedAtMs

    /** 紧凑摘要用：`stage=累计耗时` 的有序列表。 */
    private val summary = mutableListOf<Pair<String, Long>>()

    /** 记录一个阶段。永不抛异常：诊断不能把一次调用搞失败。 */
    fun stage(name: String, serverId: String? = null, detail: String? = null) {
        try {
            val at = now()
            val offset = at - startedAtMs
            val duration = at - lastMarkMs
            lastMarkMs = at
            summary += name to offset
            val line = buildString {
                append("hub.trace request_id=").append(requestId)
                append(" stage=").append(name)
                if (serverId != null) append(" server_id=").append(serverId)
                append(" at_ms=").append(offset)
                append(" dur_ms=").append(duration)
                if (detail != null) append(' ').append(detail)
            }
            emit(line)
        } catch (_: Throwable) {
            // 诊断路径不允许反过来影响主流程
        }
    }

    /** 收尾：一行给出整条时间线，方便直接对照服务端看到的 RTT。 */
    fun finish(outcome: String, extra: String? = null) {
        try {
            val total = now() - startedAtMs
            val line = buildString {
                append("hub.trace request_id=").append(requestId)
                append(" stage=done outcome=").append(outcome)
                append(" total_ms=").append(total)
                append(" stages=")
                append(summary.joinToString(",") { "${it.first}:${it.second}" })
                if (extra != null) append(' ').append(extra)
            }
            emit(line)
        } catch (_: Throwable) {
            // 同上
        }
    }

    /** 本次请求已经过去的毫秒数（单调）。 */
    fun elapsedMs(): Long = now() - startedAtMs
}
