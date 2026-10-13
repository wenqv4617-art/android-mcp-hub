package com.androidmcphub.app.mcp.store

import com.androidmcphub.app.mcp.McpHttpHeader
import com.androidmcphub.app.mcp.model.McpServerConfig
import com.androidmcphub.app.mcp.model.McpTransportType
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [McpServerStore] 的测试。
 *
 * 最要紧的两条：**敏感值不落明文键**、**坏数据不把界面搞崩**。
 * 纯 JVM 跑：`SecretBlobStore` 用假的，另有一条用例用真的
 * [AndroidSecretBlobStore] + [PrefsSecretStorage] + [FakeSecretCipher] + [FakeSharedPreferences]
 * 把"从配置到落盘"的整条链路走一遍。
 */
class McpServerStoreTest {

    private val secretValue = "Bearer TOPSECRET-abc123"

    private fun headers(): List<McpHttpHeader> = listOf(
        McpHttpHeader("Authorization", secretValue, secret = true),
        McpHttpHeader("X-Tenant", "team-a"),
    )

    private fun config(
        id: String = "server-1",
        name: String = "天气",
        headers: List<McpHttpHeader> = headers(),
        transport: McpTransportType = McpTransportType.StreamableHttp,
        enabled: Boolean = true,
    ): McpServerConfig = McpServerConfig(
        id = id,
        name = name,
        group = "默认",
        url = "https://mcp.example.com/mcp",
        transport = transport,
        headers = headers,
        enabled = enabled,
        createdAtMs = 1L,
        updatedAtMs = 2L,
    )

    private class Fixture {
        val keyValue = InMemoryKeyValueStore()
        val blob = FakeSecretBlobStore()
        val store = McpServerStore(keyValue, blob)
    }

    // ---- 基本读写 ----

    @Test
    fun `没有配置时返回空列表`() {
        assertEquals(emptyList<McpServerConfig>(), Fixture().store.list())
        assertNull(Fixture().store.find("server-1"))
    }

    @Test
    fun `新增之后能读回来并把敏感值填回去`() {
        val fixture = Fixture()
        fixture.store.upsert(config())

        val loaded = fixture.store.find("server-1")
        assertNotNull(loaded)
        assertEquals("天气", loaded!!.name)
        assertEquals("默认", loaded.group)
        assertEquals("https://mcp.example.com/mcp", loaded.url)
        assertEquals(1L, loaded.createdAtMs)
        assertEquals(2L, loaded.updatedAtMs)
        assertEquals(2, loaded.headers.size)
        val authorization = loaded.headers.first { it.name == "Authorization" }
        assertEquals(secretValue, authorization.value)
        assertTrue(authorization.secret)
        val tenant = loaded.headers.first { it.name == "X-Tenant" }
        assertEquals("team-a", tenant.value)
        assertFalse(tenant.secret)
    }

    @Test
    fun `enabled 与 transport 能往返保持`() {
        val fixture = Fixture()
        fixture.store.upsert(config(enabled = false, transport = McpTransportType.LegacyHttpSse))
        val loaded = fixture.store.find("server-1")!!
        assertFalse(loaded.enabled)
        assertEquals(McpTransportType.LegacyHttpSse, loaded.transport)
    }

    @Test
    fun `同一个 id 再次 upsert 是更新而不是新增`() {
        val fixture = Fixture()
        fixture.store.upsert(config(name = "天气"))
        fixture.store.upsert(config(name = "天气（新）"))

        val all = fixture.store.list()
        assertEquals(1, all.size)
        assertEquals("天气（新）", all.first().name)
    }

    @Test
    fun `多个服务器的敏感值互不串味`() {
        val fixture = Fixture()
        fixture.store.upsert(config(id = "a"))
        fixture.store.upsert(
            config(id = "b", headers = listOf(McpHttpHeader("Authorization", "Bearer B-SECRET", secret = true))),
        )

        assertEquals(secretValue, fixture.store.find("a")!!.headers.first().value)
        assertEquals("Bearer B-SECRET", fixture.store.find("b")!!.headers.first().value)
    }

    @Test
    fun `find 未命中返回 null`() {
        val fixture = Fixture()
        fixture.store.upsert(config())
        assertNull(fixture.store.find("不存在"))
    }

    // ---- 敏感值不落明文 ----

    @Test
    fun `明文键里看不到敏感值`() {
        val fixture = Fixture()
        fixture.store.upsert(config())

        val plaintext = fixture.keyValue.getString(McpServerStore.KEY_SERVERS)
        assertNotNull(plaintext)
        assertFalse("明文键里出现了敏感值：$plaintext", plaintext!!.contains(secretValue))
        assertFalse("明文键里出现了敏感值片段", plaintext.contains("TOPSECRET"))
        assertTrue("普通头应该留在明文里", plaintext.contains("team-a"))
    }

