# Gate 2 真机联调清单（手机 ⇄ Worker ⇄ ChatGPT）

> 这份文档是**操作手册**，不是验收报告。里面每一条的"已验证/未验证"都写在纸上：
> 自动化跑过的是 PASS，本轮没跑的一律写 **NOT RUN**，绝不把没做过的事写成做过。
> 本轮（Gate 2）的代码改动与其审计报告见 [Gate1.5-Gate2-Core-Audit-Fixes.md](Gate1.5-Gate2-Core-Audit-Fixes.md)。

---

## 0. 一句话

把 `feat/gate2-core` 的 APK 装到手机上，手机连上 Worker，然后在 ChatGPT 里调两个工具：
`phone_ping`（连通性）与 `battery_get_status`（电池状态，只读）。
**打开手机上的"允许服务端调用本机只读能力"开关之前，第二个工具会回 `not_permitted`** —— 这是设计，不是故障。

---

## 1. 本轮产物

| 项 | 值 |
| --- | --- |
| APK | `android/app/build/outputs/apk/debug/app-debug.apk` |
| 体积 | **13 074 903 字节**（约 12.5 MiB） |
| SHA-256 | `0f723bad099780c860e4284afd011e03be69e2ce25219272aa9a24d861e0f4b8` |
| 构建方式 | `cd android && ANDROID_HOME=/opt/android-sdk ./gradlew --no-daemon :app:assembleDebug` |
| 源码版本 | 构建于 `1629442a2f79ee562f7d27fe0a9ad46528a07031`（Android 侧图标/启动页提交；APK 里就是这棵树的内容。构建后没有再动过 `android/` 的源码与资源） |
| 签名 | debug 签名（`android/app/build.gradle.kts` 的 debug 配置），**不能**用于任何发布渠道 |
| 目标设备 | compileSdk 35 / minSdk 26 / targetSdk 35；本机（OnePlus PLQ110，Android 16 / SDK 36）可装 |

APK 里可核对的内容（都是**从产物里读出来的**，不是从源码推断）：

- `python3 android/tools/verify_manifest_classes.py --apk <APK>` → Manifest 里 7 个组件类全部能在真实 dex 里找到（含本轮新增的 `com.androidmcphub.app.SplashActivity`）。
- `res/drawable-nodpi-v4/splash_poster.png`（2 311 402 字节）= 启动页海报。
- `res/mipmap-*/ic_launcher_foreground.png` 与 `ic_launcher_monochrome.png` 五档 = 本轮新图标。
- dex 里含 `battery_get_status`（Gate 2 的只读工具，`worker/src/mcp/tools.ts:130` 的 `BATTERY_GET_STATUS_TOOL`）。

---

## 2. 联调前置条件（缺一条就别往下走）

1. **Worker 已经部署，并且 `DEVICE_TOKENS` 里配了这台手机的摘要**。
   没有它，手机建连会得到 401（`no_active_credential`），App 上显示"认证失败"，且**不会**自动重连。
   生成与注入方式见 [GATE1_DEPLOYMENT.md](GATE1_DEPLOYMENT.md) §4.1 / §5.0（令牌与摘要只在你的终端里出现，**不要发到任何聊天窗口**）。
2. **`OAUTH_OWNER_SECRET` 已配**（否则整个 OAuth 面回 404），ChatGPT 才能完成授权。
3. **APK 已装到手机上**（§4.1），且手机 App 显示"已连接"。

