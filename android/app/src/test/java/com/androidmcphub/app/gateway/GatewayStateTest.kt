package com.androidmcphub.app.gateway

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 云端网关状态层。
 *
 * 重点不在"按钮文案"，而在几条真实约束：
 *  * 令牌不进入 UI 状态，只存在加密存储里；
 *  * 配对/管理/调用失败时，本机不会留下"看起来已连接"的假象；
 *  * 「需确认」工具必须先拿到服务端票据，再把票据交给 MCP 调用。
 */
class GatewayStateTest {

    @Test
    fun `配对成功后保存令牌、规范化地址并加载服务`() = runTest {
        val h = harness()
        h.api.snapshot = GatewaySnapshot(listOf(service()), client())

        h.state.pair("  https://hub.example.com/  ", " 12345678 ")
        advanceUntilIdle()

        assertEquals("https://hub.example.com", h.store.base)
        assertEquals("hub_admin_test", h.store.credentials?.managementToken)
        assertEquals("hub_client_test", h.store.credentials?.clientToken)
        assertTrue(h.state.state.value.paired)
        assertFalse(h.state.state.value.pairing)
        assertEquals(listOf("s1"), h.state.state.value.services.map { it.id })
        assertEquals("Test Device", h.state.state.value.client?.deviceName)
        assertEquals("android-test", h.api.lastDeviceId)
        assertEquals("12345678", h.api.lastCode)
        // 令牌绝不能进入界面状态（Compose 重组/崩溃日志都会把它带出去）。
        assertFalse(h.state.state.value.toString().contains("hub_admin_test"))
        assertFalse(h.state.state.value.toString().contains("hub_client_test"))
    }

    @Test
    fun `配对失败不落盘且错误是固定文案`() = runTest {
        val h = harness()
        h.api.pairError = GatewayApiException("pairing_denied", 401)

        h.state.pair("https://hub.example.com", "12345678")
        advanceUntilIdle()

        assertNull(h.store.credentials)
        assertFalse(h.state.state.value.paired)
        assertEquals("配对口令不正确", h.state.state.value.error)
    }

    @Test
    fun `远端 http 与非根地址在发请求前就被拒绝`() = runTest {
        val h = harness()

        h.state.pair("http://hub.example.com", "12345678")
        h.state.pair("https://hub.example.com/mcp", "12345678")
        advanceUntilIdle()

        assertTrue(h.api.pairCalls == 0)
        assertNotNull(h.state.state.value.error)
    }

    @Test
    fun `过短的配对码在本地被拒绝`() = runTest {
        val h = harness()
        h.state.pair("https://hub.example.com", "short")
        advanceUntilIdle()
        assertEquals(0, h.api.pairCalls)
        assertTrue(h.state.state.value.error!!.contains("配对码"))
    }

    @Test
    fun `列表返回 unauthorized 时清掉本机凭据并要求重新配对`() = runTest {
        val h = harness(paired = true)
        h.api.listError = GatewayApiException("unauthorized", 401)

        h.state.refresh()
        advanceUntilIdle()

        assertTrue(h.store.cleared)
        assertNull(h.store.credentials)
        assertFalse(h.state.state.value.paired)
        assertEquals("网关凭据已失效，请重新配对", h.state.state.value.error)
    }

    @Test
    fun `网络异常不会让已配对状态丢失`() = runTest {
        val h = harness(paired = true)
        h.api.listError = java.io.IOException("connection reset")

        h.state.refresh()
        advanceUntilIdle()

        assertTrue(h.state.state.value.paired)
        assertEquals("网络不可达，请检查网络后重试", h.state.state.value.error)
        assertNotNull(h.store.credentials)
    }

    @Test
    fun `自动检测先试 Streamable HTTP，失败后回退 SSE 并保存结果`() = runTest {
        val h = harness(paired = true)
        h.api.onAdmin = { action ->
            when (action.optString("action")) {
                "test" -> {
                    if (h.api.lastPutTransport == "streamable-http") {
                        throw GatewayApiException("upstream_failure", 502)
                    }
                    JSONObject().put("tools", JSONArray().put(JSONObject().put("name", "ping")).put(JSONObject().put("name", "pong")))
                }
                else -> JSONObject()
            }
        }

        h.state.save(
            GatewayServiceDraft(
                id = "s1",
                name = "示例服务",
                url = "https://up.example/mcp",
                transport = GatewayTransport.Auto,
            ),
        )
        advanceUntilIdle()

        val transports = h.api.adminCalls
            .filter { it.optString("action") == "put-service" }
            .map { it.getString("transport") }
        assertEquals(listOf("streamable-http", "sse"), transports)
        assertTrue(h.state.state.value.notice!!.contains("旧版 HTTP+SSE"))
        assertTrue(h.state.state.value.notice!!.contains("2"))
        assertNull(h.state.state.value.busyServiceId)
    }

