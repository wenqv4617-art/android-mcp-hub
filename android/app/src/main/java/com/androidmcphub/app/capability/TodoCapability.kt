package com.androidmcphub.app.capability

import com.androidmcphub.app.protocol.ToolResultErrorCode
import org.json.JSONArray
import org.json.JSONObject

/** 待办的公共常量与字段读取。 */
internal object TodoFields {
    const val MAX_TITLE = 200
    const val MAX_NOTES = 2000
    const val MAX_LIST = 50
    const val PREVIEW_LENGTH = 100
    val PRIORITIES: Set<String> = setOf("low", "normal", "high")
    val STATUSES: Set<String> = setOf("open", "done")
    val ID_PATTERN = Regex("^todo_[0-9]{1,12}$")

    fun parseId(arguments: JSONObject): String {
        val id = CapabilityArgs.requiredString(arguments, "id", 32, allowBlank = false)
        if (!ID_PATTERN.matches(id)) throw ArgumentProblem("参数 id 格式不正确")
        return id
    }

    /** 对外的字段集合：把内部存储的英文枚举映射成同样的英文（协议里不翻译）。 */
    fun view(todo: JSONObject, nowMs: Long): JSONObject {
        val status = todo.optString("status", "open")
        val due = todo.optLong("due_at_ms", 0L).takeIf { it > 0 }
        return JSONObject()
            .put("id", todo.optString("id"))
            .put("title", todo.optString("title"))
            .put("notes", todo.optString("notes"))
            .put("due_at_ms", due ?: JSONObject.NULL)
            .put("priority", todo.optString("priority", "normal"))
            .put("status", status)
            .put(
                "overdue",
                status == "open" && due != null && due < nowMs,
            )
            .put("created_at_ms", todo.optLong("created_at_ms"))
            .put("updated_at_ms", todo.optLong("updated_at_ms"))
            .put("completed_at_ms", todo.optLong("completed_at_ms", 0L).takeIf { it > 0 } ?: JSONObject.NULL)
    }
}

/** `todo_create`。 */
class TodoCreateCapability(
    private val store: JsonStore,
    private val clock: () -> Long = System::currentTimeMillis,
) : Capability {

    override val name: String = NAME
    override val metadata: CapabilityMetadata = METADATA
    override val allowedArguments: Set<String> = setOf("title", "notes", "due_at_ms", "priority")

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
            .put("notes", spec.notes)
            .put("due_at_ms", spec.dueAtMs ?: 0L)
            .put("priority", spec.priority)
            .put("status", "open")
            .put("created_at_ms", now)
            .put("updated_at_ms", now)
            .put("completed_at_ms", 0L)
        return try {
            val id = store.insert(item)
            CapabilityResult.Success(
                JSONObject()
                    .put("id", id)
                    .put("title", spec.title)
                    .put("priority", spec.priority)
                    .put("due_at_ms", spec.dueAtMs ?: JSONObject.NULL),
            )
        } catch (t: Throwable) {
            CapabilityResult.Failure(ToolResultErrorCode.TOOL_FAILED, "保存待办失败（${t.javaClass.simpleName}）")
        }
    }

    private data class Spec(val title: String, val notes: String, val dueAtMs: Long?, val priority: String)

    private fun parse(arguments: JSONObject): Spec = Spec(
        title = CapabilityArgs.requiredString(arguments, "title", TodoFields.MAX_TITLE, allowBlank = false),
        notes = CapabilityArgs.optionalString(arguments, "notes", TodoFields.MAX_NOTES) ?: "",
        dueAtMs = CapabilityArgs.optionalLong(arguments, "due_at_ms"),
        priority = CapabilityArgs.enumeration(arguments, "priority", TodoFields.PRIORITIES, default = "normal"),
    )

    companion object {
        const val NAME = "todo_create"

        val METADATA: CapabilityMetadata = CapabilityMetadata(
            name = NAME,
            displayName = "新建待办",
            description = "创建一条待办（标题、备注、截止时间、优先级）。纯本地存储。",
            category = CapabilityCategory.Productivity,
            arguments = listOf(
                CapabilityArgument("title", "string", true, "待办标题，1–200 字。"),
                CapabilityArgument("notes", "string", false, "备注，最多 2000 字。"),
                CapabilityArgument("due_at_ms", "integer", false, "截止时间（Unix 毫秒），可省略。"),
                CapabilityArgument("priority", "string", false, "优先级：low / normal（默认）/ high。"),
            ),
            readOnly = false,
        )
    }
}

