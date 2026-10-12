package com.androidmcphub.app.capability

import android.net.NetworkCapabilities
import com.androidmcphub.app.protocol.ToolResultErrorCode
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gate 2.5 §十 1–6：网络状态能力的 JVM 单测。
 *
 * 这里全部用假数据源，所以能在没有网络、没有 Android 设备的机器上跑。
 * 真实 `ConnectivityManager` 的读法在 [AndroidNetworkSource] 里，只做一行"读原始值"。
 */
class NetworkCapabilityTest {

    private class FakeNetworkSource(
        private val reading: RawNetworkReading? = null,
        private val throws: Throwable? = null,
    ) : NetworkSource {
        override fun read(): RawNetworkReading {
            throws?.let { throw it }
            return reading ?: RawNetworkReading(hasActiveNetwork = true)
        }
    }

    private fun capability(reading: RawNetworkReading? = null, throws: Throwable? = null) =
        NetworkCapability(FakeNetworkSource(reading, throws))

    private fun successPayload(reading: RawNetworkReading): JSONObject {
        val result = capability(reading).execute(JSONObject())
        assertTrue("应当成功：$result", result is CapabilityResult.Success)
        return (result as CapabilityResult.Success).payload
    }

    private fun failureOf(reading: RawNetworkReading? = null, throws: Throwable? = null): CapabilityResult.Failure {
        val result = capability(reading, throws).execute(JSONObject())
        assertTrue("应当是失败：$result", result is CapabilityResult.Failure)
        return result as CapabilityResult.Failure
    }

    // ---- 传输类型映射（闭集、固定优先级）------------------------------------

