package com.androidmcphub.app.capability

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import com.androidmcphub.app.protocol.ToolResultErrorCode
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 一个日历（用于让用户/模型知道该往哪个日历写事件）。**不返回账户邮箱**。 */
data class CalendarInfo(
    val id: Long,
    val displayName: String,
    val timezone: String?,
    val primary: Boolean,
    val writable: Boolean,
)

/** 一条日历事件（或重复事件的一个实例）。 */
data class CalendarEvent(
    val eventId: Long,
    val calendarId: Long,
    val calendarName: String?,
    val title: String?,
    val description: String?,
    val location: String?,
    val startMs: Long,
    val endMs: Long,
    val allDay: Boolean,
    val timezone: String?,
    val rrule: String?,
)

/** 新建事件时的字段（已校验）。 */
data class NewCalendarEvent(
    val calendarId: Long?,
    val title: String,
    val description: String?,
    val location: String?,
    val startMs: Long,
    val endMs: Long,
    val allDay: Boolean,
    val timezone: String,
    val rrule: String?,
    val reminderMinutes: List<Int>,
)

/** 修改事件时的字段：null 表示"不动这一项"。 */
data class CalendarEventPatch(
    val title: String? = null,
    val description: String? = null,
    val location: String? = null,
    val startMs: Long? = null,
    val endMs: Long? = null,
    val timezone: String? = null,
    val rrule: String? = null,
    val reminderMinutes: List<Int>? = null,
)

/**
 * 日历数据来源。真机实现是 [AndroidCalendarSource]，单测里换成假实现。
 *
 * 读写权限分成两个方法，因为 Android 有两个独立的运行期权限：
 * 用户可能允许"看日历"但拒绝"改日历"，界面与错误必须能区分这两种情况。
 */
interface CalendarSource {
    fun hasReadPermission(): Boolean
    fun hasWritePermission(): Boolean

    fun calendars(): List<CalendarInfo>
    fun events(calendarId: Long?, startMs: Long, endMs: Long, limit: Int, query: String): List<CalendarEvent>

    /**
     * 这条事件此刻是否仍然存在。
     *
     * 用于清理本地"Hub 创建"记录：事件可能被用户在系统日历里删掉，之后
     * Calendar Provider 可能把同一个行 ID 分配给新事件。写入/删除前先确认
     * 目标还在，避免把行 ID 复用后的**别人的**事件当成自己的。
     *
     * 无法判断时（例如读权限被单独撤销、查询抛异常）返回 `true`：宁可让
     * 后面的系统写入自己失败，也不能因为一次查询失败就误删本地记录。
     */
    fun exists(eventId: Long): Boolean

    fun create(spec: NewCalendarEvent): ControlOutcome
    fun update(eventId: Long, patch: CalendarEventPatch): ControlOutcome
    fun delete(eventId: Long): ControlOutcome
}

/**
 * "哪些事件是 Hub 创建的"。
 *
 * 为什么需要它：Calendar Provider 里可能有用户多年积累的日程。允许模型
 * 修改任意事件的风险远高于允许它管理自己刚创建的事件。更新/删除只对本应用
 * 创建的事件开放；其它事件返回 `not_permitted` 并说明原因（用户可以自己在
 * 系统日历里改）。这不是完美边界（用户可以在系统日历里删掉后重建），但它
 * 把"模型能误伤的范围"从整本日历缩小到它自己创建的那几条。
 */
interface CalendarOwnershipStore {
    fun isOwned(eventId: Long): Boolean
    fun mark(eventId: Long)
    fun unmark(eventId: Long)
}

class FileCalendarOwnershipStore(private val store: JsonStore) : CalendarOwnershipStore {

    override fun isOwned(eventId: Long): Boolean =
        store.list().any { it.optLong("event_id", -1L) == eventId }

    override fun mark(eventId: Long) {
        if (isOwned(eventId)) return
        store.insert(JSONObject().put("event_id", eventId).put("created_at_ms", System.currentTimeMillis()))
    }

    override fun unmark(eventId: Long) {
        store.list()
            .filter { it.optLong("event_id", -1L) == eventId }
            .forEach { store.delete(it.optString("id")) }
    }
}

