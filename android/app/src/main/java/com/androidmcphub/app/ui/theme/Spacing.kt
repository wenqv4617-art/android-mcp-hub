package com.androidmcphub.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 设计 Token —— 间距与圆角内边距。
 *
 * 视觉规范：页面水平边距 16dp、主卡片圆角 20dp、普通卡片 16dp、按钮 12–16dp。
 * 这里只放**间距**（圆角在 [HubShapes]），并以 4dp 为基数成阶梯，
 * 避免出现 7dp / 9dp / 13dp 这类随手写出来的值。
 */
@Immutable
data class HubSpacing(
    /** 4dp：图标与文字之间这类最小缝。 */
    val xs: Dp = 4.dp,
    /** 8dp：同一卡片内相邻元素。 */
    val sm: Dp = 8.dp,
    /** 12dp：卡片内成组元素之间。 */
    val md: Dp = 12.dp,
    /** 16dp：卡片内边距 / 列表项之间。 */
    val lg: Dp = 16.dp,
    /** 24dp：区块之间。 */
    val xl: Dp = 24.dp,
    /** 32dp：页面首尾留白。 */
    val xxl: Dp = 32.dp,
) {
    /** 页面水平边距：**16dp**（视觉规范）。 */
    val screenHorizontal: Dp = lg

    /**
     * 卡片内边距：**14dp**。
     *
     * 视觉规范给的是 12–16dp。取中值是因为两边都在压：卡片内边距一旦是 16dp，
     * 再叠加卡片之间 10dp 的间隔与字段自带的间距，一屏就只剩两三个字段。
     */
    val cardPadding: Dp = 14.dp

    /** 列表项之间的垂直间隔：10dp（视觉规范 10–12dp）。 */
    val itemGap: Dp = 10.dp

    /**
     * 可点击列表行的最小高度：**52dp**。
     *
     * 视觉规范 48–56dp。紧凑的前提是不能把点击区域压到无障碍下限（48dp）以下 ——
     * 所以"紧凑"是减少留白，不是缩小触摸目标。
     */
    val rowMinHeight: Dp = 52.dp
}

/**
 * 间距 Token 的 CompositionLocal。
 *
 * 默认值就是规范值，所以页面即使不套主题也不会拿到 0dp；主题里会显式提供一份，
 * 将来要做"紧凑模式"只需要换这一个 provider。
 */
val LocalHubSpacing = staticCompositionLocalOf { HubSpacing() }
