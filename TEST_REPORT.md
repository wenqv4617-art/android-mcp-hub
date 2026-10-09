# TEST_REPORT.md — Android MCP Hub · Gate 0

**报告日期**：2026-10-09
**被测提交**：见文末「提交哈希」（本报告与该提交一同入库）
**测试环境**：Linux aarch64 容器（`uname -m` = `aarch64`），Node v24.19.0 / npm 11.17.0 / JDK 17.0.20.1 / Gradle 8.11.1 / Android SDK 平台 android-35 + build-tools 34.0.0。

> **本报告的原则**：每一条"通过"后面都跟着**实际执行的命令**和**真实输出**；任何因环境限制没能执行的测试，一律标 **NOT RUN**，绝不写成通过。证据文件保存在 `test-reports/` 下，可逐个核对。

---

## 0. 一句话结论

| 测试面 | 结果 |
| --- | --- |
| Worker / MCP 协议 | ✅ **118 个单元测试全过**（6 个文件） |
| Durable Object（PhoneSession / McpSession） | ✅ 含在上面 118 里（37 个） |
| 真实端到端链路（Worker → DO → WebSocket → 独立手机进程） | ✅ **21/21 通过**（官方 MCP SDK 客户端） |
| Android 编译 | ✅ `BUILD SUCCESSFUL`，产出 debug APK |
| Android JVM 单测 | ✅ **90/90 通过** |
| 真实 workerd 运行时上的验证 | ⛔ **NOT RUN**（容器无法运行 workerd，见 §5，附精确错误） |
| Android 真机行为验证 | ⛔ **NOT RUN**（需要真机/模拟器，见 §6） |
| ChatGPT 官方客户端真实接入 | ⛔ **NOT RUN**（OAuth 属下一阶段，见 §7） |
| 真实 Cloudflare 部署 | ⛔ **未执行，且任务书明确禁止** |

**一句话**：逻辑层被真实地测过（含一条真正的端到端链路，手机是另一个操作系统进程）；**平台层（workerd）与真机层没测过**，原因在 §5/§6 里写清楚。

---

## 1. 怎么自己重跑

```bash
# 全部（typecheck + 单元测试 + 端到端）
npm run gate0

# 分开跑
npm run typecheck                     # tsc --noEmit
npm run test                          # vitest run --config worker/vitest.config.ts
npm run e2e                           # tsx e2e/run-local-e2e.ts

# Android
cd android
ANDROID_HOME=/opt/android-sdk ./gradlew --no-daemon :app:assembleDebug
ANDROID_HOME=/opt/android-sdk ./gradlew --no-daemon :app:testDebugUnitTest
```

首次需要 `npm install`（194 个包，约 50s）与一个 `.dev.vars`（从 `worker/.dev.vars.example` 复制，**只用假令牌**）。

---

## 2. 类型检查

```
$ npx tsc --noEmit -p tsconfig.json
EXIT=0
```

证据：`test-reports/typecheck.log`。覆盖 `worker/src` 13 个文件、`worker/test` 9 个文件、`tools/mock-phone` 2 个文件、`e2e/` 以及根目录配置文件。

---

## 3. 单元测试：118 个用例，全部通过

```
$ npx vitest run --config worker/vitest.config.ts

 ✓ worker/test/mcp-session.test.ts   (11 tests)   36ms
 ✓ worker/test/worker-index.test.ts  (18 tests)   41ms
 ✓ worker/test/mcp-http.test.ts      (31 tests)   99ms
 ✓ worker/test/tools.test.ts         (19 tests)  148ms
 ✓ worker/test/security.test.ts      (13 tests)  405ms
 ✓ worker/test/phone-session.test.ts (26 tests) 2415ms

 Test Files  6 passed (6)
      Tests  118 passed (118)
   Duration  3.48s
EXIT=0
```

证据：`test-reports/vitest.log`。

### 3.1 测试架构说明（诚实交代"测的到底是什么"）

workerd 跑不起来（§5），所以单元测试**不跑在 Cloudflare 的运行时上**，而是跑在我们自己写的 Node 端替身上（`worker/test/harness/runtime.ts`）。它实现了 DO 的这几件事：

