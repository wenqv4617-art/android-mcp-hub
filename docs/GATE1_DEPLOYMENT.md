# Gate 1 部署方案（**已获批准 · 由用户自己执行**）

> **当前状态：NOT RUN。**
> 本文件是**部署前的方案与检查清单**。Gate 1 的代码修改、本地测试（阶段 A）已经完成，
> 但**没有执行过任何真实的 Cloudflare 部署**，**没有创建过任何云端资源**。
>
> 任务书「十、执行权限」要求：真实部署必须**先经用户确认**。所以这份文件的作用是
> 让用户在批准之前，能把"会创建什么、花多少钱、怎么撤"全部看清楚。
>
> 最后更新：Gate 1 部署前检查完成时（见文末「部署前检查结果」）。

---

## 0. 一句话

在**一个专属的新 Cloudflare 账号**里，创建 **1 个 Worker + 3 个 Durable Object 类 + 2～3 个 Secret**，
不创建 D1 / KV / R2 / Queue、不绑定自定义域名、不开 GitHub Actions，预计费用 **0**（免费计划内）。

---

## 1. 预计创建的资源（全部清单）

| 资源 | 名称 | 说明 |
|---|---|---|
| Worker 脚本 | `android-mcp-hub` | `worker/src/index.ts`，承载 `/healthz`、`/mcp`、`/phone/ws`、OAuth 端点 |
| Durable Object 类 | `PhoneSession` | 每台手机一个实例（`idFromName(deviceId)`） |
| Durable Object 类 | `McpSession` | 每个 MCP 会话一个实例（`idFromName(sessionId)`） |
| Durable Object 类 | `OAuthStore` | **单实例**（`idFromName("oauth-global")`），OAuth 客户端/码/令牌/同意记录 |
| Secret | `DEVICE_TOKENS` | 手机设备令牌的 **sha256 摘要**清单（JSON），**必须配置**，否则任何手机都连不上 |
| Secret | `OAUTH_OWNER_SECRET` | OAuth 同意页口令。**必须配置**，否则整个 OAuth 面回 404，ChatGPT 接不上 |
| Secret | `MCP_ACCESS_TOKEN` | 可选。给本机 `curl` / e2e 用的静态 Bearer。不配也能跑 ChatGPT（走 OAuth） |
| 变量 | `OAUTH_REDIRECT_ALLOWLIST` 等 5+1 项 | 已在 `worker/wrangler.jsonc` 的 `vars` 里，非秘密 |
| 域名 | `<worker>.<account>.workers.dev` | **使用 Cloudflare 分配的 workers.dev 子域**，不申请自定义域名 |
| Cloudflare 账号 | 专属新账号 | 与既有生产项目完全独立，不复用其 Worker / DO / D1 / OAuth / Token / Secrets / 域名 |

### 明确**不会**创建的东西

- ❌ 不使用 **D1**（任务书明令）。配置里没有任何 `d1_databases`。
- ❌ 不创建 **KV / R2 / Queues / Hyperdrive / Analytics Engine**（`worker/test/security.test.ts` 有一条测试守着这一点）。
- ❌ 不绑定 **route / 自定义域名 / zone**，不碰任何线上流量。
- ❌ 不创建 `[env.production]` 之类的环境分区 —— 只有一个目标环境，杜绝误部署。
- ❌ **不配置 GitHub Actions / CI 自动部署**，仓库里也没有 `.github/` 目录。
- ❌ 不使用既有生产项目的任何凭据或资源。

> ⚠ 关于"Durable Object 迁移里的 SQLite 是不是数据库"：**不是 D1**。
> `new_sqlite_classes` 只是声明这三个 DO 类使用 DO **自带的** SQLite 存储后端
> （随对象生命周期存在、没有独立数据库实例、没有 SQL 端点、不能从外部用 SQL 查询）。
> 这是 Cloudflare 现在**唯一允许**的 DO 存储类型，见
> Cloudflare 官方 changelog 中关于「新建 DO 命名空间必须使用 SQLite 存储后端」的那一条（见 <https://developers.cloudflare.com/changelog/>）。

