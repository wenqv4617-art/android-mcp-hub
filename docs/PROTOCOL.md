# 内部通信协议（Worker ⇄ 手机）

> 版本：Gate 0 / v1
> 适用范围：Cloudflare Worker（含 `PhoneSession` Durable Object）与 Android 手机 APK 之间的
> **长连接 WebSocket 通道**。这套协议与对外的 MCP 协议是两套东西，不要混用。

---

## 1. 两层协议的分工

| 层 | 对端 | 载体 | 文档 |
| --- | --- | --- | --- |
| 对外 MCP 层 | ChatGPT / 任意 MCP 客户端 | HTTPS `POST /mcp`（Streamable HTTP） | `ARCHITECTURE.md` 第 3 节 |
| 内部设备层 | Android APK | WSS `/phone/ws` | **本文档** |

MCP 层永远不直接触碰手机。它调用的 `phone_ping` 工具，最终由 `PhoneSession` Durable Object
把一条 `phone_ping` 帧推给手机，并等待 `phone_pong` 回来。

```
MCP client ──HTTPS──> Worker ──DO binding──> PhoneSession ──WSS──> Android APK
                                              (唯一持有连接的地方)
```

---

## 2. 建立连接

```
GET /phone/ws  HTTP/1.1
Host: <worker-host>
Upgrade: websocket
Connection: Upgrade
Authorization: Bearer <device-token>      ← 明文令牌，仅此一次，不落日志
X-Device-Id: test-phone-1                 ← 设备标识，决定路由到哪个 PhoneSession
X-Client-Version: android-mcp-hub/0.1.0   ← 可选，便于排查
```

**握手与鉴权顺序（服务端）**

1. 必须是 `Upgrade: websocket`，否则 `426 Upgrade Required`。
2. 必须同时有 `Authorization: Bearer` 与 `X-Device-Id`，否则 `401`。
3. 用 `X-Device-Id` 在 `DEVICE_TOKENS` 里找出该设备的全部有效凭据（`revokedAt == null`）。
4. 对出示的令牌算 SHA-256，与每条凭据的 `tokenSha256` 做**定时安全比较**。
   找不到任何匹配 → `401`，同时 `authFailures` 指标 +1。
5. 鉴权通过才把请求转发给 `PHONE_SESSION.idFromName(deviceId)`；
   Durable Object 只接受带内部标记头 `X-Phone-Session-Device` 的升级请求，
   缺这个头直接 `500`（防止绕过 Worker 的鉴权直连 DO）。

**失败响应体**（不区分「设备不存在」与「令牌错误」，避免枚举设备）：

```json
{ "error": "unauthorized", "message": "device authentication failed" }
```

---

## 3. 服务端 → 手机

### 3.1 `welcome`（连接建立后服务端主动发送的第一帧）

```json
{
  "type": "welcome",
  "device_id": "test-phone-1",
  "connection_generation": 3,
  "server_time": 1760000000000,
  "heartbeat_interval_ms": 30000,
  "ping_timeout_ms": 10000,
  "max_pending_requests": 16,
  "protocol_version": "phone-link-v1"
}
```

手机应当把 `connection_generation` 记进连接日志：它是一个单调递增的连接代次，
用于在排查「旧连接把新连接的请求答错了」这类问题时一眼看出谁是谁。

### 3.2 `phone_ping`

```json
{
  "type": "phone_ping",
  "request_id": "5f1c0f3a-...-9d2b",
  "timestamp": 1760000000123
}
```

- `request_id`：服务端生成的 UUID v4，全局唯一，也是唯一允许用来配对响应的键。
- `timestamp`：服务端发出时刻（毫秒）。
- 手机收到后**必须**用**同一个** `request_id` 回 `phone_pong`，且不得重放。

### 3.3 `heartbeat_ack`

```json
{ "type": "heartbeat_ack", "server_time": 1760000000456 }
```

### 3.4 `error`（协议级错误，连接未必关闭）

```json
{ "type": "error", "code": "message_too_large", "message": "frame exceeds 65536 bytes" }
```

已知 `code`：`message_too_large`、`malformed_message`、`unsupported_type`、`unknown_request_id`。

---

## 4. 手机 → 服务端

### 4.1 `phone_pong`

```json
{
  "type": "phone_pong",
  "request_id": "5f1c0f3a-...-9d2b",
  "timestamp": 1760000000155
}
```

- `request_id` 必须与收到的 `phone_ping.request_id` 逐字符相同。
- `timestamp` 是**手机本地时钟**。它只被记录，**绝不**用于计算 RTT ——
  RTT 一律由服务端用 `Date.now()` 在本地测量，不信任设备时钟。

### 4.2 `hello`（可选，连接后立即发送）

