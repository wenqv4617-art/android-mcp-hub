package com.androidmcphub.app.ui.model

import com.androidmcphub.app.capability.BuiltInCapabilities
import com.androidmcphub.app.capability.CapabilityDispatcher
import com.androidmcphub.app.capability.NativeSources
import com.androidmcphub.app.capability.NetworkSource
import com.androidmcphub.app.capability.RawBatteryReading
import com.androidmcphub.app.capability.RawNetworkReading
import com.androidmcphub.app.capability.RawStorageReading
import com.androidmcphub.app.capability.BatterySource
import com.androidmcphub.app.capability.StorageSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具目录的 JVM 单测。
 *
 * 最重要的那一条是 `目录与真正能执行的能力注册表逐字一致`：它用**真实的**
 * 注册清单（[BuiltInCapabilities]）构造一个 [CapabilityDispatcher]
 * （与 `service/PhoneLinkService.kt` 里同一个类、同一份清单），拿它的 `names()`
 * 与目录比对 —— 这是唯一能在 JVM 上验证"界面说的工具 == 真正能执行的工具"的办法，
 * 也是 Gate 2.5"单一可信来源"落地的验收点。
 */
class ToolCatalogTest {

    /** 假的电池来源：这里只关心**工具名**，不关心读数。 */
    private class FakeBatterySource : BatterySource {
        override fun read(): RawBatteryReading = RawBatteryReading(
            level = 50,
            scale = 100,
            status = null,
            plugged = null,
            powerSaveMode = null,
        )
    }

    private class FakeNetworkSource : NetworkSource {
        override fun read(): RawNetworkReading = RawNetworkReading(hasActiveNetwork = true)
    }

    private class FakeStorageSource : StorageSource {
        override fun read(): RawStorageReading = RawStorageReading(
            volume = "internal_data",
            totalBytes = 1_000L,
            availableBytes = 400L,
        )
    }

    private val fakeSources = NativeSources(
        battery = FakeBatterySource(),
        network = FakeNetworkSource(),
        storage = FakeStorageSource(),
    )

    private fun realRegistryNames(): List<String> =
        CapabilityDispatcher(BuiltInCapabilities.create(fakeSources)).names()

    @Test
    fun `目录与真正能执行的能力注册表逐字一致`() {
        assertEquals(realRegistryNames(), ToolCatalog.localNames)
    }

    @Test
    fun `注册清单本身就是界面目录的数据源`() {
        assertEquals(BuiltInCapabilities.names, ToolCatalog.localNames)
        assertEquals(
            BuiltInCapabilities.metadata.map { it.name }.sorted(),
            ToolCatalog.local.map { it.name }.sorted(),
        )
    }

    @Test
    fun `目录里没有 phone_ping`() {
        assertFalse(ToolCatalog.local.any { it.name == ToolCatalog.DIAGNOSTIC_PING })
        assertFalse(ToolCatalog.localNames.contains(ToolCatalog.DIAGNOSTIC_PING))
    }

    @Test
    fun `id 唯一且工具名符合协议形状`() {
        val pattern = Regex("^[a-z][a-z0-9_]{0,63}$")
        assertEquals(
            "id 必须唯一",
            ToolCatalog.all.size,
            ToolCatalog.all.map { it.id }.toSet().size,
        )
        ToolCatalog.all.forEach { tool ->
            assertTrue("工具名非法：${tool.name}", pattern.matches(tool.name))
            assertTrue("说明不能为空：${tool.id}", tool.description.isNotBlank())
            assertEquals("本轮 id 与协议工具名一致", tool.name, tool.id)
        }
    }

    @Test
    fun `三个本机工具都在目录里`() {
        assertEquals(
            listOf("battery_get_status", "device_get_network", "device_get_storage"),
            ToolCatalog.localNames,
        )
    }

