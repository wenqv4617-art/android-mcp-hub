# Android MCP Hub

让 **ChatGPT 官方客户端**通过 MCP 直接指挥一台 **Android 手机**。

> **状态：Gate 0.2（修掉 Gate 0.1 交付件里的启动闪退；仍然只验证链路，不接任何本地 MCP 应用）**
> 本仓库目前**没有**部署到任何 Cloudflare 账户，也**没有**创建任何云端资源。
> Gate 0 的目标只有一个：证明 `ChatGPT → Cloudflare Worker → Durable Object → 手机上的 App` 这条链路的每一环都真的能通、而且不会说谎。
>
> **Gate 0.1** 在此基础上做了三件事：①修掉审计提出的重连竞态（R1）、日志安全（R2）、前台服务启动失败（R3）、设备令牌保护（R4）；②把 Home 界面补成可用的配置/状态/日志面板；③交付一个可安装的 Debug APK 并逐条核对 Android 13–16 的官方规范。
>
> **Gate 0.2** 修掉的是 Gate 0.1 交付件里一个**真实存在、用户已经踩到**的问题：APK 装上后**点图标立刻闪退**。
> 根因是 `HubApplication.kt` 的 `package` 声明与 `AndroidManifest.xml` 里的 `android:name` 对不上 —— 编译期全绿、当时 132 个单测也全绿，却导致系统建进程时 `ClassNotFoundException`。
> 除了修这一行，还补了**两层**自动化检查防它再来：一层进 JVM 单测，一层解析**真实 APK** 里的二进制 Manifest 与 dex 的 class_defs 表（`:app:verifyManifestClasses`）。详见 [TEST_REPORT.md](TEST_REPORT.md) §6.6。
>
> **没做的**：workerd 上的验证、真机验证（**包括"这个新 APK 装上之后到底还会不会闪退"**）、ChatGPT 的真实 OAuth 接入 —— 这些在 [TEST_REPORT.md](TEST_REPORT.md) 里明确标着 **NOT RUN**。

---

## 1. 这条链路长什么样

