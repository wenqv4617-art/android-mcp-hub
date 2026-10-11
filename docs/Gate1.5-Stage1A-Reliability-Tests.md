# Gate 1.5 · 阶段 1A：可靠性测试补强

只写测试。**没有改动任何业务代码。**

| | |
|---|---|
| 报告生成时间 | 2026-10-11T09:42Z |
| 基线 SHA | `39ef38eec151b4bb21b4e6cac933ec5221a12302` |
| 当前分支 | `test/gate1.5-reliability` |
| `gate0` 是否被触碰 | 否（仍指向 `39ef38ee`） |
| 是否推送远程 | 否 |
| 业务代码改动 | **0 行** |

---

## 1. 基线 SHA

```
$ git rev-parse HEAD
39ef38eec151b4bb21b4e6cac933ec5221a12302
```

与任务书指定的已验证 Gate 1 基线**逐字符一致**。分支 `test/gate1.5-reliability` 即从这个提交拉出，
`gate0` 分支在此之后的整个阶段 1A 期间没有移动过。

## 2. 当前分支

```
$ git branch --show-current
test/gate1.5-reliability
```

创建自 `gate0`。本阶段所有改动都只存在于这个分支上。

## 3. 工作区状态

```
$ git status --short
 M android/app/src/test/java/com/androidmcphub/app/protocol/PhoneLinkTest.kt
 M e2e/run-local-e2e.ts
 M worker/test/harness/runtime.ts
?? android/app/src/test/java/com/androidmcphub/app/net/PhoneLinkReliabilityTest.kt
?? docs/Gate1.5-Stage0-Test-Baseline.md
?? worker/test/phone-session-reliability.test.ts
?? worker/test/security-boundary.test.ts
```

说明：

- 开工时工作区里已有 1 个未跟踪文件 `docs/Gate1.5-Stage0-Test-Baseline.md`（阶段 0 报告）。
  按任务书要求**没有**对它做 `reset` / `clean` / `stash` / 覆盖，字节未动
  （sha256 `d63415ee5c2bd08f304344a5b55bc65864b71b07e66ed9a30ee3c335b7c335a9`）。
- 用户此前已明确表示阶段 0 报告**不提交**，因此本次提交也**不包含**它；它继续以未跟踪状态留在磁盘上。
- `docs/Gate1.5-Stage1A-Reliability-Tests.md`（本报告）是阶段 1A 的交付物，属于任务书 §十一 允许的
  "测试报告"，会随本次提交进入版本库。

## 4. 新增测试文件

| 文件 | 行数 | 字节 | sha256（前 16） | 用例数 |
|---|---:|---:|---|---:|
| `worker/test/phone-session-reliability.test.ts` | 315 | 14 176 | `b844385db707ae8a` | 10 |
| `worker/test/security-boundary.test.ts` | 261 | 12 374 | `ebc9a5db782687d5` | 7 |
| `android/app/src/test/java/com/androidmcphub/app/net/PhoneLinkReliabilityTest.kt` | 413 | 17 488 | `0b64283fc9208898` | 7 |

## 5. 修改测试文件

| 文件 | 增/删 | 说明 |
|---|---|---|
| `android/app/src/test/java/com/androidmcphub/app/protocol/PhoneLinkTest.kt` | +42 / −0 | 新增 2 条 C3 纯状态机用例，原 35 条 → 37 条。**纯新增，未改动任何既有用例。** |
| `e2e/run-local-e2e.ts` | +416 / −4 | 新增设备表、5 个局部辅助、阶段 ⑨（44 项 check）；修正 2 处测试基础设施保真度缺陷（见 §9、§10）。 |
| `worker/test/harness/runtime.ts` | +77 / −7 | `FakeWebSocket` 支持二进制帧；`FakeDurableObjectStorage` 新增 `delayNext()` 注入通道。 |

`git diff --stat gate0` 合计 **535 插入 / 11 删除**，全部落在测试目录与 E2E 脚本里。

各文件删除行的确切位置（为什么会有删除）：

- `e2e/run-local-e2e.ts` −4：`bridgeSockets` 里两段旧实现被替换（`accept()` 与桥接的顺序、二进制帧的降级解码），
  `makeRig()` 的无参调用被替换，新增设备表插入位置调整。
- `worker/test/harness/runtime.ts` −7：`deliver()` 的签名与 `queued` 字段类型、`send()` 的负载处理被替换。

**没有任何一行落在 `worker/src/` 或 `android/app/src/main/`。**

## 6. 新增测试数量

