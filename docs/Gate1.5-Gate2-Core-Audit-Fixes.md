# Gate 1.5 / Gate 2 集中审计问题修复交付报告

> 本文件是**交付报告**。全部改动停留在本地分支 `feat/gate2-core`，本轮**未合并** `gate0`、
> **未部署** Worker、**未改动**任何生产 Secrets 或生产令牌。
> 报告里出现的每个测试数字都来自本机实际执行，未执行的检查单独列在 §8。

---

## 1. 本轮基线、提交与改动文件

### 1.1 分支与 Commit

| 项 | 值 |
| --- | --- |
| 仓库 | `wenqv4617-art/android-mcp-hub`（私有） |
| 工作分支 | `feat/gate2-core` |
| `gate0`（主分支） | `39ef38eec151b4bb21b4e6cac933ec5221a12302` —— **本轮未移动** |
| 审计基线（起） | `ac17a19b86020c4add4d75712e377b38027e90d7` |
| **Commit A（Worker：问题 1/2/3）** | `350dadb` `fix(gate1.5): 连接绑定具体凭据、撤销后的重新授权与静默连接的存活 alarm` |
| **Commit B（Android：问题 4/5）** | `543ea8908332f3bf5936567b94f00da5b40b0d5d` `fix(android): 4002/device_revoked 进入终止态，能力总开关首次安装默认关闭` |
| **Commit C（本报告）** | `docs(gate2): 集中审计 5 问题修复交付报告`（SHA 见 `git log -1`，本文件不写自身 SHA 以免自引用失准） |

`git diff --stat ac17a19..543ea89`：**20 文件，+1383 / −145**（Commit A：8 文件 +964/−131；Commit B：12 文件 +419/−14）。

### 1.2 改动文件清单

**Worker（Commit A）**

- 修改源码：`worker/src/config.ts`（凭据指纹与"此刻是否有效"的判据）、
  `worker/src/index.ts`（升级鉴权改成 `find()` 并把命中的指纹交给 DO）、
  `worker/src/mcp/http.ts`（撤销通知带上可选的凭据范围）、
  `worker/src/phone/phone-session.ts`（本轮改动主体，+570 行）
- 修改测试：`worker/test/harness/env.ts`（撤销/轮换/指纹夹具）、
  `worker/test/phone-lifecycle.test.ts`（24 → 29 条）、
  `worker/test/phone-session.test.ts`（alarm 断言注释与前提）、
  `worker/test/security-boundary.test.ts`（D4-b 从"记录缺口"改成"守住修复"）

**Android（Commit B）**

- 修改源码：`.../core/HubPrefs.kt`、`.../protocol/PhoneLink.kt`、`.../protocol/PhoneLinkProtocol.kt`、
  `.../net/PhoneLinkClient.kt`、`.../service/PhoneLinkService.kt`、`.../ui/HubScreen.kt`、
  `.../ui/HubViewModel.kt`、`app/src/main/res/values/strings.xml`
- 新增测试：`.../test/.../core/CapabilitySwitchDefaultTest.kt`（新文件）
- 修改测试：`.../test/.../protocol/PhoneLinkTest.kt`、`.../test/.../net/PhoneLinkClientTest.kt`、
  `.../test/.../capability/CapabilityDispatcherTest.kt`

**未跟踪（刻意不提交）**：`docs/Gate1.5-Stage0-Test-Baseline.md` —— 阶段 0 报告，
用户明确要求保持未跟踪，本轮同样未动、未提交。

### 1.3 结果汇总（详见 §7）

| 检查 | 基线（上一轮） | 本轮 | 结论 |
| --- | --- | --- | --- |
| Worker 单测（14 文件） | 314 | **319 / 319** | 0 失败 0 跳过 |
| TypeScript 类型检查 | PASS | **PASS（exit 0）** | — |
| Android JVM 单测（19 类） | 243 | **255 / 255** | 0 失败 0 跳过，带 `--rerun` |
| 本地 E2E | 78 | **78 / 78** | exit 0 |
| Android Lint | 29 warning / 0 error | **29 warning / 0 error** | 与基线完全同一告警集合 |

---

## 2. 问题 1：凭据轮换后旧连接仍然存活

