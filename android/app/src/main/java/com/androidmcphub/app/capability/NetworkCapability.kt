package com.androidmcphub.app.capability

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.androidmcphub.app.protocol.ToolResultErrorCode
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一次网络读取的**原始**值：直接来自 Android API，尚未解释成对外字段。
 *
 * 与 [RawBatteryReading] 同一个思路：把系统调用和"怎么解释"分开，
 * 解释逻辑（传输类型优先级、metered 的含义、无网络时的取值）就是纯函数，
 * 可以在 JVM 单测里跑；真机上只剩"把 API 读出来"这一小段。
 *
 * `null` 统一表示"系统没有给出这一项"，与 `false` 严格区分。
 */
data class RawNetworkReading(
    /**
     * 系统是否报告存在**活动默认网络**（`ConnectivityManager.activeNetwork != null`）。
     *
     * 注意它不等于"互联网可用"：`validated` 才是系统报告的联网验证结果。
     */
    val hasActiveNetwork: Boolean,
    /** `ConnectivityManager` 是否可用。false 表示这台设备/这个版本读不了，属于明确的错误。 */
    val serviceAvailable: Boolean = true,
    /** 该网络声明的传输类型（`TRANSPORT_*` 原始常量）；无网络或读不到能力时为 emptyList。 */
    val transports: List<Int> = emptyList(),
    /** `NET_CAPABILITY_VALIDATED`：系统是否报告该网络通过了联网验证。 */
    val validated: Boolean? = null,
    /** 是否计费网络（`NET_CAPABILITY_NOT_METERED` 取反）。 */
    val metered: Boolean? = null,
    /** `NET_CAPABILITY_INTERNET`：系统认为这个网络具备互联网能力。 */
    val internetCapable: Boolean? = null,
)

/** 网络原始值的来源。真实实现是 [AndroidNetworkSource]，单测里换成假实现。 */
interface NetworkSource {
    fun read(): RawNetworkReading
}

/**
 * 用**公开 API** 读当前默认网络：`ConnectivityManager.activeNetwork` +
 * `getNetworkCapabilities`。
 *
 * 需要 `ACCESS_NETWORK_STATE`（正常权限，Manifest 里本来就有 —— 连接服务用它
 * 监听"网络回来了"）。除此之外：
 *  * 不主动访问任何外部站点去"测试"网络（那会把一次只读查询变成一次外部请求）；
 *  * 不读 SSID / BSSID / MAC / IP / SIM 标识；
 *  * 不申请任何位置权限（读 Wi-Fi 名称才需要，这里不读）。
 *
 * 只持有 `applicationContext`，避免泄漏 Activity。
 */
class AndroidNetworkSource(context: Context) : NetworkSource {

    private val appContext = context.applicationContext

    override fun read(): RawNetworkReading {
        val manager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return RawNetworkReading(hasActiveNetwork = false, serviceAvailable = false)

        val network = manager.activeNetwork
            ?: return RawNetworkReading(hasActiveNetwork = false)

        // 默认网络存在但读不到能力：如实汇报"有网络、但这一版拿不到细节"，
        // 不猜传输类型、也不把 validated 说成 false。
        val caps: NetworkCapabilities = manager.getNetworkCapabilities(network)
            ?: return RawNetworkReading(hasActiveNetwork = true)

        val transports = TRANSPORT_CANDIDATES.filter { caps.hasTransport(it) }

        return RawNetworkReading(
            hasActiveNetwork = true,
            transports = transports,
            validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
            internetCapable = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
        )
    }

    private companion object {
        /**
         * 逐个试探的传输类型常量（字面量 + 单测锁值，理由同
         * `BatteryCapability.BATTERY_PLUGGED_DOCK`：`TRANSPORT_USB` 要 API 31、
         * `TRANSPORT_THREAD` 要 33、`TRANSPORT_SATELLITE` 要 34，本应用 minSdk 26，
         * 直接引用会踩 API 级别检查；而它们的**取值**是 AOSP 里冻结的公开常量）。
         */
        val TRANSPORT_CANDIDATES = listOf(0, 1, 2, 3, 4, 5, 6, 8, 9, 10)

    }
}

