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

### 3.5 `tool_request`（Gate 2：通用只读能力调用）

```jsonc
{
  "type": "tool_request",
  "request_id": "8b1f...",     // 服务端生成，与 phone_ping 共用一个 request_id 空间
  "tool_name": "battery_get_status",
  "arguments": {}               // 见下方"arguments 的三种形态"
}
```

**`arguments` 的三种形态**（手机侧 `parseIncoming`）：

| 形态 | 处理 |
| --- | --- |
| 键不存在 / 值为 `null` | 等价于"没有参数"，按空对象执行 |
| 是对象 | 正常校验每个键 |
| **存在但不是对象**（数组 / 字符串 / 数字 / 布尔） | 回 `tool_result{ok:false,error.code:"invalid_arguments"}`，**绝不**按空参数执行 |

第三行不能退化成第二行：用空参数执行一个本该带参数的工具，会产出一个"看起来成功、
其实答错了"的结果。此时 `request_id` 是拿得到的，所以手机**立刻回帧**，
不让服务端白等到超时。

`tool_name` 必须在**双端白名单**里：

- Worker 侧：`worker/src/mcp/tools.ts` 的 `TOOL_REGISTRY`（显式注册）**且**未被 `PHONE_DISABLED_TOOLS` 禁用。
- 手机侧：Android 的能力表（见工作包 D）**且**用户没有关掉"只读能力总开关"。

任何一侧不认识这个名字，都必须回 `tool_result{ok:false,error.code:"unsupported_tool"}` ——
**不允许**"尽力而为"地执行一个没注册的东西，也不允许服务端替手机放行。

这一帧与 `phone_ping` 的区别只有一点：它不是"测量链路"，而是"请求一次能力"。
超时、并发上限、代次校验、重复 `request_id` 拒绝、断线结算**全部共用同一套逻辑**。

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

### 4.4 `tool_result`（Gate 2）

```jsonc
// 成功
{ "type": "tool_result", "request_id": "8b1f...", "ok": true,
  "result": { "battery_level_percent": 42, "charging": false,
              "charging_source": "unavailable", "power_save_mode": true } }

// 失败
{ "type": "tool_result", "request_id": "8b1f...", "ok": false,
  "error": { "code": "tool_failed", "message": "..." } }
```

解析规则（`worker/src/phone/messages.ts`）：

| 情况 | 结果 |
| --- | --- |
| `request_id` 缺失/非字符串/空 | `invalid`（`tool_result_missing_request_id`）→ 回 `error{malformed_message}` |
| `ok` 不是布尔 | `invalid`（`tool_result_missing_ok`） |
| `ok:true` 但 `result` 不是对象 | `invalid`（`tool_result_bad_result`） |
| `ok:true` 同时带 `error` | `invalid`（`tool_result_conflicting_error`） |
| `ok:false` 但缺 `error` / `error.code` | `invalid` |
| `ok:false` 同时带 `result` | `invalid`（`tool_result_conflicting_result`） |
| `error.code` 是没见过的名字 | **不丢帧**，归一化成 `tool_failed` |
| 手机侧主动拒绝（参数格式错 / 用户关了开关 / 未知工具 / 执行失败） | 仍然是一条合法帧，`ok:false` + 对应 code |

最后一条是刻意的：丢帧会让一个**已经拿到结果**的请求白等到超时，
而超时是"不知道结果"，比"知道失败了"更糟。

信封不可被结果污染：`result` 里的 `ok` / `request_id` / `device_id` / `tool_name` /
`rtt_ms` / `server_timestamp` / `device_timestamp` / `connection_generation` /
`error_code` / `error_message` 一律被**丢弃并记日志**（`tool.result_reserved_key_dropped`）。
手机的返回是数据，不是信封。

### 4.5 设备凭据撤销（Gate 1.5 / A1）

撤销不是一条线上帧，而是一个**受保护的内部入口**：

- `POST /internal/phone/revoke`（`ROUTE_REVOKE`），body `{deviceId, reason?}`。
- **没有任何公网路由映射到它**（`worker/test/phone-lifecycle.test.ts` 用源码断言守住这条）。
- Worker 在两个同步点尽力调用它：① 设备升级被拒（凭据已撤销）时；② 工具调用撞上已撤销设备时。
- DO 侧的保证不依赖这次通知能否送达：**撤销状态落盘**（`revoked_device_ids`），
  DO 重建后依然生效，`testing/reset` 也清不掉它。
