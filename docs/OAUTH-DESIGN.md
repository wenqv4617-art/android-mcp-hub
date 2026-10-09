# OAuth 2.1 接入设计（ChatGPT MCP Connector）

> **本文是设计文档，不是实现，也不代表已部署。**
> 适用范围：`/mcp` 端点对 **ChatGPT 官方客户端（MCP connector）** 的认证方案。
> 与手机 WebSocket 通道（`/phone/ws`）的认证**必须分离** —— 这是本文的核心结论之一。
>
> 现状对照：Gate 0 用「共享 Bearer Token」保护 `/mcp`，那只是本地/测试阶段的临时方案。
> 阶段 2 上线公网前必须按本文替换为 OAuth 2.1 + PKCE。

---

## 0. 证据等级约定

全文严格使用三档标签，**不要把没验证过的东西读成已验证**：

| 标签 | 含义 |
| --- | --- |
| `[官方规范]` | 有官方文档 / RFC / MCP 规范原文依据，文中给出出处 |
| `[已验证·本项目]` | 在本仓库代码、测试或配置中可直接读到、可复现 |
| `[经验判断·未验证]` | 工程推断或建议，本项目**没有**做过对照实验，落地前必须自行验证 |

本文所有 `[经验判断·未验证]` 条目在**第 9 节**集中列出，便于逐条安排测量。

---

## 1. 现状：Gate 0 的共享 Bearer Token

### 1.1 `/mcp` 入口

`worker/src/mcp/http.ts:109` 的 `verifyMcpAuth(request, env, config)` 是唯一入口守卫，
在路由分发之前无条件执行（`worker/src/mcp/http.ts:89`）。行为逐条：

1. 若 `config.hasMcpAccessToken` 为假 → **失败关闭**，返回 `503` 且不进入任何分支
   （`worker/src/mcp/http.ts:114`，message 见 `worker/src/mcp/http.ts:121`）。
   `hasMcpAccessToken` 由 env 是否为空决定（`worker/src/config.ts:192`），
   没配令牌时还会在 `/healthz` 的 `config_problems` 里留一条（`worker/src/index.ts:89`）。
2. 用正则 `/^Bearer\s+(.+)$/i` 从 `Authorization` 头取令牌（`worker/src/mcp/http.ts:129`），
   取不到就 `401`。
3. 与 `env.MCP_ACCESS_TOKEN`（`worker/src/env.ts:30`）做 `timingSafeEqual`
   （`worker/src/mcp/http.ts:136`，实现见 `worker/src/util/crypto.ts:33`），
   不等则记 `mcp.auth_failed` 并 `401`。

失败响应由 `unauthorized()` 构造（`worker/src/mcp/http.ts:144`），
响应头是 `WWW-Authenticate: Bearer realm="android-mcp-hub", error="invalid_token"`
（`worker/src/mcp/http.ts:152`）。

### 1.2 手机 WebSocket 入口

`worker/src/index.ts:122` 的 `handlePhoneUpgrade` 走一条**完全不同**的路径：

1. 要求 `Upgrade: websocket`，否则 `426`。
2. 同时读 `X-Device-Id` 与 `Authorization: Bearer`（`worker/src/index.ts:130`），缺任一即 `401`。
3. 用 `activeCredentialsFor(config, deviceId)` 取出该设备**未撤销**的凭据
   （`worker/src/index.ts:140`，实现见 `worker/src/config.ts:222`）。
   凭据模型是 `DeviceCredential`（`worker/src/config.ts:27`），**只存令牌的 SHA-256**
   （字段 `tokenSha256`，`worker/src/config.ts:29`），从不存明文。
4. 对出示的令牌算 SHA-256（`worker/src/index.ts:148`，实现见 `worker/src/util/crypto.ts:15`），
   与候选摘要做 `timingSafeEqual`（`worker/src/index.ts:149`）。
5. 通过后注入内部标记头 `X-Phone-Session-Device`（`worker/src/index.ts:160`）转发给
   `PhoneSession` DO；DO 侧缺这个头直接 `500`（`worker/src/phone/phone-session.ts:226`），
   防止绕过 Worker 直连 DO。
6. 失败响应是 `WWW-Authenticate: Bearer realm="android-mcp-hub-phone"`（`worker/src/index.ts:177`），
   且**不区分**「设备不存在」与「令牌错误」，避免枚举设备 ID（对照 `docs/PROTOCOL.md:43`）。

