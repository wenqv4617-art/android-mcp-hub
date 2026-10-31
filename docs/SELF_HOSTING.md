# 自己部署 Android MCP Hub

目标：在**你自己的** Cloudflare 账户里运行一个 Worker，绑定**你自己的** Android 手机，
再用任意标准 MCP 客户端访问它。整个过程不需要把任何令牌、口令或账号信息交给仓库维护者。

本文中的命令都在**可信终端**里执行。凡是出现 `<...>` 的地方都是占位符，
不要把真实值写进仓库、截图、聊天记录或 URL 查询参数。

---

## 0. 前置条件

| 项 | 要求 | 怎么确认 |
| --- | --- | --- |
| Cloudflare 账号 | 能创建 Workers 与 Durable Objects | `npx wrangler@4.149.0 whoami` |
| Durable Objects | 推荐 SQLite 后端（本仓库的迁移使用 `new_sqlite_classes`） | 控制台 → Workers & Pages → Durable Objects |
| 域名 | **不需要**自带域名；`*.workers.dev` 即可 | 部署输出会打印地址 |
| Node | ≥ 20 | `node -v` |
| Git | 任意近期版本 | `git --version` |
| Android 构建（可选） | JDK 17 + Android SDK | `java -version` |

> Cloudflare 的套餐与 Durable Objects 计费会变化，以你账号控制台当前显示为准。
> 本部署不使用 D1、KV、R2、Queue 或任何第三方托管数据库。

---

## 1. 取代码并本地验证

```bash
git clone https://github.com/wenqv4617-art/android-mcp-hub.git
cd android-mcp-hub
npm ci

npm run typecheck
npm run test
# 可选但推荐：真 HTTP + 真 WebSocket + 官方 MCP SDK
npm run e2e
```

任何一步失败都**不要部署**。先修好本地，再动线上。

---

## 2. 登录 Cloudflare

```bash
npx wrangler@4.149.0 login
npx wrangler@4.149.0 whoami
```

确认输出里的 account 就是你打算部署到的那一个。**账号错了会在别处新建同名 Worker**，
你的手机与客户端都会连不上。

如果使用 API Token（CI 或没有浏览器），在当前终端临时设置：

```bash
export CLOUDFLARE_API_TOKEN=...      # Workers Scripts: Edit
export CLOUDFLARE_ACCOUNT_ID=...     # 32 位十六进制
```

不要把这两个值写进 `.env`、脚本或仓库。

---

## 3. 生成并注入 Secret

全部 Secret 都只在你的机器上生成，用 `wrangler secret put` 上传；
Worker 代码与仓库里没有默认值。

### 3.1 手机设备令牌 → `DEVICE_TOKENS`

每个手机一个 `deviceId` 和一枚随机令牌。`DEVICE_TOKENS` 只保存令牌的 **SHA-256 摘要**。

```bash
# 1) 生成 32 字节随机令牌（只存在当前 shell 变量里）
DEVICE_TOKEN=$(node -e "console.log(require('crypto').randomBytes(32).toString('hex'))")

# 2) 算出摘要记录（令牌只从 stdin 读，输出不含明文）
printf '%s' "$DEVICE_TOKEN" | npm run check:credential -- \
  --device-id my-android-phone --label "Pixel 8"

# 3) 把输出的 JSON 数组保存成 secret（stdin，避免进 shell history）
printf '%s' '[{"deviceId":"my-android-phone","tokenSha256":"<上一步输出的摘要>","label":"Pixel 8","createdAt":"2026-10-31T00:00:00Z","revokedAt":null}]' \
  | npx wrangler@4.149.0 secret put DEVICE_TOKENS --config worker/wrangler.jsonc
```

把 `DEVICE_TOKEN` 安全地交给手机端（例如用密码管理器），然后在 App 里填写。
**不要**把它发到聊天窗口；需要重放时重新生成并轮换。

轮换：追加一条同 `deviceId` 的新记录、给旧记录填 `revokedAt`，重新 `secret put`。
旧连接会在下一次鉴权时失效。

### 3.2 OAuth 运维口令 → `OAUTH_OWNER_SECRET`

```bash
npx wrangler@4.149.0 secret put OAUTH_OWNER_SECRET --config worker/wrangler.jsonc
```

