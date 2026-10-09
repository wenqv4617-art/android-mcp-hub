# ARCHITECTURE.md — Android MCP Hub（Gate 0）

本文说明这套东西**为什么长这样**。凡是"我们选了 X 而不是 Y"的地方，我都把 **why** 写出来 —— 没有理由的架构决策会在下一次评审时被当成迷信。

相关文档：[README.md](README.md) · [SECURITY.md](SECURITY.md) · [docs/PROTOCOL.md](docs/PROTOCOL.md) · [TEST_REPORT.md](TEST_REPORT.md)

---

## 1. 一张图 + 每一跳的职责

```
ChatGPT 官方客户端
   │  HTTPS · MCP Streamable HTTP · POST /mcp
   ▼
┌──────────────────────── Cloudflare Worker ────────────────────────┐
│  worker/src/index.ts       路由：/healthz  /  /mcp  /phone/ws      │
│  worker/src/mcp/http.ts    MCP 协议：鉴权 → 会话 → 方法分发        │
│  worker/src/mcp/tools.ts   phone_ping 的定义 / 参数校验 / 结果映射 │
└──────────────────────────────────────────────────────────────────┘
   │  DO Binding（平台内部 fetch，带 X-Phone-Session-Device）
   ▼
┌──────────────────── Durable Object: PhoneSession ─────────────────┐
│  worker/src/phone/phone-session.ts                                │
│  · 一台手机一个对象（idFromName(deviceId)）                        │
│  · 连接代次仲裁：新连接顶掉旧连接                                  │
│  · pending 请求表：request_id → {socket, sentAt, generation, ...}  │
│  · 超时（内存 setTimeout + DO alarm 双保险）、断线、重建清理        │
└──────────────────────────────────────────────────────────────────┘
   │  WebSocket（自定义内部协议 phone-link-v1，见 docs/PROTOCOL.md）
   ▼
┌──────────────────── Android App（独立 APK）───────────────────────┐
│  android/  Kotlin + Compose + OkHttp + ForegroundService          │
│  · 手动连接/断开、状态、最近 request_id、最近 RTT、日志            │
│  · 收到 phone_ping → 校验 → 回 phone_pong（带 LRU 去重）           │
└──────────────────────────────────────────────────────────────────┘
   │  localhost HTTP
   ▼
本地 MCP 应用        ⛔ Gate 0 未接入（本阶段不存在这一跳）
```

另一个并行的 Durable Object 是 `McpSession`（`worker/src/mcp/session-do.ts`），它不在这条数据链路上，只负责 ChatGPT 那侧的会话生命周期。

---

## 2. 关键决策

### 2.1 为什么一台手机 = 一个 Durable Object

**要解决的问题**：一台手机的受信任连接必须**恰好有一个**。如果两个 Worker isolate 各自持有一份"这台手机的 socket"状态，重连时就会出现"新旧连接并存"，然后旧连接上迟到的 pong 可能把一个不属于它的请求标成成功 —— 那是一个**假成功**，比失败坏得多。

**为什么 DO 能解决**：Durable Object 的寻址语义保证"同一个 name 只有一个实例"。用 `idFromName(deviceId)`，那么"这台手机的状态"天然收敛到一个点上，不需要分布式锁、不需要一致性协议、不需要数据库。这不是把 DO 当数据库用，是把它当**一个带存储的单线程 actor** 用。

**反面**：如果用 KV 或者内存缓存存 socket 状态，就必须自己实现"谁是新连接"的仲裁，而 socket 本身（`WebSocket` 对象）也没法跨 isolate 共享。DO 的 WebSocket Hibernation API 正好是为这个场景设计的。

### 2.2 连接代次（generation）：整个系统里最重要的一条不变量

```ts
// worker/src/phone/phone-session.ts  handleConnect 的顺序（顺序本身就是设计）
1. generation += 1                     // 先自增
2. ctx.acceptWebSocket(server, ["gen:" + generation])
3. 用 close code 4001 关掉旧 socket     // 再关旧的
4. storage.put({generation})
5. 发 welcome
```

**为什么顺序不能换**：从第 2 步返回的那一刻起，就只有标着**新**代次的 pong 才可能被接受。如果先关旧 socket 再自增代次，中间会出现一个窗口：旧连接还能发帧、而代次还是旧值 —— 那个窗口里的 pong 会被当成合法响应。

**三个位置都在查代次**：

