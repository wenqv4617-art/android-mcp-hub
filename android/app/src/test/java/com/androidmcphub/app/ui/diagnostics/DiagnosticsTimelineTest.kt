package com.androidmcphub.app.ui.diagnostics

import com.androidmcphub.app.core.DiagClientEvent
import com.androidmcphub.app.core.LinkEvent
import com.androidmcphub.app.core.LinkEventKind
import com.androidmcphub.app.net.CloudDiagBuffer
import com.androidmcphub.app.net.CloudDiagDetail
import com.androidmcphub.app.net.CloudDiagEvent
import com.androidmcphub.app.net.CloudDiagSummary
import com.androidmcphub.app.net.CloudDiagnostics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 合并时间线：排序只能用校正后的时间轴，相关性只能标「可能」。
 */
class DiagnosticsTimelineTest {

    private fun cloud(
        events: List<CloudDiagEvent> = emptyList(),
        serverTime: Long = 100_000L,
        fetchedAt: Long = 110_000L,
    ) = CloudDiagnostics(
        deviceId = "device-secret-id",
        serverTimeMs = serverTime,
        fetchedAtMs = fetchedAt,
        online = false,
        socketOpen = false,
        stale = false,
        connectionGeneration = 7L,
        lastPhoneActivityAtMs = null,
        events = events,
        summary = CloudDiagSummary(
            lastOnlineAtMs = null,
            lastOfflineAtMs = null,
            lastOfflineReason = null,
            lastOfflineCloseCode = null,
            lastServerInitiatedCloseAtMs = null,
            replacedEvents = 0,
            revocations = 0,
            livenessTimeouts = 0,
            authFailures = 0,
            clientReports = 0,
        ),
        buffer = CloudDiagBuffer(
            size = events.size,
            returned = events.size,
            dropped = 0,
            firstAtMs = null,
            lastAtMs = null,
            maxEvents = 200,
            maxAgeMs = 86_400_000L,
        ),
        closeCodes = mapOf(4001 to "replaced_by_newer_connection"),
    )

    private fun cloudEvent(
        seq: Long,
        at: Long,
        type: String,
        generation: Long? = null,
        clientAt: Long? = null,
        detail: Map<String, String> = emptyMap(),
    ) = CloudDiagEvent(
        seq = seq,
        atMs = at,
        clientAtMs = clientAt,
        source = "durable_object",
        type = type,
        generation = generation,
        closeCode = if (type == "replaced") 4001 else null,
        closeReason = if (type == "replaced") "replaced_by_newer_connection" else null,
        serverInitiated = type == "replaced",
        durationMs = 30_000L,
        detail = CloudDiagDetail(detail),
    )

    @Test
    fun `排序按校正后的时间轴而不是原始服务端时间`() {
        // 手机时钟比服务端快 10 秒：服务端 90000 校正后是 80000。
        val local = listOf(
            LinkEvent(kind = LinkEventKind.SERVER_CLOSED, atMs = 85_000L, closeCode = 4001),
        )
        val cloud = cloud(events = listOf(cloudEvent(seq = 1, at = 90_000L, type = "replaced")))

        val entries = DiagnosticsTimeline.build(local, cloud, DiagTimelineSource.MERGED)

        assertEquals(2, entries.size)
        assertEquals(
            "校正后本机 85000 > 云端 80000，本机事件应排在前面；" +
                "若按原始时间比较会得到相反顺序",
            TimelineOrigin.LOCAL,
            entries.first().origin,
        )
        assertEquals(80_000L, entries.first { it.origin == TimelineOrigin.CLOUD }.axisAtMs)
    }

    @Test
    fun `云端事件同时保留服务端原始时间与手机上报时间`() {
        val cloud = cloud(
            events = listOf(
                cloudEvent(seq = 3, at = 90_000L, type = "client_report", clientAt = 95_000L),
            ),
        )

        val entry = DiagnosticsTimeline.build(emptyList(), cloud, DiagTimelineSource.CLOUD).single()

        assertEquals(90_000L, entry.serverAtMs)
        assertEquals(80_000L, entry.axisAtMs)
        assertEquals(95_000L, entry.reportedAtMs)
        assertEquals("client_report", entry.eventType)
    }

    @Test
    fun `client_report 按内部 client_event 归类与筛选`() {
        val cloud = cloud(
            events = listOf(
                cloudEvent(
                    seq = 1,
                    at = 90_000L,
                    type = "client_report",
                    detail = mapOf("client_event" to DiagClientEvent.SOCKET_FAILURE),
                ),
                cloudEvent(seq = 2, at = 91_000L, type = "device_offline"),
            ),
        )

        val all = DiagnosticsTimeline.build(emptyList(), cloud, DiagTimelineSource.CLOUD)
        val filtered = DiagnosticsTimeline.build(
            emptyList(),
            cloud,
            DiagTimelineSource.CLOUD,
            typeFilter = DiagClientEvent.SOCKET_FAILURE,
        )

        assertEquals(
            listOf("device_offline", DiagClientEvent.SOCKET_FAILURE),
            all.map { it.eventType },
        )
        assertEquals(1, filtered.size)
        assertEquals(
            "连接失败（手机上报）",
            all.first { it.eventType == DiagClientEvent.SOCKET_FAILURE }.title,
        )
    }

    @Test
    fun `两侧事件时间接近时标可能相关`() {
        val local = listOf(
            LinkEvent(
                kind = LinkEventKind.SOCKET_FAILURE,
                atMs = 85_000L,
                category = "connection_reset",
                generation = 7L,
            ),
        )
        val cloud = cloud(
            events = listOf(cloudEvent(seq = 1, at = 90_000L, type = "device_offline", generation = 7L)),
        )

        val entries = DiagnosticsTimeline.build(local, cloud, DiagTimelineSource.MERGED)

        assertTrue("两侧时间相差 5 秒且代次一致", entries.all { it.possibleRelated })
    }

