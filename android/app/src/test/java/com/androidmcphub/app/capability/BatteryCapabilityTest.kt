package com.androidmcphub.app.capability

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gate 2 工作包 E：`battery_get_status` 的 JVM 单测。
 *
 * 这里测的是**解释逻辑**（百分比怎么算、什么时候是 null、来源字符串怎么取），
 * 而不是 `registerReceiver` 本身 —— 后者是 Android 框架行为，JVM 上跑不了，
 * 用 [BatterySource] 把它换掉。真机上"读到的值是否合理"属于 REAL_DEVICE_REQUIRED。
 */
class BatteryCapabilityTest {

    /** 可控的假数据源：固定返回一份原始值，或按需抛异常。 */
    private class FakeSource(
        private val reading: RawBatteryReading,
        private val failure: Throwable? = null,
    ) : BatterySource {
        var calls = 0
            private set

        override fun read(): RawBatteryReading {
            calls += 1
            failure?.let { throw it }
            return reading
        }
    }

    private fun reading(
        level: Int? = 42,
        scale: Int? = 100,
        status: Int? = BatteryCapabilityFixtures.STATUS_DISCHARGING,
        plugged: Int? = 0,
        powerSaveMode: Boolean? = true,
    ) = RawBatteryReading(level, scale, status, plugged, powerSaveMode)

    // ------------------------------------------------------------------ 百分比

    @Test
    fun `百分比用 level 与 scale 算，而不是假定分母是 100`() {
        assertEquals(42, BatteryCapability.percentOf(reading(level = 42, scale = 100)))
        assertEquals(25, BatteryCapability.percentOf(reading(level = 50, scale = 200)))
        // 整数除法的截断是明确的：1/3 就是 33，不四舍五入成 33.33 再取整出意外。
        assertEquals(33, BatteryCapability.percentOf(reading(level = 1, scale = 3)))
    }

    @Test
    fun `百分比边界被夹在 0 到 100 之间`() {
        assertEquals(0, BatteryCapability.percentOf(reading(level = 0, scale = 100)))
        assertEquals(100, BatteryCapability.percentOf(reading(level = 100, scale = 100)))
        // 系统偶尔会报出超过 scale 的值（校准中），不夹紧就会回一个 >100 的百分比。
        assertEquals(100, BatteryCapability.percentOf(reading(level = 150, scale = 100)))
        assertEquals(0, BatteryCapability.percentOf(reading(level = 0, scale = 100)))
    }

    @Test
    fun `百分比在缺项时是 null，绝不猜一个默认值`() {
        assertEquals(null, BatteryCapability.percentOf(reading(level = null)))
        assertEquals(null, BatteryCapability.percentOf(reading(scale = null)))
        // scale=0 会让除法炸掉；宁可说读不到。
        assertEquals(null, BatteryCapability.percentOf(reading(scale = 0)))
        assertEquals(null, BatteryCapability.percentOf(reading(scale = -1)))
        assertEquals(null, BatteryCapability.percentOf(reading(level = -1)))
    }

    // ------------------------------------------------------------------ 是否在充电

    @Test
    fun `充电状态的映射是闭集，未知状态一律 null`() {
        assertEquals(true, BatteryCapability.chargingOf(BatteryCapabilityFixtures.STATUS_CHARGING))
        // FULL 是"插着且已充满"，对用户而言仍然是在充电（与 Android 的 isCharging 一致）。
        assertEquals(true, BatteryCapability.chargingOf(BatteryCapabilityFixtures.STATUS_FULL))
        assertEquals(false, BatteryCapability.chargingOf(BatteryCapabilityFixtures.STATUS_DISCHARGING))
        assertEquals(false, BatteryCapability.chargingOf(BatteryCapabilityFixtures.STATUS_NOT_CHARGING))

        // UNKNOWN 与"没读到"必须都是 null —— 默认成 false 就是把"不知道"说成"没充电"。
        assertEquals(null, BatteryCapability.chargingOf(BatteryCapabilityFixtures.STATUS_UNKNOWN))
        assertEquals(null, BatteryCapability.chargingOf(null))
        assertEquals(null, BatteryCapability.chargingOf(999))
    }

    // ------------------------------------------------------------------ 充电来源

