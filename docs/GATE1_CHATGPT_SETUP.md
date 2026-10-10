# ChatGPT MCP 接入说明（把 `phone_ping` 接进 ChatGPT 官方客户端）

> **状态：NOT RUN。** 这份说明是按**官方规范**（出处逐条列出）加**我们自己的实现**写的操作步骤，
> 但**它还没有被真实执行过一次** —— 真实执行需要先完成 Cloudflare 部署，而部署在等用户批准。
> 所以：本文件里凡属"ChatGPT 界面会长什么样"的描述，都请当作**待验证**，
> 实际以你眼前看到的为准；我们用 `[官方规范]` / `[待验证]` 标注了每条的来源。
>
> 配套文档：[GATE1_DEPLOYMENT.md](./GATE1_DEPLOYMENT.md)（怎么部署、手机怎么装）、
> [GATE1_SECURITY.md](./GATE1_SECURITY.md)（安全设计与已知问题）、
> [GATE1_ACCEPTANCE.md](./GATE1_ACCEPTANCE.md)（阶段 C 的六项验收）。

---

## 0. 先记住三个 URL，别填错

| 你手里有什么 | 填到哪里 |
| --- | --- |
| `https://<主机>/mcp` | **ChatGPT 的 MCP server URL**（本文件的主题） |
| `wss://<主机>/phone/ws` | **手机 App 的服务器地址**（见 GATE1_DEPLOYMENT §5.1） |
| `https://<主机>/healthz` | 浏览器/curl 看的健康检查（不填进任何客户端） |

三个都是**精确路径**，不能互换。尤其是：ChatGPT 那边要的是 **`https://`** 开头的 MCP 端点，
手机那边要的是 **`wss://`** 开头、且带 `/phone/ws` 的 WebSocket 端点。

---

## 1. 前置条件（三条，缺一条就别往下走）

1. **阶段 B 已通过**：`/healthz` 回 `status=ok` 且 `config_problems` 为空、三个 DO 都在、
   未认证的 `/mcp` 回 401 且带 `resource_metadata`。
   一条命令验证：`npm run smoke -- --base https://<WORKER>`（见 GATE1_DEPLOYMENT §6.0；
   它只读、不需要你的任何秘密）。逐条原始命令见 GATE1_DEPLOYMENT §6。
2. **`OAUTH_OWNER_SECRET` 已经用 `wrangler secret put` 配好**。没有它，整个 OAuth 面回 **404**
   —— 注意是 404 不是 500，这是刻意的（见 §6 排障第 1 条）。
3. **手机 App 已经在线上**（状态显示已连接）。没有手机连着也能完成授权和工具发现，
   但 `phone_ping` 一定会返回 `phone_offline`。

---

## 2. 在 ChatGPT 里添加这个 MCP server

`[官方规范]` 出处：<https://developers.openai.com/plugins/deploy/connect-chatgpt.md>
（旧的 Developer mode 开关已经移除，现在是下面这条路径）

1. 打开 **`chatgpt.com/plugins`**；
2. 点 **加号** → **Add custom MCP server**；
3. **MCP server URL** 填：`https://<主机>/mcp`
   （是 MCP 端点本身，**不要**填 `/`，也**不要**填 `/healthz`）；
4. **认证方式选 OAuth**（下一节解释为什么只有这一个选择）；
5. 确认风险提示；
6. **Create as a plugin** —— 创建完成后它是一个「插件/连接器」，
   在插件详情页能看到 URL、授权方式、版本。

`[待验证]` 创建界面上**看不到工具清单**是预期行为：工具不是在这里配置的。
ChatGPT 连接后会自己拉取并缓存工具表；改了工具名或 schema 之后，
需要到插件详情页点 **Refresh** 才会更新。

---

## 3. 为什么认证只能选 OAuth（不是我们偷懒，是平台限制）

`[官方规范]` 出处：<https://developers.openai.com/plugins/build/auth.md>，原文：

