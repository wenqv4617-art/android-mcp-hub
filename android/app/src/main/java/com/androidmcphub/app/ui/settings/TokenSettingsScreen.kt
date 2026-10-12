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
import com.androidmcphub.app.ui.components.InfoRow
import com.androidmcphub.app.ui.components.PageHeader
import com.androidmcphub.app.ui.components.SectionCard
import com.androidmcphub.app.ui.components.TokenField
import com.androidmcphub.app.ui.theme.spacing

/**
 * 设备令牌管理（设置 → 连接设置 → 第三项）。
 *
 * 这一页只做三件事，安全行为与改版前**完全一致**：
 *  * 输入/粘贴令牌（默认打码）；
 *  * 临时明文查看（明文只活在 ViewModel 的内存里，不会被落盘）；
 *  * 清除已保存的令牌（清草稿 + 删掉磁盘密文，Keystore 密钥本身不动）。
 *
 * 页面只显示"有没有令牌"，从不显示令牌内容、长度或前后几位；
 * 令牌也永远不会进入日志或本文件里的任何提示文案。
 */
@Composable
fun TokenSettingsScreen(
    viewModel: HubViewModel,
    onBack: () -> Unit,
) {
    val spacing = MaterialTheme.spacing
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val tokenVisible by viewModel.tokenVisible.collectAsStateWithLifecycle()

    LazyColumn(
        contentPadding = PaddingValues(bottom = spacing.xxl),
        verticalArrangement = Arrangement.spacedBy(spacing.itemGap),
    ) {
        item {
            PageHeader(
                title = "设备令牌管理",
                subtitle = "加密存本机，只用于 Authorization 请求头",
                onBack = onBack,
            )
        }

        item {
            SectionCard(
                title = "设备令牌",
                modifier = Modifier.padding(horizontal = spacing.screenHorizontal),
            ) {
                InfoRow(
                    label = "当前状态",
                    value = if (draft.token.isBlank()) "尚未设置" else "已保存（加密存储）",
                )
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
            Column(modifier = Modifier.padding(horizontal = spacing.screenHorizontal)) {
                Button(
                    onClick = viewModel::save,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("保存令牌") }
                Text(
                    text = "保存会把本机配置一起写入（与设置里原来的「保存配置」是同一个动作）；" +
                        "「清除已保存的令牌」会立即删掉磁盘上的密文，不需要再点保存。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = spacing.sm),
                )
            }
        }
    }
}
