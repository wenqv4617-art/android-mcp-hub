package com.androidmcphub.app.capability

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gate 2 工作包 D：能力分发器。
 *
 * 这是手机侧**唯一**一条"外部请求 → 本地能力"的路径，所以这个文件里最重要的
 * 不是"能跑通"，而是**不能跑通的那些情况**：不在白名单里的工具、多出来的参数、
 * 抛异常的能力、企图污染信封的结果。
 */
class CapabilityDispatcherTest {

    /** 一个可控的假能力：记录被调用了多少次、收到了什么参数。 */
    private class FakeCapability(
        override val name: String,
        override val allowedArguments: Set<String> = emptySet(),
        private val payload: JSONObject = JSONObject().put("value", "ok"),
        private val failure: CapabilityResult.Failure? = null,
        private val throws: Throwable? = null,
        private val validateProblem: String? = null,
    ) : Capability {

        /** 注册清单要求每个能力都带元数据；假能力给一份最小可用的。 */
        override val metadata: CapabilityMetadata = CapabilityMetadata(
            name = name,
            displayName = name,
            description = "测试用的假能力",
            category = CapabilityCategory.Other,
        )

        var calls: Int = 0
            private set

        var lastArguments: JSONObject? = null
            private set

        override fun validate(arguments: JSONObject): String? = validateProblem

        override fun execute(arguments: JSONObject): CapabilityResult {
            calls += 1
            lastArguments = arguments
            throws?.let { throw it }
            failure?.let { return it }
            return CapabilityResult.Success(payload)
        }
    }

    private fun frame(requestId: String): JSONObject =
        JSONObject(dispatcher.handle(requestId, "battery_get_status", JSONObject()).frame!!)

    private val battery = FakeCapability(
        name = "battery_get_status",
        payload = JSONObject().put("battery_level_percent", 42).put("charging", false),
    )

    private val dispatcher = CapabilityDispatcher(
        capabilities = listOf(battery, FakeCapability(name = "echo", allowedArguments = setOf("text"))),
        clock = { 1_000L },
    )

    // ---- 正常路径 ----------------------------------------------------------

    @Test
    fun `白名单里的工具被执行的完整结果`() {
        val outcome = dispatcher.handle("tr-1", "battery_get_status", JSONObject())

        assertFalse("不该是重复", outcome.duplicate)
        assertTrue("应当成功", outcome.ok)
        assertNull(outcome.errorCode)
        assertEquals("battery_get_status", outcome.toolName)
        assertEquals("tr-1", outcome.requestId)
        assertEquals(1, battery.calls)

        val obj = JSONObject(outcome.frame!!)
        assertEquals("tool_result", obj.getString("type"))
        assertEquals("tr-1", obj.getString("request_id"))
        assertTrue(obj.getBoolean("ok"))
        assertEquals(42, obj.getJSONObject("result").getInt("battery_level_percent"))
        assertFalse("成功帧不允许带 error", obj.has("error"))
    }

    @Test
    fun `request_id 原样回传（逐字符相同）`() {
        val tricky = "tr-\"quoted\"-\n-换行-\\反斜杠"
        val outcome = dispatcher.handle(tricky, "battery_get_status", JSONObject())

        assertEquals(tricky, JSONObject(outcome.frame!!).getString("request_id"))
    }

    @Test
    fun `只把白名单里的参数交给能力`() {
        val echoCapability = FakeCapability(name = "echo", allowedArguments = setOf("text"))
        val echo = CapabilityDispatcher(listOf(echoCapability))

        val outcome = echo.handle("tr-2", "echo", JSONObject().put("text", "hello"))

        assertTrue(outcome.ok)
        assertEquals(1, echoCapability.calls)
        assertEquals("hello", echoCapability.lastArguments!!.getString("text"))
        // 假能力的 payload 是固定值，这里只确认它被原样放进 result。
        assertEquals("ok", JSONObject(outcome.frame!!).getJSONObject("result").getString("value"))
    }

    @Test
    fun `能力名列表是有序的且等于注册表`() {
        assertEquals(listOf("battery_get_status", "echo"), dispatcher.names())
        assertEquals(2, dispatcher.size)
    }

    // ---- 白名单之外 --------------------------------------------------------

    @Test
    fun `没有注册的工具一律 unsupported_tool，且不回显对端给的名字`() {
        val outcome = dispatcher.handle("tr-3", "read_any_file", JSONObject())

        assertFalse(outcome.ok)
        assertEquals("unsupported_tool", outcome.errorCode)
        assertEquals(UNKNOWN_TOOL_LABEL, outcome.toolName)

        val obj = JSONObject(outcome.frame!!)
        assertFalse(obj.getBoolean("ok"))
        assertEquals("unsupported_tool", obj.getJSONObject("error").getString("code"))
        // ⚠ 对端给的名字绝不能出现在回帧里 —— 它可能是一段任意文本。
        assertFalse("回帧里不得出现对端给的工具名", outcome.frame!!.contains("read_any_file"))
        assertFalse("也不允许带 result", obj.has("result"))
    }

