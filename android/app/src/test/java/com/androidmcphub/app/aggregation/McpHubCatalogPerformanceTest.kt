package com.androidmcphub.app.aggregation

import com.androidmcphub.app.capability.CapabilityResult
import com.androidmcphub.app.mcp.McpClientException
import com.androidmcphub.app.mcp.McpErrorKind
import com.androidmcphub.app.mcp.McpToolFingerprint
import com.androidmcphub.app.mcp.model.McpServerConfig
import com.androidmcphub.app.mcp.store.FakeSecretBlobStore
import com.androidmcphub.app.mcp.store.InMemoryKeyValueStore
import com.androidmcphub.app.mcp.store.McpAuthorizationStore
import com.androidmcphub.app.mcp.store.McpServerStore
import com.androidmcphub.app.mcp.transport.McpRemoteTool
import com.androidmcphub.app.mcp.transport.McpServerHandshake
import com.androidmcphub.app.mcp.transport.McpToolCallResult
import com.androidmcphub.app.mcp.transport.McpTransport
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * **V1 目录发现超时专项**的回归测试。
 *
 * 与 [McpHubRouterTest] 的分工：
 *  * 那边钉的是**语义**（未授权的东西一个字节都不出去、指纹判定、参数校验）；
 *  * 这里钉的是**时间与恢复**：一次目录请求会不会被某台慢/离线服务器拖住、
 *    缓存什么时候命中、失败之后多久允许重试、并发请求会不会变成重连风暴。
 *
 * 所有耗时断言都留了数量级的余量（"3 秒的挂起必须 500ms 内返回"），因为它们是
 * 用来证明**结构上不等**，不是用来测 JVM 的调度精度的。
 */
class McpHubCatalogPerformanceTest {

    // ==================================================================
    // A. `servers_only`：不连网、不等超时
    // ==================================================================

    @Test
    fun `A1 servers_only 在服务器挂起时不等待`() {
        val h = harness(
            servers = listOf(config("srv_a"), config("srv_b"), config("srv_c")),
            transports = mapOf(
                "srv_a" to ProbeTransport("srv_a", connectDelayMs = 3_000),
                "srv_b" to ProbeTransport("srv_b", connectDelayMs = 3_000),
                "srv_c" to ProbeTransport("srv_c", connectDelayMs = 3_000),
            ),
        )

        val elapsed = timeMs { h.serversOnly() }

        // 修复前：串行 connect，三台各 8 秒上限 —— 这正是实测 13.6 秒的形状。
        assertTrue("servers_only 不应该等待服务器，实测 ${elapsed}ms", elapsed < 500)
        val payload = h.serversOnly()
        assertEquals(3, payload.getJSONArray("servers").length())
        // 没探测过就是 unknown：不编 offline，也不编 online。
        for (index in 0 until 3) {
            val server = payload.getJSONArray("servers").getJSONObject(index)
            assertEquals(HubStates.AVAILABILITY_UNKNOWN, server.getString("availability"))
        }
        assertTrue(payload.getBoolean("refresh_pending"))
        h.close()
    }

    @Test
    fun `A2 冷缓存如实报告 unknown，探测结果下次请求才可见`() {
        val transport = ProbeTransport("srv_a", tools = listOf(tool("t1")))
        val h = harness(servers = listOf(config("srv_a")), transports = mapOf("srv_a" to transport))

        val first = h.serversOnly()
        assertEquals(HubStates.AVAILABILITY_UNKNOWN, first.server(0).getString("availability"))
        assertEquals(HubStates.SOURCE_UNKNOWN, first.server(0).getString("availability_source"))
        assertFalse(first.server(0).has("checked_at_ms"))

        // 等后台探测落地（真线程池）。
        awaitTrue { transport.connects.get() >= 1 }

        val second = h.serversOnly()
        assertEquals(HubStates.AVAILABILITY_ONLINE, second.server(0).getString("availability"))
        assertEquals(HubStates.SOURCE_CACHE, second.server(0).getString("availability_source"))
        assertTrue(second.server(0).has("checked_at_ms"))
        // 探活不枚举：`tools/list` 一次都没被调用。
        assertEquals(0, transport.listCalls.get())
        h.close()
    }