### 1.3 为什么这只是临时方案

`[已验证·本项目]` 以下四点都是代码里可读到的事实：

1. **单一静态凭据，无有效期。** `MCP_ACCESS_TOKEN` 是一把长期不变的共享密钥
   （`worker/src/env.ts:30`）。没有过期、没有刷新、没有逐客户端区分；
   一旦泄漏只能靠人工轮换 secret（`worker/.dev.vars.example:18` 也是这么注释的）。
2. **无法撤销单个客户端。** 令牌在服务端只有一个值，任何拿到它的人与合法客户端不可区分。
   手机侧至少还有「新增凭据 + 给旧的填 `revokedAt`」的轮换姿势
   （`worker/.dev.vars.example:23`、`worker/src/config.ts:222`），MCP 侧连这个都没有。
3. **不能用于公网/生产。** 共享密钥意味着无授权归属、无审计到「谁换的令牌」、
   无最小权限（一个令牌即全部工具）。任务书本身也要求它与手机凭据分离
   （`worker/src/env.ts:29` 的注释即此意）。
4. **ChatGPT 根本用不了它。** `[官方规范]` ChatGPT 连接器界面没有 API key / 自定义 header
   输入框，不支持 `client_credentials`、服务账号或静态 Bearer
   （出处：<https://developers.openai.com/plugins/build/auth.md>）。要带鉴权只能实现 OAuth；
   否则只能选「No authentication」，在公网不可接受。

> 因此共享 Bearer 的定位是：**本地测试与 `wrangler dev --local` 的便利开关**，
> 不是接入形态。

---

## 2. ChatGPT 官方客户端实际要求的认证形态

### 2.1 接入流程

`[官方规范]` 旧 Developer mode 开关已移除，当前流程（出处：
<https://developers.openai.com/plugins/deploy/connect-chatgpt.md>）：

1. 打开 `chatgpt.com/plugins`；
2. 点加号 → **Add custom MCP server**；
3. 填 MCP server URL（约定是 MCP 端点本身，本项目即 `/mcp`）；
4. 选认证方式；
5. 确认风险提示；
6. **Create as a plugin**。

`[官方规范]` 认证选项只有「OAuth / No authentication / 二者皆可」三种表述
（出处：<https://developers.openai.com/api/docs/guides/custom-mcp-server.md>）：

> "Authentication options include OAuth, No authentication, and OAuth or no authentication."

### 2.2 硬性要求清单

依据 MCP 授权规范（<https://modelcontextprotocol.io/specification/2025-06-18/basic/authorization.md>）
与 OpenAI 的 `plugins/build/auth.md`，以下每条都是「不做就连不上」：

| # | 要求 | 标签 |
| --- | --- | --- |
| 1 | 暴露 `GET /.well-known/oauth-protected-resource`（RFC 9728），**或**在 401 里带 `WWW-Authenticate: Bearer resource_metadata="<url>"`；文档含 `resource`、`authorization_servers`、`scopes_supported` | `[官方规范]` |
| 2 | 暴露 `/.well-known/oauth-authorization-server` 或 `/.well-known/openid-configuration` | `[官方规范]` |
| 3 | 授权服务器元数据 `code_challenge_methods_supported` **必须含 `S256`** | `[官方规范]` |
| 4 | authorize 与 token 两个请求都要接受 `resource=<MCP URL>`（RFC 8707），签发令牌的 `aud` 必须匹配 | `[官方规范]` |
| 5 | `redirect_uri` 精确匹配：`https://chatgpt.com/connector_platform_oauth_redirect`（有 `iss` 时）或 `https://chatgpt.com/connector/oauth/{callback_id}` | `[官方规范]` |
| 6 | DCR（RFC 7591）仍受支持：ChatGPT 每个 MCP 连接跑**一次**注册并复用该 client | `[官方规范]` |
| 7 | refresh token 必须支持，且**轮换**（旧的立即失效） | `[官方规范]` |
| 8 | 未实现的发现路由必须回 **404**（5xx 会被当基础设施故障并终止接入） | `[官方规范]` |
| 9 | 工具级重新授权用错误里的 `_meta["mcp/www_authenticate"]`，须同时给 `error` 与 `error_description` | `[官方规范]` |

`[官方规范]` 关于 S256 的原文（决定了第 3 条是硬门槛）：

