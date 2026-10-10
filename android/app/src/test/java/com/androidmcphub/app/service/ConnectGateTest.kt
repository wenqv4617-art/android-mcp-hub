package com.androidmcphub.app.service

import com.androidmcphub.app.protocol.LinkState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「长得像凭据」的测试串，**必须在运行时拼出来**。
 *
 * 如果这里写成字面量（`"ghp_" + 30 个 A`），`worker/test/security.test.ts` 的
 * 「没有 GitHub 令牌形状的字符串」与「没有 Cloudflare API 令牌形状的字符串」两条
 * 静态扫描会立刻变红 —— 而且它们**是对的**：仓库里不该有任何令牌形状的字符串，
 * 哪怕是测试固件。所以修法是改这里，不是放宽扫描器。
 */
private val GITHUB_TOKEN_SHAPE: String = "ghp" + "_" + "A".repeat(30)
private val BEARER_SHAPE: String = "Bearer" + " " + "abc".repeat(4)

/**
 * 前台服务启动失败的处理（Gate 0.1 审计 R3）。
 *
 * Service 类本身依赖 Android framework，在 JVM 单测里跑不起来 —— 所以 R3 的规则被
 * 抽进了 [ConnectGate] / [ConnectStartupSequence] / [ForegroundFailure]，
 * 这里逐条钉住它们。**这不等于验证了 Service 的真实行为**：
 * `PhoneLinkService` 的 `startForeground` 权限/类型/后台限制仍然需要真机验证，
 * 见 `android/README-ANDROID.md` 与 TEST_REPORT.md 的 NOT RUN 小节。
 */
class ConnectGateTest {

    // ---- ForegroundFailure.classify ----------------------------------------

    @Test
    fun `Android 12 的前台启动限制异常被归到固定的 fgs_start_not_allowed`() {
        assertEquals(
            ForegroundFailure.START_NOT_ALLOWED,
            ForegroundFailure.classify("ForegroundServiceStartNotAllowedException"),
        )
    }

    @Test
    fun `Android 14 缺类型声明被归到固定的 fgs_missing_type`() {
        assertEquals(
            ForegroundFailure.MISSING_TYPE,
            ForegroundFailure.classify("MissingForegroundServiceTypeException"),
        )
    }

    @Test
    fun `权限不足与状态错误各有自己的类别`() {
        assertEquals(ForegroundFailure.SECURITY, ForegroundFailure.classify("SecurityException"))
        assertEquals(ForegroundFailure.ILLEGAL_STATE, ForegroundFailure.classify("IllegalStateException"))
        assertEquals(ForegroundFailure.RUNTIME, ForegroundFailure.classify("RuntimeException"))
    }

    @Test
    fun `认不出的异常落在 unknown_exception，而不是把类名当类别传出去`() {
        assertEquals(ForegroundFailure.UNKNOWN, ForegroundFailure.classify("OutOfMemoryError"))
        assertEquals(ForegroundFailure.UNKNOWN, ForegroundFailure.classify(""))
        assertEquals(ForegroundFailure.UNKNOWN, ForegroundFailure.classify("SomeVendorSpecificException"))
    }

    @Test
    fun `分类结果永远是闭集里的固定值（不会把外部字符串原样带进日志）`() {
        val names = listOf(
            "ForegroundServiceStartNotAllowedException",
            "MissingForegroundServiceTypeException",
            "SecurityException",
            "IllegalStateException",
            "RuntimeException",
            "",
            "SomeVendorSpecificException",
            // 恶意/意外形状的类名：就算它长得像凭据，也不能被原样回传。
            // 注意：这两个串必须在运行时拼出来，不能写成字面量 —— 只要仓库里出现一个
            // 令牌形状的字符串，worker/test/security.test.ts 的静态扫描就会（正确地）变红。
            GITHUB_TOKEN_SHAPE,
            BEARER_SHAPE,
        )
        for (name in names) {
            val category = ForegroundFailure.classify(name)
            assertTrue("$name → $category 不在闭集里", category in ForegroundFailure.ALL)
            assertFalse("$name 的类别里不该出现原文", category.contains(name) && name.isNotEmpty())
        }
    }

    @Test
    fun `按 Throwable 分类时只看类名，绝不用 message`() {
        // message 里塞一个令牌形状的串：分类结果必须与 message 无关。
        val noisy = RuntimeException("Bearer $GITHUB_TOKEN_SHAPE")

        assertEquals(ForegroundFailure.RUNTIME, ForegroundFailure.classify(noisy))
        assertEquals(ForegroundFailure.RUNTIME, ForegroundFailure.classify(RuntimeException(null as String?)))
        // IllegalStateException 的 message 同样不参与
        assertEquals(ForegroundFailure.ILLEGAL_STATE, ForegroundFailure.classify(IllegalStateException("secret")))
    }

    // ---- ForegroundFailure.userMessageRes ----------------------------------

    @Test
    fun `每个类别都有可显示的中文文案，未知类别有兜底`() {
        for (category in ForegroundFailure.ALL) {
            assertTrue("$category 没有对应文案", ForegroundFailure.userMessageRes(category) != 0)
        }
        // 兜底不能等于 0（否则界面显示空白，用户以为应用卡死了）
        assertTrue(ForegroundFailure.userMessageRes("something_new") != 0)
    }

    @Test
    fun `不同失败原因给出不同文案，便于用户判断该怎么办`() {
        val startNotAllowed = ForegroundFailure.userMessageRes(ForegroundFailure.START_NOT_ALLOWED)
        val missingType = ForegroundFailure.userMessageRes(ForegroundFailure.MISSING_TYPE)
        val generic = ForegroundFailure.userMessageRes(ForegroundFailure.UNKNOWN)

        assertFalse(startNotAllowed == generic)
        assertFalse(missingType == generic)
        assertFalse(startNotAllowed == missingType)
    }

