# 客户端接入与兼容性

Hub 对外只有一个标准入口：

```
https://<your-worker>.<subdomain>.workers.dev/mcp
```

协议是 **MCP Streamable HTTP**（JSON-RPC 2.0）。客户端需要支持远程 MCP；
只支持本地 stdio 的客户端必须借助它自己的远程桥接功能。

---

## 1. 两种鉴权方式

| 方式 | 适合 | 令牌位置 | 说明 |
| --- | --- | --- | --- |
| **URL + Bearer Token** | 支持自定义 Header 的客户端、脚本、CI | `Authorization: Bearer <令牌>` | Android / 操作台签发的 `hub_client_*` **只访问已授权的 `ext_*` 第三方工具**；明文只显示一次；Hub 只保存 SHA-256 摘要 |
| **OAuth 2.1 + PKCE** | 支持 OAuth 发现的客户端 | OAuth 访问令牌 | 需要配置 `OAUTH_OWNER_SECRET`；同意页由运维口令保护；可访问本机原生工具 |

**令牌永远不放在 URL、查询参数、片段或 Cookie 里。** 如果某个客户端只允许填 URL、
没有 Header/OAuth 输入框，那它就不能安全地接入本 Hub —— 不要为了“先连上”把令牌拼进 URL。

### 1.1 签发令牌

两种入口，效果相同：

1. **Android App**：服务 → 云端网关 → 访问令牌 → 签发令牌。
   - 选择 30 / 90 / 365 天或永久；
   - 选择是否允许写操作（只读令牌看不到、也调不了写工具）；
   - 明文只显示一次，点「我已保存」后从内存清除。
2. **操作台**：`https://<worker>/gateway` → 访问令牌 → 签发令牌。
   需要 `GATEWAY_ADMIN_TOKEN` 登录。

每个客户端用**不同**的令牌。撤销某一枚不会影响其它客户端；
撤销/过期在服务端立即生效，不需要等会话重连。

> 这两个入口签发的都是 `hub_client_*`，**只访问已授权的 `ext_*`**。
> 需要本机原生工具（备忘录、日历、设备状态等）时，请让客户端走 OAuth；
> 静态 `MCP_ACCESS_TOKEN` 仅保留作本地联调旁路，真实部署默认不配置。

### 1.2 OAuth

- 资源元数据：`/.well-known/oauth-protected-resource`
- 授权服务器元数据：`/.well-known/oauth-authorization-server`
- 动态客户端注册、PKCE S256、刷新令牌轮换均已实现；
- 回调主机必须出现在 `OAUTH_REDIRECT_ALLOWLIST`（默认 `chatgpt.com`）。

OAuth 只解决“客户端能不能进来”。完成 OAuth 后可以看到**本机原生工具**，
但**不会**因此获得任何上游 `ext_*` 工具 —— 上游仍须在操作台逐项授权。
反过来，Android / 操作台签发的 `hub_client_*` 令牌只访问 `ext_*`，看不到本机原生工具。

---

## 2. 兼容性矩阵

> 下表的“状态”是**代码能力与协议层面**的判断。除本地自动化测试外，
> 本轮没有用真实账号对任何客户端做验收；请以你自己的实测为准。

| 客户端 | 远程 MCP | 静态 Bearer | OAuth | 状态 | 配置要点 |
| --- | --- | --- | --- | --- | --- |
| **Claude Desktop** | 支持（Connectors / 远程 MCP） | 视版本；若设置界面只能填 URL，则不支持自定义 Header | 支持 | 协议兼容，**未实测** | 优先走 OAuth；若版本支持 `mcpServers` 的 URL + headers，再填 Bearer |
| **ChatGPT**（开发者模式 Connector） | 支持 | 通常不提供自定义 Header | 支持 | 协议兼容，**未实测** | 用 OAuth；回调主机需在允许清单内 |
| **Codex** | 支持 Streamable HTTP | 支持（`bearer_token_env_var`） | 视版本 | 协议兼容，**未实测** | 令牌从环境变量读，不要写进 `config.toml` |
| **微光机 / Glimmer** | 未对接 | — | — | **不支持开箱接入** | 需要它自己的后端做用户鉴权、按 Char 发牌与确认 UI；Hub 不信任客户端自报身份 |
| **Claude Code** | `claude mcp add --transport http` | 支持 Header | 支持 | 可用于联调 | 适合先用它验证链路 |
| **MCP Inspector / 脚本** | 支持 | 支持 | 支持 | 本地验证首选 | 见下面 curl 示例 |

### 2.1 Claude Desktop

- 若版本提供 **Settings → Connectors → Add custom connector**：填 `/mcp` 地址，走 OAuth。
- 若使用配置文件，只在该版本支持远程 URL 时填写；示例（字段名以你的客户端文档为准）：

```json
{
  "mcpServers": {
    "android-hub": {
      "url": "https://<worker>/mcp",
      "headers": { "Authorization": "Bearer ${ANDROID_HUB_TOKEN}" }
    }
  }
}
```

不要把这个文件提交进任何仓库；它含有令牌占位符时也要小心客户端不支持环境变量展开。

