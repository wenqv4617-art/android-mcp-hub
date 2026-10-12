package com.androidmcphub.app.capability

import com.androidmcphub.app.protocol.ToolResultErrorCode
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gate 2.5 §十 7–9：存储容量能力的 JVM 单测。
 *
 * 和网络能力一样，这里用假数据源，因此能在没有 Android 设备的机器上跑。
 * 重点不是"能读到数字"，而是：**容量为 0 / 读不到时不能编造一串 0**。
 */
class StorageCapabilityTest {

    private class FakeStorageSource(
        private val reading: RawStorageReading? = null,
        private val throws: Throwable? = null,
    ) : StorageSource {
        override fun read(): RawStorageReading {
            throws?.let { throw it }
            return reading ?: RawStorageReading(AndroidStorageSource.VOLUME, 1_000L, 400L)
        }
    }

    private fun capability(reading: RawStorageReading? = null, throws: Throwable? = null) =
        StorageCapability(FakeStorageSource(reading, throws))

    private fun successPayload(reading: RawStorageReading): JSONObject {
        val result = capability(reading).execute(JSONObject())
        assertTrue("应当成功：$result", result is CapabilityResult.Success)
        return (result as CapabilityResult.Success).payload
    }

    private fun failureOf(reading: RawStorageReading? = null, throws: Throwable? = null): CapabilityResult.Failure {
        val result = capability(reading, throws).execute(JSONObject())
        assertTrue("应当是失败：$result", result is CapabilityResult.Failure)
        return result as CapabilityResult.Failure
    }

    // ---- §十 7：正常容量 ---------------------------------------------------

    @Test
    fun `完整读取时回五个真实字段`() {
        val payload = successPayload(
            RawStorageReading(volume = "internal_data", totalBytes = 1_000L, availableBytes = 400L),
        )

        assertEquals("internal_data", payload.getString("volume"))
        assertEquals(1_000L, payload.getLong("total_bytes"))
        assertEquals(400L, payload.getLong("available_bytes"))
        assertEquals(600L, payload.getLong("used_bytes"))
        assertEquals(60.0, payload.getDouble("usage_percent"), 0.0001)
    }

    @Test
    fun `已用比例保留一位小数`() {
        // 2/3 ≈ 66.6666…% → 66.7，而不是 66 或者 66.66666
        assertEquals(66.7, StorageCapability.usagePercentOf(3L, 1L)!!, 0.0001)
        assertEquals(33.3, StorageCapability.usagePercentOf(3L, 2L)!!, 0.0001)
        assertEquals(0.0, StorageCapability.usagePercentOf(1_000L, 1_000L)!!, 0.0001)
        assertEquals(100.0, StorageCapability.usagePercentOf(1_000L, 0L)!!, 0.0001)
    }

    @Test
    fun `可用字节被夹在 0 到总容量之间`() {
        // 文件系统偶尔会报出 available > total（保留块/计数竞态）：夹住，
        // 否则"已用 = 总 - 可用"会变成负数，调用方只能自己猜。
        assertEquals(1_000L, StorageCapability.availableBytesOf(1_000L, 2_000L))
        assertEquals(0L, StorageCapability.availableBytesOf(1_000L, -5L))
        assertEquals(400L, StorageCapability.availableBytesOf(1_000L, 400L))
        assertEquals(0L, StorageCapability.usedBytesOf(1_000L, 2_000L))
    }

    @Test
    fun `used_bytes 是文件系统已占用，不是用户文件占用`() {
        // 这个断言本身就是文档：语义写在测试名里，谁改都要先看一眼说明。
        val payload = successPayload(
            RawStorageReading(volume = "internal_data", totalBytes = 512_000L, availableBytes = 12_000L),
        )

        assertEquals(500_000L, payload.getLong("used_bytes"))
        assertTrue(
            "used_bytes 必须等于 total - available",
            payload.getLong("used_bytes") ==
                payload.getLong("total_bytes") - payload.getLong("available_bytes"),
        )
    }

    // ---- §十 8：容量为零 ---------------------------------------------------

    @Test
    fun `容量为零时明确失败，而不是回一串 0`() {
        val failure = failureOf(
            RawStorageReading(volume = "internal_data", totalBytes = 0L, availableBytes = 0L),
        )

        assertEquals(ToolResultErrorCode.TOOL_UNAVAILABLE, failure.code)
        assertTrue(failure.message.contains("容量为 0"))
        assertNull(StorageCapability.usedBytesOf(0L, 0L))
        assertNull(StorageCapability.usagePercentOf(0L, 0L))
    }