/**
 * Gate 2.5：`device_get_network`。
 *
 * 只读、幂等、无副作用、不申请任何新权限、不读用户文件、不上传设备标识符，
 * 只返回 [OUTPUT_KEYS] 里那几个字段。
 *
 * ── 字段语义（严格定义，不靠猜）────────────────────────────────────────────
 *  * `connected`        —— 系统报告存在**活动默认网络**。它**不代表互联网可用**；
 *  * `validated`        —— 系统是否报告该网络通过了联网验证（`NET_CAPABILITY_VALIDATED`）；
 *                          没有活动网络时为 null；
 *  * `transports`       —— 该网络声明的全部传输类型（字符串数组，可能多于一个：
 *                          例如 VPN 与底层网络会同时出现）；
 *  * `transport`        —— **主要连接类型**，按 [TRANSPORT_PRIORITY] 从 `transports`
 *                          里取第一个；有网络但没有已知传输类型时是 `"unknown"`，
 *                          没有活动网络时是 `"none"`；
 *  * `metered`          —— 是否计费网络；
 *  * `internet_capable` —— 系统认为该网络具备互联网能力。
 *
 * 输出与 `worker/src/mcp/tools.ts` 的 `DEVICE_GET_NETWORK_TOOL.outputSchema` 一一对应。
 *
 * **漫游状态刻意不返回**：可靠的 `isNetworkRoaming` 需要 `READ_PHONE_STATE`
 * （危险权限），而按网络类型反推就是猜 —— 与其返回一个可能错的字段，
 * 不如不返回。任务书也允许"无法可靠取得时省略"。
 */
