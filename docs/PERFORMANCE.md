# 性能与错误语义记录

本文件只记录**测过的东西**，并把"本地自动化"、"线上真实环境"、
"真实 Android 设备"分开写。没有测过的格子保持空白或写“未验收”，
不用一个 “all good” 盖过去。

---

## 1. 可复现的本地采样

### 1.1 命令

```bash
BENCH=1 BENCH_SAMPLES=20 npm run e2e
```

- 输出：`test-reports/local-performance.json`；
- 链路：官方 MCP SDK → 真实 HTTP → Worker/DO（**Node shim，不是 workerd**）
  → 真实 WebSocket → 手机替身子进程；第三方调用再经本地 MCP 测试服务器子进程；
- 每个场景预热 1 次，再取 20 个样本；
- 只适合做**同机相对比较**与数量级异常判断，不能当作线上 SLA。

同一台机器上的 Android 聚合层（JVM）另有热缓存采样：

```bash
cd android
./gradlew :app:testDebugUnitTest \
  --tests "com.androidmcphub.app.aggregation.McpHubCatalogPerformanceTest"
```

### 1.2 本地结果（2026-10-31，Windows 11，Node 24.18.0）

| 场景 | p50 | p90 | 样本 / 成功 | 观察到的缓存状态 |
| --- | ---: | ---: | ---: | --- |
| `phone_ping` | 15 ms | 17 ms | 20 / 20 | — |
| `hub_list_tools` · `servers_only` | 15 ms | 17 ms | 20 / 20 | `catalog_source=cache`，`availability_source=cache` |
| `hub_list_tools` · 完整目录 | 57 ms | 65 ms | 20 / 20 | `catalog_source=refresh`，`refresh_pending=false` |
| `hub_list_tools` · `limit=5` | 51 ms | 63 ms | 20 / 20 | `catalog_source=refresh` |
| `hub_get_tool_schema` | 48 ms | 63 ms | 20 / 20 | 详情层每次重新 `tools/list` |
| `hub_call_tool` · 第三方 `sum_numbers` | 63 ms | 78 ms | 20 / 20 | 真实第三方工具调用 |

> 手机替身的 MCP 桥**不做目录缓存**：每次完整目录都会真的调用一次上游
> `tools/list`。所以这里的 57 ms 是“协议往返 + 手机替身 + Worker 转发”的成本，
> 不是 Android 端缓存命中；缓存命中的数字见下一节。

Android 聚合层热缓存（40 个工具，同一进程内）：

```
hot-cache catalog: min=0ms median=0ms p95=2ms max=3ms
```

80 个工具逐页取完（每页 10 条）的功能断言另见
`McpHubCatalogPerformanceTest.B1b`：8 页、无缺失、无重复、
`total_tools`/`returned`/`has_more`/`next_offset`/`truncated` 一致，
首页 `catalog_source=refresh`、后续页 `cache`，全程只连一次。

---

## 2. 历史真机现象的结构性解释

历史报告里的形状是：

- 服务器目录约 350 ms；
- 工具目录 3.4–3.8 s，**刷新后仍然慢**；
- 麦当劳真实工具调用约 6.1 s。

先给结论：**不能把工具目录慢简单归因于“缓存没命中”**。
代码里有两个独立的结构性来源，分别对应两类场景：

### 2.1 3.4–3.8 s：`catalogDeadlineMs` 正好是 3 500 ms

`McpHubRouter.collectCatalog()` 在缓存为空或不新鲜时会**同步等待**刷新，
最多等 `HubLimits.catalogDeadlineMs`（默认 **3 500 ms**，见
`android/.../HubLimits.kt`）。因此：

- 冷缓存 + 一台慢/挂起的服务器 → 请求等满 3.5 s 才返回；
- 显式 `refresh=true` 会绕过 TTL，即使缓存是热的也重新等一次；
- 刷新仍在飞时，后续请求会共用同一个 future（单飞），**继续等新的截止时间**
  —— 这就是“刷新之后下一次还是慢”的形状。

自动化证据（不依赖真机）：

- `E2 冷缓存遇到挂起服务器会等到目录截止时间`：把截止时间调成 700 ms，
  两次请求都等到截止时间，证明等待由 deadline 决定；
- `D1 慢服务器被截止时间截断`、`A3 一台服务器离线不拖慢其余服务器`：
  证明不会退化成“每台 8 秒串行”。

这不是“缓存坏了”，而是**同步刷新策略**在慢上游上的代价。若要进一步优化，
方向是“过期缓存先返回 + 后台刷新 + 明确标记 `refresh_pending`”，
而不是继续调大超时或假定缓存一定命中。本轮没有改变这个语义，
因为它涉及“用户点刷新后是否必须看到本次结果”的产品决定。

### 2.2 6.1 s：第三方调用本身，不是网关重试

`hub_call_tool` 没有自动重试；上游调用受 `perServerTimeoutMs=8 000 ms` 约束。
6.1 s 落在“上游自己慢”的区间内，而不是网关超时后重放。
需要真机日志确认时，按 `request_id` 对齐：

- Worker 侧：`hub.trace request_id=… stage=… dur_ms=…`；
- Android 侧：同一条 `request_id` 的 HubTrace 阶段日志
  （`entry` / `connect` / `list_tools` / `execute` / `done total_ms=…`）；
- 第三方返回的 `is_error` 与原始文本必须原样保留。

### 2.3 业务失败 ≠ 传输失败

以麦当劳那类第三方为例：MCP 传输成功、HTTP 200、工具结果 `isError=false`，
但业务体里可能是 `success=false`。Hub 的约定是：