### 2.1 审计复现的现象

> 旧凭据连接在同设备令牌轮换后仍成功返回 `phone_ping`。

### 2.2 根因（代码级）

1. **连接只绑定 `deviceId`，不绑定"是哪条凭据开的"。** 升级鉴权是
   `candidates.some((c) => timingSafeEqual(digest, c.tokenSha256))` ——
   它只回答"匹配上了没有"，匹配到哪一条被丢掉了；socket 附件里只有
   `{ deviceId, generation }`。于是"轮换掉的那条旧凭据"和"当前有效的新凭据"
   在连接层面**不可区分**。
2. **撤销只是一个落盘的粘性布尔**，而不是"按当前配置推导"。旧实现里
   `isDeviceRevoked()` 的判据可以退化成落盘状态，而设备级布尔无法表达
   "这台设备还有一条有效凭据、只是某一条被换掉了"这种中间状态 —— 于是轮换
   在 DO 侧根本没有触发点。

### 2.3 修复

- **鉴权结果带上身份**：`worker/src/index.ts` 从 `some()` 改成 `find()`，
  把命中的 `tokenSha256` 作为 `X-Phone-Session-Credential`（`INTERNAL_CREDENTIAL_HEADER`，
  定义在 `worker/src/phone/phone-session.ts`）交给 DO。**DO 强制要求这个头**，
  缺失即 `500 missing_internal_credential_header` —— fail-closed：宁可不建立
  一条无法复核来源的长连接。指纹只走 Worker→DO 的内部通道，**不是**公网输入。
- **指纹即凭据身份**：`credentialFingerprint()`（= `tokenSha256`，明文令牌从不落盘）、
  `findCredential()`、`isCredentialActive()`、`allCredentialsFor()` 落在
  `worker/src/config.ts`。判定用**当前配置**，而不是"连接建立那一刻的结论"。
- **两个拒绝码分开**：`connectionRejection()` 返回
  `device_revoked`（这台设备此刻一条有效凭据都没有）/ `credential_revoked`
  （这条指纹被撤销，但设备还有别的有效凭据 —— 典型即**轮换**）/ `null`。
- **每个请求入口与每个上行帧都重算**：`phone_ping`、`tool_request`、
  `status`、`handlePong` 都先过 `revocationCheck()`，且撤销判断**排在离线判断之前**
  （撤销是安全判断，离线是可用性判断）。
- **主动终结**：设备级撤销终结该设备所有 socket；凭据级撤销只终结
  `attachment.credentialId === 目标指纹` 的那一条。终结时**先发一帧
  `error{device_revoked}` 再 `close(4002)`**（手机侧拿不到 reason 就分不清
  "网络断了"还是"我被撤销了"，会白重连很多次），并把在途请求显式结算为
  `device_revoked`、代次 +1（代次换代是在途迟到的 pong/tool_result 不能结算的关键）。
- **迟到的回连请求也是信号**：用**已撤销**的旧凭据来升级会被 401，
  此时 Worker 顺带推一条**凭据级**撤销通知给 DO —— 这正是"轮换后旧连接还挂着"
  最常见的触发时机。

### 2.4 关键取舍

| 取舍 | 理由 |
| --- | --- |
| 指纹用 `tokenSha256`，不引入第二条标识 | 明文令牌绝不落盘；摘要本来就在凭据表里，客户端无感、协议零改动 |
| 内部头缺失时**拒绝建连（500）**，而不是"按设备级放行" | 一条无法回答"它属于哪条凭据"的长连接，等于把问题 1 重新引回来 |
| 区分 `credential_revoked` 与 `device_revoked` | 前者是轮换（设备仍在线、新凭据可用），后者是吊销（这台设备不该再服务）；合并会让运维看不出到底发生了什么，也会让手机关错连接 |
| 撤销通知带**可选**的 `credentialId` 范围 | 范围信息只从 Worker→DO 的内部通道来，不在公网上开一个新的"按凭据撤销"接口 |

### 2.5 对应测试（`worker/test/phone-lifecycle.test.ts`，24 → 29 条）

