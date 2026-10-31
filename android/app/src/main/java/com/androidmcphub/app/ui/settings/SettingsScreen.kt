package com.androidmcphub.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidmcphub.app.BuildConfig
import com.androidmcphub.app.ui.HubViewModel
import com.androidmcphub.app.ui.components.NavRow
import com.androidmcphub.app.ui.components.PageHeader
import com.androidmcphub.app.ui.components.SectionCard
import com.androidmcphub.app.ui.components.SettingSwitchRow
import com.androidmcphub.app.ui.model.serverHostLabel
import com.androidmcphub.app.ui.theme.spacing

/**
 * 设置主页：分组的入口 + 一个必须一眼看到的开关。
 *
 * Stage 2 的信息架构调整：改版前这一页把 10 个输入框 + 保存按钮 + 诊断 + 关于
 * 全部展开，纵向要滑三屏多。现在它只做导航：
 *  * 「服务器与设备配置」→ [ConnectionSettingsScreen]
 *  * 「高级连接参数」→ [AdvancedSettingsScreen]
 *  * 「设备令牌管理」→ [TokenSettingsScreen]
 *
 * **一个功能都没少**：十个配置字段分别落在两个子页里，令牌的显示/隐藏与清除
 * 在令牌页，能力总开关仍在设置主页（它是安全开关，藏进二级页不合适），
 * 诊断与日志入口、最近一次错误的清除也仍然在这里。
 *
 * 这一页**不含**保存按钮：每个子页保存自己那部分，避免"改了哪一页没存"这种困惑。
 */
@Composable
fun SettingsScreen(
    viewModel: HubViewModel,
    onOpenConnectionSettings: () -> Unit,
    onOpenAdvancedSettings: () -> Unit,
    onOpenTokenSettings: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenTools: () -> Unit,
) {
    val spacing = MaterialTheme.spacing
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val capabilitiesEnabled by viewModel.capabilitiesEnabled.collectAsStateWithLifecycle()
    val writeCapabilitiesEnabled by viewModel.writeCapabilitiesEnabled.collectAsStateWithLifecycle()
    val enabledWriteTools by viewModel.enabledWriteCapabilities.collectAsStateWithLifecycle()

    LazyColumn(
        contentPadding = PaddingValues(bottom = spacing.xxl),
        verticalArrangement = Arrangement.spacedBy(spacing.itemGap),
    ) {
        item {
            PageHeader(
                title = "设置",
                subtitle = "连接配置、权限管理与应用设置",
            )
        }

        item {
            SectionCard(
                title = "连接设置",
                subtitle = "配置分成三页，避免一屏里堆满输入框。",
                modifier = Modifier.padding(horizontal = spacing.screenHorizontal),
            ) {
                NavRow(
                    icon = Icons.Filled.Settings,
                    title = "服务器与设备配置",
                    subtitle = "${serverHostLabel(draft.serverUrl)} · 设备 ID " +
                        draft.deviceId.ifBlank { "（未填写）" },
                    onClick = onOpenConnectionSettings,
                )
                NavRow(
                    icon = Icons.Filled.Refresh,
                    title = "高级连接参数",
                    subtitle = "ping 超时、心跳周期、重连与并发上限",
                    onClick = onOpenAdvancedSettings,
                )
                NavRow(
                    icon = Icons.Filled.Lock,
                    title = "设备令牌管理",
                    // 只说"有没有"，永远不显示令牌本身、长度或前后几位。
                    subtitle = if (draft.token.isBlank()) {
                        "尚未设置"
                    } else {
                        "已保存（用 Keystore 密钥加密）"
                    },
                    onClick = onOpenTokenSettings,
                    showDivider = false,
                )
            }
        }

        item {
            SectionCard(
                title = "安全与授权",
                modifier = Modifier.padding(horizontal = spacing.screenHorizontal),
            ) {
                SettingSwitchRow(
                    title = "允许服务端调用本机只读能力",
                    description = "关掉之后，手机对任何工具请求都回 not_permitted，且不执行任何能力。" +
                        "打开后，每一条只读工具还要在「工具」页里单独保持开启（默认开）。" +
                        "开关立即生效，不需要重新连接。首次安装默认关闭，" +
                        "而 phone_ping 这类连通性探测不受这个开关影响。",
                    checked = capabilitiesEnabled,
                    onCheckedChange = viewModel::setCapabilitiesEnabled,
                )
                SettingSwitchRow(
                    title = "允许服务端修改本机数据",
                    description = "写入总开关，默认关闭。打开它只表示「我知道这个应用可以改数据」；" +
                        "闹钟、提醒、备忘、待办、日历、音量、手电筒、打开应用等每一条写入类工具，" +
                        "仍然需要在「工具」页里逐条打开。删除类工具还会额外要求调用方传 confirm=true。" +
                        "现在是：${if (writeCapabilitiesEnabled) "已允许，逐条打开 ${enabledWriteTools.size} 条" else "已禁止全部写入"}。",
                    checked = writeCapabilitiesEnabled,
                    onCheckedChange = viewModel::setWriteCapabilitiesEnabled,
                )
                NavRow(
                    icon = Icons.Filled.Build,
                    title = "逐条管理本机能力",
                    subtitle = "查看每个工具的权限状态、单独开关，以及去系统设置授权",
                    onClick = onOpenTools,
                    showDivider = false,
                )
            }
        }

        item {
            SectionCard(
                title = "诊断与维护",
                modifier = Modifier.padding(horizontal = spacing.screenHorizontal),
            ) {
                NavRow(
                    icon = Icons.Filled.Warning,
                    title = "连接诊断",
                    subtitle = "ping 明细与连接日志",
                    onClick = onOpenDiagnostics,
                )
                NavRow(
                    icon = Icons.Filled.Delete,
                    title = "清空日志",
                    subtitle = "只清内存里的环形日志，不影响连接",
                    onClick = viewModel::clearLogs,
                    showChevron = false,
                )
                NavRow(
                    icon = Icons.Filled.Build,
                    title = "清除最近一次错误",
                    subtitle = "错误提示会留在首页直到手动清除",
                    onClick = viewModel::clearLastError,
                    showChevron = false,
                    showDivider = false,
                )
            }
        }

        item {
            SectionCard(
                title = "关于",
                modifier = Modifier.padding(horizontal = spacing.screenHorizontal),
            ) {
                Text(
                    text = "Android MCP Hub ${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "本应用只在本机执行用户逐条授权的工具。" +
                        "默认只开放只读能力；写入能力需要总开关 + 逐条授权，删除类还要调用方显式确认。" +
                        "不发送短信、不读取通讯录、不代用户静默修改系统设置。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = spacing.xs),
                )
            }
        }
    }
}
