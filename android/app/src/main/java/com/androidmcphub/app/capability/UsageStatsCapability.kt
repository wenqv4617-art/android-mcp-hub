package com.androidmcphub.app.capability

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Process
import com.androidmcphub.app.protocol.ToolResultErrorCode
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

/** 一个应用在统计区间内的前台使用量（原始值，尚未解释）。 */
data class AppUsage(
    val packageName: String,
    /** 应用名；读不到时为 null（不拿包名冒充）。 */
    val label: String?,
    val foregroundMs: Long,
    val lastUsedMs: Long?,
    val isSystemApp: Boolean,
)

/** 使用情况聚合结果。 */
data class UsageSnapshot(
    val apps: List<AppUsage>,
    /** 实际查询区间，回给调用方便于理解"这个数字覆盖的是哪一段"。 */
    val startMs: Long,
    val endMs: Long,
)

/** 左闭右开的时间区间（毫秒）。 */
data class UsageRange(val startMs: Long, val endMs: Long)

/**
 * 使用情况来源。
 *
 * [hasAccess] 与 [query] 分开：前者是"用户有没有去系统设置里授权"，
 * 后者才是真正读数据。两者混在一起就没有办法给出可操作的错误
 * （"去设置打开使用情况访问" 与 "读数据失败" 是两种完全不同的下一步）。
 */
interface UsageStatsSource {
    fun hasAccess(): Boolean
    fun query(startMs: Long, endMs: Long): UsageSnapshot
}

/**
 * 真机实现：`UsageStatsManager.queryAndAggregateUsageStats`。
 *
 * 为什么不用 `queryUsageStats` 后自己按天求和：那会把跨天的同一段会话
 * 在每一天各算一次（至少部分厂商 ROM 上是这样），用户看到的"7 天总时长"
 * 可能比真实值大很多。`queryAndAggregateUsageStats` 由系统负责跨区间合并。
 *
 * 权限：`PACKAGE_USAGE_STATS` 是**特殊权限**（AppOps），不能在运行时弹窗申请，
 * 只能跳系统设置让用户手动开。没开时系统返回空列表（而不是抛异常），
 * 所以必须先查 AppOps，否则会把"没授权"谎报成"今天没使用任何应用"。
 */
class AndroidUsageStatsSource(context: Context) : UsageStatsSource {

    private val appContext = context.applicationContext
    private val packageManager: PackageManager = appContext.packageManager