| 层 | 之前 | 现在 | 净增 |
|---|---:|---:|---:|
| Worker 单元测试（vitest） | 223 | **240** | +17 |
| Android JVM 单元测试 | 171 | **180** | +9 |
| 本地模拟 E2E check | 21 | **65** | +44 |
| **合计** | 415 | **485** | **+70** |

其中 Android 的 +9 = 新文件 `PhoneLinkReliabilityTest.kt` 7 条 + `PhoneLinkTest.kt` 新增 2 条。

## 7. 测试覆盖矩阵

图例：**VERIFIED** 已有可执行测试证明行为符合预期 · **CONFIRMED_DEFECT** 有可执行测试证明偏离预期 ·
**STATIC_RISK** 只能靠读代码判定，尚无测试 · **TEST_GAP** 无覆盖 ·
**REAL_DEVICE_REQUIRED** 只能在真机上验证。

### 7.1 任务 A：Mock Phone 场景（`e2e/run-local-e2e.ts` 阶段 ⑨）

| # | 场景 | 状态 | 关键证据 |
|---|---|---|---|
| A1 | `no-response` | VERIFIED | Gate 1 阶段已有；本轮 D5-a 与 `phone-session.test.ts` §2 从服务端侧再次覆盖 |
| A2 | `wrong-request-id` | VERIFIED | `phone_timeout`（1014ms）、`pongsUnknown=1`、表与镜像归零、绝无虚假成功 |
| A3 | `duplicate-pong` | VERIFIED | `pongsMatched===1`（只结算一次）、多出的那份只记 `pongsUnknown`、下一个请求正常 |
| A4 | `late` | VERIFIED | 超时 150ms / 延迟 400ms：`phone_timeout`(162ms)、迟到帧只算未知、第二个请求成功且 request_id 不同 |
| A5 | `drop-after-ping` | VERIFIED | `phone_disconnected`(12–21ms，远小于 5000ms)、无自动重放、之后新请求**快速** `phone_offline`(5–7ms) |
| A6 | `noisy` | VERIFIED | 3 帧垃圾全部被计数拒绝（`messagesRejected=3`）、`pongsUnknown=0`、`pongsStaleGeneration=0`、链路照常可用、**另一台设备不受影响** |
| A7 | `binary` | VERIFIED | `messagesRejected=1`、**`pongsMatched===0`**、如实 `phone_timeout`(1212ms)、连接未被误关 |
| A8 | `hello-mismatch` | VERIFIED | 已认证身份不被改写、不一致被 `messagesRejected=1` 留痕、被冒充的 `device_id` 未被路由（`connected=false`、请求 `unknown_device`） |

### 7.2 任务 B：Durable Object 竞态

| # | 场景 | 状态 | 测试位置 |
|---|---|---|---|
| B1 | 超时与 pong 同时发生 | VERIFIED | `phone-session-reliability.test.ts` 5 条（相对超时偏移 −3/−1/0/+1/+3ms），不规定谁赢，只要求 `pongsMatched + timeouts === 1` 且表/镜像归零 |
| B2 | 相同 request_id 并发 | VERIFIED | 同上「第二个被 409 拒绝」；`phone-session.test.ts` §14 |
| B3 | 不同 request_id 并发 | VERIFIED | 同上「三个并发请求：中间那个超时，迟到 pong 只算未知，不串台也不复活」；`phone-session.test.ts` §8 |
| B4 | 旧连接响应 | VERIFIED | `phone-session.test.ts` §7 |
| B5 | 连接替换 | VERIFIED | `phone-session.test.ts` §6、§11（6 条） |
| B6 | 发送失败 | VERIFIED | 同上「server 侧 socket 已不可写」「发送失败后手机重连」「记账比超时还慢时」3 条 + `phone-session.test.ts` §12/§13 |

### 7.3 任务 C：Android 连接可靠性