---

## 2. 费用风险

| 项目 | 判断 |
|---|---|
| 是否必须开通付费计划 | **不需要**。Durable Objects 自 2025-04-07 起在 Workers **Free** 计划可用（见 <https://developers.cloudflare.com/changelog/> 中 Durable Objects on Workers Free plan 那一条）。 |
| 预计费用 | **0 元**，前提是**不开通 Workers Paid**、不使用付费附加项。 |
| 主要额度风险 | Free 计划的每日请求数、DO 请求数、DO 存储量都有上限；单用户单设备的本轮负载远低于这些上限，但如果被公网恶意刷 `/mcp`，仍可能**打满每日请求额度**（额度耗尽后是拒绝服务，**不会自动产生账单**）。 |
| SQLite 存储计费 | Cloudflare 从 2026-01 起把 DO 的 SQLite 存储纳入计量（见 <https://developers.cloudflare.com/changelog/> 中 SQLite storage billing 那一条）。**需要用户在自己的账号里确认当前计费口径**——这一条我在容器里无法验证，标为 **NOT RUN**。 |
| 需要用户确认的事 | ①账号是否为 Free 计划；②是否已绑定付款方式（即使 Free 也常要求绑卡）；③账号里**没有**任何付费订阅。 |

> 这是**唯一**可能出现意外支出的方向（顺手开了付费计划），因此部署方案里把它列成
> 用户必须自查的一项；本代理不接触账号设置，也不会替用户开通任何付费项。

---

## 3. 部署前检查结果

任务书要求检查 8 项。以下为**在本地仓库里实际执行过**的检查与结论。

| # | 检查项 | 结论 | 证据 |
|---|---|---|---|
| 1 | Wrangler 配置 | ✅ 无 `account_id` / `route` / `d1_databases` / `kv_namespaces` / `r2_buckets` / `queues`；`main` 与三个 DO 类一致。**并且真的打包成功过一次**：`wrangler deploy --dry-run` → `Total Upload: 120.35 KiB / gzip: 31.40 KiB`，三个 DO binding 全部解析到真实导出类，`DRYRUN_EXIT=0`（`--dry-run` **不上传、不创建任何资源**） | `worker/wrangler.jsonc`；`worker/test/security.test.ts` 的隔离类用例；[test-reports/wrangler-dryrun.log](../test-reports/wrangler-dryrun.log) |
| 2 | Durable Object 绑定与迁移 | ✅ 三个 binding（`PHONE_SESSION` / `MCP_SESSION` / `OAUTH_STORE`）；迁移 `v1` + 追加 `v2`，都是 `new_sqlite_classes` | 同上；`worker/test/worker-index.test.ts` 断言 `/healthz` 返回三个绑定 |
| 3 | Worker 路由 | ✅ 只有 `/healthz`、`/`、`/mcp`、`/phone/ws` 与 OAuth 精确路径集合；**无前缀通配**，无公开调试接口以外的暴露面 | `worker/src/index.ts`；`worker/test/worker-index.test.ts` |
| 4 | 环境变量与 Secrets | ✅ 敏感值**全部**走 secret，`vars` 里只有非敏感数字/清单；新增 env 字段已加入配置缓存键 | `worker/src/config.ts` 的 `cacheKey`；`worker/test/security.test.ts` 的凭据类用例 |
| 5 | 设备鉴权 | ✅ `DEVICE_TOKENS` 只存 sha256 摘要，常数时间比较；缺头/缺令牌/令牌错**一律 401 且不区分设备是否存在**（不给枚举接口） | `worker/src/index.ts` 的 `handlePhoneUpgrade`；`worker/test/worker-index.test.ts` |
| 6 | MCP 入口鉴权 | ✅ 两条路：静态 `MCP_ACCESS_TOKEN`（常数时间比较）或 OAuth 访问令牌；**两者都没配时失败关闭（503）**，不存在"无认证入口" | `worker/src/mcp/http.ts` 的 `verifyMcpAuth`；`worker/test/oauth.test.ts`、`worker/test/mcp-http.test.ts` |
| 7 | 公开调试接口 | ✅ 唯一的重置接口是 DO 内部路由 `/internal/phone/testing/reset`，**只能由 Worker 内部转发进入**，公网不可达 | `worker/src/phone/phone-session.ts` 的 `ROUTE_RESET`；`worker/src/index.ts` 的路由表 |
| 8 | 账号套餐 / 额度 / 费用风险 | ⚠️ **NOT RUN（需要用户在自己的账号里确认）** —— 见第 2 节。本代理无法访问用户的 Cloudflare 账号 | — |

