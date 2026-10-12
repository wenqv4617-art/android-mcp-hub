package com.androidmcphub.app.ui.services

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.androidmcphub.app.ui.components.EmptyState
import com.androidmcphub.app.ui.components.NavRow
import com.androidmcphub.app.ui.components.PageHeader
import com.androidmcphub.app.ui.components.SectionCard
import com.androidmcphub.app.ui.components.StatusPill
import com.androidmcphub.app.ui.theme.spacing

/**
 * 服务：第三方 MCP 服务的统一管理入口。
 *
 * 这一版**没有**实现第三方 MCP 客户端，所以这一页的全部内容是诚实的空状态：
 *  * 不显示虚构的服务、虚构的连接状态、虚构的工具数量；
 *  * 不给"保存成功"这种实际不存在的反馈 —— 添加按钮是**禁用**的，并写明原因；
 *  * 不列"支持的服务类型"之类的开发向说明（那是文档的活）。
 *
 * 宁少做一个看起来能用的假按钮，也不要造一个点了没反应的表单。
 */
@Composable
fun ServicesScreen(
    onOpenDiagnostics: () -> Unit,
    onOpenConnectionSettings: () -> Unit,
) {
    val spacing = MaterialTheme.spacing

    LazyColumn(
        contentPadding = PaddingValues(bottom = spacing.xxl),
        verticalArrangement = Arrangement.spacedBy(spacing.itemGap),
    ) {
        item {
            PageHeader(
                title = "服务",
                subtitle = "连接并管理第三方 MCP 服务器",
            )
        }

        item {
            SectionCard(
                title = "我的服务",
                trailing = { StatusPill(text = "0 个", color = MaterialTheme.colorScheme.onSurfaceVariant, showDot = false) },
                modifier = Modifier.padding(horizontal = spacing.screenHorizontal),
            ) {
                EmptyState(
                    icon = Icons.AutoMirrored.Filled.List,
                    title = "尚未添加 MCP 服务",
                    description = "这个版本还没有接入第三方 MCP 客户端。" +
                        "接入之后，服务会出现在这里，并显示连接状态与已发现的工具数量。",
                    action = {
                        Button(
                            onClick = { },
                            enabled = false,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("添加 MCP 服务器（即将支持）") }
                    },
                )
                Text(
                    text = "添加功能尚未开放：现在不会保存任何服务配置，也不会显示任何连接状态。" +
                        "远程与本地 MCP 服务将来会共用这一套管理方式，不会分成两个入口。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            SectionCard(
                title = "相关设置",
                modifier = Modifier.padding(horizontal = spacing.screenHorizontal),
            ) {
                NavRow(
                    icon = Icons.Filled.Warning,
                    title = "连接诊断",
                    subtitle = "ping 明细与连接日志",
                    onClick = onOpenDiagnostics,
                )
                NavRow(
                    icon = Icons.Filled.Settings,
                    title = "服务器与设备配置",
                    subtitle = "Hub 连接的目标地址、设备 ID 与令牌",
                    onClick = onOpenConnectionSettings,
                    showDivider = false,
                )
            }
        }
    }
}
