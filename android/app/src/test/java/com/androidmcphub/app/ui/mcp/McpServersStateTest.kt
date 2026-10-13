package com.androidmcphub.app.ui.mcp

import com.androidmcphub.app.mcp.McpClientException
import com.androidmcphub.app.mcp.McpErrorKind
import com.androidmcphub.app.mcp.McpLimits
import com.androidmcphub.app.mcp.McpToolIdentity
import com.androidmcphub.app.mcp.model.McpServerConfig
import com.androidmcphub.app.mcp.store.FakeSecretBlobStore
import com.androidmcphub.app.mcp.store.InMemoryKeyValueStore
import com.androidmcphub.app.mcp.store.McpAuthorizationState
import com.androidmcphub.app.mcp.store.McpAuthorizationStore
import com.androidmcphub.app.mcp.store.McpServerStore
import com.androidmcphub.app.mcp.transport.McpRemoteTool
import com.androidmcphub.app.mcp.transport.McpServerHandshake
import com.androidmcphub.app.mcp.transport.McpToolCallResult
import com.androidmcphub.app.mcp.transport.McpTransport
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 服务页状态层。
 *
 * 这一层是"用户点的那一下"与"运行时那次调用"之间唯一的共同事实来源：
 * 界面显示"已授权"、聚合入口放行调用，两边读的是同一份记录、同一套指纹。
 * 所以这里测的重点不是 UI 文案，而是**授权在什么时候仍然成立**：
 * 工具定义没变就成立，定义变了/地址变了/服务器删了就不成立。
 */
class McpServersStateTest {

    // ---- 保存 ---------------------------------------------------------------

    @Test
    fun `名称为空时拒绝保存且不落盘`() = runTest {
        val h = harness()
        val ok = h.state.save(McpServerDraft(name = "   ", url = LOOPBACK))

        assertFalse(ok)
        assertEquals("请填写服务名称", h.state.message.value)
        assertTrue(h.serverStore.list().isEmpty())
    }

    @Test
    fun `非本机的明文 http 被拒`() = runTest {
        val h = harness()
        val ok = h.state.save(McpServerDraft(name = "远端", url = "http://example.com/mcp"))

        assertFalse(ok)
        assertNotNull(h.state.message.value)
        assertTrue(h.serverStore.list().isEmpty())
    }

    @Test
    fun `禁用的请求头名被拒`() = runTest {
        val h = harness()
        val ok = h.state.save(
            McpServerDraft(
                name = "服务器",
                url = LOOPBACK,
                headers = listOf(McpHeaderDraft(name = "Host", value = "evil.example")),
            ),
        )

        assertFalse(ok)
        assertNotNull(h.state.message.value)
        assertTrue(h.serverStore.list().isEmpty())
    }

    @Test
    fun `请求头名字重复（大小写不敏感）被拒`() = runTest {
        val h = harness()
        val ok = h.state.save(
            McpServerDraft(
                name = "服务器",
                url = LOOPBACK,
                headers = listOf(
                    McpHeaderDraft(name = "Authorization", value = "Bearer a"),
                    McpHeaderDraft(name = "authorization", value = "Bearer b"),
                ),
            ),
        )

        assertFalse(ok)
        assertTrue(h.state.message.value!!.contains("两次"))
        assertTrue(h.serverStore.list().isEmpty())
    }

    @Test
    fun `保存成功后能在列表里看到且名称被去掉首尾空白`() = runTest {
        val h = harness()
        val ok = h.state.save(
            McpServerDraft(
                name = "  我的服务器  ",
                url = LOOPBACK,
                headers = listOf(McpHeaderDraft(name = "Authorization", value = "Bearer t")),
            ),
        )

        assertTrue(ok)
        val view = h.state.servers.value.single()
        assertEquals("我的服务器", view.config.name)
        assertEquals(LOOPBACK, view.config.url)
        assertTrue(view.config.id.startsWith("srv_"))
        assertEquals(McpDiscovery.Never, view.discovery)
        // 敏感值必须能原样读回来（读不回来 = 用户刷新时用的不是他填的那个密钥）。
        assertEquals("Bearer t", h.serverStore.find(view.config.id)!!.headers.single().value)
    }

    // ---- 发现 ---------------------------------------------------------------

    @Test
    fun `发现成功后工具可读且默认未授权`() = runTest {
        val transport = FakeTransport(tools = listOf(tool("echo_text")))
        val h = harness(factory = { transport })
        h.addServer()

        h.state.discover(SERVER_ID)
        advanceUntilIdle()

        val ready = h.state.servers.value.single().discovery as McpDiscovery.Ready
        assertEquals("fake-server", ready.serverName)
        assertEquals("2025-06-18", ready.protocolVersion)
        val view = ready.tools.single()
        assertEquals("echo_text", view.name)
        assertEquals("echo_text 的标题", view.title)
        assertEquals(McpAuthorizationState.NotGranted, view.authorization)
        assertTrue(transport.closed)
    }

