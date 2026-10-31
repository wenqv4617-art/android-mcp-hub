package com.androidmcphub.app.net

import com.androidmcphub.app.core.HubConfig
import com.androidmcphub.app.core.TokenLoadResult
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * [CloudDiagnosticsClient] 的 JVM 测试：地址推导、鉴权头、解析与失败闭集。
 *
 * 这里**不**验证真机网络，也不验证"云端确实记了这条事件" —— 那需要真实 Worker
 * 与设备令牌（见 TEST_REPORT.md）。这里只证明：手机把请求发到哪里、带什么头、
 * 如何把响应收敛成界面能安全展示的结构。
 */
class CloudDiagnosticsClientTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun client(
        token: String? = "test-token-not-a-secret",
        config: HubConfig = HubConfig(
            serverUrl = server.url("/phone/ws").toString(),
            deviceId = "test-phone-1",
        ),
        httpClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(500, TimeUnit.MILLISECONDS)
            .readTimeout(500, TimeUnit.MILLISECONDS)
            .callTimeout(2, TimeUnit.SECONDS)
            .build(),
    ) = CloudDiagnosticsClient(
        configProvider = { config },
        credentialProvider = {
            if (token == null) TokenLoadResult.Absent else TokenLoadResult.Present(token)
        },
        clock = { 1_700_000_000_500L },
        httpClient = httpClient,
    )

    private val happyBody = """
        {
          "ok": true,
          "device_id": "test-phone-1",
          "server_time": 1700000001000,
          "online": true,
          "socket_open": true,
          "stale": false,
          "connection_generation": 52,
          "last_phone_activity_at": 1700000000000,
          "events": [
            {
              "seq": 7,
              "at": 1700000000000,
              "client_at": 1699999999000,
              "source": "durable_object",
              "type": "replaced",
              "generation": 51,
              "close_code": 4001,
              "close_reason": "replaced_by_newer_connection",
              "server_initiated": true,
              "duration_ms": 30000,
              "detail": {
                "cause": "new_connection",
                "replaced_generation": "50",
                "exception_class": "SocketException",
                "unknown_free_text": "Bearer should-never-be-shown"
              }
            }
          ],
          "summary": {
            "last_online_at": 1700000000000,
            "last_offline_at": 1699999990000,
            "last_offline_reason": "replaced_by_newer_connection",
            "last_offline_close_code": 4001,
            "last_server_initiated_close_at": 1699999990000,
            "replaced_events": 3,
            "revocations": 0,
            "liveness_timeouts": 1,
            "auth_failures": 0,
            "client_reports": 5
          },
          "buffer": {
            "size": 9, "returned": 1, "dropped": 2,
            "first_at": 1699999000000, "last_at": 1700000000000,
            "max_events": 200, "max_age_ms": 86400000
          },
          "close_codes": {
            "4000": "liveness_timeout",
            "4001": "replaced_by_newer_connection",
            "4002": "device_credential_revoked"
          }
        }
    """.trimIndent()

    // ---- 地址推导 ----------------------------------------------------------

    @Test
    fun `wss 地址换算成同主机的 https 诊断路由`() {
        assertEquals(
            "https://hub.example.workers.dev/phone/diagnostics",
            CloudDiagnosticsClient.diagnosticsUrlOf("wss://hub.example.workers.dev/phone/ws"),
        )
        assertEquals(
            "http://127.0.0.1:8787/phone/diagnostics",
            CloudDiagnosticsClient.diagnosticsUrlOf("ws://127.0.0.1:8787/phone/ws"),
        )
    }

    @Test
    fun `地址里的 query 与 fragment 不会带进诊断请求`() {
        val url = CloudDiagnosticsClient.diagnosticsUrlOf(
            "wss://hub.example/phone/ws?token=should-not-be-forwarded#frag",
        )

        assertEquals("https://hub.example/phone/diagnostics", url)
        assertFalse(url!!.contains("token"))
    }

    @Test
    fun `非标准路径按固定路由追加`() {
        assertEquals(
            "https://hub.example/diagnostics",
            CloudDiagnosticsClient.diagnosticsUrlOf("wss://hub.example/ws"),
        )
        assertEquals(
            "https://hub.example/phone/diagnostics",
            CloudDiagnosticsClient.diagnosticsUrlOf("wss://hub.example/phone/ws/"),
        )
        assertEquals(
            "https://hub.example/edge/phone/diagnostics",
            CloudDiagnosticsClient.diagnosticsUrlOf("wss://hub.example/edge/phone/ws"),
        )
        assertEquals(
            "https://hub.example/custom/phone/diagnostics",
            CloudDiagnosticsClient.diagnosticsUrlOf("wss://hub.example/custom"),
        )
    }

    @Test
    fun `非法地址不猜，直接返回 null`() {
        assertNull(CloudDiagnosticsClient.diagnosticsUrlOf(""))
        assertNull(CloudDiagnosticsClient.diagnosticsUrlOf("ftp://hub.example/ws"))
        assertNull(CloudDiagnosticsClient.diagnosticsUrlOf("hub.example"))
    }

    // ---- 请求与解析 --------------------------------------------------------

    @Test
    fun `请求带设备鉴权头且只走 GET`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(happyBody))

        val result = client().fetch()

        assertTrue("解析应当成功：$result", result is CloudDiagnosticsResult.Success)
        val request = server.takeRequest(2, TimeUnit.SECONDS)
        assertNotNull(request)
        assertEquals("GET", request!!.method)
        assertEquals("/phone/diagnostics?limit=100", request.path)
        assertEquals("Bearer test-token-not-a-secret", request.getHeader("Authorization"))
        assertEquals("test-phone-1", request.getHeader("X-Device-Id"))
        assertNull("设备令牌不能出现在 URL 里", request.requestUrl?.queryParameter("token"))
    }

    @Test
    fun `成功响应被解析成结构化快照并校正时钟差`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(happyBody))

        val payload = (client().fetch() as CloudDiagnosticsResult.Success).payload

        assertEquals("test-phone-1", payload.deviceId)
        assertTrue(payload.online)
        assertTrue(payload.socketOpen)
        assertFalse(payload.stale)
        assertEquals(52L, payload.connectionGeneration)
        assertEquals("服务端 1000ms − 本机 500ms", 500L, payload.clockOffsetMs)
        assertEquals(
            "服务端时间戳 + 时钟差",
            1_700_000_001_000L,
            payload.serverToLocalAxis(1_700_000_000_500L),
        )

        val event = payload.events.single()
        assertEquals("replaced", event.type)
        assertEquals(4001, event.closeCode)
        assertEquals(true, event.serverInitiated)
        assertEquals(52L - 1L, event.generation)
        assertEquals("new_connection", event.detail.cause)
        assertEquals("50", event.detail["replaced_generation"])
        assertEquals("SocketException", event.detail.exceptionClass)
        assertNull(
            "白名单之外的 detail 键整条丢弃",
            event.detail["unknown_free_text"],
        )
        assertFalse(
            "响应里的任意文本不能出现在解析结果中",
            payload.events.any { it.detail.values.values.any { v -> v.contains("Bearer") } },
        )

        assertEquals("replaced_by_newer_connection", payload.closeCodeLabel(4001))
        assertNull("服务端没定义的关闭码不本地臆造语义", payload.closeCodeLabel(4321))
        assertEquals(3, payload.summary.replacedEvents)
        assertEquals(2, payload.buffer.dropped)
        assertEquals(200, payload.buffer.maxEvents)
    }

    @Test
    fun `关闭码语义完全来自服务端响应`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(happyBody))

        val payload = (client().fetch() as CloudDiagnosticsResult.Success).payload

        assertEquals(
            mapOf(
                4000 to "liveness_timeout",
                4001 to "replaced_by_newer_connection",
                4002 to "device_credential_revoked",
            ),
            payload.closeCodes,
        )
    }

    // ---- 失败闭集 ----------------------------------------------------------

    @Test
    fun `401 与 403 与 404 映射成不同的可操作原因`() {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(403))
        server.enqueue(MockResponse().setResponseCode(404))

        assertEquals(
            CloudDiagFailure.UNAUTHORIZED,
            (client().fetch() as CloudDiagnosticsResult.Failure).kind,
        )
        assertEquals(
            CloudDiagFailure.FORBIDDEN,
            (client().fetch() as CloudDiagnosticsResult.Failure).kind,
        )
        assertEquals(
            CloudDiagFailure.ENDPOINT_MISSING,
            (client().fetch() as CloudDiagnosticsResult.Failure).kind,
        )
    }

    @Test
    fun `5xx 归为服务端错误并保留状态码`() {
        server.enqueue(MockResponse().setResponseCode(503))

        val failure = client().fetch() as CloudDiagnosticsResult.Failure

        assertEquals(CloudDiagFailure.SERVER_ERROR, failure.kind)
        assertEquals(503, failure.httpStatus)
    }

    @Test
    fun `超时收敛成 TIMEOUT 而不是抛异常`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(happyBody)
                .setBodyDelay(2, TimeUnit.SECONDS),
        )

        val result = client(
            httpClient = OkHttpClient.Builder()
                .readTimeout(200, TimeUnit.MILLISECONDS)
                .callTimeout(500, TimeUnit.MILLISECONDS)
                .build(),
        ).fetch()

        assertEquals(CloudDiagFailure.TIMEOUT, (result as CloudDiagnosticsResult.Failure).kind)
    }

    @Test
    fun `连接被断开收敛成 NETWORK`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        val result = client().fetch()

        assertEquals(CloudDiagFailure.NETWORK, (result as CloudDiagnosticsResult.Failure).kind)
    }

    @Test
    fun `缺少 ok 或 server_time 的响应不可用`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"events":[]}"""))
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"ok":true,"events":[]}"""),
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("not json"))

        repeat(3) {
            val failure = client().fetch() as CloudDiagnosticsResult.Failure
            assertEquals(
                "没有服务端时间就无法校正两端时钟差，不能假装一致",
                CloudDiagFailure.BAD_RESPONSE,
                failure.kind,
            )
        }
    }

    @Test
    fun `没填地址或没有令牌时根本不发请求`() {
        val noUrl = client(config = HubConfig(serverUrl = "", deviceId = "test-phone-1"))
        val noToken = client(token = null)

        assertEquals(
            CloudDiagFailure.INVALID_URL,
            (noUrl.fetch() as CloudDiagnosticsResult.Failure).kind,
        )
        assertEquals(
            CloudDiagFailure.NOT_CONFIGURED,
            (noToken.fetch() as CloudDiagnosticsResult.Failure).kind,
        )
        assertEquals("不应产生任何 HTTP 请求", 0, server.requestCount)
    }

    @Test
    fun `limit 被收敛在服务端允许的范围内`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(happyBody))
        server.enqueue(MockResponse().setResponseCode(200).setBody(happyBody))
        val c = client()

        c.fetch(limit = 0)
        c.fetch(limit = 10_000)

        assertEquals(
            "/phone/diagnostics?limit=1",
            server.takeRequest(2, TimeUnit.SECONDS)!!.path,
        )
        assertEquals(
            "/phone/diagnostics?limit=${CloudDiagnosticsClient.MAX_LIMIT}",
            server.takeRequest(2, TimeUnit.SECONDS)!!.path,
        )
    }

    @Test
    fun `默认客户端不跟随重定向且不重试`() {
        val http = CloudDiagnosticsClient.defaultHttpClient()

        assertFalse("诊断接口不该把凭据转发到另一个地址", http.followRedirects)
        assertFalse(http.followSslRedirects)
        assertFalse(http.retryOnConnectionFailure)
    }
}
