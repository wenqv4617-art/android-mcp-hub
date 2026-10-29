package com.androidmcphub.app.aggregation

import com.androidmcphub.app.capability.CapabilityResult
import com.androidmcphub.app.capability.HubCommandRouter
import com.androidmcphub.app.mcp.McpClientException
import com.androidmcphub.app.mcp.McpErrorKind
import com.androidmcphub.app.mcp.McpLimits
import com.androidmcphub.app.mcp.McpToolIdentity
import com.androidmcphub.app.mcp.json.JsonSchemaValidator
import com.androidmcphub.app.mcp.model.McpServerConfig
import com.androidmcphub.app.mcp.store.McpAuthorizationState
import com.androidmcphub.app.mcp.store.McpAuthorizationStore
import com.androidmcphub.app.mcp.store.McpServerStore
import com.androidmcphub.app.mcp.transport.McpRemoteTool
import com.androidmcphub.app.mcp.transport.McpToolCallResult
import com.androidmcphub.app.mcp.transport.McpTransport
import com.androidmcphub.app.mcp.transport.McpTransportFactory
import com.androidmcphub.app.protocol.ToolResultErrorCode
import org.json.JSONArray
import org.json.JSONObject

/**
 * 聚合入口的真正实现：服务端要的"第三方工具目录"和"第三方工具调用"都从这里走。
 *
 * ## 它为什么长在手机这一侧
 *
 * 工具目录只存在于手机上（Worker 不持久化任何东西，这是它的安全边界之一），
 * 凭据（第三方服务器的请求头）也只在手机上（Worker 永远看不到它们）。
 * 所以"发现"和"执行"都只能由手机完成，Worker 只负责把两个稳定的入口
 * （[HubCommands.LIST_TOOLS] / [HubCommands.CALL_TOOL]）转成一次 `tool_request`。
 *
 * ## 三条不可退让的规则
 *
 * 1. **未授权的东西一个字节都不外泄**：目录里只出现"已授权且这次真的连上了"的工具。
 *    未授权的、离线的、超限的一律只贡献一个数字 [HubCatalog.otherToolsCount]，
 *    名字与描述绝不出现 —— 否则第三方工具的清单会变成"给模型看的广告位"，
 *    而这正是用户没同意过的事。
 * 2. **执行前重新校验授权**：调用时重新 `connect()` + `tools/list`，用**这次**的
 *    schema 算指纹再查授权。授权记录记的是"用户同意的是**那个**定义"，
 *    服务器把 `read_file` 换成 `delete_file`（或改掉 schema 让参数语义反转）之后，
 *    指纹变化会让状态掉到 `Stale`，调用被拒。
 * 3. **失败必须变成带错误码的失败**：这个类的所有路径都不抛异常
 *    （[route] 自己吞掉一切），因为调用方是协议层 —— 一次未捕获的异常在
 *    `tool_request` 那条路上会表现为"手机没有任何回应"，最终用户看到的是超时，
 *    而不是"这台服务器连不上"。
 *
 * @param transportFactory 造传输。测试注入假实现；生产用
 *   [McpTransportFactory]，并且**把每次操作的超时压到 [HubLimits.perServerTimeoutMs]
 *   与 [McpLimits.readTimeoutMs] 的较小值** —— 一次目录刷新要让用户等
 *   "最多几秒"，而不是每台服务器 30 秒叠加。
 * @param enabled 执行总开关（界面上是「允许服务端调用本机能力」）。它**只门禁调用**，
 *   不门禁目录：总开关关着时用户仍然可以在服务页里看工具、授权工具，
 *   只是任何第三方工具都不会被真正执行。
 */
