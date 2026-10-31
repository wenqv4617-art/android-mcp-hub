package com.androidmcphub.app.capability

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 3C：备忘与待办的完整 CRUD（走真实分发器，不用直接调 execute）。
 *
 * 走分发器是有意的：这些测试同时验证参数白名单、确认机制与错误码，
 * 而那三件事都发生在 [CapabilityDispatcher] 里 —— 直接调 `execute` 会漏掉。
 */
class MemoTodoCapabilityTest {

    private val now = 1_700_000_000_000L

    private fun dispatcher(): CapabilityDispatcher {
        val memos = MemoryJsonStore("memo_")
        val todos = MemoryJsonStore("todo_")
        return CapabilityDispatcher(
            capabilities = listOf(
                MemoCapability(memos, clock = { now }),
                MemoListCapability(memos),
                MemoGetCapability(memos),
                MemoUpdateCapability(memos, clock = { now + 1 }),
                MemoDeleteCapability(memos),
                TodoCreateCapability(todos, clock = { now }),
                TodoListCapability(todos, clock = { now }),
                TodoUpdateCapability(todos, clock = { now + 1 }),
                TodoCompleteCapability(todos, clock = { now + 2 }),
                TodoDeleteCapability(todos),
            ),
            clock = { now },
        )
    }

    private var requestSeq = 0

    private fun CapabilityDispatcher.call(
        tool: String,
        vararg args: Pair<String, Any>,
    ): JSONObject {
        val arguments = JSONObject()
        args.forEach { (key, value) -> arguments.put(key, value) }
        requestSeq += 1
        val outcome = handle("tr-$requestSeq", tool, arguments)
        assertFalse("不该被判为重复请求", outcome.duplicate)
        val frame = outcome.frame
        assertNotNull("$tool 没有产生结果帧", frame)
        return JSONObject(frame!!)
    }

    private fun JSONObject.isOk(): Boolean = getBoolean("ok")

    private fun JSONObject.errorCode(): String = getJSONObject("error").getString("code")

    private fun JSONObject.result(): JSONObject = getJSONObject("result")

    // ---- 备忘 --------------------------------------------------------------

    @Test
    fun `备忘的创建、列表、读取、修改与删除`() {
        val dispatcher = dispatcher()

        val created = dispatcher.call("memo_create", "title" to "购物", "body" to "牛奶")
        assertTrue(created.isOk())
        val id = created.result().getString("id")
        assertEquals("memo_1", id)

        val listed = dispatcher.call("memo_list", "query" to "购物")
        assertTrue(listed.isOk())
        assertEquals(1, listed.result().getInt("total"))
        assertEquals(id, listed.result().getJSONArray("memos").getJSONObject(0).getString("id"))

        val got = dispatcher.call("memo_get", "id" to id)
        assertTrue(got.isOk())
        assertEquals("牛奶", got.result().getString("body"))

        val updated = dispatcher.call("memo_update", "id" to id, "title" to "采购")
        assertTrue(updated.isOk())
        assertEquals("采购", dispatcher.call("memo_get", "id" to id).result().getString("title"))

        // 不带 confirm：必须拒绝，而且记录还在。
        val refused = dispatcher.call("memo_delete", "id" to id)
        assertFalse(refused.isOk())
        assertEquals("confirmation_required", refused.errorCode())
        assertTrue(dispatcher.call("memo_get", "id" to id).isOk())

        val deleted = dispatcher.call("memo_delete", "id" to id, "confirm" to true)
        assertTrue(deleted.isOk())
        val gone = dispatcher.call("memo_get", "id" to id)
        assertFalse(gone.isOk())
        assertEquals("invalid_arguments", gone.errorCode())
    }

