# Gate 1.5 收尾 + Gate 2 核心交付（工作包 A–E）

> 本文件是**交付报告**，不含逐行审计。全部改动停留在本地分支，
> 未推送、未部署、未合并 `gate0`、未触碰任何生产环境。

---

## 1. 当前分支与 Commit

| 项 | 值 |
| --- | --- |
| 分支 | `feat/gate2-core`（从阶段 1A 的测试提交 `055095f` 拉出） |
| `gate0`（主分支） | `39ef38eec151b4bb21b4e6cac933ec5221a12302` —— **未被移动** |
| 阶段 1A 测试提交 | `055095fd4949c9566ecd10f55560710afcbfbdb0` |
| **Commit 1（服务端 A+B+C）** | `3051ae0` `fix(gate1.5)+feat(gate2): 凭据撤销、服务端存活判定、通用工具协议与 Worker Dispatcher` |
| **Commit 2（Android 分发层 D）** | `cfafa0e` `feat(android): 只读能力分发层（显式白名单，默认空表）` |
| **Commit 3（电池能力 E + 总开关）** | `804637d` `feat(gate2): battery_get_status 只读能力、本机能力总开关与非法格式请求的明确拒绝` |
| 未跟踪文件 | `docs/Gate1.5-Stage0-Test-Baseline.md`（阶段 0 报告，用户明确要求**不提交**，本轮未动） |

### 1.1 与任务书建议的偏差（必须记下来）

任务书建议 `Commit A` / `B` / `C` / `D` / `E` 五段。实际做成三段，理由：

- 工作包 **A（凭据撤销）、B（通用协议）、C（Worker Dispatcher）改的是同两个文件**
  ——`worker/src/phone/messages.ts` 与 `worker/src/phone/phone-session.ts`。
  按 A/B/C 拆只能得到中间态**编译不过**的提交，或者在 hunk 级别硬拆出一个
  "历史看起来分开了、其实没有任何一次能跑"的假边界。所以合并成 Commit 1。
- 工作包 **D** 与 **E** 是可分的（D 交付`capability/` 白名单机制与协议层，
  E 交付第一个真实能力），按建议各成一个提交。
- Commit 3 里额外包含了 **D 的两处收口**（运行期总开关、非法格式请求的明确拒绝），
  它们与 E 共享 `CapabilityDispatcher` 与 `PhoneLinkService` 的接线点，
  拆开同样会出现编译不过的中间态。

---

## 2. 变更文件清单

`git diff --stat 055095f..HEAD`：**33 文件，+5581 / −197**。

**Worker（服务端）**

- 修改：`worker/src/config.ts`、`worker/src/env.ts`、`worker/src/index.ts`、
  `worker/src/mcp/http.ts`、`worker/src/mcp/tools.ts`、
  `worker/src/phone/messages.ts`、`worker/src/phone/phone-session.ts`
- 新增测试：`worker/test/phone-lifecycle.test.ts`、`worker/test/phone-protocol.test.ts`、
  `worker/test/phone-tool-channel.test.ts`、`worker/test/tool-dispatch.test.ts`
- 修改测试：`worker/test/{mcp-http,phone-session,tools}.test.ts`、`worker/test/harness/env.ts`

**协议与 Mock**

- `docs/PROTOCOL.md`（203 → 约 340 行）
- `e2e/run-local-e2e.ts`、`tools/mock-phone/{client,main}.ts`

**Android**

- 新增：`app/src/main/java/com/androidmcphub/app/capability/CapabilityDispatcher.kt`、
  `.../capability/BatteryCapability.kt`
- 修改：`.../protocol/PhoneLinkProtocol.kt`、`.../protocol/PhoneLink.kt`、
  `.../net/PhoneLinkClient.kt`、`.../core/HubPrefs.kt`、
  `.../service/PhoneLinkService.kt`、`.../ui/{HubScreen,HubViewModel}.kt`
- 新增测试：`.../capability/CapabilityDispatcherTest.kt`、
  `.../capability/BatteryCapabilityTest.kt`、
  `.../protocol/ToolChannelProtocolTest.kt`
- 修改测试：`.../protocol/PhoneLinkTest.kt`

**文档**

- 修改 `docs/Gate1.5-Stage1A-Reliability-Tests.md`（追记：该报告自身引用了那串
  会触发安全守卫的 40 字符假令牌，已改成描述性文字）
