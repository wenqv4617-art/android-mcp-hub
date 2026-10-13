package com.androidmcphub.app.mcp.store

import android.content.Context
import com.androidmcphub.app.core.AndroidSecretCipher
import com.androidmcphub.app.core.PrefsSecretStorage
import com.androidmcphub.app.mcp.McpHttpHeader
import com.androidmcphub.app.mcp.model.McpServerConfig
import com.androidmcphub.app.mcp.model.McpTransportType
import org.json.JSONArray
import org.json.JSONObject

/**
 * MCP 服务器配置的持久化。
 *
 * 存储被刻意**劈成两半**：
 *  - 非敏感配置（名字、地址、分组、非敏感请求头）是明文 JSON，键 [KEY_SERVERS]，
 *    方便直接看、直接排错、坏了也不至于全丢；
 *  - 敏感请求头的**值**（Bearer 令牌之类）单独放在一个加密 blob 里，明文键里只留
 *    `{name, enabled, secret:true}`。这样"我把配置导出给你看看"永远不会顺手漏令牌。
 *
 * 另一半是"不崩"：这一层读的是磁盘上可能被改坏、被旧版本写过、被解密失败的 JSON。
 * 任何一条读不出来都只能退化成"这条没读到"，绝不能让设置页打不开。
 */
