# SECURITY.md — Android MCP Hub（Gate 0）

> 这份文档描述的是 **Gate 0 的安全现状**，不是目标形态。
> 每一节都落在一句可以验证的话上：要么指向仓库里的一行代码，要么指向一个会失败的测试。
> 凡是没有验证过的，我会明写"**未验证**"，不拿"应该没问题"充数。

相关文档：[ARCHITECTURE.md](ARCHITECTURE.md) · [docs/PROTOCOL.md](docs/PROTOCOL.md) · [docs/OAUTH-DESIGN.md](docs/OAUTH-DESIGN.md) · [TEST_REPORT.md](TEST_REPORT.md)

---

## 1. 先把话说清楚：Gate 0 不是一个可以放在公网上的服务

Gate 0 要证明的是**链路能通且不说谎**，不是"安全上线"。以下都是**已知的、故意的**缺口：

| 缺口 | 现状 | 风险 | 阶段 2 该做什么 |
| --- | --- | --- | --- |
| MCP 入口用**共享** Bearer token | `worker/src/mcp/http.ts` 的 `verifyMcpAuth`：一个 `MCP_ACCESS_TOKEN`，谁拿到谁能用 | 令牌没法按客户端吊销、没法区分用户、泄漏即全失守 | OAuth 2.1 + PKCE + DCR，见 [docs/OAUTH-DESIGN.md](docs/OAUTH-DESIGN.md) |
| **没有速率限制** | 无 | 暴力猜令牌、拿 phone_ping 当探针刷 RTT、把 DO 打爆 | 按 IP + 按设备双维度限流；`MAX_PENDING_REQUESTS` 只挡并发不挡频率 |
| **没有持久化审计** | 只用 `logEvent` 打单行 JSON | 日志随平台保留期消失，事后查不到"谁在什么时候连过" | 阶段 2 引入审计存储（**不是**沿用见川记忆的 D1，要独立资源） |
| `/healthz` 公开 | `worker/src/index.ts` 无鉴权返回健康契约 | 暴露部署存在性、绑定名、开关布尔值 | 若要收紧：只回 `status`，细节另开带鉴权的端点 |
| 401 不带 `resource_metadata` | `worker/src/mcp/http.ts` 故意省略 | 客户端无法自动发现授权服务器；现在**还没有**授权服务器，指过去只会更糟 | 阶段 2 补上，并**反向**那条断言（`worker/test/mcp-http.test.ts:102` 附近） |
| 手机端是 debug 签名 | `app-debug.apk` | 不能分发给真人用 | 走 release 签名与密钥托管 |

**所以：** 本仓库当前**没有部署**到任何 Cloudflare 账户。`worker/wrangler.jsonc` 是"可供人工审核的配置"，不是"已生效的部署"。

---

## 2. 资产、信任边界与威胁模型

### 2.1 资产

| 资产 | 价值 | 现在放在哪 |
| --- | --- | --- |
| **手机设备令牌** | 拿到它就等于能冒充那台手机连上 `PhoneSession` | 只在 Worker 的 secret（`DEVICE_TOKENS`）里，且**只存 sha256** |
| **MCP 访问令牌** | 拿到它就能调用 `phone_ping`（Gate 0 里危害有限，阶段 2 起就是"能指挥手机"） | Worker secret（`MCP_ACCESS_TOKEN`） |
| **"能指挥一台真手机"这个能力本身** | Gate 0 只有 ping；后续阶段就是真正的危险面 | 目前**不存在** —— 只有一个 `phone_ping` |
| 连接代次、pending 请求等状态 | 完整性资产：被写坏就会产生假成功 | `PhoneSession` 的 DO 存储 |

**Gate 0 的能力上限必须被反复强调：** 唯一能做的事是"让手机回一个 pong"。没有文件读写、没有应用管理、没有截屏、没有 shell。任务书要求的所有"不实现"都真的没实现。

### 2.2 信任边界

