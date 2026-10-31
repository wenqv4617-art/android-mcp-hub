package com.androidmcphub.app.capability

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 3D：日历。
 *
 * 重点不在"能建事件"，而在两条边界：
 *  * 权限缺失时是 `permission_denied`，并告诉用户去哪开；
 *  * 修改/删除**只对 Hub 自己创建的事件**开放（`not_permitted`），
 *    删除还必须调用方显式 `confirm=true`。
 */
class CalendarCapabilityTest {

    private val now = 1_700_000_000_000L
    private val start = now + 3_600_000L
    private val end = start + 3_600_000L

    private class FakeCalendarSource(
        var read: Boolean = true,
        var write: Boolean = true,
    ) : CalendarSource {
        var created: NewCalendarEvent? = null
        var updated: Pair<Long, CalendarEventPatch>? = null
        val deleted = mutableListOf<Long>()
        val events = mutableListOf<CalendarEvent>()
        /** 模拟"事件已经被系统日历外部删除"，但本地归属记录还在。 */
        val externallyDeleted = mutableSetOf<Long>()

        override fun hasReadPermission(): Boolean = read
        override fun hasWritePermission(): Boolean = write

        override fun calendars(): List<CalendarInfo> = listOf(
            CalendarInfo(1L, "主日历", "Asia/Shanghai", primary = true, writable = true),
            CalendarInfo(2L, "只读日历", "UTC", primary = false, writable = false),
        )

        override fun events(
            calendarId: Long?,
            startMs: Long,
            endMs: Long,
            limit: Int,
            query: String,
        ): List<CalendarEvent> = events
            .filter { calendarId == null || it.calendarId == calendarId }
            .filter { it.startMs >= startMs && it.startMs < endMs }
            .filter { query.isBlank() || (it.title ?: "").contains(query, ignoreCase = true) }
            .take(limit)

        override fun exists(eventId: Long): Boolean = eventId !in externallyDeleted

        override fun create(spec: NewCalendarEvent): ControlOutcome {
            created = spec
            return ControlOutcome.success(JSONObject().put("event_id", "42"))
        }

        override fun update(eventId: Long, patch: CalendarEventPatch): ControlOutcome {
            updated = eventId to patch
            return ControlOutcome.success(JSONObject().put("event_id", eventId.toString()))
        }

        override fun delete(eventId: Long): ControlOutcome {
            deleted += eventId
            return ControlOutcome.success(JSONObject().put("deleted", true))
        }
    }

    private class FakeOwnership(private val owned: MutableSet<Long> = mutableSetOf()) : CalendarOwnershipStore {
        override fun isOwned(eventId: Long): Boolean = owned.contains(eventId)
        override fun mark(eventId: Long) {
            owned += eventId
        }

        override fun unmark(eventId: Long) {
            owned -= eventId
        }
    }

    private class Fixture(
        val source: FakeCalendarSource = FakeCalendarSource(),
        val ownership: FakeOwnership = FakeOwnership(),
    ) {
        val dispatcher = CapabilityDispatcher(
            capabilities = listOf(
                CalendarListCalendarsCapability(source),
                CalendarListEventsCapability(source),
                CalendarCreateEventCapability(source, ownership, clock = { 1_700_000_000_000L }),
                CalendarUpdateEventCapability(source, ownership, clock = { 1_700_000_000_000L }),
                CalendarDeleteEventCapability(source, ownership),
            ),
        )
        private var seq = 0

        fun call(tool: String, arguments: JSONObject = JSONObject()): JSONObject {
            seq += 1
            val outcome = dispatcher.handle("tr-$seq", tool, arguments)
            return JSONObject(outcome.frame!!)
        }
    }

    private fun JSONObject.ok(): Boolean = getBoolean("ok")
    private fun JSONObject.result(): JSONObject = getJSONObject("result")
    private fun JSONObject.code(): String = getJSONObject("error").getString("code")

    @Test
    fun `没有读权限时返回 permission_denied`() {
        val f = Fixture(source = FakeCalendarSource(read = false))
        val frame = f.call("calendar_list_calendars")
        assertFalse(frame.ok())
        assertEquals("permission_denied", frame.code())
        assertTrue(frame.getJSONObject("error").getString("message").contains("日历"))
    }

    @Test
    fun `日历列表不返回账户信息`() {
        val frame = Fixture().call("calendar_list_calendars")
        assertTrue(frame.ok())
        val calendars = frame.result().getJSONArray("calendars")
        assertEquals(2, calendars.length())
        val first = calendars.getJSONObject(0)
        assertEquals("1", first.getString("calendar_id"))
        assertEquals("主日历", first.getString("display_name"))
        assertTrue(first.getBoolean("primary"))
        // 刻意不暴露账户邮箱/账户名：这些不是工具需要的，却会泄露用户身份。
        assertFalse(first.has("account_name"))
        assertFalse(first.has("account_email"))
    }