| # | 场景 | 状态 | 测试位置 |
|---|---|---|---|
| C1 | 用户主动断开后禁止自动重连 | VERIFIED | `PhoneLinkTest`「用户断开后不再自动重连」等 3 条；`ConnectGateTest`；**本轮新增客户端层**「C1 用户按下断开之后…不得触发任何重连」+ 阳性对照 |
| C2 | 网络恢复触发重新连接 | VERIFIED | `PhoneLinkTest`「网络恢复时重置退避并立即重连」；**本轮新增**「C2 C3 网络恢复抢在重连定时器之前时，只发生一次新连接」 |
| C3 | 重连定时器与网络恢复事件竞争 | VERIFIED | **本轮新增 5 条**：`PhoneLinkTest` 2 条（状态机层）+ `PhoneLinkReliabilityTest` 2 条（真实调度器层）+ 1 条阳性对照 |
| C4 | 旧 WebSocket 回调晚于新连接 | VERIFIED | **本轮新增**「C4 重复发起连接会替换掉旧连接，旧连接的迟到回调不得干扰新连接」+「C4 阳性对照」 |
| C5 | 401/403 后不自动重试 | VERIFIED | `PhoneLinkTest` 2 条 + `PhoneLinkClientTest` 1 条 |
| C6 | 心跳超时后的状态转换 | VERIFIED | `PhoneLinkTest`「连续两个心跳周期没有任何服务端帧则主动重连」「收到任意服务端帧都会重置静默计时」 |
| C7 | 连接成功后重置退避计数 | VERIFIED | `PhoneLinkTest`「重连成功后退避计数归零」（注意归零点在 `handleWelcome`） |
| C8 | 服务端静默但 TCP 未关闭 | VERIFIED | **本轮新增**「C4 C8 服务端静默但 TCP 未关时主动重连」——断言必须由**静默**判定（服务端全程没关过任何 socket），日志出现「未收到服务端任何帧」「心跳超时」，旧连接被主动关掉 |

C3/C4/C8 是阶段 0 报告列出的缺口，本轮补齐。**C4 的防线只在传输层**
（`PhoneLinkClient` 的 `epochCounter`/`currentEpoch`/`isCurrent`），纯状态机层没有"世代"概念，
所以这条必须用真实 OkHttp WebSocket 才能测到 —— 这也是新文件用 MockWebServer 而不是直测状态机的原因。

### 7.4 任务 D：安全边界

| # | 场景 | 状态 | 测试位置 |
|---|---|---|---|
| D1 | 未配置设备凭据必须拒绝 | VERIFIED | `worker-index.test.ts`「没配 DEVICE_TOKENS 时失败关闭（401）」 |
| D2 | 错误设备令牌 | VERIFIED | `worker-index.test.ts` 3 条（含"设备不存在与令牌错误不可区分，防枚举"） |
| D3 | 已撤销凭据的新连接 | VERIFIED | `worker-index.test.ts`「已吊销的凭据被拒绝」；**本轮新增** D4-a 对照组坐实"撤销确实生效" |
| D4 | 已连接设备随后撤销凭据 | **CONFIRMED_DEFECT（P1）** | **本轮新增** D4-a（对照）+ D4-b（缺口） |
| D5 | 半开连接 | **CONFIRMED_DEFECT（P1）** | **本轮新增** D5-a（缺口）+ D5-b（对照）+ D5-c（重连可恢复） |
| D6 | 日志脱敏 | VERIFIED | `logging.test.ts` 12 条（纯函数层）+ **本轮新增** D6-f / D6-g（走真实 Worker 流程的端到端验证） |

### 7.5 覆盖率变化的总体形态

- 阶段 0 的 18 项矩阵里，**PARTIAL 的心跳超时**与 **PARTIAL 的自动重连**本轮都升级为 VERIFIED；
- 阶段 0 的缺口清单 G-1/G-2/G-4/G-7/G-8 本轮转为可执行证据（G-3 真实驱逐仍为 TEST_GAP）；
- **新增 CONFIRMED_DEFECT 2 条**（D4-b、D5-a），均由测试而非读代码得到。

## 8. 全量回归结果

全部在本机执行，命令与仓库 `package.json` / Gradle 配置一致。

| # | 检查 | 命令 | 数量 | 通过 | 失败 | 跳过 | 耗时 | 退出码 |
|---|---|---|---:|---:|---:|---:|---:|---:|
| 1 | TypeScript 类型检查 | `npm run typecheck` | — | — | 0 | — | 3s | **0** |
| 2 | Worker 单元测试 | `npm test` | 240 | 240 | 0 | 0 | 6.50s | **0** |
| 3 | Android JVM 单元测试 | `cd android && ANDROID_HOME=/opt/android-sdk ./gradlew --no-daemon :app:testDebugUnitTest --rerun` | 180 | 180 | 0 | 0 | 1m06s | **0** |
| 4 | 本地模拟 E2E | `npm run e2e` | 65 | 65 | 0 | 0 | 16s | **0** |
| 5 | Android Lint（静态检查） | `cd android && ANDROID_HOME=/opt/android-sdk ./gradlew --no-daemon :app:lintDebug` | — | — | 0 | 0 | 51s | **0** |

补充事实：

