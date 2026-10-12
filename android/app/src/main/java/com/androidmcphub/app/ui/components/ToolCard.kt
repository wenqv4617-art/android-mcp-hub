package com.androidmcphub.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidmcphub.app.ui.model.ToolAvailability
import com.androidmcphub.app.ui.model.ToolDescriptor
import com.androidmcphub.app.ui.model.ToolSource
import com.androidmcphub.app.ui.theme.spacing
import com.androidmcphub.app.ui.theme.statusPalette

/**
 * 工具卡片：**所有普通工具共用同一张卡**。
 *
 * 页面里不允许出现 `when (toolName) { … }` 来给不同工具画不同的卡片 ——
 * 那正是"工具一多页面就失控"的来源。确实特殊的工具（将来）走独立详情页，
 * 而不是在这里加分支。
 */
@Composable
fun ToolCard(
    tool: ToolDescriptor,
    availability: ToolAvailability,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val spacing = MaterialTheme.spacing
    Card(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            modifier = Modifier.padding(spacing.cardPadding),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Build,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = spacing.md),
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                Text(
                    text = tool.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = tool.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    StatusPill(
                        text = tool.category.label,
                        color = MaterialTheme.colorScheme.primary,
                        showDot = false,
                    )
                    StatusPill(
                        text = tool.source.label,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        showDot = false,
                    )
                }
            }

            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                StatusPill(
                    text = availability.label,
                    color = availabilityColor(availability),
                )
                if (onClick != null) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

/** 可用性 → 语义色。集中在这里，页面不再各自判断。 */
@Composable
fun availabilityColor(availability: ToolAvailability): Color {
    val palette = statusPalette
    return when (availability) {
        ToolAvailability.Enabled -> palette.connected
        ToolAvailability.SwitchOff -> palette.idle
        ToolAvailability.NeedsPermission -> palette.reconnecting
        ToolAvailability.ComingSoon -> palette.idle
    }
}

/** 来源标签的语义色（服务页复用）。 */
@Composable
fun sourceColor(source: ToolSource): Color = when (source) {
    ToolSource.BuiltIn -> MaterialTheme.colorScheme.primary
    ToolSource.ThirdPartyMcp -> statusPalette.connecting
    ToolSource.LocalHttpMcp -> statusPalette.reconnecting
}
