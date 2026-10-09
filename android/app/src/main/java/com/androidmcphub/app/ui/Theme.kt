package com.androidmcphub.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** 品牌主色，与启动图标的底色一致。 */
private val BrandBlue = Color(0xFF0D3B66)
private val BrandBlueDark = Color(0xFF9EC5E8)

private val LightColors = lightColorScheme(
    primary = BrandBlue,
    onPrimary = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = BrandBlueDark,
    onPrimary = Color(0xFF00203C),
)

/**
 * 状态文字用色。
 *
 * 刻意不塞进 [MaterialTheme.colorScheme]：连接状态是「语义色」，
 * 与主题的容器色是两回事，分开写更好懂。
 */
data class StatusPalette(
    val idle: Color,
    val connecting: Color,
    val connected: Color,
    val reconnecting: Color,
    val failed: Color,
)

private val LightStatus = StatusPalette(
    idle = Color(0xFF616161),
    connecting = Color(0xFF1565C0),
    connected = Color(0xFF2E7D32),
    reconnecting = Color(0xFFEF6C00),
    failed = Color(0xFFC62828),
)

private val DarkStatus = StatusPalette(
    idle = Color(0xFFBDBDBD),
    connecting = Color(0xFF90CAF9),
    connected = Color(0xFF81C784),
    reconnecting = Color(0xFFFFB74D),
    failed = Color(0xFFEF9A9A),
)

/** 当前主题下该用哪套状态色。 */
@Composable
fun statusPalette(): StatusPalette =
    if (isSystemInDarkTheme()) DarkStatus else LightStatus

/** 应用主题（Material3）。 */
@Composable
fun AndroidMcpHubTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
