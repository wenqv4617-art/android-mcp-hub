package com.androidmcphub.app.hub

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.androidmcphub.app.ui.HubViewModel
import com.androidmcphub.app.ui.theme.AndroidMcpHubTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * 导航返回栈的**真实**测试（Robolectric + Compose UI 测试规则，不需要模拟器）。
 *
 * ## 为什么这些用例必须是真机式的，而不是纯逻辑单测
 *
 * Stage 1 装到真机上暴露的故障是：
 *  * 首页点「工具目录」→ 进工具页；
 *  * 之后点底部「首页」回不去；
 *  * 有一次点首页却进了工具页。
 *
 * 根因是返回栈记账（裸 `navigate` 与底导 `saveState/restoreState` 两套写法混用），
 * 只有**真的把 NavHost 跑起来**才能验证，[TopLevelNavigationTest] 那种纯函数
 * 断言测不到这一层。所以这里加载真实的 [HubApp] + 真实的 [HubViewModel]，
 * 在 Robolectric 提供的 Android 框架上点按钮、按返回键，断言看到的页面。
 *
 * 覆盖任务书 §8.1 的可自动化部分：
 *  1. 冷启动落在首页；
 *  2. 四个标签两两切换 + 连续点击同一标签；
 *  3. 首页快捷入口 → 工具页 → 底栏首页（**故障原场景**）；
 *  4. 二级页面（工具详情）→ 系统返回键回到工具页；
 *  5. 二级页面不显示底部导航（`currentTab == null`）。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
// 构建机是 linux-aarch64，而 Robolectric 依赖的 conscrypt-openjdk-uber 2.5.2
// 只带 linux-x86_64 / windows / osx 的原生库，装 OpenSSLProvider 时会
// UnsatisfiedLinkError。这些用例只验证导航返回栈（不做 TLS），关掉 Conscrypt 即可；
// 关掉它不影响被测代码 —— 被测代码用的是 AndroidKeyStore + javax.crypto。
@ConscryptMode(ConscryptMode.Mode.OFF)
class HubAppNavigationTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /** 用真实的 Application 构造真实的 ViewModel —— 与 MainActivity 的用法一致。 */
    private fun setUpHubApp() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = HubViewModel(application)
        composeRule.setContent {
            AndroidMcpHubTheme {
                HubApp(viewModel = viewModel)
            }
        }
        composeRule.waitForIdle()
    }

    /** 点底部导航的某个标签（标签按钮有 click 动作，页面标题没有，用它区分）。 */
    private fun tapTab(label: String) {
        composeRule.onNode(hasText(label) and hasClickAction()).performClick()
        composeRule.waitForIdle()
    }

    private fun pressBack() {
        composeRule.runOnUiThread {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeRule.waitForIdle()
    }

    /**
     * 断言"当前在哪一页"用的是**页面标题栏的副标题** —— 它在页面顶部，
     * 一定在视口里（LazyColumn 只组合可见项，滚不到的低处节点"存在但不可见"，
     * 用 `assertIsDisplayed` 会误报）。
     */
    private fun assertOnHome() {
        composeRule.onNodeWithText("Android MCP Hub").assertIsDisplayed()
    }

    private fun assertOnTools() {
        composeRule.onNodeWithText("本机能力与外部 MCP 工具的统一目录").assertIsDisplayed()
    }

    private fun assertOnServices() {
        composeRule.onNodeWithText("连接并管理第三方 MCP 服务器").assertIsDisplayed()
    }

    private fun assertOnSettings() {
        composeRule.onNodeWithText("连接配置、权限管理与应用设置").assertIsDisplayed()
    }

    /** 工具详情独有：只有详情页有「来源与可用性」这张卡。 */
    private fun assertOnToolDetail() {
        composeRule.onNodeWithText("来源与可用性").assertIsDisplayed()
    }

    @Test
    fun `冷启动落在首页`() {
        setUpHubApp()
        assertOnHome()
        // 首页第一张卡是连接状态：显示状态、不自动发起连接。
        composeRule.onNodeWithText("连接状态").assertIsDisplayed()
    }

    @Test
    fun `四个标签之间可以任意切换`() {
        setUpHubApp()
        tapTab("工具"); assertOnTools()
        tapTab("服务"); assertOnServices()
        tapTab("设置"); assertOnSettings()
        tapTab("首页"); assertOnHome()
        tapTab("服务"); assertOnServices()
        tapTab("工具"); assertOnTools()
        tapTab("首页"); assertOnHome()
    }

    @Test
    fun `连续点击当前标签不重复创建页面也不崩`() {
        setUpHubApp()
        tapTab("工具")
        tapTab("工具")
        tapTab("工具")
        assertOnTools()
        tapTab("首页")
        tapTab("首页")
        assertOnHome()
    }

    /**
     * 真机故障原场景：首页 →「工具目录」快捷入口 → 底栏「首页」。
     * 修复前这里会停留在工具页（或更糟，点首页又回到工具页）。
     */
    @Test
    fun `从首页快捷入口进工具页之后能回到首页`() {
        setUpHubApp()
        composeRule.onNodeWithText("工具目录").performClick()
        composeRule.waitForIdle()
        assertOnTools()

        tapTab("首页")
        assertOnHome()

        // 再进一次仍然正常（返回栈没有被上一次导航污染）。
        composeRule.onNodeWithText("工具目录").performClick()
        composeRule.waitForIdle()
        assertOnTools()
        tapTab("首页")
        assertOnHome()
    }

    /** 首页快捷入口与底栏进的是**同一个**工具页（不存在两套页面）。 */
    @Test
    fun `快捷入口与底栏进入同一个工具页`() {
        setUpHubApp()
        composeRule.onNodeWithText("工具目录").performClick()
        composeRule.waitForIdle()
        assertOnTools()
        // 底栏仍然高亮工具（此页仍是顶层工具页，工具详情的判定不能用）。
        tapTab("首页")
        assertOnHome()
        tapTab("工具")
        assertOnTools()
    }

    @Test
    fun `工具详情是二级页面且返回键回到工具页`() {
        setUpHubApp()
        tapTab("工具")
        // 工具卡显示的是真实能力名（目录里唯一那条本机能力 `battery_get_status`）。
        composeRule.onNode(hasText("battery_get_status") and hasClickAction()).performClick()
        composeRule.waitForIdle()
        assertOnToolDetail()
        // 二级页面不显示底部导航：底栏四个标签的点击节点全部消失。
        listOf("首页", "工具", "服务", "设置").forEach { label ->
            composeRule.onAllNodes(hasText(label) and hasClickAction()).assertCountEquals(0)
        }
        pressBack()
        assertOnTools()
    }

    @Test
    fun `工具页的系统返回键回到首页`() {
        setUpHubApp()
        tapTab("工具")
        assertOnTools()
        pressBack()
        assertOnHome()
    }
}
