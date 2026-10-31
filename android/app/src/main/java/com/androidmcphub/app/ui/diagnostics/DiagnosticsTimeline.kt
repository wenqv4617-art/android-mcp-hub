package com.androidmcphub.app.ui.diagnostics

import com.androidmcphub.app.core.DiagClientEvent
import com.androidmcphub.app.core.LinkEvent
import com.androidmcphub.app.core.diagEventName
import com.androidmcphub.app.net.CloudDiagEvent
import com.androidmcphub.app.net.CloudDiagnostics
import com.androidmcphub.app.protocol.APP_VERSION
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 诊断页的日志来源。 */
enum class DiagTimelineSource(val label: String) {
    /** 只显示本机 Android 侧事件。 */
    LOCAL("本机"),

    /** 只显示 Cloudflare 云端事件。 */
    CLOUD("云端"),

    /** 双端合并，按校正后的时间轴排序。 */
    MERGED("合并"),
}

/** 事件来自哪一侧。 */
enum class TimelineOrigin(val label: String) {
    LOCAL("本机"),
    CLOUD("云端"),
}

/**
 * 合并时间线上的一行。
 *
 * 时间字段刻意保留三种：
 *  - [axisAtMs]：**统一到手机时间轴**的可比较时刻（云端事件已按本次查询的
 *    时钟差校正）——排序只用它；
 *  - [serverAtMs]：云端事件在**服务端时钟**上的原始时刻，照原样展示；
 *  - [reportedAtMs]：手机上报云端事件时自己的时刻（`client_at`）。
 *
 * 合并时间线不能只凭本地时间排先后：手机与服务端时钟不一致时，那样会把
 * "服务端先关"排成"手机先断"。
 */
data class TimelineEntry(
    val origin: TimelineOrigin,
    val id: String,
    val eventType: String,
    val title: String,
    val detailLines: List<String>,
    val axisAtMs: Long,
    val serverAtMs: Long? = null,
    val reportedAtMs: Long? = null,
    val generation: Long? = null,
    val closeCode: Int? = null,
    /** 与另一侧存在时间上接近的事件，但**无法证明**因果关系。 */
    val possibleRelated: Boolean = false,
)

/**
 * 合并时间线的纯逻辑：不依赖 Compose / Android，JVM 可直接测。
 *
 * 因果标记的边界（刻意保守）：只有在时间窗口内、且代次不冲突时，才标
 * 「可能相关」。从不输出「因为…所以…」的结论 —— 诊断页不能替用户编造因果。
 */
object DiagnosticsTimeline {

    /** 两侧事件落在同一窗口内才考虑标记相关性（毫秒）。 */
    const val RELATED_WINDOW_MS: Long = 15_000L

    /** 云端这一类事件通常是"连接结束"的证据。 */
    private val CLOUD_DISCONNECT_TYPES = setOf(
        "device_offline",
        "replaced",
        "liveness_timeout",
        "revoked",
        "auth_failure",
    )

    /** 本机这一侧与断连相关的证据类型。 */
    private val LOCAL_DISCONNECT_TYPES = setOf(
        DiagClientEvent.SOCKET_FAILURE,
        DiagClientEvent.SERVER_CLOSE,
        DiagClientEvent.HEARTBEAT_TIMEOUT,
        DiagClientEvent.HANDSHAKE_TIMEOUT,
        DiagClientEvent.AUTHENTICATION_FAILED,
        DiagClientEvent.NETWORK_CHANGED,
        DiagClientEvent.USER_DISCONNECTED,
    )

    /**
     * 生成时间线（最新在前），并应用来源与类型筛选。
     *
     * @param typeFilter 为 null 表示不筛选；匹配的是 [TimelineEntry.eventType]。
     */
    fun build(
        localEvents: List<LinkEvent>,
        cloud: CloudDiagnostics?,
        source: DiagTimelineSource,
        typeFilter: String? = null,
    ): List<TimelineEntry> {
        val merged = mutableListOf<TimelineEntry>()
        if (source != DiagTimelineSource.CLOUD) {
            merged += localEvents.map { localEntry(it, cloud) }
        }
        if (source != DiagTimelineSource.LOCAL && cloud != null) {
            merged += cloud.events.map { cloudEntry(it, cloud) }
        }

        markPossibleRelated(merged)

        return merged
            .asSequence()
            .filter { typeFilter == null || it.eventType == typeFilter }
            .sortedWith(
                compareByDescending<TimelineEntry> { it.axisAtMs }
                    // 同一时刻：本机直接观察到的排前面；它不是"更权威"，
                    // 只是同一毫秒内排序需要稳定规则。
                    .thenByDescending { it.origin == TimelineOrigin.LOCAL }
                    .thenByDescending { it.id },
            )
            .toList()
    }

