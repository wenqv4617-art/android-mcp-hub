package com.androidmcphub.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * V1.6.2：后台连接的所有权契约（源码级）。
 *
 * 这组断言钉住的是**本次事故的直接成因**，而不是运行时行为 —— 那部分由
 * [com.androidmcphub.app.protocol.PhoneLinkTest] 与
 * [com.androidmcphub.app.net.PhoneLinkReliabilityTest] 用真实 OkHttp 链路覆盖：
 *
 *  1. `PhoneLinkService.onDestroy()` 曾经复用 `client.stop()`，而 `stop()` 的默认
 *     原因是「用户主动断开」并记一条 `USER_DISCONNECTED`。系统回收服务时，诊断页
 *     因此显示"用户主动断开"。现在 `onDestroy` 只能走 `shutdown()`（abandon 语义）。
 *  2. 只有真实用户动作可以清除落盘的连接意图 `linkDesired`。系统停止服务、启动失败
 *     都必须保留它，否则"用户明明点过连接"会变成"应用忘了"。
 *  3. 前台服务启动失败/停止时不得顺手清掉意图，也不得冒充用户动作。
 *  4. 断开来源必须随 Intent 传递，诊断页才能区分界面按钮与通知栏按钮。
 *  5. 回到前台时 Activity 必须补一次恢复检查（ViewModel 的 `init` 不会重跑）。
 *
 * 为什么用源码扫描：`PhoneLinkService` 依赖 Android `Service`/`NotificationManager`，
 * 在 JVM 单测里起不来；而这几条都是"某条路径存在/不存在"的性质，扫描比模拟整个
 * 服务生命周期更直接，也不会因为系统版本差异而失去意义。它**不**证明运行时时序正确，
 * 只证明没有任何代码路径把系统行为写成用户行为。
 */
class BackgroundConnectionContractTest {

    private val sourceRoot: File = findSourceRoot()

    private fun read(relative: String): String {
        val file = File(sourceRoot, relative)
        assertTrue("找不到源文件：$relative", file.isFile)
        return file.readText()
    }

    private val service: String get() = read("com/androidmcphub/app/service/PhoneLinkService.kt")

