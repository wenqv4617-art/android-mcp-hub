package com.androidmcphub.app.protocol

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gate 2 工作包 D：`tool_request` 的解析与 `tool_result` 的编码。
 *
 * 这里锁的是"手机的字节 ↔ 服务端的对象"之间的那一层。两侧一开始就不是同一门语言
 * （Kotlin/org.json 对 TypeScript），所以这一层只能靠**断言形状**来保证一致：
 * 字段名、必需性、以及"什么时候必须拒绝"。
 */
class ToolChannelProtocolTest {

    // ---- 解析 --------------------------------------------------------------

    @Test
    fun `解析一个完整的 tool_request`() {
        val raw = """
            {"type":"tool_request","request_id":"tr-1","tool_name":"battery_get_status",
             "arguments":{"foo":"bar"}}
        """.trimIndent()

        val frame = parseIncoming(raw)

        assertTrue("应当是 ToolRequest，实际 $frame", frame is IncomingFrame.ToolRequest)
        val request = frame as IncomingFrame.ToolRequest
        assertEquals("tr-1", request.requestId)
        assertEquals("battery_get_status", request.toolName)
        assertEquals("bar", request.arguments.getString("foo"))
    }

    @Test
    fun `缺 arguments 字段等价于没有参数`() {
        val request = parseIncoming("""{"type":"tool_request","request_id":"tr-2","tool_name":"t"}""")

        assertTrue(request is IncomingFrame.ToolRequest)
        assertEquals(0, (request as IncomingFrame.ToolRequest).arguments.length())
    }

    @Test
    fun `arguments 为 null 同样等价于没有参数`() {
        val request = parseIncoming(
            """{"type":"tool_request","request_id":"tr-3","tool_name":"t","arguments":null}""",
        )

        assertTrue(request is IncomingFrame.ToolRequest)
        assertEquals(0, (request as IncomingFrame.ToolRequest).arguments.length())
    }