    @Test
    fun `传输类型常量与 Android 公开值一致`() {
        assertEquals(0, NetworkCapability.TRANSPORT_CELLULAR)
        assertEquals(1, NetworkCapability.TRANSPORT_WIFI)
        assertEquals(2, NetworkCapability.TRANSPORT_BLUETOOTH)
        assertEquals(3, NetworkCapability.TRANSPORT_ETHERNET)
        assertEquals(4, NetworkCapability.TRANSPORT_VPN)
        assertEquals(5, NetworkCapability.TRANSPORT_WIFI_AWARE)
        assertEquals(6, NetworkCapability.TRANSPORT_LOWPAN)
        assertEquals(8, NetworkCapability.TRANSPORT_USB)
        assertEquals(9, NetworkCapability.TRANSPORT_THREAD)
        assertEquals(10, NetworkCapability.TRANSPORT_SATELLITE)
        // 能力位直接引用框架常量（API 21/23 就有，minSdk 26 安全），
        // 这里把取值钉住：如果哪天有人把它们换成别的数字，测试先红。
        assertEquals(11, NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        assertEquals(12, NetworkCapabilities.NET_CAPABILITY_INTERNET)
        assertEquals(16, NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    @Test
    fun `传输类型映射是闭集，未知值收敛成 unknown`() {
        assertEquals("cellular", NetworkCapability.transportName(0))
        assertEquals("wifi", NetworkCapability.transportName(1))
        assertEquals("vpn", NetworkCapability.transportName(4))
        assertEquals("satellite", NetworkCapability.transportName(10))
        assertEquals("unknown", NetworkCapability.transportName(7))
        assertEquals("unknown", NetworkCapability.transportName(99))
        assertEquals("unknown", NetworkCapability.transportName(-1))
    }

    @Test
    fun `传输列表去重且不含 unknown`() {
        assertEquals(
            listOf("wifi", "vpn"),
            NetworkCapability.transportNamesOf(
                listOf(
                    NetworkCapability.TRANSPORT_WIFI,
                    NetworkCapability.TRANSPORT_VPN,
                    NetworkCapability.TRANSPORT_WIFI,
                    7,
                ),
            ),
        )
        assertTrue(NetworkCapability.transportNamesOf(listOf(7, 99)).isEmpty())
    }

    @Test
    fun `多个传输并存时按固定优先级给出主要类型`() {
        // VPN 与 Wi-Fi 同时存在：对外说 vpn，同时把两者都列在 transports 里。
        assertEquals(
            "vpn",
            NetworkCapability.primaryTransportOf(
                RawNetworkReading(
                    hasActiveNetwork = true,
                    transports = listOf(NetworkCapability.TRANSPORT_WIFI, NetworkCapability.TRANSPORT_VPN),
                ),
            ),
        )
        assertEquals(
            "ethernet",
            NetworkCapability.primaryTransportOf(
                RawNetworkReading(
                    hasActiveNetwork = true,
                    transports = listOf(NetworkCapability.TRANSPORT_WIFI, NetworkCapability.TRANSPORT_ETHERNET),
                ),
            ),
        )
        assertEquals(
            "wifi",
            NetworkCapability.primaryTransportOf(
                RawNetworkReading(
                    hasActiveNetwork = true,
                    transports = listOf(NetworkCapability.TRANSPORT_CELLULAR, NetworkCapability.TRANSPORT_WIFI),
                ),
            ),
        )
        assertEquals(
            "cellular",
            NetworkCapability.primaryTransportOf(
                RawNetworkReading(
                    hasActiveNetwork = true,
                    transports = listOf(NetworkCapability.TRANSPORT_BLUETOOTH, NetworkCapability.TRANSPORT_CELLULAR),
                ),
            ),
        )
    }

    // ---- §十 1–5：Wi-Fi / 蜂窝 / 无网络 / 未验证 / VPN ----------------------

    @Test
    fun `Wi-Fi 已联网且系统已验证`() {
        val payload = successPayload(
            RawNetworkReading(
                hasActiveNetwork = true,
                transports = listOf(NetworkCapability.TRANSPORT_WIFI),
                validated = true,
                metered = false,
                internetCapable = true,
            ),
        )

        assertTrue(payload.getBoolean("connected"))
        assertEquals(true, payload.get("validated"))
        assertEquals("wifi", payload.getString("transport"))
        assertEquals(
            listOf("wifi"),
            payload.getJSONArray("transports").let { array -> (0 until array.length()).map { array.getString(it) } },
        )
        assertEquals(false, payload.get("metered"))
        assertEquals(true, payload.get("internet_capable"))
    }

    @Test
    fun `蜂窝已联网但系统未验证`() {
        val payload = successPayload(
            RawNetworkReading(
                hasActiveNetwork = true,
                transports = listOf(NetworkCapability.TRANSPORT_CELLULAR),
                validated = false,
                metered = true,
                internetCapable = true,
            ),
        )

        assertTrue(payload.getBoolean("connected"))
        assertEquals("connected 不等于互联网可用：validated 由系统报告", false, payload.get("validated"))
        assertEquals("cellular", payload.getString("transport"))
        assertEquals(true, payload.get("metered"))
    }

    @Test
    fun `没有活动网络时 connected 为 false、transport 为 none`() {
        val payload = successPayload(RawNetworkReading(hasActiveNetwork = false))

        assertFalse(payload.getBoolean("connected"))
        assertEquals("none", payload.getString("transport"))
        assertEquals(0, payload.getJSONArray("transports").length())
        // 系统没报的字段是 null，不是猜出来的 false。
        assertTrue(payload.isNull("validated"))
        assertTrue(payload.isNull("metered"))
        assertTrue(payload.isNull("internet_capable"))
    }

    @Test
    fun `VPN 与底层网络并存时两者都列出来`() {
        val payload = successPayload(
            RawNetworkReading(
                hasActiveNetwork = true,
                transports = listOf(NetworkCapability.TRANSPORT_WIFI, NetworkCapability.TRANSPORT_VPN),
                validated = true,
                metered = false,
                internetCapable = true,
            ),
        )

        assertEquals("vpn", payload.getString("transport"))
        val transports = payload.getJSONArray("transports")
        assertEquals(2, transports.length())
        assertEquals("wifi", transports.getString(0))
        assertEquals("vpn", transports.getString(1))
    }

    @Test
    fun `有网络但拿不到传输类型时是 unknown，而不是 none`() {
        val payload = successPayload(RawNetworkReading(hasActiveNetwork = true))

        assertTrue(payload.getBoolean("connected"))
        assertEquals("unknown", payload.getString("transport"))
        assertEquals(0, payload.getJSONArray("transports").length())
    }

    @Test
    fun `不返回漫游字段`() {
        val payload = successPayload(
            RawNetworkReading(
                hasActiveNetwork = true,
                transports = listOf(NetworkCapability.TRANSPORT_CELLULAR),
            ),
        )

        // 可靠的漫游状态要 READ_PHONE_STATE，靠网络类型反推就是猜 —— 所以干脆不给。
        assertNull(payload.opt("roaming"))
    }

    // ---- §十 6：API 异常 / 权限不足 ----------------------------------------

    @Test
    fun `系统连接服务不可用时明确失败，而不是装作没有网络`() {
        val failure = failureOf(
            RawNetworkReading(hasActiveNetwork = false, serviceAvailable = false),
        )

        assertEquals(ToolResultErrorCode.TOOL_UNAVAILABLE, failure.code)
        assertTrue(failure.message.contains("连接服务"))
    }

    @Test
    fun `数据源抛异常时是 tool_failed，且异常原文不进回帧`() {
        val failure = failureOf(throws = SecurityException("permission denied by system"))

        assertEquals(ToolResultErrorCode.TOOL_FAILED, failure.code)
        assertFalse(failure.message.contains("permission denied by system"))
        assertTrue(failure.message.contains("SecurityException"))
    }

    // ---- 形状与元数据 ------------------------------------------------------

    @Test
    fun `输出的键集合与 Worker 的 outputSchema 完全一致`() {
        assertEquals(
            setOf("connected", "validated", "transport", "transports", "metered", "internet_capable"),
            NetworkCapability.OUTPUT_KEYS,
        )
        val payload = successPayload(RawNetworkReading(hasActiveNetwork = true))
        assertEquals(NetworkCapability.OUTPUT_KEYS, payload.keys().asSequence().toSet())
    }

    @Test
    fun `不接受任何参数`() {
        assertTrue(capability().allowedArguments.isEmpty())
    }

    @Test
    fun `元数据如实描述网络能力`() {
        val metadata = NetworkCapability.METADATA

        assertEquals("device_get_network", metadata.name)
        assertEquals("网络状态", metadata.displayName)
        assertEquals(CapabilityCategory.Network, metadata.category)
        assertTrue(metadata.readOnly)
        assertTrue(metadata.arguments.isEmpty())
        assertEquals(
            listOf(NetworkCapability.PERMISSION_ACCESS_NETWORK_STATE),
            metadata.declaredPermissions,
        )
        // ACCESS_NETWORK_STATE 是普通权限：安装即授予，不需要用户当场批准。
        assertFalse(metadata.requiresRuntimePermission)
    }

    @Test
    fun `工具名符合协议允许的形状`() {
        assertTrue(Regex("^[a-z][a-z0-9_]{0,63}$").matches(NetworkCapability.NAME))
    }

    // ---- 通过分发器调用（与真实链路同一段代码）-----------------------------

    @Test
    fun `通过分发器调用时，成功结果被摊平成 tool_result 的信封`() {
        val dispatcher = CapabilityDispatcher(
            listOf(
                capability(
                    RawNetworkReading(
                        hasActiveNetwork = true,
                        transports = listOf(NetworkCapability.TRANSPORT_CELLULAR),
                        validated = true,
                        metered = true,
                        internetCapable = true,
                    ),
                ),
            ),
        )

        val outcome = dispatcher.handle("tr-net-1", NetworkCapability.NAME, JSONObject())

        assertTrue(outcome.ok)
        val obj = JSONObject(outcome.frame!!)
        assertEquals("tool_result", obj.getString("type"))
        assertEquals("tr-net-1", obj.getString("request_id"))
        // 帧里刻意不带 tool_name（Worker 用自己的请求记账补），手机侧只在 outcome 上留名字。
        assertEquals(NetworkCapability.NAME, outcome.toolName)
        assertFalse(obj.has("tool_name"))
        val result = obj.getJSONObject("result")
        assertTrue(result.getBoolean("connected"))
        assertEquals("cellular", result.getString("transport"))
        assertEquals(JSONArray(listOf("cellular")).toString(), result.getJSONArray("transports").toString())
    }

    @Test
    fun `通过分发器调用时，总开关关闭回 not_permitted 且不读数据`() {
        var reads = 0
        val counting = object : NetworkSource {
            override fun read(): RawNetworkReading {
                reads += 1
                return RawNetworkReading(hasActiveNetwork = true)
            }
        }
        val dispatcher = CapabilityDispatcher(
            listOf(NetworkCapability(counting)),
            policy = { false },
        )

        val outcome = dispatcher.handle("tr-net-2", NetworkCapability.NAME, JSONObject())

        assertFalse(outcome.ok)
        assertEquals(ToolResultErrorCode.NOT_PERMITTED, outcome.errorCode)
        assertEquals("总开关关闭时不该碰系统接口", 0, reads)
    }
}