- 本文件

---

## 3. 已实现功能

### 3.1 工作包 A：凭据撤销与在线存活判定（Commit 1）

- **撤销有了明确的传递路径，不依赖"DO 会感知 Secret 变化"这种假设。**
  DO 新增受保护的内部入口 `POST /internal/phone/revoke`（`ROUTE_REVOKE`），
  **没有任何公网路由映射到它**（`worker/test/phone-lifecycle.test.ts` 用源码断言守住）。
  Worker 在两个同步点尽力调用：设备升级被拒时、工具调用撞上已撤销设备时。
- **DO 侧的保证不依赖通知能否送达**：撤销集合落盘（`revoked_device_ids`），
  DO 重建后仍生效，`testing/reset` 也清不掉。
- 撤销的效果：在途请求**立即**结算为 `device_revoked`（不是等超时），
  所有 socket 以 close code **`4002`** 关闭，之后任何升级与任何工具请求都是 403。
- 撤销**只影响那一台设备**（按 deviceId 分桶，逐条断言）。
- **修掉了阶段 1A 的 D-1 根因**：`resolveDeviceId` 原先从不筛 `revokedAt`，
  于是"唯一一台设备被撤销后，不传 `device_id` 的请求还会被推断到它身上"。
  现在只看 `activeDeviceIds()`；显式指定已撤销设备回新的 `device_revoked` 码。
- **应用层存活判定**：DO 记录 `lastPhoneActivityAt`，超过
  `livenessTimeoutMs`（默认 **90s** = 3 个默认心跳周期）就判 stale ——
  `connected` 报 false、新请求快速回 `phone_offline`、并主动关掉那个 socket。
  半开连接（TCP 还在、应用层不回）因此**不再**被报成健康在线，
  也不会再拖满超时才回 `phone_timeout`。

### 3.2 工作包 B：Gate 2 通用协议（Commit 1）

- 新增两条线上帧：`tool_request`（下行，§3.5）与 `tool_result`（上行，§4.4）。
  字段结合现有协议设计，没有机械照搬任务书的建议结构：`request_id` 与
  `phone_ping` **共用同一个命名空间**，超时 / 并发上限 / 代次校验 / 重复 id 拒绝 /
  断线结算**全部共用同一套 `enqueueRequest()` 逻辑**。
- `tool_result` 的解析是**闭集校验**：缺 `request_id`/`ok`、`result` 不是对象、
  同时带 `result` 与 `error`、缺 `error.code` …… 各归到明确的 `invalid` 原因。
  `error.code` 认不出来时**不丢帧**，归一成 `tool_failed`（丢帧会让一个已经拿到结果的
  请求白等到超时）。
- 信封不可被结果污染：`result` 里的 `ok`/`request_id`/`device_id`/`tool_name`/
  `rtt_ms`/`server_timestamp`/`connection_generation`/`error_code`/`error_message`
  一律丢弃并记日志。
- 协议里**没有** `shell` / `intent` / `reflection` / `file_path` / `url` 这些字段，
  也不存在"按名字反射调用"的兜底分支。

### 3.3 工作包 C：Worker 通用 Dispatcher（Commit 1）

- `TOOL_REGISTRY` 显式注册，每个工具声明 `transport: "ping" | "tool"`（逐条断言，
  写错一个字符就会静默走错通道）。
- 调用链：`MCP → 注册表 → 严格 schema → 设备解析/授权 → 服务端禁用名单 →
  DO → tool_request → 手机`。
- 未注册工具与非法参数走**协议错误**（`-32602`），且**不碰转发层**；
  `PHONE_DISABLED_TOOLS` 的 `not_permitted` **排在设备解析之前**（否则报错差异会变成
  探测设备表的工具）；已撤销设备只**报告** `revokedDeviceId`，撤销动作在传输层做。
- `phone_ping` 保持原样，与 tool 通道并行共存。

### 3.4 工作包 D：Android 能力分发（Commit 2 + 3）

- `CapabilityDispatcher` 是手机侧**唯一**一条"外部请求 → 本地能力"的路径；
  注册表在**构造时**建立，没有运行期注册接口。
- 校验顺序：去重（同一个 `request_id` 只执行一次、只回一次；**被拒绝的 id 也占位**）
  → 工具名形状 → 注册表命中 → **运行期授权策略** → 多余参数 → 取值校验 → 执行。