- 检查 2 的明细：10 个测试文件；`phone-session.test.ts` 46 条、`phone-session-reliability.test.ts` 10 条、
  `security-boundary.test.ts` 7 条，其余 7 个文件共 177 条。vitest 自带耗时统计 `tests 11.44s`，
  墙钟 6.50s（多文件并行）。
- 检查 3 的明细（15 个类，180 条）：`ManifestComponentTest` 2、`BackoffTest` 9、`DeviceTokenStoreTest` 18、
  `HubConfigTest` 8、`HubLogTest` 4、`SocketFailureTest` 9、`WsUrlTest` 8、`PhoneLinkClientTest` 7、
  **`PhoneLinkReliabilityTest` 7**、`LogRedactionTest` 13、`PhoneLinkProtocolTest` 15、
  **`PhoneLinkTest` 37**、`PingResponderTest` 9、`ConnectGateTest` 21、`LinkBusTest` 13。
- 检查 3 **刻意连续跑了两遍**，两遍都是 180/180、`EXIT=0` —— 见下条。
- 检查 5 的结果是 **0 errors, 29 warnings**，与阶段 0 基线（0 errors / 29 warnings）完全一致，未引入新告警。
  29 条 warning 全部是既有的 `ObsoleteSdkInt` / `DataExtractionRules` / `ConstantLocale` 之类，与本次改动无关。

### 关于 `--rerun`（阶段 0 的方法学教训，本阶段继续适用）

阶段 0 发现 Android 单测第一次跑会命中 **`:app:testDebugUnitTest FROM-CACHE`**，
Gradle 直接复用上次结论、**本轮根本没执行测试**，那个 `BUILD SUCCESSFUL` 不构成证据。
本阶段所有 Android 单测命令**一律带 `--rerun`**，并核对 `> Task :app:testDebugUnitTest` 是 `executed` 而不是
`UP-TO-DATE` / `FROM-CACHE`。

### 本轮修掉的一处**不确定性**（写在这里，因为它影响"回归结果可不可信"）

新文件里的 `C3 重连定时器先到点、网络恢复随后到达时…` 第一次进全量回归时**红了**：

```
java.lang.AssertionError: 网络恢复应当再建一条新连接
    at com.androidmcphub.app.net.PhoneLinkReliabilityTest.C3 ...(PhoneLinkReliabilityTest.kt:290)
```

但它单独跑（`--tests` 过滤该文件）时却是绿的。**这是一条看运气的测试，不是业务缺陷。**

根因：`conns[0].opened` 是**服务端**侧看到 socket 的时点，它比客户端跑完 `onOpen` 更早。
在这段窗口里 `PhoneLink.socketOpen` 仍是 `false`，于是 `onNetworkAvailable()` 会走"立即重连"分支
（返回 `[Close, Open]`）多建一条连接；而机器负载重、客户端已经处理完 `onOpen` 时，
同一个调用就变成 no-op（`if (socketOpen) return emptyList()`）。

修法不是放宽断言，而是**把等待条件换成能证明客户端也处理完 welcome 的那个**：
先 `awaitState { Connected(2) }`，再触发 `onNetworkAvailable()`，此时 `socketOpen == true`，行为确定。
该用例被改写为「已经连上时，网络恢复回调不得引起任何状态变化 / 不得再建连接 / 不得关掉现有连接」，
并另外新增一条「重复发起连接会替换掉旧连接」来真正覆盖连接替换。
改写后连跑两遍全量，180/180 稳定。

### NOT RUN（明确没有执行，不算通过）

| 项目 | 命令 | 为什么没跑 |
|---|---|---|
| 真实设备 `phone_ping` 闭环 | — | 任务书 §二：本轮仅测试开发，且"不执行任何真实设备破坏性测试"。 |
| 部署后冒烟 | `npm run smoke` / `npm run smoke:selftest` | 需要已部署的 Worker 实例；本轮禁止部署。 |
| 真实凭据校验 | `npm run check:credential` | 需要生产设备凭据，任务书禁止读取/输出生产密钥。 |
| 真实 workerd 运行时 | `npm run dev` | 本容器 workerd 起不来（见 §10 的 R-3）。 |

## 9. 已确认缺陷

> 定义：**CONFIRMED_DEFECT** = 有可执行测试证明其存在、且偏离预期行为的现象。
> 级别：P0 安全或核心功能阻断 / P1 高风险可靠性问题 / P2 一般缺陷 / P3 低优先级。
> **本轮没有任何 P0。**

### D-1（P1，CONFIRMED_DEFECT）撤销凭据不会终止已经建立的连接