    override fun hasAccess(): Boolean {
        val appOps = appContext.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        return try {
            val mode = appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                appContext.packageName,
            )
            mode == AppOpsManager.MODE_ALLOWED
        } catch (_: Throwable) {
            false
        }
    }

    override fun query(startMs: Long, endMs: Long): UsageSnapshot {
        val manager = appContext.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return UsageSnapshot(emptyList(), startMs, endMs)
        val aggregated = manager.queryAndAggregateUsageStats(startMs, endMs) ?: emptyMap()
        val apps = aggregated.values.mapNotNull { stat ->
            val packageName = stat.packageName ?: return@mapNotNull null
            val foreground = stat.totalTimeInForeground
            if (foreground <= 0) return@mapNotNull null
            AppUsage(
                packageName = packageName,
                label = labelOf(packageName),
                foregroundMs = foreground,
                lastUsedMs = stat.lastTimeUsed.takeIf { it > 0 },
                isSystemApp = isSystemApp(packageName),
            )
        }
        return UsageSnapshot(apps, startMs, endMs)
    }

    private fun labelOf(packageName: String): String? = try {
        val info = packageManager.getApplicationInfo(packageName, 0)
        packageManager.getApplicationLabel(info).toString().ifBlank { null }
    } catch (_: Throwable) {
        null
    }

    private fun isSystemApp(packageName: String): Boolean = try {
        val info = packageManager.getApplicationInfo(packageName, 0)
        (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
            (info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
    } catch (_: Throwable) {
        false
    }
}

/**
 * Phase 3A：`device_get_usage_stats`。
 *
 * 只读；不写任何数据；不读取应用内容，只读系统提供的"前台时长"聚合值。
 * 需要用户到系统设置里授予「使用情况访问」特殊权限（AppOps）。
 *
 * 输出刻意**分区间聚合**而不是给原始事件流：原始事件包含每次切前台/后台的
 * 精确时间戳，推断出的作息比"每个应用用了多久"敏感得多，而这个工具的目的
 * 只是回答"今天用了哪些应用、多久"。
 */
class UsageStatsCapability(
    private val source: UsageStatsSource,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) : Capability {

    override val name: String = NAME

    override val metadata: CapabilityMetadata = METADATA

    override val allowedArguments: Set<String> = setOf("range", "limit", "include_system_apps")

    override fun validate(arguments: JSONObject): String? {
        val parsed = validated(arguments) { parse(arguments) }
        return parsed.second
    }

    override fun execute(arguments: JSONObject): CapabilityResult {
        val query = try {
            parse(arguments)
        } catch (problem: ArgumentProblem) {
            return CapabilityResult.Failure(ToolResultErrorCode.INVALID_ARGUMENTS, problem.message ?: "参数不合法")
        }

        if (!source.hasAccess()) {
            return CapabilityResult.Failure(
                code = ToolResultErrorCode.PERMISSION_DENIED,
                message = "未授予「使用情况访问」权限。请到 系统设置 → 应用 → 特殊应用权限 → 使用情况访问，" +
                    "找到 Android MCP Hub 并允许。授权后无需重连即可再次调用。",
            )
        }

        val now = clock()
        val range = rangeOf(query.range, now, zone())
        val snapshot = try {
            source.query(range.startMs, range.endMs)
        } catch (t: Throwable) {
            return CapabilityResult.Failure(
                ToolResultErrorCode.TOOL_FAILED,
                "读取使用情况失败（${t.javaClass.simpleName}）",
            )
        }

        val filtered = snapshot.apps
            .filter { query.includeSystemApps || !it.isSystemApp }
            .sortedByDescending { it.foregroundMs }
        val totalMs = filtered.sumOf { it.foregroundMs }
        val page = filtered.take(query.limit)

        val apps = JSONArray()
        for (app in page) {
            apps.put(
                JSONObject()
                    .put("package_name", app.packageName)
                    .put("label", app.label ?: JSONObject.NULL)
                    .put("foreground_ms", app.foregroundMs)
                    .put("foreground_minutes", app.foregroundMs / 60_000L)
                    .put("last_used_at_ms", app.lastUsedMs ?: JSONObject.NULL)
            )
        }

        val caveats = JSONArray()
            .put("数据来自系统 UsageStatsManager，统计口径为「应用处于前台」的时长，不是屏幕点亮时长。")
            .put("部分厂商 ROM 会延迟聚合、或在省电模式下停止统计，数字可能比实际偏小。")
            .put("本工具不返回逐次切换事件，也不返回通知、位置等任何内容。")

        val payload = JSONObject()
            .put("range", query.range)
            .put("start_ms", range.startMs)
            .put("end_ms", range.endMs)
            .put("returned", apps.length())
            .put("has_more", filtered.size > apps.length())
            .put("total_apps", filtered.size)
            .put("total_foreground_ms", totalMs)
            .put("apps", apps)
            .put("caveats", caveats)
        return CapabilityResult.Success(payload)
    }

    private data class Query(val range: String, val limit: Int, val includeSystemApps: Boolean)

    private fun parse(arguments: JSONObject): Query = Query(
        range = CapabilityArgs.enumeration(arguments, "range", RANGES, default = RANGE_TODAY),
        limit = CapabilityArgs.intInRange(arguments, "limit", 1..50, default = 10),
        includeSystemApps = CapabilityArgs.optionalBoolean(arguments, "include_system_apps") ?: false,
    )

    companion object {

        const val NAME = "device_get_usage_stats"
        const val RANGE_TODAY = "today"
        const val RANGE_YESTERDAY = "yesterday"
        const val RANGE_7D = "7d"
        val RANGES: Set<String> = setOf(RANGE_TODAY, RANGE_YESTERDAY, RANGE_7D)

        /** 某一天的本地零点。 */
        fun dayStart(date: LocalDate, zone: ZoneId): Long =
            date.atStartOfDay(zone).toInstant().toEpochMilli()

        /**
         * 把 `range` 解释成具体的毫秒区间。
         *
         * 区间一律是**左闭右开** `[start, end)`，且以设备当前时区计算
         * （"今天"是手机上的今天，不是 UTC 的今天 —— 用户问的是自己的日常）。
         */
        fun rangeOf(range: String, nowMs: Long, zone: ZoneId): UsageRange {
            val today = java.time.Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
            return when (range) {
                RANGE_YESTERDAY -> {
                    val start = dayStart(today.minusDays(1), zone)
                    UsageRange(start, dayStart(today, zone))
                }

                RANGE_7D -> UsageRange(dayStart(today.minusDays(6), zone), nowMs)

                // today 以及任何未知值（parse 已经挡掉，这里兜底）都按今天处理。
                else -> UsageRange(dayStart(today, zone), nowMs)
            }
        }

        val METADATA: CapabilityMetadata = CapabilityMetadata(
            name = NAME,
            displayName = "应用使用时长",
            description = "查询今天 / 昨天 / 最近 7 天的应用前台使用时长（按应用聚合）。" +
                "需要用户在系统设置里授予「使用情况访问」权限。",
            category = CapabilityCategory.Device,
            arguments = listOf(
                CapabilityArgument(
                    "range", "string", false,
                    "统计区间：today（默认）、yesterday、7d。",
                ),
                CapabilityArgument(
                    "limit", "integer", false,
                    "返回多少个应用（按使用时长降序），1–50，默认 10。",
                ),
                CapabilityArgument(
                    "include_system_apps", "boolean", false,
                    "是否包含系统应用，默认 false。",
                ),
            ),
            readOnly = true,
            declaredPermissions = listOf(android.Manifest.permission.PACKAGE_USAGE_STATS),
            requiresRuntimePermission = true,
            permissionHint = "系统设置 → 应用 → 特殊应用权限 → 使用情况访问 → Android MCP Hub → 允许",
        )
    }
}