> "`code_challenge_methods_supported`: must include `S256`. MCP servers are unsupported when their authorization server metadata omits this field or does not advertise `S256`."

`[官方规范]` 关于 DCR 的原文，且注意 MCP 规范 2026-07-28 已弃用 RFC 7591：
> "ChatGPT runs DCR once per MCP server connection, then keeps and reuses the registered OAuth client"
> 弃用原因是转向 Client ID Metadata Documents（CIMD，`client_id` 是 HTTPS URL，
> 例 `https://chatgpt.com/oauth/client.json`），但「remains available for backwards compatibility」。

---

## 3. OAuth 2.1 + PKCE 完整设计

### 3.1 端点总览

下列**路径**是开源实现命名（`[经验判断·未验证]`，路径本身官方未规定）；
**发现文档路径与 ChatGPT 回调 URL** 是固定的（`[官方规范]`）。

| 端点 | 方法 | 作用 |
| --- | --- | --- |
| `/mcp` | POST | 受保护资源。未认证 → `401` + `WWW-Authenticate` |
| `/.well-known/oauth-protected-resource` | GET | RFC 9728 资源元数据 |
| `/.well-known/oauth-authorization-server` | GET | RFC 8414 授权服务器元数据 |
| `/oauth/authorize` | GET | 授权页；渲染登录 / 同意 |
| `/oauth/authorize` | POST | 提交登录或同意（approve / deny） |
| `/oauth/token` | POST | 授权码换取 + refresh |
| `/oauth/register` | POST | DCR |

### 3.2 `/.well-known/oauth-protected-resource`（RFC 9728）

返回体必须含三个字段：

```json
{
  "resource": "https://<worker-host>/mcp",
  "authorization_servers": ["https://<worker-host>"],
  "scopes_supported": ["mcp:tools"]
}
```

**为什么必需：** ChatGPT 拿到 `401` 后**不会去猜**授权端点，它只会读 `resource_metadata`
指向的文档，再顺着 `authorization_servers` 找授权服务器元数据。缺这一跳，接入在第一步就停住。
`[官方规范]`（RFC 9728；MCP authorization 规范）。

### 3.3 `WWW-Authenticate` 必须带 `resource_metadata`

未认证的 `/mcp` 响应头应为：

```
WWW-Authenticate: Bearer realm="android-mcp-hub", resource_metadata="https://<worker-host>/.well-known/oauth-protected-resource", error="invalid_token"
```

**为什么必需：** 这是**发现入口**。`[官方规范]`（RFC 9728）明确要求用它指向资源元数据，
而不是靠「难猜的 URL」。现阶段代码**故意**没有这个指针（`worker/src/mcp/http.ts:152`，
决定写在 `worker/src/mcp/http.ts:19`），原因见第 6 节。

### 3.4 授权服务器元数据

至少公布：

- `issuer`
- `authorization_endpoint` / `token_endpoint` / `registration_endpoint`
- `response_types_supported`: `["code"]`
- `grant_types_supported`: `["authorization_code", "refresh_token"]`
- `code_challenge_methods_supported`: `["S256"]` ← **必须**
- `token_endpoint_auth_methods_supported`: `["none"]`（只支持公开客户端）
- `authorization_response_iss_parameter_supported`: `true`（RFC 9207）

**为什么必需：** 第 3 条不满足客户端直接判「不支持」；第 8 条要求**未实现**的发现路由回
`404` 而不是 5xx —— 回 5xx 会让 ChatGPT 把接入判定为基础设施故障并终止
（`[官方规范]`）。

### 3.5 authorization endpoint

请求参数：`response_type=code`、`client_id`、`redirect_uri`、`code_challenge`、
`code_challenge_method=S256`、`state`、`scope`、`resource`。

处理规则：

1. **先校验 `redirect_uri`，再决定是否渲染表单。** 必须「已注册 + 精确匹配 + 可接受」
   双重判断。`[官方规范]` RFC 6749 §3.1.2：精确匹配、不得含 fragment；
   OAuth 2.1 / RFC 9700 收紧为「仅 HTTPS，或回环地址上的 HTTP」。
   **无法安全重定向时一律渲染错误页（400），绝不 302 到未验证地址。**
2. `code_challenge` 缺失或方法非 `S256` → `302` 回客户端带 `error=invalid_request`。
3. 同意页 POST 是**全新请求**，所有授权参数只能靠 hidden fields 原样带过
   —— 覆盖 `state` / `resource` / `code_challenge` 等全部字段。`[官方规范]` RFC 8707
   要求把 `resource` 在授权时绑定进授权码，避免令牌被挪用到别的资源。