> ⚠️ **本轮最大的卡点，先看清再动手**：`feat/gate2-core` 上的 Gate 2 代码**还没有部署到任何 Cloudflare 账户**（硬约束：本轮不部署）。
> 而仓库里的部署工作流 `deploy-worker.yml` 只在 **`gate0` 分支**上、且输入 `confirm_deploy=DEPLOY` 时才可能跑（见 §3）。
> 所以真机联调之前必须先决定走哪条路：
>
> - **A. 你来部署**，两条子路径选一条：
>   - **A1 只有手机**：手机浏览器打开 Actions 里的 `Worker manual deployment`，
>     分支选 `feat/gate2-core`、确认串填 `DEPLOY-GATE2` → **Run workflow**。
>     这是本轮新加的**临时**路径，逐步说明见 §3.1（先读再点）。
>   - **A2 有电脑**：`git checkout feat/gate2-core` 后
>     `npx wrangler@4.149.0 deploy --config worker/wrangler.jsonc`（凭据自备，仓库里不存任何 token），
>     逐步见 [Gate2-Deploy-By-Yourself.md](Gate2-Deploy-By-Yourself.md)。
> - **B. 先把 Gate 2 合并进 `gate0`**（需要你明确批准；本轮**没有**做，`gate0` 仍是 `39ef38eec151b4bb21b4e6cac933ec5221a12302`）。
> - **C. 只联调已在线的旧版本**：那样 `battery_get_status` 与撤销语义都不是本轮代码，联调结论不能代表 Gate 2。
>
> 本条状态：**NOT RUN**（本轮既没部署，也没合并）。

---

## 3. 部署工作流现状（本轮只读检查，**未触发**）

三个工作流**全部只有 `workflow_dispatch`**：push、PR、定时都不会触发它们。所以本轮推送 `feat/gate2-core` 不会产生任何 run。

| 工作流 | 触发条件 | 它做什么 |
| --- | --- | --- |
| `.github/workflows/deploy-worker.yml` | 手动；且 job 的 `if` 同时要求**分支与确认串一一配对**：`refs/heads/gate0` + `DEPLOY`，或 `refs/heads/feat/gate2-core` + `DEPLOY-GATE2`（Gate 2 联调临时路径） | `npm ci` → `npm run typecheck/test/e2e` → `wrangler deploy --dry-run` → 校验两个 secret 存在 → `wrangler deploy` |
| `.github/workflows/worker-gate1-check.yml` | 手动 | `npm ci` → 类型检查 → Worker/DO/MCP 单测 → 本地端到端 → 失败时打印定位信息 → 上传日志 artifact |
| `.github/workflows/android-debug-apk.yml` | 手动 | JDK 17 + Android SDK → 关掉 aarch64 专用的 aapt2 覆盖 → `:app:testDebugUnitTest` → `:app:assembleDebug` → `verify_manifest_classes.py --apk` → 记录 `sha256sum` → 上传 APK artifact |

`deploy-worker.yml` 里还有两条值得记住的护栏：

- **配置校验**：`worker/wrangler.jsonc` 里出现 `account_id` / `routes` / `route` / `d1_databases` / `kv_namespaces` / `r2_buckets` / `queues` / `hyperdrive` 中任何一个键就直接失败；同时要求 Worker 名是 `android-mcp-hub`、且含 `PhoneSession` / `McpSession` / `OAuthStore` 三个 Durable Object 类。
- **凭据**：需要仓库 Secret `CLOUDFLARE_API_TOKEN` 与 `CLOUDFLARE_ACCOUNT_ID`（后者必须是 32 位十六进制）。缺任一就失败，**不会**带着空凭据往下走。
- 另有 `concurrency: android-mcp-hub-manual-deploy`（`cancel-in-progress: false`）与 20 分钟超时：同时点两次不会有两个部署并行。

本轮检查方式：`ls .github/workflows/` + 逐个 `grep` 触发段与关键步骤（只读）。**没有**任何 `gh workflow run`、没有 `wrangler deploy`。状态：PASS（配置就是这样）／部署本身 **NOT RUN**。

之后为"只有手机"补了一条 **Gate 2 临时部署路径**（§3.1）：它仍然只能人工派发、仍然必须逐字输入确认串，只是把分支白名单从 `gate0` 临时扩到 `feat/gate2-core`，并用**另一个**确认串 `DEPLOY-GATE2` 与它配对。
`worker/test/security.test.ts` 的部署工作流闸门同步加了第 14 条约束（分支与确认串必须一一对应）、3 条反向用例、1 条真值表用例 —— 其中真值表那条是把 `if` 条件真的拿来求值，证明**普通 push（`inputs` 为空）永远进不了部署 job**。

### 3.1 只有手机怎么部署（方案 A1，逐步）

> 本轮**没有**执行本节。下面的按钮位置来自 GitHub 官方文档（手动运行工作流的网页路径），
> 逐字核对过；但"这一次点击"是用户自己的动作，状态记 **NOT RUN**。

