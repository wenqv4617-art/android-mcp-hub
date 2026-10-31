package com.androidmcphub.app.ui.settings

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidmcphub.app.core.LinkEvent
import com.androidmcphub.app.core.LinkEventKind
import com.androidmcphub.app.protocol.LinkState
import com.androidmcphub.app.ui.HubViewModel
import com.androidmcphub.app.ui.components.EmptyState
import com.androidmcphub.app.ui.components.InfoRow
import com.androidmcphub.app.ui.components.LogRow
import com.androidmcphub.app.ui.components.PageHeader
import com.androidmcphub.app.ui.components.SectionCard
import com.androidmcphub.app.ui.components.formatDateTime
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
    val diagnostics by viewModel.diagnostics.collectAsStateWithLifecycle()
    val events by viewModel.events.collectAsStateWithLifecycle()
    val batteryUnrestricted by viewModel.batteryUnrestricted.collectAsStateWithLifecycle()

    val view = state.toStatusView(serviceRunning)
    val spacing = MaterialTheme.spacing
    val context = LocalContext.current

    // 从系统设置页回来时重新读一次"电池优化"状态：用户很可能就是去改这一项的。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshBatteryStatus()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val reconnecting = state as? LinkState.Reconnecting
    val nextRetry = reconnecting?.let {
        "约 ${formatTime(it.deadlineMs)}（第 ${it.attempt} 次）"
    } ?: "—"

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
                InfoRow(label = "当前网络", value = diagnostics.networkLabel)
            }
        }

        item {
            SectionCard(
                title = "连接时序",
                modifier = Modifier.padding(horizontal = spacing.screenHorizontal),
            ) {
                InfoRow(
                    label = "上次连接成功",
                    value = diagnostics.lastConnectedAtMs?.let(::formatDateTime) ?: "—",
                )
                InfoRow(
                    label = "上次心跳发送",
                    value = diagnostics.lastHeartbeatSentAtMs?.let(::formatDateTime) ?: "—",
                )
                InfoRow(
                    label = "上次心跳成功",
                    value = diagnostics.lastHeartbeatAckAtMs?.let(::formatDateTime) ?: "—",
                )
                InfoRow(
                    label = "上次断开",
                    value = diagnostics.lastDisconnectAtMs?.let(::formatDateTime) ?: "—",
                )
                InfoRow(
                    label = "断开原因",
                    value = diagnostics.lastDisconnectReason ?: "—",
                )
                InfoRow(
                    label = "断开来源",
                    value = diagnostics.lastDisconnectSource?.let(::disconnectSourceLabel) ?: "—",
                )
                InfoRow(
                    label = "连接意图",
                    value = if (diagnostics.linkDesired) "保持连接（用户意愿）" else "已断开（用户意愿）",
                )
                InfoRow(label = "重连次数", value = diagnostics.reconnectCount.toString())
                InfoRow(label = "下次重试", value = nextRetry)
                InfoRow(
                    label = "服务停止原因",
                    value = diagnostics.lastServiceStopReason ?: "—",
                )
                Text(
                    text = "云端是否在线由服务端按心跳判定，本页没有查询云端状态的接口，" +
                        "因此只展示本机链路与心跳证据，不伪造「云端已确认在线」。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = spacing.sm),
                )
            }
        }

        item {
            SectionCard(
                title = "后台保活",
                modifier = Modifier.padding(horizontal = spacing.screenHorizontal),
            ) {
                InfoRow(
                    label = "电池优化",
                    value = if (batteryUnrestricted) "已设为「不限制」" else "受系统优化限制",
                )
                Text(
                    text = if (batteryUnrestricted) {
                        "系统已允许本应用在后台运行。ColorOS 若另开了「应用耗电管理」，" +
                            "请确认本应用允许后台运行。"
                    } else {
                        "本应用仍受系统电池优化限制，息屏或长时间后台时可能被冻结。" +
                            "建议把电池优化改为「不限制」；ColorOS 还需在「应用耗电管理」里允许后台运行。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "即使做到以上设置，系统「强行停止」或极端省电策略仍可能冻结应用；" +
                        "这种情况下无法保证永久在线，回到应用或系统重建服务后会自动重连。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { openBatterySettings(context) }) {
                    Text(if (batteryUnrestricted) "查看电池设置" else "去设置电池优化")
                }
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
                    text = "最近连接事件（${events.size}/50）",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = viewModel::clearEvents) { Text("清空") }
            }
        }

        if (events.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Filled.Info,
                    title = "还没有连接事件",
                    description = "连接成功、断开、重连、网络变化、心跳超时、认证失败、用户断开" +
                        "与服务停止都会出现在这里，按时间倒序排列。",
                    modifier = Modifier.padding(horizontal = spacing.screenHorizontal),
                )
            }
        } else {
            items(items = events) { event ->
                EventRow(
                    event = event,
                    modifier = Modifier.padding(horizontal = spacing.screenHorizontal),
                )
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
 * 一条结构化连接事件：时间 + 类别 + 细节。
 *
 * 事件里只可能有代次、次数、耗时与固定类别（由 [com.androidmcphub.app.protocol.PhoneLink]
 * 拼装），不会出现令牌、配对码或服务器地址 —— 这一页是可以直接截图发出去的。
 */
@Composable
private fun EventRow(
    event: LinkEvent,
    modifier: Modifier = Modifier,
) {
    val spacing = MaterialTheme.spacing
    val color = when (event.kind) {
        LinkEventKind.CONNECTED -> MaterialTheme.colorScheme.primary
        LinkEventKind.DISCONNECTED,
        LinkEventKind.HEARTBEAT_TIMEOUT,
        LinkEventKind.AUTHENTICATION_FAILED,
        -> MaterialTheme.colorScheme.error

        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = formatTime(event.atMs),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(spacing.sm))
        Column(Modifier.weight(1f)) {
            Text(
                text = event.kind.label,
                style = MaterialTheme.typography.bodyMedium,
                color = color,
            )
            if (event.detail.isNotBlank()) {
                Text(
                    text = event.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 打开系统的电池优化设置列表。
 *
 * 用 `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`（列表页）而不是
 * `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`：后者需要声明
 * `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`，而且会绕过系统的确认界面 ——
 * 那是一个过度索取权限的捷径。用户自己点开列表页，由系统解释每一项的后果。
 *
 * 个别 ROM 没有这个列表页时退回到应用详情页，不把"点不开"变成崩溃。
 */
/**
 * 把 [com.androidmcphub.app.service.LinkDiagnostics.lastDisconnectSource] 的闭集标签
 * 翻成界面文案。未知标签原样显示，方便测试与排障时发现新增来源没有配套文案。
 */
private fun disconnectSourceLabel(source: String): String = when {
    source.startsWith("user:") -> when (source.removePrefix("user:")) {
        "ui_button" -> "用户点击界面按钮"
        "notification_action" -> "用户点击通知栏按钮"
        else -> "用户操作（来源未知）"
    }

    source == "service_destroyed" -> "系统停止前台服务（非用户）"
    source == "remote_close" -> "服务端关闭连接"
    source == "heartbeat_timeout" -> "心跳超时"
    source == "handshake_timeout" -> "握手超时"
    source == "auth_failed" -> "认证失败"
    source.startsWith("network:") -> "网络错误（${source.removePrefix("network:")}）"
    source == "network_failure" -> "网络错误"
    else -> source
}

private fun openBatterySettings(context: Context) {
    val candidates = listOf(
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}"),
        ),
    )
    for (intent in candidates) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (_: ActivityNotFoundException) {
            // 试下一个；全部失败时给一句可读提示。
        }
    }
    Toast.makeText(context, "系统没有可用的电池优化设置页面", Toast.LENGTH_SHORT).show()
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