    @Test
    fun `固定 Streamable HTTP 测试失败时保留已保存服务并明确提示`() = runTest {
        val h = harness(paired = true)
        h.api.onAdmin = { action ->
            if (action.optString("action") == "test") {
                throw GatewayApiException("upstream_timeout", 504)
            }
            JSONObject()
        }

        h.state.save(
            GatewayServiceDraft(
                id = "s1",
                name = "示例服务",
                url = "https://up.example/mcp",
                transport = GatewayTransport.StreamableHttp,
            ),
        )
        advanceUntilIdle()

        val puts = h.api.adminCalls.filter { it.optString("action") == "put-service" }
        assertEquals(1, puts.size)
        assertTrue(h.state.state.value.notice!!.contains("连接测试失败"))
        assertTrue(h.state.state.value.notice!!.contains("超时"))
        assertNull(h.state.state.value.error)
    }

    @Test
    fun `保存前校验名称与 https 地址`() = runTest {
        val h = harness(paired = true)

        h.state.save(GatewayServiceDraft(id = "s1", name = "  ", url = "https://up.example/mcp"))
        assertTrue(h.state.state.value.error!!.contains("名称"))
        h.state.save(GatewayServiceDraft(id = "s1", name = "N", url = "http://up.example/mcp"))
        assertTrue(h.state.state.value.error!!.contains("https"))
        advanceUntilIdle()

        assertTrue(h.api.adminCalls.isEmpty())
    }

    @Test
    fun `密钥引用只允许受限格式`() = runTest {
        val h = harness(paired = true)
        h.state.save(
            GatewayServiceDraft(
                id = "s1",
                name = "N",
                url = "https://up.example/mcp",
                secretRef = "Bad Secret!",
            ),
        )
        advanceUntilIdle()
        assertTrue(h.state.state.value.error!!.contains("密钥引用"))
        assertTrue(h.api.adminCalls.isEmpty())
    }

    @Test
    fun `保存时把服务端密钥引用原样上报且不下发密钥明文`() = runTest {
        val h = harness(paired = true)
        h.state.save(
            GatewayServiceDraft(
                id = "s1",
                name = "N",
                url = "https://up.example/mcp",
                secretRef = "my-service",
                transport = GatewayTransport.StreamableHttp,
            ),
            testAfterSave = false,
        )
        advanceUntilIdle()

        val put = h.api.adminCalls.single { it.optString("action") == "put-service" }
        assertEquals("my-service", put.getString("secretRef"))
        assertFalse(put.has("token"))
        assertFalse(put.has("apiKey"))
    }

    @Test
    fun `授权请求带上工具定义指纹，撤销只带工具名`() = runTest {
        val h = harness(paired = true)
        h.api.snapshot = GatewaySnapshot(listOf(service()), client())
        h.state.refresh()
        advanceUntilIdle()
        val tool = h.state.state.value.services.single().tools.single()

        h.state.grant("s1", tool, GatewayGrantMode.Confirm)
        advanceUntilIdle()
        val grant = h.api.adminCalls.last { it.optString("action") == "grant" }
        assertEquals("s1", grant.getString("serviceId"))
        assertEquals("ping", grant.getString("tool"))
        assertEquals("confirm", grant.getString("mode"))
        assertEquals("fp-1", grant.getString("fingerprint"))

        h.state.revoke("s1", tool)
        advanceUntilIdle()
        val revoke = h.api.adminCalls.last { it.optString("action") == "revoke" }
        assertEquals("ping", revoke.getString("tool"))
        assertFalse(revoke.has("mode"))
    }