    @Test
    fun `超长 schema 只截断显示、长度与指纹仍按原始定义算`() = runTest {
        val bigSchema = """{"type":"object","properties":{"q":{"type":"string","description":"${"x".repeat(2000)}"}}}"""
        val remote = tool("echo_text", schema = bigSchema)
        val h = harness(factory = { FakeTransport(tools = listOf(remote)) })
        h.addServer()

        h.state.discover(SERVER_ID)
        advanceUntilIdle()

        val view = (h.state.servers.value.single().discovery as McpDiscovery.Ready).tools.single()
        assertEquals(McpServersState.SCHEMA_DISPLAY_CHARS + 1, view.schemaText!!.length)
        assertTrue(view.schemaText!!.endsWith("…"))
        assertEquals(bigSchema.length, view.schemaChars)
        // 指纹必须与运行时（聚合入口）用同一份原始定义算出来的一致，否则界面显示
        // "已授权"、真正调用时却被判成"定义已变化"。
        assertEquals(McpToolIdentity.fingerprintOf(LOOPBACK, remote), view.fingerprint)
    }

    @Test
    fun `发现的失败原因用枚举标签而不是服务器原文`() = runTest {
        val h = harness(factory = {
            FakeTransport(
                failConnect = McpClientException(
                    kind = McpErrorKind.NOT_AUTHORIZED,
                    message = "server said: your token expired at 3pm",
                    httpStatus = 401,
                ),
            )
        })
        h.addServer()

        h.state.discover(SERVER_ID)
        advanceUntilIdle()

        val failed = h.state.servers.value.single().discovery as McpDiscovery.Failed
        assertTrue(failed.reason.contains(McpErrorKind.NOT_AUTHORIZED.label))
        assertTrue(failed.reason.contains("401"))
        assertFalse(failed.reason.contains("token expired"))
    }

    @Test
    fun `未知异常只报类名`() = runTest {
        val h = harness(factory = { FakeTransport(failConnect = IllegalStateException("内部细节：密钥 abc")) })
        h.addServer()

        h.state.discover(SERVER_ID)
        advanceUntilIdle()

        val failed = h.state.servers.value.single().discovery as McpDiscovery.Failed
        assertEquals("连接失败（IllegalStateException）", failed.reason)
    }

    @Test
    fun `停用的服务器不联网且状态为已停用`() = runTest {
        val transport = FakeTransport(tools = listOf(tool("echo_text")))
        val h = harness(factory = { transport })
        h.addServer()

        h.state.setEnabled(SERVER_ID, false)
        h.state.discover(SERVER_ID)
        advanceUntilIdle()

        assertEquals(0, transport.connects)
        val failed = h.state.servers.value.single().discovery as McpDiscovery.Failed
        assertEquals("这台服务器已停用", failed.reason)
        assertEquals(McpDiscovery.Never, h.state.discoveryOf(SERVER_ID).let { if (it is McpDiscovery.Failed) McpDiscovery.Never else it })
    }

    @Test
    fun `刷新全部跳过已就绪的与停用的`() = runTest {
        val transport = FakeTransport(tools = listOf(tool("echo_text")))
        val h = harness(factory = { transport })
        h.addServer()

        h.state.discoverAll()
        advanceUntilIdle()
        assertEquals(1, transport.connects)

        // 已经拿到结果：不重复连。
        h.state.discoverAll()
        advanceUntilIdle()
        assertEquals(1, transport.connects)

        // 强制刷新：再连一次。
        h.state.discoverAll(force = true)
        advanceUntilIdle()
        assertEquals(2, transport.connects)

        // 停用后连"强制刷新"也不连。
        h.state.setEnabled(SERVER_ID, false)
        h.state.discoverAll(force = true)
        advanceUntilIdle()
        assertEquals(2, transport.connects)
    }

    @Test
    fun `工具数量受上限约束`() = runTest {
        val h = harness(
            factory = { FakeTransport(tools = listOf(tool("a"), tool("b"), tool("c"))) },
            limits = McpLimits(maxTools = 2),
        )
        h.addServer()

        h.state.discover(SERVER_ID)
        advanceUntilIdle()

        val ready = h.state.servers.value.single().discovery as McpDiscovery.Ready
        assertEquals(listOf("a", "b"), ready.tools.map { it.name })
    }

    // ---- 授权 ---------------------------------------------------------------

