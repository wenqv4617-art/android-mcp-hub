# Android MCP Hub

让 **ChatGPT 官方客户端**通过 MCP 直接指挥一台 **Android 手机**。

> **状态：Gate 1（OAuth 接入已实现；真实 Cloudflare 部署与 ChatGPT 验收尚未执行 —— 见 [docs/GATE1_ACCEPTANCE.md](docs/GATE1_ACCEPTANCE.md)）**
> 本仓库目前**没有**部署到任何 Cloudflare 账户，也**没有**创建任何云端资源。
> Gate 0 的目标只有一个：证明 `ChatGPT → Cloudflare Worker → Durable Object → 手机上的 App` 这条链路的每一环都真的能通、而且不会说谎。
>
> **Gate 0.1** 在此基础上做了三件事：①修掉审计提出的重连竞态（R1）、日志安全（R2）、前台服务启动失败（R3）、设备令牌保护（R4）；②把 Home 界面补成可用的配置/状态/日志面板；③交付一个可安装的 Debug APK 并逐条核对 Android 13–16 的官方规范。
>
> **Gate 0.2** 修掉的是 Gate 0.1 交付件里一个**真实存在、用户已经踩到**的问题：APK 装上后**点图标立刻闪退**。
> 根因是 `HubApplication.kt` 的 `package` 声明与 `AndroidManifest.xml` 里的 `android:name` 对不上 —— 编译期全绿、当时 132 个单测也全绿，却导致系统建进程时 `ClassNotFoundException`。
> 除了修这一行，还补了**两层**自动化检查防它再来：一层进 JVM 单测，一层解析**真实 APK** 里的二进制 Manifest 与 dex 的 class_defs 表（`:app:verifyManifestClasses`）。详见 [TEST_REPORT.md](TEST_REPORT.md) §6.6。
>
> **Gate 0.3** 按独立审计的三项发现做**最小修复**，不回退 Gate 0.2 的启动修复与 Manifest 类名校验：
> ①**Android 日志脱敏** —— 手机端日志不再出现完整 URL / 令牌 / 敏感查询参数 / 未处理的异常消息，只留主机名（`hostOf()`）与 `SocketFailure` 闭集里的**固定错误类别**（`SocketFailure.kt`）；服务端错误帧的 `message` 与未知帧的 `type` 原文一概不记（只记 `ServerErrorCode.normalize()` 的结果与 `typeLen`）。
> ②**Lint 门禁** —— `android/app/build.gradle.kts` 的 `abortOnError` 从 `false` 改回 `true`，并修掉被它抓出来的 `MissingPermission`；**没有**加任何 `disable` / baseline / `@SuppressLint`。证据（含"故意注入故障 → 门禁变红 → 恢复 → 放行"的原始输出）在 [test-reports/lint-gate.log](test-reports/lint-gate.log)。
> ③**DO 结算路径加固** —— `PhoneSession` 的 `settlePending()` 原来在三行裸 `await`（落盘 / 指标 / alarm）之后才 resolve，任一抛错就是**请求永久挂起**；现在记账失败被 `bestEffort()` 兜住并计数（`settleStorageFailures` / `settleMetricFailures` / `settleAlarmFailures`），`handlePingRequest` 也把定时器提到落盘之前。故障注入测试在 `worker/test/phone-session.test.ts` 的 `describe("12. 结算路径的故障注入")` 6 条。
>
> **Gate 1** 做的是任务书第四节的核心任务：**把 `/mcp` 从"共享一个静态 Bearer"升级成完整的 OAuth 2.1 授权服务器** —— 新增第三个 Durable Object `OAuthStore`（单实例，只存 `sha256` 摘要），实现 RFC 9728 资源元数据、RFC 8414 授权服务器元数据、RFC 7591 动态客户端注册、**强制 PKCE S256**、RFC 8707 受众绑定、RFC 9207 `iss`、刷新令牌轮换，以及一个零脚本、CSP `default-src 'none'` 的**运维同意页**。同时把 Gate 0.3 审计遗留的三项（结算路径、重复 `request_id`、DO 重建遗留 pending）修到有故障注入测试。安全决策与已知问题见 [docs/GATE1_SECURITY.md](docs/GATE1_SECURITY.md)。
>
> **没做的（不许当成已通过）**：真实 `wrangler deploy`、真实 workerd 验证、真机验证、ChatGPT 官方客户端的真实授权与调用 —— 这些在 [TEST_REPORT.md](TEST_REPORT.md) 与 [docs/GATE1_ACCEPTANCE.md](docs/GATE1_ACCEPTANCE.md) 里明确标着 **NOT RUN / BLOCKED**。Gate 1 的最终验收标准是"ChatGPT 真的 ping 到这台手机"，**这条链目前还没有真的跑过**。

