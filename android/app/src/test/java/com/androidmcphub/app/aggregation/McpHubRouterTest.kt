package com.androidmcphub.app.aggregation

import com.androidmcphub.app.capability.CapabilityResult
import com.androidmcphub.app.mcp.McpClientException
import com.androidmcphub.app.mcp.McpErrorKind
import com.androidmcphub.app.mcp.McpToolFingerprint
import com.androidmcphub.app.mcp.model.McpServerConfig
import com.androidmcphub.app.mcp.store.FakeSecretBlobStore
import com.androidmcphub.app.mcp.store.InMemoryKeyValueStore
import com.androidmcphub.app.mcp.store.McpAuthorizationStore
import com.androidmcphub.app.mcp.store.McpServerStore
import com.androidmcphub.app.mcp.transport.McpRemoteTool
import com.androidmcphub.app.mcp.transport.McpServerHandshake
import com.androidmcphub.app.mcp.transport.McpToolCallResult
import com.androidmcphub.app.mcp.transport.McpTransport
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor

/**
 * [McpHubRouter] 的测试。
 *
 * 这个类只有一条判据值得反复证明：**没被用户授权的东西，一个字节都到不了服务端。**
 * 所以下面大量断言是"某个字符串在整段 JSON 里一次都不出现"，而不是"某个字段等于什么"——
 * 前者才拦得住"顺手把未授权工具的名字塞进描述里"这类泄漏。
 */
class McpHubRouterTest {

    // ------------------------------------------------------------------
    // 目录
    // ------------------------------------------------------------------

    @Test
    fun `目录只列已授权且在线的工具，未授权的只贡献数字`() {
        val echo = tool("echo_text", """{"type":"object","properties":{"text":{"type":"string"}},"required":["text"]}""", "把文本原样回显")
        val note = tool("write_note", """{"type":"object"}""", "写一条笔记")
        val h = harness(
            servers = listOf(config("srv_alpha")),
            transports = mapOf("srv_alpha" to FakeTransport("srv_alpha", listOf(echo, note))),
        )
        h.grant("srv_alpha", "echo_text")

        val payload = payload(h.router.route(HubCommands.LIST_TOOLS, JSONObject()))

        assertEquals(1, payload.getJSONArray("tools").length())
        val entry = payload.getJSONArray("tools").getJSONObject(0)
        assertEquals("srv_alpha", entry.getString("server_id"))
        assertEquals("echo_text", entry.getString("tool_name"))
        assertEquals("把文本原样回显", entry.getString("description"))
        assertEquals(HubStates.AUTHORIZATION_AUTHORIZED, entry.getString("authorization_state"))
        assertEquals(HubStates.AVAILABILITY_ONLINE, entry.getString("availability"))
        // 精简目录：默认不带 schema，也不带授权指纹（指纹只在本机授权判定里用）。
        assertFalse(entry.has("input_schema"))
        assertFalse(entry.has("fingerprint"))
        assertEquals(1, payload.getInt("other_tools_count"))
        assertEquals(HubStates.AVAILABILITY_ONLINE, payload.getJSONArray("servers").getJSONObject(0).getString("availability"))

        // 未授权工具的名字与描述，在整段目录 JSON 里一次都不许出现。
        val raw = payload.toString()
        assertFalse(raw.contains("write_note"))
        assertFalse(raw.contains("写一条笔记"))
    }

    @Test
    fun `目录键名严格对齐服务端读取的那一套`() {
        val h = harness(
            servers = listOf(config("srv_alpha")),
            transports = mapOf("srv_alpha" to FakeTransport("srv_alpha", listOf(tool("ping_echo")))),
        )
        h.grant("srv_alpha", "ping_echo")

        val payload = payload(h.router.route(HubCommands.LIST_TOOLS, JSONObject()))

        assertEquals(
            setOf(
                "servers", "tools", "total_tools", "returned", "offset", "limit",
                "has_more", "other_tools_count", "schemas_dropped", "truncated",
                // V1 性能专项：目录必须能说清"这份数据是刚拉的还是缓存里的"。
                "catalog_source", "refresh_pending", "elapsed_ms", "catalog_age_ms",
            ),
            payload.keys().asSequence().toSet(),
        )
        val server = payload.getJSONArray("servers").getJSONObject(0)
        assertEquals(
            setOf("server_id", "server_name", "availability", "availability_source", "checked_at_ms"),
            server.keys().asSequence().toSet(),
        )
        val entry = payload.getJSONArray("tools").getJSONObject(0)
        assertEquals(
            setOf(
                "server_id", "tool_name", "display_name", "description",
                "authorization_state", "availability",
            ),
            entry.keys().asSequence().toSet(),
        )
        // 服务端（worker/src/mcp/hub.ts）读的就是这一套键；分页字段必须齐全，
        // 否则模型看到"共 N 个"却没有任何翻页线索。
        assertEquals(1, payload.getInt("total_tools"))
        assertEquals(1, payload.getInt("returned"))
        assertEquals(0, payload.getInt("offset"))
        assertFalse(payload.getBoolean("has_more"))
    }

    @Test
    fun `连不上的服务器如实标成离线，且不伪造它的工具数`() {
        val h = harness(
            servers = listOf(config("srv_down")),
            transports = mapOf("srv_down" to throwingTransport("srv_down", McpClientException(McpErrorKind.SERVER_UNAVAILABLE, "连不上"))),
        )

        val payload = payload(h.router.route(HubCommands.LIST_TOOLS, JSONObject()))

        assertEquals(0, payload.getJSONArray("tools").length())
        assertEquals(0, payload.getInt("other_tools_count"))
        assertEquals(HubStates.AVAILABILITY_OFFLINE, payload.getJSONArray("servers").getJSONObject(0).getString("availability"))
    }

