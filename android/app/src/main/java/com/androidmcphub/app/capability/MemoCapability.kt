package com.androidmcphub.app.capability

import com.androidmcphub.app.protocol.ToolResultErrorCode
import org.json.JSONArray
import org.json.JSONObject

/**
 * 本机备忘（纯本地存储，不依赖任何第三方应用或云服务）。
 *
 * 为什么是"一个工具组"而不是一个万能 `memo` 工具：MCP 客户端靠名字 + schema
 * 发现能力，一个带 `action` 参数的万能工具会让模型更容易写错参数，
 * 也让"删除"这种高影响操作藏在一个普通字符串值后面。拆开之后，每个动作的
 * 必填项与高影响标记都是明确的。
 *
 * 数据只存在应用私有目录，`allowBackup=false`，卸载即消失；没有导出接口。
 */
class MemoCapability(
    private val store: JsonStore,
    private val clock: () -> Long = System::currentTimeMillis,
) : Capability {

    override val name: String = NAME
    override val metadata: CapabilityMetadata = METADATA
    override val allowedArguments: Set<String> = setOf("title", "body")

    override fun validate(arguments: JSONObject): String? = validated(arguments) { parseCreate(arguments) }.second

    override fun execute(arguments: JSONObject): CapabilityResult {
        val create = try {
            parseCreate(arguments)
        } catch (problem: ArgumentProblem) {
            return CapabilityResult.Failure(ToolResultErrorCode.INVALID_ARGUMENTS, problem.message ?: "参数不合法")
        }
        val now = clock()
        val item = JSONObject()
            .put("title", create.title)
            .put("body", create.body)
            .put("created_at_ms", now)
            .put("updated_at_ms", now)
        return try {
            val id = store.insert(item)
            CapabilityResult.Success(JSONObject().put("id", id).put("title", create.title).put("created_at_ms", now))
        } catch (t: Throwable) {
            CapabilityResult.Failure(ToolResultErrorCode.TOOL_FAILED, "保存备忘失败（${t.javaClass.simpleName}）")
        }
    }

    private data class Create(val title: String, val body: String)

    private fun parseCreate(arguments: JSONObject): Create = Create(
        title = CapabilityArgs.requiredString(arguments, "title", MAX_TITLE, allowBlank = false),
        body = CapabilityArgs.optionalString(arguments, "body", MAX_BODY) ?: "",
    )

    companion object {
        const val NAME = "memo_create"
        const val MAX_TITLE = 200
        const val MAX_BODY = 4000
        const val MAX_LIST = 50
        const val PREVIEW_LENGTH = 120

        val METADATA: CapabilityMetadata = CapabilityMetadata(
            name = NAME,
            displayName = "新建备忘",
            description = "在本机创建一条备忘（标题 + 正文，纯本地存储）。标题最多 200 字，正文最多 4000 字。",
            category = CapabilityCategory.Productivity,
            arguments = listOf(
                CapabilityArgument("title", "string", true, "备忘标题，1–200 字。"),
                CapabilityArgument("body", "string", false, "备忘正文，最多 4000 字。"),
            ),
            readOnly = false,
        )
    }
}

/** `memo_list`：按更新时间倒序返回摘要，支持分页与关键字过滤。 */
class MemoListCapability(private val store: JsonStore) : Capability {

    override val name: String = NAME
    override val metadata: CapabilityMetadata = METADATA
    override val allowedArguments: Set<String> = setOf("query", "offset", "limit")

    override fun validate(arguments: JSONObject): String? = validated(arguments) { parse(arguments) }.second

    override fun execute(arguments: JSONObject): CapabilityResult {
        val query = try {
            parse(arguments)
        } catch (problem: ArgumentProblem) {
            return CapabilityResult.Failure(ToolResultErrorCode.INVALID_ARGUMENTS, problem.message ?: "参数不合法")
        }
        val all = try {
            store.list()
        } catch (t: Throwable) {
            return CapabilityResult.Failure(ToolResultErrorCode.TOOL_FAILED, "读取备忘失败（${t.javaClass.simpleName}）")
        }
        .filter { memo ->
            query.text.isEmpty() ||
                memo.optString("title").contains(query.text, ignoreCase = true) ||
                memo.optString("body").contains(query.text, ignoreCase = true)
        }
        .sortedByDescending { it.optLong("updated_at_ms") }

        val page = all.drop(query.offset).take(query.limit)
        val items = JSONArray()
        for (memo in page) {
            items.put(
                JSONObject()
                    .put("id", memo.optString("id"))
                    .put("title", memo.optString("title"))
                    .put("preview", preview(memo.optString("body")))
                    .put("created_at_ms", memo.optLong("created_at_ms"))
                    .put("updated_at_ms", memo.optLong("updated_at_ms")),
            )
        }
        return CapabilityResult.Success(
            JSONObject()
                .put("total", all.size)
                .put("offset", query.offset)
                .put("limit", query.limit)
                .put("returned", items.length())
                .put("has_more", all.size > query.offset + items.length())
                .put("memos", items),
        )
    }