---

## 4. 用户需要准备的凭据（**不要发到聊天窗口**）

任务书明令：**不得要求用户把真实 Token / API Key / 密码发到聊天窗口**。
下面每一条都请在**你自己的终端**里执行，值只出现在你的终端和 Cloudflare 里。

### 4.1 生成设备令牌（手机与 Worker 共享的秘密）

```bash
# 1) 生成一个高熵随机令牌（只在你的终端里显示一次）
DEVICE_TOKEN="$(openssl rand -base64 32 | tr -d '\n')"
echo "设备令牌（填进手机 App）：$DEVICE_TOKEN"

# 2) 让**和 Worker 完全同一份代码**把摘要算出来，并生成可以直接粘贴的那条记录
printf '%s' "$DEVICE_TOKEN" | npm run check:credential -- --device-id my-android-phone
```

第 2 步会打印一条 `DEVICE_TOKENS` 记录（**只有摘要，不回显令牌**），并做一次两条独立实现的一致性自检。
实测输出与证据见 [test-reports/credential-check.log](../test-reports/credential-check.log)。

> ⚠️ **一定要用 `printf '%s'`，不要用 `echo`。** `echo` 会在末尾多送一个换行符，于是算出来的摘要
> 和 Worker 算的**不一样** —— 症状是"手机怎么都连不上，而日志里只看到 `token_mismatch`"。
> 实测：同一条令牌，`printf '%s'` 得 `a51f0ee…9de42c`，`echo` 得 `cd38083…0d737`（见上面那份日志）。

如果确实要手算，正确写法是：

```bash
printf '%s' "$DEVICE_TOKEN" | sha256sum | awk '{print $1}'
```

`DEVICE_TOKENS` 的 JSON 形状（把 `<64位小写十六进制>` 换成上面第 2 步的输出）：

```json
[
  {
    "deviceId": "my-android-phone",
    "tokenSha256": "<64位小写十六进制>",
    "label": "gate1-test-phone",
    "createdAt": "2026-01-01T00:00:00.000Z"
  }
]
```

> `deviceId` 要和你在手机 App 里填的**完全一致**。它**不是秘密**，日志里出现是允许的。

### 4.2 生成同意页口令

```bash
# 这是 ChatGPT 走 OAuth 时，你在同意页上要输入的口令。
openssl rand -base64 24
```

> 它决定了"谁有权把这台手机交给某个 MCP 客户端"。**不要**和 4.1 的令牌用同一个值。

### 4.3 登录 Cloudflare

```bash
npx wrangler@4.149.0 login
```

> 浏览器会打开授权页。请**确认登录的是那个专属新账号**，不是既有生产项目的账号。

---

## 5. 部署命令（**由你自己执行**）

> **这一节是给你亲手跑的，我不执行。** 原因不是形式：执行 `wrangler deploy` 需要账号登录态，
> 而任务书明令「绝不要求用户在聊天窗口粘贴真实 Token / API Key / 密码」——
> 最干净的做法就是凭据从始至终只存在于你的终端和 Cloudflare 里。
> 你跑完把 `workers.dev` 地址告诉我，我用 `npm run smoke` 做**只读**自检（不需要任何秘密），然后进阶段 C。

### 5.0 先把仓库放到你信任的环境里

