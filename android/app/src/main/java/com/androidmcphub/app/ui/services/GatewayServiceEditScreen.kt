package com.androidmcphub.app.ui.services

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import com.androidmcphub.app.gateway.GatewayService
import com.androidmcphub.app.gateway.GatewayServiceDraft
import com.androidmcphub.app.gateway.GatewayTransport
import com.androidmcphub.app.ui.components.HubTextField
import com.androidmcphub.app.ui.components.PageHeader
import com.androidmcphub.app.ui.components.SectionCard
import com.androidmcphub.app.ui.components.SettingSwitchRow
import com.androidmcphub.app.ui.gateway.GatewayViewModel
import com.androidmcphub.app.ui.theme.spacing

/**
 * 添加 / 编辑云端网关里的一台 MCP 服务器。
 *
 * 表单上没有任何地方能输入上游明文密钥：网关只接受 `secretRef`（服务端
 * `GATEWAY_UPSTREAM_SECRETS` 里的引用名）。这是刻意的 —— 手机保存上游密钥
 * 意味着它必须能被手机读回来，而没有任何界面需要读回它。
 */
@Composable
fun GatewayServiceEditScreen(
    viewModel: GatewayViewModel,
    service: GatewayService?,
    onBack: () -> Unit,
    onSaved: () -> Unit,
) {
    val spacing = MaterialTheme.spacing
    var draft by remember(service?.id) {
        mutableStateOf(
            if (service == null) {
                GatewayServiceDraft()
            } else {
                GatewayServiceDraft(
                    id = service.id,
                    name = service.name,
                    description = service.description,
                    url = service.url,
                    transport = service.transport,
                    enabled = service.enabled,
                    secretRef = service.secretRef.orEmpty(),
                )
            },
        )
    }
    var usesSecret by remember(service?.id) {
        mutableStateOf(!service?.secretRef.isNullOrBlank())
    }

    LazyColumn(
        contentPadding = PaddingValues(bottom = spacing.xxl),
        verticalArrangement = Arrangement.spacedBy(spacing.itemGap),
    ) {
        item {
            PageHeader(
                title = if (draft.isNew) "添加云端 MCP 服务器" else "编辑云端 MCP 服务器",
                subtitle = "服务注册在网关，手机只持有受限管理令牌",
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
                    onValueChange = { draft = draft.copy(name = it) },
                    label = "名称",
                    placeholder = "我的 MCP 服务器",
                    supportingText = "最多 80 个字符，显示在服务列表与工具名前缀里。",
                )
                HubTextField(
                    value = draft.url,
                    onValueChange = { draft = draft.copy(url = it) },
                    label = "服务器地址",
                    placeholder = "https://example.com/mcp",
                    keyboardType = KeyboardType.Uri,
                    supportingText = "必须是网关允许清单里的 https 地址。只支持 Streamable HTTP 与旧版 SSE。",
                )
                HubTextField(
                    value = draft.description,
                    onValueChange = { draft = draft.copy(description = it) },
                    label = "说明（可选）",
                    placeholder = "这台服务器提供什么",
                    supportingText = "只用于管理界面，不会成为工具说明。",
                )
                SettingSwitchRow(
                    title = "启用这台服务器",
                    description = "停用后它的工具不会出现在 /mcp 工具列表里，也无法被调用。",
                    checked = draft.enabled,
                    onCheckedChange = { draft = draft.copy(enabled = it) },
                )
            }
        }

        item {
            SectionCard(
                title = "协议",
                subtitle = "自动检测会先试 Streamable HTTP，再试旧版 SSE",
                modifier = Modifier.padding(horizontal = spacing.screenHorizontal),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    GatewayTransport.entries.forEach { transport ->
                        FilterChip(
                            selected = draft.transport == transport,
                            onClick = { draft = draft.copy(transport = transport) },
                            label = { Text(transport.label) },
                        )
                    }
                }
                Text(
                    text = "自动检测只在保存时做一次：两种传输都失败时，服务仍会保存，但会显示最近一次失败原因。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = spacing.sm),
                )
            }
        }

        item {
            SectionCard(
                title = "上游认证",
                subtitle = "网关替代手机去访问上游，手机不需要保存上游密钥",
                modifier = Modifier.padding(horizontal = spacing.screenHorizontal),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    FilterChip(
                        selected = !usesSecret,
                        onClick = {
                            usesSecret = false
                            draft = draft.copy(secretRef = "")
                        },
                        label = { Text("无认证") },
                    )
                    FilterChip(
                        selected = usesSecret,
                        onClick = { usesSecret = true },
                        label = { Text("服务端密钥引用") },
                    )
                }
                if (usesSecret) {
                    HubTextField(
                        value = draft.secretRef,
                        onValueChange = { draft = draft.copy(secretRef = it) },
                        label = "密钥引用 (secretRef)",
                        placeholder = "my-service",
                        supportingText = "对应服务端 GATEWAY_UPSTREAM_SECRETS 里的 <ownerId>/<secretRef>。" +
                            "手机只保存这个引用名，不保存、也读不到密钥本身。",
                        modifier = Modifier.padding(top = spacing.md),
                    )
                }
            }
        }

        item {
            androidx.compose.foundation.layout.Column(
                modifier = Modifier.padding(horizontal = spacing.screenHorizontal),
            ) {
                Button(
                    onClick = {
                        viewModel.save(draft)
                        onSaved()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (draft.isNew) "保存并连接测试" else "保存修改") }
                if (!draft.isNew) {
                    Text(
                        text = "修改会生成新的服务修订并清空该服务此前的工具授权；需要重新发现并授权。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = spacing.sm),
                    )
                }
                OutlinedButton(
                    onClick = onBack,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = spacing.sm),
                ) { Text("取消") }
            }
        }
    }
}
