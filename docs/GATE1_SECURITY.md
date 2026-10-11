# GATE1_SECURITY.md — Android MCP Hub Gate 1 安全说明

> 状态：**本地实现已完成，真实部署 NOT RUN。**
> 本文描述的是"代码里已经成立的性质"与"上线后必须补做的验证"两件事，两者严格分开标注。
> 与 `SECURITY.md`（Gate 0 的威胁模型，威胁编号 T1–T12）互补：本文从 **T13** 继续编号。

---

## 0. 一句话

Gate 1 把 `/mcp` 从"共享一个静态 Bearer"升级成"**完整的 OAuth 2.1 授权服务器 + PKCE + 受众绑定 + 刷新轮换**"，
所有公网入口仍然只有两条认证路径（静态 MCP Bearer / OAuth 访问令牌），
**不存在任何无认证的 MCP 工具入口**；
但因为**还没真实部署过**，本节所有"运行期性质"结论都只能算**代码级证据 + 本地替身证据**，不等于生产环境已验证。

---

## 1. Gate 1 相对 Gate 0 新增的攻击面

| 新增面 | 路径 | 认证 | 为什么它危险 | 本项目的处理 |
| --- | --- | --- | --- | --- |
| OAuth 发现元数据 | `/.well-known/oauth-protected-resource`<br>`/.well-known/oauth-protected-resource/mcp`<br>`/.well-known/oauth-authorization-server`<br>`/.well-known/openid-configuration` | 无（**公开是规范要求**） | 泄漏 issuer、endpoint、scope | 只暴露"接通所必需"的字段；不含任何凭据、不含设备信息、不含账号 |
| 动态客户端注册 DCR | `/oauth/register` | 无（**RFC 7591 定义如此**） | 任何人都能注册客户端，客户记录会无限增长 | redirect_uri 必须过**主机白名单**；同 `client_id` 重复注册**不覆盖** `redirect_uris`；**客户端记录数上限 500（超出回 429，`MAX_CLIENTS`）**；一次最多 10 个 `redirect_uris`（`MAX_REDIRECT_URIS`）。原 G1-K1 已关闭 |
| 同意页 | `/oauth/authorize`（GET 渲染表单） | 需要**有效且未过期的一次性 `consent_id`** | 钓鱼/CSRF/点击劫持 | 表单**只**提交 `consent_id` + 口令 + 决定；`consent_id` 是 24 字节随机数且一次性；CSP `default-src 'none'`、`frame-ancestors 'none'`；**不根据 Origin/Referer 做任何信任判断** |
| 同意提交 | `/oauth/authorize`（POST） | **运维口令** `OAUTH_OWNER_SECRET` | 口令是唯一的人为闸门 | 常数时间比较；连续失败 10 次锁 5 分钟；**先 peek 再验口令最后才消费**（输错一次不作废授权请求）；**已知问题 G1-K2（限流是全局的，可被用来锁死运维）** |
| 令牌端点 | `/oauth/token` | 公开客户端（PKCE 代替密钥） | 授权码拦截、重放 | 强制 `S256`；码只能用一次；码绑定 `client_id` + `redirect_uri` + `resource`；刷新令牌**轮换**（旧的重放必然 `invalid_grant`） |
| 撤销端点 | `/oauth/revoke` | 令牌本身 | 探测探针 | 令牌不存在也回 **200**（不区分"存在/不存在"） |
| 第三个 Durable Object | `OAuthStore`（单实例 `oauth-global`） | 只有 Worker 能访问（内部路径 + 无公网路由） | 单点状态 | 只存**摘要**（`sha256Hex`），dump 存储也拿不到可用令牌；空闲清理靠 `alarm()` |

---

## 2. 两类凭据主体必须继续分离

| | 设备（手机） | MCP 客户端（ChatGPT） |
| --- | --- | --- |
| 凭据 | `DEVICE_TOKENS`：`deviceId` + `tokenSha256` | OAuth 访问令牌（`mcp_at_…`）或静态 `MCP_ACCESS_TOKEN` |
| 生效路径 | `GET /phone/ws` 的 `X-Device-Id` + `Authorization: Bearer` | `/mcp` 的 `Authorization: Bearer` |
| 谁签发 | 运维手工生成（见部署文档第 4 节） | OAuth 授权服务器；静态令牌由运维 `secret put` |
| 存哪 | Worker 环境变量（**只存摘要**） | `OAuthStore` DO（**只存摘要**） |