- `配置里没有有效凭据 ⇒ 新升级被拒：403 device_revoked，且计入 upgradesRejected`
- `升级请求不带内部凭据指纹时 fail-closed：500，绝不建立无法复核来源的长连接`
- `拿另一台设备的凭据指纹来连这台设备：403 credential_revoked（跨设备冒名也不行）`
- **`凭据轮换：旧凭据的连接被终结，新凭据的连接照常服务，迟到通知**不会**误伤新连接`**
  （覆盖任务书要求的三件事：连接**已建立**、**请求在途**、**新令牌重连**）
- `拿被轮换掉的旧凭据去连：403 credential_revoked（而不是 device_revoked）`
- `撤销后的迟到 pong/tool_result 落在过期代次上，不能结算任何东西`
- `ROUTE_REVOKE 只出现在受保护的 DO 内部路径里，没有任何公网 fetch 分支引用它`（源码级断言）

对照与反例：`撤销一台设备**不影响**另一台`、`没被撤销的设备：同样的调用照常转发`
（证明上面的拒绝不是无条件的）。

---

## 3. 问题 2：撤销后无法重新授权（粘性布尔）

### 3.1 审计复现的现象

> 撤销设备后，配置有效新令牌仍得到 DO 的 403。

### 3.2 根因

`PhoneSession` 把撤销写成一个**按 deviceId 永久落盘**的布尔（旧键
`revoked_device_ids`），并且**它本身就是门禁**：一旦落盘就再也回不去。
即使这台设备后来配置了有效的新令牌、Worker 侧鉴权已经通过，DO 仍然对每一次
升级回 403 —— "重新授权"在 DO 这一层**不可能发生**。

### 3.3 修复

- **门禁与当前配置一致**：门禁完全由 `revocationCheck()` 按当前凭据表推导；
  落盘的东西降级为 `RevocationRecord`（`{ at, scope, credentialId, generation }`，
  键 `STORAGE_KEY_REVOCATION = "revocation"`），**只用于 status/日志的审计说明**。
  旧键 `STORAGE_KEY_REVOKED_LEGACY = "revoked_device_ids"` 仅用来给审计记录补一个 `at`。
- **唯一且受保护的重新授权入口**：拿一条**当前配置里有效**的凭据完成升级。
  走到 `handleConnect()` 里"门禁通过"之后，若存在撤销审计记录，就记一条
  `phone_session.reauthorized`（带 `previousScope` / `previousAt`）并把记录结算掉。
  没有别的入口 —— 不存在"调一下接口就恢复"的路径。
- **过期的撤销通知不得误伤**：`terminateSockets({scope:"credential", credentialId})`
  里，如果**没有任何活连接属于那条凭据**（`current` 不在目标集合里），直接
  `return { closed: 0, pendingFailed: 0 }` —— **连代次都不换**。理由：代次是 DO
  级全局计数，换一次等于宣布"所有在途请求作废"，新凭据的连接会被连坐；
  那这条撤销通知本身就成了拒绝服务路径。
- **安全状态不可被 reset**：`testing/reset` 依旧清不掉撤销审计记录
  （它不是门禁，但也不是可随便重置的普通状态）。

### 3.4 对应测试

- `撤销状态跨对象重建仍然生效：门禁来自配置，审计记录落盘`（**跨 DO 重建**）
- **`撤销之后补一条新凭据 ⇒ 重新授权：能连上、能服务请求，并把撤销记录结算掉`**
- `撤销之后的新请求被拒（403 + requestsRejectedRevoked）：判据是**当前配置**，不是粘性布尔`
- `testing/reset **不能**清掉撤销状态（否则它就是一条绕过撤销的后门）`
- `重复撤销是幂等的：指标只加一次，连接不会被重复关`
- `撤销通知自己带上设备身份：deviceId 对不上时拒绝执行（409）`
- `**所有**凭据都被撤销的设备，即便不传 device_id 也不会被推断出来（D-1 的根因）`
- `D4-b【R-1 缺口已关闭】`（`worker/test/security-boundary.test.ts`）：先连上 →
  撤销 → 新请求 `device_revoked` → socket `readyState=3`、`closeInfo.code=4002`
  → Worker 层新连接仍 401，**两层判断从此一致**

---

## 4. 问题 3：静默连接永远不死

