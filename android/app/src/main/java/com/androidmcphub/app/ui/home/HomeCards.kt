package com.androidmcphub.app.ui.home

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.androidmcphub.app.core.LogEntry
import com.androidmcphub.app.protocol.PingOutcome
import com.androidmcphub.app.ui.components.InfoRow
import com.androidmcphub.app.ui.components.LogRow
import com.androidmcphub.app.ui.components.SectionCard
import com.androidmcphub.app.ui.components.formatTime
import com.androidmcphub.app.ui.model.LinkStatusView
import com.androidmcphub.app.ui.model.serverHostLabel
import com.androidmcphub.app.ui.model.ToolCatalog
import com.androidmcphub.app.ui.theme.spacing
import com.androidmcphub.app.ui.theme.statusToneColor

/**
 * 首页的连接状态卡。
 *
 * Stage 2 的调整：不再在这张卡里铺开服务器 URL、设备 ID 和连接代次 ——
 * 一个 60 多字符的 `wss://…` 地址会把卡片撑成三行。首页只回答
 * "现在通不通 + 服务器是谁"，细节交给「连接详情」二级页。
 */
@Composable
internal fun ConnectionStatusCard(
    view: LinkStatusView,
    nowMs: Long,
    serverUrl: String,
    serviceRunning: Boolean,
    canConnect: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenDetail: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = MaterialTheme.spacing
    val toneColor = statusToneColor(view.tone)

    SectionCard(
        title = "连接状态",
        trailing = { TextButton(onClick = onOpenDetail) { Text("连接详情") } },
        modifier = modifier,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(toneColor, CircleShape),
            )
            Spacer(Modifier.size(spacing.sm))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = view.titleAt(nowMs), style = MaterialTheme.typography.titleMedium)
                Text(
                    text = view.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        InfoRow(label = "服务器", value = serverHostLabel(serverUrl), monospace = true, maxLines = 1)
        InfoRow(label = "前台服务", value = if (serviceRunning) "运行中" else "未运行")

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

/**
 * 设备概况：全部取自本机，没有任何网络请求。
 *
 * 只显示**有真实数据来源**的指标。改版前这里还有"前台服务""连接代次"两格，
 * 它们和连接状态卡重复，已经在 Stage 2 去掉了 —— 首页的目的是扫一眼，不是列清单。
 */
@Composable
internal fun DeviceOverviewCard(
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
                OverviewTile("本机能力", "${ToolCatalog.local.size} 个已注册", Modifier.weight(1f))
                OverviewTile(
                    "能力授权",
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
internal fun RecentActivityCard(
    logs: List<LogEntry>,
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
