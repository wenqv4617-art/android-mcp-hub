package com.androidmcphub.app.protocol

import kotlin.math.max

/**
 * 有界 LRU 的 request_id 集合（R2：同一个 request_id 只能回一次）。
 *
 * [LinkedHashMap] 用 `accessOrder = true` 维护访问顺序：
 * `keys.first()` 就是最久未使用的那个。注意 `containsKey` 不会改变访问顺序，
 * 所以「命中」时要显式重新 `put` 一次把它顶到队尾。
 *
 * 纯逻辑，可 JVM 单测。
 */
class RequestIdLru(private val capacity: Int = PING_DEDUP_CAPACITY) {

    private val map = LinkedHashMap<String, Unit>(64, 0.75f, true)

    /**
     * 记录一个 request_id。
     * @return true 表示**第一次**见到（需要回复）；false 表示重复（不再回复）。
     */
    @Synchronized
    fun firstSeen(requestId: String): Boolean {
        if (map.containsKey(requestId)) {
            map[requestId] = Unit // 命中也要刷新 LRU 顺序
            return false
        }
        map[requestId] = Unit
        while (map.size > capacity) {
            val eldest = map.keys.firstOrNull() ?: break
            map.remove(eldest)
        }
        return true
    }

    /** 当前记录条数（用于单测验证容量上限）。 */
    @Synchronized
    fun size(): Int = map.size

    @Synchronized
    fun clear() {
        map.clear()
    }
}

/**
 * 一次 phone_ping 的处理结果。
 */
data class PingOutcome(
    val requestId: String,
    /** 服务端在这个 ping 里带的时间戳（服务端 epoch 毫秒）。 */
    val serverTimestampMs: Long,
    /** 本机处理该 ping 的时刻。 */
    val receivedAtMs: Long,
    /** 单程延迟估计 = 本机收到时刻 − 服务端发送时刻（要求两端时钟已由 NTP 同步）。 */
    val oneWayMs: Long,
    /** RTT 估计 = 单程 × 2（假设上下行对称）。 */
    val rttMs: Long,
    /** 要回发的 phone_pong 帧；**null 表示重复 request_id，按 R2 不回复**。 */
    val frame: String?,
) {
    val duplicate: Boolean get() = frame == null
}

/**
 * phone_ping 的应答器：去重 + 生成 pong 帧。
 *
 * 纯 Kotlin（[clock] 可注入），无 Android 依赖，可 JVM 单测。
 */
class PingResponder(
    capacity: Int = PING_DEDUP_CAPACITY,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val dedup = RequestIdLru(capacity)

    /**
     * welcome 里 server_time 与本机时钟的差值（本机 − 服务端），仅用于日志诊断。
     *
     * 刻意**不**用它去修正 [PingOutcome.rttMs]：welcome 是单向消息，
     * 用它估出来的偏移量本身已经吸收了那一次的单程延迟，再拿去修正只会把
     * 真实延迟减没。所以 RTT 只依赖「两端时钟已同步」这一前提。
     */
    @Volatile
    var clockOffsetMs: Long = 0L
        private set

    /** 是否已经从 welcome 拿到过服务端时间参考。 */
    @Volatile
    var hasServerTimeReference: Boolean = false
        private set

    fun noteServerTime(serverTimeMs: Long, atMs: Long = clock()) {
        clockOffsetMs = atMs - serverTimeMs
        hasServerTimeReference = true
    }

    /** 处理一帧 phone_ping。 */
    fun handle(ping: IncomingFrame.Ping): PingOutcome {
        val now = clock()
        val first = dedup.firstSeen(ping.requestId)

        val oneWay = if (ping.timestamp <= 0L) 0L else max(0L, now - ping.timestamp)

        return PingOutcome(
            requestId = ping.requestId,
            serverTimestampMs = ping.timestamp,
            receivedAtMs = now,
            oneWayMs = oneWay,
            rttMs = oneWay * 2L,
            frame = if (first) encodePong(ping.requestId, now) else null,
        )
    }

    fun seenCount(): Int = dedup.size()

    fun reset() = dedup.clear()
}
