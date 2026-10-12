package com.androidmcphub.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 设计 Token 的单测。
 *
 * 存在的理由：视觉规范里那几个色值/圆角/边距是**产品给定的约定值**，
 * 而"集中管理"这件事本身没法靠肉眼保证 —— 用断言把约定钉住，
 * 改动色值就必然要改这个文件（于是改动会被 review 看见）。
 */
class ThemeTokenTest {

    @Test
    fun `浅色配色表的每一项都与视觉规范一致`() {
        assertEquals(Color(0xFF7775D8), HubColors.Primary)
        assertEquals(Color(0xFFEEECFF), HubColors.PrimaryContainer)
        assertEquals(Color(0xFFF8F7FC), HubColors.Background)
        assertEquals(Color(0xFFFFFFFF), HubColors.Surface)
        assertEquals(Color(0xFF252B49), HubColors.TextPrimary)
        assertEquals(Color(0xFF7B8298), HubColors.TextSecondary)
        assertEquals(Color(0xFF35A979), HubColors.Success)
        assertEquals(Color(0xFFD69A43), HubColors.Warning)
        assertEquals(Color(0xFFD46B75), HubColors.Error)
    }

    @Test
    fun `形状 Token 符合视觉规范`() {
        // 主卡片 20dp / 普通卡片 16dp / 按钮 12dp
        assertEquals(RoundedCornerShape(20.dp), HubShapes.large)
        assertEquals(RoundedCornerShape(16.dp), HubShapes.medium)
        assertEquals(RoundedCornerShape(12.dp), HubShapes.small)
    }

    @Test
    fun `间距 Token 符合视觉规范`() {
        val spacing = HubSpacing()
        assertEquals(16.dp, spacing.screenHorizontal)
        // 视觉规范给的是区间：卡片内边距 12–16dp、卡片之间 10–12dp。
        // Stage 2 为了"紧凑"取区间里的偏小值，断言跟着区间一起写死，
        // 避免以后有人顺手把它改回 16dp 却没人看见。
        assertEquals(14.dp, spacing.cardPadding)
        assertEquals(10.dp, spacing.itemGap)
        assertEquals(52.dp, spacing.rowMinHeight)
        assertEquals(4.dp, spacing.xs)
        assertEquals(8.dp, spacing.sm)
        assertEquals(12.dp, spacing.md)
        assertEquals(16.dp, spacing.lg)
        assertEquals(24.dp, spacing.xl)
        assertEquals(32.dp, spacing.xxl)
    }

    @Test
    fun `语义色与主题容器色是两套`() {
        // 连接状态的"成功绿"不应该等于品牌主色，否则状态在浅色下会失去辨识度
        assertEquals(false, HubColors.Success == HubColors.Primary)
        assertEquals(false, HubColors.Error == HubColors.Warning)
        assertEquals(false, HubColors.TextPrimary == HubColors.TextSecondary)
    }
}