- 撤销的效果：在途请求全部结算为 `device_revoked`（不是超时），所有 socket 以
  close code **`4002`** 关闭，之后的任何升级与任何工具请求都被 403 拒绝。

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
| R14 | `phone_ping` 与 `tool_request` **共用**一个待处理表、一个并发额度、一条超时逻辑 | 两条通道各写一份必然写出"只在一条上复现"的 bug |
| R15 | 一条 `phone_pong` 绝不允许结算 `tool_request`，一条 `tool_result` 绝不允许结算 `phone_ping`（即使 `request_id` 相同） | 通道串台 |
| R16 | 同 `request_id` 跨通道重复登记 → 409 `duplicate_request_id`，已有请求不受影响 | 防覆盖 |
| R17 | 应用层存活：记录最近一条**合法**上行帧时间；静默超过 `PHONE_LIVENESS_TIMEOUT_MS`（默认 90 s）判定链路已死 | `readyState` 分不出半开连接 |
| R18 | 判定链路死亡时：`connected=false`、在途请求结算为 `phone_disconnected`、主动 `close(4000)` | 半开连接必须快速失败，而不是拖满超时 |
| R19 | 垃圾帧（非 JSON / 未知类型 / 缺字段）**不**刷新存活时间 | 那正是更该判死的情形 |
| R20 | 凭据撤销后：新升级 403、新工具请求 403 `device_revoked`、在途请求结算为 `device_revoked`、socket 以 `4002` 关闭 | 撤销必须真的断掉 |
| R21 | 未知工具名 / 未知参数 / 越界参数一律是 **JSON-RPC 协议错误**（`-32602`），不产生 tool result，也不碰转发层 | 调用方必须知道自己的请求没被接受 |

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
| `device_revoked` | 该设备的凭据已被撤销 | 否（需重新配发凭据） |
| `unsupported_tool` | 工具名不在白名单里（服务端或手机侧） | 否（是调用方写错了） |
| `invalid_arguments` | 参数结构不合法（手机侧拒绝） | 否（改参数） |
| `tool_failed` | 能力执行过程中失败（**也是未知错误码的归一化目标**） | 视能力语义而定 |
| `tool_unavailable` | 能力此刻不可用（例如系统服务拿不到） | 是（稍后重试） |
| `not_permitted` | 服务端用 `PHONE_DISABLED_TOOLS` 关掉了这个工具 | 否 |

Gate 2 的工具失败码（`tool_result.error.code`）是一个**闭集**，见
`worker/src/phone/messages.ts` 的 `TOOL_RESULT_ERROR_CODES`；手机回别的名字会被归一化成
`tool_failed`，并保留原始 message。

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
| Liveness timeout | 90 s（= 3 个心跳周期） | Worker `PHONE_LIVENESS_TIMEOUT_MS` |
| Disabled tools | 空 | Worker `PHONE_DISABLED_TOOLS`（逗号分隔的工具名） |

存活阈值取 3 个心跳周期是刻意的：慢一个周期（手机后台被降频、网络抖动一次）不该被误断，
而半开连接最迟 90 秒就会变成一次明确的 `phone_offline`，而不是拖满整个 `timeout_ms`。

⚠ 新增任何 env 字段，必须同时加进 `worker/src/config.ts` 的 `cacheKey` 数组，
否则改了不生效（配置按这个键缓存）。

---

## 8. 未知类型与向前兼容

- 手机收到不认识的 `type`：忽略并记日志，**不回错误**（服务端可能更新了）。
- 服务端收到不认识的 `type`：回 `error{code:"unsupported_type"}`，不断开连接。
- 任何一方的未知字段一律忽略，不因此拒绝整帧（`type` 与 `request_id` 除外）。

---

## 9. Gate 2 工具通道：为什么这样设计

这一节是设计理由，不是接口规范；接口在上面 §3.5 / §4.4。

**为什么 ping 与 tool 共用一套待处理逻辑。**「登记 → 发帧 → 等结果 → 结算」这段顺序里，
每一步都有一个不写对就出错的细节（先登记后发帧、先武装定时器再落盘、记账期间被结算就绝不发帧、
发送失败立刻按 `phone_disconnected` 结算）。如果两条通道各写一份，任何一处写歪都会变成
"只在某一条通道上复现"的缺陷。所以只有 `enqueueRequest()` 一份实现，两条通道都从它走。