4. `[官方规范]` RFC 9207：成功与失败的重定向都应附 `iss`。

**浏览器陷阱（必读）：** 同意页 CSP 的 `form-action` **必须**包含已验证回调的 origin。
`[官方规范]` CSP Level 3 §6.1.2.4 且 MDN 明确 `form-action` **没有 `default-src` 回落**
（缺省=不限制，显式 `'self'` 就只剩同源）。`[官方规范]` 该指令是否约束表单提交**之后**的重定向
仍有争论（w3c/webappsec-csp#8），实现并不一致。若写成 `form-action 'self'`，
approve 后的 `302 → https://chatgpt.com/...` 可能被浏览器拦下，症状是
**「用户点 Approve 后页面不动、服务端一次 `/oauth/token` 都没收到」**。
修法是渲染同意页时在 `form-action` 追加**已验证回调的 origin**，其余指令（`default-src 'none'`、
`base-uri 'none'`）逐字不动。
（本条机制来自本技能参考实现，在 Chromium 上被复现过；本项目尚未自测 →
落地时按第 9 节测量。）

### 3.6 token endpoint

**必须严格绑定，不做宽容：**

1. `client_id` 或 `redirect_uri` 缺失/为空 → `invalid_request`。
2. `client_id` 与授权码记录的 `client_id` 不一致 → 记审计后 `invalid_grant`，**不消费授权码**。
3. `redirect_uri` 与**这条码绑定的** URI 比（不是与注册列表比），不等 → `invalid_grant`，**不消费授权码**。
4. PKCE：重算 `S256(code_verifier)` 与存下的 challenge 做 `timingSafeEqual`。

`[官方规范]` 依据：RFC 6749 §4.1.3（换 token 时 `redirect_uri` 必须与授权请求相同）、
§4.1.2（`client_id` 与 `code` 关联）、RFC 7636（PKCE）。

**为什么不能"容错"（`[经验判断·未验证]`，但有参考实现的回退记录）：**
接受缺失/错配的 `client_id`、或接受同客户端任一已注册 `redirect_uri`，会让一次请求同时产生
「mismatch/denied」和「ok」两条自相矛盾的审计，事后无法判断客户端到底带对了没有；
更严重的是把 `redirect_uri` 精确匹配这条不变量降级成启发式，在多 URI 客户端上正好是
code interception 的入口。原则是：**容忍传输信封（JSON / urlencoded 都接），绝不宽容安全绑定。**

### 3.7 授权码单次使用

用**守卫式 UPDATE** 实现，而不是先查后写：

```sql
UPDATE oauth_auth_codes SET consumed_at = ?1
WHERE code_hash = ?2 AND consumed_at IS NULL
RETURNING code_hash
```

只有返回行数 `=== 1` 才发牌，否则 `invalid_grant`。

**为什么必需：** `[官方规范]` RFC 9700（OAuth 2.0 Security BCP）要求 code 一次性、短 TTL。
先查后写存在竞态：两个并发换取都能读到 `consumed_at IS NULL`。
**坑：** 不要用 `meta.changes !== 1` 判重 —— 真实 D1 上触发器写入也会计入 `meta.changes`，
一旦存在可选索引，每次正常换取都会被误判为重放。必须数 `RETURNING` 的行。

TTL 参考（`[经验判断·未验证]`，参考实现取值，需本项目自行决定）：
授权码 300 s、access token 3600 s、refresh token 30 天。

### 3.8 refresh token 轮换 + 重用检测

- refresh 成功即签发新 refresh token，**旧的立刻失效**。
- 检测到已失效的 refresh token 被再次使用（重放）→ **撤销整个 token family** 并返回 `invalid_grant`。

**为什么必需：** `[官方规范]` RFC 9700 要求轮换并检测重放；MCP 规范也要求 refresh 轮换。
逼出这一条的是客户端行为：access token（本项目拟定 3600 s）过期后，ChatGPT 会自动刷新，
所以该路径**必然**会被走到。重放检测是因为 refresh token 一旦泄漏，不撤销 family 就等于
让攻击者与用户共享一个永不过期的凭据。
**客户端侧约束：** 客户端必须串行化刷新、禁止并发刷新，否则会自己触发重放检测。

