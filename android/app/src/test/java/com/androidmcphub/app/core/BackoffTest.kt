package com.androidmcphub.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * 指数退避 + 抖动的纯函数测试。
 *
 * 抖动比例固定为 0.2、随机源固定 seed，因此结果完全可复现。
 */
class BackoffTest {

    private val cfg = HubConfig(
        reconnectInitialDelayMs = 1_000L,
        reconnectMaxDelayMs = 5_000L,
        reconnectJitterRatio = 0.2,
    )

    @Test
    fun `基准延迟按 2 的幂递增`() {
        assertEquals(1_000L, backoffBaseDelayMs(0, cfg))
        assertEquals(2_000L, backoffBaseDelayMs(1, cfg))
        assertEquals(4_000L, backoffBaseDelayMs(2, cfg))
    }

    @Test
    fun `超过上限后封顶`() {
        // 第 3 次理论上是 8000ms，超过 5000ms 的上限
        assertEquals(5_000L, backoffBaseDelayMs(3, cfg))
        assertEquals(5_000L, backoffBaseDelayMs(10, cfg))
        // 极大的尝试次数也不能溢出成负数
        assertEquals(5_000L, backoffBaseDelayMs(Int.MAX_VALUE, cfg))
        assertTrue(backoffDelayMs(Int.MAX_VALUE, cfg, Random(1)) > 0L)
    }

    @Test
    fun `负数尝试次数按第 0 次处理`() {
        assertEquals(1_000L, backoffBaseDelayMs(-5, cfg))
    }

    @Test
    fun `抖动落在 base 的正负 20 百分比之内`() {
        for (seed in 1..200) {
            val delay = backoffDelayMs(0, cfg, Random(seed))
            assertTrue("seed=$seed delay=$delay", delay in 800L..1_200L)
        }
    }

    @Test
    fun `封顶之后抖动区间跟着封顶值走`() {
        for (seed in 1..200) {
            val delay = backoffDelayMs(10, cfg, Random(seed))
            assertTrue("seed=$seed delay=$delay", delay in 4_000L..6_000L)
        }
    }

    @Test
    fun `加上抖动之后仍然严格递增`() {
        // 相邻档位差 2 倍，远大于 ±20% 的抖动幅度，因此必定递增
        val random = Random(20241201)
        val d0 = backoffDelayMs(0, cfg, random)
        val d1 = backoffDelayMs(1, cfg, random)
        val d2 = backoffDelayMs(2, cfg, random)
        assertTrue("$d0 < $d1", d0 < d1)
        assertTrue("$d1 < $d2", d1 < d2)
    }

    @Test
    fun `抖动比例为 0 时等于基准值`() {
        val noJitter = cfg.copy(reconnectJitterRatio = 0.0)
        for (attempt in 0..5) {
            assertEquals(
                backoffBaseDelayMs(attempt, noJitter),
                backoffDelayMs(attempt, noJitter, Random(attempt)),
            )
        }
    }

    @Test
    fun `抖动比例越界时被夹到 0 到 1`() {
        val tooBig = cfg.copy(reconnectJitterRatio = 9.9)
        for (seed in 1..50) {
            val delay = backoffDelayMs(1, cfg = tooBig, random = Random(seed))
            assertTrue("seed=$seed delay=$delay", delay in 0L..4_000L)
        }
    }

    @Test
    fun `初始间隔为 0 时直接返回 0`() {
        val zero = cfg.copy(reconnectInitialDelayMs = 0L)
        assertEquals(0L, backoffDelayMs(3, zero, Random(1)))
    }
}
