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
import kotlinx.coroutines.delay

/**
 * 首页：Hub 当前的运行状态。
 *
 * 这一页的回答只有三个问题 —— 「连上了吗」「手机是谁」「最近一次心跳如何」。
 * 所有高级配置都搬到了设置页：首页不再放表单，避免用户为了看一眼状态
 * 先滚过十个输入框。
 */
@Composable
fun HomeScreen(
    viewModel: HubViewModel,
    onOpenTools: () -> Unit,
    onOpenServices: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val serviceRunning by viewModel.serviceRunning.collectAsStateWithLifecycle()
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val lastPing by viewModel.lastPing.collectAsStateWithLifecycle()
    val lastError by viewModel.lastError.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val capabilitiesEnabled by viewModel.capabilitiesEnabled.collectAsStateWithLifecycle()

    val view = state.toStatusView(serviceRunning)

    // 重连倒计时要每秒刷新，但**只**在重连状态下起定时器：
    // 其他状态用一个固定时刻即可，避免首页常驻一个 500ms 的循环。
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state) {
        nowMs = System.currentTimeMillis()
        while (state is LinkState.Reconnecting) {
            delay(500)
            nowMs = System.currentTimeMillis()
        }
    }

    val spacing = MaterialTheme.spacing

    LazyColumn(
        contentPadding = PaddingValues(bottom = spacing.xxl),
        verticalArrangement = Arrangement.spacedBy(spacing.itemGap),
    ) {
        item {
            PageHeader(
                title = "Android MCP Hub",
                subtitle = "让 AI 通过一个入口，访问你的手机与更多能力",
                modifier = Modifier.padding(horizontal = spacing.sm),
            )
        }
        item {
            ConnectionStatusCard(
                view = view,
                nowMs = nowMs,
                serverUrl = draft.serverUrl,
                deviceId = draft.deviceId,
                serviceRunning = serviceRunning,
                canConnect = state is LinkState.Idle,
                onConnect = viewModel::connect,
                onDisconnect = viewModel::disconnect,
                modifier = Modifier.padding(horizontal = spacing.screenHorizontal),
            )
        }
        item {
            DeviceOverviewCard(
                serviceRunning = serviceRunning,
                generation = view.generation,
                capabilitiesEnabled = capabilitiesEnabled,
                modifier = Modifier.padding(horizontal = spacing.screenHorizontal),
            )
        }
        item {
            PingSummaryCard(
                lastPing = lastPing,
                lastError = lastError,
                onOpenDiagnostics = onOpenDiagnostics,
                modifier = Modifier.padding(horizontal = spacing.screenHorizontal),
            )
        }
        item {
            QuickActionsCard(
                toolCount = ToolCatalog.local.size,
                onOpenTools = onOpenTools,
                onOpenServices = onOpenServices,
                onOpenDiagnostics = onOpenDiagnostics,
                onOpenSettings = onOpenSettings,
                modifier = Modifier.padding(horizontal = spacing.screenHorizontal),
            )
        }
        item {
            RecentActivityCard(
                logs = logs,
                onOpenDiagnostics = onOpenDiagnostics,
                modifier = Modifier.padding(horizontal = spacing.screenHorizontal),
            )
        }
        item {
            Text(
                text = "版本 ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = spacing.screenHorizontal),
            )
        }
    }
}