| 位置 | 行为 |
| --- | --- |
| pong 到达时 | `entry.generation !== this.generation \|\| socketGeneration !== this.generation` → 记 `pongsStaleGeneration` + 回 `invalid_pong`，**不结算那个请求** |
| socket 断开时 | socket 代次 ≠ 当前代次 → **直接返回**，不动当前请求（旧 socket 的死活与新连接无关） |
| 返回给调用方 | 成功结果里带上 `connection_generation`，让"这个结果来自哪一代连接"是可审计的 |

代次是**持久化**的（存在 DO 存储里）。所以即使对象被驱逐后重建，代次也不会归零 —— 不会出现"重建后旧连接的帧突然变成有效"。

### 2.3 pending 请求：注册顺序决定有没有竞态

```ts
// handlePingRequest
1. 检查离线 / 并发上限
2. 把请求登记进 this.pending，并写存储镜像
3. rescheduleAlarm()          // DO 侧超时保险
4. entry.timer = setTimeout() // 内存侧超时保险
5. socket.send(phone_ping)    // ← 最后才发
```

**为什么发帧必须在登记之后**：如果先发再登记，手机（尤其在同一台机器的回环上）可能在登记完成之前就把 pong 发回来。此时 `webSocketMessage` 查表查不到，会把它当成 `unknown_request_id` 拒绝，然后那个请求会一直挂到超时 —— **一个本该成功 8ms 完成的 ping，变成了 10 秒超时**。这是个真实的、只在快网络下出现的竞态，所以顺序被写死并加了注释。

### 2.4 `settlePending` 是唯一结算入口

一个请求的结局有六种：`pong` / `timeout` / `timeout_alarm` / `disconnect` / `send_failed` / 重建时清理。如果每条路径各自去"删表、清定时器、加指标"，早晚会有某条路漏掉一步，指标和状态就会分叉。

所以只有一个函数做结算，内部顺序固定：

```
查表 → 删表 → clearTimeout → （超时类才 metrics.timeouts += 1）
     → 删存储镜像 + storage.put → flushMetrics() → rescheduleAlarm()
     → 最后才 entry.settle(build({...entry}))
```

**为什么 `entry.settle` 放最后**：先让调用方拿到结果、再改本地状态的话，调用方可能在状态还是旧的时候读指标。反过来（先改状态再 resolve）保证"拿到结果时，账已经记好了"。

### 2.5 超时为什么是双保险

- **内存 `setTimeout`**：正常情况下的快速路径。
- **DO `setAlarm`**：对象在超时前被驱逐/休眠时，内存定时器会没。`rescheduleAlarm()` 取所有 pending 的 `deadline` 最小值设闹钟；没有 pending 就 `deleteAlarm()`。`alarm()` 回放时用**存储镜像**把过期请求记成超时。

**代价**：同一件事被记两次的可能 —— 所以 `cause` 有 `"timeout"` 与 `"timeout_alarm"` 两种，且都只经 `settlePending`，第二次进来时表里已经没有这个 request_id 了，自然是无操作。

### 2.6 对象重建：绝不产生虚假成功

构造函数里 `blockConcurrencyWhile` 装载 `generation` / `metrics` / `pending`，然后：

> **重建时如果存储里的 `pending` 非空 → 全部记 `reconstructedPendingFailed` 并清空。**

**为什么**：对象被驱逐意味着那些请求的**调用方已经等不到了**（原来的 fetch 早断了）。如果重建后还把它们留在表里，一个恰好在此时到达的、属于上一代的 pong 就可能把它们"完成"掉，产生一个没有真实调用方、却有成功记录的幽灵结果。宁可记账说"这些请求在重建中失败了"。

（Harness 里刻意复用了旧存储来构造这个场景：`worker/test/harness/runtime.ts` 的 `stateFor()` 在实例被驱逐后重建时会复用同一个 storage —— 这就是"持久化状态还在、内存态没了"。有专门测试守着。）

### 2.7 为什么 Worker 里不可能伪造 pong

这是任务书里最硬的一条要求。它靠**依赖方向**保证，而不是靠自律：

```
worker/src/mcp/tools.ts 的 dispatchToolCall(config, name, rawArgs, callPhonePing)
                                                          ↑
                            转发函数是**注入**的，工具层不认识 DO
```