```
[不可信] 公网 / ChatGPT 客户端
   │  ① MCP Bearer token（共享，阶段 2 换 OAuth）
   ▼
[边界 A] Cloudflare Worker edge             ← 在这里做 MCP 鉴权 + 协议校验
   │  ② Service binding / 内部 fetch（带 INTERNAL_DEVICE_HEADER）
   ▼
[边界 B] PhoneSession DO                     ← 在这里做"这台手机的唯一性"仲裁
   │  ③ WSS + 设备令牌（另一套凭据，与 ① 无关）
   ▼
[边界 C] Android App（用户设备）
   │  ④ localhost HTTP
   ▼
[不可信·尚未接入] 本地 MCP 应用
```

**边界 A↔B 的关键**：Worker 在转发前把设备身份写进 `X-Phone-Session-Device` 头，DO 只信这个头。这意味着**任何人都不能从公网直接打 DO**（DO 没有公网入口），而 Worker 是唯一能进入的通道。反过来说，DO 把头当成可信输入——这是刻意的，因为 DO 与 Worker 之间是平台内部的调用。

### 2.3 威胁清单

下面每条的"缓解"都指向仓库里的真实代码；"残余风险"是我明确承认还没解决的。

#### T1 — 设备 ID 枚举
用不同的 `X-Device-Id` 试探"这个设备 ID 存不存在"。
**缓解**：鉴权失败一律返回同一个 401，**不区分**"设备不存在"和"令牌错误"（`worker/src/index.ts:140-143` 与 `:151-153` 分别处理 `no_active_credential` 与 `token_mismatch`，但对外都是 401 + 同样的 `WWW-Authenticate`）。日志里区分是为了排障，但日志不外泄给请求方。
**残余风险**：**未验证**——我没有做过"响应时间是否泄漏"的测量。`activeCredentialsFor` 先按设备 ID 过滤再比哈希，理论上设备不存在时少跑一次 sha256 比较，时间差在几十纳秒量级，通常淹在网络抖动里，但**这只是一个判断，不是一次测量**。

#### T2 — 令牌重放 / 令牌用于另一台设备
把 A 手机的令牌拿去连 B 手机的 deviceId。
**缓解**：令牌是**按设备**绑定的：`activeCredentialsFor(config, deviceId)` 先挑出该设备的凭据，再逐条比 `tokenSha256`（`worker/src/index.ts:140-149`）。A 的令牌在 B 的候选集里根本不参与比较。
**残余风险**：没有（这条是设计上就闭合的）。

#### T3 — 时序侧信道猜令牌
逐字节猜令牌，靠比较耗时的变化判断"猜对了几位"。
**缓解**：比对的是**定长 64 位十六进制摘要**，且用 `timingSafeEqual`（`worker/src/util/crypto.ts:33-44`）——定长循环、越界位取 0、**不提前 return**。注释里写清了为什么 `a === b` 不行。
**残余风险**：JS 引擎的优化行为无法完全保证常数时间（**未验证**），但摘要定长已消除前缀泄漏这类主要信号。

#### T4 — 旧连接冒充新连接完成请求
手机重连后，旧 socket 上迟到/伪造的 pong 把新连接发起的请求"完成"掉，制造一个假成功。
**缓解**：**连接代次**。`handleConnect` 里代次**先自增**、再 `acceptWebSocket`、再用 close code `4001` 关掉旧 socket；每个 pending 请求都记着发起时的 `connection_generation`，pong 回来时代次不匹配就记 `pongsStaleGeneration` 并回 `invalid_pong`（`worker/src/phone/phone-session.ts`）。`webSocketClose` 里也是：socket 代次不等于当前代次就直接返回，**不动当前请求**。
**残余风险**：无（有专门测试覆盖，见 `worker/test/phone-session.test.ts`）。

#### T5 — 故意不回 pong 撑爆内存 / 堆积请求
手机收到 ping 就不回，让服务端无限堆积待处理请求。
**缓解**：三重闸门 —— ①`MAX_PENDING_REQUESTS`（默认 16）到了立刻 `too_many_pending_requests` 失败，**不排队**；②每个请求有超时，内存 `setTimeout` + DO `setAlarm` 双保险，超时后从内存表和存储镜像里都删掉；③单帧大小受 `MAX_PHONE_MESSAGE_BYTES`（默认 65536）限制，超了回 `message_too_large` 并计数。
**残余风险**：**未验证**——没有做过高并发压测，也没算过"单台手机上界 × 设备数"的理论内存占用。DO 的存储写入次数也没有配额评估。