class NetworkCapability(
    private val source: NetworkSource,
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
                message = "读取网络状态失败（${t.javaClass.simpleName}）",
            )
        }

        // 「系统连接服务都拿不到」是"这台设备上不可用"，不是"没有网络"——
        // 两者必须回不同的错误码，否则服务端会把一台读不了状态的手机
        // 当成"手机连不上网"。
        if (!reading.serviceAvailable) {
            return CapabilityResult.Failure(
                code = ToolResultErrorCode.TOOL_UNAVAILABLE,
                message = "系统连接服务不可用，无法读取网络状态",
            )
        }

        return CapabilityResult.Success(payloadOf(reading))
    }

    companion object {

        const val NAME = "device_get_network"

        /** 回给服务端的字段集合（测试与文档共用一份）。 */
        val OUTPUT_KEYS: Set<String> = setOf(
            "connected",
            "validated",
            "transport",
            "transports",
            "metered",
            "internet_capable",
        )

        // ---- 传输类型常量（AOSP 冻结值，单测锁值）--------------------------------

        const val TRANSPORT_CELLULAR = 0
        const val TRANSPORT_WIFI = 1
        const val TRANSPORT_BLUETOOTH = 2
        const val TRANSPORT_ETHERNET = 3
        const val TRANSPORT_VPN = 4
        const val TRANSPORT_WIFI_AWARE = 5
        const val TRANSPORT_LOWPAN = 6
        const val TRANSPORT_USB = 8
        const val TRANSPORT_THREAD = 9
        const val TRANSPORT_SATELLITE = 10

        /** 没有活动网络时的主要连接类型。 */
        const val TRANSPORT_NONE = "none"

        /** 有活动网络、但传输类型一个都不认识时的主要连接类型。 */
        const val TRANSPORT_UNKNOWN = "unknown"

        /**
         * **主要连接类型**的优先级（从高到低）。
         *
         * 为什么是这个顺序：VPN 承载的是用户实际流量（它出现时必然与底层网络同时存在，
         * 所以必须排在前面，否则会把"走 VPN"说成"走 Wi-Fi"）；有线与 Wi-Fi 是
         * 不计费/不限量的优先通道；USB 网络共享通常是用户主动插上的；
         * 蜂窝排在这之后；蓝牙与其余小众类型排最后。
         */
        val TRANSPORT_PRIORITY: List<Int> = listOf(
            TRANSPORT_VPN,
            TRANSPORT_ETHERNET,
            TRANSPORT_WIFI,
            TRANSPORT_USB,
            TRANSPORT_CELLULAR,
            TRANSPORT_BLUETOOTH,
            TRANSPORT_WIFI_AWARE,
            TRANSPORT_LOWPAN,
            TRANSPORT_THREAD,
            TRANSPORT_SATELLITE,
        )

        /** 传输类型原始值 → 对外字符串；不认识的一律 `"unknown"`（不回显数字）。 */
        fun transportName(raw: Int): String = when (raw) {
            TRANSPORT_CELLULAR -> "cellular"
            TRANSPORT_WIFI -> "wifi"
            TRANSPORT_BLUETOOTH -> "bluetooth"
            TRANSPORT_ETHERNET -> "ethernet"
            TRANSPORT_VPN -> "vpn"
            TRANSPORT_WIFI_AWARE -> "wifi_aware"
            TRANSPORT_LOWPAN -> "lowpan"
            TRANSPORT_USB -> "usb"
            TRANSPORT_THREAD -> "thread"
            TRANSPORT_SATELLITE -> "satellite"
            else -> TRANSPORT_UNKNOWN
        }

        /** 传输类型原始值列表 → 对外字符串列表（保持原始顺序、去掉重复与未知项）。 */
        fun transportNamesOf(transports: List<Int>): List<String> =
            transports.map { transportName(it) }
                .filter { it != TRANSPORT_UNKNOWN }
                .distinct()

        /**
         * 主要连接类型：按 [TRANSPORT_PRIORITY] 取第一个已知类型。
         *
         * 没有传输类型时：[RawNetworkReading.hasActiveNetwork] 为 false → `"none"`；
         * 有网络却一个都不认识 → `"unknown"`。两者不混为一谈。
         */
        fun primaryTransportOf(reading: RawNetworkReading): String {
            val known = transportNamesOf(reading.transports)
            if (known.isEmpty()) {
                return if (reading.hasActiveNetwork) TRANSPORT_UNKNOWN else TRANSPORT_NONE
            }
            val byPriority = TRANSPORT_PRIORITY.map { transportName(it) }
            return byPriority.firstOrNull { it in known } ?: known.first()
        }

        /**
         * 原始值 → 对外 payload。
         *
         * 无活动网络时除 `connected=false` 之外一律显式写 null（而不是省略键）——
         * 省略与 null 在 JSON 里是两件事，前者会让"没有这个字段"与"读不到"混在一起。
         */
        fun payloadOf(reading: RawNetworkReading): JSONObject {
            val transports = JSONArray()
            transportNamesOf(reading.transports).forEach { transports.put(it) }

            return JSONObject()
                .put("connected", reading.hasActiveNetwork)
                .put("validated", reading.validated ?: JSONObject.NULL)
                .put("transport", primaryTransportOf(reading))
                .put("transports", transports)
                .put("metered", reading.metered ?: JSONObject.NULL)
                .put("internet_capable", reading.internetCapable ?: JSONObject.NULL)
        }

        val METADATA: CapabilityMetadata = CapabilityMetadata(
            name = NAME,
            displayName = "网络状态",
            description = "查询当前默认网络是否连通、传输类型（Wi-Fi / 蜂窝 / 以太网 / VPN 等）、" +
                "是否计费以及系统是否验证过联网。只读，不读取 SSID、MAC、IP 等标识信息。",
            category = CapabilityCategory.Network,
            readOnly = true,
            declaredPermissions = listOf("android.permission.ACCESS_NETWORK_STATE"),
        )

        /** 供检测用：本能力用到的权限常量（与 Manifest 里那条一致）。 */
        const val PERMISSION_ACCESS_NETWORK_STATE = "android.permission.ACCESS_NETWORK_STATE"
    }
}