```bash
git clone --branch gate0 https://github.com/wenqv4617-art/android-mcp-hub.git
cd android-mcp-hub
npm ci
git log --oneline -1        # 应看到 933a915 或更新
```

> 部署用的就是这份代码。`gate0` 分支的 HEAD 就是我要你部署的那个版本 ——
> 如果你看到的是更新的提交，那是我在这之后又推了东西，请按新的提交号记录。

```bash
# 在你 clone 下来的仓库目录里执行

# ---- 0) 先本地全量回归（不产生任何云端资源）----
npm run typecheck
npm run test
npm run e2e

# ---- 1) 注入 Secret（不要写进任何文件、不要提交）----
#     DEVICE_TOKENS：粘贴 §4.1 第 2 步生成的那条 JSON 数组
#     OAUTH_OWNER_SECRET：粘贴 §4.2 生成的口令
npx wrangler@4.149.0 secret put DEVICE_TOKENS       --config worker/wrangler.jsonc
npx wrangler@4.149.0 secret put OAUTH_OWNER_SECRET  --config worker/wrangler.jsonc
# 本轮已确认**不配置** MCP_ACCESS_TOKEN（只走 OAuth）：
# 静态 Bearer 等于多留一条认证路径，且它会被 /healthz 自检要求为未配置。

# ---- 2) 部署 ----
npx wrangler@4.149.0 deploy --config worker/wrangler.jsonc

# ---- 3) 记下输出的 workers.dev 地址 ----
#     形如 https://android-mcp-hub.<subdomain>.workers.dev
```

> ⚠ `wrangler deploy` 是本项目**唯一**会创建云端资源的命令。执行前请再看一眼第 2 节。

---

### 5.1 手机端准备（把 APK 装上、把地址填对）

阶段 C 的验收要**真机**，所以这一步必须由你在手机上完成。本代理不接触你的设备。

**① 装 APK**

仓库里的交付件是 `dist/Android-MCP-Hub-Gate1-debug.apk`，
SHA-256 `2467a0348696fd94170f0db86f829d43beeb6bfb6286aba4b31adad80b8c868b`（10,318,558 字节）。
Debug 签名，安装时系统会提示"未知来源"，允许即可。装好先**核对一下校验值**，别装到旧的。

**② 在 App 里填三项**

| 字段 | 填什么 | 注意 |
| --- | --- | --- |
| 服务器 URL | `wss://<第 5 节 deploy 输出的主机>/phone/ws` | **必须是 `wss://` 且必须带 `/phone/ws` 这一截路径**。`deploy` 打印的是 `https://…workers.dev`（只有主机名），直接粘进去会被 App 拦下并提示要写到 `/phone/ws` —— 这个拦截是刻意加的，因为少写路径会以「握手失败」的形式失败，很难看出原因 |
| 设备 ID | 与 `DEVICE_TOKENS` 里的 `deviceId` **完全一致** | 例如 `my-android-phone`。它**不是秘密**，写错的表现是 `no_active_credential` |
| 设备令牌 | 4.1 生成的那个**明文**令牌 | 只填一次；之后由 Android Keystore 加密存放。**令牌与 `deviceId` 要成对**，错配的表现是 `token_mismatch` |

**③ 点「连接」，然后看这三处**

1. 连接状态变成已连接（会显示连接代次）；
2. 日志里出现 `hello` / `welcome`；
3. 断开再连一次，代次应该 +1（说明不是复用了旧的连接）。

**④ 两个已知的真机注意事项**

- **通知权限**（Android 13+）：拒绝**不影响**前台服务运行，只是通知抽屉里看不到那条常驻通知；
- 这个 App **从来没有在真机上跑过**（`TEST_REPORT.md` §6.4 全部标 NOT RUN），
  所以"装上之后到底顺不顺"这次才算第一次验证。**如果启动就闪退，请把现象告诉我** —— 那属于必须回代码查的问题，不要靠反复重装绕过。

## 6. 部署后验证（阶段 B）