#### T6 — 令牌出现在日志里
把 `Authorization` 头或令牌打进日志，被平台日志、错误上报或人看到。
**缓解**：①`logEvent` 所有字段过 `redact()`，敏感键模式 `/token|secret|authorization|password|credential|cookie|apikey/i`，子串匹配、任意深度、值替换为 `"[redacted]"`（`worker/src/util/log.ts`）；②日志里只出现 `deviceId` 和 `label`，从不出现令牌本身（`worker/src/index.ts:170`）；③**这条是被测试守着的**：`worker/test/security.test.ts` 用捕获的 console 缓冲区断言"任何令牌都不出现在日志里"。改坏 `redact` 会让测试红。
**残余风险**：模式是"键名匹配"，如果哪天有人把令牌塞进一个叫 `payload` 的键，`redact` 不会救他。这是模式的固有弱点，**已知**。

#### T7 — 非 WebSocket 请求打升级端点
用普通 GET 打 `/phone/ws` 试图拿到非 socket 的响应或探测实现。
**缓解**：`Upgrade` 头不是 `websocket` 就回 `426 Upgrade Required`（`worker/src/index.ts:123-127`），且**在鉴权之前**——不泄漏设备是否存在。
**残余风险**：无。

#### T8 — 拿 device_id 当探针
调 `phone_ping` 时随便填 `device_id`，靠错误码区分"设备存在但离线"和"设备不存在"。
**缓解**：未知 `device_id` 在**不调用转发层**的前提下就失败为 `unknown_device`（`worker/src/config.ts:236-241` + `worker/src/mcp/tools.ts`）。有专门测试断言转发层调用次数为 0（`worker/test/tools.test.ts`）。
**残余风险**：这仍然**确认了"哪些 device_id 是配置过的"**——对已经持有有效 MCP token 的人来说，设备 ID 清单是可枚举的。阶段 2 应改为不区分（或者用不透明 handle）。

#### T9 — MCP 会话固定 / 协议版本降级
①拿一个偷来的 `Mcp-Session-Id` 复用别人的会话；②用旧协议版本让服务端退到更弱的校验路径。
**缓解**：①会话 ID 是 `crypto.randomUUID()`（`worker/src/util/ids.ts`），24 小时空闲过期，`DELETE /mcp` 可显式终止；②会话建立时协商的 `protocolVersion` **先到先得、不可改写**（`worker/src/mcp/session-do.ts`：重复 initialize 只记账 + 记一条 warn 日志），后续请求带 `MCP-Protocol-Version` 头时与会话值不一致直接拒；不在支持列表里的版本一律回落到最新支持版本。
**残余风险**：会话 ID 本身**不是一个鉴权凭据**——它只证明"你接着上一句说"，Bearer token 才是鉴权。所以偷到会话 ID 但没有 token 依然进不来（`worker/src/mcp/http.ts` 里鉴权在会话校验之前）。这条是设计意图，**未做过对抗性测试**。

#### T10 — 伪造 MCP 方法/参数绕过
发畸形 JSON-RPC、未知方法、多余字段、超大 body，看服务端会不会崩或者放过什么。
**缓解**：①不支持 JSON-RPC batch —— 收到数组直接 `-32600`（MCP 2025-06-18 已移除 batch）；②未知方法 `-32601`；③`phone_ping` 参数**手写严格校验**，`additionalProperties:false`，多余字段直接拒（`worker/src/mcp/tools.ts`）；④`Content-Type` 不是 `application/json` → 415，`Accept` 不含允许类型 → 406；⑤`Mcp-Session-Id` 只接受可见 ASCII 且 ≤128 字符（防 header 注入）。
**残余风险**：**未验证** —— 没有测过"超大 body 会不会在解析前就把 Worker 内存吃掉"。Worker 平台本身有请求体上限，但我没有在本项目里断言过。

