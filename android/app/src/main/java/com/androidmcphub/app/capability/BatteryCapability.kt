package com.androidmcphub.app.capability

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import com.androidmcphub.app.protocol.ToolResultErrorCode
import org.json.JSONObject

/**
 * 一次电池读取的**原始**值：直接来自 Android API 的枚举，尚未解释。
 *
 * 为什么不直接把 `Intent` 交给 [BatteryCapability]：那样的话"百分比怎么算、
 * charging 什么时候是 null"这些真正容易出错的判断就没法在 JVM 单测里跑，
 * 只能上真机碰运气。把原始值抠出来之后，解释逻辑就是纯函数。
 *
 * 所有字段都是可空的，`null` 的含义统一是"系统没有提供这一项"
 * —— 与"提供了 0 / false"严格区分。
 */
data class RawBatteryReading(
    /** `BatteryManager.EXTRA_LEVEL`。 */
    val level: Int?,
    /** `BatteryManager.EXTRA_SCALE`。 */
    val scale: Int?,
    /** `BatteryManager.EXTRA_STATUS`（`BATTERY_STATUS_*`）。 */
    val status: Int?,
    /** `BatteryManager.EXTRA_PLUGGED`（`BATTERY_PLUGGED_*` 的位组合）。 */
    val plugged: Int?,
    /** `PowerManager.isPowerSaveMode`。 */
    val powerSaveMode: Boolean?,
)

/** 电池原始值的来源。真实实现是 [AndroidBatterySource]，单测里换成假实现。 */
interface BatterySource {
    fun read(): RawBatteryReading
}

/**
 * 用**公开 API**读电池状态：`ACTION_BATTERY_CHANGED` 是一次粘性广播，
 * `registerReceiver(null, ...)` 可以同步取到它最后的状态，不需要真正注册接收者，
 * 也就不需要任何权限（电池状态是公开信息，连 `BATTERY_STATS` 都不需要）。
 *
 * 这里只用 `applicationContext`，避免持有 Activity 造成泄漏。
 */
class AndroidBatterySource(context: Context) : BatterySource {

    private val appContext = context.applicationContext

    override fun read(): RawBatteryReading {
        val intent: Intent? = appContext.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
        )

        // 拿不到粘性广播时**不编造**：所有字段留 null，让上层如实汇报"不可用"。
        if (intent == null) {
            return RawBatteryReading(
                level = null,
                scale = null,
                status = null,
                plugged = null,
                powerSaveMode = powerSaveMode(),
            )
        }

        return RawBatteryReading(
            level = intent.extraOrNull(BatteryManager.EXTRA_LEVEL),
            scale = intent.extraOrNull(BatteryManager.EXTRA_SCALE),
            status = intent.extraOrNull(BatteryManager.EXTRA_STATUS),
            plugged = intent.extraOrNull(BatteryManager.EXTRA_PLUGGED),
            powerSaveMode = powerSaveMode(),
        )
    }

    /**
     * 读一个可缺的整数 extra。
     *
     * `getIntExtra(key, default)` 无法区分"键不存在"和"值等于默认值"，而本能力
     * 必须把两者分开（`null` = 系统没给）。先用 `hasExtra` 问一次，就不用去猜默认值。
     */
    private fun Intent.extraOrNull(key: String): Int? =
        if (hasExtra(key)) getIntExtra(key, MISSING) else null

    private fun powerSaveMode(): Boolean? =
        (appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isPowerSaveMode

    private companion object {
        /** `getIntExtra` 的默认值：用它区分"字段不存在"和"字段就是 0"。 */
        const val MISSING = Int.MIN_VALUE
    }
}

/**
 * Gate 2 工作包 E：`battery_get_status`。
 *
 * 只读、幂等、无副作用、不申请任何危险权限、不读用户文件、不上传设备标识符，
 * 只返回 [OUTPUT_KEYS] 里那四个字段。
 *
 * 输出与 `worker/src/mcp/tools.ts` 的 `BATTERY_GET_STATUS_TOOL.outputSchema` 一一对应：
 *   · `battery_level_percent` 整数或 null
 *   · `charging`              布尔或 null
 *   · `charging_source`       字符串（不可用时为 `"unavailable"`）
 *   · `power_save_mode`       布尔或 null
 *
 * **不编造数据**：任何取不到的字段就是 null（用 `JSONObject.NULL` 显式写入，
 * 而不是省略键 —— 省略与 null 在 JSON 里是两件事，前者会让"没有这个字段"
 * 与"这个字段不可用"混在一起）。
 */
