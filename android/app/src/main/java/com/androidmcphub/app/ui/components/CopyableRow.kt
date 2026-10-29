package com.androidmcphub.app.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * 一行"标签 + 可复制的值"。
 *
 * 为什么这些 ID 必须能一键复制：`server_id` 不是用户起的名字，而是本机生成的短标识，
 * 手抄一遍（`srv_ab12cd` 这种）几乎必然出错，而写错的 server_id 在服务端只会得到
 * 一句"本机没有启用这台服务器"。复制按钮把这条"看起来像调试信息"的字段变成
 * 可以真的拿去用的东西 —— 用户要能在 ChatGPT 里直接粘进 `hub_list_tools` /
 * `hub_call_tool` 的 `server_id` 参数。
 */
@Composable
fun CopyableRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    labelWidth: Dp = 104.dp,
    monospace: Boolean = true,
    maxLines: Int = 2,
) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(value) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1_500)
            copied = false
        }
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
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
        TextButton(onClick = {
            clipboard.setText(AnnotatedString(value))
            copied = true
        }) {
            Text(if (copied) "已复制" else "复制", style = MaterialTheme.typography.labelMedium)
        }
    }
}