### 3.9 dynamic client registration（DCR）

`POST /oauth/register` 接受 RFC 7591 客户端元数据，响应**必须**是
`token_endpoint_auth_method: "none"` 且**不签发 `client_secret`**。
若客户端声明 `token_endpoint_auth_method != "none"` → `400 invalid_client_metadata`。

**为什么必需：** `[官方规范]` ChatGPT **没有**预注册通道，它每个 MCP 连接跑一次 DCR 并复用结果；
`[官方规范]` RFC 7591 §2 `token_endpoint_auth_method` 默认为 `client_secret_basic`，
公开客户端必须显式声明 `none`。
**迁移预期：** `[官方规范]` DCR 在 MCP 规范 2026-07-28 已弃用、转向 CIMD，
`client_id` 可能是 HTTPS URL 形态。设计上**必须接受 URL 形式的 `client_id`**，否则未来切换即断连。

### 3.10 存储位置（与 Gate 0「不使用 D1」的张力）

`[已验证·本项目]` Gate 0 的隔离约束包含「没有 D1」：`wrangler.jsonc:54` 的注释说明
两个 DO 类使用 SQLite 存储后端，且 `worker/test/security.test.ts:155` 会断言
`wrangler.jsonc` 里**不得出现** `d1_databases`。同时 `worker/src/env.ts:5` 的 `Env`
被刻意写成只有两个 DO 绑定，作为编译期断言。

因此阶段 2 的持久化有两个候选，**取舍属待决事项**：

- **候选 A：复用 DO SQLite 存储。** 新增一个单例 DO（如 `OAuthState`）持有
  `oauth_clients` / `oauth_auth_codes` / `oauth_tokens` / `oauth_audit` 表。
  优点是**不引入 D1**，与 `worker/test/security.test.ts:151` 的静态检查共存。
  `[经验判断·未验证]` 单例 DO 的吞吐与并发刷新表现需实测。
- **候选 B：引入 D1。** 必须先**显式**修改 `worker/src/env.ts`（20 行契约）并放宽
  `worker/test/security.test.ts:155` 的断言 —— 这是一次有意为之的隔离边界变更，
  必须由操作者单独评审，不能顺手带上。

无论选哪个，都必须有 **append-only 审计**（近似 `safeAudit`：审计失败只打 error，
绝不让审计拖垮主流程），动作至少覆盖：登录、approve、deny、consent cookie 缺失、
token（含 `grant_type` 与 outcome）、client/redirect 错配。审计**不得**包含任何令牌明文或
`code_verifier`（对照 `worker/test/security.test.ts:187` 已有的「令牌不进日志」断言）。

---

## 4. 凭据边界：两套完全不同的凭据

**这是本文最重要的表。** ChatGPT 的 OAuth 令牌与手机设备令牌**不可互换、不可混用**，
也不能把设备令牌当 OAuth 令牌用（任务书明确禁止）。

| 维度 | ChatGPT OAuth token | 手机设备 token |
| --- | --- | --- |
| 谁持有 | ChatGPT 后端 | Android APK |
| 谁签发 | 本服务（授权服务器，阶段 2 落地） | 操作者离线生成，人工写入 secret |
| 谁校验 | `/mcp` 的认证中间件（**待实现**） | `handlePhoneUpgrade`（`worker/src/index.ts:122`） |
| 存储形态 | 服务端存哈希；下发明文给 ChatGPT | 服务端只存 SHA-256（`worker/src/config.ts:29`） |
| 有效期 | access 短（拟 1 h）+ refresh 轮换 | 长期，直到人工 `revokedAt`（`worker/src/config.ts:222`） |
| 能访问什么 | **仅** `/mcp` 的 MCP 工具 | **仅** `/phone/ws` 的升级与设备通道 |
| 传输通道 | HTTPS `POST /mcp` | WSS `/phone/ws` |
| 轮换方式 | refresh 轮换 + 重用检测 | 新增凭据 + 给旧的填 `revokedAt`（`worker/.dev.vars.example:23`） |
| 撤销粒度 | 单个 client / token family | 单条凭据（单设备） |

### 4.1 手机链路现在是什么

