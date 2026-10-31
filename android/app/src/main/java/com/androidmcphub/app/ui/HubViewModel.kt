package com.androidmcphub.app.ui

import android.app.Application
import android.content.Context
import android.os.PowerManager
import androidx.lifecycle.AndroidViewModel
import com.androidmcphub.app.core.HubDraft
import com.androidmcphub.app.core.HubPrefs
import com.androidmcphub.app.core.TokenLoadResult
import com.androidmcphub.app.service.LinkBus
import com.androidmcphub.app.service.LinkRestorePolicy
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

    /**
     * 一进界面就得知道的事：令牌到底读不读得出来（Gate 0.1 审计 R4）。
     *
     * 读不出来**必须**当场告诉用户，不能等用户点「连接」之后再说 ——
     * 那时候他只会看到一句"认证失败"，根本想不到是自己那串令牌没被读出来。
     */
    private val initialCredential: TokenLoadResult = prefs.loadTokenResult()

    private val _draft = MutableStateFlow(
        HubDraft.from(
            prefs.load(),
            (initialCredential as? TokenLoadResult.Present)?.token.orEmpty(),
        ),
    )

    /** 「高级设置」表单草稿。 */
    val draft: StateFlow<HubDraft> = _draft.asStateFlow()

    private val _tokenVisible = MutableStateFlow(false)

    /** 令牌是否明文显示。 */
    val tokenVisible: StateFlow<Boolean> = _tokenVisible.asStateFlow()

    private val _advancedExpanded = MutableStateFlow(false)

    /** 「高级设置」是否展开。 */
    val advancedExpanded: StateFlow<Boolean> = _advancedExpanded.asStateFlow()

    private val _capabilitiesEnabled = MutableStateFlow(prefs.capabilitiesEnabled())

    /**
     * 是否允许服务端调用本机的只读能力（Gate 2 工作包 D 的"本地授权策略"）。
     *
     * 初始值只有一个来源（[HubPrefs.capabilitiesEnabled]，集中审计问题 5 之后
     * 首次安装默认**关**）：这里直接从 prefs 读，**不写第二份字面量** ——
     * 否则两处默认值总有一天会不一致，而那种不一致正好是"以为关着其实开着"。
     */
    val capabilitiesEnabled: StateFlow<Boolean> = _capabilitiesEnabled.asStateFlow()

    private val _writeCapabilitiesEnabled = MutableStateFlow(prefs.writeCapabilitiesEnabled())

    /** 是否允许执行**会修改数据**的能力（写入总开关，默认关）。 */
    val writeCapabilitiesEnabled: StateFlow<Boolean> = _writeCapabilitiesEnabled.asStateFlow()

    private val _disabledCapabilities = MutableStateFlow(prefs.disabledCapabilities())

    /** 被用户单独关掉的工具名集合。 */
    val disabledCapabilities: StateFlow<Set<String>> = _disabledCapabilities.asStateFlow()

    private val _enabledWriteCapabilities = MutableStateFlow(prefs.enabledWriteCapabilities())

    /** 被用户逐条打开的写入类工具名集合（写入类默认关）。 */
    val enabledWriteCapabilities: StateFlow<Set<String>> = _enabledWriteCapabilities.asStateFlow()

    private val _message = MutableStateFlow(
        (initialCredential as? TokenLoadResult.Unreadable)
            ?.let { "设备令牌无法解密（${it.reason}），请重新填写并保存" },
    )

    /** 一次性提示（保存成功 / 校验失败原因 / 令牌读不出来）。 */
    val message: StateFlow<String?> = _message.asStateFlow()

    /** 连接状态（来自前台服务）。 */
    val state: StateFlow<com.androidmcphub.app.protocol.LinkState> = LinkBus.state

    /** 环形日志（最新在前）。 */
    val logs = LinkBus.logs

    /** 最近一次 ping。 */
    val lastPing = LinkBus.lastPing

    /** 前台服务是否在运行。 */
    val serviceRunning = LinkBus.serviceRunning

    /**
     * 最近一次错误。来自 [LinkBus]，**不会**被"断开/服务销毁"清掉 ——
     * 否则用户永远看不到连接为什么失败。
     */
    val lastError = LinkBus.lastError

    /** 结构化连接事件（最新在前，最多 50 条）。 */
    val events = LinkBus.events

    /** 连接统计：最近成功/心跳/断线、重连次数、当前网络类型。 */
    val diagnostics = LinkBus.diagnostics

    private val _batteryUnrestricted = MutableStateFlow(isBatteryUnrestricted())

    /**
     * 本应用是否被系统排除在电池优化之外。
     *
     * 只用于**提示与跳转**：不申请 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`，
     * 也不弹系统对话框逼用户改设置 —— 用户不开也能用，只是后台被省电策略
     * 冻结的概率更高（诊断页会如实说明这一点）。
     */
    val batteryUnrestricted: StateFlow<Boolean> = _batteryUnrestricted.asStateFlow()

    /** 最近一次请求恢复连接的时刻，避免 `init` 与 `onStart` 在几毫秒内各投递一次 CONNECT。 */
    private var lastRestoreRequestAtMs: Long = 0L

    init {
        LinkBus.publishLinkDesired(prefs.linkDesired())
        restoreLinkIfDesired()
    }

    /**
     * 应用回到前台时调用（`MainActivity.onStart`）。
     *
     * 为什么不能只在 ViewModel `init` 里恢复：进程还活着、只是前台服务被系统
     * 停掉时，Activity 在后台被保留，ViewModel 不会重建，`init` 也不会再跑。
     * 用户切回应用看到"已断开、服务未运行"，却不会自动恢复连接 —— 这正是
     * V1.6.2 要补的一环。这里只读 `linkDesired`，不会替用户做断开决定。
     */
    fun onAppForegrounded() {
        refreshBatteryStatus()
        LinkBus.publishLinkDesired(prefs.linkDesired())
        restoreLinkIfDesired()
    }

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

    /**
     * 切换只读能力总开关。立即落盘 —— 分发器每次都重读设置，所以**不用重连**就生效。
     */
    fun setCapabilitiesEnabled(enabled: Boolean) {
        prefs.saveCapabilitiesEnabled(enabled)
        _capabilitiesEnabled.value = enabled
        _message.value = if (enabled) {
            "已允许服务端调用本机只读能力"
        } else {
            "已关闭只读能力调用，本机不再执行任何能力请求"
        }
    }

    /**
     * 切换**写入**总开关。与只读总开关分开：打开它只表示"我知道这个应用可以改数据"，
     * 每一条写入类工具还要单独打开（默认关）。
     */
    fun setWriteCapabilitiesEnabled(enabled: Boolean) {
        prefs.saveWriteCapabilitiesEnabled(enabled)
        _writeCapabilitiesEnabled.value = enabled
        _message.value = if (enabled) {
            "已允许修改数据。写入类工具仍需逐条打开才会执行"
        } else {
            "已关闭数据修改，所有写入/删除类工具都不再执行"
        }
    }

    /** 单独开关一条只读工具（写入类用 [setWriteToolEnabled]）。 */
    fun setToolEnabled(toolName: String, enabled: Boolean) {
        prefs.setCapabilityToolEnabled(toolName, enabled)
        _disabledCapabilities.value = prefs.disabledCapabilities()
        _message.value = if (enabled) "已启用 $toolName" else "已关闭 $toolName"
    }

    /** 单独开关一条写入类工具。关闭写入总开关时这里只记录偏好，不会绕过总开关。 */
    fun setWriteToolEnabled(toolName: String, enabled: Boolean) {
        prefs.setWriteCapabilityEnabled(toolName, enabled)
        _enabledWriteCapabilities.value = prefs.enabledWriteCapabilities()
        _message.value = if (enabled) {
            "已允许 $toolName（仍受写入总开关约束）"
        } else {
            "已禁止 $toolName"
        }
    }

    fun dismissMessage() {
        _message.value = null
    }

    /** 只保存设置，不建连。 */
    fun save() {
        val config = _draft.value.toConfig()
        prefs.save(config)
        // 令牌写失败必须说出来 —— 静默失败会让用户以为已经存好了。
        _message.value = if (prefs.saveToken(_draft.value.token.trim())) {
            "已保存"
        } else {
            "设置已保存，但设备令牌写入失败（存储不可用），请重试"
        }
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
        if (!prefs.saveToken(_draft.value.token.trim())) {
            _message.value = "设备令牌写入失败（存储不可用），未发起连接"
            return
        }
        _message.value = null
        LinkBus.clearLastError()
        PhoneLinkService.connect(getApplication())
    }

    /** 清掉"最近一次错误"，不动连接状态。 */
    fun clearLastError() {
        LinkBus.clearLastError()
    }

    /** 清空设备令牌（同时删掉磁盘上的密文）。 */
    fun clearToken() {
        _draft.value = _draft.value.copy(token = "")
        prefs.clearToken()
        _message.value = "设备令牌已清除"
    }

    /** 用户点「断开」：停掉服务，禁止自动重连。 */
    fun disconnect() {
        PhoneLinkService.disconnect(getApplication())
    }

    fun clearLogs() {
        LinkBus.log.clear()
    }

    /** 清空诊断页的连接事件（不动当前连接状态）。 */
    fun clearEvents() {
        LinkBus.clearEvents()
    }

    /** 诊断页回到前台时重新读一次系统设置（用户可能刚从设置页回来）。 */
    fun refreshBatteryStatus() {
        _batteryUnrestricted.value = isBatteryUnrestricted()
    }

    /**
     * 打开应用时恢复"用户此前希望保持连接"的会话。
     *
     * 触发条件刻意收得很紧：
     *  - 用户明确点过「连接」（[HubPrefs.linkDesired]），不是系统自己猜的；
     *  - 前台服务当前**没有**在跑（已经在跑就不重复投递 CONNECT）；
     *  - 服务器地址与令牌仍在且可用 —— 否则恢复只会立刻撞出同一个错误，
     *    把用户上次失败的原因覆盖成一条更模糊的提示。
     *
     * 这是"进程被系统回收后重开应用"的最后一道恢复网；正常后台保活由
     * `START_STICKY` + 前台服务自己完成，不依赖界面。
     */
    private fun restoreLinkIfDesired() {
        val token = (prefs.loadTokenResult() as? TokenLoadResult.Present)?.token
        val shouldRestore = LinkRestorePolicy.shouldRestore(
            linkDesired = prefs.linkDesired(),
            serviceRunning = LinkBus.serviceRunning.value,
            configValid = prefs.load().validate() == null,
            tokenPresent = !token.isNullOrBlank(),
        )
        if (!shouldRestore) return
        // 同一个 ViewModel 可能在 init 与 onStart 里各调一次；两次 CONNECT 会让
        // 服务把刚建立的 socket 关掉再建一条。2 秒窗口足够覆盖启动序列，又不会
        // 妨碍用户真正"断开后立刻重连"（那条路径不经过这里）。
        val now = System.currentTimeMillis()
        if (now - lastRestoreRequestAtMs < RESTORE_DEBOUNCE_MS) return
        lastRestoreRequestAtMs = now

        LinkBus.log.info("检测到上次的连接意图，恢复前台连接")
        PhoneLinkService.connect(getApplication())
    }

    private fun isBatteryUnrestricted(): Boolean {
        val app = getApplication<Application>()
        val pm = app.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return pm.isIgnoringBatteryOptimizations(app.packageName)
    }

    private companion object {
        const val RESTORE_DEBOUNCE_MS = 2_000L
    }
}