    @Test
    fun `形状非法的工具名同样收敛成 unsupported_tool，且不进日志`() {
        for (bad in listOf("READ_ANY_FILE", "tool-name-with-dash", "9starts_with_digit", "a".repeat(65), "", "有中文")) {
            val outcome = dispatcher.handle("tr-bad-$bad", bad, JSONObject())

            assertEquals("工具名 $bad 应当被拒", "unsupported_tool", outcome.errorCode)
            assertEquals(UNKNOWN_TOOL_LABEL, outcome.toolName)
        }
    }

    @Test
    fun `空注册表时回 unsupported_tool 且提示没有启用任何能力`() {
        val empty = CapabilityDispatcher(emptyList())
        val outcome = empty.handle("tr-4", "battery_get_status", JSONObject())

        assertEquals("unsupported_tool", outcome.errorCode)
        val message = JSONObject(outcome.frame!!).getJSONObject("error").getString("message")
        assertTrue("提示应当说明本机没有启用能力，实际：$message", message.contains("没有启用"))
    }

    @Test
    fun `多出来的参数被拒，且不执行能力`() {
        val outcome = dispatcher.handle(
            "tr-5",
            "battery_get_status",
            JSONObject().put("package_name", "com.example.evil"),
        )

        assertFalse(outcome.ok)
        assertEquals("invalid_arguments", outcome.errorCode)
        assertEquals("参数不合法时不得执行能力", 0, battery.calls)
        assertFalse("不得回显对端给的参数名", outcome.frame!!.contains("package_name"))
        assertFalse(outcome.frame!!.contains("com.example.evil"))
    }

    @Test
    fun `参数取值校验失败被拒，且不执行能力`() {
        val pickyCapability = FakeCapability(
            name = "echo",
            allowedArguments = setOf("text"),
            validateProblem = "text 不能为空",
        )
        val picky = CapabilityDispatcher(listOf(pickyCapability))

        val outcome = picky.handle("tr-6", "echo", JSONObject().put("text", ""))

        assertEquals("invalid_arguments", outcome.errorCode)
        assertEquals("校验失败时不得执行", 0, pickyCapability.calls)
        assertTrue(JSONObject(outcome.frame!!).getJSONObject("error").getString("message").contains("不能为空"))
    }

    // ---- 执行期异常 --------------------------------------------------------

    @Test
    fun `能力抛异常时回 tool_failed，且不外泄异常原文`() {
        val boom = CapabilityDispatcher(
            listOf(
                FakeCapability(
                    name = "battery_get_status",
                    throws = IllegalStateException("/data/data/com.androidmcphub.app/secret token=abc"),
                ),
            ),
        )
        val outcome = boom.handle("tr-7", "battery_get_status", JSONObject())

        assertFalse(outcome.ok)
        assertEquals("tool_failed", outcome.errorCode)
        assertFalse("异常原文不得出现在回帧里", outcome.frame!!.contains("/data/data"))
        assertFalse(outcome.frame!!.contains("token=abc"))
        assertTrue(outcome.frame!!.contains("IllegalStateException"))
    }

    @Test
    fun `能力自己回的未知错误码被归一成 tool_failed`() {
        val weird = CapabilityDispatcher(
            listOf(FakeCapability(name = "echo", failure = CapabilityResult.Failure("some_future_code", "x"))),
        )
        val outcome = weird.handle("tr-8", "echo", JSONObject())

        assertEquals("tool_failed", outcome.errorCode)
        assertEquals("tool_failed", JSONObject(outcome.frame!!).getJSONObject("error").getString("code"))
    }

    @Test
    fun `能力自己回的失败码与文案被原样转达`() {
        val denied = CapabilityDispatcher(
            listOf(FakeCapability(name = "echo", failure = CapabilityResult.Failure("not_permitted", "用户没有打开这个能力"))),
        )
        val outcome = denied.handle("tr-9", "echo", JSONObject())

        assertEquals("not_permitted", outcome.errorCode)
        val error = JSONObject(outcome.frame!!).getJSONObject("error")
        assertEquals("not_permitted", error.getString("code"))
        assertEquals("用户没有打开这个能力", error.getString("message"))
    }

    // ---- 信封完整性 --------------------------------------------------------

    @Test
    fun `问题5 总开关在两次调用之间翻转，无需重建分发器（即无需重连）`() {
        // 模拟 HubPrefs：一个可变的用户设置，分发器通过 policy lambda 每次现读
        var permitted = false
        val capability = FakeCapability(name = "echo")
        val dispatcher = CapabilityDispatcher(
            listOf(capability),
            policy = { permitted },
        )

        val denied = dispatcher.handle("tr-toggle-1", "echo", JSONObject())
        assertFalse(denied.ok)
        assertEquals("not_permitted", denied.errorCode)
        assertEquals("关着的时候一次都不能执行", 0, capability.calls)

        // 用户在界面上打开开关 —— 同一个 dispatcher、同一条连接
        permitted = true

        val allowed = dispatcher.handle("tr-toggle-2", "echo", JSONObject())
        assertTrue(allowed.ok)
        assertEquals(1, capability.calls)

        // 再关回去同样立刻生效
        permitted = false
        val deniedAgain = dispatcher.handle("tr-toggle-3", "echo", JSONObject())
        assertEquals("not_permitted", deniedAgain.errorCode)
        assertEquals(1, capability.calls)
    }