/**
 * 真机实现：Calendar Provider。
 *
 * 平台限制：
 *  * 读需要 READ_CALENDAR、写需要 WRITE_CALENDAR（运行期权限）；
 *  * 设备上可能一个可写日历都没有（没登录账户）——此时新建返回 `tool_unavailable`，
 *    不伪造一个成功事件；
 *  * 重复事件的列表查询用 `Instances`（系统展开），修改用 `Events`（改系列本身）；
 *  * 全天事件按本地日期换算成 UTC 零点，这是 Calendar Provider 的要求。
 */
class AndroidCalendarSource(context: Context) : CalendarSource {

    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver

    override fun hasReadPermission(): Boolean =
        appContext.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    override fun hasWritePermission(): Boolean =
        appContext.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

    override fun calendars(): List<CalendarInfo> {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.CALENDAR_TIME_ZONE,
            CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Calendars.VISIBLE,
        )
        val result = mutableListOf<CalendarInfo>()
        resolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            "${CalendarContract.Calendars.VISIBLE}=1",
            null,
            "${CalendarContract.Calendars.IS_PRIMARY} DESC",
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                result += CalendarInfo(
                    id = cursor.getLong(0),
                    displayName = cursor.getString(1) ?: "日历 ${cursor.getLong(0)}",
                    timezone = cursor.getString(2),
                    primary = cursor.getInt(3) == 1,
                    writable = cursor.getInt(4) >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR,
                )
            }
        }
        return result
    }

    override fun events(calendarId: Long?, startMs: Long, endMs: Long, limit: Int, query: String): List<CalendarEvent> {
        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, startMs)
        ContentUris.appendId(builder, endMs)

        val selectionParts = mutableListOf<String>()
        val args = mutableListOf<String>()
        if (calendarId != null) {
            selectionParts += "${CalendarContract.Instances.CALENDAR_ID}=?"
            args += calendarId.toString()
        }
        if (query.isNotBlank()) {
            selectionParts += "(${CalendarContract.Instances.TITLE} LIKE ? OR ${CalendarContract.Instances.DESCRIPTION} LIKE ?)"
            args += "%$query%"
            args += "%$query%"
        }

        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.DESCRIPTION,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_TIMEZONE,
            CalendarContract.Instances.RRULE,
        )
        val result = mutableListOf<CalendarEvent>()
        resolver.query(
            builder.build(),
            projection,
            selectionParts.takeIf { it.isNotEmpty() }?.joinToString(" AND "),
            args.takeIf { it.isNotEmpty() }?.toTypedArray(),
            "${CalendarContract.Instances.BEGIN} ASC",
        )?.use { cursor ->
            while (cursor.moveToNext() && result.size < limit) {
                result += CalendarEvent(
                    eventId = cursor.getLong(0),
                    calendarId = cursor.getLong(1),
                    calendarName = cursor.getString(2),
                    title = cursor.getString(3),
                    description = cursor.getString(4),
                    location = cursor.getString(5),
                    startMs = cursor.getLong(6),
                    endMs = cursor.getLong(7),
                    allDay = cursor.getInt(8) == 1,
                    timezone = cursor.getString(9),
                    rrule = cursor.getString(10),
                )
            }
        }
        return result
    }

    override fun create(spec: NewCalendarEvent): ControlOutcome {
        val calendar = resolveWritableCalendar(spec.calendarId)
            ?: return ControlOutcome.unavailable(
                "没有可写入的日历。请先在系统日历应用里登录一个账户并创建一个可编辑日历。",
            )
        val zone = ZoneId.of(spec.timezone)
        val (start, end) = if (spec.allDay) {
            utcMidnightRange(spec.startMs, spec.endMs, zone)
        } else {
            spec.startMs to spec.endMs
        }
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendar.id)
            put(CalendarContract.Events.TITLE, spec.title)
            put(CalendarContract.Events.DTSTART, start)
            put(CalendarContract.Events.DTEND, end)
            put(CalendarContract.Events.ALL_DAY, if (spec.allDay) 1 else 0)
            put(CalendarContract.Events.EVENT_TIMEZONE, if (spec.allDay) "UTC" else spec.timezone)
            spec.description?.let { put(CalendarContract.Events.DESCRIPTION, it) }
            spec.location?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
            spec.rrule?.let { put(CalendarContract.Events.RRULE, it) }
        }
        val uri = resolver.insert(CalendarContract.Events.CONTENT_URI, values)
            ?: return ControlOutcome.failed("系统拒绝创建日历事件")
        val eventId = uri.lastPathSegment?.toLongOrNull()
            ?: return ControlOutcome.failed("创建成功但系统没有返回事件 ID")
        if (spec.reminderMinutes.isNotEmpty()) {
            addReminders(eventId, spec.reminderMinutes)
        }
        return ControlOutcome.success(
            JSONObject()
                .put("event_id", eventId.toString())
                .put("calendar_id", calendar.id.toString())
                .put("calendar_name", calendar.displayName)
                .put("start_ms", start)
                .put("end_ms", end)
                .put("all_day", spec.allDay),
        )
    }

    override fun exists(eventId: Long): Boolean {
        return try {
            resolver.query(
                ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
                arrayOf(CalendarContract.Events._ID),
                null,
                null,
                null,
            )?.use { it.moveToFirst() } ?: true
        } catch (_: SecurityException) {
            // 只给了写权限、没给读权限时无法确认；交给后续写入自己报错。
            true
        } catch (_: Throwable) {
            true
        }
    }

    override fun update(eventId: Long, patch: CalendarEventPatch): ControlOutcome {
        val values = ContentValues()
        patch.title?.let { values.put(CalendarContract.Events.TITLE, it) }
        patch.description?.let { values.put(CalendarContract.Events.DESCRIPTION, it) }
        patch.location?.let { values.put(CalendarContract.Events.EVENT_LOCATION, it) }
        patch.startMs?.let { values.put(CalendarContract.Events.DTSTART, it) }
        patch.endMs?.let { values.put(CalendarContract.Events.DTEND, it) }
        patch.timezone?.let { values.put(CalendarContract.Events.EVENT_TIMEZONE, it) }
        patch.rrule?.let { values.put(CalendarContract.Events.RRULE, it) }

        if (values.size() > 0) {
            val updated = resolver.update(
                ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
                values,
                null,
                null,
            )
            if (updated <= 0) return ControlOutcome.failed("系统没有更新这条事件（可能已被删除）")
        }
        patch.reminderMinutes?.let { minutes ->
            resolver.delete(
                CalendarContract.Reminders.CONTENT_URI,
                "${CalendarContract.Reminders.EVENT_ID}=?",
                arrayOf(eventId.toString()),
            )
            if (minutes.isNotEmpty()) addReminders(eventId, minutes)
        }
        return ControlOutcome.success(JSONObject().put("event_id", eventId.toString()).put("updated", true))
    }

    override fun delete(eventId: Long): ControlOutcome {
        val deleted = resolver.delete(
            ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
            null,
            null,
        )
        if (deleted <= 0) return ControlOutcome.failed("系统没有删除这条事件（可能已被删除）")
        return ControlOutcome.success(JSONObject().put("event_id", eventId.toString()).put("deleted", true))
    }

    private fun resolveWritableCalendar(requested: Long?): CalendarInfo? {
        val all = calendars()
        if (requested != null) return all.firstOrNull { it.id == requested && it.writable }
        return all.firstOrNull { it.primary && it.writable }
            ?: all.firstOrNull { it.writable }
    }

    private fun addReminders(eventId: Long, minutes: List<Int>) {
        for (minute in minutes.distinct()) {
            val values = ContentValues().apply {
                put(CalendarContract.Reminders.EVENT_ID, eventId)
                put(CalendarContract.Reminders.MINUTES, minute)
                put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
            }
            resolver.insert(CalendarContract.Reminders.CONTENT_URI, values)
        }
    }

    companion object {
        /**
         * 全天事件在 Calendar Provider 里必须用 **UTC 零点**表示。
         * 输入是本地时间轴上的毫秒，这里换算成本地日期对应的 UTC 零点。
         */
        fun utcMidnightRange(startMs: Long, endMs: Long, zone: ZoneId): Pair<Long, Long> {
            val startDate: LocalDate = Instant.ofEpochMilli(startMs).atZone(zone).toLocalDate()
            val endDate: LocalDate = Instant.ofEpochMilli(maxOf(endMs, startMs + 1)).atZone(zone).toLocalDate()
            // 结束日期至少是开始日期的下一天（Calendar 的全天事件是左闭右开）。
            val safeEnd = if (endDate.isAfter(startDate)) endDate else startDate.plusDays(1)
            return utcMidnight(startDate) to utcMidnight(safeEnd)
        }

        private fun utcMidnight(date: LocalDate): Long =
            date.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()
    }
}

