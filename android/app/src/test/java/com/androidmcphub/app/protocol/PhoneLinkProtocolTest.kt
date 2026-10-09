package com.androidmcphub.app.protocol

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 协议解析与编码（R1、R4、R5 的纯逻辑部分）。 */
class PhoneLinkProtocolTest {

    @Test
    fun `解析 welcome 的全部字段`() {
        val raw = """
            {"type":"welcome","device_id":"test-phone-1","connection_generation":3,
             "server_time":1760000000000,"heartbeat_interval_ms":30000,"ping_timeout_ms":10000,
             "max_pending_requests":16,"protocol_version":"phone-link-v1"}
        """.trimIndent()

        val frame = parseIncoming(raw)

        assertTrue("应当是 Welcome，实际 $frame", frame is IncomingFrame.Welcome)
        val welcome = frame as IncomingFrame.Welcome
        assertEquals("test-phone-1", welcome.deviceId)
        assertEquals(3L, welcome.connectionGeneration)
        assertEquals(1_760_000_000_000L, welcome.serverTime)
        assertEquals(30_000L, welcome.heartbeatIntervalMs)
        assertEquals(10_000L, welcome.pingTimeoutMs)
        assertEquals(16, welcome.maxPendingRequests)
        assertEquals("phone-link-v1", welcome.protocolVersion)
    }

    @Test
    fun `welcome 缺少可选字段时为 null`() {
        val welcome = parseIncoming("""{"type":"welcome","device_id":"d"}""") as IncomingFrame.Welcome

        assertEquals("d", welcome.deviceId)
        assertEquals(0L, welcome.connectionGeneration)
        assertNull(welcome.heartbeatIntervalMs)
        assertNull(welcome.pingTimeoutMs)
        assertNull(welcome.maxPendingRequests)
        assertNull(welcome.protocolVersion)
    }

    @Test
    fun `解析 phone_ping`() {
        val frame = parseIncoming("""{"type":"phone_ping","request_id":"uuid-1","timestamp":1760000000123}""")

        assertTrue("应当是 Ping，实际 $frame", frame is IncomingFrame.Ping)
        val ping = frame as IncomingFrame.Ping
        assertEquals("uuid-1", ping.requestId)
        assertEquals(1_760_000_000_123L, ping.timestamp)
    }

    @Test
    fun `解析 heartbeat_ack`() {
        val frame = parseIncoming("""{"type":"heartbeat_ack","server_time":1760000000456}""")

        assertTrue("应当是 HeartbeatAck，实际 $frame", frame is IncomingFrame.HeartbeatAck)
        assertEquals(1_760_000_000_456L, (frame as IncomingFrame.HeartbeatAck).serverTime)
    }

    @Test
    fun `解析 error`() {
        val frame = parseIncoming("""{"type":"error","code":"message_too_large","message":"帧太大"}""")

        assertTrue("应当是 ServerError，实际 $frame", frame is IncomingFrame.ServerError)
        val error = frame as IncomingFrame.ServerError
        assertEquals("message_too_large", error.code)
        assertEquals("帧太大", error.message)
    }

    @Test
    fun `未知 type 归为 Unknown 且不抛异常（R4）`() {
        val frame = parseIncoming("""{"type":"phone_screenshot","request_id":"x"}""")

        assertTrue("应当是 Unknown，实际 $frame", frame is IncomingFrame.Unknown)
        val unknown = frame as IncomingFrame.Unknown
        assertEquals("phone_screenshot", unknown.type)
        assertEquals(UnknownReason.UNSUPPORTED_TYPE, unknown.reason)
    }

    @Test
    fun `非法 JSON 归为 Unknown（R4）`() {
        for (raw in listOf("", "不是 JSON", "{", "[1,2,3]", "null")) {
            val frame = parseIncoming(raw)
            assertTrue("raw=$raw 应当是 Unknown，实际 $frame", frame is IncomingFrame.Unknown)
            assertEquals(UnknownReason.INVALID_JSON, (frame as IncomingFrame.Unknown).reason)
        }
    }

    @Test
    fun `缺少 type 归为 Unknown`() {
        val unknown = parseIncoming("""{"request_id":"uuid-1"}""") as IncomingFrame.Unknown

        assertEquals("", unknown.type)
        assertEquals(UnknownReason.MISSING_TYPE, unknown.reason)
    }

    @Test
    fun `phone_ping 缺少 request_id 时不构造 Ping（无法回包，只能忽略）`() {
        val frame = parseIncoming("""{"type":"phone_ping","timestamp":1760000000123}""")

        assertTrue("应当是 Unknown，实际 $frame", frame is IncomingFrame.Unknown)
        val unknown = frame as IncomingFrame.Unknown
        assertEquals("phone_ping", unknown.type)
        assertEquals(UnknownReason.MISSING_REQUEST_ID, unknown.reason)
    }

    @Test
    fun `encodePong 的 request_id 与输入逐字符相同且 type 正确（R1）`() {
        val ids = listOf(
            "uuid-1",
            "550e8400-e29b-41d4-a716-446655440000",
            "带 空格 与\"引号\"的中文",
            "a/b+c=",
            "x".repeat(200),
        )

        for (id in ids) {
            val obj = JSONObject(encodePong(id, 1_760_000_000_155L))
            assertEquals("phone_pong", obj.getString("type"))
            assertEquals(id, obj.getString("request_id")) // 解析回来仍逐字符相同
            assertEquals(1_760_000_000_155L, obj.getLong("timestamp"))
        }
    }

    @Test
    fun `encodePong 对引号做正确转义`() {
        val raw = encodePong("""he said "hi"""", 1L)
        val obj = JSONObject(raw)
        assertEquals("""he said "hi"""", obj.getString("request_id"))
        assertTrue("JSON 里必须出现转义后的引号", raw.contains("\\\""))
    }

    @Test
    fun `encodeHeartbeat 与 encodeHello`() {
        val heartbeat = JSONObject(encodeHeartbeat(1_760_000_000_500L))
        assertEquals("heartbeat", heartbeat.getString("type"))
        assertEquals(1_760_000_000_500L, heartbeat.getLong("timestamp"))

        val hello = JSONObject(encodeHello("test-phone-1", 35))
        assertEquals("hello", hello.getString("type"))
        assertEquals("test-phone-1", hello.getString("device_id"))
        assertEquals("phone-link-v1", hello.getString("protocol_version"))
        assertEquals("0.1.0", hello.getString("app_version"))
        assertEquals(35, hello.getInt("android_sdk"))
    }

    @Test
    fun `数字以字符串形式下发时也能解析（宽容解析）`() {
        val frame = parseIncoming(
            """{"type":"welcome","device_id":"d","connection_generation":"7",
                "heartbeat_interval_ms":"25000","max_pending_requests":"4"}""",
        )

        assertTrue("应当是 Welcome，实际 $frame", frame is IncomingFrame.Welcome)
        val welcome = frame as IncomingFrame.Welcome
        assertEquals(7L, welcome.connectionGeneration)
        assertEquals(25_000L, welcome.heartbeatIntervalMs)
        assertEquals(4, welcome.maxPendingRequests)
    }

    @Test
    fun `常量与契约一致`() {
        assertEquals("phone-link-v1", PROTOCOL_VERSION)
        assertEquals("0.1.0", APP_VERSION)
        assertEquals("android-mcp-hub/0.1.0", CLIENT_VERSION)
        assertEquals(256, PING_DEDUP_CAPACITY)
    }
}