- 按 name 寻址的实例表 + `get(id).fetch()`；
- `ctx.storage`（`get`/`put`/`delete`/`setAlarm`/`deleteAlarm`/`list`）与 `blockConcurrencyWhile`；
- `ctx.acceptWebSocket` / `getWebSockets(tag)` / `serializeAttachment` / `deserializeAttachment` 与 **hibernation 风格的 tag 查询**；
- `DurableObjectState` 实例的**驱逐与重建**（`evict()`，重建时复用同一份 storage —— 这正是"持久化状态还在、内存态没了"）；
- `WebSocketPair` 与一台可编程的 `FakeWebSocket`（可精确控制"收到 ping 但不回"、"回错误 id"、"回两次"、"发完就断"）。

**这段是必须交代清楚的**：这些测试证明的是**我们的逻辑**在给定语义下的正确性，证明不了"workerd 真的按我们假设的语义实现了这些 API"。替代品能证伪的东西比真运行时少 —— 所以 §5 的 NOT RUN 不是形式主义。

### 3.2 Worker / MCP 协议（`mcp-http.test.ts` 31 个 + `worker-index.test.ts` 18 个）

| 任务书要求 | 用例 | 结果 |
| --- | --- | --- |
| initialize 成功 | 协商版本、返回 `serverInfo`、生成 `Mcp-Session-Id` | ✅ |
| tools/list 返回 phone_ping | 断言工具列表**恰好**是 `["phone_ping"]` | ✅ |
| tools/call 正确转发 | 转发层被调用、参数原样到达 | ✅ |
| JSON-RPC ID 正确对应 | 字符串/数字/大写特殊 id 都能原样回；notification 不回 | ✅ |
| 不支持的方法返回规范错误 | `-32601` | ✅ |
| 无效参数被拒绝 | 多余字段、错类型、超范围 → `-32602` | ✅ |
| 未认证请求被拒绝 | 缺/错 Bearer → 401 + `WWW-Authenticate`；未配置 token → **503（失败关闭）** | ✅ |

另含传输层细节：`GET /mcp` → `405 Allow: POST, DELETE`；`Accept` 不含 `application/json`/`text/event-stream` → 406；`Content-Type` 不对 → 415；坏 JSON → 400 且 `id: null`；JSON-RPC **batch 被拒绝**（`-32600`）；`notifications/initialized` → 202；未知/过期会话 → 404；`MCP-Protocol-Version` 头存在但不支持 → 400；会话头只接受可见 ASCII 且 ≤128 字符（防 header 注入）。

### 3.3 Durable Object：PhoneSession（`phone-session.test.ts` 26 个）

逐条对应任务书「七、测试要求 / Durable Object」的 10 条：

| 任务书要求 | 结果 | 关键断言 |
| --- | --- | --- |
| 正常 ping/pong | ✅ | `pongsMatched=1`、`rtt_ms` 是服务端算的 |
| 请求超时 | ✅ | 400ms 配置下 `timeouts=1`、pending 清空 |
| 错误 request_id | ✅ | `pongsUnknown=1` + 回 `error/unknown_request_id`，原请求**不被结算** |
| 重复 pong | ✅ | 第二次 pong 不改变任何计数 |
| WebSocket 意外断开 | ✅ | 全部 pending 以 `phone_disconnected` 显式失败 |
| 断线后重新连接 | ✅ | 新代次可用，旧代次失效 |
| 旧连接响应被拒绝 | ✅ | `pongsStaleGeneration=1`，**且不产生成功** |
| 并发请求正确关联 | ✅ | 3 并发各自拿到自己的 `request_id` |
| 超出并发上限时正确失败 | ✅ | 第 17 个立刻 `too_many_pending_requests` |
| 对象重建后不产生虚假成功 | ✅ | 遗留 pending → `reconstructedPendingFailed`，旧代次 pong 回来仍是失败 |

最慢的两条：`超过 MAX_PENDING_REQUESTS 的请求立刻失败` 416ms、`重建时把存储里遗留的待处理请求记为失败` 315ms。