**前提**：仓库 Settings → Secrets and variables → Actions 里已经有 `CLOUDFLARE_API_TOKEN` 与 `CLOUDFLARE_ACCOUNT_ID`（后者 32 位十六进制）。缺了会在 `Check required credentials` 这一步失败——**不会**带着空凭据往下走。

1. 手机浏览器里打开（私有仓库需要先登录）：
   `https://github.com/wenqv4617-art/android-mcp-hub/actions/workflows/deploy-worker.yml`
2. 页面上方/右侧找 **Run workflow** 按钮（下拉，默认收起）→ 点开。
3. **Branch** 下拉：默认是 `gate0`，**必须改成 `feat/gate2-core`**。
4. **confirm_deploy** 输入框：一字不差填 `DEPLOY-GATE2`（全大写，中间是连字符）。
   > 输入框旁边的说明文字可能还写着 `Type DEPLOY to authorize a real Cloudflare deployment`
   > —— 表单项的文案取自**默认分支（gate0）那份文件**，不一定跟着你选的分支变
   > （要是你那边显示的已经是 Gate 2 的文案，忽略这一条）。
   > 字段名只有一个 `confirm_deploy`，以本文档为准填 `DEPLOY-GATE2`。
5. 点绿色的 **Run workflow**。页面出现一个新的 run，点进去。
6. 逐步看：`Validate target and configuration` 会打印
   `::warning::Gate 2 临时路径：…` 与 `部署目标：feat/gate2-core (Gate 2 联调，临时路径) @ <sha>`；
   接着 `Install locked dependencies` → `Run Gate 1 checks` → `Wrangler dry-run` → `Check required credentials` → `Deploy Worker`。
7. 成功的样子：整条 run 绿色勾，`Deploy Worker` 日志里有 `Uploaded android-mcp-hub` 和一行 `https://<子域>.workers.dev`。
8. 哪里红了怎么读：
   | 红在这一步 | 含义 |
   | --- | --- |
   | `Validate target and configuration` | 分支或确认串填错了（例如填了 `DEPLOY`，或没改分支） |
   | `Check required credentials` | 缺 Secret，或 `CLOUDFLARE_ACCOUNT_ID` 不是 32 位十六进制 |
   | `Run Gate 1 checks` | 代码本身没过测试 → 不要部署，先看日志 |
   | `Wrangler dry-run` | `worker/wrangler.jsonc` 配置不合规（含被禁的键等） |
   | `Deploy Worker` | Cloudflare 侧拒绝（令牌权限/账号不对） |
9. 关于 **GitHub 手机 App**：GitHub 官方文档只写了网页路径的手动派发。App 里进
   `仓库 → Actions → Worker manual deployment`，**如果**上方有 `Run workflow` 按钮，填法完全一样；
   **没有这个按钮就回浏览器**（网页版最稳，必要时用浏览器菜单里的"桌面版网站/Request desktop site"）。
10. **万一 run 一上来就是 `skipped`、一个步骤都没跑**：那说明这次 run 取的是默认分支那份工作流
   （它的 `if` 只认 `gate0`）。这时**不要**反复点 —— 本次不会部署任何东西（安全的失败方向），
   把 run 页面截图发回来即可；那种情况下要走"有电脑"那条路（A2）。正常情况应能看到
   run 的 ref 是 `feat/gate2-core`，且第一个步骤打出 Gate 2 的 `::warning::`。
11. 别顺手做的两件事：**不要合并 `gate0`**；**不要**给这个工作流加 `push` 触发器（加了会被闸门测试和 review 拦下）。
    这条 Gate 2 路径是临时的：真机验证通过、代码并入 `gate0` 之后，要连同闸门里那两条配对约束与反向用例一起删掉。

---

## 4. 手机侧步骤

### 4.1 安装

```bash
# 用数据线（或 adb over Wi-Fi）：
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
# 覆盖安装旧版本也行（allowBackup=false、没有跨版本数据迁移的坑）
```

或者把 APK 传到手机上（`/app/export?path=…` 会落到"下载"目录）后点安装。
首次安装要先核对体积/SHA-256（§1），别装到半截的包。

### 4.2 首次启动会看到什么（本轮新增）

