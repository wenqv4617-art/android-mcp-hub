# Gate 1 部署方案（**待批准，尚未执行**）

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
| 1 | Wrangler 配置 | ✅ 无 `account_id` / `route` / `d1_databases` / `kv_namespaces` / `r2_buckets` / `queues`；`main` 与三个 DO 类一致 | `worker/wrangler.jsonc`；`worker/test/security.test.ts` 的隔离类用例 |
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

# 2) 算出它给 Worker 用的 sha256 摘要（Worker 只存摘要，不存明文）
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

## 5. 部署命令（**在用户批准之前不要执行**）

```bash
cd /root/android-mcp-hub            # 或你本地的仓库路径

# ---- 0) 先本地全量回归（不产生任何云端资源）----
npm run typecheck
npm run test
npm run e2e

# ---- 1) 注入 Secret（不要写进任何文件、不要提交）----
npx wrangler@4.149.0 secret put DEVICE_TOKENS     --config worker/wrangler.jsonc
npx wrangler@4.149.0 secret put OAUTH_OWNER_SECRET --config worker/wrangler.jsonc
# 可选：给本机 curl 用的静态 Bearer
# npx wrangler@4.149.0 secret put MCP_ACCESS_TOKEN --config worker/wrangler.jsonc

# ---- 2) 部署 ----
npx wrangler@4.149.0 deploy --config worker/wrangler.jsonc

# ---- 3) 记下输出的 workers.dev 地址 ----
#     形如 https://android-mcp-hub.<subdomain>.workers.dev
```

> ⚠ `wrangler deploy` 是本项目**唯一**会创建云端资源的命令。执行前请再看一眼第 2 节。

---

## 6. 部署后验证（阶段 B）

把 `<WORKER>` 换成第 5 步输出里的地址（例如 `android-mcp-hub.abc.workers.dev`）。

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
```

阶段 B 的完整 10 项见 [GATE1_ACCEPTANCE.md](./GATE1_ACCEPTANCE.md)。

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

## 9. 我在批准前**不会**做的事

- ❌ 不执行 `wrangler deploy` / `wrangler secret put` / `wrangler login`
- ❌ 不创建、不修改任何云端资源
- ❌ 不修改 Cloudflare 账号配置，不开通任何付费项
- ❌ 不配置 GitHub Actions 或任何自动部署
- ❌ 不要求你在聊天里粘贴任何真实 Token / Key / 密码

## 10. 需要用户回复的内容

请确认（可以直接回复"批准部署"，或指出要改的地方）：

1. 使用**专属新 Cloudflare 账号**（与既有生产项目完全独立）—— 确认？
2. 账号是 **Free 计划**、**没有**付费订阅 —— 确认？
3. 同意创建第 1 节列出的资源（1 Worker + 3 DO 类 + 2～3 个 Secret）—— 确认？
4. 同意在本轮**不开通付费服务**、**不配自动部署** —— 确认？
5. 是否要我**同时**配置可选的 `MCP_ACCESS_TOKEN`（本机 curl 用）？还是只用 OAuth？
6. 设备 `deviceId` 想用什么值？（默认建议 `my-android-phone`，需与手机 App 里填的一致）
