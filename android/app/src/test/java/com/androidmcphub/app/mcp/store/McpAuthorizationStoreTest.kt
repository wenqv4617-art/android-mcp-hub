package com.androidmcphub.app.mcp.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [McpAuthorizationStore] 的测试：授权绑的是 (服务器, 工具, 指纹)，指纹一变就掉回待确认。 */
class McpAuthorizationStoreTest {

    private val server = "server-1"
    private val tool = "get_weather"
    private val fingerprint = "a".repeat(32)

    @Test
    fun `没授权过是 NotGranted`() {
        val store = McpAuthorizationStore(InMemoryKeyValueStore())
        assertEquals(McpAuthorizationState.NotGranted, store.state(server, tool, fingerprint))
        assertFalse(store.isAuthorized(server, tool, fingerprint))
    }

    @Test
    fun `授权之后是 Granted`() {
        val store = McpAuthorizationStore(InMemoryKeyValueStore())
        store.grant(server, tool, fingerprint)
        assertEquals(McpAuthorizationState.Granted, store.state(server, tool, fingerprint))
        assertTrue(store.isAuthorized(server, tool, fingerprint))
    }

    @Test
    fun `指纹变了变成 Stale 而不是 NotGranted`() {
        val store = McpAuthorizationStore(InMemoryKeyValueStore())
        store.grant(server, tool, fingerprint)
        assertEquals(McpAuthorizationState.Stale, store.state(server, tool, "b".repeat(32)))
        assertFalse("Stale 不算已授权", store.isAuthorized(server, tool, "b".repeat(32)))
    }

    @Test
    fun `按新指纹重新授权后回到 Granted`() {
        val store = McpAuthorizationStore(InMemoryKeyValueStore())
        store.grant(server, tool, fingerprint)
        val newFingerprint = "b".repeat(32)
        store.grant(server, tool, newFingerprint)
        assertEquals(McpAuthorizationState.Granted, store.state(server, tool, newFingerprint))
        assertEquals("重新授权不该留下两条记录", 1, store.list().size)
    }

    @Test
    fun `revoke 之后回到 NotGranted`() {
        val store = McpAuthorizationStore(InMemoryKeyValueStore())
        store.grant(server, tool, fingerprint)
        store.revoke(server, tool)
        assertEquals(McpAuthorizationState.NotGranted, store.state(server, tool, fingerprint))
        assertEquals(emptyList<McpGrant>(), store.list())
    }

    @Test
    fun `revokeServer 清掉该服务器的全部授权但留下别人的`() {
        val store = McpAuthorizationStore(InMemoryKeyValueStore())
        store.grant(server, "get_weather", fingerprint)
        store.grant(server, "send_mail", fingerprint)
        store.grant("server-2", "get_weather", fingerprint)

        store.revokeServer(server)

        assertEquals(McpAuthorizationState.NotGranted, store.state(server, "get_weather", fingerprint))
        assertEquals(McpAuthorizationState.NotGranted, store.state(server, "send_mail", fingerprint))
        assertEquals(McpAuthorizationState.Granted, store.state("server-2", "get_weather", fingerprint))
        assertEquals(1, store.list().size)
    }

    @Test
    fun `不同服务器上的同名工具互不影响`() {
        val store = McpAuthorizationStore(InMemoryKeyValueStore())
        store.grant(server, tool, fingerprint)
        assertEquals(McpAuthorizationState.NotGranted, store.state("server-9", tool, fingerprint))
    }

    @Test
    fun `grant 记录当时的时间`() {
        val store = McpAuthorizationStore(InMemoryKeyValueStore(), clock = { 1700000000000L })
        store.grant(server, tool, fingerprint)
        assertEquals(1700000000000L, store.list().single().grantedAtMs)
    }

    @Test
    fun `授权记录能跨实例读回来`() {
        val keyValue = InMemoryKeyValueStore()
        McpAuthorizationStore(keyValue).grant(server, tool, fingerprint)
        assertEquals(McpAuthorizationState.Granted, McpAuthorizationStore(keyValue).state(server, tool, fingerprint))
    }

    @Test
    fun `落盘用的键是约定的 grants_v1`() {
        val keyValue = InMemoryKeyValueStore()
        McpAuthorizationStore(keyValue).grant(server, tool, fingerprint)
        assertEquals("grants_v1", McpAuthorizationStore.KEY_GRANTS)
        assertTrue(keyValue.contains("grants_v1"))
        assertTrue(keyValue.getString("grants_v1")!!.contains(tool))
    }

    @Test
    fun `坏 JSON 不崩只是当作没有授权`() {
        val keyValue = InMemoryKeyValueStore()
        keyValue.putString(McpAuthorizationStore.KEY_GRANTS, "{坏掉了")
        val store = McpAuthorizationStore(keyValue)
        assertEquals(McpAuthorizationState.NotGranted, store.state(server, tool, fingerprint))
        assertEquals(emptyList<McpGrant>(), store.list())
    }

    @Test
    fun `坏 JSON 之后照样能重新授权`() {
        val keyValue = InMemoryKeyValueStore()
        keyValue.putString(McpAuthorizationStore.KEY_GRANTS, "{坏掉了")
        val store = McpAuthorizationStore(keyValue)
        store.grant(server, tool, fingerprint)
        assertEquals(McpAuthorizationState.Granted, store.state(server, tool, fingerprint))
    }

    @Test
    fun `缺字段的授权记录被跳过`() {
        val keyValue = InMemoryKeyValueStore()
        keyValue.putString(
            McpAuthorizationStore.KEY_GRANTS,
            """{"version":1,"grants":[{"serverId":"a"},{"serverId":"a","toolName":"t","fingerprint":"f","grantedAtMs":9}]}""",
        )
        val all = McpAuthorizationStore(keyValue).list()
        assertEquals(1, all.size)
        assertEquals(9L, all.single().grantedAtMs)
    }

    @Test
    fun `grants 不是数组时不崩`() {
        val keyValue = InMemoryKeyValueStore()
        keyValue.putString(McpAuthorizationStore.KEY_GRANTS, """{"version":1,"grants":"什么都没有"}""")
        assertEquals(emptyList<McpGrant>(), McpAuthorizationStore(keyValue).list())
    }

    @Test
    fun `撤掉最后一条授权后键被清掉`() {
        val keyValue = InMemoryKeyValueStore()
        val store = McpAuthorizationStore(keyValue)
        store.grant(server, tool, fingerprint)
        store.revoke(server, tool)
        assertNull(keyValue.getString(McpAuthorizationStore.KEY_GRANTS))
        assertFalse(keyValue.contains(McpAuthorizationStore.KEY_GRANTS))
    }
}