    @Test
    fun `arguments 不是对象时属于格式错误，绝不退化成空参数`() {
        // 这是本文件里最重要的一条：用空参数去执行一个本该带参数的工具，
        // 会产出一个"看起来成功但其实答错了"的结果 —— 比明确失败更糟。
        for (bad in listOf("""["a","b"]""", """"text"""", "42", "true")) {
            val frame = parseIncoming(
                """{"type":"tool_request","request_id":"tr-4","tool_name":"t","arguments":$bad}""",
            )

            assertTrue("arguments=$bad 应当是 Unknown，实际 $frame", frame is IncomingFrame.Unknown)
            assertEquals(UnknownReason.BAD_ARGUMENTS, (frame as IncomingFrame.Unknown).reason)
        }
    }

    @Test
    fun `缺 tool_name 或 tool_name 为空 → Unknown`() {
        val missing = parseIncoming("""{"type":"tool_request","request_id":"tr-5","arguments":{}}""")
        val empty = parseIncoming("""{"type":"tool_request","request_id":"tr-6","tool_name":"","arguments":{}}""")

        assertEquals(UnknownReason.MISSING_TOOL_NAME, (missing as IncomingFrame.Unknown).reason)
        assertEquals(UnknownReason.MISSING_TOOL_NAME, (empty as IncomingFrame.Unknown).reason)
    }

    @Test
    fun `缺 request_id 的 tool_request 无法回包，只能忽略`() {
        val frame = parseIncoming("""{"type":"tool_request","tool_name":"battery_get_status","arguments":{}}""")

        assertTrue(frame is IncomingFrame.Unknown)
        assertEquals(UnknownReason.MISSING_REQUEST_ID, (frame as IncomingFrame.Unknown).reason)
    }

    @Test
    fun `未知 type 仍然只记类型名，不记原文（向前兼容）`() {
        val frame = parseIncoming("""{"type":"some_future_frame","request_id":"x"}""")

        assertTrue(frame is IncomingFrame.Unknown)
        assertEquals(UnknownReason.UNSUPPORTED_TYPE, (frame as IncomingFrame.Unknown).reason)
        assertEquals("some_future_frame", frame.type)
    }

    // ---- 编码 --------------------------------------------------------------

    @Test
    fun `成功帧带 result 且不带 error`() {
        val obj = JSONObject(
            encodeToolResult(
                requestId = "tr-1",
                ok = true,
                result = JSONObject().put("battery_level_percent", 42),
            ),
        )

        assertEquals("tool_result", obj.getString("type"))
        assertEquals("tr-1", obj.getString("request_id"))
        assertTrue(obj.getBoolean("ok"))
        assertEquals(42, obj.getJSONObject("result").getInt("battery_level_percent"))
        assertFalse("成功帧不允许带 error", obj.has("error"))
    }

    @Test
    fun `失败帧带 error 且不带 result`() {
        val obj = JSONObject(
            encodeToolResult(
                requestId = "tr-2",
                ok = false,
                errorCode = ToolResultErrorCode.UNSUPPORTED_TOOL,
                errorMessage = "这台手机不支持该工具",
            ),
        )

        assertFalse(obj.getBoolean("ok"))
        assertFalse("失败帧不允许带 result", obj.has("result"))
        assertEquals("unsupported_tool", obj.getJSONObject("error").getString("code"))
        assertEquals("这台手机不支持该工具", obj.getJSONObject("error").getString("message"))
    }

    @Test
    fun `认不出来的错误码在本地就收敛成 tool_failed`() {
        val obj = JSONObject(
            encodeToolResult(requestId = "tr-3", ok = false, errorCode = "some_future_code", errorMessage = "x"),
        )

        assertEquals("tool_failed", obj.getJSONObject("error").getString("code"))
    }

    @Test
    fun `失败帧一定有非空 message（不能出现没有理由的失败）`() {
        val emptyMessage = JSONObject(encodeToolResult(requestId = "tr-4", ok = false, errorCode = "tool_failed"))
        val blankMessage = JSONObject(
            encodeToolResult(requestId = "tr-5", ok = false, errorCode = "tool_failed", errorMessage = ""),
        )

        assertTrue(emptyMessage.getJSONObject("error").getString("message").isNotEmpty())
        assertTrue(blankMessage.getJSONObject("error").getString("message").isNotEmpty())
    }

    @Test
    fun `request_id 逐字符原样回传（含引号、换行、反斜杠）`() {
        val tricky = "tr-\"q\"-\n-\u4e2d\u6587-\\"

        val ok = JSONObject(encodeToolResult(requestId = tricky, ok = true))
        val bad = JSONObject(encodeToolResult(requestId = tricky, ok = false, errorCode = "tool_failed"))

        assertEquals(tricky, ok.getString("request_id"))
        assertEquals(tricky, bad.getString("request_id"))
    }

    @Test
    fun `成功的 tool_result 能被服务端的校验规则接受（形状自检）`() {
        // 服务端要求：ok 是布尔、成功帧 result 是对象、失败帧 error.code 非空。
        val okObj = JSONObject(encodeToolResult(requestId = "tr-6", ok = true))
        assertTrue(okObj.get("ok") is Boolean)
        assertTrue(okObj.get("result") is JSONObject)

        val badObj = JSONObject(
            encodeToolResult(requestId = "tr-7", ok = false, errorCode = "tool_failed", errorMessage = "x"),
        )
        assertTrue(badObj.get("error") is JSONObject)
        assertTrue(badObj.getJSONObject("error").getString("code").isNotEmpty())
    }

    // ---- 闭集 --------------------------------------------------------------

    @Test
    fun `工具失败码闭集与服务端一致`() {
        // 与 worker/src/phone/messages.ts 的 TOOL_RESULT_ERROR_CODES 逐项相同。
        // 多一个或少一个都意味着两侧对"失败"的词汇表开始分叉。
        assertEquals(
            setOf("unsupported_tool", "invalid_arguments", "tool_failed", "tool_unavailable", "not_permitted"),
            ToolResultErrorCode.ALL,
        )
    }

    @Test
    fun `服务端错误码闭集包含 Gate 2 新增的 device_revoked`() {
        assertTrue(ServerErrorCode.ALL.contains(ServerErrorCode.DEVICE_REVOKED))
        assertEquals("device_revoked", ServerErrorCode.normalize("device_revoked"))
        // 认不出来的 code 依旧收敛，不记原文。
        assertEquals(ServerErrorCode.UNRECOGNIZED, ServerErrorCode.normalize("device_revoked_v2"))
    }
}