`[已验证·本项目]` 现状：`DEVICE_TOKENS` 是一张 JSON 凭据表（`worker/src/env.ts:32`），
解析逻辑在 `worker/src/config.ts:108`，字段模型是 `DeviceCredential`
（`worker/src/config.ts:27`）。鉴权时按 `X-Device-Id` 取该设备的全部未撤销凭据，
对出示令牌的 SHA-256 做定时安全比较（`worker/src/index.ts:148`）。
Durable Object 侧再用内部标记头二次确认请求来自 Worker（`worker/src/phone/phone-session.ts:226`）。

### 4.2 未来怎么轮换

`[经验判断·未验证]`（方向明确，但本项目未实现）：

1. **不改协议**：仍走 `X-Device-Id` + `Authorization: Bearer`（`docs/PROTOCOL.md:41`），
   避免手机端改造。
2. **重叠窗口轮换**：先新增一条同 `deviceId` 的新凭据（新 `tokenSha256`），
   等手机侧更新并成功连上一次后，再把旧凭据的 `revokedAt` 填上。
   这样任何时刻至少有一条有效凭据，不会把在线设备踢下线。
3. **凭据表进密钥管理**：`DEVICE_TOKENS` 继续只从 secret 注入，不进 `wrangler.jsonc` 的 vars
   （`worker/wrangler.jsonc:36` 已如此声明）。
4. **与 OAuth 的隔离要写成测试**：新增断言「OAuth 令牌无法通过 `/phone/ws` 鉴权」，
   反向亦然。`[经验判断·未验证]` 这类跨凭据断言是防止两套体系未来被合并的最便宜保险。

---

## 5. `/mcp` 认证中间件的目标形态（阶段 2）

`[经验判断·未验证]` 目标行为（替换 `worker/src/mcp/http.ts:109` 的 `verifyMcpAuth`）：

1. 无 `Authorization` → `401`，头里带 `resource_metadata`（见 3.3）。
2. Bearer 令牌是本服务签发的、未过期的 access token，且 `aud` 匹配本资源 → 放行。
3. 令牌无效/过期 → `401`，`error="invalid_token"`。
4. **不再读 `MCP_ACCESS_TOKEN`**。该变量可以保留为「本地开发旁路」，
   但必须由显式开关控制且**默认关闭**，绝不能在公网同时接受两种凭据
   （那等于给共享密钥留了后门）。`[经验判断·未验证]`：是否保留旁路需要在实现时明确决策。

---

## 6. 真实风险：现在故意缺 `resource_metadata`，阶段 2 必须补上

### 6.1 现状是有意为之

`[已验证·本项目]` 401 响应头是：

```
WWW-Authenticate: Bearer realm="android-mcp-hub", error="invalid_token"
```

（`worker/src/mcp/http.ts:152`）**刻意不带** `resource_metadata`，
原因写在 `worker/src/mcp/http.ts:19` 与 `worker/src/mcp/http.ts:150` 的注释里：
Gate 0 还没有授权服务器，指向一个不存在的地址只会让 ChatGPT 的接入流程
卡在更难看的地方。这个决定被测试钉住 —— `worker/test/mcp-http.test.ts:102`
断言 `expect(challenge).not.toContain("resource_metadata")`。

### 6.2 阶段 2 必须翻转这条断言

上线授权服务器时，**必须**：

1. 在 `unauthorized()`（`worker/src/mcp/http.ts:144`）里加上
   `resource_metadata="https://<host>/.well-known/oauth-protected-resource"`。
2. **同时修改** `worker/test/mcp-http.test.ts:102`：把 `not.toContain` 改为
   `toContain('resource_metadata=')`，并断言该 URL 可解析、指向的文档真的存在。
   不改测试，就是留了一条「文档说要改、测试说不许改」的自相矛盾，下一次改动会被测试挡住。

### 6.3 不补会怎样

`[官方规范]`（RFC 9728 / MCP authorization）：`resource_metadata` 是客户端发现授权服务器的
**唯一入口**。不补的后果是：

- ChatGPT 拿到 `401` 后不知道去哪授权，连接器流程**直接失败或停在错误页**；
- 这不是「降级可用」，而是**完全不可用** —— 与共享 Bearer 阶段的表现一致，
  即「静态 token 只能配合 No authentication 使用」；
- 而选 No authentication 意味着公网裸奔，不可接受。

**换句话说：现在的「故意不带」在 Gate 0 是正确的，在阶段 2 是致命的。**
这条从「正确」到「致命」的切换点，就是本文最需要被记住的一句话。

---

## 7. 后续阶段落地步骤（分阶段 + 验收判据）

> 每个阶段的判据都应是**可执行、会失败**的断言，而不是文字描述。