    @Test
    fun `A3 一台服务器离线不拖慢其余服务器`() {
        val fast = ProbeTransport("srv_fast", tools = listOf(tool("fast_tool")))
        val dead = ProbeTransport("srv_dead", connectDelayMs = 5_000, failing = true)
        val h = harness(
            servers = listOf(config("srv_dead"), config("srv_fast")),
            transports = mapOf("srv_dead" to dead, "srv_fast" to fast),
            limits = HubLimits(catalogDeadlineMs = 400),
        )
        h.grantAll("srv_fast", fast.tools)

        val startedAt = System.nanoTime()
        val payload = h.list()
        val elapsed = (System.nanoTime() - startedAt) / 1_000_000

        // 挂着的那台最多吃掉请求的**截止时间**（这里 400ms），
        // 不能吃掉"每台服务器各一整个 8 秒超时"——那正是 13.6 秒的形状。
        assertTrue("目录请求被挂起的服务器拖住了，实测 ${elapsed}ms", elapsed < 1_500)
        assertEquals(1, payload.getJSONArray("tools").length())
        assertEquals("fast_tool", payload.getJSONArray("tools").getJSONObject(0).getString("tool_name"))
        h.close()
    }

    @Test
    fun `A4 失败的服务器进入退避，不会被每次请求重复重连`() {
        val dead = ProbeTransport("srv_a", failing = true)
        val h = harness(servers = listOf(config("srv_a")), transports = mapOf("srv_a" to dead))

        h.list()
        awaitTrue { dead.connects.get() >= 1 }
        val firstConnects = dead.connects.get()

        h.list()
        h.list()
        // 退避窗口内不允许再撞那堵墙（否则每次请求都会多等一次失败）。
        assertEquals(firstConnects, dead.connects.get())

        val offline = h.serversOnly().server(0)
        assertEquals(HubStates.AVAILABILITY_OFFLINE, offline.getString("availability"))

        // 退避结束后允许重试。
        h.now += h.limits.serverFailureBackoffMs + 1
        h.list()
        awaitTrue { dead.connects.get() > firstConnects }
        assertTrue(dead.connects.get() > firstConnects)
        h.close()
    }

    @Test
    fun `A4b servers_only 的 refresh 绕过可用性 TTL，但仍然不等待`() {
        val transport = ProbeTransport("srv_a", tools = listOf(tool("t1")))
        val h = harness(servers = listOf(config("srv_a")), transports = mapOf("srv_a" to transport))

        h.serversOnly()
        awaitTrue { transport.connects.get() >= 1 }
        assertEquals(1, transport.connects.get())

        // 可用性仍然新鲜：普通 servers_only 不再探测。
        h.serversOnly()
        assertEquals(1, transport.connects.get())

        // 手动刷新：发起一次后台探活（结果下一次请求可见），本次响应照样立即返回。
        val elapsed = timeMs { h.serversOnly(refresh = true) }
        assertTrue("servers_only 的 refresh 不应该等待探测，实测 ${elapsed}ms", elapsed < 500)
        awaitTrue { transport.connects.get() >= 2 }
        assertEquals(2, transport.connects.get())
        h.close()
    }

    @Test
    fun `A5 服务器数超过上限时如实标记 skipped`() {
        val h = harness(
            servers = listOf(config("srv_a"), config("srv_b"), config("srv_c")),
            transports = mapOf(
                "srv_a" to ProbeTransport("srv_a"),
                "srv_b" to ProbeTransport("srv_b"),
                "srv_c" to ProbeTransport("srv_c"),
            ),
            limits = HubLimits(maxServers = 2),
        )

        val payload = h.serversOnly()
        assertEquals(3, payload.getJSONArray("servers").length())
        assertEquals(HubStates.SOURCE_SKIPPED, payload.server(2).getString("availability_source"))
        assertEquals(HubStates.AVAILABILITY_UNKNOWN, payload.server(2).getString("availability"))
        h.close()
    }

    // ==================================================================
    // B. 工具目录：分页、缓存、失效
    // ==================================================================