1. 点桌面图标 →（Android 12+）系统启动屏 → **满屏海报**（`splash_poster.png`，裁两侧不留边）→ 约 0.9 秒后淡入主界面。
   全程不该出现白屏/黑屏闪一下。
2. 图标本身是本轮的新设计稿（蓝白玻璃片插画）。
3. **不点图标、从通知栏进来**时**不会**看到启动页（`PendingIntent` 直接指向 `MainActivity`）。
4. 启动页停留期间旋转屏幕：不再重播一遍，直接进主界面。

### 4.3 填配置（界面里的"高级设置"）

| 字段 | 填什么 | 常见写错 |
| --- | --- | --- |
| 服务器 URL | `wss://<你的 Worker 主机>/phone/ws` | 写成 `https://…`、漏了 `/phone/ws`、只写主机名 —— 都会被表单校验拦下（`HubConfig.validate()`） |
| 设备 ID | 与 `DEVICE_TOKENS` 里的 `deviceId` **逐字符一致**（例如 `my-android-phone`） | 它是**非秘密**；写错的表现是 `no_active_credential` |
| 设备令牌 | 与摘要对应的那串明文令牌 | 只在手机上输入；App 用 Keystore 加密后存 `device_token_v1`，不落明文 |

三个 URL 别混：

| 给谁 | 形状 |
| --- | --- |
| 手机 App | `wss://<主机>/phone/ws` |
| ChatGPT 的 MCP server | `https://<主机>/mcp` |
| 浏览器/curl 健康检查 | `https://<主机>/healthz` |

### 4.4 打开"只读能力"开关（**这一步最容易漏**）

界面「高级设置」里有一个 **"允许服务端调用本机只读能力"** 开关：

- **首次安装默认是关的**（`HubPrefs.DEFAULT_CAPABILITIES_ENABLED = false`，见 `android/app/src/main/java/com/androidmcphub/app/core/HubPrefs.kt:171`；单测 `CapabilitySwitchDefaultTest` 钉住这个默认值）。
- 关着的时候，手机对**任何**工具请求都回 `not_permitted`，且不执行任何能力；`phone_ping` 这类连通性探测不受影响。
- 打开后**立即生效**，不需要重新连接（服务层的策略是每次请求现读：`android/app/src/main/java/com/androidmcphub/app/service/PhoneLinkService.kt:215` 的 `policy = { prefs.capabilitiesEnabled() }`）。

### 4.5 连接

点「连接」→ 通知栏出现前台服务通知（"正在连接 …" → "已连接到 …"）→ 界面状态变成"已连接（代次 N）"。
Android 13+ 首次会弹通知权限：**允许**才能看到通知；**拒绝**也应该照常连接成功、不崩溃。

---

## 5. ChatGPT / MCP 侧步骤

1. 在 ChatGPT 里添加 MCP server，URL 填 `https://<主机>/mcp`，认证方式选 **OAuth**（步骤与授权时会看到什么，见 [GATE1_CHATGPT_SETUP.md](GATE1_CHATGPT_SETUP.md) §2–§4）。
2. 让 ChatGPT 列出工具：应看到两个 —— `phone_ping`、`battery_get_status`。
3. 逐个调用（判定标准见 §6）。

---

## 6. 期望结果与判定

| # | 操作 | 期望 | 失败时先看哪里 |
| --- | --- | --- | --- |
| 6.1 | 调 `phone_ping`（手机在线） | `ok: true`，有 `request_id` / `rtt_ms` / `server_timestamp` / `connection_generation`；手机上"最近一次 ping"卡片同时出现这条 `request_id` | 手机不在线 → `phone_offline`；能力开关与它无关 |
| 6.2 | 调 `battery_get_status`（开关**开**） | 四个字段：`battery_level_percent`（整数或 null）、`charging`（布尔或 null）、`charging_source`（字符串，取不到是 `"unavailable"`）、`power_save_mode`（布尔或 null） | 数值应与系统设置里的电量一致；**null 是合法结果**（不编造数据），不要当成故障 |
| 6.3 | 把开关**关掉**后再调 `battery_get_status` | `not_permitted`，手机不执行任何能力 | 若仍返回数值 → 策略没生效，属于**必须记录**的缺陷 |
| 6.4 | 断网/飞行模式再恢复 | 状态进入"重连等待（第 N 次，Xs 后）"，网络恢复后立刻重连成功、退避归零 | 见 README-ANDROID §8 第 5 条 |
| 6.5 | 手机 App 里点「断开」后再调工具 | `phone_offline`（不是 500、不是超时卡死） | — |
| 6.6 | 用**错的令牌**连接 | 手机显示"认证失败"且**不**自动重连；服务端日志 `phone.auth_failed`（`token_mismatch`） | 不要在聊天里贴令牌 |

