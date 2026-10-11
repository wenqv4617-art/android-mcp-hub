# GATE1_ACCEPTANCE.md — Android MCP Hub Gate 1 验收报告

> **当前状态：阶段 A 完成（本地）；阶段 B / 阶段 C 全部 BLOCKED —— 等用户在可信环境完成 Cloudflare 部署与 ChatGPT 接入授权。**
> 本文只记录**真实执行过的命令与真实输出**。没有真实执行的项一律标 `NOT RUN` 或 `BLOCKED`，**绝不写成 PASS**。

---

## 0. 一句话现状

**代码已经具备完整闭环所需的全部能力，本地能证明的每一条都通过了（阶段 A：13 PASS / 0 FAIL / 6 NOT RUN）；但「ChatGPT 真的 ping 到这台手机」这件事本身还没有发生 —— 它需要用户在专属账号里执行一次真实的 `wrangler deploy`（已获批准，约定由用户自己执行），然后手机连上、ChatGPT 完成授权。**
>
> 另：由于 Termux 装不上 workerd，新增了 `worker-gate1-check.yml` 把 Worker 测试挪到 ubuntu runner 上；它在本地已通过静态校验（A13），但**尚未在 GitHub 上真实执行过**（需用户手动触发）。

---

## 1. 图例

| 标记 | 含义 |
| --- | --- |
| **PASS** | 在本机真实执行过，输出为通过，证据文件在仓库里可查 |
| **FAIL** | 真实执行过且失败（本轮为 0 条） |
| **NOT RUN** | 本机根本跑不了（环境缺能力），**不是通过** |
| **BLOCKED** | 本机能跑，但需要用户批准/提供凭据才能执行，按任务书要求**主动暂停** |

---

## 2. 验收的唯一标准

任务书第 11 节：Gate 1 的最终验收 **不是**"Worker 测试通过"，**也不是**"APK 显示已连接"，而是下面这条完整闭环：

```
用户在 ChatGPT 官方客户端说："G老师，Ping 一下我的手机。"
   → ChatGPT 真的发起 MCP tools/call phone_ping
   → Cloudflare Worker → PhoneSession DO → WSS → 真实 Android 手机
   → 手机回同一个 request_id 的 phone_pong
   → ChatGPT 拿到真实结果（成功/失败、device_id、request_id、RTT、connection_generation）
   → 手机界面能看到对应的 Ping 记录
```

**只要还没走完这条链，就不允许宣布 Gate 1 PASS。** 本报告的结论就是"还没走完"。

---

## 3. 阶段 A —— 本地自动化测试与部署前检查（**已执行**）

