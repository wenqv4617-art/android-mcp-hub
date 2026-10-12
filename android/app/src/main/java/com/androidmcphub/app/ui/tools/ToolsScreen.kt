package com.androidmcphub.app.ui.tools

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.androidmcphub.app.ui.components.EmptyState
import com.androidmcphub.app.ui.components.HubTextField
import com.androidmcphub.app.ui.components.PageHeader
import com.androidmcphub.app.ui.components.SectionCard
import com.androidmcphub.app.ui.components.StatusPill
import com.androidmcphub.app.ui.components.ToolCard
import com.androidmcphub.app.ui.model.ToolAvailability
import com.androidmcphub.app.ui.model.ToolCatalog
import com.androidmcphub.app.ui.model.ToolCategory
import com.androidmcphub.app.ui.model.filterTools
import com.androidmcphub.app.ui.theme.spacing

/**
 * 工具：本机能力目录（只读展示）。
 *
 * **数据来源是真实的**：列表由 `ToolCatalog` 提供，而 `ToolCatalog` 的 id/名字
 * 与 `capability/CapabilityDispatcher` 的注册表逐字对应（有 JVM 单测钉住）。
 * 这一页不发明工具、不发明参数，也不猜测服务端会下发什么。
 *
 * 「第三方 MCP 工具」「本地 HTTP MCP 工具」两个来源目前是**明确的未开放占位**：
 * 显示成空分组 + 「未开放」标签，而不是塞几张假卡片进去。
 */
@Composable
fun ToolsScreen(
    capabilitiesEnabled: Boolean,
    onOpenTool: (String) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var selectedCategory by rememberSaveable { mutableStateOf<String?>(null) }
    val category = selectedCategory?.let { name ->
        ToolCategory.entries.firstOrNull { it.name == name }
    }

    val spacing = MaterialTheme.spacing
    val tools = filterTools(ToolCatalog.local, query, category)
    val enabledCount = ToolCatalog.local.count {
        it.availability(capabilitiesEnabled) == ToolAvailability.Enabled
    }

    LazyColumn(
        contentPadding = PaddingValues(bottom = spacing.xxl),
        verticalArrangement = Arrangement.spacedBy(spacing.itemGap),
    ) {
        item {
            PageHeader(
                title = "工具",
                subtitle = "已授权的手机能力与外部 MCP 工具",
                modifier = Modifier.padding(horizontal = spacing.sm),
            )
        }

        item {
            HubTextField(
                value = query,
                onValueChange = { query = it },
                label = "搜索工具",
                placeholder = "按名称、说明或 ID 搜索…",
                modifier = Modifier.padding(horizontal = spacing.screenHorizontal),
            )
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = spacing.screenHorizontal),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = category == null,
                    onClick = { selectedCategory = null },
                    label = { Text("全部 (${ToolCatalog.local.size})") },
                )
                ToolCategory.entries.forEach { entry ->
                    val count = ToolCatalog.local.count { it.category == entry }
                    FilterChip(
                        selected = category == entry,
                        onClick = {
                            selectedCategory = if (category == entry) null else entry.name
                        },
                        label = { Text("${entry.label} ($count)") },
                        enabled = count > 0,
                    )
                }
            }
        }

        item {
            SectionCard(
                title = "本机能力（${tools.size}）",
                subtitle = "列表来自 App 内置的能力注册表，与 Worker 协议无关；" +
                    "手机连上后，服务端看到的工具名就是这里的 ID。",
                modifier = Modifier.padding(horizontal = spacing.screenHorizontal),
            ) {
                InfoLine(
                    "当前可用",
                    "$enabledCount / ${ToolCatalog.local.size}" +
                        if (capabilitiesEnabled) "（能力总开关已开启）" else "（能力总开关已关闭）",
                )
                InfoLine(
                    "本页说明",
                    "这是只读目录：这里不能执行工具，执行只发生在服务端请求到达手机时。",
                )
            }
        }

        if (tools.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Filled.Build,
                    title = "没有匹配的工具",
                    description = "试试清空搜索词，或切回「全部」分类。",
                    modifier = Modifier.padding(horizontal = spacing.screenHorizontal),
                )
            }
        } else {
            items(items = tools, key = { it.id }) { tool ->
                ToolCard(
                    tool = tool,
                    availability = tool.availability(capabilitiesEnabled),
                    onClick = { onOpenTool(tool.id) },
                    modifier = Modifier.padding(horizontal = spacing.screenHorizontal),
                )
            }
        }

        item {
            SectionCard(
                title = "其他来源",
                subtitle = "这些来源还没有接入，分组先占位 —— 不会显示任何假工具。",
                modifier = Modifier.padding(horizontal = spacing.screenHorizontal),
            ) {
                FutureSourceRow(
                    title = "第三方 MCP 工具",
                    description = "通过「服务」页接入第三方 MCP 服务器后，其工具会出现在这里。",
                )
                Spacer(Modifier.height(spacing.md))
                FutureSourceRow(
                    title = "本地 HTTP MCP 中转",
                    description = "把本机能力经本地 HTTP 中转暴露给其他 MCP 客户端（尚未实现）。",
                )
            }
        }

        item {
            SectionCard(
                title = "关于 phone_ping",
                modifier = Modifier.padding(horizontal = spacing.screenHorizontal),
            ) {
                Text(
                    text = "phone_ping 是连接诊断通道，不是本机能力：它由连接层处理，" +
                        "因此不出现在上面的工具列表里，也不受能力总开关影响。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    val spacing = MaterialTheme.spacing
    Row(modifier = Modifier.padding(vertical = spacing.xs)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = spacing.md),
        )
        Text(text = value, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun FutureSourceRow(title: String, description: String) {
    val spacing = MaterialTheme.spacing
    Row(verticalAlignment = Alignment.Top) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = title, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.width(spacing.sm))
                StatusPill(
                    text = "未开放",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    showDot = false,
                )
            }
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
