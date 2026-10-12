package com.androidmcphub.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * 设计 Token —— 形状。
 *
 * 视觉规范：主卡片圆角 20dp、普通卡片 16dp、按钮 12–16dp。
 * 页面里不再出现 `RoundedCornerShape(6.dp)` 这类一次性值（改版前唯一一个自定义形状
 * 就是这个 6dp，出现在 ping 的 request_id 底框上）。
 */
val HubShapes = Shapes(
    /** 8dp：小标签、状态药丸（比按钮更小一级）。 */
    extraSmall = RoundedCornerShape(8.dp),
    /** 12dp：按钮（规范区间 12–16dp 的下沿）、输入框。 */
    small = RoundedCornerShape(12.dp),
    /** 16dp：普通卡片。 */
    medium = RoundedCornerShape(16.dp),
    /** 20dp：主卡片（首页连接状态、工具详情大卡）。 */
    large = RoundedCornerShape(20.dp),
    /** 28dp：底部弹层 / 全宽面板。 */
    extraLarge = RoundedCornerShape(28.dp),
)