| # | 项目 | 命令 | 结果 | 证据文件 |
| --- | --- | --- | --- | --- |
| A1 | TypeScript 类型检查 | `npx tsc --noEmit -p tsconfig.json` | **PASS**（`TYPECHECK_EXIT=0`） | `test-reports/typecheck.log` |
| A2 | Worker / DO / MCP 协议单元测试 | `npx vitest run --config worker/vitest.config.ts` | **PASS** — `Test Files 8 passed (8)` / `Tests 207 passed (207)` | `test-reports/vitest.log` |
| A3 | 端到端（Worker + 两个 DO + 模拟手机客户端，走真实 WebSocket） | `npx tsx e2e/run-local-e2e.ts` | **PASS** — `端到端结果：21 通过，0 失败（共 21 项）` | `test-reports/e2e.log` |
| A4 | Android JVM 单元测试 | `ANDROID_HOME=/opt/android-sdk ./gradlew --no-daemon :app:testDebugUnitTest` | **PASS** — `TOTAL tests=171 failures=0 errors=0 skipped=0`（14 个测试类） | `test-reports/android-build.log` |
| A5 | Manifest 组件类名 ↔ **真实 dex** | `./gradlew --no-daemon :app:verifyManifestClasses` | **PASS**（`VERIFY_EXIT=0`） | `test-reports/android-build.log` |
| A6 | Android Lint 门禁（`abortOnError = true`） | `./gradlew --no-daemon :app:lintDebug` | **PASS** — `0 errors / 29 warnings`；注入故障时 `BUILD FAILED`（证明门禁真的会红） | `test-reports/lint-gate.log` |
| A7 | Debug APK 构建 | `./gradlew --no-daemon clean :app:assembleDebug` | **PASS**（`ASSEMBLE_EXIT=0`，两次重建 `REPRODUCIBLE=YES`） | `test-reports/android-build.log` |
| A8 | APK 内不含凭据形状字符串 | 解包 + `grep` 10 个模式 | **PASS** — 全部 `命中文件数=0` | `test-reports/android-build.log` §8 |
| A9 | **部署配置能打包**（`--dry-run`，不上传、不创建资源） | `npx wrangler@4.149.0 deploy --config worker/wrangler.jsonc --dry-run` | **PASS** — `Total Upload: 120.35 KiB / gzip: 31.40 KiB`，三个 DO binding 全部解析到真实导出类，`DRYRUN_EXIT=0` | `test-reports/wrangler-dryrun.log` |
| A10 | **凭据生成步骤自检**（第 4.1 节的命令真的产出 Worker 认的摘要） | `printf '%s' "$TOKEN" \| npm run check:credential -- --device-id my-android-phone` | **PASS** — 与 `sha256sum` 逐字节一致；`loadConfig` 接受该 `DEVICE_TOKENS` 形状（1 条生效凭据、`deviceId` 解析成功）；把明文令牌填进 `tokenSha256` 会被明确拒绝 | `test-reports/credential-check.log` |
| A11 | **部署后自检脚本本身可信**（部署前能验的就只剩这个） | `npm run smoke:selftest` | **PASS** — 10 PASS / 0 FAIL。先回放**正确**响应证明 `tools/gate1-smoke.mjs` 判 PASS（退出码 0），再把 S256、`resource_metadata`、`healthz.status` 分别改坏，证明它**真的会**报 FAIL（退出码 1），目标不可达时报退出码 2 —— 即「不会假绿、也不会把没验的算成通过」 | `test-reports/smoke-selftest.log` |
| A12 | **部署文档里的凭据生成流程真的能跑通**（部署已被批准、改由用户执行，文档本身于是也成了交付件） | `printf '%s' "$DEVICE_TOKEN" | npm run check:credential -- --device-id my-android-phone`，再把输出的那段 JSON 喂给真实的 `loadConfig` | **PASS** — 生成的记录被 `loadConfig` 接受：`problems: []`、`credentials: 1`、`deviceId: "my-android-phone"`、`tokenSha256` 长度 64、`oauth.enabled: true`、`hasMcpAccessToken: false`（正是我们要部署的那套配置） | 本节 |
| A13 | **新增的 Worker CI 工作流满足全部静态约束**（Termux 装不上 workerd，测试挪到 ubuntu 上跑） | `npx vitest run worker/test/security.test.ts`（§12.13 的四条闸门）+ 人工核对 YAML 缩进与结构 | **PASS** — `workflow_dispatch` 手动触发、无 push/PR/定时触发、无部署命令/云凭据/GitHub Secrets、`permissions: contents: read`、工作流清单恰好两个文件；三条闸门都做过故障注入（`on: push` / `run: npx wrangler deploy` + `CLOUDFLARE_API_TOKEN: ${{ secrets.CF_TOKEN }}` / 多丢一个 `rogue.yml`） | `test-reports/vitest.log`；说明见 `TEST_REPORT.md` §12.16 |

### A2 明细（`worker/test/` 8 个文件，207 条）

| 文件 | 条数 | 覆盖 |
| --- | --- | --- |
| `worker/test/oauth.test.ts` | 49 | **Gate 1 新增**：OAuth 全流程 + 安全性质 |
| `worker/test/phone-session.test.ts` | 46 | DO 状态机、超时、断线、重建、**发现项 A/B/C 的故障注入** |
| `worker/test/mcp-http.test.ts` | 31 | `/mcp` 传输层、鉴权、会话、方法派发 |
| `worker/test/tools.test.ts` | 19 | `phone_ping` 参数校验与结果形状 |
| `worker/test/worker-index.test.ts` | 20 | 路由、`/healthz` 契约、OAuth 开启/关闭时的认证路径自检 |
| `worker/test/security.test.ts` | 19 | 仓库凭据扫描、隔离性、DO 绑定数、**不开启自动部署**（4 条，见表下注） |
| `worker/test/logging.test.ts` | 12 | 日志白名单 / 错误类别 / 凭据形状终检 |
| `worker/test/mcp-session.test.ts` | 11 | `McpSession` 生命周期与 TTL |

