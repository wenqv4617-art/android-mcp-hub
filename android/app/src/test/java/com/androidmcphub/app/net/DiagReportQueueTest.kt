package com.androidmcphub.app.net

import com.androidmcphub.app.core.DiagClientEvent
import com.androidmcphub.app.core.DiagReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 诊断上报队列：有界、会过期、发不出去时保留。 */
class DiagReportQueueTest {

    private fun report(at: Long, event: String = DiagClientEvent.CONNECTED) =
        DiagReport(event = event, clientAtMs = at)

    @Test
    fun `超过上限时丢弃最老的并计数`() {
        val queue = DiagReportQueue(limit = 3, maxAgeMs = 60_000L) { 0L }

        (1L..5L).forEach { queue.enqueue(report(at = it)) }

        assertEquals(3, queue.size)
        assertEquals(2, queue.droppedCount)
        val sent = mutableListOf<Long>()
        queue.drain { sent += it.clientAtMs; true }
        assertEquals("保留的必须是最近的 3、4、5", listOf(3L, 4L, 5L), sent)
    }

    @Test
    fun `按入队顺序发送`() {
        val queue = DiagReportQueue(limit = 10, maxAgeMs = 60_000L) { 10L }
        queue.enqueue(report(at = 1L))
        queue.enqueue(report(at = 2L))

        val sent = mutableListOf<Long>()
        val expired = queue.drain { sent += it.clientAtMs; true }

        assertEquals(listOf(1L, 2L), sent)
        assertEquals(0, expired)
        assertEquals(0, queue.size)
    }

    @Test
    fun `过期事件不补发且计入丢弃`() {
        val queue = DiagReportQueue(limit = 10, maxAgeMs = 15 * 60 * 1000L) { 1_000_000L }
        queue.enqueue(report(at = 1_000_000L - 15 * 60 * 1000L - 1))
        queue.enqueue(report(at = 999_000L))

        val sent = mutableListOf<Long>()
        val expired = queue.drain { sent += it.clientAtMs; true }

        assertEquals(1, expired)
        assertEquals(1, queue.droppedCount)
        assertEquals("只发送未过期的那条", listOf(999_000L), sent)
    }

    @Test
    fun `发送失败时该条放回队首并停止本轮`() {
        val queue = DiagReportQueue(limit = 10, maxAgeMs = 60_000L) { 10L }
        queue.enqueue(report(at = 1L))
        queue.enqueue(report(at = 2L))

        val firstAttempt = mutableListOf<Long>()
        queue.drain { firstAttempt += it.clientAtMs; false }

        assertEquals("只尝试了队首", listOf(1L), firstAttempt)
        assertEquals("两条都还在，顺序不变", 2, queue.size)

        val second = mutableListOf<Long>()
        queue.drain { second += it.clientAtMs; true }
        assertEquals(listOf(1L, 2L), second)
    }

    @Test
    fun `清空队列同时复位丢弃计数`() {
        val queue = DiagReportQueue(limit = 1, maxAgeMs = 60_000L) { 10L }
        queue.enqueue(report(at = 1L))
        queue.enqueue(report(at = 2L))
        assertTrue(queue.droppedCount > 0)

        queue.clear()

        assertEquals(0, queue.size)
        assertEquals(0, queue.droppedCount)
    }

    @Test
    fun `并发入队不会超过上限`() {
        val queue = DiagReportQueue(limit = 20, maxAgeMs = 60_000L) { 10L }
        val threads = 8
        val perThread = 100
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)

        repeat(threads) { t ->
            pool.execute {
                start.await()
                repeat(perThread) { i -> queue.enqueue(report(at = (t * perThread + i).toLong())) }
                done.countDown()
            }
        }
        start.countDown()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        pool.shutdown()

        assertTrue("队列必须有界，实际 ${queue.size}", queue.size <= 20)
        assertEquals(
            "总数 = 发出的 + 丢弃的 + 仍在队列里的",
            threads * perThread,
            queue.droppedCount + queue.size,
        )
    }

    @Test
    fun `没有事件时 drain 什么都不做`() {
        val queue = DiagReportQueue(limit = 10, maxAgeMs = 60_000L) { 10L }
        var called = false

        val expired = queue.drain { called = true; true }

        assertEquals(0, expired)
        assertFalse(called)
    }
}