- 状态：阶段 0 的 **R-1 STATIC_RISK** 升级为 **CONFIRMED_DEFECT**。
- 证据：`worker/test/security-boundary.test.ts` 的 D4-a / D4-b 一对。
- D4-a（对照组）：同一个 rig 里先升级成功（101）→ 改写 `DEVICE_TOKENS` 把该凭据置 `revokedAt` →
  再升级必须 **401** 且带 `WWW-Authenticate: Bearer`。这证明"撤销确实生效"，
  否则 D4-b 会以错误的理由变绿。
- D4-b（缺口）：先 `connectMockPhone("normal")` → 发一个在途 ping → 撤销 → 实测：
  1. 在途请求照常 `ok:true`；
  2. `status.connected === true` 且 `socket.readyState === 1`；
  3. **撤销之后新发的 `phone_ping` 依然 `ok:true`** —— 设备**仍在继续接收新请求**；
  4. 同一时刻 Worker 层的新连接已经是 401 —— **两层判断不一致，这就是缺口本身**；
  5. `metrics.authFailures === 0` —— DO 自己根本不参与鉴权。
- 机制（读代码）：撤销只在**升级时**被检查（`worker/src/index.ts:165` → `activeCredentialsFor`；
  `worker/src/config.ts:310`、`:318`）。**DO 没有任何撤销输入，也没有强制关闭 socket 的接口。**
- 为什么是 P1 而不是 P0：被撤销的设备**不能建立新连接**，但它**已经建立的那条连接会一直有效**，
  且能继续执行 `phone_ping`。对"撤销 = 立刻切断"的直觉预期而言这是安全缺口。
- 规格事实（必须如实记录）：**当前协议从未承诺"撤销即刻断开"**。所以这是设计缺口，
  不是回归 —— 但既然有测试能证明它，就不该再停留在"静态风险"。

### D-2（P1，CONFIRMED_DEFECT）半开连接的在线状态误报

- 状态：阶段 0 的 **R-2 STATIC_RISK** 升级为 **CONFIRMED_DEFECT**。
- 证据：`worker/test/security-boundary.test.ts` 的 D5-a / D5-b / D5-c。
- D5-a（缺口）：`no-response` 手机（不回应任何应用层消息，但 TCP 一直开着）→
  `status.connected === true`、`open_sockets > 0`；一个 `timeout_ms = 300` 的 ping
  **拖满整个超时**才失败为 `phone_timeout`（`elapsed >= timeoutMs - 20`），
  而**不是**快速的 `phone_offline`。无虚假成功（这点是好的），随后 `pending_requests=0`、
  `pending_mirror=0`、`timeouts=1`、`pongsMatched=0`。
- D5-b（对照）：真的 `socket.close(1006)` 之后 `connected === false`，同一个 ping **快速**
  `phone_offline`（`elapsed < 500`，而不是 5000ms）。⇒ 两种情况的差异被坐实。
- D5-c：半开期间手机重连 → 旧 socket 被以 **4001** 关掉，新代次 ping 成功，`connection_generation >= 2`。
  ⇒ 缺口**可以自愈**，但只能等手机自己回来。
- 机制：`worker/src/phone/phone-session.ts:846-854` 的 `currentSocket()` **只看当前代次有没有已注册的
  socket，不检查 `readyState`、也不看最后一次收到帧是多久以前**。而服务端对 `heartbeat` 只回
  `heartbeat_ack`（`phone-session.ts:629-630`），**没有任何基于心跳的存活判定**。
- 诚实边界（写进测试注释里的）D5 造的是"**应用层不回帧**"，**不是** TCP 半开。
  真实的 TCP 半开（对端断电/进隧道、本端 socket 仍 ESTABLISHED）是 **REAL_DEVICE_REQUIRED**（见 §11），
  **刻意没有**包成一条永远为真的 `it()` —— 一条永远不会红的断言不是证据。

### D-3（P3，CONFIRMED_DEFECT）同一个工具返回两套错误编码

- `phone_ping` 在**设备解析失败**时用扁平字段
  `structuredContent: {ok:false, error_code, error_message}`（`worker/src/mcp/tools.ts:241-255`、`:256-275`），
  在 **DO 失败**时用嵌套字段 `structuredContent.error.code` / `.message`。
- 证据：A8 实测同时出现两种形状 —— `unknown_device` 走扁平，`phone_timeout` 走嵌套。
- 影响：客户端要写两套解析。**本轮按任务书不改业务代码**，只在 E2E 里把两种都读出来。
- 为什么是 P3：不影响正确性，只影响 API 一致性。

