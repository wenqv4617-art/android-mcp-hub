package com.androidmcphub.app.ui.mcp

import com.androidmcphub.app.aggregation.HubIdentity
import com.androidmcphub.app.mcp.McpClientException
import com.androidmcphub.app.mcp.McpHeaderPolicy
import com.androidmcphub.app.mcp.McpHttpHeader
import com.androidmcphub.app.mcp.McpLimits
import com.androidmcphub.app.mcp.McpToolIdentity
import com.androidmcphub.app.mcp.McpUrlCheck
import com.androidmcphub.app.mcp.McpUrlPolicy
import com.androidmcphub.app.mcp.model.McpServerConfig
import com.androidmcphub.app.mcp.model.McpTransportType
import com.androidmcphub.app.mcp.store.McpAuthorizationState
import com.androidmcphub.app.mcp.store.McpAuthorizationStore
import com.androidmcphub.app.mcp.store.McpServerStore
import com.androidmcphub.app.mcp.transport.McpRemoteTool
import com.androidmcphub.app.mcp.transport.McpTransport
import com.androidmcphub.app.mcp.transport.McpTransportFactory
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 一台服务器在界面上的样子：配置 + 最近一次的发现结果。
 *
 * 发现结果**只在内存里**：工具列表是服务器的实时状态，落盘只会得到一份
 * 明天就过期的名单，还会让界面在没连上服务器的时候显示"上次看到的工具"。
 */
data class McpServerView(
    val config: McpServerConfig,
    val discovery: McpDiscovery,
)

/** 一台服务器的发现状态。四种状态都如实显示，没有"假装成功"的中间态。 */
sealed interface McpDiscovery {

    /** 还没发现过（刚添加、或应用刚启动）。 */
    data object Never : McpDiscovery

    data object Discovering : McpDiscovery

    data class Ready(
        val serverName: String?,
        val protocolVersion: String,
        val tools: List<McpToolView>,
        val fetchedAtMs: Long,
    ) : McpDiscovery

    /** @param reason 中文原因，可能直接显示给用户；**不含**服务器返回的自由文本。 */
    data class Failed(val reason: String, val atMs: Long) : McpDiscovery
}

/** 一个远端工具在界面上的样子（授权状态是**本机**的判断，不是服务器说的）。 */
data class McpToolView(
    val name: String,
    val title: String?,
    val description: String,
    val schemaText: String?,
    val schemaChars: Int,
    val fingerprint: String,
    val authorization: McpAuthorizationState,
)

/** 添加/编辑表单的草稿。 */
data class McpServerDraft(
    /** 空串 = 新增。 */
    val id: String = "",
    val name: String = "",
    val url: String = "",
    val enabled: Boolean = true,
    val headers: List<McpHeaderDraft> = emptyList(),
) {
    val isNew: Boolean get() = id.isEmpty()

    companion object {
        fun of(config: McpServerConfig): McpServerDraft = McpServerDraft(
            id = config.id,
            name = config.name,
            url = config.url,
            enabled = config.enabled,
            headers = config.headers.map { McpHeaderDraft(it.name, it.value, it.secret) },
        )
    }
}

data class McpHeaderDraft(
    val name: String = "",
    val value: String = "",
    /**
     * 是否加密保存。默认**开**：用户加一条请求头多半就是因为服务器要凭据，
     * 猜错的代价不对称 —— 把普通头当密钥加密只是白加密一次，
     * 把密钥当普通头存进明文 Prefs 是实打实的泄漏。
     */
    val secret: Boolean = true,
)

/**
 * 服务页的状态与动作（纯 Kotlin，没有 Android 依赖）。
 *
 * 之所以不直接写进 ViewModel：这里的每条规则（地址准入、请求头准入、指纹一致、
 * 授权判定）都必须在 JVM 单测里用假传输穷举，不能等到真机上才发现。
 * ViewModel 只负责把它接到应用上下文与 `viewModelScope` 上。
 */
