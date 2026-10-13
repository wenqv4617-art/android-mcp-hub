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

        val configured = serverStore.list().filter { it.enabled }.sortedBy { it.id }
        val selected = when (filter) {
            null -> configured
            else -> configured.filter { it.id == filter }.ifEmpty {
                return failure(ToolResultErrorCode.UNSUPPORTED_TOOL, "本机没有启用这台 MCP 服务器")
            }
        }

        val startedAt = clock()
        val servers = mutableListOf<HubCatalogServer>()
        val tools = mutableListOf<HubCatalogTool>()
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
                val remoteTools = transport.listTools()
                servers += HubCatalogServer(config.id, serverName(config), HubStates.AVAILABILITY_ONLINE)
                for (tool in remoteTools) {
                    val fingerprint = fingerprintOf(config, tool)
                    val state = authorizationStore.state(config.id, tool.name, fingerprint)
                    if (state != McpAuthorizationState.Granted) {
                        others++
                        continue
                    }
                    if (tools.size >= hubLimits.maxTools) {
                        others++
                        continue
                    }
                    tools += catalogTool(config, tool, state, fingerprint)
                }
            } catch (error: Exception) {
                // 单台服务器失败不影响其它服务器，也**不**伪造它的工具列表。
                servers += HubCatalogServer(config.id, serverName(config), HubStates.AVAILABILITY_OFFLINE)
            } finally {
                runCatching { transport.close() }
            }
        }

        return CapabilityResult.Success(
            HubCatalog(servers = servers, tools = tools, otherToolsCount = others).toJson(),
        )
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
                append("（该工具的输入结构超过本机上限，未提供 schema）")
            }
        }
        return HubCatalogTool(
            serverId = config.id,
            toolName = tool.name,
            displayName = clip(tool.title?.takeIf { it.isNotBlank() } ?: tool.name, hubLimits.maxDisplayName),
            description = description,
            inputSchema = if (schemaTooLarge) null else schema,
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
        val ALLOWED_CATALOG_KEYS = setOf("server_id")
        val ALLOWED_CALL_KEYS = setOf("server_id", "tool_name", "arguments")
    }
}