### D-4（P2，CONFIRMED_DEFECT · 测试基础设施，已修复）E2E 桥接让"协议约束"永远走不到

这两条是**测试代码**的缺陷，本轮发现并修复。它们不污染生产，但会让**既往的 E2E 证据失真** ——
所以必须完整披露。

1. **`welcome` 被静默丢弃，`hello` 分支在 E2E 里从未被执行过。**
   `startServer` 里原来是 `workerSide.accept(); bridgeSockets(real, workerSide);`。
   `FakeWebSocket.accept()` 会**立刻 flush** 队列并 dispatch `message`，而那时桥的监听器还没挂上，
   于是 DO 在返回 101 **之前**发出的 `welcome` 被丢掉。可观测后果：**所有** mock phone 的
   `welcomeSeen` 恒为 false，`hello-mismatch` 场景因此根本没发出它那帧 hello，`messagesRejected` 恒为 0。
   修法：调换顺序 —— **先 `bridgeSockets`，再 `accept()`**。修复后 `welcome:` 日志出现 10 次
   （= 10 个 mock 进程），`phone_session.hello` 与
   `phone_session.hello_device_mismatch{authenticatedDeviceId:"rel-hello-mismatch", claimedDeviceId:"some-other-phone"}` 都出现了。
2. **二进制帧被降级成文本，"二进制必须被拒绝"这条约束在 E2E 里永远走不到。**
   原来是 `workerSide.send(isBinary ? new TextDecoder().decode(data) : data.toString())` ——
   一个"内容是 phone_pong 字节"的二进制帧会被解码成**合法 JSON 文本**、**当场被配对成合法 pong**。
   修法：二进制走 `ArrayBuffer` 原样转发。修复后 A7 才真的有意义
   （`messagesRejected=1`、`pongsMatched=0`、`phone_timeout`）。

## 10. 静态风险

### R-3（STATIC_RISK）真实驱逐（eviction）下的在途请求结局无法在本机验证

- `worker/test/phone-session.test.ts` §10「对象重建」断言"驱逐后原实例的本地 `setTimeout` 仍会结算它"——
  这是 **Node 替身特有**的行为。真实 workerd 驱逐会**带走内存里的定时器与在途 fetch**，
  所以生产环境下这类请求的结局（会被重建后的 `reconstructedPendingFailed` 接管吗？）**没有测试覆盖**。
- 本容器 **workerd 永久跑不起来**：`MmapAligned() failed`、
  `TCMalloc assumes a 48-bit virtual address space`、
  `FATAL ERROR: Out of memory trying to allocate internal tcmalloc data`，退出码 134。
  `TCMALLOC_ADDRESS_BITS` 是编译期参数，`LD_PRELOAD` 改不了。
- ⇒ **所有 Worker 结论都建立在 Node 替身之上**，这是本阶段全部 Worker 证据的共同前提。
- 归类：STATIC_RISK（无法在真机/真运行时验证）。

### R-4（STATIC_RISK → 部分缓解）网络恢复后的重连行为

- 阶段 0 标为 STATIC_RISK（低）。本轮 C2/C3 在状态机层与真实调度器层都补上了可执行测试，
  风险等级下降；但"**真正切换 WiFi ↔ 蜂窝**之后的重连"仍然是 REAL_DEVICE_REQUIRED。

### R-5（STATIC_RISK）心跳缺少服务端侧的阈值定义

- 服务端收到 `heartbeat` 只回 `heartbeat_ack`（`phone-session.ts:629-630`），
  **没有把"多久没收到手机任何帧"变成一种可判定的状态**。
  客户端有（`PhoneLink.onTick()`：`max(2 × heartbeatIntervalMs, pingTimeoutMs)`），
  服务端没有。D-2 的表现形式就是这条的直接后果。
- 归类：STATIC_RISK（P2）—— 修复需要改业务代码，属阶段 1B 范围。

### R-6（STATIC_RISK）测试里编的假凭据会被仓库自带的安全守卫抓

- `worker/test/security.test.ts:119` 会扫描"40 位 base62 且含数字"的字符串来防止密钥泄漏，
  它的扫描范围**包含 `worker/test/` 本身，也包含 `docs/`**。本轮一开始编的假令牌
  （40 字符、含数字、形如 `STAGE1A-FAKE-TOKEN-<8 位十六进制>-never-real`）正好命中，导致 `npm test` 239/240。
  改成 `STAGE1A-FAKE-TOKEN-NEVER-REAL`（29 字符、不含数字）后 240/240。

  ⚠ 2026-xx 追记（Gate 2 轮次发现）：**本文件当时也引用了那串旧令牌原文**，而报告是在
  最后一次全量回归**之后**才写的 —— 于是这份文档自己把那条守卫重新点亮了，
  只是当时没人再跑一遍 `npm test`。Gate 2 轮次已把原文替换成描述性文字。
  教训：报告里引用"触发安全守卫的字符串"时，要么写形状、要么在写完报告后再跑一次全量回归。
