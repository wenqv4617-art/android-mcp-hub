# Gate 3 交付报告：Android 作为第三方 MCP 客户端 + 工具聚合转发

分支：`feat/gate3-mcp-client`（从 `feat/gate2-5-native-tools` 的 `ba53c599` 新建，**未合并、未部署生产**）
本轮**没有**部署生产 Worker，**没有**动 OAuth、生产 Secrets 或任何真实用户数据（任务书 §十 的禁止项）。

> 本报告按"事实 / 判断 / 建议"分别标注。
> **事实**＝能在仓库里用文件与命令复现的；**判断**＝我的解释；**建议**＝还没做的取舍。
> 凡是"需要真机或需要 ChatGPT 真实调用才能确认"的，一律写在 §7，不计入通过项。

---

## 1. 一句话结论（事实）

Android 现在真的能当第三方 MCP 客户端：连真实的 MCP Streamable HTTP 服务器、按
`nextCursor` 翻页列出工具、把工具定义算成指纹、等用户逐个授权，再由 ChatGPT 经
Worker 上的聚合入口 `hub_list_tools` / `hub_call_tool` 调用，命令下到手机、手机转发到
第三方服务器、第三方结果原样回到模型。整条链路在开发环境里用**真实**的 MCP 测试服务器
跑通了（§4 与 §6）。

---

## 2. 这条链路长什么样（事实）

```
ChatGPT ──HTTPS/MCP──► Worker（Cloudflare）
                          │  ① tools/list 暴露 6 个工具（4 个本机 + 2 个聚合入口）
                          │  ② hub_call_tool → 复用既有 tool_request 帧，命令名是保留字
                          ▼
                    Durable Object（唯一 pending 表负责结算）
                          │  WebSocket（设备令牌鉴权、代次校验、去重）
                          ▼
                    Android App（前台服务）
                          │  ③ HubCommandRouter 先于本机能力白名单接走保留命令
                          │  ④ 查本机配置 → 查授权指纹 → 校验参数 schema → 走真实 MCP 客户端
                          ▼
                    第三方 MCP 服务器（真实 HTTP：initialize / tools/list / tools/call）
```

**关键设计决定（判断，附理由）**

| 决定 | 理由 |
| --- | --- |
| **不新增任何帧类型**，聚合命令复用 `tool_request` / `tool_result` | 这条通道已经把"要手机做一件事、拿回结果"走通了：request_id 全链透传、Worker 唯一 pending 表结算、代次校验与去重都在老路上。为聚合再发明一对帧只会把攻击面与回归面各扩大一倍 |
| 两个**保留命令名**在手机侧**先于**本机能力白名单被接走 | 第三方工具永远进不了 `BuiltInCapabilities`；本机能力名也永远不能被当成第三方工具名路由过去 —— 两条路互不借道 |
| `tool_id`（`mcp:<server>:<tool>`）由 **Worker 派生**，手机不回传 | 身份只有一个来源，少一处可以不一致的地方 |
| 指纹包含**标题与描述**，不只是 schema | 描述是模型看到的指令文本。只对 schema 取哈希，"把描述换成别有用心的说明"就不会让授权失效 |
| 指纹对 schema 做**规范化**（键排序）再哈希 | 同一份 JSON 在不同解析器下键序不同（实测：单测 JVM 的 `org.json` 会按字典序重排键，Android 运行时保插入序）。直接对重排后的字符串取哈希，会让同一个工具算出两个指纹，用户被迫反复重新授权 |
| 未授权 / 离线 / 超限的工具**不进目录数组**，只让 `other_tools_count` 变大 | 模型看不到不在它职权范围内的东西；但"被藏起来的数量"如实报出来，不是偷偷摸摸 |
| 明文 `http` 只允许回环（`127.0.0.1` / `localhost` / `::1`） | 本机 MCP 服务器只能走 http；流量不出网卡。协议层放宽与地址准入是**两道独立的门**，各自有测试 |

---

## 3. 《Gate 3 传输兼容与工具聚合矩阵》