class McpServersState(
    private val serverStore: McpServerStore,
    private val authorizationStore: McpAuthorizationStore,
    private val scope: CoroutineScope,
    private val transportFactory: (McpServerConfig) -> McpTransport = { McpTransportFactory.create(it) },
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idFactory: () -> String = { "srv_" + java.util.UUID.randomUUID().toString().replace("-", "").take(12) },
    private val limits: McpLimits = McpLimits(),
) {

    private val discoveries = mutableMapOf<String, McpDiscovery>()

    private val _servers = MutableStateFlow(snapshot())

    /** 服务器列表（始终按名称排序，顺序稳定，界面不会自己跳）。 */
    val servers: StateFlow<List<McpServerView>> = _servers.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)

    /** 一次性提示（保存结果 / 校验失败原因 / 授权变化）。 */
    val message: StateFlow<String?> = _message.asStateFlow()

    fun dismissMessage() {
        _message.value = null
    }

    private fun snapshot(): List<McpServerView> =
        serverStore.list()
            .sortedWith(compareBy({ it.name.lowercase() }, { it.id }))
            .map { McpServerView(it, discoveries[it.id] ?: McpDiscovery.Never) }

    private fun publish() {
        _servers.value = snapshot()
    }

    private fun setDiscovery(serverId: String, discovery: McpDiscovery) {
        discoveries[serverId] = discovery
        publish()
    }

    fun discoveryOf(serverId: String): McpDiscovery = discoveries[serverId] ?: McpDiscovery.Never

    /** 草稿：新增用空草稿，编辑用当前配置（含真实密钥值，界面自己要负责别明文显示）。 */
    fun draftFor(serverId: String?): McpServerDraft {
        if (serverId == null) return McpServerDraft()
        val config = serverStore.find(serverId) ?: return McpServerDraft()
        return McpServerDraft.of(config)
    }

    // ---- 发现 --------------------------------------------------------------

    /** 发现所有**已启用**服务器的工具；已经拿到结果或正在发现的不重复发请求。 */
    fun discoverAll(force: Boolean = false) {
        for (config in serverStore.list()) {
            if (!config.enabled) continue
            val current = discoveries[config.id]
            if (current is McpDiscovery.Discovering) continue
            if (!force && current is McpDiscovery.Ready) continue
            discover(config.id)
        }
    }

    /**
     * 连上这台服务器、列出工具、把每个工具的**授权状态**按指纹重新判定。
     *
     * 只列工具，**不调用**任何工具 —— 发现过程本身不会让远端的任何东西被执行。
     */
    fun discover(serverId: String) {
        val config = serverStore.find(serverId)
        if (config == null) {
            _message.value = "这台服务器已经不在了"
            publish()
            return
        }
        if (!config.enabled) {
            setDiscovery(serverId, McpDiscovery.Failed("这台服务器已停用", clock()))
            return
        }
        if (discoveries[serverId] is McpDiscovery.Discovering) return

        setDiscovery(serverId, McpDiscovery.Discovering)
        scope.launch(io) {
            val transport = transportFactory(config)
            try {
                val handshake = transport.connect()
                val tools = transport.listTools()
                val views = tools.take(limits.maxTools).map { toolView(config, it) }
                setDiscovery(
                    serverId,
                    McpDiscovery.Ready(
                        serverName = handshake.serverName,
                        protocolVersion = handshake.protocolVersion,
                        tools = views,
                        fetchedAtMs = clock(),
                    ),
                )
            } catch (error: McpClientException) {
                setDiscovery(serverId, McpDiscovery.Failed(describe(error), clock()))
            } catch (error: Exception) {
                // 兜底只报类名：异常的 message 可能带着服务器返回的原文。
                setDiscovery(serverId, McpDiscovery.Failed("连接失败（${error.javaClass.simpleName}）", clock()))
            } finally {
                runCatching { transport.close() }
            }
        }
    }

    private fun toolView(config: McpServerConfig, tool: McpRemoteTool): McpToolView {
        val schema = McpToolIdentity.originalSchema(tool)
        val schemaText = schema?.toString()
        val fingerprint = McpToolIdentity.fingerprintOf(config.url, tool)
        return McpToolView(
            name = tool.name,
            title = tool.title?.takeIf { it.isNotBlank() },
            description = clip(tool.description, limits.maxDescriptionChars),
            schemaText = schemaText?.let { clip(it, SCHEMA_DISPLAY_CHARS) },
            schemaChars = schemaText?.length ?: 0,
            fingerprint = fingerprint,
            authorization = authorizationStore.state(serverId = config.id, toolName = tool.name, fingerprint = fingerprint),
        )
    }

    // ---- 授权 --------------------------------------------------------------

    /**
     * 授权一个工具。
     *
     * 指纹取的是**刚刚发现到的**那一次定义 —— 用户点的就是屏幕上看到的那个工具，
     * 授权它现在这个样子。之后服务器改了定义，指纹对不上，运行时会拒绝调用并要求重新确认。
     */
    fun grant(serverId: String, toolName: String) {
        val ready = discoveries[serverId] as? McpDiscovery.Ready
        val tool = ready?.tools?.firstOrNull { it.name == toolName }
        if (tool == null) {
            _message.value = "请先刷新工具列表，再授权"
            return
        }
        authorizationStore.grant(serverId, toolName, tool.fingerprint)
        refreshAuthorizations(serverId)
        _message.value = "已授权「${tool.title ?: tool.name}」"
    }

    fun revoke(serverId: String, toolName: String) {
        authorizationStore.revoke(serverId, toolName)
        refreshAuthorizations(serverId)
        _message.value = "已撤销授权"
    }

    /** 按**当前**定义重算每个工具的授权状态（不联网，只读本机记录）。 */
    private fun refreshAuthorizations(serverId: String) {
        val ready = discoveries[serverId] as? McpDiscovery.Ready ?: return
        val updated = ready.tools.map { tool ->
            tool.copy(
                authorization = authorizationStore.state(serverId, tool.name, tool.fingerprint),
            )
        }
        setDiscovery(serverId, ready.copy(tools = updated))
    }

    // ---- 增删改 ------------------------------------------------------------

    /**
     * 保存（新增或更新）一台服务器。
     *
     * @return `true` 表示已落盘；`false` 表示被拦下，原因在 [message] 里。
     */
    fun save(draft: McpServerDraft): Boolean {
        val name = draft.name.trim()
        if (name.isEmpty()) {
            _message.value = "请填写服务名称"
            return false
        }
        if (name.length > MAX_NAME_CHARS) {
            _message.value = "服务名称太长（最多 $MAX_NAME_CHARS 个字符）"
            return false
        }

        val urlCheck = McpUrlPolicy.validate(draft.url)
        if (urlCheck is McpUrlCheck.Rejected) {
            _message.value = urlCheck.reason
            return false
        }
        urlCheck as McpUrlCheck.Ok

        val headers = mutableListOf<McpHttpHeader>()
        val seen = mutableSetOf<String>()
        for (row in draft.headers) {
            val headerName = row.name.trim()
            val value = row.value
            if (headerName.isEmpty() && value.isEmpty()) continue
            if (headerName.isEmpty()) {
                _message.value = "请求头名称不能为空"
                return false
            }
            McpHeaderPolicy.problem(McpHttpHeader(headerName, value, secret = row.secret))?.let {
                _message.value = it
                return false
            }
            if (!seen.add(headerName.lowercase())) {
                _message.value = "请求头「$headerName」出现了两次"
                return false
            }
            headers += McpHttpHeader(name = headerName, value = value, enabled = true, secret = row.secret)
        }

        val existing = draft.id.takeIf { it.isNotEmpty() }?.let { serverStore.find(it) }
        val id = existing?.id ?: idFactory()
        if (!HubIdentity.isValidServerId(id)) {
            _message.value = "内部错误：生成的服务器编号不合法"
            return false
        }

        val config = McpServerConfig(
            id = id,
            name = name,
            group = existing?.group.orEmpty(),
            url = urlCheck.url,
            transport = existing?.transport ?: McpTransportType.StreamableHttp,
            headers = headers,
            enabled = draft.enabled,
            createdAtMs = existing?.createdAtMs ?: clock(),
            updatedAtMs = clock(),
        )

        // 地址换了就是换了一台服务器：旧的授权指向别处，必须作废，不能让"我在旧地址
        // 上授权过的那个工具"被悄悄带到新地址上去。
        val movedElsewhere = existing != null && existing.url != config.url
        if (movedElsewhere) authorizationStore.revokeServer(id)

        runCatching { serverStore.upsert(config) }.onFailure {
            _message.value = "保存失败（存储不可用）"
            return false
        }

        if (movedElsewhere) {
            discoveries.remove(id)
            _message.value = "地址已变更，原有的工具授权已全部作废，请重新刷新并授权"
        } else {
            _message.value = if (draft.isNew) "已添加「$name」" else "已保存「$name」"
        }
        publish()
        return true
    }

    fun setEnabled(serverId: String, enabled: Boolean) {
        val config = serverStore.find(serverId) ?: return
        runCatching { serverStore.upsert(config.copy(enabled = enabled, updatedAtMs = clock())) }
            .onFailure {
                _message.value = "保存失败（存储不可用）"
                return
            }
        if (!enabled) {
            discoveries[serverId] = McpDiscovery.Never
            _message.value = "已停用「${config.name}」：模型不会再看到它，也不会被调用"
        } else {
            _message.value = "已启用「${config.name}」，刷新后可以看到它的工具"
        }
        publish()
    }

    fun delete(serverId: String) {
        val config = serverStore.find(serverId) ?: return
        runCatching { serverStore.delete(serverId) }.onFailure {
            _message.value = "删除失败（存储不可用）"
            return
        }
        // 配置没了，授权记录也不能留：否则将来新建一台同 id 的服务器会白捡旧授权。
        authorizationStore.revokeServer(serverId)
        discoveries.remove(serverId)
        _message.value = "已删除「${config.name}」及其全部授权"
        publish()
    }

    private fun describe(error: McpClientException): String {
        val where = when {
            error.httpStatus != null -> "（HTTP ${error.httpStatus}）"
            error.rpcCode != null -> "（JSON-RPC 码 ${error.rpcCode}）"
            else -> ""
        }
        return "${error.kind.label}$where"
    }

    private fun clip(text: String, max: Int): String =
        if (text.length <= max) text else text.take(max) + "…"

    companion object {
        const val MAX_NAME_CHARS = 128
        const val SCHEMA_DISPLAY_CHARS = 600
    }
}
