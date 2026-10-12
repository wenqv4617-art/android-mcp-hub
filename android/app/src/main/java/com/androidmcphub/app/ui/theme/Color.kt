package com.androidmcphub.app.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * 设计 Token —— 颜色。
 *
 * 为什么集中成一个 object：色值是**视觉规范**，页面只该引用语义
 * （`MaterialTheme.colorScheme.primary`、[StatusPalette]）。一旦某个页面自己写
 * `Color(0xFF7775D8)`，"换主题"就退化成全局搜索替换，而漏掉的那一处永远不会被发现。
 *
 * 浅色是产品给定配色表的逐项落地；深色没有给表，按同一色相推导
 * （不是简单反色：背景压暗、主色提亮，保持"柔和不刺眼"的调性）。
 */
object HubColors {

    // ---- 浅色（产品配色表）----------------------------------------------
    /** Primary #7775D8 */
    val Primary = Color(0xFF7775D8)

    /** PrimaryContainer #EEECFF */
    val PrimaryContainer = Color(0xFFEEECFF)

    /** Background #F8F7FC */
    val Background = Color(0xFFF8F7FC)

    /** Surface #FFFFFF */
    val Surface = Color(0xFFFFFFFF)

    /** 卡片里的浅底（进度条槽、标签底色）；配色表未给，取 Background 与 Surface 的中间值。 */
    val SurfaceVariant = Color(0xFFF1F0F7)

    /** TextPrimary #252B49 */
    val TextPrimary = Color(0xFF252B49)

    /** TextSecondary #7B8298 */
    val TextSecondary = Color(0xFF7B8298)

    /** 分隔线/描边；配色表未给，取 TextSecondary 的低饱和淡化。 */
    val Outline = Color(0xFFDCDBE6)

    /** Success #35A979 */
    val Success = Color(0xFF35A979)

    /** Warning #D69A43 */
    val Warning = Color(0xFFD69A43)

    /** Error #D46B75 */
    val Error = Color(0xFFD46B75)

    // ---- 深色（按同一色相推导）------------------------------------------
    val PrimaryDark = Color(0xFF9E9CF0)
    val PrimaryContainerDark = Color(0xFF2B2A44)
    val BackgroundDark = Color(0xFF131220)
    val SurfaceDark = Color(0xFF1C1B28)
    val SurfaceVariantDark = Color(0xFF262536)
    val TextPrimaryDark = Color(0xFFE9E8F4)
    val TextSecondaryDark = Color(0xFFA3A8BD)
    val OutlineDark = Color(0xFF3A3949)
    val SuccessDark = Color(0xFF6FD3A8)
    val WarningDark = Color(0xFFE8B36B)
    val ErrorDark = Color(0xFFE89AA3)
}

/** 浅色 ColorScheme：所有槽位都来自 [HubColors]，页面不再写十六进制字面量。 */
internal val LightColorScheme = lightColorScheme(
    primary = HubColors.Primary,
    onPrimary = Color.White,
    primaryContainer = HubColors.PrimaryContainer,
    onPrimaryContainer = HubColors.TextPrimary,
    secondary = HubColors.TextSecondary,
    onSecondary = Color.White,
    background = HubColors.Background,
    onBackground = HubColors.TextPrimary,
    surface = HubColors.Surface,
    onSurface = HubColors.TextPrimary,
    surfaceVariant = HubColors.SurfaceVariant,
    onSurfaceVariant = HubColors.TextSecondary,
    outline = HubColors.Outline,
    outlineVariant = HubColors.Outline,
    error = HubColors.Error,
    onError = Color.White,
    errorContainer = Color(0xFFFDECEE),
    onErrorContainer = Color(0xFF6E2B33),
)

/** 深色 ColorScheme。 */
internal val DarkColorScheme = darkColorScheme(
    primary = HubColors.PrimaryDark,
    onPrimary = Color(0xFF1B1A2B),
    primaryContainer = HubColors.PrimaryContainerDark,
    onPrimaryContainer = HubColors.TextPrimaryDark,
    secondary = HubColors.TextSecondaryDark,
    onSecondary = Color(0xFF1B1A2B),
    background = HubColors.BackgroundDark,
    onBackground = HubColors.TextPrimaryDark,
    surface = HubColors.SurfaceDark,
    onSurface = HubColors.TextPrimaryDark,
    surfaceVariant = HubColors.SurfaceVariantDark,
    onSurfaceVariant = HubColors.TextSecondaryDark,
    outline = HubColors.OutlineDark,
    outlineVariant = HubColors.OutlineDark,
    error = HubColors.ErrorDark,
    onError = Color(0xFF3A1F23),
    errorContainer = Color(0xFF3A1F23),
    onErrorContainer = HubColors.ErrorDark,
)