> 逐文件计数用 `npx vitest run --config worker/vitest.config.ts --reporter=json --outputFile=…` 解析得到，合计 49+46+31+20+19+19+12+11 = **207**，与 vitest 自报的 `207 passed` 逐条吻合。
>
> `oauth.test.ts` 由 46 增至 49：新增客户端注册上限（`MAX_CLIENTS = 500`，超出回 **429** 而非 503）与 `redirect_uris` 数组长度闸（`MAX_REDIRECT_URIS = 10`）三条。这修掉的是 `docs/GATE1_SECURITY.md` 里原本记为**已知问题 G1-K1** 的真实资源耗尽路径：`/oauth/register` 必须公开，而 `client:` 记录既无 `expiresAt`、也不在 `alarm()` 的清理前缀里 —— 一个不需要任何凭据的循环就能让它单调增长。上限闸门整段删掉时这条测试会红（`Tests 1 failed | 48 passed`）。
>
> `security.test.ts` 由 15 增至 19：新增 `describe("安全 / 不开启任何自动部署")` —— 起初三条，Gate 1 收尾时第 2 条由"禁字符串"改写为"禁能力"、并补了第 4 条（工作流清单必须恰好是两个文件）。起因见 `TEST_REPORT.md` §12.16：Termux 装不上 workerd，于是多了一个在 ubuntu 上跑测试的工作流，而"跑测试"这件事本身就会碰到 `wrangler` 这个词 —— 禁字符串的闸会去咬正确的改动。四条都做过故障注入验证（`on: push` → 1 红；`run: npx wrangler deploy` + `CLOUDFLARE_API_TOKEN: ${{ secrets.CF_TOKEN }}` → 1 红且失败信息点名文件；多丢一个 `rogue.yml` → 1 红），注入后还原到逐字节一致。

### A4 明细（Android 14 个类，171 条）

`PhoneLinkTest` 35 / `ConnectGateTest` 21 / `DeviceTokenStoreTest` 18 / `PhoneLinkProtocolTest` 15 / `LogRedactionTest` 13 / `BackoffTest` 9 / `PingResponderTest` 9 / `SocketFailureTest` 9 / `WsUrlTest` 8 / `HubConfigTest` 7 / `PhoneLinkClientTest` 7 / `HubLogTest` 4 / `ManifestComponentTest` 2

### A7 产物

| 项 | 值 |
| --- | --- |
| 文件 | `dist/Android-MCP-Hub-Gate1-debug.apk` |
| SHA-256 | `2467a0348696fd94170f0db86f829d43beeb6bfb6286aba4b31adad80b8c868b` |
| 大小 | 10 318 558 字节 |
| `versionName` / `versionCode` | `0.1.4-gate1` / `5` |
| `minSdk` / `targetSdk` / `compileSdk` | 26 / 35 / 35 |
| 签名 | Debug（`CN=Android Debug`），cert SHA-256 `b73f783c579622944c969d1cc5689daf789589ac8fd2e035a548dc8506f36429` |
| 权限 | 5 个自有权限 + AndroidX 的 `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`（**没有新增任何权限**） |

---

## 4. 阶段 A 里 **NOT RUN** 的部分（不许当成通过）

| 项 | 为什么没跑 | 影响 |
| --- | --- | --- |
| `wrangler dev --local`（真实 workerd） | 本容器是 Android 运行时，workerd 启动即 `MmapAligned() failed` / `FATAL ERROR: Out of memory trying to allocate internal tcmalloc data`，退出码 134 | 所有 Worker 结论都来自 **Node 替身**，不是真运行时 |
| `@cloudflare/vitest-pool-workers` | 同上 | Durable Object 的真实驱逐/重建、真实 alarm 送达未验证 |
| 真实 Hibernation API 行为 | 同上 | `acceptWebSocket` 的休眠唤醒路径未验证 |
| GitHub Actions 工作流（真实执行） | 本机没有 runner；两个工作流都从未被执行过 | `android-debug-apk.yml` 与 `worker-gate1-check.yml` 都只是**手动触发**、都不含任何部署步骤（`permissions: contents: read`）。新增的 Worker CI 工作流已本地做过静态验证（A13），但「在 GitHub 上真的跑一次」仍需用户手动触发 —— 在那之前它不算通过 |
| 费用测算 | 需要账号与真实账单 | 见 `docs/GATE1_DEPLOYMENT.md` 第 2 节，标 NOT RUN |

