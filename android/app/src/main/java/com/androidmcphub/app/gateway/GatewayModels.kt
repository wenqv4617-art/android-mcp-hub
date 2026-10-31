package com.androidmcphub.app.gateway

/**
 * 云端网关的数据形态。
 *
 * 这些类型只描述**网关管理 API 返回的事实**，不做任何授权推断：
 * 一个工具是否可调用由服务端的 grant（工具名 + 定义指纹）决定，
 * 客户端显示的状态只是那份记录的回显。
 */

data class GatewayService(
    val id: String,
    val name: String,
    val description: String,
    val url: String,
    val transport: GatewayTransport,
    val enabled: Boolean,
    val secretRef: String?,
    val timeoutMs: Int,
    val revision: String,
    val tools: List<GatewayTool>,
    val lastError: String?,
    val lastTestAtMs: Long?,
)

data class GatewayTool(
    val name: String,
    /** 网关对外的稳定名字（`ext_<service>_<hash>`），调用时用它。 */
    val gatewayName: String,
    val description: String,
    val schemaJson: String?,
    /** 服务端记录的 `sha256(canonical(tool))`，授权时原样回传。 */
    val fingerprint: String,
    val grant: GatewayGrantMode?,
)

enum class GatewayTransport(val wire: String, val label: String) {
    /** 仅用于新增表单：先试 Streamable HTTP，失败再试旧版 SSE。 */
    Auto("auto", "自动检测"),
    StreamableHttp("streamable-http", "Streamable HTTP"),
    Sse("sse", "旧版 HTTP+SSE"),
    ;

    companion object {
        fun fromWire(value: String?): GatewayTransport =
            entries.firstOrNull { it.wire == value } ?: StreamableHttp

        /** 自动检测时实际尝试的顺序：Streamable HTTP 优先。 */
        fun candidates(selected: GatewayTransport): List<GatewayTransport> = when (selected) {
            Auto -> listOf(StreamableHttp, Sse)
            else -> listOf(selected)
        }
    }
}

enum class GatewayGrantMode(val wire: String, val label: String) {
    Read("read", "只读"),
    Confirm("confirm", "需确认"),
}

data class GatewayClientInfo(
    val ownerId: String,
    val clientId: String,
    val deviceId: String,
    val deviceName: String,
    val lastPairedAtMs: Long?,
)

data class GatewayPairResult(
    val managementToken: String,
    val clientToken: String,
    val ownerId: String,
    val clientId: String,
)

data class GatewayCallResult(
    val isError: Boolean,
    val text: String?,
)

/** 已保存的凭据。只在内存与加密存储之间流转，不进入任何 UI 状态或日志。 */
data class GatewayCredentials(
    val managementToken: String,
    val clientToken: String,
)

/**
 * 一条访问令牌的**元数据**。明文只在签发响应里出现一次，永远不会回到这里。
 *
 * `id` 是令牌字符串里内嵌的 8 位十六进制编号（`hub_client_<id>_<secret>`），
 * 撤销时只传它；服务端按摘要认证，列表里也不会出现摘要。
 */
data class GatewayAccessToken(
    val id: String,
    val label: String,
    val scopes: List<String>,
    val source: String,
    val createdAtMs: Long?,
    val expiresAtMs: Long?,
    val lastUsedAtMs: Long?,
    val revokedAtMs: Long?,
) {
    fun isActive(nowMs: Long): Boolean =
        revokedAtMs == null && (expiresAtMs == null || expiresAtMs > nowMs)

    /** scope 只能收窄授权，所以这里讲的是"这张令牌最多能做什么"。 */
    val scopeLabel: String
        get() = when {
            scopes.contains("write") -> "读写"
            scopes.contains("read") -> "只读"
            else -> "无权限"
        }

    val sourceLabel: String
        get() = if (source == "pair") "设备配对" else "手动签发"
}

/** 列表接口的解析结果。 */
data class GatewaySnapshot(
    val services: List<GatewayService>,
    val client: GatewayClientInfo?,
    val tokens: List<GatewayAccessToken> = emptyList(),
)

/** 添加/编辑表单。 */
data class GatewayServiceDraft(
    val id: String = "",
    val name: String = "",
    val description: String = "",
    val url: String = "",
    val transport: GatewayTransport = GatewayTransport.StreamableHttp,
    val enabled: Boolean = true,
    val secretRef: String = "",
) {
    val isNew: Boolean get() = id.isEmpty()
}
