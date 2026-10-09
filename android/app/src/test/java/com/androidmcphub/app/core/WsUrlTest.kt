package com.androidmcphub.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 地址规范化：ws/wss → http/https，且按协议约定丢掉 query。 */
class WsUrlTest {

    @Test
    fun `wss 映射为 https`() {
        assertEquals(
            "https://example.workers.dev/phone/ws",
            normalizeWebSocketUrl("wss://example.workers.dev/phone/ws"),
        )
    }

    @Test
    fun `ws 映射为 http`() {
        assertEquals(
            "http://127.0.0.1:8787/phone/ws",
            normalizeWebSocketUrl("ws://127.0.0.1:8787/phone/ws"),
        )
    }

    @Test
    fun `https 与 http 原样保留（便于本地联调）`() {
        assertEquals("https://a.test/phone/ws", normalizeWebSocketUrl("https://a.test/phone/ws"))
        assertEquals("http://a.test/phone/ws", normalizeWebSocketUrl("http://a.test/phone/ws"))
    }

    @Test
    fun `去掉 query 与 fragment`() {
        assertEquals(
            "https://a.test/phone/ws",
            normalizeWebSocketUrl("wss://a.test/phone/ws?token=x&y=1#frag"),
        )
    }

    @Test
    fun `带端口的地址保留端口`() {
        assertEquals("https://a.test:8443/phone/ws", normalizeWebSocketUrl("wss://a.test:8443/phone/ws"))
    }

    @Test
    fun `没有路径时只保留主机`() {
        assertEquals("https://a.test", normalizeWebSocketUrl("wss://a.test"))
    }

    @Test
    fun `非法输入返回 null`() {
        assertNull(normalizeWebSocketUrl(""))
        assertNull(normalizeWebSocketUrl("   "))
        assertNull(normalizeWebSocketUrl("example.com/phone/ws"))
        assertNull(normalizeWebSocketUrl("ftp://a.test/phone/ws"))
        assertNull(normalizeWebSocketUrl("wss://"))
        assertNull(normalizeWebSocketUrl("wss://a.test /phone/ws"))
    }

    @Test
    fun `取主机名用于界面与通知`() {
        assertEquals("example.workers.dev", hostOf("https://example.workers.dev/phone/ws"))
        assertEquals("127.0.0.1:8787", hostOf("http://127.0.0.1:8787/phone/ws"))
        assertEquals("example.workers.dev", hostOf("https://example.workers.dev/phone/ws?a=1"))
    }
}