状态用四档，**不混用**：`已实现`（代码在）、`已测试`（有自动化证据）、`仅预留`（接口在、实现明确抛错）、`未支持`（没做）。

### 3.1 MCP 客户端连接能力

| 项 | 实现状态 | 测试结果 | 限制 |
| --- | --- | --- | --- |
| Streamable HTTP（POST + `application/json`） | 已实现 | 已测试：`StreamableHttpTransportTest`（38 例）+ `StreamableHttpInteropTest`（6 例，对**真实** Node MCP 服务器） | 未在真机跑过；TLS 用的是系统信任锚 |
| Streamable HTTP（POST + `text/event-stream`） | 已实现 | 已测试：`SSE 响应只取本次请求的那一条事件`、`SSE 里没有本次请求的响应时报 PROTOCOL_ERROR`；互操作测试也走过 SSE 模式 | 不支持服务器主动推送的长连接事件流 |
| 协议版本协商 | 已实现 | 已测试：协商结果必须落在 `2025-06-18 / 2025-03-26 / 2024-11-05`，真实服务器实测拿到协商值 | 只声明这三个版本 |
| `Mcp-Session-Id`（回传 + `DELETE` 关闭） | 已实现 | 已测试：`服务器给了 session id 之后每次请求都带上并在 close 时 DELETE`；互操作测试断言真实会话 id 非空 | 关闭失败静默（尽力而为），不影响本地状态 |
| 自定义请求头（多组、增删改、敏感值加密落盘） | 已实现 | 已测试：`connect 发出 initialize 请求并带上协议头与自定义头` + **互操作** `自定义请求头真的上了线：没有它服务器就拒绝`（服务器 `--require-header` 缺了直接 401） | 值的长度上限 2048；受控头（`Host`/`Content-Length`/`Transfer-Encoding`/`Mcp-Session-Id` 等）一律拒绝 |
| 请求头注入拦截（CR/LF/空字符、非 token 名） | 已实现 | 已测试：`McpHeaderPolicyTest`（12 例）+ `请求头的值带换行时在构造就被拦住` | —— |
| 敏感头不外泄（日志 / `toString` / 错误文案） | 已实现 | 已测试：`data class 的 toString 也不会泄漏 secret 值`、`401 报 NOT_AUTHORIZED 且消息里不出现请求头的值`、仓库级静态扫描禁止把异常 `message` 拼进字符串 | 值以密文落盘（`AndroidSecretCipher`，alias `android-mcp-hub.mcp-header.v1`） |
| 重定向 | 已实现（**不跟随**） | 已测试：`3xx 报 SERVER_REJECTED 且绝不跟随重定向`、`默认客户端不跟随重定向且超时可控` | 不做跨主机凭据转发 —— 干脆不跳 |
| 超时 / 取消 / 响应大小上限 | 已实现 | 已测试：`读超时报 TIMEOUT`、`callTool 的结果超过大小上限时报 RESULT_TOO_LARGE`、`连不上服务器时报 SERVER_UNAVAILABLE 且不泄漏地址` | 默认 connect 15s / read 30s / 512KB |
| **旧版 HTTP+SSE（GET 长连接）** | **仅预留** | 已测"明确失败"：`McpTransportFactory` 对 `LegacyHttpSse` **抛**"旧版 HTTP+SSE 传输还没实现"，界面同样标注 | 本轮不做（P1） |
| **stdio / 服务器主动通知流 / MCP 侧 OAuth** | **未支持** | 无 | 见 §8 建议 |

### 3.2 MCP 工具发现能力