/** 日历工具共用的参数解析。 */
internal object CalendarArgs {
    const val MAX_TITLE = 200
    const val MAX_TEXT = 2000
    const val MAX_RRULE = 500
    val ID_PATTERN = Regex("^[0-9]{1,18}$")

    fun eventId(arguments: JSONObject): Long {
        val raw = arguments.opt("event_id")
        val text = when (raw) {
            is Number -> raw.toLong().toString()
            is String -> raw
            else -> throw ArgumentProblem("缺少参数 event_id")
        }
        if (!ID_PATTERN.matches(text)) throw ArgumentProblem("参数 event_id 格式不正确")
        return text.toLong()
    }

    fun calendarId(arguments: JSONObject): Long? {
        if (!arguments.has("calendar_id") || arguments.isNull("calendar_id")) return null
        val raw = arguments.opt("calendar_id")
        val text = when (raw) {
            is Number -> raw.toLong().toString()
            is String -> raw
            else -> throw ArgumentProblem("参数 calendar_id 必须是数字或数字字符串")
        }
        if (!ID_PATTERN.matches(text)) throw ArgumentProblem("参数 calendar_id 格式不正确")
        return text.toLong()
    }

    fun timezone(arguments: JSONObject): String {
        val raw = CapabilityArgs.optionalString(arguments, "timezone", 64)?.trim()
            ?: return ZoneId.systemDefault().id
        return try {
            ZoneId.of(raw).id
        } catch (_: Throwable) {
            throw ArgumentProblem("参数 timezone 不是合法的时区（例如 Asia/Shanghai）")
        }
    }