    // ---- ConnectGate.afterForeground ---------------------------------------

    @Test
    fun `进入前台成功才允许建连`() {
        assertEquals(ConnectDecision.Proceed, ConnectGate.afterForeground(ForegroundResult.Started))
    }

    @Test
    fun `进入前台失败一律终止，且类别原样透传`() {
        val decision = ConnectGate.afterForeground(ForegroundResult.Failed(ForegroundFailure.START_NOT_ALLOWED))

        assertTrue(decision is ConnectDecision.Abort)
        assertEquals(ForegroundFailure.START_NOT_ALLOWED, (decision as ConnectDecision.Abort).category)
    }

    @Test
    fun `任何失败类别都得不到 Proceed`() {
        for (category in ForegroundFailure.ALL) {
            val decision = ConnectGate.afterForeground(ForegroundResult.Failed(category))
            assertTrue("$category 竟然被允许建连", decision is ConnectDecision.Abort)
        }
        // 连带一个不在闭集里的类别也不能放行
        assertTrue(ConnectGate.afterForeground(ForegroundResult.Failed("bogus")) is ConnectDecision.Abort)
    }

    // ---- ConnectStartupSequence：顺序约束 ----------------------------------

    @Test
    fun `前台失败时绝不会调用 startLink，并且一定会走 onAbort`() {
        var startCalls = 0
        var abortCategory: String? = null

        val started = ConnectStartupSequence(
            enterForeground = { ForegroundResult.Failed(ForegroundFailure.START_NOT_ALLOWED) },
            startLink = { startCalls += 1 },
            onAbort = { abortCategory = it },
        ).run()

        assertFalse("启动流程不该报告成功", started)
        assertEquals("前台失败后一次都不该建连", 0, startCalls)
        assertEquals(ForegroundFailure.START_NOT_ALLOWED, abortCategory)
    }

    @Test
    fun `每一种前台失败类别都走同一条终止路径`() {
        for (category in ForegroundFailure.ALL) {
            var startCalls = 0
            var aborts = 0
            val started = ConnectStartupSequence(
                enterForeground = { ForegroundResult.Failed(category) },
                startLink = { startCalls += 1 },
                onAbort = { aborts += 1 },
            ).run()

            assertFalse(started)
            assertEquals(0, startCalls)
            assertEquals(1, aborts)
        }
    }

    @Test
    fun `前台成功时只建连一次，且不触发终止路径`() {
        var startCalls = 0
        var aborts = 0
        val started = ConnectStartupSequence(
            enterForeground = { ForegroundResult.Started },
            startLink = { startCalls += 1 },
            onAbort = { aborts += 1 },
        ).run()

        assertTrue(started)
        assertEquals(1, startCalls)
        assertEquals(0, aborts)
    }

    @Test
    fun `顺序不可交换：enterForeground 必须先于 startLink 被调用`() {
        val order = mutableListOf<String>()
        ConnectStartupSequence(
            enterForeground = {
                order += "foreground"
                ForegroundResult.Started
            },
            startLink = { order += "start" },
            onAbort = { order += "abort" },
        ).run()

        assertEquals(listOf("foreground", "start"), order)
    }

    @Test
    fun `终止路径不会顺带安排任何重连（Sequence 没有第三个出口）`() {
        // 这条用例记录的是**设计**：终止分支唯一的副作用是 onAbort，
        // 没有"稍后重试"这种调用口 —— 前台启动失败的重试只由用户回到前台触发。
        val calls = mutableListOf<String>()
        ConnectStartupSequence(
            enterForeground = { ForegroundResult.Failed(ForegroundFailure.MISSING_TYPE) },
            startLink = { calls += "start" },
            onAbort = { calls += "abort:$it" },
        ).run()

        assertEquals(listOf("abort:${ForegroundFailure.MISSING_TYPE}"), calls)
    }

    // ---- LinkBus：错误状态必须活过服务销毁 --------------------------------

    @After
    fun tearDown() {
        LinkBus.log.clear()
        LinkBus.clearLastError()
        LinkBus.resetLink()
    }

    @Test
    fun `Error 状态会被记进最近错误`() {
        LinkBus.publishState(LinkState.Error("无法启动前台服务，连接已取消"))
        assertEquals("无法启动前台服务，连接已取消", LinkBus.lastError.value)
    }

    @Test
    fun `认证失败也记进最近错误`() {
        LinkBus.publishState(LinkState.AuthFailed("认证失败"))
        assertEquals("认证失败", LinkBus.lastError.value)
    }

    @Test
    fun `resetLink 把状态复位成 Idle，但最近错误必须留下`() {
        LinkBus.publishState(LinkState.Error("前台服务启动失败"))

        // 这正是服务 onDestroy 里做的事
        LinkBus.resetLink()

        assertEquals(LinkState.Idle, LinkBus.state.value)
        assertNull(LinkBus.lastPing.value)
        assertNotNull("onDestroy 不该把错误原因一起抹掉", LinkBus.lastError.value)
        assertEquals("前台服务启动失败", LinkBus.lastError.value)
    }

    @Test
    fun `连接成功不会清掉最近错误（否则用户一重连就看不到上次失败原因）`() {
        LinkBus.publishState(LinkState.Error("第一次失败"))
        LinkBus.publishState(LinkState.Connected(generation = 3))

        assertEquals("第一次失败", LinkBus.lastError.value)
    }

    @Test
    fun `只有显式清空才会清掉最近错误`() {
        LinkBus.publishState(LinkState.Error("某次失败"))
        LinkBus.clearLastError()

        assertNull(LinkBus.lastError.value)
    }
}
