package com.androidmcphub.app.core

import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * 计算第 [attempt] 次连续失败后的重连等待时间（毫秒）。
 *
 * 公式：`delay = min(initial * 2^attempt, max) * (1 ± jitter)`
 * - [attempt] 从 0 开始计数（0 表示刚断开后的第一次重连）；
 * - 抖动为乘性抖动，落点区间是 `[base*(1-j), base*(1+j)]`；
 * - [random] 由调用方注入，单测里传固定 seed 即可复现。
 *
 * 纯函数，无 Android 依赖。
 */
fun backoffDelayMs(attempt: Int, cfg: HubConfig, random: Random): Long {
    val maxDelay = max(0L, cfg.reconnectMaxDelayMs)
    val initial = cfg.reconnectInitialDelayMs.coerceIn(0L, maxDelay)
    if (initial <= 0L) return 0L

    val n = attempt.coerceAtLeast(0)

    // 指数部分：2^n。n 很大时直接取上限，避免 Long 左移溢出。
    val base = if (n >= 40) maxDelay else min(initial shl n, maxDelay)

    val jitter = cfg.reconnectJitterRatio.coerceIn(0.0, 1.0)
    if (jitter <= 0.0 || base <= 0L) return base

    // random.nextDouble() ∈ [0, 1) → factor ∈ [1-j, 1+j)
    val factor = 1.0 + (random.nextDouble() * 2.0 - 1.0) * jitter
    return max(0L, (base.toDouble() * factor).toLong())
}

/**
 * 抖动前的基准值（不加随机量）。界面要展示「Xs 后重连」时可以拿它做参考，
 * 也方便单测独立验证「指数递增 / 封顶」这两件事。
 */
fun backoffBaseDelayMs(attempt: Int, cfg: HubConfig): Long {
    val maxDelay = max(0L, cfg.reconnectMaxDelayMs)
    val initial = cfg.reconnectInitialDelayMs.coerceIn(0L, maxDelay)
    if (initial <= 0L) return 0L
    val n = attempt.coerceAtLeast(0)
    return if (n >= 40) maxDelay else min(initial shl n, maxDelay)
}
