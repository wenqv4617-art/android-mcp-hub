package com.androidmcphub.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidmcphub.app.protocol.LinkState
import com.androidmcphub.app.ui.HubViewModel
import com.androidmcphub.app.ui.components.NavRow
import com.androidmcphub.app.ui.components.PageHeader
import com.androidmcphub.app.ui.components.SectionCard
import com.androidmcphub.app.ui.model.ToolCatalog
import com.androidmcphub.app.ui.model.toStatusView
import com.androidmcphub.app.ui.theme.spacing
import kotlinx.coroutines.delay

/**
 * 首页：设备与连接状态的**轻量控制台**。
 *
 * Stage 2 的信息架构调整：
 *  * 只保留两个真正高频的快捷入口（工具目录、连接诊断）。服务与设置本来就在底部导航上，
 *    再列一遍只是把同一件事说两次；改版前那张「快捷操作」卡片正好是这个问题；
 *  * 服务器 URL、设备 ID、连接代次从首页移到「连接详情」，长地址不再撑开卡片；
 *  * 设置类字段（令牌、超时、重连参数）一律不在首页出现。
 *
 * 原有的连接能力一个没少：状态、连接/断开按钮、ping 摘要、最近错误、最近日志都还在这里。
 */
@Composable
fun HomeScreen(
    viewModel: HubViewModel,
    onOpenTools: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenConnectionDetail: () -> Unit,
) {
    val spacing = MaterialTheme.spacing
    val state by viewModel.state.collectAsStateWithLifecycle()
    val serviceRunning by viewModel.serviceRunning.collectAsStateWithLifecycle()
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val lastPing by viewModel.lastPing.collectAsStateWithLifecycle()
    val lastError by viewModel.lastError.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val capabilitiesEnabled by viewModel.capabilitiesEnabled.collectAsStateWithLifecycle()

    val view = state.toStatusView(serviceRunning)

    // 倒计时只在重连时走：其他状态下常驻一个 500ms 的心跳既没必要，
    // 也会让"首页在偷偷干活"变成事实。
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state) {
        nowMs = System.currentTimeMillis()
        while (state is LinkState.Reconnecting) {
            delay(500)
            nowMs = System.currentTimeMillis()
        }
    }

    LazyColumn(
        contentPadding = PaddingValues(bottom = spacing.xxl),
        verticalArrangement = Arrangement.spacedBy(spacing.itemGap),
    ) {
        item {
            PageHeader(
                title = "Android MCP Hub",
                subtitle = "让 AI 通过一个入口，访问你的手机与更多能力",
            )
        }

        item {
            ConnectionStatusCard(
                view = view,
                nowMs = nowMs,
                serverUrl = draft.serverUrl,
                serviceRunning = serviceRunning,
                canConnect = state is LinkState.Idle,
                onConnect = viewModel::connect,
                onDisconnect = viewModel::disconnect,
                onOpenDetail = onOpenConnectionDetail,
                modifier = Modifier.padding(horizontal = spacing.screenHorizontal),
            )
        }

        item {
            DeviceOverviewCard(
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
            SectionCard(
                title = "快捷入口",
                modifier = Modifier.padding(horizontal = spacing.screenHorizontal),
            ) {
                NavRow(
                    icon = Icons.Filled.Build,
                    title = "工具目录",
                    subtitle = "${ToolCatalog.local.size} 个本机能力，可搜索与筛选",
                    onClick = onOpenTools,
                )
                NavRow(
                    icon = Icons.Filled.Warning,
                    title = "连接诊断",
                    subtitle = "ping 明细与连接日志",
                    onClick = onOpenDiagnostics,
                    showDivider = false,
                )
            }
        }

        item {
            RecentActivityCard(
                logs = logs,
                onOpenDiagnostics = onOpenDiagnostics,
                modifier = Modifier.padding(horizontal = spacing.screenHorizontal),
            )
        }
    }
}