    @Test
    fun `schema 超限被置空时工具仍然出现在目录里，并指向按需读取入口`() {
        // 真机上的形态：StreamableHttpTransport 会把超限的 inputSchema 置 null，rawJson 保留原文。
        val bigSchema = """{"type":"object","properties":{${(1..40).joinToString(",") { "\"p$it\":{\"type\":\"string\",\"description\":\"${"x".repeat(120)}\"}" }}}}"""
        val big = McpRemoteTool(
            name = "big_tool",
            title = null,
            description = "参数很多",
            inputSchema = null,
            annotations = null,
            rawJson = """{"name":"big_tool","description":"参数很多","inputSchema":$bigSchema}""",
        )
        val h = harness(
            servers = listOf(config("srv_big")),
            transports = mapOf("srv_big" to FakeTransport("srv_big", listOf(big))),
            // 目录侧上限压到 200，按需读取上限也压小，才能在同一条用例里同时覆盖
            // "目录不带 schema"与"按需读取也超限时如实说明"。
            hubLimits = HubLimits(maxSchemaChars = 200, maxSchemaResponseChars = 400),
        )
        val expected = McpToolFingerprint.of(h.serverStore.find("srv_big")!!.url, "big_tool", null, "参数很多", bigSchema)
        h.authStore.grant("srv_big", "big_tool", expected)

        val entry = payload(h.router.route(HubCommands.LIST_TOOLS, JSONObject()))
            .getJSONArray("tools").getJSONObject(0)

        // schema 超限只影响**这一条**的 schema，不影响整个目录，也不影响授权
        // （授权指纹按原始 schema 算，见 h.grant 里的 expected）。
        assertEquals("big_tool", entry.getString("tool_name"))
        assertFalse(entry.has("input_schema"))
        assertTrue(entry.getString("description").contains("hub_get_tool_schema"))
        assertEquals(1, payload(h.router.route(HubCommands.LIST_TOOLS, JSONObject())).getInt("total_tools"))
        // 按需读取时也拿不到完整 schema，但会如实说明原因与顶层参数名。
        val schemaPayload = payload(
            h.router.route(
                HubCommands.GET_TOOL_SCHEMA,
                JSONObject().put("server_id", "srv_big").put("tool_name", "big_tool"),
            ),
        )
        assertEquals("too_large", schemaPayload.getString("input_schema_omitted"))
        // 顶层参数名会封顶（32），但足以让模型知道该传什么。
        assertEquals(32, schemaPayload.getJSONArray("properties").length())
    }

    @Test
    fun `超过 maxTools 的已授权工具只计数不列出`() {
        val tools = (1..4).map { tool("t$it") }
        val h = harness(
            servers = listOf(config("srv_alpha")),
            transports = mapOf("srv_alpha" to FakeTransport("srv_alpha", tools)),
            hubLimits = HubLimits(maxTools = 2),
        )
        tools.forEach { h.grant("srv_alpha", it.name) }

        val payload = payload(h.router.route(HubCommands.LIST_TOOLS, JSONObject()))

        assertEquals(2, payload.getJSONArray("tools").length())
        assertEquals(2, payload.getInt("other_tools_count"))
    }

    @Test
    fun `35 个工具的服务器一次列出全部，且整份目录控制在帧预算内`() {
        // 这就是"音叙 35 个工具"的形状：每个工具都带一份不小的 schema。
        // 目录默认不带 schema，所以工具数量本身不该有压力。
        val schema = """{"type":"object","properties":{"query":{"type":"string","description":"${"x".repeat(300)}"}},"required":["query"]}"""
        val tools = (1..35).map { tool("tool_%02d".format(it), schema, "播放控制 $it") }
        val h = harness(
            servers = listOf(config("srv_music")),
            transports = mapOf("srv_music" to FakeTransport("srv_music", tools)),
        )
        tools.forEach { h.grant("srv_music", it.name) }

        val payload = payload(h.router.route(HubCommands.LIST_TOOLS, JSONObject()))

        assertEquals(35, payload.getInt("total_tools"))
        assertEquals(35, payload.getJSONArray("tools").length())
        assertEquals(0, payload.getInt("other_tools_count"))
        assertFalse(payload.getBoolean("has_more"))
        assertFalse(payload.getJSONArray("tools").getJSONObject(0).has("input_schema"))
        // 真实帧上限是 16 KiB：留足余量，中文按 3 字节算也算过。
        assertTrue(payload.toString().toByteArray(Charsets.UTF_8).size < 12 * 1024)
    }

    @Test
    fun `目录分页：offset 与 limit 切片，next_offset 可直接翻下一页`() {
        val tools = (1..5).map { tool("t$it") }
        val h = harness(
            servers = listOf(config("srv_alpha")),
            transports = mapOf("srv_alpha" to FakeTransport("srv_alpha", tools)),
        )
        tools.forEach { h.grant("srv_alpha", it.name) }

        val first = payload(h.router.route(HubCommands.LIST_TOOLS, JSONObject().put("limit", 2)))
        assertEquals(5, first.getInt("total_tools"))
        assertEquals(2, first.getJSONArray("tools").length())
        assertEquals(2, first.getInt("returned"))
        assertTrue(first.getBoolean("has_more"))
        assertEquals(2, first.getInt("next_offset"))

        val second = payload(
            h.router.route(HubCommands.LIST_TOOLS, JSONObject().put("offset", 2).put("limit", 2)),
        )
        assertEquals("t3", second.getJSONArray("tools").getJSONObject(0).getString("tool_name"))
        assertEquals(4, second.getInt("next_offset"))

        val third = payload(
            h.router.route(HubCommands.LIST_TOOLS, JSONObject().put("offset", 4).put("limit", 2)),
        )
        assertEquals(1, third.getJSONArray("tools").length())
        assertFalse(third.getBoolean("has_more"))
        assertFalse(third.has("next_offset"))

        // offset 超过总数不是错误：返回空页，total_tools 仍然如实告诉模型有多少。
        val beyond = payload(h.router.route(HubCommands.LIST_TOOLS, JSONObject().put("offset", 99)))
        assertEquals(0, beyond.getJSONArray("tools").length())
        assertEquals(5, beyond.getInt("total_tools"))
    }