class McpServerStore(
    private val store: KeyValueStore,
    private val secretBlob: SecretBlobStore? = null,
) {

    /** 列出所有服务器，敏感请求头的值会被填回来。 */
    fun list(): List<McpServerConfig> {
        val configs = loadServerObjects().mapNotNull { parseServer(it) }
        if (configs.isEmpty()) return emptyList()
        val secrets = readSecretMap()
        return configs.map { attachSecrets(it, secrets) }
    }

    fun find(id: String): McpServerConfig? = list().firstOrNull { it.id == id }

    /** 新增或按 id 覆盖。 */
    fun upsert(config: McpServerConfig) {
        val servers = loadServerObjects().toMutableList()
        val index = servers.indexOfFirst { it.optString("id", "") == config.id }
        val encoded = encodeServer(config)
        if (index >= 0) servers[index] = encoded else servers += encoded
        writeServerObjects(servers)
        updateSecrets(config)
    }

    /** 删除配置，**同时**清掉这台服务器在密文 blob 里的所有请求头。 */
    fun delete(id: String) {
        val servers = loadServerObjects().filterNot { it.optString("id", "") == id }
        writeServerObjects(servers)
        val secrets = readSecretMap()
        val prefix = secretKey(id, "")
        val remaining = secrets.filterKeys { !it.startsWith(prefix) }
        if (remaining.size != secrets.size) writeSecretMap(remaining)
    }

    /** 清空全部配置与全部敏感值。 */
    fun clearAll() {
        store.remove(KEY_SERVERS)
        secretBlob?.clear()
    }

    /** 给界面用的形态：敏感值一律替换成空串，其余照旧。 */
    fun redacted(): List<McpServerConfig> = list().map { config ->
        config.copy(
            headers = config.headers.map { header ->
                if (header.secret) header.copy(value = "") else header
            },
        )
    }

    // ---- 明文配置 ----

    private fun loadServerObjects(): List<JSONObject> {
        val text = store.getString(KEY_SERVERS) ?: return emptyList()
        val root = try {
            JSONObject(text)
        } catch (_: Exception) {
            return emptyList()
        }
        val array = root.optJSONArray("servers") ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
    }

    private fun writeServerObjects(servers: List<JSONObject>) {
        val root = JSONObject()
        root.put("version", CURRENT_VERSION)
        root.put("servers", JSONArray(servers))
        store.putString(KEY_SERVERS, root.toString())
    }

    private fun parseServer(obj: JSONObject): McpServerConfig? {
        val id = obj.optString("id", "")
        if (id.isEmpty()) return null
        val url = obj.optString("url", "")
        val transportName = obj.optString("transport", "")
        val transport = McpTransportType.entries.firstOrNull { it.name == transportName }
            ?: McpTransportType.StreamableHttp
        val headers = mutableListOf<McpHttpHeader>()
        val array = obj.optJSONArray("headers")
        if (array != null) {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val name = item.optString("name", "")
                if (name.isEmpty()) continue
                val secret = item.optBoolean("secret", false)
                headers += McpHttpHeader(
                    name = name,
                    // 敏感值永远不从明文里读：即使有人手工把值塞进明文键，这里也当它不存在，
                    // 免得"明文里那份"被当成真值继续用下去。
                    value = if (secret) "" else item.optString("value", ""),
                    enabled = item.optBoolean("enabled", true),
                    secret = secret,
                )
            }
        }
        return McpServerConfig(
            id = id,
            name = obj.optString("name", ""),
            group = obj.optString("group", ""),
            url = url,
            transport = transport,
            headers = headers,
            enabled = obj.optBoolean("enabled", true),
            createdAtMs = obj.optLong("createdAtMs", 0L),
            updatedAtMs = obj.optLong("updatedAtMs", 0L),
        )
    }

    private fun encodeServer(config: McpServerConfig): JSONObject {
        val obj = JSONObject()
        obj.put("id", config.id)
        obj.put("name", config.name)
        obj.put("group", config.group)
        obj.put("url", config.url)
        obj.put("transport", config.transport.name)
        obj.put("enabled", config.enabled)
        obj.put("createdAtMs", config.createdAtMs)
        obj.put("updatedAtMs", config.updatedAtMs)
        val headers = JSONArray()
        for (header in config.headers) {
            val item = JSONObject()
            item.put("name", header.name)
            item.put("enabled", header.enabled)
            item.put("secret", header.secret)
            if (!header.secret) item.put("value", header.value)
            headers.put(item)
        }
        obj.put("headers", headers)
        return obj
    }

    // ---- 敏感值 ----

    private fun readSecretMap(): Map<String, String> {
        val text = try {
            secretBlob?.read()
        } catch (_: Exception) {
            null
        } ?: return emptyMap()
        val obj = try {
            JSONObject(text)
        } catch (_: Exception) {
            return emptyMap()
        }
        val map = mutableMapOf<String, String>()
        val names = obj.keys()
        while (names.hasNext()) {
            val key = names.next()
            val value = obj.optString(key, "")
            if (value.isNotEmpty()) map[key] = value
        }
        return map
    }

    private fun writeSecretMap(map: Map<String, String>) {
        val blob = secretBlob ?: return
        if (map.isEmpty()) {
            blob.clear()
            return
        }
        val obj = JSONObject()
        for ((key, value) in map) obj.put(key, value)
        blob.write(obj.toString())
    }

    private fun updateSecrets(config: McpServerConfig) {
        if (secretBlob == null) return
        val secrets = readSecretMap().toMutableMap()
        val prefix = secretKey(config.id, "")
        secrets.keys.removeAll { it.startsWith(prefix) }
        for (header in config.headers) {
            if (!header.secret || header.value.isEmpty()) continue
            secrets[secretKey(config.id, header.name)] = header.value
        }
        writeSecretMap(secrets)
    }

    private fun attachSecrets(config: McpServerConfig, secrets: Map<String, String>): McpServerConfig {
        if (config.headers.none { it.secret }) return config
        return config.copy(
            headers = config.headers.map { header ->
                if (!header.secret) {
                    header
                } else {
                    header.copy(value = secrets[secretKey(config.id, header.name)].orEmpty())
                }
            },
        )
    }

    /** 密文里的键：`<serverId>\n<headerName>`。用换行分隔是因为两者都不允许含换行。 */
    private fun secretKey(serverId: String, headerName: String): String = "$serverId\n$headerName"

    companion object {

        /** 明文配置的键。 */
        const val KEY_SERVERS = "servers_v1"

        /** 加密 blob 所在的 prefs 键。 */
        const val KEY_HEADER_BLOB = "mcp_header_blob_v1"

        /**
         * **独立的** SharedPreferences 文件名。
         * 绝不能用 `core/HubPrefs` 的 `hub_settings`：那份文件一旦被"清缓存/重置设置"
         * 类操作整个清掉，用户配好的服务器就一起没了，而这里的数据不该跟着别的东西陪葬。
         */
        const val PREF_FILE = "mcp_servers"

        /** 加密密钥别名，与设备令牌的别名分开：各自轮换、各自失效，互不牵连。 */
        const val HEADER_ALIAS = "android-mcp-hub.mcp-header.v1"

        const val CURRENT_VERSION = 1

        /** Android 侧的真实装配：独立 prefs + Keystore 密钥 + 加密 blob。 */
        fun android(context: Context): McpServerStore {
            val prefs = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            val cipher = AndroidSecretCipher(alias = HEADER_ALIAS)
            val storage = PrefsSecretStorage(prefs, key = KEY_HEADER_BLOB)
            return McpServerStore(PrefsKeyValueStore(prefs), AndroidSecretBlobStore(cipher, storage))
        }
    }
}
