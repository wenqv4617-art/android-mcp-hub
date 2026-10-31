package com.androidmcphub.app.ui.gateway

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.androidmcphub.app.core.HubPrefs
import com.androidmcphub.app.gateway.AndroidGatewayCredentialsStore
import com.androidmcphub.app.gateway.GatewayCredentialsStore
import com.androidmcphub.app.gateway.GatewayGrantMode
import com.androidmcphub.app.gateway.GatewayServiceDraft
import com.androidmcphub.app.gateway.GatewayState
import com.androidmcphub.app.gateway.GatewayTool
import com.androidmcphub.app.gateway.GatewayUiState
import com.androidmcphub.app.gateway.OkHttpGatewayApi
import com.androidmcphub.app.gateway.TransportGatewayCaller
import kotlinx.coroutines.flow.StateFlow
import java.net.URI

/**
 * 云端网关模式的 ViewModel。
 *
 * 与 `McpViewModel` 并列而不是合并：本机直连与网关是两套独立的凭据与
 * 数据来源，共用一个状态会把"哪些服务在手机上、哪些在云端"搅在一起。
 * 服务页用分段控件让用户明确选择当前查看的是哪一边。
 */
class GatewayViewModel(application: Application) : AndroidViewModel(application) {

    private val credentials: GatewayCredentialsStore = AndroidGatewayCredentialsStore(application)

    private val state = GatewayState(
        api = OkHttpGatewayApi(),
        credentialsStore = credentials,
        caller = TransportGatewayCaller(),
        scope = viewModelScope,
        fallbackBaseUrl = deriveGatewayBase(application),
    )

    val ui: StateFlow<GatewayUiState> = state.state

    fun pair(baseUrl: String, code: String) = state.pair(baseUrl, code)
    fun unpair() = state.unpair()
    fun refresh() = state.refresh()
    fun test(serviceId: String) = state.test(serviceId)
    fun setEnabled(serviceId: String, enabled: Boolean) = state.setEnabled(serviceId, enabled)
    fun delete(serviceId: String) = state.delete(serviceId)
    fun save(draft: GatewayServiceDraft) = state.save(draft)
    fun grant(serviceId: String, tool: GatewayTool, mode: GatewayGrantMode) = state.grant(serviceId, tool, mode)
    fun revoke(serviceId: String, tool: GatewayTool) = state.revoke(serviceId, tool)
    fun call(serviceId: String, tool: GatewayTool, arguments: String) = state.call(serviceId, tool, arguments)
    fun issueToken(label: String, expiresInDays: Int, write: Boolean) =
        state.issueToken(label, expiresInDays, write)
    fun revokeToken(tokenId: String) = state.revokeToken(tokenId)
    fun clearIssuedToken() = state.clearIssuedToken()
    fun clearNotice() = state.clearNotice()
    fun clearError() = state.clearError()
    fun clearCall() = state.clearCall()

    private fun deriveGatewayBase(application: Application): String {
        val configured = runCatching { HubPrefs(application).load().serverUrl }.getOrNull().orEmpty()
        val derived = runCatching {
            val uri = URI(configured)
            val scheme = when (uri.scheme?.lowercase()) {
                "wss", "https" -> "https"
                "ws", "http" -> "http"
                else -> return@runCatching ""
            }
            val host = uri.host ?: return@runCatching ""
            val port = if (uri.port == -1) "" else ":${uri.port}"
            "$scheme://$host$port"
        }.getOrNull().orEmpty()
        return derived.ifBlank { DEFAULT_GATEWAY_BASE_URL }
    }

    companion object {
        /**
         * 不预填任何地址：Hub 是**每个使用者自己部署**的私有网关，
         * 维护者的 Worker 不是公共中转服务。优先从用户已保存的服务器地址推导；
         * 都没有时留空，让用户填写自己的 `https://<worker>.<subdomain>.workers.dev`。
         */
        const val DEFAULT_GATEWAY_BASE_URL = ""
    }
}