**这里发现并修掉过一个真实缺陷（值得单独记）**：超时配置的下限原本是 `1000ms`，于是测试里设 `PHONE_PING_TIMEOUT_MS=200` 被**静默夹成 1000**，还把一条 problem 塞进 `config_problems`（`/healthz` 因此变 `degraded`）。后果是**所有超时用例都以错误的理由通过** —— 它们确实"超时了"，但等到的是 1000ms，而不是被测的那个配置值。改成下限 50ms 后，整个套件从 **6.28s 降到 2.41s**，超时路径才算真被测到。

### 3.4 Durable Object：McpSession（`mcp-session.test.ts` 11 个）

建会话记住协议版本与 clientInfo；缺参 → 400；未建过的会话 validate → `valid:false / unknown_session`；版本不一致 → `protocol_version_mismatch`（带 `expected`）；**★ 重复 initialize 不改写已协商的协议版本**（这条修掉了一个真实不变量缺陷，见下）；terminate 后 `deleted:true` 且再 validate 变 `unknown_session`；terminate 不存在的会话如实 `deleted:false`；不同 sessionId 落在不同 DO 实例；未知内部路由 → 404。

**修掉的真实不变量缺陷**：`handleCreate` 原来**无条件**写 `protocolVersion`（用本次请求的版本）。但 `handleValidate` 是拿请求头跟 `record.protocolVersion` 比对的 —— 一旦它能被改写，"会话建立时协商的版本"就不再可信，校验形同虚设。改成**先到先得**（`existing?.protocolVersion ?? protocolVersion`）并在版本不同时记 warn。理由：这条不变量应由**拥有状态的那一层**保证，而不是依赖调用方永远记得换 session id。

### 3.5 参数与工具层（`tools.test.ts` 19 个）

覆盖 `phone_ping` 的 inputSchema 与严格校验（多余字段被拒；`arguments` 为 `null`/`undefined` 视作没带参数**不是错误**）、未知 device_id 直接 `unknown_device` **且根本不调用转发层**（防止拿 device_id 探测设备）、执行失败走 `isError:true` 而协议失败才走 JSON-RPC error。

**★ 一条专门守着"Worker 内不可能伪造 pong"的测试**：给 `dispatchToolCall` 注入一个**永不 resolve** 的转发函数，断言整个调用一直挂起（`Promise.race` 必须拿到 `"still-waiting"`）。谁哪天在工具层加一句"本地兜底 / 超时后自己造个成功"，这条就会红。

### 3.6 安全与隔离（`security.test.ts` 13 个）

- 静态扫描：仓库内**不得**出现 `d1_databases`、`new D1Database`、`env.DB`、`D1Database` 类型引用；`worker/src/env.ts` 里不得声明任何 D1/KV/R2/Queue 绑定字段（**这是"没有 D1"的编译期 + 测试期双重断言**）。
- 静态扫描：源码/配置/wrangler 配置里不得出现真令牌形状的字面量；`.dev.vars` 与 `local.properties` 必须在 `.gitignore` 里。
- 静态扫描：**不得**出现任何指向**既有生产项目**的标识（项目名、账户 id、Worker 名、已知生产域名、仓库路径）。禁词表内联在测试源码里，扫描时排除该测试文件自身。
  > 这条测试**真的抓到过东西**：本报告与 `SECURITY.md` 的第一版在正文里写到了那个生产项目的名字，扫描立刻变红。修法是**改文档**（统一改称"既有生产项目"），而不是放宽容忍度 —— 新仓库里不该出现那个名字。
- 日志脱敏：`redact()` 对嵌套对象、数组、深层（≤6 层）的敏感键一律 `[redacted]`；`token`/`secret`/`authorization`/`password`/`credential`/`cookie`/`apikey` 命中；**同时**测了"非敏感键原样保留"，避免脱敏过度。

---

## 4. 端到端：21/21 通过（这条链路是真的）

```
$ npm run e2e          # = GATE0_VERBOSE=1 tsx e2e/run-local-e2e.ts
  端到端结果：21 通过，0 失败（共 21 项）
EXIT=0
```