> "ChatGPT does **not** support machine-to-machine OAuth grants such as client credentials,
> service accounts, or JWT bearer assertions, nor can it present custom API keys or
> customer-provided mTLS certificates."

`[官方规范]` 认证选项只有 **OAuth** 或 **No authentication** 两种形态。

推论很直接：连接器界面**没有** "自定义 header" 或 "静态 Bearer" 的输入框。
所以在这个项目里：

- 想用静态 `MCP_ACCESS_TOKEN` 保护 `/mcp` → **ChatGPT 用不了它**，只能选 No authentication，
  而把一个能 ping 你手机的公网 MCP 端点开放出去是不可接受的；
- 所以 **Gate 1 实现了一整套最小 OAuth 2.1 授权服务器**（`/.well-known/*`、DCR、
  PKCE S256、同意页、token、refresh 轮换、revoke），设计说明见
  [OAUTH-DESIGN.md](./OAUTH-DESIGN.md)。

> ⚠ 顺带一条：`MCP_ACCESS_TOKEN` 这条静态路径**仍然保留在代码里**，
> 但它只用于**你自己在本机用 curl 调试**。真实部署**不要配置它** ——
> 理由与风险见 GATE1_SECURITY.md 的 G1-K4。

---

## 4. 授权时会发生什么（你会看到的东西）

点完 Create 之后，ChatGPT 会去拉发现信息、注册客户端、然后把你送到我们的**同意页**。

`[官方规范]` ChatGPT 会依次用到这些东西（我们已全部实现，逐条有测试）：

| 官方要求 | 我们的端点 |
| --- | --- |
| 资源元数据（RFC 9728） | `GET /.well-known/oauth-protected-resource`（另有 `/mcp` 后缀别名） |
| 授权服务器元数据 | `GET /.well-known/oauth-authorization-server`（另有 `/openid-configuration` 别名） |
| PKCE `S256` 必须声明 | 元数据里的 `code_challenge_methods_supported` 含 `S256` |
| `resource` 参数（RFC 8707） | authorize 与 token 都接受，并绑定到 token 的 `aud` |
| DCR（RFC 7591） | `POST /oauth/register` |
| redirect URI 精确匹配 | 逐字节匹配 + 主机白名单（默认只放行 `chatgpt.com`） |
| 客户端 ID 可能是 HTTPS URL（CIMD） | 接受 URL 形态的 `client_id` |
| refresh 轮换 | `POST /oauth/token` 返还新 refresh token，旧的立刻失效 |
| 未实现的发现路由必须 404 | 未启用的 OAuth 路径一律 404，**绝不回 5xx** |

**同意页长这样**（`worker/src/oauth/pages.ts`）：标题是「授权 Android MCP Hub」，
列出**客户端名称、客户端 ID、授权后重定向到、申请的权限范围**，
然后是两个按钮「拒绝」「批准」和**一个密码框**：

> 服务拥有者口令（OAUTH_OWNER_SECRET）

**填入你在 GATE1_DEPLOYMENT §4.2 生成的那个口令**（不是设备令牌，也不是 MCP token），
点「批准」。这一页**没有任何脚本**，也不会向第三方发出请求。

几个刻意的设计，自己操作时能感觉到：

- **写错口令不用从头再来**：失败时页面显示「口令不对 · 没有批准授权。刷新这一页可以重试，
  或检查 OAUTH_OWNER_SECRET 配置。」—— 授权请求**没有被作废**（实现是 peek → 验口令 → 才 consume），
  刷新即可重试；连续输错太多次会被节流，页面换成「尝试次数过多」并给出剩余秒数。
  （这个顺序是刻意的：如果先消费再验口令，输错一次就得回 ChatGPT 从头走一遍授权。）
- **`consent_id` 本身就是 CSRF 令牌**，参数都存在服务端，URL 里不带任何敏感值。
- **批准后 token 立刻可用于收尾**：服务端只保存令牌的 **sha256 摘要**，明文只在响应里出现一次。

---

## 5. 验收：让 ChatGPT 真的 ping 一次

这是 Gate 1 的**唯一验收语句**，请原样说：