把 `<WORKER>` 换成第 5 步输出里的地址（例如 `android-mcp-hub.abc.workers.dev`）。

### 6.0 先跑一条命令：`npm run smoke`

B1–B4 这 24 条期望已经写成了**可执行的断言**，不用一条条抄 curl：

```bash
npm run smoke -- --base https://<WORKER>
```

它只读、不改变任何东西，**也不需要你提供任何秘密**（凡是需要"错误凭据"的地方用的是写死的假串），
跑完给一张 `PASS/FAIL/SKIP` 表，全对时退出码 0、有 FAIL 时退出码 1。样例输出：

```
PASS  B1.2   status=ok（config_problems 为空）
PASS  B1.4   三个 Durable Object 绑定都在
PASS  B1.8   MCP 静态 Bearer **没有**配置（G1-K4：真实部署不要配）
PASS  B2.5   ★ code_challenge_methods_supported 含 S256（缺了 ChatGPT 直接拒绝接入）
PASS  B3.2   ★ 401 带 resource_metadata 指针（Gate 0.3 的 K4 已反向）
SKIP  B4.2   错误设备令牌回 401   ← Node 的 fetch 不让带 Upgrade 头，如实标 SKIP，请用下面的 curl 复核
合计：24 PASS / 0 FAIL / 1 SKIP
```

**这个脚本本身有自检**，而且是"先证明它会红"那一种：`npm run smoke:selftest` 会起一个本地假服务器，
先回放**正确**的响应证明它判 PASS，再分别把 S256、`resource_metadata`、`healthz.status` 改坏，
证明它**真的会**报 FAIL（10 条断言全过）。它的断言不是凭空写的，是照着下面这串 curl 的"期望"列抄的。

> `SKIP` 与 `FAIL` 是不同的东西：SKIP 表示"这次没验"，我们不会把它算成通过。

下面是可以逐条手动复核的原始命令（也是上面那些断言的出处）：

```bash
# B1 Worker 正常、配置无问题、三个 DO 绑定都在
curl -s "https://<WORKER>/healthz" | head -c 800
#   期望：status=ok、database="not-used"、d1_bindings=0、
#         durable_object_bindings 含 PHONE_SESSION / MCP_SESSION / OAUTH_STORE、
#         config_problems 为空数组、capabilities.oauth_authorization_server=true

# B2 发现路由可用（ChatGPT 接入的前提）
curl -s "https://<WORKER>/.well-known/oauth-protected-resource" | head -c 400
curl -s "https://<WORKER>/.well-known/oauth-authorization-server" | head -c 400

# B3 未认证的 /mcp 必须 401，且带 resource_metadata
curl -si -X POST "https://<WORKER>/mcp" \
  -H 'Accept: application/json, text/event-stream' \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}' | head -20

# B4 错误设备令牌必须 401
curl -si "https://<WORKER>/phone/ws" \
  -H 'Upgrade: websocket' -H 'Connection: Upgrade' \
  -H 'X-Device-Id: my-android-phone' \
  -H 'Authorization: Bearer wrong-token' | head -5

# B5 正确设备令牌 + 手机 App 连上之后，用手机端界面确认状态显示
#    （手机怎么装、地址怎么填，见 §5.1）
```

阶段 B 的完整 10 项见 [GATE1_ACCEPTANCE.md](./GATE1_ACCEPTANCE.md)。
**B5 之后的所有项都需要 §5.1 里的真机已经在线上**。

---

## 7. 回滚方法

**场景 A：刚部署完就发现不对，想退回上一个版本**

```bash
# 列出历史版本（拿到 Version ID）
npx wrangler@4.149.0 versions list --config worker/wrangler.jsonc

# 回滚到指定版本
npx wrangler@4.149.0 rollback <VERSION_ID> --config worker/wrangler.jsonc
```

**场景 B：想回到 Gate 0.3 的代码（没有 OAuth、只有两个 DO）**