    fun reminders(arguments: JSONObject, required: Boolean = false): List<Int>? {
        val raw = arguments.opt("reminder_minutes")
        if (raw == null || arguments.isNull("reminder_minutes")) {
            if (required) throw ArgumentProblem("缺少参数 reminder_minutes")
            return null
        }
        if (raw !is JSONArray) throw ArgumentProblem("参数 reminder_minutes 必须是整数数组")
        return (0 until raw.length()).map { index ->
            val value = raw.opt(index)
            if (value !is Number || value.toDouble() != value.toLong().toDouble()) {
                throw ArgumentProblem("参数 reminder_minutes 必须是整数数组")
            }
            val minutes = value.toInt()
            if (minutes < 0 || minutes > 60 * 24 * 30) {
                throw ArgumentProblem("reminder_minutes 的每一项必须在 0–43200 之间")
            }
            minutes
        }.distinct().sorted()
    }

    fun rrule(arguments: JSONObject): String? {
        val value = CapabilityArgs.optionalString(arguments, "rrule", MAX_RRULE, allowBlank = false)
        if (value != null && !value.startsWith("FREQ=")) {
            throw ArgumentProblem("rrule 必须是以 FREQ= 开头的 iCalendar 规则（例如 FREQ=WEEKLY;BYDAY=MO）")
        }
        return value
    }
}

/** `calendar_list_calendars`。 */
class CalendarListCalendarsCapability(private val source: CalendarSource) : Capability {

    override val name: String = NAME
    override val metadata: CapabilityMetadata = METADATA
    override val allowedArguments: Set<String> = emptySet()

    override fun execute(arguments: JSONObject): CapabilityResult {
        if (!source.hasReadPermission()) return permissionFailure("读取日历")
        val calendars = try {
            source.calendars()
        } catch (t: Throwable) {
            return CapabilityResult.Failure(ToolResultErrorCode.TOOL_FAILED, "读取日历列表失败（${t.javaClass.simpleName}）")
        }
        val items = JSONArray()
        for (calendar in calendars) {
            items.put(
                JSONObject()
                    .put("calendar_id", calendar.id.toString())
                    .put("display_name", calendar.displayName)
                    .put("timezone", calendar.timezone ?: JSONObject.NULL)
                    .put("primary", calendar.primary)
                    .put("writable", calendar.writable),
            )
        }
        return CapabilityResult.Success(JSONObject().put("count", items.length()).put("calendars", items))
    }

