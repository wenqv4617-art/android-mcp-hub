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
    fun `注册清单是完整且有序的内置工具集合`() {
        // 这份清单与服务端 worker/src/mcp/tools.ts 的 TOOL_REGISTRY 必须逐字对应。
        // 少一个 = 界面/协议说没有这个工具；多一个 = 执行层愿意执行一个服务端不认识的工具。
        assertEquals(
            listOf(
                "alarm_create",
                "alarm_open_list",
                "battery_get_status",
                "calendar_create_event",
                "calendar_delete_event",
                "calendar_list_calendars",
                "calendar_list_events",
                "calendar_update_event",
                "device_get_network",
                "device_get_status",
                "device_get_storage",
                "device_get_usage_stats",
                "device_open_app",
                "device_open_settings",
                "device_post_notification",
                "device_set_flashlight",
                "device_set_volume",
                "memo_create",
                "memo_delete",
                "memo_get",
                "memo_list",
                "memo_update",
                "reminder_complete",
                "reminder_create",
                "reminder_delete",
                "reminder_list",
                "reminder_update",
                "todo_complete",
                "todo_create",
                "todo_delete",
                "todo_list",
                "todo_update",
            ),
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
        assertEquals(
            BuiltInCapabilities.metadata.sortedBy { it.name },
            dispatcher.catalog(),
        )
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
    fun `每条元数据都自洽`() {
        val names = BuiltInCapabilities.metadata.map { it.name }
        assertEquals("工具名不能重复", names.size, names.toSet().size)
        BuiltInCapabilities.metadata.forEach { metadata ->
            assertTrue("${metadata.name} 说明不能为空", metadata.description.isNotBlank())
            assertTrue("${metadata.name} 显示名不能为空", metadata.displayName.isNotBlank())
            if (metadata.destructive) {
                assertFalse("${metadata.name} 是删除类，不能同时被标成只读", metadata.readOnly)
            }
            if (metadata.requiresRuntimePermission) {
                assertTrue(
                    "${metadata.name} 要求运行时权限，就必须声明至少一个权限",
                    metadata.declaredPermissions.isNotEmpty(),
                )
                assertTrue(
                    "${metadata.name} 要求运行时权限，就必须告诉用户去哪开",
                    !metadata.permissionHint.isNullOrBlank(),
                )
            }
        }
    }

    @Test
    fun `运行时权限只出现在确实需要它的工具上`() {
        assertEquals(
            setOf(
                "calendar_create_event",
                "calendar_delete_event",
                "calendar_list_calendars",
                "calendar_list_events",
                "calendar_update_event",
                "device_get_usage_stats",
                "device_post_notification",
                "device_set_flashlight",
                "reminder_create",
            ),
            BuiltInCapabilities.metadata
                .filter { it.requiresRuntimePermission }
                .map { it.name }
                .toSet(),
        )
    }

    @Test
    fun `声明的权限都是完整权限名`() {
        val namespaces = listOf("android.permission.", "com.android.alarm.permission.")
        BuiltInCapabilities.metadata
            .flatMap { it.declaredPermissions }
            .forEach { permission ->
                assertTrue(
                    "权限名看起来不完整：$permission",
                    namespaces.any { permission.startsWith(it) },
                )
            }
    }
}