    @Test
    fun `电池工具如实声明无参数与无权限`() {
        val battery = ToolCatalog.byId("battery_get_status")!!
        assertTrue(battery.arguments.isEmpty())
        assertFalse(battery.requiresPermission)
        assertTrue(battery.declaredPermissions.isEmpty())
        assertEquals(ToolCategory.Device, battery.category)
        assertEquals(ToolSource.BuiltIn, battery.source)
        assertTrue(battery.readOnly)
        assertTrue(battery.availableFromBuild)
    }

    @Test
    fun `网络工具无参数、零运行期权限但声明网络状态权限`() {
        val network = ToolCatalog.byId("device_get_network")!!
        assertTrue(network.arguments.isEmpty())
        assertEquals(ToolCategory.Network, network.category)
        assertTrue(network.readOnly)
        assertEquals(listOf("android.permission.ACCESS_NETWORK_STATE"), network.declaredPermissions)
        // 普通权限（安装时授予）不该被当成"需要用户授权"，否则工具会一直是灰的。
        assertFalse(network.requiresPermission)
        assertEquals(ToolAvailability.Enabled, network.availability(capabilitiesEnabled = true))
    }

    @Test
    fun `存储工具无参数且不声明任何权限`() {
        val storage = ToolCatalog.byId("device_get_storage")!!
        assertTrue(storage.arguments.isEmpty())
        assertEquals(ToolCategory.Storage, storage.category)
        assertTrue(storage.readOnly)
        assertTrue(storage.declaredPermissions.isEmpty())
        assertFalse(storage.requiresPermission)
    }

    @Test
    fun `可用性由能力总开关决定`() {
        ToolCatalog.local.forEach { tool ->
            assertEquals(ToolAvailability.Enabled, tool.availability(capabilitiesEnabled = true))
            assertEquals(ToolAvailability.SwitchOff, tool.availability(capabilitiesEnabled = false))
        }
    }

    @Test
    fun `未实现的能力不会显示成已启用`() {
        val future = ToolCatalog.local.first().copy(id = "x", name = "x", availableFromBuild = false)
        assertEquals(ToolAvailability.ComingSoon, future.availability(capabilitiesEnabled = true))
    }

    @Test
    fun `搜索按名称与说明命中`() {
        val tools = ToolCatalog.local
        assertEquals(1, filterTools(tools, query = "battery", category = null).size)
        assertEquals(1, filterTools(tools, query = "BATTERY", category = null).size)
        assertEquals(1, filterTools(tools, query = "network", category = null).size)
        assertEquals(1, filterTools(tools, query = "storage", category = null).size)
        assertTrue(filterTools(tools, query = "电量", category = null).isNotEmpty())
        assertTrue(filterTools(tools, query = "存储", category = null).isNotEmpty())
        assertEquals(0, filterTools(tools, query = "不存在的工具", category = null).size)
        assertEquals(tools.size, filterTools(tools, query = "  ", category = null).size)
    }

    @Test
    fun `分类筛选设备命中而其他分类为空`() {
        val tools = ToolCatalog.local
        assertEquals(1, filterTools(tools, query = "", category = ToolCategory.Device).size)
        assertEquals(1, filterTools(tools, query = "", category = ToolCategory.Network).size)
        assertEquals(1, filterTools(tools, query = "", category = ToolCategory.Storage).size)
        assertEquals(0, filterTools(tools, query = "", category = ToolCategory.Media).size)
        assertEquals(0, filterTools(tools, query = "", category = ToolCategory.Notification).size)
    }

    @Test
    fun `筛选条只画有工具的分类`() {
        assertEquals(
            listOf(ToolCategory.Device, ToolCategory.Network, ToolCategory.Storage),
            ToolCatalog.categoriesInUse(),
        )
    }

    @Test
    fun `byId 能找到工具、找不到时返回 null`() {
        assertNotNull(ToolCatalog.byId("battery_get_status"))
        assertNotNull(ToolCatalog.byId("device_get_network"))
        assertNotNull(ToolCatalog.byId("device_get_storage"))
        assertEquals(null, ToolCatalog.byId("nope"))
    }
}