- 这是同意页的口令，**同时是 OAuth 的开关**：不设置时整个 OAuth 面返回 404。
- 只给 OAuth 客户端使用；不要和 `GATEWAY_ADMIN_TOKEN` 复用。
- 不想用 OAuth 时可以跳过，改用 URL + Bearer Token。

### 3.3 操作台管理令牌 → `GATEWAY_ADMIN_TOKEN`

```bash
npx wrangler@4.149.0 secret put GATEWAY_ADMIN_TOKEN --config worker/wrangler.jsonc
```

用于 `https://<worker>/gateway` 操作台与全局管理 API。
它**不会**下发给任何客户端或 Android App。

### 3.4 Android 配对码 → `GATEWAY_ANDROID_PAIRING_CODE`

```bash
npx wrangler@4.149.0 secret put GATEWAY_ANDROID_PAIRING_CODE --config worker/wrangler.jsonc
```

- 长度 8–256 个字符；建议 32 字节随机串。
- 只用于「手机 → 网关」的首次配对。配对成功后手机会拿到自己的受限令牌，
  但配对码仍然有效 —— 用完后请轮换或删除这个 Secret。
- 未设置时 `/gateway/pair` 返回 404。

### 3.5 上游凭据（可选）→ `GATEWAY_UPSTREAM_SECRETS`

只有当你要接入**需要鉴权**的第三方 MCP 服务时才需要。值是 JSON：

```json
{
  "android-ab12cd34ef56/my-service": "Bearer <上游服务给你的凭据>"
}
```

键是 `ownerId/secretRef`；服务配置里只保存 `secretRef`，不会把上游凭据回传给客户端。
如果上游不需要鉴权（例如公开文档 MCP），这一项可以完全不设置。

---

## 4. 配置 `wrangler.jsonc`

编辑 `worker/wrangler.jsonc` 的 `vars`（这些不是秘密，可以进仓库 / 部署配置）：

```jsonc
"GATEWAY_ENABLED": "true",
"GATEWAY_ALLOWED_URLS": "https://your-upstream.example/mcp",
```

规则：

- 允许清单是**精确 URL**（含路径），逗号分隔，不能带 query 或 hash；
- 生产环境只接受 HTTPS / 443，拒绝 IP 字面量、本地域名与 URL 里的用户名密码；
- 不在清单里的地址即使被写进服务配置也会被拒绝；
- 每次新增上游都要改配置并重新部署，**不能**由客户端在运行时扩展。

`wrangler.jsonc` 里**不要**添加 `account_id`、`route` 或 `[env.*]`：
本仓库刻意让部署目标由命令行显式指定，避免误落到别的账号或接管线上流量。

---

## 5. 干跑与部署

```bash
npx wrangler@4.149.0 deploy --config worker/wrangler.jsonc --dry-run
```

看点：

- 脚本名是 `android-mcp-hub`（可改，但手机和客户端都要跟着改）；
- 绑定是 `PHONE_SESSION` / `MCP_SESSION` / `OAUTH_STORE` / `GATEWAY_STORE`；
- 迁移 tag `v1`、`v2`、`v3-gateway` 都会被列出。
  **已经部署过的 tag 不能修改**，只能追加新 tag。

确认无误后：

```bash
npx wrangler@4.149.0 deploy --config worker/wrangler.jsonc
```

记下输出里的 `https://<worker>.<subdomain>.workers.dev`。

---

## 6. 部署后自检

```bash
BASE=https://<worker>.<subdomain>.workers.dev

curl -sS "$BASE/healthz" | jq .
curl -sS -o /dev/null -w '%{http_code}\n' "$BASE/mcp"        # 期望 401（没有令牌）
curl -sS -o /dev/null -w '%{http_code}\n' "$BASE/gateway"    # 期望 200
```

- `/healthz` 的 `capabilities` 里应看到 `gateway_enabled: true`、
  `gateway_android_pairing_configured: true`（健康检查只回布尔值与类别，不会回显任何凭据）。
- `/mcp` 在没有任何认证路径时按失败关闭返回 503；配置了 OAuth 或静态令牌但未带令牌时返回 401。
- 打开 `/gateway`，用 `GATEWAY_ADMIN_TOKEN` 登录，确认可以添加服务、测试连接、签发令牌。

Android 端：安装 APK → 填网关地址 / 设备 ID / 设备令牌 → 服务页切「云端网关」→ 填配对码。
配对成功后，「访问令牌」卡片可以给每个客户端签发独立令牌。

