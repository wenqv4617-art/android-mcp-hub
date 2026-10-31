package com.androidmcphub.app.capability

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import com.androidmcphub.app.protocol.ToolResultErrorCode
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * 系统闹钟应用检查的权限名。
 *
 * 注意不是 `android.permission.SET_ALARM`（那是给旧式广播用的另一条权限）；
 * `AlarmClock.ACTION_SET_ALARM` 的文档要求的是这一条。写成前者不会崩溃，
 * 但请求拿不到真正需要的授权。
 */
internal const val SET_ALARM_PERMISSION = "com.android.alarm.permission.SET_ALARM"

/** 闹钟动作的来源（真机 [AndroidAlarmSource]，单测用假实现）。 */
interface AlarmSource {
    /**
     * 请求系统闹钟应用创建闹钟。
     *
     * @param days ISO 星期（1=周一 … 7=周日）；空列表表示"只响一次"。
     * @param skipUi true 表示不经过系统闹钟应用的确认界面直接创建。
     */
    fun createAlarm(hour: Int, minute: Int, label: String?, days: List<Int>, skipUi: Boolean): ControlOutcome

    fun openAlarmList(): ControlOutcome
}

/**
 * 用 `AlarmClock` 公开动作交给系统闹钟应用。
 *
 * 为什么**不**自己用 AlarmManager 响铃：那需要精确闹钟权限、前台服务、
 * 音频焦点、锁屏显示等一整套正确实现，任何一处做错都会变成"半夜不响"。
 * 系统闹钟应用本来就是用户信任、且会正确响铃的那一个；我们只负责把时间/标签/重复
 * 传过去。这也意味着最终创建动作可能仍需要用户在系统界面上确认（除 `skip_ui=true`）。
 */
class AndroidAlarmSource(context: Context) : AlarmSource {

    private val appContext = context.applicationContext

    override fun createAlarm(hour: Int, minute: Int, label: String?, days: List<Int>, skipUi: Boolean): ControlOutcome {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, skipUi)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // 标签没有**公开**的 AlarmClock 常量；各系统闹钟应用普遍读这个字符串 extra。
        // 读不懂的应用会忽略它 —— 所以输出里 `label_best_effort=true`，不假装一定生效。
        if (!label.isNullOrBlank()) intent.putExtra(EXTRA_LABEL, label)
        if (days.isNotEmpty()) {
            intent.putIntegerArrayListExtra(AlarmClock.EXTRA_DAYS, ArrayList(days.map { isoDayToCalendar(it) }))
        }

        if (intent.resolveActivity(appContext.packageManager) == null) {
            return ControlOutcome.unavailable("这台设备没有可以处理新建闹钟的应用（缺少系统闹钟）。")
        }
        return try {
            appContext.startActivity(intent)
            ControlOutcome.success(
                JSONObject()
                    .put("alarm_requested", true)
                    .put("hour", hour)
                    .put("minute", minute)
                    .put("repeat_days", JSONArray(days))
                    .put("skip_ui", skipUi)
                    .put("label_best_effort", !label.isNullOrBlank()),
            )
        } catch (t: Throwable) {
            ControlOutcome.failed("打开系统闹钟失败（${t.javaClass.simpleName}）。Android 可能阻止了后台启动界面。")
        }
    }

    override fun openAlarmList(): ControlOutcome {
        val intent = Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(appContext.packageManager) == null) {
            return ControlOutcome.unavailable("这台设备没有可以显示闹钟列表的应用。")
        }
        return try {
            appContext.startActivity(intent)
            ControlOutcome.success(JSONObject().put("opened", "alarms"))
        } catch (t: Throwable) {
            ControlOutcome.failed("打开闹钟列表失败（${t.javaClass.simpleName}）")
        }
    }

    companion object {
        /** 系统闹钟应用通用的标签 extra（没有公开常量，见 [createAlarm] 的注释）。 */
        const val EXTRA_LABEL = "android.intent.extra.alarm.LABEL"

        /** ISO 星期（1=周一 … 7=周日）→ `java.util.Calendar` 的星期常量。 */
        fun isoDayToCalendar(isoDay: Int): Int = when (isoDay) {
            1 -> Calendar.MONDAY
            2 -> Calendar.TUESDAY
            3 -> Calendar.WEDNESDAY
            4 -> Calendar.THURSDAY
            5 -> Calendar.FRIDAY
            6 -> Calendar.SATURDAY
            7 -> Calendar.SUNDAY
            else -> throw IllegalArgumentException("星期必须在 1..7")
        }
    }
}

