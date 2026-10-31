package com.androidmcphub.app.gateway

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/**
 * 「服务」页的云端网关模式状态。
 *
 * 纯 Kotlin（无 Android 依赖），因此配对、权限、错误映射都能在 JVM 单测里跑。
 * 令牌**不进入** [GatewayUiState]：状态只说明"有没有配对"，值留在
 * [GatewayCredentialsStore] 的加密存储与这个类的私有字段里。
 */
data class GatewayUiState(
    val baseUrl: String = "",
    val paired: Boolean = false,
    val pairing: Boolean = false,
    val loading: Boolean = false,
    val services: List<GatewayService> = emptyList(),
    val client: GatewayClientInfo? = null,
    val tokens: List<GatewayAccessToken> = emptyList(),
    /**
     * 刚签发出来的令牌明文。只在内存里存在到用户点击"我已保存"为止 ——
     * 不写偏好、不进日志、不进加密存储（存储里只有配对令牌）。
     */
    val issuedToken: String? = null,
    val issuingToken: Boolean = false,
    val busyTokenId: String? = null,
    /** 最近一次成功读取管理面清单的时间；只在界面上用于"数据有多新"。 */
    val lastLoadedAtMs: Long? = null,
    val notice: String? = null,
    val error: String? = null,
    val busyServiceId: String? = null,
    val lastCall: GatewayToolCallUi? = null,
)

data class GatewayToolCallUi(
    val serviceId: String,
    val toolName: String,
    val gatewayToolName: String,
    val isError: Boolean,
    val text: String,
    val atMs: Long,
)