```json
{
  "type": "hello",
  "device_id": "test-phone-1",
  "protocol_version": "phone-link-v1",
  "app_version": "0.1.0",
  "android_sdk": 35
}
```

仅用于连接日志与排障。服务端会校验 `device_id` 与鉴权得到的设备一致，不一致则回
`error{code:"malformed_message"}` 并计数，但**不**因此断开（Gate 0 的宽松选择，见下）。

### 4.3 `heartbeat`

```json
{ "type": "heartbeat", "timestamp": 1760000000500 }
```

服务端回 `heartbeat_ack`。WebSocket 协议层自带的 ping/pong 帧由运行时自动应答，
这条路是**应用层**心跳，用来穿过中间设备可能存在的空闲超时。

---

## 5. 规则（强制，全部有对应测试）

| # | 规则 | 为什么 |
| --- | --- | --- |
| R1 | `request_id` 由服务端生成，UUID v4，唯一 | 配对只能靠它 |
| R2 | 响应必须逐字符匹配请求的 `request_id` | 防串台 |
| R3 | RTT 在服务端测量 | 不信任设备时钟 |
| R4 | 默认超时 10 000 ms（`PHONE_PING_TIMEOUT_MS` 可配，上限 60 000） | 不能无限挂起 |
| R5 | 每个待处理请求记录 `connection_generation`；响应必须来自**当前**代次 | 旧连接的响应不得完成新连接的请求 |
| R6 | 超时请求从待处理表中清除并记账 | 不泄漏、不重复 |
| R7 | 连接断开时所有未完成请求显式失败（`phone_disconnected`） | 明确失败 > 静默挂起 |
| R8 | **绝不自动重放**任何请求 | 工具可能有副作用 |
| R9 | 单帧最大 `MAX_PHONE_MESSAGE_BYTES`（默认 65 536），超限回 `error` 并计数 | 防内存放大攻击 |
| R10 | 并发待处理上限 `MAX_PENDING_REQUESTS`（默认 16），超限直接拒绝新请求 | 同上 |
| R11 | 每次成功握手 `connection_generation += 1` 并落盘；新连接会关闭同设备的旧连接（close code `4001`） | 防重连竞争 |
| R12 | 未知 `request_id` 的 pong 一律拒绝并计入 `unknownPongs` | 防伪造/迟到响应 |
| R13 | Durable Object 重建后，存储里遗留的待处理请求一律记为失败，**绝不**产生虚假成功 | 休眠/驱逐的正确处理 |

---

## 6. 失败原因码（`phone_ping` 工具返回值 / DO 内部错误）

| 码 | 含义 | 是否可重试 |
| --- | --- | --- |
| `phone_offline` | 该设备当前没有已建立的连接 | 是（等手机连上） |
| `phone_timeout` | 请求已发出且在超时时间内没有收到合法 pong | 视工具语义而定 |
| `phone_disconnected` | 等待期间连接断开 | 视工具语义而定 |
| `too_many_pending_requests` | 超过并发上限 | 是（退避后） |
| `unknown_device` | `device_id` 不在 `DEVICE_TOKENS` 中 | 否 |
| `ambiguous_device` | 未指定 `device_id` 且无法唯一推断 | 否（补上 `device_id`） |
| `invalid_pong` | 收到 pong 但 `request_id` 不匹配当前待处理项 | 否（是 bug） |

Gate 0 **不做**自动重试：上层（人或未来的调度器）看到 `isError: true` 自行决定。

---

## 7. 心跳与超时的默认值（全部可配置）

| 参数 | 默认 | 配置来源 |
| --- | --- | --- |
| Ping timeout | 10 s | Worker `PHONE_PING_TIMEOUT_MS`；手机侧 `HubConfig.pingTimeoutMs` |
| Heartbeat interval | 30 s | Worker `HEARTBEAT_INTERVAL_MS`；手机侧 `HubConfig.heartbeatIntervalMs` |
| Reconnect initial delay | 1 s | 手机侧 `HubConfig.reconnectInitialDelayMs` |
| Reconnect max delay | 60 s | 手机侧 `HubConfig.reconnectMaxDelayMs` |
| Jitter ratio | 0.2（±20%） | 手机侧 `HubConfig.reconnectJitterRatio` |
| Max pending requests | 16 | Worker `MAX_PENDING_REQUESTS`；手机侧只读展示 |
| Max frame bytes | 65 536 | Worker `MAX_PHONE_MESSAGE_BYTES` |

---

## 8. 未知类型与向前兼容

- 手机收到不认识的 `type`：忽略并记日志，**不回错误**（服务端可能更新了）。
- 服务端收到不认识的 `type`：回 `error{code:"unsupported_type"}`，不断开连接。
- 任何一方的未知字段一律忽略，不因此拒绝整帧（`type` 与 `request_id` 除外）。