```bash
git checkout 18f05e5           # Gate 1 的基线（Gate 0.3 代码提交）
npx wrangler@4.149.0 deploy --config worker/wrangler.jsonc
```

> ⚠ 注意：`migrations` 里的 `v2` **不能撤销**（DO 类的注册只能追加）。回滚代码不会删除
> `OAuthStore` 这个类，只是没有代码再用它。这是可接受的 —— 它不消耗资源，也不暴露接口。

**场景 C：不想让任何人再连上（"紧急断开"）**

```bash
# 最彻底：直接删掉 Worker（见第 8 节）。更轻的做法是删掉两个关键 Secret：
npx wrangler@4.149.0 secret delete OAUTH_OWNER_SECRET --config worker/wrangler.jsonc
npx wrangler@4.149.0 secret delete DEVICE_TOKENS     --config worker/wrangler.jsonc
```

删掉 `OAUTH_OWNER_SECRET` 后整个 OAuth 面回 404；删掉 `DEVICE_TOKENS` 后任何手机都无法建连。

---

## 8. 资源删除方法（彻底清理）

```bash
# 1) 删除 Worker 脚本（含三个 DO 类）
npx wrangler@4.149.0 delete --config worker/wrangler.jsonc
```

删除后请**手动确认**：

1. 打开 Cloudflare Dashboard → Workers & Pages，确认 `android-mcp-hub` 已消失；
2. 打开 Dashboard → **Durable Objects**，确认 `PhoneSession` / `McpSession` / `OAuthStore`
   三个命名空间都不再可见；**如果还能看到，手动删除它们**。
   （`wrangler delete` 会移除脚本，但 DO 命名空间的可见性/残留我不在本机验证过 —— 这一条标 **NOT RUN**，请以 Dashboard 实际显示为准。）
3. 确认账号里没有残留的 Secret（随 Worker 一起删除）；
4. 如果部署时顺手创建了 API Token，去 Dashboard → My Profile → API Tokens 撤销它。

> 本轮**不使用** D1 / KV / R2 / Queue，所以没有别的资源需要清理。

---

## 9. 我**不会**做的事（部署已获批准，这一节仍然有效）

- ❌ 不执行 `wrangler login` / `secret put` / `deploy`（改由你自己执行，见 §5）
- ❌ 不创建、不修改任何云端资源
- ❌ 不修改 Cloudflare 账号配置，不开通任何付费项
- ❌ 不配置 GitHub Actions 或任何自动部署
- ❌ 不要求你在聊天里粘贴任何真实 Token / Key / 密码

## 10. 已确认的决定（2026-… · 用户答复）

| # | 事项 | 决定 |
| --- | --- | --- |
| 1 | 谁执行部署 | **用户自己执行**（凭据只存在于用户终端与 Cloudflare；我提供逐条命令） |
| 2 | 账号与计划 | Android MCP Hub **专属新账号**（与见川记忆完全独立），**Free 计划**，不订阅任何付费服务 |
| 3 | 创建的资源 | **批准**：1 个 Worker + 3 个 DO 类 + 2 个 Secret + `workers.dev` 子域。不建 D1/KV/R2/Queue/Hyperdrive、不配 route 或自定义域名、不开自动部署 |
| 4 | 静态 `MCP_ACCESS_TOKEN` | **不配置**（只走 OAuth）。自检断言 `auth.mcp_bearer_configured` 必须为 `false` |
| 5 | 设备 `deviceId` | **`my-android-phone`**（必须与手机 App 输入框里填的完全一致） |

**下一步（等你）**：按 §4 生成两个凭据 → 按 §5 部署 → 把 `https://android-mcp-hub.<subdomain>.workers.dev` 告诉我。

**然后（我来）**：`npm run smoke -- --base <你的地址>` 做只读自检（B1–B4）→ 你去手机 App 填地址与令牌、在 ChatGPT 里加连接器（`docs/GATE1_CHATGPT_SETUP.md`）→ 阶段 C 用「G老师，Ping 一下我的手机。」验收。
