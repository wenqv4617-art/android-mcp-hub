package com.androidmcphub.app.mcp.json

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [encodeRequest] / [encodeNotification] / [parseMessage] / [extractSseEvents] 的测试。 */
class JsonRpcTest {

    // ---- 解析辅助（让每个用例只关心自己那一条规则） ----

    private fun failure(text: String): JsonRpcMessage.Failure {
        val message = parseMessage(text)
        assertTrue("期望 Failure，实际 $message", message is JsonRpcMessage.Failure)
        return message as JsonRpcMessage.Failure
    }

    private fun result(text: String): JsonRpcMessage.Result {
        val message = parseMessage(text)
        assertTrue("期望 Result，实际 $message", message is JsonRpcMessage.Result)
        return message as JsonRpcMessage.Result
    }

    private fun notification(text: String): JsonRpcMessage.Notification {
        val message = parseMessage(text)
        assertTrue("期望 Notification，实际 $message", message is JsonRpcMessage.Notification)
        return message as JsonRpcMessage.Notification
    }

    private fun malformed(text: String): JsonRpcMessage.Malformed {
        val message = parseMessage(text)
        assertTrue("期望 Malformed，实际 $message", message is JsonRpcMessage.Malformed)
        return message as JsonRpcMessage.Malformed
    }

    // ---- 编码 ----

    @Test
    fun `encodeRequest 带 id 与 params`() {
        val obj = JSONObject(encodeRequest(7L, "tools/list", JSONObject().put("cursor", "c1")))
        assertEquals("2.0", obj.getString("jsonrpc"))
        assertEquals(7L, obj.getLong("id"))
        assertEquals("tools/list", obj.getString("method"))
        assertEquals("c1", obj.getJSONObject("params").getString("cursor"))
    }

    @Test
    fun `encodeRequest 在 params 为 null 时不写 params 键`() {
        val obj = JSONObject(encodeRequest(1L, "initialize", null))
        assertTrue(obj.has("jsonrpc"))
        assertTrue(obj.has("id"))
        assertTrue(obj.has("method"))
        assertTrue("params 为 null 时不该写这个键", !obj.has("params"))
    }

    @Test
    fun `encodeNotification 不带 id`() {
        val obj = JSONObject(encodeNotification("notifications/initialized", JSONObject()))
        assertEquals("2.0", obj.getString("jsonrpc"))
        assertEquals("notifications/initialized", obj.getString("method"))
        assertTrue("通知不该带 id", !obj.has("id"))
    }

    // ---- 解析 ----

    @Test
    fun `解析合法 result`() {
        val message = result("""{"jsonrpc":"2.0","id":12,"result":{"tools":[]}}""")
        assertEquals(12L, message.id)
        assertTrue(message.result.has("tools"))
    }

    @Test
    fun `解析带 code 的 error`() {
        val message = failure("""{"jsonrpc":"2.0","id":9,"error":{"code":-32601,"message":"method not found"}}""")
        assertEquals(9L, message.id)
        assertEquals(-32601, message.code)
        assertEquals("method not found", message.message)
    }

    @Test
    fun `解析没有 code 的 error`() {
        val message = failure("""{"jsonrpc":"2.0","id":3,"error":{"message":"boom"}}""")
        assertNull(message.code)
        assertEquals("boom", message.message)
    }

    @Test
    fun `error 的 code 不是数字时依然可读`() {
        val message = failure("""{"jsonrpc":"2.0","id":4,"error":{"code":"E_FAIL","message":"nope"}}""")
        assertNull(message.code)
        assertEquals("nope", message.message)
    }

    @Test
    fun `error 没有 message 时给中文兜底`() {
        val message = failure("""{"jsonrpc":"2.0","id":5,"error":{"code":-32000}}""")
        assertEquals(-32000, message.code)
        assertEquals("服务器返回了未说明的错误", message.message)
    }

    @Test
    fun `error 的 message 是空串时也给中文兜底`() {
        assertEquals("服务器返回了未说明的错误", failure("""{"error":{"message":"   "}}""").message)
    }