---

## 1. 这条链路长什么样

```
ChatGPT 官方客户端
        │  ① OAuth：发现元数据 → 动态注册 → 运维在同意页批准 → 换访问令牌
        │  ② HTTPS / MCP (Streamable HTTP)，Authorization 头带访问令牌
        ▼
Cloudflare Worker          worker/src/index.ts
        │  ── OAuth 状态 ──► Durable Object   worker/src/oauth/store-do.ts  ← 类名 OAuthStore
        │  Durable Object Binding
        ▼
Durable Object             worker/src/phone/phone-session.ts   ← 类名 PhoneSession
        │  WebSocket / WSS（自定义内部协议 phone-link-v1）
        ▼
Android MCP Hub（独立 APK） android/                          ← Kotlin + Compose + OkHttp
        │  localhost HTTP
        ▼
本地 MCP 应用               ⛔ Gate 0 尚未接入
```

Gate 0 只做**一件事**：暴露一个工具 `phone_ping`，它必须一路走到真实的手机上、拿到真实手机回的真实 `phone_pong`、在**服务端**量出 RTT，再把结果返回给 ChatGPT。**Gate 1 只增加了"怎么安全地让 ChatGPT 进来"这一层（OAuth），工具集合没有变化 —— 仍然只有 `phone_ping`。**

**Worker 里没有、也不允许有任何"直接伪造 pong"的代码路径。** 这条不变量有测试守着，见 `worker/test/tools.test.ts` 的「转发证明」一节。

---

## 2. 为什么值得做一个 Durable Object

因为"会话"和"这台手机"都必须**恰好有一个归属**：

- MCP 用 `Mcp-Session-Id` 串起一串请求，而 Worker 的 isolate 随时会换机器、随时会被回收 —— 会话状态放内存里会随机失踪，所以放进 `McpSession`（一个会话一个对象）。
- 一台手机同一时刻只应该有一个受信任连接，重连必须**顶掉**旧连接而不是并存 —— 这个"唯一性"放进 `PhoneSession`，用**连接代次（generation）**仲裁。
- OAuth 的"授权码只能用一次""刷新令牌只能用一次"是**原子读—判—写**，而 KV 是最终一致 —— 所以放进 `OAuthStore`（**单实例** `idFromName("oauth-global")`），靠 DO 的单线程串行把这三步变成一个事务。

详见 [ARCHITECTURE.md](ARCHITECTURE.md)。

---

## 3. 目录结构