**怎么看到服务端侧证据**：`npx wrangler@4.149.0 tail --config worker/wrangler.jsonc`（或 Cloudflare 面板的实时日志）。
关键事件名：`phone.upgrade_forwarding`（转发升级到 DO）、`phone.auth_failed`（缺令牌/摘要不匹配/无活跃凭据）、
`phone_session.revoked`（真的执行了撤销）、`phone_session.revoke_stale_ignored`（**迟到的撤销通知被忽略**）、
`phone_session.message_from_revoked_connection`（已撤销连接还在发帧）、`phone.revoke_notify_failed`（通知本身没送出去）。
日志里不含令牌：地址只记主机名、异常只记固定类别。

---

## 7. 撤销链路专项（本轮修复的重点）

### 7.1 可以手工复现的时序（设备级撤销）

1. 手机连上（凭据 A），调一次 `phone_ping` 确认通。
2. 在 Worker 侧轮换/移除该设备的凭据：改 `DEVICE_TOKENS` 摘要（`npx wrangler@4.149.0 secret put DEVICE_TOKENS --config worker/wrangler.jsonc`），把旧摘要去掉。
3. 手机上点「断开」再「连接」（或等它自动重连）。
4. 期望：升级被拒（401 / `no_active_credential`），手机**不**自动重连；**若**撤销发生时还有在途的工具请求，那些请求以 `device_revoked` 结算，而不是假装成功或永远挂着。

### 7.2 "迟到通知"为什么不在真机上手工复现

本轮的缺陷是：设备**已经重新授权**之后，一条**在途的旧撤销通知**仍会终结新连接/在途请求。
这个时序要求"通知已发出"与"设备刚好完成重新授权"两个事件重叠，手工很难稳定造出来；它由 8 条定点单测覆盖：

- 迟到设备级通知：`revoked: false` / `stale: true` / `closed: 0`、`revocationsApplied` 与 `pendingRevoked` 均为 0，新凭据重连与在途 ping 都正常工作。
- 迟到凭据级通知：凭据重新启用后不误伤。
- 两条**正向对照**（设备仍撤销 / 凭据仍撤销）：`closed: 1`、`pending_failed: 1`、在途请求结算为 `device_revoked`、socket 以 4002 关闭 —— 证明"忽略迟到通知"没有把真正的撤销一起放过去。
- 通知生命周期：`ctx.waitUntil` 登记、失败不 reject、没有 `waitUntil` 时退化为后台执行。
- 源码级守卫：`worker/src/index.ts` 里 `notifyRevocationInBackground(` 恰好出现 2 次；不得再出现 `void revokePhoneDevice(` 与 `/internal/phone/revoke`。

手工联调时如果要**顺带**看一眼真实日志，就盯 `phone_session.revoke_stale_ignored`：它出现即表示"迟到通知被正确忽略"。

### 7.3 手机上会看到什么

撤销生效时，服务端先发 `error{code=device_revoked}`、随后以 **close 4002** 关闭连接；
手机端两条路都汇到同一个终态 `LinkState.Revoked`（幂等），界面显示
「凭据已被服务端撤销，已停止自动重连；请更新设备令牌后手动重连」，并且**不再**自动重连。

### 7.4 已知残余（写清楚，别当成已解决）

- **在线的连接不会因为"你改了 Cloudflare Secret"而立刻掉线**：配置层面的变更没有主动推送的钩子，
  只有下一次升级/重连（或上面的通知路径）才会把新配置带进 Durable Object。要立刻验证撤销，
  就在手机上点一次「断开 → 连接」。