---

## 5. 阶段 B —— 真实 Cloudflare 联调（**BLOCKED**）

**全部 10 项都未执行。** 前置条件是用户在可信环境完成：
① `wrangler login`；② `wrangler secret put DEVICE_TOKENS`；③ `wrangler secret put OAUTH_OWNER_SECRET`；④ `wrangler deploy`。

| # | 要验证的 | 怎么验证 | 期望 | 状态 |
| --- | --- | --- | --- | --- |
| B1 | Worker 正常运行 | `curl https://<worker>/healthz` | `status=ok`、`d1_bindings=0`、`durable_object_bindings` 含三个绑定、`config_problems=[]` | **BLOCKED** |
| B2 | 发现路由可用 | `curl https://<worker>/.well-known/oauth-protected-resource/mcp` 与 `/.well-known/oauth-authorization-server` | 200 + JSON，`resource` 指向 `<worker>/mcp` | **BLOCKED** |
| B3 | 未认证 `/mcp` 必须 401 且带 `resource_metadata` | `curl -i https://<worker>/mcp` | 401 + `WWW-Authenticate` 含 `resource_metadata="…/.well-known/oauth-protected-resource/mcp"` | **BLOCKED** |
| B4 | 错误设备令牌必须 401 | `curl -i "https://<worker>/phone/ws" -H 'X-Device-Id: …' -H 'Authorization: Bearer <错误值>'` | 401 + `{error:"unauthorized"}` | **BLOCKED** |
| B5 | Android 能建立 WSS | 手机上填真实 WSS 地址 + 设备 ID + 令牌，点"连接" | 状态显示已连接，`/healthz` 的 `phone_connected` 变 true | **BLOCKED** |
| B6 | （上一项的否命题）错误令牌被拒 | 手机上故意填错令牌 | 界面明确显示失败，**不显示已连接** | **BLOCKED** |
| B7 | 真实 phone_ping / phone_pong | 用 OAuth 令牌或静态令牌调 `tools/call phone_ping` | 返回 `isError=false` + `rtt_ms` 为真实数字 | **BLOCKED** |
| B8 | `request_id` 正确匹配 | 同上，核对返回的 `request_id` 与手机界面上的记录一致 | 一致 | **BLOCKED** |
| B9 | RTT 正确 | 对比 `rtt_ms` 与手机端显示的耗时 | 同数量级、不出现 0 或负数 | **BLOCKED** |
| B10 | 断线后状态正确 / 网络恢复能重连 | 手机断网 → 观察 Worker 侧请求失败而不是挂起；恢复网络 → 观察自动重连 | 失败有明确错误码；重连后能再次 ping 通 | **BLOCKED** |

---

## 6. 阶段 C —— ChatGPT 官方 MCP 验收（**BLOCKED**）

**不得用模拟客户端结果代替本阶段。** 前置条件：阶段 B 全绿 + 用户在 ChatGPT 里完成 OAuth 授权。

| # | 要验证的 | 操作 | 期望 | 状态 |
| --- | --- | --- | --- | --- |
| C1 | ChatGPT 能完成授权 | ChatGPT → 添加自定义 MCP server → 填 `<worker>/mcp` → 选 OAuth → 在同意页输入 `OAUTH_OWNER_SECRET` 并批准 | 授权成功，连接器状态为已连接 | **BLOCKED** |
| C2 | ChatGPT 能发现 `phone_ping` | 在 ChatGPT 里问"我连了哪些工具" | 列出 `phone_ping` | **BLOCKED** |
| C3 | ChatGPT 能实际调用 `phone_ping` | 输入验收语句："**G老师，Ping 一下我的手机。**" | ChatGPT 真的发起 `tools/call phone_ping`（不是它自己编一个答案） | **BLOCKED** |
| C4 | 手机收到真实请求 | 同时看手机界面 | 出现一条新的 Ping 记录 | **BLOCKED** |
| C5 | ChatGPT 获得真实 `phone_pong` 结果 | 看 ChatGPT 的回复 | 含成功状态、`device_id`、`request_id`、RTT | **BLOCKED** |
| C6 | 双方 `request_id` 一致 | 对比 ChatGPT 回复里的 `request_id` 与手机记录 | 完全一致 | **BLOCKED** |