**为什么通道要被显式标记。** `ToolDefinition.transport` 是 `"ping" | "tool"` 的闭集，
每个工具必须声明。写错一个字符就会让某个工具静默走错路线 —— 那是最难发现的一类 bug，
所以 `worker/test/tools.test.ts` 里逐条断言了每个工具的 `transport`。

**为什么服务端也要有一份开关。** 注册表决定"存在哪些工具"，`PHONE_DISABLED_TOOLS` 决定
"此刻允许调用哪些"。只靠注册表的话，关掉一个能力就得改代码 + 重新部署；只靠开关的话，
一个发现漏洞就能让运维在不知道代码的情况下把它暴露出去。

**为什么手机是最后一道白名单。** 服务端注册表可能被改错、被绕过、被未来版本加上新工具。
手机侧的能力表是**编译期**的，不认识的 `tool_name` 表里查不到，直接回 `unsupported_tool`。
"服务端能调用什么"和"手机肯执行什么"是两条独立的否决权。

**为什么手机侧还要再分"白名单"和"用户开关"两层。** 白名单是编译期的（"这个版本里
存在哪些能力"），开关是运行期的（"用户此刻允许不允许用"）。关掉开关时能力**仍然注册着** ——
协议上必须认得这个名字，才能回一个明确的 `not_permitted`；一个"查不到的装备名"回
`unsupported_tool` 会让用户以为是版本不对，而不是"我自己关的"。开关由
`HubPrefs.capabilitiesEnabled()` 提供，分发器**每次调用都重读**，所以拨开关立即生效、
不需要重连。开关的**安全默认是关**（`HubPrefs.DEFAULT_CAPABILITIES_ENABLED = false`，
由 `CapabilitySwitchDefaultTest` 钉住）：首次安装时服务端调用会被回 `not_permitted`，
用户必须在设置页显式打开。这个默认值是安全取舍 —— "先拒绝、等用户授权"，
而不是"先允许、等用户发现"。

**为什么 `not_permitted` 排在参数校验之前。** 不允许执行的能力，不该因为"参数不合法"
而漏出任何关于它自己的信息（连"它接受哪些参数"都不该漏）。

**为什么禁止任何形式的通用执行。** 这个协议里没有 `shell`、没有 `intent`、
没有 `reflection`、没有 `file_path`、没有 `url`。新增一个能力 = 新增一个显式的
`ToolDefinition` + 手机侧一条显式的分支 + 两侧各自的测试。没有"通用执行"这个逃生口。

---

## 10. Gate 2.5 的两个只读能力：字段契约

`device_get_network` 与 `device_get_storage` 与 `battery_get_status` 同形：
`transport = "tool"`、**零能力参数**（`allowedArgs` 为空，只有 `device_id` / `timeout_ms` 是
信封参数）、只读幂等。它们的 `result` 字段（`tool_result.result`，Worker 摊平进
`structuredContent`）如下 —— 手机侧 `OUTPUT_KEYS`、Worker 侧 `outputSchema`、
E2E 断言三处必须同时包含这些键：

**`device_get_network`**

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `connected` | boolean | 系统是否有默认网络（**不等于**互联网可用） |
| `validated` | boolean \| null | 系统是否报告该网络通过了联网验证；`null` = 系统没报 |
| `transport` | string | 主要传输类型，或 `"none"`（无网络）/ `"unknown"`（有网络但认不出类型） |
| `transports` | string[] | 全部已知传输类型（去重、去掉 `"unknown"`），如 `["vpn","wifi"]` |
| `metered` | boolean \| null | 是否计费网络；`null` = 系统没报 |
| `internet_capable` | boolean \| null | 系统认为该网络具备互联网能力 |

优先级（多个传输并存时 `transport` 取哪个）：VPN > 以太网 > Wi-Fi > USB > 蜂窝 >
蓝牙 > Wi-Fi Aware > LoWPAN > Thread > 卫星。**不返回漫游状态**：可靠的
`isNetworkRoaming` 需要 `READ_PHONE_STATE`，靠网络类型反推就是编数据。
**不读** SSID / BSSID / MAC / IP / SIM 标识，也不主动访问外部站点去"测试"联网。

**`device_get_storage`**

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `volume` | string | 统计的是哪个卷，当前固定 `"internal_data"`（内部存储卷，经 `StatFs(filesDir)`） |
| `total_bytes` | number | 该卷总容量 |
| `available_bytes` | number | 该卷可用容量（夹在 `[0, total]`） |
| `used_bytes` | number | `total - available`，即**文件系统已占用**；**不代表用户文件占用** |
| `usage_percent` | number | 一位小数，如 `62.5`（是数字，不是 `"62.5%"`） |

