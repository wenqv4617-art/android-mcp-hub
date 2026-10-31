package com.androidmcphub.app.service

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * V1.6.1：连接的所有权必须只属于前台服务，界面生命周期不得碰它。
 *
 * 这次排查要回答的问题之一是"切到后台后，是谁把连接关掉的"。结论是：
 * 没有任何 Activity/Composable 持有或关闭 WebSocket —— 连接由
 * [PhoneLinkService] 创建并持有，界面只读 [LinkBus]。这个结论如果不钉住，
 * 以后任何一个"在 onStop 里顺手断开"的改动都会悄无声息地把后台保活打回原形，
 * 而 Compose/Robolectric 都很难在 JVM 上跑出这条回归。
 *
 * 所以这里直接扫源码。它不证明"运行时一定不断线"（那需要真机），
 * 但能保证**没有任何代码路径**在界面生命周期里发起断开。
 */
class ConnectionLifecycleOwnershipTest {

    private val sourceRoot: File = findSourceRoot()

    private fun read(relative: String): String {
        val file = File(sourceRoot, relative)
        assertTrue("找不到源文件：$relative", file.isFile)
        return file.readText()
    }

    @Test
    fun `WebSocket 客户端只在服务里创建`() {
        val offenders = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            // 类声明本身（`class PhoneLinkClient(`）不算"创建连接"。
            .filter { it.name != "PhoneLinkClient.kt" }
            .filter { it.readText().contains("PhoneLinkClient(") }
            .map { it.relativeTo(sourceRoot).invariantSeparatorsPath }
            .toList()

        assertTrue(
            "连接只能由服务创建，界面不得自己拿一份：$offenders",
            offenders.isNotEmpty() && offenders.all { it.endsWith("service/PhoneLinkService.kt") },
        )
    }

    @Test
    fun `Activity 与导航层不发起连接或断开`() {
        val files = listOf(
            "com/androidmcphub/app/MainActivity.kt",
            "com/androidmcphub/app/SplashActivity.kt",
            "com/androidmcphub/app/hub/HubApp.kt",
        )
        for (relative in files) {
            val text = read(relative)
            assertTrue(
                "$relative 不得直接调用 PhoneLinkService.connect/disconnect",
                !text.contains("PhoneLinkService.connect") && !text.contains("PhoneLinkService.disconnect"),
            )
            assertTrue(
                "$relative 不得直接 stopService",
                !text.contains("stopService("),
            )
        }
    }

    @Test
    fun `Activity 不在生命周期回调里收尾连接`() {
        val main = read("com/androidmcphub/app/MainActivity.kt")
        listOf("onPause", "onStop", "onDestroy", "onSaveInstanceState").forEach { callback ->
            assertTrue(
                "MainActivity 不应重写 $callback —— 进入后台不该有任何连接动作",
                !main.contains("override fun $callback"),
            )
        }
    }

    @Test
    fun `ViewModel 只在用户动作里调用连接服务，且没有 onCleared 清理连接`() {
        val vm = read("com/androidmcphub/app/ui/HubViewModel.kt")
        assertTrue(
            "ViewModel 销毁（Activity 旋转/回收）不得关闭连接",
            !vm.contains("onCleared"),
        )
        // connect 有两个入口（用户点连接 + 恢复上次意图），disconnect 只有一个：
        // 用户点「断开」。每一处都必须落在显式的函数里，而不是生命周期回调里。
        assertTrue("断开只允许由 disconnect() 这个用户动作触发", vm.contains("fun disconnect()"))
        assertTrue("恢复连接必须走 PhoneLinkService.connect", vm.contains("PhoneLinkService.connect"))
    }

    @Test
    fun `服务重建按连接意图恢复且不再永久停服`() {
        val service = read("com/androidmcphub/app/service/PhoneLinkService.kt")
        assertTrue(
            "onStartCommand 必须把系统重建（null intent）交给 ServiceStartPolicy",
            service.contains("ServiceStartPolicy.decide"),
        )
        assertTrue(
            "看门狗只能强制重连，不得再调用 stopForegroundService 收尾",
            service.contains("onWatchdogTimeout"),
        )
        assertTrue(
            "连接意图必须在用户动作时落盘",
            service.contains("prefs.setLinkDesired(true)") && service.contains("prefs.setLinkDesired(false)"),
        )
        assertTrue(
            "用户希望连接时必须返回 START_STICKY",
            service.contains("START_STICKY"),
        )
    }

    /** 从 `user.dir` 往上找 `src/main/java`（与 LogRedactionTest 相同的定位方式）。 */
    private fun findSourceRoot(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val nested = File(dir, "app/src/main/java")
            if (nested.isDirectory) return nested
            val direct = File(dir, "src/main/java")
            if (direct.isDirectory) return direct
            dir = dir.parentFile
        }
        fail("找不到 src/main/java，当前目录=${File(".").absolutePath}")
        error("unreachable")
    }
}
