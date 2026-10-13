package com.androidmcphub.app.ui.services

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidmcphub.app.mcp.McpUrlPolicy
import com.androidmcphub.app.mcp.store.McpAuthorizationState
import com.androidmcphub.app.ui.components.InfoRow
import com.androidmcphub.app.ui.components.PageHeader
import com.androidmcphub.app.ui.components.SectionCard
import com.androidmcphub.app.ui.components.SettingSwitchRow
import com.androidmcphub.app.ui.components.StatusPill
import com.androidmcphub.app.ui.mcp.McpDiscovery
import com.androidmcphub.app.ui.mcp.McpServerView
import com.androidmcphub.app.ui.mcp.McpToolView
import com.androidmcphub.app.ui.mcp.McpViewModel
import com.androidmcphub.app.ui.theme.spacing
import com.androidmcphub.app.ui.theme.statusPalette

/**
 * 一台服务器的工具与授权。
 *
 * 这一页是 Gate 3 里"用户点的那一下"所在的地方：**连接一台服务器 ≠ 授权它的能力**。
 * 这里列出的每个工具都要用户单独点头，运行时（聚合入口）在真正调用前会再查一遍
 * 本机授权记录；服务器改过工具定义（名称/描述/参数）就会显示"定义已变化"，
 * 必须重新确认 —— 因为那时候用户当初点的那份说明书已经不是现在这份了。
 */