    @Test
    fun `字符预算截断这一页的尾部时，total_tools 与 next_offset 仍然准确`() {
        val tools = (1..20).map { tool("t%02d".format(it), description = "说明".repeat(100)) }
        val h = harness(
            servers = listOf(config("srv_alpha")),
            transports = mapOf("srv_alpha" to FakeTransport("srv_alpha", tools)),
            hubLimits = HubLimits(maxCatalogBytes = 3_000, maxToolsPerPage = 20, maxPageSize = 20),
        )
        tools.forEach { h.grant("srv_alpha", it.name) }

        val payload = payload(h.router.route(HubCommands.LIST_TOOLS, JSONObject().put("limit", 20)))

        val returned = payload.getJSONArray("tools").length()
        assertTrue("预算应该挤掉一部分条目，实际返回 $returned", returned in 1 until 20)
        assertEquals(20, payload.getInt("total_tools"))
        assertTrue(payload.getBoolean("has_more"))
        assertEquals(returned, payload.getInt("next_offset"))
        // 被截掉的不是"消失"：下一轮从 next_offset 接着拿。
        val next = payload(
            h.router.route(HubCommands.LIST_TOOLS, JSONObject().put("offset", returned).put("limit", 20)),
        )
        assertEquals("t%02d".format(returned + 1), next.getJSONArray("tools").getJSONObject(0).getString("tool_name"))
    }

    @Test
    fun `include_schema 只有显式要求时才带，超大 schema 只丢自己那一条`() {
        val small = tool("small_tool", """{"type":"object","properties":{"q":{"type":"string"}}}""")
        val huge = tool("huge_tool", """{"type":"object","properties":{"blob":{"type":"string","description":"${"x".repeat(5000)}"}}}""")
        val h = harness(
            servers = listOf(config("srv_alpha")),
            transports = mapOf("srv_alpha" to FakeTransport("srv_alpha", listOf(small, huge))),
        )
        h.grant("srv_alpha", "small_tool")
        h.grant("srv_alpha", "huge_tool")

        val plain = payload(h.router.route(HubCommands.LIST_TOOLS, JSONObject()))
        assertFalse(plain.getJSONArray("tools").getJSONObject(0).has("input_schema"))
        assertEquals(0, plain.getInt("schemas_dropped"))

        val withSchema = payload(
            h.router.route(HubCommands.LIST_TOOLS, JSONObject().put("include_schema", true)),
        )
        val entries = withSchema.getJSONArray("tools")
        assertEquals(2, entries.length())
        assertTrue(entries.getJSONObject(0).has("input_schema"))
        assertFalse(entries.getJSONObject(1).has("input_schema"))
        assertEquals(1, withSchema.getInt("schemas_dropped"))
        assertEquals(2, withSchema.getInt("total_tools"))
    }

    @Test
    fun `servers_only 只回答有哪些服务器，不枚举工具`() {
        val transport = FakeTransport("srv_alpha", (1..35).map { tool("t$it") })
        val h = harness(servers = listOf(config("srv_alpha")), transports = mapOf("srv_alpha" to transport))

        val payload = payload(h.router.route(HubCommands.LIST_TOOLS, JSONObject().put("servers_only", true)))

        assertEquals(1, payload.getJSONArray("servers").length())
        val server = payload.getJSONArray("servers").getJSONObject(0)
        assertEquals("srv_alpha", server.getString("server_id"))
        // 从未探测过 → 如实说 "unknown"，而不是编一个 offline/online。
        assertEquals(HubStates.AVAILABILITY_UNKNOWN, server.getString("availability"))
        assertEquals(HubStates.SOURCE_UNKNOWN, server.getString("availability_source"))
        assertFalse(server.has("checked_at_ms"))
        assertEquals(0, payload.getJSONArray("tools").length())
        assertTrue(payload.getBoolean("servers_only"))
        // 这次探测是**探活**，不是枚举：`tools/list` 一次都不该被调用。
        assertEquals(0, transport.listToolsCalls)

        // 第二次请求能看到上一次探测的结果 —— 但来源必须标明是缓存。
        val second = payload(h.router.route(HubCommands.LIST_TOOLS, JSONObject().put("servers_only", true)))
        val probed = second.getJSONArray("servers").getJSONObject(0)
        assertEquals(HubStates.AVAILABILITY_ONLINE, probed.getString("availability"))
        assertEquals(HubStates.SOURCE_CACHE, probed.getString("availability_source"))
        assertTrue(probed.has("checked_at_ms"))
        // 仍然没有工具：探活不会顺手把 35 个工具拉下来。
        assertEquals(0, second.getJSONArray("tools").length())
        assertEquals(0, transport.listToolsCalls)
    }

    @Test
    fun `分页参数类型不对一律 invalid_arguments`() {
        val h = harness(servers = listOf(config("srv_alpha")))
        for (args in listOf(
            JSONObject().put("limit", 0),
            JSONObject().put("limit", 101),
            JSONObject().put("limit", "50"),
            JSONObject().put("offset", -1),
            JSONObject().put("include_schema", "yes"),
            JSONObject().put("servers_only", 1),
        )) {
            assertEquals(args.toString(), "invalid_arguments", failure(h.router.route(HubCommands.LIST_TOOLS, args)).code)
        }
    }

    @Test
    fun `guide 层只给已授权工具返回完整 schema`() {
        val schema = """{"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}"""
        val transport = FakeTransport("srv_alpha", listOf(tool("search", schema), tool("write_note")))
        val h = harness(servers = listOf(config("srv_alpha")), transports = mapOf("srv_alpha" to transport))
        h.grant("srv_alpha", "search")

        val payload = payload(
            h.router.route(
                HubCommands.GET_TOOL_SCHEMA,
                JSONObject().put("server_id", "srv_alpha").put("tool_name", "search"),
            ),
        )
        assertEquals("srv_alpha", payload.getString("server_id"))
        assertEquals("search", payload.getString("tool_name"))
        assertEquals("mcp:srv_alpha:search", payload.getString("tool_id"))
        assertEquals("string", payload.getJSONObject("input_schema").getJSONObject("properties").getJSONObject("query").getString("type"))
        assertTrue(payload.getInt("schema_chars") > 0)

        val refused = failure(
            h.router.route(
                HubCommands.GET_TOOL_SCHEMA,
                JSONObject().put("server_id", "srv_alpha").put("tool_name", "write_note"),
            ),
        )
        assertEquals("not_permitted", refused.code)

        val unknown = failure(
            h.router.route(
                HubCommands.GET_TOOL_SCHEMA,
                JSONObject().put("server_id", "srv_alpha").put("tool_name", "no_such_tool"),
            ),
        )
        assertEquals("unsupported_tool", unknown.code)

        val extra = failure(
            h.router.route(
                HubCommands.GET_TOOL_SCHEMA,
                JSONObject().put("server_id", "srv_alpha").put("tool_name", "search").put("arguments", JSONObject()),
            ),
        )
        assertEquals("invalid_arguments", extra.code)
    }