**不变式**：设备令牌 **不能** 用于 `/mcp`，OAuth 访问令牌 **不能** 用于 `/phone/ws`。
两者由不同的函数校验（`handlePhoneUpgrade` 的 `timingSafeEqual` vs `verifyMcpAuth`），
`worker/test/security.test.ts` 与 `worker/test/oauth.test.ts` 都覆盖了"用错令牌必 401"。

---

## 3. OAuth 实现里的安全决策（每条都写清"为什么"）

1. **未启用 OAuth 时所有 OAuth 路由回 404，不回 5xx。**
   理由：官方接入要求明写"未实现的发现路由必须 404"——回 5xx 会让 ChatGPT 连接器**永久失败**（它把 5xx 当成基础设施故障，不再重试也不回退）。
   404 的副作用是好事：`OAUTH_OWNER_SECRET` 未配置时，`/mcp` 的 401 响应**不带** `resource_metadata`
   （这正是 Gate 0.3 记录的 K4 断言 `worker/test/mcp-http.test.ts` 仍在守的；启用后新增了**必须带**的反向断言）。
2. **状态用第三个 Durable Object，而不是 KV。**
   理由：KV 是最终一致，撑不住"授权码只能用一次""刷新令牌只能用一次"这类**原子读—判—写**。
   DO 单线程串行执行天然把这三个动作变成一个事务。代价：单实例 = 单点（可接受，本轮单用户）。
3. **只存摘要。** 授权码、访问令牌、刷新令牌、客户端密钥、设备令牌全部只存 `sha256Hex`。
   收益：拿到存储快照也换不出可用令牌；令牌本身就是高熵随机串，不需要加盐慢哈希。
4. **强制 `S256`，拒绝 `plain` 与缺失。** 授权请求缺 `code_challenge` 或 `code_challenge_method != "S256"` 一律 302 回 `error=invalid_request`。
5. **受众绑定（RFC 8707 `resource`）。** 授权请求里的 `resource` 必须等于本服务的 `<origin>/mcp`，否则 302 `error=invalid_target`；
   访问令牌记录里存下 `resource`，校验时**必须逐字节相等**，否则 `insufficient_scope`/拒绝。
   收益：一个发给本服务的令牌**不能**被拿去当别的服务的凭据（防混淆代理）。
6. **`iss` 参数（RFC 9207）。** 授权响应里带 `iss=<origin>`，客户端可据此确认"这个码确实来自我以为的那台授权服务器"（防 mix-up）。
7. **同意页参数留在服务端。** 表单只提交 `consent_id`/`owner_secret`/`decision`。
   如果像常见做法那样把 `redirect_uri`/`code_challenge`/`client_id` 放隐藏字段，攻击者改一个字段就能把码送到自己站点。
8. **`consent_id` 即 CSRF 令牌。** 24 字节随机、一次性、10 分钟过期；GET 同意页本身就要求它有效。
9. **口令校验放在消费之前（peek → 验 → consume）。**
   先消费再验口令的话，运维**输错一次**就把整个授权请求作废，得回 ChatGPT 从头点一遍——把可用性问题变成了绕过动机。
10. **口令失败有节流。** 连续 10 次失败锁 5 分钟，窗口过期自动重新计数（避免把运维自己永久锁在外面）。
11. **DCR 不覆盖已有 `redirect_uris`。** 同 `client_id` 重复注册只返回已有记录。
    否则"拿同一个 client_id 再注册一次"就能把重定向目标改到攻击者站点。
12. **`redirect_uri` 双重闸。** 除了"与注册值逐字节相等"，还有主机白名单（默认只放行 `chatgpt.com`）。
    挡住"先注册一个指向攻击者站点的 redirect_uri，再骗运维点同意"。非 https 只在白名单显式含 `localhost` 时放行回环地址。