- 失败一律用我们自己的文案：未知工具名不回显对端给的字符串（归一成 `<unknown>`），
  执行异常只带**异常类名**（绝不外泄异常原文 / 路径 / 令牌）。
- 结果超过 `maxResultBytes`（16 KiB）在**本机**失败，不发超大帧。
- **运行期总开关**（Commit 3）：`CapabilityPolicy` + `HubPrefs.capabilitiesEnabled()`
  + 高级设置里的开关。关掉之后能力**不执行**、回 `not_permitted`；这条检查排在参数校验
  之前（不允许执行的能力不该漏出"它接受哪些参数"）。策略每次调用都重读用户设置，
  所以拨开关**不需要重连**就生效。默认**开**。
- `tool_request` 的 `arguments` 存在但不是对象时，**不再静默丢弃整帧**，改为立刻回
  `invalid_arguments`：`request_id` 拿得到，让服务端等满超时会把"参数写错了"
  说成"手机可能死了"。缺 `request_id` 的帧仍然只能忽略。

### 3.5 工作包 E：`battery_get_status`（Commit 3）

- `AndroidBatterySource`：用 `ACTION_BATTERY_CHANGED` 这条**粘性广播**
  （`registerReceiver(null, filter)`）同步读 `level`/`scale`/`status`/`plugged`，
  以及 `PowerManager.isPowerSaveMode`。**零权限**、只读、幂等、不读用户文件、
  不上传设备标识符。
- `BatteryCapability`：输出 `battery_level_percent` / `charging` / `charging_source` /
  `power_save_mode` 四个字段，与 `worker/src/mcp/tools.ts` 的 `outputSchema` 一一对应。
  - 百分比用 `level` 与 `scale` 算（**不假定分母是 100**）并夹在 0–100；缺项 → `null`。
  - `charging`：`CHARGING`/`FULL` → true，`DISCHARGING`/`NOT_CHARGING` → false，
    **未知状态 → `null`**（绝不当成 false）。
  - `charging_source`：`ac`/`usb`/`wireless`/`dock`/`unknown`，没插电或读不到 → `unavailable`。
  - 取不到的字段是**显式 `null`**，不是缺键。
- 原始值经 `BatterySource` 抽象出来，解释逻辑因此可在 JVM 单测里完整覆盖，
  而不是只能上真机碰运气。

---

## 4. 关键设计决策

| 决策 | 理由 |
| --- | --- |
| 撤销走"受保护的内部入口 + 落盘状态"，不做任何公网撤销接口 | Secret 变更不会自动抵达 DO；而公网撤销入口本身就是一个新的攻击面 |
| 撤销状态落盘且 `testing/reset` 清不掉 | "重启一下就恢复"的撤销等于没有撤销 |
| 在途请求在撤销时结算为 `device_revoked` 而不是等超时 | 明确结果优于"不知道" |
| 存活判定用**应用层最近活动时间**，不看 `WebSocket.readyState` | 半开连接下 `readyState` 仍是 open，正是阶段 1A 的 D-2 |
| 阈值默认 90s（3 个心跳周期） | 容忍一个周期丢失/延迟，避免把"慢一拍"误判成离线 |
| 两条通道共用一份 `enqueueRequest()` | 先登记后发帧、先武装定时器再落盘这些细节只写对一次 |
| 未知 `tool_result.error.code` 归一成 `tool_failed` 而**不丢帧** | 丢帧 = 已经拿到结果的请求白等到超时 |
| 手机侧白名单是编译期的，**没有**运行期注册 | 能执行的东西在编译期就定下来，不留逃生口 |
| 白名单（存在哪些能力）与用户开关（此刻是否允许）分层 | 关掉开关时能力仍要"认得名字"，才能回明确的 `not_permitted` |
| 手机侧 `dispatch` 默认空注册表 | 忘记接线的后果是"功能不可用"，而不是"悄悄多出一个可被远程调用的能力" |
| `battery_get_status` 不接受任何能力参数 | 多一个键就多一条要审的路径 |
| `BATTERY_PLUGGED_DOCK` 落字面量 8 并用测试钉住 | API 33 才公开该常量，直接引用会在低 SDK 上编译失败 |

---

## 5. 测试执行结果

全部为本地运行，命令与退出码如下（Android 一律带 `--rerun`，
避免 Gradle 用 `FROM-CACHE` 复用上次结论 —— 这是阶段 0 的教训）。

