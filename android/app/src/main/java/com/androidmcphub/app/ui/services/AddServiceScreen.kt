package com.androidmcphub.app.ui.services

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.androidmcphub.app.ui.components.HubTextField
import com.androidmcphub.app.ui.components.PageHeader
import com.androidmcphub.app.ui.components.SectionCard
import com.androidmcphub.app.ui.components.StatusPill
import com.androidmcphub.app.ui.theme.spacing

/**
 * 添加 MCP 服务器：**表单结构预览**。
 *
 * 这里没有任何"保存"逻辑，而且按钮点下去会明确说"没保存"。
 * 理由：本轮不实现 MCP 客户端，也就没有可信的凭据存储（令牌必须加密后才允许落盘，
 * 直接写明文属于安全红线）。给一个"保存成功"的假提示，
 * 比少做一个功能更糟 —— 用户会以为服务已经接上了。
 */
@Composable
fun AddServiceScreen(
    onBack: () -> Unit,
    onOpenTools: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var group by rememberSaveable { mutableStateOf("默认") }
    var url by rememberSaveable { mutableStateOf("") }
    var header by rememberSaveable { mutableStateOf("") }
    var noticeVisible by rememberSaveable { mutableStateOf(false) }
    val spacing = MaterialTheme.spacing

    LazyColumn(
        contentPadding = PaddingValues(bottom = spacing.xxl),
        verticalArrangement = Arrangement.spacedBy(spacing.itemGap),
    ) {
        item {
            PageHeader(
                title = "添加 MCP 服务器",
                subtitle = "结构预览",
                onBack = onBack,
                modifier = Modifier.padding(horizontal = spacing.sm),
                actions = {
                    StatusPill(
                        text = "未开放",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        showDot = false,
                    )
                },
            )
        }

        item {
            SectionCard(
                title = "基本信息",
                modifier = Modifier.padding(horizontal = spacing.screenHorizontal),
            ) {
                HubTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = "服务器名称",
                    placeholder = "例如：音叙 SoundTale",
                )
                HubTextField(
                    value = group,
                    onValueChange = { group = it },
                    label = "所属分组",
                    placeholder = "默认",
                    supportingText = "分组只影响以后的服务列表排版，不影响连接。",
                )
            }
        }

        item {
            SectionCard(
                title = "连接",
                modifier = Modifier.padding(horizontal = spacing.screenHorizontal),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "传输协议类型",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = "Streamable HTTP（JSON-RPC POST）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(spacing.sm))
                    StatusPill(
                        text = "未开放",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        showDot = false,
                    )
                }
                HubTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = "服务器 URL",
                    placeholder = "http://127.0.0.1:8765/mcp",
                    keyboardType = KeyboardType.Uri,
                    supportingText = "本版本不会请求这个地址。",
                )
                HubTextField(
                    value = header,
                    onValueChange = { header = it },
                    label = "请求头（可选）",
                    placeholder = "Authorization: Bearer …",
                    supportingText = "本版本不保存令牌或请求头（凭据加密存储尚未实现）。",
                )
            }
        }

        item {
            SectionCard(
                title = "本版本的行为",
                modifier = Modifier.padding(horizontal = spacing.screenHorizontal),
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        imageVector = Icons.Filled.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.width(24.dp),
                    )
                    Text(
                        text = "「保存并拉取工具」不会保存任何内容，也不会连接任何服务器：" +
                            "MCP 客户端（认证、工具发现、工具调用）还没有实现。" +
                            "表单里的文字只停留在当前页面，退出即丢弃。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            Column(modifier = Modifier.padding(horizontal = spacing.screenHorizontal)) {
                Button(
                    onClick = { noticeVisible = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("保存并拉取工具")
                }
                if (noticeVisible) {
                    Spacer(Modifier.height(spacing.sm))
                    Text(
                        text = "未开放：没有保存，也没有连接。" +
                            "在当前版本里，导入外部 MCP 工具还需要等接入功能落地。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(spacing.sm))
                androidx.compose.material3.TextButton(
                    onClick = onOpenTools,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("去看当前可用的工具")
                }
            }
        }
    }
}