    @Test
    fun `备忘的字段上限与参数白名单`() {
        val dispatcher = dispatcher()

        val tooLong = dispatcher.call("memo_create", "title" to "x".repeat(201))
        assertFalse(tooLong.isOk())
        assertEquals("invalid_arguments", tooLong.errorCode())

        val blankTitle = dispatcher.call("memo_create", "title" to "   ")
        assertFalse(blankTitle.isOk())
        assertEquals("invalid_arguments", blankTitle.errorCode())

        // 未声明的参数不能被悄悄忽略。
        val extra = dispatcher.call("memo_create", "title" to "ok", "body" to "b", "author" to "me")
        assertFalse(extra.isOk())
        assertEquals("invalid_arguments", extra.errorCode())

        // 非法 ID 形状在读路径上也要被挡住。
        val badId = dispatcher.call("memo_get", "id" to "memo_x")
        assertFalse(badId.isOk())
        assertEquals("invalid_arguments", badId.errorCode())
    }

    @Test
    fun `备忘列表的摘要不返回完整正文`() {
        val dispatcher = dispatcher()
        val body = "a".repeat(MemoCapability.PREVIEW_LENGTH + 50)
        dispatcher.call("memo_create", "title" to "长文", "body" to body)

        val item = dispatcher.call("memo_list")
            .result().getJSONArray("memos").getJSONObject(0)
        assertFalse("列表不该带完整正文", item.has("body"))
        assertEquals(MemoCapability.PREVIEW_LENGTH + 1, item.getString("preview").length)
        assertTrue(item.getString("preview").endsWith("…"))
    }

    // ---- 待办 --------------------------------------------------------------

    @Test
    fun `待办的创建、排序、完成与删除`() {
        val dispatcher = dispatcher()

        val later = dispatcher.call(
            "todo_create",
            "title" to "晚一点",
            "due_at_ms" to (now + 86_400_000L),
            "priority" to "low",
        )
        val sooner = dispatcher.call(
            "todo_create",
            "title" to "先做",
            "due_at_ms" to (now + 3_600_000L),
            "priority" to "high",
        )
        assertTrue(later.isOk())
        assertTrue(sooner.isOk())
        val soonerId = sooner.result().getString("id")

        val listed = dispatcher.call("todo_list")
        assertTrue(listed.isOk())
        val todos = listed.result().getJSONArray("todos")
        assertEquals(2, todos.length())
        assertEquals("先做", todos.getJSONObject(0).getString("title"))
        assertFalse(todos.getJSONObject(0).getBoolean("overdue"))

        val completed = dispatcher.call("todo_complete", "id" to soonerId)
        assertTrue(completed.isOk())
        assertEquals("done", completed.result().getString("status"))
        assertNotNull(completed.result().get("completed_at_ms"))

        // 完成后默认排在未完成之后。
        val reopened = dispatcher.call("todo_list", "status" to "open")
        assertEquals(1, reopened.result().getInt("total"))
        assertEquals("晚一点", reopened.result().getJSONArray("todos").getJSONObject(0).getString("title"))

        val refused = dispatcher.call("todo_delete", "id" to soonerId)
        assertEquals("confirmation_required", refused.errorCode())
        val deleted = dispatcher.call("todo_delete", "id" to soonerId, "confirm" to true)
        assertTrue(deleted.isOk())
        assertEquals(1, dispatcher.call("todo_list").result().getInt("total"))
    }

    @Test
    fun `待办的优先级与状态是闭集`() {
        val dispatcher = dispatcher()

        val badPriority = dispatcher.call("todo_create", "title" to "x", "priority" to "urgent")
        assertFalse(badPriority.isOk())
        assertEquals("invalid_arguments", badPriority.errorCode())

        val badStatus = dispatcher.call("todo_list", "status" to "archived")
        assertFalse(badStatus.isOk())
        assertEquals("invalid_arguments", badStatus.errorCode())

        val created = dispatcher.call("todo_create", "title" to "过期任务", "due_at_ms" to (now - 1))
        val id = created.result().getString("id")
        val overdue = dispatcher.call("todo_update", "id" to id, "priority" to "high")
        assertTrue(overdue.isOk())
        assertEquals("high", overdue.result().getString("priority"))
    }

    @Test
    fun `修改必须至少提供一项，空修改被拒绝`() {
        val dispatcher = dispatcher()
        val created = dispatcher.call("memo_create", "title" to "t")
        val id = created.result().getString("id")

        val empty = dispatcher.call("memo_update", "id" to id)
        assertFalse(empty.isOk())
        assertEquals("invalid_arguments", empty.errorCode())
    }
}
