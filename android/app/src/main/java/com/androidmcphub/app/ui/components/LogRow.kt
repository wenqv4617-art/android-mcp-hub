package com.androidmcphub.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.androidmcphub.app.core.LogEntry
import com.androidmcphub.app.core.LogLevel
import com.androidmcphub.app.ui.theme.spacing
import com.androidmcphub.app.ui.theme.statusPalette

/**
 * 一行日志：时间 + 级别 + 文本。
 *
 * 级别色走主题语义色（不再各写 `Color(0xFFC62828)` 这种深浅色三元式）：
 *  * INFO → 次要文字色
 *  * WARN → 主题的"需要注意"色
 *  * ERROR → 主题的 error 色
 *
 * 日志文本由 `core/HubLog` 脱敏后写入，这里**不再做任何加工**（尤其不做拼接截断，
 * 免得把脱敏边界搞乱）。
 */
@Composable
fun LogRow(entry: LogEntry, modifier: Modifier = Modifier) {
    val palette = statusPalette
    val color = when (entry.level) {
        LogLevel.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
        LogLevel.WARN -> palette.reconnecting
        LogLevel.ERROR -> MaterialTheme.colorScheme.error
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                text = formatTime(entry.timeMs),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = entry.level.name,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = color,
                modifier = Modifier
                    .padding(start = MaterialTheme.spacing.sm)
                    .width(44.dp),
            )
            Text(
                text = entry.text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
        }
        HorizontalDivider(
            modifier = Modifier.padding(top = MaterialTheme.spacing.xs),
            color = MaterialTheme.colorScheme.surfaceVariant,
        )
    }
}