- **不猜参数、不自动重试、不改写业务字段**；
- 把 `content` / `structured_content` 原样交给调用方，由调用方判断业务成败；
- 只有传输/协议失败才映射成 `upstream_timeout` / `upstream_failure` 等错误码。

本地 e2e 的 `boom` 用例钉住了“工具自己报错要原样回传”这一条；
真实第三方业务失败仍需在线上采样时记录原始返回。

---

## 3. 分页与状态一致性

- 自动化：`B1`（250 个工具）、`B1b`（80 个工具逐页取完）；
- 断言：`total_tools` 每页不变；`returned` 与数组长度一致；
  `has_more` / `next_offset` 首尾一致；最后一页无 `next_offset`；
  `truncated=false`；工具名无缺失、无重复；
- 缓存状态：首页刷新、后续页缓存；`refresh_pending` 在缓存命中时为 false；
- Worker 侧 `hub-tools.test.ts` 另有上限、分页参数与越界拒绝的用例。

---

## 4. 线上真实环境采样

### 4.1 部署与采样条件

| 项 | 值 |
| --- | --- |
| Worker | `https://android-mcp-hub.causticrry.workers.dev` |
| 版本 ID | `8d267f9e-31db-45ef-8747-aad202301571`（2026-10-31 部署） |
| 部署运行 | [41179814832](https://github.com/wenqv4617-art/android-mcp-hub/actions/runs/41179814832) |
| 采样运行 | [41180041220](https://github.com/wenqv4617-art/android-mcp-hub/actions/runs/41180041220)（`verify_only=true`, `bench_samples=10`） |
| 采样位置 | GitHub 托管 Ubuntu runner（公网直连，非本机代理） |
| 鉴权 | 真实配对签发的 `hub_client_*` 受限令牌（值未打印） |
| 上游 | `https://docs.mcp.cloudflare.com/mcp`（公开文档 MCP，真实第三方） |
| 手机 | **未连接**；本轮没有真实 Android 设备 |

### 4.2 实测

| 场景 | p50 | p90 | 样本 / 成功 | 缓存状态 | 说明 |
| --- | ---: | ---: | ---: | --- | --- |
| `tools/list`（网关令牌，1 个已授权 `ext_*`） | 185.7 ms | 198.6 ms | 10 / 10 | `cache=none` | Worker + DO 鉴权与授权查询；不含原生目录 |
| 第三方只读工具调用（Cloudflare 文档搜索） | **4 040.6 ms** | **4 099.6 ms** | 10 / 10 | `cache=none` | min 3 896.1 ms / max 4 613.7 ms；含上游真实检索 |

对应的原始日志行（部署流水线输出，未打印令牌）：

```
BENCH tools/list: samples=10 p50=185.7ms p90=198.6ms min=181ms max=210.7ms cache=none
BENCH ext_read_call: samples=10 p50=4040.6ms p90=4099.6ms min=3896.1ms max=4613.7ms cache=none
```

解读：

- 同一次运行里，`tools/list` 约 0.19 s，而第三方调用约 4.0 s。差距不在网关转发，
  而在上游检索本身；
- 10 个样本的 p50 与 p90 很接近（4.04 s / 4.10 s），且 10 次全部成功，
  **没有观察到网关超时后重放**；历史上 6.1 s 的麦当劳调用落在这个上游延迟区间的上沿，
  与“上游自己慢”一致，而不是缓存失效；
- 本轮**没有**自动重试，也没有把业务失败改写成传输失败。

### 4.3 没有测到的线上格子

| 场景 | 状态 | 原因 |
| --- | --- | --- |
| `phone_ping` | 未采样 | 没有真实手机；`hub_client_*` 令牌按设计也不能调用原生工具 |
| `servers_only` / 完整 36 工具目录 / `limit=5` | 未采样 | 同上；需要 OAuth 或静态 MCP 令牌 + 已连接手机 |
| `hub_get_tool_schema` | 未采样 | 同上 |
| 真实客户端（ChatGPT / Claude Desktop / Codex）时延 | 未采样 | 没有真实账号；本轮只有官方 MCP SDK 客户端 |

这些格子不能用一个“手机离线”的快速失败冒充成功样本，因此保持未采样。

### 4.4 `request_id` 对应关系

- **原生工具路径**（`phone_ping`、`hub_*`、29 个 Android 工具）：Worker 在
  `worker/src/mcp/http.ts` 生成一次 `request_id`，写
  `hub.forward_started` / `hub.forward_finished` 日志，并随信封下发给手机；
  Android 的 `HubTrace` 用同一个 `request_id` 记录 `entry` / `connect` /
  `list_tools` / `execute` / `done`。三段可以按这个值对齐。
- **第三方 `ext_*` 路径**：V1 没有把网关内部的审计 ID 回传给下游；审计表里有
  每次调用的 `id` / `principal` / `serviceId` / `tool` / `outcome`，但客户端拿不到这个 ID。
  这是已知的可观测性缺口，不应假装三个系统天然共享同一个 `request_id`。
  本轮线上验收以“同一运行、同一工具、同一参数、10 次全部成功”作为对照，
  并用 `target_denied`、`confirmation_required`、票据重放等错误语义确认没有被静默重试。

---

## 5. 仍未验证的部分

- 真实 Android 设备上的网络、Doze、后台限制对时延的影响；
- 真实客户端（ChatGPT / Claude Desktop / Codex）的端到端时延；
- 第三方 `ext_*` 路径的 `request_id` 回传（见 4.4）。

已补齐：

- 真实 Cloudflare workerd 与真实公网 Worker（部署 + 线上验收已通过）；
- 真实第三方 MCP（Cloudflare 文档 MCP，10 次采样全部成功）；
- 线上鉴权失败（未带令牌 401）与撤销后拒绝（解除配对后旧令牌被拒绝）。
