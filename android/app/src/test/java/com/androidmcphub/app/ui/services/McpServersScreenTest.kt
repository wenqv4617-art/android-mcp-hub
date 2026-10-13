package com.androidmcphub.app.ui.services

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.androidmcphub.app.hub.HubApp
import com.androidmcphub.app.ui.HubViewModel
import com.androidmcphub.app.ui.theme.AndroidMcpHubTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * 「服务」页的真实界面测试（Robolectric + Compose UI 测试规则，不需要模拟器）。
 *
 * 这一条走的是**整条用户路径**：底栏进服务页 → 添加服务器 → 填名称与地址 → 保存 →
 * 回到列表看到它。之所以用真的 [HubApp] 而不是单独渲染某个 Composable，是因为
 * Stage 1 真的踩过"点首页进了工具页"这种返回栈故障 —— 那种故障只有在 NavHost
 * 真跑起来的时候才看得见，单独渲染一个页面永远测不到接线。
 *
 * 地址刻意用 `http://127.0.0.1:1/mcp`（本机回环、端口必然连不上）：
 *  * 通过 `McpUrlPolicy` 的明文 http 准入（只允许回环），所以能真的存下去；
 *  * 发现流程会立刻失败，不会去碰真实网络 —— 单测里不该有外部依赖。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
@ConscryptMode(ConscryptMode.Mode.OFF)
class McpServersScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun openServicesTab() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        composeRule.setContent {
            AndroidMcpHubTheme {
                HubApp(viewModel = HubViewModel(application))
            }
        }
        composeRule.onNodeWithText("服务").performClick()
        composeRule.waitForIdle()
    }

    @Test
    fun `冷启动进服务页看到空状态与添加入口`() {
        openServicesTab()

        composeRule.onNodeWithText("尚未添加 MCP 服务").assertExists()
        composeRule.onNodeWithText("添加 MCP 服务器").assertExists()
    }

    @Test
    fun `添加一台服务器之后它出现在列表里`() {
        openServicesTab()

        composeRule.onNodeWithText("添加 MCP 服务器").performClick()
        composeRule.waitForIdle()

        // 表单页：字段按出现顺序是「名称」「地址」。
        val fields = composeRule.onAllNodes(hasSetTextAction())
        fields[0].performTextInput("测试服务器")
        fields[1].performTextInput("http://127.0.0.1:1/mcp")

        composeRule.onNodeWithText("添加").performClick()
        composeRule.waitForIdle()

        // 回到列表：名称、主机、数量都对得上（数量只可能因为本用例自己添加而变化）。
        composeRule.onNodeWithText("测试服务器").assertExists()
        composeRule.onNodeWithText("127.0.0.1").assertExists()
        composeRule.onNodeWithText("1 个").assertExists()
        composeRule.onNodeWithText("刷新全部").assertExists()
    }

    @Test
    fun `地址不合规时留在表单页并给出原因`() {
        openServicesTab()

        composeRule.onNodeWithText("添加 MCP 服务器").performClick()
        composeRule.waitForIdle()

        val fields = composeRule.onAllNodes(hasSetTextAction())
        fields[0].performTextInput("坏地址")
        // 明文 http 指向外部主机：允许保存的话，密钥就会以明文过网。
        fields[1].performTextInput("http://example.com/mcp")

        composeRule.onNodeWithText("添加").performClick()
        composeRule.waitForIdle()

        // 没跳走：还在表单页（表单页才有「保存/添加」按钮与请求头区块）。
        composeRule.onNodeWithText("添加请求头").assertExists()
        // 也没落盘：列表页的计数文案不该出现。`onAllNodesWithText` 在 0 个匹配时返回空集合，
        // 所以这条断言同时证明"页面没跳回去"和"没有悄悄存下来"。
        composeRule.onAllNodesWithText("1 个").assertCountEquals(0)
    }
}
