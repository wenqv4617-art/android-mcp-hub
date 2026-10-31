package com.androidmcphub.app.net

import com.androidmcphub.app.core.HubConfig
import com.androidmcphub.app.core.TokenFailure
import com.androidmcphub.app.core.TokenLoadResult
import com.androidmcphub.app.protocol.LinkState
import com.androidmcphub.app.protocol.PhoneLink
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 在 JVM 上用 MockWebServer 跑真实 WebSocket 链路。
 *
 * 这里验证的是「网络层 ↔ 状态机」的接线：请求头、hello、pong、认证失败。
 * 纯协议规则（R1–R6）在 [com.androidmcphub.app.protocol.PhoneLinkTest] 里覆盖。
 */
class PhoneLinkClientTest {

    private fun awaitState(
        states: List<LinkState>,
        timeoutMs: Long = 10_000L,
        predicate: (LinkState) -> Boolean,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (states.any(predicate)) return true
            Thread.sleep(20)
        }
        return states.any(predicate)
    }

    @Test
    fun `真实链路上完成握手并回复 phone_pong`() {
        val server = MockWebServer()
        val clientFrames = LinkedBlockingQueue<String>()
        val serverOpened = CountDownLatch(1)

        val serverListener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                serverOpened.countDown()
                val now = System.currentTimeMillis()
                webSocket.send(
                    """{"type":"welcome","device_id":"test-phone-1","connection_generation":3,""" +
                        """"server_time":$now,"heartbeat_interval_ms":30000,"ping_timeout_ms":10000,""" +
                        """"max_pending_requests":16,"protocol_version":"phone-link-v1"}""",
                )
                webSocket.send("""{"type":"phone_ping","request_id":"uuid-1","timestamp":$now}""")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                clientFrames.add(text)
            }
        }
        server.enqueue(MockResponse().withWebSocketUpgrade(serverListener))
        server.start()

        val states = CopyOnWriteArrayList<LinkState>()
        val config = HubConfig(serverUrl = server.url("/phone/ws").toString(), deviceId = "test-phone-1")
        val client = PhoneLinkClient(
            configProvider = { config },
            credentialProvider = { TokenLoadResult.Present("test-token-not-a-secret") },
            androidSdk = 35,
            onStateChanged = { states += it },
        )

        try {
            client.start()
            assertTrue("服务端应当收到 WebSocket 建连", serverOpened.await(10, TimeUnit.SECONDS))

            // 第一帧必须是 hello
            val helloRaw = clientFrames.poll(10, TimeUnit.SECONDS)
            assertNotNull("应当发出 hello 帧", helloRaw)
            val hello = JSONObject(helloRaw!!)
            assertEquals("hello", hello.getString("type"))
            assertEquals("test-phone-1", hello.getString("device_id"))
            assertEquals("phone-link-v1", hello.getString("protocol_version"))
            assertEquals("1.6.2", hello.getString("app_version"))
            assertEquals(35, hello.getInt("android_sdk"))

            // 随后必须用同一个 request_id 回 phone_pong
            var pong: JSONObject? = null
            val deadline = System.currentTimeMillis() + 10_000L
            while (pong == null && System.currentTimeMillis() < deadline) {
                val raw = clientFrames.poll(500, TimeUnit.MILLISECONDS) ?: continue
                val obj = JSONObject(raw)
                if (obj.optString("type") == "phone_pong") pong = obj
            }
            assertNotNull("应当回 phone_pong", pong)
            assertEquals("uuid-1", pong!!.getString("request_id"))
            assertTrue(
                "pong 的时间戳应当是本机当前时间",
                Math.abs(pong.getLong("timestamp") - System.currentTimeMillis()) < 60_000L,
            )

            // 请求头符合协议契约
            val request = server.takeRequest(5, TimeUnit.SECONDS)
            assertNotNull(request)
            assertEquals("Bearer test-token-not-a-secret", request!!.getHeader("Authorization"))
            assertEquals("test-phone-1", request.getHeader("X-Device-Id"))
            assertEquals("android-mcp-hub/1.6.2", request.getHeader("X-Client-Version"))
            assertEquals("GET", request.method)
            assertTrue("按契约不带 query", request.path == "/phone/ws")

            // welcome 之后界面必须看到「已连接（代次 3）」
            assertTrue(
                "状态应当变为已连接，实际 $states",
                awaitState(states) { it is LinkState.Connected && it.generation == 3L },
            )
            assertNotNull("最近一次 ping 应当有记录", client.lastPing)
            assertEquals("uuid-1", client.lastPing?.requestId)
        } finally {
            client.shutdown()
            runCatching { server.shutdown() }
        }
    }

    @Test
    fun `服务端返回 401 时进入认证失败且不重连`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(401).setBody("unauthorized"))
        server.start()

        val states = CopyOnWriteArrayList<LinkState>()
        val config = HubConfig(serverUrl = server.url("/phone/ws").toString(), deviceId = "test-phone-1")
        val client = PhoneLinkClient(
            configProvider = { config },
            credentialProvider = { TokenLoadResult.Present("test-token-not-a-secret") },
            androidSdk = 35,
            onStateChanged = { states += it },
        )

        try {
            client.start()
            assertTrue(
                "应当是认证失败，实际 $states",
                awaitState(states) { it is LinkState.AuthFailed },
            )
        } finally {
            client.shutdown()
            runCatching { server.shutdown() }
        }
    }

    @Test
    fun `问题4 服务端以 4002 撤销时进入终态，且整条链路上不再发起第二次连接`() {
        val server = MockWebServer()
        val serverOpened = CountDownLatch(1)
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        serverOpened.countDown()
                        // 与 Worker 的真实行为一致：先发 error 帧，再以 4002 关闭
                        webSocket.send("""{"type":"error","code":"device_revoked","message":"revoked"}""")
                        webSocket.close(4002, "device credential revoked")
                    }
                },
            ),
        )
        // 第二、三次连接若真的发生，会被 MockWebServer 按队列顺序立刻放行，
        // 所以「只发生一次连接」这件事可以被 requestCount 精确测出来。
        repeat(3) {
            server.enqueue(
                MockResponse().withWebSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) = Unit
                    },
                ),
            )
        }
        server.start()

        val states = CopyOnWriteArrayList<LinkState>()
        val config = HubConfig(
            serverUrl = server.url("/phone/ws").toString(),
            deviceId = "test-phone-1",
            // 退避窗口压到 300ms，让"本来会重连"在测试时限内必然发生
            reconnectInitialDelayMs = 300L,
            reconnectMaxDelayMs = 300L,
            reconnectJitterRatio = 0.0,
        )
        val client = PhoneLinkClient(
            configProvider = { config },
            credentialProvider = { TokenLoadResult.Present("test-token-not-a-secret") },
            androidSdk = 35,
            onStateChanged = { states += it },
        )

        try {
            client.start()
            assertTrue("服务端已握手", serverOpened.await(5, TimeUnit.SECONDS))
            assertTrue(
                "应当是「凭据已被撤销」的终态，实际 $states",
                awaitState(states) { it is LinkState.Revoked },
            )

            // 等待时间远长于退避窗口：若还留着自动重连，这里必然出现第二次连接
            Thread.sleep(1_500L)

            assertEquals("撤销之后不得再发起任何新连接，状态历史=$states", 1, server.requestCount)
            assertFalse("不得再进入重连等待", states.any { it is LinkState.Reconnecting })
            assertEquals(
                LinkState.Revoked(PhoneLink.REVOKED_DETAIL),
                states.filterIsInstance<LinkState.Revoked>().last(),
            )
        } finally {
            client.shutdown()
            runCatching { server.shutdown() }
        }
    }

    @Test
    fun `服务器地址无效时直接进入出错状态且不发起连接`() {
        val states = CopyOnWriteArrayList<LinkState>()
        val config = HubConfig(serverUrl = "", deviceId = "test-phone-1")
        val client = PhoneLinkClient(
            configProvider = { config },
            credentialProvider = { TokenLoadResult.Present("test-token-not-a-secret") },
            androidSdk = 35,
            onStateChanged = { states += it },
        )

        try {
            client.start()
            assertTrue(
                "应当是出错状态，实际 $states",
                awaitState(states, timeoutMs = 2_000L) { it is LinkState.Error },
            )
            assertEquals(
                LinkState.Error("服务器地址无效，请在「高级设置」里填写形如 wss://<host>/phone/ws 的地址"),
                states.first { it is LinkState.Error },
            )
        } finally {
            client.shutdown()
        }
    }

    @Test
    fun `设备令牌为空时拒绝连接`() {
        val states = CopyOnWriteArrayList<LinkState>()
        val config = HubConfig(serverUrl = "wss://example.test/phone/ws", deviceId = "test-phone-1")
        val client = PhoneLinkClient(
            configProvider = { config },
            credentialProvider = { TokenLoadResult.Present("") },
            androidSdk = 35,
            onStateChanged = { states += it },
        )

        try {
            client.start()
            assertTrue(
                "令牌为空必须拒绝连接，实际 $states",
                awaitState(states, timeoutMs = 2_000L) { it is LinkState.Error },
            )
        } finally {
            client.shutdown()
        }
    }

    // ── Gate 0.1 审计 R4：凭据读不出来时绝不建连 ──────────────────────────────

    @Test
    fun `令牌从未配置时拒绝连接，服务端一个请求都收不到`() {
        val server = MockWebServer()
        server.start()

        val states = CopyOnWriteArrayList<LinkState>()
        val config = HubConfig(serverUrl = server.url("/phone/ws").toString(), deviceId = "test-phone-1")
        val client = PhoneLinkClient(
            configProvider = { config },
            credentialProvider = { TokenLoadResult.Absent },
            androidSdk = 35,
            onStateChanged = { states += it },
        )

        try {
            client.start()
            assertTrue(
                "没配置令牌必须拒绝连接，实际 $states",
                awaitState(states, timeoutMs = 2_000L) { it is LinkState.Error },
            )
            assertEquals(
                "不能拿空令牌去撞服务器",
                0,
                server.requestCount,
            )
        } finally {
            client.shutdown()
            runCatching { server.shutdown() }
        }
    }

    @Test
    fun `令牌解不开时拒绝连接，且提示与「从未配置」不同`() {
        val server = MockWebServer()
        server.start()

        val states = CopyOnWriteArrayList<LinkState>()
        val config = HubConfig(serverUrl = server.url("/phone/ws").toString(), deviceId = "test-phone-1")
        val client = PhoneLinkClient(
            configProvider = { config },
            credentialProvider = { TokenLoadResult.Unreadable(TokenFailure.DECRYPT_FAILED) },
            androidSdk = 35,
            onStateChanged = { states += it },
        )

        try {
            client.start()
            assertTrue(
                "解不开的令牌必须拒绝连接，实际 $states",
                awaitState(states, timeoutMs = 2_000L) { it is LinkState.Error },
            )
            // 关键：**不能**退化成"空令牌重试" —— 也就是一个请求都不该发出去。
            assertEquals(0, server.requestCount)

            val error = states.filterIsInstance<LinkState.Error>().first()
            assertTrue(
                "提示必须说清是解不开，而不是没配置：${error.message}",
                error.message.contains("无法解密"),
            )
            assertTrue(
                "提示里应当带上固定类别，便于排查：${error.message}",
                error.message.contains(TokenFailure.DECRYPT_FAILED),
            )
            assertFalse("提示里不该出现令牌本身", error.message.contains("token-"))
        } finally {
            client.shutdown()
            runCatching { server.shutdown() }
        }
    }

    @Test
    fun `每次建连都重新读凭据：中途读不出来就不再发起新连接`() {
        val server = MockWebServer()
        val serverOpened = CountDownLatch(1)
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        serverOpened.countDown()
                    }
                },
            ),
        )
        server.start()

        var reads = 0
        var credentialUsable = true
        val states = CopyOnWriteArrayList<LinkState>()
        val config = HubConfig(serverUrl = server.url("/phone/ws").toString(), deviceId = "test-phone-1")
        val client = PhoneLinkClient(
            configProvider = { config },
            credentialProvider = {
                reads += 1
                if (credentialUsable) {
                    TokenLoadResult.Present("test-token-not-a-secret")
                } else {
                    TokenLoadResult.Absent
                }
            },
            androidSdk = 35,
            onStateChanged = { states += it },
        )

        try {
            client.start()
            assertTrue("第一次建连应当成功", serverOpened.await(10, TimeUnit.SECONDS))
            assertEquals(1, server.requestCount)

            // 凭据在这期间坏掉了（密钥被系统作废、存储被改）。
            // 再点一次「连接」：必须**不**发起新的网络请求，而不是拿空令牌去撞一次 401。
            val readsBefore = reads
            credentialUsable = false
            client.start()

            assertTrue(
                "第二次应当因凭据不可用而报错，实际 $states",
                awaitState(states, timeoutMs = 2_000L) { state ->
                    state is LinkState.Error && state.message.contains("还没有配置设备令牌")
                },
            )
            assertEquals("凭据读不出来就不该再有新的建连请求", 1, server.requestCount)
            assertTrue("凭据必须每次建连都重新读（含 openSocket 那一次）", reads > readsBefore)
        } finally {
            client.shutdown()
            runCatching { server.shutdown() }
        }
    }
}