### 4.1 审计复现的现象

> 新连接建立后、没有请求时，`storage.getAlarm()` 返回 null。

### 4.2 根因（两处，必须都改）

1. **调用点**：`rescheduleAlarm()` 只在"登记一个请求"时被调用。一台连上、
   发过 `hello`、之后**没有任何人问过它**的手机，根本没有 alarm —— 静默到死
   也没人发现，"半开连接"只在有人来问的时候才被看见。
2. **比较方式**：`existing > next` 才重排（"只让 alarm 更早、绝不更晚"）意味着
   心跳**永远推迟不了**死亡时刻：首次排下 `t0 + liveness` 之后，后续心跳算出的
   deadline 更晚，条件不成立，alarm 就钉在 `t0 + liveness` 上 ——
   一台心跳完全正常的手机也会在该时刻被自己的旧 alarm 判死。

### 4.3 修复

- `handleConnect()` 建连完成后**立刻** `await this.bestEffort("reschedule_alarm", () => this.rescheduleAlarm())`。
- `noteActivity()`（每一次"手机还活着"的证据：`hello` 与任何合法上行帧）
  末尾同样补一次 `bestEffort("reschedule_alarm", ...)`。
- 判据改成 `if (existing === null || existing !== next)`：alarm 跟着真实 deadline 走。
  **安全性论证**：`next = Math.min(...deadlines)` 取的是**全部**在途超时与
  存活阈值里的最小值，绝不会晚于任何一件真事 —— 所以"可以更晚"不会漏掉任何
  一个到期点，只是不再把 alive 的手机提前判死。
- 断开时清 `lastPhoneActivityAt`，`deadlines` 为空则 `deleteAlarm()`，
  不留无用 alarm。

### 4.4 对应测试（`worker/test/phone-lifecycle.test.ts` 的 A2 段）

- `应用层静默超过阈值后：connected=false，但 socket_open 仍为 true（半开被如实描述）`
- `半开连接上的请求**快速**失败为 phone_offline，而不是拖满 timeout_ms（D-2 的核心）`
- `判定链路死亡时，在途请求被显式结算为 phone_disconnected，绝不虚假成功`
- **`**阴性对照**：活着的连接不会被提前响的 alarm 误杀`**（问题 3 第二处的正面对照）
- **`任何**合法的**上行帧都会刷新存活时间（心跳确实在续命）`**
- `垃圾帧**不**刷新存活时间（那正是更该判死的情形）`
- `链路死了之后重连可以自愈：新的代次重新起算存活时间`
- `断开连接会清零活动时间：不会在重连的瞬间被读成 stale 的纯误报`
- `半开连接上的 tool_request 快速失败为 phone_offline`
- `worker/test/phone-session.test.ts` 的 R1 重连竞态用例：断言前提与注释
  按 `existing !== next` 重写（守的性质仍是"不晚于存活阈值"，而不是某个具体毫秒数）

---

## 5. 问题 4：Android 对 4002 仍自动重连

### 5.1 审计复现的现象

> `PhoneLink.onSocketClosed()` 对 4002 走 `scheduleReconnect()`。

### 5.2 根因

`LinkState` 只有 Idle / Connecting / Connected / Reconnecting / AuthFailed / Error 六态：
"服务端明确撤销了这条连接的凭据"这一情形**没有归宿**，于是 4002 与普通断线
（1006）、连接替换（4001）落进同一条分支，一律无限重连。

### 5.3 修复（`android/app/src/main/java/com/androidmcphub/app/protocol/PhoneLink.kt`）

- 新增 `data class Revoked(val detail: String) : LinkState` —— 可诊断的**终态**。
  与 `AuthFailed`（401/403：握手就被拒）和 `Reconnecting`（还在重试）都不同，
  注释写明三者的边界。
- `companion object`：`const val CLOSE_CODE_REVOKED: Int = 4002`、
  `const val REVOKED_DETAIL: String = "设备凭据已被服务端撤销"`。
- `onSocketClosed(code, reason)`：`if (code == CLOSE_CODE_REVOKED) { enterRevoked(code = code); return emptyList() }`，
  否则照旧 `scheduleReconnect(...)`。
