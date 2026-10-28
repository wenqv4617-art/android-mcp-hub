# Universal MCP Gateway V1

## 审计基线（2026-10-28）

仓库 `wenqv4617-art/android-mcp-hub`。空工作区获取现有仓库后检查：默认分支 `gate0` HEAD `39ef38eec151b4bb21b4e6cac933ec5221a12302`；后续功能分支 `feat/gate3-mcp-client` HEAD `f7487e65269528b6268c65b8674c04f7ff0e6d84` 已包含 Gate 2、2.5、UI 和 Gate 3，故本轮从后者创建 `feat/universal-mcp-gateway`。工作区干净，无 AGENTS.md。

现有 Worker 是 MCP Server 与 Android 设备连接网关；`/mcp` 提供有状态 Streamable HTTP，`McpSession` 保存协议会话，`PhoneSession` 管理 phone-link-v1 WebSocket、凭据指纹、请求配对、超时和能力白名单。`OAuthStore` 提供现有 OAuth/PKCE。原四个原生读取工具及手机侧两个聚合工具保持原路径。此前第三方 MCP 客户端与授权存储位于 Android，本轮不改它们。

已有配置无 D1/KV/R2，采用 DO 强一致存储；通用网关增加同一个 Worker 内的 `GatewayStore`（迁移 v3-gateway），复用 DO，避免引入平行服务。部署工作流仅手动触发且本分支不在可部署分支列表。

## 实现边界

- 下游仍为 `https://<existing-worker>/mcp`：initialize、通知、ping、tools/list、tools/call、DELETE 会话终止。GET 返回 405，不提供主动列表变更通知，客户端需重新 list。
- 上游采用仓库固定的官方 MCP SDK 1.32.1（v1 维护线），支持 Streamable HTTP 与显式选择的旧 HTTP+SSE；不在失败调用后自动回退/重放。
- 每次调用新建独立 SDK 会话，重新读取上游全部工具页并核对授权指纹、校验参数，避免凭据或会话串用户。下游无需知道上游 URL/header。
- 工具映射 `ext_<service-id>_<sha256(original-name)[0:24]>`，最长 61 字符；服务 ID 与 owner 不可转移，修改配置生成新 revision 并撤销旧授权。保留描述与输入/输出 Schema；上游 annotations 不产生授权。
- tools/list 使用管理端探测时持久化的目录快照；启停/撤权立即生效，目录需再次探测刷新。调用始终检查实时定义，改变后拒绝，须刷新并重新授权。
- 新客户身份 `client:<id>` 仅可调用逐项授权的远程工具，不能访问 Android 工具。旧静态凭据身份 `legacy:owner` 与现有 OAuth 身份 `oauth:<client_id>` 保留原有 Android 能力；通用远程工具仍须显式逐项授权。
- 独立客户端记录包含 userId/charId，服务 ownerId 必须等于 userId；每个 Char 使用独立客户端与令牌，可独立撤销。现有 OAuth 同意页仍为单拥有者，不能作为 Glimmer 多用户登录系统。

## 安全与限制

默认 `GATEWAY_ENABLED=false`。管理 API 另用 `GATEWAY_ADMIN_TOKEN`，MCP 令牌不能管理服务；同源 Origin 检查，未提供跨域 CORS。客户端 token 只保存 SHA-256，推荐 `hub_client_` 加 32 字节随机串；所有凭据均由后端生成和保管。

上游认证使用 Worker secret `GATEWAY_UPSTREAM_SECRETS`，JSON 键为 `ownerId/secretRef`，值为完整 Authorization 值。服务只存引用，不向前端返回上游密钥，也不接受任意客户端 Authorization 转发。无上游 OAuth 自动授权/刷新，需运维管理凭据；不同用户必须使用各自的引用。上游工具级错误统一脱敏，响应/目录若包含配置的上游凭据则拒绝。

SSRF：运维配置 `GATEWAY_ALLOWED_URLS` 精确端点允许列表（逗号分隔、无 query/hash），生产仅 HTTPS/443、无 URL 凭据、禁止 IP 字面量和本地域名。每次实际 fetch 检查端点及同源，A/AAAA 通过固定 Cloudflare DoH 检查，任何非公网单播 IP 或解析错误均拒绝；所有重定向拒绝。旧 SSE 的 message 端点也必须逐个批准，允许协议生成的 query。**Workers fetch 不能绑定到已校验的 DNS IP，DoH 与 fetch 存在解析时间差；严格允许列表是最终信任边界，只能批准自己控制或信任其 DNS 的目标，不能开放给任意用户自助添加域名。** 更强的网络层 DNS 固定/出口代理尚未实现。