```
android-mcp-hub/
├── worker/                        Cloudflare Worker + Durable Object
│   ├── wrangler.jsonc             ⚠ 仅供人工审核，**没有部署过**
│   ├── .dev.vars.example          占位符；真实 .dev.vars 被 .gitignore 挡住
│   └── src/
│       ├── index.ts               入口：/healthz、/、/mcp、/phone/ws、/.well-known/*、/oauth/*
│       ├── env.ts                 绑定类型（**刻意只有三个 DO 绑定**，没有 D1/KV/R2/Queue 字段）
│       ├── config.ts              参数与设备凭据装载、device_id 解析、OAuth 参数
│       ├── oauth/                 ★ Gate 1 新增
│       │   ├── store-do.ts        OAuthStore DO（单实例；客户端/码/令牌/同意记录，只存 sha256）
│       │   ├── endpoints.ts       发现元数据、DCR、authorize、token、revoke、访问令牌校验
│       │   └── pages.ts           同意页与错误页（零脚本、CSP default-src 'none'）
│       ├── phone/
│       │   ├── PhoneSession DO    连接代次、pending 请求、超时、断线
│       │   └── messages.ts        内部协议帧的解析与编码
│       ├── mcp/
│       │   ├── http.ts            MCP Streamable HTTP 入口（initialize/tools/*）
│       │   ├── jsonrpc.ts         JSON-RPC 2.0 与错误码
│       │   ├── session-do.ts      McpSession DO（会话生命周期）
│       │   └── tools.ts           phone_ping 的定义、参数校验、结果映射
│       └── util/                  ids / crypto / http / log（白名单式安全日志）
├── worker/test/                   200 个自动化测试 + Workers 运行时 shim
│   ├── harness/                   Node 端 Workers 运行时替身（含故障注入，见 TEST_REPORT.md）
│   └── *.test.ts                  8 个测试文件
├── tools/mock-phone/              模拟手机 WebSocket 客户端（可命令行、可被引用）
├── tools/check-device-credential.ts  部署前检查：设备令牌 → DEVICE_TOKENS 记录（令牌只走 stdin）
├── e2e/run-local-e2e.ts           端到端：真 HTTP + 真 WebSocket + 官方 MCP SDK
├── android/                       Kotlin + Compose + OkHttp 手机端 App
│   ├── README-ANDROID.md          前台服务类型选择理由、人工验证清单
│   └── COMPATIBILITY-ANDROID-13-16.md  Android 13–16 逐项官方规范核对
├── docs/
│   ├── PROTOCOL.md                内部 WSS 协议规范（规则 R1–R13）
│   ├── OAUTH-DESIGN.md            OAuth 方案设计（Gate 1 已按它实现，实际实现见 worker/src/oauth/）
│   ├── GATE1_DEPLOYMENT.md        ★ 部署方案：资源清单、费用风险、命令、回滚、资源删除（待批准）
│   ├── GATE1_ACCEPTANCE.md        ★ 验收报告：阶段 A PASS / 阶段 B、C BLOCKED
│   └── GATE1_SECURITY.md          ★ Gate 1 安全说明：OAuth 决策、威胁 T13–T26、已知问题 G1-K1…K6
├── .github/workflows/             仅一个**手动触发**的 Debug APK 构建（无部署、无密钥）
├── test-reports/                  各项测试的**原始输出**（本报告的证据来源）
├── SECURITY.md                    威胁模型与安全边界
├── ARCHITECTURE.md                架构与关键设计决策
└── TEST_REPORT.md                 测试报告（含 NOT RUN 项）
```

---

## 4. 快速开始（本地）

前置：Node ≥ 20。本仓库锁定了 `package-lock.json`。

```bash
npm install
```

### 4.1 类型检查 + 单元测试 + 端到端

```bash
npm run gate0        # = typecheck && test && e2e
```

或者分开跑：

```bash
npm run typecheck    # tsc --noEmit
npm run test         # 200 个用例（8 个测试文件）
npm run e2e          # 21 项端到端检查（真 HTTP + 真 WebSocket + 官方 MCP SDK）
```

部署前还想自查两件事（都**不上传、不创建任何云端资源**）：

```bash
# 1) 部署配置能不能打包（三个 DO 绑定都会在这里被解析一遍）
npx wrangler@4.149.0 deploy --config worker/wrangler.jsonc --dry-run

# 2) 设备令牌 → DEVICE_TOKENS 记录（令牌只从 stdin 读，输出只有摘要）
printf '%s' "$DEVICE_TOKEN" | npm run check:credential -- --device-id my-android-phone
```

### 4.2 手动起一个"手机"和一个网关

> ⚠ **`npm run dev` 在本容器里跑不起来** —— `wrangler dev --local` 需要 workerd，而 workerd 在这个 Android 容器里因虚拟地址空间布局限制直接 abort。原因与证据见 [TEST_REPORT.md](TEST_REPORT.md) 的「NOT RUN」一节。

用模拟手机直连一个**本地网关**（由 shim 驱动）时，请用 `npm run e2e`；它会把整条链路真跑一遍并打印每一步。

单独跑模拟手机客户端（连接一个已经在跑的网关）：

```bash
# 令牌从环境变量读，不要写进任何文件、也不要粘到聊天里
export MCP_DEVICE_TOKEN='<本地测试令牌>'

npm run mock:phone -- \
  --url ws://127.0.0.1:8787/phone/ws \
  --device test-phone-1 \
  --token "$MCP_DEVICE_TOKEN" \
  --scenario normal        # normal | no-response | wrong-request-id | duplicate-pong
                           # | late | drop-after-ping | noisy | binary | hello-mismatch
```

`--scenario` 存在的意义是把 Gate 0 要求覆盖的那些**恶劣情况**变成一条命令，而不是靠人手去拔网线。

### 4.3 Android