容量为 0 / 不可访问时**明确失败**（`tool_unavailable`「存储卷不可用：系统报告的容量为 0」），
绝不返回一串 0 假装"手机是空的"。该能力不申请 `MANAGE_EXTERNAL_STORAGE`、
`READ_MEDIA_*` 或任何传统整盘权限，也不枚举用户文件。

---

## 11. Gate 3：第三方 MCP 工具聚合（复用 `tool_request` / `tool_result`）

Gate 3 让 Android 当**第三方 MCP 客户端**：它连真实 MCP 服务器、列出工具、等用户授权，
再让模型经聚合入口调用。关键决定：**不为它新增任何帧类型**。

理由：`tool_request` / `tool_result`（§3.5 / §4.4）已经把"服务端要手机做一件事、
拿回一个结果"这条路走通了 —— request_id 全链透传、Worker 侧唯一 pending 表负责结算、
代次校验与去重都在老路上。为聚合再发明一对帧，只会把攻击面和回归面各扩大一倍。

于是聚合的两个入口就"长"在既有通道上，作为两个**保留命令名**下发：

| 命令名 | 语义 | `arguments` |
| --- | --- | --- |
| `hub_list_tools` | 列出可用的第三方工具目录 | `{ "server_id"?: string }`，省略表示全部服务器 |
| `hub_call_tool` | 调用某个第三方工具 | `{ "server_id": string, "tool_name": string, "arguments"?: object }` |

**这两个名字由手机侧在能力白名单查找之前抢先识别**（`HubCommandRouter`），
永远进不了 `BuiltInCapabilities` 的本机能力路径；反过来，本机能力名（`battery_get_status`
等）也永远不能被当成第三方工具名去路由。两条路互不借道。

`server_id` 形如 `^[a-z0-9][a-z0-9_-]{0,63}$`（本地生成，与 URL 解耦），
`tool_name` 形如 `^[A-Za-z0-9_-]{1,128}$`。

### 11.1 `hub_list_tools` 的结果（`tool_result.result`）

```json
{
  "servers": [{ "server_id": "...", "server_name": "...", "availability": "online" }],
  "tools": [{
    "server_id": "...",
    "tool_name": "...",
    "display_name": "...",
    "description": "...",
    "input_schema": { },
    "fingerprint": "……32 位十六进制……",
    "authorization_state": "authorized",
    "availability": "online"
  }],
  "other_tools_count": 3
}
```

约定（两侧都有测试钉住）：

* **没被用户授权、服务器离线、以及超出上限的工具，不进 `tools` 数组**，只让
  `other_tools_count` 变大 —— 模型看不到不存在于它职权范围内的东西，
  而"被藏起来的数量"仍然如实报出来，不是偷偷摸摸。
* `authorization_state` 只有 `"authorized"` 才会出现在数组里（其余状态在手机本地可见）；
  服务端仍会**再判一次**，两端都不信对方。
* `fingerprint` 是工具定义的哈希（含服务器地址、工具名、标题、描述与**规范化后的**
  `input_schema`）。定义一变，指纹就变，旧授权失效 —— 这是"授权的是定义，不是名字"。
* `input_schema` 可能因超过展示上限而缺失；缺了**不代表定义变了**，指纹按服务器原始
  schema 计算。
* `tool_id`（`mcp:<server_id>:<tool_name>`）由 **Worker 派生**，手机不回传这个字段：
  身份只有一个来源，少一处可以不一致的地方。

### 11.2 `hub_call_tool` 的结果（`tool_result.result`）

```json
{
  "server_id": "...", "tool_name": "...", "tool_id": "mcp:...:...",
  "is_error": false,
  "content": [{ "type": "text", "text": "..." }],
  "content_types": ["text"],
  "structured_content": { },
  "truncated": false
}
```

* 第三方工具**自己报的**失败走 `is_error: true`（不是把成功包装成失败，也不是把失败
  包装成成功）；传输层/授权层的失败用错误码走 `tool_result.error`。
* 结果里的保留字段（`type`/`ok`/`request_id`/`device_id`/`server_id`/`tool_id` …）
  不能被第三方内容覆盖：手机侧重建结果对象，冲突键**丢弃**并记一条告警。
