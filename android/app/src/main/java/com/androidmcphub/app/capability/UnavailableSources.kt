package com.androidmcphub.app.capability

import org.json.JSONObject

/**
 * 没有 Context 时的**空来源**。
 *
 * 它们存在只有一个原因：注册清单（`BuiltInCapabilities.ALL`）必须在 JVM 单测里
 * 也能被完整构造出来，而 JVM 单测没有 Android 系统服务。空来源**不会**伪造数据：
 *  * 读操作返回空/全 null；
 *  * 写操作一律返回 `tool_unavailable`，并明确说"当前环境没有这个能力"；
 *  * 权限查询返回 false。
 *
 * 生产接线（`androidSources`）永远不会用到这里 —— 如果谁在真机上看到
 * "当前环境没有…"，那说明依赖注入接错了，而不是设备真的不支持。
 */
object UnavailableUsageStatsSource : UsageStatsSource {
    override fun hasAccess(): Boolean = false
    override fun query(startMs: Long, endMs: Long): UsageSnapshot = UsageSnapshot(emptyList(), startMs, endMs)
}

object UnavailableDeviceStatusSource : DeviceStatusSource {
    override fun read(): DeviceStatus = DeviceStatus(null, null, null, null, null)
}

object UnavailableDeviceControlSource : DeviceControlSource {
    private fun unavailable(): ControlOutcome = ControlOutcome.unavailable("当前环境没有设备控制能力")
    override fun setMediaVolume(volume: Int): ControlOutcome = unavailable()
    override fun setTorch(enabled: Boolean): ControlOutcome = unavailable()
    override fun launchApp(packageName: String): ControlOutcome = unavailable()
    override fun openSettings(page: String): ControlOutcome = unavailable()
    override fun postNotification(title: String, body: String): ControlOutcome = unavailable()
}

object UnavailableAlarmSource : AlarmSource {
    override fun createAlarm(
        hour: Int,
        minute: Int,
        label: String?,
        days: List<Int>,
        skipUi: Boolean,
    ): ControlOutcome = ControlOutcome.unavailable("当前环境没有系统闹钟能力")

    override fun openAlarmList(): ControlOutcome = ControlOutcome.unavailable("当前环境没有系统闹钟能力")
}

object UnavailableReminderScheduler : ReminderScheduler {
    override fun schedule(id: String, atMs: Long, title: String, note: String): ControlOutcome =
        ControlOutcome.unavailable("当前环境没有本地提醒能力")

    override fun cancel(id: String) = Unit
    override fun canNotify(): Boolean = false
}

object UnavailableCalendarSource : CalendarSource {
    override fun hasReadPermission(): Boolean = false
    override fun hasWritePermission(): Boolean = false
    override fun calendars(): List<CalendarInfo> = emptyList()
    override fun events(
        calendarId: Long?,
        startMs: Long,
        endMs: Long,
        limit: Int,
        query: String,
    ): List<CalendarEvent> = emptyList()

    override fun create(spec: NewCalendarEvent): ControlOutcome =
        ControlOutcome.unavailable("当前环境没有日历能力")

    override fun update(eventId: Long, patch: CalendarEventPatch): ControlOutcome =
        ControlOutcome.unavailable("当前环境没有日历能力")

    override fun delete(eventId: Long): ControlOutcome =
        ControlOutcome.unavailable("当前环境没有日历能力")
}

object EmptyCalendarOwnershipStore : CalendarOwnershipStore {
    override fun isOwned(eventId: Long): Boolean = false
    override fun mark(eventId: Long) = Unit
    override fun unmark(eventId: Long) = Unit
}

/** 空 JSON 对象（只读，调用方不应修改）。 */
internal val EMPTY_JSON = JSONObject()
