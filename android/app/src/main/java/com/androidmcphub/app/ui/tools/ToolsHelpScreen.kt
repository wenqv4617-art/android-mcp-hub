package com.androidmcphub.app.ui.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.androidmcphub.app.ui.components.NavRow
import com.androidmcphub.app.ui.components.PageHeader
import com.androidmcphub.app.ui.components.SectionCard
import com.androidmcphub.app.ui.theme.spacing

/**
 * 工具目录说明（工具页 → 「说明」）。
 *
 * 这一页就是 Stage 1 工具列表页里那几段开发说明的去处：
 * 用户想看工具的时候不该先读协议注释，但排查"为什么这个工具是灰的"时又确实需要它。
 */
@Composable
fun ToolsHelpScreen(
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val spacing = MaterialTheme.spacing

    LazyColumn(
        contentPadding = PaddingValues(bottom = spacing.xxl),
        verticalArrangement = Arrangement.spacedBy(spacing.itemGap),
    ) {
        item {
            PageHeader(
                title = "工具目录说明",
                subtitle = "工具从哪来、为什么有些不可用",
                onBack = onBack,
            )
        }

        item {
            HelpCard(
                title = "本机能力从哪来",
                body = "列表来自 App 内置的能力注册表，不是服务端下发的 —— " +
                    "手机连上之后，服务端能调用的工具名就是这里的 ID。" +
                    "当前正式内置的能力是 battery_get_status（电池状态，只读，不需要任何权限）。",
            )
        }

        item {
            HelpCard(
                title = "为什么有些工具是灰的",
                body = "「允许服务端调用本机只读能力」总开关关闭时，工具仍然会列出来，但会标成" +
                    "「未授权」，此时手机对任何工具请求都回 not_permitted 并且不会执行。" +
                    "开关在设置页，改动立即生效，不需要重新连接。",
            )
        }

        item {
            HelpCard(
                title = "其他来源",
                body = "第三方 MCP 服务器与本地 HTTP MCP 中转还没有接入，所以现在不会出现它们的工具 —— " +
                    "这里也不会用占位卡片假装有。接入后它们会出现在同一个列表里，用来源标签区分。",
            )
        }

        item {
            HelpCard(
                title = "phone_ping 不在这个列表里",
                body = "phone_ping 是连接诊断通道，由连接层自己处理，不是本机能力：" +
                    "它不受能力总开关影响，所以在「连接诊断」里看，而不是在工具列表里找。",
            )
        }

        item {
            SectionCard(
                title = "相关设置",
                modifier = Modifier.padding(horizontal = spacing.screenHorizontal),
            ) {
                NavRow(
                    icon = Icons.Filled.Settings,
                    title = "本机能力授权",
                    subtitle = "在设置页打开或关闭总开关",
                    onClick = onOpenSettings,
                    showDivider = false,
                )
            }
        }
    }
}

@Composable
private fun HelpCard(title: String, body: String) {
    val spacing = MaterialTheme.spacing
    SectionCard(
        title = title,
        modifier = Modifier.padding(horizontal = spacing.screenHorizontal),
    ) {
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