| 检查 | 命令 | 数量 | 结果 | 退出码 | 耗时 |
| --- | --- | --- | --- | --- | --- |
| Worker 单测 | `npx vitest run --config worker/vitest.config.ts` | 314 | **314 / 314**（14 文件，0 失败，0 跳过） | 0 | 10.8s |
| TypeScript | `npx tsc --noEmit -p tsconfig.json` | — | PASS | 0 | ~4s |
| Android JVM 单测 | `cd android && ANDROID_HOME=/opt/android-sdk ./gradlew --no-daemon :app:testDebugUnitTest --rerun` | 243 | **243 / 243**（18 类，0 失败，0 跳过） | 0 | 1m19s |
| 本地 E2E | `npm run e2e` | 78 | **78 / 78** | 0 | ~17s |
| Android Lint | `cd android && ANDROID_HOME=/opt/android-sdk ./gradlew --no-daemon :app:lintDebug` | 29 warning | **0 errors / 29 warnings**（与阶段 0/1A 完全一致的告警集合） | 0 | 1m06s |

**测试总量对比**：本轮开始时 485（Worker 240 + Android 180 + E2E 65）
→ 本轮结束 **635**（314 + 243 + 78）。

Android 18 个测试类的分布：
`ManifestComponentTest 2`、`BatteryCapabilityTest 19`、`CapabilityDispatcherTest 21`、
`BackoffTest 9`、`DeviceTokenStoreTest 18`、`HubConfigTest 8`、`HubLogTest 4`、
`SocketFailureTest 9`、`WsUrlTest 8`、`PhoneLinkClientTest 7`、
`PhoneLinkReliabilityTest 7`、`LogRedactionTest 13`、`PhoneLinkProtocolTest 15`、
`PhoneLinkTest 45`、`PingResponderTest 9`、`ToolChannelProtocolTest 15`、
`ConnectGateTest 21`、`LinkBusTest 13`。

### 5.1 任务书 §九 十二项重点的验证位置

| # | 验证点 | 覆盖位置 |
| --- | --- | --- |
| 1 | 原有 `phone_ping` 完全正常 | `worker/test/phone-session.test.ts`、E2E ①–⑧ 段 |
| 2 | 原有 `phone_pong` 完全正常 | 同上 + `PhoneLinkTest`（Kotlin 侧编解码） |
| 3 | 新旧协议共存 | E2E ⑩ 段最后一条：同一台手机上 legacy ping 与新通道都成功 |
| 4 | 未授权设备无法调用工具 | `worker/test/tool-dispatch.test.ts`（`unknown_device` / `device_revoked`）、E2E ⑩ |
| 5 | 未知工具被拒绝 | `tool-dispatch`（`-32602` + `known_tools`，且不碰转发层）、`CapabilityDispatcherTest`（`unsupported_tool`） |
| 6 | 错误参数被拒绝 | `tool-dispatch`（多余键、`timeout_ms` 越界）、`CapabilityDispatcherTest`、`ToolChannelProtocolTest` |
| 7 | 重复响应不会重复结算 | `worker/test/phone-tool-channel.test.ts`、`PhoneLinkTest`（重复 `tool_request` 只回一次） |
| 8 | 超时请求不会自动重放 | `phone-tool-channel.test.ts`（`no-response` → `phone_timeout`，且连接不被误关） |
| 9 | 断线请求正确失败 | `phone-tool-channel.test.ts`（`phone_disconnected`，`pending`/镜像归零） |
| 10 | 旧代次响应不能匹配新请求 | `phone-session.test.ts`、`phone-tool-channel.test.ts`、`phone-lifecycle.test.ts` |
| 11 | `battery_get_status` 返回正确结构 | `BatteryCapabilityTest` 19 条 + `tool-dispatch.test.ts` 3 条 + E2E ⑩ 段全链路 |
| 12 | 其他设备不受影响 | E2E ⑨/⑩ 段的对照组；`phone-lifecycle.test.ts` 的按设备分桶断言 |

**未删除、未弱化任何原有断言。** 唯一被改写的既有断言是阶段 1A 遗留的
DTO 驱逐用例（把 Node 替身特有的 `phone_timeout` 放宽成
`∈ {phone_timeout, phone_disconnected, phone_replaced}`），并**同时加上了**
镜像与 `reconstructedPendingFailed` 的强断言 —— 见 §6 第 3 条。

