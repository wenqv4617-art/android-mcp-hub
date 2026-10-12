package com.androidmcphub.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import com.androidmcphub.app.ui.theme.spacing

/**
 * 「标题 + 说明 + 开关」一行。
 *
 * 说明文字**必填**：能力总开关这种开关一旦没有解释，用户只能在
 * "打开会不会出事"里猜。
 *
 * 三个刻意的地方：
 *  * 整行可点（`toggleable` + `Role.Switch`），触摸目标不小于 [HubSpacing.rowMinHeight]，
 *    而不是只有右边那颗小开关能点；
 *  * 不用 `Switch` 自带的 `onCheckedChange` 去写业务，避免"点文字无效"；
 *  * 组件不自带顶部间距 —— 间距归父布局，见 [InfoRow] 的同类说明。
 */
@Composable
fun SettingSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val spacing = MaterialTheme.spacing
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = spacing.rowMinHeight)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = spacing.xs),
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            modifier = Modifier.padding(start = spacing.md),
        )
    }
}
