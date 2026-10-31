package com.androidmcphub.app.ui.settings

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import com.androidmcphub.app.net.CloudDiagFailure
import com.androidmcphub.app.net.CloudDiagnostics
import com.androidmcphub.app.net.PhoneLinkClient
import com.androidmcphub.app.ui.CloudDiagUiState
import com.androidmcphub.app.ui.HubViewModel
import com.androidmcphub.app.ui.components.EmptyState
import com.androidmcphub.app.ui.components.InfoRow
import com.androidmcphub.app.ui.components.LogRow
import com.androidmcphub.app.ui.components.PageHeader
import com.androidmcphub.app.ui.components.SectionCard
import com.androidmcphub.app.ui.components.formatDateTime
import com.androidmcphub.app.ui.components.formatTime
import com.androidmcphub.app.ui.diagnostics.DiagTimelineSource
import com.androidmcphub.app.ui.diagnostics.DiagnosticsTimeline
import com.androidmcphub.app.ui.diagnostics.TimelineEntry
import com.androidmcphub.app.ui.diagnostics.TimelineOrigin
import com.androidmcphub.app.ui.model.toStatusView
import com.androidmcphub.app.ui.theme.spacing

/**
 * 连接诊断：本机事件 + 云端事件的"只读观察台"。
 *
 * 三条硬规矩：
 *  - 除了清日志、刷新云端查询，不触发任何连接动作 —— 诊断页绝不能让用户
 *    一不小心把正在进行的连接搞断；
 *  - 云端查询走独立 HTTPS，查询失败/超时**不会**触碰主 WebSocket；
 *  - 合并时间线按校正后的时间轴排序，并明确标注「可能相关」而非因果结论。
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
    val cloudState by viewModel.cloudDiagnostics.collectAsStateWithLifecycle()

    val view = state.toStatusView(serviceRunning)
    val spacing = MaterialTheme.spacing
    val context = LocalContext.current

    var source by rememberSaveable { mutableStateOf(DiagTimelineSource.MERGED) }
    var typeFilter by rememberSaveable { mutableStateOf<String?>(null) }

    // 第一次打开诊断页才去取云端日志；之后由用户手动刷新。
    LaunchedEffect(Unit) { viewModel.refreshCloudDiagnosticsIfNeverLoaded() }

    // 从系统设置页回来时重新读一次"电池优化"状态：用户很可能就是去改这一项的。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshBatteryStatus()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val cloud = cloudState.payload
    val baseEntries = remember(events, cloud, source) {
        DiagnosticsTimeline.build(events, cloud, source)
    }
    val entries = remember(baseEntries, typeFilter) {
        if (typeFilter == null) baseEntries else baseEntries.filter { it.eventType == typeFilter }
    }
    val types = remember(baseEntries) { DiagnosticsTimeline.eventTypes(baseEntries) }

    val reconnecting = state as? com.androidmcphub.app.protocol.LinkState.Reconnecting
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
                subtitle = "本机与云端的连接证据",
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
            CloudStatusCard(
                state = cloudState,
                onRefresh = viewModel::refreshCloudDiagnostics,
                modifier = Modifier.padding(horizontal = spacing.screenHorizontal),
            )
        }

        item {
            SectionCard(
                title = "连接时序（本机）",
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
                    label = "断开关闭码",
                    value = closeCodeText(
                        diagnostics.lastDisconnectCloseCode,
                        cloud?.closeCodeLabel(diagnostics.lastDisconnectCloseCode),
                    ),
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
                    text = "以上全部来自本机。云端是否在线只看上方「云端状态」最近一次查询的结果；" +
                        "没查过就显示「未知」，不用推测填充。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = spacing.sm),
                )
            }
        }

        item {
            SectionCard(
                title = "心跳的两套机制",
                modifier = Modifier.padding(horizontal = spacing.screenHorizontal),
            ) {
                InfoRow(
                    label = "协议层 ping（OkHttp）",
                    value = "每 ${PhoneLinkClient.PING_INTERVAL_SECONDS} 秒，由 Cloudflare 自动 pong",
                )
                InfoRow(
                    label = "应用层心跳",
                    value = "按 hello 协商（默认 30 秒），需要 heartbeat_ack",
                )
                Text(
                    text = "两者不能混为一谈：协议层 ping 不唤醒 Durable Object，Cloudflare 直接回 pong，" +
                        "只能说明链路可达；只有应用层 heartbeat_ack 才能证明服务端还在处理这台设备。" +
                        "OkHttp 不对外暴露 pong 回调，本页不会声称观察到协议层 pong。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            TimelineHeader(
                source = source,
                onSourceChange = {
                    source = it
                    typeFilter = null
                },
                counts = entries,
                cloudState = cloudState,
                onRefresh = viewModel::refreshCloudDiagnostics,
                onCopy = {
                    val text = DiagnosticsTimeline.buildExport(
                        entries = entries,
                        cloud = cloud,
                        source = source,
                        generatedAtMs = System.currentTimeMillis(),
                    )
                    copyToClipboard(context, text)
                },
                onShare = {
                    val text = DiagnosticsTimeline.buildExport(
                        entries = entries,
                        cloud = cloud,
                        source = source,
                        generatedAtMs = System.currentTimeMillis(),
                    )
                    shareText(context, text)
                },
                modifier = Modifier.padding(horizontal = spacing.screenHorizontal),
            )
        }

        if (types.size > 1) {
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
                        selected = typeFilter == null,
                        onClick = { typeFilter = null },
                        label = { Text("全部 (${baseEntries.size})") },
                    )
                    types.forEach { type ->
                        val count = baseEntries.count { it.eventType == type }
                        FilterChip(
                            selected = typeFilter == type,
                            onClick = {
                                typeFilter = if (typeFilter == type) null else type
                            },
                            label = { Text("${DiagnosticsTimeline.typeLabel(type)} ($count)") },
                        )
                    }
                }
            }
        }

        if (entries.isEmpty()) {
            item {
                TimelineEmptyState(
                    source = source,
                    cloudState = cloudState,
                    onRefresh = viewModel::refreshCloudDiagnostics,
                    modifier = Modifier.padding(horizontal = spacing.screenHorizontal),
                )
            }
        } else {
            itemsIndexed(
                items = entries,
                key = { index, entry -> "${entry.id}-$index" },
            ) { _, entry ->
                TimelineRow(
                    entry = entry,
                    modifier = Modifier.padding(horizontal = spacing.screenHorizontal),
                )
            }
        }

        if (source != DiagTimelineSource.CLOUD) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = spacing.screenHorizontal),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "本机原始日志（${logs.size}/200）",
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
 * 云端状态卡：最近一次查询的结果。
 *
 * 失败时**不隐藏**上一次成功的数据，但用日期明确标出它是什么时候取的 ——
 * 旧数据只能解释过去，不能冒充"现在云端仍在线"。
 */