> **G老师，Ping 一下我的手机。**

要检查的是（对应 GATE1_ACCEPTANCE §6 的 C1–C6）：

| # | 看什么 | 期望 |
| --- | --- | --- |
| C1 | 授权是否完成 | 连接器状态为「已连接」 |
| C2 | 工具是否被发现 | 问「我连了哪些工具」，列出 `phone_ping` |
| C3 | 是否**真的**发起了调用 | ChatGPT 的回复里出现 `request_id` 与 `RTT`，而不是它自己编的答案 |
| C4 | 手机是否真的收到 | 手机 App 的「最近一次 Ping」记录出现新条目 |
| C5 | 结果是否真实 | 含成功/失败状态、`device_id`、`request_id`、RTT、连接代次 |
| C6 | 双方 `request_id` 是否一致 | ChatGPT 回复里的 `request_id` **逐字符等于**手机上那一条 |

`phone_ping` 的入参（`worker/src/mcp/tools.ts`）：`device_id`（可省）、
`timeout_ms`（50–60000，可省）、`echo`（原样回显，**不会发给手机**）。
它是**只读**工具，不会改动手机上的任何东西。

> **怎么区分"真的调用了"和"模型自己编的"**：只有真的走完闭环，
> 回复里才会带出 `request_id`（服务端生成的 UUID）和 `connection_generation`
> （那只手机的连接代次）。让手机 App 上显示的 `request_id` 与之对一下，对得上就是真的。

---

## 6. 接不上时怎么自己定位

按"卡在哪一步"找，不要凭报错猜。

| 症状 | 最可能的原因 | 怎么确认 |
| --- | --- | --- |
| 添加时就说连不上 / 插件报错 | Worker 没部署好，或 URL 填成了 `/`、`/healthz` | 浏览器直接开 `https://<主机>/healthz`，看 `status` |
| 提示**找不到授权服务器** | `OAUTH_OWNER_SECRET` 没配 —— OAuth 面整体回 **404** | `curl -si https://<主机>/.well-known/oauth-authorization-server`，404 就是没配 |
| 卡在「未授权」反复要求授权 | 口令输错太多次被节流 | 同意页会显示「尝试次数过多」与剩余秒数；等它归零 |
| 授权成功但**看不到 `phone_ping`** | 工具表是缓存的 | 插件详情页点 **Refresh**，或在 ChatGPT 里问「我连了哪些工具」 |
| 调用了但回 `phone_offline` | 手机没连上，或前台服务被系统杀了 | 看手机 App 的连接状态；`/phone/ws` 走的是 WSS，手机端 IP 变了会自动重连 |
| 调用超时（`phone_timeout`） | 手机收到了但没及时回 pong，或缺省超时太短 | 用 `timeout_ms` 显式给大一点（上限 60000）；同时看手机日志 |
| 昨天还能用，今天要重新授权 | refresh token **轮换**，旧的立刻失效；也可能是 DO 被驱逐后只清了过期记录 | 重新授权一次即可；这属于预期行为，不是故障 |
| ChatGPT 一直说「服务器故障」 | 回的是 **5xx**。按规范，5xx 会被当成基础设施故障**终止接入** | 我们的设计是未实现路径一律 404；如果你看到 5xx，请把它当 bug 报给我 |

---

## 7. 我不会（也不该）让你做的事

- ❌ **不要**把 `OAUTH_OWNER_SECRET`、设备令牌、任何 API token **贴到聊天窗口里**。
  要在可信环境用 `wrangler secret put` 配置（GATE1_DEPLOYMENT §4）。
- ❌ **不要**为了"先跑通"而把认证关掉、或把 `/mcp` 改成无认证。
- ❌ **不要**把设备令牌当成 ChatGPT 的凭据 —— 它只用于手机 ↔ Worker 那一跳，
  拿它去调 `/mcp` 会被拒（两类凭据主体是刻意分开的，见 GATE1_SECURITY §2）。
- ❌ **不要**把 `MCP_ACCESS_TOKEN` 配到真实环境（G1-K4）。