    companion object {
        const val NAME = "calendar_list_calendars"

        val METADATA: CapabilityMetadata = CapabilityMetadata(
            name = NAME,
            displayName = "日历列表",
            description = "列出设备上可见的日历（名称、时区、是否主日历、是否可写）。需要日历读取权限。",
            category = CapabilityCategory.Calendar,
            readOnly = true,
            declaredPermissions = listOf(Manifest.permission.READ_CALENDAR),
            requiresRuntimePermission = true,
            permissionHint = "首次使用时会弹出日历读取授权；若已拒绝，请到 系统设置 → 应用 → Android MCP Hub → 权限 中开启",
        )
    }
}

/** `calendar_list_events`：按时间范围查询（重复事件按实例展开）。 */
class CalendarListEventsCapability(private val source: CalendarSource) : Capability {

    override val name: String = NAME
    override val metadata: CapabilityMetadata = METADATA
    override val allowedArguments: Set<String> = setOf("start_ms", "end_ms", "calendar_id", "query", "limit")

    override fun validate(arguments: JSONObject): String? = validated(arguments) { parse(arguments) }.second

    override fun execute(arguments: JSONObject): CapabilityResult {
        val query = try {
            parse(arguments)
        } catch (problem: ArgumentProblem) {
            return CapabilityResult.Failure(ToolResultErrorCode.INVALID_ARGUMENTS, problem.message ?: "参数不合法")
        }
        if (!source.hasReadPermission()) return permissionFailure("读取日历")
        val events = try {
            source.events(query.calendarId, query.startMs, query.endMs, query.limit, query.text)
        } catch (t: Throwable) {
            return CapabilityResult.Failure(ToolResultErrorCode.TOOL_FAILED, "读取日历事件失败（${t.javaClass.simpleName}）")
        }
        val items = JSONArray()
        for (event in events) {
            items.put(
                JSONObject()
                    .put("event_id", event.eventId.toString())
                    .put("calendar_id", event.calendarId.toString())
                    .put("calendar_name", event.calendarName ?: JSONObject.NULL)
                    .put("title", event.title ?: JSONObject.NULL)
                    .put("description", event.description ?: JSONObject.NULL)
                    .put("location", event.location ?: JSONObject.NULL)
                    .put("start_ms", event.startMs)
                    .put("end_ms", event.endMs)
                    .put("all_day", event.allDay)
                    .put("timezone", event.timezone ?: JSONObject.NULL)
                    .put("recurring", event.rrule != null),
            )
        }
        return CapabilityResult.Success(
            JSONObject()
                .put("start_ms", query.startMs)
                .put("end_ms", query.endMs)
                .put("returned", items.length())
                .put("limit", query.limit)
                .put("has_more", events.size >= query.limit)
                .put("events", items),
        )
    }

    private data class Query(val startMs: Long, val endMs: Long, val calendarId: Long?, val text: String, val limit: Int)

    private fun parse(arguments: JSONObject): Query {
        val start = CapabilityArgs.requiredLong(arguments, "start_ms")
        val end = CapabilityArgs.requiredLong(arguments, "end_ms")
        if (end <= start) throw ArgumentProblem("end_ms 必须大于 start_ms")
        if (end - start > 366L * 24 * 60 * 60 * 1000) throw ArgumentProblem("时间范围不能超过一年")
        return Query(
            startMs = start,
            endMs = end,
            calendarId = CalendarArgs.calendarId(arguments),
            text = CapabilityArgs.optionalString(arguments, "query", 100)?.trim() ?: "",
            limit = CapabilityArgs.intInRange(arguments, "limit", 1..50, default = 20),
        )
    }

    companion object {
        const val NAME = "calendar_list_events"

        val METADATA: CapabilityMetadata = CapabilityMetadata(
            name = NAME,
            displayName = "查询日历事件",
            description = "查询一段时间内的日历事件（重复事件按发生实例展开）。时间用 Unix 毫秒。",
            category = CapabilityCategory.Calendar,
            arguments = listOf(
                CapabilityArgument("start_ms", "integer", true, "区间开始（Unix 毫秒，含）。"),
                CapabilityArgument("end_ms", "integer", true, "区间结束（Unix 毫秒，不含）。"),
                CapabilityArgument("calendar_id", "string", false, "只看某个日历；不传表示所有可见日历。"),
                CapabilityArgument("query", "string", false, "标题/描述关键字。"),
                CapabilityArgument("limit", "integer", false, "返回条数，1–50，默认 20。"),
            ),
            readOnly = true,
            declaredPermissions = listOf(Manifest.permission.READ_CALENDAR),
            requiresRuntimePermission = true,
            permissionHint = "首次使用时会弹出日历读取授权；若已拒绝，请到 系统设置 → 应用 → Android MCP Hub → 权限 中开启",
        )
    }
}