仅测试环境 `ENVIRONMENT=gateway-local-test` 允许明确列入列表的 `127.0.0.1` HTTP 端点；部署配置不设置此值。

请求体 64 KiB（包括 chunked），单上游响应/最终结果 1 MiB，目录最多 256 工具/16 服务；持久化总注册表 120 KiB，超限明确拒绝。全局最多 8 个网络操作、每客户端 2 个工具操作；超时每服务 100–30000ms。审计最近 200 条，仅标识、时间、工具名和固定结果类别，不保存参数、返回值、原始异常或凭据。开始审计落盘失败则不执行。

工具授权的 `mode=confirm` 要求运维/可信后端明确批准具体参数并签发 60 秒、一次性票据，通过 tools/call 的 `_meta["hub/confirmation"]` 提交。消费与开始审计在同一个存储事务；调用超时不退还票据，不自动重试。`mode=read` 是运维明确认可该工具可无确认执行，不依据上游自报只读标记。确认后再次主动申请新票据仍可再次执行，V1 没有跨票据业务幂等键。已发出的操作不能被撤权追溯取消。

## 管理界面

启用后访问 `https://<existing-worker>/gateway`。沿用现有 Android 的紫色主题 Token；可新增/编辑服务、启停、测试连接、查看原始工具 Schema、创建身份/生成或轮换下游令牌、逐项授权/撤权，以及查看最近脱敏错误与审计。上游仅填写 Secret 引用，不提供密钥输入框。管理令牌仅在当前页面内存，不使用 localStorage/cookie。所有上游文字通过 textContent 显示，静态资源同源且采用严格 CSP；无内联事件、无 innerHTML。

## 管理 API

`POST /gateway/admin`，JSON，独立 Bearer 管理凭据。未启用或未配置管理凭据时 404。

| action | 参数 / 行为 |
| --- | --- |
| list | 返回服务、缓存工具、客户端（无令牌摘要）与最近审计 |
| put-service | id, name, ownerId, url, transport, secretRef?, timeoutMs?, enabled；更新会清空旧目录并撤销该服务授权 |
| set-enabled | id, enabled |
| test | id；连接/发现并持久化目录，返回 tools 与 fingerprints，错误仅固定类别 |
| issue-client-token | clientId；后端生成 256-bit 随机客户端令牌，仅本次返回；轮换立即拒绝旧令牌 |
| put-client | id, userId, charId, enabled, tokenSha256?；ID/身份绑定不可转移；停用即时撤销 |
| grant | clientId, serviceId, tool, fingerprint, mode(read/confirm)；指纹来自 test，逐项授权 |
| revoke | clientId, serviceId, tool |
| confirm | clientId, name（下游命名）, arguments；返回一次性 confirmation 与 expiresAt |

既有 OAuth CIMD（URL 形式的 client_id 元数据）也纳入 SSRF 加固：仅允许 `OAUTH_REDIRECT_ALLOWLIST` 内的确切主机、HTTPS、DNS 公网检查、无重定向、64 KiB 元数据上限；不影响 DCR 与设备令牌路径。真实 CIMD 公网拉取仍待云端验收。

## 验证

`npm run typecheck`、`npm test`（含 gateway.test.ts 真实 SDK/HTTP 测试）、`npm run e2e`（既有 Android HTTP/WebSocket 回归）、`npx wrangler deploy --config worker/wrangler.jsonc --dry-run`。

本地测试中测试服务器用官方 Server/StreamableHTTPServerTransport/SSEServerTransport；下游与上游均有真实 TCP/HTTP，Worker 与 DO 用既有 Node shim。它不等于 Cloudflare 部署验证，也不等于 Android 真机或 ChatGPT 官端验收。

## ChatGPT 与 Glimmer

ChatGPT 候选接入 URL 为 `https://android-mcp-hub.causticrry.workers.dev/mcp`（沿用既有部署地址；本轮代码未部署到该地址）。协议为 Streamable HTTP，复用现有 OAuth 发现、DCR、PKCE S256、受众与令牌撤销；通用工具需为 `oauth:<client_id>` 创建拥有者身份并逐项授权，不能因为完成 OAuth 就获得新服务工具。真实 ChatGPT 客户端兼容性尚未验收，不能宣称可直接上线接入。