### 阶段 1：设计与测量（现在）

- [ ] 定稿本文；确认存储方案选 A 还是 B（第 3.10 节）。
- [ ] 测量第 9 节列出的未验证项（至少 DCR 复用与 refresh 是否回显 `client_id`）。
- **判据：** 未验证项各有「怎么测 + 测出什么」的记录；存储取舍有书面决定与理由。

### 阶段 2a：授权服务器骨架（不接 ChatGPT）

- [ ] 实现 `/.well-known/oauth-protected-resource` 与 `/.well-known/oauth-authorization-server`。
- [ ] 未实现的发现路由回 **404**（不得 5xx）。
- **判据：** 两个 well-known 路由 `200` 且 JSON 可被 schema 校验；
  `code_challenge_methods_supported` 含 `S256`；
  任意未实现发现路径返回 `404`；`/mcp` 未认证仍 `503`/`401` 行为不变。

### 阶段 2b：授权码流程 + 浏览器验证

- [ ] `/oauth/authorize`（GET/POST）、`/oauth/token`、`/oauth/register`。
- [ ] PKCE S256、code 一次性（`RETURNING` 判行）、`client_id`/`redirect_uri` 精确绑定。
- [ ] 同意页 CSP `form-action` = `'self'` + 已验证回调 origin，其余指令逐字不变。
- **判据（必须含真实浏览器）：**
  - Node 端到端脚本：register → authorize → login → consent → approve →
    断言 `302` 带 `code`/`state`/`iss` → 换 token → 调 `/mcp` → 重放 code 被拒 →
    轮换 refresh → 重放 refresh 整族被撤销 → 未认证 `401` 带 `resource_metadata`。
  - 真实浏览器（Playwright/Chromium）跑通同一路径，回调请求数 = 1、Console 无 CSP violation。
  - **Node 全绿不构成验收** —— Node `fetch` 不执行 CSP，浏览器那一跳必须单独证明。

### 阶段 2c：切换 `/mcp` 认证

- [ ] 按第 5、6 节替换 `verifyMcpAuth` 并翻转 `mcp-http.test.ts:102`。
- [ ] 移除或默认关闭 `MCP_ACCESS_TOKEN` 旁路。
- **判据：** 旧共享 Bearer 令牌**不能再**访问 `/mcp`（新增一条会失败的负向测试）；
  手机令牌也不能访问 `/mcp`；OAuth 令牌也不能升级 `/phone/ws`。

### 阶段 2d：审计与刷新长跑

- [ ] append-only 审计落地；`grant_type` 逐条可见。
- [ ] 让连接保持 ≥ 1 小时，观察首次 refresh。
- **判据：** 审计能一眼答出「码生成了几个、换取了几个、最后一次 token 请求的
  `grant_type` 与 `error`」；1 小时后无 `invalid_grant (client_id mismatch)`。

### 阶段 3：上线与运营

- [ ] 手动、有门禁的部署；回滚路径明确。
- **判据：** 健康检查 → 协议探针 → OAuth e2e → 人工连接 → 首次 refresh 后审计齐全。

---

## 8. 现在就知道的坑

1. **CSP `form-action` 会跟着重定向走**（见 3.5）。症状是「approve 后页面不动」；
   用审计把范围缩到「码已生成 + 零换取」再查 CSP，不要先怀疑服务端代码。
2. **Node 测试测不出浏览器故障。** 任何「只在 Node/Worker 里跑全流程」的验收都不足以
   证明连接器可用；CSP、cookie、`state` 编解码必须在真实浏览器过一遍。
3. **`state` 必须原样往返。** 同意页 POST 是全新请求，`state` 只能靠 hidden fields 带过。
   丢失 `state` 的客户端会**静默丢弃授权码且不报错** —— 这是「码生成了但没人换」的第二种成因，
   必须和 CSP 那一跳区分开。测试值应包含空格、`+`、`&`、`?`、`=`、非 ASCII。
4. **`meta.changes` 判重会误伤。** 见 3.7，必须数 `RETURNING` 的行。
5. **`form-action` 没有 `default-src` 回落。** 算不出安全回调 origin 时**不要**删这条指令
   （那等于完全放开表单目标），正确做法是**根本不渲染可提交的表单**，直接 `400`。
6. **DCR 的 `token_endpoint_auth_method` 默认值是 `client_secret_basic`。**
   公开客户端必须显式声明 `none`，服务端应拒绝其它值。
