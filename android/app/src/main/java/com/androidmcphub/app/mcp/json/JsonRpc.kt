package com.androidmcphub.app.mcp.json

import org.json.JSONObject

/**
 * JSON-RPC 2.0 的编解码。**纯函数**：没有 OkHttp、没有 Android、没有 IO。
 *
 * 之所以单独拆一个文件并让每个函数都不抛异常：这里处理的全部是**服务器给的字节**。
 * 一个畸形的 MCP 服务器（或者一个中间人改过的响应）不该让客户端崩溃 ——
 * 解析失败要变成 [JsonRpcMessage.Malformed]，由上层翻译成用户能看懂的错误。
 */

/** 编码一个带 id 的请求。`id` 由调用方保证唯一。 */
fun encodeRequest(id: Long, method: String, params: JSONObject?): String {
    val message = JSONObject()
    message.put("jsonrpc", "2.0")
    message.put("id", id)
    message.put("method", method)
    if (params != null) message.put("params", params)
    return message.toString()
}

/** 编码一条通知（没有 `id`，服务器不会回响应）。 */
fun encodeNotification(method: String, params: JSONObject?): String {
    val message = JSONObject()
    message.put("jsonrpc", "2.0")
    message.put("method", method)
    if (params != null) message.put("params", params)
    return message.toString()
}

/** 服务器发来（或我们发出去）的一条 JSON-RPC 消息。 */
sealed interface JsonRpcMessage {

    /** 成功响应。[id] 为 `null` 表示服务器没带 id（畸形，但可读）。 */
    data class Result(val id: Long?, val result: JSONObject) : JsonRpcMessage

    /** 错误响应。[code] 为 `null` 表示服务器给的 code 不是数字。 */
    data class Failure(val id: Long?, val code: Int?, val message: String) : JsonRpcMessage

    /** 通知（服务器主动发起）。 */
    data class Notification(val method: String, val params: JSONObject?) : JsonRpcMessage

    /** 解析不了的输入。[reason] 是中文说明，不含原始文本。 */
    data class Malformed(val reason: String) : JsonRpcMessage
}

/**
 * 解析一条 JSON-RPC 消息。**不抛异常**。
 *
 * 判定顺序：`error` → `result` → `method` → 都不像就 [JsonRpcMessage.Malformed]。
 * `error` 优先于 `result`：两者同时出现时（畸形服务器），错误信息更有诊断价值。
 */
fun parseMessage(text: String): JsonRpcMessage {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return JsonRpcMessage.Malformed("内容是空的")

    val obj = try {
        JSONObject(trimmed)
    } catch (_: Exception) {
        return JsonRpcMessage.Malformed("不是合法的 JSON 对象")
    }

    val id = readId(obj)

    if (obj.has("error") && !obj.isNull("error")) {
        val error = obj.optJSONObject("error")
            ?: return JsonRpcMessage.Failure(id, null, "服务器返回了无法解析的错误对象")
        // code 允许缺席、也允许是非数字（有的服务器给字符串），都只是"分类不可用"，
        // 不影响把 message 显示给用户。
        val code = if (error.has("code") && !error.isNull("code")) {
            runCatching { error.getInt("code") }.getOrNull()
        } else {
            null
        }
        val raw = error.optString("message", "")
        return JsonRpcMessage.Failure(id, code, raw.ifBlank { "服务器返回了未说明的错误" })
    }

    if (obj.has("result")) {
        val result = obj.optJSONObject("result")
            ?: return JsonRpcMessage.Malformed("result 不是对象")
        return JsonRpcMessage.Result(id, result)
    }

    val method = obj.optString("method", "")
    if (obj.has("method") && method.isNotEmpty()) {
        return JsonRpcMessage.Notification(method, obj.optJSONObject("params"))
    }

    return JsonRpcMessage.Malformed("既不是 result 也不是 error")
}

/**
 * 从 SSE 响应体里拆事件。**按 SSE 规范实现**，不做"能凑合解析就行"的简化：
 *
 *  - 只有 `data:` 行是事件内容，多行 data 用 `\n` 拼接；
 *  - `event:` / `id:` / `retry:` 与 `:` 开头的注释行一律忽略；
 *  - 空行分事件（SSE 的 dispatch 时机）；
 *  - **末尾没有空行的事件也要收** —— 有些服务器发完最后一个 data 就直接关连接，
 *    少收一个事件就会表现为"最后一个工具看不到"这种诡异 bug；
 *  - CRLF / CR / LF 三种换行都按行分隔处理。
 *
 * 返回值只做"拆"，不做 JSON 解析：`[DONE]` 这类非 JSON 的 data 交给调用方判 Malformed。
 */
fun extractSseEvents(body: String): List<String> {
    val events = mutableListOf<String>()
    val dataLines = mutableListOf<String>()

    fun dispatch() {
        if (dataLines.isEmpty()) return
        events += dataLines.joinToString("\n")
        dataLines.clear()
    }

    val normalized = body.replace("\r\n", "\n").replace('\r', '\n')
    for (line in normalized.split('\n')) {
        if (line.isEmpty()) {
            dispatch()
            continue
        }
        if (line.startsWith(":")) continue // 注释/心跳

        val colon = line.indexOf(':')
        val field = if (colon < 0) line else line.substring(0, colon)
        var value = if (colon < 0) "" else line.substring(colon + 1)
        // 规范规定冒号后可选一个空格，且只吃掉一个
        if (value.startsWith(" ")) value = value.substring(1)

        if (field == "data") dataLines += value
    }
    dispatch()

    return events
}

/** 读 id：缺席、JSON null、或者不是数字 → `null`（都只是"id 不可用"，不是致命错误）。 */
private fun readId(obj: JSONObject): Long? {
    if (!obj.has("id") || obj.isNull("id")) return null
    return runCatching { obj.getLong("id") }.getOrNull()
}