Glimmer 仓库未修改。后续由自己的后端鉴别真实用户/Char，再用可信管理面 provisioning 或预置独立 `client:<id>`，逐项批准工具并保管自己的下游令牌。Hub 不信任客户端自报 userId/charId，也不接受请求头直接选择他人身份。每个用户使用自己的上游凭据 Secret 引用；使用 /mcp 的官方 SDK 发起调用，写操作另经可信用户确认流程申请一次性票据。面向公众开放前仍需 Glimmer 后端的用户登录、拥有权校验、确认 UI、速率/配额/预算策略与托管凭据生命周期；现有管理令牌不得交给普通用户。

## 本轮验证记录

- 基线：379 条 Worker 测试、102 项原有真实 HTTP/WebSocket e2e 均通过。
- 第一检查点：419 条（含 40 条新网关协议/安全测试）与 102 项 e2e 均通过；提交内容树 `3b25b7ae0a761dbd8df9185bb0627d670b5e5d59`。
- 完整 Worker 验证 429 条通过（含 OAuth CIMD 安全用例）；新增网关专项已扩展至 49 条，通过；覆盖初始化和调用超时、断连/重定向/超限、分页、权限/身份/会话隔离、原生 Android 读取、配置持久化、停用/撤权、令牌轮换和默认关闭。
- `npm run test:gateway:workerd`：11 项通过，真实 workerd + DO SQLite + 双向真实 HTTP + 官方 SDK；包括 OAuth/DCR/PKCE 发令牌后调用远程工具，以及并发确认票据只执行一次。此脚本已加入原手动检查 CI；本轮没有触发远程 CI。
- 管理界面已通过临时 JSDOM 交互检查：登录、创建服务、发现目录、查看 Schema、创建客户端/生成令牌、逐工具授权、退出。真实浏览器安装包下载受当前网络环境限制，未验证布局与浏览器 CSP 执行。
- `wrangler deploy --dry-run` 打包通过，没有实际 deploy。
- 未验证：真实 Cloudflare 网络与资源、生产 Secret/DoH 出口、Android 真机、ChatGPT 官端、Glimmer 后端。既有 Android Kotlin 代码未修改，本轮没有重新构建 APK 或执行 JVM/仪器测试。

可复核 SDK 官方资料：https://ts.sdk.modelcontextprotocol.io/client （Client、StreamableHTTPClientTransport 与 SSEClientTransport），https://ts.sdk.modelcontextprotocol.io/server 。V1 复用仓库固定的 SDK 维护线，未盲目迁移 v2。

## 交付范围与后续

本轮已实现并验证：Worker 内直接连接远程 MCP 的完整闭环、两种上游传输、既有下游 Streamable HTTP/OAuth、命名路由、持久化服务管理、用户/Char/工具授权、一次性确认与审计、管理 API 和 DOM 交互、原 Android Worker/WS 调用链。原 Android Kotlin 与手机侧聚合模块零改动。

已实现但待真实环境联调：生产 HTTPS/DoH/Secret、Cloudflare 部署后迁移与运行、真实 ChatGPT connector、真实 Android 手机；管理页面真实浏览器布局/CSP 执行。工作流已加入真实 workerd 检查，但远程 CI 未触发。

仅预留/需后续产品接线：Glimmer 登录身份映射、可信用户确认 UI、ChatGPT 高风险工具确认票据交互。当前需确认的工具必须由可信后端/运维提供票据，不能假设 ChatGPT 会自动处理自定义 `_meta`。

尚未开发：第三方 OAuth 授权与刷新托管、任意域名自助注册的安全出口、业务级幂等键/计费配额、超出 120 KiB 单对象注册表的分片与长期审计导出、主动 list_changed 推送、多节点吞吐扩展。均不影响本轮受控最小闭环；默认关闭配置没有改变，未部署生产。

最终完整验证：`npm run typecheck` 通过；`npm test` 18 文件 / 429 用例全过；`npm run e2e` 102 项全过；`npm run test:gateway:workerd` 11 项全过；`wrangler deploy --dry-run` 通过。曾有一次 workerd 启动输出本地连接 reset 诊断，测试仍全部通过；单独重跑后诊断未再出现，已再次确认完整协议链成功。无 Cloudflare 账号资源变更、无 ChatGPT 官端成功声明。