/** Phase 3B：`alarm_create`。 */
class AlarmCreateCapability(private val source: AlarmSource) : Capability {

    override val name: String = NAME
    override val metadata: CapabilityMetadata = METADATA

    override val allowedArguments: Set<String> = setOf("hour", "minute", "label", "repeat_days", "skip_ui")

    override fun validate(arguments: JSONObject): String? = validated(arguments) { parse(arguments) }.second

    override fun execute(arguments: JSONObject): CapabilityResult {
        val spec = try {
            parse(arguments)
        } catch (problem: ArgumentProblem) {
            return CapabilityResult.Failure(ToolResultErrorCode.INVALID_ARGUMENTS, problem.message ?: "参数不合法")
        }
        return source.createAlarm(spec.hour, spec.minute, spec.label, spec.days, spec.skipUi).toResult()
    }

    private data class Spec(val hour: Int, val minute: Int, val label: String?, val days: List<Int>, val skipUi: Boolean)

    private fun parse(arguments: JSONObject): Spec {
        val hour = CapabilityArgs.intInRange(arguments, "hour", 0..23)
        val minute = CapabilityArgs.intInRange(arguments, "minute", 0..59)
        val label = CapabilityArgs.optionalString(arguments, "label", 100, allowBlank = false)
        val daysValue = arguments.opt("repeat_days")
        val days: List<Int> = when {
            daysValue == null || arguments.isNull("repeat_days") -> emptyList()
            daysValue !is org.json.JSONArray -> throw ArgumentProblem("参数 repeat_days 必须是整数数组")
            else -> (0 until daysValue.length()).map { index ->
                val value = daysValue.opt(index)
                if (value !is Number) throw ArgumentProblem("参数 repeat_days 必须是整数数组")
                val day = value.toInt()
                if (value.toDouble() != day.toDouble() || day !in 1..7) {
                    throw ArgumentProblem("参数 repeat_days 的每一项必须在 1..7（周一=1）")
                }
                day
            }.distinct().sorted()
        }
        val skipUi = CapabilityArgs.optionalBoolean(arguments, "skip_ui") ?: false
        return Spec(hour, minute, label, days, skipUi)
    }

    companion object {
        const val NAME = "alarm_create"

        val METADATA: CapabilityMetadata = CapabilityMetadata(
            name = NAME,
            displayName = "创建闹钟",
            description = "通过系统闹钟应用创建一个闹钟（时间、标签、每周重复）。" +
                "默认会打开系统闹钟的确认界面，用户可当场调整。",
            category = CapabilityCategory.Device,
            arguments = listOf(
                CapabilityArgument("hour", "integer", true, "小时，0–23（设备本地时间）。"),
                CapabilityArgument("minute", "integer", true, "分钟，0–59。"),
                CapabilityArgument("label", "string", false, "闹钟标签，最多 100 字。"),
                CapabilityArgument(
                    "repeat_days", "integer[]", false,
                    "每周重复的星期，1=周一 … 7=周日；不传表示只响一次。",
                ),
                CapabilityArgument(
                    "skip_ui", "boolean", false,
                    "true = 尝试跳过系统闹钟确认界面（部分系统不支持），默认 false。",
                ),
            ),
            readOnly = false,
            // 必须用系统闹钟应用声明的完整权限名；Manifest.permission.SET_ALARM
            // 是另一条（广播用）权限，不能替代它。见 AndroidManifest.xml 的说明。
            declaredPermissions = listOf(SET_ALARM_PERMISSION),
        )
    }
}

/** Phase 3B：`alarm_open_list`。 */
class AlarmOpenListCapability(private val source: AlarmSource) : Capability {

    override val name: String = NAME
    override val metadata: CapabilityMetadata = METADATA
    override val allowedArguments: Set<String> = emptySet()

    override fun execute(arguments: JSONObject): CapabilityResult = source.openAlarmList().toResult()

    companion object {
        const val NAME = "alarm_open_list"

        val METADATA: CapabilityMetadata = CapabilityMetadata(
            name = NAME,
            displayName = "打开闹钟列表",
            description = "打开系统闹钟应用的闹钟列表页。只打开界面，不修改任何闹钟。",
            category = CapabilityCategory.Device,
            readOnly = false,
            // 打开闹钟列表不需要 SET_ALARM：ACTION_SHOW_ALARMS 只是显示界面。
        )
    }
}