    @Test
    fun `B1 几百个工具的分页元数据如实`() {
        val tools = (1..250).map { tool("t$it") }
        val transport = ProbeTransport("srv_a", tools = tools)
        val h = harness(
            servers = listOf(config("srv_a")),
            transports = mapOf("srv_a" to transport),
            limits = HubLimits(maxTools = 500),
        )
        h.grantAll("srv_a", tools)

        val first = h.list(limit = 20)
        assertEquals(250, first.getInt("total_tools"))
        assertEquals(20, first.getInt("returned"))
        assertEquals(20, first.getJSONArray("tools").length())
        assertTrue(first.getBoolean("has_more"))
        assertEquals(20, first.getInt("next_offset"))

        val second = h.list(offset = 240, limit = 20)
        assertEquals(250, second.getInt("total_tools"))
        assertEquals(10, second.getInt("returned"))
        assertFalse(second.getBoolean("has_more"))
        assertFalse(second.has("next_offset"))
        // 翻页不重新联网：第二页来自同一份缓存。
        assertEquals(1, transport.connects.get())
        h.close()
    }

    @Test
    fun `B1b 80 个工具逐页取完，不缺失不重复且元数据一致`() {
        val tools = (1..80).map { tool("t$it") }
        val transport = ProbeTransport("srv_a", tools = tools)
        val h = harness(
            servers = listOf(config("srv_a")),
            transports = mapOf("srv_a" to transport),
            limits = HubLimits(maxTools = 500),
        )
        h.grantAll("srv_a", tools)

        val pageSize = 10
        val seen = mutableListOf<String>()
        var offset = 0
        var pages = 0
        while (true) {
            val page = h.list(offset = offset, limit = pageSize)
            pages += 1
            assertEquals("每一页的总量都必须是 80", 80, page.getInt("total_tools"))
            assertEquals(offset, page.getInt("offset"))
            assertEquals("refresh_pending 不能在缓存命中时变成 true", false, page.getBoolean("refresh_pending"))
            assertEquals(
                "首页刷新、后续页命中缓存",
                if (pages == 1) HubCatalog.SOURCE_REFRESH else HubCatalog.SOURCE_CACHE,
                page.getString("catalog_source"),
            )
            val entries = page.getJSONArray("tools")
            assertEquals(page.getInt("returned"), entries.length())
            for (i in 0 until entries.length()) {
                seen += entries.getJSONObject(i).getString("tool_name")
            }
            if (!page.getBoolean("has_more")) {
                assertFalse("最后一页不应再给 next_offset", page.has("next_offset"))
                assertFalse("取完时不该标记 truncated", page.getBoolean("truncated"))
                break
            }
            assertEquals(offset + entries.length(), page.getInt("next_offset"))
            offset = page.getInt("next_offset")
            assertTrue("页数不可能超过工具数", pages <= 80)
        }

        assertEquals("页数应为 80 / 10", 8, pages)
        assertEquals("不能有重复", 80, seen.toSet().size)
        assertEquals("不能有缺失", (1..80).map { "t$it" }.toSet(), seen.toSet())
        // 翻页全部来自第一次刷新写下的同一份缓存，不重复联网。
        assertEquals(1, transport.connects.get())
        // 第一页是 refresh，后续页是 cache —— 缓存状态必须随实际来源变化。
        h.close()
    }

    @Test
    fun `B2 limit 只影响这一页，不影响总量`() {
        val tools = (1..30).map { tool("t$it") }
        val h = harness(
            servers = listOf(config("srv_a")),
            transports = mapOf("srv_a" to ProbeTransport("srv_a", tools = tools)),
        )
        h.grantAll("srv_a", tools)

        val page = h.list(limit = 5)
        assertEquals(30, page.getInt("total_tools"))
        assertEquals(5, page.getInt("returned"))
        assertEquals(5, page.getInt("next_offset"))
        assertEquals(5, page.getJSONArray("tools").length())
        h.close()
    }

