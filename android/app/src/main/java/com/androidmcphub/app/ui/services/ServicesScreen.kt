package com.androidmcphub.app.ui.services

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidmcphub.app.gateway.GatewayService
import com.androidmcphub.app.gateway.GatewayUiState
import com.androidmcphub.app.gateway.gatewayErrorText
import com.androidmcphub.app.mcp.McpUrlPolicy
import com.androidmcphub.app.ui.components.CopyableRow
import com.androidmcphub.app.ui.components.EmptyState
import com.androidmcphub.app.ui.components.HubTextField
import com.androidmcphub.app.ui.components.NavRow
import com.androidmcphub.app.ui.components.PageHeader
import com.androidmcphub.app.ui.components.SectionCard
import com.androidmcphub.app.ui.components.StatusPill
import com.androidmcphub.app.ui.gateway.GatewayViewModel
import com.androidmcphub.app.ui.mcp.McpDiscovery
import com.androidmcphub.app.ui.mcp.McpServerView
import com.androidmcphub.app.ui.mcp.McpViewModel
import com.androidmcphub.app.ui.theme.HubSpacing
import com.androidmcphub.app.ui.theme.spacing
import com.androidmcphub.app.ui.theme.statusPalette

/** 服务页当前查看的是哪一边。两边的数据来源、凭据与授权机制都相互独立。 */
private enum class ServicesSource { Local, Gateway }

/**
 * 服务：第三方 MCP 服务器的统一管理入口。
 *
 * 两种来源：
 *  * **本机直连**（Gate 3）：配置存在手机上，手机自己去连 MCP 服务器；
 *  * **云端网关**：服务注册在 Cloudflare Worker 的 Durable Object 里，手机用
 *    配对换来的受限令牌管理，并通过 `/mcp` 调用。全局管理员令牌从不进入 APK。
 *
 * 默认显示本机直连，保持升级后的行为不变；用户明确切换后才访问云端。
 */
@Composable
fun ServicesScreen(
    viewModel: McpViewModel,
    gatewayViewModel: GatewayViewModel,
    capabilitiesEnabled: Boolean,
    onOpenDiagnostics: () -> Unit,
    onOpenConnectionSettings: () -> Unit,
    onAddServer: () -> Unit,
    onOpenServer: (String) -> Unit,
    onAddGatewayServer: () -> Unit,
    onOpenGatewayServer: (String) -> Unit,
) {
    val spacing = MaterialTheme.spacing
    val servers by viewModel.servers.collectAsStateWithLifecycle()
    val gateway by gatewayViewModel.ui.collectAsStateWithLifecycle()
    var source by rememberSaveable { mutableStateOf(ServicesSource.Local) }

    // 只在用户真的切到网关、且已经配对时拉一次列表；不在进入页面时偷偷联网。
    LaunchedEffect(source, gateway.paired) {
        when {
            source == ServicesSource.Local -> viewModel.discoverAll()
            gateway.paired -> gatewayViewModel.refresh()
        }
    }

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

        item {
            Row(
                modifier = Modifier.padding(horizontal = spacing.screenHorizontal),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                FilterChip(
                    selected = source == ServicesSource.Local,
                    onClick = { source = ServicesSource.Local },
                    label = { Text("本机直连") },
                )
                FilterChip(
                    selected = source == ServicesSource.Gateway,
                    onClick = { source = ServicesSource.Gateway },
                    label = {
                        Text(if (gateway.paired) "云端网关 · 已连接" else "云端网关")
                    },
                )
            }
        }

        if (source == ServicesSource.Local) {
            localServices(
                viewModel = viewModel,
                servers = servers,
                capabilitiesEnabled = capabilitiesEnabled,
                onOpenDiagnostics = onOpenDiagnostics,
                onOpenConnectionSettings = onOpenConnectionSettings,
                onAddServer = onAddServer,
                onOpenServer = onOpenServer,
                spacing = spacing,
            )
        } else {
            gatewayServices(
                gatewayViewModel = gatewayViewModel,
                gateway = gateway,
                onAddServer = onAddGatewayServer,
                onOpenServer = onOpenGatewayServer,
                spacing = spacing,
            )
        }
    }
}