```bash
cd android
ANDROID_HOME=/opt/android-sdk ./gradlew --no-daemon :app:testDebugUnitTest   # 157 个 JVM 单测
ANDROID_HOME=/opt/android-sdk ./gradlew --no-daemon :app:verifyManifestClasses # Manifest 类名 ↔ 真实 dex
ANDROID_HOME=/opt/android-sdk ./gradlew --no-daemon :app:lintDebug            # Lint 门禁（abortOnError = true）
ANDROID_HOME=/opt/android-sdk ./gradlew --no-daemon :app:assembleDebug        # 产出 Debug APK
```

产物：`android/app/build/outputs/apk/debug/app-debug.apk`（`versionName 0.1.4-gate1`，`applicationId com.androidmcphub.app`，`minSdk 26 / targetSdk 35`）。
现成的交付件在 `dist/Android-MCP-Hub-Gate1-debug.apk`（SHA-256 见 [docs/GATE1_ACCEPTANCE.md](docs/GATE1_ACCEPTANCE.md) §3）。
手机端配置（网关地址、设备 ID、令牌）在 App 界面上填，**不写在源码里**；设备令牌用 Android Keystore 里的密钥加密后存本机私有存储（**不留明文**）。

> 想拿到一个现成的 APK，也可以用手动触发的 GitHub Actions 工作流：`.github/workflows/android-debug-apk.yml`（**只用 `workflow_dispatch`**，不部署、不读任何密钥）。⚠ 它在本仓库里**从未被执行过**。

---

## 5. 配置项

全部通过 Worker 的 `vars` / secrets 注入，代码里没有默认令牌。见 `worker/wrangler.jsonc` 与 `worker/.dev.vars.example`。

| 名字 | 默认 | 说明 |
| --- | --- | --- |
| `PHONE_PING_TIMEOUT_MS` | `10000` | 一次 `phone_ping` 等 pong 的上限。**服务端**计时 |
| `HEARTBEAT_INTERVAL_MS` | `30000` | 心跳间隔 |
| `MAX_PENDING_REQUESTS` | `16` | 单台手机同时在飞的请求上限，超了立刻失败而不是排队 |
| `MAX_PHONE_MESSAGE_BYTES` | `65536` | 单帧上限，超了拒绝 |
| `SUPPORTED_MCP_PROTOCOL_VERSIONS` | `2025-06-18,2025-03-26,2024-11-05` | 协商用 |
| `DEFAULT_DEVICE_ID` | 空 | 只配了一台手机时可以省掉 `device_id` |
| `MCP_ACCESS_TOKEN` | 无 | **secret，可选**。静态 Bearer，只给本机 `curl` / e2e 用；**真实部署时不要设置它**，ChatGPT 走 OAuth |
| `DEVICE_TOKENS` | 无 | **secret**。`[{deviceId, tokenSha256, label, createdAt, revokedAt}]`，只存 sha256 |
| `OAUTH_OWNER_SECRET` | 无 | **secret**。同意页口令；**同时是 OAuth 的开关** —— 不设置时整个 OAuth 面回 404 |
| `OAUTH_SCOPE` | `mcp:tools` | 签发的访问令牌带的 scope |
| `OAUTH_ACCESS_TOKEN_TTL_MS` | `3600000` | 访问令牌有效期（夹紧 [60s, 24h]） |
| `OAUTH_REFRESH_TOKEN_TTL_MS` | `2592000000` | 刷新令牌有效期（夹紧 [60s, 365d]） |
| `OAUTH_CODE_TTL_MS` | `300000` | 授权码有效期（夹紧 [10s, 10min]） |
| `OAUTH_CONSENT_TTL_MS` | `600000` | 同意请求有效期（夹紧 [10s, 1h]） |
| `OAUTH_REDIRECT_ALLOWLIST` | `chatgpt.com` | 允许的重定向主机；只有显式含 `localhost` 时才放行 http 回环 |

OAuth 相关的安全设计与已知问题见 [docs/GATE1_SECURITY.md](docs/GATE1_SECURITY.md)。

任务书要求"这些参数必须可配置"。每个值都有上下限夹紧（`worker/src/config.ts`）：夹紧的意义是挡 `0`/负数/荒谬大值，而**不是**替运维决定"多快算快"——所以下限刻意放得很低（`PHONE_PING_TIMEOUT_MS` 最低 50ms），这样本地回环上的测试可以真的测到超时路径。

---

## 6. Gate 0 / 0.1 / 0.2 / 0.3 / 1 明确**没有**做的事

