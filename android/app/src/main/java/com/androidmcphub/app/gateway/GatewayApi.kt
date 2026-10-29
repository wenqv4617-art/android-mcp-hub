package com.androidmcphub.app.gateway

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** 网关 API 的窄接口：JVM 单测用假实现，真机上用 [OkHttpGatewayApi]。 */
interface GatewayApi {

    fun pair(baseUrl: String, code: String, deviceId: String, deviceName: String): GatewayPairResult

    /** 受限管理面的统一入口；[action] 必须带 `action` 字段。 */
    fun admin(baseUrl: String, managementToken: String, action: JSONObject): JSONObject

    fun list(baseUrl: String, managementToken: String): GatewaySnapshot
}

class GatewayApiException(val code: String?, val status: Int) :
    Exception(gatewayErrorText(code, status))

/**
 * OkHttp 实现。
 *
 * 三条安全约束：
 *  * 管理令牌只出现在 `Authorization` 头里，永不拼进 URL、查询串或日志；
 *  * 错误只保留服务端的固定 `error` 码，不回显响应体（响应体可能含上游文本）；
 *  * 响应体有上限，避免一个被攻破/异常的上游把手机内存打满。
 */
class OkHttpGatewayApi(
    private val client: OkHttpClient = defaultClient(),
) : GatewayApi {

    override fun pair(baseUrl: String, code: String, deviceId: String, deviceName: String): GatewayPairResult {
        val body = JSONObject().apply {
            put("code", code)
            put("deviceId", deviceId)
            put("deviceName", deviceName.take(64))
        }
        val response = post(GatewayUrls.pair(baseUrl), null, body)
        return GatewayPairResult(
            managementToken = response.getString("managementToken"),
            clientToken = response.getString("clientToken"),
            ownerId = response.optString("ownerId"),
            clientId = response.optString("clientId"),
        )
    }

    override fun admin(baseUrl: String, managementToken: String, action: JSONObject): JSONObject =
        post(GatewayUrls.admin(baseUrl), managementToken, action)

    override fun list(baseUrl: String, managementToken: String): GatewaySnapshot =
        parseSnapshot(admin(baseUrl, managementToken, JSONObject().put("action", "list")))

    private fun post(url: String, bearer: String?, body: JSONObject): JSONObject {
        val request = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(JSON))
            .header("Accept", "application/json")
            .apply { if (bearer != null) header("Authorization", "Bearer $bearer") }
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (text.toByteArray(Charsets.UTF_8).size > MAX_RESPONSE_BYTES) {
                throw GatewayApiException("response_too_large", response.code)
            }
            val json = runCatching { JSONObject(text) }.getOrNull()
            if (!response.isSuccessful) {
                throw GatewayApiException(json?.optString("error")?.takeIf { it.isNotBlank() }, response.code)
            }
            return json ?: throw GatewayApiException("invalid_json", response.code)
        }
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }
}

/** 把管理 API 的 JSON 还原成界面模型。解析集中在这里，避免 UI 里散落 `optString`。 */
internal fun parseSnapshot(root: JSONObject): GatewaySnapshot {
    val grants: Map<String, GatewayGrantMode> = root.optJSONArray("clients")
        ?.optJSONObject(0)
        ?.optJSONArray("grants")
        ?.let { array ->
            buildMap {
                for (index in 0 until array.length()) {
                    val grant = array.optJSONObject(index) ?: continue
                    val tool = grant.optString("tool")
                    if (tool.isBlank()) continue
                    put(tool, if (grant.optString("mode") == "confirm") GatewayGrantMode.Confirm else GatewayGrantMode.Read)
                }
            }
        }
        .orEmpty()

    val servicesJson = root.optJSONArray("services") ?: JSONArray()
    val services = (0 until servicesJson.length()).mapNotNull { index ->
        servicesJson.optJSONObject(index)?.let { parseService(it, grants) }
    }.sortedWith(compareBy({ it.name.lowercase() }, { it.id }))

    val identity = root.optJSONObject("android")
    val client = identity?.let {
        GatewayClientInfo(
            ownerId = it.optString("ownerId"),
            clientId = it.optString("clientId"),
            deviceId = it.optString("deviceId"),
            deviceName = it.optString("deviceName"),
            lastPairedAtMs = it.optLong("lastPairedAt").takeIf { value -> value > 0 },
        )
    }
    return GatewaySnapshot(services = services, client = client)
}

private fun parseService(json: JSONObject, grants: Map<String, GatewayGrantMode>): GatewayService? {
    val id = json.optString("id")
    if (id.isBlank()) return null
    val toolMap = buildMap {
        val array = json.optJSONArray("toolMap") ?: JSONArray()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            put(
                item.optString("original"),
                item.optString("name") to item.optString("fingerprint"),
            )
        }
    }
    val tools = buildList {
        val array = json.optJSONArray("tools") ?: JSONArray()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val name = item.optString("name")
            val mapped = toolMap[name] ?: continue
            add(
                GatewayTool(
                    name = name,
                    gatewayName = mapped.first,
                    description = item.optString("description").take(2_000),
                    schemaJson = item.optJSONObject("inputSchema")?.toString()?.take(8_000),
                    fingerprint = mapped.second,
                    grant = grants[name],
                ),
            )
        }
    }
    return GatewayService(
        id = id,
        name = json.optString("name", id),
        description = json.optString("description"),
        url = json.optString("url"),
        transport = GatewayTransport.fromWire(json.optString("transport")),
        enabled = json.optBoolean("enabled", false),
        secretRef = json.optString("secretRef").takeIf { it.isNotBlank() },
        timeoutMs = json.optInt("timeoutMs", 10_000),
        revision = json.optString("revision"),
        tools = tools,
        lastError = json.optString("lastError").takeIf { it.isNotBlank() },
        lastTestAtMs = json.optLong("lastTestAt").takeIf { it > 0 },
    )
}