private fun LazyListScope.localServices(
    viewModel: McpViewModel,
    servers: List<McpServerView>,
    capabilitiesEnabled: Boolean,
    onOpenDiagnostics: () -> Unit,
    onOpenConnectionSettings: () -> Unit,
    onAddServer: () -> Unit,
    onOpenServer: (String) -> Unit,
    spacing: HubSpacing,
) {
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
                    LocalServerRow(
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

private fun LazyListScope.gatewayServices(
    gatewayViewModel: GatewayViewModel,
    gateway: GatewayUiState,
    onAddServer: () -> Unit,
    onOpenServer: (String) -> Unit,
    spacing: HubSpacing,
) {
    item {
        if (!gateway.paired) {
            GatewayPairCard(gatewayViewModel = gatewayViewModel, gateway = gateway)
        } else {
            SectionCard(
                title = "云端服务",
                subtitle = gateway.client?.let { "${it.deviceName.ifBlank { it.deviceId }} · ${it.ownerId}" },
                trailing = {
                    StatusPill(
                        text = "${gateway.services.size} 个",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        showDot = false,
                    )
                },
                modifier = Modifier.padding(horizontal = spacing.screenHorizontal),
            ) {
                if (gateway.loading) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = spacing.md),
                        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator()
                        Text("正在从网关读取服务…", style = MaterialTheme.typography.bodyMedium)
                    }
                } else if (gateway.services.isEmpty()) {
                    EmptyState(
                        icon = Icons.AutoMirrored.Filled.List,
                        title = "云端还没有服务",
                        description = "添加后网关会去连这台 MCP 服务器、发现工具；" +
                            "工具仍需你逐个授权。",
                    )
                } else {
                    gateway.services.forEachIndexed { index, service ->
                        if (index > 0) HorizontalDivider()
                        GatewayServiceRow(
                            service = service,
                            busy = gateway.busyServiceId == service.id,
                            onOpen = { onOpenServer(service.id) },
                            onToggleEnabled = { enabled ->
                                gatewayViewModel.setEnabled(service.id, enabled)
                            },
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
                    Button(onClick = onAddServer, modifier = Modifier.weight(1f)) {
                        Icon(imageVector = Icons.Filled.Add, contentDescription = null)
                        Text("添加 MCP 服务器", modifier = Modifier.padding(start = spacing.xs))
                    }
                    OutlinedButton(onClick = { gatewayViewModel.refresh() }) { Text("刷新") }
                }
                OutlinedButton(
                    onClick = { gatewayViewModel.unpair() },
                    modifier = Modifier.padding(top = spacing.sm),
                ) { Text("解除配对") }
            }
        }
    }

    if (gateway.error != null) {
        item {
            SectionCard(
                title = "操作失败",
                modifier = Modifier.padding(horizontal = spacing.screenHorizontal),
            ) {
                Text(
                    text = gateway.error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = statusPalette.failed,
                )
                OutlinedButton(
                    onClick = { gatewayViewModel.clearError() },
                    modifier = Modifier.padding(top = spacing.sm),
                ) { Text("关闭") }
            }
        }
    }

    item {
        Text(
            text = "云端网关模式使用配对换来的受限令牌：它只能管理这台设备名下的服务，" +
                "不能创建别的客户端，也不会拿到全局管理员令牌。上游密钥只按引用名配置在服务端，" +
                "手机不保存、也读不到它的明文。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = spacing.screenHorizontal),
        )
    }
}

@Composable
private fun GatewayPairCard(gatewayViewModel: GatewayViewModel, gateway: GatewayUiState) {
    val spacing = MaterialTheme.spacing
    var base by rememberSaveable(gateway.baseUrl) { mutableStateOf(gateway.baseUrl) }
    var code by rememberSaveable { mutableStateOf("") }

    SectionCard(
        title = "连接云端网关",
        subtitle = "用一次性配对口令换取受限管理凭据",
        modifier = Modifier.padding(horizontal = spacing.screenHorizontal),
    ) {
        Text(
            text = "配对成功后，手机拿到两枚受限令牌：一枚只能管理这台设备自己的服务，" +
                "另一枚只能调用已授权的工具。全局管理员令牌不会下发给手机。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HubTextField(
            value = base,
            onValueChange = { base = it },
            label = "网关地址",
            placeholder = "https://<worker>.workers.dev",
            keyboardType = KeyboardType.Uri,
            supportingText = "只填根地址，不带路径。默认值取自 Hub 连接地址（或上一轮部署地址）。",
            modifier = Modifier.padding(top = spacing.md),
        )
        HubTextField(
            value = code,
            onValueChange = { code = it },
            label = "配对口令",
            placeholder = "由管理员在服务端设置",
            visualTransformation = PasswordVisualTransformation(),
            supportingText = "口令只用于这次配对；手机不会保存它。每次配对都会轮换此前的令牌。",
        )
        Button(
            onClick = { gatewayViewModel.pair(base, code) },
            enabled = !gateway.pairing,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = spacing.md),
        ) {
            if (gateway.pairing) {
                CircularProgressIndicator(modifier = Modifier.padding(end = spacing.sm))
                Text("正在配对…")
            } else {
                Text("连接并配对")
            }
        }
        if (gateway.error != null) {
            Text(
                text = gateway.error,
                style = MaterialTheme.typography.bodySmall,
                color = statusPalette.failed,
                modifier = Modifier.padding(top = spacing.sm),
            )
        }
    }
}

@Composable
private fun GatewayServiceRow(
    service: GatewayService,
    busy: Boolean,
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
                text = service.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${service.transport.label} · " +
                    McpUrlPolicy.hostOf(service.url).ifEmpty { service.url },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = gatewayStatusText(service),
                style = MaterialTheme.typography.bodySmall,
                color = if (service.lastError != null) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(top = spacing.xs),
            )
            OutlinedButton(
                onClick = onOpen,
                enabled = !busy,
                modifier = Modifier.padding(top = spacing.sm),
            ) { Text(if (busy) "处理中…" else "查看工具与授权") }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Switch(
                checked = service.enabled,
                onCheckedChange = onToggleEnabled,
                enabled = !busy,
            )
            Text(
                text = if (service.enabled) "已启用" else "已停用",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun gatewayStatusText(service: GatewayService): String {
    if (!service.enabled) return "已停用，模型看不到它的工具"
    val granted = service.tools.count { it.grant != null }
    val base = if (service.tools.isEmpty()) "尚未发现工具" else "${service.tools.size} 个工具 · 已授权 $granted 个"
    val failure = service.lastError?.let { " · 最近一次失败：${gatewayErrorText(it, 0)}" }.orEmpty()
    return base + failure
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

/** 一台本机服务器一行：名称、地址、最近一次的发现结果、启用开关。 */
@Composable
private fun LocalServerRow(
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
            // Hub 内部 ID：模型调用时填的 server_id。用户给服务器起的名字常常和
            // 服务器自报的名字长得一样，只有这一行是**永远不会变、也永远唯一**的那个。
            CopyableRow(label = "ID", value = view.config.id, labelWidth = 24.dp, maxLines = 1)
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