- `onTextFrame()` 的 `ServerError` 分支：`ServerErrorCode.normalize(frame.code) == DEVICE_REVOKED`
  → `enterRevoked(code = null)` 并**立刻回 `LinkAction.Close`** —— 服务端可能来不及
  发 close 帧（或那帧永远不来），本机不能等；帧里的 `message` 仍绝不入日志。
- `enterRevoked()`：清 `reconnectScheduled` / `attemptInFlight` / `socketOpen`，
  幂等（`if (state is LinkState.Revoked) return`，只记一条日志），日志明确写
  "**不自动重连**"并给出更新设备令牌的指引。
- 三处护栏 `isTerminal(current)`（`AuthFailed`/`Revoked`/`Error`）：
  `onReconnectTimerFired()`、`onNetworkAvailable()`（网络恢复不许复活终态）、
  `scheduleReconnect()`（兜底）。`onSocketFailure()` 的 401/403 分支同样把
  `reconnectScheduled = false`（否则旧定时器会绕过"不自动重连"）。
  `start()` 是终态**唯一**出口（用户重新配置凭据后手动连接 → 回到 `Connecting`）。
- 四处穷举 `when` 同步补齐（编译器强制）：`PhoneLinkClient.describeState`、
  `PhoneLinkService.publishState`、`PhoneLinkService.buildNotification`、
  `HubScreen.statusText` / `statusDetail` / `StatusCard` 颜色；
  `res/values/strings.xml` 新增 `notif_revoked`。

### 5.4 对应测试（Android JVM）

`android/app/src/test/java/com/androidmcphub/app/protocol/PhoneLinkTest.kt`（7 条，均以 `问题4` 开头）：

- `close 4002 进入可诊断的终态且不再自动重连`（actions 为空、状态 = `Revoked(REVOKED_DETAIL)`、日志含"不自动重连"与 `close code=4002`）
- `普通断线仍然照旧自动重连（4002 不改变既有行为）`（1006 → `Reconnecting`）
- `服务端换连接（4001）仍然自动重连` ← 任务书要求的"4001 连接替换"补测
- `收到 device_revoked 错误帧就立刻进入终态（关闭回调可能永远不来）`
  （断言 `listOf(LinkAction.Close)`；帧 `message` 用哨兵串并断言它**没有**出现在日志里）
- `错误帧与 4002 都到达时只进一次终态、只记一条日志`
- `撤销之后网络恢复、心跳节拍、残留定时器都不得复活连接`
- `用户更新令牌后点连接可以恢复（终态是可逆的出口）` ← "用户主动重新配置凭据后"补测

`android/app/src/test/java/com/androidmcphub/app/net/PhoneLinkClientTest.kt` 新增**真实链路**用例：

- `问题4 服务端以 4002 撤销时进入终态，且整条链路上不再发起第二次连接` ——
  服务端先发 `error{device_revoked}` 再 `close(4002)`；预先入队 4 个 WS 升级响应
  （若真发生第二次连接会立刻被放行，从而被 `server.requestCount` 抓住）；
  重连退避固定 300ms；断言 `Revoked`、1.5s 后 `server.requestCount == 1`、
  状态历史里没有 `Reconnecting`。

---

## 6. 问题 5：本机能力总开关首次安装默认关闭

### 6.1 审计结论

`HubPrefs.capabilitiesEnabled()` 的 `getBoolean(..., true)` 让**首次安装即为"已授权"**：
装完应用、什么都没做，远端就能读本机电池状态。

### 6.2 修复

- `android/app/src/main/java/com/androidmcphub/app/core/HubPrefs.kt`：
  新增 `const val DEFAULT_CAPABILITIES_ENABLED: Boolean = false`，
  `capabilitiesEnabled()` 改用它。**只改默认值，不改键、不改语义**：
  键存在时一律以用户明确设置的值优先（老用户手动开/关过的设置不受影响）。
- `HubViewModel` 仍然只有一个初值来源（`prefs.capabilitiesEnabled()`），
  不写第二份字面量 —— 否则"以为关着其实开着"总有一天会出现。
- 高级设置的说明文案补上"首次安装默认关闭（需要你在这里主动打开），以及
  `phone_ping` 这类连通性探测不受这个开关影响"。