**NOT RUN**：无。本轮所有计划的检查都实际执行了。

---

## 6. 已知问题（不隐瞒）

1. **D-3（P3，未修）同一工具两套错误编码。** 工具层"设备解析失败"用扁平
   `structuredContent.error_code` / `error_message`，而 DO 侧失败用嵌套
   `structuredContent.error.{code,message}`。本轮**刻意不改**（改它会影响 Gate 1
   已验收的返回形状），但在 `tool-dispatch.test.ts` 里两条形状都做了断言，
   让这份不一致始终可见。
2. **撤销状态在 DO 上是永久性的。** 撤销过的 deviceId 在当前 DO 上无法恢复
   （`testing/reset` 清不掉它，这是刻意的安全语义）。生产语义上正确，
   但排查时要知道"重新加回凭据不会让旧 DO 立刻接受它"。
3. **R-3（真实 workerd 驱逐语义）仍未验证。** 本容器里 workerd 起不来
   （`MmapAligned() failed` / TCMalloc 48-bit 地址空间，退出码 134），
   所有 Worker 结论都建立在 Node 替身上。被驱逐实例的本地定时器在替身里仍会结算，
   真实 workerd 会带走它 —— 这正是驱逐用例写成"允许三种结局"的原因。
4. **`BATTERY_PLUGGED_DOCK` 是手写的字面量 8**（API 33 才公开该常量）。
   已用单测钉住它的值；低 SDK 上"底座"来源不会被误判成别的来源。
5. **用户开关的默认值是两处一致的问题。** `HubPrefs.capabilitiesEnabled()` 默认 true，
   ViewModel 从 prefs 读初值（不写第二份字面量）；将来若要改默认值，
   只需要改 prefs 那一处。
6. **`phone_ping` 与 `tool_request` 共用 `request_id` 空间**：跨通道撞 id 会被
   `409 duplicate_request_id` 拒绝（已有测试）。这是刻意的（一套去重、一套并发上限），
   但服务端调用方要知道两条通道的 id 不能重名。

---

## 7. 尚未验证的真机场景（REAL_DEVICE_REQUIRED）

本轮**没有**在真机上执行任何一条；下列场景只有单测 / E2E 替身级别的证据。

| 编号 | 场景 | 为什么单测不够 | 验证步骤 |
| --- | --- | --- | --- |
| RD-1 | 真实 TCP 半开（手机关 Wi-Fi 但 socket 未关） | Node 替身没有内核 TCP 状态 | 手机连上后开飞行模式 30s，观察 `connected` 是否在 `livenessTimeoutMs` 内转 false、ping 是否快速回 `phone_offline` |
| RD-2 | 真实网络切换（Wi-Fi ↔ 蜂窝） | 替身只能模拟回调 | 连接中来回切网，观察代次替换与重连退避是否如预期 |
| RD-3 | 真实 workerd 驱逐下的在途请求结局 | 本容器跑不了 workerd | 部署到 Cloudflare 后，发送工具请求同时强制驱逐 DO，观察结算码 |
| RD-4 | 真机 `battery_get_status` 的实际返回值 | JVM 上 `registerReceiver` 是 stub | 装上 APK、连上部署好的 Worker，调用工具核对四个字段与系统"电池"页一致 |
| RD-5 | 真机拨动"允许服务端调用本机只读能力"开关 | Compose UI 没有自动化测试 | 开着开关调用一次 → 成功；关掉开关再调用一次 → `not_permitted`；**不重连** |
| RD-6 | 真机上撤销凭据后的观感 | 替身没有真实浏览器/升级路径 | 撤销后观察日志是否出现 `device_revoked`、socket 是否以 4002 关闭、界面是否切到失败态 |
| RD-7 | 低电量 / 充电中的百分比与来源 | 同上 | 插 USB、插无线充、拔掉，各调用一次核对 `charging` / `charging_source` |

---

## 8. 是否涉及新权限或配置

**Android 权限：无新增。** `AndroidManifest.xml` 本轮未改动（`AndroidBatterySource`
用的是公开的粘性广播，连 `BATTERY_STATS` 都不需要）。

**Android 本地存储：新增一个布尔键** `capabilities_enabled`（`HubPrefs`，
非加密、非凭据）；同时把它加进了 `HubPrefs.load()` 的跳过集合，
避免它混进 `HubConfig` 的 map。

