package com.androidmcphub.app.ui.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import com.androidmcphub.app.ui.components.EmptyState
import com.androidmcphub.app.ui.components.InfoRow
import com.androidmcphub.app.ui.components.PageHeader
import com.androidmcphub.app.ui.components.SectionCard
import com.androidmcphub.app.ui.components.StatusPill
import com.androidmcphub.app.ui.components.availabilityColor
import com.androidmcphub.app.ui.model.ToolArgument
import com.androidmcphub.app.ui.model.ToolAvailability
import com.androidmcphub.app.ui.model.ToolCatalog
import com.androidmcphub.app.ui.model.ToolDescriptor
import com.androidmcphub.app.ui.theme.spacing

/**
 * 工具详情：一个**统一的**模板，不给任何单个工具写专属分支。
 *
 * 页面上不出现"执行"按钮，这不是漏做：本机能力的执行入口是服务端通过
 * `phone-link-v1` 发来的工具请求，手机侧由 `capability/CapabilityDispatcher`
 * 的七步校验把关。在手机上放一个"点一下执行"的按钮会绕开那条链路，
 * 属于安全红线，本轮不做。
 */
@Composable
fun ToolDetailScreen(
    toolId: String,
    capabilitiesEnabled: Boolean,
    onBack: () -> Unit,
) {
    val tool = ToolCatalog.byId(toolId)
    val spacing = MaterialTheme.spacing

    LazyColumn(
        contentPadding = PaddingValues(bottom = spacing.xxl),
        verticalArrangement = Arrangement.spacedBy(spacing.itemGap),
    ) {
        item {
            PageHeader(
                title = tool?.name ?: "工具详情",
                subtitle = "工具详情",
                onBack = onBack,
                modifier = Modifier.padding(horizontal = spacing.sm),
                actions = {
                    if (tool != null) {
                        StatusPill(
                            text = tool.availability(capabilitiesEnabled).label,
                            color = availabilityColor(tool.availability(capabilitiesEnabled)),
                        )
                    }
                },
            )
        }

        if (tool == null) {
            item {
                EmptyState(
                    icon = Icons.Filled.Build,
                    title = "找不到这个工具",
                    description = "ID「$toolId」不在本机能力注册表里。" +
                        "它可能来自已断开的外部 MCP 服务，或者只是一个过期链接。",
                    modifier = Modifier.padding(horizontal = spacing.screenHorizontal),
                )
            }
            return@LazyColumn
        }

        item {
            SectionCard(
                title = "工具说明",
                modifier = Modifier.padding(horizontal = spacing.screenHorizontal),
            ) {
                Text(text = tool.description, style = MaterialTheme.typography.bodyMedium)
                if (tool.source == com.androidmcphub.app.ui.model.ToolSource.BuiltIn) {
                    Text(
                        text = "这个能力由 App 内置实现，只在手机上执行；服务端只负责转发请求与结果。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            SectionCard(
                title = "参数 Schema",
                subtitle = if (tool.arguments.isEmpty()) null else "服务端调用时必须满足的结构",
                modifier = Modifier.padding(horizontal = spacing.screenHorizontal),
            ) {
                if (tool.arguments.isEmpty()) {
                    Text(
                        text = "这个工具不接受任何参数。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    SchemaBlock(tool.arguments)
                }
            }
        }

        item {
            SectionCard(
                title = "来源与可用性",
                modifier = Modifier.padding(horizontal = spacing.screenHorizontal),
            ) {
                InfoRow(label = "工具 ID", value = tool.id, monospace = true)
                InfoRow(label = "来源", value = tool.source.label)
                InfoRow(label = "分类", value = tool.category.label)
                InfoRow(
                    label = "可用状态",
                    value = when (tool.availability(capabilitiesEnabled)) {
                        ToolAvailability.Enabled -> "已启用"
                        ToolAvailability.SwitchOff ->
                            "总开关已关闭 —— 现在调用会被回 not_permitted，" +
                                "且不执行任何能力"
                        ToolAvailability.NeedsPermission ->
                            "需要授权：${tool.permissionLabel ?: "未知权限"}"
                        ToolAvailability.ComingSoon -> "本版本尚未实现"
                    },
                )
                InfoRow(label = "读写", value = if (tool.readOnly) "只读，无副作用" else "有副作用")
                InfoRow(
                    label = "执行入口",
                    value = "服务端经 phone-link-v1 下发请求，手机侧执行",
                )
            }
        }

        item {
            SectionCard(
                title = "执行授权",
                subtitle = "为将来的敏感操作预留的说明",
                modifier = Modifier.padding(horizontal = spacing.screenHorizontal),
            ) {
                Text(
                    text = "只读能力会直接执行。将来接入有副作用的工具时，" +
                        "每次调用都需要你在手机上确认（授权窗口有超时，超时按拒绝处理）。" +
                        "本轮没有实现协议级授权状态机，所以这里没有" +
                        "「允许 / 拒绝」按钮 —— 不会给一个点了没用的按钮。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 把参数表渲染成一段类 JSON 文本（只呈现结构，不引入 JSON 依赖）。 */
@Composable
private fun SchemaBlock(arguments: List<ToolArgument>) {
    val spacing = MaterialTheme.spacing
    val text = buildString {
        appendLine("{")
        appendLine("  \"type\": \"object\",")
        appendLine("  \"properties\": {")
        arguments.forEachIndexed { index, arg ->
            appendLine("    \"${arg.name}\": {")
            appendLine("      \"type\": \"${arg.type}\",")
            appendLine("      \"description\": \"${arg.description}\"")
            append("    }${if (index == arguments.lastIndex) "" else ","}")
            appendLine()
        }
        appendLine("  },")
        val required = arguments.filter { it.required }
        if (required.isEmpty()) {
            appendLine("  \"required\": []")
        } else {
            appendLine("  \"required\": [${required.joinToString { "\"${it.name}\"" }}]")
        }
        append("}")
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.padding(top = spacing.xs),
    ) {
        Column(modifier = Modifier.padding(spacing.md)) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

/** 仅供将来的"工具步骤/调用链"页面复用的占位（当前没有调用方）。 */
@Composable
private fun ToolIdentityBlock(tool: ToolDescriptor) {
    Text(text = tool.name, style = MaterialTheme.typography.titleMedium)
}