@Composable
private fun CloudStatusCard(
    state: CloudDiagUiState,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = MaterialTheme.spacing
    val cloud = state.payload

    SectionCard(
        title = "云端状态",
        trailing = {
            TextButton(onClick = onRefresh, enabled = !state.loading) {
                Text(if (state.loading) "查询中…" else "刷新")
            }
        },
        modifier = modifier,
    ) {
        when {
            state.loading && cloud == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(spacing.sm))
                Text(
                    text = "正在通过 HTTPS 查询云端日志（不影响主连接）…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            cloud == null && state.failure != null -> CloudFailureBlock(
                state = state,
                hasOldPayload = false,
            )

            cloud == null -> Text(
                text = "还没有查询云端。点右上角「刷新」通过 HTTPS 读取服务端为这台设备记录的连接事件；" +
                    "主 WebSocket 断开时也能查。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            else -> {
                if (state.failure != null) {
                    CloudFailureBlock(state = state, hasOldPayload = true)
                    Spacer(Modifier.height(spacing.sm))
                }
                InfoRow(
                    label = "查询结果",
                    value = if (state.loading) "刷新中…" else "成功",
                )
                InfoRow(
                    label = "数据获取时间",
                    value = state.lastFetchedAtMs?.let(::formatDateTime) ?: "—",
                )
                InfoRow(
                    label = "云端判定",
                    value = when {
                        !cloud.online -> "离线（服务端当前没有这条连接）"
                        cloud.stale -> "在线，但已超过判活阈值没有应用层活动"
                        else -> "在线"
                    },
                )
                InfoRow(label = "云端 socket", value = if (cloud.socketOpen) "打开" else "未打开")
                InfoRow(label = "云端连接代次", value = cloud.connectionGeneration.toString())
                InfoRow(
                    label = "最近云端在线",
                    value = cloud.summary.lastOnlineAtMs?.let(::formatDateTime) ?: "—",
                )
                InfoRow(
                    label = "最近云端离线",
                    value = cloud.summary.lastOfflineAtMs?.let(::formatDateTime) ?: "—",
                )
                InfoRow(
                    label = "最近离线原因",
                    value = cloud.summary.lastOfflineReason ?: "—",
                )
                InfoRow(
                    label = "最近离线关闭码",
                    value = closeCodeText(
                        cloud.summary.lastOfflineCloseCode,
                        cloud.closeCodeLabel(cloud.summary.lastOfflineCloseCode),
                    ),
                )
                InfoRow(
                    label = "最近服务端主动关闭",
                    value = cloud.summary.lastServerInitiatedCloseAtMs?.let(::formatDateTime) ?: "—",
                )
                InfoRow(
                    label = "替换 / 撤销 / 判活超时",
                    value = "${cloud.summary.replacedEvents} / ${cloud.summary.revocations} / " +
                        "${cloud.summary.livenessTimeouts}",
                )
                InfoRow(
                    label = "认证失败 / 手机上报",
                    value = "${cloud.summary.authFailures} / ${cloud.summary.clientReports}",
                )
                InfoRow(
                    label = "事件缓冲",
                    value = "${cloud.buffer.size} 条（返回 ${cloud.buffer.returned}，" +
                        "丢弃 ${cloud.buffer.dropped}，上限 ${cloud.buffer.maxEvents}）",
                )
                InfoRow(
                    label = "两端时钟差",
                    value = "${cloud.clockOffsetMs} ms（服务端 − 本机）",
                )
                if (cloud.closeCodes.isNotEmpty()) {
                    Text(
                        text = "关闭码语义（服务端定义）：" +
                            cloud.closeCodes.entries.joinToString("；") { (code, label) -> "$code=$label" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = spacing.sm),
                    )
                }
                Text(
                    text = "时钟差含一次单程网络延迟（查询往返的一半），合并时间线按它校正，不保证毫秒级对齐。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = spacing.sm),
                )
            }
        }
    }
}

