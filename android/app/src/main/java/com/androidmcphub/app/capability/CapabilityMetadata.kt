package com.androidmcphub.app.capability

/**
 * 能力分类。
 *
 * 这是**元数据**，不是协议字段 —— 协议（`phone-link-v1`）里没有"分类"这个概念，
 * 服务端也不需要知道。它只影响工具页的筛选与展示。
 *
 * 分类必须如实反映能力的性质：不要为了让筛选条好看而把网络能力标成"设备"。
 */
enum class CapabilityCategory(val label: String) {
    Device("设备"),
    Network("网络"),
    Storage("存储"),
    Media("媒体"),
    Notification("通知"),
    Calendar("日历"),
    Productivity("效率"),
    Other("其他"),
}

/**
 * 一个能力的参数定义（用于生成展示用的 schema，以及将来的契约一致性测试）。
 *
 * 本轮三个能力都**不接受任何参数**，所以这个类型目前没有实际条目；
 * 留着它是因为"参数定义"是元数据契约的一部分（任务书要求），
 * 将来加带参数的能力时，参数表与执行层白名单必须是同一份数据。
 */
data class CapabilityArgument(
    val name: String,
    val type: String,
    val required: Boolean,
    val description: String,
)

/**
 * 内置能力的**元数据**。
 *
 * ── 它解决什么问题 ──────────────────────────────────────────────────────────
 * 在本轮之前，"手机侧能执行什么"（`service/PhoneLinkService.kt` 里构造
 * `CapabilityDispatcher` 时的那份 `listOf(...)`）与"界面上显示什么"
 * （`ui/model/ToolCatalog.kt` 手写的展示条目）是**两份分别维护的清单**，
 * 靠单测钉住它们别漂移。现在它们由同一份注册清单派生：
 *
 *      capability/BuiltInCapabilities.kt (一份 ALL)
 *              ├── create(sources) → 交给 CapabilityDispatcher（执行）
 *              └── metadata        → 交给 UI 工具目录（展示）
 *
 * ── 边界（红线，与之前一致）────────────────────────────────────────────────
 *  * 元数据**不是**执行依据：能执行的仍然只有 `CapabilityDispatcher` 构造时
 *    注册进去的能力对象。改这份说明不会让任何东西变得可执行；
 *  * 元数据里没有 shell、路径、URL、反射入口 —— 它只有名字、说明、分类、参数表、
 *    只读标志与权限声明，全部是给人和给契约测试看的；
 *  * "执行能力是否已注册"不在这里存一个布尔值：本对象**派生自注册清单**
 *    （见 [BuiltInCapabilities]），能取到它就代表这一版 App 里已经注册了。
 *    `CapabilityDispatcher.catalog()` 只会返回注册表里真实存在的能力。
 */
data class CapabilityMetadata(
    /** 协议里的工具名，必须与 `Capability.name`、Worker 注册表、UI 目录**逐字一致**。 */
    val name: String,
    /** 给人看的短名字（工具卡片的标题）。 */
    val displayName: String,
    /** 一句话说明（给人看，也会出现在工具详情里）。 */
    val description: String,
    val category: CapabilityCategory,
    /** 参数定义；空列表表示"不接受任何参数"。 */
    val arguments: List<CapabilityArgument> = emptyList(),
    /** 是否只读、无副作用。当前所有本机能力都是只读的。 */
    val readOnly: Boolean = true,
    /**
     * 实现这个能力所依赖的 Android 权限（Manifest 里声明的原始权限名）。
     *
     * 这里列的是**正常权限**（安装即授予）为主，用来说明"这个能力需要什么"，
     * 而不是用来做运行期检查 —— 运行期检查只有一处：能力总开关。
     */
    val declaredPermissions: List<String> = emptyList(),
    /**
     * 是否需要用户在运行期单独授权（危险权限 / 特殊权限）。
     *
     * 当前三个只读能力都是 false：它们只用正常权限或不申请权限。
     * 为 true 时，UI 不会把它显示成"已启用"，而是"需要授权"。
     */
    val requiresRuntimePermission: Boolean = false,
    /** 用户该怎么授予这个权限（给人看的一步一步说明）。 */
    val permissionHint: String? = null,
    /**
     * 这个工具会**删除/覆盖已有数据**。
     *
     * UI 用它显示醒目标记；执行层用它要求调用方传 `confirm=true`
     * （见 [com.androidmcphub.app.capability.Capability.confirmationProblem]）。
     */
    val destructive: Boolean = false,
)