    @Test
    fun `代次不一致时不标记相关`() {
        val local = listOf(
            LinkEvent(
                kind = LinkEventKind.SOCKET_FAILURE,
                atMs = 85_000L,
                category = "connection_reset",
                generation = 7L,
            ),
        )
        val cloud = cloud(
            events = listOf(
                cloudEvent(seq = 1, at = 90_000L, type = "device_offline", generation = 6L),
            ),
        )

        val entries = DiagnosticsTimeline.build(local, cloud, DiagTimelineSource.MERGED)

        assertFalse(entries.any { it.possibleRelated })
    }

    @Test
    fun `超出时间窗口时不标记相关`() {
        val local = listOf(
            LinkEvent(kind = LinkEventKind.SOCKET_FAILURE, atMs = 40_000L, generation = 7L),
        )
        val cloud = cloud(
            events = listOf(cloudEvent(seq = 1, at = 90_000L, type = "device_offline", generation = 7L)),
        )

        val entries = DiagnosticsTimeline.build(local, cloud, DiagTimelineSource.MERGED)

        assertFalse(entries.any { it.possibleRelated })
    }

    @Test
    fun `来源切换只保留对应侧的事件`() {
        val local = listOf(LinkEvent(kind = LinkEventKind.CONNECTED, atMs = 85_000L))
        val cloud = cloud(events = listOf(cloudEvent(seq = 1, at = 90_000L, type = "device_online")))

        assertEquals(
            listOf(TimelineOrigin.LOCAL),
            DiagnosticsTimeline.build(local, cloud, DiagTimelineSource.LOCAL).map { it.origin }.distinct(),
        )
        assertEquals(
            listOf(TimelineOrigin.CLOUD),
            DiagnosticsTimeline.build(local, cloud, DiagTimelineSource.CLOUD).map { it.origin }.distinct(),
        )
        assertEquals(
            2,
            DiagnosticsTimeline.build(local, cloud, DiagTimelineSource.MERGED).size,
        )
    }

    @Test
    fun `云端未查询时合并视图仍可展示本机事件`() {
        val local = listOf(LinkEvent(kind = LinkEventKind.CONNECTED, atMs = 1L))

        val entries = DiagnosticsTimeline.build(local, null, DiagTimelineSource.MERGED)

        assertEquals(1, entries.size)
        assertEquals(TimelineOrigin.LOCAL, entries.single().origin)
    }

    @Test
    fun `关闭码语义来自云端但本机事件也能用`() {
        val local = listOf(
            LinkEvent(kind = LinkEventKind.SERVER_CLOSED, atMs = 85_000L, closeCode = 4001),
        )
        val cloud = cloud(events = listOf(cloudEvent(seq = 1, at = 90_000L, type = "replaced")))

        val entry = DiagnosticsTimeline.build(local, cloud, DiagTimelineSource.LOCAL).single()

        assertTrue(entry.detailLines.any { it.contains("replaced_by_newer_connection") })
    }

    @Test
    fun `本机连接失败展示异常类型`() {
        val local = listOf(
            LinkEvent(
                kind = LinkEventKind.SOCKET_FAILURE,
                atMs = 85_000L,
                category = "connection_reset",
                exceptionClass = "SocketException",
            ),
        )

        val entry = DiagnosticsTimeline.build(local, null, DiagTimelineSource.LOCAL).single()

        assertTrue(entry.detailLines.any { it.contains("异常类型 SocketException") })
    }

    @Test
    fun `导出包含双端时间与可能相关标记且不泄露敏感字段`() {
        val local = listOf(
            LinkEvent(
                kind = LinkEventKind.SOCKET_FAILURE,
                atMs = 85_000L,
                detail = "连接失败 category=connection_reset",
                category = "connection_reset",
                generation = 7L,
            ),
        )
        val cloud = cloud(
            events = listOf(
                cloudEvent(
                    seq = 1,
                    at = 90_000L,
                    type = "device_offline",
                    generation = 7L,
                    detail = mapOf("cause" to "silent_timeout", "network" to "wifi"),
                ),
            ),
        )
        val entries = DiagnosticsTimeline.build(local, cloud, DiagTimelineSource.MERGED)

        val text = DiagnosticsTimeline.buildExport(
            entries = entries,
            cloud = cloud,
            source = DiagTimelineSource.MERGED,
            generatedAtMs = 200_000L,
        )

        assertTrue(text.contains("[可能相关]"))
        assertTrue(text.contains("服务端时间"))
        assertTrue(text.contains("时钟差"))
        assertTrue(text.contains("云端缓冲区"))
        listOf("device-secret-id", "Bearer", "token", "Authorization", "wss://").forEach {
            assertFalse("导出不应包含 $it", text.contains(it, ignoreCase = true))
        }
    }

    @Test
    fun `导出有上限并明确说明截断`() {
        val local = (1..5).map { LinkEvent(kind = LinkEventKind.CONNECTED, atMs = it.toLong()) }

        val text = DiagnosticsTimeline.buildExport(
            entries = DiagnosticsTimeline.build(local, null, DiagTimelineSource.LOCAL),
            cloud = null,
            source = DiagTimelineSource.LOCAL,
            generatedAtMs = 200_000L,
            maxEntries = 2,
        )

        assertTrue(text.contains("其余 3 条未导出"))
    }
}
