package com.androidmcphub.app.ui.services

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidmcphub.app.mcp.McpUrlPolicy
import com.androidmcphub.app.ui.components.EmptyState
import com.androidmcphub.app.ui.components.NavRow
import com.androidmcphub.app.ui.components.PageHeader
import com.androidmcphub.app.ui.components.SectionCard
import com.androidmcphub.app.ui.components.StatusPill
import com.androidmcphub.app.ui.mcp.McpDiscovery
import com.androidmcphub.app.ui.mcp.McpServerView
import com.androidmcphub.app.ui.mcp.McpViewModel
import com.androidmcphub.app.ui.theme.spacing

/**
 * 服务：第三方 MCP 服务器的统一管理入口。
 *
 * 这一页显示的全部是**真实状态**：每台服务器的地址、开关、最近一次发现的结果
 * （拿到了几个工具、其中几个被授权、失败原因）。列表里没有任何虚构的行，
 * 也没发现过的服务器就写"尚未发现工具"，不会编一个工具数量出来。
 *
 * 服务器上跑的工具**不会被自动授权**：发现只是"看看有什么"，能不能调用由用户在
 * 详情页逐个点头。这是 Gate 3 的核心边界 —— 连接一台服务器不等于授权它的全部能力。
 */
@Composable
fun ServicesScreen(
    viewModel: McpViewModel,
    capabilitiesEnabled: Boolean,
    onOpenDiagnostics: () -> Unit,
    onOpenConnectionSettings: () -> Unit,
    onAddServer: () -> Unit,
    onOpenServer: (String) -> Unit,
) {
    val spacing = MaterialTheme.spacing
    val servers by viewModel.servers.collectAsStateWithLifecycle()

    // 进页面就发现一次：只对"能发现但还没结果"的服务器发请求，不重复联网。
    LaunchedEffect(Unit) { viewModel.discoverAll() }

    LazyColumn(
        contentPadding = PaddingValues(bottom = spacing.xxl),
        verticalArrangement = Arrangement.spacedBy(spacing.itemGap),
    ) {
        item {
            PageHeader(
                title = "服务",
                subtitle = "连接并管理第三方 MCP 服务器",
            )
        }

        if (!capabilitiesEnabled) {
            item {
                SectionCard(
                    title = "本机能力调用当前是关闭的",
                    modifier = Modifier.padding(horizontal = spacing.screenHorizontal),
                ) {
                    Text(
                        text = "「允许服务端调用本机能力」关着的时候，第三方工具也不会被执行。" +
                            "要看工具、要调整授权都可以，但模型真正发起调用时会被拒绝。" +
                            "开关在设置 → 工具与权限。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            SectionCard(
                title = "我的服务",
                trailing = {
                    StatusPill(
                        text = "${servers.size} 个",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        showDot = false,
                    )
                },
                modifier = Modifier.padding(horizontal = spacing.screenHorizontal),
            ) {
                if (servers.isEmpty()) {
                    EmptyState(
                        icon = Icons.AutoMirrored.Filled.List,
                        title = "尚未添加 MCP 服务",
                        description = "添加之后，手机就会去连这台服务器、列出它的工具。" +
                            "工具要你逐个授权，模型才能调用。",
                    )
                } else {
                    servers.forEachIndexed { index, view ->
                        if (index > 0) HorizontalDivider()
                        ServerRow(
                            view = view,
                            onOpen = { onOpenServer(view.config.id) },
                            onToggleEnabled = { enabled -> viewModel.setEnabled(view.config.id, enabled) },
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = spacing.md),
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = onAddServer,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(imageVector = Icons.Filled.Add, contentDescription = null)
                        Text("添加 MCP 服务器", modifier = Modifier.padding(start = spacing.xs))
                    }
                    if (servers.isNotEmpty()) {
                        OutlinedButton(onClick = { viewModel.discoverAll(force = true) }) {
                            Text("刷新全部")
                        }
                    }
                }
                Text(
                    text = "服务配置只存在这台手机上；请求头里的密钥用系统 Keystore 加密后单独保存，" +
                        "不会出现在任何日志或导出内容里。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = spacing.sm),
                )
            }
        }

        item {
            SectionCard(
                title = "相关设置",
                modifier = Modifier.padding(horizontal = spacing.screenHorizontal),
            ) {
                NavRow(
                    icon = Icons.Filled.Warning,
                    title = "连接诊断",
                    subtitle = "ping 明细与连接日志",
                    onClick = onOpenDiagnostics,
                )
                NavRow(
                    icon = Icons.Filled.Settings,
                    title = "服务器与设备配置",
                    subtitle = "Hub 连接的目标地址、设备 ID 与令牌",
                    onClick = onOpenConnectionSettings,
                    showDivider = false,
                )
            }
        }
    }
}

/** 一台服务器一行：名称、地址、最近一次的发现结果、启用开关。 */
@Composable
private fun ServerRow(
    view: McpServerView,
    onOpen: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
) {
    val spacing = MaterialTheme.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = spacing.sm),
        ) {
            Text(
                text = view.config.name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = McpUrlPolicy.hostOf(view.config.url).ifEmpty { view.config.url },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = statusText(view),
                style = MaterialTheme.typography.bodySmall,
                color = if (view.discovery is McpDiscovery.Failed) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(top = spacing.xs),
            )
            OutlinedButton(
                onClick = onOpen,
                modifier = Modifier.padding(top = spacing.sm),
            ) { Text("查看工具与授权") }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Switch(
                checked = view.config.enabled,
                onCheckedChange = onToggleEnabled,
            )
            Text(
                text = if (view.config.enabled) "已启用" else "已停用",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun statusText(view: McpServerView): String = when (val discovery = view.discovery) {
    McpDiscovery.Never -> if (view.config.enabled) "尚未发现工具" else "已停用，未连接"
    McpDiscovery.Discovering -> "正在发现工具…"
    is McpDiscovery.Failed -> "发现失败：${discovery.reason}"
    is McpDiscovery.Ready -> {
        val granted = discovery.tools.count {
            it.authorization == com.androidmcphub.app.mcp.store.McpAuthorizationState.Granted
        }
        val name = discovery.serverName?.takeIf { it.isNotBlank() }?.let { "（$it）" }.orEmpty()
        "${discovery.tools.size} 个工具$name · 已授权 $granted 个"
    }
}