    private data class Query(val text: String, val offset: Int, val limit: Int)

    private fun parse(arguments: JSONObject): Query = Query(
        text = CapabilityArgs.optionalString(arguments, "query", 100)?.trim() ?: "",
        offset = CapabilityArgs.intInRange(arguments, "offset", 0..100_000, default = 0),
        limit = CapabilityArgs.intInRange(arguments, "limit", 1..MemoCapability.MAX_LIST, default = 20),
    )

    private fun preview(body: String): String =
        if (body.length <= MemoCapability.PREVIEW_LENGTH) body
        else body.substring(0, MemoCapability.PREVIEW_LENGTH) + "…"

    companion object {
        const val NAME = "memo_list"

        val METADATA: CapabilityMetadata = CapabilityMetadata(
            name = NAME,
            displayName = "备忘列表",
            description = "列出本机备忘（摘要，不含完整正文），可按关键字过滤并分页。",
            category = CapabilityCategory.Productivity,
            arguments = listOf(
                CapabilityArgument("query", "string", false, "可选关键字（标题或正文包含即命中）。"),
                CapabilityArgument("offset", "integer", false, "跳过多少条，默认 0。"),
                CapabilityArgument("limit", "integer", false, "返回条数，1–50，默认 20。"),
            ),
            readOnly = true,
        )
    }
}

/** `memo_get`：按 ID 读取完整正文。 */
class MemoGetCapability(private val store: JsonStore) : Capability {

    override val name: String = NAME
    override val metadata: CapabilityMetadata = METADATA
    override val allowedArguments: Set<String> = setOf("id")

    override fun validate(arguments: JSONObject): String? = validated(arguments) { parseId(arguments) }.second

    override fun execute(arguments: JSONObject): CapabilityResult {
        val id = try {
            parseId(arguments)
        } catch (problem: ArgumentProblem) {
            return CapabilityResult.Failure(ToolResultErrorCode.INVALID_ARGUMENTS, problem.message ?: "参数不合法")
        }
        val memo = try {
            store.get(id)
        } catch (t: Throwable) {
            return CapabilityResult.Failure(ToolResultErrorCode.TOOL_FAILED, "读取备忘失败（${t.javaClass.simpleName}）")
        } ?: return CapabilityResult.Failure(ToolResultErrorCode.INVALID_ARGUMENTS, "找不到这条备忘（$id）")

        return CapabilityResult.Success(
            JSONObject()
                .put("id", memo.optString("id"))
                .put("title", memo.optString("title"))
                .put("body", memo.optString("body"))
                .put("created_at_ms", memo.optLong("created_at_ms"))
                .put("updated_at_ms", memo.optLong("updated_at_ms")),
        )
    }

    private fun parseId(arguments: JSONObject): String {
        val id = CapabilityArgs.requiredString(arguments, "id", 32, allowBlank = false)
        if (!ID_PATTERN.matches(id)) throw ArgumentProblem("参数 id 格式不正确")
        return id
    }

    companion object {
        const val NAME = "memo_get"
        val ID_PATTERN = Regex("^memo_[0-9]{1,12}$")

        val METADATA: CapabilityMetadata = CapabilityMetadata(
            name = NAME,
            displayName = "查看备忘",
            description = "按 ID 读取一条备忘的完整标题与正文。",
            category = CapabilityCategory.Productivity,
            arguments = listOf(CapabilityArgument("id", "string", true, "备忘 ID，例如 memo_1。")),
            readOnly = true,
        )
    }
}