* 非文本内容只上报**类型**，正文不外泄（图片/音频的 base64 不进日志、不进模型上下文）。
* 错误码闭集（§6 之外新增的映射）：服务器连不上 → `tool_unavailable`；
  未授权/定义已变 → `not_permitted`；参数不合 schema → `invalid_arguments`；
  其余（HTTP 错误、协议错误、超时、结果过大、服务器拒绝）→ `tool_failed`。
  旧版 App 收到这两个保留命令会回 `unsupported_tool`，服务端如实转述成"这台手机
  还不支持聚合"，不伪造空目录。

## 12. 连接诊断（V1.6.3）

诊断是**观测**通道，不是控制通道：服务端只把手机上报的事件规整后写进该设备的
诊断缓冲区，永远不会据此建立/关闭连接或改变鉴权结果。

### 12.1 手机 → 服务端：`diag_event`

```json
{
  "type": "diag_event",
  "event": "socket_failure",
  "client_at": 1760000000000,
  "category": "connection_reset",
  "exception_class": "SocketException",
  "close_code": 4001,
  "generation": 52,
  "network": "wifi", "vpn": true, "screen": "off",
  "pid": 1234, "process_uptime_ms": 60000, "android_sdk": 36,
  "app_version": "1.6.3",
  "local_close_source": "ui_button",
  "heartbeat_age_ms": 32000,
  "attempt": 3
}
```

* `event` 闭集：`connected` / `disconnected` / `reconnecting` / `network_changed` /
  `heartbeat_timeout` / `authentication_failed` / `user_disconnected` /
  `service_destroyed` / `service_restarted` / `server_close` / `socket_failure` /
  `handshake_timeout` / `transport_close`。认不出的值整条丢弃。
* `category` 闭集见 `diagnostics.ts` 的 `FAILURE_CATEGORIES`；`network` / `screen` /
  `local_close_source` 也都是闭集。`exception_class` 只接受标识符形状
  （`^[A-Za-z_$][A-Za-z0-9_$.]{0,63}$`），**不采集异常 message**。
* `client_at` 是手机时钟；断网期间事件会排队、连上后补发，因此可能早于服务端
  收到它的时刻。合并时间线必须同时使用两个时间戳，不能假设两端时钟一致。
* 每 60 秒最多 20 条；超出直接丢弃并计数。帧内的任何字段都不会让连接失败。

### 12.2 服务端 → 手机：`GET /phone/diagnostics`

鉴权与主连接相同：`X-Device-Id` + `Authorization: Bearer <device token>`。
只返回**该凭据对应设备**的事件；换设备 ID 读不到别人的日志，未认证一律 401。
响应带 `Cache-Control: no-store`，不含令牌、地址或请求参数。

```json
{
  "ok": true,
  "device_id": "…",
  "server_time": 1760000005000,
  "online": false, "socket_open": false, "stale": false,
  "connection_generation": 52,
  "last_phone_activity_at": 1760000000000,
  "events": [
    { "seq": 7, "at": 1760000000000, "client_at": 1759999999000,
      "source": "durable_object", "type": "replaced", "generation": 51,
      "close_code": 4001, "close_reason": "replaced_by_newer_connection",
      "server_initiated": true, "duration_ms": 30000, "detail": {} }
  ],
  "summary": { "last_online_at": 0, "last_offline_at": 0, "last_offline_reason": null,
               "last_offline_close_code": 4001, "replaced_events": 3 },
  "buffer": { "size": 9, "returned": 1, "dropped": 2,
              "max_events": 200, "max_age_ms": 86400000 },
  "close_codes": {
    "4000": "liveness_timeout",
    "4001": "replaced_by_newer_connection",
    "4002": "device_credential_revoked"
  }
}
```

* 每设备最多保留 **200 条 / 24 小时**，超出按最旧丢弃并累计 `dropped`；
  查询只返回最近的事件，`buffer` 如实报告是否发生过丢弃。
* 服务端主动关闭会在调用 `close()` **之前**登记关闭意图；`server_initiated`
  只有在能对上意图时才是 `true`，对不上是 `null`（"无法确定"），绝不编造。
* 服务端没收到正常关闭帧时，事件如实记为异常断连；`close_code` 只在确实观测到
  关闭帧时填写，不会用 1006 之类的码冒充。
* `close_codes` 是关闭码语义的**唯一来源**；客户端不得硬编码或凭码值猜原因。