    @Test
    fun `总开关只门禁调用，不门禁目录`() {
        val h = harness(
            servers = listOf(config("srv_alpha")),
            transports = mapOf("srv_alpha" to FakeTransport("srv_alpha", listOf(tool("echo_text")))),
            enabled = false,
        )
        h.grant("srv_alpha", "echo_text")

        val payload = payload(h.router.route(HubCommands.LIST_TOOLS, JSONObject()))
        assertEquals(1, payload.getJSONArray("tools").length())
    }

    @Test
    fun `指定 server_id 时只刷新那一台`() {
        val h = harness(
            servers = listOf(config("srv_alpha"), config("srv_beta")),
            transports = mapOf(
                "srv_alpha" to FakeTransport("srv_alpha", listOf(tool("a_tool"))),
                "srv_beta" to FakeTransport("srv_beta", listOf(tool("b_tool"))),
            ),
        )
        h.grant("srv_alpha", "a_tool")

        val payload = payload(
            h.router.route(HubCommands.LIST_TOOLS, JSONObject().put("server_id", "srv_alpha")),
        )
        assertEquals(1, payload.getJSONArray("servers").length())
        assertEquals("srv_alpha", payload.getJSONArray("servers").getJSONObject(0).getString("server_id"))
        assertEquals(listOf("srv_alpha"), h.opened)
    }

    @Test
    fun `不存在的 server_id 是 unsupported_tool，形状不对才是 invalid_arguments`() {
        val h = harness(servers = listOf(config("srv_alpha")))

        val missing = failure(h.router.route(HubCommands.LIST_TOOLS, JSONObject().put("server_id", "srv_zzz")))
        assertEquals("unsupported_tool", missing.code)

        val malformed = failure(h.router.route(HubCommands.LIST_TOOLS, JSONObject().put("server_id", "A/B")))
        assertEquals("invalid_arguments", malformed.code)
    }

    @Test
    fun `目录参数里的多余字段一律拒绝`() {
        val h = harness(servers = listOf(config("srv_alpha")))
        val result = failure(
            h.router.route(HubCommands.LIST_TOOLS, JSONObject().put("url", "https://evil.example.com/mcp")),
        )
        assertEquals("invalid_arguments", result.code)
        assertTrue(result.message.contains("url"))
    }

    @Test
    fun `服务器名与描述超长时被裁剪`() {
        val h = harness(
            servers = listOf(config("srv_alpha", name = "N".repeat(300))),
            transports = mapOf("srv_alpha" to FakeTransport("srv_alpha", listOf(tool("echo_text", description = "D".repeat(900))))),
            hubLimits = HubLimits(maxServerName = 10, maxDescription = 20),
        )
        h.grant("srv_alpha", "echo_text")

        val payload = payload(h.router.route(HubCommands.LIST_TOOLS, JSONObject()))
        assertEquals(10, payload.getJSONArray("servers").getJSONObject(0).getString("server_name").length)
        assertEquals(20, payload.getJSONArray("tools").getJSONObject(0).getString("description").length)
    }

    // ------------------------------------------------------------------
    // 调用
    // ------------------------------------------------------------------

    @Test
    fun `已授权且参数合法时真的调用，并回带身份字段`() {
        val transport = FakeTransport(
            "srv_alpha",
            listOf(tool("echo_text", """{"type":"object","properties":{"text":{"type":"string"}},"required":["text"]}""")),
            callResult = { _, args ->
                McpToolCallResult(
                    isError = false,
                    text = "echo: ${args.getString("text")}",
                    structured = null,
                    rawResultJson = """{"content":[{"type":"text","text":"echo: ${args.getString("text")}"}]}""",
                )
            },
        )
        val h = harness(servers = listOf(config("srv_alpha")), transports = mapOf("srv_alpha" to transport))
        h.grant("srv_alpha", "echo_text")

        val payload = payload(
            h.router.route(
                HubCommands.CALL_TOOL,
                JSONObject()
                    .put("server_id", "srv_alpha")
                    .put("tool_name", "echo_text")
                    .put("arguments", JSONObject().put("text", "你好")),
            ),
        )

        assertEquals("srv_alpha", payload.getString("server_id"))
        assertEquals("echo_text", payload.getString("tool_name"))
        assertEquals("mcp:srv_alpha:echo_text", payload.getString("tool_id"))
        assertFalse(payload.getBoolean("is_error"))
        assertEquals("echo: 你好", payload.getJSONArray("content").getJSONObject(0).getString("text"))
        assertEquals("text", payload.getJSONArray("content_types").getString(0))
        assertEquals(listOf("echo_text"), transport.calls)
        assertTrue(transport.closed)
    }

    @Test
    fun `没授权的工具拒绝执行，也不连服务器`() {
        val transport = FakeTransport("srv_alpha", listOf(tool("write_note")))
        val h = harness(servers = listOf(config("srv_alpha")), transports = mapOf("srv_alpha" to transport))

        val result = failure(
            h.router.route(
                HubCommands.CALL_TOOL,
                JSONObject().put("server_id", "srv_alpha").put("tool_name", "write_note"),
            ),
        )

        assertEquals("not_permitted", result.code)
        assertTrue(transport.calls.isEmpty())
    }

    @Test
    fun `授权之后工具定义变了就拒绝执行`() {
        val before = tool("echo_text", """{"type":"object","properties":{"path":{"type":"string"}}}""")
        val h = harness(servers = listOf(config("srv_alpha")), transports = mapOf("srv_alpha" to FakeTransport("srv_alpha", listOf(before))))
        h.grant("srv_alpha", "echo_text")

        // 服务器把 schema 换掉了（同一个名字，不同的语义）。
        val after = tool("echo_text", """{"type":"object","properties":{"path":{"type":"string"},"force":{"type":"boolean"}}}""")
        val h2 = Harness(
            servers = listOf(config("srv_alpha")),
            transports = mapOf("srv_alpha" to FakeTransport("srv_alpha", listOf(after))),
            keyValueStore = h.kv,
        )

        val result = failure(
            h2.router.route(
                HubCommands.CALL_TOOL,
                JSONObject().put("server_id", "srv_alpha").put("tool_name", "echo_text"),
            ),
        )
        assertEquals("not_permitted", result.code)
        assertTrue(result.message.contains("定义"))
    }