    @Test
    fun `同时有 error 与 result 时按 error 处理`() {
        assertEquals(-1, failure("""{"id":6,"error":{"code":-1,"message":"x"},"result":{}}""").code)
    }

    @Test
    fun `解析通知`() {
        val message = notification(
            """{"jsonrpc":"2.0","method":"notifications/message","params":{"level":"info"}}""",
        )
        assertEquals("notifications/message", message.method)
        assertEquals("info", message.params?.getString("level"))
    }

    @Test
    fun `没有 params 的通知也能解析`() {
        val message = notification("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
        assertNull(message.params)
    }

    @Test
    fun `id 不是数字时按缺失处理`() {
        assertNull(result("""{"jsonrpc":"2.0","id":"abc","result":{}}""").id)
    }

    @Test
    fun `id 是 null 时按缺失处理`() {
        assertNull(result("""{"jsonrpc":"2.0","id":null,"result":{}}""").id)
    }

    @Test
    fun `非 JSON 输入返回 Malformed 而不抛异常`() {
        assertTrue(malformed("这不是 JSON").reason.isNotEmpty())
    }

    @Test
    fun `空体返回 Malformed`() {
        assertEquals("内容是空的", malformed("   \n ").reason)
    }

    @Test
    fun `result 是数组时算 Malformed`() {
        assertEquals("result 不是对象", malformed("""{"jsonrpc":"2.0","id":1,"result":[1,2,3]}""").reason)
    }

    @Test
    fun `result 是字符串时算 Malformed`() {
        assertEquals("result 不是对象", malformed("""{"jsonrpc":"2.0","id":1,"result":"ok"}""").reason)
    }

    @Test
    fun `既没有 result 也没有 error 也没有 method 时算 Malformed`() {
        assertTrue(malformed("""{"jsonrpc":"2.0","id":1}""").reason.isNotEmpty())
    }

    @Test
    fun `JSON 数组整体也是 Malformed`() {
        assertTrue(malformed("""[{"id":1}]""").reason.isNotEmpty())
    }

    // ---- SSE ----

    @Test
    fun `拆出多个 SSE 事件`() {
        val body = "event: message\ndata: {\"id\":1}\n\nevent: message\ndata: {\"id\":2}\n\n"
        assertEquals(listOf("""{"id":1}""", """{"id":2}"""), extractSseEvents(body))
    }

    @Test
    fun `多行 data 用换行拼接`() {
        assertEquals(listOf("{\"a\":\n1}"), extractSseEvents("data: {\"a\":\ndata: 1}\n\n"))
    }

    @Test
    fun `末尾没有空行的事件也要收`() {
        assertEquals(
            listOf("""{"id":1}""", """{"id":2}"""),
            extractSseEvents("data: {\"id\":1}\n\ndata: {\"id\":2}"),
        )
    }

    @Test
    fun `CRLF 换行也能正确拆分`() {
        val body = "event: message\r\ndata: {\"id\":1}\r\n\r\ndata: {\"id\":2}\r\n\r\n"
        assertEquals(listOf("""{"id":1}""", """{"id":2}"""), extractSseEvents(body))
    }

    @Test
    fun `忽略事件名 id 重试与注释行`() {
        val body = ": 心跳\nid: 42\nretry: 3000\nevent: message\ndata: {\"id\":1}\n\n: 又一条注释\n"
        assertEquals(listOf("""{"id":1}"""), extractSseEvents(body))
    }

    @Test
    fun `只有注释时没有事件`() {
        assertEquals(emptyList<String>(), extractSseEvents(": ping\n: ping\n"))
    }

    @Test
    fun `空体没有事件`() {
        assertEquals(emptyList<String>(), extractSseEvents(""))
    }

    @Test
    fun `非 JSON 的 data 原样返回交给调用方判定`() {
        assertEquals(listOf("[DONE]"), extractSseEvents("data: [DONE]\n\n"))
    }

    @Test
    fun `data 冒号后没有空格也能解析`() {
        assertEquals(listOf("""{"id":1}"""), extractSseEvents("data:{\"id\":1}\n\n"))
    }

    @Test
    fun `没有冒号的裸 data 行按空值处理`() {
        assertEquals(listOf(""), extractSseEvents("data\n\n"))
    }
}
