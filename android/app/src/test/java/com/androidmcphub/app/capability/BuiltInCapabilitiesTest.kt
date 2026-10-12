package com.androidmcphub.app.capability

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Gate 2.5 §四：**单一可信来源**的注册清单。
 *
 * 这些断言保护的是"界面说的工具 == 真正能执行的工具"这条不变式：
 * 清单改错、元数据与实现对不上、有人偷偷加一个只有界面知道的条目，
 * 都应该在这里或者构造 [CapabilityDispatcher] 时立刻失败。
 */
class BuiltInCapabilitiesTest {

    // 三个来源的 read() 同名不同返回类型，不能用同一个类实现（JVM 签名冲突），
    // 所以这里老老实实写三个小假类。
    private class FakeBatterySource : BatterySource {
        override fun read(): RawBatteryReading = RawBatteryReading(50, 100, null, null, null)
    }

    private class FakeNetworkSource : NetworkSource {
        override fun read(): RawNetworkReading = RawNetworkReading(hasActiveNetwork = true)
    }

    private class FakeStorageSource : StorageSource {
        override fun read(): RawStorageReading = RawStorageReading("internal_data", 1_000L, 400L)
    }

    private val sources: NativeSources = NativeSources(
        battery = FakeBatterySource(),
        network = FakeNetworkSource(),
        storage = FakeStorageSource(),
    )

    @Test
    fun `注册清单就是三个内置工具（有序）`() {
        assertEquals(
            listOf("battery_get_status", "device_get_network", "device_get_storage"),
            BuiltInCapabilities.names,
        )
        assertEquals(
            BuiltInCapabilities.metadata.map { it.name }.sorted(),
            BuiltInCapabilities.names,
        )
    }

    @Test
    fun `清单里没有 phone_ping`() {
        // phone_ping 是连接诊断通道，不是本机 Capability。
        assertFalse(BuiltInCapabilities.names.contains("phone_ping"))
    }

    @Test
    fun `每条元数据的 name 与构造出来的能力实例一致`() {
        val created = BuiltInCapabilities.create(sources)

        assertEquals(BuiltInCapabilities.ALL.size, created.size)
        BuiltInCapabilities.ALL.zip(created).forEach { (entry, capability) ->
            assertEquals(entry.metadata.name, capability.name)
            assertEquals(entry.metadata.name, capability.metadata.name)
        }
    }

    @Test
    fun `分发器看到的目录与注册清单一致`() {
        val dispatcher = CapabilityDispatcher(BuiltInCapabilities.create(sources))

        assertEquals(BuiltInCapabilities.names, dispatcher.names())
        assertEquals(BuiltInCapabilities.metadata, dispatcher.catalog())
    }

    @Test
    fun `元数据与实现的名字对不上时，构造分发器直接失败`() {
        // 这正是"界面显示了一个不存在的工具"的成因，必须在构造时就炸掉。
        val mismatched = object : Capability {
            override val name: String = "device_get_network"
            override val allowedArguments: Set<String> = emptySet()
            override val metadata: CapabilityMetadata = CapabilityMetadata(
                name = "device_get_netwrk", // 打错一个字母
                displayName = "网络状态",
                description = "故意写错的元数据",
                category = CapabilityCategory.Network,
            )

            override fun execute(arguments: JSONObject): CapabilityResult =
                CapabilityResult.Success(JSONObject())
        }

        try {
            CapabilityDispatcher(listOf(mismatched))
            fail("元数据 name 与 Capability.name 不一致时必须构造失败")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("元数据的 name"))
        }
    }

    @Test
    fun `每个内置能力都是只读、零参数`() {
        BuiltInCapabilities.metadata.forEach { metadata ->
            assertTrue("${metadata.name} 必须是只读", metadata.readOnly)
            assertTrue("${metadata.name} 不该有参数", metadata.arguments.isEmpty())
            assertTrue("${metadata.name} 说明不能为空", metadata.description.isNotBlank())
            assertTrue("${metadata.name} 显示名不能为空", metadata.displayName.isNotBlank())
        }
    }

    @Test
    fun `需要用户当场批准的运行时权限一个都没有`() {
        // 本轮新增的两个能力都是普通权限或零权限：工具页不该出现"需要授权"的灰条目。
        BuiltInCapabilities.metadata.forEach { metadata ->
            assertFalse(metadata.name, metadata.requiresRuntimePermission)
        }
    }

    @Test
    fun `声明的权限都是完整权限名`() {
        BuiltInCapabilities.metadata
            .flatMap { it.declaredPermissions }
            .forEach { permission ->
                assertTrue("权限名看起来不完整：$permission", permission.startsWith("android.permission."))
            }
    }
}