/** `todo_list`：列出待办，可按状态/关键字过滤。 */
class TodoListCapability(
    private val store: JsonStore,
    private val clock: () -> Long = System::currentTimeMillis,
) : Capability {

    override val name: String = NAME
    override val metadata: CapabilityMetadata = METADATA
    override val allowedArguments: Set<String> = setOf("status", "query", "offset", "limit")

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
            return CapabilityResult.Failure(ToolResultErrorCode.TOOL_FAILED, "读取待办失败（${t.javaClass.simpleName}）")
        }
            .filter { query.status == null || it.optString("status", "open") == query.status }
            .filter { todo ->
                query.text.isEmpty() ||
                    todo.optString("title").contains(query.text, ignoreCase = true) ||
                    todo.optString("notes").contains(query.text, ignoreCase = true)
            }
            .sortedWith(
                compareBy<JSONObject> { it.optString("status", "open") != "open" }
                    .thenBy { it.optLong("due_at_ms", Long.MAX_VALUE).takeIf { due -> due > 0 } ?: Long.MAX_VALUE }
                    .thenByDescending { it.optLong("created_at_ms") },
            )

        val page = all.drop(query.offset).take(query.limit)
        val items = JSONArray()
        for (todo in page) items.put(TodoFields.view(todo, now))
        return CapabilityResult.Success(
            JSONObject()
                .put("total", all.size)
                .put("offset", query.offset)
                .put("limit", query.limit)
                .put("returned", items.length())
                .put("has_more", all.size > query.offset + items.length())
                .put("todos", items),
        )
    }

    private data class Query(val status: String?, val text: String, val offset: Int, val limit: Int)

    private fun parse(arguments: JSONObject): Query = Query(
        status = CapabilityArgs.optionalString(arguments, "status", 16, allowBlank = false)?.lowercase()?.also {
            if (it !in TodoFields.STATUSES) throw ArgumentProblem("参数 status 只能是：open, done")
        },
        text = CapabilityArgs.optionalString(arguments, "query", 100)?.trim() ?: "",
        offset = CapabilityArgs.intInRange(arguments, "offset", 0..100_000, default = 0),
        limit = CapabilityArgs.intInRange(arguments, "limit", 1..TodoFields.MAX_LIST, default = 20),
    )

    companion object {
        const val NAME = "todo_list"

        val METADATA: CapabilityMetadata = CapabilityMetadata(
            name = NAME,
            displayName = "待办列表",
            description = "列出本机待办（未完成在前，按截止时间排序），可按状态与关键字过滤。",
            category = CapabilityCategory.Productivity,
            arguments = listOf(
                CapabilityArgument("status", "string", false, "只看 open 或 done；不传表示全部。"),
                CapabilityArgument("query", "string", false, "可选关键字（标题或备注包含即命中）。"),
                CapabilityArgument("offset", "integer", false, "跳过多少条，默认 0。"),
                CapabilityArgument("limit", "integer", false, "返回条数，1–50，默认 20。"),
            ),
            readOnly = true,
        )
    }
}

/** `todo_update`：修改标题/备注/截止时间/优先级（至少一项）。 */
class TodoUpdateCapability(
    private val store: JsonStore,
    private val clock: () -> Long = System::currentTimeMillis,
) : Capability {

    override val name: String = NAME
    override val metadata: CapabilityMetadata = METADATA
    override val allowedArguments: Set<String> = setOf("id", "title", "notes", "due_at_ms", "priority")

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
            return CapabilityResult.Failure(ToolResultErrorCode.TOOL_FAILED, "读取待办失败（${t.javaClass.simpleName}）")
        } ?: return CapabilityResult.Failure(ToolResultErrorCode.INVALID_ARGUMENTS, "找不到这条待办（${update.id}）")

        val now = clock()
        if (update.title != null) current.put("title", update.title)
        if (update.notes != null) current.put("notes", update.notes)
        if (update.dueAtMs != null) current.put("due_at_ms", update.dueAtMs)
        if (update.priority != null) current.put("priority", update.priority)
        current.put("updated_at_ms", now)
        return try {
            store.replace(update.id, current)
            CapabilityResult.Success(TodoFields.view(current, now))
        } catch (t: Throwable) {
            CapabilityResult.Failure(ToolResultErrorCode.TOOL_FAILED, "保存待办失败（${t.javaClass.simpleName}）")
        }
    }

    private data class Update(
        val id: String,
        val title: String?,
        val notes: String?,
        val dueAtMs: Long?,
        val priority: String?,
    )

    private fun parse(arguments: JSONObject): Update {
        val id = TodoFields.parseId(arguments)
        val title = CapabilityArgs.optionalString(arguments, "title", TodoFields.MAX_TITLE, allowBlank = false)
        val notes = CapabilityArgs.optionalString(arguments, "notes", TodoFields.MAX_NOTES)
        val due = CapabilityArgs.optionalLong(arguments, "due_at_ms")
        val priority = CapabilityArgs.optionalString(arguments, "priority", 16, allowBlank = false)?.let {
            val normalized = it.lowercase()
            if (normalized !in TodoFields.PRIORITIES) {
                throw ArgumentProblem("参数 priority 只能是：low, normal, high")
            }
            normalized
        }
        if (title == null && notes == null && due == null && priority == null) {
            throw ArgumentProblem("至少要修改 title / notes / due_at_ms / priority 中的一项")
        }
        return Update(id, title, notes, due, priority)
    }

    companion object {
        const val NAME = "todo_update"

        val METADATA: CapabilityMetadata = CapabilityMetadata(
            name = NAME,
            displayName = "修改待办",
            description = "修改待办的标题、备注、截止时间或优先级（至少提供一项）。",
            category = CapabilityCategory.Productivity,
            arguments = listOf(
                CapabilityArgument("id", "string", true, "待办 ID。"),
                CapabilityArgument("title", "string", false, "新标题，1–200 字。"),
                CapabilityArgument("notes", "string", false, "新备注，最多 2000 字。"),
                CapabilityArgument("due_at_ms", "integer", false, "新截止时间（Unix 毫秒）。"),
                CapabilityArgument("priority", "string", false, "low / normal / high。"),
            ),
            readOnly = false,
        )
    }
}

