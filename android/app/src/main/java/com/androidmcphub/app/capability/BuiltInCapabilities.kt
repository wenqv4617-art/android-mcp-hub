package com.androidmcphub.app.capability

import android.content.Context

/**
 * 本机能力的数据来源集合。
 *
 * 存在的理由：让"注册清单"能在**没有 Android Context** 的地方被构造出来
 * （JVM 单测里换成假来源 / 空来源），从而让"注册清单 → 执行白名单"与
 * "注册清单 → 界面目录"这两条派生链都能在单测里跑。
 *
 * 新增来源时**必须给默认值**，且默认值只能是 [UnavailableSources] 里的空来源：
 * 这保证老的 JVM 测试不需要为每个新能力补一段与它无关的构造代码。
 */
data class NativeSources(
    val battery: BatterySource,
    val network: NetworkSource,
    val storage: StorageSource,
    val usageStats: UsageStatsSource = UnavailableUsageStatsSource,
    val deviceStatus: DeviceStatusSource = UnavailableDeviceStatusSource,
    val deviceControl: DeviceControlSource = UnavailableDeviceControlSource,
    val alarms: AlarmSource = UnavailableAlarmSource,
    val memos: JsonStore = MemoryJsonStore("memo_"),
    val todos: JsonStore = MemoryJsonStore("todo_"),
    val reminders: JsonStore = MemoryJsonStore("reminder_"),
    val reminderScheduler: ReminderScheduler = UnavailableReminderScheduler,
    val calendar: CalendarSource = UnavailableCalendarSource,
    val calendarOwnership: CalendarOwnershipStore = EmptyCalendarOwnershipStore,
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
 *    写入类还要再过 `writeCapabilitiesEnabled()` 与逐工具开关，在每个请求上实时读取；
 *  * 不是：动态注册表。没有运行期注册/注销接口 —— 能执行的东西在编译期就定下来。
 *
 * ── 两条派生链（同一份数据）────────────────────────────────────────────────
 *      BuiltInCapabilities.ALL
 *          ├── create(sources)  → CapabilityDispatcher(capabilities = ...)  执行
 *          └── metadata         → ui/model/ToolCatalog → 工具页目录         展示
 *
 * ── 授权分级 ──────────────────────────────────────────────────────────────
 *  * 只读能力：总开关打开即可执行（`readOnly = true`）；
 *  * 写入能力：写入总开关 + 逐工具开关（默认关）都打开才执行；
 *  * 删除/覆盖：执行层还要求调用方传 `confirm=true`（`destructive = true`）；
 *  * 系统运行期权限（日历、使用情况、相机、通知）：能力自己在 execute 里检查，
 *    缺失时回 `permission_denied`，并告诉用户去哪开。
 */
object BuiltInCapabilities {

    /**
     * 唯一注册清单（顺序稳定，便于断言与展示）。
     *
     * 加新工具的**完整步骤**：①在这个类里加一行（元数据 + 工厂）；
     * ②在 `worker/src/mcp/tools.ts` 的注册表里加同名工具（含参数白名单与 schema）；
     * ③Worker 侧补清单测试。除此之外没有任何"注册到 UI"的步骤。
     */
    val ALL: List<BuiltInCapability> = listOf(
        // ---- Gate 2：基础只读能力 ----
        BuiltInCapability(BatteryCapability.METADATA) { BatteryCapability(it.battery) },
        BuiltInCapability(NetworkCapability.METADATA) { NetworkCapability(it.network) },
        BuiltInCapability(StorageCapability.METADATA) { StorageCapability(it.storage) },

        // ---- Phase 3A：使用情况与设备状态 ----
        BuiltInCapability(UsageStatsCapability.METADATA) { UsageStatsCapability(it.usageStats) },
        BuiltInCapability(DeviceStatusCapability.METADATA) { DeviceStatusCapability(it.deviceStatus) },

        // ---- Phase 3B：闹钟与本地提醒 ----
        BuiltInCapability(AlarmCreateCapability.METADATA) { AlarmCreateCapability(it.alarms) },
        BuiltInCapability(AlarmOpenListCapability.METADATA) { AlarmOpenListCapability(it.alarms) },
        BuiltInCapability(ReminderCreateCapability.METADATA) {
            ReminderCreateCapability(it.reminders, it.reminderScheduler)
        },
        BuiltInCapability(ReminderListCapability.METADATA) { ReminderListCapability(it.reminders) },
        BuiltInCapability(ReminderUpdateCapability.METADATA) {
            ReminderUpdateCapability(it.reminders, it.reminderScheduler)
        },
        BuiltInCapability(ReminderCompleteCapability.METADATA) {
            ReminderCompleteCapability(it.reminders, it.reminderScheduler)
        },
        BuiltInCapability(ReminderDeleteCapability.METADATA) {
            ReminderDeleteCapability(it.reminders, it.reminderScheduler)
        },

        // ---- Phase 3C：备忘 ----
        BuiltInCapability(MemoCapability.METADATA) { MemoCapability(it.memos) },
        BuiltInCapability(MemoListCapability.METADATA) { MemoListCapability(it.memos) },
        BuiltInCapability(MemoGetCapability.METADATA) { MemoGetCapability(it.memos) },
        BuiltInCapability(MemoUpdateCapability.METADATA) { MemoUpdateCapability(it.memos) },
        BuiltInCapability(MemoDeleteCapability.METADATA) { MemoDeleteCapability(it.memos) },

        // ---- Phase 3C：待办 ----
        BuiltInCapability(TodoCreateCapability.METADATA) { TodoCreateCapability(it.todos) },
        BuiltInCapability(TodoListCapability.METADATA) { TodoListCapability(it.todos) },
        BuiltInCapability(TodoUpdateCapability.METADATA) { TodoUpdateCapability(it.todos) },
        BuiltInCapability(TodoCompleteCapability.METADATA) { TodoCompleteCapability(it.todos) },
        BuiltInCapability(TodoDeleteCapability.METADATA) { TodoDeleteCapability(it.todos) },

        // ---- Phase 3D：日历 ----
        BuiltInCapability(CalendarListCalendarsCapability.METADATA) {
            CalendarListCalendarsCapability(it.calendar)
        },
        BuiltInCapability(CalendarListEventsCapability.METADATA) {
            CalendarListEventsCapability(it.calendar)
        },
        BuiltInCapability(CalendarCreateEventCapability.METADATA) {
            CalendarCreateEventCapability(it.calendar, it.calendarOwnership)
        },
        BuiltInCapability(CalendarUpdateEventCapability.METADATA) {
            CalendarUpdateEventCapability(it.calendar, it.calendarOwnership)
        },
        BuiltInCapability(CalendarDeleteEventCapability.METADATA) {
            CalendarDeleteEventCapability(it.calendar, it.calendarOwnership)
        },

        // ---- Phase 3E：设备控制第二批次 ----
        BuiltInCapability(SetVolumeCapability.METADATA) { SetVolumeCapability(it.deviceControl) },
        BuiltInCapability(FlashlightCapability.METADATA) { FlashlightCapability(it.deviceControl) },
        BuiltInCapability(OpenAppCapability.METADATA) { OpenAppCapability(it.deviceControl) },
        BuiltInCapability(OpenSettingsCapability.METADATA) { OpenSettingsCapability(it.deviceControl) },
        BuiltInCapability(PostNotificationCapability.METADATA) { PostNotificationCapability(it.deviceControl) },
    )

    /** 注册清单里每个能力的元数据（UI 目录与契约测试的数据源）。 */
    val metadata: List<CapabilityMetadata> = ALL.map { it.metadata }

    /** 注册清单里的工具名（有序）。 */
    val names: List<String> = metadata.map { it.name }.sorted()

    /** 用给定的数据来源构造全部能力（真机用 [androidCapabilities]，单测用假来源）。 */
    fun create(sources: NativeSources): List<Capability> = ALL.map { it.create(sources) }

    /** 真机上的数据来源。 */
    fun androidSources(context: Context): NativeSources = NativeSources(
        battery = AndroidBatterySource(context),
        network = AndroidNetworkSource(context),
        storage = AndroidStorageSource(context),
        usageStats = AndroidUsageStatsSource(context),
        deviceStatus = AndroidDeviceStatusSource(context),
        deviceControl = AndroidDeviceControlSource(context),
        alarms = AndroidAlarmSource(context),
        memos = LocalStores.memos(context),
        todos = LocalStores.todos(context),
        reminders = LocalStores.reminders(context),
        reminderScheduler = AndroidReminderScheduler(context),
        calendar = AndroidCalendarSource(context),
        calendarOwnership = LocalStores.calendarOwnership(context),
    )

    /**
     * 真机上的本机能力列表 —— `service/PhoneLinkService.kt` 构造
     * `CapabilityDispatcher` 时就传它。这是**唯一**的注册入口。
     */
    fun androidCapabilities(context: Context): List<Capability> = create(androidSources(context))
}
