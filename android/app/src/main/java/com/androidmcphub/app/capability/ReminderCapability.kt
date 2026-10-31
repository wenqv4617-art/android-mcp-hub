package com.androidmcphub.app.capability

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.androidmcphub.app.MainActivity
import com.androidmcphub.app.R
import com.androidmcphub.app.protocol.ToolResultErrorCode
import org.json.JSONArray
import org.json.JSONObject

/**
 * 提醒的定时器来源（真机 [AndroidReminderScheduler]，单测用假实现）。
 *
 * 与闹钟工具的区别：闹钟交给系统闹钟应用（用户可见、可管理）；
 * 提醒是**本应用自己的**本地提醒 —— 不依赖任何第三方应用，也不需要系统闹钟权限，
 * 代价是它只在应用被允许通知时才可见，且重启后需要重新排程。
 */
interface ReminderScheduler {
    /** 排一条提醒；[atMs] 必须是未来时间。 */
    fun schedule(id: String, atMs: Long, title: String, note: String): ControlOutcome

    /** 取消一条提醒（不存在/已取消也算成功，幂等）。 */
    fun cancel(id: String)

    /** 当前是否可以发通知（Android 13+ 需要运行期权限）。 */
    fun canNotify(): Boolean
}

/**
 * 真机实现：`AlarmManager.setAndAllowWhileIdle` + 广播接收器 + 通知。
 *
 * 为什么不用 `setExactAndAllowWhileIdle`：Android 12+ 需要
 * `SCHEDULE_EXACT_ALARM` 特殊权限，用户不授权时应用会直接崩（SecurityException），
 * 而"'大概在 09:00 前后提醒'比'没有提醒'"更符合备忘/待办场景。
 * 代价是省电模式下可能延后几分钟 —— 这一点在工具输出里如实写明。
 */
class AndroidReminderScheduler(context: Context) : ReminderScheduler {

    private val appContext = context.applicationContext

    override fun canNotify(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            appContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    override fun schedule(id: String, atMs: Long, title: String, note: String): ControlOutcome {
        val alarm = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            ?: return ControlOutcome.unavailable("系统没有提供闹钟服务")
        return try {
            val pending = pendingIntent(id, title, note, PendingIntent.FLAG_UPDATE_CURRENT)
                ?: return ControlOutcome.failed("无法创建提醒的定时任务")
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pending)
            ControlOutcome.success(
                JSONObject()
                    .put("scheduled_at_ms", atMs)
                    .put("notification_permission_granted", canNotify()),
            )
        } catch (t: Throwable) {
            ControlOutcome.failed("安排提醒失败（${t.javaClass.simpleName}）")
        }
    }

    override fun cancel(id: String) {
        try {
            val pending = pendingIntent(id, "", "", PendingIntent.FLAG_NO_CREATE)
                ?: return
            (appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager)
                ?.cancel(pending)
            pending.cancel()
        } catch (_: Throwable) {
            // 取消失败不抛给调用方：它已经不在系统里了，结果是一样的。
        }
    }

    private fun pendingIntent(id: String, title: String, note: String, flags: Int): PendingIntent? {
        val intent = Intent(appContext, ReminderReceiver::class.java)
            .setAction(ReminderReceiver.ACTION_FIRE)
            .putExtra(ReminderReceiver.EXTRA_ID, id)
            .putExtra(ReminderReceiver.EXTRA_TITLE, title)
            .putExtra(ReminderReceiver.EXTRA_NOTE, note)
        val requestCode = id.hashCode()
        return PendingIntent.getBroadcast(
            appContext,
            requestCode,
            intent,
            flags or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

/**
 * 提醒到点后的接收器：发一条本应用通知，并把提醒标记成 `fired`。
 *
 * 标记 `fired` 不是"完成"：用户仍可以在列表里看到它，并手动标记完成/删除。
 * 这样即使通知被系统拦截，记录也不会凭空消失。
 */
class ReminderReceiver : android.content.BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "提醒" }
        val note = intent.getStringExtra(EXTRA_NOTE).orEmpty()

        try {
            val store = LocalStores.reminders(context)
            val reminder = store.get(id)
            if (reminder != null) {
                reminder.put("status", "fired")
                reminder.put("fired_at_ms", System.currentTimeMillis())
                reminder.put("updated_at_ms", System.currentTimeMillis())
                store.replace(id, reminder)
            }
        } catch (_: Throwable) {
            // 存储失败不影响发通知：用户至少能看到提醒本身。
        }

        postNotification(context, id, title, note)
    }