    /** 当前时间线里出现过的类型（用于筛选器；保持固定顺序）。 */
    fun eventTypes(entries: List<TimelineEntry>): List<String> =
        entries.map { it.eventType }.distinct().sortedBy { typeOrder(it) }

    private fun localEntry(event: LinkEvent, cloud: CloudDiagnostics?): TimelineEntry {
        val type = diagEventName(event.kind)
        val lines = mutableListOf<String>()
        if (event.detail.isNotBlank()) lines += event.detail
        event.generation?.let { lines += "连接代次 $it" }
        event.closeCode?.let { code ->
            val label = cloud?.closeCodeLabel(code)
            lines += if (label != null) "关闭码 $code（$label）" else "关闭码 $code"
        }
        event.category?.let { lines += "失败类别 $it" }
        event.exceptionClass?.let { lines += "异常类型 $it" }
        event.source?.let { lines += "本地关闭来源 $it" }
        event.attempt?.let { lines += "重连尝试 第 $it 次" }
        event.heartbeatAgeMs?.let { lines += "静默 ${it}ms" }

        return TimelineEntry(
            origin = TimelineOrigin.LOCAL,
            id = "local-${event.atMs}-${event.kind.name}",
            eventType = type,
            title = event.kind.label,
            detailLines = lines,
            axisAtMs = event.atMs,
            generation = event.generation,
            closeCode = event.closeCode,
        )
    }

    private fun cloudEntry(event: CloudDiagEvent, cloud: CloudDiagnostics): TimelineEntry {
        val type = effectiveType(event)
        val lines = mutableListOf<String>()
        event.detail.ordered().forEach { (key, value) -> lines += "$key=$value" }
        event.closeReason?.let { lines += "关闭原因 $it（服务端标签）" }
        when (event.serverInitiated) {
            true -> lines += "是否服务端发起：是"
            false -> lines += "是否服务端发起：否"
            null -> lines += "是否服务端发起：无法确定"
        }
        event.durationMs?.let { lines += "连接存活 ${it}ms" }
        event.detail.clientGeneration?.let { lines += "手机上报代次 $it" }

        return TimelineEntry(
            origin = TimelineOrigin.CLOUD,
            id = "cloud-${event.seq}-${event.type}",
            eventType = type,
            title = cloudEventLabel(event),
            detailLines = lines,
            axisAtMs = cloud.serverToLocalAxis(event.atMs),
            serverAtMs = event.atMs,
            reportedAtMs = event.clientAtMs,
            generation = event.generation,
            closeCode = event.closeCode,
        )
    }

    /** `client_report` 的"真正事件类型"在 `detail.client_event` 里。 */
    fun effectiveType(event: CloudDiagEvent): String =
        if (event.type == "client_report") {
            event.detail.values["client_event"] ?: event.type
        } else {
            event.type
        }

    private fun cloudEventLabel(event: CloudDiagEvent): String {
        val type = effectiveType(event)
        val base = cloudTypeLabel(type)
        return if (event.type == "client_report") "$base（手机上报）" else base
    }

    private fun markPossibleRelated(entries: List<TimelineEntry>) {
        val locals = entries.filter { it.origin == TimelineOrigin.LOCAL }
        for (cloud in entries) {
            if (cloud.origin != TimelineOrigin.CLOUD) continue
            if (effectiveCloudType(cloud.eventType) !in CLOUD_DISCONNECT_TYPES) continue
            val match = locals.firstOrNull { local ->
                local.eventType in LOCAL_DISCONNECT_TYPES &&
                    kotlin.math.abs(local.axisAtMs - cloud.axisAtMs) <= RELATED_WINDOW_MS &&
                    generationsCompatible(local.generation, cloud.generation)
            } ?: continue
            replace(entries, cloud, cloud.copy(possibleRelated = true))
            replace(entries, match, match.copy(possibleRelated = true))
        }
    }

    /**
     * 代次不冲突才可能是同一条连接的事件。
     *
     * 两侧都缺代次时不能判定为"冲突"（那是未知，不是不可能）；但只要两侧都有
     * 且不同，就明确不是同一条连接 —— 宁可不标，也不能把两条连接的证据串起来。
     */
    private fun generationsCompatible(local: Long?, cloud: Long?): Boolean {
        if (local == null || cloud == null) return true
        return local == cloud
    }

    private fun effectiveCloudType(type: String): String = type

    private fun replace(entries: List<TimelineEntry>, old: TimelineEntry, new: TimelineEntry) {
        // entries 是可变的 ArrayList；按身份替换（TimelineEntry 是 data class，
        // 直接 indexOf 在有重复行时也可能命中，但 id 唯一，用 id 定位更稳）。
        @Suppress("UNCHECKED_CAST")
        val list = entries as MutableList<TimelineEntry>
        val index = list.indexOfFirst { it.id == old.id }
        if (index >= 0) list[index] = new
    }

    /** 事件类型 → 中文标签（筛选器与文案共用）。 */
    fun typeLabel(type: String): String = cloudTypeLabel(type)