13. **接受 URL 形态的 `client_id`（CIMD）。** MCP 新规范用 CIMD 取代 DCR。
    取元数据文档时：只接受 https、禁止带凭据（userinfo）、`document.client_id` 必须**逐字节等于**请求用的 URL 本身、
    `redirect_uris` 仍要过白名单、3 秒超时、`redirect: "error"`。任一条不满足即拒绝。
14. **`/mcp` 走 OAuth 分支前先看前缀 `mcp_at_`。**
    否则任何人拿一串随机字符就能让 Worker 去敲一次 DO，等于把公开端点变成 **DO 放大器**。
15. **撤销不区分"令牌是否存在"。** 一律 200，避免 `/oauth/revoke` 变成"这个令牌还有效吗"的探测探针。
16. **CORS 只对 OAuth 端点放开，且不依赖 Origin 做信任判断。**
    OAuth 端点本来就是给别的源（ChatGPT 后端/前端）用的；安全性来自"不使用 Cookie、凭据只出现在 body 与 Authorization 头"。
    同意页则相反：`form-action 'self'`，只接受来自自己页面的提交。
17. **`/mcp` 未知方法回 HTTP 404 + JSON-RPC `-32601`。**
    这不是风格问题：实测"200 + `-32601`"会让新版官方连接器判定"服务器实现了一个我们不认识的方法"从而**永久失败**，只有 404 才会回退到 `initialize`。

---

## 4. 威胁清单（从 T13 续编）

| 编号 | 威胁 | 现状 | 证据 / 说明 |
| --- | --- | --- | --- |
| T13 | 令牌出现在 URL 里 | **已防** | 授权码会出现在 `redirect_uri` 的 query（规范如此，且码一次性 + PKCE 绑定）；**访问令牌/刷新令牌从不进 URL**，只在 JSON body 或 `Authorization` 头 |
| T14 | 开放重定向 | **已防** | `checkRedirectUri`：注册时 + 授权时两道；禁 userinfo/fragment；主机白名单；非 https 仅回环 |
| T15 | 授权码被第三方截获 | **已防（弱）** | 强制 S256 PKCE + 码绑定 `client_id`/`redirect_uri` + 一次性。**但码本身在浏览器重定向里明文可见**——这正是 PKCE 存在的理由 |
| T16 | 跨客户端令牌混用 | **已防** | 码记录带 `clientId`；换令牌时比对；刷新令牌带 `clientId`，A 的刷新令牌拿到 B 必 `invalid_grant`（oauth.test.ts 有专条） |
| T17 | 同意页 CSRF / 伪造表单 | **已防** | `consent_id` 一次性随机、必须先在服务端存在；CSP + `form-action 'self'`；不信任 Origin/Referer |
| T18 | 暴力猜 `OAUTH_OWNER_SECRET` | **已缓解** | 常数时间比较 + 10 次失败锁 5 分钟；口令由运维用高熵随机串生成 |
| T19 | 未授权者注册恶意客户端 | **部分防** | DCR 本来就匿名（规范如此）；恶意客户端**拿不到**任何东西，除非运维在同意页上亲手批准。真正的风险是**存储增长** —— 已用 `MAX_CLIENTS=500` / `MAX_REDIRECT_URIS=10` 挡住 |
| T20 | 用随机 Bearer 放大 DO 调用 | **已防** | `/mcp` 只有 `mcp_at_` 前缀才去敲 DO；静态令牌路径不碰 DO |
| T21 | 刷新令牌重放 | **已防** | 轮换 + 读出即删；旧令牌重放 `invalid_grant` |
| T22 | 混淆代理（拿本服务的令牌去用别的服务） | **已防** | `resource` 参数 + 令牌记录里的 `resource` 逐字节校验 |
| T23 | 同意页 XSS | **已防** | 页面零脚本；`escapeHtml`（`&` 最先替换）；CSP `default-src 'none'`；`client_name` 截断到 200 字符 |
| T24 | 点击劫持（把同意页塞进 iframe） | **已防** | `Content-Security-Policy: frame-ancestors 'none'` |
| T25 | 客户端密钥时序侧信道 | **已防** | `timingSafeEqual` 定长比较、不提前 return（与设备令牌同一套） |
| T26 | OAuth 产物进日志 | **已防（有测试）** | 日志走 `SAFE_FIELDS` 键名白名单 + 固定错误类别 + 整行凭据形状终检；`oauth.test.ts` 有一条"走完整套流程后日志里不出现 `access_token`/`refresh_token`/`code`/`OAUTH_OWNER_SECRET`" |