    private fun postNotification(context: Context, id: String, title: String, note: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "由本机提醒触发的通知"
                },
            )
        }
        val pending = PendingIntent.getActivity(
            context,
            id.hashCode(),
            Intent(context, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP,
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(note)
            .setStyle(NotificationCompat.BigTextStyle().bigText(note))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        try {
            manager.notify(id.hashCode(), notification)
        } catch (_: Throwable) {
            // 通知失败已经尽力而为；不在这里崩溃（接收器异常会影响系统）。
        }
    }

    companion object {
        const val ACTION_FIRE = "com.androidmcphub.app.action.REMINDER_FIRE"
        const val EXTRA_ID = "reminder_id"
        const val EXTRA_TITLE = "reminder_title"
        const val EXTRA_NOTE = "reminder_note"
        const val CHANNEL_ID = "hub_reminders"
    }
}

internal object ReminderFields {
    const val MAX_TITLE = 200
    const val MAX_NOTE = 1000
    const val MAX_LIST = 50
    val STATUSES: Set<String> = setOf("scheduled", "fired", "done")
    val ID_PATTERN = Regex("^reminder_[0-9]{1,12}$")

    fun parseId(arguments: JSONObject): String {
        val id = CapabilityArgs.requiredString(arguments, "id", 32, allowBlank = false)
        if (!ID_PATTERN.matches(id)) throw ArgumentProblem("参数 id 格式不正确")
        return id
    }

    fun view(reminder: JSONObject): JSONObject = JSONObject()
        .put("id", reminder.optString("id"))
        .put("title", reminder.optString("title"))
        .put("note", reminder.optString("note"))
        .put("at_ms", reminder.optLong("at_ms"))
        .put("status", reminder.optString("status", "scheduled"))
        .put("created_at_ms", reminder.optLong("created_at_ms"))
        .put("updated_at_ms", reminder.optLong("updated_at_ms"))
}

