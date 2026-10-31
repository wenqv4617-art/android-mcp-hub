package com.androidmcphub.app.ui.model

import com.androidmcphub.app.capability.BuiltInCapabilities
import com.androidmcphub.app.capability.CapabilityCategory
import com.androidmcphub.app.capability.CapabilityMetadata

/**
 * 本机工具的**只读展示目录**。
 *
 * ── 数据从哪来（Gate 2.5 起）────────────────────────────────────────────────
 * 全部派生自 `capability/BuiltInCapabilities.kt` 那份**唯一注册清单**：
 *
 *     BuiltInCapabilities.ALL
 *         ├── create(sources) → CapabilityDispatcher(白名单)   执行
 *         └── metadata        → 本文件 → 工具页                 展示
 *
 * 也就是说：**界面里能看到的工具，就是注册清单里的工具**，没有任何第三处
 * 清单可以各自漂移。想把一个工具"显示出来"而去改本文件是没用的 ——
 * 条目里没有硬编码数据，只有映射。
 *
 * ── 边界（红线）──────────────────────────────────────────────────────────
 *  * 不新增任何执行入口：本目录只有数据，没有任何 `execute` 调用；
 *  * 不改 `CapabilityDispatcher` 的七步校验、不改白名单；
 *  * 不允许通过修改展示数据绕过执行授权：`availableFromBuild` 恒为 true，
 *    是否"此刻可用"由运行期的能力总开关推导（见 [ToolDescriptor.availability]）；
 *  * `phone_ping` **不在**这里：它是连接诊断通道（`PingResponder`），
 *    不是本机 Capability，把它列进工具目录等于把诊断伪装成工具。
 *
 * 漂移由 `ToolCatalogTest` 兜住：它用**真实的**注册清单构造一个
 * `CapabilityDispatcher`，断言这里的 id 集合与 `dispatcher.names()` 完全一致。
 */
object ToolCatalog {

    /** 诊断通道的工具名：出现在日志/ping 卡片里，但**不属于**本机能力目录。 */
    const val DIAGNOSTIC_PING: String = "phone_ping"

    /**
     * 本机能力目录（按工具名排序，与注册清单一一对应）。
     *
     * 加新工具只需在 `capability/BuiltInCapabilities.ALL` 里加一行，
     * 这里会自动多出一条 —— 没有"顺手补一条展示数据"这种步骤，
     * 也就不会出现"界面说有个工具、其实没注册"的情况。
     */
    val local: List<ToolDescriptor> = BuiltInCapabilities.metadata
        .map { it.toToolDescriptor() }
        .sortedBy { it.name }

    /** 本机可执行能力名的展示镜像（有序，便于断言）。 */
    val localNames: List<String> = local.map { it.name }.sorted()

    /** 全部来源的工具（本轮 == 本机工具；将来聚合第三方 MCP 后这里会合流）。 */
    val all: List<ToolDescriptor> = local

    /** 按 id 取一个工具（详情页用）。 */
    fun byId(id: String): ToolDescriptor? = all.firstOrNull { it.id == id }

    /**
     * 目录里**实际出现**的分类（有序，按 [ToolCategory] 声明顺序）。
     *
     * 工具页的筛选条只画这些分类：7 个分类里只注册了 3 个能力时，
     * 画 4 个点了没反应的空分类是在制造噪音（"未实现"应当由内容如实说明，
     * 而不是靠一排灰掉的按钮）。
     */
    fun categoriesInUse(): List<ToolCategory> =
        ToolCategory.entries.filter { entry -> local.any { it.category == entry } }
}

/**
 * 注册清单元数据 → 展示模型。
 *
 * 这是**唯一的**转换点：字段要么来自元数据，要么是这里写死的常量
 * （`source = BuiltIn`、`availableFromBuild = true`）。写死这两个常量是刻意的：
 * 本文件里的条目全部来自内置注册清单，它们当然属于本机来源、当然随版本发布。
 */
internal fun CapabilityMetadata.toToolDescriptor(): ToolDescriptor = ToolDescriptor(
    id = name,
    name = name,
    description = description,
    category = category.toToolCategory(),
    source = ToolSource.BuiltIn,
    arguments = arguments.map { ToolArgument(it.name, it.type, it.required, it.description) },
    availableFromBuild = true,
    // 需要用户当场批准的运行时权限才算"被拦住"；普通权限（安装时授予）
    // 只作为"声明权限"信息展示，不影响可用性。
    requiresPermission = requiresRuntimePermission,
    permissionLabel = declaredPermissions
        .takeIf { requiresRuntimePermission }
        ?.joinToString("、") { it.substringAfterLast('.') }
        ?.ifEmpty { null },
    permissionHint = permissionHint,
    readOnly = readOnly,
    requiresConfirmation = destructive,
    declaredPermissions = declaredPermissions,
)

/** 能力层的分类 → 展示层分类。两个枚举分开，避免展示层反过来定义执行层。 */
internal fun CapabilityCategory.toToolCategory(): ToolCategory = when (this) {
    CapabilityCategory.Device -> ToolCategory.Device
    CapabilityCategory.Network -> ToolCategory.Network
    CapabilityCategory.Storage -> ToolCategory.Storage
    CapabilityCategory.Media -> ToolCategory.Media
    CapabilityCategory.Notification -> ToolCategory.Notification
    CapabilityCategory.Calendar -> ToolCategory.Calendar
    CapabilityCategory.Productivity -> ToolCategory.Productivity
    CapabilityCategory.Other -> ToolCategory.Other
}