---

## 5. 明确**没有**做到的事（不许当成已解决）

| 项 | 状态 | 说明 |
| --- | --- | --- |
| 真实 Cloudflare 上的 OAuth 行为 | **NOT RUN** | workerd 在本容器无法运行（见 `TEST_REPORT.md` §5），全部结论来自 Node 替身 |
| ChatGPT 官方连接器的真实握手 | **NOT RUN** | 阶段 C 未执行；客户端是否回显 `client_id`、是否接受 404 回退等只有实测才知道 |
| 边缘限流 / WAF / 速率限制 | **未实现** | 只有同意页口令的失败节流。`/oauth/register`、`/mcp`、`/phone/ws` 都没有速率限制（沿用 K7） |
| 审计日志留存 | **未实现** | Cloudflare 侧 observability 打开，但没有长期留存与告警 |
| 多用户 / 多租户隔离 | **不适用** | 本轮刻意单用户、单设备 |
| 令牌撤销列表的分布式一致性 | **不适用** | 单实例 DO，天然串行 |

---

## 6. Gate 1 已知问题清单

| 编号 | 问题 | 影响 | 现在的缓解 | 建议 |
| --- | --- | --- | --- | --- |
| ~~**G1-K1**~~ **已修复** | `OAuthStore.alarm()` 只清理 `code:`/`access:`/`refresh:`/`consent:` 四个前缀，**客户端记录（`client:`）没有过期时间、也没有数量上限** | 任何人都能反复调用 `/oauth/register` 把 DO 存储撑大（费用 + 容量） | 已加 `MAX_CLIENTS = 500`：`handleClientRegister` 在**新建**记录前用 `storage.list({ prefix: PREFIX_CLIENT, limit: MAX_CLIENTS })` 探一次，到顶回 **429**（不是 503，避免被当成基础设施故障）并记 `oauth.client_register_rejected`。刻意**不淘汰最旧的记录**：被淘汰的若正是 ChatGPT 在用的那个，下次 authorize 就会带着未知 `client_id` 进来、表现为「突然要重新连」。本次不做（记为剩余风险）：`client:` 记录仍无 TTL |
| **G1-K2** | 口令失败节流是**全局单计数器**，不区分来源 IP | 攻击者故意输错 10 次，就能把运维锁在同意页外 5 分钟（自我 DoS） | 窗口 5 分钟后自愈 | 若真实暴露到公网，考虑按来源 IP/ASN 分桶，或改成指数退避而非硬锁 |
| **G1-K3** | `OAUTH_OWNER_SECRET` 是**共享秘密**，不是多因素 | 口令泄漏 = 任何人都能替运维批准任意客户端 | 用户只在自己终端配置、不进聊天、不进仓库 | 可考虑加 Cloudflare Access 或 WebAuthn（本轮不做） |
| **G1-K4** | 静态 `MCP_ACCESS_TOKEN` 路径仍然保留 | 多一条长期凭据 | 默认不配置；仅本机 e2e 用 | 真实部署时**不要**设置它 |
| **G1-K5** | 访问令牌 `expires_in` 默认 1 小时，撤销只在 `OAuthStore` 里生效 | 撤销后到下一次校验前（= 下一次请求）即失效，没有缓存，所以实际是**立即生效**；但如果以后加了边缘缓存就会退化 | 无缓存 | 永远不要缓存 `/mcp` 鉴权结果 |
| **G1-K6** | `alarm()` 的过期清理依赖 DO 被唤醒 | 长期没有请求时，过期记录会留在存储里（不影响安全，只影响容量） | `scheduleCleanup()` 在每次写入后重排 1 小时后的闹钟；DO 被驱逐后 alarm 仍会被投递 | 接受 |

---

## 7. 密钥卫生（Gate 1 增量）

**必须用 `wrangler secret put` 配置、绝不进仓库的：**