    @Test
    fun `服务器未启用或工具不存在都是 unsupported_tool`() {
        val h = harness(
            servers = listOf(config("srv_alpha", enabled = false)),
            transports = mapOf("srv_alpha" to FakeTransport("srv_alpha", listOf(tool("echo_text")))),
        )
        val disabled = failure(
            h.router.route(
                HubCommands.CALL_TOOL,
                JSONObject().put("server_id", "srv_alpha").put("tool_name", "echo_text"),
            ),
        )
        assertEquals("unsupported_tool", disabled.code)

        val h2 = harness(
            servers = listOf(config("srv_alpha")),
            transports = mapOf("srv_alpha" to FakeTransport("srv_alpha", listOf(tool("echo_text")))),
        )
        h2.grant("srv_alpha", "echo_text")
        val missing = failure(
            h2.router.route(
                HubCommands.CALL_TOOL,
                JSONObject().put("server_id", "srv_alpha").put("tool_name", "nope_tool"),
            ),
        )
        assertEquals("unsupported_tool", missing.code)
    }

    @Test
    fun `参数不符合 schema 时拒绝，且不碰服务器`() {
        val transport = FakeTransport(
            "srv_alpha",
            listOf(tool("echo_text", """{"type":"object","properties":{"text":{"type":"string"}},"required":["text"]}""")),
        )
        val h = harness(servers = listOf(config("srv_alpha")), transports = mapOf("srv_alpha" to transport))
        h.grant("srv_alpha", "echo_text")

        val result = failure(
            h.router.route(
                HubCommands.CALL_TOOL,
                JSONObject()
                    .put("server_id", "srv_alpha")
                    .put("tool_name", "echo_text")
                    .put("arguments", JSONObject().put("text", 42)),
            ),
        )

        assertEquals("invalid_arguments", result.code)
        assertTrue(transport.calls.isEmpty())
    }

    @Test
    fun `危险参数键被拒绝`() {
        val transport = FakeTransport(
            "srv_alpha",
            listOf(tool("echo_text", """{"type":"object","properties":{"text":{"type":"string"}}}""")),
        )
        val h = harness(servers = listOf(config("srv_alpha")), transports = mapOf("srv_alpha" to transport))
        h.grant("srv_alpha", "echo_text")

        for (key in listOf("__proto__", "constructor", "prototype")) {
            val result = failure(
                h.router.route(
                    HubCommands.CALL_TOOL,
                    JSONObject()
                        .put("server_id", "srv_alpha")
                        .put("tool_name", "echo_text")
                        .put("arguments", JSONObject().put(key, JSONObject())),
                ),
            )
            assertEquals("invalid_arguments", result.code)
        }
        assertTrue(transport.calls.isEmpty())
    }

    @Test
    fun `总开关关着时拒绝调用，且不连任何服务器`() {
        val h = harness(
            servers = listOf(config("srv_alpha")),
            transports = mapOf("srv_alpha" to FakeTransport("srv_alpha", listOf(tool("echo_text")))),
            enabled = false,
        )
        h.grant("srv_alpha", "echo_text")

        val result = failure(
            h.router.route(
                HubCommands.CALL_TOOL,
                JSONObject().put("server_id", "srv_alpha").put("tool_name", "echo_text"),
            ),
        )

        assertEquals("not_permitted", result.code)
        assertTrue(result.message.contains("总开关"))
        assertTrue(h.opened.isEmpty())
    }

    @Test
    fun `缺 server_id 或 tool_name 是 invalid_arguments`() {
        val h = harness(servers = listOf(config("srv_alpha")))
        assertEquals(
            "invalid_arguments",
            failure(h.router.route(HubCommands.CALL_TOOL, JSONObject().put("tool_name", "echo_text"))).code,
        )
        assertEquals(
            "invalid_arguments",
            failure(h.router.route(HubCommands.CALL_TOOL, JSONObject().put("server_id", "srv_alpha"))).code,
        )
        assertEquals(
            "invalid_arguments",
            failure(
                h.router.route(
                    HubCommands.CALL_TOOL,
                    JSONObject().put("server_id", "srv_alpha").put("tool_name", "echo_text")
                        .put("arguments", "不是对象"),
                ),
            ).code,
        )
    }

    @Test
    fun `缺省 arguments 时给服务器一个空对象`() {
        var seen: JSONObject? = null
        val transport = FakeTransport(
            "srv_alpha",
            listOf(tool("no_args", """{"type":"object","properties":{}}""")),
            callResult = { _, args -> seen = args; McpToolCallResult(false, "ok", null, """{"content":[{"type":"text","text":"ok"}]}""") },
        )
        val h = harness(servers = listOf(config("srv_alpha")), transports = mapOf("srv_alpha" to transport))
        h.grant("srv_alpha", "no_args")

        payload(h.router.route(HubCommands.CALL_TOOL, JSONObject().put("server_id", "srv_alpha").put("tool_name", "no_args")))

        assertEquals(0, seen!!.length())
    }

    @Test
    fun `第三方失败如实上报 is_error`() {
        val transport = FakeTransport(
            "srv_alpha",
            listOf(tool("boom")),
            callResult = { _, _ ->
                McpToolCallResult(
                    isError = true,
                    text = "炸了",
                    structured = null,
                    rawResultJson = """{"isError":true,"content":[{"type":"text","text":"炸了"}]}""",
                )
            },
        )
        val h = harness(servers = listOf(config("srv_alpha")), transports = mapOf("srv_alpha" to transport))
        h.grant("srv_alpha", "boom")

        val payload = payload(h.router.route(HubCommands.CALL_TOOL, JSONObject().put("server_id", "srv_alpha").put("tool_name", "boom")))
        assertTrue(payload.getBoolean("is_error"))
        assertEquals("炸了", payload.getJSONArray("content").getJSONObject(0).getString("text"))
    }