    @Test
    fun `B3 第二次请求命中缓存，不再联网`() {
        val transport = ProbeTransport("srv_a", tools = listOf(tool("t1")))
        val h = harness(servers = listOf(config("srv_a")), transports = mapOf("srv_a" to transport))
        h.grantAll("srv_a", transport.tools)

        val first = h.list()
        assertEquals(HubCatalog.SOURCE_REFRESH, first.getString("catalog_source"))
        assertEquals(1, transport.connects.get())

        val second = h.list()
        assertEquals(HubCatalog.SOURCE_CACHE, second.getString("catalog_source"))
        assertEquals(1, transport.connects.get())
        assertEquals(1, second.getJSONArray("tools").length())
        assertNotNull(second.opt("catalog_age_ms"))
        assertFalse(second.getBoolean("refresh_pending"))
        h.close()
    }

    @Test
    fun `B4 缓存过期后重新刷新`() {
        val transport = ProbeTransport("srv_a", tools = listOf(tool("t1")))
        val h = harness(servers = listOf(config("srv_a")), transports = mapOf("srv_a" to transport))
        h.grantAll("srv_a", transport.tools)

        h.list()
        assertEquals(1, transport.connects.get())

        h.now += h.limits.catalogTtlMs + 1
        val refreshed = h.list()
        assertEquals(HubCatalog.SOURCE_REFRESH, refreshed.getString("catalog_source"))
        assertEquals(2, transport.connects.get())
        h.close()
    }

    @Test
    fun `B4b 手动 refresh 绕过新鲜缓存并只产生一次连接`() {
        val transport = ProbeTransport("srv_a", tools = listOf(tool("t1")))
        val h = harness(servers = listOf(config("srv_a")), transports = mapOf("srv_a" to transport))
        h.grantAll("srv_a", transport.tools)

        h.list()
        assertEquals(1, transport.connects.get())

        // 缓存仍然新鲜，普通请求命中缓存。
        assertEquals(HubCatalog.SOURCE_CACHE, h.list().getString("catalog_source"))
        assertEquals(1, transport.connects.get())

        // 手动刷新：跳过 TTL，真的重新连一次。
        val refreshed = h.list(refresh = true)
        assertEquals(2, transport.connects.get())
        assertEquals(HubCatalog.SOURCE_REFRESH, refreshed.getString("catalog_source"))
        assertEquals(1, refreshed.getJSONArray("tools").length())
        h.close()
    }

    @Test
    fun `B4c 手动 refresh 绕过失败退避，失败仍然如实返回`() {
        val flaky = ProbeTransport("srv_a", tools = listOf(tool("t1")), failing = true)
        val h = harness(servers = listOf(config("srv_a")), transports = mapOf("srv_a" to flaky))
        h.grantAll("srv_a", flaky.tools)

        assertEquals(0, h.list().getJSONArray("tools").length())
        val firstConnects = flaky.connects.get()
        assertEquals(1, firstConnects)

        // 退避窗口内的普通请求不重连。
        h.list()
        assertEquals(firstConnects, flaky.connects.get())

        // 手动刷新绕过了退避 —— 用户点「刷新」就应该看到这次尝试的结果。
        val forced = h.list(refresh = true)
        assertEquals(firstConnects + 1, flaky.connects.get())
        assertEquals(0, forced.getJSONArray("tools").length())
        assertEquals(HubStates.AVAILABILITY_OFFLINE, forced.server(0).getString("availability"))
        h.close()
    }

    @Test
    fun `B4d 并发的 refresh 请求仍然合并成一次刷新`() {
        val transport = ProbeTransport("srv_a", tools = listOf(tool("t1")), connectDelayMs = 800)
        val h = harness(servers = listOf(config("srv_a")), transports = mapOf("srv_a" to transport))
        h.grantAll("srv_a", transport.tools)
        h.list()  // 预热
        assertEquals(1, transport.connects.get())

        val threads = 4
        val barrier = CyclicBarrier(threads)
        val done = CountDownLatch(threads)
        repeat(threads) {
            Thread {
                try {
                    barrier.await(2, TimeUnit.SECONDS)
                    h.list(refresh = true)
                } finally {
                    done.countDown()
                }
            }.apply { isDaemon = true }.start()
        }
        assertTrue(done.await(10, TimeUnit.SECONDS))
        // 单飞不受 refresh 影响：4 个并发刷新仍只连一次。
        assertEquals(2, transport.connects.get())
        h.close()
    }

