package com.androidmcphub.app.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 集中审计问题 5：能力总开关的默认值必须是**关**。
 *
 * 为什么值得单独一个文件：这个默认值决定"装完这个应用，远端能不能读本机的电池状态"。
 * 它只写在 `HubPrefs.capabilitiesEnabled()` 里一个 `getBoolean` 的第二个实参上 ——
 * 极易被一次"顺手改回默认开"的改动悄悄翻掉，而那种翻转在别的测试里**看不出来**：
 * 能力测试都会传自己的 policy，`HubConfigTest` 只关心配置键，UI 无法在 JVM 里跑。
 * 所以这里把默认值钉死：任何把它改回 `true` 的改动都必须显式改这个测试并给出理由。
 */
class CapabilitySwitchDefaultTest {

    @Test
    fun `首次安装（键不存在）时总开关默认关闭`() {
        assertFalse(
            "首次安装默认必须是关：用户从未对「允许远端读本机能力」表达过同意",
            HubPrefs.DEFAULT_CAPABILITIES_ENABLED,
        )
    }

    @Test
    fun `总开关不是 HubConfig 的一部分，键存在时以用户的值为准`() {
        // 这个键必须被 HubPrefs.load() 跳过：它是布尔，不是配置字符串
        assertTrue(HubPrefs.KEY_CAPABILITIES_ENABLED in HubPrefs.NON_CONFIG_KEYS)
    }
}