    @Test
    fun `需确认工具先取一次性票据再交给 MCP 调用`() = runTest {
        val h = harness(paired = true)
        h.api.snapshot = GatewaySnapshot(listOf(service(grant = GatewayGrantMode.Confirm)), client())
        h.state.refresh()
        advanceUntilIdle()
        val tool = h.state.state.value.services.single().tools.single()
        h.api.onAdmin = { action ->
            if (action.optString("action") == "confirm") {
                JSONObject().put("confirmation", "ticket-123")
            } else {
                JSONObject()
            }
        }
        h.caller.result = GatewayCallResult(isError = false, text = "pong")

        h.state.call("s1", tool, """{"query":"hello"}""")
        advanceUntilIdle()

        val confirm = h.api.adminCalls.last { it.optString("action") == "confirm" }
        assertEquals("ext_s1_ping", confirm.getString("name"))
        assertEquals("hello", confirm.getJSONObject("arguments").getString("query"))

        val call = h.caller.calls.single()
        assertEquals("hub_client_test", call.clientToken)
        assertEquals("ext_s1_ping", call.gatewayToolName)
        assertEquals("ticket-123", call.confirmation)
        assertEquals("pong", h.state.state.value.lastCall?.text)
        assertFalse(h.state.state.value.lastCall!!.isError)
        assertNull(h.state.state.value.busyServiceId)
    }

    @Test
    fun `只读工具直接调用，不申请票据`() = runTest {
        val h = harness(paired = true)
        h.api.snapshot = GatewaySnapshot(listOf(service(grant = GatewayGrantMode.Read)), client())
        h.state.refresh()
        advanceUntilIdle()
        val tool = h.state.state.value.services.single().tools.single()
        h.caller.result = GatewayCallResult(isError = false, text = "ok")

        h.state.call("s1", tool, "")
        advanceUntilIdle()

        assertTrue(h.api.adminCalls.none { it.optString("action") == "confirm" })
        assertNull(h.caller.calls.single().confirmation)
        assertEquals("ok", h.state.state.value.lastCall?.text)
    }

    @Test
    fun `未授权工具不会发任何网络请求`() = runTest {
        val h = harness(paired = true)
        h.api.snapshot = GatewaySnapshot(listOf(service(grant = null)), client())
        h.state.refresh()
        advanceUntilIdle()
        val tool = h.state.state.value.services.single().tools.single()

        h.state.call("s1", tool, "{}")
        advanceUntilIdle()

        assertTrue(h.caller.calls.isEmpty())
        assertTrue(h.api.adminCalls.none { it.optString("action") == "confirm" })
        assertEquals("这个工具还没有授权", h.state.state.value.error)
    }

    @Test
    fun `参数不是 JSON 对象时本地拦下`() = runTest {
        val h = harness(paired = true)
        h.api.snapshot = GatewaySnapshot(listOf(service()), client())
        h.state.refresh()
        advanceUntilIdle()
        val tool = h.state.state.value.services.single().tools.single()

        h.state.call("s1", tool, """[1,2,3]""")
        advanceUntilIdle()

        assertTrue(h.caller.calls.isEmpty())
        assertTrue(h.state.state.value.error!!.contains("JSON"))
    }

    @Test
    fun `MCP 调用返回 isError 时按失败展示而不是抛出`() = runTest {
        val h = harness(paired = true)
        h.api.snapshot = GatewaySnapshot(listOf(service()), client())
        h.state.refresh()
        advanceUntilIdle()
        val tool = h.state.state.value.services.single().tools.single()
        h.caller.result = GatewayCallResult(isError = true, text = "工具执行失败")

        h.state.call("s1", tool, "{}")
        advanceUntilIdle()

        assertTrue(h.state.state.value.lastCall!!.isError)
        assertNull(h.state.state.value.error)
    }

    @Test
    fun `删除服务走服务端并刷新列表`() = runTest {
        val h = harness(paired = true)
        h.api.snapshot = GatewaySnapshot(listOf(service()), client())
        h.state.delete("s1")
        advanceUntilIdle()

        val delete = h.api.adminCalls.single { it.optString("action") == "delete-service" }
        assertEquals("s1", delete.getString("id"))
        assertNull(h.state.state.value.busyServiceId)
    }

    @Test
    fun `解除配对先尝试服务端撤销，再清本机`() = runTest {
        val h = harness(paired = true)

        h.state.unpair()
        advanceUntilIdle()

        assertTrue(h.api.adminCalls.any { it.optString("action") == "unpair" })
        assertTrue(h.store.cleared)
        assertNull(h.store.credentials)
        assertFalse(h.state.state.value.paired)
        assertTrue(h.state.state.value.services.isEmpty())
    }

    @Test
    fun `服务端撤销失败也清理本机，用户不会被卡住`() = runTest {
        val h = harness(paired = true)
        h.api.onAdmin = { throw java.io.IOException("offline") }

        h.state.unpair()
        advanceUntilIdle()

        assertTrue(h.store.cleared)
        assertFalse(h.state.state.value.paired)
        assertTrue(h.state.state.value.notice!!.contains("本机解除配对"))
    }