#### T11 — 冒用设备身份建连（无令牌）
不带令牌或者用错令牌连 `ws://…/phone/ws`。
**缓解**：缺 `X-Device-Id` / 缺 Bearer / 该设备没有有效凭据 / 摘要不匹配，**四种情况全部 401**，不建连（`worker/src/index.ts:130-155`）。没有任何"认证失败就放过"的分支，也没有测试环境后门。
**残余风险**：无。

#### T12 — 健康端点泄漏
`/healthz` 公开，告诉任何人服务存在、用了哪些绑定。
**缓解**：这是**刻意的**（见 §5）。它只回布尔值和绑定**名字**，不含任何凭据、不含设备 ID、不含 account id。
**残余风险**：攻击者能确认"这个域名跑着 android-mcp-hub 的某个版本"。对 Gate 0 可接受。

---

## 3. 凭据模型

### 3.1 手机设备令牌

存储形态（`worker/src/config.ts`）：

```json
[{"deviceId":"test-phone-1","tokenSha256":"<64位小写hex>","label":"local mock phone","createdAt":"...","revokedAt":null}]
```

- **只存 sha256，不存明文。** 配置泄漏不等于令牌泄漏（63 个十六进制字符的搜索空间）。
- **比较用 `timingSafeEqual`**（`worker/src/util/crypto.ts:33`），对定长摘要做常数时间比较。
- **`revokedAt`**：非空的凭据被 `activeCredentialsFor` 过滤掉，请求立即失效。这就是"吊销"。
- **轮换**：配置里可以同时存在同一个 `deviceId` 的多条凭据，`candidates.some(...)` 任意一条匹配即可 —— 所以轮换是"先加新的、再删旧的"**重叠窗口**，手机端不需要停机。**未验证**：没有实际跑过这个流程。
- **不在日志里**：日志只有 `deviceId` + `label`。

### 3.2 MCP 访问令牌

- 一个共享 Bearer token，来自 secret `MCP_ACCESS_TOKEN`。
- **失败关闭**：secret 没配 → `503`（而不是"没配就放行"）。这是个重要选择：配置缺失必须是**大声失败**，不能退化成无鉴权。
- 缺失/错误 → `401` + `WWW-Authenticate: Bearer realm="android-mcp-hub", error="invalid_token"`，**故意不带** `resource_metadata`（理由见 §1 与 [docs/OAUTH-DESIGN.md](docs/OAUTH-DESIGN.md)）。

### 3.3 ★ 两套凭据必须完全分离

这是任务书的硬要求，也是这份文档最想钉住的一条：

| | ChatGPT / MCP 入口 | 手机 WebSocket |
| --- | --- | --- |
| 头 | `Authorization: Bearer <MCP_ACCESS_TOKEN>` | `Authorization: Bearer <设备令牌>` + `X-Device-Id` |
| 校验位置 | `worker/src/mcp/http.ts` 的 `verifyMcpAuth` | `worker/src/index.ts` 的 `handlePhoneUpgrade` |
| 凭据集合 | 一个 `MCP_ACCESS_TOKEN` | `DEVICE_TOKENS`（按设备，可吊销、可多份） |
| 拿到它能干什么 | 调 MCP 工具（Gate 0 只有 `phone_ping`） | 让**一台**手机建立受信任连接 |
| 能互相替代吗 | **不能** | **不能** |

现在这两条路径的代码**没有任何交叉**：MCP token 不参与 `/phone/ws` 的鉴权，设备令牌也不参与 `/mcp` 的鉴权。它们是两个独立的读环境变量的地方、两个独立的比较。

**这条约束被测试记着**：`worker/test/security.test.ts` 里断言"用一个令牌过另一条通道"必须失败。**未验证**：这条具体的跨凭据负向测试是否已存在——写这份文档时我没有逐条核对 `security.test.ts` 的用例名（`security.test.ts` 目前 13 个用例，主要是静态扫描 + 日志脱敏 + 隔离扫描）。**阶段 2 第一件事之一就是把它显式补上**，并确认它真的会红。

---

## 4. 日志纪律

