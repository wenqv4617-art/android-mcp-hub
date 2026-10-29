package com.androidmcphub.app.aggregation

/**
 * 本机的**保留命令名**。
 *
 * 服务端要用两个稳定的入口来"发现第三方工具"和"调用第三方工具"，而这两个入口
 * 最终是以 `tool_request` 帧的形式推到手机上执行的。手机侧的执行入口是
 * `CapabilityDispatcher` 的白名单 —— 本机能力（电量、网络、存储）都在那份白名单里。
 *
 * 这里刻意**不**把聚合入口注册成本机能力，理由有两条：
 *  1. 本机能力的语义是"在一台 Android 设备上读一个只读属性"，而聚合入口的语义是
 *     "去网络上执行一个用户自己填进来的第三方服务器上的工具"。把后者塞进前者的
 *     注册表，会让"这个能力能读哪些系统 API"这类审计变得毫无意义；
 *  2. 白名单里多一个名字，就多一条"服务端可以要求手机去访问任意外网地址"的路径。
 *     聚合入口单独走一条前置分支（见 `CapabilityDispatcher`），它的授权模型是
 *     **逐工具**的用户授权（[com.androidmcphub.app.mcp.store.McpAuthorizationStore]），
 *     而不是能力开关。
 *
 * 因此这两个名字属于"保留给聚合层"的命名空间：它们永远不会出现在
 * `BuiltInCapabilities.ALL` 里，任何第三方工具名也永远不会进入这个命名空间 ——
 * 第三方工具的身份始终是 `mcp:<服务器>:<工具>`（见 [HubIdentity.toolId]）。
 */
object HubCommands {

    /** 发现：分页列出已授权且可用的第三方工具（默认不带 schema）。 */
    const val LIST_TOOLS = "hub_list_tools"

    /** 参数结构：按需取**一个**已授权工具的最新 inputSchema。 */
    const val GET_TOOL_SCHEMA = "hub_get_tool_schema"

    /** 调用：执行一个**已被用户授权**的第三方工具。 */
    const val CALL_TOOL = "hub_call_tool"

    /** 保留命令名的全集。 */
    val ALL: Set<String> = setOf(LIST_TOOLS, GET_TOOL_SCHEMA, CALL_TOOL)

    fun isHubCommand(name: String): Boolean = name in ALL
}