@Composable
fun McpServerToolsScreen(
    viewModel: McpViewModel,
    serverId: String,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
) {
    val spacing = MaterialTheme.spacing
    val servers by viewModel.servers.collectAsStateWithLifecycle()
    val view = servers.firstOrNull { it.config.id == serverId }

    LazyColumn(
        contentPadding = PaddingValues(bottom = spacing.xxl),
        verticalArrangement = Arrangement.spacedBy(spacing.itemGap),
    ) {
        item {
            PageHeader(
                title = view?.config?.name ?: "服务器",
                subtitle = view?.config?.let { McpUrlPolicy.hostOf(it.url).ifEmpty { it.url } },
                onBack = onBack,
            )
        }

        if (view == null) {
            item {
                SectionCard(modifier = Modifier.padding(horizontal = spacing.screenHorizontal)) {
                    Text(
                        text = "这台服务器已经不在了（可能刚被删除）。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            return@LazyColumn
        }

        item {
            SectionCard(
                title = "服务器",
                modifier = Modifier.padding(horizontal = spacing.screenHorizontal),
            ) {
                InfoRow(label = "完整地址", value = view.config.url, monospace = true)
                ServerStatusInfo(view)
                SettingSwitchRow(
                    title = "启用",
                    description = "停用后模型看不到这台服务器的工具，也不会被调用。",
                    checked = view.config.enabled,
                    onCheckedChange = { enabled -> viewModel.setEnabled(view.config.id, enabled) },
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = spacing.sm),
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    Button(
                        onClick = { viewModel.discover(view.config.id) },
                        modifier = Modifier.weight(1f),
                    ) { Text("刷新工具列表") }
                    DeleteButton(
                        onDelete = { viewModel.delete(view.config.id); onDeleted() },
                    )
                }
            }
        }

        item {
            SectionCard(
                title = "工具与授权",
                subtitle = "授权是逐个工具给的；模型只能调用已授权的工具",
                trailing = {
                    val count = (view.discovery as? McpDiscovery.Ready)?.tools?.size
                    if (count != null) {
                        StatusPill(
                            text = "$count 个",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            showDot = false,
                        )
                    }
                },
                modifier = Modifier.padding(horizontal = spacing.screenHorizontal),
            ) {
                when (val discovery = view.discovery) {
                    McpDiscovery.Never -> Text(
                        text = "还没有发现工具。点上面的「刷新工具列表」去这台服务器上看看它提供了什么。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    McpDiscovery.Discovering -> Text(
                        text = "正在连接并列出工具…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    is McpDiscovery.Failed -> Text(
                        text = "发现失败：${discovery.reason}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = statusPalette.failed,
                    )

                    is McpDiscovery.Ready -> {
                        if (discovery.tools.isEmpty()) {
                            Text(
                                text = "这台服务器没有列出任何工具。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            discovery.tools.forEachIndexed { index, tool ->
                                if (index > 0) HorizontalDivider(modifier = Modifier.padding(vertical = spacing.sm))
                                ToolAuthorizationRow(
                                    tool = tool,
                                    onGrant = { viewModel.grant(view.config.id, tool.name) },
                                    onRevoke = { viewModel.revoke(view.config.id, tool.name) },
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            Text(
                text = "工具定义由服务器提供，属于**不可信输入**：它可能要求模型把任何文本当作参数。" +
                    "授权前请看一眼说明与参数；服务器改了定义之后授权会自动失效，需要你重新确认。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = spacing.screenHorizontal),
            )
        }
    }
}

@Composable
private fun ServerStatusInfo(view: McpServerView) {
    val text = when (val discovery = view.discovery) {
        McpDiscovery.Never -> "尚未发现"
        McpDiscovery.Discovering -> "正在发现…"
        is McpDiscovery.Failed -> "失败：${discovery.reason}"
        is McpDiscovery.Ready -> buildString {
            append("已连接")
            discovery.serverName?.takeIf { it.isNotBlank() }?.let { append("（$it）") }
            append("，协议版本 ${discovery.protocolVersion}")
        }
    }
    InfoRow(label = "状态", value = text)
}

/** 删除：第一次点只把按钮变成确认态，第二次才真的删（没有撤销，值得多问一句）。 */
@Composable
private fun DeleteButton(onDelete: () -> Unit) {
    var confirming by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { if (confirming) onDelete() else confirming = true }) {
        Text(if (confirming) "确认删除" else "删除")
    }
}

@Composable
private fun ToolAuthorizationRow(
    tool: McpToolView,
    onGrant: () -> Unit,
    onRevoke: () -> Unit,
) {
    val spacing = MaterialTheme.spacing
    var showSchema by remember(tool.name) { mutableStateOf(false) }
    val palette = statusPalette

    Column(modifier = Modifier.padding(vertical = spacing.sm)) {
        Text(
            text = tool.title ?: tool.name,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "远端名称：${tool.name}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (tool.description.isNotBlank()) {
            Text(
                text = tool.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = spacing.xs),
            )
        }

        if (tool.schemaText != null) {
            Text(
                text = if (showSchema) "收起参数结构" else "查看参数结构（${tool.schemaChars} 字符）",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(top = spacing.xs)
                    .clickableText { showSchema = !showSchema },
            )
            if (showSchema) {
                Text(
                    text = tool.schemaText,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = spacing.xs),
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusPill(
                text = when (tool.authorization) {
                    McpAuthorizationState.Granted -> "已授权"
                    McpAuthorizationState.NotGranted -> "未授权"
                    McpAuthorizationState.Stale -> "定义已变化"
                },
                color = when (tool.authorization) {
                    McpAuthorizationState.Granted -> palette.connected
                    McpAuthorizationState.NotGranted -> palette.idle
                    McpAuthorizationState.Stale -> palette.reconnecting
                },
                modifier = Modifier.weight(1f),
            )
            when (tool.authorization) {
                McpAuthorizationState.Granted -> OutlinedButton(onClick = onRevoke) { Text("撤销授权") }
                McpAuthorizationState.NotGranted -> Button(onClick = onGrant) { Text("授权") }
                // Stale：名字没变、定义变了。重新授权 = 用**当前**这份定义覆盖旧指纹。
                McpAuthorizationState.Stale -> Button(onClick = onGrant) { Text("重新授权") }
            }
        }

        if (tool.authorization == McpAuthorizationState.Stale) {
            Text(
                text = "这台服务器上的工具定义在授权之后变了（名称、描述或参数）。原来的授权已失效，" +
                    "请核对上面的说明之后再点「重新授权」。",
                style = MaterialTheme.typography.bodySmall,
                color = palette.reconnecting,
                modifier = Modifier.padding(top = spacing.xs),
            )
        }
    }
}

private fun Modifier.clickableText(onClick: () -> Unit): Modifier = this.clickable(onClick = onClick)
