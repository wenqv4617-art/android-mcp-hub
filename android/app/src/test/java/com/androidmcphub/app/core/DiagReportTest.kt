package com.androidmcphub.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 客户端诊断上报的"唯一构造入口"测试。
 *
 * 关键约束不是"字段齐全"，而是**闭集**：上层传进来的任何自由文本、越界数字，
 * 都不能变成网络帧里的一段内容。
 */
class DiagReportTest {

    @Test
    fun `每个本地事件都有闭集事件名且不重复`() {
        val names = LinkEventKind.entries.map { diagEventName(it) }

        assertEquals("映射必须一对一", LinkEventKind.entries.size, names.toSet().size)
        names.forEach { assertTrue("事件名 $it 不在闭集里", it in DiagClientEvent.ALL) }
    }

    @Test
    fun `连接失败类别只保留闭集值`() {
        val reset = buildDiagReport(
            LinkEvent(kind = LinkEventKind.SOCKET_FAILURE, atMs = 1L, category = "connection_reset"),
            DeviceSnapshot(),
        )
        assertEquals("connection_reset", reset.category)

        val forged = buildDiagReport(
            LinkEvent(
                kind = LinkEventKind.SOCKET_FAILURE,
                atMs = 1L,
                category = "socket reset at wss://host/phone/ws?token=leak",
            ),
            DeviceSnapshot(),
        )
        assertEquals(SocketFailure.UNKNOWN, forged.category)
        assertFalse(forged.toString().contains("leak"))
    }

    @Test
    fun `只有连接失败事件可以携带失败类别`() {
        val report = buildDiagReport(
            LinkEvent(kind = LinkEventKind.SERVER_CLOSED, atMs = 1L, category = "dns_failure"),
            DeviceSnapshot(),
        )

        assertNull("服务端关闭不是网络失败，类别必须留空", report.category)
    }

    @Test
    fun `越界的关闭码与代次在构造时丢弃`() {
        val report = buildDiagReport(
            LinkEvent(
                kind = LinkEventKind.SERVER_CLOSED,
                atMs = 1L,
                closeCode = 9999,
                generation = -3L,
                heartbeatAgeMs = -1L,
                attempt = 99_999,
            ),
            DeviceSnapshot(),
        )

        assertNull(report.closeCode)
        assertNull(report.generation)
        assertNull(report.heartbeatAgeMs)
        assertNull(report.attempt)
    }

    @Test
    fun `网络与屏幕状态按闭集收敛`() {
        val report = buildDiagReport(
            LinkEvent(kind = LinkEventKind.CONNECTED, atMs = 1L),
            DeviceSnapshot(network = "wifi\" OR 1=1", vpn = true, screen = "<script>"),
        )

        assertNull("未知网络不进上报帧", report.network)
        assertEquals(DiagScreen.UNKNOWN, report.screen)
        assertEquals(true, report.vpn)
    }

    @Test
    fun `异常类名只保留标识符形状且只在网络失败事件上`() {
        val known = buildDiagReport(
            LinkEvent(
                kind = LinkEventKind.SOCKET_FAILURE,
                atMs = 1L,
                category = "connection_reset",
                exceptionClass = "java.net.SocketException\$Reset",
            ),
            DeviceSnapshot(),
        )
        assertEquals("java.net.SocketException\$Reset", known.exceptionClass)

        val auth = buildDiagReport(
            LinkEvent(
                kind = LinkEventKind.AUTHENTICATION_FAILED,
                atMs = 1L,
                category = "protocol_failure",
                exceptionClass = "ProtocolException",
            ),
            DeviceSnapshot(),
        )
        assertEquals("由 onFailure 升级的认证失败也带类名", "ProtocolException", auth.exceptionClass)

        val wrongKind = buildDiagReport(
            LinkEvent(
                kind = LinkEventKind.SERVER_CLOSED,
                atMs = 1L,
                exceptionClass = "ProtocolException",
            ),
            DeviceSnapshot(),
        )
        assertNull("服务端关闭不是异常", wrongKind.exceptionClass)

        val forged = buildDiagReport(
            LinkEvent(
                kind = LinkEventKind.SOCKET_FAILURE,
                atMs = 1L,
                exceptionClass = "java.io.IOException: token=leak",
            ),
            DeviceSnapshot(),
        )
        assertNull("异常 message 形状的内容不能被当成类名", forged.exceptionClass)
        assertFalse(forged.toString().contains("leak"))
    }

    @Test
    fun `本地关闭来源按闭集收敛`() {
        val known = buildDiagReport(
            LinkEvent(kind = LinkEventKind.USER_DISCONNECTED, atMs = 1L, source = "ui_button"),
            DeviceSnapshot(),
        )
        val forged = buildDiagReport(
            LinkEvent(kind = LinkEventKind.USER_DISCONNECTED, atMs = 1L, source = "Bearer xyz"),
            DeviceSnapshot(),
        )

        assertEquals(DiagCloseSource.UI_BUTTON, known.localCloseSource)
        assertEquals(DiagCloseSource.UNKNOWN, forged.localCloseSource)
        assertFalse(forged.toString().contains("Bearer"))
    }

    @Test
    fun `每一条事件都带上当时的设备快照`() {
        val report = buildDiagReport(
            LinkEvent(kind = LinkEventKind.SOCKET_FAILURE, atMs = 1L, category = "connection_reset"),
            DeviceSnapshot(
                network = DiagNetwork.WIFI,
                vpn = false,
                screen = DiagScreen.OFF,
                pid = 4321,
                processUptimeMs = 90_000L,
                androidSdk = 35,
            ),
        )

        assertEquals(DiagNetwork.WIFI, report.network)
        assertEquals(false, report.vpn)
        assertEquals(DiagScreen.OFF, report.screen)
        assertEquals(4321, report.pid)
        assertEquals(90_000L, report.processUptimeMs)
        assertEquals(35, report.androidSdk)
        assertEquals("客户端快照必须带版本号", report.appVersion, com.androidmcphub.app.protocol.APP_VERSION)
    }
}