    @Test
    fun `B5 服务器地址变化使缓存立即失效`() {
        val transport = ProbeTransport("srv_a", tools = listOf(tool("t1")))
        val transports = Collections.synchronizedMap(mutableMapOf<String, McpTransport>("srv_a" to transport))
        val h = harness(servers = listOf(config("srv_a")), transports = transports)
        h.grantAll("srv_a", transport.tools)

        h.list()
        assertEquals(1, transport.connects.get())

        // 用户把地址改到另一台服务器：旧目录里的工具属于**另一台**，必须立刻作废。
        val moved = ProbeTransport("srv_a", tools = listOf(tool("t2")))
        transports["srv_a"] = moved
        h.serverStore.upsert(config("srv_a", url = "https://elsewhere.example.com/mcp"))
        // 新地址上的工具当然没有被授权过（授权绑定的是 (服务器, 工具, 指纹)）。
        val payload = h.list()
        assertEquals(2, moved.connects.get() + transport.connects.get())
        assertEquals(0, payload.getJSONArray("tools").length())
        assertFalse(payload.toString().contains("t1"))
        h.close()
    }

    @Test
    fun `B6 离线服务器的工具不下发`() {
        val good = ProbeTransport("srv_good", tools = listOf(tool("good")))
        val dead = ProbeTransport("srv_dead", tools = listOf(tool("secret_dead")), failing = true)
        val h = harness(
            servers = listOf(config("srv_good"), config("srv_dead")),
            transports = mapOf("srv_good" to good, "srv_dead" to dead),
        )
        h.grantAll("srv_good", good.tools)
        h.grantAll("srv_dead", dead.tools)

        val payload = h.list()
        assertEquals(1, payload.getJSONArray("tools").length())
        assertEquals("good", payload.getJSONArray("tools").getJSONObject(0).getString("tool_name"))
        assertFalse(payload.toString().contains("secret_dead"))
        h.close()
    }

    @Test
    fun `B7 并发目录请求共用同一次刷新`() {
        val transport = ProbeTransport("srv_a", tools = listOf(tool("t1")), connectDelayMs = 200)
        val h = harness(servers = listOf(config("srv_a")), transports = mapOf("srv_a" to transport))
        h.grantAll("srv_a", transport.tools)

        val threads = 5
        val barrier = CyclicBarrier(threads)
        val done = CountDownLatch(threads)
        val failures = AtomicInteger()
        repeat(threads) {
            Thread {
                try {
                    barrier.await(2, TimeUnit.SECONDS)
                    val payload = h.list()
                    if (payload.getJSONArray("tools").length() != 1) failures.incrementAndGet()
                } catch (t: Throwable) {
                    failures.incrementAndGet()
                } finally {
                    done.countDown()
                }
            }.apply { isDaemon = true }.start()
        }
        assertTrue(done.await(10, TimeUnit.SECONDS))
        assertEquals(0, failures.get())
        // 单飞：5 个并发请求只应产生一次真实连接。
        assertEquals(1, transport.connects.get())
        h.close()
    }

    // ==================================================================
    // C. 安全：缓存不许成为"绕过授权"的旁路
    // ==================================================================

    @Test
    fun `C1 撤销授权后缓存里的工具立即消失且不联网`() {
        val transport = ProbeTransport("srv_a", tools = listOf(tool("t1"), tool("t2")))
        val h = harness(servers = listOf(config("srv_a")), transports = mapOf("srv_a" to transport))
        h.grantAll("srv_a", transport.tools)

        assertEquals(2, h.list().getJSONArray("tools").length())

        h.authStore.revoke("srv_a", "t1")
        val afterRevoke = h.list()
        // 命中缓存（不再联网），但未授权的那条必须立刻消失。
        assertEquals(1, transport.connects.get())
        assertEquals(1, afterRevoke.getJSONArray("tools").length())
        assertEquals("t2", afterRevoke.getJSONArray("tools").getJSONObject(0).getString("tool_name"))
        assertFalse(afterRevoke.toString().contains("\"t1\""))
        assertEquals(1, afterRevoke.getInt("other_tools_count"))
        h.close()
    }

