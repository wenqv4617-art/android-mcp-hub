package com.androidmcphub.app.gateway

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 管理 API → 界面模型的解析。解析只回显服务端事实，不推断权限。 */
class GatewaySnapshotParseTest {

    @Test
    fun `服务按名称排序且工具映射与授权正确合并`() {
        val root = JSONObject(
            """
            {
              "clients": [
                {
                  "grants": [
                    {"tool": "search", "mode": "read"},
                    {"tool": "write_file", "mode": "confirm"}
                  ]
                }
              ],
              "services": [
                {
                  "id": "zeta",
                  "name": "Zeta",
                  "url": "https://z.example/mcp",
                  "transport": "sse",
                  "enabled": true,
                  "revision": "abcdef123456",
                  "toolMap": [
                    {"original": "search", "name": "ext_zeta_search", "fingerprint": "fp1"}
                  ],
                  "tools": [
                    {"name": "search", "description": "search docs", "inputSchema": {"type": "object"}}
                  ]
                },
                {
                  "id": "alpha",
                  "name": "alpha",
                  "url": "https://a.example/mcp",
                  "transport": "streamable-http",
                  "enabled": false,
                  "lastError": "upstream_failure",
                  "lastTestAt": 1700000000000,
                  "toolMap": [
                    {"original": "write_file", "name": "ext_alpha_write", "fingerprint": "fp2"},
                    {"original": "orphan", "name": "ext_alpha_orphan", "fingerprint": "fp3"}
                  ],
                  "tools": [
                    {"name": "write_file", "description": "写文件"},
                    {"name": "not_mapped", "description": "没有映射，应被忽略"}
                  ]
                },
                {"name": "没有 id，应被忽略"}
              ],
              "android": {
                "ownerId": "android-abc",
                "clientId": "client:android-abc",
                "deviceId": "android-abc",
                "deviceName": "Pixel 8",
                "lastPairedAt": 1700000000000
              }
            }
            """.trimIndent(),
        )

        val snapshot = parseSnapshot(root)

        assertEquals(listOf("alpha", "zeta"), snapshot.services.map { it.id })
        val alpha = snapshot.services.first()
        assertEquals(GatewayTransport.StreamableHttp, alpha.transport)
        assertEquals(false, alpha.enabled)
        assertEquals("upstream_failure", alpha.lastError)
        assertEquals(1700000000000L, alpha.lastTestAtMs)
        assertEquals(1, alpha.tools.size)
        val write = alpha.tools.single()
        assertEquals("write_file", write.name)
        assertEquals("ext_alpha_write", write.gatewayName)
        assertEquals("fp2", write.fingerprint)
        assertEquals(GatewayGrantMode.Confirm, write.grant)

        val zeta = snapshot.services.last()
        assertEquals(GatewayTransport.Sse, zeta.transport)
        assertEquals(GatewayGrantMode.Read, zeta.tools.single().grant)
        assertEquals("ext_zeta_search", zeta.tools.single().gatewayName)

        assertEquals("android-abc", snapshot.client?.ownerId)
        assertEquals("Pixel 8", snapshot.client?.deviceName)
    }

    @Test
    fun `令牌只解析元数据，按签发时间倒序，没有编号的条目被忽略`() {
        val root = JSONObject(
            """
            {
              "clients": [
                {
                  "tokens": [
                    {
                      "id": "aaaaaaaa",
                      "label": "Claude",
                      "scopes": ["read"],
                      "source": "issued",
                      "createdAt": 1700000000000,
                      "expiresAt": null,
                      "lastUsedAt": null,
                      "revokedAt": null
                    },
                    {
                      "id": "bbbbbbbb",
                      "label": "",
                      "scopes": ["read", "write"],
                      "source": "pair",
                      "createdAt": 1700000001000,
                      "expiresAt": 1800000000000,
                      "lastUsedAt": 1700000002000,
                      "revokedAt": null
                    },
                    {"label": "没有编号，应被忽略"}
                  ]
                }
              ],
              "services": []
            }
            """.trimIndent(),
        )

        val tokens = parseSnapshot(root).tokens

        assertEquals(listOf("bbbbbbbb", "aaaaaaaa"), tokens.map { it.id })
        val pair = tokens.first()
        assertEquals("访问令牌", pair.label)
        assertEquals("读写", pair.scopeLabel)
        assertEquals("设备配对", pair.sourceLabel)
        assertEquals(1800000000000L, pair.expiresAtMs)
        assertEquals(1700000002000L, pair.lastUsedAtMs)
        assertTrue(pair.isActive(1700000003000))

        val issued = tokens.last()
        assertEquals("只读", issued.scopeLabel)
        assertEquals("手动签发", issued.sourceLabel)
        assertNull(issued.expiresAtMs)
        assertNull(issued.lastUsedAtMs)
        assertEquals(1700000000000L, issued.createdAtMs)
    }

    @Test
    fun `没有 android 身份与 grants 时也能解析`() {
        val snapshot = parseSnapshot(JSONObject("""{"services":[]}"""))
        assertTrue(snapshot.services.isEmpty())
        assertNull(snapshot.client)
    }

    @Test
    fun `超长 schema 与描述被截断，避免界面被上游打满`() {
        val big = "x".repeat(20_000)
        val root = JSONObject().apply {
            put(
                "services",
                org.json.JSONArray().put(
                    JSONObject().apply {
                        put("id", "s1")
                        put("name", "S1")
                        put(
                            "toolMap",
                            org.json.JSONArray().put(
                                JSONObject().apply {
                                    put("original", "t")
                                    put("name", "ext_s1_t")
                                    put("fingerprint", "fp")
                                },
                            ),
                        )
                        put(
                            "tools",
                            org.json.JSONArray().put(
                                JSONObject().apply {
                                    put("name", "t")
                                    put("description", big)
                                    put(
                                        "inputSchema",
                                        JSONObject().put("description", big),
                                    )
                                },
                            ),
                        )
                    },
                ),
            )
        }

        val tool = parseSnapshot(root).services.single().tools.single()
        assertEquals(2_000, tool.description.length)
        assertTrue(tool.schemaJson!!.length <= 8_000)
    }
}