/** `todo_complete`：把待办标为完成或重新打开。 */
class TodoCompleteCapability(
    private val store: JsonStore,
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
            return CapabilityResult.Failure(ToolResultErrorCode.TOOL_FAILED, "读取待办失败（${t.javaClass.simpleName}）")
        } ?: return CapabilityResult.Failure(ToolResultErrorCode.INVALID_ARGUMENTS, "找不到这条待办（${spec.id}）")

        val now = clock()
        current.put("status", if (spec.done) "done" else "open")
        current.put("completed_at_ms", if (spec.done) now else 0L)
        current.put("updated_at_ms", now)
        return try {
            store.replace(spec.id, current)
            CapabilityResult.Success(TodoFields.view(current, now))
        } catch (t: Throwable) {
            CapabilityResult.Failure(ToolResultErrorCode.TOOL_FAILED, "保存待办失败（${t.javaClass.simpleName}）")
        }
    }

    private data class Spec(val id: String, val done: Boolean)

    private fun parse(arguments: JSONObject): Spec = Spec(
        id = TodoFields.parseId(arguments),
        done = CapabilityArgs.optionalBoolean(arguments, "done") ?: true,
    )

    companion object {
        const val NAME = "todo_complete"

        val METADATA: CapabilityMetadata = CapabilityMetadata(
            name = NAME,
            displayName = "完成待办",
            description = "把待办标记为完成（done=true，默认）或重新打开（done=false）。",
            category = CapabilityCategory.Productivity,
            arguments = listOf(
                CapabilityArgument("id", "string", true, "待办 ID。"),
                CapabilityArgument("done", "boolean", false, "true=完成（默认），false=重新打开。"),
            ),
            readOnly = false,
        )
    }
}

/** `todo_delete`：删除待办，必须 `confirm=true`。 */
class TodoDeleteCapability(private val store: JsonStore) : Capability {

    override val name: String = NAME
    override val metadata: CapabilityMetadata = METADATA
    override val allowedArguments: Set<String> = setOf("id", "confirm")

    override fun validate(arguments: JSONObject): String? = validated(arguments) { TodoFields.parseId(arguments) }.second

    override fun confirmationProblem(arguments: JSONObject): String? {
        val confirm = try {
            CapabilityArgs.optionalBoolean(arguments, "confirm")
        } catch (_: ArgumentProblem) {
            return null
        }
        return if (confirm == true) null else "删除待办不可恢复，需要调用方显式传 confirm=true 才能执行。"
    }

    override fun execute(arguments: JSONObject): CapabilityResult {
        val id = try {
            TodoFields.parseId(arguments)
        } catch (problem: ArgumentProblem) {
            return CapabilityResult.Failure(ToolResultErrorCode.INVALID_ARGUMENTS, problem.message ?: "参数不合法")
        }
        return try {
            if (store.delete(id)) {
                CapabilityResult.Success(JSONObject().put("deleted", true).put("id", id))
            } else {
                CapabilityResult.Failure(ToolResultErrorCode.INVALID_ARGUMENTS, "找不到这条待办（$id）")
            }
        } catch (t: Throwable) {
            CapabilityResult.Failure(ToolResultErrorCode.TOOL_FAILED, "删除待办失败（${t.javaClass.simpleName}）")
        }
    }

    companion object {
        const val NAME = "todo_delete"

        val METADATA: CapabilityMetadata = CapabilityMetadata(
            name = NAME,
            displayName = "删除待办",
            description = "永久删除一条待办。必须先传 confirm=true；删除后无法恢复。",
            category = CapabilityCategory.Productivity,
            arguments = listOf(
                CapabilityArgument("id", "string", true, "待办 ID。"),
                CapabilityArgument("confirm", "boolean", true, "必须为 true，表示确认删除。"),
            ),
            readOnly = false,
            destructive = true,
        )
    }
}
