package com.androidmcphub.app.gateway

import java.net.URI

/**
 * 网关地址的准入。
 *
 * 和 MCP 服务器地址不同：网关只应通过 HTTPS 访问（本机回环例外，供开发调试）。
 * 不允许用户名/密码、查询串或片段 —— 这些要么会被拼进路径后失效，要么会把
 * 凭据写进地址栏/日志。返回的 base 永远不带尾部 `/`。
 */
object GatewayUrls {

    fun normalizeBase(raw: String): Result<String> {
        val text = raw.trim()
        if (text.isEmpty()) return Result.failure(GatewayAddressException("请填写网关地址"))
        val uri = try {
            URI(text)
        } catch (_: Exception) {
            return Result.failure(GatewayAddressException("网关地址格式不正确"))
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "https" && scheme != "http") {
            return Result.failure(GatewayAddressException("网关地址必须以 https:// 开头"))
        }
        val host = uri.host ?: return Result.failure(GatewayAddressException("网关地址缺少主机名"))
        if (host.isBlank()) return Result.failure(GatewayAddressException("网关地址缺少主机名"))
        if (scheme == "http" && host !in setOf("127.0.0.1", "localhost", "::1")) {
            return Result.failure(GatewayAddressException("只有本机回环地址可以使用 http"))
        }
        if (!uri.userInfo.isNullOrEmpty()) {
            return Result.failure(GatewayAddressException("网关地址里不能包含用户名或密码"))
        }
        if (!uri.query.isNullOrEmpty() || !uri.rawFragment.isNullOrEmpty()) {
            return Result.failure(GatewayAddressException("网关地址不能带查询参数或片段"))
        }
        val path = uri.path.orEmpty().trimEnd('/')
        if (path.isNotEmpty()) {
            return Result.failure(GatewayAddressException("只填网关根地址，例如 https://example.workers.dev"))
        }
        val port = if (uri.port == -1) "" else ":${uri.port}"
        return Result.success("$scheme://$host$port")
    }

    fun pair(base: String): String = "$base/gateway/pair"
    fun admin(base: String): String = "$base/gateway/android"
    fun mcp(base: String): String = "$base/mcp"
}

class GatewayAddressException(val userText: String) : Exception(userText)

/** 服务器返回的错误码 → 用户能看懂的中文。未知码只显示固定文本，不回显任意响应体。 */
fun gatewayErrorText(code: String?, status: Int): String = when (code) {
    "pairing_denied" -> "配对口令不正确"
    "pairing_rate_limited" -> "配对尝试过于频繁，请一分钟后再试"
    "pairing_limit" -> "这台网关上的设备数量已达上限"
    "not_found" -> "该操作不可用（接口或服务不存在）"
    "unauthorized" -> "网关凭据已失效，请重新配对"
    "permission_denied", "immutable_owner" -> "当前设备无权执行这个操作"
    "invalid_service" -> "服务配置不合法，请检查名称、地址与传输方式"
    "target_denied" -> "这个地址不在网关的允许清单里，或不是 HTTPS"
    "definition_changed" -> "工具定义已变化，请重新发现后再授权"
    "service_limit" -> "服务数量已达上限"
    "token_limit" -> "这个客户端的有效令牌已达上限，请先撤销不用的令牌"
    "invalid_token_options" -> "令牌有效期不合法"
    "invalid_scopes" -> "令牌权限范围不合法"
    "upstream_timeout" -> "连接上游 MCP 服务器超时"
    "upstream_failure" -> "连接上游 MCP 服务器失败"
    "upstream_network" -> "连接上游 MCP 服务器时网络中断"
    "upstream_tool_error" -> "上游 MCP 工具返回错误"
    "confirmation_required" -> "这个工具需要确认：请重新申请确认后再调用"
    "invalid_arguments" -> "参数不符合工具定义"
    "dns_denied" -> "上游域名解析失败或被拒绝"
    "dns_unavailable" -> "网关的 DNS 查询服务暂时不可用"
    "concurrency_limit" -> "网关繁忙，请稍后再试"
    "gateway_disabled" -> "网关未启用"
    null -> "请求失败（HTTP $status）"
    else -> "网关返回错误：$code"
}