/** `calendar_create_event`。 */
class CalendarCreateEventCapability(
    private val source: CalendarSource,
    private val ownership: CalendarOwnershipStore,
    private val clock: () -> Long = System::currentTimeMillis,
) : Capability {

    override val name: String = NAME
    override val metadata: CapabilityMetadata = METADATA
    override val allowedArguments: Set<String> = setOf(
        "title", "start_ms", "end_ms", "calendar_id", "description", "location",
        "all_day", "timezone", "rrule", "reminder_minutes",
    )

    override fun validate(arguments: JSONObject): String? = validated(arguments) { parse(arguments) }.second

    override fun execute(arguments: JSONObject): CapabilityResult {
        val spec = try {
            parse(arguments)
        } catch (problem: ArgumentProblem) {
            return CapabilityResult.Failure(ToolResultErrorCode.INVALID_ARGUMENTS, problem.message ?: "参数不合法")
        }
        if (!source.hasWritePermission()) return permissionFailure("写入日历")
        val outcome = try {
            source.create(spec)
        } catch (t: Throwable) {
            return CapabilityResult.Failure(ToolResultErrorCode.TOOL_FAILED, "创建日历事件失败（${t.javaClass.simpleName}）")
        }
        if (!outcome.ok) return outcome.toResult()
        outcome.payload.optString("event_id").toLongOrNull()?.let { ownership.mark(it) }
        return CapabilityResult.Success(
            outcome.payload.put(
                "note",
                "该事件由 Android MCP Hub 创建，之后可以用 calendar_update_event / calendar_delete_event 修改或删除它。",
            ),
        )
    }

    private fun parse(arguments: JSONObject): NewCalendarEvent {
        val title = CapabilityArgs.requiredString(arguments, "title", CalendarArgs.MAX_TITLE, allowBlank = false)
        val start = CapabilityArgs.requiredLong(arguments, "start_ms")
        val end = CapabilityArgs.requiredLong(arguments, "end_ms")
        if (end <= start) throw ArgumentProblem("end_ms 必须大于 start_ms")
        if (start < clock() - 365L * 24 * 60 * 60 * 1000) throw ArgumentProblem("start_ms 太早（不能早于一年前）")
        val timezone = CalendarArgs.timezone(arguments)
        return NewCalendarEvent(
            calendarId = CalendarArgs.calendarId(arguments),
            title = title,
            description = CapabilityArgs.optionalString(arguments, "description", CalendarArgs.MAX_TEXT),
            location = CapabilityArgs.optionalString(arguments, "location", 300),
            startMs = start,
            endMs = end,
            allDay = CapabilityArgs.optionalBoolean(arguments, "all_day") ?: false,
            timezone = timezone,
            rrule = CalendarArgs.rrule(arguments),
            reminderMinutes = CalendarArgs.reminders(arguments) ?: emptyList(),
        )
    }

    companion object {
        const val NAME = "calendar_create_event"

        val METADATA: CapabilityMetadata = CapabilityMetadata(
            name = NAME,
            displayName = "新建日历事件",
            description = "在系统日历中创建事件（时间、地点、说明、提醒、重复规则）。" +
                "不传 calendar_id 时使用主日历；需要日历写入权限。",
            category = CapabilityCategory.Calendar,
            arguments = listOf(
                CapabilityArgument("title", "string", true, "事件标题，1–200 字。"),
                CapabilityArgument("start_ms", "integer", true, "开始时间（Unix 毫秒）。"),
                CapabilityArgument("end_ms", "integer", true, "结束时间（Unix 毫秒），必须晚于开始。"),
                CapabilityArgument("calendar_id", "string", false, "目标日历 ID；不传用主日历。"),
                CapabilityArgument("description", "string", false, "说明，最多 2000 字。"),
                CapabilityArgument("location", "string", false, "地点，最多 300 字。"),
                CapabilityArgument("all_day", "boolean", false, "是否为全天事件，默认 false。"),
                CapabilityArgument("timezone", "string", false, "时区 ID，例如 Asia/Shanghai；默认设备时区。"),
                CapabilityArgument("rrule", "string", false, "iCalendar 重复规则，例如 FREQ=WEEKLY;BYDAY=MO。"),
                CapabilityArgument("reminder_minutes", "integer[]", false, "提前多少分钟提醒，例如 [10, 60]。"),
            ),
            readOnly = false,
            declaredPermissions = listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR),
            requiresRuntimePermission = true,
            permissionHint = "首次使用时会弹出日历读写授权；若已拒绝，请到 系统设置 → 应用 → Android MCP Hub → 权限 中开启",
        )
    }
}

