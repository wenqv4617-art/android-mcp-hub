package com.androidmcphub.app.ui.tools

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.androidmcphub.app.ui.components.EmptyState
import com.androidmcphub.app.ui.components.HubTextField
import com.androidmcphub.app.ui.components.PageHeader
import com.androidmcphub.app.ui.components.StatusPill
import com.androidmcphub.app.ui.components.ToolCard
import com.androidmcphub.app.ui.model.ToolAvailability
import com.androidmcphub.app.ui.model.ToolCatalog
import com.androidmcphub.app.ui.model.ToolCategory
import com.androidmcphub.app.ui.model.filterTools
import com.androidmcphub.app.ui.theme.spacing
import com.androidmcphub.app.ui.theme.statusPalette

/**
 * 工具：本机能力目录（只读展示）。
 *
 * **数据来源是真实的**：列表由 `ToolCatalog` 提供，而 `ToolCatalog` 的 id/名字
 * 与 `capability/CapabilityDispatcher` 的注册表逐字对应（有 JVM 单测钉住）。
 * 这一页不发明工具、不发明参数，也不猜测服务端会下发什么。
 *
 * Stage 2 的信息架构调整：把"来源说明""phone_ping 为什么不在列表里""本机注册表
 * 与 Worker 协议的关系"这类开发向的段落从列表页搬到了 [ToolsHelpScreen]。
 * 工具页的第一屏应该是工具本身，不是技术文档。
 */
@Composable
fun ToolsScreen(
    capabilitiesEnabled: Boolean,
    onOpenTool: (String) -> Unit,
    onOpenHelp: () -> Unit,
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
                subtitle = "本机能力与外部 MCP 工具的统一目录",
                actions = { TextButton(onClick = onOpenHelp) { Text("说明") } },
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
                // 只画目录里真的出现了的分类（见 ToolCatalog.categoriesInUse）。
                ToolCatalog.categoriesInUse().forEach { entry ->
                    val count = ToolCatalog.local.count { it.category == entry }
                    FilterChip(
                        selected = category == entry,
                        onClick = {
                            selectedCategory = if (category == entry) null else entry.name
                        },
                        label = { Text("${entry.label} ($count)") },
                    )
                }
            }
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.screenHorizontal),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "本机能力 ${ToolCatalog.local.size} 个 · 当前可用 $enabledCount 个",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                StatusPill(
                    text = if (capabilitiesEnabled) "授权已开启" else "授权已关闭",
                    color = if (capabilitiesEnabled) {
                        statusPalette.connected
                    } else {
                        statusPalette.idle
                    },
                    showDot = true,
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
    }
}