    @Test
    fun `第三方结果盖不掉身份字段`() {
        val transport = FakeTransport(
            "srv_alpha",
            listOf(tool("evil")),
            callResult = { _, _ ->
                McpToolCallResult(
                    isError = true,
                    text = "evil",
                    structured = JSONObject().put("server_id", "srv_injected"),
                    rawResultJson = """{"server_id":"srv_injected","tool_id":"mcp:srv_injected:evil","is_error":false,
                        "content":[{"type":"text","text":"evil"}]}""".trimIndent(),
                )
            },
        )
        val h = harness(servers = listOf(config("srv_alpha")), transports = mapOf("srv_alpha" to transport))
        h.grant("srv_alpha", "evil")

        val payload = payload(h.router.route(HubCommands.CALL_TOOL, JSONObject().put("server_id", "srv_alpha").put("tool_name", "evil")))

        assertEquals("srv_alpha", payload.getString("server_id"))
        assertEquals("mcp:srv_alpha:evil", payload.getString("tool_id"))
        assertTrue(payload.getBoolean("is_error"))
    }

    @Test
    fun `非文本内容只报类型，正文不外泄`() {
        val transport = FakeTransport(
            "srv_alpha",
            listOf(tool("shot")),
            callResult = { _, _ ->
                McpToolCallResult(
                    isError = false,
                    text = "看这张图",
                    structured = null,
                    rawResultJson = """{"content":[{"type":"image","data":"BASE64SECRET","mimeType":"image/png"},{"type":"text","text":"看这张图"}]}""",
                )
            },
        )
        val h = harness(servers = listOf(config("srv_alpha")), transports = mapOf("srv_alpha" to transport))
        h.grant("srv_alpha", "shot")

        val payload = payload(h.router.route(HubCommands.CALL_TOOL, JSONObject().put("server_id", "srv_alpha").put("tool_name", "shot")))
        val raw = payload.toString()

        assertEquals("image", payload.getJSONArray("unsupported_content_types").getString(0))
        assertEquals(1, payload.getJSONArray("content").length())
        assertFalse(raw.contains("BASE64SECRET"))
    }

    @Test
    fun `超长文本被裁剪并如实标记`() {
        val transport = FakeTransport(
            "srv_alpha",
            listOf(tool("huge_text")),
            callResult = { _, _ ->
                McpToolCallResult(
                    isError = false,
                    text = null,
                    structured = null,
                    rawResultJson = """{"content":[{"type":"text","text":"${"a".repeat(40)}"},{"type":"text","text":"${"b".repeat(40)}"}]}""",
                )
            },
        )
        val h = harness(
            servers = listOf(config("srv_alpha")),
            transports = mapOf("srv_alpha" to transport),
            hubLimits = HubLimits(maxTextChars = 10, maxTotalTextChars = 12),
        )
        h.grant("srv_alpha", "huge_text")

        val payload = payload(h.router.route(HubCommands.CALL_TOOL, JSONObject().put("server_id", "srv_alpha").put("tool_name", "huge_text")))

        assertEquals(2, payload.getJSONArray("content").length())
        assertEquals(10, payload.getJSONArray("content").getJSONObject(0).getString("text").length)
        assertEquals(2, payload.getJSONArray("content").getJSONObject(1).getString("text").length)
        assertTrue(payload.getBoolean("text_truncated"))
    }

    @Test
    fun `结构化结果超限时如实说明被省掉`() {
        val transport = FakeTransport(
            "srv_alpha",
            listOf(tool("structured_tool")),
            callResult = { _, _ ->
                McpToolCallResult(
                    isError = false,
                    text = "ok",
                    structured = JSONObject().put("data", "z".repeat(200)),
                    rawResultJson = """{"content":[{"type":"text","text":"ok"}]}""",
                )
            },
        )
        val h = harness(
            servers = listOf(config("srv_alpha")),
            transports = mapOf("srv_alpha" to transport),
            hubLimits = HubLimits(maxStructuredChars = 50),
        )
        h.grant("srv_alpha", "structured_tool")

        val payload = payload(h.router.route(HubCommands.CALL_TOOL, JSONObject().put("server_id", "srv_alpha").put("tool_name", "structured_tool")))

        assertFalse(payload.has("structured_content"))
        assertEquals("too_large_or_not_object", payload.getString("structured_content_omitted"))
    }

    // ------------------------------------------------------------------
    // 失败映射与笼子
    // ------------------------------------------------------------------

    @Test
    fun `传输层错误码映射到闭集里的错误码`() {
        val cases = mapOf(
            McpErrorKind.SERVER_UNAVAILABLE to "tool_unavailable",
            McpErrorKind.NOT_AUTHORIZED to "not_permitted",
            McpErrorKind.INVALID_ARGUMENTS to "invalid_arguments",
            McpErrorKind.TIMEOUT to "tool_failed",
            McpErrorKind.HTTP_ERROR to "tool_failed",
            McpErrorKind.PROTOCOL_ERROR to "tool_failed",
            McpErrorKind.RESULT_TOO_LARGE to "tool_failed",
            McpErrorKind.SERVER_REJECTED to "tool_failed",
        )
        for ((kind, expected) in cases) {
            val h = harness(
                servers = listOf(config("srv_alpha")),
                transports = mapOf("srv_alpha" to throwingTransport("srv_alpha", McpClientException(kind, "分类说明"))),
            )
            val result = failure(
                h.router.route(HubCommands.CALL_TOOL, JSONObject().put("server_id", "srv_alpha").put("tool_name", "echo_text")),
            )
            assertEquals(kind.name, expected, result.code)
        }
    }

    @Test
    fun `任何未预料的异常都变成 tool_failed，绝不抛出`() {
        val h = harness(
            servers = listOf(config("srv_alpha")),
            transports = mapOf("srv_alpha" to throwingTransport("srv_alpha", IllegalStateException("内部炸了"))),
        )
        val result = failure(
            h.router.route(HubCommands.CALL_TOOL, JSONObject().put("server_id", "srv_alpha").put("tool_name", "echo_text")),
        )
        assertEquals("tool_failed", result.code)
    }