证据：`test-reports/e2e.log`（**已随仓库入库**，`test-reports/*.log` 在 `.gitignore` 里被显式豁免）。

**这条 e2e 为什么算"真"**：

1. 本地起一个**真实 HTTP 服务器**，把请求交给真实的 `worker.fetch()`（`worker/src/index.ts` 的入口，不是测试专用的旁路）；
2. `ws` 库起**真实 WebSocket 服务**，与 DO 手里的 socket 做双向桥接 —— 所以字节真的走了一遍 socket；
3. 模拟手机是**另一个操作系统进程**（`spawn(node, tools/mock-phone/main.ts)`），不是 stub；
4. 客户端是**官方 `@modelcontextprotocol/sdk`**（`Client` + `StreamableHTTPClientTransport`），不是手写 JSON。
5. **整个链路里唯一没有真东西的是 workerd 本身**（§5）。

覆盖的场景：

| # | 场景 | 关键真实数据 |
| --- | --- | --- |
| ① | `/healthz` 契约 | `status=ok`、声明没用 D1、`config_problems` 为空 |
| ② | 未认证 MCP 请求被拒 | `401` + 带 `WWW-Authenticate` |
| ③ | SDK 客户端 initialize | `server=android-mcp-hub@0.1.0-gate0` |
| ④ | tools/list | `tools=[phone_ping]`（恰好一个） |
| ⑤ | 手机没连上时 ping | `isError=true`、`code=phone_offline` —— **如实失败，不伪造 pong** |
| ⑥ | 起真手机进程（normal）再 ping | **成功**，`rtt_ms=5`、`request_id` 与手机收到的一致、`connection_generation≥1`、`echo` 原样返回 |
| ⑦ | 第二台手机不回（no-response） | `code=phone_timeout`、`elapsed=313ms`、**连续第二次超时仍是 phone_timeout**（连接没被误关、状态没写坏） |
| ⑧ | 会话与协议头 | 同一 SDK 客户端第二次请求仍有效（会话被正确复用） |

**★ 这条 e2e 暴露的一个重要发现（`[已验证·本项目]`）**：官方 SDK 请求的协议版本是 **`2025-11-25`**，而我们的支持列表到 `2025-06-18`；服务端**回落**到最新支持的版本并把结果告诉客户端，SDK 接受了。日志 `mcp.initialize` 同时记录 `requestedProtocolVersion` 与 `negotiatedProtocolVersion`。
→ 含义：真实客户端可能要求比我们更新的协议版本。Gate 0 靠"回落"通过，但**真实 ChatGPT 是否会像 SDK 一样接受回落，本阶段没有验证**（§7）。

---

## 5. ⛔ NOT RUN：真实 workerd 运行时

**这是 Gate 0 最大的未验证项，我说清楚原因。**

```
$ node_modules/@cloudflare/workerd-linux-arm64/bin/workerd --version
MmapAligned() failed - unable to allocate with tag (hint=0x4fb500000000, size=1073741824,
  alignment=1073741824)
TCMalloc assumes a 48-bit virtual address space size
FATAL ERROR: Out of memory trying to allocate internal tcmalloc data
  (bytes=131072, object-size=16384)
Aborted
EXIT=134
```

证据：`test-reports/workerd-not-run.log`。同一条命令用 `npx wrangler dev --config worker/wrangler.jsonc --local --port 8787` 也以同样的 `tcmalloc` abort 收场（外加 `write EPIPE`）—— **但 wrangler 成功解析并打印了配置里的 vars**，所以 `worker/wrangler.jsonc` 本身是能被 wrangler 读取的。

**排查结论（为什么这是环境问题而不是我们配错了）**：

- 容器跑在 **Android 手机上**（`DSHA_ANDROID_RUNTIME=1`、`ANDROID_ROOT=/system`），虚拟地址空间布局与 workerd 编译期假设的 48 位 VA 不兼容；
- `ulimit -v` 已经是 `unlimited`，**不是**配额问题；
- `TCMALLOC_ADDRESS_BITS` 是**编译期常量**，而 workerd 静态链接了 tcmalloc，`LD_PRELOAD` 覆盖不了；
- 换 `node_modules/workerd`（v1.20260815.1）与 `@cloudflare/workerd-linux-arm64` 两个包都一样。