| Secret | 用途 | 缺失后果 |
| --- | --- | --- |
| `DEVICE_TOKENS` | 手机设备凭据（JSON 数组，**只放摘要**） | 手机连不上（`no_active_credential`） |
| `OAUTH_OWNER_SECRET` | 同意页口令；同时是"是否启用 OAuth"的开关 | **整个 OAuth 面回 404**，ChatGPT 无法接入 |

**可选（默认不设）：** `MCP_ACCESS_TOKEN`（本机 curl / e2e 用）。

**明确不放进仓库的：** `.dev.vars`、`.env`、`wrangler.toml` 里的明文凭据、任何 `account_id`。
`worker/test/security.test.ts` 有一条测试在扫全仓库是否存在这些形状，命中即红。

**用户侧生成方式（不要发到聊天窗口）：**

```bash
# 设备令牌
DEVICE_TOKEN=$(openssl rand -base64 32)
printf '%s' "$DEVICE_TOKEN" | sha256sum    # 摘要给 Worker，明文给手机
# 同意页口令
openssl rand -base64 32
```

---

## 8. 安全相关的测试

| 位置 | 覆盖 |
| --- | --- |
| `worker/test/security.test.ts`（31 条） | 仓库无凭据形状、无既有生产项目引用、无 D1/KV/R2/Queue 绑定、Env 只有三个 DO 绑定、日志不出现明文令牌；**工作流闸门 16 条** —— 测试类工作流必须零部署能力（触发方式 / 部署能力 / 只读权限），`deploy-worker.yml` 必须满足 13 条约束，外加工作流清单必须恰好三个文件；其中 **12 条是篡改反向测试**（改坏真实工作流后必须报错）。见 `TEST_REPORT.md` §12.13 / §12.16 / §12.17 |
| `worker/test/oauth.test.ts`（49 条） | 未启用时 8 条路由 404 + `/mcp` 401 不带 `resource_metadata`；启用后必须带；DCR 校验；同意页只提交三个字段、无 `<script`、CSP；**输错口令一次后改对仍能拿码**；码只能用一次；跨客户端拒绝；刷新轮换 + 重放拒绝；OAuth 令牌真能完成 `initialize`；伪造/无前缀/已撤销/过期/受众不符/缺 scope 一律 401；全套流程后日志无凭据；存储故障 → 503 而不是假码 |
| `worker/test/mcp-http.test.ts` | 未知方法 404 + `-32601`；`Accept`/`Content-Type` 校验；会话头校验 |
| `worker/test/phone-session.test.ts` | 重复 `request_id` 拒绝覆盖（409）；落盘永不返回时结算照常发生；DO 重建后遗留 pending 不产生虚假成功 |
| `worker/test/logging.test.ts` | 日志白名单、错误类别归一化、凭据形状终检 |

**注意**：以上全部运行在 **Node 替身**上，不是 workerd。替身能证伪的东西比真运行时**少**（详见 `TEST_REPORT.md` §3.1）。

---

## 9. 报告安全问题

本项目仍在 Gate 1 开发阶段，**没有生产部署、没有真实用户**。
发现问题的直接回报方式：在仓库开 issue，或直接告诉项目负责人。
**不要在 issue 里粘贴真实令牌、密钥、账号信息。**

---

## 10. 部署前的最后一道人工检查（**BLOCKED，等用户批准**）

真实执行 `wrangler deploy` 之前，必须由用户本人确认：

1. `DEVICE_TOKENS` / `OAUTH_OWNER_SECRET` 已用 `wrangler secret put` 注入（**不是**写进 `wrangler.jsonc`）；
2. **没有**设置 `MCP_ACCESS_TOKEN`（除非确实要用静态路径）；
3. `OAUTH_REDIRECT_ALLOWLIST` 只含 `chatgpt.com`（`localhost` 只在本机调试时需要）；
4. 账号套餐与费用已知悉（第 2 节标 NOT RUN 的那一项）；
5. 回滚与资源删除步骤已读（部署文档第 7/8 节）；
6. 同意页口令用高熵随机串生成，且没有出现在任何聊天窗口、文件或截图里。
