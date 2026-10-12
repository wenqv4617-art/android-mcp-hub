package com.androidmcphub.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow

/**
 * 页面标题栏 —— 四个主页面和所有子页面共用同一个。
 *
 * 两个刻意的选择：
 *  * `windowInsets = WindowInsets(0,0,0,0)`：系统栏内边距已经由外层 `Scaffold`
 *    与 `safeDrawingPadding()` 处理过，标题栏再吃一次就会多出一条白边；
 *  * `containerColor = Color.Transparent`：标题直接坐在页面背景（#F8F7FC）上，
 *    而不是 Material 默认的 surface 色，这样"轻盈"的观感来自背景层次
 *    而不是卡片堆叠。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = {
            Column {
                // 页面标题用 headlineSmall（22sp，视觉规范 20–22sp）；
                // TopAppBar 默认的 titleLarge 在 Stage 2 被降成卡片标题档（18sp），
                // 所以这里必须显式指定，不能沿用默认值。
                Text(text = title, style = MaterialTheme.typography.headlineSmall)
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                    )
                }
            }
        },
        actions = actions,
        windowInsets = WindowInsets(0, 0, 0, 0),
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        modifier = modifier,
    )
}