- **无需重连即生效**是既有机制，本轮只补了证明：`PhoneLinkService` 构造
  `CapabilityDispatcher(..., policy = { prefs.capabilitiesEnabled() })`，
  而 `policy` 每次请求现读，所以拨开关立即生效。
- `phone_ping` 走的是独立通道，不经能力分发器 —— 不受开关影响（有测试钉住）。
- 机制外的保护：新增 `CapabilitySwitchDefaultTest` 把默认值**钉死**，
  任何把它改回 `true` 的改动都必须显式改测试并给出理由。

### 6.3 对应测试

- 新增 `android/app/src/test/java/com/androidmcphub/app/core/CapabilitySwitchDefaultTest.kt`：
  `首次安装（键不存在）时总开关默认关闭`（断言 `DEFAULT_CAPABILITIES_ENABLED == false`）、
  `总开关不是 HubConfig 的一部分，键存在时以用户的值为准`
- `CapabilityDispatcherTest`：`问题5 总开关在两次调用之间翻转，无需重建分发器（即无需重连）`
  （拒绝 → 0 次执行；置真 → 成功 1 次；再置假 → 又拒绝且仍是 1 次）
- `PhoneLinkTest`：`问题5 总开关关着时工具回 not_permitted 且不执行，而 phone_ping 不受影响`
  （同一条连接上翻转开关即时生效，`tool.calls == 0` → `1`）

---

## 7. 完整测试命令与结果

全部在本机实际执行（Android 一律带 `--rerun`，避免 Gradle 用 `FROM-CACHE` 复用
上一次结论 —— 阶段 0 的教训）。

| # | 检查 | 命令 | 数量 | 结果 | 退出码 |
| --- | --- | --- | --- | --- | --- |
| 1 | TypeScript | `npx tsc --noEmit -p tsconfig.json` | — | **PASS** | 0 |
| 2 | Worker 单测 | `npx vitest run --config worker/vitest.config.ts` | 319（14 文件） | **319 passed / 0 failed / 0 skipped** | 0 |
| 3 | 本地 E2E | `npm run e2e` | 78 | **78 通过 / 0 失败** | 0 |
| 4 | Android JVM 单测 | `cd android && ANDROID_HOME=/opt/android-sdk ./gradlew --no-daemon :app:testDebugUnitTest --rerun` | 255（19 类） | **tests=255 skipped=0 failures=0 errors=0** | 0 |
| 5 | Android Lint | `cd android && ANDROID_HOME=/opt/android-sdk ./gradlew --no-daemon :app:lintDebug` | 29 warning | **0 errors / 29 warnings**（与基线同一集合） | 0 |

Worker 逐文件：`logging 12`、`mcp-http 31`、`mcp-session 11`、`oauth 53`、
`phone-lifecycle 29`、`phone-protocol 15`、`phone-session-reliability 10`、
`phone-session 46`、`phone-tool-channel 18`、`security-boundary 7`、`security 31`、
`tool-dispatch 15`、`tools 21`、`worker-index 20`。

Android 逐类：`CapabilitySwitchDefaultTest 2`（新）、`CapabilityDispatcherTest 22`、
`BatteryCapabilityTest 19`、`BackoffTest 9`、`DeviceTokenStoreTest 18`、`HubConfigTest 8`、
`HubLogTest 4`、`SocketFailureTest 9`、`WsUrlTest 8`、`PhoneLinkClientTest 8`、
`PhoneLinkReliabilityTest 7`、`LogRedactionTest 13`、`PhoneLinkProtocolTest 15`、
`PhoneLinkTest 53`、`PingResponderTest 9`、`ToolChannelProtocolTest 15`、
`ConnectGateTest 21`、`LinkBusTest 13`、`ManifestComponentTest 2`。

Lint 告警类别（全部既有、与本次改动无关）：`GradleDependency 18`、`ApplySharedPref 3`、
`AndroidGradlePluginVersion 3`、`ObsoleteSdkInt 2`、`InlinedApi 1`、
`DataExtractionRules 1`、`ConstantLocale 1`。

**未删除、未弱化任何原有断言。** 被**改写**的既有断言只有三条，都在报告里点名：