    @Test
    fun `充电来源的位映射是固定的`() {
        assertEquals("ac", BatteryCapability.sourceOf(BatteryCapabilityFixtures.PLUGGED_AC))
        assertEquals("usb", BatteryCapability.sourceOf(BatteryCapabilityFixtures.PLUGGED_USB))
        assertEquals("wireless", BatteryCapability.sourceOf(BatteryCapabilityFixtures.PLUGGED_WIRELESS))
        assertEquals("dock", BatteryCapability.sourceOf(BatteryCapability.BATTERY_PLUGGED_DOCK))
    }

    @Test
    fun `没插电或读不到时来源是 unavailable，未知位是 unknown`() {
        assertEquals(BatteryCapability.SOURCE_UNAVAILABLE, BatteryCapability.sourceOf(0))
        assertEquals(BatteryCapability.SOURCE_UNAVAILABLE, BatteryCapability.sourceOf(null))
        assertEquals("unknown", BatteryCapability.sourceOf(16))
    }

    @Test
    fun `多个位同时置起时按固定优先级取一个名字，保证同样输入永远同样输出`() {
        // 真机上几乎不会出现同时报 AC 与 USB，但协议上允许，行为必须确定。
        val acOrUsb = BatteryCapabilityFixtures.PLUGGED_AC or BatteryCapabilityFixtures.PLUGGED_USB
        assertEquals("ac", BatteryCapability.sourceOf(acOrUsb))
        val usbOrWireless =
            BatteryCapabilityFixtures.PLUGGED_USB or BatteryCapabilityFixtures.PLUGGED_WIRELESS
        assertEquals("usb", BatteryCapability.sourceOf(usbOrWireless))
    }

    @Test
    fun `底座位的常量与 Android 公开值一致`() {
        // BatteryManager.BATTERY_PLUGGED_DOCK 是 API 33 才公开的，代码里落的是字面量。
        // 这条断言把这一个字面量钉住：哪天有人改错，这里会红。
        assertEquals(8, BatteryCapability.BATTERY_PLUGGED_DOCK)
    }

    // ------------------------------------------------------------------ 能力输出

    @Test
    fun `输出的键集合与 Worker 的 outputSchema 完全一致`() {
        assertEquals(
            setOf("battery_level_percent", "charging", "charging_source", "power_save_mode"),
            BatteryCapability.OUTPUT_KEYS,
        )
    }

    @Test
    fun `不接受任何参数`() {
        assertTrue(BatteryCapability(FakeSource(reading())).allowedArguments.isEmpty())
    }

    @Test
    fun `完整读取时回四个真实值`() {
        val capability = BatteryCapability(
            FakeSource(
                reading(
                    level = 42,
                    scale = 100,
                    status = BatteryCapabilityFixtures.STATUS_DISCHARGING,
                    plugged = 0,
                    powerSaveMode = true,
                ),
            ),
        )

        val result = capability.execute(JSONObject()) as CapabilityResult.Success
        assertEquals(42, result.payload.getInt("battery_level_percent"))
        assertFalse(result.payload.getBoolean("charging"))
        assertEquals("unavailable", result.payload.getString("charging_source"))
        assertTrue(result.payload.getBoolean("power_save_mode"))
        assertEquals(BatteryCapability.OUTPUT_KEYS, result.payload.keys().asSequence().toSet())
    }

    @Test
    fun `读不到的字段是显式的 null，而不是缺键`() {
        val capability = BatteryCapability(
            FakeSource(reading(level = null, scale = null, status = null, plugged = null, powerSaveMode = null)),
        )

        val payload = (capability.execute(JSONObject()) as CapabilityResult.Success).payload

        // 四个键**都在**：缺键与 null 在 JSON 里是两件事，前者会被读成"没有这个字段"。
        assertEquals(BatteryCapability.OUTPUT_KEYS, payload.keys().asSequence().toSet())
        assertTrue(payload.isNull("battery_level_percent"))
        assertTrue(payload.isNull("charging"))
        assertTrue(payload.isNull("power_save_mode"))
        // charging_source 是不可空字段：没有来源就是 unavailable。
        assertEquals("unavailable", payload.getString("charging_source"))
    }

    @Test
    fun `数据源抛异常时是失败，且异常原文不进回帧`() {
        val source = FakeSource(
            reading(),
            failure = IllegalStateException("BatteryService 崩了: /data/data/com.androidmcphub.app/token=abc"),
        )

        val result = BatteryCapability(source).execute(JSONObject())
        assertTrue(result is CapabilityResult.Failure)
        val failure = result as CapabilityResult.Failure

        assertEquals("tool_failed", failure.code)
        // 只带异常类名。
        assertTrue(failure.message.contains("IllegalStateException"))
        assertFalse(failure.message.contains("/data/data"))
        assertFalse(failure.message.contains("token=abc"))
        assertEquals(1, source.calls)
    }