/** `reminder_create`：本地提醒（非系统闹钟）。 */
class ReminderCreateCapability(
    private val store: JsonStore,
    private val scheduler: ReminderScheduler,
    private val clock: () -> Long = System::currentTimeMillis,
) : Capability {

    override val name: String = NAME
    override val metadata: CapabilityMetadata = METADATA
    override val allowedArguments: Set<String> = setOf("title", "at_ms", "note")

    override fun validate(arguments: JSONObject): String? = validated(arguments) { parse(arguments) }.second

    override fun execute(arguments: JSONObject): CapabilityResult {
        val spec = try {
            parse(arguments)
        } catch (problem: ArgumentProblem) {
            return CapabilityResult.Failure(ToolResultErrorCode.INVALID_ARGUMENTS, problem.message ?: "参数不合法")
        }
        val now = clock()
        val item = JSONObject()
            .put("title", spec.title)
            .put("note", spec.note)
            .put("at_ms", spec.atMs)
            .put("status", "scheduled")
            .put("created_at_ms", now)
            .put("updated_at_ms", now)
        val id = try {
            store.insert(item)
        } catch (t: Throwable) {
            return CapabilityResult.Failure(ToolResultErrorCode.TOOL_FAILED, "保存提醒失败（${t.javaClass.simpleName}）")
        }
        val outcome = scheduler.schedule(id, spec.atMs, spec.title, spec.note)
        if (!outcome.ok) {
            // 排程失败：把刚写入的记录删掉，避免留下一条"永远不会响"的提醒。
            runCatching { store.delete(id) }
            return outcome.toResult()
        }
        return CapabilityResult.Success(
            JSONObject()
                .put("id", id)
                .put("title", spec.title)
                .put("at_ms", spec.atMs)
                .put("status", "scheduled")
                .put("notification_permission_granted", scheduler.canNotify())
                .put("timing_note", "使用系统非精确闹钟，省电模式下可能延后几分钟；设备重启后需打开应用才会重新排程。"),
        )
    }

    private data class Spec(val title: String, val atMs: Long, val note: String)

    private fun parse(arguments: JSONObject): Spec {
        val title = CapabilityArgs.requiredString(arguments, "title", ReminderFields.MAX_TITLE, allowBlank = false)
        val atMs = CapabilityArgs.requiredLong(arguments, "at_ms")
        if (atMs <= clock()) throw ArgumentProblem("参数 at_ms 必须是未来的时间（Unix 毫秒）")
        val note = CapabilityArgs.optionalString(arguments, "note", ReminderFields.MAX_NOTE) ?: ""
        return Spec(title, atMs, note)
    }

    companion object {
        const val NAME = "reminder_create"

        val METADATA: CapabilityMetadata = CapabilityMetadata(
            name = NAME,
            displayName = "新建提醒",
            description = "在本机创建一条到点提醒（通知形式）。不依赖系统闹钟应用；需要通知权限才能看见。" +
                "时间为 Unix 毫秒，必须是未来时间。",
            category = CapabilityCategory.Productivity,
            arguments = listOf(
                CapabilityArgument("title", "string", true, "提醒标题，1–200 字。"),
                CapabilityArgument("at_ms", "integer", true, "提醒时间（Unix 毫秒）。"),
                CapabilityArgument("note", "string", false, "附加说明，最多 1000 字。"),
            ),
            readOnly = false,
            declaredPermissions = listOf(Manifest.permission.POST_NOTIFICATIONS),
            requiresRuntimePermission = true,
            permissionHint = "系统设置 → 应用 → Android MCP Hub → 通知 → 允许通知（否则到点提醒不可见）",
        )
    }
}

/** `reminder_list`。 */
class ReminderListCapability(
    private val store: JsonStore,
    private val clock: () -> Long = System::currentTimeMillis,
) : Capability {

    override val name: String = NAME
    override val metadata: CapabilityMetadata = METADATA
    override val allowedArguments: Set<String> = setOf("status", "offset", "limit")

    override fun validate(arguments: JSONObject): String? = validated(arguments) { parse(arguments) }.second

    override fun execute(arguments: JSONObject): CapabilityResult {
        val query = try {
            parse(arguments)
        } catch (problem: ArgumentProblem) {
            return CapabilityResult.Failure(ToolResultErrorCode.INVALID_ARGUMENTS, problem.message ?: "参数不合法")
        }
        val now = clock()
        val all = try {
            store.list()
        } catch (t: Throwable) {
            return CapabilityResult.Failure(ToolResultErrorCode.TOOL_FAILED, "读取提醒失败（${t.javaClass.simpleName}）")
        }
            .filter { query.status == null || it.optString("status", "scheduled") == query.status }
            .sortedBy { it.optLong("at_ms") }
        val page = all.drop(query.offset).take(query.limit)
        val items = JSONArray()
        for (reminder in page) items.put(ReminderFields.view(reminder))
        return CapabilityResult.Success(
            JSONObject()
                .put("now_ms", now)
                .put("total", all.size)
                .put("offset", query.offset)
                .put("limit", query.limit)
                .put("returned", items.length())
                .put("has_more", all.size > query.offset + items.length())
                .put("reminders", items),
        )
    }

    private data class Query(val status: String?, val offset: Int, val limit: Int)

    private fun parse(arguments: JSONObject): Query = Query(
        status = CapabilityArgs.optionalString(arguments, "status", 16, allowBlank = false)?.lowercase()?.also {
            if (it !in ReminderFields.STATUSES) throw ArgumentProblem("参数 status 只能是：scheduled, fired, done")
        },
        offset = CapabilityArgs.intInRange(arguments, "offset", 0..100_000, default = 0),
        limit = CapabilityArgs.intInRange(arguments, "limit", 1..ReminderFields.MAX_LIST, default = 20),
    )

    companion object {
        const val NAME = "reminder_list"

        val METADATA: CapabilityMetadata = CapabilityMetadata(
            name = NAME,
            displayName = "提醒列表",
            description = "列出本机提醒（按时间排序），可按状态过滤：scheduled / fired / done。",
            category = CapabilityCategory.Productivity,
            arguments = listOf(
                CapabilityArgument("status", "string", false, "只看某个状态；不传表示全部。"),
                CapabilityArgument("offset", "integer", false, "跳过多少条，默认 0。"),
                CapabilityArgument("limit", "integer", false, "返回条数，1–50，默认 20。"),
            ),
            readOnly = true,
        )
    }
}

