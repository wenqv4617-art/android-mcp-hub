package com.androidmcphub.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidmcphub.app.ui.HubViewModel
import com.androidmcphub.app.ui.components.HubNumberField
import com.androidmcphub.app.ui.components.PageHeader
import com.androidmcphub.app.ui.components.SectionCard
import com.androidmcphub.app.ui.theme.spacing

/**
 * 高级连接参数（设置 → 连接设置 → 第二项）。
 *
 * 这七个字段一个不少地从改版前的「高级设置」折叠面板搬过来：
 * ping 超时、心跳周期、初始/最大重连间隔、抖动比例、最大挂起请求数、单帧字节上限。
 *
 * 字段值仍然是**字符串**（草稿模型 [com.androidmcphub.app.core.HubDraft] 的口径），
 * 非法值在保存/连接时回落到默认值 —— 这一点没变，不要在 UI 层提前解析。
 */
@Composable
fun AdvancedSettingsScreen(
    viewModel: HubViewModel,
    onBack: () -> Unit,
) {
    val spacing = MaterialTheme.spacing
    val draft by viewModel.draft.collectAsStateWithLifecycle()

    LazyColumn(
        contentPadding = PaddingValues(bottom = spacing.xxl),
        verticalArrangement = Arrangement.spacedBy(spacing.itemGap),
    ) {
        item {
            PageHeader(
                title = "高级连接参数",
                subtitle = "保持默认值即可；改错值会在保存时提示",
                onBack = onBack,
            )
        }

        item {
            SectionCard(
                title = "超时与重连",
                subtitle = "单位均为毫秒（抖动比例除外）。",
                modifier = Modifier.padding(horizontal = spacing.screenHorizontal),
            ) {
                // 字段之间不再手动插 Spacer：卡片内容列已经统一 spacedBy(spacing.sm)。
                HubNumberField(
                    label = "ping 超时（毫秒）",
                    value = draft.pingTimeoutMs,
                    onValueChange = { value -> viewModel.updateDraft { it.copy(pingTimeoutMs = value) } },
                )
                HubNumberField(
                    label = "心跳周期（毫秒）",
                    value = draft.heartbeatIntervalMs,
                    onValueChange = { value -> viewModel.updateDraft { it.copy(heartbeatIntervalMs = value) } },
                )
                HubNumberField(
                    label = "初始重连间隔（毫秒）",
                    value = draft.reconnectInitialDelayMs,
                    onValueChange = { value ->
                        viewModel.updateDraft { it.copy(reconnectInitialDelayMs = value) }
                    },
                )
                HubNumberField(
                    label = "最大重连间隔（毫秒）",
                    value = draft.reconnectMaxDelayMs,
                    onValueChange = { value ->
                        viewModel.updateDraft { it.copy(reconnectMaxDelayMs = value) }
                    },
                )
                HubNumberField(
                    label = "抖动比例（0~1）",
                    value = draft.reconnectJitterRatio,
                    onValueChange = { value ->
                        viewModel.updateDraft { it.copy(reconnectJitterRatio = value) }
                    },
                    decimal = true,
                )
                HubNumberField(
                    label = "最大挂起请求数",
                    value = draft.maxPendingRequests,
                    onValueChange = { value ->
                        viewModel.updateDraft { it.copy(maxPendingRequests = value) }
                    },
                )
                HubNumberField(
                    label = "单帧字节上限",
                    value = draft.maxFrameBytes,
                    onValueChange = { value ->
                        viewModel.updateDraft { it.copy(maxFrameBytes = value) }
                    },
                )
            }
        }

        item {
            Column(modifier = Modifier.padding(horizontal = spacing.screenHorizontal)) {
                Button(
                    onClick = viewModel::save,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("保存配置") }
                Text(
                    text = "改完不保存也不影响当前连接：这些值在下次建连时才读。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = spacing.sm),
                )
            }
        }
    }
}