    @Test
    fun `C2 服务器改了工具定义，缓存条目按 stale 处理`() {
        val original = tool("t1", description = "原始说明")
        val transport = ProbeTransport("srv_a", tools = listOf(original))
        val h = harness(servers = listOf(config("srv_a")), transports = mapOf("srv_a" to transport))
        h.grantAll("srv_a", transport.tools)
        assertEquals(1, h.list().getJSONArray("tools").length())

        // 服务器把同一个工具名换成了另一套说明（真实世界里这就是"偷换"）。
        transport.tools = listOf(tool("t1", description = "换过的说明"))
        h.now += h.limits.catalogTtlMs + 1
        val after = h.list()

        assertEquals(0, after.getJSONArray("tools").length())
        assertEquals(1, after.getInt("other_tools_count"))
        assertFalse(after.toString().contains("换过的说明"))
        h.close()
    }

    @Test
    fun `C3 同名工具在不同服务器之间按 server_id 正确路由`() {
        val a = ProbeTransport("srv_a", tools = listOf(tool("ping")))
        val b = ProbeTransport("srv_b", tools = listOf(tool("ping")))
        val h = harness(
            servers = listOf(config("srv_a"), config("srv_b")),
            transports = mapOf("srv_a" to a, "srv_b" to b),
        )
        h.grantAll("srv_a", a.tools)
        h.grantAll("srv_b", b.tools)

        val result = h.router.route(
            HubCommands.CALL_TOOL,
            JSONObject().put("server_id", "srv_b").put("tool_name", "ping"),
        )
        assertTrue(result is CapabilityResult.Success)
        assertEquals(listOf("ping"), b.calls)
        assertTrue(a.calls.isEmpty())
        h.close()
    }

    @Test
    fun `C4 退避结束后离线服务器恢复，工具重新出现`() {
        val flaky = ProbeTransport("srv_a", tools = listOf(tool("t1")), failing = true)
        val h = harness(servers = listOf(config("srv_a")), transports = mapOf("srv_a" to flaky))
        h.grantAll("srv_a", flaky.tools)

        assertEquals(0, h.list().getJSONArray("tools").length())

        flaky.failing = false
        h.now += h.limits.serverFailureBackoffMs + 1
        val recovered = h.list()
        assertEquals(1, recovered.getJSONArray("tools").length())
        assertEquals(0, recovered.getInt("other_tools_count"))
        h.close()
    }

    // ==================================================================
    // D. 截止时间与可观测性
    // ==================================================================

    @Test
    fun `D1 慢服务器被截止时间截断，快服务器照常返回`() {
        val slow = ProbeTransport("srv_slow", tools = listOf(tool("slow_tool")), connectDelayMs = 5_000)
        val fast = ProbeTransport("srv_fast", tools = listOf(tool("fast_tool")))
        val h = harness(
            servers = listOf(config("srv_slow"), config("srv_fast")),
            transports = mapOf("srv_slow" to slow, "srv_fast" to fast),
            limits = HubLimits(catalogDeadlineMs = 400),
        )
        h.grantAll("srv_slow", slow.tools)
        h.grantAll("srv_fast", fast.tools)

        val payload = h.list()

        // 硬上限：整次请求不超过截止时间太多（留 1s 给 JVM 启动与序列化）。
        val elapsed = payload.getLong("elapsed_ms")
        assertTrue("目录请求超过了硬上限：${elapsed}ms", elapsed < 2_000)
        assertEquals(1, payload.getJSONArray("tools").length())
        assertEquals("fast_tool", payload.getJSONArray("tools").getJSONObject(0).getString("tool_name"))
        // 没答上来的那台**不编造状态**，并如实说后台还在刷新。
        val slowEntry = (0 until payload.getJSONArray("servers").length())
            .map { payload.getJSONArray("servers").getJSONObject(it) }
            .first { it.getString("server_id") == "srv_slow" }
        assertEquals(HubStates.AVAILABILITY_UNKNOWN, slowEntry.getString("availability"))
        assertTrue(payload.getBoolean("refresh_pending"))
        h.close()
    }