    @Test
    fun `还没发现工具时授权会被拒绝`() = runTest {
        val h = harness()
        h.addServer()

        h.state.grant(SERVER_ID, "echo_text")

        assertEquals("请先刷新工具列表，再授权", h.state.message.value)
        assertTrue(h.authorizationStore.list().isEmpty())
    }

    @Test
    fun `授权后状态为已授权且落进授权记录`() = runTest {
        val h = harness(factory = { FakeTransport(tools = listOf(tool("echo_text"))) })
        h.addServer()
        h.state.discover(SERVER_ID)
        advanceUntilIdle()

        h.state.grant(SERVER_ID, "echo_text")

        val view = readyTool(h)
        assertEquals(McpAuthorizationState.Granted, view.authorization)
        assertTrue(h.authorizationStore.isAuthorized(SERVER_ID, "echo_text", view.fingerprint))
    }

    @Test
    fun `服务器改了工具描述之后授权变成定义已变化`() = runTest {
        var tools = listOf(tool("echo_text", description = "回显你给的文本"))
        val h = harness(factory = { FakeTransport(tools = tools) })
        h.addServer()
        h.state.discover(SERVER_ID)
        advanceUntilIdle()
        h.state.grant(SERVER_ID, "echo_text")
        assertEquals(McpAuthorizationState.Granted, readyTool(h).authorization)

        // 名字与参数结构一个字没动，只改描述 —— 说明书变了，用户当初点的那一版就不是这一版。
        tools = listOf(tool("echo_text", description = "回显你给的文本，并把它写进 note 参数"))
        h.state.discover(SERVER_ID)
        advanceUntilIdle()

        assertTrue(h.authorizationStore.list().isNotEmpty())
        assertEquals(McpAuthorizationState.Stale, readyTool(h).authorization)
    }

    @Test
    fun `重新授权之后状态回到已授权`() = runTest {
        var tools = listOf(tool("echo_text", description = "第一版"))
        val h = harness(factory = { FakeTransport(tools = tools) })
        h.addServer()
        h.state.discover(SERVER_ID)
        advanceUntilIdle()
        h.state.grant(SERVER_ID, "echo_text")

        tools = listOf(tool("echo_text", description = "第二版"))
        h.state.discover(SERVER_ID)
        advanceUntilIdle()
        assertEquals(McpAuthorizationState.Stale, readyTool(h).authorization)

        h.state.grant(SERVER_ID, "echo_text")

        assertEquals(McpAuthorizationState.Granted, readyTool(h).authorization)
        assertEquals(1, h.authorizationStore.list().size)
    }

    @Test
    fun `撤销授权后回到未授权`() = runTest {
        val h = harness(factory = { FakeTransport(tools = listOf(tool("echo_text"))) })
        h.addServer()
        h.state.discover(SERVER_ID)
        advanceUntilIdle()
        h.state.grant(SERVER_ID, "echo_text")

        h.state.revoke(SERVER_ID, "echo_text")

        assertEquals(McpAuthorizationState.NotGranted, readyTool(h).authorization)
        assertTrue(h.authorizationStore.list().isEmpty())
    }

    // ---- 增删改的连带后果 ----------------------------------------------------

    @Test
    fun `改地址会作废原有授权并丢掉旧的发现结果`() = runTest {
        val h = harness(factory = { FakeTransport(tools = listOf(tool("echo_text"))) })
        h.addServer()
        h.state.discover(SERVER_ID)
        advanceUntilIdle()
        h.state.grant(SERVER_ID, "echo_text")
        assertEquals(1, h.authorizationStore.list().size)

        val saved = h.state.save(
            McpServerDraft(
                id = SERVER_ID,
                name = "服务器",
                url = "http://127.0.0.1:8999/mcp",
            ),
        )

        assertTrue(saved)
        assertEquals("http://127.0.0.1:8999/mcp", h.serverStore.find(SERVER_ID)!!.url)
        assertTrue("换地址必须吊销旧授权", h.authorizationStore.list().isEmpty())
        assertEquals(McpDiscovery.Never, h.state.discoveryOf(SERVER_ID))
        assertTrue(h.state.message.value!!.contains("地址已变更"))
    }

    @Test
    fun `改名字不改地址时授权保留`() = runTest {
        val h = harness(factory = { FakeTransport(tools = listOf(tool("echo_text"))) })
        h.addServer()
        h.state.discover(SERVER_ID)
        advanceUntilIdle()
        h.state.grant(SERVER_ID, "echo_text")

        h.state.save(McpServerDraft(id = SERVER_ID, name = "换个名字", url = LOOPBACK))

        assertEquals("换个名字", h.serverStore.find(SERVER_ID)!!.name)
        assertEquals(1, h.authorizationStore.list().size)
        // 配置变了但地址没变：上次的发现结果仍然对应这台服务器，不用重新连。
        assertTrue(h.state.discoveryOf(SERVER_ID) is McpDiscovery.Ready)
    }