- ❌ 没有 `wrangler deploy`，没有创建任何 Cloudflare 资源，没有 D1/KV/R2/Queue。
- ❌ **没有在真实 Cloudflare 上跑过 OAuth**（Gate 1 实现的是代码 + 本地替身证据；真实授权流程 NOT RUN）。
- ❌ **没有让 ChatGPT 官方客户端真的调过一次 `phone_ping`**（任务书把这条列为 Gate 1 的唯一验收标准，目前 BLOCKED）。
- ❌ 没有接入任何本地 MCP 应用（不是 `phone_ping` 之外的工具都没有，是**一个都没有**）。
- ❌ 没有自动部署流水线（`.github/workflows` 里那个只在手动点击时构建 APK，不部署、不读密钥），没有把任何真实凭据提交进仓库。
- ❌ 没有测量真实手机上的 Android 运行时行为（App 编得过、单测跑得过、APK 出得来、Lint 门禁过得了，但**从未在真机上运行过**，手工验证清单一条都没跑）。
- ❌ 没有在真实 workerd 上跑过任何测试（容器跑不起 workerd，原因见 TEST_REPORT.md）。
- ❌ 没有验证 Android Keystore 的真实加解密路径（JVM 测不到，工程没引 Robolectric）。

完整清单与逐条证据在 [TEST_REPORT.md](TEST_REPORT.md) 与 [docs/GATE1_ACCEPTANCE.md](docs/GATE1_ACCEPTANCE.md)。

---

## 7. 文档索引

| 文件 | 内容 |
| --- | --- |
| [ARCHITECTURE.md](ARCHITECTURE.md) | 架构、连接代次、超时与重连、DO 为什么这么切分 |
| [SECURITY.md](SECURITY.md) | 威胁模型、凭据模型、安全边界与残余风险 |
| [TEST_REPORT.md](TEST_REPORT.md) | 实际执行的命令与结果、**NOT RUN 项**、已知风险 |
| [docs/GATE1_DEPLOYMENT.md](docs/GATE1_DEPLOYMENT.md) | **待批准**的部署方案：资源清单、费用风险、命令、验证、回滚、资源删除 |
| [docs/GATE1_ACCEPTANCE.md](docs/GATE1_ACCEPTANCE.md) | Gate 1 验收：阶段 A 的 PASS 明细 + 阶段 B/C 的 BLOCKED 清单 |
| [docs/GATE1_SECURITY.md](docs/GATE1_SECURITY.md) | Gate 1 安全：OAuth 决策、威胁 T13–T26、已知问题 G1-K1…G1-K6 |
| [docs/PROTOCOL.md](docs/PROTOCOL.md) | 内部 WSS 协议 v1（规则 R1–R13、失败码、心跳） |
| [docs/OAUTH-DESIGN.md](docs/OAUTH-DESIGN.md) | OAuth 2.1 + PKCE 方案设计（Gate 1 已实现，实现见 `worker/src/oauth/`） |
| [android/README-ANDROID.md](android/README-ANDROID.md) | 前台服务类型选择理由、人工验证清单 |
| [android/COMPATIBILITY-ANDROID-13-16.md](android/COMPATIBILITY-ANDROID-13-16.md) | Android 13–16 逐项官方规范核对、`specialUse` 的合规论证与替代方案对比 |

---

## 8. 下一步（**等用户批准，不要自己往下走**）

**Gate 1 的代码与本地验证已经做完，现在停在"部署前"这一步。**

用户在可信环境完成下面 4 件事之后，才能进入阶段 B / 阶段 C：

1. `npx wrangler@4.149.0 login`（用 **Android MCP Hub 专属账号**，与既有生产项目完全独立）；
2. `npx wrangler@4.149.0 secret put DEVICE_TOKENS --config worker/wrangler.jsonc`；
3. `npx wrangler@4.149.0 secret put OAUTH_OWNER_SECRET --config worker/wrangler.jsonc`；
4. `npx wrangler@4.149.0 deploy --config worker/wrangler.jsonc`。

完整命令、验证步骤、回滚与资源删除在 [docs/GATE1_DEPLOYMENT.md](docs/GATE1_DEPLOYMENT.md)。
**没有用户明确确认，不做任何云端操作、不创建任何收费资源、不改账号配置。**

阶段 B/C 通过之后，才谈第三件事：接入本地 MCP 应用（仍未开始设计）。