1. `worker/test/security-boundary.test.ts` 的 D4-b —— 从"记录 R-1 已知缺口
   （已有连接照常服务）"改成"守住修复（4002 终结 + 新请求明确拒绝）"。
   这条断言的方向本身就是要翻过来的：旧值描述的正是被修掉的行为。
2. `worker/test/phone-session.test.ts` 的 R1 重连竞态 alarm 断言 —— 前提从
   `existing > next` 改成 `existing !== next`，守的性质不变。
3. `PhoneLinkTest` 的 `Harness` 构造多了一个 `policy` 参数（默认 `AllowAll`），
   既有用例行为不变。

另外两条测试是**重写既有用例名下的实现**（`phone-lifecycle.test.ts` 内），
净增 5 条（24 → 29）。

---

## 8. 未能执行的检查（NOT RUN，不得读作 PASS）

| # | 检查 | 状态 | 原因 |
| --- | --- | --- | --- |
| N-1 | 真实 workerd 下的驱逐语义（DO 被驱逐时在途请求/本地定时器的结局，即阶段 0 的 R-3） | **NOT RUN** | 本容器**跑不起来 workerd**：`MmapAligned() failed` / `TCMalloc assumes a 48-bit virtual address space`，exit 134。所有 Worker 结论都建立在 Node 替身（`worker/test/harness/`）上 |
| N-2 | 真机（Android）上的任何一条端到端行为 | **NOT RUN** | 本轮没有可用的真机链路（无已部署 Worker、无可用设备通道）；Android 侧证据全部来自 JVM 单测与 MockWebServer |
| N-3 | 部署到 Cloudflare 后的行为（含 DO 迁移、Secret 变更、真实 WebSocket 升级路径） | **NOT RUN** | 任务书明确禁止部署；本轮未部署、未改生产 Secrets |
| N-4 | Compose UI 的交互（拨开关、拨开关后界面文案） | **NOT RUN** | Compose 没有在本仓库搭建 UI 自动化测试；本轮只覆盖到 ViewModel/状态层与文案字符串 |

---

## 9. 仍需真实 Cloudflare / Android 真机验证（REAL_DEVICE_REQUIRED）

| 编号 | 场景 | 为什么单测不够 | 验证步骤（建议） |
| --- | --- | --- | --- |
| RD-1 | 旧凭据连接在真机上的轮换行为（问题 1） | 替身里的 WS 没有真实 TLS/升级路径；真机的重连退避与真实网络叠加 | 真机用旧令牌连上并保持；后台把该令牌轮换掉；观察旧连接是否在**下一条**请求前收到 `error{device_revoked}` 并以 4002 关闭，而手机用新令牌重连后照常服务 |
| RD-2 | 重新授权的真机观感（问题 2） | 替身没有"部署间隔"与真实 Secret 生效延迟 | 吊销 → 手机上补新令牌并连接 → 应成功且日志出现 `phone_session.reauthorized`，不再有 403 |
| RD-3 | 静默连接被判死的真机时序（问题 3） | 真机的 OS 省电策略会延迟 socket 上的读事件 | 手机连上后什么都不做，静默超过 `livenessTimeoutMs`（默认 90s）后 Worker 应报 `connected=false`、新请求快速 `phone_offline`，并主动关掉 socket |
| RD-4 | 真实 TCP 半开（手机关 Wi-Fi 但 socket 未关） | Node 替身没有内核 TCP 状态 | 连上后开飞行模式 30s，观察是否在阈值内转 false（阶段 0 的 R-8/D-2 同源） |
| RD-5 | 真机上收到 4002 后的界面与日志（问题 4） | JVM 里没有真实 Activity/通知栏 | 撤销后观察：状态卡是否显示"凭据已被撤销"、是否**不再**自动重连、日志是否给出更新设备令牌的指引、通知是否切到 `notif_revoked` |
| RD-6 | 真机拨动总开关（问题 5） | Compose UI 无自动化 | 全新安装（或清应用数据）后：默认应为**关**；调用 `battery_get_status` → `not_permitted`；在界面上打开后**不重连**再调用 → 成功；`phone_ping` 两次都应成功 |
| RD-7 | 真实 workerd 下 DO 驱逐与 alarm（问题 3 的真实语义） | 见 N-1 | 部署后发送请求同时强制驱逐 DO，观察在途请求结算码与 alarm 是否在驱逐后恢复 |

