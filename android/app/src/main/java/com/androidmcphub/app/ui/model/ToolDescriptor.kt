package com.androidmcphub.app.ui.model

/**
 * 工具分类。
 *
 * 只影响**列表筛选**，不影响可执行性 —— 分类是展示层的概念，
 * 协议里没有这个东西（服务端也不需要知道）。
 */
enum class ToolCategory(val label: String) {
    Device("设备"),
    File("文件"),
    Media("媒体"),
    Notification("通知"),
    Other("其他"),
}

/**
 * 工具来源。
 *
 * 本轮只有 [BuiltIn] 是真实存在的：另外两种是**为将来的聚合预留的枚举位**，
 * 界面上不会凭空造出属于它们的条目（见 `ToolCatalog`）。
 */
enum class ToolSource(val label: String) {
    /** 本机 Android 能力（`capability/` 里注册的只读能力）。 */
    BuiltIn("本机能力"),

    /** 将来：第三方 MCP 服务器提供的工具。 */
    ThirdPartyMcp("第三方 MCP"),

    /** 将来：本机 HTTP MCP 中转提供的工具。 */
    LocalHttpMcp("本地 HTTP MCP"),
}

/**
 * 工具此刻的可用性。
 *
 * 它由**运行期两个事实**推导：[ToolDescriptor.availableFromBuild]（这个版本里有没有）
 * 与用户的能力总开关（此刻允不允许）。两者都不是"猜"的，界面不自己编状态。
 */
enum class ToolAvailability(val label: String) {
    /** 已启用：总开关打开、能力就在注册表里。 */
    Enabled("已启用"),

    /** 总开关关闭：能力仍然注册着（协议上认得），但一条都不会被执行。 */
    SwitchOff("总开关已关闭"),

    /** 需要授权：标了权限要求、而权限尚未满足（本轮没有这种工具，留作将来）。 */
    NeedsPermission("需要授权"),

    /** 未开放：这个版本里还没有实现（不伪造入口）。 */
    ComingSoon("未开放"),
}

/** 一个工具参数（用于详情页展示 schema）。 */
data class ToolArgument(
    val name: String,
    val type: String,
    val required: Boolean,
    val description: String,
)

/**
 * 工具的**展示模型**。
 *
 * 为什么工具页必须由它驱动（而不是 `when (toolName)`）：
 *  * 工具会变多。每加一个工具就加一个 `when` 分支，页面迟早变成 50 个分支的巨型文件；
 *  * 展示模型能让"普通工具统一卡片 + 统一详情模板"，只有确实特殊的工具才另开页面。
 *
 * [name] 是**协议里的工具名**（必须与服务端/注册表逐字一致），[id] 是界面用主键
 * （当前与 name 相同，将来聚合第三方工具时可能出现"同名不同源"，那时 id 带前缀）。
 */
data class ToolDescriptor(
    val id: String,
    /** 与执行层注册表一致的工具名；不得凭展示需要改写。 */
    val name: String,
    /** 一句话说明（给人看）。 */
    val description: String,
    val category: ToolCategory,
    val source: ToolSource,
    /** 参数表；空列表表示"不接受任何参数"。 */
    val arguments: List<ToolArgument> = emptyList(),
    /** 编译期是否可用（本版本里是否真的注册了这个能力）。 */
    val availableFromBuild: Boolean = true,
    /** 是否需要危险权限（需要则在 UI 上明确标出）。 */
    val requiresPermission: Boolean = false,
    /** 权限说明（例如"通知权限"）；[requiresPermission] 为 true 时不该为空。 */
    val permissionLabel: String? = null,
    /** 是否只读、无副作用。 */
    val readOnly: Boolean = true,
) {

    /** 结合"用户此刻的授权总开关"推导出的可用性。 */
    fun availability(capabilitiesEnabled: Boolean): ToolAvailability = when {
        !availableFromBuild -> ToolAvailability.ComingSoon
        !capabilitiesEnabled -> ToolAvailability.SwitchOff
        requiresPermission -> ToolAvailability.NeedsPermission
        else -> ToolAvailability.Enabled
    }

    /** 搜索命中：工具名或说明里包含关键字（忽略大小写）。 */
    fun matches(query: String): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return true
        return name.contains(q, ignoreCase = true) ||
            id.contains(q, ignoreCase = true) ||
            description.contains(q, ignoreCase = true)
    }
}

/**
 * 工具目录的**纯筛选逻辑**（可 JVM 单测，不依赖 Compose）。
 *
 * @param category null = 全部分类。
 */
fun filterTools(
    tools: List<ToolDescriptor>,
    query: String,
    category: ToolCategory?,
    source: ToolSource? = null,
): List<ToolDescriptor> = tools.filter { tool ->
    (category == null || tool.category == category) &&
        (source == null || tool.source == source) &&
        tool.matches(query)
}
