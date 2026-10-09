package com.androidmcphub.app.protocol

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 去重 LRU 与 pong 生成（R2）。 */
class PingResponderTest {

    private fun ping(id: String, ts: Long) = IncomingFrame.Ping(requestId = id, timestamp = ts)

    @Test
    fun `首次见到 request_id 才回复`() {
        var now = 1_760_000_000_100L
        val responder = PingResponder(clock = { now })

        val first = responder.handle(ping("uuid-1", 1_760_000_000_050L))
        assertFalse(first.duplicate)
        assertNotNull(first.frame)
        val obj = JSONObject(first.frame!!)
        assertEquals("phone_pong", obj.getString("type"))
        assertEquals("uuid-1", obj.getString("request_id"))

        val second = responder.handle(ping("uuid-1", 1_760_000_000_050L))
        assertTrue("同一 request_id 第二次必须不回复", second.duplicate)
        assertNull(second.frame)

        // 换一个 request_id 仍然要回
        now += 10
        val other = responder.handle(ping("uuid-2", now))
        assertFalse(other.duplicate)
    }

    @Test
    fun `RTT 是本机收到时刻减服务端时间戳的两倍`() {
        val responder = PingResponder(clock = { 1_760_000_000_100L })

        val outcome = responder.handle(ping("uuid-1", 1_760_000_000_050L))

        assertEquals(1_760_000_000_050L, outcome.serverTimestampMs)
        assertEquals(1_760_000_000_100L, outcome.receivedAtMs)
        assertEquals(50L, outcome.oneWayMs)
        assertEquals(100L, outcome.rttMs)
        // pong 用的 timestamp 是本机当前时间
        assertEquals(1_760_000_000_100L, JSONObject(outcome.frame!!).getLong("timestamp"))
    }

    @Test
    fun `时钟未同步导致时间戳超前时不会出现负数`() {
        val responder = PingResponder(clock = { 1_000L })

        val outcome = responder.handle(ping("uuid-1", 9_999_999L))

        assertEquals(0L, outcome.oneWayMs)
        assertEquals(0L, outcome.rttMs)
    }

    @Test
    fun `timestamp 为 0 时延迟按 0 处理`() {
        val responder = PingResponder(clock = { 1_000L })

        val outcome = responder.handle(ping("uuid-1", 0L))

        assertEquals(0L, outcome.oneWayMs)
        assertEquals(0L, outcome.rttMs)
    }

    @Test
    fun `容量上限内的重复依然被去重`() {
        val responder = PingResponder(clock = { 5L })

        repeat(256) { responder.handle(ping("id-$it", 5L)) }
        assertEquals(256, responder.seenCount())
        assertTrue(responder.handle(ping("id-0", 5L)).duplicate)
    }

    @Test
    fun `超出容量后最久未使用的 request_id 被淘汰（可以再回一次）`() {
        val responder = PingResponder(capacity = 2, clock = { 5L })

        responder.handle(ping("a", 5L))
        responder.handle(ping("b", 5L))
        // 重新见到 a：a 被顶到最新，b 变成最久未使用
        assertTrue(responder.handle(ping("a", 5L)).duplicate)
        // 塞入 c，应当淘汰 b（而不是淘汰刚被访问过的 a）
        responder.handle(ping("c", 5L))

        assertEquals(2, responder.seenCount())
        // 若「命中时刷新 LRU 顺序」失效，上面塞 c 时被淘汰的就会是 a，这条会红。
        assertTrue("a 刚被访问过，必须留在窗口内", responder.handle(ping("a", 5L)).duplicate)
        // 顺序要紧：下面这次 handle 自己会把 b 塞回表里、从而挤掉 c，
        // 所以「b 曾被淘汰」必须在把 b 放回去之前断言，否则测的不是淘汰行为。
        assertFalse("b 是最久未使用的那个，应当已被淘汰、可以再回一次", responder.handle(ping("b", 5L)).duplicate)
        assertEquals(2, responder.seenCount())
    }

    @Test
    fun `reset 之后可以重新回复同一个 request_id`() {
        val responder = PingResponder(clock = { 5L })
        assertFalse(responder.handle(ping("a", 5L)).duplicate)

        responder.reset()

        assertEquals(0, responder.seenCount())
        assertFalse("重连后服务端可能复用 request_id，reset 必须放行", responder.handle(ping("a", 5L)).duplicate)
    }

    @Test
    fun `记录服务端时间参考`() {
        var now = 1_760_000_000_000L
        val responder = PingResponder(clock = { now })
        assertFalse(responder.hasServerTimeReference)

        responder.noteServerTime(serverTimeMs = now - 500L)

        assertTrue(responder.hasServerTimeReference)
        assertEquals(500L, responder.clockOffsetMs)
    }

    @Test
    fun `LRU 单独使用时的默认容量是 256`() {
        val lru = RequestIdLru()
        repeat(300) { lru.firstSeen("id-$it") }
        assertEquals(256, lru.size())

        lru.clear()
        assertEquals(0, lru.size())
        assertTrue(lru.firstSeen("id-0"))
    }
}