| 项 | 实现状态 | 测试结果 | 限制 |
| --- | --- | --- | --- |
| `initialize` → `notifications/initialized` → `tools/list` | 已实现 | 已测试（单测 + 互操作） | —— |
| `nextCursor` 分页取全 | 已实现 | 已测试：`listTools 跟着 nextCursor 翻页`；互操作用 `--page-size 3` 对 8 个工具**必须翻页** | 页数上限 20、工具数上限 200，超限即报错而不是截断后假装成功 |
| 工具字段逐字保留（title/description/inputSchema/annotations/原始 JSON） | 已实现 | 已测试：互操作逐字比对（含 `no_annotations` 为 `null`、`destructive_write.destructiveHint == true`） | 描述截断到 400 字符；schema 超 4000 字符时**展示用**字段置空，指纹仍按服务器原始 schema 算 |
| 非法工具名跳过并计数 / 重复游标 / 缺 `tools` 数组 | 已实现 | 已测试：`名字不合规的工具被跳过并计数`、`listTools 遇到重复游标时报 PROTOCOL_ERROR`、`tools 数组缺失时报 PROTOCOL_ERROR` | —— |
| 发现失败如实说明原因，不伪造空目录 | 已实现 | 已测试：`McpServersStateTest`（失败原因只出错误类别 + HTTP 码，且**不含服务器原文**） | —— |

### 3.3 Android 工具聚合能力

| 项 | 实现状态 | 测试结果 | 限制 |
| --- | --- | --- | --- |
| 多服务器配置（增删改启停、排序、地址变更即失效） | 已实现 | 已测试：`McpServersStateTest`（21 例）+ Store/UI 测试 | 服务器数上限 8（聚合目录侧） |
| 统一工具身份（`server_id` + `tool_name` + 指纹） | 已实现 | 已测试：`McpHubRouterTest`（**32 例**）+ `McpToolFingerprintTest`（12 例，含键序/空白不敏感、标题或描述变化必变） | 指纹算法是本项目自定（`v1`），换算法即全体重新授权 |
| 用户逐工具授权（默认拒绝、按定义冻结、删除即清） | 已实现 | 已测试：未授权不连服务器、定义变化拒绝、撤销后立即拒绝、删除服务器后无法再调用、改地址清空授权 | 授权只存在本机，不同步、不备份 |
| 参数 schema 校验（含深度/节点/长度/数组上限、拒 `__proto__` 等） | 已实现 | 已测试：`参数不符合 schema 时拒绝，且不碰服务器`、`危险参数键被拒绝`、`目录参数里的多余字段一律拒绝` | schema 子集（`JsonSchemaValidator` 支持 type/properties/required/additionalProperties/enum/min-max/长度/items，深度 ≤4） |
| 结果回传（保留字段不可覆盖、非文本只报类型、超长如实标记） | 已实现 | 已测试：`第三方结果盖不掉身份字段`、`非文本内容只报类型，正文不外泄`、`超长文本被裁剪并如实标记`、`结构化结果超限时如实说明被省掉` | 文本上限 8192/项、结构化 8192 字符 |
| **第三方工具调用本机能力** | **结构上不可能** | 已测试：`第三方工具即使和本机能力同名，也只是第三方工具`（服务器故意暴露 `battery_get_status`，拿到的是第三方文本）；Worker 侧也断言 `battery_get_status` 不会落进本机能力路径 | —— |
| 本机四个工具（`phone_ping` / `battery_get_status` / `device_get_network` / `device_get_storage`）回归 | 未改动 | 已测试：Gate 2.5 的 347 例全在，本轮全量 **608 例 0 失败** | —— |

### 3.4 Worker 工具转发能力

| 项 | 实现状态 | 测试结果 | 限制 |
| --- | --- | --- | --- |
| `hub_list_tools` / `hub_call_tool` 注册与 Schema | 已实现 | 已测试：`hub-tools.test.ts`（**23 例**）+ 注册表整表断言 | 聚合入口只允许 `server_id`/`tool_name`/`arguments`（+ 公共 `device_id`/`timeout_ms`）；Schema 里不出现 url/headers/token |
| 参数校验与身份来源 | 已实现 | 已测试：`arguments` 被**重建**、`__proto__` 拒绝、越界数值拒绝 | 参数上限：4096 字符 / 深度 4 / 64 节点 / 键长 64 / 数组 32 / 字符串 1024 |
| 目录结果映射（含"手机自报的被隐藏数量"） | 已实现 | 已测试：自报 2 → 2；自报 4 + 服务端判掉 1 = 5；`["3",-1,2.5,NaN]` → 0；自报 10,000,000 → 封顶 `maxReportedOtherTools = 256` | 自报数字来自手机（先滤过），服务端封顶并叠加自己判掉的条目 |
| 失败结果**满足自己声明的 outputSchema** | 已实现 | 已测试：直接用 `HUB_CALL_TOOL_TOOL.outputSchema.required` 逐键断言（自锁）；全链路测试断言手机离线时 `server_id`/`tool_id` 仍在 | 这条是 e2e 真踩出来的缺陷（§5） |
| 旧版 App（不认识保留命令） | 已实现 | 已测试：`toolBehavior:"unsupported"` → `unsupported_tool`，且**没有** `tools` 字段（不伪造空目录） | —— |
| Worker 侧持久化 | **刻意没有** | 已测试：`security.test.ts` 钉死只有三个 DO 命名空间、全仓不得出现 D1/KV/R2/Queue/Hyperdrive | 工具目录只存在手机上 |