    private fun cloudTypeLabel(type: String): String = when (type) {
        "device_online" -> "云端判定在线"
        "device_offline" -> "云端判定离线"
        "hello" -> "收到 hello"
        "replaced" -> "连接被新连接替换"
        "liveness_timeout" -> "服务端判活超时"
        "revoked" -> "凭据已撤销"
        "auth_failure" -> "认证被拒"
        "client_report" -> "手机上报"
        DiagClientEvent.CONNECTED -> "连接成功"
        DiagClientEvent.SERVER_CLOSE -> "服务端关闭"
        DiagClientEvent.SOCKET_FAILURE -> "连接失败"
        DiagClientEvent.HANDSHAKE_TIMEOUT -> "握手超时"
        DiagClientEvent.RECONNECTING -> "开始重连"
        DiagClientEvent.NETWORK_CHANGED -> "网络变化"
        DiagClientEvent.HEARTBEAT_TIMEOUT -> "心跳超时"
        DiagClientEvent.AUTHENTICATION_FAILED -> "认证失败"
        DiagClientEvent.USER_DISCONNECTED -> "用户断开"
        DiagClientEvent.SERVICE_DESTROYED -> "服务停止"
        DiagClientEvent.SERVICE_RESTARTED -> "服务恢复"
        else -> type
    }

    /** 筛选器排序：先断连类，再连接类，其余按名字。 */
    private fun typeOrder(type: String): Int = when (type) {
        "device_offline", "replaced", "liveness_timeout", "revoked", "auth_failure" -> 0
        DiagClientEvent.SERVER_CLOSE, DiagClientEvent.SOCKET_FAILURE,
        DiagClientEvent.HEARTBEAT_TIMEOUT, DiagClientEvent.HANDSHAKE_TIMEOUT,
        DiagClientEvent.AUTHENTICATION_FAILED, DiagClientEvent.USER_DISCONNECTED,
        -> 1
        "device_online", DiagClientEvent.CONNECTED -> 2
        else -> 3
    }

    /**
     * 生成**脱敏**文本，供复制/分享。
     *
     * 脱敏不是"把某些字段删掉"，而是"只写白名单字段"：本函数从不读取令牌、
     * 服务器地址、设备 ID、Authorization 或任何自由文本。导出内容与界面
     * 能看到的证据完全一致。
     */
    fun buildExport(
        entries: List<TimelineEntry>,
        cloud: CloudDiagnostics?,
        source: DiagTimelineSource,
        generatedAtMs: Long,
        maxEntries: Int = MAX_EXPORT_ENTRIES,
    ): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        val out = StringBuilder()
        out.appendLine("Android MCP Hub 连接诊断导出（已脱敏）")
        out.appendLine("生成时间: ${sdf.format(Date(generatedAtMs))}")
        out.appendLine("App 版本: $APP_VERSION")
        out.appendLine("显示来源: ${source.label}")
        out.appendLine("事件条数: ${entries.size}（最多导出 $maxEntries 条）")
        if (cloud != null) {
            out.appendLine(
                "云端状态: online=${cloud.online} socket_open=${cloud.socketOpen} " +
                    "stale=${cloud.stale} 代次=${cloud.connectionGeneration}",
            )
            out.appendLine(
                "时钟差（服务端-本机）: ${cloud.clockOffsetMs}ms（含一次单程网络延迟，" +
                    "合并时间线已按此校正）",
            )
            out.appendLine(
                "云端缓冲区: size=${cloud.buffer.size} returned=${cloud.buffer.returned} " +
                    "dropped=${cloud.buffer.dropped} max=${cloud.buffer.maxEvents}",
            )
        } else {
            out.appendLine("云端状态: 本次未获取（或获取失败）")
        }
        out.appendLine("---- 时间线（最新在前）----")
        val shown = entries.take(maxEntries.coerceAtLeast(1))
        for (entry in shown) {
            val time = sdf.format(Date(entry.axisAtMs))
            val origin = entry.origin.label
            val relation = if (entry.possibleRelated) " [可能相关]" else ""
            out.appendLine("[$origin] $time ${entry.title}$relation")
            if (entry.origin == TimelineOrigin.CLOUD && entry.serverAtMs != null) {
                out.appendLine("   服务端时间 ${sdf.format(Date(entry.serverAtMs))}")
            }
            entry.reportedAtMs?.let {
                out.appendLine("   手机上报时间 ${sdf.format(Date(it))}")
            }
            for (line in entry.detailLines) {
                out.appendLine("   $line")
            }
        }
        if (entries.size > shown.size) {
            out.appendLine("……其余 ${entries.size - shown.size} 条未导出")
        }
        return out.toString()
    }

    /** 单次导出上限：避免一个巨大的剪贴板内容把输入法/聊天应用卡住。 */
    const val MAX_EXPORT_ENTRIES: Int = 200
}
