package com.androidmcphub.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidmcphub.app.ui.theme.spacing

/**
 * 「图标 + 标题 + 副标题 + 右箭头」的可点击行。
 *
 * 为什么要有这个组件：改版前同一个排版在三处各写了一遍 ——
 * 首页的 `ActionRow`、设置页的 `SettingRow`、服务页的 `RelatedRow`。
 * 三份代码的间距、图标尺寸、分割线行为迟早会漂移，而"设置里能点、服务里不能点"
 * 这种问题最难被 review 发现。合并成一份之后，`NavRow` 是唯一的入口。
 *
 * 行高不低于 [rowMinHeight]（52dp），整行可点 —— 紧凑不等于把触摸目标压到 48dp 以下。
 *
 * @param showChevron 右侧是否显示箭头。纯动作行（例如"清空日志"）传 `false`。
 * @param trailing 需要放开关/标签等自定义尾部时使用；与 [showChevron] 互斥，优先显示它。
 */
@Composable
fun NavRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showChevron: Boolean = true,
    showDivider: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
) {
    val spacing = MaterialTheme.spacing
    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = spacing.rowMinHeight)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(vertical = spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.size(spacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            when {
                trailing != null -> {
                    Spacer(Modifier.size(spacing.sm))
                    trailing()
                }

                showChevron -> Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (showDivider) {
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        }
    }
}