### 3.5 ChatGPT 真实调用验证

| 项 | 状态 | 说明 |
| --- | --- | --- |
| ChatGPT 官方客户端真实调用本机四工具 | **历史已验证**（Gate 0/1 阶段用户实操过），本轮**未重测** | 本轮没有部署生产 Worker，无法在真机上重跑 |
| ChatGPT 官方客户端真实调用 `hub_list_tools` / `hub_call_tool` | **未验证** | 需要：部署（本轮禁止）+ 真机安装新 APK + 在 ChatGPT 里重新授权 connector。**不得声称通过** |
| 开发环境等效链路（Worker + 真实 DO + 真实 WebSocket + 真实 MCP 服务器） | 已测试 | `npm run e2e`：**102 通过 / 0 失败**，其中 §⑫ 的 10 项就是「手机 → 真实第三方 MCP 服务器」的真实协议往返 |

---

## 4. §九 13 项专项测试逐条对照（事实）

| # | 要求 | 落在哪 |
| --- | --- | --- |
| 1 | 自定义 Header 正确发送 | `StreamableHttpTransportTest.connect 发出 initialize 请求并带上协议头与自定义头`、`StreamableHttpInteropTest.自定义请求头真的上了线：没有它服务器就拒绝` |
| 2 | Header 注入拦截 | `McpHeaderPolicyTest`（12 例）、`请求头的值带换行时在构造就被拦住` |
| 3 | 敏感 Header 日志脱敏 | `data class 的 toString 也不会泄漏 secret 值`、`401 报 NOT_AUTHORIZED 且消息里不出现请求头的值`、`LogRedactionTest` 仓库级静态扫描 |
| 4 | 跨域重定向凭证保护 | `3xx 报 SERVER_REJECTED 且绝不跟随重定向`、`默认客户端不跟随重定向且超时可控` |
| 5 | 多服务器同名工具隔离 | `两台服务器上的同名工具互不串台，授权也不通用` |
| 6 | 已删除服务器无法继续调用 | `已删除的服务器无法继续调用，授权也一并失效`、`McpServersStateTest` 删除用例 |
| 7 | 已撤销授权立即拒绝调用 | `撤销授权之后立刻拒绝调用，不需要重新发现` |
| 8 | 工具 Schema 变化后的授权处理 | `授权之后工具定义变了就拒绝执行`、`McpToolFingerprintTest`、`McpServersStateTest` 的 Stale/重新授权用例 |
| 9 | 未授权第三方工具无法调用本机能力 | `第三方工具即使和本机能力同名，也只是第三方工具`、`worker/test/hub-tools.test.ts` 的 `battery_get_status` 用例 |
| 10 | 第三方结果不能覆盖协议保留字段 | `第三方结果盖不掉身份字段`、`失败结果也满足自己声明的 outputSchema` |
| 11 | 大体积响应被安全拒绝 | `callTool 的结果超过大小上限时报 RESULT_TOO_LARGE`、`超长文本被裁剪并如实标记`、Worker 侧截断用例 |
| 12 | 工具执行失败不被误报为成功 | `第三方失败如实上报 is_error`、`第三方工具报的 isError 不会被当成传输失败吞掉`、Worker `error → tool_failed` |
| 13 | 现有四个本机工具回归测试 | Android 全量 41 类 / 608 例 0 失败；Worker 17 文件 / 379 例 0 失败；e2e §①–⑨ |