- 这条风险本身不是缺陷，但它是个**给后续测试作者的坑**：写假凭据时不能凑成"40 位含数字"的形状，
  注释里也不能引用旧的那串。

## 11. 尚未验证的真机场景（REAL_DEVICE_REQUIRED）

以下场景**无法**用 JVM 单测或本地模拟 E2E 诚实覆盖，**一律没有伪造 PASS**。

| # | 场景 | 为什么必须是真机 | 验证步骤 |
|---|---|---|---|
| RD-1 | TCP 半开（对端断电/进隧道，本端 socket 仍 ESTABLISHED） | D5 造的是"应用层不回帧"；真正的半开要靠系统网络栈，MockWebServer 造不出来 | 真机连上 → 开飞行模式但保持 App 进程 → 服务端 `GET /status` 看 `connected` → 发 `phone_ping` 记录是否拖满超时 → 观察多久后才被判定死亡 |
| RD-2 | 真实网络切换（WiFi ↔ 蜂窝）触发的重连 | `MockWebServer` 只能模拟"服务端关闭"，不能模拟网络接口变化 | 真机连上 → 关 WiFi 开蜂窝 → 观察是否重连、退避是否重置、旧连接是否被关 |
| RD-3 | 真实 workerd 驱逐下在途请求的结局（R-3） | 本容器 workerd 跑不起来（退出码 134） | 部署到 Cloudflare → 触发 DO 驱逐（或等自然驱逐）→ 观察在途 `phone_ping` 的最终响应 |
| RD-4 | 真机长时间静默后服务端的在线判定 | 需要真实手机进后台/被系统冻结 | 真机连上 → 息屏挂 10 分钟 → 服务端 `GET /status` 与 `phone_ping` 的实际表现 |
| RD-5 | 撤销凭据后真机侧的观感（D-1 的生产形态） | D4-b 是替身结论 | 真机连上 → 在 Cloudflare 改 `DEVICE_TOKENS` 撤销 → 观察手机是否仍能收到 ping、何时断开 |

RD-1 至 RD-5 **本轮全部未执行**。

## 12. 建议阶段 1B 修复范围

按优先级：

**P0 —— 无。** 没有发现阻断核心功能或直接可被利用的安全缺陷。

**P1（建议优先）**

1. **给 DO 一条撤销通道**（对应 D-1）。可选做法：
   - 在凭据里保存可判定的 `revokedAt`，由 DO 在每次 `handlePingRequest` 之前校验；
   - 并提供一个内部路由（如 `POST /revoke`）让 Worker 在撤销时直接命令 DO
     **关闭当前 socket（4001）+ 结算所有在途请求 + 拒绝后续请求**；
   - 需要先确定**规格**：撤销是"立刻断开"还是"不再接受新请求但让在途请求跑完"。规格没定就不该改代码。
2. **服务端侧的应用层存活判定**（对应 D-2 / R-5）：
   - 记录 `lastPhoneActivityAt`（任何入站帧都刷新）；
   - 在 `handleStatus` / `handlePingRequest` 里对超过阈值的连接判定为 stale，
     返回 `phone_offline` 而**不是**拖满 `timeout_ms` 的 `phone_timeout`，并主动关闭 socket；
   - 阈值需要新的 env（例如 `PHONE_SILENCE_TIMEOUT_MS`），
     ⚠ 按 `worker/src/config.ts:217-234` 的注释，**新 env 字段必须加进 `loadConfig` 的 `cacheKey` 数组**，
     否则改了环境变量不会生效。

**P2**

3. 统一 `phone_ping` 的错误编码（D-3），或至少在文档里写清两套形状。
4. 面对 R-3：把"驱逐后如何接管在途请求"写进设计文档，并在可能的情况下用真实 workerd 验证一次。

**P3**

5. 配置夹紧区间（阶段 0 的 G-5，`parseBoundedInt` 越界静默夹紧只记 `problems`）补测。
6. MCP 层并发（G-6）、`MAX_PENDING_REQUESTS` 多档边界（G-9/G-10 相关）补测。

**注意：以上全部属于阶段 1B，本阶段一行都没有动。**