class BatteryCapability(
    private val source: BatterySource,
) : Capability {

    override val name: String = NAME

    override val metadata: CapabilityMetadata = METADATA

    /** 刻意不接受任何参数：多一个键就多一条要审的路径。 */
    override val allowedArguments: Set<String> = emptySet()

    override fun execute(arguments: JSONObject): CapabilityResult {
        val reading = try {
            source.read()
        } catch (t: Throwable) {
            return CapabilityResult.Failure(
                code = ToolResultErrorCode.TOOL_FAILED,
                message = "读取电池状态失败（${t.javaClass.simpleName}）",
            )
        }

        val payload = JSONObject()
            .put("battery_level_percent", percentOf(reading) ?: JSONObject.NULL)
            .put("charging", chargingOf(reading.status) ?: JSONObject.NULL)
            .put("charging_source", sourceOf(reading.plugged))
            .put("power_save_mode", reading.powerSaveMode ?: JSONObject.NULL)

        return CapabilityResult.Success(payload)
    }

    companion object {

        const val NAME = "battery_get_status"

        /**
         * 元数据（展示与契约用）。
         *
         * 这份数据同时被两处读：`capability/BuiltInCapabilities.kt`（注册清单）
         * 与 `ui/model/ToolCatalog.kt`（工具页目录）—— 界面上的名字、说明、分类
         * 不再由 UI 自己手写，改名字只可能改这里。
         */
        val METADATA: CapabilityMetadata = CapabilityMetadata(
            name = NAME,
            displayName = "电池状态",
            description = "查询手机电量百分比、是否在充电、充电来源与省电模式。只读、不需要任何权限。",
            category = CapabilityCategory.Device,
            readOnly = true,
        )

        /** 回给服务端的字段集合（测试与文档共用一份）。 */
        val OUTPUT_KEYS: Set<String> = setOf(
            "battery_level_percent",
            "charging",
            "charging_source",
            "power_save_mode",
        )

        /** `charging_source` 在"没有插电 / 读不到"时的取值。 */
        const val SOURCE_UNAVAILABLE = "unavailable"

        /**
         * 电量百分比。
         *
         * `scale` 是分母（常见 100，但**不能假定**）。任何一项缺失、
         * `scale <= 0`、`level < 0` 都返回 null —— 宁可说"读不到"，
         * 也不要拿一个默认分母算出一个看起来合理的错数字。
         */
        fun percentOf(reading: RawBatteryReading): Int? {
            val level = reading.level ?: return null
            val scale = reading.scale ?: return null
            if (scale <= 0 || level < 0) return null
            val percent = (level.toLong() * 100L) / scale.toLong()
            return percent.coerceIn(0L, 100L).toInt()
        }

        /**
         * 是否正在充电。
         *
         * `BATTERY_STATUS_FULL` 算"正在充电"（插着且充满）——
         * 这与 Android 自己的 `isCharging` 语义一致。
         * 未知状态返回 null，绝不默认成 false：那会把"不知道"说成"没充电"。
         */
        fun chargingOf(status: Int?): Boolean? = when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING, BatteryManager.BATTERY_STATUS_FULL -> true
            BatteryManager.BATTERY_STATUS_DISCHARGING, BatteryManager.BATTERY_STATUS_NOT_CHARGING -> false
            else -> null
        }

        /**
         * 充电来源。
         *
         * `EXTRA_PLUGGED` 是**位组合**（一台设备可能同时报 AC 与 USB 的情况几乎不存在，
         * 但协议上允许）。这里按 AC → USB → 无线 → 底座 的固定优先级取一个名字，
         * 保证同样的输入永远得到同样的字符串（可断言、可复现）。
         * 一个位都没置（没插电）或读不到 → `"unavailable"`。
         */
        fun sourceOf(plugged: Int?): String {
            val bits = plugged ?: return SOURCE_UNAVAILABLE
            if (bits == 0) return SOURCE_UNAVAILABLE
            if (bits and BatteryManager.BATTERY_PLUGGED_AC != 0) return "ac"
            if (bits and BatteryManager.BATTERY_PLUGGED_USB != 0) return "usb"
            if (bits and BatteryManager.BATTERY_PLUGGED_WIRELESS != 0) return "wireless"
            if (bits and BATTERY_PLUGGED_DOCK != 0) return "dock"
            return "unknown"
        }

        /**
         * `BatteryManager.BATTERY_PLUGGED_DOCK`（API 33 才公开）。
         *
         * 直接写常量会在老 SDK 上编译失败，所以这里落一个字面量并**用测试锁住它的值**
         * （见 `BatteryCapabilityTest`），避免哪天它变了却没人发现。
         */
        const val BATTERY_PLUGGED_DOCK = 8
    }
}