    // ---- harness ------------------------------------------------------------

    private fun TestScope.harness(paired: Boolean = false): Harness {
        val dispatcher: CoroutineDispatcher = StandardTestDispatcher(testScheduler)
        val store = FakeCredentialsStore().apply {
            if (paired) {
                base = "https://hub.example.com"
                credentials = GatewayCredentials("hub_admin_test", "hub_client_test")
            }
        }
        val api = FakeApi()
        val caller = FakeCaller()
        return Harness(
            state = GatewayState(
                api = api,
                credentialsStore = store,
                caller = caller,
                scope = this,
                io = dispatcher,
                clock = { 1700000000000 },
                idFactory = { "s1" },
            ),
            api = api,
            store = store,
            caller = caller,
        )
    }

    private class Harness(
        val state: GatewayState,
        val api: FakeApi,
        val store: FakeCredentialsStore,
        val caller: FakeCaller,
    )

    private class FakeCredentialsStore(
        var base: String = "",
        var credentials: GatewayCredentials? = null,
    ) : GatewayCredentialsStore {
        var cleared = false
        override fun baseUrl(): String = base
        override fun saveBaseUrl(baseUrl: String) {
            base = baseUrl
        }
        override fun load(): GatewayCredentials? = credentials
        override fun save(credentials: GatewayCredentials) {
            this.credentials = credentials
        }
        override fun clear() {
            credentials = null
            cleared = true
        }
        override fun deviceId(): String = "android-test"
        override fun deviceName(): String = "Test Device"
    }

    private class FakeApi : GatewayApi {
        var pairCalls = 0
        var lastCode: String? = null
        var lastDeviceId: String? = null
        var pairError: Exception? = null
        var listError: Exception? = null
        var lastPutTransport: String? = null
        var snapshot = GatewaySnapshot(emptyList(), null)
        val adminCalls = mutableListOf<JSONObject>()
        var onAdmin: ((JSONObject) -> JSONObject)? = null

        override fun pair(baseUrl: String, code: String, deviceId: String, deviceName: String): GatewayPairResult {
            pairCalls += 1
            lastCode = code
            lastDeviceId = deviceId
            pairError?.let { throw it }
            return GatewayPairResult(
                managementToken = "hub_admin_test",
                clientToken = "hub_client_test",
                ownerId = "android-test",
                clientId = "client:android-test",
            )
        }

        override fun admin(baseUrl: String, managementToken: String, action: JSONObject): JSONObject {
            adminCalls += action
            if (action.optString("action") == "put-service") {
                lastPutTransport = action.optString("transport")
            }
            return onAdmin?.invoke(action) ?: JSONObject()
        }

        override fun list(baseUrl: String, managementToken: String): GatewaySnapshot {
            listError?.let { throw it }
            return snapshot
        }
    }

    private data class RecordedCall(
        val clientToken: String,
        val gatewayToolName: String,
        val arguments: JSONObject,
        val confirmation: String?,
    )

    private class FakeCaller : GatewayCaller {
        var result = GatewayCallResult(isError = false, text = "ok")
        val calls = mutableListOf<RecordedCall>()
        override fun call(
            baseUrl: String,
            clientToken: String,
            gatewayToolName: String,
            arguments: JSONObject,
            confirmation: String?,
        ): GatewayCallResult {
            calls += RecordedCall(clientToken, gatewayToolName, arguments, confirmation)
            return result
        }
    }

    private fun service(
        id: String = "s1",
        grant: GatewayGrantMode? = GatewayGrantMode.Read,
    ) = GatewayService(
        id = id,
        name = "示例服务",
        description = "",
        url = "https://up.example/mcp",
        transport = GatewayTransport.StreamableHttp,
        enabled = true,
        secretRef = null,
        timeoutMs = 10_000,
        revision = "rev-1",
        tools = listOf(
            GatewayTool(
                name = "ping",
                gatewayName = "ext_s1_ping",
                description = "ping",
                schemaJson = """{"type":"object"}""",
                fingerprint = "fp-1",
                grant = grant,
            ),
        ),
        lastError = null,
        lastTestAtMs = 1700000000000,
    )

    private fun client() = GatewayClientInfo(
        ownerId = "android-test",
        clientId = "client:android-test",
        deviceId = "android-test",
        deviceName = "Test Device",
        lastPairedAtMs = 1700000000000,
    )
}
