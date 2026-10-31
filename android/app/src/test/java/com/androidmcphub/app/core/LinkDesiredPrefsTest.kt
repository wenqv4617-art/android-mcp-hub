package com.androidmcphub.app.core

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * V1.6.1：「用户是否希望保持连接」这个意图必须跨进程存活。
 *
 * 它是 `START_STICKY` 重建服务时**唯一**的依据：
 *  - 键不存在（全新安装）→ 不连，不能凭空替用户做决定；
 *  - 用户点过「连接」→ 落盘 true，进程被杀后系统重建服务时恢复；
 *  - 用户点过「断开」→ 落盘 false，重建服务时**不得**偷偷再连。
 *
 * 同时钉住它不能混进 [HubConfig]：那会把一个布尔开关塞进配置 map，
 * 跟着"保存设置"一起被当成配置项读写。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
// Robolectric 默认会把 Conscrypt 装成 JVM 的全局 SecurityProvider；同一测试 JVM 里
// 后面的 OkHttp 用例（PhoneLinkReliabilityTest）会因此选中 ConscryptPlatform 并
// 在 JDK 的 trust manager 上报 "Not a Conscrypt trust manager"。本用例不碰 TLS，
// 与另外两个 Robolectric 用例保持一致地关掉它。
@ConscryptMode(ConscryptMode.Mode.OFF)
class LinkDesiredPrefsTest {

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val prefs = HubPrefs(context)

    @Before
    fun setUp() {
        // 删掉而不是写 false：第一条用例要验证的是"键不存在"的默认值。
        rawPrefs().edit().remove(HubPrefs.KEY_LINK_DESIRED).commit()
    }

    @After
    fun tearDown() {
        rawPrefs().edit().remove(HubPrefs.KEY_LINK_DESIRED).commit()
    }

    private fun rawPrefs() =
        context.getSharedPreferences(HubPrefs.PREF_FILE, android.content.Context.MODE_PRIVATE)

    @Test
    fun `全新安装默认不想连接`() {
        assertFalse("没有任何用户动作时不能替用户决定保持连接", HubPrefs(context).linkDesired())
    }

    @Test
    fun `连接意图跨实例存活（模拟进程被杀后重建）`() {
        prefs.setLinkDesired(true)

        assertTrue("新实例必须读回 true", HubPrefs(context).linkDesired())

        prefs.setLinkDesired(false)

        assertFalse("用户断开后必须读回 false", HubPrefs(context).linkDesired())
    }

    @Test
    fun `连接意图不属于 HubConfig`() {
        prefs.setLinkDesired(true)

        assertTrue(HubPrefs.KEY_LINK_DESIRED in HubPrefs.NON_CONFIG_KEYS)
        assertFalse(
            "布尔开关不能出现在配置 map 里",
            HubPrefs(context).load().toMap().containsKey(HubPrefs.KEY_LINK_DESIRED),
        )
    }
}
