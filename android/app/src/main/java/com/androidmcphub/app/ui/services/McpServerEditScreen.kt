package com.androidmcphub.app.ui.services

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.androidmcphub.app.ui.components.HubTextField
import com.androidmcphub.app.ui.components.PageHeader
import com.androidmcphub.app.ui.components.SectionCard
import com.androidmcphub.app.ui.components.SettingSwitchRow
import com.androidmcphub.app.ui.mcp.McpHeaderDraft
import com.androidmcphub.app.ui.mcp.McpServerDraft
import com.androidmcphub.app.ui.mcp.McpViewModel
import com.androidmcphub.app.ui.theme.spacing

/**
 * 添加 / 编辑一台 MCP 服务器。
 *
 * 三条刻意的设计：
 *  * **密钥默认按密文处理**：新加的请求头默认勾上「加密保存」。猜错的代价不对称 ——
 *    把普通头当密钥只是白加密一次，把密钥当普通头存明文是实打实的泄漏。
 *  * 表单里显示真实密钥（否则"保存"会把 `<已设置>` 这种占位符存回去，等于悄悄毁掉配置），
 *    但用密码样式渲染，要看就点右边的眼睛。
 *  * 地址在**保存时**才做准入判定（[com.androidmcphub.app.mcp.McpUrlPolicy]）：
 *    明文 HTTP 只允许本机回环，地址里不许写用户名密码。原因直接显示在下面。
 */
@Composable
fun McpServerEditScreen(
    viewModel: McpViewModel,
    serverId: String?,
    onBack: () -> Unit,
    onSaved: () -> Unit,
) {
    val spacing = MaterialTheme.spacing
    var draft by remember(serverId) { mutableStateOf(viewModel.draftFor(serverId)) }

    LazyColumn(
        contentPadding = PaddingValues(bottom = spacing.xxl),
        verticalArrangement = Arrangement.spacedBy(spacing.itemGap),
    ) {
        item {
            PageHeader(
                title = if (draft.isNew) "添加 MCP 服务器" else "编辑 MCP 服务器",
                subtitle = "地址、请求头与启用状态",
                onBack = onBack,
            )
        }

        item {
            SectionCard(
                title = "服务器",
                modifier = Modifier.padding(horizontal = spacing.screenHorizontal),
            ) {
                HubTextField(
                    value = draft.name,
                    onValueChange = { value -> draft = draft.copy(name = value) },
                    label = "名称",
                    placeholder = "我的 MCP 服务器",
                    supportingText = "只用于在这台手机上区分不同服务器，会出现在模型看到的工具名前缀里。",
                )
                HubTextField(
                    value = draft.url,
                    onValueChange = { value -> draft = draft.copy(url = value) },
                    label = "地址",
                    placeholder = "https://example.com/mcp",
                    keyboardType = KeyboardType.Uri,
                    supportingText = "必须是完整地址。明文 http 只允许 127.0.0.1 / localhost / ::1（手机本机）。",
                )
                SettingSwitchRow(
                    title = "启用这台服务器",
                    description = "停用后模型看不到它的工具，也不会被调用；配置与授权记录都保留。",
                    checked = draft.enabled,
                    onCheckedChange = { value -> draft = draft.copy(enabled = value) },
                )
            }
        }

        item {
            SectionCard(
                title = "请求头",
                subtitle = "需要凭据的服务器通常要求 Authorization: Bearer …",
                modifier = Modifier.padding(horizontal = spacing.screenHorizontal),
            ) {
                draft.headers.forEachIndexed { index, header ->
                    HeaderEditor(
                        index = index,
                        header = header,
                        onChange = { updated ->
                            draft = draft.copy(
                                headers = draft.headers.toMutableList().also { it[index] = updated },
                            )
                        },
                        onRemove = {
                            draft = draft.copy(
                                headers = draft.headers.toMutableList().also { it.removeAt(index) },
                            )
                        },
                    )
                }

                Button(
                    onClick = { draft = draft.copy(headers = draft.headers + McpHeaderDraft()) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = spacing.sm),
                ) {
                    Icon(imageVector = Icons.Filled.Add, contentDescription = null)
                    Text("添加请求头", modifier = Modifier.padding(start = spacing.xs))
                }
                Text(
                    text = "名字不能是 Host / Content-Length / Mcp-Session-Id 这类由客户端自己管理的头，" +
                        "值里不能有换行。勾上「加密保存」的值会用系统 Keystore 加密后单独落盘。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = spacing.sm),
                )
            }
        }

        item {
            Column(modifier = Modifier.padding(horizontal = spacing.screenHorizontal)) {
                Button(
                    onClick = { if (viewModel.save(draft)) onSaved() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (draft.isNew) "添加" else "保存") }
                Text(
                    text = "保存只写本机配置。保存之后回到服务页点「刷新全部」才会去这台服务器上列工具。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = spacing.sm),
                )
            }
        }
    }
}

@Composable
private fun HeaderEditor(
    index: Int,
    header: McpHeaderDraft,
    onChange: (McpHeaderDraft) -> Unit,
    onRemove: () -> Unit,
) {
    val spacing = MaterialTheme.spacing
    var visible by remember(index) { mutableStateOf(false) }

    Column(modifier = Modifier.padding(top = spacing.md)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HubTextField(
                value = header.name,
                onValueChange = { value -> onChange(header.copy(name = value)) },
                label = "名称",
                placeholder = "Authorization",
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onRemove) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "删除这条请求头",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
        HubTextField(
            value = header.value,
            onValueChange = { value -> onChange(header.copy(value = value)) },
            label = "值",
            placeholder = "Bearer …",
            visualTransformation = if (header.secret && !visible) {
                PasswordVisualTransformation()
            } else {
                VisualTransformation.None
            },
            trailing = if (header.secret) {
                {
                    Text(
                        text = if (visible) "隐藏" else "显示",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .padding(end = spacing.sm)
                            .clickableText { visible = !visible },
                    )
                }
            } else {
                null
            },
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "加密保存",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = header.secret,
                onCheckedChange = { value -> onChange(header.copy(secret = value)) },
            )
        }
    }
}

/** 一小段可点的文字（`Modifier.clickable` 的极简包装，避免在槽位里写一长串）。 */
private fun Modifier.clickableText(onClick: () -> Unit): Modifier = this.clickable(onClick = onClick)
