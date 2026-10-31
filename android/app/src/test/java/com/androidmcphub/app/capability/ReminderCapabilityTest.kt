package com.androidmcphub.app.capability

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 3B：本地提醒。
 *
 * 定时器用假实现：[FakeScheduler] 只记录"排了什么、取消了什么"，
 * 所以这些断言验证的是**能力层的决定**（该不该排、排在哪、失败怎么办），
 * 而不是 AlarmManager 本身 —— 后者只能在真机上验证。
 */
class ReminderCapabilityTest {

    private val now = 1_700_000_000_000L

    private class FakeScheduler(var failNext: Boolean = false) : ReminderScheduler {
        val scheduled = mutableListOf<Triple<String, Long, String>>()
        val cancelled = mutableListOf<String>()

        override fun schedule(id: String, atMs: Long, title: String, note: String): ControlOutcome {
            if (failNext) return ControlOutcome.failed("测试注入的排程失败")
            scheduled += Triple(id, atMs, title)
            return ControlOutcome.success(JSONObject().put("scheduled_at_ms", atMs))
        }

        override fun cancel(id: String) {
            cancelled += id
        }

        override fun canNotify(): Boolean = true
    }

    private class Fixture {
        val store = MemoryJsonStore("reminder_")
        val scheduler = FakeScheduler()
        val dispatcher = CapabilityDispatcher(
            capabilities = listOf(
                ReminderCreateCapability(store, scheduler, clock = { 1_700_000_000_000L }),
                ReminderListCapability(store, clock = { 1_700_000_000_000L }),
                ReminderUpdateCapability(store, scheduler, clock = { 1_700_000_000_000L }),
                ReminderCompleteCapability(store, scheduler, clock = { 1_700_000_000_000L }),
                ReminderDeleteCapability(store, scheduler),
            ),
        )
        var seq = 0

        fun call(tool: String, vararg args: Pair<String, Any>): JSONObject {
            val arguments = JSONObject()
            args.forEach { (key, value) -> arguments.put(key, value) }
            seq += 1
            val outcome = dispatcher.handle("tr-$seq", tool, arguments)
            assertFalse(outcome.duplicate)
            val frame = outcome.frame
            assertNotNull("$tool 没有产生结果帧", frame)
            return JSONObject(frame!!)
        }
    }

    private fun JSONObject.ok(): Boolean = getBoolean("ok")
    private fun JSONObject.result(): JSONObject = getJSONObject("result")
    private fun JSONObject.code(): String = getJSONObject("error").getString("code")

    private fun Fixture.createFuture(title: String = "喝水"): String {
        val created = call("reminder_create", "title" to title, "at_ms" to (now + 60_000L))
        assertTrue(created.ok())
        return created.result().getString("id")
    }

    @Test
    fun `创建提醒会写入存储并排程到指定时间`() {
        val f = Fixture()
        val id = f.createFuture()

        assertEquals(1, f.scheduler.scheduled.size)
        assertEquals(id, f.scheduler.scheduled[0].first)
        assertEquals(now + 60_000L, f.scheduler.scheduled[0].second)

        val listed = f.call("reminder_list")
        assertTrue(listed.ok())
        assertEquals(1, listed.result().getInt("total"))
        assertEquals("scheduled", listed.result().getJSONArray("reminders").getJSONObject(0).getString("status"))
    }

    @Test
    fun `过去的时间被拒绝，且不会留下记录或排程`() {
        val f = Fixture()
        val rejected = f.call("reminder_create", "title" to "过去", "at_ms" to (now - 1))

        assertFalse(rejected.ok())
        assertEquals("invalid_arguments", rejected.code())
        assertTrue(f.scheduler.scheduled.isEmpty())
        assertEquals(0, f.call("reminder_list").result().getInt("total"))
    }

    @Test
    fun `排程失败时撤回刚写入的记录`() {
        val f = Fixture()
        f.scheduler.failNext = true
        val failed = f.call("reminder_create", "title" to "排不上", "at_ms" to (now + 60_000L))

        assertFalse(failed.ok())
        assertEquals("tool_failed", failed.code())
        assertEquals(0, f.call("reminder_list").result().getInt("total"))
    }

    @Test
    fun `修改时间会重新排程`() {
        val f = Fixture()
        val id = f.createFuture()
        val newTime = now + 120_000L

        val updated = f.call("reminder_update", "id" to id, "title" to "多喝水", "at_ms" to newTime)
        assertTrue(updated.ok())
        assertEquals(newTime, updated.result().getLong("at_ms"))
        assertEquals(2, f.scheduler.scheduled.size)
        assertEquals(newTime, f.scheduler.scheduled.last().second)
        assertEquals("多喝水", f.scheduler.scheduled.last().third)
    }

    @Test
    fun `完成后取消排程，重新打开需要未来时间`() {
        val f = Fixture()
        val id = f.createFuture()

        val done = f.call("reminder_complete", "id" to id)
        assertTrue(done.ok())
        assertEquals("done", done.result().getString("status"))
        assertEquals(listOf(id), f.scheduler.cancelled)

        // 时间已过（这里 now 不变，但完成后再打开会重新检查 at_ms —— 它是未来，因此允许）。
        val reopened = f.call("reminder_complete", "id" to id, "done" to false)
        assertTrue(reopened.ok())
        assertEquals("scheduled", reopened.result().getString("status"))
        assertEquals(2, f.scheduler.scheduled.size)
    }

    @Test
    fun `删除必须先确认，确认后才取消排程`() {
        val f = Fixture()
        val id = f.createFuture()

        val refused = f.call("reminder_delete", "id" to id)
        assertFalse(refused.ok())
        assertEquals("confirmation_required", refused.code())
        assertTrue("未确认时不该动系统的闹钟", f.scheduler.cancelled.isEmpty())

        val deleted = f.call("reminder_delete", "id" to id, "confirm" to true)
        assertTrue(deleted.ok())
        assertEquals(listOf(id), f.scheduler.cancelled)
        assertEquals(0, f.call("reminder_list").result().getInt("total"))
    }

    @Test
    fun `状态过滤只接受闭集里的值`() {
        val f = Fixture()
        f.createFuture()
        val bad = f.call("reminder_list", "status" to "pending")
        assertFalse(bad.ok())
        assertEquals("invalid_arguments", bad.code())
    }
}