### 2.2 ChatGPT

- 在开发者模式/连接器设置里添加远程 MCP Server，URL 填 `/mcp`；
- 选择 OAuth，按同意页输入 `OAUTH_OWNER_SECRET`；
- 如果客户端要求回调域名，确认 `OAUTH_REDIRECT_ALLOWLIST` 包含它；
- ChatGPT 通常不提供静态 Bearer 输入框，此时不要尝试把令牌拼进 URL。

### 2.3 Codex

`~/.codex/config.toml`（示例，具体键名以你安装的版本为准）：

```toml
[mcp_servers.android_hub]
url = "https://<worker>/mcp"
bearer_token_env_var = "ANDROID_HUB_TOKEN"
```

```bash
# 令牌只在当前 shell 里设置
export ANDROID_HUB_TOKEN='<客户端令牌>'
codex
```

### 2.4 微光机 / 其他自研客户端

本仓库**没有**修改微光机，也不提供“自报 userId 就能选身份”的入口。
如果要在微光机里接入，需要它自己的后端：

1. 完成它自己的用户登录；
2. 用可信管理面为每个用户/Char 创建独立 `client:<id>` 并逐项授权；
3. 在自己的后端保存并使用该客户端的令牌，不把管理令牌交给普通用户；
4. 为写操作提供用户确认 UI，再向 Hub 申请一次性确认票据；
5. 自行实现配额、套餐与凭据生命周期。

在完成这些之前，请把微光机视为**不兼容**，不要宣称可以直接接入。

---

## 3. 用 curl 验证链路

```bash
export HUB_BASE=https://<worker>.<subdomain>.workers.dev
export HUB_TOKEN=<客户端令牌>     # 只在当前 shell，不要写进脚本文件

# 1) initialize，记下 mcp-session-id 响应头
curl -sS -D /tmp/hub-headers \
  -H "Authorization: Bearer $HUB_TOKEN" \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"curl","version":"1"}}}' \
  "$HUB_BASE/mcp"

SESSION=$(grep -i '^mcp-session-id:' /tmp/hub-headers | tr -d '\r' | cut -d' ' -f2)
rm -f /tmp/hub-headers

# 2) 列出工具（只会出现已授权给你的 ext_*）
curl -sS \
  -H "Authorization: Bearer $HUB_TOKEN" \
  -H "Mcp-Session-Id: $SESSION" \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}' \
  "$HUB_BASE/mcp"

# 3) 调用一个工具
curl -sS \
  -H "Authorization: Bearer $HUB_TOKEN" \
  -H "Mcp-Session-Id: $SESSION" \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"phone_ping","arguments":{}}}' \
  "$HUB_BASE/mcp"
```

未授权工具不会出现在 `tools/list` 里；直接调用会返回 `isError=true`。
需要确认的工具还要带一次性票据，不能靠客户端界面“看起来点了确认”绕过。

---

## 4. 连接诊断

Hub 会把失败归到固定类别，客户端看到的 `isError` 内容与操作台里的错误码一致：

| 现象 / 错误 | 含义 | 先查什么 |
| --- | --- | --- |
| HTTP 401 / `unauthorized` | 令牌缺失、撤销或过期 | 重新签发；确认 Header 拼写；确认没有把令牌放进 URL |
| HTTP 403 / `origin_denied` | 浏览器跨源调用管理面 | 用客户端/服务端调用，不要从任意网页直接 POST |
| HTTP 404 / `not_found` | 网关未启用或接口不存在 | `GATEWAY_ENABLED`、路径是否 `/mcp` |
| 工具列表为空 | 没有授权、服务停用或令牌 scope 只读 | 操作台逐项 grant；检查服务 enabled |
| `definition_changed` | 上游工具定义变了，旧指纹失效 | 重新 test 并重新授权 |
| `upstream_timeout` | 上游 MCP 服务超时 | 该服务是否在线；调大 `timeoutMs` |
| `upstream_failure` / `upstream_network` | 上游返回错误或连接中断 | 上游健康状态与凭据 |
| `target_denied` | URL 不在允许清单 | `GATEWAY_ALLOWED_URLS` |
| `concurrency_limit` | 网关繁忙（全局 8 / 每客户端 2） | 降低并发或重试 |
| `device_offline` / 手机无响应 | 手机断线或超时 | App 前台服务、网络、`DEVICE_TOKENS` |
| `permission_denied` | Android 系统权限未授予 | 系统设置里授予使用情况/日历/通知等权限 |
| `not_permitted` | 用户在 App 里关掉了这个工具 | App → 工具与权限里打开 |
| `confirmation_required` | 高影响操作缺少 `confirm=true` | 先向用户确认，再带参数重试 |

---

## 5. 安全提醒

- 一个客户端一枚令牌；不要把配对令牌、管理令牌或设备令牌发给客户端；
- 只读令牌适合“只看不改”的客户端；写操作优先用 confirm 模式的工具；
- 撤销令牌后，已完成的调用不会被追溯取消，但之后的调用会立即被拒绝；
- 截图、日志、Issue 里不要出现完整令牌。Hub 的管理面只显示令牌编号与元数据。