    /** 取出 `fun <name>(...) { ... }` 的函数体（按大括号配对，不依赖缩进）。 */
    private fun bodyOf(source: String, name: String): String {
        val signature = Regex("""fun\s+${Regex.escape(name)}\s*\(""")
        val match = signature.find(source)
        if (match == null) {
            fail("找不到函数 $name")
            error("unreachable")
        }
        val open = source.indexOf('{', match.range.last)
        assertTrue("函数 $name 没有函数体", open >= 0)
        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return source.substring(open, index + 1)
                }
            }
        }
        fail("函数 $name 的大括号不配对")
        error("unreachable")
    }

    @Test
    fun `V162 服务销毁只能走 abandon 语义的 shutdown`() {
        val onDestroy = bodyOf(service, "onDestroy")

        assertTrue(
            "onDestroy 必须通过 shutdown() 收尾（abandon → 不产生 USER_DISCONNECTED）",
            onDestroy.contains("client?.shutdown("),
        )
        assertFalse(
            "onDestroy 不得调用 client.stop()：它会记一条 USER_DISCONNECTED，把系统停止写成用户断开",
            onDestroy.contains("client?.stop("),
        )
        assertFalse(
            "onDestroy 不得自己记录 USER_DISCONNECTED",
            onDestroy.contains("LinkEventKind.USER_DISCONNECTED"),
        )
        assertTrue(
            "没有任何主动停止路径时必须记录 SERVICE_DESTROYED",
            onDestroy.contains("LinkEventKind.SERVICE_DESTROYED"),
        )
    }

    @Test
    fun `V162 启动失败保留连接意图，不能替用户决定断开`() {
        val abort = bodyOf(service, "abortStartup")

        assertFalse(
            "前台服务启动失败不是用户改主意，不得清除 linkDesired",
            abort.contains("setLinkDesired(false)"),
        )
        assertTrue(
            "启动失败必须留下可诊断的停止原因",
            abort.contains("startup_failed:"),
        )
    }

    @Test
    fun `V162 清除连接意图只出现在真实用户断开路径`() {
        val handleDisconnect = bodyOf(service, "handleDisconnect")
        val companionDisconnect = bodyOf(service, "disconnect")

        assertTrue(
            "用户断开服务内路径必须落盘 false",
            handleDisconnect.contains("prefs.setLinkDesired(false)"),
        )
        assertTrue(
            "界面/通知栏断开也必须先落盘 false",
            companionDisconnect.contains("setLinkDesired(false)"),
        )
        // 服务里只有这两处；任何第三处都会让某条非用户路径偷偷改掉用户意图。
        val writes = Regex("""setLinkDesired\(\s*false\s*\)""").findAll(service).count()
        assertEquals("服务里清除连接意图只能有这两处（用户断开的两条入口）", 2, writes)
    }

    @Test
    fun `V162 用户断开先落盘意图再关连接`() {
        val handleDisconnect = bodyOf(service, "handleDisconnect")
        val desiredIndex = handleDisconnect.indexOf("setLinkDesired(false)")
        val stopIndex = handleDisconnect.indexOf("client?.stop(")

        assertTrue("用户断开必须关闭连接", stopIndex > 0)
        assertTrue(
            "必须先落盘「用户不要连接了」，再关 socket —— 顺序反了，进程在两步之间被杀就会偷偷重连",
            desiredIndex in 0 until stopIndex,
        )
    }

    @Test
    fun `V162 断开来源随 Intent 传递，且通知栏按钮带自己的来源`() {
        val onStartCommand = bodyOf(service, "onStartCommand")
        val buildNotification = bodyOf(service, "buildNotification")

        assertTrue(
            "ACTION_DISCONNECT 必须读取来源 extra",
            onStartCommand.contains("EXTRA_DISCONNECT_SOURCE"),
        )
        assertTrue(
            "通知栏断开按钮必须带上 notification_action 来源",
            buildNotification.contains("SOURCE_NOTIFICATION_ACTION") &&
                buildNotification.contains("EXTRA_DISCONNECT_SOURCE"),
        )
        assertTrue(
            "界面断开默认来源必须是 ui_button",
            service.contains("fun disconnect(context: Context, source: String = SOURCE_UI_BUTTON)"),
        )
    }

    @Test
    fun `V162 网络回调必须过滤迟到的旧网络事件`() {
        val register = bodyOf(service, "registerNetworkCallback")

        assertTrue(
            "onLost 必须经过 NetworkCallbackPolicy.shouldProcessLost 过滤",
            register.contains("NetworkCallbackPolicy.shouldProcessLost"),
        )
        assertTrue(
            "onAvailable 必须抑制同一网络的重复上报",
            register.contains("NetworkCallbackPolicy.shouldProcessAvailable"),
        )
    }

    @Test
    fun `V162 回到前台时必须补一次恢复检查`() {
        val activity = read("com/androidmcphub/app/MainActivity.kt")
        val activityOnStart = bodyOf(activity, "onStart")
        assertTrue(
            "MainActivity.onStart 必须调用 onAppForegrounded（ViewModel init 不会重跑）",
            activityOnStart.contains("viewModel.onAppForegrounded()"),
        )
        assertFalse(
            "回到前台只恢复，不得顺手断开",
            activityOnStart.contains("disconnect"),
        )

        val viewModel = read("com/androidmcphub/app/ui/HubViewModel.kt")
        val foregrounded = bodyOf(viewModel, "onAppForegrounded")
        assertTrue(
            "onAppForegrounded 必须重新读一次落盘意图并交给恢复策略",
            foregrounded.contains("publishLinkDesired") && foregrounded.contains("restoreLinkIfDesired()"),
        )
        assertFalse(
            "onAppForegrounded 不得自己发起断开",
            foregrounded.contains("disconnect"),
        )
    }

    /** 从 `user.dir` 往上找 `src/main/java`（与 [ConnectionLifecycleOwnershipTest] 相同）。 */
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