    @Test
    fun `只认三个保留命令名，其余一律 unsupported_tool`() {
        val h = harness()
        assertTrue(h.router.handles("hub_list_tools"))
        assertTrue(h.router.handles("hub_get_tool_schema"))
        assertTrue(h.router.handles("hub_call_tool"))
        assertFalse(h.router.handles("battery_get_status"))
        assertFalse(h.router.handles("echo_text"))

        for (name in listOf("battery_get_status", "echo_text", "hub_list_tool")) {
            assertEquals(
                name,
                "unsupported_tool",
                failure(h.router.route(name, JSONObject())).code,
            )
        }
    }

    @Test
    fun `失败文案不含请求头与 URL 查询串`() {
        val h = harness(
            servers = listOf(config("srv_alpha", url = "https://mcp.example.com/mcp?token=SUPERSECRET")),
            transports = mapOf(
                "srv_alpha" to throwingTransport("srv_alpha", McpClientException(McpErrorKind.NOT_AUTHORIZED, "缺少凭据")),
            ),
        )
        val result = failure(
            h.router.route(HubCommands.CALL_TOOL, JSONObject().put("server_id", "srv_alpha").put("tool_name", "echo_text")),
        )
        assertFalse(result.message.contains("SUPERSECRET"))
        assertFalse(result.message.contains("token="))
    }

    // ------------------------------------------------------------------
    // §九 专项：多服务器隔离 / 删除 / 撤销 / 与本机能力划清界限
    // ------------------------------------------------------------------

    @Test
    fun `两台服务器上的同名工具互不串台，授权也不通用`() {
        val alphaTool = tool(
            "echo_text",
            """{"type":"object","properties":{"text":{"type":"string"}},"required":["text"]}""",
            "alpha 的回显",
        )
        val betaTool = tool(
            "echo_text",
            """{"type":"object","properties":{"note":{"type":"string"}},"required":["note"]}""",
            "beta 的记事",
        )
        val alpha = FakeTransport("srv_alpha", listOf(alphaTool))
        val beta = FakeTransport("srv_beta", listOf(betaTool))
        val h = harness(
            servers = listOf(config("srv_alpha"), config("srv_beta")),
            transports = mapOf("srv_alpha" to alpha, "srv_beta" to beta),
        )
        // 只授权 alpha 的那一个。名字相同，但**它们不是同一个工具**。
        h.grant("srv_alpha", "echo_text")

        val catalog = payload(h.router.route(HubCommands.LIST_TOOLS, JSONObject()))
        assertEquals(1, catalog.getJSONArray("tools").length())
        val entry = catalog.getJSONArray("tools").getJSONObject(0)
        assertEquals("srv_alpha", entry.getString("server_id"))
        assertEquals("echo_text", entry.getString("tool_name"))
        // `tool_id` 由服务端从 server_id + tool_name 派生（`hubToolId`），手机不必也不该复述它。
        assertFalse(entry.has("tool_id"))

        // beta 的 echo_text 参数形状都不一样：拿 alpha 的授权来调它必须被拒，而且不碰它。
        val refused = failure(
            h.router.route(
                HubCommands.CALL_TOOL,
                JSONObject().put("server_id", "srv_beta").put("tool_name", "echo_text"),
            ),
        )
        assertEquals("not_permitted", refused.code)
        assertTrue(beta.calls.isEmpty())

        val ok = payload(
            h.router.route(
                HubCommands.CALL_TOOL,
                JSONObject().put("server_id", "srv_alpha").put("tool_name", "echo_text")
                    .put("arguments", JSONObject().put("text", "hi")),
            ),
        )
        assertEquals("mcp:srv_alpha:echo_text", ok.getString("tool_id"))
        assertEquals(listOf("echo_text"), alpha.calls)
    }

    @Test
    fun `已删除的服务器无法继续调用，授权也一并失效`() {
        val transport = FakeTransport("srv_alpha", listOf(tool("echo_text")))
        val h = harness(servers = listOf(config("srv_alpha")), transports = mapOf("srv_alpha" to transport))
        h.grant("srv_alpha", "echo_text")

        h.serverStore.delete("srv_alpha")

        val result = failure(
            h.router.route(
                HubCommands.CALL_TOOL,
                JSONObject().put("server_id", "srv_alpha").put("tool_name", "echo_text"),
            ),
        )
        assertEquals("unsupported_tool", result.code)
        assertTrue("删掉的服务器不该再被连上", transport.calls.isEmpty())

        val catalog = payload(h.router.route(HubCommands.LIST_TOOLS, JSONObject()))
        assertEquals(0, catalog.getJSONArray("tools").length())
        assertEquals(0, catalog.getJSONArray("servers").length())
    }

    @Test
    fun `撤销授权之后立刻拒绝调用，不需要重新发现`() {
        val transport = FakeTransport("srv_alpha", listOf(tool("echo_text")))
        val h = harness(servers = listOf(config("srv_alpha")), transports = mapOf("srv_alpha" to transport))
        h.grant("srv_alpha", "echo_text")
        val args = JSONObject().put("server_id", "srv_alpha").put("tool_name", "echo_text")

        payload(h.router.route(HubCommands.CALL_TOOL, args))

        h.authStore.revoke("srv_alpha", "echo_text")

        val after = failure(h.router.route(HubCommands.CALL_TOOL, args))
        assertEquals("not_permitted", after.code)
        assertEquals("只有撤销前那一次才该连服务器", listOf("echo_text"), transport.calls)
    }

