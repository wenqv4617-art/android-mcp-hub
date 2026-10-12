package com.androidmcphub.app.ui.services

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.androidmcphub.app.ui.components.EmptyState
import com.androidmcphub.app.ui.components.PageHeader
import com.androidmcphub.app.ui.components.SectionCard
import com.androidmcphub.app.ui.components.StatusPill
import com.androidmcphub.app.ui.theme.spacing

/**
 * 服务：第三方 MCP 服务器的管理入口。
 *
 * 这一页**刻意是空的**。本轮没有实现 MCP 客户端（连接、认证、工具发现、工具调用），
 * 所以没有"已接入的服务"可显示，也没有真实的服务状态可读。与其摆三张假卡片
 * （mock 设计稿里那三张）让人以为能用，不如如实显示空状态。
 *
 * 与连接诊断的分工：Cloudflare / phone-link 的连接状态属于**本应用自身**的连接，
 * 它的诊断入口在「设置 → 连接诊断」；这里管的是**第三方服务**，两者不是一回事。
 */
@Composable
fun ServicesScreen(
    onAddService: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val spacing = MaterialTheme.spacing

    LazyColumn(
        contentPadding = PaddingValues(bottom = spacing.xxl),
        verticalArrangement = Arrangement.spacedBy(spacing.itemGap),
    ) {
        item {
            PageHeader(
                title = "服务",
                subtitle = "连接并管理第三方 MCP 服务器",
                modifier = Modifier.padding(horizontal = spacing.sm),
                actions = {
                    IconButton(onClick = onAddService) {
                        Icon(imageVector = Icons.Filled.Add, contentDescription = "添加 MCP 服务器")
                    }
                },
            )
        }

        item {
            SectionCard(
                title = "我的服务",
                trailing = {
                    StatusPill(
                        text = "0 个",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        showDot = false,
                    )
                },
                modifier = Modifier.padding(horizontal = spacing.screenHorizontal),
                contentPadding = PaddingValues(spacing.lg),
            ) {
                EmptyState(
                    icon = Icons.AutoMirrored.Filled.List,
                    title = "尚未添加 MCP 服务",
                    description = "连接第三方 MCP 服务器需要认证、凭据加密存储与工具发现，" +
                        "这些还没有实现（见 docs/UI-Stage1-Manual-QA.md 的「未实现功能」）。" +
                        "在当前版本里，请用电脑上的 MCP 客户端连接本手机的 Worker 地址。",
                    modifier = Modifier.padding(vertical = spacing.sm),
                )
                Button(
                    onClick = onAddService,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("添加 MCP 服务器")
                }
                Text(
                    text = "这个入口会打开表单结构预览，但本版本不会保存或连接。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            SectionCard(
                title = "支持的服务类型",
                subtitle = "本轮只搭 UI 结构，两种类型都还没有接入",
                modifier = Modifier.padding(horizontal = spacing.screenHorizontal),
            ) {
                ServiceTypeRow(
                    title = "第三方 MCP 服务器",
                    description = "通过 Streamable HTTP（JSON-RPC POST）连接远端 MCP 服务，" +
                        "拉取其工具并纳入统一工具目录。",
                )
                Spacer(Modifier.height(spacing.md))
                ServiceTypeRow(
                    title = "本地 HTTP MCP 中转",
                    description = "把本机能力经本地 HTTP 中转暴露给其他 MCP 客户端。",
                )
            }
        }

        item {
            SectionCard(
                title = "相关设置",
                modifier = Modifier.padding(horizontal = spacing.screenHorizontal),
            ) {
                RelatedRow(
                    icon = Icons.Filled.Settings,
                    title = "连接诊断",
                    subtitle = "查看本应用自身连接的 ping 明细与日志（在设置页）",
                    onClick = onOpenSettings,
                )
                Spacer(Modifier.height(spacing.sm))
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        imageVector = Icons.Filled.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(24.dp),
                    )
                    Text(
                        text = "「服务」页不显示 phone-link / Cloudflare 的连接状态 —— " +
                            "那是本应用自身的连接，不是第三方的服务。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ServiceTypeRow(title: String, description: String) {
    val spacing = MaterialTheme.spacing
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.width(spacing.sm))
            StatusPill(
                text = "未开放",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                showDot = false,
            )
        }
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RelatedRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    val spacing = MaterialTheme.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(24.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        androidx.compose.material3.TextButton(onClick = onClick) { Text("打开") }
    }
}