class McpHubRouter(
    private val serverStore: McpServerStore,
    private val authorizationStore: McpAuthorizationStore,
    private val transportFactory: ((McpServerConfig) -> McpTransport)? = null,
    private val limits: McpLimits = McpLimits(),
    private val hubLimits: HubLimits = HubLimits(),
    private val enabled: () -> Boolean = { true },
    private val clock: () -> Long = System::currentTimeMillis,
) : HubCommandRouter {

    override fun handles(toolName: String): Boolean = HubCommands.isHubCommand(toolName)

    override fun route(toolName: String, arguments: JSONObject): CapabilityResult = try {
        when (toolName) {
            HubCommands.LIST_TOOLS -> listTools(arguments)
            HubCommands.GET_TOOL_SCHEMA -> getToolSchema(arguments)
            HubCommands.CALL_TOOL -> callTool(arguments)
            else -> CapabilityResult.Failure(
                ToolResultErrorCode.UNSUPPORTED_TOOL,
                "未知的聚合命令",
            )
        }
    } catch (invalid: InvalidArguments) {
        failure(ToolResultErrorCode.INVALID_ARGUMENTS, invalid.message ?: "参数不合法")
    } catch (error: McpClientException) {
        failure(codeFor(error.kind), describe(error))
    } catch (error: Exception) {
        // 兜底：任何没预料到的异常都必须变成一条有错误码的失败，而不是消失。
        failure(ToolResultErrorCode.TOOL_FAILED, "聚合执行失败（${error.javaClass.simpleName}）")
    }

    // ------------------------------------------------------------------
    // 目录
    // ------------------------------------------------------------------

    private fun listTools(arguments: JSONObject): CapabilityResult {
        rejectUnknownKeys(arguments, ALLOWED_CATALOG_KEYS)
        val filter = readOptionalServerId(arguments)
        val offset = readInt(arguments, "offset", 0, 0, MAX_OFFSET)
        val limit = readInt(arguments, "limit", hubLimits.maxToolsPerPage, 1, hubLimits.maxPageSize)
        val includeSchema = readBoolean(arguments, "include_schema", false)
        val serversOnly = readBoolean(arguments, "servers_only", false)

        val configured = serverStore.list().filter { it.enabled }.sortedBy { it.id }
        val selected = when (filter) {
            null -> configured
            else -> configured.filter { it.id == filter }.ifEmpty {
                return failure(ToolResultErrorCode.UNSUPPORTED_TOOL, "本机没有启用这台 MCP 服务器")
            }
        }

        val startedAt = clock()
        val servers = mutableListOf<HubCatalogServer>()
        val authorized = mutableListOf<HubCatalogTool>()
        var others = 0

        for (config in selected) {
            // 服务器数量上限：超出的整台不刷新。它们仍然会出现在目录里（availability=offline），
            // 因为"没刷新"和"连不上"对调用方是同一件事：这份目录里没有它的工具。
            if (servers.size >= hubLimits.maxServers) {
                servers += HubCatalogServer(config.id, serverName(config), HubStates.AVAILABILITY_OFFLINE)
                continue
            }
            if (clock() - startedAt > hubLimits.totalBudgetMs) {
                servers += HubCatalogServer(config.id, serverName(config), HubStates.AVAILABILITY_OFFLINE)
                continue
            }

            val transport = try {
                openTransport(config)
            } catch (error: Exception) {
                servers += HubCatalogServer(config.id, serverName(config), HubStates.AVAILABILITY_OFFLINE)
                continue
            }

            try {
                transport.connect()
                // 只为列服务器时**不**枚举工具：一台 35 个工具的服务器，`tools/list`
                // 才是慢的那一步。连接成功已经足以回答"它现在可不可用"。
                val remoteTools = if (serversOnly) emptyList() else transport.listTools()
                servers += HubCatalogServer(config.id, serverName(config), HubStates.AVAILABILITY_ONLINE)
                for (tool in remoteTools) {
                    val fingerprint = fingerprintOf(config, tool)
                    val state = authorizationStore.state(config.id, tool.name, fingerprint)
                    if (state != McpAuthorizationState.Granted) {
                        others++
                        continue
                    }
                    // 收集上限只防内存失控；它**不再**是一页能返回多少。
                    if (authorized.size >= hubLimits.maxTools) {
                        others++
                        continue
                    }
                    authorized += catalogTool(config, tool, state, fingerprint)
                }
            } catch (error: Exception) {
                // 单台服务器失败不影响其它服务器，也**不**伪造它的工具列表。
                servers += HubCatalogServer(config.id, serverName(config), HubStates.AVAILABILITY_OFFLINE)
            } finally {
                runCatching { transport.close() }
            }
        }

        val total = authorized.size
        val safeOffset = offset.coerceAtMost(total)
        val page = authorized.drop(safeOffset).take(limit)

        // 分页之后再套一层字符预算：极端情况下 100 个长描述仍然可能把 16 KiB 帧撑爆。
        // 预算只截断**这一页的尾部**，不会让已列出的工具消失；被截掉的用 next_offset 告知。
        //
        // schema 先按页算好放进 map（无副作用），而不是在 toJson 的回调里边算边计数 ——
        // 那样同一页会被序列化两次（预算试算 + 正式输出），计数就会被重复加。
        val schemas = HashMap<HubCatalogTool, JSONObject?>()
        if (includeSchema) {
            for (tool in page) {
                val schema = tool.inputSchema ?: continue
                if (schema.toString().length <= hubLimits.maxSchemaChars) schemas[tool] = schema
            }
        }
        val emptyBudget = utf8Bytes(
            HubCatalog(
                servers = servers,
                tools = emptyList(),
                otherToolsCount = others,
                totalTools = total,
                offset = safeOffset,
                limit = limit,
            ).toJson(hubLimits.maxListDescription),
        )
        var usedBytes = emptyBudget
        val pageTools = mutableListOf<HubCatalogTool>()
        var budgetTruncated = false
        for (tool in page) {
            val entry = utf8Bytes(tool.toListJson(hubLimits.maxListDescription, schemas[tool]))
            // 每条 +2 是数组里逗号与可能的空白；另留 64 字节给 has_more/next_offset 这类
            // 只在预算算完之后才确定下来的键，避免"刚好卡在预算上"时又被撑超。
            if (usedBytes + entry + 2 + 64 > hubLimits.maxCatalogBytes) {
                budgetTruncated = true
                break
            }
            pageTools += tool
            usedBytes += entry + 2
        }

        val returned = pageTools.size
        val hasMore = safeOffset + returned < total
        val schemasDropped = if (!includeSchema) 0 else pageTools.count { it.inputSchema != null && !schemas.containsKey(it) }
        val catalog = HubCatalog(
            servers = servers,
            tools = pageTools,
            otherToolsCount = others,
            totalTools = total,
            offset = safeOffset,
            limit = limit,
            hasMore = hasMore,
            nextOffset = if (hasMore) safeOffset + returned else null,
            schemasDropped = schemasDropped,
            truncatedByBudget = budgetTruncated,
            serversOnly = serversOnly,
        )
        return CapabilityResult.Success(catalog.toJson(hubLimits.maxListDescription) { schemas[it] })
    }

    /**
     * 按需取一个**已授权**工具的参数结构。
     *
     * 目录默认不带 schema（35 个工具 × 完整 schema 会撑爆帧），但模型要正确调用工具
     * 仍然需要它。所以拆成单独一次按工具读取：只有真正要调的那个才付这份体积。
     * 未授权/定义已变化的工具在这里同样被拒绝 —— 否则它就成了一条绕过授权的信息出口。
     */
    private fun getToolSchema(arguments: JSONObject): CapabilityResult {
        rejectUnknownKeys(arguments, ALLOWED_SCHEMA_KEYS)
        val serverId = requireString(arguments, "server_id")
        if (!HubIdentity.isValidServerId(serverId)) {
            throw InvalidArguments("server_id 不合法")
        }
        val toolName = requireString(arguments, "tool_name")
        if (!HubIdentity.isValidToolName(toolName)) {
            throw InvalidArguments("tool_name 不合法")
        }

        val config = serverStore.list().firstOrNull { it.id == serverId && it.enabled }
            ?: return failure(ToolResultErrorCode.UNSUPPORTED_TOOL, "本机没有启用这台 MCP 服务器")

        val transport = openTransport(config)
        try {
            transport.connect()
            // 与调用一样，用**这次**拿到的定义算指纹；旧授权不算数。
            val tool = transport.listTools().firstOrNull { it.name == toolName }
                ?: return failure(ToolResultErrorCode.UNSUPPORTED_TOOL, "这台服务器上没有这个工具")
            val fingerprint = fingerprintOf(config, tool)
            when (authorizationStore.state(serverId, toolName, fingerprint)) {
                McpAuthorizationState.Granted -> Unit
                McpAuthorizationState.Stale -> return failure(
                    ToolResultErrorCode.NOT_PERMITTED,
                    "这个工具的定义在授权之后发生了变化，需要你重新确认后才能读取参数结构",
                )
                McpAuthorizationState.NotGranted -> return failure(
                    ToolResultErrorCode.NOT_PERMITTED,
                    "这个工具还没有被授权",
                )
            }

            val payload = JSONObject()
            payload.put("server_id", serverId)
            payload.put("tool_name", toolName)
            payload.put("tool_id", HubIdentity.toolId(serverId, toolName))
            val schema = originalSchema(tool)
            if (schema == null) {
                payload.put("input_schema_omitted", "no_schema")
            } else {
                val text = schema.toString()
                payload.put("schema_chars", text.length)
                if (text.length <= hubLimits.maxSchemaResponseChars) {
                    payload.put("input_schema", schema)
                } else {
                    // 不返回半截 JSON（那比没有更糟）：只给体积与顶层参数名，让模型知道
                    // 这个工具存在、以及为什么拿不到完整结构。
                    payload.put("input_schema_omitted", "too_large")
                    payload.put("properties", propertyNames(schema))
                }
            }
            return CapabilityResult.Success(payload)
        } finally {
            runCatching { transport.close() }
        }
    }

    private fun catalogTool(
        config: McpServerConfig,
        tool: McpRemoteTool,
        state: McpAuthorizationState,
        fingerprint: String,
    ): HubCatalogTool {
        val schema = originalSchema(tool)
        val schemaTooLarge = schema != null && schema.toString().length > hubLimits.maxSchemaChars
        val description = buildString {
            append(clip(tool.description, hubLimits.maxDescription))
            if (schemaTooLarge) {
                if (isNotEmpty()) append(' ')
                append("（该工具的输入结构超过本机上限；需要参数结构时请使用 hub_get_tool_schema）")
            }
        }
        return HubCatalogTool(
            serverId = config.id,
            toolName = tool.name,
            displayName = clip(tool.title?.takeIf { it.isNotBlank() } ?: tool.name, hubLimits.maxDisplayName),
            description = description,
            // 保留**原始** schema：是否随目录下发由 listTools 的 include_schema + 上限决定。
            // 在这里就置 null 会让"这次到底丢了几条 schema"变得数不清。
            inputSchema = schema,
            authorizationState = HubStates.authorizationOf(state),
            availability = HubStates.AVAILABILITY_ONLINE,
            fingerprint = fingerprint,
        )
    }

    // ------------------------------------------------------------------
    // 调用
    // ------------------------------------------------------------------

    private fun callTool(arguments: JSONObject): CapabilityResult {
        if (!enabled()) {
            return failure(
                ToolResultErrorCode.NOT_PERMITTED,
                "服务端调用本机能力的总开关当前是关闭的",
            )
        }
        rejectUnknownKeys(arguments, ALLOWED_CALL_KEYS)

        val serverId = requireString(arguments, "server_id")
        if (!HubIdentity.isValidServerId(serverId)) {
            throw InvalidArguments("server_id 不合法")
        }
        val toolName = requireString(arguments, "tool_name")
        if (!HubIdentity.isValidToolName(toolName)) {
            throw InvalidArguments("tool_name 不合法")
        }
        val rawArguments = arguments.opt("arguments")
        val validated = when {
            rawArguments == null || rawArguments == JSONObject.NULL -> HubArgumentValidation.Ok(JSONObject())
            rawArguments is JSONObject -> HubArgumentValidator.validate(rawArguments, hubLimits)
            else -> throw InvalidArguments("arguments 必须是一个对象")
        }
        if (validated is HubArgumentValidation.Error) {
            throw InvalidArguments(validated.message)
        }
        val callArguments = (validated as HubArgumentValidation.Ok).value

        val config = serverStore.list().firstOrNull { it.id == serverId && it.enabled }
            ?: return failure(ToolResultErrorCode.UNSUPPORTED_TOOL, "本机没有启用这台 MCP 服务器")

        val transport = openTransport(config)
        try {
            transport.connect()
            // 每次调用都重新拉一次工具定义：授权判定必须基于"现在这个定义"。
            val tool = transport.listTools().firstOrNull { it.name == toolName }
                ?: return failure(ToolResultErrorCode.UNSUPPORTED_TOOL, "这台服务器上没有这个工具")

            val fingerprint = fingerprintOf(config, tool)
            when (authorizationStore.state(serverId, toolName, fingerprint)) {
                McpAuthorizationState.Granted -> Unit
                McpAuthorizationState.Stale -> return failure(
                    ToolResultErrorCode.NOT_PERMITTED,
                    "这个工具的定义在授权之后发生了变化，需要你重新确认后才能调用",
                )
                McpAuthorizationState.NotGranted -> return failure(
                    ToolResultErrorCode.NOT_PERMITTED,
                    "这个工具还没有被授权",
                )
            }

            val schema = originalSchema(tool)
            if (schema != null) {
                val schemaError = JsonSchemaValidator.validate(schema, callArguments)
                if (schemaError != null) {
                    // 只回我们自己的说明：远端 schema 的内容不回显，避免把不可信文本
                    // 原样塞进错误信息里绕一圈又回到模型面前。
                    throw InvalidArguments("参数不符合这个工具的声明")
                }
            }

            val result = transport.callTool(toolName, callArguments)
            return CapabilityResult.Success(callPayload(config, toolName, result))
        } finally {
            runCatching { transport.close() }
        }
    }

    private fun callPayload(
        config: McpServerConfig,
        toolName: String,
        result: McpToolCallResult,
    ): JSONObject {
        val payload = JSONObject()
        payload.put("server_id", config.id)
        payload.put("tool_name", toolName)
        payload.put("tool_id", HubIdentity.toolId(config.id, toolName))
        payload.put("is_error", result.isError)

        val items = runCatching { JSONObject(result.rawResultJson).optJSONArray("content") }.getOrNull()
        val content = JSONArray()
        val contentTypes = mutableListOf<String>()
        val unsupported = mutableListOf<String>()
        var truncated = false
        var totalChars = 0

        if (items != null) {
            for (index in 0 until minOf(items.length(), hubLimits.maxContentItems)) {
                val item = items.optJSONObject(index) ?: continue
                val type = item.optString("type", "")
                if (type == "text") {
                    val raw = item.optString("text", "")
                    val remaining = hubLimits.maxTotalTextChars - totalChars
                    if (remaining <= 0) {
                        truncated = true
                        continue
                    }
                    val kept = clip(raw, minOf(hubLimits.maxTextChars, remaining))
                    if (kept.length < raw.length) truncated = true
                    totalChars += kept.length
                    if (!contentTypes.contains("text")) contentTypes += "text"
                    content.put(JSONObject().put("type", "text").put("text", kept))
                } else {
                    // 非文本内容（图片、音频、资源引用）**只报告类型**：
                    // 正文不外泄，因为它既可能是巨大的 base64，也可能包含用户没打算给模型看的东西。
                    if (type.isNotEmpty() && !unsupported.contains(type)) unsupported += type
                }
            }
            if (items.length() > hubLimits.maxContentItems) truncated = true
        }

        payload.put("content", content)
        payload.put("content_types", JSONArray(contentTypes))
        if (unsupported.isNotEmpty()) payload.put("unsupported_content_types", JSONArray(unsupported))
        if (truncated) payload.put("text_truncated", true)

        val structured = result.structured
        if (structured != null && structured.toString().length <= hubLimits.maxStructuredChars) {
            payload.put("structured_content", structured)
        } else if (structured != null) {
            payload.put("structured_content_omitted", "too_large_or_not_object")
        }
        return payload
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    private fun openTransport(config: McpServerConfig): McpTransport {
        val factory = transportFactory ?: { server: McpServerConfig ->
            // 单次操作的超时取两者较小值：目录刷新是用户按了「刷新」之后要立刻看到结果的动作。
            McpTransportFactory.create(
                server,
                limits.copy(
                    readTimeoutMs = minOf(limits.readTimeoutMs, hubLimits.perServerTimeoutMs),
                    connectTimeoutMs = minOf(limits.connectTimeoutMs, hubLimits.perServerTimeoutMs),
                ),
            )
        }
        return factory(config)
    }

    /**
     * 授权指纹。输入是服务器给的**原始** schema（从 `rawJson` 里取），
     * 不是被本机截断过的那个 —— 否则"我们的展示上限"会被误算成"工具定义变了"，
     * 让用户反复重新授权。
     *
     * 标题与描述也一起进指纹：描述就是给模型看的说明书，服务器把它改成别的话术时，
     * 工具名和 schema 可以一个字都不动。
     */
    private fun fingerprintOf(config: McpServerConfig, tool: McpRemoteTool): String =
        McpToolIdentity.fingerprintOf(config.url, tool)

    /** 见 [McpToolIdentity.originalSchema]：界面与运行时用的是同一个算法，不能各写一份。 */
    private fun originalSchema(tool: McpRemoteTool): JSONObject? = McpToolIdentity.originalSchema(tool)

    private fun serverName(config: McpServerConfig): String =
        clip(config.name.ifBlank { config.id }, hubLimits.maxServerName)

    private fun readOptionalServerId(arguments: JSONObject): String? {
        val raw = arguments.opt("server_id") ?: return null
        if (raw == JSONObject.NULL) return null
        if (raw !is String || !HubIdentity.isValidServerId(raw)) {
            throw InvalidArguments("server_id 不合法")
        }
        return raw
    }

    private fun requireString(arguments: JSONObject, key: String): String {
        val raw = arguments.opt(key)
        if (raw !is String || raw.isEmpty()) throw InvalidArguments("$key 必填")
        return raw
    }

    /**
     * 读一个整数参数。
     *
     * 类型不对、越界都**拒绝**：分页参数是模型自己写的，写错了应该让它看见错误并改。
     * 静默夹紧（比如把 limit=1000 变成 100）会让它以为"一页就这么多"，而真正的原因
     * 是它自己写错了 —— 那比一条 invalid_arguments 更难排查。
     * （`offset` 超过总数**不是**越界：那是合法的空页，明确返回 total_tools 即可。）
     */
    private fun readInt(arguments: JSONObject, key: String, default: Int, min: Int, max: Int): Int {
        val raw = arguments.opt(key)
        if (raw == null || raw == JSONObject.NULL) return default
        val value = when (raw) {
            is Int -> raw
            is Long -> if (raw >= Int.MIN_VALUE && raw <= Int.MAX_VALUE) raw.toInt()
            else throw InvalidArguments("$key 超出范围")
            is Double -> if (raw.isFinite() && raw % 1.0 == 0.0 && raw >= Int.MIN_VALUE && raw <= Int.MAX_VALUE) {
                raw.toInt()
            } else {
                throw InvalidArguments("$key 必须是整数")
            }
            else -> throw InvalidArguments("$key 必须是整数")
        }
        if (value < min || value > max) throw InvalidArguments("$key 必须在 [$min, $max] 之间")
        return value
    }

    private fun readBoolean(arguments: JSONObject, key: String, default: Boolean): Boolean {
        val raw = arguments.opt(key)
        if (raw == null || raw == JSONObject.NULL) return default
        if (raw !is Boolean) throw InvalidArguments("$key 必须是布尔值")
        return raw
    }

    /** schema 的顶层参数名。schema 过大时只给这些名字，让调用方至少知道要传什么。 */
    private fun propertyNames(schema: JSONObject): JSONArray {
        val properties = schema.optJSONObject("properties") ?: return JSONArray()
        val names = JSONArray()
        val keys = properties.keys()
        while (keys.hasNext()) {
            val name = keys.next()
            if (names.length() >= MAX_PROPERTY_NAMES) break
            names.put(clip(name, hubLimits.maxArgumentKeyLength))
        }
        return names
    }

    private fun rejectUnknownKeys(arguments: JSONObject, allowed: Set<String>) {
        val keys = arguments.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key !in allowed) throw InvalidArguments("参数里有不认识的字段：$key")
        }
    }

    private fun clip(text: String, max: Int): String =
        if (text.length <= max) text else text.substring(0, max)

    /**
     * 序列化后的 UTF-8 字节数。
     *
     * 帧上限按字节算，中文字符是 3 字节 —— 用字符数当预算会让"看起来没超"的目录
     * 在真的发出去时被整帧丢掉，而现象和修复前完全一样（一条空结果）。
     */
    private fun utf8Bytes(value: JSONObject): Int = value.toString().toByteArray(Charsets.UTF_8).size

    /**
     * 失败文案。
     *
     * **刻意不拼异常自己的 message**：那条路上流的是第三方服务器返回的原文
     * （JSON-RPC error.message 是对方自由填的），把它接进我们的失败文案，
     * 就等于让一台第三方服务器往模型的上下文里塞任意一段话。
     * 这里只用闭集分类 [McpErrorKind.label] 与 HTTP 状态 / RPC 码这种结构化定位信息。
     * 仓库里的 `LogRedactionTest` 有静态扫描守着这条（主源码里不许出现 `*.message`）。
     */
    private fun describe(error: McpClientException): String {
        val where = when {
            error.httpStatus != null -> "（HTTP ${error.httpStatus}）"
            error.rpcCode != null -> "（JSON-RPC 码 ${error.rpcCode}）"
            else -> ""
        }
        return "第三方 MCP 服务器：${error.kind.label}$where"
    }

    private fun codeFor(kind: McpErrorKind): String = when (kind) {
        McpErrorKind.SERVER_UNAVAILABLE -> ToolResultErrorCode.TOOL_UNAVAILABLE
        McpErrorKind.NOT_AUTHORIZED -> ToolResultErrorCode.NOT_PERMITTED
        McpErrorKind.INVALID_ARGUMENTS -> ToolResultErrorCode.INVALID_ARGUMENTS
        McpErrorKind.HTTP_ERROR,
        McpErrorKind.PROTOCOL_ERROR,
        McpErrorKind.TIMEOUT,
        McpErrorKind.RESULT_TOO_LARGE,
        McpErrorKind.SERVER_REJECTED,
        -> ToolResultErrorCode.TOOL_FAILED
    }

    /** [code] 只取自 [ToolResultErrorCode]；[CapabilityDispatcher] 收口时还会再收敛一次闭集。 */
    private fun failure(code: String, message: String): CapabilityResult =
        CapabilityResult.Failure(code, message)

    /** 参数不合法：不继承 [McpClientException]，因为它是本地校验的结果，与远端无关。 */
    private class InvalidArguments(message: String) : Exception(message)

    private companion object {
        val ALLOWED_CATALOG_KEYS = setOf("server_id", "offset", "limit", "include_schema", "servers_only")
        val ALLOWED_SCHEMA_KEYS = setOf("server_id", "tool_name")
        val ALLOWED_CALL_KEYS = setOf("server_id", "tool_name", "arguments")

        /** offset 的硬上限：只是防"请求一个天文数字"这种无意义输入，夹紧即可。 */
        const val MAX_OFFSET = 10_000

        /** schema 过大时最多回报多少个顶层参数名。 */
        const val MAX_PROPERTY_NAMES = 32
    }
}