```
ChatGPT 官方客户端
        │  HTTPS / MCP (Streamable HTTP)
        ▼
Cloudflare Worker          worker/src/index.ts
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

Gate 0 只做**一件事**：暴露一个工具 `phone_ping`，它必须一路走到真实的手机上、拿到真实手机回的真实 `phone_pong`、在**服务端**量出 RTT，再把结果返回给 ChatGPT。

**Worker 里没有、也不允许有任何"直接伪造 pong"的代码路径。** 这条不变量有测试守着，见 `worker/test/tools.test.ts` 的「转发证明」一节。

---

## 2. 为什么值得做一个 Durable Object

因为"会话"和"这台手机"都必须**恰好有一个归属**：

- MCP 用 `Mcp-Session-Id` 串起一串请求，而 Worker 的 isolate 随时会换机器、随时会被回收 —— 会话状态放内存里会随机失踪，所以放进 `McpSession`（一个会话一个对象）。
- 一台手机同一时刻只应该有一个受信任连接，重连必须**顶掉**旧连接而不是并存 —— 这个"唯一性"放进 `PhoneSession`，用**连接代次（generation）**仲裁。

详见 [ARCHITECTURE.md](ARCHITECTURE.md)。

---

## 3. 目录结构

```
android-mcp-hub/
├── worker/                        Cloudflare Worker + Durable Object
│   ├── wrangler.jsonc             ⚠ 仅供人工审核，**没有部署过**
│   ├── .dev.vars.example          占位符；真实 .dev.vars 被 .gitignore 挡住
│   └── src/
│       ├── index.ts               入口：/healthz、/、/mcp、/phone/ws
│       ├── env.ts                 绑定类型（**刻意没有** D1/KV/R2/Queue 字段）
│       ├── config.ts              参数与设备凭据装载、device_id 解析
│       ├── phone/
│       │   ├── PhoneSession DO    连接代次、pending 请求、超时、断线
│       │   └── messages.ts        内部协议帧的解析与编码
│       ├── mcp/
│       │   ├── http.ts            MCP Streamable HTTP 入口（initialize/tools/*）
│       │   ├── jsonrpc.ts         JSON-RPC 2.0 与错误码
│       │   ├── session-do.ts      McpSession DO（会话生命周期）
│       │   └── tools.ts           phone_ping 的定义、参数校验、结果映射
│       └── util/                  ids / crypto / http / log（白名单式安全日志）
├── worker/test/                   140 个自动化测试 + Workers 运行时 shim
│   ├── harness/                   Node 端 Workers 运行时替身（见 TEST_REPORT.md）
│   └── *.test.ts                  7 个测试文件
├── tools/mock-phone/              模拟手机 WebSocket 客户端（可命令行、可被引用）
├── e2e/run-local-e2e.ts           端到端：真 HTTP + 真 WebSocket + 官方 MCP SDK
├── android/                       Kotlin + Compose + OkHttp 手机端 App
│   ├── README-ANDROID.md          前台服务类型选择理由、人工验证清单
│   └── COMPATIBILITY-ANDROID-13-16.md  Android 13–16 逐项官方规范核对
├── docs/
│   ├── PROTOCOL.md                内部 WSS 协议规范（规则 R1–R13）
│   └── OAUTH-DESIGN.md            ChatGPT 接入所需的 OAuth 方案（设计，未实现）
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
npm run test         # 140 个用例（7 个测试文件）
npm run e2e          # 21 项端到端检查（真 HTTP + 真 WebSocket + 官方 MCP SDK）
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
ANDROID_HOME=/opt/android-sdk ./gradlew --no-daemon :app:testDebugUnitTest   # 135 个 JVM 单测
ANDROID_HOME=/opt/android-sdk ./gradlew --no-daemon :app:verifyManifestClasses # Manifest 类名 ↔ 真实 dex
ANDROID_HOME=/opt/android-sdk ./gradlew --no-daemon :app:assembleDebug        # 产出 Debug APK
```

产物：`android/app/build/outputs/apk/debug/app-debug.apk`（`versionName 0.1.2-gate0.2`，`applicationId com.androidmcphub.app`，`minSdk 26 / targetSdk 35`）。
现成的交付件在 `dist/Android-MCP-Hub-Gate0.2-debug.apk`（SHA-256 见 [TEST_REPORT.md](TEST_REPORT.md) §11）。
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
| `MCP_ACCESS_TOKEN` | 无 | **secret**。ChatGPT 那条链路的 Bearer token |
| `DEVICE_TOKENS` | 无 | **secret**。`[{deviceId, tokenSha256, label, createdAt, revokedAt}]`，只存 sha256 |

任务书要求"这些参数必须可配置"。每个值都有上下限夹紧（`worker/src/config.ts`）：夹紧的意义是挡 `0`/负数/荒谬大值，而**不是**替运维决定"多快算快"——所以下限刻意放得很低（`PHONE_PING_TIMEOUT_MS` 最低 50ms），这样本地回环上的测试可以真的测到超时路径。

---

## 6. Gate 0 / 0.1 明确**没有**做的事

- ❌ 没有 `wrangler deploy`，没有创建任何 Cloudflare 资源，没有 D1/KV/R2/Queue。
- ❌ 没有 OAuth（现在 MCP 入口是一个共享 Bearer token）。设计见 [docs/OAUTH-DESIGN.md](docs/OAUTH-DESIGN.md)。
- ❌ 没有接入任何本地 MCP 应用（不是 `phone_ping` 之外的工具都没有，是**一个都没有**）。
- ❌ 没有自动部署流水线（`.github/workflows` 里那个只在手动点击时构建 APK，不部署、不读密钥），没有把任何真实凭据提交进仓库。
- ❌ 没有测量真实手机上的 Android 运行时行为（App 编得过、单测跑得过、APK 出得来，但**从未在真机上运行过**，手工验证清单一条都没跑）。
- ❌ 没有在真实 workerd 上跑过任何测试（容器跑不起 workerd，原因见 TEST_REPORT.md）。
- ❌ 没有验证 Android Keystore 的真实加解密路径（JVM 测不到，工程没引 Robolectric）。

完整清单与逐条证据在 [TEST_REPORT.md](TEST_REPORT.md)。

---

## 7. 文档索引

| 文件 | 内容 |
| --- | --- |
| [ARCHITECTURE.md](ARCHITECTURE.md) | 架构、连接代次、超时与重连、DO 为什么这么切分 |
| [SECURITY.md](SECURITY.md) | 威胁模型、凭据模型、安全边界与残余风险 |
| [TEST_REPORT.md](TEST_REPORT.md) | 实际执行的命令与结果、**NOT RUN 项**、已知风险 |
| [docs/PROTOCOL.md](docs/PROTOCOL.md) | 内部 WSS 协议 v1（规则 R1–R13、失败码、心跳） |
| [docs/OAUTH-DESIGN.md](docs/OAUTH-DESIGN.md) | ChatGPT 接入所需的 OAuth 2.1 + PKCE 方案（设计） |
| [android/README-ANDROID.md](android/README-ANDROID.md) | 前台服务类型选择理由、人工验证清单 |
| [android/COMPATIBILITY-ANDROID-13-16.md](android/COMPATIBILITY-ANDROID-13-16.md) | Android 13–16 逐项官方规范核对、`specialUse` 的合规论证与替代方案对比 |

---

## 8. 下一步（交给 G 老师复审后再动）

**Gate 0.2 完成即停。** 没有用户明确确认，不做任何云端操作、不配置 OAuth、不连真机。
下一阶段建议见 [TEST_REPORT.md](TEST_REPORT.md) 的 §8 与 §11 —— 按优先级是：①**在正常 Linux 主机上跑真实 workerd**（消掉最大的 NOT RUN）；②**真机跑一遍人工验证清单**（`android/README-ANDROID.md` §8）；③在那之后才谈 OAuth 与公网暴露。
