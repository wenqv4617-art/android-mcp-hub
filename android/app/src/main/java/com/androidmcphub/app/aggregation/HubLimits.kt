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
    /** 最多报告多少个工具（超出的只计数）。 */
    val maxTools: Int = 32,
    /** 显示名上限。 */
    val maxDisplayName: Int = 128,
    /** 描述上限。 */
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
    /** 一次目录刷新/调用在所有服务器上花掉的总预算（毫秒）。 */
    val totalBudgetMs: Long = 20_000,
)