7. **未实现的发现路由回 5xx 会终止接入**，回 404 才会被当作「该端点不存在」继续走。
8. **refresh 时客户端是否回显 `client_id` 未经采样。** 把 token 端点收严成「refresh 必须带
   匹配的 `client_id`」后，风险在 access token 过期后的**第一次刷新**才暴露。
9. **DCR 正在被 CIMD 取代。** `client_id` 未来可能是 HTTPS URL，现在就别把
   `client_id` 当成「一串随机 ID」写死校验逻辑。
10. **文档引用悬空。** `[已验证·本项目]` `worker/src/mcp/http.ts:17` 与
    `worker/wrangler.jsonc:36` 都写着「见 SECURITY.md 第 3 节」，但仓库里**没有**
    `SECURITY.md`（仅有 `worker/test/security.test.ts`）。阶段 2 落地前应补上或修正这些引用，
    否则安全设计的权威出处无处可查。
11. **单例 DO 的并发刷新未测。** 若选存储候选 A（第 3.10 节），
    refresh 轮换涉及「读旧、写新、撤销旧」三步，需确认单例 DO 下不存在竞态。

---

## 9. `[经验判断·未验证]` 条目清单

以下条目本项目**没有**做过对照实验，落地前必须逐条安排测量：

| # | 条目 | 怎么测 |
| --- | --- | --- |
| U1 | 端点**路径**命名（`/oauth/authorize` 等）—— 官方只固定 well-known 与回调 URL | 实现后按 OpenAI 文档复核 |
| U2 | access/refresh/授权码的 TTL 取值（拟 1 h / 30 天 / 300 s） | 按实际客户端刷新节奏调整 |
| U3 | 「容忍传输信封、不宽容安全绑定」在本项目的实现取舍 | 写绑定测试：错配 `client_id`/`redirect_uri` → `invalid_grant` 且码不被消费 |
| U4 | 存储候选 A（单例 DO SQLite）的吞吐与并发刷新表现 | 压测单例 DO 的 refresh 并发 |
| U5 | 存储候选 B（引入 D1）需修改 `worker/src/env.ts` 与 `worker/test/security.test.ts:155` 的影响面 | 单独评审，不随阶段 2 顺手带上 |
| U6 | 手机设备凭据「重叠窗口轮换」的具体操作步骤 | 本地双凭据重叠连接实验 |
| U7 | 「OAuth 令牌与设备令牌互不通用」的跨凭据负向测试 | 新增断言并确认其会失败 |
| U8 | 阶段 2 是否保留 `MCP_ACCESS_TOKEN` 本地旁路 | 明确决策并写成测试（默认关闭） |
| U9 | 单例 DO 下 refresh 轮换的竞态安全性 | 并发 refresh 实验 |
| U10 | 同意页 CSP `form-action` 在本项目目标浏览器上的实际行为 | Playwright Chromium/Firefox/WebKit 对照（保留 `'self'`-only 作为 control） |
| U11 | ChatGPT 在 refresh 请求里是否回显 `client_id` | 审计区分 `grant_type=refresh_token` 的成功/失败，连接保持 ≥ 1 h |
| U12 | ChatGPT 当前是否仍对新建连接跑一次 DCR（以及何时切 CIMD） | 观察 `oauth_clients` 是否随每次连接新增 |
| U13 | 连接器请求是否带 `Origin: https://chatgpt.com` | 记录被拒请求的 `Origin` 原值（脱敏） |
| U14 | 工具数量上限、响应体上限、调用超时、工具清单缓存过期策略 | 官方未公布，用一次性最小连接器做对照实验 |
| U15 | 同意页是否需要 `frame-ancestors`（防 iframe 嵌套） | 若观察到 iframe 场景则补，并重测 `SameSite=Lax` cookie |

---

## 10. 一句话总结

ChatGPT 官方客户端**只认 OAuth**（静态凭据不可用），所以阶段 2 必须上线
OAuth 2.1 + PKCE + DCR + `resource_metadata` 发现；
**OAuth 令牌与手机设备令牌是两套完全不同的凭据**，各自签发、各自校验、各自只能访问自己的通道；
而阶段 2 的第一件事，就是把这个「故意缺失的 `resource_metadata`」补上，
并同时翻转 `worker/test/mcp-http.test.ts:102` 那条如今必须反向的断言。
