package com.androidmcphub.app.aggregation

/**
 * 聚合层对外暴露的量上限。
 *
 * 为什么这些数字必须与 `worker/src/mcp/hub.ts` 里的 `HUB_LIMITS` 对齐：
 * 目录要走"手机 → WebSocket 帧 → Worker → ChatGPT"这条路，两端的上限只要不一致，
 * 就一定会出现"手机认为合法地发出去、Worker 认为超限地丢掉"的半截结果 ——
 * 用户看到的是工具列表莫名其妙少了几项，而不是任何一条错误信息。
 * 因此：**Worker 侧的上限是硬约束，这里只允许等于或更小**（手机先截断，语义可控）。
 *
 * 单位：[maxArgumentChars] / [maxSchemaChars] / [maxTextChars] 等未注明者均为
 * 字符数（UTF-8 下与字节数不同，字符数是给"模型可读"的量），
 * [maxTotalTextChars] 是多个内容块加起来的字符数总预算。
 */
data class HubLimits(
    /** 最多报告多少台服务器（超出的只计数）。 */
    val maxServers: Int = 8,
    /**
     * 一次目录扫描最多收集多少个工具（超出的只计数）。
     *
     * 这个值不再是"一次返回多少"——返回量由 [maxToolsPerPage] 与分页参数决定。
     * 它只是收集阶段的内存上限：单台 35 个工具、三台服务器都能完整进入分页。
     */
    val maxTools: Int = 200,
    /** 一页默认返回多少工具。 */
    val maxToolsPerPage: Int = 50,
    /** 调用方可以请求的最大页大小。 */
    val maxPageSize: Int = 100,
    /**
     * 目录 JSON（不含 input_schema）的 **UTF-8 字节**预算，必须明显小于 16 KiB 的帧上限。
     *
     * 为什么是字节而不是字符：帧的上限（`CapabilityDispatcher.maxResultBytes`）按字节算，
     * 而中文在 UTF-8 里是 3 字节。按字符预算的话，一份 12,000 "字符"的中文目录可以是
     * 36 KB —— 照样被整帧丢弃，而且表现和没改之前一模一样。
     */
    val maxCatalogBytes: Int = 12_000,
    /** 目录里单条描述的字符上限；完整描述仍留在手机上（详情页），不出现在目录里。 */
    val maxListDescription: Int = 200,
    /** `hub_get_tool_schema` 单次返回 schema 的字符上限。 */
    val maxSchemaResponseChars: Int = 8_000,
    /** 显示名上限。 */
    val maxDisplayName: Int = 128,
    /** 描述上限（本地详情页展示用）。 */
    val maxDescription: Int = 512,
    /** inputSchema 序列化后的字符上限，超出则整块丢弃（宁可不给 schema，也不给半截 schema）。 */
    val maxSchemaChars: Int = 4096,
    /** 一次工具调用 arguments 的序列化字符上限。 */
    val maxArgumentChars: Int = 4096,
    /** arguments 的最大嵌套层数（根对象算第 0 层）。 */
    val maxArgumentDepth: Int = 4,
    /** arguments 的节点总数（对象 + 数组 + 标量）。 */
    val maxArgumentNodes: Int = 64,
    /** arguments 里单个字符串的长度上限。 */
    val maxArgumentString: Int = 1024,
    /** arguments 里单个键名的长度上限。 */
    val maxArgumentKeyLength: Int = 64,
    /** arguments 里单个数组的元素个数上限。 */
    val maxArgumentArrayLength: Int = 32,
    /** 一次调用最多回传多少个内容块。 */
    val maxContentItems: Int = 16,
    /** 单个文本内容块的字符上限。 */
    val maxTextChars: Int = 8192,
    /** 所有文本内容块加起来的字符上限。 */
    val maxTotalTextChars: Int = 24576,
    /** structured_content 序列化后的字符上限。 */
    val maxStructuredChars: Int = 8192,
    /** 服务器名上限（与 serverId 分开：名字是给人看的，允许长一点）。 */
    val maxServerName: Int = 128,
    /** 单台服务器一次操作的超时预算（毫秒）。 */
    val perServerTimeoutMs: Long = 8_000,

    // ---- 目录缓存与刷新（V1 性能专项）------------------------------------
    //
    // 这几个数字解决的是同一件事：**一次目录请求不该等所有第三方服务器**。
    // 修复前每次 `hub_list_tools` 都会串行地对每台服务器 `connect()`，一台离线
    // 服务器就要吃掉 [perServerTimeoutMs]，三台服务器串起来正好是实测的 13.6 秒。

    /**
     * 一次目录请求**等待刷新完成**的最长时间（毫秒）。
     *
     * 它不是"超时时间变长" —— 恰恰相反：旧实现的 `totalBudgetMs` 是 20 秒，而且
     * 只在两台服务器**之间**检查，单台就能吃掉 8 秒。现在这里是整次请求的硬上限：
     * 到点就用缓存里已经有的结果返回，没答上来的服务器如实标成上次已知状态。
     */
    val catalogDeadlineMs: Long = 3_500,

    /** 工具目录的缓存有效期（毫秒）。有效期内直接读缓存，一个网络包都不发。 */
    val catalogTtlMs: Long = 30_000,

    /** 服务器可用性（只探测不枚举）的缓存有效期（毫秒）。 */
    val availabilityTtlMs: Long = 30_000,

    /**
     * 一台服务器刷新失败后的冷却时间（毫秒）。
     *
     * 没有它，每次目录请求都会去重连一台已经挂掉的服务器 —— 那正是"刷新风暴"。
     */
    val serverFailureBackoffMs: Long = 15_000,

    /** 同时刷新的服务器数上限。并发不是越大越好：手机的网络与 CPU 都要留给前台。 */
    val refreshConcurrency: Int = 3,
)
