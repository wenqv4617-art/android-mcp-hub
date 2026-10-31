package com.androidmcphub.app.net

import com.androidmcphub.app.core.DiagReport

/**
 * 待上报诊断事件的有界队列（V1.6.3）。
 *
 * 为什么单独成类：这是"断网期间不丢关键证据"与"不能无限占内存"之间唯一的
 * 平衡点。把它从 [PhoneLinkClient] 里拆出来，容量上限与过期规则就能在 JVM 上
 * 直接测，不必真的去断一次网。
 *
 * 两条硬约束：
 *  - 容量有上限，超出时丢**最老的**并计数（旧事件已不能解释"刚刚"发生了什么）；
 *  - 超过 [maxAgeMs] 的事件不补发（补发陈旧事件只会污染合并时间线），同样计数。
 *
 * 线程安全：入队可能发生在 PhoneLink 的锁里，出队发生在调度器线程；
 * 发送本身**不**在锁内执行。
 */
class DiagReportQueue(
    private val limit: Int = DEFAULT_LIMIT,
    private val maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    init {
        require(limit > 0) { "limit 必须为正数" }
        require(maxAgeMs > 0L) { "maxAgeMs 必须为正数" }
    }

    private val items = ArrayDeque<DiagReport>()
    private val lock = Any()
    private var dropped: Int = 0

    /** 入队；超出上限时丢最老的并累加丢弃计数。 */
    fun enqueue(report: DiagReport) {
        synchronized(lock) {
            items.addLast(report)
            while (items.size > limit) {
                items.removeFirst()
                dropped += 1
            }
        }
    }

    /**
     * 按入队顺序逐条交给 [send]。
     *
     * [send] 返回 false 表示"现在发不出去"：该条放回队首并停止本轮，
     * 等下一次连上再试。返回 true 表示已发送或本地编码失败（重试无意义）。
     *
     * @return 本轮因过期而丢弃的条数（已计入 [droppedCount]）。
     */
    fun drain(send: (DiagReport) -> Boolean): Int {
        val now = clock()
        var expired = 0
        while (true) {
            val report = synchronized(lock) { items.removeFirstOrNull() } ?: break
            if (now - report.clientAtMs > maxAgeMs) {
                synchronized(lock) { dropped += 1 }
                expired += 1
                continue
            }
            if (!send(report)) {
                synchronized(lock) { items.addFirst(report) }
                break
            }
        }
        return expired
    }

    /** 清空队列并复位丢弃计数（用户断开 / 服务销毁时调用）。 */
    fun clear() {
        synchronized(lock) {
            items.clear()
            dropped = 0
        }
    }

    /** 排队中的事件数。 */
    val size: Int get() = synchronized(lock) { items.size }

    /** 因容量或过期而丢失的事件数。 */
    val droppedCount: Int get() = synchronized(lock) { dropped }

    companion object {
        /** 50 条足够覆盖一次断网重连的证据，又不会让长期离线攒出可观内存。 */
        const val DEFAULT_LIMIT: Int = 50

        /** 15 分钟前的连接事件已不能解释"刚刚"的断连。 */
        const val DEFAULT_MAX_AGE_MS: Long = 15 * 60 * 1000L
    }
}
