package com.androidmcphub.app.mcp

/**
 * 一次 MCP 会话的全部硬上限。
 *
 * 存在的理由：MCP 服务器是**用户自己填进来的第三方进程**，它的响应大小、分页次数、
 * 工具数量都不受我们控制。所有"可能被远端撑爆"的量在这里集中成可注入的参数 ——
 * 默认值同时在真机上生效，单测里则把它调小（比如 `readTimeoutMs = 300`）就能把
 * 超时、超量这些分支真的跑一遍，而不是靠 mock 假装。
 *
 * 单位说明：[maxResponseBytes] / [maxSchemaChars] 都是**字节**（UTF-8），
 * 与 HTTP 的 Content-Length 语义对齐；[maxDescriptionChars] 是字符数。
 */
data class McpLimits(
    /** 单次响应体上限（字节）。超过按 [McpErrorKind.RESULT_TOO_LARGE] 处理。 */
    val maxResponseBytes: Int = 512 * 1024,
    /** `tools/list` 分页最多翻多少页。防止服务器用"永远给 nextCursor"把客户端拖死。 */
    val maxToolPages: Int = 20,
    /** 工具总数上限。超过说明这个服务器不该整表拉进手机，宁可报错也不把内存吃光。 */
    val maxTools: Int = 200,
    /** 建连超时（毫秒）。 */
    val connectTimeoutMs: Long = 15_000,
    /** 单次读超时（毫秒）。Agent 类工具会比较慢，所以给到 30s。 */
    val readTimeoutMs: Long = 30_000,
    /** 工具描述保留的最大字符数，超出截断（截断而不是丢弃：描述是给模型看的）。 */
    val maxDescriptionChars: Int = 400,
    /** inputSchema 保留的最大字节数，超出则丢弃 schema 并在描述里注明。 */
    val maxSchemaChars: Int = 4_000,
)
