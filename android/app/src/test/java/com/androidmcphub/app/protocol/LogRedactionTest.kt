package com.androidmcphub.app.protocol

import com.androidmcphub.app.core.HubConfig
import com.androidmcphub.app.core.SocketFailure
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * 日志脱敏（Gate 0.3 审计发现项 1）。
 *
 * 守的三条性质：
 *
 *  1. **完整 URL 不进日志** —— 只记主机名。用户从别处复制来的地址可能带
 *     `?token=…`，那是设备令牌最容易泄漏的位置。
 *  2. **未经处理的异常消息不进日志** —— 只记 `SocketFailure` 闭集里的类别。
 *  3. **对端自由文本不进日志** —— 服务端错误帧的 `message`、未知帧的 `type`
 *     都不记原文。
 *
 * 前 9 条是行为测试（真的把带令牌的地址喂进去看日志），后 4 条是静态扫描
 * （防止有人以后**另开一条**不脱敏的写法）。两者缺一不可：行为测试只能覆盖
 * 它跑到的那条路径，静态扫描覆盖"以后新加的代码"。
 */
class LogRedactionTest {

    /** 一个明显是假令牌、但形状就像凭据的字符串。 */
    private val fakeToken = "gate0-not-a-real-token-9f3c1b7a5e2d4086"

    private inner class Harness(url: String = "wss://example.test/phone/ws") {
        var now: Long = 1_760_000_000_000L
        val logs = mutableListOf<String>()

        val config = HubConfig(
            serverUrl = url,
            deviceId = "test-phone-1",
            pingTimeoutMs = 10_000L,
            heartbeatIntervalMs = 30_000L,
            reconnectInitialDelayMs = 1_000L,
            reconnectMaxDelayMs = 5_000L,
            reconnectJitterRatio = 0.0,
        )

        val link = PhoneLink(
            config = config,
            random = Random(20250301),
            clock = { now },
            log = { _, text -> logs += text },
        )

        fun all(): String = logs.joinToString("\n")

        fun assertAbsent(needle: String) {
            assertTrue(
                "日志里出现了不该出现的内容「$needle」：\n${all()}",
                !all().contains(needle),
            )
        }
    }

    private fun errorFrame(code: String, message: String): String = JSONObject()
        .put("type", "error")
        .put("code", code)
        .put("message", message)
        .toString()

    // ---- 1. 完整 URL ------------------------------------------------------

    @Test
    fun `带令牌的地址：日志里只有主机名，路径与查询串都不出现`() {
        val h = Harness("wss://example.test/phone/ws?token=$fakeToken&device=test-phone-1")

        h.link.start()

        assertTrue("主机名还是要有，否则排障没法用：${h.all()}", h.all().contains("example.test"))
        h.assertAbsent(fakeToken)
        h.assertAbsent("token=")
        h.assertAbsent("/phone/ws")
        h.assertAbsent("device=test-phone-1")
        h.assertAbsent("?")
    }

    @Test
    fun `令牌的任意长片段都不在日志里`() {
        val h = Harness("wss://example.test/phone/ws?token=$fakeToken")

        h.link.start()
        h.link.onSocketOpened(androidSdk = 35)
        h.link.onTextFrame(JSONObject().put("type", "welcome").put("device_id", "test-phone-1")
            .put("connection_generation", 1).put("server_time", h.now)
            .put("heartbeat_interval_ms", 30_000).put("ping_timeout_ms", 10_000)
            .put("max_pending_requests", 16).put("protocol_version", PROTOCOL_VERSION).toString())

        // 令牌长度 8 以上的每一个片段都不许出现 —— 防止"只截掉一部分"式的半吊子脱敏。
        for (start in 0..(fakeToken.length - 8)) {
            h.assertAbsent(fakeToken.substring(start, start + 8))
        }
    }

    // ---- 2. 异常原文 ------------------------------------------------------

    @Test
    fun `onSocketFailure 拿到原文也只会留下闭集里的类别`() {
        val h = Harness("wss://example.test/phone/ws?token=$fakeToken")
        h.link.start()

        // 故意塞一坨"看起来像真实异常消息"的东西 —— 调用方（或未来的重构）塞错了，
        // 脱敏也必须成立。
        val raw = "UnknownHostException: 无法解析 wss://example.test/phone/ws?token=$fakeToken"
        h.link.onSocketFailure(raw, null)

        h.assertAbsent(fakeToken)
        h.assertAbsent("UnknownHostException")
        h.assertAbsent("无法解析")
        assertTrue(
            "应该留下归一化后的类别：${h.all()}",
            h.all().contains("category=${SocketFailure.UNKNOWN}"),
        )
    }

    @Test
    fun `正常分类路径：日志里是固定类别与固定描述，没有异常原文`() {
        val h = Harness()
        h.link.start()

        h.link.onSocketFailure(SocketFailure.classify("UnknownHostException"), null)

        assertTrue(h.all().contains("category=${SocketFailure.DNS}"))
        assertTrue(h.all().contains(SocketFailure.describe(SocketFailure.DNS)))
        h.assertAbsent("UnknownHostException")
    }

    @Test
    fun `认证失败：日志里有状态码与类别，没有异常原文`() {
        val h = Harness()
        h.link.start()

        h.link.onSocketFailure(SocketFailure.classify("SSLHandshakeException"), 401)

        assertTrue(h.all().contains("HTTP 401"))
        assertTrue(h.all().contains("category=${SocketFailure.TLS}"))
        h.assertAbsent("SSLHandshakeException")
        h.assertAbsent("trust anchor")
    }

    // ---- 3. 对端自由文本 --------------------------------------------------

