package com.androidmcphub.app.ui.home

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidmcphub.app.BuildConfig
import com.androidmcphub.app.protocol.LinkState
import com.androidmcphub.app.protocol.PingOutcome
import com.androidmcphub.app.ui.HubViewModel
import com.androidmcphub.app.ui.components.InfoRow
import com.androidmcphub.app.ui.components.LogRow
import com.androidmcphub.app.ui.components.PageHeader
import com.androidmcphub.app.ui.components.SectionCard
import com.androidmcphub.app.ui.components.StatusPill
import com.androidmcphub.app.ui.components.formatTime
import com.androidmcphub.app.ui.model.ToolCatalog
import com.androidmcphub.app.ui.model.toStatusView
import com.androidmcphub.app.ui.theme.spacing
import com.androidmcphub.app.ui.theme.statusToneColor

@Composable
internal fun ConnectionStatusCard(
    view: com.androidmcphub.app.ui.model.LinkStatusView,
    nowMs: Long,
    serverUrl: String,
    deviceId: String,
    serviceRunning: Boolean,
    canConnect: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = MaterialTheme.spacing
    val toneColor = statusToneColor(view.tone)

    SectionCard(title = "连接状态", modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(toneColor, CircleShape),
            )
            Spacer(Modifier.size(spacing.sm))
            Column {
                Text(text = view.titleAt(nowMs), style = MaterialTheme.typography.titleMedium)
                Text(
                    text = view.detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(spacing.md))
        InfoRow(label = "服务器", value = serverUrl.ifBlank { "（未填写）" }, monospace = true)
        InfoRow(label = "设备 ID", value = deviceId.ifBlank { "（未填写）" }, monospace = true)
        InfoRow(label = "前台服务", value = if (serviceRunning) "运行中" else "未运行")
        InfoRow(
            label = "连接代次",
            value = view.generation?.toString() ?: "—",
        )

        Spacer(Modifier.height(spacing.lg))
        // 连接与断开互斥：Idle 之外的状态一律给"断开"，
        // 避免用户在"连接中"时连点出一个并发建连。
        if (canConnect) {
            Button(onClick = onConnect, modifier = Modifier.fillMaxWidth()) { Text("连接") }
        } else {
            OutlinedButton(
                onClick = onDisconnect,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("断开") }
        }
    }
}

@Composable
internal fun DeviceOverviewCard(
    serviceRunning: Boolean,
    generation: Long?,
    capabilitiesEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val spacing = MaterialTheme.spacing
    val manufacturer = Build.MANUFACTURER.orEmpty().ifBlank { "Android" }
    val model = Build.MODEL.orEmpty().ifBlank { "未知型号" }

    SectionCard(
        title = "设备概况",
        subtitle = "以下信息全部来自本机，未经网络请求。",
        modifier = modifier,
    ) {
        // 2×3 网格：每格一行标签 + 一行值。没有数据源的指标（电量、温度、
        // 存储、网络制式）一律不显示 —— 宁可少一格，也不放占位数字。
        Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.md)) {
                OverviewTile("设备型号", "$manufacturer $model", Modifier.weight(1f))
                OverviewTile(
                    "系统版本",
                    "Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）",
                    Modifier.weight(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.md)) {
                OverviewTile("前台服务", if (serviceRunning) "运行中" else "未运行", Modifier.weight(1f))
                OverviewTile("连接代次", generation?.toString() ?: "—", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.md)) {
                OverviewTile("本机工具", "${ToolCatalog.local.size} 个已注册", Modifier.weight(1f))
                OverviewTile(
                    "能力总开关",
                    if (capabilitiesEnabled) "已开启" else "已关闭",
                    Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
internal fun OverviewTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun PingSummaryCard(
    lastPing: PingOutcome?,
    lastError: String?,
    onOpenDiagnostics: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = MaterialTheme.spacing

    SectionCard(
        title = "最近一次 ping",
        trailing = { TextButton(onClick = onOpenDiagnostics) { Text("连接诊断") } },
        modifier = modifier,
    ) {
        if (lastPing == null) {
            Text(
                text = "还没有收到 phone_ping。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            InfoRow(label = "RTT（估算）", value = "${lastPing.rttMs} ms")
            InfoRow(label = "单向延迟（估算）", value = "${lastPing.oneWayMs} ms")
            InfoRow(label = "本机收到时刻", value = formatTime(lastPing.receivedAtMs))
        }

        // 「最近一次错误」必须独立于当前状态：断开或服务销毁会把状态复位成
        // 「已断开」，但失败原因要留到用户看到为止。
        if (lastError != null) {
            Spacer(Modifier.height(spacing.md))
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.size(spacing.sm))
                Text(
                    text = lastError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
internal fun QuickActionsCard(
    toolCount: Int,
    onOpenTools: () -> Unit,
    onOpenServices: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(title = "快捷操作", modifier = modifier) {
        ActionRow(
            icon = Icons.Filled.Build,
            title = "工具目录",
            subtitle = "$toolCount 个本机能力",
            onClick = onOpenTools,
        )
        ActionRow(
            icon = Icons.AutoMirrored.Filled.List,
            title = "服务管理",
            subtitle = "第三方 MCP 服务器（尚未接入）",
            onClick = onOpenServices,
        )
        ActionRow(
            icon = Icons.Filled.Warning,
            title = "连接诊断",
            subtitle = "ping 明细与连接日志",
            onClick = onOpenDiagnostics,
        )
        ActionRow(
            icon = Icons.Filled.Settings,
            title = "设置",
            subtitle = "服务器、令牌、超时与重连",
            onClick = onOpenSettings,
            showDivider = false,
        )
    }
}

@Composable
internal fun ActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    showDivider: Boolean = true,
) {
    val spacing = MaterialTheme.spacing
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = spacing.md),
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
                Text(text = title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (showDivider) {
            androidx.compose.material3.HorizontalDivider(
                color = MaterialTheme.colorScheme.surfaceVariant,
            )
        }
    }
}

@Composable
internal fun RecentActivityCard(
    logs: List<com.androidmcphub.app.core.LogEntry>,
    onOpenDiagnostics: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = MaterialTheme.spacing
    val recent = logs.take(3)

    SectionCard(
        title = "最近活动",
        subtitle = if (logs.isEmpty()) null else "共 ${logs.size} 条（上限 200）",
        trailing = { TextButton(onClick = onOpenDiagnostics) { Text("全部") } },
        modifier = modifier,
    ) {
        if (recent.isEmpty()) {
            Text(
                text = "还没有日志。连接一次之后这里会出现心跳记录。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                recent.forEach { LogRow(it) }
            }
        }
    }
}
