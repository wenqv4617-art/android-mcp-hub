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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidmcphub.app.gateway.GatewayGrantMode
import com.androidmcphub.app.gateway.GatewayService
import com.androidmcphub.app.gateway.GatewayTool
import com.androidmcphub.app.gateway.gatewayErrorText
import com.androidmcphub.app.ui.components.HubTextField
import com.androidmcphub.app.ui.components.InfoRow
import com.androidmcphub.app.ui.components.PageHeader
import com.androidmcphub.app.ui.components.SectionCard
import com.androidmcphub.app.ui.components.SettingSwitchRow
import com.androidmcphub.app.ui.components.StatusPill
import com.androidmcphub.app.ui.gateway.GatewayViewModel
import com.androidmcphub.app.ui.theme.spacing
import com.androidmcphub.app.ui.theme.statusPalette
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 一台云端服务的详情：真实连接状态、工具、服务端授权、手动测试与真实调用。
 *
 * 授权按钮只是入口。真正的闸门在 Worker/DO：它会复核服务启用状态、工具定义
 * 指纹、grant 模式与一次性确认票据。界面被绕过也不会多出任何权限。
 */
@Composable
fun GatewayServiceDetailScreen(
    viewModel: GatewayViewModel,
    serviceId: String,
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    onDeleted: () -> Unit,
) {
    val spacing = MaterialTheme.spacing
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val service = ui.services.firstOrNull { it.id == serviceId }
    val busy = ui.busyServiceId == serviceId
    var confirmingDelete by rememberSaveable(serviceId) { mutableStateOf(false) }

    LazyColumn(
        contentPadding = PaddingValues(bottom = spacing.xxl),
        verticalArrangement = Arrangement.spacedBy(spacing.itemGap),
    ) {
        item {
            PageHeader(
                title = service?.name ?: "云端服务",
                subtitle = "Cloudflare 网关",
                onBack = onBack,
            )
        }

        if (service == null) {
            item {
                SectionCard(modifier = Modifier.padding(horizontal = spacing.screenHorizontal)) {
                    if (ui.loading) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator()
                            Text("正在读取服务…", modifier = Modifier.padding(start = spacing.sm))
                        }
                    } else {
                        Text(
                            text = "这台服务不在当前网关列表里（可能刚被删除，或凭据已失效）。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            return@LazyColumn
        }

        item {
            SectionCard(
                title = "服务器",
                modifier = Modifier.padding(horizontal = spacing.screenHorizontal),
            ) {
                if (service.description.isNotBlank()) {
                    Text(
                        text = service.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                InfoRow(label = "完整地址", value = service.url, monospace = true)
                InfoRow(label = "协议", value = service.transport.label)
                InfoRow(
                    label = "连接状态",
                    value = when {
                        !service.enabled -> "已停用"
                        service.lastError != null -> "最近一次测试失败：${gatewayErrorText(service.lastError, 0)}"
                        service.tools.isEmpty() -> "尚未发现工具"
                        else -> "已连接，发现 ${service.tools.size} 个工具"
                    },
                )
                InfoRow(
                    label = "最近测试",
                    value = service.lastTestAtMs?.let { formatTime(it) } ?: "从未测试",
                )
                InfoRow(
                    label = "上游凭据",
                    value = service.secretRef?.let { "服务端引用：$it（手机不保存密钥）" } ?: "无",
                )
                InfoRow(label = "修订", value = service.revision.take(8), monospace = true)
                SettingSwitchRow(
                    title = "启用",
                    description = "停用后工具立刻从 /mcp 列表消失，调用会被服务端拒绝。",
                    checked = service.enabled,
                    onCheckedChange = { viewModel.setEnabled(service.id, it) },
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = spacing.sm),
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    Button(
                        onClick = { viewModel.test(service.id) },
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    ) { Text(if (busy) "处理中…" else "重新发现") }
                    OutlinedButton(
                        onClick = { onEdit(service.id) },
                        enabled = !busy,
                    ) { Text("编辑") }
                }
                OutlinedButton(
                    onClick = {
                        if (confirmingDelete) {
                            viewModel.delete(service.id)
                            onDeleted()
                        } else {
                            confirmingDelete = true
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = spacing.sm),
                ) {
                    Text(if (confirmingDelete) "确认删除（不可撤销）" else "删除服务")
                }
            }
        }

        item {
            SectionCard(
                title = "工具与授权",
                subtitle = "授权逐个工具给出；「需确认」的工具每次调用都要一次性票据",
                trailing = {
                    StatusPill(
                        text = "${service.tools.size} 个",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        showDot = false,
                    )
                },
                modifier = Modifier.padding(horizontal = spacing.screenHorizontal),
            ) {
                if (service.tools.isEmpty()) {
                    Text(
                        text = "还没有发现工具。点上面的「重新发现」让网关去连接这台服务器。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    service.tools.forEachIndexed { index, tool ->
                        if (index > 0) HorizontalDivider(modifier = Modifier.padding(vertical = spacing.sm))
                        GatewayToolRow(
                            service = service,
                            tool = tool,
                            busy = busy,
                            lastCall = ui.lastCall?.takeIf {
                                it.serviceId == service.id && it.gatewayToolName == tool.gatewayName
                            },
                            onGrant = { mode -> viewModel.grant(service.id, tool, mode) },
                            onRevoke = { viewModel.revoke(service.id, tool) },
                            onCall = { args -> viewModel.call(service.id, tool, args) },
                        )
                    }
                }
            }
        }

        ui.error?.let { error ->
            item {
                SectionCard(
                    title = "操作失败",
                    modifier = Modifier.padding(horizontal = spacing.screenHorizontal),
                ) {
                    Text(error, color = statusPalette.failed)
                    OutlinedButton(
                        onClick = { viewModel.clearError() },
                        modifier = Modifier.padding(top = spacing.sm),
                    ) { Text("关闭") }
                }
            }
        }

        item {
            Text(
                text = "工具定义由上游服务器提供，是不可信输入。授权前请看一眼说明与参数结构；" +
                    "上游改了定义后，旧授权会因指纹不匹配而失效，必须重新确认。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = spacing.screenHorizontal),
            )
        }
    }
}

@Composable
private fun GatewayToolRow(
    service: GatewayService,
    tool: GatewayTool,
    busy: Boolean,
    lastCall: com.androidmcphub.app.gateway.GatewayToolCallUi?,
    onGrant: (GatewayGrantMode) -> Unit,
    onRevoke: () -> Unit,
    onCall: (String) -> Unit,
) {
    val spacing = MaterialTheme.spacing
    var showSchema by remember(tool.name) { mutableStateOf(false) }
    var showCall by remember(tool.name) { mutableStateOf(false) }
    var arguments by rememberSaveable(tool.name) { mutableStateOf("{}") }
    val palette = statusPalette
    val sideEffect = looksSideEffect(tool.name)

    Column(modifier = Modifier.padding(vertical = spacing.sm)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = tool.name,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (sideEffect) {
                StatusPill(
                    text = "可能有副作用",
                    color = palette.reconnecting,
                    showDot = false,
                )
            }
        }
        Text(
            text = "网关名称：${tool.gatewayName}",
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

        if (tool.schemaJson != null) {
            Text(
                text = if (showSchema) "收起参数结构" else "查看参数结构（${tool.schemaJson.length} 字符）",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(top = spacing.xs)
                    .clickableText { showSchema = !showSchema },
            )
            if (showSchema) {
                Text(
                    text = tool.schemaJson,
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
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            StatusPill(
                text = when (tool.grant) {
                    null -> "未授权"
                    GatewayGrantMode.Read -> "已授权 · 只读"
                    GatewayGrantMode.Confirm -> "已授权 · 需确认"
                },
                color = when (tool.grant) {
                    null -> palette.idle
                    GatewayGrantMode.Read -> palette.connected
                    GatewayGrantMode.Confirm -> palette.reconnecting
                },
                modifier = Modifier.weight(1f),
            )
            if (tool.grant != null) {
                OutlinedButton(onClick = onRevoke, enabled = !busy) { Text("撤销") }
            }
        }

        if (tool.grant == null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = spacing.xs),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                OutlinedButton(onClick = { onGrant(GatewayGrantMode.Read) }, enabled = !busy) {
                    Text("授权为只读")
                }
                Button(onClick = { onGrant(GatewayGrantMode.Confirm) }, enabled = !busy) {
                    Text("授权（需确认）")
                }
            }
            Text(
                text = if (sideEffect) {
                    "这个工具名看起来会改变外部状态。建议选「需确认」：每次调用都会要求一次性票据。"
                } else {
                    "只读适合查询类工具；不确定是否安全时选「需确认」。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = spacing.xs),
            )
        } else {
            Text(
                text = if (sideEffect) {
                    "提醒：这个工具名看起来会改变外部状态；当前授权模式是「${tool.grant.label}」。"
                } else {
                    "当前授权模式：${tool.grant.label}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (sideEffect) palette.reconnecting else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = spacing.xs),
            )
            OutlinedButton(
                onClick = { showCall = !showCall },
                enabled = !busy,
                modifier = Modifier.padding(top = spacing.sm),
            ) { Text(if (showCall) "收起调用测试" else "真实调用测试") }
            if (showCall) {
                Text(
                    text = "参数（JSON 对象）。调用会经网关到达真实上游，产生真实结果。" +
                        if (tool.grant == GatewayGrantMode.Confirm) "需确认模式会先申请一次性票据。" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = spacing.xs),
                )
                HubTextField(
                    value = arguments,
                    onValueChange = { arguments = it },
                    label = "参数 JSON",
                    placeholder = "{}",
                    singleLine = false,
                    keyboardType = KeyboardType.Text,
                    supportingText = "例如 {\"query\":\"hello\"}。留空等同 {}。",
                    modifier = Modifier.padding(top = spacing.xs),
                )
                Button(
                    onClick = { onCall(arguments) },
                    enabled = !busy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = spacing.xs),
                ) { Text(if (busy) "调用中…" else "通过网关调用") }
            }
            if (lastCall != null) {
                Text(
                    text = if (lastCall.isError) "调用失败" else "调用成功",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (lastCall.isError) palette.failed else palette.connected,
                    modifier = Modifier.padding(top = spacing.sm),
                )
                Text(
                    text = lastCall.text,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/** 名称启发式只用于**建议**，从不自动决定权限。真正的模式由用户选择。 */
private fun looksSideEffect(name: String): Boolean {
    val lower = name.lowercase()
    return listOf(
        "write", "create", "delete", "remove", "update", "set", "send", "execute",
        "run", "post", "put", "patch", "upload", "publish", "revoke", "grant",
    ).any { lower.contains(it) }
}

private fun formatTime(ms: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ms))

private fun Modifier.clickableText(onClick: () -> Unit): Modifier = this.clickable(onClick = onClick)