- 真实 workerd 的**驱逐语义**（Durable Object 被驱逐后重启时撤销通知是否仍守着）在本环境无法验证 ——
  本容器的 workerd 永久跑不起来（`TCMalloc assumes a 48-bit virtual address space`，exit 134），
  这条一直是 **NOT RUN**，不要因为单测绿了就认为它已经验证过。

---

## 8. 图标与启动页的真机核对项（自动化覆盖不到）

新图标在三种常见启动器遮罩下的效果（左=圆形、中=圆角方形、右=设计稿自身的圆角；
这张对比图是**肉眼看图用的**，由 Pillow 从 `android/art/ic_launcher_source.png` 现场裁切生成，
不参与构建）：

![新图标在三种遮罩下的效果](assets/gate2-icon-masks.png)

逐条与 [README-ANDROID.md](../android/README-ANDROID.md) §8 的 0a–0e 对应：

| # | 看什么 | 期望 |
| --- | --- | --- |
| 8.1 | 桌面图标（新设计稿） | 满幅玻璃片插画；圆形/圆角遮罩下构图不被切；长按菜单、系统"应用信息"里一致 |
| 8.2 | 主题图标（Android 13+ 打开"主题图标"） | 变成单色剪影（两块圆角玻璃 + 轨道 + 两个光点），跟随壁纸取色，**不是**一块实心方块 |
| 8.3 | 启动页 | 系统启动屏 → 海报无缝衔接，无白/黑闪；约 0.9 秒后淡入主界面 |
| 8.4 | 通知栏点进来 | 不经过启动页 |
| 8.5 | 启动页停留期间旋转屏幕 | 不重播启动页 |

---

## 9. 本轮"跑过什么、没跑什么"

**跑过（PASS，均在本容器内、可复现）**

| 检查 | 命令 | 结果 |
| --- | --- | --- |
| 类型检查 | `npm run typecheck` | 退出码 0 |
| Worker / DO / MCP 单测 | `npx vitest run --config worker/vitest.config.ts` | **14 文件 327 通过**，退出码 0 |
| 本地端到端（真实 HTTP + WebSocket + 官方 MCP SDK） | `npm run e2e` | **78 通过 / 0 失败**，退出码 0 |
| Android JVM 单测（强制重跑） | `cd android && ANDROID_HOME=/opt/android-sdk ./gradlew --no-daemon :app:testDebugUnitTest --rerun` | **19 个测试类 / 255 条用例 / 0 failures / 0 errors** |
| Android lint（硬门禁 `abortOnError`） | `./gradlew --no-daemon :app:lintDebug` | **0 errors / 30 warnings**（多出来的那条是 `CustomSplashScreen`，接受理由见 README-ANDROID §9） |
| Manifest 组件类（真实 APK + 真实 dex） | `./gradlew --no-daemon :app:verifyManifestClasses` | 7/7 ✓ |
| APK 构建 | `./gradlew --no-daemon :app:assembleDebug` | `BUILD SUCCESSFUL`，产物见 §1 |

**没跑（NOT RUN，任何一条都不得写成 PASS）**

- **部署到 Cloudflare**（硬约束：本轮不部署）。
- **真机端到端**：本容器虽然是一台 Android 手机，但这份 APK 的联调需要"手机连着你自己的 Worker + 在 ChatGPT 里授权"，本轮没有做。
- **真实 workerd**：本容器永久不可用（见 §7.4）。
- **Compose UI 渲染**：没有 Robolectric/仪器测试，界面只能真机看。
- **撤销链路的真机手工复现**（§7.2 的迟到通知时序）。
- **ChatGPT 客户端的真实授权与工具调用**（需要线上 Worker + 你的账号）。

---

## 10. 本轮刻意不做的事

- 不合并 `gate0`、不部署 Worker、不改任何生产 Secrets / 凭据 / 自定义域名。
- 不要求你在聊天窗口里粘贴令牌、API key 或口令。
- 不为联调新增任何 Android 权限：权限清单仍是 5 条（INTERNET / ACCESS_NETWORK_STATE / POST_NOTIFICATIONS / FOREGROUND_SERVICE / FOREGROUND_SERVICE_SPECIAL_USE），`battery_get_status` 只读系统广播，不需要危险权限。
