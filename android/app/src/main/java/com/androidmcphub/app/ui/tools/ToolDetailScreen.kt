package com.androidmcphub.app.ui.tools

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.androidmcphub.app.ui.components.EmptyState
import com.androidmcphub.app.ui.components.InfoRow
import com.androidmcphub.app.ui.components.PageHeader
import com.androidmcphub.app.ui.components.SectionCard
import com.androidmcphub.app.ui.components.SettingSwitchRow
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
 * 的七步校验把关。这里能改的只有"授权"：
 *
 *  只读工具      → 总开关 + 这一条的开关
 *  写入工具      → 总开关 + 写入总开关 + 这一条的开关（写入类默认关）
 *  删除/覆盖工具 → 上面三道 + 调用方每次必须传 `confirm=true`
 *  系统权限      → 在这里看到真实状态，并能跳到对应的授权入口
 *
 * 权限状态每次都真的去系统查（见 [ToolPermissions]），不在界面里猜。
 */
@Composable
fun ToolDetailScreen(
    toolId: String,
    capabilitiesEnabled: Boolean,
    writeCapabilitiesEnabled: Boolean,
    disabledTools: Set<String>,
    enabledWriteTools: Set<String>,
    onToolEnabledChange: (String, Boolean) -> Unit,
    onWriteToolEnabledChange: (String, Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    onBack: () -> Unit,
) {
    val tool = ToolCatalog.byId(toolId)
    val spacing = MaterialTheme.spacing
    val context = LocalContext.current

    // 权限状态在"回到前台"时刷新：用户可能刚从系统设置里授权回来。
    var permissionGranted by remember(tool?.name) {
        mutableStateOf(tool == null || !tool.requiresPermission || ToolPermissions.isGranted(context, tool))
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, tool) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && tool != null) {
                permissionGranted = !tool.requiresPermission || ToolPermissions.isGranted(context, tool)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        if (tool != null) permissionGranted = ToolPermissions.isGranted(context, tool)
    }

    LazyColumn(
        contentPadding = PaddingValues(bottom = spacing.xxl),
        verticalArrangement = Arrangement.spacedBy(spacing.itemGap),
    ) {
        item {
            PageHeader(
                title = tool?.name ?: "工具详情",
                subtitle = "本机能力",
                onBack = onBack,
                actions = {
                    if (tool != null) {
                        val availability = tool.availability(
                            capabilitiesEnabled = capabilitiesEnabled,
                            writeCapabilitiesEnabled = writeCapabilitiesEnabled,
                            toolEnabled = tool.isEnabledByUser(disabledTools, enabledWriteTools),
                            permissionGranted = if (tool.requiresPermission) permissionGranted else null,
                        )
                        StatusPill(text = availability.label, color = availabilityColor(availability))
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
                Text(
                    text = "这个能力由 App 内置实现，只在手机上执行；服务端只负责转发请求与结果。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            SectionCard(
                title = "执行授权",
                subtitle = if (tool.readOnly) {
                    "只读工具，打开后收到请求即执行"
                } else {
                    "会修改数据；需要额外的写入授权"
                },
                modifier = Modifier.padding(horizontal = spacing.screenHorizontal),
            ) {
                if (!capabilitiesEnabled) {
                    Text(
                        text = "能力总开关已关闭。下面的单项开关会记住你的选择，" +
                            "但在总开关打开之前，任何工具都不会执行。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                if (tool.readOnly) {
                    SettingSwitchRow(
                        title = "允许服务端调用这个工具",
                        description = "关闭后，对这个工具的请求会收到 not_permitted，且不会执行。",
                        checked = tool.name !in disabledTools,
                        onCheckedChange = { onToolEnabledChange(tool.name, it) },
                    )
                } else {
                    if (!writeCapabilitiesEnabled) {
                        Text(
                            text = "写入总开关已关闭。写入/删除类工具默认全部禁止，打开总开关后才轮到逐条授权。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        TextButton(onClick = onOpenSettings, modifier = Modifier.padding(top = spacing.xs)) {
                            Text("去设置打开数据修改总开关")
                        }
                    }
                    SettingSwitchRow(
                        title = "允许执行这个写入工具",
                        description = "写入类工具默认关闭，必须逐条打开；还要写入总开关也是开的。",
                        checked = tool.name in enabledWriteTools,
                        enabled = capabilitiesEnabled && writeCapabilitiesEnabled,
                        onCheckedChange = { onWriteToolEnabledChange(tool.name, it) },
                    )
                }

                if (tool.requiresConfirmation) {
                    InfoRow(
                        label = "删除保护",
                        value = "调用方每次必须显式传 confirm=true，否则只回 confirmation_required，不执行",
                    )
                }
                InfoRow(
                    label = "执行入口",
                    value = "服务端经 phone-link-v1 下发请求，手机侧七步校验后执行",
                )
            }
        }

        if (tool.requiresPermission) {
            item {
                SectionCard(
                    title = "系统授权",
                    subtitle = "这是 Android 系统层面的权限，不是本应用的开关",
                    modifier = Modifier.padding(horizontal = spacing.screenHorizontal),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                    ) {
                        StatusPill(
                            text = if (permissionGranted) "已授权" else "未授权",
                            color = availabilityColor(
                                if (permissionGranted) ToolAvailability.Enabled else ToolAvailability.NeedsPermission,
                            ),
                        )
                    }
                    for (permission in tool.declaredPermissions) {
                        InfoRow(
                            label = "权限",
                            value = permission.removePrefix("android.permission."),
                            monospace = true,
                        )
                    }
                    tool.permissionHint?.let { hint ->
                        Text(
                            text = hint,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    val requestable = ToolPermissions.requestable(tool, context)
                    if (!permissionGranted) {
                        if (requestable.isNotEmpty()) {
                            Button(
                                onClick = { permissionLauncher.launch(requestable.toTypedArray()) },
                                modifier = Modifier.padding(top = spacing.xs),
                            ) {
                                Text("授予权限")
                            }
                        }
                        if (ToolPermissions.hasSpecial(tool)) {
                            Button(
                                onClick = { ToolPermissions.openSpecialSettings(context, tool) },
                                modifier = Modifier.padding(top = spacing.xs),
                            ) {
                                Text("打开系统授权页")
                            }
                        }
                    }
                    Text(
                        text = "没有权限时，工具会返回 permission_denied 并说明该去哪里开，" +
                            "不会返回编造的数据，也不影响其它工具。",
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
                val availability = tool.availability(
                    capabilitiesEnabled = capabilitiesEnabled,
                    writeCapabilitiesEnabled = writeCapabilitiesEnabled,
                    toolEnabled = tool.isEnabledByUser(disabledTools, enabledWriteTools),
                    permissionGranted = if (tool.requiresPermission) permissionGranted else null,
                )
                InfoRow(
                    label = "可用状态",
                    value = when (availability) {
                        ToolAvailability.Enabled -> "已启用"
                        ToolAvailability.SwitchOff ->
                            if (tool.readOnly) {
                                "总开关已关闭 —— 现在调用会被回 not_permitted，且不执行任何能力"
                            } else {
                                "写入总开关已关闭 —— 现在调用会被回 not_permitted"
                            }
                        ToolAvailability.Disabled -> "这一条被单独关闭"
                        ToolAvailability.NeedsPermission ->
                            "需要系统授权：${tool.permissionLabel ?: "见上方系统权限"}"
                        ToolAvailability.ComingSoon -> "本版本尚未实现"
                    },
                )
                InfoRow(label = "读写", value = if (tool.readOnly) "只读，无副作用" else "有副作用")
                if (tool.declaredPermissions.isNotEmpty()) {
                    InfoRow(
                        label = "系统权限",
                        value = tool.declaredPermissions.joinToString("、") {
                            it.removePrefix("android.permission.")
                        },
                    )
                }
            }
        }
    }
}

/** 这一条工具是否被用户单独放行（写入类默认关，只读类默认开）。 */
private fun ToolDescriptor.isEnabledByUser(
    disabledTools: Set<String>,
    enabledWriteTools: Set<String>,
): Boolean = if (readOnly) name !in disabledTools else name in enabledWriteTools

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
