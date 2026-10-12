package com.androidmcphub.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidmcphub.app.ui.HubViewModel
import com.androidmcphub.app.ui.components.HubTextField
import com.androidmcphub.app.ui.components.NavRow
import com.androidmcphub.app.ui.components.PageHeader
import com.androidmcphub.app.ui.components.SectionCard
import com.androidmcphub.app.ui.theme.spacing

/**
 * 服务器与设备配置（设置 → 连接设置 → 第一项）。
 *
 * 只放"Hub 要连到哪里、以谁的身份连"：服务器 URL、设备 ID。
 * 令牌单独一页（[TokenSettingsScreen]），因为它有显示/隐藏与清除三个动作，
 * 混在表单里会让人误以为"点保存就等于保存令牌"。保存按钮仍然调用
 * [HubViewModel.save]，与设置主页原来那个按钮是同一个动作。
 */
@Composable
fun ConnectionSettingsScreen(
    viewModel: HubViewModel,
    onBack: () -> Unit,
    onOpenTokenSettings: () -> Unit,
) {
    val spacing = MaterialTheme.spacing
    val draft by viewModel.draft.collectAsStateWithLifecycle()

    LazyColumn(
        contentPadding = PaddingValues(bottom = spacing.xxl),
        verticalArrangement = Arrangement.spacedBy(spacing.itemGap),
    ) {
        item {
            PageHeader(
                title = "服务器与设备配置",
                subtitle = "Hub 主动连出去的那条 WebSocket",
                onBack = onBack,
            )
        }

        item {
            SectionCard(
                title = "连接配置",
                modifier = Modifier.padding(horizontal = spacing.screenHorizontal),
            ) {
                HubTextField(
                    value = draft.serverUrl,
                    onValueChange = { value -> viewModel.updateDraft { it.copy(serverUrl = value) } },
                    label = "服务器 URL",
                    placeholder = "wss://<host>/phone/ws",
                    keyboardType = KeyboardType.Uri,
                )
                HubTextField(
                    value = draft.deviceId,
                    onValueChange = { value -> viewModel.updateDraft { it.copy(deviceId = value) } },
                    label = "设备 ID",
                )
                NavRow(
                    icon = Icons.Filled.Lock,
                    title = "设备令牌",
                    subtitle = if (draft.token.isBlank()) {
                        "尚未设置 —— 连接前必须填写"
                    } else {
                        "已保存（用 Keystore 密钥加密）"
                    },
                    onClick = onOpenTokenSettings,
                    showDivider = false,
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
                    text = "保存只写本机配置，不会立即发起连接；连接与断开在首页。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = spacing.sm),
                )
            }
        }
    }
}