- 所有服务端日志走 `logEvent(level, event, fields)`，输出**单行 JSON**：`{ts, level, event, ...redact(fields)}`。
- `SENSITIVE_KEY_PATTERN = /token|secret|authorization|password|credential|cookie|apikey/i`，**子串匹配**（所以 `tokenSha256`、`refreshToken`、`client_secret` 都会被命中）。
- `MAX_DEPTH = 6`，超过就写 `"[depth-limit]"`（防止有人构造深度嵌套对象把日志变成炸弹）。
- 键名保留、**只换值**——这样日志仍然可读（你知道这里有一个 token 字段），但值永远出不来。
- **实测证据**：`{deviceId, Authorization, token, tokenSha256, apiKey, nested:{password, ok}}` → `deviceId` 与 `nested.ok` 原样，其余五个全变 `"[redacted]"`。
- 测试基建：`worker/test/harness/setup.ts` 把 `console.log/warn/error` 收进缓冲区（默认**不打印**），`security.test.ts` 据此断言"任何令牌都不出现在日志里"。要看日志设 `GATE0_VERBOSE=1`。

---

## 5. `/healthz` 为什么公开、以及它到底暴露了什么

它公开，因为**证据链比这点信息值钱**：一个能在 CI 和部署后立刻断言的健康契约，是"这次部署到底对不对"的唯一快速判据。它回的东西全是**结构性**的：

- `status` / `service` / `version` / `environment` / `runtime` —— 你是谁、哪个版本。
- `database: "not-used"` / `d1_bindings: 0` —— **这是任务书"不得使用 D1"的可断言证据**。它把一条安全约束变成了一个 CI 能看的数字。
- `durable_object_bindings: ["PHONE_SESSION","MCP_SESSION"]` —— 绑定名字，不是 ID。
- `config_problems: []` —— 配置解析出的问题列表（比如某个数字被夹紧了）。空数组本身就说明配置没被静默改写。
- `auth: {mcp_bearer_configured, device_credentials_configured, device_tokens_configured}` —— **布尔值**。它告诉运维"开关配上了没"，但不告诉"值是什么"。
- `capabilities.tools: ["phone_ping"]` —— 暴露了工具清单。对 Gate 0 可接受（只有一个工具，且 MCP 协议本身就要求 tools/list 公开它）。

**不含**：任何令牌、任何设备 ID、任何 account id、任何 route/zone 信息。`worker/wrangler.jsonc` 里也**刻意没有** `account_id` / `route` / `routes` / `zone_name`。

---

## 6. Android 侧

### 6.1 权限清单（逐条，`android/app/src/main/AndroidManifest.xml`）

| 权限 | 用途 | 能不能不要 |
| --- | --- | --- |
| `INTERNET` | 连 WSS | 不能 |
| `ACCESS_NETWORK_STATE` | 网络恢复后重连需要知道网络状态 | 不能 |
| `POST_NOTIFICATIONS` | Android 13+ 前台服务必须有可见通知 | 不能 |
| `FOREGROUND_SERVICE` | 保持长连接 | 不能 |
| `FOREGROUND_SERVICE_SPECIAL_USE` | 见 §6.2 | 不能 |

**没有**申请：通讯录、位置、相机、麦克风、存储、短信、电话、`QUERY_ALL_PACKAGES`、`SYSTEM_ALERT_WINDOW`、`REQUEST_INSTALL_PACKAGES`。任务书要求"不扫描本地应用、不申请无关权限"——这条是靠**权限列表短**来证明的，而不是靠一句承诺。

### 6.2 前台服务为什么用 `specialUse`

`android/app/src/main/AndroidManifest.xml:47-54`：`android:foregroundServiceType="specialUse"`，配套声明 `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` 说明理由。

用 `specialUse` 是因为**没有更贴切的类型**：这个服务的职责是"维持一条被用户手动开启的、与用户自己服务器的长连接"。它不属于 `dataSync`（不是同步数据）、不属于 `connectedDevice`（连接的设备是用户自己这台）、不属于 `location`/`mediaPlayback`/`phoneCall`。

