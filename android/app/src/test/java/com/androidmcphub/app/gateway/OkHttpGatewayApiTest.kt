package com.androidmcphub.app.gateway

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * 真实 HTTP 形态：令牌只进 Authorization 头，错误只取固定 error 码。
 *
 * 这里刻意不让 mock 响应体里的随机文本进入异常消息 —— 上游响应是不可信输入，
 * 把它拼进界面/日志既会泄露内容，也会让错误码失去意义。
 */
class OkHttpGatewayApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: OkHttpGatewayApi
    private lateinit var base: String

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        base = server.url("").toString().trimEnd('/')
        api = OkHttpGatewayApi()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `配对请求把口令放 JSON 体且不带任何 bearer`() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"managementToken":"hub_admin_x","clientToken":"hub_client_x","ownerId":"o","clientId":"c"}""",
            ),
        )

        val result = api.pair(base, "12345678", "android-abc", "Pixel 8")

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/gateway/pair", request.path)
        assertEquals(null, request.getHeader("Authorization"))
        val body = JSONObject(request.body.readUtf8())
        assertEquals("12345678", body.getString("code"))
        assertEquals("android-abc", body.getString("deviceId"))
        assertEquals("Pixel 8", body.getString("deviceName"))
        assertEquals("hub_admin_x", result.managementToken)
        assertEquals("hub_client_x", result.clientToken)
    }

    @Test
    fun `管理请求把令牌放在 Authorization 头而不是地址里`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))

        api.admin(base, "hub_admin_secret", JSONObject().put("action", "list"))

        val request = server.takeRequest()
        assertEquals("/gateway/android", request.path)
        assertEquals("Bearer hub_admin_secret", request.getHeader("Authorization"))
        assertFalse(request.path!!.contains("hub_admin"))
        assertEquals("list", JSONObject(request.body.readUtf8()).getString("action"))
    }

    @Test
    fun `列表响应被解析为界面模型`() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "services": [{
                    "id": "s1", "name": "S1", "url": "https://up.example/mcp",
                    "transport": "streamable-http", "enabled": true,
                    "toolMap": [{"original":"ping","name":"ext_s1_ping","fingerprint":"fp"}],
                    "tools": [{"name":"ping","description":"ping"}]
                  }],
                  "android": {"ownerId":"o","clientId":"c","deviceId":"d","deviceName":"Pixel"}
                }
                """.trimIndent(),
            ),
        )

        val snapshot = api.list(base, "hub_admin_secret")

        assertEquals("s1", snapshot.services.single().id)
        assertEquals("ext_s1_ping", snapshot.services.single().tools.single().gatewayName)
        assertEquals("Pixel", snapshot.client?.deviceName)
    }

    @Test
    fun `错误响应只保留服务端 error 码`() {
        server.enqueue(
            MockResponse().setResponseCode(401).setBody("""{"error":"pairing_denied","detail":"secret upstream text"}"""),
        )

        try {
            api.pair(base, "12345678", "android-abc", "Pixel")
            fail("应当抛出")
        } catch (error: GatewayApiException) {
            assertEquals("pairing_denied", error.code)
            assertEquals(401, error.status)
            assertFalse(error.message!!.contains("secret upstream text"))
        }
    }

    @Test
    fun `非 JSON 错误响应不把响应体回显给用户`() {
        server.enqueue(MockResponse().setResponseCode(502).setBody("<html>bad gateway token=abc</html>"))

        try {
            api.admin(base, "hub_admin_secret", JSONObject().put("action", "list"))
            fail("应当抛出")
        } catch (error: GatewayApiException) {
            assertEquals(null, error.code)
            assertFalse(error.message!!.contains("bad gateway"))
            assertFalse(error.message!!.contains("token=abc"))
        }
    }

    @Test
    fun `成功但非 JSON 的响应视为解析失败`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("not-json"))

        try {
            api.pair(base, "12345678", "android-abc", "Pixel")
            fail("应当抛出")
        } catch (error: GatewayApiException) {
            assertEquals("invalid_json", error.code)
        }
    }

    @Test
    fun `超大响应被拒绝而不是读进内存`() {
        // 2 MiB + 1 字节；服务端多数情况下不会返回这么大，但被攻破的上游可能。
        val body = "x".repeat(2 * 1024 * 1024 + 1)
        server.enqueue(MockResponse().setResponseCode(200).setBody(body))

        try {
            api.pair(base, "12345678", "android-abc", "Pixel")
            fail("应当抛出")
        } catch (error: GatewayApiException) {
            assertEquals("response_too_large", error.code)
        }
    }

    @Test
    fun `配对响应缺少令牌时抛解析异常而不是返回半截凭据`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ownerId":"o"}"""))

        try {
            api.pair(base, "12345678", "android-abc", "Pixel")
            fail("应当抛出")
        } catch (_: org.json.JSONException) {
            // 预期：绝不能用空令牌覆盖本机已有凭据。
            assertTrue(true)
        }
    }
}
