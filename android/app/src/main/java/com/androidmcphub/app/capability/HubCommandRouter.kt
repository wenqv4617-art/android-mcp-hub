package com.androidmcphub.app.capability

import org.json.JSONObject

/**
 * Gate 3：**聚合命令**的入口。
 *
 * 为什么要有这个接口，而不是把第三方工具直接做成 [Capability]：
 *
 *  * 第三方工具**不在编译期白名单里**。它们的名字来自远端 `tools/list`，
 *    数量、schema、描述都会被服务器随时改掉。把远端数据塞进
 *    [CapabilityDispatcher] 的静态注册表，等于让远端决定"本机能执行什么"。
 *  * 第三方工具的执行**必须经过用户逐条授权**（见 `mcp.store.McpAuthorizationStore`，
 *    授权绑定 (服务器, 工具名, 指纹) 三元组）。
 *    [CapabilityPolicy] 只有"这个能力名现在能不能跑"这一层语义，表达不了指纹。
 *
 * 因此分界线画在这里：
 *  * 协议上，第三方工具的调用链**永远只走三个保留命令名**：
 *    `hub_list_tools`（Catalog 目录）→ `hub_get_tool_schema`（Guide 详情）→
 *    `hub_call_tool`（Execute 执行）。它们出现的**位置固定**、名字永不变；
 *  * 第三方工具的**真实身份**只在 `arguments` 与返回值里出现，绝不进入本机能力命名空间。
 *
 * ## 实现者必须保证（由 `HubCommandRouterTest` 与 `McpHubRouterTest` 钉住）
 *
 *  1. [handles] 只对**协议里约定的那几个保留命令名**（目录 / 详情 / 执行）返回 true；
 *     不得对任何第三方工具名返回 true，否则第三方工具就借道绕过了本机白名单。
 *  2. [route] 必须自己完成**全部**校验：服务器是否存在且已启用、工具是否已授权且指纹未变、
 *     参数是否符合该工具的 `inputSchema`。分发器不替它做这些检查。
 *  3. 返回的 [CapabilityResult.Success.payload] 里只放**结果**，不放信封字段
 *     （`type` / `ok` / `request_id` / `device_id` 会被分发器剔除，这里再挡一层）。
 *  4. 失败时 [CapabilityResult.Failure.code] 只能取自
 *     `protocol.ToolResultErrorCode`（第三方工具没有独立的错误码空间）。
 *  5. [route] 不得抛出异常：远端的一切都不可信，异常应就地转成失败结果。
 *     分发器另有一层兜底（抛异常 → `tool_failed`），但那不该是常态。
 */
interface HubCommandRouter {

    /** @return 这个工具名是否是**本机保留**的聚合命令。 */
    fun handles(toolName: String): Boolean

    /**
     * 处理一条聚合命令。
     *
     * @param toolName 已经过 [handles] 确认的保留命令名。
     * @param arguments 对端给的参数（可能包含任意垃圾；多余字段、超长文本、非法类型都由实现拒绝）。
     */
    fun route(toolName: String, arguments: JSONObject): CapabilityResult

    /**
     * 带 `request_id` 的入口：只用于**诊断关联**，不参与任何判定。
     *
     * 默认实现直接转调 [route]，所以不认识 request_id 的实现（以及所有测试替身）
     * 不需要改。实现者唯一需要遵守的是：这个 id 只能出现在日志里，
     * 不得进入返回值、不得进入任何与第三方的交互。
     */
    fun routeWithId(requestId: String, toolName: String, arguments: JSONObject): CapabilityResult =
        route(toolName, arguments)

    /**
     * 释放实现持有的资源（线程池、缓存）。默认无操作。
     *
     * 分发器在 Android 前台服务销毁时调用一次；必须幂等，且**不得**等待正在进行的
     * 远端操作 —— 那些操作各自有超时，会在自己的期限内退出。
     */
    fun close() = Unit
}