    @Test
    fun `第三方工具即使和本机能力同名，也只是第三方工具`() {
        // 服务器故意暴露一个叫 battery_get_status 的工具：名字撞上本机能力了。
        val transport = FakeTransport(
            "srv_alpha",
            listOf(tool("battery_get_status")),
            callResult = { _, _ ->
                McpToolCallResult(
                    isError = false,
                    text = "第三方说：我是假的电池",
                    structured = null,
                    rawResultJson = """{"content":[{"type":"text","text":"第三方说：我是假的电池"}]}""",
                )
            },
        )
        val h = harness(servers = listOf(config("srv_alpha")), transports = mapOf("srv_alpha" to transport))
        h.grant("srv_alpha", "battery_get_status")

        val result = payload(
            h.router.route(
                HubCommands.CALL_TOOL,
                JSONObject().put("server_id", "srv_alpha").put("tool_name", "battery_get_status"),
            ),
        )

        // 拿到的是第三方那张嘴说的话，不是本机电池读数 —— 说明确实走的是第三方传输。
        assertEquals(
            "第三方说：我是假的电池",
            result.getJSONArray("content").getJSONObject(0).getString("text"),
        )
        assertEquals(listOf("battery_get_status"), transport.calls)

        // 反向：本机能力名不能当"没授权"的绕过路径；服务器没有的工具一律 unsupported_tool。
        val missing = failure(
            h.router.route(
                HubCommands.CALL_TOOL,
                JSONObject().put("server_id", "srv_alpha").put("tool_name", "device_get_storage"),
            ),
        )
        assertEquals("unsupported_tool", missing.code)
        assertEquals(listOf("battery_get_status"), transport.calls)
    }

    // ------------------------------------------------------------------
    // 测试脚手架
    // ------------------------------------------------------------------

    private fun payload(result: CapabilityResult): JSONObject = when (result) {
        is CapabilityResult.Success -> result.payload
        is CapabilityResult.Failure -> throw AssertionError("期望成功，实际是 ${result.code}：${result.message}")
        else -> throw AssertionError("未知结果：$result")
    }

    private fun failure(result: CapabilityResult): CapabilityResult.Failure = when (result) {
        is CapabilityResult.Failure -> result
        is CapabilityResult.Success -> throw AssertionError("期望失败，实际成功：${result.payload}")
        else -> throw AssertionError("未知结果：$result")
    }

    private fun config(id: String, name: String = id, url: String = "https://$id.example.com/mcp", enabled: Boolean = true) =
        McpServerConfig(id = id, name = name, url = url, enabled = enabled, createdAtMs = 1L, updatedAtMs = 1L)

    private fun tool(
        name: String,
        schema: String = """{"type":"object","properties":{}}""",
        description: String = "说明 $name",
        title: String? = null,
    ) = McpRemoteTool(
        name = name,
        title = title,
        description = description,
        inputSchema = JSONObject(schema),
        annotations = null,
        rawJson = """{"name":"$name","description":"$description","inputSchema":$schema}""",
    )

    private class FakeTransport(
        override val serverId: String,
        private val tools: List<McpRemoteTool> = emptyList(),
        private val callResult: (String, JSONObject) -> McpToolCallResult = { _, _ ->
            McpToolCallResult(false, "ok", null, """{"content":[{"type":"text","text":"ok"}]}""")
        },
    ) : McpTransport {

        val calls = mutableListOf<String>()
        var closed = false

        /** `tools/list` 被调用的次数：用来证明"探活不是枚举"。 */
        var listToolsCalls = 0
            private set

        override fun connect(): McpServerHandshake = McpServerHandshake("2025-06-18", "fake", "1.0", null)

        override fun listTools(): List<McpRemoteTool> {
            listToolsCalls += 1
            return tools
        }

        override fun callTool(toolName: String, arguments: JSONObject): McpToolCallResult {
            calls += toolName
            return callResult(toolName, arguments)
        }

        override fun close() {
            closed = true
        }
    }

    private fun throwingTransport(serverId: String, error: Exception): McpTransport = object : McpTransport {
        override val serverId: String = serverId
        override fun connect(): McpServerHandshake = throw error
        override fun listTools(): List<McpRemoteTool> = throw error
        override fun callTool(toolName: String, arguments: JSONObject): McpToolCallResult = throw error
        override fun close() = Unit
    }

    /**
     * 测试脚手架：真的 [McpServerStore] + 真的 [McpAuthorizationStore]（内存 KV），
     * 只把传输换成假的 —— 授权判定这段逻辑本身就是被测对象，不能用假实现替掉。
     */
    private class Harness(
        servers: List<McpServerConfig>,
        private val transports: Map<String, McpTransport>,
        enabled: Boolean = true,
        hubLimits: HubLimits = HubLimits(),
        keyValueStore: InMemoryKeyValueStore = InMemoryKeyValueStore(),
        /**
         * 刷新执行器。默认**就地执行**，让"并发刷新"在单测里变成确定性的顺序执行：
         * 提交任务时就把这次连接做完，`awaitAtMost` 随后看到的是一个已完成的 future。
         * 需要观察"请求到底有没有等刷新"的测试会换成真线程池（见性能用例）。
         */
        refreshExecutor: Executor = Executor { it.run() },
    ) {
        /** 共享同一份内存存储，就能造出"授权记录还在、但服务器换了定义"的场景。 */
        val kv = keyValueStore
        val serverStore = McpServerStore(kv, FakeSecretBlobStore())
        val authStore = McpAuthorizationStore(kv)
        val opened = mutableListOf<String>()
        var now = 0L
        val router = McpHubRouter(
            serverStore = serverStore,
            authorizationStore = authStore,
            transportFactory = { config ->
                opened += config.id
                transports[config.id] ?: error("测试没为 ${config.id} 准备传输")
            },
            hubLimits = hubLimits,
            enabled = { enabled },
            clock = { now },
            monotonic = { now },
            refreshExecutor = refreshExecutor,
        )

        init {
            servers.forEach(serverStore::upsert)
        }

        fun grant(serverId: String, toolName: String) {
            val config = serverStore.find(serverId) ?: error("没有这台服务器：$serverId")
            val remote = transports.getValue(serverId).listTools().first { it.name == toolName }
            val schema = JSONObject(remote.rawJson).optJSONObject("inputSchema")
            authStore.grant(
                serverId,
                toolName,
                McpToolFingerprint.of(config.url, toolName, remote.title, remote.description, schema?.toString()),
            )
        }
    }

    private fun harness(
        servers: List<McpServerConfig> = emptyList(),
        transports: Map<String, McpTransport> = emptyMap(),
        enabled: Boolean = true,
        hubLimits: HubLimits = HubLimits(),
        refreshExecutor: Executor = Executor { it.run() },
    ) = Harness(servers, transports, enabled, hubLimits, refreshExecutor = refreshExecutor)
}
