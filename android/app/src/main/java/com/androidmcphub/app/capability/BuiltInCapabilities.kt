package com.androidmcphub.app.capability

import android.content.Context

/**
 * 三个本机数据来源的集合。
 *
 * 存在的理由：让"注册清单"能在**没有 Android Context** 的地方被构造出来
 * （JVM 单测里换成假来源），从而让"注册清单 → 执行白名单"与
 * "注册清单 → 界面目录"这两条派生链都能在单测里跑。
 */
data class NativeSources(
    val battery: BatterySource,
    val network: NetworkSource,
    val storage: StorageSource,
)

/**
 * 一条内置能力 = **元数据** + 用数据来源构造出可执行实例的工厂。
 *
 * 工厂而不是现成实例：`Capability` 需要一个具体的数据来源（真机上是
 * `ConnectivityManager` 之类），而元数据必须在没有 Context 的地方也能读到。
 * 两者放在同一个对象里，是"单一可信来源"的实现方式 —— 想加一个工具，
 * 只可能在 [BuiltInCapabilities.ALL] 这一处加一行。
 */
class BuiltInCapability(
    val metadata: CapabilityMetadata,
    private val factory: (NativeSources) -> Capability,
) {
    fun create(sources: NativeSources): Capability = factory(sources)
}

/**
 * 内置能力的**唯一注册清单**。
 *
 * ── 它是什么、不是什么 ─────────────────────────────────────────────────────
 *  * 是：这一版 App 里有哪些本机能力，以及它们各自的展示元数据；
 *  * 不是：运行期授权。用户的能力总开关仍然是 `HubPrefs.capabilitiesEnabled()`，
 *    在每个请求上实时读取（`CapabilityPolicy`）；
 *  * 不是：动态注册表。没有运行期注册/注销接口 —— 能执行的东西在编译期就定下来，
 *    这一点没有因为本轮的"统一目录"而改变。
 *
 * ── 两条派生链（同一份数据）────────────────────────────────────────────────
 *      BuiltInCapabilities.ALL
 *          ├── create(sources)  → CapabilityDispatcher(capabilities = ...)  执行
 *          └── metadata         → ui/model/ToolCatalog → 工具页目录         展示
 *
 * 于是"界面说有的工具"与"真正能执行的工具"不可能各说各话：
 *  * `CapabilityDispatcher` 构造时断言 `metadata.name == capability.name`；
 *  * `ToolCatalog` 直接由 [metadata] 生成，不再手写第二份清单；
 *  * `ToolCatalogTest` 用真实注册表比对 `dispatcher.names()` 与目录的 id 集合。
 */
object BuiltInCapabilities {

    /**
     * 唯一注册清单（顺序稳定，便于断言与展示）。
     *
     * 加新工具的**完整步骤**：①在这个类里加一行（元数据 + 工厂）；
     * ②在 `protocol` 之外的 Worker 注册表（`worker/src/mcp/tools.ts`）里加同名工具；
     * ③Worker 侧补清单测试。除此之外没有任何"注册到 UI"的步骤。
     */
    val ALL: List<BuiltInCapability> = listOf(
        BuiltInCapability(BatteryCapability.METADATA) { BatteryCapability(it.battery) },
        BuiltInCapability(NetworkCapability.METADATA) { NetworkCapability(it.network) },
        BuiltInCapability(StorageCapability.METADATA) { StorageCapability(it.storage) },
    )

    /** 注册清单里每个能力的元数据（UI 目录与契约测试的数据源）。 */
    val metadata: List<CapabilityMetadata> = ALL.map { it.metadata }

    /** 注册清单里的工具名（有序）。 */
    val names: List<String> = metadata.map { it.name }.sorted()

    /** 用给定的数据来源构造全部能力（真机用 [androidCapabilities]，单测用假来源）。 */
    fun create(sources: NativeSources): List<Capability> = ALL.map { it.create(sources) }

    /** 真机上的三个数据来源。 */
    fun androidSources(context: Context): NativeSources = NativeSources(
        battery = AndroidBatterySource(context),
        network = AndroidNetworkSource(context),
        storage = AndroidStorageSource(context),
    )

    /**
     * 真机上的本机能力列表 —— `service/PhoneLinkService.kt` 构造
     * `CapabilityDispatcher` 时就传它。这是**唯一**的注册入口。
     */
    fun androidCapabilities(context: Context): List<Capability> = create(androidSources(context))
}