**因此以下项目标 NOT RUN（不是通过）**：

| 项 | 状态 |
| --- | --- |
| `wrangler dev --local` 下的 Worker / DO 行为 | ⛔ NOT RUN |
| `@cloudflare/vitest-pool-workers`（真实 workerd 上跑测试） | ⛔ NOT RUN（包已装，运行时起不来） |
| 真实 **WebSocket Hibernation** 的休眠/唤醒 | ⛔ NOT RUN（Node 替身只模拟了语义，见 §3.1） |
| DO **驱逐后重建**的真实时序 | ⛔ NOT RUN（单元测试是主动触发 `evict()` 模拟的） |
| DO **alarm** 在真实平台上的送达行为 | ⛔ NOT RUN（替身按点触发） |
| 真实的 CPU/时长**计费与限制** | ⛔ NOT RUN |

**下一阶段必须做的第一件事**：在一个正常的 x86-64 CI 环境里把 `npm run gate0` 与 `npx vitest-pool-workers` 跑一遍；如果 workerd 起来后行为与替身不一致，替身要按真实语义改。

---

## 6. Android

### 6.1 编译：通过

```
$ ANDROID_HOME=/opt/android-sdk ./gradlew --no-daemon :app:assembleDebug
BUILD SUCCESSFUL in 21s
ASSEMBLE_EXIT=0
```

产物：`android/app/build/outputs/apk/debug/app-debug.apk`（10108601 字节）。证据：`test-reports/android-build.log`。

### 6.2 JVM 单元测试：90/90 通过

```
$ ANDROID_HOME=/opt/android-sdk ./gradlew --no-daemon :app:testDebugUnitTest
BUILD SUCCESSFUL
UNITTEST_RERUN_EXIT=0
```

| 测试类 | 用例数 | 结果 |
| --- | --- | --- |
| `core.BackoffTest` | 9 | ✅ |
| `core.HubConfigTest` | 7 | ✅ |
| `core.HubLogTest` | 4 | ✅ |
| `core.WsUrlTest` | 8 | ✅ |
| `net.PhoneLinkClientTest` | 4 | ✅ |
| `protocol.PhoneLinkProtocolTest` | 14 | ✅ |
| `protocol.PhoneLinkTest` | 35 | ✅ |
| `protocol.PingResponderTest` | 9 | ✅ |
| **合计** | **90** | ✅ 0 失败 |

**同一批测试的第一次运行是 `90 tests completed, 3 failed`**。我逐条查了是"产品错"还是"测试错"，三条**都是测试写错**（产品行为正确），修法是让断言更贴近真实意图，而不是把断言放松到能过：

1. `onSocketOpened 发送 hello 帧且内容符合契约`：测试**忘了先调 `start()`**，于是在 `Idle` 状态下断言 `Connecting`。真实的 `onOpen` 只可能发生在 `start()` 发起的连接上。→ 补上 `h.link.start()`（补了之后这条路才真的走到 `Connecting`）。
2. `重复的 ping 也会刷新最近一次 ping 的展示数据`：原断言 `pings.size == 1` 与**测试自己的名字**矛盾 —— 产品设计是"去重只决定要不要回 pong，不决定要不要刷新展示"，两帧都会产生一个 `PingOutcome`，第二帧 `duplicate=true` 且 `frame=null`（所以没有任何 `Send`）。→ 改成断言 2 个 outcome，并**新增**三条更强的断言（第一帧非重复、第二帧是重复、重复帧不带 pong 帧）。R2（"第二次不再回复"）由另一个独立用例守着，没有被放松。
3. `超出容量后最久未使用的 request_id 被淘汰`：容量为 2 时，原顺序里"验证 b 被淘汰"的那次 `handle("b")` **自己会把 b 塞回表里、从而挤掉 a**，所以最后那句"a 仍在窗口内"必然失败 —— 是测试忘了算这一步。→ 把"a 还在窗口内"挪到把 b 放回去**之前**，并加注释说明为什么顺序要紧；同时新增断言 `seenCount()==2`。改完这条测试**更能证伪**：如果"命中刷新 LRU 顺序"失效，它现在会红（原来不会）。

