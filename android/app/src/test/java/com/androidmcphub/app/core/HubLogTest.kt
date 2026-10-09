package com.androidmcphub.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 环形日志的容量与顺序。 */
class HubLogTest {

    @Test
    fun `超出容量后只保留最新的若干条`() {
        var now = 0L
        val log = HubLog(capacity = 3, clock = { now++ })

        log.info("一")
        log.info("二")
        log.info("三")
        log.info("四")

        val entries = log.entries()
        assertEquals(3, entries.size)
        // 最新的在最前面
        assertEquals("四", entries[0].text)
        assertEquals("三", entries[1].text)
        assertEquals("二", entries[2].text)
    }

    @Test
    fun `监听器每次写入都收到新快照`() {
        val log = HubLog(capacity = 2)
        val snapshots = mutableListOf<List<LogEntry>>()
        log.setListener { snapshots += it }

        log.warn("第一次")
        log.error("第二次")

        assertEquals(2, snapshots.size)
        assertEquals(1, snapshots[0].size)
        assertEquals(2, snapshots[1].size)
        assertEquals(LogLevel.ERROR, snapshots[1][0].level)
    }

    @Test
    fun `清空后监听器收到空快照`() {
        val log = HubLog(capacity = 4)
        log.info("有内容")
        var last: List<LogEntry>? = null
        log.setListener { last = it }

        log.clear()

        assertTrue(log.entries().isEmpty())
        assertEquals(emptyList<LogEntry>(), last)
    }

    @Test
    fun `默认容量是 200`() {
        assertEquals(200, HubLog.DEFAULT_CAPACITY)
        val log = HubLog()
        repeat(250) { log.info("第 $it 条") }
        assertEquals(200, log.entries().size)
        assertEquals("第 249 条", log.entries()[0].text)
    }
}