    @Test
    fun `D2 目录响应带 elapsed_ms 与 catalog_source`() {
        val h = harness(
            servers = listOf(config("srv_a")),
            transports = mapOf("srv_a" to ProbeTransport("srv_a", tools = listOf(tool("t1")))),
        )
        val payload = h.list()
        assertTrue(payload.has("elapsed_ms"))
        assertTrue(payload.getLong("elapsed_ms") >= 0)
        assertEquals(HubCatalog.SOURCE_REFRESH, payload.getString("catalog_source"))
        assertTrue(payload.has("refresh_pending"))
        h.close()
    }

    @Test
    fun `E2 冷缓存遇到挂起服务器会等到目录截止时间`() {
        // 历史真机上「工具目录 3.4–3.8 秒、刷新后仍然慢」的结构性来源：
        // 目录请求在缓存为空/过期时会**同步等待**刷新，单台挂起的服务器就能吃掉
        // 整个 catalogDeadlineMs（默认 3_500ms）。这里把截止时间调小，验证的是
        // “等多久由截止时间决定”，避免为了断言时长拖慢整个测试套件。
        val slow = ProbeTransport("srv_slow", tools = listOf(tool("t1")), connectDelayMs = 10_000)
        val h = harness(
            servers = listOf(config("srv_slow")),
            transports = mapOf("srv_slow" to slow),
            limits = HubLimits(catalogDeadlineMs = 700),
        )
        h.grantAll("srv_slow", slow.tools)

        val first = timeMs { h.list() }
        assertTrue("冷缓存应等到截止时间，实测 ${first}ms", first >= 650)
        assertTrue("不能被单台服务器拖过截止时间太多，实测 ${first}ms", first < 2_000)

        // 单飞：刷新还没结束，第二次请求仍会等同一个 future 到新的截止时间。
        // 这正是“刷新之后下一次还是慢”的形状 —— 不是缓存没命中，是刷新本身还在飞。
        val second = timeMs { h.list() }
        assertTrue("刷新仍在飞时应继续等到截止时间，实测 ${second}ms", second >= 650)
        h.close()
    }

    // ==================================================================
    // E. 热缓存下的重复请求（同一进程内的本地回环，**不是**真实链路验收）
    // ==================================================================

    @Test
    fun `E1 热缓存下连续 30 次目录请求的中位耗时`() {
        val tools = (1..40).map { tool("t$it") }
        val h = harness(
            servers = listOf(config("srv_a")),
            transports = mapOf("srv_a" to ProbeTransport("srv_a", tools = tools)),
        )
        h.grantAll("srv_a", tools)
        h.list()  // 预热缓存

        val samples = (1..30).map { timeMs { h.list() } }.sorted()
        val median = samples[samples.size / 2]
        val p95 = samples[(samples.size * 95 + 99) / 100 - 1]
        println("hot-cache catalog: min=${samples.first()}ms median=${median}ms p95=${p95}ms max=${samples.last()}ms")
        // 40 个工具的热缓存目录没有任何网络动作，应当是个位数毫秒级。
        assertTrue("热缓存中位耗时过高：${median}ms", median < 100)
        h.close()
    }

    // ==================================================================
    // 脚手架
    // ==================================================================

    /** 可控的假传输：能挂起、能失败、能改工具列表，并记录连接/枚举/调用次数。 */
    private class ProbeTransport(
        override val serverId: String,
        var tools: List<McpRemoteTool> = emptyList(),
        @Volatile var connectDelayMs: Long = 0,
        @Volatile var failing: Boolean = false,
    ) : McpTransport {

        val connects = AtomicInteger()
        val listCalls = AtomicInteger()
        val calls = Collections.synchronizedList(mutableListOf<String>())

        override fun connect(): McpServerHandshake {
            connects.incrementAndGet()
            if (connectDelayMs > 0) Thread.sleep(connectDelayMs)
            if (failing) throw McpClientException(McpErrorKind.SERVER_UNAVAILABLE, "probe")
            return McpServerHandshake("2025-06-18", "probe", "1.0", null)
        }

        override fun listTools(): List<McpRemoteTool> {
            listCalls.incrementAndGet()
            if (failing) throw McpClientException(McpErrorKind.SERVER_UNAVAILABLE, "probe")
            return tools
        }

        override fun callTool(toolName: String, arguments: JSONObject): McpToolCallResult {
            calls += toolName
            return McpToolCallResult(false, "ok", null, """{"content":[{"type":"text","text":"ok"}]}""")
        }

        override fun close() = Unit
    }