### 6.3 ⛔ NOT RUN：真机行为（任务书「Android」7 条里的 1 条 + 全部运行时行为）

`android/README-ANDROID.md` 里有一份**人工验证清单**（需要真机或模拟器）。以下**全部 NOT RUN**：

| 任务书要求 | 状态 | 现有替代证据 |
| --- | --- | --- |
| 手动连接 | ⛔ NOT RUN（真机） | ✅ JVM 单测（`PhoneLinkTest` 覆盖 `start → onSocketOpened → welcome` 全流程） |
| 手动断开 | ⛔ NOT RUN（真机） | ✅ JVM 单测 |
| 收到 ping 并返回 pong | ⛔ NOT RUN（真机） | ✅ JVM 单测 + ✅ e2e（**注意**：e2e 里跑的是 Node 版模拟手机，不是这个 APK） |
| 网络断开后重连 | ⛔ NOT RUN（真机） | ✅ JVM 单测（退避调度、抖动、上限） |
| 手动停止后不重连 | ⛔ NOT RUN（真机） | ✅ JVM 单测（`userStopped` 置位后所有回调都不安排重连） |
| **前台服务生命周期正确** | ⛔ **NOT RUN**（**无替代**，只有编译通过 + Manifest 静态检查） | — |
| 不访问其他本地应用 | ⛔ NOT RUN（真机） | ✅ 静态：Manifest 恰好 5 个权限、无 `QUERY_ALL_PACKAGES`、无 `getInstalledPackages` 类调用 |

**特别提醒评审**：`Compose` 界面（`ui/HubScreen.kt`、`ui/HubViewModel.kt`）与 `service/PhoneLinkService.kt` 的**粘合代码没有自动化测试** —— 这是刻意的（它们的正确性属于人工验证清单），但因此**"这个 App 真能连上并显示 RTT"目前是未经运行验证的**。这是 Gate 0 交付里最需要下一位审计者动手的部分。

---

## 7. ⛔ NOT RUN：ChatGPT 官方客户端真实接入

Gate 0 的验收范围是"A + B + C 三段链路连通"，**不含** ChatGPT 的 OAuth。设计已在 `docs/OAUTH-DESIGN.md`（含未验证清单 U6–U15，每条都配了验证手段）里说明，但**一行 OAuth 代码都没写**。

⚠ 因此有一处**已知的、故意的**不一致：`worker/test/mcp-http.test.ts:102` 断言"401 响应**不**带 `resource_metadata`"。这在 Gate 0 是对的（没有授权服务器，指向不存在的地址只会让客户端卡得更难看），但**阶段 2 上线 OAuth 时必须把这条断言反过来**，否则第一次真实接入就会被它挡住。改动点已写进 `docs/OAUTH-DESIGN.md`。

其它 NOT RUN：真实 ChatGPT 客户端的 `initialize`（是否会像官方 SDK 一样接受版本回落，§4）、真实公网环境下的 WS 升级、Cloudflare 边缘的真实超时与 limit。

---

## 8. 未执行的测试 & 已知风险汇总

### 8.1 NOT RUN 清单（本报告不把它们算作通过）

1. 真实 workerd 上的任何测试（§5，6 个子项）
2. Android 真机/模拟器行为（§6.3，7 项，其中**前台服务生命周期无任何替代证据**）
3. ChatGPT 官方客户端真实接入与 OAuth（§7）
4. 真实 Cloudflare 部署后的验收（**任务书明确禁止**，不是遗漏）
5. 时序侧信道抗性（`SECURITY.md` T1）—— **没测过，只有判断**
6. 超大请求体压测（T10）—— 未做
7. 跨凭据负向测试：持有 A 设备令牌者不能冒充 B —— `security.test.ts` 的 13 个用例主要是静态扫描 + 日志脱敏 + 隔离扫描，**没有逐条核对这条已存在**，阶段 2 必须显式补上并确认它会红

### 8.2 已知风险