/** `calendar_update_event`：只能修改 Hub 创建的事件。 */
class CalendarUpdateEventCapability(
    private val source: CalendarSource,
    private val ownership: CalendarOwnershipStore,
    private val clock: () -> Long = System::currentTimeMillis,
) : Capability {

    override val name: String = NAME
    override val metadata: CapabilityMetadata = METADATA
    override val allowedArguments: Set<String> = setOf(
        "event_id", "title", "start_ms", "end_ms", "description", "location",
        "timezone", "rrule", "reminder_minutes",
    )

    override fun validate(arguments: JSONObject): String? = validated(arguments) { parse(arguments) }.second

    override fun execute(arguments: JSONObject): CapabilityResult {
        val spec = try {
            parse(arguments)
        } catch (problem: ArgumentProblem) {
            return CapabilityResult.Failure(ToolResultErrorCode.INVALID_ARGUMENTS, problem.message ?: "参数不合法")
        }
        if (!source.hasWritePermission()) return permissionFailure("写入日历")
        if (!ownership.isOwned(spec.first)) {
            return CapabilityResult.Failure(
                ToolResultErrorCode.NOT_PERMITTED,
                "只能修改由 Android MCP Hub 创建的事件。其它事件请在系统日历应用里修改。",
            )
        }
        // 本地记录可能是旧的：事件被用户在系统日历里删掉后，行 ID 可能被复用。
        // 先确认目标还在，不在就清掉旧记录并明确拒绝，绝不去碰可能复用该 ID 的新事件。
        if (!source.exists(spec.first)) {
            ownership.unmark(spec.first)
            return CapabilityResult.Failure(
                ToolResultErrorCode.TOOL_FAILED,
                "这条日历事件已不存在（可能被外部删除），本地记录已清理。",
            )
        }
        val outcome = try {
            source.update(spec.first, spec.second)
        } catch (t: Throwable) {
            return CapabilityResult.Failure(ToolResultErrorCode.TOOL_FAILED, "修改日历事件失败（${t.javaClass.simpleName}）")
        }
        if (!outcome.ok && !source.exists(spec.first)) ownership.unmark(spec.first)
        return outcome.toResult()
    }

    private fun parse(arguments: JSONObject): Pair<Long, CalendarEventPatch> {
        val eventId = CalendarArgs.eventId(arguments)
        val start = CapabilityArgs.optionalLong(arguments, "start_ms")
        val end = CapabilityArgs.optionalLong(arguments, "end_ms")
        if (start != null && end != null && end <= start) throw ArgumentProblem("end_ms 必须大于 start_ms")
        if (start != null && start < clock() - 365L * 24 * 60 * 60 * 1000) throw ArgumentProblem("start_ms 太早")

        val patch = CalendarEventPatch(
            title = CapabilityArgs.optionalString(arguments, "title", CalendarArgs.MAX_TITLE, allowBlank = false),
            description = CapabilityArgs.optionalString(arguments, "description", CalendarArgs.MAX_TEXT),
            location = CapabilityArgs.optionalString(arguments, "location", 300),
            startMs = start,
            endMs = end,
            timezone = if (arguments.has("timezone")) CalendarArgs.timezone(arguments) else null,
            rrule = CalendarArgs.rrule(arguments),
            reminderMinutes = CalendarArgs.reminders(arguments),
        )
        if (patch == CalendarEventPatch()) throw ArgumentProblem("至少要修改一项事件字段")
        return eventId to patch
    }

    companion object {
        const val NAME = "calendar_update_event"

        val METADATA: CapabilityMetadata = CapabilityMetadata(
            name = NAME,
            displayName = "修改日历事件",
            description = "修改由 Android MCP Hub 创建的日历事件（至少一项）。其它来源的事件会被拒绝。",
            category = CapabilityCategory.Calendar,
            arguments = listOf(
                CapabilityArgument("event_id", "string", true, "事件 ID。"),
                CapabilityArgument("title", "string", false, "新标题，1–200 字。"),
                CapabilityArgument("start_ms", "integer", false, "新开始时间（Unix 毫秒）。"),
                CapabilityArgument("end_ms", "integer", false, "新结束时间（Unix 毫秒）。"),
                CapabilityArgument("description", "string", false, "新说明，最多 2000 字。"),
                CapabilityArgument("location", "string", false, "新地点，最多 300 字。"),
                CapabilityArgument("timezone", "string", false, "新时区 ID。"),
                CapabilityArgument("rrule", "string", false, "新的 iCalendar 重复规则。"),
                CapabilityArgument("reminder_minutes", "integer[]", false, "替换提醒设置；空数组表示移除提醒。"),
            ),
            readOnly = false,
            declaredPermissions = listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR),
            requiresRuntimePermission = true,
            permissionHint = "需要日历读写权限；只能修改本应用创建的事件",
        )
    }
}