任务书明令"**不使用虚假的前台服务类型绕过系统限制**"。所以：宁可走 `specialUse`（Play 商店会人工审核这个 subtype 的说明文字），也不去挑一个"看起来更像、但其实在撒谎"的类型。这个选择的完整论证与人工验证清单在 [android/README-ANDROID.md](android/README-ANDROID.md)。

### 6.3 明文流量默认关闭

`android/app/src/main/res/xml/network_security_config.xml`：`cleartextTrafficPermitted="false"`，只有系统信任锚。协议是 `wss://`，不需要明文例外。文件注释里写明了：本地联调要用 `ws://` 时，**改这个 XML**，不要在代码里加 `usesCleartextTraffic`。

### 6.4 服务不被外部启动

`android/app/src/main/AndroidManifest.xml:49`：`android:exported="false"`。只有本应用能启动/停止它，别的 App 调不起这个服务。

---

## 7. 密钥卫生

- `.gitignore` 挡住：`.dev.vars`、`*.dev.vars`、`*.jks`、`*.keystore`、`*.apk`、`*.aab`、`local.properties`、`captures/`、`*.log`、`.wrangler/`。
- `worker/.dev.vars.example` **只有占位符**，里面那个 `tokenSha256` 是 64 个 `0`，一眼能看出是假的。
- **源码里没有任何真实凭据**，并且这一点是**被静态扫描测着的**（`worker/test/security.test.ts`）：
  - 扫描**文本文件**里的 40 位 base62 令牌形状；`package-lock.json` / 锁文件被排除（里面全是 npm integrity 哈希，扫它们只会造假警报）。
  - 扫描"见川记忆"项目的隔离性（`jianchuan` / `见川` 等词），并**排除这个测试文件自己**。
  - 扫描 `worker/src/env.ts` 里**不许出现** `D1Database` / KV / R2 / Queue 形状（先 `stripComments`，否则那句"刻意没有任何 D1 字段"的注释会被自己误伤）。
  - 扫描 `redact()` 真的会脱敏（含嵌套键）。
- 因此仓库里**没有**、也不该有：`.dev.vars`、真实令牌、account id、D1 绑定、路由配置。
- 测试一律使用**明显是假的**凭据：`gate0-local-test-mcp-token-not-a-secret`、`gate0-local-test-device-token-not-a-secret`（`worker/test/harness/env.ts`）。

---

## 8. 安全相关的测试

`worker/test/security.test.ts`（13 个用例，全部通过）把上文的"不许"变成了会失败的测试：令牌形状扫描、隔离扫描、无 D1 形状、日志脱敏（含嵌套）、敏感键模式。

其余章节对应的功能测试：连接代次与旧连接拒绝、超时清理、并发上限、重建后不产生虚假成功 —— 见 `worker/test/phone-session.test.ts`；MCP 鉴权失败关闭、方法/参数拒绝 —— 见 `worker/test/mcp-http.test.ts` 与 `worker/test/tools.test.ts`。

**明确 NOT RUN 的安全相关项**（原因见 [TEST_REPORT.md](TEST_REPORT.md)）：

- 在**真实 workerd** 上跑任何安全测试 —— 本容器跑不了 workerd。
- 在**真机**上验证前台服务、权限、手动断开后不重连 —— 没有真机接入。
- 任何**速率限制 / 压测 / 对抗性**实验（并发上限的爆炸半径、响应时间侧信道、超大 body）。
- 跨凭据负向测试（§3.3 末尾说明的那条）。
- 真实 Cloudflare 环境下的 TLS/WSS 行为。

---

## 9. 报告安全问题

Gate 0 是**内部验证阶段**，没有对外的安全响应流程。发现任何问题（尤其是"Worker/DO 能在手机没回 pong 的情况下返回成功"这类**正确性即安全**的问题），请直接开仓库 issue 或告知项目所有者，**不要**在公开渠道贴可利用细节。

最要紧的一条：**任何时候都不要为了让客户端连上而关闭认证。** 不要禁用鉴权、不要把允许列表放宽到 `*`、不要给 token endpoint 加"容忍"分支。宁可连不上，也不要假装连上了。