---

## 7. 让客户端接入

给客户端的只有一个地址和一枚令牌（或走 OAuth）：

```
https://<worker>.<subdomain>.workers.dev/mcp
Authorization: Bearer <客户端令牌>
```

- 令牌在 Android 「服务 → 云端网关 → 访问令牌」或操作台 `/gateway` 签发；
- 明文只显示一次，Hub 只保存摘要；
- 只读令牌看不到、也调不了写操作；任何令牌都不能越过服务端 grant；
- 不同客户端用不同令牌，撤销时互不影响。

逐客户端的配置、兼容性与排障见 [CLIENT_SETUP.md](CLIENT_SETUP.md)。

---

## 8. 这个 Worker 不是公共中转服务

请默认把它当成**你自己的私有网关**：

- 不要把 `GATEWAY_ADMIN_TOKEN`、`GATEWAY_ANDROID_PAIRING_CODE` 或设备令牌发给别人；
- 不要公开分享 `/gateway` 登录后的截图（里面有服务地址与客户端 ID）；
- 需要给他人/其他应用使用时，**为每个客户端单独签发令牌**并限制 scope，
  不要共用管理令牌或配对令牌；
- 上游允许清单只应包含你信任的地址；DNS 解析与 fetch 之间存在时间差，
  严格允许清单是最终信任边界；
- 若要面向陌生人开放，需先自行补上独立的用户体系、配额与审计策略 ——
  当前实现提供的是单拥有者 + 受限客户端模型，不是多租户 SaaS。

---

## 9. 回滚与删除

**代码回滚**（Secret 不受影响）：

```bash
npx wrangler@4.149.0 deployments list --config worker/wrangler.jsonc
npx wrangler@4.149.0 rollback <deployment-id> --config worker/wrangler.jsonc
```

**只停用网关**（不改代码）：把 `GATEWAY_ENABLED` 改成 `"false"` 后重新部署，
或删除 `GATEWAY_ADMIN_TOKEN` / `GATEWAY_ANDROID_PAIRING_CODE` Secret。

**删除部署**（不可逆，先确认没有其他项目在用这个 Worker）：

```bash
npx wrangler@4.149.0 delete --config worker/wrangler.jsonc
```

> 删除 Worker 会连同 Durable Object 数据一起消失（服务配置、授权、令牌元数据、OAuth 记录）。
> 这是**不可恢复**操作，请先自行导出需要保留的信息。

---

## 10. 常见故障

| 现象 | 常见原因 | 处理 |
| --- | --- | --- |
| 部署报 `Missing entry-point` / 绑定错 | 没带 `--config worker/wrangler.jsonc` | 加上参数重试 |
| `/gateway` 404 | `GATEWAY_ENABLED` 不是 `"true"`，或 `GATEWAY_STORE` 绑定缺失 | 检查 `vars` 与 `durable_objects` |
| `/gateway/pair` 404 | 没设置 `GATEWAY_ANDROID_PAIRING_CODE` | `secret put` 后重新请求（无需重新部署） |
| 手机连不上，`/healthz` 提示没有有效设备 | `DEVICE_TOKENS` 缺失、摘要大小写/截断错误 | 用 `check:credential` 重新生成摘要 |
| `/mcp` 一直 401 | 令牌已撤销/过期，或客户端把令牌放进了 URL | 重新签发，确认走 `Authorization` 头 |
| 客户端看不到某个 `ext_*` 工具 | 没授权、服务停用、工具定义变化导致指纹失效 | 在操作台重新 test + grant |
| 上游报 `target_denied` | URL 不在 `GATEWAY_ALLOWED_URLS` 或不是 HTTPS | 改配置并重新部署 |
| 上游报 `upstream_failure` / `upstream_timeout` | 上游服务故障、网络或凭据问题 | 在操作台 `test` 查看固定错误类别 |
| 手机原生工具报 `permission_denied` | Android 系统权限未授予 | 在系统设置里授予对应权限后重试 |
| 删除类工具报 `confirmation_required` | 调用方没传 `confirm=true` | 先向用户确认，再带参数重试 |

更多错误码见 [NATIVE_TOOLS.md](NATIVE_TOOLS.md) 与 [GATEWAY_V1.md](GATEWAY_V1.md)。
