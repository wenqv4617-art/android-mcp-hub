package com.androidmcphub.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidmcphub.app.protocol.LinkState
import com.androidmcphub.app.ui.HubViewModel
import com.androidmcphub.app.ui.components.InfoRow
import com.androidmcphub.app.ui.components.NavRow
import com.androidmcphub.app.ui.components.PageHeader
import com.androidmcphub.app.ui.components.SectionCard
import com.androidmcphub.app.ui.model.toStatusView
import com.androidmcphub.app.ui.theme.spacing
import com.androidmcphub.app.ui.theme.statusToneColor
import kotlinx.coroutines.delay

/**
 * 连接详情（首页 → 「连接详情」）。
 *
 * 存在的理由：首页不该被一条 60 字符的 `wss://…` 撑开，但用户排查连接问题时
 * 又确实需要看到完整地址、设备 ID 和连接代次。于是把它们放在这一页，
 * 首页只留"通不通"。
 *
 * 这一页**不触发任何连接动作**：连接/断开仍然只在首页。
 */
@Composable
fun ConnectionDetailScreen(
    viewModel: HubViewModel,
    onBack: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenConnectionSettings: () -> Unit,
) {
    val spacing = MaterialTheme.spacing
    val state by viewModel.state.collectAsStateWithLifecycle()
    val serviceRunning by viewModel.serviceRunning.collectAsStateWithLifecycle()
    val draft by viewModel.draft.collectAsStateWithLifecycle()

    val view = state.toStatusView(serviceRunning)
    val toneColor = statusToneColor(view.tone)

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
                title = "连接详情",
                subtitle = "当前连接状态与 Hub 侧参数",
                onBack = onBack,
            )
        }

        item {
            SectionCard(
                title = "状态",
                modifier = Modifier.padding(horizontal = spacing.screenHorizontal),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(toneColor, CircleShape),
                    )
                    Spacer(Modifier.size(spacing.sm))
                    Text(text = view.titleAt(nowMs), style = MaterialTheme.typography.titleMedium)
                }
                Text(
                    text = view.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                InfoRow(label = "前台服务", value = if (serviceRunning) "运行中" else "未运行")
                InfoRow(label = "连接代次", value = view.generation?.toString() ?: "—")
            }
        }

        item {
            SectionCard(
                title = "连接目标",
                subtitle = "完整地址只在这里和设置页出现，首页只显示主机。",
                modifier = Modifier.padding(horizontal = spacing.screenHorizontal),
            ) {
                InfoRow(
                    label = "服务器 URL",
                    value = draft.serverUrl.ifBlank { "（未填写）" },
                    monospace = true,
                    maxLines = 3,
                )
                InfoRow(
                    label = "设备 ID",
                    value = draft.deviceId.ifBlank { "（未填写）" },
                    monospace = true,
                )
                // 只显示"有没有令牌"，永远不显示令牌本身，也不显示长度或前后几位。
                InfoRow(
                    label = "设备令牌",
                    value = if (draft.token.isBlank()) "尚未设置" else "已保存（加密存储）",
                )
            }
        }

        item {
            SectionCard(
                title = "相关操作",
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
                    subtitle = "修改服务器 URL、设备 ID 与令牌",
                    onClick = onOpenConnectionSettings,
                    showDivider = false,
                )
            }
        }
    }
}