/** `reminder_update`：修改标题/说明/时间并重新排程。 */
class ReminderUpdateCapability(
    private val store: JsonStore,
    private val scheduler: ReminderScheduler,
    private val clock: () -> Long = System::currentTimeMillis,
) : Capability {

    override val name: String = NAME
    override val metadata: CapabilityMetadata = METADATA
    override val allowedArguments: Set<String> = setOf("id", "title", "note", "at_ms")

    override fun validate(arguments: JSONObject): String? = validated(arguments) { parse(arguments) }.second

    override fun execute(arguments: JSONObject): CapabilityResult {
        val update = try {
            parse(arguments)
        } catch (problem: ArgumentProblem) {
            return CapabilityResult.Failure(ToolResultErrorCode.INVALID_ARGUMENTS, problem.message ?: "参数不合法")
        }
        val current = try {
            store.get(update.id)
        } catch (t: Throwable) {
            return CapabilityResult.Failure(ToolResultErrorCode.TOOL_FAILED, "读取提醒失败（${t.javaClass.simpleName}）")
        } ?: return CapabilityResult.Failure(ToolResultErrorCode.INVALID_ARGUMENTS, "找不到这条提醒（${update.id}）")

        val now = clock()
        if (update.title != null) current.put("title", update.title)
        if (update.note != null) current.put("note", update.note)
        if (update.atMs != null) current.put("at_ms", update.atMs)
        current.put("status", "scheduled")
        current.put("updated_at_ms", now)

        val atMs = current.optLong("at_ms")
        val outcome = scheduler.schedule(
            update.id,
            atMs,
            current.optString("title"),
            current.optString("note"),
        )
        if (!outcome.ok) return outcome.toResult()
        return try {
            store.replace(update.id, current)
            CapabilityResult.Success(ReminderFields.view(current))
        } catch (t: Throwable) {
            CapabilityResult.Failure(ToolResultErrorCode.TOOL_FAILED, "保存提醒失败（${t.javaClass.simpleName}）")
        }
    }

    private data class Update(val id: String, val title: String?, val note: String?, val atMs: Long?)

    private fun parse(arguments: JSONObject): Update {
        val id = ReminderFields.parseId(arguments)
        val title = CapabilityArgs.optionalString(arguments, "title", ReminderFields.MAX_TITLE, allowBlank = false)
        val note = CapabilityArgs.optionalString(arguments, "note", ReminderFields.MAX_NOTE)
        val atMs = CapabilityArgs.optionalLong(arguments, "at_ms")?.also {
            if (it <= clock()) throw ArgumentProblem("参数 at_ms 必须是未来的时间（Unix 毫秒）")
        }
        if (title == null && note == null && atMs == null) {
            throw ArgumentProblem("至少要修改 title / note / at_ms 中的一项")
        }
        return Update(id, title, note, atMs)
    }

    companion object {
        const val NAME = "reminder_update"

        val METADATA: CapabilityMetadata = CapabilityMetadata(
            name = NAME,
            displayName = "修改提醒",
            description = "修改提醒的标题、说明或时间（至少一项），并重新排程。已经触发过的提醒会被重新激活。",
            category = CapabilityCategory.Productivity,
            arguments = listOf(
                CapabilityArgument("id", "string", true, "提醒 ID。"),
                CapabilityArgument("title", "string", false, "新标题，1–200 字。"),
                CapabilityArgument("note", "string", false, "新说明，最多 1000 字。"),
                CapabilityArgument("at_ms", "integer", false, "新的提醒时间（Unix 毫秒，必须是未来）。"),
            ),
            readOnly = false,
        )
    }
}