| # | 风险 | 影响 | 处置 |
| --- | --- | --- | --- |
| R1 | **workerd 未验证** | 逻辑正确 ≠ 平台上正确（DO 寻址、hibernation、alarm、limit 都可能不同） | 下一阶段第一件事：在正常 CI 上跑真实 workerd |
| R2 | 替身测试的**语义假设**可能有偏差 | 替身答"对"的地方真平台可能答"错" | 同上；替身实现集中在 `worker/test/harness/runtime.ts`（425 行），便于对照真实文档逐条复核 |
| R3 | **APK 从未真正运行** | UI/前台服务/网络回调的粘合可能一跑就崩 | 真机人工验证清单（`android/README-ANDROID.md`） |
| R4 | 共享 MCP Bearer，无 OAuth | 不能放公网 | 阶段 2 做 OAuth；`docs/OAUTH-DESIGN.md` 已备 |
| R5 | 401 不带 `resource_metadata` | 真实 ChatGPT 可能连不上 | 阶段 2 必须改（含把 `mcp-http.test.ts:102` 反向） |
| R6 | 官方 SDK 要求 `2025-11-25` | 真实客户端可能拒绝回落 | 阶段 2 先验真实客户端，再决定是否升协议版本 |
| R7 | `redact()` 靠**键名**匹配 | 令牌藏在叫 `payload` 的键里会漏进日志 | 已知；后续把"不记外部输入"作为首要纪律而非依赖脱敏 |
| R8 | 无限流、无持久审计 | 公网暴露即风险 | 阶段 2 |
| R9 | 设备 ID 可被持有有效 MCP token 者枚举 | 轻微信息泄漏 | 阶段 2 改为"令牌→设备"反查，不再接受客户端指定 device_id |

### 8.3 本阶段**没有**做的事（符合任务书要求，特此声明）

- ❌ 没有执行 `wrangler deploy` 或任何云端资源创建（**任务书第 8/10 条**）
- ❌ 没有创建/绑定/访问任何 **D1** 数据库（`env.ts` 里连类型都没有；有静态测试守着）
- ❌ 没有读取或复用**既有生产项目**的任何密钥 / Token / 环境变量（有静态测试扫着禁词）
- ❌ 没有修改**既有生产项目**的代码 / 配置 / 部署（全程未进入那个目录做写操作）
- ❌ 没有配置任何自动生产部署（无 CI workflow 触发 deploy）
- ❌ 没有把真实凭据提交到 GitHub（`.gitignore` 挡住 `.dev.vars`/`local.properties`/keystore，且只有假令牌）
- ❌ 没有实现工具管理 UI / 扫描本地应用 / 自动启动其他应用 / 跨设备控制

---

## 9. 测试计数（供快速核对）

| 类别 | 通过 | 失败 | NOT RUN |
| --- | --- | --- | --- |
| TypeScript 类型检查 | 1（整包） | 0 | 0 |
| Worker/DO 单元测试 | 118 | 0 | 6（workerd 相关，§5） |
| 端到端（真实链路） | 21 | 0 | 0 |
| Android JVM 单测 | 90 | 0 | 0 |
| Android 真机行为 | 0 | 0 | 7 |
| ChatGPT 接入 / OAuth | 0 | 0 | 若干（§7） |
| **合计（已执行）** | **230** | **0** | **13+** |

**关于"0 失败"这句话的边界**：它只覆盖"在 §5 那个 Node 替身上、在这些用例断言的那些性质上"。它**不**意味着"这套代码在 Cloudflare 上一定对" —— 那句话要等 §5 的 NOT RUN 被消掉之后才能说。

---

## 10. 提交与分支

本报告与代码同一次提交入库：**`ad3b510`**，分支 **`gate0`**，新仓库 `wenqv4617-art/android-mcp-hub`（`main` 保持为空，未推送）。

```bash
$ git log --oneline
ad3b510 feat(gate0): Worker + Durable Object + Android APK 全链路可测闭环
```

> 刻意**不**在本文件里写死"当前 HEAD"的哈希：哈希一旦被写进文件，任何后续提交都会让它变成一句假话。要引用最新哈希，请用 `git rev-parse HEAD`。
