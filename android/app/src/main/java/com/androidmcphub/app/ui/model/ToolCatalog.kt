package com.androidmcphub.app.ui.model

/**
 * 本机工具的**只读展示目录**。
 *
 * ── 为什么是"镜像"而不是"直接读注册表" ──────────────────────────────────────
 * 手机侧真正决定"什么能被执行"的，是 `service/PhoneLinkService.kt:214` 里那一个构造：
 *
 *     capabilityDispatcher = CapabilityDispatcher(
 *         capabilities = listOf(BatteryCapability(AndroidBatterySource(this))),
 *         policy = { prefs.capabilitiesEnabled() },
 *     )
 *
 * 它就是**执行白名单**，而且按红线要求本文件不许去改 `capability` 目录与
 * `PhoneLinkService.kt`。所以界面**不新造可执行白名单**，只维护一份
 * "给人看的说明"（分类、文案），并对齐同一个工具名。
 *
 * 漂移由 `ToolCatalogTest` 兜住：它用**真实的** `BatteryCapability` 构造一个
 * `CapabilityDispatcher`，断言这里的名字集合与 `dispatcher.names()` 完全一致 ——
 * 一旦将来能力改名而这里忘了改，单测立刻红。
 *
 * ── 边界（红线）──────────────────────────────────────────────────────────
 *  * 不新增任何执行入口：本目录只有数据，没有任何 `execute` 调用；
 *  * 不改 `CapabilityDispatcher` 的七步校验、不改白名单；
 *  * `phone_ping` **不在**这里：它是连接诊断通道（`PingResponder`），
 *    不是本机 Capability，把它列进工具目录等于把诊断伪装成工具。
 */
object ToolCatalog {

    /** 诊断通道的工具名：出现在日志/ping 卡片里，但**不属于**本机能力目录。 */
    const val DIAGNOSTIC_PING: String = "phone_ping"

    /** 电池状态：本机当前唯一注册的能力（只读、零权限、零参数）。 */
    val BatteryGetStatus = ToolDescriptor(
        id = "battery_get_status",
        name = "battery_get_status",
        description = "查询手机电量百分比、是否在充电、充电来源与省电模式。只读、不需要任何权限。",
        category = ToolCategory.Device,
        source = ToolSource.BuiltIn,
        arguments = emptyList(),
        requiresPermission = false,
        readOnly = true,
    )

    /**
     * 本机能力目录（当前只有一项 —— 如实反映注册表现状，不为了让页面"好看"而补条目）。
     *
     * 加新工具的**正确顺序**：先在 `capability/` 里加能力并注册进
     * `PhoneLinkService.kt:214` 的白名单（需另行批准），再在这里补一条展示说明；
     * 只加这里的一条数据不会让它变成可执行的工具。
     */
    val local: List<ToolDescriptor> = listOf(BatteryGetStatus)

    /** 本机可执行能力名的展示镜像（有序，便于断言）。 */
    val localNames: List<String> = local.map { it.name }.sorted()

    /** 全部来源的工具（本轮 == 本机工具；将来聚合第三方 MCP 后这里会合流）。 */
    val all: List<ToolDescriptor> = local

    /** 按 id 取一个工具（详情页用）。 */
    fun byId(id: String): ToolDescriptor? = all.firstOrNull { it.id == id }
}