---

## 10. 兼容性、权限与环境变量

**协议兼容性**：本轮**没有新增、删除或改名任何线上帧字段**。
`X-Phone-Session-Credential` 是 Worker→DO 的**内部头**，公网不可达；
`credentialId` 只出现在内部撤销通知里（可选字段，不传=设备级）。
`phone_ping` 的请求/响应形状一字未改，Gate 1 的验收用例仍全绿。

**Android 权限**：无新增。`AndroidManifest.xml` 本轮未改动。

**Android 本地存储**：无新增键。`capabilities_enabled` 是既有键，本轮只改默认值
（键仍是 `HubPrefs.NON_CONFIG_KEYS` 的成员，不会混进 `HubConfig`）。

**Worker 环境变量**：无新增。本轮用到的仍是上一轮已存在的
`PHONE_LIVENESS_TIMEOUT_MS`（默认 90000）与 `PHONE_DISABLED_TOOLS`（默认空）。

**DO 存储**：新增键 `revocation`（审计记录）；
旧的 `revoked_device_ids` 只被读来给审计记录补 `at`，**不再是门禁**，
所以历史上被粘性布尔卡住的设备在本轮部署后**可以**通过补一条有效凭据重新授权 ——
这正是问题 2 的修复目标。无需数据迁移。

**向后兼容**：旧手机 App 不受影响（新的拒绝码只出现在新 App 才会处理的
`error` 帧与 close 码上；旧 App 对未知 close 码仍走它的重连逻辑，
也就是"看起来像普通断线"——这是旧 App 的既有行为，本轮未改协议去逼迫它）。
新 App + 旧 Worker：`4002`/`device_revoked` 在旧 Worker 上不会出现，
终态分支不会被触发，行为与今天一致。

---

## 11. 回滚

两个代码提交都在本地分支上，且都**未推送**（推送后也仍不合并 `gate0`）：

```bash
# 整体回滚到本轮之前（审计基线）
git reset --hard ac17a19b86020c4add4d75712e377b38027e90d7   # 本地分支，未推送

# 或保留历史、按逆序 revert
git revert --no-commit 543ea89 350dadb && git commit -m "revert(gate2): 回滚集中审计修复"
```

**回滚后需要人工确认的唯一一件事**：如果 Worker 已经部署过本轮的代码，
DO 里可能存在写入过的新键 `revocation` —— 回滚后的代码不再读它，
**无需清理**；旧的 `revoked_device_ids` 语义在回滚后恢复成粘性布尔，
因此回滚**会重新引入问题 2**（撤销后无法重新授权），这是选择回滚时要接受的代价。

**数据侧无迁移、无 Cloudflare 资源变更**：没有 D1/KV/R2/Queue，没有改
`wrangler.jsonc`，没有改 DO 的 `migrations`（本轮未新增 DO 类）。

---

## 12. 验收目标对照（任务书交付清单）

| 任务书要求 | 本报告位置 / 状态 |
| --- | --- |
| 分支名、起止 Commit SHA、改动文件 | §1（`feat/gate2-core`，起 `ac17a19`，Commit A `350dadb` / B `543ea89`，20 文件 +1383/−145） |
| 每个问题的根因、修复方式及对应测试 | §2（问题 1）、§3（问题 2）、§4（问题 3）、§5（问题 4）、§6（问题 5） |
| 完整测试命令与结果 | §7 |
| 明确列出未能执行的检查 | §8（N-1…N-4） |
| 仍需真实 Cloudflare / 真机验证的项目 | §9（RD-1…RD-7） |
| 推送到同名远程分支供独立复审，不合并、不部署 | 推送 `feat/gate2-core`（含本报告的提交）；**未**合并 `gate0`、**未**部署、**未**改生产 Secrets |
| 不以测试数量代替关键场景断言 | §2–§6 每条都列了具体用例名与它守的性质；关键性质（旧连接被终结、重新授权、静默判死、4002 终态、开关默认关）都有**正面对照 + 阴性对照** |
