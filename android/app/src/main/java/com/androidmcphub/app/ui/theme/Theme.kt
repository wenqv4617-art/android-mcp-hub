package com.androidmcphub.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.androidmcphub.app.ui.model.StatusTone

/**
 * 状态语义色。
 *
 * 刻意**不塞进** [MaterialTheme.colorScheme]：连接状态是"语义色"，与主题的容器色
 * 是两回事，分开写更好懂（这条思路沿用改版前的 `StatusPalette`，只把它从
 * "页面自己 when(LinkState)" 改成了"展示模型给 tone、主题给颜色"）。
 */
data class StatusPalette(
    val idle: Color,
    val connecting: Color,
    val connected: Color,
    val reconnecting: Color,
    val failed: Color,
)

private val LightStatusPalette = StatusPalette(
    idle = HubColors.TextSecondary,
    connecting = Color(0xFF5B7FD4),
    connected = HubColors.Success,
    reconnecting = HubColors.Warning,
    failed = HubColors.Error,
)

private val DarkStatusPalette = StatusPalette(
    idle = HubColors.TextSecondaryDark,
    connecting = Color(0xFF8FB0F0),
    connected = HubColors.SuccessDark,
    reconnecting = HubColors.WarningDark,
    failed = HubColors.ErrorDark,
)

private val LocalStatusPalette = staticCompositionLocalOf { LightStatusPalette }

/** 当前主题下该用哪套状态色。由 [AndroidMcpHubTheme] 提供，不再自己读系统深浅色。 */
val statusPalette: StatusPalette
    @Composable @ReadOnlyComposable get() = LocalStatusPalette.current

/** 展示模型的语气 → 具体颜色。页面只需要 [StatusTone]，不再各自穷举 `LinkState`。 */
@Composable
fun statusToneColor(tone: StatusTone): Color {
    val palette = statusPalette
    return when (tone) {
        StatusTone.Idle -> palette.idle
        StatusTone.Connecting -> palette.connecting
        StatusTone.Connected -> palette.connected
        StatusTone.Reconnecting -> palette.reconnecting
        StatusTone.Failed -> palette.failed
    }
}

/** 间距 Token 的取用口：`MaterialTheme.spacing.lg`。 */
val MaterialTheme.spacing: HubSpacing
    @Composable @ReadOnlyComposable get() = LocalHubSpacing.current

/**
 * 应用主题（Material 3）。
 *
 * 颜色、字体、形状、间距**都在这一个地方**装配：四个页面里不允许再出现
 * 十六进制颜色、`RoundedCornerShape(…)` 或零散 dp 常量。
 */
@Composable
fun AndroidMcpHubTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalHubSpacing provides HubSpacing(),
        LocalStatusPalette provides if (darkTheme) DarkStatusPalette else LightStatusPalette,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
            typography = HubTypography,
            shapes = HubShapes,
            content = content,
        )
    }
}
