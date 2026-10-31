package com.androidmcphub.app.aggregation

import com.androidmcphub.app.capability.CapabilityResult
import com.androidmcphub.app.capability.HubCommandRouter
import com.androidmcphub.app.mcp.McpClientException
import com.androidmcphub.app.mcp.McpErrorKind
import com.androidmcphub.app.mcp.McpLimits
import com.androidmcphub.app.mcp.McpToolIdentity
import com.androidmcphub.app.mcp.json.JsonSchemaValidator
import com.androidmcphub.app.mcp.model.McpServerConfig
import com.androidmcphub.app.mcp.store.McpAuthorizationState
import com.androidmcphub.app.mcp.store.McpAuthorizationStore
import com.androidmcphub.app.mcp.store.McpServerStore
import com.androidmcphub.app.mcp.transport.McpRemoteTool
import com.androidmcphub.app.mcp.transport.McpToolCallResult
import com.androidmcphub.app.mcp.transport.McpTransport
import com.androidmcphub.app.mcp.transport.McpTransportFactory
import com.androidmcphub.app.protocol.ToolResultErrorCode
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 聚合入口的真正实现：服务端要的"第三方工具目录"和"第三方工具调用"都从这里走。
 *
 * ## 它为什么长在手机这一侧
 *
 * 工具目录只存在于手机上（Worker 不持久化任何东西，这是它的安全边界之一），
 * 凭据（第三方服务器的请求头）也只在手机上（Worker 永远看不到它们）。
 * 所以"发现"和"执行"都只能由手机完成，Worker 只负责把两个稳定的入口
 * （[HubCommands.LIST_TOOLS] / [HubCommands.CALL_TOOL]）转成一次 `tool_request`。
 *
 * ## 三条不可退让的规则
 *
 * 1. **未授权的东西一个字节都不外泄**：目录里只出现"已授权且这次真的连上了"的工具。
 *    未授权的、离线的、超限的一律只贡献一个数字 [HubCatalog.otherToolsCount]，
 *    名字与描述绝不出现 —— 否则第三方工具的清单会变成"给模型看的广告位"，
 *    而这正是用户没同意过的事。
 * 2. **执行前重新校验授权**：调用时重新 `connect()` + `tools/list`，用**这次**的
 *    schema 算指纹再查授权。授权记录记的是"用户同意的是**那个**定义"，
 *    服务器把 `read_file` 换成 `delete_file`（或改掉 schema 让参数语义反转）之后，
 *    指纹变化会让状态掉到 `Stale`，调用被拒。
 * 3. **失败必须变成带错误码的失败**：这个类的所有路径都不抛异常
 *    （[route] 自己吞掉一切），因为调用方是协议层 —— 一次未捕获的异常在
 *    `tool_request` 那条路上会表现为"手机没有任何回应"，最终用户看到的是超时，
 *    而不是"这台服务器连不上"。
 *
 * @param transportFactory 造传输。测试注入假实现；生产用
 *   [McpTransportFactory]，并且**把每次操作的超时压到 [HubLimits.perServerTimeoutMs]
 *   与 [McpLimits.readTimeoutMs] 的较小值** —— 一次目录刷新要让用户等
 *   "最多几秒"，而不是每台服务器 30 秒叠加。
 * @param enabled 执行总开关（界面上是「允许服务端调用本机能力」）。它**只门禁调用**，
 *   不门禁目录：总开关关着时用户仍然可以在服务页里看工具、授权工具，
 *   只是任何第三方工具都不会被真正执行。
 */