**新增的跨语言互操作测试（本轮多写的一条，非任务书要求）**：
`android/app/src/test/java/com/androidmcphub/app/mcp/transport/StreamableHttpInteropTest.kt`
起一台**真实**的 Node MCP 服务器（`tools/mcp-test-server/server.mjs`，零依赖、不是替身），
让 Kotlin 客户端去连它，断言协商版本、会话 id、**翻页取全 8 个工具**、字段逐字一致、
`echo`/`sum`/`boom`/未知工具的行为。意义：此前 Android 传输层只被 `MockWebServer` 回放的
**自写响应**测过 —— 那只能证明"代码按我理解的协议走"，证明不了"我理解的协议就是别人实现的协议"。

---

## 5. 本轮修掉的真实缺陷（事实 + 判断）

1. **`hub_call_tool` 的失败结果不满足自己声明的 outputSchema**（e2e 真踩出来的）
   `HUB_CALL_TOOL_TOOL.outputSchema.required` 含 `server_id` / `tool_id`，而失败分支只回
   `{ok,request_id,device_id,tool_name,error_code,error_message}` → 官方 MCP SDK 做
   outputSchema 校验失败，把**每一次失败**变成 JSON-RPC `-32602`
   （`data must have required property 'server_id'`），"手机离线 / 没授权 / 超时"
   这些原因模型一个字都看不到。修法：失败时补 `server_id` 与派生的 `tool_id`，
   并加"用自己声明的 required 逐键断言"的回归测试（防止再漂）。
   **判断**：这类缺陷单测很难自己长出来 —— 必须让**官方 SDK 客户端**参与链路才暴露。
2. **跨组件约定错位：`other_tools_count` 恒为 0**
   Android 侧约定"未授权/离线/超限的工具不进数组、只让 `other_tools_count` 变大"，
   而 Worker 侧此前只数数组里被自己判掉的条目 → 真实手机上服务端永远数出 0，
   "没有偷偷藏东西"失去可验证形式。修法：认手机自报的数字并封顶 256，再叠加服务端自己的判断。
3. **测试服务器 `--port 0` 时打印端口 0**，调用方解析不出地址 → 改回读内核分配的真实端口。
   **判断**：这不是产品 bug，但它是"e2e 能跑起来"的前置条件，值得记一笔。

---

## 6. 测试与构建数字（事实）

| 项 | 命令 | 结果 |
| --- | --- | --- |
| Android 单元测试（全量，含 UI / 互操作） | `cd android && ./gradlew --no-daemon -Djava.net.preferIPv4Stack=true :app:testDebugUnitTest --rerun` | **41 类 / 608 用例 / 0 失败 / 0 错误 / 0 跳过**（Gate 2.5 基线 28 类 / 347 用例） |
| Android lint | `./gradlew :app:lintDebug` | **0 error / 40 warning**（均为既有类别：依赖可升级 27、`ApplySharedPref` 5、AGP 版本 3、`ObsoleteSdkInt` 2、`InlinedApi` 1、`CustomSplashScreen` 1、`DataExtractionRules` 1）。新增的 2 条 `ApplySharedPref` 落在 `mcp/store/KeyValueStore.kt:36,40`，是**故意**用 `commit()`：授权记录必须同步落盘，`apply()` 异步写出期间进程被杀就等于"授权看起来成功、实际没存" |
| APK | `./gradlew :app:assembleDebug` | `android/app/build/outputs/apk/debug/app-debug.apk`，**13,778,949 字节**（Gate 2.5：13,603,134） |
| Worker 单元测试 | `npm test` | **17 文件 / 379 用例全过**（Gate 3 之前 15 文件 / 348） |
| Worker 类型检查 | `npx tsc --noEmit -p tsconfig.json` | 无输出（通过） |
| 端到端 | `npm run e2e` | **102 通过 / 0 失败**（Gate 3 之前 83；§⑪ 聚合入口 10 项、§⑫ 真实协议 10 项） |
| 跨语言互操作 | `./gradlew :app:testDebugUnitTest --tests "com.androidmcphub.app.mcp.transport.StreamableHttpInteropTest"` | **6 用例 / 0 跳过 / 全过**（真实 Node MCP 服务器 + 真实 HTTP） |

