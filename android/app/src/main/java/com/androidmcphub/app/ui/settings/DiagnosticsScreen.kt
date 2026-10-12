package com.androidmcphub.app.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidmcphub.app.ui.HubViewModel
import com.androidmcphub.app.ui.components.EmptyState
import com.androidmcphub.app.ui.components.InfoRow
import com.androidmcphub.app.ui.components.LogRow
import com.androidmcphub.app.ui.components.PageHeader
import com.androidmcphub.app.ui.components.SectionCard
import com.androidmcphub.app.ui.components.formatTime
import com.androidmcphub.app.ui.model.toStatusView
import com.androidmcphub.app.ui.theme.spacing

/**
 * 连接诊断：改版前首页里 `PingCard` + 日志区 + 「清除最近一次错误」的完整搬家。
 *
 * 这一页是"只读观察台"：除了清日志/清错误，不会触发任何连接动作 ——
 * 诊断页面绝不能让用户一不小心把正在进行的连接搞断。
 */
@Composable
fun DiagnosticsScreen(
    viewModel: HubViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val serviceRunning by viewModel.serviceRunning.collectAsStateWithLifecycle()
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val lastPing by viewModel.lastPing.collectAsStateWithLifecycle()
    val lastError by viewModel.lastError.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()

    val view = state.toStatusView(serviceRunning)
    val spacing = MaterialTheme.spacing

    LazyColumn(
        contentPadding = PaddingValues(bottom = spacing.xxl),
        verticalArrangement = Arrangement.spacedBy(spacing.itemGap),
    ) {
        item {
            PageHeader(
                title = "连接诊断",
                subtitle = "ping 明细与连接日志",
                onBack = onBack,
                modifier = Modifier.padding(horizontal = spacing.sm),
            )
        }

        item {
            SectionCard(
                title = "连接",
                modifier = Modifier.padding(horizontal = spacing.screenHorizontal),
            ) {
                InfoRow(label = "当前状态", value = view.title)
                InfoRow(label = "前台服务", value = if (serviceRunning) "运行中" else "未运行")
                InfoRow(label = "服务器", value = draft.serverUrl.ifBlank { "（未填写）" }, monospace = true)
                InfoRow(label = "设备 ID", value = draft.deviceId.ifBlank { "（未填写）" }, monospace = true)
                InfoRow(label = "连接代次", value = view.generation?.toString() ?: "—")
            }
        }

        item {
            SectionCard(
                title = "最近一次 ping",
                modifier = Modifier.padding(horizontal = spacing.screenHorizontal),
            ) {
                // 先取成局部量：lastPing 是 collectAsStateWithLifecycle 的委托属性，
                // 编译器不会对它做智能转换（委托属性的 getter 可能每次返回不同的值）。
                val ping = lastPing
                if (ping == null) {
                    Text(
                        text = "还没有收到 phone_ping。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    RequestIdBox(ping.requestId)
                    Spacer(Modifier.height(spacing.sm))
                    InfoRow(label = "RTT（估算）", value = "${ping.rttMs} ms")
                    InfoRow(label = "单向延迟（估算）", value = "${ping.oneWayMs} ms")
                    InfoRow(
                        label = "服务端时间戳",
                        value = "${ping.serverTimestampMs}（${formatTime(ping.serverTimestampMs)}）",
                    )
                    InfoRow(label = "本机收到时刻", value = formatTime(ping.receivedAtMs))
                    Text(
                        text = "RTT 由「本机收到时刻 − ping 里的服务端时间戳」×2 估算，需要两端时钟已同步。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = spacing.sm),
                    )
                }
            }
        }

        if (lastError != null) {
            item {
                SectionCard(
                    title = "最近一次错误",
                    trailing = { TextButton(onClick = viewModel::clearLastError) { Text("清除") } },
                    modifier = Modifier.padding(horizontal = spacing.screenHorizontal),
                ) {
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(
                            imageVector = Icons.Filled.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.size(spacing.sm))
                        Text(
                            text = lastError.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
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
                    text = "最近连接日志（${logs.size}/200）",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = viewModel::clearLogs) { Text("清空") }
            }
        }

        if (logs.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Filled.Info,
                    title = "还没有日志",
                    description = "连接一次之后，这里会按时间倒序显示心跳、重连与错误记录。" +
                        "日志只存在内存里，进程结束即清空。",
                    modifier = Modifier.padding(horizontal = spacing.screenHorizontal),
                )
            }
        } else {
            items(items = logs, key = { "${it.timeMs}-${it.level}-${it.text}" }) { entry ->
                LogRow(
                    entry = entry,
                    modifier = Modifier.padding(horizontal = spacing.screenHorizontal),
                )
            }
        }

        item {
            HorizontalDivider(
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.padding(horizontal = spacing.screenHorizontal, vertical = spacing.sm),
            )
        }
    }
}

/**
 * request_id 展示块：长按复制。
 *
 * 保留改版前的行为（长按 → 剪贴板 + Toast）—— 排查线上问题时，
 * 这串 id 是唯一能把手机端日志和服务端日志对起来的东西。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RequestIdBox(requestId: String) {
    val context = LocalContext.current
    val spacing = MaterialTheme.spacing

    Column {
        Text(
            text = "长按 request_id 可复制",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = requestId,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .padding(top = spacing.xs)
                .fillMaxWidth()
                .clip(RoundedCornerShape(HubCornerSmall))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .combinedClickable(
                    onClick = {},
                    onLongClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                            as? ClipboardManager
                        clipboard?.setPrimaryClip(ClipData.newPlainText("request_id", requestId))
                        Toast.makeText(context, "已复制 request_id", Toast.LENGTH_SHORT).show()
                    },
                )
                .padding(spacing.sm),
        )
    }
}

private val HubCornerSmall = 8.dp
