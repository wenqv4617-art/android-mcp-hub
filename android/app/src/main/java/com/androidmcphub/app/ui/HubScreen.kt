package com.androidmcphub.app.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.androidmcphub.app.core.HubDraft
import com.androidmcphub.app.core.LogEntry
import com.androidmcphub.app.core.LogLevel
import com.androidmcphub.app.protocol.LinkState
import com.androidmcphub.app.protocol.PingOutcome
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 唯一的一屏界面。
 *
 * 这里只做「连接控制 + 状态 + 最近一次 ping + 日志 + 高级设置」这五件事，
 * 刻意没有工具列表、应用扫描、跨设备控制之类的入口。
 */
@Composable
fun HubScreen(vm: HubViewModel = viewModel()) {
    val context = LocalContext.current

    val state by vm.state.collectAsState()
    val logs by vm.logs.collectAsState()
    val lastPing by vm.lastPing.collectAsState()
    val draft by vm.draft.collectAsState()
    val tokenVisible by vm.tokenVisible.collectAsState()
    val advancedExpanded by vm.advancedExpanded.collectAsState()
    val message by vm.message.collectAsState()
    val serviceRunning by vm.serviceRunning.collectAsState()
    val lastError by vm.lastError.collectAsState()
    val capabilitiesEnabled by vm.capabilitiesEnabled.collectAsState()

    val palette = statusPalette()
    val darkTheme = isSystemInDarkTheme()

    // 每秒刷新一次「重连等待」的倒计时；不在重连状态时不做无意义的刷新
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state) {
        nowMs = System.currentTimeMillis()
        while (state is LinkState.Reconnecting) {
            delay(500L)
            nowMs = System.currentTimeMillis()
        }
    }

    // Android 13+ 的通知权限：拒绝也不影响连接，只是通知不可见
    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { /* 用户允许或拒绝都继续，连接本身不依赖这个权限 */ }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(modifier = Modifier.padding(top = 16.dp)) {
                Text(
                    text = "Android MCP Hub 手机端",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "由用户主动发起的 WebSocket 长连接，只用于响应配对 MCP 客户端的保活与 ping。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        item {
            StatusCard(
                state = state,
                nowMs = nowMs,
                serverUrl = draft.serverUrl,
                serviceRunning = serviceRunning,
                idleColor = palette.idle,
                connectingColor = palette.connecting,
                connectedColor = palette.connected,
                reconnectingColor = palette.reconnecting,
                failedColor = palette.failed,
            )
        }

        item {
            Column {
                // 「连接」与「断开」按状态互斥出现，避免同时可点造成语义冲突
                if (state is LinkState.Idle) {
                    Button(
                        onClick = vm::connect,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("连接")
                    }
                } else {
                    OutlinedButton(
                        onClick = vm::disconnect,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("断开")
                    }
                }
                if (message != null) {
                    Text(
                        text = message.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state is LinkState.Idle) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }

        item { PingCard(lastPing = lastPing, lastError = lastError, onClearError = vm::clearLastError) }

        item {
            AdvancedSettingsCard(
                draft = draft,
                tokenVisible = tokenVisible,
                expanded = advancedExpanded,
                capabilitiesEnabled = capabilitiesEnabled,
                onDraftChange = vm::updateDraft,
                onToggleTokenVisible = vm::toggleTokenVisible,
                onToggleExpanded = vm::toggleAdvanced,
                onCapabilitiesEnabledChange = vm::setCapabilitiesEnabled,
                onSave = vm::save,
                onClearToken = vm::clearToken,
            )
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "最近连接日志（${logs.size}/200）",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = vm::clearLogs) { Text("清空") }
            }
        }

        if (logs.isEmpty()) {
            item {
                Text(
                    text = "还没有日志。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(items = logs) { entry ->
                LogRow(entry)
            }
        }

        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

/** 状态文案；[nowMs] 用于把重连倒计时算成「Xs 后」。 */
private fun statusText(state: LinkState, nowMs: Long): String = when (state) {
    is LinkState.Idle -> "已断开"
    is LinkState.Connecting -> "连接中"
    is LinkState.Connected -> "已连接（代次 ${state.generation}）"
    is LinkState.Reconnecting -> {
        val remainSec = ((state.deadlineMs - nowMs).coerceAtLeast(0L) + 999L) / 1000L
        "重连等待（第 ${state.attempt} 次，${remainSec}s 后）"
    }
    is LinkState.AuthFailed -> "认证失败"
    is LinkState.Error -> "出错"
}

private fun statusDetail(state: LinkState, serviceRunning: Boolean): String = when (state) {
    is LinkState.Idle ->
        if (serviceRunning) "前台服务在运行，当前没有连接" else "前台服务未运行"
    is LinkState.Connecting -> "正在建立 WebSocket 连接并等待 welcome"
    is LinkState.Connected -> "前台服务在运行，保持应用层心跳"
    is LinkState.Reconnecting -> "按指数退避 + 抖动重连，成功后计数归零"
    is LinkState.AuthFailed -> "凭据被拒绝（${state.detail}），需修改设备令牌后手动重连"
    is LinkState.Error -> state.message
}

@Composable
private fun StatusCard(
    state: LinkState,
    nowMs: Long,
    serverUrl: String,
    serviceRunning: Boolean,
    idleColor: Color,
    connectingColor: Color,
    connectedColor: Color,
    reconnectingColor: Color,
    failedColor: Color,
) {
    val color = when (state) {
        is LinkState.Idle -> idleColor
        is LinkState.Connecting -> connectingColor
        is LinkState.Connected -> connectedColor
        is LinkState.Reconnecting -> reconnectingColor
        is LinkState.AuthFailed, is LinkState.Error -> failedColor
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = "连接状态", style = MaterialTheme.typography.labelMedium)
            Text(
                text = statusText(state, nowMs),
                style = MaterialTheme.typography.titleLarge,
                color = color,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                text = statusDetail(state, serviceRunning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
            Text(
                text = "服务器：" + serverUrl.ifBlank { "（未填写）" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PingCard(lastPing: PingOutcome?, lastError: String?, onClearError: () -> Unit) {
    val context = LocalContext.current

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = "最近一次 ping", style = MaterialTheme.typography.labelMedium)

            if (lastPing == null) {
                Text(
                    text = "还没有收到 phone_ping。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            } else {
                Text(
                    text = "长按 request_id 可复制",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    text = lastPing.requestId,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(8.dp)
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = {},
                            onLongClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                    as? ClipboardManager
                                clipboard?.setPrimaryClip(
                                    ClipData.newPlainText("request_id", lastPing.requestId),
                                )
                                Toast.makeText(context, "已复制 request_id", Toast.LENGTH_SHORT).show()
                            },
                        ),
                )

                PingRow("RTT（估算）", "${lastPing.rttMs} ms")
                PingRow("单向延迟（估算）", "${lastPing.oneWayMs} ms")
                PingRow("服务端时间戳", "${lastPing.serverTimestampMs}（${formatTime(lastPing.serverTimestampMs)}）")
                PingRow("本机收到时刻", formatTime(lastPing.receivedAtMs))

                Text(
                    text = "RTT 由「本机收到时刻 − ping 里的服务端时间戳」×2 估算，需要两端时钟已同步。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            // 「最近一次错误」独立于当前状态：状态会被断开/服务销毁复位成「已断开」，
            // 而失败原因必须留得下来 —— 否则用户永远看不到连接为什么失败。
            if (lastError != null) {
                HorizontalDivider(
                    modifier = Modifier.padding(top = 12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "最近一次错误",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onClearError) { Text("清除") }
                }
                Text(
                    text = lastError,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isSystemInDarkTheme()) Color(0xFFEF9A9A) else Color(0xFFC62828),
                )
            }
        }
    }
}

@Composable
private fun PingRow(label: String, value: String) {
    Row(modifier = Modifier.padding(top = 6.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(112.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun AdvancedSettingsCard(
    draft: HubDraft,
    tokenVisible: Boolean,
    expanded: Boolean,
    capabilitiesEnabled: Boolean,
    onDraftChange: ((HubDraft) -> HubDraft) -> Unit,
    onToggleTokenVisible: () -> Unit,
    onToggleExpanded: () -> Unit,
    onCapabilitiesEnabledChange: (Boolean) -> Unit,
    onSave: () -> Unit,
    onClearToken: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "高级设置",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onToggleExpanded) {
                    Text(if (expanded) "收起" else "展开")
                }
            }

            if (!expanded) return@Column

            OutlinedTextField(
                value = draft.serverUrl,
                onValueChange = { v -> onDraftChange { it.copy(serverUrl = v) } },
                label = { Text("服务器 URL") },
                placeholder = { Text("wss://<host>/phone/ws") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            )

            OutlinedTextField(
                value = draft.deviceId,
                onValueChange = { v -> onDraftChange { it.copy(deviceId = v) } },
                label = { Text("设备 ID") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            )

            OutlinedTextField(
                value = draft.token,
                onValueChange = { v -> onDraftChange { it.copy(token = v) } },
                label = { Text("设备令牌") },
                singleLine = true,
                visualTransformation = if (tokenVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    TextButton(onClick = onToggleTokenVisible) {
                        Text(if (tokenVisible) "隐藏" else "显示")
                    }
                },
                supportingText = {
                    Text("用 Android Keystore 里的密钥加密后存本机私有存储（不留明文），仅用于 Authorization 请求头。")
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            )

            TextButton(
                onClick = onClearToken,
                modifier = Modifier.padding(top = 4.dp),
            ) {
                Text("清除已保存的令牌")
            }

            NumberField(
                label = "ping 超时（毫秒）",
                value = draft.pingTimeoutMs,
                onValueChange = { v -> onDraftChange { it.copy(pingTimeoutMs = v) } },
            )
            NumberField(
                label = "心跳周期（毫秒）",
                value = draft.heartbeatIntervalMs,
                onValueChange = { v -> onDraftChange { it.copy(heartbeatIntervalMs = v) } },
            )
            NumberField(
                label = "初始重连间隔（毫秒）",
                value = draft.reconnectInitialDelayMs,
                onValueChange = { v -> onDraftChange { it.copy(reconnectInitialDelayMs = v) } },
            )
            NumberField(
                label = "最大重连间隔（毫秒）",
                value = draft.reconnectMaxDelayMs,
                onValueChange = { v -> onDraftChange { it.copy(reconnectMaxDelayMs = v) } },
            )
            NumberField(
                label = "抖动比例（0~1）",
                value = draft.reconnectJitterRatio,
                onValueChange = { v -> onDraftChange { it.copy(reconnectJitterRatio = v) } },
                decimal = true,
            )
            NumberField(
                label = "最大挂起请求数",
                value = draft.maxPendingRequests,
                onValueChange = { v -> onDraftChange { it.copy(maxPendingRequests = v) } },
            )
            NumberField(
                label = "单帧字节上限",
                value = draft.maxFrameBytes,
                onValueChange = { v -> onDraftChange { it.copy(maxFrameBytes = v) } },
            )

            HorizontalDivider(modifier = Modifier.padding(top = 16.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "允许服务端调用本机只读能力",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = "关掉之后，手机对任何工具请求都回 not_permitted，且不执行任何能力。" +
                            "当前已启用的能力只有 battery_get_status（电池状态，只读、不需要任何权限）。" +
                            "开关立即生效，不需要重新连接。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Switch(
                    checked = capabilitiesEnabled,
                    onCheckedChange = onCapabilitiesEnabledChange,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }

            Button(
                onClick = onSave,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
            ) {
                Text("保存")
            }
        }
    }
}

@Composable
private fun NumberField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    decimal: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
    )
}

@Composable
private fun LogRow(entry: LogEntry) {
    val color = when (entry.level) {
        LogLevel.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
        LogLevel.WARN -> if (isSystemInDarkTheme()) Color(0xFFFFB74D) else Color(0xFFEF6C00)
        LogLevel.ERROR -> if (isSystemInDarkTheme()) Color(0xFFEF9A9A) else Color(0xFFC62828)
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                text = formatTime(entry.timeMs),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = entry.level.name,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = color,
                modifier = Modifier.width(44.dp),
            )
            Text(
                text = entry.text,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
        }
        HorizontalDivider(
            modifier = Modifier.padding(top = 4.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        )
    }
}

private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

private fun formatTime(ms: Long): String =
    if (ms <= 0L) "—" else timeFormat.format(Date(ms))