- 工具层（`tools.ts`）**不 import** `PhoneSession`，也不 import 任何 DO 相关的东西。它对"转发"的全部认知就是"有一个返回 `PingOutcome` 的异步函数"。
- 唯一真实的转发实现在 `worker/src/mcp/http.ts`：`callPhonePingViaDurableObject`，它做 `env.PHONE_SESSION.get(idFromName(deviceId)).fetch(...)`。全仓库只有这一处。
- 成功的结果只能由 DO 返回的 `PingSuccess` 构造，而 DO 只在**匹配到当前代次的、request_id 对得上的 pong** 时才构造它。

**这条不变量是被测试钉住的**（`worker/test/tools.test.ts`）：给 `dispatchToolCall` 注入一个**永不 resolve** 的转发函数，然后断言整个调用一直挂起（`Promise.race` 必须拿到 `"still-waiting"`）。如果哪天有人在工具层加了一句"本地兜底 / 本地超时后自己造个成功"，这个测试会红。

### 2.8 工具层与 HTTP 层为什么分开

| | 职责 | 输入 | 输出 |
| --- | --- | --- | --- |
| `mcp/tools.ts` | 工具是什么、参数合法吗、结果长什么样 | `RuntimeConfig` + 原始 arguments + 转发函数 | `ToolCallDispatched{result?, protocolError?}` |
| `mcp/http.ts` | HTTP/MCP 传输层：鉴权、会话、头、Content-Type、Accept | `Request` + `Env` | `Response` |

**为什么**：这样"参数校验"和"转发"可以脱离 HTTP 被单测（`tools.test.ts` 就是这么测的：转发层调用次数、永不 resolve 的行为、失败不会被翻成成功），而"HTTP 语义"可以脱离工具被单测（`mcp-http.test.ts` 31 个用例）。如果揉在一起，这两类测试都写不出来。

**一个刻意的选择**：执行失败用 **`result.isError = true`**（工具执行结果），协议失败才用 **JSON-RPC error**。"手机离线"不是协议错误 —— 请求本身完全合法，是**工具执行**失败了。混用会让客户端分不清"我的请求写错了"和"环境不满足"。

### 2.9 为什么不用 JSON-RPC batch

`worker/src/mcp/jsonrpc.ts` 收到数组直接回 `-32600`。

**理由**：MCP 2025-06-18 已经移除了 JSON-RPC batch。保留它意味着要处理"部分成功、部分失败"的语义，而这些语义在 MCP 里没有用 —— 支持一个被协议删掉的东西，只是多一块可能出错的代码。

### 2.10 会话为什么是"先到先得"的协议版本

`McpSession.handleCreate` 对已经在的会话**不改写** `protocolVersion`，只把 `initializeCount` 加一，并在版本不同时记一条 warn 日志。

**理由**：`handleValidate` 是拿请求头跟 `record.protocolVersion` 比对的。如果会话建立时协商的版本能被后续 initialize 改写，那么"会话建立时协商的版本"就不再是一份可信约定，校验就没有意义了。**这条不变量应该由拥有状态的这一层保证**，而不是依赖"调用方永远记得换 session id"（虽然 HTTP 层目前确实每次都生成新的）。

### 2.11 内部协议的 RTT 为什么必须在服务端测

手机回的 `phone_pong` 里带自己的 `timestamp`，但**RTT 由服务端用 `Date.now() - entry.sentAt` 算**。

**为什么不能信手机报的 RTT**：手机的时钟不可信（可能没对时、可能是用户改的），而且"手机自己说自己很快"没有任何验证价值。服务端测的是**端到端真实往返**，包含网络、包含手机处理、包含它自己排队的时间。这是唯一有意义的数。

---

## 3. 参数与默认值

| 参数 | 默认 | 上限/下限 | 谁在用 |
| --- | --- | --- | --- |
| `PHONE_PING_TIMEOUT_MS` | 10000 | [**50**, 60000] | DO 单次请求超时；也可被 `tools/call` 的 `timeout_ms` 覆盖（同样夹在 [50, 60000]） |
| `HEARTBEAT_INTERVAL_MS` | 30000 | [5000, 300000] | 手机心跳间隔 |
| `MAX_PENDING_REQUESTS` | 16 | [1, 256] | DO 并发闸门 |
| `MAX_PHONE_MESSAGE_BYTES` | 65536 | [1024, 1048576] | 单帧上限 |

**为什么下限要低到 50ms**：任务书要求"这些参数必须可配置"。夹紧的意义是挡 `0` / 负数 / 荒谬大值，**不是替运维决定"多快算快"**。测试手机就在回环上，200~400ms 的超时是完全合理的配置。

