package com.androidmcphub.app.capability

import com.androidmcphub.app.aggregation.HubCommands
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * **V1 性能专项 5.5**：聚合命令不得在 WebSocket 读线程上执行。
 *
 * 修复前的因果链（实测）：`PhoneLinkClient.onMessage` 在 OkHttp 读线程上调用
 * `PhoneLink.onTextFrame` → `CapabilityDispatcher.handle` → `McpHubRouter.route`
 * → 串行的第三方 `connect()`。在这整段时间里读线程读不了下一帧，于是并发的
 * `phone_ping` 也一起超时 —— 不是" ping 变慢了"，是**读循环停了**。
 *
 * 这里钉住三件事：
 *  1. `handle` 对聚合命令立刻返回（`deferred = true`），不等待远端；
 *  2. 结果稍后经 [CapabilityDispatcher.deferredSink] 交付，且带着正确的结果帧；
 *  3. 去重仍然是**同步**判定的 —— 异步化不能把重放窗口打开。
 */
class HubDeferredDispatchTest {

    @Test
    fun `聚合命令立刻返回，结果异步交付`() {
        val router = SlowRouter(delayMs = 400)
        val workers = Executors.newFixedThreadPool(2) { Thread(it, "hub-work-test").apply { isDaemon = true } }
        val results = Collections.synchronizedList(mutableListOf<ToolDispatchOutcome>())
        val delivered = CountDownLatch(1)
        val dispatcher = CapabilityDispatcher(
            capabilities = emptyList(),
            hubCommands = router,
            hubExecutor = workers,
        ).also { d ->
            d.deferredSink = { outcome ->
                results += outcome
                delivered.countDown()
            }
        }

        val startedAt = System.nanoTime()
        val accepted = dispatcher.handle("req-1", HubCommands.LIST_TOOLS, JSONObject())
        val elapsed = (System.nanoTime() - startedAt) / 1_000_000

        // 读线程上的这一段必须立刻结束：这里只是"受理"。
        assertTrue("handle 不应该等待聚合命令，实测 ${elapsed}ms", elapsed < 100)
        assertTrue(accepted.deferred)
        assertFalse(accepted.ok)
        assertFalse(accepted.duplicate)
        assertNull(accepted.frame)

        assertTrue("异步结果没有回来", delivered.await(5, TimeUnit.SECONDS))
        val outcome = results.single()
        assertEquals("req-1", outcome.requestId)
        assertTrue(outcome.ok)
        assertFalse(outcome.deferred)
        assertNotNull(outcome.frame)
        assertTrue(outcome.frame!!.contains("req-1"))
        assertEquals(1, router.calls.get())

        workers.shutdownNow()
    }

    @Test
    fun `并发的重复 request_id 只执行一次`() {
        val router = SlowRouter(delayMs = 200)
        val workers = Executors.newFixedThreadPool(2) { Thread(it, "hub-work-test").apply { isDaemon = true } }
        val delivered = CountDownLatch(1)
        val dispatcher = CapabilityDispatcher(
            capabilities = emptyList(),
            hubCommands = router,
            hubExecutor = workers,
        ).also { d -> d.deferredSink = { delivered.countDown() } }

        val first = dispatcher.handle("req-dup", HubCommands.LIST_TOOLS, JSONObject())
        val second = dispatcher.handle("req-dup", HubCommands.LIST_TOOLS, JSONObject())

        assertTrue(first.deferred)
        // 第二次必须在**第一次受理的那一刻**就被判重，而不是等结果回来。
        assertTrue(second.duplicate)
        assertNull(second.frame)

        assertTrue(delivered.await(5, TimeUnit.SECONDS))
        Thread.sleep(100)
        assertEquals(1, router.calls.get())
        workers.shutdownNow()
    }

    @Test
    fun `没有异步出口时退回同步执行`() {
        val router = SlowRouter(delayMs = 0)
        val workers = Executors.newFixedThreadPool(1) { Thread(it, "hub-work-test").apply { isDaemon = true } }
        // 刻意**不**设置 deferredSink：宁可慢一点，也不能把结果丢掉。
        val dispatcher = CapabilityDispatcher(
            capabilities = emptyList(),
            hubCommands = router,
            hubExecutor = workers,
        )

        val outcome = dispatcher.handle("req-sync", HubCommands.LIST_TOOLS, JSONObject())

        assertFalse(outcome.deferred)
        assertTrue(outcome.ok)
        assertNotNull(outcome.frame)
        assertEquals(1, router.calls.get())
        workers.shutdownNow()
    }

    @Test
    fun `本机能力不受影响，仍在调用线程上同步执行`() {
        val router = SlowRouter(delayMs = 0)
        val workers = Executors.newFixedThreadPool(1) { Thread(it, "hub-work-test").apply { isDaemon = true } }
        val dispatcher = CapabilityDispatcher(
            capabilities = listOf(EchoCapability),
            hubCommands = router,
            hubExecutor = workers,
        )
        dispatcher.deferredSink = { error("本机能力不该走异步路径") }

        val outcome = dispatcher.handle("req-echo", "echo_probe", JSONObject())

        assertFalse(outcome.deferred)
        assertTrue(outcome.ok)
        assertEquals(0, router.calls.get())
        workers.shutdownNow()
    }

    /** 一个"很慢的"聚合路由器：模拟串行的第三方网络 I/O。 */
    private class SlowRouter(private val delayMs: Long) : HubCommandRouter {
        val calls = AtomicInteger()

        override fun handles(toolName: String): Boolean = HubCommands.isHubCommand(toolName)

        override fun route(toolName: String, arguments: JSONObject): CapabilityResult =
            routeWithId("unknown", toolName, arguments)

        override fun routeWithId(requestId: String, toolName: String, arguments: JSONObject): CapabilityResult {
            calls.incrementAndGet()
            if (delayMs > 0) Thread.sleep(delayMs)
            return CapabilityResult.Success(
                JSONObject().put("ok", true).put("request_id_seen", requestId),
            )
        }
    }

    private object EchoCapability : Capability {
        override val name: String = "echo_probe"
        override val metadata: CapabilityMetadata = CapabilityMetadata(
            name = name,
            displayName = name,
            description = "测试用回显能力",
            category = CapabilityCategory.Other,
        )
        override val allowedArguments: Set<String> = emptySet()
        override fun execute(arguments: JSONObject): CapabilityResult =
            CapabilityResult.Success(JSONObject().put("echo", "ok"))
    }
}
