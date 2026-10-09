package com.androidmcphub.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.androidmcphub.app.core.HubDraft
import com.androidmcphub.app.core.HubPrefs
import com.androidmcphub.app.service.LinkBus
import com.androidmcphub.app.service.PhoneLinkService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 界面唯一的 ViewModel。
 *
 * 连接状态的**唯一真相**在 [LinkBus]（由前台服务写入），本类只做转发，
 * 不自己维护第二份状态，避免「界面说已连接、服务其实断了」这类不一致。
 */
class HubViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = HubPrefs(application)

    private val _draft = MutableStateFlow(HubDraft.from(prefs.load(), prefs.loadToken()))

    /** 「高级设置」表单草稿。 */
    val draft: StateFlow<HubDraft> = _draft.asStateFlow()

    private val _tokenVisible = MutableStateFlow(false)

    /** 令牌是否明文显示。 */
    val tokenVisible: StateFlow<Boolean> = _tokenVisible.asStateFlow()

    private val _advancedExpanded = MutableStateFlow(false)

    /** 「高级设置」是否展开。 */
    val advancedExpanded: StateFlow<Boolean> = _advancedExpanded.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)

    /** 一次性提示（保存成功 / 校验失败原因）。 */
    val message: StateFlow<String?> = _message.asStateFlow()

    /** 连接状态（来自前台服务）。 */
    val state: StateFlow<com.androidmcphub.app.protocol.LinkState> = LinkBus.state

    /** 环形日志（最新在前）。 */
    val logs = LinkBus.logs

    /** 最近一次 ping。 */
    val lastPing = LinkBus.lastPing

    /** 前台服务是否在运行。 */
    val serviceRunning = LinkBus.serviceRunning

    fun updateDraft(transform: (HubDraft) -> HubDraft) {
        _draft.value = transform(_draft.value)
        _message.value = null
    }

    fun toggleTokenVisible() {
        _tokenVisible.value = !_tokenVisible.value
    }

    fun toggleAdvanced() {
        _advancedExpanded.value = !_advancedExpanded.value
    }

    fun dismissMessage() {
        _message.value = null
    }

    /** 只保存设置，不建连。 */
    fun save() {
        val config = _draft.value.toConfig()
        prefs.save(config)
        prefs.saveToken(_draft.value.token.trim())
        _message.value = "已保存"
    }

    /**
     * 用户点「连接」：先保存当前表单（所见即所得），再启动前台服务。
     *
     * 校验不通过时只提示、不启动服务 —— 免得服务起来才发现地址是空的。
     */
    fun connect() {
        val config = _draft.value.toConfig()
        val problem = config.validate()
        if (problem != null) {
            _message.value = problem
            return
        }
        if (_draft.value.token.isBlank()) {
            _message.value = "设备令牌不能为空"
            return
        }
        prefs.save(config)
        prefs.saveToken(_draft.value.token.trim())
        _message.value = null
        PhoneLinkService.connect(getApplication())
    }

    /** 用户点「断开」：停掉服务，禁止自动重连。 */
    fun disconnect() {
        PhoneLinkService.disconnect(getApplication())
    }

    fun clearLogs() {
        LinkBus.log.clear()
    }
}
