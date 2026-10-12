package com.androidmcphub.app.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 「标签 + 值」一行。
 *
 * 改版前叫 `PingRow`（只在 ping 卡片里用），现在 ping 摘要、诊断详情、工具详情、
 * 连接详情四处都要用同一种排版 —— 提成通用组件，避免四处各写一遍宽度。
 *
 * 间距由**父布局**决定（卡片内容列统一 `Arrangement.spacedBy`）：
 * Stage 1 这里自带 `padding(top = 8.dp)`，与父布局的间距叠加成了两份。
 *
 * @param monospace 值是否用等宽字体（时间戳、request_id、URL、数字这类对齐敏感的值）。
 * @param maxLines 值最多几行，超出省略。默认 2 —— 长服务器 URL 不允许把卡片撑开。
 */
@Composable
fun InfoRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    monospace: Boolean = false,
    labelWidth: Dp = 104.dp,
    maxLines: Int = 2,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(labelWidth),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Start,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