    @Test
    fun `查询事件需要合法区间，并按条件过滤`() {
        val f = Fixture()
        f.source.events += CalendarEvent(
            eventId = 9L,
            calendarId = 1L,
            calendarName = "主日历",
            title = "周会",
            description = null,
            location = null,
            startMs = start,
            endMs = end,
            allDay = false,
            timezone = "Asia/Shanghai",
            rrule = null,
        )

        val badRange = f.call(
            "calendar_list_events",
            JSONObject().put("start_ms", start).put("end_ms", start),
        )
        assertFalse(badRange.ok())
        assertEquals("invalid_arguments", badRange.code())

        val frame = f.call(
            "calendar_list_events",
            JSONObject().put("start_ms", start - 1_000L).put("end_ms", end + 1_000L),
        )
        assertTrue(frame.ok())
        assertEquals(1, frame.result().getInt("returned"))
        assertEquals("周会", frame.result().getJSONArray("events").getJSONObject(0).getString("title"))
    }

    @Test
    fun `没有写权限时不能创建事件`() {
        val f = Fixture(source = FakeCalendarSource(write = false))
        val frame = f.call("calendar_create_event", createArgs())
        assertFalse(frame.ok())
        assertEquals("permission_denied", frame.code())
        assertNull(f.source.created)
    }

    @Test
    fun `创建成功后记录归属，提醒会去重排序`() {
        val f = Fixture()
        val frame = f.call(
            "calendar_create_event",
            createArgs()
                .put("reminder_minutes", JSONArray(listOf(60, 10, 60)))
                .put("rrule", "FREQ=WEEKLY;BYDAY=MO")
                .put("timezone", "Asia/Shanghai"),
        )
        assertTrue(frame.ok())
        assertEquals("42", frame.result().getString("event_id"))
        assertTrue(f.ownership.isOwned(42L))
        assertEquals(listOf(10, 60), f.source.created!!.reminderMinutes)
        assertEquals("FREQ=WEEKLY;BYDAY=MO", f.source.created!!.rrule)
        assertEquals("Asia/Shanghai", f.source.created!!.timezone)
    }

    @Test
    fun `rrule 必须是以 FREQ= 开头的规则`() {
        val f = Fixture()
        val frame = f.call("calendar_create_event", createArgs().put("rrule", "every monday"))
        assertFalse(frame.ok())
        assertEquals("invalid_arguments", frame.code())
    }

    @Test
    fun `只能修改 Hub 创建的事件`() {
        val f = Fixture()
        val refused = f.call(
            "calendar_update_event",
            JSONObject().put("event_id", "7").put("title", "改名"),
        )
        assertFalse(refused.ok())
        assertEquals("not_permitted", refused.code())
        assertNull("未授权时不该碰系统日历", f.source.updated)

        f.ownership.mark(7L)
        val allowed = f.call(
            "calendar_update_event",
            JSONObject().put("event_id", "7").put("title", "改名"),
        )
        assertTrue(allowed.ok())
        assertEquals(7L, f.source.updated!!.first)
        assertEquals("改名", f.source.updated!!.second.title)
    }

    @Test
    fun `删除必须先确认，且只删除 Hub 创建的事件`() {
        val f = Fixture()

        val notOwned = f.call(
            "calendar_delete_event",
            JSONObject().put("event_id", "8").put("confirm", true),
        )
        assertFalse(notOwned.ok())
        assertEquals("not_permitted", notOwned.code())
        assertTrue(f.source.deleted.isEmpty())

        f.ownership.mark(8L)
        val noConfirm = f.call("calendar_delete_event", JSONObject().put("event_id", "8"))
        assertFalse(noConfirm.ok())
        assertEquals("confirmation_required", noConfirm.code())
        assertTrue("未确认时不该删除", f.source.deleted.isEmpty())

        val deleted = f.call(
            "calendar_delete_event",
            JSONObject().put("event_id", "8").put("confirm", true),
        )
        assertTrue(deleted.ok())
        assertEquals(listOf(8L), f.source.deleted)
        assertFalse("删除后归属记录也要清掉", f.ownership.isOwned(8L))
    }

    @Test
    fun `事件被外部删除后，修改会拒绝并清掉本地归属，不碰系统日历`() {
        val f = Fixture()
        f.ownership.mark(21L)
        f.source.externallyDeleted += 21L

        val frame = f.call(
            "calendar_update_event",
            JSONObject().put("event_id", "21").put("title", "不该写入"),
        )
        assertFalse(frame.ok())
        assertEquals("tool_failed", frame.code())
        assertNull("目标不存在时不该发起系统更新", f.source.updated)
        assertFalse("过期的归属记录必须清掉，避免行 ID 复用后误伤", f.ownership.isOwned(21L))
    }

    @Test
    fun `事件被外部删除后，删除会拒绝并清掉本地归属`() {
        val f = Fixture()
        f.ownership.mark(22L)
        f.source.externallyDeleted += 22L

        val frame = f.call(
            "calendar_delete_event",
            JSONObject().put("event_id", "22").put("confirm", true),
        )
        assertFalse(frame.ok())
        assertEquals("tool_failed", frame.code())
        assertTrue("目标不存在时不该发起系统删除", f.source.deleted.isEmpty())
        assertFalse(f.ownership.isOwned(22L))
    }

    private fun createArgs(): JSONObject = JSONObject()
        .put("title", "牙医")
        .put("start_ms", start)
        .put("end_ms", end)
}
