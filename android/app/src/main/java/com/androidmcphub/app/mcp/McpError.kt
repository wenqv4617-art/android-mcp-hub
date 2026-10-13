package com.androidmcphub.app.mcp

/**
 * MCP 客户端失败的**闭集**分类。
 *
 * 为什么要分类而不是丢一个 `IOException` 出去：界面上要显示的补救动作完全不同 ——
 * [NOT_AUTHORIZED] 让用户去改请求头里的凭据，[SERVER_UNAVAILABLE] 让用户检查地址和网络，
 * [RESULT_TOO_LARGE] 是"结果太大，不是你的配置错了"。把异常文本直接扔到屏幕上，
 * 用户既看不懂也修不了。
 *
 * [label] 是给界面用的中文短语，**不含任何运行期数据**。
 */
enum class McpErrorKind(val label: String) {
    HTTP_ERROR("服务器返回了错误的 HTTP 状态"),
    SERVER_UNAVAILABLE("连不上或服务器不可用"),
    PROTOCOL_ERROR("服务器返回的内容不是合法 MCP/JSON-RPC"),
    TIMEOUT("请求超时"),
    RESULT_TOO_LARGE("结果超过大小上限"),
    INVALID_ARGUMENTS("参数不符合工具的 Schema"),
    NOT_AUTHORIZED("这个工具没有被授权"),
    SERVER_REJECTED("服务器拒绝了这次请求"),
}

/**
 * MCP 客户端的唯一异常类型。
 *
 * 安全约定（本文件存在的第二个理由）：[message] 里**绝不能**出现
 *  - 任何请求头的值（在 [com.androidmcphub.app.mcp.McpHeaderPolicy.safeHeaderSummary] 里
 *    统一走脱敏路径）；
 *  - 设备令牌或 header 里携带的凭据；
 *  - 完整 URL 的查询串（query 里经常挂着 `?token=...`，所以异常里一律不提 query）。
 *
 * 只带"哪一类失败"（[kind]）与"HTTP 状态 / JSON-RPC 错误码"这种不含凭据的定位信息。
 *
 * @param httpStatus 触发失败的 HTTP 状态码；不是 HTTP 层失败时为 `null`。
 * @param rpcCode JSON-RPC error.code；不是 RPC 层失败时为 `null`。
 */
class McpClientException(
    val kind: McpErrorKind,
    message: String,
    val httpStatus: Int? = null,
    val rpcCode: Int? = null,
) : Exception(message)