/** `calendar_delete_event`：只能删除 Hub 创建的事件，且必须 `confirm=true`。 */
class CalendarDeleteEventCapability(
    private val source: CalendarSource,
    private val ownership: CalendarOwnershipStore,
) : Capability {

    override val name: String = NAME
    override val metadata: CapabilityMetadata = METADATA
    override val allowedArguments: Set<String> = setOf("event_id", "confirm")

    override fun validate(arguments: JSONObject): String? = validated(arguments) { CalendarArgs.eventId(arguments) }.second

    override fun confirmationProblem(arguments: JSONObject): String? {
        val confirm = try {
            CapabilityArgs.optionalBoolean(arguments, "confirm")
        } catch (_: ArgumentProblem) {
            return null
        }
        return if (confirm == true) null else "删除日历事件不可恢复，需要调用方显式传 confirm=true 才能执行。"
    }

    override fun execute(arguments: JSONObject): CapabilityResult {
        val eventId = try {
            CalendarArgs.eventId(arguments)
        } catch (problem: ArgumentProblem) {
            return CapabilityResult.Failure(ToolResultErrorCode.INVALID_ARGUMENTS, problem.message ?: "参数不合法")
        }
        if (!source.hasWritePermission()) return permissionFailure("写入日历")
        if (!ownership.isOwned(eventId)) {
            return CapabilityResult.Failure(
                ToolResultErrorCode.NOT_PERMITTED,
                "只能删除由 Android MCP Hub 创建的事件。其它事件请在系统日历应用里删除。",
            )
        }
        // 与修改同理：先确认目标仍存在，避免在行 ID 被复用后误删新事件。
        if (!source.exists(eventId)) {
            ownership.unmark(eventId)
            return CapabilityResult.Failure(
                ToolResultErrorCode.TOOL_FAILED,
                "这条日历事件已不存在（可能被外部删除），本地记录已清理。",
            )
        }
        val outcome = try {
            source.delete(eventId)
        } catch (t: Throwable) {
            return CapabilityResult.Failure(ToolResultErrorCode.TOOL_FAILED, "删除日历事件失败（${t.javaClass.simpleName}）")
        }
        if (outcome.ok || !source.exists(eventId)) ownership.unmark(eventId)
        return outcome.toResult()
    }

    companion object {
        const val NAME = "calendar_delete_event"

        val METADATA: CapabilityMetadata = CapabilityMetadata(
            name = NAME,
            displayName = "删除日历事件",
            description = "删除由 Android MCP Hub 创建的日历事件。必须先传 confirm=true；其它来源的事件会被拒绝。",
            category = CapabilityCategory.Calendar,
            arguments = listOf(
                CapabilityArgument("event_id", "string", true, "事件 ID。"),
                CapabilityArgument("confirm", "boolean", true, "必须为 true，表示确认删除。"),
            ),
            readOnly = false,
            destructive = true,
            declaredPermissions = listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR),
            requiresRuntimePermission = true,
            permissionHint = "需要日历读写权限；只能删除本应用创建的事件",
        )
    }
}

/** 日历权限缺失时的统一错误：告诉用户**去哪开**，而不是笼统地说"失败"。 */
private fun permissionFailure(action: String): CapabilityResult = CapabilityResult.Failure(
    ToolResultErrorCode.PERMISSION_DENIED,
    "未授予日历权限，无法$action。请到 系统设置 → 应用 → Android MCP Hub → 权限 中开启「日历」后重试。",
)
