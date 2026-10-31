package com.androidmcphub.app.protocol

import com.androidmcphub.app.core.DiagClientEvent
import com.androidmcphub.app.core.DiagReport
import com.androidmcphub.app.core.DeviceSnapshot
import com.androidmcphub.app.core.LinkEvent
import com.androidmcphub.app.core.LinkEventKind
import com.androidmcphub.app.core.buildDiagReport
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** `diag_event` 帧的形状：闭集事件名、可选字段、绝不夹带凭据。 */
class DiagEventEncodingTest {

    private fun encode(report: DiagReport): JSONObject = JSONObject(encodeDiagEvent(report))

    @Test
    fun `结构化事件编码成 diag_event 帧`() {
        val report = buildDiagReport(
            LinkEvent(
                kind = LinkEventKind.SERVER_CLOSED,
                atMs = 1_700_000_000_000L,
                detail = "服务端关闭 code=4001",
                generation = 52L,
                closeCode = 4001,
            ),
            DeviceSnapshot(
                network = "wifi",
                vpn = false,
                screen = "on",
                pid = 1234,
                processUptimeMs = 60_000L,
                androidSdk = 35,
            ),
        )

        val obj = encode(report)

        assertEquals("diag_event", obj.getString("type"))
        assertEquals("server_close", obj.getString("event"))
        assertEquals(1_700_000_000_000L, obj.getLong("client_at"))
        assertEquals(4001, obj.getInt("close_code"))
        assertEquals(52L, obj.getLong("generation"))
        assertEquals("wifi", obj.getString("network"))
        assertEquals(false, obj.getBoolean("vpn"))
        assertEquals("on", obj.getString("screen"))
        assertEquals(1234, obj.getInt("pid"))
        assertEquals(60_000L, obj.getLong("process_uptime_ms"))
        assertEquals(APP_VERSION, obj.getString("app_version"))
        assertEquals(35, obj.getInt("android_sdk"))
        assertTrue("自由文本 detail 不得进入上报帧", !obj.has("detail"))
        assertTrue(!obj.has("reason"))
    }

    @Test
    fun `连接失败帧带上异常类名而不带异常消息`() {
        val report = buildDiagReport(
            LinkEvent(
                kind = LinkEventKind.SOCKET_FAILURE,
                atMs = 1_700_000_000_000L,
                detail = "连接失败 category=connection_reset",
                category = "connection_reset",
                exceptionClass = "SocketException",
            ),
            DeviceSnapshot(),
        )

        val obj = encode(report)

        assertEquals("socket_failure", obj.getString("event"))
        assertEquals("SocketException", obj.getString("exception_class"))
        assertTrue("异常消息不是字段", !obj.has("exception_message"))
        assertTrue(!obj.has("message"))
    }

    @Test
    fun `空的可选字段不会以 null 或空字符串发出去`() {
        val obj = encode(DiagReport(event = DiagClientEvent.CONNECTED, clientAtMs = 1L))

        assertEquals("connected", obj.getString("event"))
        listOf(
            "category", "close_code", "generation", "network", "vpn", "screen",
            "pid", "process_uptime_ms", "android_sdk", "local_close_source",
            "heartbeat_age_ms", "exception_class", "attempt",
        ).forEach { key ->
            // 注意：org.json 的 isNull() 对“不存在的键”也会返回 true（NULL.equals(null)），
            // 所以这里只能用 has() 判断。
            assertFalse("$key 不应出现", obj.has(key))
        }
    }

    @Test
    fun `闭集之外的事件名发成 unknown 而不是顶替成真实事件`() {
        val obj = encode(DiagReport(event = "made_up_event", clientAtMs = 1L))

        assertEquals("unknown", obj.getString("event"))
        assertFalse(
            "不能把程序 bug 伪装成某个真实事件",
            obj.getString("event") in DiagClientEvent.ALL,
        )
    }

    @Test
    fun `上报帧里没有承载凭据的字段`() {
        val obj = encode(
            DiagReport(
                event = DiagClientEvent.SOCKET_FAILURE,
                clientAtMs = 1L,
                category = "connection_reset",
                network = "cellular",
            ),
        )

        val text = obj.toString()
        listOf("authorization", "bearer", "token", "cookie", "server_url", "wss://").forEach {
            assertFalse("帧里不应出现 $it", text.contains(it, ignoreCase = true))
        }
    }
}
