package com.androidmcphub.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [HubConfig] 的键值往返与兜底行为。 */
class HubConfigTest {

    @Test
    fun `默认值与契约一致`() {
        val d = HubConfig()
        assertEquals("", d.serverUrl)
        assertEquals("test-phone-1", d.deviceId)
        assertEquals(10_000L, d.pingTimeoutMs)
        assertEquals(30_000L, d.heartbeatIntervalMs)
        assertEquals(1_000L, d.reconnectInitialDelayMs)
        assertEquals(60_000L, d.reconnectMaxDelayMs)
        assertEquals(0.2, d.reconnectJitterRatio, 0.0000001)
        assertEquals(16, d.maxPendingRequests)
        assertEquals(65_536, d.maxFrameBytes)
    }

    @Test
    fun `toMap 与 fromMap 往返一致`() {
        val cfg = HubConfig(
            serverUrl = "wss://example.workers.dev/phone/ws",
            deviceId = "my-phone",
            pingTimeoutMs = 5_000L,
            heartbeatIntervalMs = 15_000L,
            reconnectInitialDelayMs = 500L,
            reconnectMaxDelayMs = 20_000L,
            reconnectJitterRatio = 0.35,
            maxPendingRequests = 8,
            maxFrameBytes = 32_768,
        )

        val restored = HubConfig.fromMap(cfg.toMap())

        assertEquals(cfg, restored)
    }

    @Test
    fun `fromMap 忽略不认识的键（例如令牌）`() {
        val map = HubConfig().toMap().toMutableMap()
        map[HubPrefs.LEGACY_KEY_TOKEN] = "test-token-not-a-secret"

        val restored = HubConfig.fromMap(map)

        assertEquals(HubConfig(), restored)
        // 令牌不该出现在配置的序列化结果里
        assertFalse(restored.toMap().containsKey(HubPrefs.LEGACY_KEY_TOKEN))
        assertFalse(restored.toString().contains("test-token-not-a-secret"))
    }

    @Test
    fun `fromMap 缺失或非法时回落默认值`() {
        val restored = HubConfig.fromMap(
            mapOf(
                HubConfig.KEY_SERVER_URL to null,
                HubConfig.KEY_HEARTBEAT_INTERVAL_MS to "不是数字",
                HubConfig.KEY_MAX_FRAME_BYTES to "",
            ),
        )

        assertEquals(HubConfig(), restored)
    }

    @Test
    fun `校验拦下明显非法的配置`() {
        assertNotNull(HubConfig(serverUrl = "").validate())
        assertNotNull(HubConfig(serverUrl = "example.com/phone/ws").validate())
        assertNotNull(HubConfig(serverUrl = "wss://a/ws", deviceId = " ").validate())
        assertNotNull(HubConfig(serverUrl = "wss://a/ws", heartbeatIntervalMs = 0L).validate())
        assertNotNull(
            HubConfig(
                serverUrl = "wss://a/ws",
                reconnectInitialDelayMs = 10_000L,
                reconnectMaxDelayMs = 1_000L,
            ).validate(),
        )
        assertNotNull(HubConfig(serverUrl = "wss://a/ws", maxFrameBytes = 10).validate())
        assertNull(HubConfig(serverUrl = "wss://a/ws").validate())
    }

    @Test
    fun `表单草稿解析失败时回落默认值`() {
        val draft = HubDraft(
            serverUrl = "  wss://example.test/phone/ws  ",
            deviceId = " test-phone-1 ",
            token = "test-token-not-a-secret",
            pingTimeoutMs = "abc",
            heartbeatIntervalMs = "15000",
            reconnectInitialDelayMs = "",
            reconnectMaxDelayMs = "20000",
            reconnectJitterRatio = "0.0",
            maxPendingRequests = "-1",
            maxFrameBytes = "32768",
        )

        val cfg = draft.toConfig()

        assertEquals("wss://example.test/phone/ws", cfg.serverUrl)
        assertEquals("test-phone-1", cfg.deviceId)
        assertEquals(HubConfig().pingTimeoutMs, cfg.pingTimeoutMs)
        assertEquals(15_000L, cfg.heartbeatIntervalMs)
        assertEquals(HubConfig().reconnectInitialDelayMs, cfg.reconnectInitialDelayMs)
        assertEquals(20_000L, cfg.reconnectMaxDelayMs)
        assertEquals(0.0, cfg.reconnectJitterRatio, 0.0000001)
        assertEquals(-1, cfg.maxPendingRequests)
        assertEquals(32_768, cfg.maxFrameBytes)
    }

    @Test
    fun `草稿与配置可以互相还原`() {
        val cfg = HubConfig(serverUrl = "wss://example.test/phone/ws", deviceId = "d-1")
        val draft = HubDraft.from(cfg, "test-token-not-a-secret")

        assertEquals(cfg, draft.toConfig())
        assertTrue(draft.token.isNotEmpty())
    }
}