    @Test
    fun `明文键里看不到密文内容`() {
        val fixture = Fixture()
        fixture.store.upsert(config())

        val plaintext = fixture.keyValue.getString(McpServerStore.KEY_SERVERS)!!
        val blobText = fixture.blob.plainText
        assertNotNull("密文键应该被写过", blobText)
        assertFalse("明文键里混进了密文", plaintext.contains(blobText!!))
    }

    @Test
    fun `明文里的敏感头只有名字没有值`() {
        val fixture = Fixture()
        fixture.store.upsert(config())

        val root = JSONObject(fixture.keyValue.getString(McpServerStore.KEY_SERVERS))
        val item = root.getJSONArray("servers").getJSONObject(0).getJSONArray("headers").getJSONObject(0)
        assertEquals("Authorization", item.getString("name"))
        assertTrue(item.getBoolean("secret"))
        assertFalse("敏感头不该带 value 字段", item.has("value"))
    }

    @Test
    fun `密文里用服务器 id 与头名做键`() {
        val fixture = Fixture()
        fixture.store.upsert(config())

        val blob = JSONObject(fixture.blob.plainText!!)
        assertEquals(secretValue, blob.getString("server-1\nAuthorization"))
    }

    @Test
    fun `真实加密链路上 prefs 里看不到明文`() {
        val prefs = FakeSharedPreferences()
        val keyValue = PrefsKeyValueStore(prefs)
        val storage = com.androidmcphub.app.core.PrefsSecretStorage(prefs, McpServerStore.KEY_HEADER_BLOB)
        val blob = AndroidSecretBlobStore(FakeSecretCipher(), storage)
        val store = McpServerStore(keyValue, blob)

        store.upsert(config())

        val dumped = prefs.raw.entries.joinToString(" | ") { "${it.key}=${it.value}" }
        assertFalse("prefs 里出现了敏感值：$dumped", dumped.contains(secretValue))
        assertFalse(dumped.contains("TOPSECRET"))
        val encoded = prefs.getString(McpServerStore.KEY_HEADER_BLOB, null)
        assertNotNull(encoded)
        assertTrue("密文应当是 SecretCodec 的 v1 格式：$encoded", encoded!!.startsWith("v1."))
        assertFalse("密文不该是明文 JSON", encoded.contains(secretValue))
        assertEquals(secretValue, store.find("server-1")!!.headers.first().value)
    }

    // ---- 删除 ----

    @Test
    fun `delete 会同时清掉该服务器的敏感值`() {
        val fixture = Fixture()
        fixture.store.upsert(config(id = "a"))
        fixture.store.upsert(
            config(id = "b", headers = listOf(McpHttpHeader("Authorization", "Bearer B-SECRET", secret = true))),
        )

        fixture.store.delete("a")

        assertEquals(1, fixture.store.list().size)
        val blob = JSONObject(fixture.blob.plainText!!)
        assertFalse(blob.has("a\nAuthorization"))
        assertTrue("别的服务器的敏感值不能跟着被删", blob.has("b\nAuthorization"))
    }

    @Test
    fun `删掉最后一个服务器后密文键被清空`() {
        val fixture = Fixture()
        fixture.store.upsert(config())
        fixture.store.delete("server-1")

        assertEquals(emptyList<McpServerConfig>(), fixture.store.list())
        assertNull(fixture.blob.plainText)
    }

    @Test
    fun `delete 不存在的 id 也不出错`() {
        val fixture = Fixture()
        fixture.store.upsert(config())
        fixture.store.delete("没有这个")
        assertEquals(1, fixture.store.list().size)
    }

    // ---- redacted ----

    @Test
    fun `redacted 把敏感值换成空串而普通头保留`() {
        val fixture = Fixture()
        fixture.store.upsert(config())

        val redacted = fixture.store.redacted().first()
        val authorization = redacted.headers.first { it.name == "Authorization" }
        assertEquals("", authorization.value)
        assertTrue("secret 标记要留着，界面才知道这是个敏感头", authorization.secret)
        assertEquals("team-a", redacted.headers.first { it.name == "X-Tenant" }.value)
        // 原始数据不能被 redacted 破坏
        assertEquals(secretValue, fixture.store.find("server-1")!!.headers.first().value)
    }

    // ---- 坏数据 ----

    @Test
    fun `明文 JSON 坏掉时返回空列表而不是崩`() {
        val fixture = Fixture()
        fixture.keyValue.putString(McpServerStore.KEY_SERVERS, "{这不是 JSON")
        assertEquals(emptyList<McpServerConfig>(), fixture.store.list())
    }