    // ------------------------------------------------------------------ 与分发器合起来

    @Test
    fun `通过分发器调用时，成功结果被摊平成 tool_result 的信封`() {
        val dispatcher = CapabilityDispatcher(listOf(BatteryCapability(FakeSource(reading()))))

        val outcome = dispatcher.handle("req-battery-1", BatteryCapability.NAME, JSONObject())

        assertTrue(outcome.ok)
        assertNotNull(outcome.frame)
        val frame = JSONObject(outcome.frame!!)
        assertEquals("tool_result", frame.getString("type"))
        assertEquals("req-battery-1", frame.getString("request_id"))
        assertTrue(frame.getBoolean("ok"))
        assertEquals(42, frame.getJSONObject("result").getInt("battery_level_percent"))
        assertFalse(frame.has("error"))
    }

    @Test
    fun `用户关掉总开关时能力不执行，回 not_permitted`() {
        val source = FakeSource(reading())
        val dispatcher = CapabilityDispatcher(
            listOf(BatteryCapability(source)),
            policy = { false },
        )

        val outcome = dispatcher.handle("req-battery-2", BatteryCapability.NAME, JSONObject())

        assertFalse(outcome.ok)
        assertEquals("not_permitted", outcome.errorCode)
        // 关键：**没有**读电池。
        assertEquals(0, source.calls)
    }

    @Test
    fun `策略抛异常时按拒绝处理，不默认放行`() {
        val source = FakeSource(reading())
        val dispatcher = CapabilityDispatcher(
            listOf(BatteryCapability(source)),
            policy = { throw IllegalStateException("prefs 读不到") },
        )

        val outcome = dispatcher.handle("req-battery-3", BatteryCapability.NAME, JSONObject())

        assertFalse(outcome.ok)
        assertEquals("not_permitted", outcome.errorCode)
        assertEquals(0, source.calls)
    }

    @Test
    fun `策略只影响被拒的能力名，放行时能力照常执行`() {
        val source = FakeSource(reading())
        val dispatcher = CapabilityDispatcher(
            listOf(BatteryCapability(source)),
            policy = { name -> name != BatteryCapability.NAME },
        )

        val outcome = dispatcher.handle("req-battery-4", BatteryCapability.NAME, JSONObject())

        assertFalse(outcome.ok)
        assertEquals("not_permitted", outcome.errorCode)
        assertEquals(0, source.calls)

        val allowed = CapabilityDispatcher(listOf(BatteryCapability(source)), policy = { true })
        assertTrue(allowed.handle("req-battery-5", BatteryCapability.NAME, JSONObject()).ok)
        assertEquals(1, source.calls)
    }

    @Test
    fun `拒绝也是按 request_id 一次性的，重复帧同样不再执行`() {
        val source = FakeSource(reading())
        val dispatcher = CapabilityDispatcher(
            listOf(BatteryCapability(source)),
            policy = { false },
        )

        dispatcher.handle("req-battery-6", BatteryCapability.NAME, JSONObject())
        val second = dispatcher.handle("req-battery-6", BatteryCapability.NAME, JSONObject())

        assertTrue(second.duplicate)
        assertEquals(null, second.frame)
        assertEquals(0, source.calls)
    }

    @Test
    fun `工具名符合协议允许的形状`() {
        assertTrue(Regex("^[a-z][a-z0-9_]{0,63}$").matches(BatteryCapability.NAME))
        assertEquals("battery_get_status", BatteryCapability.NAME)
    }
}

/**
 * `BatteryManager` 的常量在 JVM 单测里也能用（它们是编译期常量），
 * 这里抄一份显式的值，是为了让"映射到哪个数字"这件事在测试里一目了然 ——
 * 而不是藏在 SDK 常量名后面。
 */
private object BatteryCapabilityFixtures {
    const val STATUS_UNKNOWN = 1
    const val STATUS_CHARGING = 2
    const val STATUS_DISCHARGING = 3
    const val STATUS_NOT_CHARGING = 4
    const val STATUS_FULL = 5
    const val PLUGGED_AC = 1
    const val PLUGGED_USB = 2
    const val PLUGGED_WIRELESS = 4
}