/** `reminder_complete`：标记完成并取消排程；`done=false` 可重新打开。 */
class ReminderCompleteCapability(
    private val store: JsonStore,
    private val scheduler: ReminderScheduler,
    private val clock: () -> Long = System::currentTimeMillis,
) : Capability {

    override val name: String = NAME
    override val metadata: CapabilityMetadata = METADATA
    override val allowedArguments: Set<String> = setOf("id", "done")

    override fun validate(arguments: JSONObject): String? = validated(arguments) { parse(arguments) }.second

    override fun execute(arguments: JSONObject): CapabilityResult {
        val spec = try {
            parse(arguments)
        } catch (problem: ArgumentProblem) {
            return CapabilityResult.Failure(ToolResultErrorCode.INVALID_ARGUMENTS, problem.message ?: "参数不合法")
        }
        val current = try {
            store.get(spec.id)
        } catch (t: Throwable) {
            return CapabilityResult.Failure(ToolResultErrorCode.TOOL_FAILED, "读取提醒失败（${t.javaClass.simpleName}）")
        } ?: return CapabilityResult.Failure(ToolResultErrorCode.INVALID_ARGUMENTS, "找不到这条提醒（${spec.id}）")

        val now = clock()
        if (spec.done) {
            scheduler.cancel(spec.id)
            current.put("status", "done")
            current.put("completed_at_ms", now)
        } else {
            val atMs = current.optLong("at_ms")
            if (atMs <= now) {
                return CapabilityResult.Failure(
                    ToolResultErrorCode.INVALID_ARGUMENTS,
                    "这条提醒的时间已经过去，请先用 reminder_update 设置一个新的时间。",
                )
            }
            val outcome = scheduler.schedule(spec.id, atMs, current.optString("title"), current.optString("note"))
            if (!outcome.ok) return outcome.toResult()
            current.put("status", "scheduled")
            current.put("completed_at_ms", 0L)
        }
        current.put("updated_at_ms", now)
        return try {
            store.replace(spec.id, current)
            CapabilityResult.Success(ReminderFields.view(current))
        } catch (t: Throwable) {
            CapabilityResult.Failure(ToolResultErrorCode.TOOL_FAILED, "保存提醒失败（${t.javaClass.simpleName}）")
        }
    }

    private data class Spec(val id: String, val done: Boolean)

    private fun parse(arguments: JSONObject): Spec = Spec(
        id = ReminderFields.parseId(arguments),
        done = CapabilityArgs.optionalBoolean(arguments, "done") ?: true,
    )

    companion object {
        const val NAME = "reminder_complete"

        val METADATA: CapabilityMetadata = CapabilityMetadata(
            name = NAME,
            displayName = "完成提醒",
            description = "把提醒标记完成并取消排程（done=true，默认）；done=false 会在原时间重新打开（时间已过则报错）。",
            category = CapabilityCategory.Productivity,
            arguments = listOf(
                CapabilityArgument("id", "string", true, "提醒 ID。"),
                CapabilityArgument("done", "boolean", false, "true=完成（默认），false=重新激活。"),
            ),
            readOnly = false,
        )
    }
}