---

## 7. 汇总

> **状态更新（本轮）**：真实部署**已获用户批准**，并约定**由用户自己执行**（凭据只存在于用户的终端与 Cloudflare；逐条命令见 [GATE1_DEPLOYMENT.md](GATE1_DEPLOYMENT.md) §5，已确认的决定见其 §10）。
> 因此 B/C 的阻塞原因已经从「等批准」变成「等用户跑完部署命令并把 `workers.dev` 地址给我」——
> 这两段在**真实执行之前仍然是 BLOCKED**，不会因为"批准了"就改标 PASS。

| 阶段 | PASS | FAIL | NOT RUN | BLOCKED | 合计 |
| --- | --- | --- | --- | --- | --- |
| A 本地自动化（含 A9–A13 五项部署前检查） | **13** | 0 | 6 | 0 | 19 |
| B 真实 Cloudflare 联调 | 0 | 0 | 0 | **10** | 10 |
| C ChatGPT 官方验收 | 0 | 0 | 0 | **6** | 6 |
| **合计** | **13** | **0** | **6** | **16** | **35** |

---

## 8. 什么时候可以宣布 Gate 1 PASS

三条**全部**满足才可宣布：

1. 阶段 B 的 10 项全部从 BLOCKED 变成 PASS，且证据（`curl` 原始输出、手机截图、Worker 日志）入库；
2. 阶段 C 的 6 项全部从 BLOCKED 变成 PASS，其中 C6 的 `request_id` 一致性有**两边各自的截图**为证；
3. 阶段 A 的 13 项在**与阶段 B 相同的那个 commit** 上重跑仍然全绿。

任何一条不满足，状态就是"Gate 1 未完成"，不能写成"基本可用"。

---

## 9. 交付物对照（任务书第九节 10 项）

| # | 交付物 | 状态 | 位置 |
| --- | --- | --- | --- |
| 1 | Gate 1 源代码 | ✅ | 本仓库 `gate0` 分支 |
| 2 | Git commit SHA | ✅ | 见 `TEST_REPORT.md` §11 |
| 3 | 测试 APK | ✅ | `dist/Android-MCP-Hub-Gate1-debug.apk` |
| 4 | APK SHA-256 | ✅ | `2467a0348696fd94170f0db86f829d43beeb6bfb6286aba4b31adad80b8c868b` |
| 5 | Cloudflare 部署配置 | ✅（**未部署**） | `worker/wrangler.jsonc` + `docs/GATE1_DEPLOYMENT.md` |
| 6 | 部署操作说明 | ✅ | `docs/GATE1_DEPLOYMENT.md`（§6.0 是一条命令的自检，§6 是逐条 curl，§5.1 是手机端准备） |
| 7 | ChatGPT MCP 接入说明 | ✅（**从未真实执行过，见文首状态**） | `docs/GATE1_CHATGPT_SETUP.md`（含六个 URL 对应关系、官方出处、同意页行为、六项验收、症状→原因排障表） |
| 8 | Gate 1 验收报告 | ✅（本文） | `docs/GATE1_ACCEPTANCE.md` |
| 8b | 部署后自检工具（本文新增，非任务书要求） | ✅ 已自检 | `tools/gate1-smoke.mjs` + `tools/gate1-smoke-selftest.mjs` |
| 9 | 已知问题清单 | ✅ | `docs/GATE1_SECURITY.md` 第 6 节（G1-K1…G1-K6）+ `TEST_REPORT.md` §8 |
| 10 | 回滚与资源删除说明 | ✅ | `docs/GATE1_DEPLOYMENT.md` 第 7、8 节 |