@Composable
private fun CloudFailureBlock(state: CloudDiagUiState, hasOldPayload: Boolean) {
    val spacing = MaterialTheme.spacing
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            imageVector = Icons.Filled.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.size(spacing.sm))
        Column {
            Text(
                text = "云端查询失败：${cloudFailureLabel(state.failure, state.failureStatus)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            if (hasOldPayload) {
                Text(
                    text = "下面是上一次成功获取的数据（获取时间见上），不代表现在的云端状态。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "查询失败只影响这一页，不会导致主连接重连。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TimelineHeader(
    source: DiagTimelineSource,
    onSourceChange: (DiagTimelineSource) -> Unit,
    counts: List<TimelineEntry>,
    cloudState: CloudDiagUiState,
    onRefresh: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = MaterialTheme.spacing
    val localCount = counts.count { it.origin == TimelineOrigin.LOCAL }
    val cloudCount = counts.count { it.origin == TimelineOrigin.CLOUD }

    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "连接时间线（$localCount 本机 / $cloudCount 云端）",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRefresh, enabled = !cloudState.loading) {
                Text(if (cloudState.loading) "刷新中…" else "刷新云端")
            }
            TextButton(onClick = onCopy) { Text("复制") }
            TextButton(onClick = onShare) { Text("导出") }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DiagTimelineSource.entries.forEach { option ->
                FilterChip(
                    selected = source == option,
                    onClick = { onSourceChange(option) },
                    label = { Text(option.label) },
                )
            }
        }
        Text(
            text = "合并视图按「服务端时间 − 本机时间」校正后排序，不按某一侧的时间直接比先后；" +
                "「可能相关」只表示两侧事件时间接近且代次不冲突，不等于因果已确认。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = spacing.xs),
        )
    }
}

@Composable
private fun TimelineEmptyState(
    source: DiagTimelineSource,
    cloudState: CloudDiagUiState,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cloud = cloudState.payload
    when {
        source == DiagTimelineSource.CLOUD && cloud == null -> EmptyState(
            icon = Icons.Filled.Info,
            title = "还没有云端事件",
            description = "先点「刷新云端」通过 HTTPS 读取服务端日志。" +
                "主连接断开时也能查，只要网络可达。",
            modifier = modifier,
        )

        source == DiagTimelineSource.CLOUD && cloud != null && cloud.events.isEmpty() -> EmptyState(
            icon = Icons.Filled.Info,
            title = "云端缓冲区里还没有事件",
            description = "服务端会记录在线/离线、替换、撤销、判活超时与手机上报；" +
                "缓冲区按设备隔离、有上限，旧事件会被淘汰。",
            modifier = modifier,
        )

        else -> {
            Column(modifier) {
                EmptyState(
                    icon = Icons.Filled.Info,
                    title = "还没有连接事件",
                    description = "连接成功、服务端关闭、连接失败、重连、网络变化、心跳超时、" +
                        "认证失败、用户断开与服务停止都会出现在这里，按时间倒序排列。",
                )
                if (source != DiagTimelineSource.LOCAL) {
                    TextButton(onClick = onRefresh) { Text("刷新云端事件") }
                }
            }
        }
    }
}

