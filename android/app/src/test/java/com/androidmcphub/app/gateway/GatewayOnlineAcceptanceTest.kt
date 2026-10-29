package com.androidmcphub.app.gateway

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID

/**
 * 线上闭环验收：**用 APK 里同一份代码**跑真实的公网网关。
 *
 * 这不是 mock，也不是协议单测：
 *   pair → 添加服务 → 服务端连接上游并发现工具 → 授权只读工具
 *   → 通过 /mcp 真实调用 → 拿到上游真实结果 → 清理。
 *
 * 依赖两个环境变量，缺一就跳过（本地和普通 CI 不需要它们）：
 *   GATEWAY_BASE_URL=https://android-mcp-hub.causticrry.workers.dev
 *   GATEWAY_ANDROID_PAIRING_CODE=<一次性/可轮换的配对码>
 *
 * 凭据只存在于这个进程的内存里，不打印、不写文件。
 */
class GatewayOnlineAcceptanceTest {

    private val baseUrl = System.getenv("GATEWAY_BASE_URL").orEmpty().trim()
    private val pairingCode = System.getenv("GATEWAY_ANDROID_PAIRING_CODE").orEmpty().trim()
    private val upstreamUrl = System.getenv("GATEWAY_SERVICE_URL").orEmpty().trim()
        .ifBlank { "https://docs.mcp.cloudflare.com/mcp" }

    private class MemoryStore(private val deviceId: String, initialBase: String) : GatewayCredentialsStore {
        private var base = initialBase
        private var credentials: GatewayCredentials? = null
        override fun baseUrl(): String = base
        override fun saveBaseUrl(baseUrl: String) { base = baseUrl }
        override fun load(): GatewayCredentials? = credentials
        override fun save(credentials: GatewayCredentials) { this.credentials = credentials }
        override fun clear() { credentials = null }
        override fun deviceId(): String = deviceId
        override fun deviceName(): String = "GitHub Actions Android acceptance"
    }

    @Test
    fun androidClientCompletesTheRealOnlineLoop() = runBlocking {
        assumeTrue(
            "缺少 GATEWAY_BASE_URL / GATEWAY_ANDROID_PAIRING_CODE，跳过线上验收",
            baseUrl.isNotBlank() && pairingCode.length >= 8,
        )

        val serviceId = "android-ci"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = MemoryStore("ci-android-" + UUID.randomUUID().toString().replace("-", "").take(12), baseUrl)
        val state = GatewayState(
            api = OkHttpGatewayApi(),
            credentialsStore = store,
            caller = TransportGatewayCaller(),
            scope = scope,
            io = Dispatchers.IO,
            idFactory = { serviceId },
        )

        try {
            // 1. 配对：拿到受限管理令牌 + 客户端令牌（只留在内存里）。
            state.pair(baseUrl, pairingCode)
            val paired = await(state, "配对") { it.paired && !it.pairing }
            assertNull("配对失败：${paired.error}", paired.error)

            // 2. 添加服务：保存后立即测试、由服务端连接上游并发现工具。
            state.save(
                GatewayServiceDraft(
                    name = "GitHub Actions Android acceptance",
                    description = "CI 验收用；结束后删除",
                    url = upstreamUrl,
                    transport = GatewayTransport.Auto,
                    enabled = true,
                ),
            )
            val saved = await(state, "发现工具") { it.busyServiceId == null && it.services.any { s -> s.id == serviceId } }
            val service = saved.services.first { it.id == serviceId }
            assertTrue("服务未启用", service.enabled)
            assertTrue("服务端没有发现任何工具", service.tools.isNotEmpty())

            // 3. 选择一个只读工具并授权。授权以服务端记录的定义指纹为准。
            val tool = service.tools.firstOrNull { tool ->
                argumentsFor(tool.schemaJson) != null && Regex("search|read|list|get|docs|wiki|query", RegexOption.IGNORE_CASE).containsMatchIn(tool.name)
            } ?: service.tools.first { argumentsFor(it.schemaJson) != null }
            val arguments = argumentsFor(tool.schemaJson)!!

            state.grant(serviceId, tool, GatewayGrantMode.Read)
            val granted = await(state, "授权工具") {
                it.busyServiceId == null && it.services.firstOrNull { s -> s.id == serviceId }
                    ?.tools?.firstOrNull { t -> t.name == tool.name }?.grant == GatewayGrantMode.Read
            }
            assertNull("授权失败：${granted.error}", granted.error)

            // 4. 通过 /mcp 真实调用，拿到上游的真实结果。
            state.call(serviceId, tool, arguments.toString())
            val called = await(state, "真实调用") { it.busyServiceId == null && it.lastCall != null }
            val call = called.lastCall!!
            assertEquals("调用工具不符", tool.name, call.toolName)
            assertTrue("真实调用返回错误：${call.text.take(200)}", !call.isError)
            assertTrue("真实调用没有返回内容", call.text.isNotBlank())
            println("[android-online] 真实调用成功：${tool.name} → ${call.text.replace(Regex("\\s+"), " ").take(120)}")
        } finally {
            // 5. 清理：先删服务，再解除配对（两步都走服务端，失败不掩盖上面的断言）。
            runCatching {
                if (state.state.value.paired) {
                    state.delete(serviceId)
                    await(state, "删除服务", timeoutMs = 20_000) { it.busyServiceId == null && it.services.none { s -> s.id == serviceId } }
                    state.unpair()
                    await(state, "解除配对", timeoutMs = 20_000) { !it.paired }
                }
            }
            scope.cancel()
        }
    }

