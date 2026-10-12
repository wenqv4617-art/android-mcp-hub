package com.androidmcphub.app.ui.model

import com.androidmcphub.app.capability.BatteryCapability
import com.androidmcphub.app.capability.BatterySource
import com.androidmcphub.app.capability.CapabilityDispatcher
import com.androidmcphub.app.capability.RawBatteryReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具目录的 JVM 单测。
 *
 * 最重要的那一条是 `目录与本机真实能力注册表逐字一致`：它用真实的
 * [BatteryCapability] 构造一个 [CapabilityDispatcher]（与
 * `service/PhoneLinkService.kt:214` 同一个类、同一个能力），拿它的
 * `names()` 与目录比对 —— 这是唯一能在 JVM 上验证"界面说的能力 == 真正能执行的能力"
 * 的办法，也是防止将来能力改名后界面继续显示旧名字的兜底。
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

    private fun realRegistryNames(): List<String> =
        CapabilityDispatcher(listOf(BatteryCapability(FakeBatterySource()))).names()

    @Test
    fun `目录与本机真实能力注册表逐字一致`() {
        assertEquals(realRegistryNames(), ToolCatalog.localNames)
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
    fun `电池工具如实声明无参数与无权限`() {
        val battery = ToolCatalog.BatteryGetStatus
        assertTrue(battery.arguments.isEmpty())
        assertFalse(battery.requiresPermission)
        assertEquals(ToolCategory.Device, battery.category)
        assertEquals(ToolSource.BuiltIn, battery.source)
        assertTrue(battery.readOnly)
    }

    @Test
    fun `可用性由能力总开关决定`() {
        val battery = ToolCatalog.BatteryGetStatus
        assertEquals(ToolAvailability.Enabled, battery.availability(capabilitiesEnabled = true))
        assertEquals(ToolAvailability.SwitchOff, battery.availability(capabilitiesEnabled = false))
    }

    @Test
    fun `未实现的能力不会显示成已启用`() {
        val future = ToolCatalog.BatteryGetStatus.copy(id = "x", name = "x", availableFromBuild = false)
        assertEquals(ToolAvailability.ComingSoon, future.availability(capabilitiesEnabled = true))
    }

    @Test
    fun `搜索按名称与说明命中`() {
        val tools = ToolCatalog.local
        assertEquals(1, filterTools(tools, query = "battery", category = null).size)
        assertEquals(1, filterTools(tools, query = "BATTERY", category = null).size)
        assertTrue(filterTools(tools, query = "电量", category = null).isNotEmpty())
        assertEquals(0, filterTools(tools, query = "不存在的工具", category = null).size)
        assertEquals(tools.size, filterTools(tools, query = "  ", category = null).size)
    }

    @Test
    fun `分类筛选设备命中而其他分类为空`() {
        val tools = ToolCatalog.local
        assertEquals(1, filterTools(tools, query = "", category = ToolCategory.Device).size)
        assertEquals(0, filterTools(tools, query = "", category = ToolCategory.Media).size)
        assertEquals(0, filterTools(tools, query = "", category = ToolCategory.Notification).size)
    }

    @Test
    fun `byId 能找到工具、找不到时返回 null`() {
        assertNotNull(ToolCatalog.byId("battery_get_status"))
        assertEquals(null, ToolCatalog.byId("nope"))
    }
}