class GatewayState(
    private val api: GatewayApi,
    private val credentialsStore: GatewayCredentialsStore,
    private val caller: GatewayCaller,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idFactory: () -> String = { "mcp-" + java.util.UUID.randomUUID().toString().replace("-", "").take(10) },
    fallbackBaseUrl: String = "",
) {

    private var credentials: GatewayCredentials? = credentialsStore.load()

    private val _state = MutableStateFlow(
        GatewayUiState(
            baseUrl = credentialsStore.baseUrl().ifBlank { fallbackBaseUrl },
            paired = credentials != null,
        ),
    )
    val state: StateFlow<GatewayUiState> = _state.asStateFlow()

    fun clearNotice() {
        _state.value = _state.value.copy(notice = null)
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    fun clearCall() {
        _state.value = _state.value.copy(lastCall = null)
    }

    // ---- 配对 --------------------------------------------------------------

    /**
     * 用一次性/可轮换的配对口令换回受限令牌。
     *
     * 换回的两枚令牌都只能管理"这台设备自己的服务"；全局管理员令牌从不经过这里，
     * 也不存在于 APK 中。
     */
    fun pair(baseUrlInput: String, code: String) {
        if (_state.value.pairing) return
        val normalized = GatewayUrls.normalizeBase(baseUrlInput)
        val base = normalized.getOrNull()
        if (base == null) {
            val reason = (normalized.exceptionOrNull() as? GatewayAddressException)?.userText
                ?: "网关地址不合法"
            _state.value = _state.value.copy(error = reason)
            return
        }
        if (code.trim().length < 8) {
            _state.value = _state.value.copy(error = "请填写配对码（至少 8 个字符）")
            return
        }
        _state.value = _state.value.copy(pairing = true, error = null, notice = null)
        scope.launch(io) {
            try {
                val result = api.pair(
                    baseUrl = base,
                    code = code.trim(),
                    deviceId = credentialsStore.deviceId(),
                    deviceName = credentialsStore.deviceName(),
                )
                val saved = GatewayCredentials(result.managementToken, result.clientToken)
                credentialsStore.saveBaseUrl(base)
                credentialsStore.save(saved)
                credentials = saved
                _state.value = _state.value.copy(
                    baseUrl = base,
                    paired = true,
                    pairing = false,
                    notice = "已连接到云端网关",
                    services = emptyList(),
                )
                loadSnapshot()
            } catch (error: Exception) {
                _state.value = _state.value.copy(pairing = false, error = describe(error))
            }
        }
    }

    /** 先在服务端撤销这两枚令牌，再清本机。服务端失败也照样清本机（否则用户被困住）。 */
    fun unpair() {
        val current = credentials
        val base = _state.value.baseUrl
        _state.value = _state.value.copy(error = null, notice = null)
        scope.launch(io) {
            if (current != null && base.isNotBlank()) {
                runCatching { api.admin(base, current.managementToken, JSONObject().put("action", "unpair")) }
            }
            credentialsStore.clear()
            credentials = null
            _state.value = GatewayUiState(
                baseUrl = base,
                paired = false,
                notice = "已在本机解除配对；服务端凭据已尝试撤销",
            )
        }
    }

    // ---- 列表 / 发现 --------------------------------------------------------

    fun refresh() {
        if (_state.value.loading) return
        if (credentials == null) {
            _state.value = _state.value.copy(error = "请先连接云端网关", paired = false)
            return
        }
        _state.value = _state.value.copy(loading = true, error = null)
        scope.launch(io) { loadSnapshot() }
    }

    /** 调用方已经持有 IO 上下文；这里不 launch。 */
    private fun loadSnapshot() {
        val current = credentials ?: run {
            _state.value = _state.value.copy(loading = false, paired = false, error = "请先连接云端网关")
            return
        }
        val base = _state.value.baseUrl
        try {
            val snapshot = api.list(base, current.managementToken)
            _state.value = _state.value.copy(
                loading = false,
                paired = true,
                services = snapshot.services,
                client = snapshot.client,
                tokens = snapshot.tokens,
                lastLoadedAtMs = clock(),
                error = null,
            )
        } catch (error: GatewayApiException) {
            if (error.code == "unauthorized") {
                credentialsStore.clear()
                credentials = null
                _state.value = _state.value.copy(
                    loading = false,
                    paired = false,
                    services = emptyList(),
                    tokens = emptyList(),
                    issuedToken = null,
                    error = "网关凭据已失效，请重新配对",
                )
            } else {
                _state.value = _state.value.copy(loading = false, error = describe(error))
            }
        } catch (error: Exception) {
            _state.value = _state.value.copy(loading = false, error = describe(error))
        }
    }

    fun test(serviceId: String) = runServiceAction(serviceId) { token, base ->
        api.admin(base, token, JSONObject().apply {
            put("action", "test")
            put("id", serviceId)
        })
    }

    fun setEnabled(serviceId: String, enabled: Boolean) = runServiceAction(serviceId) { token, base ->
        api.admin(base, token, JSONObject().apply {
            put("action", "set-enabled")
            put("id", serviceId)
            put("enabled", enabled)
        })
    }

    fun delete(serviceId: String) = runServiceAction(serviceId, refreshAfter = true) { token, base ->
        api.admin(base, token, JSONObject().apply {
            put("action", "delete-service")
            put("id", serviceId)
        })
    }

    fun save(draft: GatewayServiceDraft, testAfterSave: Boolean = true) {
        if (_state.value.busyServiceId != null) return
        val name = draft.name.trim()
        if (name.isEmpty()) return fail("请填写服务名称")
        if (name.length > MAX_NAME_CHARS) return fail("服务名称最多 $MAX_NAME_CHARS 个字符")
        if (draft.description.length > MAX_DESCRIPTION_CHARS) return fail("说明最多 $MAX_DESCRIPTION_CHARS 个字符")
        val url = validateServiceUrl(draft.url) ?: return
        val id = draft.id.ifBlank { idFactory() }
        if (!SERVICE_ID.matches(id)) return fail("内部错误：生成的服务编号不合法")
        val secretRef = draft.secretRef.trim()
        if (secretRef.isNotEmpty() && !SECRET_REF.matches(secretRef)) {
            return fail("密钥引用只能用小写字母、数字和 -（例如 my-service）")
        }

        val current = credentials ?: return fail("请先连接云端网关")
        val base = _state.value.baseUrl
        _state.value = _state.value.copy(busyServiceId = id, error = null, notice = null)
        scope.launch(io) {
            var savedTransport: GatewayTransport? = null
            var lastTestError: Exception? = null
            try {
                // 「自动检测」：Streamable HTTP 优先，测试失败再以 SSE 重存一次。
                for (transport in GatewayTransport.candidates(draft.transport)) {
                    try {
                        api.admin(base, current.managementToken, JSONObject().apply {
                            put("action", "put-service")
                            put("id", id)
                            put("name", name)
                            put("description", draft.description.trim())
                            put("url", url)
                            put("transport", transport.wire)
                            put("enabled", draft.enabled)
                            put("timeoutMs", 10_000)
                            if (secretRef.isNotEmpty()) put("secretRef", secretRef)
                        })
                    } catch (error: Exception) {
                        lastTestError = error
                        continue
                    }
                    savedTransport = transport
                    if (!testAfterSave) break
                    try {
                        val tested = api.admin(base, current.managementToken, JSONObject().apply {
                            put("action", "test")
                            put("id", id)
                        })
                        val count = tested.optJSONArray("tools")?.length() ?: 0
                        _state.value = _state.value.copy(
                            busyServiceId = null,
                            notice = "已保存（${transport.label}）并发现 $count 个工具",
                        )
                        loadSnapshot()
                        return@launch
                    } catch (error: Exception) {
                        lastTestError = error
                        if (transport != GatewayTransport.StreamableHttp || draft.transport != GatewayTransport.Auto) break
                    }
                }

                if (savedTransport == null) {
                    _state.value = _state.value.copy(busyServiceId = null, error = describe(lastTestError ?: Exception()))
                } else {
                    _state.value = _state.value.copy(
                        busyServiceId = null,
                        notice = if (lastTestError == null) {
                            "已保存（${savedTransport!!.label}）"
                        } else {
                            "已保存（${savedTransport!!.label}），但连接测试失败：${describe(lastTestError!!)}"
                        },
                    )
                    loadSnapshot()
                }
            } catch (error: Exception) {
                _state.value = _state.value.copy(busyServiceId = null, error = describe(error))
            }
        }
    }

    fun grant(serviceId: String, tool: GatewayTool, mode: GatewayGrantMode) {
        runServiceAction(serviceId, refreshAfter = true) { token, base ->
            api.admin(base, token, JSONObject().apply {
                put("action", "grant")
                put("serviceId", serviceId)
                put("tool", tool.name)
                put("mode", mode.wire)
                put("fingerprint", tool.fingerprint)
            })
        }
    }

    fun revoke(serviceId: String, tool: GatewayTool) {
        runServiceAction(serviceId, refreshAfter = true) { token, base ->
            api.admin(base, token, JSONObject().apply {
                put("action", "revoke")
                put("serviceId", serviceId)
                put("tool", tool.name)
            })
        }
    }

    // ---- 客户端访问令牌 ----------------------------------------------------

    /**
     * 签发一枚新的 Bearer 令牌。
     *
     * 明文只在这一次响应里回到界面；之后列表里只有编号、标签、范围与时间。
     * scope 只能**收窄**授权：即使选了「读写」，confirm 模式的工具仍要一次性票据。
     */
    fun issueToken(label: String, expiresInDays: Int, write: Boolean) {
        if (_state.value.issuingToken) return
        if (expiresInDays < 0 || expiresInDays > MAX_TOKEN_DAYS) {
            return fail("有效期必须是 0–$MAX_TOKEN_DAYS 天（0 表示不设期限）")
        }
        val current = credentials ?: return fail("请先连接云端网关")
        val base = _state.value.baseUrl
        val safeLabel = label.trim().take(MAX_TOKEN_LABEL_CHARS).ifBlank { "访问令牌" }
        _state.value = _state.value.copy(
            issuingToken = true,
            error = null,
            notice = null,
            issuedToken = null,
        )
        scope.launch(io) {
            try {
                val response = api.admin(base, current.managementToken, JSONObject().apply {
                    put("action", "issue-client-token")
                    put("label", safeLabel)
                    put("expiresInDays", expiresInDays)
                    put(
                        "scopes",
                        JSONArray(if (write) listOf("read", "write") else listOf("read")),
                    )
                })
                val plain = response.optString("token")
                _state.value = _state.value.copy(
                    issuingToken = false,
                    issuedToken = plain.takeIf { it.isNotBlank() },
                    notice = "访问令牌已签发：明文只显示这一次",
                )
                loadSnapshot()
            } catch (error: Exception) {
                _state.value = _state.value.copy(issuingToken = false, error = describe(error))
            }
        }
    }

    /** 用户确认已保存明文后调用；界面据此换回普通列表。 */
    fun clearIssuedToken() {
        _state.value = _state.value.copy(issuedToken = null, notice = null)
    }

    fun revokeToken(tokenId: String) {
        if (_state.value.busyTokenId != null) return
        if (!TOKEN_ID.matches(tokenId)) return fail("内部错误：令牌编号不合法")
        val current = credentials ?: return fail("请先连接云端网关")
        val base = _state.value.baseUrl
        _state.value = _state.value.copy(busyTokenId = tokenId, error = null, notice = null)
        scope.launch(io) {
            try {
                api.admin(base, current.managementToken, JSONObject().apply {
                    put("action", "revoke-client-token")
                    put("tokenId", tokenId)
                })
                _state.value = _state.value.copy(busyTokenId = null, notice = "访问令牌已撤销")
                loadSnapshot()
            } catch (error: Exception) {
                _state.value = _state.value.copy(busyTokenId = null, error = describe(error))
            }
        }
    }

    /**
     * 通过网关真实调用一个工具。
     *
     * 授权模式为「需确认」时，先向服务端申请一次性票据（绑定工具名 + 参数摘要，
     * 60 秒过期），再把票据放进 `_meta["hub/confirmation"]`。服务端会自行复核
     * grant、定义指纹、服务启用状态与票据；客户端界面上的按钮只是入口，不是闸门。
     */
    fun call(serviceId: String, tool: GatewayTool, argumentsText: String) {
        val service = _state.value.services.firstOrNull { it.id == serviceId }
        if (service == null) return fail("这台服务已经不在列表里")
        val mode = tool.grant ?: return fail("这个工具还没有授权")
        val arguments = try {
            if (argumentsText.isBlank()) JSONObject() else JSONObject(argumentsText)
        } catch (_: Exception) {
            return fail("参数必须是 JSON 对象，例如 {\"query\":\"hello\"}")
        }
        val current = credentials ?: return fail("请先连接云端网关")
        val base = _state.value.baseUrl
        _state.value = _state.value.copy(busyServiceId = serviceId, error = null, notice = null, lastCall = null)
        scope.launch(io) {
            try {
                val confirmation = if (mode == GatewayGrantMode.Confirm) {
                    val ticket = api.admin(base, current.managementToken, JSONObject().apply {
                        put("action", "confirm")
                        put("name", tool.gatewayName)
                        put("arguments", arguments)
                    })
                    ticket.optString("confirmation").takeIf { it.isNotBlank() }
                } else {
                    null
                }
                val result = caller.call(
                    baseUrl = base,
                    clientToken = current.clientToken,
                    gatewayToolName = tool.gatewayName,
                    arguments = arguments,
                    confirmation = confirmation,
                )
                _state.value = _state.value.copy(
                    busyServiceId = null,
                    lastCall = GatewayToolCallUi(
                        serviceId = serviceId,
                        toolName = tool.name,
                        gatewayToolName = tool.gatewayName,
                        isError = result.isError,
                        text = result.text ?: "（无返回内容）",
                        atMs = clock(),
                    ),
                )
            } catch (error: Exception) {
                _state.value = _state.value.copy(busyServiceId = null, error = describe(error))
            }
        }
    }

    // ---- 工具 --------------------------------------------------------------

    private fun runServiceAction(
        serviceId: String,
        refreshAfter: Boolean = true,
        action: (String, String) -> JSONObject,
    ) {
        if (_state.value.busyServiceId != null) return
        val current = credentials ?: return fail("请先连接云端网关")
        val base = _state.value.baseUrl
        _state.value = _state.value.copy(busyServiceId = serviceId, error = null, notice = null)
        scope.launch(io) {
            try {
                action(current.managementToken, base)
                _state.value = _state.value.copy(busyServiceId = null)
                if (refreshAfter) loadSnapshot() else Unit
            } catch (error: Exception) {
                _state.value = _state.value.copy(busyServiceId = null, error = describe(error))
            }
        }
    }

    private fun fail(message: String) {
        _state.value = _state.value.copy(error = message)
    }

    private fun describe(error: Exception): String = when (error) {
        is GatewayApiException -> gatewayErrorText(error.code, error.status)
        is GatewayAddressException -> error.userText
        is java.io.IOException -> "网络不可达，请检查网络后重试"
        is org.json.JSONException -> "网关返回了无法解析的数据"
        else -> "操作失败（${error.javaClass.simpleName}）"
    }

    /** 只允许公网 HTTPS；拒绝地址里的用户名密码、查询串与片段。 */
    private fun validateServiceUrl(raw: String): String? {
        val text = raw.trim()
        val uri = try {
            URI(text)
        } catch (_: Exception) {
            fail("MCP 服务器地址格式不正确")
            return null
        }
        if (uri.scheme?.lowercase() != "https") {
            fail("MCP 服务器地址必须是 https://")
            return null
        }
        if (uri.host.isNullOrBlank() || !uri.userInfo.isNullOrEmpty() ||
            !uri.query.isNullOrEmpty() || !uri.rawFragment.isNullOrEmpty()
        ) {
            fail("地址不能包含用户名密码、查询参数或片段")
            return null
        }
        return text
    }

    companion object {
        const val MAX_NAME_CHARS = 80
        const val MAX_DESCRIPTION_CHARS = 300
        const val MAX_TOKEN_LABEL_CHARS = 64
        const val MAX_TOKEN_DAYS = 3650
        private val SERVICE_ID = Regex("^[a-z][a-z0-9-]{0,31}$")
        private val SECRET_REF = Regex("^[a-z][a-z0-9-]{0,31}$")
        private val TOKEN_ID = Regex("^[a-f0-9]{8}$")
    }
}