/** `memo_update`：修改标题/正文（至少给一项）。 */
class MemoUpdateCapability(
    private val store: JsonStore,
    private val clock: () -> Long = System::currentTimeMillis,
) : Capability {

    override val name: String = NAME
    override val metadata: CapabilityMetadata = METADATA
    override val allowedArguments: Set<String> = setOf("id", "title", "body")

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
            return CapabilityResult.Failure(ToolResultErrorCode.TOOL_FAILED, "读取备忘失败（${t.javaClass.simpleName}）")
        } ?: return CapabilityResult.Failure(ToolResultErrorCode.INVALID_ARGUMENTS, "找不到这条备忘（${update.id}）")

        val now = clock()
        if (update.title != null) current.put("title", update.title)
        if (update.body != null) current.put("body", update.body)
        current.put("updated_at_ms", now)
        return try {
            store.replace(update.id, current)
            CapabilityResult.Success(JSONObject().put("id", update.id).put("updated_at_ms", now))
        } catch (t: Throwable) {
            CapabilityResult.Failure(ToolResultErrorCode.TOOL_FAILED, "保存备忘失败（${t.javaClass.simpleName}）")
        }
    }

    private data class Update(val id: String, val title: String?, val body: String?)

    private fun parse(arguments: JSONObject): Update {
        val id = CapabilityArgs.requiredString(arguments, "id", 32, allowBlank = false)
        if (!MemoGetCapability.ID_PATTERN.matches(id)) throw ArgumentProblem("参数 id 格式不正确")
        val title = CapabilityArgs.optionalString(arguments, "title", MemoCapability.MAX_TITLE, allowBlank = false)
        val body = CapabilityArgs.optionalString(arguments, "body", MemoCapability.MAX_BODY)
        if (title == null && body == null) throw ArgumentProblem("至少要修改 title 或 body 中的一项")
        return Update(id, title, body)
    }

    companion object {
        const val NAME = "memo_update"

        val METADATA: CapabilityMetadata = CapabilityMetadata(
            name = NAME,
            displayName = "修改备忘",
            description = "修改一条备忘的标题或正文（至少提供一项）。",
            category = CapabilityCategory.Productivity,
            arguments = listOf(
                CapabilityArgument("id", "string", true, "备忘 ID。"),
                CapabilityArgument("title", "string", false, "新标题，1–200 字。"),
                CapabilityArgument("body", "string", false, "新正文，最多 4000 字。"),
            ),
            readOnly = false,
        )
    }
}

/** `memo_delete`：删除一条备忘。**必须**显式 `confirm=true`。 */
class MemoDeleteCapability(private val store: JsonStore) : Capability {

    override val name: String = NAME
    override val metadata: CapabilityMetadata = METADATA
    override val allowedArguments: Set<String> = setOf("id", "confirm")

    override fun validate(arguments: JSONObject): String? = validated(arguments) { parseId(arguments) }.second

    override fun confirmationProblem(arguments: JSONObject): String? {
        val confirm = try {
            CapabilityArgs.optionalBoolean(arguments, "confirm")
        } catch (_: ArgumentProblem) {
            return null // 让参数校验给出更准确的错误
        }
        return if (confirm == true) null else "删除备忘不可恢复，需要调用方显式传 confirm=true 才能执行。"
    }

    override fun execute(arguments: JSONObject): CapabilityResult {
        val id = try {
            parseId(arguments)
        } catch (problem: ArgumentProblem) {
            return CapabilityResult.Failure(ToolResultErrorCode.INVALID_ARGUMENTS, problem.message ?: "参数不合法")
        }
        return try {
            if (store.delete(id)) {
                CapabilityResult.Success(JSONObject().put("deleted", true).put("id", id))
            } else {
                CapabilityResult.Failure(ToolResultErrorCode.INVALID_ARGUMENTS, "找不到这条备忘（$id）")
            }
        } catch (t: Throwable) {
            CapabilityResult.Failure(ToolResultErrorCode.TOOL_FAILED, "删除备忘失败（${t.javaClass.simpleName}）")
        }
    }

    private fun parseId(arguments: JSONObject): String {
        val id = CapabilityArgs.requiredString(arguments, "id", 32, allowBlank = false)
        if (!MemoGetCapability.ID_PATTERN.matches(id)) throw ArgumentProblem("参数 id 格式不正确")
        return id
    }

    companion object {
        const val NAME = "memo_delete"

        val METADATA: CapabilityMetadata = CapabilityMetadata(
            name = NAME,
            displayName = "删除备忘",
            description = "永久删除一条备忘。必须先传 confirm=true；删除后无法恢复。",
            category = CapabilityCategory.Productivity,
            arguments = listOf(
                CapabilityArgument("id", "string", true, "备忘 ID。"),
                CapabilityArgument("confirm", "boolean", true, "必须为 true，表示确认删除。"),
            ),
            readOnly = false,
            destructive = true,
        )
    }
}