> ⚠ 这里踩过一个真实的坑：下限原本是 `1000`，导致测试里设 `PHONE_PING_TIMEOUT_MS=200` 被**静默夹成 1000**，还在 `config_problems` 里塞了一条（于是 `/healthz` 变 `degraded`）。结果是**所有超时测试都以错误的理由通过**（耗时 1015ms 而不是 215ms）。修好之后整个套件从 6.28s 降到 2.41s，超时路径才真的被测到。**教训**：夹紧区间必须与单次请求的夹紧区间保持一致，否则配置值与实际行为会悄悄分叉 —— 而且"测试全绿"完全掩盖了它。

---

## 4. Android 侧的生命周期

```
用户点"连接"
   → 启动前台服务（specialUse）
   → OkHttp WebSocket 连 wss://<host>/phone/ws（Authorization + X-Device-Id）
   → 握手成功 → 发 hello → 等 welcome
   → 维持：每 HEARTBEAT_INTERVAL_MS 发一次 heartbeat，收 heartbeat_ack
   → 收到 phone_ping → PingResponder 校验（LRU 去重）→ 回 phone_pong
   → 断线 → 指数退避 + 随机抖动 → 重连（初始 1s，最大 60s）

用户点"断开"
   → 置"用户意图 = 停止"标志
   → 关 socket、停前台服务
   → ★ 之后任何网络恢复事件都**不得**触发重连
```

**"手动停止后禁止自动重连"的实现要害**：不是"记得不要重连"，而是**把用户意图变成一个状态位**，让重连决策必须读它。网络恢复回调（`registerNetworkCallback`）只负责"通知可以重连了"，不负责"决定要不要重连" —— 决定权在状态机上。

**纯逻辑被刻意抽出来做 JVM 单测**（不需要模拟器）：`Backoff`（退避+抖动）、`PhoneLinkProtocol`（帧解析）、`PingResponder`（LRU 去重）、`WsUrl`（URL 规范化）、`HubConfig`（配置的序列化/反序列化）。这些是**会因为逻辑错误而坑人**的地方，所以它们必须能在 CI 里被跑。视图和 Service 的粘合代码不写单测 —— 它们的正确性属于"真机人工验证清单"。

---

## 5. 依赖与版本

锁定这套组合是因为它们**在这个容器里真的编得过、跑得过**：

- Worker：TypeScript 5.9.3、vitest 4.1.11、wrangler 4.149.0、`@cloudflare/workers-types` 5.20261009.1、`@modelcontextprotocol/sdk` 1.32.1。
  （`@cloudflare/vitest-pool-workers` 0.23.0 已装但**用不上** —— 它要求真实 workerd，见 [TEST_REPORT.md](TEST_REPORT.md)。它已被上游改名为 `@cloudflare/vitest-plugin`。）
- Android：AGP 8.7.3、Kotlin 2.0.21、Gradle 8.11.1、Compose BOM 2024.12.01、OkHttp 4.12.0、compileSdk 35 / minSdk 26 / targetSdk 35、JDK 17。

**注意**：`worker/src/env.ts` 里的 `Env` 接口**刻意没有** `D1Database` / `KVNamespace` / `R2Bucket` / `Queue` 字段。这不只是"没用"，而是**编译期断言** —— 想用 D1 的人必须先改这个接口，那一刻就会有人问"你为什么要改"。并且有静态测试扫这个文件（`worker/test/security.test.ts`，先 `stripComments` 免得被注释误伤）。

---

## 6. 这套设计已知的边界

1. **单手机单连接**。`PhoneSession` 用 `idFromName(deviceId)`，所以是"一台设备一个 DO"。多设备 = 多对象，天然并行。
2. **没有持久化的请求日志**。Gate 0 没有 D1；指标活在 DO 存储里，只为测试和诊断。
3. **没有服务端主动通知**。`GET /mcp` 返回 `405 Allow: POST, DELETE` —— 明确关闭 SSE 通道。capabilities 里也**只声明 `tools.listChanged = false`**，不声明 resources / prompts / logging。**声明得少，就不欠**。
4. **手机端没有工具管理 UI**、不扫描本地应用、不自动启动别的应用、不做跨设备控制 —— 任务书要求"不实现"的，就是没实现。
5. **workerd 上未验证**（环境限制，见 TEST_REPORT.md）。这是 Gate 0 最大的未验证项。