class McpHubRouter(
    private val serverStore: McpServerStore,
    private val authorizationStore: McpAuthorizationStore,
    private val transportFactory: ((McpServerConfig) -> McpTransport)? = null,
    private val limits: McpLimits = McpLimits(),
    private val hubLimits: HubLimits = HubLimits(),
    private val enabled: () -> Boolean = { true },
    private val clock: () -> Long = System::currentTimeMillis,
    /** 单调时钟：分阶段耗时用它，不受用户/系统改时间影响。 */
    private val monotonic: () -> Long = { System.nanoTime() / 1_000_000 },
    /**
     * 目录刷新的执行器。
     *
     * 默认自建一个**有界**线程池（见 [ownedExecutor]）；测试注入直接执行的实现，
     * 就能把"并发刷新"变成确定性的顺序执行。
     */
    refreshExecutor: Executor? = null,
    /** 分阶段诊断日志的落地函数；默认丢弃。只接收本文件拼出来的结构化行。 */
    private val log: (String) -> Unit = {},
) : HubCommandRouter {

    /** 目录与可用性的本地缓存（5.1/5.2）。 */
    private val cache = HubCatalogCache(clock)

    /** 自建的线程池；外部注入执行器时为 null（生命周期归调用方）。 */
    private val ownedExecutor: ThreadPoolExecutor? = if (refreshExecutor == null) {
        // 注意 core 必须等于 max：`ThreadPoolExecutor` 配**无界队列**时并发度就是 core，
        // core=0 会让整个池永远只有一个线程，"并发刷新"变成一句空话。
        val threads = hubLimits.refreshConcurrency.coerceAtLeast(1)
        ThreadPoolExecutor(
            threads,
            threads,
            30L,
            TimeUnit.SECONDS,
            LinkedBlockingQueue(),
            daemonThreads("hub-refresh"),
        ).apply { allowCoreThreadTimeOut(true) }
    } else {
        null
    }

    private val executor: Executor = refreshExecutor ?: ownedExecutor!!

    /** 正在飞的刷新任务（单飞：同一台服务器同一时刻只有一个刷新在跑）。 */
    private val refreshes = HashMap<String, CompletableFuture<Boolean>>()

    private val refreshLock = Any()

    @Volatile
    private var closed = false

    override fun handles(toolName: String): Boolean = HubCommands.isHubCommand(toolName)

    override fun route(toolName: String, arguments: JSONObject): CapabilityResult =
        routeWithId(NO_REQUEST_ID, toolName, arguments)

    /**
     * 带 request_id 的入口：分阶段耗时靠它把手机侧的时间线与服务端看到的 RTT 对上。
     *
     * 直接调 [route]（单测、诊断脚本）时没有 id，用常量占位 —— 绝不新造一个
     * "看起来像真的"的 id，那样日志里会多出一批对不上服务端的记录。
     */
    override fun routeWithId(requestId: String, toolName: String, arguments: JSONObject): CapabilityResult {
        val trace = HubTrace(requestId, monotonic, log)
        trace.stage("entry")
        val result = try {
            when (toolName) {
                HubCommands.LIST_TOOLS -> listTools(arguments, trace)
                HubCommands.GET_TOOL_SCHEMA -> getToolSchema(arguments, trace)
                HubCommands.CALL_TOOL -> callTool(arguments, trace)
                else -> CapabilityResult.Failure(
                    ToolResultErrorCode.UNSUPPORTED_TOOL,
                    "未知的聚合命令",
                )
            }
        } catch (invalid: InvalidArguments) {
            failure(ToolResultErrorCode.INVALID_ARGUMENTS, invalid.message ?: "参数不合法")
        } catch (error: McpClientException) {
            failure(codeFor(error.kind), describe(error))
        } catch (error: Exception) {
            // 兜底：任何没预料到的异常都必须变成一条有错误码的失败，而不是消失。
            failure(ToolResultErrorCode.TOOL_FAILED, "聚合执行失败（${error.javaClass.simpleName}）")
        }
        trace.finish(
            outcome = if (result is CapabilityResult.Success) "ok" else "failed",
            extra = (result as? CapabilityResult.Failure)?.let { "error_code=${it.code}" },
        )
        return result
    }

    /**
     * 释放自建线程池（前台服务销毁时调用）。
     *
     * 幂等：重复调用无副作用。已有的刷新任务会跑完自己那次有界超时后退出，
     * 不会留下永久挂着的线程。
     */
    override fun close() {
        closed = true
        ownedExecutor?.shutdown()
        cache.clear()
    }

    // ------------------------------------------------------------------
    // 目录
    // ------------------------------------------------------------------

    private fun listTools(arguments: JSONObject, trace: HubTrace): CapabilityResult {
        rejectUnknownKeys(arguments, ALLOWED_CATALOG_KEYS)
        val filter = readOptionalServerId(arguments)
        val offset = readInt(arguments, "offset", 0, 0, MAX_OFFSET)
        val limit = readInt(arguments, "limit", hubLimits.maxToolsPerPage, 1, hubLimits.maxPageSize)
        val includeSchema = readBoolean(arguments, "include_schema", false)
        val serversOnly = readBoolean(arguments, "servers_only", false)
        // 显式手动刷新：绕过 TTL 与失败退避，立刻重新连一次。
        // 单飞仍然生效 —— 同一个 request_id / 并发的两次刷新只会产生一次网络往返。
        val refresh = readBoolean(arguments, "refresh", false)

        val configured = serverStore.list().filter { it.enabled }.sortedBy { it.id }
        trace.stage("config_read", detail = "servers=${configured.size}")
        // 用户删掉的服务器不该在内存里留一份目录。
        cache.retainOnly(configured.mapTo(HashSet()) { it.id })
        val selected = when (filter) {
            null -> configured
            else -> configured.filter { it.id == filter }.ifEmpty {
                return failure(ToolResultErrorCode.UNSUPPORTED_TOOL, "本机没有启用这台 MCP 服务器")
            }
        }

        // 两条路彻底分开：
        //  · `servers_only` 只读本地缓存 + 注册表，一个网络包都不发（5.1）；
        //  · 完整目录走"缓存优先 + 有界并发刷新"（5.2/5.3）。
        // 它们共用同一份缓存，所以"先查服务器、再列工具"不会让第二次请求重新连一遍。
        val collected = if (serversOnly) {
            collectAvailability(selected, trace, refresh)
        } else {
            collectCatalog(selected, trace, refresh)
        }
        val servers = collected.servers
        val tools = collected.tools
        val others = collected.others

        val total = tools.size
        val safeOffset = offset.coerceAtMost(total)
        val page = tools.drop(safeOffset).take(limit)

        // 分页之后再套一层字符预算：极端情况下 100 个长描述仍然可能把 16 KiB 帧撑爆。
        // 预算只截断**这一页的尾部**，不会让已列出的工具消失；被截掉的用 next_offset 告知。
        //
        // schema 先按页算好放进 map（无副作用），而不是在 toJson 的回调里边算边计数 ——
        // 那样同一页会被序列化两次（预算试算 + 正式输出），计数就会被重复加。
        val schemas = HashMap<HubCatalogTool, JSONObject?>()
        if (includeSchema) {
            for (tool in page) {
                val schema = tool.inputSchema ?: continue
                if (schema.toString().length <= hubLimits.maxSchemaChars) schemas[tool] = schema
            }
        }
        val emptyBudget = utf8Bytes(
            HubCatalog(
                servers = servers,
                tools = emptyList(),
                otherToolsCount = others,
                totalTools = total,
                offset = safeOffset,
                limit = limit,
            ).toJson(hubLimits.maxListDescription),
        )
        var usedBytes = emptyBudget
        val pageTools = mutableListOf<HubCatalogTool>()
        var budgetTruncated = false
        for (tool in page) {
            val entry = utf8Bytes(tool.toListJson(hubLimits.maxListDescription, schemas[tool]))
            // 每条 +2 是数组里逗号与可能的空白；另留 64 字节给 has_more/next_offset 这类
            // 只在预算算完之后才确定下来的键，避免"刚好卡在预算上"时又被撑超。
            if (usedBytes + entry + 2 + 64 > hubLimits.maxCatalogBytes) {
                budgetTruncated = true
                break
            }
            pageTools += tool
            usedBytes += entry + 2
        }

        val returned = pageTools.size
        val hasMore = safeOffset + returned < total
        val schemasDropped = if (!includeSchema) 0 else pageTools.count { it.inputSchema != null && !schemas.containsKey(it) }
        trace.stage("page", detail = "returned=$returned total=$total")
        val catalog = HubCatalog(
            servers = servers,
            tools = pageTools,
            otherToolsCount = others,
            totalTools = total,
            offset = safeOffset,
            limit = limit,
            hasMore = hasMore,
            nextOffset = if (hasMore) safeOffset + returned else null,
            schemasDropped = schemasDropped,
            truncatedByBudget = budgetTruncated,
            serversOnly = serversOnly,
            catalogSource = collected.catalogSource,
            catalogAgeMs = collected.catalogAgeMs,
            refreshPending = collected.refreshPending,
            elapsedMs = trace.elapsedMs(),
        )
        val json = catalog.toJson(hubLimits.maxListDescription) { schemas[it] }
        trace.stage("serialize", detail = "bytes=${utf8Bytes(json)}")
        return CapabilityResult.Success(json)
    }

    /** 一次目录请求收集到的原始材料（分页/预算/序列化对两条路径一视同仁）。 */
    private class Collected(
        val servers: List<HubCatalogServer>,
        val tools: List<HubCatalogTool>,
        val others: Int,
        val catalogSource: String,
        val catalogAgeMs: Long?,
        val refreshPending: Boolean,
    )

    /**
     * `servers_only`：**只用本地数据**回答"有哪些服务器、上次看到的可用性是什么"。
     *
     * 这条路径不建立任何连接、不枚举任何工具、不等待任何超时（5.1）。它做两件事：
     *  1. 从服务器注册表（本地配置）取名字与 id —— 这部分本来就是本地的；
     *  2. 从缓存取**上次已知**的可用性，并带上 `checked_at_ms` 与 `source=cache`，
     *     让调用方自己判断这条状态有多旧。
     *
     * 缓存过期（或从未探测）时会**发起**一次后台探测，但**不等**它：探测结果只对
     * 下一次请求可见。这样"某台服务器慢/离线"在结构上就不可能拖慢这次响应 ——
     * 实测里那 13.6 秒的绝大部分正是串行等待一台离线服务器。
     */
    private fun collectAvailability(
        selected: List<McpServerConfig>,
        trace: HubTrace,
        forceRefresh: Boolean = false,
    ): Collected {
        val now = clock()
        // 顺序是刻意的：**先取状态，再发起探测**。
        // 这样这份响应只反映"请求开始时已知的状态"，探测结果只对下一次请求可见 ——
        // 响应内容与探测快慢、执行器是同步还是异步都无关（单测可以据此断言）。
        val servers = ArrayList<HubCatalogServer>(selected.size)
        for ((index, config) in selected.withIndex()) {
            if (index >= hubLimits.maxServers) {
                // 超出的服务器如实说"这次没处理"，而不是猜一个 offline。
                servers += HubCatalogServer(
                    serverId = config.id,
                    serverName = serverName(config),
                    availability = HubStates.AVAILABILITY_UNKNOWN,
                    availabilitySource = HubStates.SOURCE_SKIPPED,
                )
                continue
            }
            servers += serverState(config, now)
        }
        var pending = false
        for (config in selected.take(hubLimits.maxServers)) {
            if (!forceRefresh && cache.isAvailabilityFresh(config, hubLimits.availabilityTtlMs)) continue
            // 单飞 + 失败退避都在 [startRefresh] 里：重复请求不会变成重连风暴。
            // forceRefresh 只绕过 TTL 与退避，**不**绕过单飞 —— 双击"刷新"不会打两次。
            if (startRefresh(config, enumerate = false, trace = null, force = forceRefresh) != null) pending = true
        }
        trace.stage("servers_only", detail = "pending=$pending")
        return Collected(
            servers = servers,
            tools = emptyList(),
            others = 0,
            catalogSource = HubCatalog.SOURCE_CACHE,
            catalogAgeMs = null,
            refreshPending = pending,
        )
    }

    /**
     * 完整目录：**缓存优先 + 有界并发刷新 + 硬截止时间**（5.2/5.3）。
     *
     * 顺序是刻意的：
     *  1. 只对"缓存里没有或不新鲜"的服务器发起刷新，且所有刷新**同时**开始
     *     （旧实现是串行 `connect()`，一台离线服务器就把后面所有服务器挡住）；
     *  2. 最多等 [HubLimits.catalogDeadlineMs]，到点就用已经拿到的结果返回；
     *  3. 没等到的服务器用**上次已知**状态，而不是编一个 offline。
     *
     * 失败退避（[HubLimits.serverFailureBackoffMs]）保证一台挂掉的服务器不会在
     * 每次请求里都被重连一次；单飞（[refreshes]）保证并发请求共用同一次刷新。
     */
    private fun collectCatalog(
        selected: List<McpServerConfig>,
        trace: HubTrace,
        forceRefresh: Boolean = false,
    ): Collected {
        val now = clock()
        val deadlineAt = monotonic() + hubLimits.catalogDeadlineMs
        val triggered = LinkedHashMap<String, CompletableFuture<Boolean>>()
        for (config in selected.take(hubLimits.maxServers)) {
            if (!forceRefresh && cache.isFresh(config, hubLimits.catalogTtlMs)) continue
            val future = startRefresh(config, enumerate = true, trace = trace, force = forceRefresh) ?: continue
            triggered[config.id] = future
        }
        trace.stage("refresh_start", detail = "servers=${triggered.size}")

        // 所有刷新已经在各自线程上跑了；这里只是**等**，不是发起。
        for ((serverId, future) in triggered) {
            awaitAtMost(future, deadlineAt)
            trace.stage("refresh_wait", serverId = serverId, detail = "ok=${future.getNow(false)}")
        }

        val refreshed: Set<String> = triggered.filterValues { it.getNow(false) == true }.keys
        val pending = triggered.values.any { !it.isDone }

        // 授权表只读一次：几百个工具时这是几百次重复解析的区别。
        val grants = authorizationStore.snapshot()
        trace.stage("auth_snapshot")

        val servers = ArrayList<HubCatalogServer>(selected.size)
        val authorized = mutableListOf<HubCatalogTool>()
        var others = 0
        var oldestAge: Long? = null
        var usedLive = 0
        var usedCached = 0

        for ((index, config) in selected.withIndex()) {
            if (index >= hubLimits.maxServers) {
                servers += HubCatalogServer(
                    serverId = config.id,
                    serverName = serverName(config),
                    availability = HubStates.AVAILABILITY_UNKNOWN,
                    availabilitySource = HubStates.SOURCE_SKIPPED,
                )
                continue
            }
            val snapshot = cache.snapshotFor(config)
            if (snapshot == null) {
                servers += HubCatalogServer(
                    serverId = config.id,
                    serverName = serverName(config),
                    availability = HubStates.AVAILABILITY_UNKNOWN,
                    availabilitySource = HubStates.SOURCE_UNKNOWN,
                )
                continue
            }
            val live = config.id in refreshed
            servers += HubCatalogServer(
                serverId = config.id,
                serverName = serverName(config),
                availability = snapshot.availability,
                availabilitySource = if (live) HubStates.SOURCE_LIVE else HubStates.SOURCE_CACHE,
                checkedAtMs = snapshot.checkedAtMs,
            )
            // 离线服务器上的工具不下发：模型看到也调不通，而"不可用"已经由服务器状态说清楚了。
            if (snapshot.availability != HubStates.AVAILABILITY_ONLINE) continue
            val cachedTools = snapshot.tools ?: continue
            val age = (now - snapshot.fetchedAtMs).coerceAtLeast(0L)
            oldestAge = maxOf(oldestAge ?: age, age)
            if (live) usedLive++ else usedCached++

            for (entry in cachedTools) {
                // **授权是每次请求重判的**：用户撤销之后，缓存里的这一条下一次请求就消失，
                // 不需要重新联网，也绝不因为"缓存里还在"就继续外泄。
                val state = grants.state(config.id, entry.tool.name, entry.fingerprint)
                if (state != McpAuthorizationState.Granted) {
                    others++
                    continue
                }
                // 收集上限只防内存失控；它**不再**是一页能返回多少。
                if (authorized.size >= hubLimits.maxTools) {
                    others++
                    continue
                }
                authorized += catalogTool(config, entry.tool, state, entry.fingerprint)
            }
        }

        val source = when {
            refreshed.isEmpty() -> HubCatalog.SOURCE_CACHE
            usedCached == 0 && usedLive > 0 -> HubCatalog.SOURCE_REFRESH
            usedLive > 0 -> HubCatalog.SOURCE_MIXED
            else -> HubCatalog.SOURCE_CACHE
        }
        trace.stage("collect", detail = "tools=${authorized.size} others=$others source=$source")
        return Collected(
            servers = servers,
            tools = authorized,
            others = others,
            catalogSource = source,
            catalogAgeMs = oldestAge,
            refreshPending = pending,
        )
    }

    /** 一台服务器此刻该报告的可用性：只来自缓存，绝不现编。 */
    private fun serverState(config: McpServerConfig, now: Long): HubCatalogServer {
        val snapshot = cache.snapshotFor(config)
            ?: return HubCatalogServer(
                serverId = config.id,
                serverName = serverName(config),
                availability = HubStates.AVAILABILITY_UNKNOWN,
                availabilitySource = HubStates.SOURCE_UNKNOWN,
            )
        return HubCatalogServer(
            serverId = config.id,
            serverName = serverName(config),
            availability = snapshot.availability,
            availabilitySource = HubStates.SOURCE_CACHE,
            checkedAtMs = snapshot.checkedAtMs,
        )
    }

    /**
     * 发起（或复用一个正在飞的）刷新任务。
     *
     * 返回 null 表示"这次不该连"：
     *  * 路由器已关闭；
     *  * 同一台服务器已有刷新在跑（调用方直接等那个 future）；
     *  * 这台服务器刚失败过，还在 [HubLimits.serverFailureBackoffMs] 冷却期里。
     *
     * 三条都是"不制造重连风暴"的必要条件 —— 没有它们，一次目录请求的失败会变成
     * 每个后续请求都去撞一遍同一堵墙。
     */
    private fun startRefresh(
        config: McpServerConfig,
        enumerate: Boolean,
        trace: HubTrace?,
        force: Boolean = false,
    ): CompletableFuture<Boolean>? {
        if (closed) return null
        // 只在锁内读 [refreshes]：它是普通 HashMap，锁外读会与另一个线程的写入竞争
        // （读到一半的 rehash 是典型的"偶发丢刷新 / 死循环"来源）。
        synchronized(refreshLock) {
            refreshes[config.id]?.let { return it }
            val failedAgo = cache.failedAgoMs(config)
            if (!force && failedAgo != null && failedAgo in 0..hubLimits.serverFailureBackoffMs) return null

            val future = CompletableFuture<Boolean>()
            refreshes[config.id] = future
            val task = Runnable {
                val startedAt = monotonic()
                val ok = try {
                    refreshServer(config, enumerate)
                } catch (error: Throwable) {
                    // 远端的一切都不可信；刷新线程绝不能把异常带出去。
                    false
                }
                trace?.stage(
                    "refresh_task",
                    serverId = config.id,
                    detail = "ok=$ok enumerate=$enumerate task_ms=${monotonic() - startedAt}",
                )
                future.complete(ok)
            }
            try {
                executor.execute(task)
            } catch (rejected: RejectedExecutionException) {
                refreshes.remove(config.id)
                future.complete(false)
                return null
            }
            future.whenComplete { _, _ ->
                synchronized(refreshLock) { if (refreshes[config.id] === future) refreshes.remove(config.id) }
            }
            return future
        }
    }

    /** 最多等到 [deadlineAtMs]（单调时钟）。超时/异常都不影响返回：用缓存里已有的结果。 */
    private fun awaitAtMost(future: CompletableFuture<Boolean>, deadlineAtMs: Long) {
        val remaining = deadlineAtMs - monotonic()
        if (remaining <= 0) return
        try {
            future.get(remaining, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (_: Exception) {
            // ExecutionException / TimeoutException：都按"这次没拿到"处理。
        }
    }

    /**
     * 真的连一次服务器。
     *
     * [enumerate] 为 false 时只做握手（`servers_only` 的后台探测）：连接成功已经足以
     * 回答"它现在可不可用"，而 `tools/list` 才是一台 35 工具服务器上最慢的那一步。
     *
     * 任何失败都只影响这一台服务器：状态记成离线、失败时刻记下来（用于退避），
     * 工具目录保留但不会下发。绝不向调用方抛异常。
     */
    private fun refreshServer(config: McpServerConfig, enumerate: Boolean): Boolean {
        val transport = try {
            openTransport(config)
        } catch (error: Exception) {
            cache.recordFailure(config)
            return false
        }
        return try {
            transport.connect()
            if (enumerate) {
                val remoteTools = transport.listTools()
                cache.recordOnline(
                    config,
                    remoteTools.map { HubCachedTool(it, fingerprintOf(config, it)) },
                )
            } else {
                cache.recordOnline(config, tools = null)
            }
            true
        } catch (error: Exception) {
            cache.recordFailure(config)
            false
        } finally {
            runCatching { transport.close() }
        }
    }

    /**
     * 按需取一个**已授权**工具的参数结构。
     *
     * 目录默认不带 schema（35 个工具 × 完整 schema 会撑爆帧），但模型要正确调用工具
     * 仍然需要它。所以拆成单独一次按工具读取：只有真正要调的那个才付这份体积。
     * 未授权/定义已变化的工具在这里同样被拒绝 —— 否则它就成了一条绕过授权的信息出口。
     */
    private fun getToolSchema(arguments: JSONObject, trace: HubTrace): CapabilityResult {
        rejectUnknownKeys(arguments, ALLOWED_SCHEMA_KEYS)
        val serverId = requireString(arguments, "server_id")
        if (!HubIdentity.isValidServerId(serverId)) {
            throw InvalidArguments("server_id 不合法")
        }
        val toolName = requireString(arguments, "tool_name")
        if (!HubIdentity.isValidToolName(toolName)) {
            throw InvalidArguments("tool_name 不合法")
        }

        val config = serverStore.list().firstOrNull { it.id == serverId && it.enabled }
            ?: return failure(ToolResultErrorCode.UNSUPPORTED_TOOL, "本机没有启用这台 MCP 服务器")

        trace.stage("connect", serverId = serverId)
        val transport = openTransport(config)
        try {
            transport.connect()
            // 与调用一样，用**这次**拿到的定义算指纹；旧授权不算数。
            val remoteTools = transport.listTools()
            trace.stage("list_tools", serverId = serverId, detail = "tools=${remoteTools.size}")
            // 顺带把这次拿到的目录写回缓存：它比缓存里的更新，而且是这次请求已经付过
            // 成本的网络结果。只写成功路径 —— 这里的失败（工具不存在/未授权）与服务器
            // 可用性无关，不能拿去把服务器标成离线。
            cache.recordOnline(config, remoteTools.map { HubCachedTool(it, fingerprintOf(config, it)) })
            val tool = remoteTools.firstOrNull { it.name == toolName }
                ?: return failure(ToolResultErrorCode.UNSUPPORTED_TOOL, "这台服务器上没有这个工具")
            val fingerprint = fingerprintOf(config, tool)
            when (authorizationStore.state(serverId, toolName, fingerprint)) {
                McpAuthorizationState.Granted -> Unit
                McpAuthorizationState.Stale -> return failure(
                    ToolResultErrorCode.NOT_PERMITTED,
                    "这个工具的定义在授权之后发生了变化，需要你重新确认后才能读取参数结构",
                )
                McpAuthorizationState.NotGranted -> return failure(
                    ToolResultErrorCode.NOT_PERMITTED,
                    "这个工具还没有被授权",
                )
            }

            val payload = JSONObject()
            payload.put("server_id", serverId)
            payload.put("tool_name", toolName)
            payload.put("tool_id", HubIdentity.toolId(serverId, toolName))
            val schema = originalSchema(tool)
            if (schema == null) {
                payload.put("input_schema_omitted", "no_schema")
            } else {
                val text = schema.toString()
                payload.put("schema_chars", text.length)
                if (text.length <= hubLimits.maxSchemaResponseChars) {
                    payload.put("input_schema", schema)
                } else {
                    // 不返回半截 JSON（那比没有更糟）：只给体积与顶层参数名，让模型知道
                    // 这个工具存在、以及为什么拿不到完整结构。
                    payload.put("input_schema_omitted", "too_large")
                    payload.put("properties", propertyNames(schema))
                }
            }
            return CapabilityResult.Success(payload)
        } finally {
            runCatching { transport.close() }
        }
    }

    private fun catalogTool(
        config: McpServerConfig,
        tool: McpRemoteTool,
        state: McpAuthorizationState,
        fingerprint: String,
    ): HubCatalogTool {
        val schema = originalSchema(tool)
        val schemaTooLarge = schema != null && schema.toString().length > hubLimits.maxSchemaChars
        val description = buildString {
            append(clip(tool.description, hubLimits.maxDescription))
            if (schemaTooLarge) {
                if (isNotEmpty()) append(' ')
                append("（该工具的输入结构超过本机上限；需要参数结构时请使用 hub_get_tool_schema）")
            }
        }
        return HubCatalogTool(
            serverId = config.id,
            toolName = tool.name,
            displayName = clip(tool.title?.takeIf { it.isNotBlank() } ?: tool.name, hubLimits.maxDisplayName),
            description = description,
            // 保留**原始** schema：是否随目录下发由 listTools 的 include_schema + 上限决定。
            // 在这里就置 null 会让"这次到底丢了几条 schema"变得数不清。
            inputSchema = schema,
            authorizationState = HubStates.authorizationOf(state),
            availability = HubStates.AVAILABILITY_ONLINE,
            fingerprint = fingerprint,
        )
    }

    // ------------------------------------------------------------------
    // 调用
    // ------------------------------------------------------------------

    private fun callTool(arguments: JSONObject, trace: HubTrace): CapabilityResult {
        if (!enabled()) {
            return failure(
                ToolResultErrorCode.NOT_PERMITTED,
                "服务端调用本机能力的总开关当前是关闭的",
            )
        }
        rejectUnknownKeys(arguments, ALLOWED_CALL_KEYS)

        val serverId = requireString(arguments, "server_id")
        if (!HubIdentity.isValidServerId(serverId)) {
            throw InvalidArguments("server_id 不合法")
        }
        val toolName = requireString(arguments, "tool_name")
        if (!HubIdentity.isValidToolName(toolName)) {
            throw InvalidArguments("tool_name 不合法")
        }
        val rawArguments = arguments.opt("arguments")
        val validated = when {
            rawArguments == null || rawArguments == JSONObject.NULL -> HubArgumentValidation.Ok(JSONObject())
            rawArguments is JSONObject -> HubArgumentValidator.validate(rawArguments, hubLimits)
            else -> throw InvalidArguments("arguments 必须是一个对象")
        }
        if (validated is HubArgumentValidation.Error) {
            throw InvalidArguments(validated.message)
        }
        val callArguments = (validated as HubArgumentValidation.Ok).value

        val config = serverStore.list().firstOrNull { it.id == serverId && it.enabled }
            ?: return failure(ToolResultErrorCode.UNSUPPORTED_TOOL, "本机没有启用这台 MCP 服务器")

        trace.stage("connect", serverId = serverId)
        val transport = openTransport(config)
        try {
            transport.connect()
            // 每次调用都重新拉一次工具定义：授权判定必须基于"现在这个定义"。
            val remoteTools = transport.listTools()
            trace.stage("list_tools", serverId = serverId, detail = "tools=${remoteTools.size}")
            // 同 getToolSchema：这次拿到的目录是更新的，写回缓存；但不因本工具的
            // 未授权/不存在而改服务器的可用性。
            cache.recordOnline(config, remoteTools.map { HubCachedTool(it, fingerprintOf(config, it)) })
            val tool = remoteTools.firstOrNull { it.name == toolName }
                ?: return failure(ToolResultErrorCode.UNSUPPORTED_TOOL, "这台服务器上没有这个工具")

            val fingerprint = fingerprintOf(config, tool)
            when (authorizationStore.state(serverId, toolName, fingerprint)) {
                McpAuthorizationState.Granted -> Unit
                McpAuthorizationState.Stale -> return failure(
                    ToolResultErrorCode.NOT_PERMITTED,
                    "这个工具的定义在授权之后发生了变化，需要你重新确认后才能调用",
                )
                McpAuthorizationState.NotGranted -> return failure(
                    ToolResultErrorCode.NOT_PERMITTED,
                    "这个工具还没有被授权",
                )
            }

            val schema = originalSchema(tool)
            if (schema != null) {
                val schemaError = JsonSchemaValidator.validate(schema, callArguments)
                if (schemaError != null) {
                    // 只回我们自己的说明：远端 schema 的内容不回显，避免把不可信文本
                    // 原样塞进错误信息里绕一圈又回到模型面前。
                    throw InvalidArguments("参数不符合这个工具的声明")
                }
            }

            val result = transport.callTool(toolName, callArguments)
            trace.stage("execute", serverId = serverId)
            return CapabilityResult.Success(callPayload(config, toolName, result))
        } finally {
            runCatching { transport.close() }
        }
    }

    private fun callPayload(
        config: McpServerConfig,
        toolName: String,
        result: McpToolCallResult,
    ): JSONObject {
        val payload = JSONObject()
        payload.put("server_id", config.id)
        payload.put("tool_name", toolName)
        payload.put("tool_id", HubIdentity.toolId(config.id, toolName))
        payload.put("is_error", result.isError)

        val items = runCatching { JSONObject(result.rawResultJson).optJSONArray("content") }.getOrNull()
        val content = JSONArray()
        val contentTypes = mutableListOf<String>()
        val unsupported = mutableListOf<String>()
        var truncated = false
        var totalChars = 0

        if (items != null) {
            for (index in 0 until minOf(items.length(), hubLimits.maxContentItems)) {
                val item = items.optJSONObject(index) ?: continue
                val type = item.optString("type", "")
                if (type == "text") {
                    val raw = item.optString("text", "")
                    val remaining = hubLimits.maxTotalTextChars - totalChars
                    if (remaining <= 0) {
                        truncated = true
                        continue
                    }
                    val kept = clip(raw, minOf(hubLimits.maxTextChars, remaining))
                    if (kept.length < raw.length) truncated = true
                    totalChars += kept.length
                    if (!contentTypes.contains("text")) contentTypes += "text"
                    content.put(JSONObject().put("type", "text").put("text", kept))
                } else {
                    // 非文本内容（图片、音频、资源引用）**只报告类型**：
                    // 正文不外泄，因为它既可能是巨大的 base64，也可能包含用户没打算给模型看的东西。
                    if (type.isNotEmpty() && !unsupported.contains(type)) unsupported += type
                }
            }
            if (items.length() > hubLimits.maxContentItems) truncated = true
        }

        payload.put("content", content)
        payload.put("content_types", JSONArray(contentTypes))
        if (unsupported.isNotEmpty()) payload.put("unsupported_content_types", JSONArray(unsupported))
        if (truncated) payload.put("text_truncated", true)

        val structured = result.structured
        if (structured != null && structured.toString().length <= hubLimits.maxStructuredChars) {
            payload.put("structured_content", structured)
        } else if (structured != null) {
            payload.put("structured_content_omitted", "too_large_or_not_object")
        }
        return payload
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    private fun openTransport(config: McpServerConfig): McpTransport {
        val factory = transportFactory ?: { server: McpServerConfig ->
            // 单次操作的超时取两者较小值：目录刷新是用户按了「刷新」之后要立刻看到结果的动作。
            McpTransportFactory.create(
                server,
                limits.copy(
                    readTimeoutMs = minOf(limits.readTimeoutMs, hubLimits.perServerTimeoutMs),
                    connectTimeoutMs = minOf(limits.connectTimeoutMs, hubLimits.perServerTimeoutMs),
                ),
            )
        }
        return factory(config)
    }

    /**
     * 授权指纹。输入是服务器给的**原始** schema（从 `rawJson` 里取），
     * 不是被本机截断过的那个 —— 否则"我们的展示上限"会被误算成"工具定义变了"，
     * 让用户反复重新授权。
     *
     * 标题与描述也一起进指纹：描述就是给模型看的说明书，服务器把它改成别的话术时，
     * 工具名和 schema 可以一个字都不动。
     */
    private fun fingerprintOf(config: McpServerConfig, tool: McpRemoteTool): String =
        McpToolIdentity.fingerprintOf(config.url, tool)

    /** 见 [McpToolIdentity.originalSchema]：界面与运行时用的是同一个算法，不能各写一份。 */
    private fun originalSchema(tool: McpRemoteTool): JSONObject? = McpToolIdentity.originalSchema(tool)

    private fun serverName(config: McpServerConfig): String =
        clip(config.name.ifBlank { config.id }, hubLimits.maxServerName)

    private fun readOptionalServerId(arguments: JSONObject): String? {
        val raw = arguments.opt("server_id") ?: return null
        if (raw == JSONObject.NULL) return null
        if (raw !is String || !HubIdentity.isValidServerId(raw)) {
            throw InvalidArguments("server_id 不合法")
        }
        return raw
    }

    private fun requireString(arguments: JSONObject, key: String): String {
        val raw = arguments.opt(key)
        if (raw !is String || raw.isEmpty()) throw InvalidArguments("$key 必填")
        return raw
    }

    /**
     * 读一个整数参数。
     *
     * 类型不对、越界都**拒绝**：分页参数是模型自己写的，写错了应该让它看见错误并改。
     * 静默夹紧（比如把 limit=1000 变成 100）会让它以为"一页就这么多"，而真正的原因
     * 是它自己写错了 —— 那比一条 invalid_arguments 更难排查。
     * （`offset` 超过总数**不是**越界：那是合法的空页，明确返回 total_tools 即可。）
     */
    private fun readInt(arguments: JSONObject, key: String, default: Int, min: Int, max: Int): Int {
        val raw = arguments.opt(key)
        if (raw == null || raw == JSONObject.NULL) return default
        val value = when (raw) {
            is Int -> raw
            is Long -> if (raw >= Int.MIN_VALUE && raw <= Int.MAX_VALUE) raw.toInt()
            else throw InvalidArguments("$key 超出范围")
            is Double -> if (raw.isFinite() && raw % 1.0 == 0.0 && raw >= Int.MIN_VALUE && raw <= Int.MAX_VALUE) {
                raw.toInt()
            } else {
                throw InvalidArguments("$key 必须是整数")
            }
            else -> throw InvalidArguments("$key 必须是整数")
        }
        if (value < min || value > max) throw InvalidArguments("$key 必须在 [$min, $max] 之间")
        return value
    }

    private fun readBoolean(arguments: JSONObject, key: String, default: Boolean): Boolean {
        val raw = arguments.opt(key)
        if (raw == null || raw == JSONObject.NULL) return default
        if (raw !is Boolean) throw InvalidArguments("$key 必须是布尔值")
        return raw
    }

    /** schema 的顶层参数名。schema 过大时只给这些名字，让调用方至少知道要传什么。 */
    private fun propertyNames(schema: JSONObject): JSONArray {
        val properties = schema.optJSONObject("properties") ?: return JSONArray()
        val names = JSONArray()
        val keys = properties.keys()
        while (keys.hasNext()) {
            val name = keys.next()
            if (names.length() >= MAX_PROPERTY_NAMES) break
            names.put(clip(name, hubLimits.maxArgumentKeyLength))
        }
        return names
    }

    private fun rejectUnknownKeys(arguments: JSONObject, allowed: Set<String>) {
        val keys = arguments.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key !in allowed) throw InvalidArguments("参数里有不认识的字段：$key")
        }
    }

    private fun clip(text: String, max: Int): String =
        if (text.length <= max) text else text.substring(0, max)

    /**
     * 序列化后的 UTF-8 字节数。
     *
     * 帧上限按字节算，中文字符是 3 字节 —— 用字符数当预算会让"看起来没超"的目录
     * 在真的发出去时被整帧丢掉，而现象和修复前完全一样（一条空结果）。
     */
    private fun utf8Bytes(value: JSONObject): Int = value.toString().toByteArray(Charsets.UTF_8).size

    /**
     * 失败文案。
     *
     * **刻意不拼异常自己的 message**：那条路上流的是第三方服务器返回的原文
     * （JSON-RPC error.message 是对方自由填的），把它接进我们的失败文案，
     * 就等于让一台第三方服务器往模型的上下文里塞任意一段话。
     * 这里只用闭集分类 [McpErrorKind.label] 与 HTTP 状态 / RPC 码这种结构化定位信息。
     * 仓库里的 `LogRedactionTest` 有静态扫描守着这条（主源码里不许出现 `*.message`）。
     */
    private fun describe(error: McpClientException): String {
        val where = when {
            error.httpStatus != null -> "（HTTP ${error.httpStatus}）"
            error.rpcCode != null -> "（JSON-RPC 码 ${error.rpcCode}）"
            else -> ""
        }
        return "第三方 MCP 服务器：${error.kind.label}$where"
    }

    private fun codeFor(kind: McpErrorKind): String = when (kind) {
        McpErrorKind.SERVER_UNAVAILABLE -> ToolResultErrorCode.TOOL_UNAVAILABLE
        McpErrorKind.NOT_AUTHORIZED -> ToolResultErrorCode.NOT_PERMITTED
        McpErrorKind.INVALID_ARGUMENTS -> ToolResultErrorCode.INVALID_ARGUMENTS
        McpErrorKind.HTTP_ERROR,
        McpErrorKind.PROTOCOL_ERROR,
        McpErrorKind.TIMEOUT,
        McpErrorKind.RESULT_TOO_LARGE,
        McpErrorKind.SERVER_REJECTED,
        -> ToolResultErrorCode.TOOL_FAILED
    }

    /** [code] 只取自 [ToolResultErrorCode]；[CapabilityDispatcher] 收口时还会再收敛一次闭集。 */
    private fun failure(code: String, message: String): CapabilityResult =
        CapabilityResult.Failure(code, message)

    /** 参数不合法：不继承 [McpClientException]，因为它是本地校验的结果，与远端无关。 */
    private class InvalidArguments(message: String) : Exception(message)

    private companion object {
        /**
         * 没有 request_id 时的占位（直接调 [route] 的单测/诊断场景）。
         *
         * 刻意不是一个 UUID：日志里要能一眼看出"这条不是服务端发来的请求"，
         * 而不是多出一批在服务端日志里永远对不上的 id。
         */
        const val NO_REQUEST_ID = "local"

        /** 后台刷新线程：daemon + 可读的名字，进程退出时不会吊住 JVM。 */
        fun daemonThreads(prefix: String): ThreadFactory = ThreadFactory { runnable ->
            Thread(runnable, "$prefix-${THREAD_IDS.getAndIncrement()}").apply { isDaemon = true }
        }

        val THREAD_IDS = AtomicInteger()

        val ALLOWED_CATALOG_KEYS =
            setOf("server_id", "offset", "limit", "include_schema", "servers_only", "refresh")
        val ALLOWED_SCHEMA_KEYS = setOf("server_id", "tool_name")
        val ALLOWED_CALL_KEYS = setOf("server_id", "tool_name", "arguments")

        /** offset 的硬上限：只是防"请求一个天文数字"这种无意义输入，夹紧即可。 */
        const val MAX_OFFSET = 10_000

        /** schema 过大时最多回报多少个顶层参数名。 */
        const val MAX_PROPERTY_NAMES = 32
    }
}
