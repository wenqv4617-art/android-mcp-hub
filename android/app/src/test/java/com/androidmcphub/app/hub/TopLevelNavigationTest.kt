package com.androidmcphub.app.hub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 顶层导航策略的单测（纯 JVM，不依赖 Android/NavController）。
 *
 * 为什么这些断言值得存在：Stage 1 的真机故障是「从首页快捷入口进工具页之后，
 * 点底部首页回不去 / 点首页却进了工具页」。根因不是某个按钮写错，而是**同一件事
 * 有两套写法**（裸 navigate vs 底导的 saveState 配方）。修复方式是把规则收敛成
 * [navigateToTopLevel] + [topLevelNavDecision] 两个函数 —— 这个文件负责钉住
 * 「路由 ↔ 标签」的映射与「同一标签不重复导航」这条规则，谁再把裸 navigate
 * 加回页面内快捷入口，路由/映射断言会先炸。
 *
 * 覆盖范围对应任务书 §8.1 的可自动化部分：四个标签互相跳转的决策、
 * 连续点击同一标签、二级页面不会被误判成顶层标签。
 * 真实的返回栈行为（返回键、状态恢复）无法在纯 JVM 里验证，见
 * `docs/UI-Stage2-Report.md` 的「已知限制」。
 */
class TopLevelNavigationTest {

    @Test
    fun `四个标签都注册了唯一的顶层路由`() {
        val routes = HubTab.entries.map { it.route }
        assertEquals(HubTab.entries.size, routes.toSet().size)
        HubTab.entries.forEach { tab ->
            assertTrue("路由不能为空：$tab", tab.route.isNotBlank())
            assertTrue("路由要能直接当 destination 用：${tab.route}", !tab.route.contains("/"))
        }
    }

    @Test
    fun `每个标签路由都能解析回自己`() {
        HubTab.entries.forEach { tab ->
            assertEquals(tab, hubTabOf(tab.route))
        }
    }

    @Test
    fun `二级页面不会被当成顶层标签`() {
        // 二级页面的路由都必须解析成 null —— 否则底栏会在子页上亮起错误的标签，
        // 或者子页被当作标签页 popUpTo 掉，返回栈直接错乱。
        val subRoutes = listOf(
            Routes.CONNECTION_DETAIL,
            Routes.TOOL_DETAIL,
            Routes.TOOLS_HELP,
            Routes.DIAGNOSTICS,
            Routes.CONNECTION_SETTINGS,
            Routes.ADVANCED_SETTINGS,
            Routes.TOKEN_SETTINGS,
            Routes.toolDetail("device_get_battery"),
        )
        subRoutes.forEach { route ->
            assertNull("不应被识别成顶层标签：$route", hubTabOf(route))
        }
    }

    @Test
    fun `未知或空路由解析为 null`() {
        assertNull(hubTabOf(null))
        assertNull(hubTabOf(""))
        assertNull(hubTabOf("not/a/route"))
    }

    @Test
    fun `点击当前标签不导航`() {
        HubTab.entries.forEach { tab ->
            assertEquals(
                TopLevelNavDecision.StayOnCurrentTab,
                topLevelNavDecision(currentTab = tab, target = tab),
            )
        }
    }

    @Test
    fun `四个标签之间的全部切换都指向目标标签`() {
        // 4 × 4 全组合：同标签 → 不导航；异标签 → 切到目标。
        HubTab.entries.forEach { current ->
            HubTab.entries.forEach { target ->
                val decision = topLevelNavDecision(currentTab = current, target = target)
                if (current == target) {
                    assertEquals(TopLevelNavDecision.StayOnCurrentTab, decision)
                } else {
                    assertEquals(TopLevelNavDecision.SwitchTo(target), decision)
                }
            }
        }
    }

    @Test
    fun `不知道当前标签时一律导航到目标`() {
        HubTab.entries.forEach { target ->
            assertEquals(
                TopLevelNavDecision.SwitchTo(target),
                topLevelNavDecision(currentTab = null, target = target),
            )
        }
    }

    @Test
    fun `工具详情路由会带上参数占位符`() {
        assertTrue(Routes.TOOL_DETAIL.contains("{" + Routes.ARG_TOOL_ID + "}"))
        assertEquals("tools/detail/device_get_battery", Routes.toolDetail("device_get_battery"))
    }

    @Test
    fun `首页快捷入口与底栏算出同一个决策`() {
        // 真机故障场景：首页 →「工具目录」（快捷入口），然后点底栏「首页」。
        // 修复后两处都走 navigateToTopLevel，于是：
        //  * 快捷入口：Home → Tools 是 SwitchTo；
        //  * 底栏点首页：current=Tools → Home 也是 SwitchTo。
        assertEquals(
            TopLevelNavDecision.SwitchTo(HubTab.Tools),
            topLevelNavDecision(currentTab = HubTab.Home, target = HubTab.Tools),
        )
        assertEquals(
            TopLevelNavDecision.SwitchTo(HubTab.Home),
            topLevelNavDecision(currentTab = HubTab.Tools, target = HubTab.Home),
        )
    }
}
