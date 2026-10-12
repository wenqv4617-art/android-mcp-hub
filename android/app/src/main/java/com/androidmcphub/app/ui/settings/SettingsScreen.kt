package com.androidmcphub.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidmcphub.app.BuildConfig
import com.androidmcphub.app.ui.HubViewModel
import com.androidmcphub.app.ui.components.HubNumberField
import com.androidmcphub.app.ui.components.HubTextField
import com.androidmcphub.app.ui.components.PageHeader
import com.androidmcphub.app.ui.components.SectionCard
import com.androidmcphub.app.ui.components.SettingSwitchRow
import com.androidmcphub.app.ui.components.TokenField
import com.androidmcphub.app.ui.theme.spacing

/**
 * 设置：改版前首页里那张「高级设置」卡片的完整搬家。
 *
 * 十个配置字段一个不少，只是从"折叠面板"改成"按用途分组的卡片"，
 * 免得用户以为字段变少了。保存按钮放在所有卡片之后（配置 → 保存的自然顺序），
 * 连接状态不再出现在这一页 —— 那是首页的职责。
 */
@Composable
fun SettingsScreen(
    viewModel: HubViewModel,
    onOpenDiagnostics: () -> Unit,
) {
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val tokenVisible by viewModel.tokenVisible.collectAsStateWithLifecycle()
    val capabilitiesEnabled by viewModel.capabilitiesEnabled.collectAsStateWithLifecycle()

    val spacing = MaterialTheme.spacing

    LazyColumn(
        contentPadding = PaddingValues(bottom = spacing.xxl),
        verticalArrangement = Arrangement.spacedBy(spacing.itemGap),
    ) {
        item {
            PageHeader(
                title = "设置",
                subtitle = "连接配置、权限管理与应用设置",
                modifier = Modifier.padding(horizontal = spacing.sm),
            )
        }

        item {
            SectionCard(
                title = "连接配置",
                subtitle = "Hub 主动连出去的那条 WebSocket。",
                modifier = Modifier.padding(horizontal = spacing.screenHorizontal),
            ) {
                HubTextField(
                    value = draft.serverUrl,
                    onValueChange = { value -> viewModel.updateDraft { it.copy(serverUrl = value) } },
                    label = "服务器 URL",
                    placeholder = "wss://<host>/phone/ws",
                    keyboardType = KeyboardType.Uri,
                )
                Spacer(Modifier.height(spacing.md))
                HubTextField(
                    value = draft.deviceId,
                    onValueChange = { value -> viewModel.updateDraft { it.copy(deviceId = value) } },
                    label = "设备 ID",
                )
                Spacer(Modifier.height(spacing.md))
                TokenField(
                    value = draft.token,
                    onValueChange = { value -> viewModel.updateDraft { it.copy(token = value) } },
                    visible = tokenVisible,
                    onToggleVisible = viewModel::toggleTokenVisible,
                    onClear = viewModel::clearToken,
                    supportingText = "用 Android Keystore 里的密钥加密后存本机私有存储（不留明文），" +
                        "仅用于 Authorization 请求头。",
                )
            }
        }

        item {
            SectionCard(
                title = "超时与重连",
                subtitle = "保持默认值即可；改错值会在保存时提示。",
                modifier = Modifier.padding(horizontal = spacing.screenHorizontal),
            ) {
                HubNumberField(
                    label = "ping 超时（毫秒）",
                    value = draft.pingTimeoutMs,
                    onValueChange = { value -> viewModel.updateDraft { it.copy(pingTimeoutMs = value) } },
                )
                Spacer(Modifier.height(spacing.md))
                HubNumberField(
                    label = "心跳周期（毫秒）",
                    value = draft.heartbeatIntervalMs,
                    onValueChange = { value -> viewModel.updateDraft { it.copy(heartbeatIntervalMs = value) } },
                )
                Spacer(Modifier.height(spacing.md))
                HubNumberField(
                    label = "初始重连间隔（毫秒）",
                    value = draft.reconnectInitialDelayMs,
                    onValueChange = { value ->
                        viewModel.updateDraft { it.copy(reconnectInitialDelayMs = value) }
                    },
                )
                Spacer(Modifier.height(spacing.md))
                HubNumberField(
                    label = "最大重连间隔（毫秒）",
                    value = draft.reconnectMaxDelayMs,
                    onValueChange = { value ->
                        viewModel.updateDraft { it.copy(reconnectMaxDelayMs = value) }
                    },
                )
                Spacer(Modifier.height(spacing.md))
                HubNumberField(
                    label = "抖动比例（0~1）",
                    value = draft.reconnectJitterRatio,
                    onValueChange = { value ->
                        viewModel.updateDraft { it.copy(reconnectJitterRatio = value) }
                    },
                    decimal = true,
                )
                Spacer(Modifier.height(spacing.md))
                HubNumberField(
                    label = "最大挂起请求数",
                    value = draft.maxPendingRequests,
                    onValueChange = { value ->
                        viewModel.updateDraft { it.copy(maxPendingRequests = value) }
                    },
                )
                Spacer(Modifier.height(spacing.md))
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
            SectionCard(
                title = "能力授权",
                modifier = Modifier.padding(horizontal = spacing.screenHorizontal),
            ) {
                SettingSwitchRow(
                    title = "允许服务端调用本机只读能力",
                    description = "关掉之后，手机对任何工具请求都回 not_permitted，且不执行任何能力。" +
                        "当前已启用的能力只有 battery_get_status（电池状态，只读、不需要任何权限）。" +
                        "开关立即生效，不需要重新连接。首次安装默认关闭（需要你在这里主动打开），" +
                        "以及 phone_ping 这类连通性探测不受这个开关影响。",
                    checked = capabilitiesEnabled,
                    onCheckedChange = viewModel::setCapabilitiesEnabled,
                )
            }
        }

        item {
            Button(
                onClick = viewModel::save,
                modifier = Modifier
                    .padding(horizontal = spacing.screenHorizontal)
                    .fillMaxWidth(),
            ) { Text("保存配置") }
        }

        item {
            SectionCard(
                title = "诊断",
                modifier = Modifier.padding(horizontal = spacing.screenHorizontal),
            ) {
                SettingRow(
                    icon = Icons.Filled.Warning,
                    title = "连接诊断",
                    subtitle = "ping 明细与连接日志",
                    onClick = onOpenDiagnostics,
                )
                SettingRow(
                    icon = Icons.Filled.Delete,
                    title = "清空日志",
                    subtitle = "只清内存里的环形日志，不影响连接",
                    onClick = viewModel::clearLogs,
                )
                SettingRow(
                    icon = Icons.Filled.Build,
                    title = "清除最近一次错误",
                    subtitle = "错误提示会留在首页直到手动清除",
                    onClick = viewModel::clearLastError,
                    showDivider = false,
                )
            }
        }

        item {
            SectionCard(
                title = "关于",
                modifier = Modifier.padding(horizontal = spacing.screenHorizontal),
            ) {
                Text(
                    text = "Android MCP Hub ${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "本应用只在本机执行用户已授权的只读能力；" +
                        "不发送短信、不读取通讯录、不修改系统设置。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = spacing.xs),
                )
            }
        }
    }
}

@Composable
private fun SettingRow(
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
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        }
    }
}