    @Test
    fun `结果里的信封保留键被剔除（纵深防御）`() {
        val hostile = CapabilityDispatcher(
            listOf(
                FakeCapability(
                    name = "battery_get_status",
                    payload = JSONObject()
                        .put("battery_level_percent", 42)
                        .put("ok", false)
                        .put("request_id", "hijacked")
                        .put("device_id", "another-phone")
                        .put("type", "phone_pong"),
                ),
            ),
        )
        val outcome = hostile.handle("tr-10", "battery_get_status", JSONObject())
        val obj = JSONObject(outcome.frame!!)
        val result = obj.getJSONObject("result")

        assertTrue(obj.getBoolean("ok"))
        assertEquals("tr-10", obj.getString("request_id"))
        assertFalse(result.has("ok"))
        assertFalse(result.has("request_id"))
        assertFalse(result.has("device_id"))
        assertFalse(result.has("type"))
        assertEquals(42, result.getInt("battery_level_percent"))
    }

    @Test
    fun `结果过大时在本机失败，而不是把超大帧发出去`() {
        val huge = CapabilityDispatcher(
            listOf(FakeCapability(name = "echo", payload = JSONObject().put("blob", "x".repeat(1024)))),
            maxResultBytes = 128,
        )
        val outcome = huge.handle("tr-11", "echo", JSONObject())

        assertFalse(outcome.ok)
        assertEquals("tool_failed", outcome.errorCode)
        assertTrue(outcome.frame!!.toByteArray(Charsets.UTF_8).size <= 512)
    }

    // ---- 去重 --------------------------------------------------------------

    @Test
    fun `同一个 request_id 第二次不再执行、也不再回复`() {
        val first = dispatcher.handle("tr-dup", "battery_get_status", JSONObject())
        val callsAfterFirst = battery.calls
        val second = dispatcher.handle("tr-dup", "battery_get_status", JSONObject())

        assertTrue(first.ok)
        assertFalse(first.duplicate)
        assertNotNull(first.frame)

        assertTrue("第二次必须标记为重复", second.duplicate)
        assertNull("重复时不得回帧", second.frame)
        assertEquals("重复时不得再次执行能力", callsAfterFirst, battery.calls)
    }

    @Test
    fun `重复的 request_id 带着不同工具名时也不执行`() {
        dispatcher.handle("tr-dup-2", "battery_get_status", JSONObject())
        val calls = battery.calls
        val second = dispatcher.handle("tr-dup-2", "echo", JSONObject())

        assertTrue(second.duplicate)
        assertNull(second.frame)
        assertEquals(calls, battery.calls)
    }

    @Test
    fun `被拒绝的 request_id 也占位，不会第二次变成可执行`() {
        val firstOutcome = dispatcher.handle("tr-rej", "read_any_file", JSONObject())
        assertEquals("unsupported_tool", firstOutcome.errorCode)

        val second = dispatcher.handle("tr-rej", "battery_get_status", JSONObject())
        assertTrue("同一个 id 被拒过之后也不能再执行别的工具", second.duplicate)
        assertEquals(0, battery.calls)
    }

    @Test
    fun `去重窗口可以清空（用户重新连接时会用到）`() {
        dispatcher.handle("tr-reset", "battery_get_status", JSONObject())
        assertEquals(1, dispatcher.seenCount())

        dispatcher.reset()

        assertEquals(0, dispatcher.seenCount())
        val after = dispatcher.handle("tr-reset", "battery_get_status", JSONObject())
        assertFalse("清空之后同一个 id 不再算重复", after.duplicate)
    }

    @Test
    fun `去重窗口有容量上限，不会无限增长`() {
        val bounded = CapabilityDispatcher(listOf(battery), capacity = 8)
        repeat(50) { bounded.handle("tr-cap-$it", "battery_get_status", JSONObject()) }

        assertTrue("去重窗口必须有界，实际 ${bounded.seenCount()}", bounded.seenCount() <= 8)
    }

    // ---- 注册表自身的约定 --------------------------------------------------

    @Test
    fun `注册同名能力直接失败（配置错误要在构造时就炸）`() {
        val duplicated = listOf(FakeCapability(name = "echo"), FakeCapability(name = "echo"))
        val error = runCatching { CapabilityDispatcher(duplicated) }.exceptionOrNull()

        assertNotNull("同名能力必须在构造时被拒绝", error)
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun `注册名字不合规的能力直接失败`() {
        for (bad in listOf("UPPER", "with-dash", "9leading", "有中文")) {
            val error = runCatching { CapabilityDispatcher(listOf(FakeCapability(name = bad))) }.exceptionOrNull()
            assertNotNull("能力名 $bad 应当被拒绝", error)
        }
    }
}
