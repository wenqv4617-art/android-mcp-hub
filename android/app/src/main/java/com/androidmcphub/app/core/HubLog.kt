package com.androidmcphub.app.core

/** 日志级别。 */
enum class LogLevel { INFO, WARN, ERROR }

/** 一条日志。 */
data class LogEntry(
    val timeMs: Long,
    val level: LogLevel,
    val text: String,
)

/**
 * 定长环形日志：最多保留 [capacity] 条，**最新的在 index 0**。
 *
 * 只存内存不落盘 —— 日志里可能有服务器地址之类的环境信息，
 * 没有任何理由把它写进磁盘。进程重启即清空。
 *
 * 纯 Kotlin（[clock] 可注入），无 Android 依赖，可 JVM 单测。
 */
class HubLog(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val lock = Any()
    private val buffer = ArrayDeque<LogEntry>()

    @Volatile
    private var listener: ((List<LogEntry>) -> Unit)? = null

    /** 注册快照回调；每次写入后用**新的不可变快照**回调一次。 */
    fun setListener(l: ((List<LogEntry>) -> Unit)?) {
        listener = l
    }

    fun info(text: String) = add(LogLevel.INFO, text)

    fun warn(text: String) = add(LogLevel.WARN, text)

    fun error(text: String) = add(LogLevel.ERROR, text)

    fun add(level: LogLevel, text: String) {
        val snapshot = synchronized(lock) {
            buffer.addFirst(LogEntry(clock(), level, text))
            while (buffer.size > capacity) {
                buffer.removeLast()
            }
            buffer.toList()
        }
        // 在锁外回调，避免回调实现里再查日志造成死锁
        listener?.invoke(snapshot)
    }

    /** 当前快照，最新在前。 */
    fun entries(): List<LogEntry> = synchronized(lock) { buffer.toList() }

    fun clear() {
        val empty = synchronized(lock) {
            buffer.clear()
            buffer.toList()
        }
        listener?.invoke(empty)
    }

    companion object {
        const val DEFAULT_CAPACITY = 200
    }
}