    private class Harness(
        servers: List<McpServerConfig>,
        private val transports: Map<String, McpTransport>,
        val limits: HubLimits,
    ) {
        val kv = InMemoryKeyValueStore()
        val serverStore = McpServerStore(kv, FakeSecretBlobStore())
        val authStore = McpAuthorizationStore(kv)

        /** 冻结的挂钟：TTL 判定用它，测试可以精确地把时间推过有效期。 */
        var now = 1_000_000L

        val router = McpHubRouter(
            serverStore = serverStore,
            authorizationStore = authStore,
            transportFactory = { config -> transports[config.id] ?: error("测试没为 ${config.id} 准备传输") },
            hubLimits = limits,
            enabled = { true },
            clock = { now },
            // 真线程池：这些用例要证明的正是"请求不等刷新"。
            monotonic = { System.nanoTime() / 1_000_000 },
        )

        init {
            servers.forEach(serverStore::upsert)
        }

        fun list(offset: Int? = null, limit: Int? = null, refresh: Boolean? = null): JSONObject {
            val args = JSONObject()
            if (offset != null) args.put("offset", offset)
            if (limit != null) args.put("limit", limit)
            if (refresh != null) args.put("refresh", refresh)
            return payload(router.route(HubCommands.LIST_TOOLS, args))
        }

        fun serversOnly(refresh: Boolean? = null): JSONObject {
            val args = JSONObject().put("servers_only", true)
            if (refresh != null) args.put("refresh", refresh)
            return payload(router.route(HubCommands.LIST_TOOLS, args))
        }

        fun grantAll(serverId: String, tools: List<McpRemoteTool>) {
            val config = serverStore.find(serverId) ?: error("没有这台服务器：$serverId")
            for (remote in tools) {
                val schema = JSONObject(remote.rawJson).optJSONObject("inputSchema")
                authStore.grant(
                    serverId,
                    remote.name,
                    McpToolFingerprint.of(config.url, remote.name, remote.title, remote.description, schema?.toString()),
                )
            }
        }

        fun close() = router.close()
    }

    private fun harness(
        servers: List<McpServerConfig> = emptyList(),
        transports: Map<String, McpTransport> = emptyMap(),
        limits: HubLimits = HubLimits(),
    ) = Harness(servers, transports, limits)

    private fun config(
        id: String,
        name: String = id,
        url: String = "https://$id.example.com/mcp",
        enabled: Boolean = true,
    ) = McpServerConfig(id = id, name = name, url = url, enabled = enabled, createdAtMs = 1L, updatedAtMs = 1L)

    private fun tool(
        name: String,
        schema: String = """{"type":"object","properties":{}}""",
        description: String = "说明 $name",
    ) = McpRemoteTool(
        name = name,
        title = null,
        description = description,
        inputSchema = JSONObject(schema),
        annotations = null,
        rawJson = """{"name":"$name","description":"$description","inputSchema":$schema}""",
    )

    private fun JSONObject.server(index: Int): JSONObject = getJSONArray("servers").getJSONObject(index)

    /** 等一个后台动作落地（最多 5 秒）。用轮询而不是 sleep，避免把用例拖慢。 */
    private fun awaitTrue(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
    }
}

/** 取出成功结果；失败直接让用例报错（错误码比"某个字段为 null"更好读）。 */
private fun payload(result: CapabilityResult): JSONObject = when (result) {
    is CapabilityResult.Success -> result.payload
    is CapabilityResult.Failure -> error("预期成功，实际失败：${result.code}")
}

private fun timeMs(block: () -> Unit): Long {
    val startedAt = System.nanoTime()
    block()
    return (System.nanoTime() - startedAt) / 1_000_000
}