/**
 * 一条时间线事件：来源徽标 + 时间 + 标题 + 细节。
 *
 * 云端事件同时显示服务端原始时间与校正到本机时间轴上的时刻，避免用户拿两个
 * 时钟的时间直接比较。所有内容都是闭集字段，可直接截图/导出。
 */
@Composable
private fun TimelineRow(
    entry: TimelineEntry,
    modifier: Modifier = Modifier,
) {
    val spacing = MaterialTheme.spacing
    val color = when (entry.origin) {
        TimelineOrigin.LOCAL -> when (entry.eventType) {
            "connected" -> MaterialTheme.colorScheme.primary
            "socket_failure", "server_close", "handshake_timeout",
            "heartbeat_timeout", "authentication_failed",
            -> MaterialTheme.colorScheme.error

            else -> MaterialTheme.colorScheme.onSurface
        }

        TimelineOrigin.CLOUD -> when (entry.eventType) {
            "device_online", "hello" -> MaterialTheme.colorScheme.primary
            "device_offline", "replaced", "liveness_timeout", "revoked", "auth_failure" ->
                MaterialTheme.colorScheme.error

            else -> MaterialTheme.colorScheme.onSurface
        }
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = entry.origin.label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .clip(RoundedCornerShape(HubCornerSmall))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
        Spacer(Modifier.size(spacing.sm))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = color,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (entry.possibleRelated) {
                    Spacer(Modifier.size(spacing.xs))
                    Text(
                        text = "可能相关",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(HubCornerSmall))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            Text(
                text = timeLabel(entry),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            entry.reportedAtMs?.let {
                Text(
                    text = "手机上报时刻 ${formatDateTime(it)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            for (line in entry.detailLines) {
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 云端事件显示双端时刻；本机事件只有本机时刻。 */
private fun timeLabel(entry: TimelineEntry): String {
    val server = entry.serverAtMs ?: return formatDateTime(entry.axisAtMs)
    return "服务端 ${formatDateTime(server)} → 本机 ${formatDateTime(entry.axisAtMs)}"
}

private fun closeCodeText(code: Int?, label: String?): String = when {
    code == null -> "未收到关闭帧"
    label != null -> "$code（$label）"
    else -> code.toString()
}

private fun cloudFailureLabel(kind: CloudDiagFailure?, status: Int?): String {
    val suffix = status?.let { "（HTTP $it）" }.orEmpty()
    return when (kind) {
        CloudDiagFailure.INVALID_URL -> "服务器地址无效，请先在设置里填写 ws(s) 地址"
        CloudDiagFailure.NOT_CONFIGURED -> "还没有设备 ID 或设备令牌"
        CloudDiagFailure.UNAUTHORIZED -> "设备令牌不被接受，可能已被撤销或轮换$suffix"
        CloudDiagFailure.FORBIDDEN -> "网关拒绝访问$suffix"
        CloudDiagFailure.ENDPOINT_MISSING -> "服务端还没有诊断接口，请先部署 V1.6.3 Worker$suffix"
        CloudDiagFailure.SERVER_ERROR -> "服务端错误$suffix"
        CloudDiagFailure.NETWORK -> "网络不可达（DNS / TLS / 连接被重置）"
        CloudDiagFailure.TIMEOUT -> "12 秒内没有响应"
        CloudDiagFailure.BAD_RESPONSE -> "响应结构不符合预期，或缺少服务端时间"
        null -> "原因未知"
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        ?: run {
            Toast.makeText(context, "系统剪贴板不可用", Toast.LENGTH_SHORT).show()
            return
        }
    clipboard.setPrimaryClip(ClipData.newPlainText("连接诊断（已脱敏）", text))
    Toast.makeText(context, "已复制脱敏诊断日志", Toast.LENGTH_SHORT).show()
}

private fun shareText(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, "Android MCP Hub 连接诊断（已脱敏）")
        .putExtra(Intent.EXTRA_TEXT, text)
    try {
        context.startActivity(
            Intent.createChooser(intent, "导出诊断日志").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "没有可用的分享应用", Toast.LENGTH_SHORT).show()
    }
}

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
