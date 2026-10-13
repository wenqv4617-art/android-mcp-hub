package com.androidmcphub.app.mcp

import com.androidmcphub.app.mcp.transport.McpRemoteTool
import org.json.JSONObject

/**
 * 工具身份的**唯一算法**。
 *
 * 存在的理由：同一个"这台服务器上的这个工具"要在两个地方被认出来 ——
 *  1. 界面：列出工具、判断"已授权 / 未授权 / 定义变了"，决定给用户看什么；
 *  2. 运行时：聚合入口在真正调用之前复查授权。
 * 这两处如果各写一份算指纹的代码，早晚会在某个细节上分叉（比如一边用被截断过的
 * schema、一边用原文），后果是用户在界面上明明授权了，模型一调用却被告知"没授权"，
 * 而且界面上怎么看都是对的 —— 查起来极其痛苦。所以算法只有这一份。
 */
object McpToolIdentity {

    /**
     * 服务器给的**原始**输入结构。
     *
     * 优先从 [McpRemoteTool.rawJson] 里取：传输层在 schema 超过展示上限时会把它
     * 置成 `null`（只留 rawJson），那是**本机自己的展示限制**，不是服务器改了定义。
     * 拿截断后的 `null` 去算指纹，会让每次重新发现都变成"定义变了"，把用户拖进
     * 反复重新授权的循环。取不到才退回 [McpRemoteTool.inputSchema]。
     */
    fun originalSchema(tool: McpRemoteTool): JSONObject? =
        runCatching { JSONObject(tool.rawJson).optJSONObject("inputSchema") }.getOrNull()
            ?: tool.inputSchema

    /** 见 [McpToolFingerprint]：标题、描述、原始 schema 一起进指纹。 */
    fun fingerprintOf(serverUrl: String, tool: McpRemoteTool): String =
        McpToolFingerprint.of(
            serverUrl = serverUrl,
            toolName = tool.name,
            toolTitle = tool.title,
            toolDescription = tool.description,
            inputSchemaJson = originalSchema(tool)?.toString(),
        )
}