    @Test
    fun `删除服务器会连授权记录一起清掉`() = runTest {
        val h = harness(factory = { FakeTransport(tools = listOf(tool("echo_text"))) })
        h.addServer()
        h.state.discover(SERVER_ID)
        advanceUntilIdle()
        h.state.grant(SERVER_ID, "echo_text")

        h.state.delete(SERVER_ID)

        assertNull(h.serverStore.find(SERVER_ID))
        assertTrue(h.state.servers.value.isEmpty())
        assertTrue(h.authorizationStore.list().isEmpty())
        assertEquals(McpDiscovery.Never, h.state.discoveryOf(SERVER_ID))
    }

    @Test
    fun `列表按名称再按编号稳定排序`() = runTest {
        var counter = 0
        val h = harness(idFactory = { "srv_" + (++counter).toString().padStart(12, '0') })
        h.state.save(McpServerDraft(id = "", name = "beta", url = "http://127.0.0.1:8001/mcp"))
        h.state.save(McpServerDraft(id = "", name = "Alpha", url = "http://127.0.0.1:8002/mcp"))
        h.state.save(McpServerDraft(id = "", name = "alpha", url = "http://127.0.0.1:8003/mcp"))

        assertEquals(listOf("Alpha", "alpha", "beta"), h.state.servers.value.map { it.config.name })
    }

    // ---- 脚手架 -------------------------------------------------------------

    private fun readyTool(h: Harness): McpToolView =
        (h.state.servers.value.single().discovery as McpDiscovery.Ready).tools.single()

    private class Harness(
        val serverStore: McpServerStore,
        val authorizationStore: McpAuthorizationStore,
        val state: McpServersState,
    ) {
        fun addServer() {
            assertTrue(
                state.save(McpServerDraft(id = SERVER_ID, name = "服务器", url = LOOPBACK)),
            )
        }
    }

    private fun kotlinx.coroutines.test.TestScope.harness(
        factory: (McpServerConfig) -> McpTransport = { FakeTransport() },
        limits: McpLimits = McpLimits(),
        idFactory: () -> String = { SERVER_ID },
    ): Harness {
        val serverStore = McpServerStore(InMemoryKeyValueStore(), FakeSecretBlobStore())
        val authorizationStore = McpAuthorizationStore(InMemoryKeyValueStore(), clock = { NOW })
        val state = McpServersState(
            serverStore = serverStore,
            authorizationStore = authorizationStore,
            scope = this,
            transportFactory = factory,
            io = StandardTestDispatcher(testScheduler) as CoroutineDispatcher,
            clock = { NOW },
            idFactory = idFactory,
            limits = limits,
        )
        return Harness(serverStore, authorizationStore, state)
    }

    private class FakeTransport(
        private val handshake: McpServerHandshake = McpServerHandshake(
            protocolVersion = "2025-06-18",
            serverName = "fake-server",
            serverVersion = "1.0.0",
            sessionId = "sid-1",
        ),
        private val tools: List<McpRemoteTool> = emptyList(),
        private val failConnect: Throwable? = null,
        private val failList: Throwable? = null,
    ) : McpTransport {

        override val serverId: String = "fake"

        var connects: Int = 0
        var closed: Boolean = false

        override fun connect(): McpServerHandshake {
            connects++
            failConnect?.let { throw it }
            return handshake
        }

        override fun listTools(): List<McpRemoteTool> {
            failList?.let { throw it }
            return tools
        }

        override fun callTool(toolName: String, arguments: JSONObject): McpToolCallResult =
            throw UnsupportedOperationException("发现流程不应该调用任何工具")

        override fun close() {
            closed = true
        }
    }

    private companion object {
        const val SERVER_ID = "srv_test000001"
        const val LOOPBACK = "http://127.0.0.1:8934/mcp"
        const val NOW = 1_700_000_000_000L

        fun tool(
            name: String,
            description: String = "回显你给的文本",
            schema: String = """{"type":"object","properties":{"q":{"type":"string"}}}""",
        ): McpRemoteTool {
            val raw = JSONObject()
                .put("name", name)
                .put("title", "$name 的标题")
                .put("description", description)
            if (schema.isNotEmpty()) raw.put("inputSchema", JSONObject(schema))
            return McpRemoteTool(
                name = name,
                title = "$name 的标题",
                description = description,
                inputSchema = if (schema.isEmpty()) null else JSONObject(schema),
                annotations = null,
                rawJson = raw.toString(),
            )
        }
    }
}