    @Test
    fun `服务端错误帧的 message 一个字都不记`() {
        val h = Harness()
        h.link.start()

        h.link.onTextFrame(errorFrame("malformed_message", "Bearer $fakeToken 你的令牌是 $fakeToken"))

        assertTrue(h.all().contains("code=malformed_message"))
        h.assertAbsent(fakeToken)
        h.assertAbsent("Bearer")
        h.assertAbsent("你的令牌是")
    }

    @Test
    fun `认不出来的错误 code 收敛成 unrecognized_error_code`() {
        val h = Harness()
        h.link.start()

        h.link.onTextFrame(errorFrame("$fakeToken", "随便什么"))

        assertTrue(h.all().contains("code=${ServerErrorCode.UNRECOGNIZED}"))
        h.assertAbsent(fakeToken)
    }

    @Test
    fun `未知帧类型只记长度，不记内容`() {
        val h = Harness()
        h.link.start()

        val suspiciousType = fakeToken
        h.link.onTextFrame(JSONObject().put("type", suspiciousType).toString())

        h.assertAbsent(fakeToken)
        assertTrue(
            "应该记下长度（$suspiciousType 长 ${suspiciousType.length}）：${h.all()}",
            h.all().contains("typeLen=${suspiciousType.length}"),
        )
        assertTrue(h.all().contains("reason=${UnknownReason.UNSUPPORTED_TYPE}"))
    }

    @Test
    fun `非法 JSON 也不记原文`() {
        val h = Harness()
        h.link.start()

        h.link.onTextFrame("{this is not json, token=$fakeToken")

        h.assertAbsent(fakeToken)
        h.assertAbsent("this is not json")
        assertTrue(h.all().contains("reason=${UnknownReason.INVALID_JSON}"))
    }

    // ---- 4. 静态扫描：防止以后另开一条不脱敏的写法 ------------------------

    private fun mainSourceFiles(): List<File> {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val nested = File(dir, "app/src/main/java")
            if (nested.isDirectory) return nested.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
            val direct = File(dir, "src/main/java")
            if (direct.isDirectory) return direct.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
            dir = dir.parentFile
        }
        error("找不到 app/src/main/java，当前目录=${File(".").absolutePath}")
    }

    @Test
    fun `静态扫描：主源码里不存在 printStackTrace 或 stackTraceToString`() {
        val hits = mainSourceFiles().filter { file ->
            val text = file.readText()
            text.contains("printStackTrace") || text.contains("stackTraceToString")
        }
        assertEquals("这些文件里有直接打印堆栈的写法（堆栈里带着 URL 与参数）：$hits", emptyList<File>(), hits)
    }

    @Test
    fun `静态扫描：没有任何地方把异常的 message 拼进字符串`() {
        // 变量名一律用常见的异常变量名；`LinkState.Error.message` 不在这个形状里。
        val pattern = Regex("""\b(t|e|it|err|error|ex|exception|throwable|cause)\.message\b""")
        val hits = mutableListOf<String>()
        for (file in mainSourceFiles()) {
            file.readLines().forEachIndexed { index, line ->
                if (pattern.containsMatchIn(line) && !line.trimStart().startsWith("//") && !line.trimStart().startsWith("*")) {
                    hits += "${file.name}:${index + 1}: ${line.trim()}"
                }
            }
        }
        assertEquals("这些行把异常消息拼进了字符串：\n${hits.joinToString("\n")}", emptyList<String>(), hits)
    }

    @Test
    fun `静态扫描：日志调用里不出现完整地址、令牌字段或对端自由文本的插值`() {
        val forbidden = listOf(
            "\${config.serverUrl}",
            "\${url}",
            "\$url",
            "\${frame.message}",
            "\${frame.type}",
            "\${frame.code}",
            "\${raw}",
            "\$raw",
        )
        val hits = mutableListOf<String>()
        for (file in mainSourceFiles()) {
            val text = file.readText()
            // 一次日志调用可能跨多行，所以取"log(LogLevel 之后 400 字符"的窗口来看。
            var index = text.indexOf("log(LogLevel")
            while (index >= 0) {
                val window = text.substring(index, minOf(index + 400, text.length))
                for (needle in forbidden) {
                    if (window.contains(needle)) {
                        hits += "${file.name}: 日志调用窗口里出现 $needle"
                    }
                }
                index = text.indexOf("log(LogLevel", index + 1)
            }
            var linkBusIndex = text.indexOf("LinkBus.log.")
            while (linkBusIndex >= 0) {
                val window = text.substring(linkBusIndex, minOf(linkBusIndex + 400, text.length))
                for (needle in forbidden) {
                    if (window.contains(needle)) {
                        hits += "${file.name}: LinkBus.log 窗口里出现 $needle"
                    }
                }
                linkBusIndex = text.indexOf("LinkBus.log.", linkBusIndex + 1)
            }
        }
        assertEquals("这些日志调用把敏感文本插了进去：\n${hits.joinToString("\n")}", emptyList<String>(), hits)
    }

    @Test
    fun `静态扫描：所有 SocketFailure_classify 调用都只传异常类名`() {
        val hits = mutableListOf<String>()
        for (file in mainSourceFiles()) {
            file.readLines().forEachIndexed { index, line ->
                if (!line.contains("SocketFailure.classify(")) return@forEachIndexed
                if (line.trimStart().startsWith("//") || line.trimStart().startsWith("*")) return@forEachIndexed
                if (!line.contains("javaClass.simpleName")) {
                    hits += "${file.name}:${index + 1}: ${line.trim()}"
                }
            }
        }
        assertEquals(
            "classify 的入参必须是异常类名（签名上就拿不到 message）：\n${hits.joinToString("\n")}",
            emptyList<String>(),
            hits,
        )
    }
}