**Worker 环境变量：新增两个，都是可选的、有默认值。**

| 名称 | 默认 | 作用 |
| --- | --- | --- |
| `PHONE_LIVENESS_TIMEOUT_MS` | `90000` | 应用层存活阈值（夹紧区间 1s–900s） |
| `PHONE_DISABLED_TOOLS` | 空 | 服务端禁用名单（逗号分隔），命中的工具回 `not_permitted` |

> ⚠ 两者都已加进 `worker/src/config.ts` 的 `cacheKey` 数组 —— 新增 env 字段若不加进去，
> **改了配置也不会生效**（配置缓存按该数组做指纹）。

**Cloudflare Secrets / 生产数据库 / OAuth 机制：未触碰。** 未部署、未变更任何线上配置。

---

## 9. 向后兼容性结论

| 面向 | 结论 |
| --- | --- |
| 旧手机 App（Gate 1 的 0.1.4-gate1） | **兼容。** 服务端所有新增字段都是可选的，未知帧类型仍走原来的 `unknown` 分支；旧 App 不认 `tool_request` 只会忽略，工具调用如实超时 |
| 新手机 App + 旧 Worker | 兼容（新帧发不出去而已）；新 App 对未知 type 前向兼容 |
| 老 MCP 客户端 | **`phone_ping` 的请求/响应形状一字未改**，Gate 1 的验收用例仍全绿 |
| `tools/list` 的可见变化 | 从 1 个工具变成 2 个（`phone_ping` + `battery_get_status`）。这是新增能力，不是破坏；E2E 里对应的两处整表断言已同步更新 |
| DO 存储 | 只新增 `revoked_device_ids` 键；不读不写旧数据，不需要迁移；旧 DO 第一次被调用时会自然获得这个键 |
| 云端配置 | 两个新 env 都是可选的且带默认值 —— **不配置也能正常跑**（存活阈值 90s、没有禁用工具） |

---

## 10. 回滚方式

三个提交都是本地提交，**没有推送**，因此回滚不影响任何人：

```bash
# 方式一：整体丢弃（回到 Gate 1.5 阶段 1A 的测试提交）
git checkout gate0            # 主分支从未被移动，可直接切回
git branch -D feat/gate2-core # 丢弃整个分支（本地）

# 方式二：保留历史、按逆序回滚（在 feat/gate2-core 上）
git revert --no-commit 804637d cfafa0e 3051ae0
git commit -m "revert(gate2): 回滚 Gate 2 核心交付"

# 方式三：只回滚某一个工作包
git revert 804637d   # 只去掉 battery_get_status 与总开关，保留协议与分发层
```

**回滚后需要人工确认的只有一件事**：如果已经部署过带新 env 的 Worker，
那三个 var 可以留着（有默认值）也可以删掉（同样有默认值）——
代码侧不依赖它们存在。

**数据侧无回滚动作**：没有数据库迁移、没有 R2/D1/KV/QUEUE 改动；
DO 里多出来的 `revoked_device_ids` 键在回滚后不再被读取。

---

## 附：本轮完成的验收目标对照（任务书 §十二）

| # | 验收目标 | 状态 |
| --- | --- | --- |
| 1 | Gate 1 Ping 兼容 | ✅ `phone_ping` 未改，全量回归含其全部用例 |
| 2 | Gate 1.5 可靠性边界改善 | ✅ 撤销传播 + 落盘、应用层存活判定（D-1/D-2 的根因都动到了） |
| 3 | Gate 2 通用协议可用 | ✅ `tool_request`/`tool_result` + 闭集校验 + 协议测试 15 条 |
| 4 | Worker Dispatcher 可用 | ✅ 显式注册 + 严格 schema + 授权前置 + 协议错误 |
| 5 | Android Dispatcher 可用 | ✅ 编译期白名单 + 运行期总开关 + 21 条单测 |
| 6 | `battery_get_status` 本地端到端通过 | ✅ JVM 19 条 + Worker 3 条 + E2E 全链路 |
| 7 | 全量回归通过 | ✅ 314 / 243 / 78 / 0 errors |
| 8 | 所有改动停留在本地开发分支 | ✅ `feat/gate2-core`，未推送 |
| 9 | 未触碰生产环境 | ✅ 未部署、未改 Secrets、未改数据库、未加权限 |

**完成后即停止**，等待用户与开发协调员审计后再决定是否部署。
