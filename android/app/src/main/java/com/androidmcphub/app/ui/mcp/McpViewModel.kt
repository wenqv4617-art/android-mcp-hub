package com.androidmcphub.app.ui.mcp

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.androidmcphub.app.mcp.McpStores
import kotlinx.coroutines.flow.StateFlow

/**
 * 服务页的 ViewModel。
 *
 * 逻辑全在 [McpServersState]（纯 Kotlin，能在 JVM 单测里穷举）；这里只做两件事：
 * 把应用上下文里的存储装配进来，以及把 `viewModelScope` 借给它做网络发现。
 *
 * 用的是 Android 默认工厂能构造的形态（只有 `Application` 一个构造参数）——
 * 多一个带默认值的参数，默认工厂就找不着构造函数了。
 */
class McpViewModel(application: Application) : AndroidViewModel(application) {

    private val state = McpServersState(
        serverStore = McpStores.serverStore(application),
        authorizationStore = McpStores.authorizationStore(application),
        scope = viewModelScope,
    )

    val servers: StateFlow<List<McpServerView>> = state.servers

    val message: StateFlow<String?> = state.message

    fun dismissMessage() = state.dismissMessage()

    fun discoverAll(force: Boolean = false) = state.discoverAll(force)

    fun discover(serverId: String) = state.discover(serverId)

    fun grant(serverId: String, toolName: String) = state.grant(serverId, toolName)

    fun revoke(serverId: String, toolName: String) = state.revoke(serverId, toolName)

    fun draftFor(serverId: String?): McpServerDraft = state.draftFor(serverId)

    fun save(draft: McpServerDraft): Boolean = state.save(draft)

    fun setEnabled(serverId: String, enabled: Boolean) = state.setEnabled(serverId, enabled)

    fun delete(serverId: String) = state.delete(serverId)
}