## 13. 向后兼容性分析

### 生产代码

`git diff --stat gate0` 的输出里**没有** `worker/src/**` 与 `android/app/src/main/**`：

```
 .../androidmcphub/app/protocol/PhoneLinkTest.kt    |  42 +++
 e2e/run-local-e2e.ts                               | 420 ++++++++++++++++++++-
 worker/test/harness/runtime.ts                     |  84 ++++-
 3 files changed, 535 insertions(+), 11 deletions(-)
```

⇒ 协议帧格式、`phone_ping` 的请求/响应形状、MCP 工具注册、OAuth 流程、设备认证方式、
`AndroidManifest.xml`、Android 权限、Cloudflare Secrets、生产数据库、设备配置 —— **全部未动**。
Gate 1 已验收的 ping 链路**零影响**。

### 测试基础设施

唯一"行为变化"发生在测试代码里，且方向都是**更严格**：

- E2E 桥接不再丢弃 `welcome`、不再把二进制帧降级成文本
  ⇒ 原来悄悄通过/悄悄走不到的场景，现在会**如实**反映协议行为；
- `FakeWebSocket` 的 `receivedFrames` **刻意没变**（二进制进新的 `receivedBinaryFrames`）
  ⇒ 所有既有用例的断言语义不变；
- `FakeDurableObjectStorage.delayNext` 是**新增**通道，与既有的 `failNext` / `hangNext` 互相独立，
  不传参时行为与从前逐字节相同。

### 风险

唯一需要留意的是 **A7/B6 这类新用例本身对时序敏感**。缓解措施：所有等待都用
`ensureConnected` / `awaitState` / `waitUntil` / `waitForMetric` 轮询到**事件真的发生**，
不用 sleep 碰运气；凡是"证明某件事在足够长时间内都不发生"的地方，都配一条阳性对照。
改写后的 Android 全量连跑两遍稳定 180/180。

## 14. 回滚方案

本轮改动完全隔离，回滚成本极低，且**不涉及任何远程状态**（未推送、未部署、未改 Secret、未改数据库）。

**整分支回滚**

```bash
cd /root/android-mcp-hub
git checkout gate0                # 回到基线
git branch -D test/gate1.5-reliability
```

**只在本分支内回滚（保留分支）**

```bash
# 3 个被修改的文件恢复成基线版本
git checkout gate0 -- \
  android/app/src/test/java/com/androidmcphub/app/protocol/PhoneLinkTest.kt \
  e2e/run-local-e2e.ts \
  worker/test/harness/runtime.ts

# 4 个新增文件删除
rm worker/test/phone-session-reliability.test.ts
rm worker/test/security-boundary.test.ts
rm android/app/src/test/java/com/androidmcphub/app/net/PhoneLinkReliabilityTest.kt
rm docs/Gate1.5-Stage1A-Reliability-Tests.md
```

**逐字节校验**

回滚后用下面的命令确认工作区与基线一致（`docs/Gate1.5-Stage0-Test-Baseline.md` 是开工前就存在的未跟踪文件，
本来就不该被清理，它的 sha256 应保持
`d63415ee5c2bd08f304344a5b55bc65864b71b07e66ed9a30ee3c335b7c335a9`）：

```bash
git status --short          # 只剩 ?? docs/Gate1.5-Stage0-Test-Baseline.md
git diff gate0 --stat       # 应为空
sha256sum docs/Gate1.5-Stage0-Test-Baseline.md
```

**不需要回滚的部分**：`gate0` 分支从未被移动；远程从未被写入；没有任何部署产物。

---

## 附：本阶段交付物清单

**新增**

- `worker/test/phone-session-reliability.test.ts`（10 条）
- `worker/test/security-boundary.test.ts`（7 条）
- `android/app/src/test/java/com/androidmcphub/app/net/PhoneLinkReliabilityTest.kt`（7 条）
- `docs/Gate1.5-Stage1A-Reliability-Tests.md`（本报告）

**修改**

- `android/app/src/test/java/com/androidmcphub/app/protocol/PhoneLinkTest.kt`（+2 条）
- `e2e/run-local-e2e.ts`（+44 项 check，修 2 处保真度缺陷）
- `worker/test/harness/runtime.ts`（二进制帧支持、`delayNext` 注入通道）

**回归**：typecheck 0 失败 · Worker 240/240 · Android 180/180（连跑两遍）· E2E 65/65 · lint 0 errors。

**提交**：仅一个本地提交，位于 `test/gate1.5-reliability`，不含任何业务代码，**未推送**。