    private suspend fun await(
        state: GatewayState,
        step: String,
        timeoutMs: Long = 60_000,
        predicate: (GatewayUiState) -> Boolean,
    ): GatewayUiState {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val current = state.state.value
            if (predicate(current)) return current
            val failed = current.error != null && !current.pairing && current.busyServiceId == null && !current.loading
            if (failed) throw AssertionError("$step 失败：${current.error}")
            delay(100)
        }
        val last = state.state.value
        throw AssertionError("$step 超时（${timeoutMs}ms）：paired=${last.paired} busy=${last.busyServiceId} error=${last.error}")
    }

    /**
     * 按 inputSchema 生成一个最小参数集。只支持常见标量/数组/对象；
     * 遇到无法安全构造的必填字段就返回 null，让调用方换一个工具。
     */
    private fun argumentsFor(schemaJson: String?): JSONObject? {
        if (schemaJson.isNullOrBlank()) return JSONObject()
        val schema = runCatching { JSONObject(schemaJson) }.getOrNull() ?: return null
        val properties = schema.optJSONObject("properties") ?: JSONObject()
        val required = schema.optJSONArray("required") ?: JSONArray()
        val args = JSONObject()
        for (index in 0 until required.length()) {
            val key = required.optString(index).ifBlank { return null }
            val property = properties.optJSONObject(key) ?: JSONObject()
            val type = when (val raw = property.opt("type")) {
                is String -> raw
                is JSONArray -> raw.optString(0)
                else -> ""
            }
            val value: Any = when (type) {
                "string" -> if (Regex("repo", RegexOption.IGNORE_CASE).containsMatchIn(key)) "microsoft/vscode" else "Durable Objects"
                "integer", "number" -> 1
                "boolean" -> true
                "array" -> if ((property.optJSONArray("items")?.length() ?: 0) > 0) return null else JSONArray()
                "object" -> JSONObject()
                else -> return null
            }
            args.put(key, value)
        }
        if (required.length() == 0) {
            val preferred = listOf("query", "q", "search", "question", "prompt").firstOrNull { properties.has(it) }
            if (preferred != null) args.put(preferred, "Durable Objects")
        }
        return args
    }
}