    @Test
    fun `明文 JSON 是数组而不是对象时返回空列表`() {
        val fixture = Fixture()
        fixture.keyValue.putString(McpServerStore.KEY_SERVERS, "[1,2,3]")
        assertEquals(emptyList<McpServerConfig>(), fixture.store.list())
    }

    @Test
    fun `缺少 id 的条目被跳过而不是整份丢弃`() {
        val fixture = Fixture()
        fixture.keyValue.putString(
            McpServerStore.KEY_SERVERS,
            """{"version":1,"servers":[{"url":"https://a.example.com/mcp"},{"id":"ok","name":"好","url":"https://b.example.com/mcp"}]}""",
        )
        val all = fixture.store.list()
        assertEquals(1, all.size)
        assertEquals("ok", all.first().id)
    }

    @Test
    fun `密文读不出来时敏感值退化成空串且不崩`() {
        val keyValue = InMemoryKeyValueStore()
        val store = McpServerStore(keyValue, FakeSecretBlobStore())
        store.upsert(config())
        // 把密文换成一段解不开的内容，模拟 Keystore 被重置 / 文件被改坏。
        val brokenBlob = object : SecretBlobStore {
            override fun read(): String = "{不是 JSON"
            override fun write(plainText: String) = Unit
            override fun clear() = Unit
        }
        val broken = McpServerStore(keyValue, brokenBlob)

        val loaded = broken.find("server-1")
        assertNotNull("配置本身还要能看到", loaded)
        assertEquals("", loaded!!.headers.first { it.name == "Authorization" }.value)
    }

    @Test
    fun `没有注入密文存储时敏感值不会崩只是读不回来`() {
        val keyValue = InMemoryKeyValueStore()
        val store = McpServerStore(keyValue, null)
        store.upsert(config())

        val plaintext = keyValue.getString(McpServerStore.KEY_SERVERS)!!
        assertFalse(plaintext.contains(secretValue))
        assertEquals("", store.find("server-1")!!.headers.first { it.name == "Authorization" }.value)
    }

    @Test
    fun `敏感值传空串时不会往密文里写空条目`() {
        val fixture = Fixture()
        fixture.store.upsert(config(headers = listOf(McpHttpHeader("Authorization", "", secret = true))))
        assertNull(fixture.blob.plainText)

        val blob = JSONObject(fixture.blob.plainText ?: "{}")
        assertFalse(blob.has("server-1\nAuthorization"))
    }

    @Test
    fun `更新成没有敏感头之后密文键被清掉`() {
        val fixture = Fixture()
        fixture.store.upsert(config())
        fixture.store.upsert(config(headers = listOf(McpHttpHeader("X-Tenant", "team-a"))))
        assertNull(fixture.blob.plainText)
        assertEquals(emptyList<McpHttpHeader>(), fixture.store.find("server-1")!!.headers.filter { it.secret })
    }

    // ---- clearAll ----

    @Test
    fun `clearAll 清掉明文与密文`() {
        val fixture = Fixture()
        fixture.store.upsert(config())
        fixture.store.clearAll()

        assertEquals(emptyList<McpServerConfig>(), fixture.store.list())
        assertNull(fixture.keyValue.getString(McpServerStore.KEY_SERVERS))
        assertNull(fixture.blob.plainText)
    }

    // ---- prefs 装配的约定 ----

    @Test
    fun `prefs 文件名与密钥别名是约定的那一套`() {
        assertEquals("mcp_servers", McpServerStore.PREF_FILE)
        assertEquals("android-mcp-hub.mcp-header.v1", McpServerStore.HEADER_ALIAS)
        assertEquals("servers_v1", McpServerStore.KEY_SERVERS)
        assertEquals("mcp_header_blob_v1", McpServerStore.KEY_HEADER_BLOB)
        assertFalse("不能和 hub_settings 共用一份 prefs", McpServerStore.PREF_FILE == "hub_settings")
    }

    // ---- PrefsKeyValueStore ----

    @Test
    fun `PrefsKeyValueStore 读写删查`() {
        val prefs = FakeSharedPreferences()
        val store = PrefsKeyValueStore(prefs)

        assertNull(store.getString("a"))
        assertFalse(store.contains("a"))

        store.putString("a", "1")
        assertEquals("1", store.getString("a"))
        assertTrue(store.contains("a"))

        store.putString("b", "2")
        assertEquals(setOf("a", "b"), store.keys())

        store.remove("a")
        assertNull(store.getString("a"))
        assertEquals(setOf("b"), store.keys())
    }

    @Test
    fun `PrefsKeyValueStore 用 commit 立刻落盘`() {
        val prefs = FakeSharedPreferences()
        val store = PrefsKeyValueStore(prefs)
        store.putString("a", "1")
        assertEquals("立刻可见（commit 语义）", "1", prefs.getString("a", null))
        store.remove("a")
        assertNull(prefs.getString("a", null))
    }
}