/** `reminder_delete`：删除提醒，必须 `confirm=true`。 */
class ReminderDeleteCapability(
    private val store: JsonStore,
    private val scheduler: ReminderScheduler,
) : Capability {

    override val name: String = NAME
    override val metadata: CapabilityMetadata = METADATA
    override val allowedArguments: Set<String> = setOf("id", "confirm")

    override fun validate(arguments: JSONObject): String? = validated(arguments) { ReminderFields.parseId(arguments) }.second

    override fun confirmationProblem(arguments: JSONObject): String? {
        val confirm = try {
            CapabilityArgs.optionalBoolean(arguments, "confirm")
        } catch (_: ArgumentProblem) {
            return null
        }
        return if (confirm == true) null else "删除提醒不可恢复，需要调用方显式传 confirm=true 才能执行。"
    }

    override fun execute(arguments: JSONObject): CapabilityResult {
        val id = try {
            ReminderFields.parseId(arguments)
        } catch (problem: ArgumentProblem) {
            return CapabilityResult.Failure(ToolResultErrorCode.INVALID_ARGUMENTS, problem.message ?: "参数不合法")
        }
        return try {
            scheduler.cancel(id)
            if (store.delete(id)) {
                CapabilityResult.Success(JSONObject().put("deleted", true).put("id", id))
            } else {
                CapabilityResult.Failure(ToolResultErrorCode.INVALID_ARGUMENTS, "找不到这条提醒（$id）")
            }
        } catch (t: Throwable) {
            CapabilityResult.Failure(ToolResultErrorCode.TOOL_FAILED, "删除提醒失败（${t.javaClass.simpleName}）")
        }
    }

    companion object {
        const val NAME = "reminder_delete"

        val METADATA: CapabilityMetadata = CapabilityMetadata(
            name = NAME,
            displayName = "删除提醒",
            description = "永久删除一条提醒并取消排程。必须先传 confirm=true；删除后无法恢复。",
            category = CapabilityCategory.Productivity,
            arguments = listOf(
                CapabilityArgument("id", "string", true, "提醒 ID。"),
                CapabilityArgument("confirm", "boolean", true, "必须为 true，表示确认删除。"),
            ),
            readOnly = false,
            destructive = true,
        )
    }
}

/**
 * 进程重启后恢复提醒排程。
 *
 * `AlarmManager` 里的闹钟在设备重启后会全部消失，而本应用**没有**申请
 * `RECEIVE_BOOT_COMPLETED`（那需要开机广播权限，且会让应用在用户没打开它时
 * 就自己跑起来）。所以采用一个折中：每次应用进程启动时，把存储里仍为
 * `scheduled` 的提醒重新排一遍。
 *
 *  * 还没到点的提醒：按原时间重新排；
 *  * 已经过点（应用关着的那段时间错过了）的提醒：**不丢弃**，排在 1 秒后，
 *    走正常的触发路径（发通知 + 标记 fired）—— 用户至少知道有这么一条提醒；
 *  * `fired` / `done` 的提醒不动。
 *
 * 返回值是重新排程成功的条数，只用于日志，不影响调用方。
 */
object ReminderRescheduler {

    fun reschedule(context: Context, nowMs: Long = System.currentTimeMillis()): Int {
        val store = try {
            LocalStores.reminders(context)
        } catch (_: Throwable) {
            return 0
        }
        val items = try {
            store.list()
        } catch (_: Throwable) {
            return 0
        }
        val scheduler = AndroidReminderScheduler(context)
        var rearmed = 0
        for (item in items) {
            if (item.optString("status", "scheduled") != "scheduled") continue
            val id = item.optString("id")
            if (id.isBlank()) continue
            val atMs = item.optLong("at_ms", 0L)
            // at_ms 缺失/为 0 说明记录本身不完整：不猜时间，也不把它排到 1970 年。
            if (atMs <= 0L) continue
            val target = if (atMs > nowMs) atMs else nowMs + 1_000L
            val outcome = scheduler.schedule(
                id = id,
                atMs = target,
                title = item.optString("title"),
                note = item.optString("note"),
            )
            if (outcome.ok) rearmed++
        }
        return rearmed
    }
}