**测试环境的老实话**：本环境**没有 Android 模拟器、也没有真机**。所以
Compose 测试走 Robolectric（`sdk=[34]`, `w411dp-h891dp-xhdpi`, `ConscryptMode.OFF`），
"装在真机上跑起来"这件事仍是**未验证**；见 §7。

---

## 7. 需要真机 / 人工验证的清单（**尚未验证，不计入通过**）

1. **在真机上安装并跑通**：`app-debug.apk` 装到手机、添加一台真实 MCP 服务器、
   发现工具、逐个授权、在 ChatGPT 里真实调用 —— 全部**未做**（本轮禁止部署生产 Worker，
   而 ChatGPT connector 指向的是生产地址）。
2. **Android 运行时 `org.json` 与 JVM 的键序差异**：指纹规范化正是为了抹平它
   （单测 JVM 按字典序重排键，Android 保插入序）。这在真机上**未实测**，只做了规范化与单测。
3. **系统 Keystore 加密落盘**：Robolectric 下用的是假实现；真机上的
   `AndroidSecretCipher` / `PrefsSecretStorage` 未验证。
4. **前台服务 + WebSocket 长连接的手机行为**（省电策略、断网重连、Doze）：未验证。
5. **TLS 对真实公网 MCP 服务器**（证书链、SNI、HTTP/2）：互操作测试跑的是本机明文回环，
   真实 HTTPS 主机未验证。
6. **通知权限 / 剪贴板 / 传感器**等其它能力：与本轮无关，状态同 Gate 2.5。

---

## 8. 判断与建议

**判断**

* 本轮最大的价值不是"多了两个工具"，而是把**授权边界**放到了手机上：工具定义、授权记录、
  请求头密钥全在本机，Worker 不存目录、不存授权、也不知道第三方服务器的地址与凭据。
  这符合 Gate 3 的原始意图（"Android 作为第三方 MCP 客户端"），也让 Worker 保持无状态可替换。
* 指纹 + 规范化这条链是"授权的是定义，不是名字"的实现核心。任何对
  `McpToolFingerprint` 的改动都等价于**让所有用户的授权失效**，属于安全红线级别，不建议随手改。
* 子 agent 在本环境**不可靠**（本轮先后 3 个委派任务在没有任何产出的情况下失败），
  剩余工作由主线完成；这是一条工程现实，不影响交付内容。

**建议（下一阶段，按价值排序）**

1. **先在真机上把 §7 的第 1 项做完**（这是唯一还没被证明的环节），再考虑合并分支。
2. 第三方 MCP 服务器的 **OAuth / 动态注册**：目前只支持手配请求头，需要凭据服务器多了以后
   手工维护会失控（P1）。
3. 旧版 **HTTP+SSE** 传输（现在明确抛错，界面标注"未支持"）；如果用户手上真有只支持它的
   客户端，再做（P1）。
4. 授权**导出/导入**或跨设备同步：现在换手机等于全部重新授权（P2，注意别把密钥带出去）。
5. 工具目录的**缓存与离线展示**：现在每次进页面都要联网发现一次（P2）。

---

## 9. 交付物（事实）

| 交付物 | 位置 |
| --- | --- |
| 分支 | `feat/gate3-mcp-client`（本轮提交见 `git log --oneline`，未合并、未部署） |
| APK | `android/app/build/outputs/apk/debug/app-debug.apk`（13,778,949 字节） |
| 本报告 | `docs/Gate3-McpClient-Report.md` |
| 协议文档（新增 §11 Gate 3 聚合） | `docs/PROTOCOL.md` |
| 本轮新增的测试服务器 / 参考客户端 / 手机桥接 | `tools/mcp-test-server/server.mjs`、`tools/mcp-streamable-http-client.mjs`(+`.d.mts`)、`tools/mock-phone/mcp-bridge.ts` |