    @Test
    fun `负数容量同样按不可用处理`() {
        val failure = failureOf(
            RawStorageReading(volume = "internal_data", totalBytes = -1L, availableBytes = 0L),
        )

        assertEquals(ToolResultErrorCode.TOOL_UNAVAILABLE, failure.code)
    }

    // ---- §十 9：存储 API 异常 ----------------------------------------------

    @Test
    fun `数据源抛异常时是 tool_failed，且异常原文不进回帧`() {
        val failure = failureOf(throws = IllegalStateException("statfs failed at /data"))

        assertEquals(ToolResultErrorCode.TOOL_FAILED, failure.code)
        assertFalse(failure.message.contains("statfs failed at /data"))
        assertTrue(failure.message.contains("IllegalStateException"))
    }

    // ---- 形状与元数据 ------------------------------------------------------

    @Test
    fun `输出的键集合与 Worker 的 outputSchema 完全一致`() {
        assertEquals(
            setOf("volume", "total_bytes", "available_bytes", "used_bytes", "usage_percent"),
            StorageCapability.OUTPUT_KEYS,
        )
        val payload = successPayload(RawStorageReading("internal_data", 1_000L, 400L))
        assertEquals(StorageCapability.OUTPUT_KEYS, payload.keys().asSequence().toSet())
    }

    @Test
    fun `不接受任何参数`() {
        assertTrue(capability().allowedArguments.isEmpty())
    }

    @Test
    fun `元数据如实描述存储能力，且不声明任何权限`() {
        val metadata = StorageCapability.METADATA

        assertEquals("device_get_storage", metadata.name)
        assertEquals("存储空间", metadata.displayName)
        assertEquals(CapabilityCategory.Storage, metadata.category)
        assertTrue(metadata.readOnly)
        assertTrue(metadata.arguments.isEmpty())
        assertTrue("存储能力不该声明任何权限", metadata.declaredPermissions.isEmpty())
        assertFalse(metadata.requiresRuntimePermission)
    }

    @Test
    fun `工具名符合协议允许的形状`() {
        assertTrue(Regex("^[a-z][a-z0-9_]{0,63}$").matches(StorageCapability.NAME))
    }

    @Test
    fun `内部数据卷标识是稳定的短名`() {
        assertEquals("internal_data", AndroidStorageSource.VOLUME)
    }

    // ---- 通过分发器调用（与真实链路同一段代码）-----------------------------

    @Test
    fun `通过分发器调用时，成功结果被摊平成 tool_result 的信封`() {
        val dispatcher = CapabilityDispatcher(
            listOf(capability(RawStorageReading("internal_data", 8_000L, 2_000L))),
        )

        val outcome = dispatcher.handle("tr-storage-1", StorageCapability.NAME, JSONObject())

        assertTrue(outcome.ok)
        val obj = JSONObject(outcome.frame!!)
        assertEquals("tool_result", obj.getString("type"))
        // 帧里刻意不带 tool_name（Worker 用自己的请求记账补），手机侧只在 outcome 上留名字。
        assertEquals(StorageCapability.NAME, outcome.toolName)
        assertFalse(obj.has("tool_name"))
        val result = obj.getJSONObject("result")
        assertEquals(8_000L, result.getLong("total_bytes"))
        assertEquals(2_000L, result.getLong("available_bytes"))
        assertEquals(75.0, result.getDouble("usage_percent"), 0.0001)
    }

    @Test
    fun `通过分发器调用时，总开关关闭回 not_permitted 且不读容量`() {
        var reads = 0
        val counting = object : StorageSource {
            override fun read(): RawStorageReading {
                reads += 1
                return RawStorageReading("internal_data", 1_000L, 400L)
            }
        }
        val dispatcher = CapabilityDispatcher(
            listOf(StorageCapability(counting)),
            policy = { false },
        )

        val outcome = dispatcher.handle("tr-storage-2", StorageCapability.NAME, JSONObject())

        assertFalse(outcome.ok)
        assertEquals(ToolResultErrorCode.NOT_PERMITTED, outcome.errorCode)
        assertEquals("总开关关闭时不该碰文件系统", 0, reads)
    }

    @Test
    fun `通过分发器调用时，容量为零的能力失败会被转达`() {
        val dispatcher = CapabilityDispatcher(
            listOf(capability(RawStorageReading("internal_data", 0L, 0L))),
        )

        val outcome = dispatcher.handle("tr-storage-3", StorageCapability.NAME, JSONObject())

        assertFalse(outcome.ok)
        assertEquals(ToolResultErrorCode.TOOL_UNAVAILABLE, outcome.errorCode)
    }
}
