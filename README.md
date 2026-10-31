# Android MCP Hub

把一台 Android 手机变成标准 MCP（Model Context Protocol）客户端可以访问的工具服务器，
同时把第三方 MCP 服务汇总到同一个入口。

- **标准远程 MCP**：`/mcp` 提供 Streamable HTTP，支持 URL + Bearer Token 与 OAuth 2.1 + PKCE。
- **通用网关**：把多个第三方 MCP 服务（Streamable HTTP / 旧版 HTTP+SSE）映射成 `ext_*` 工具，
  逐工具授权，客户端看不到未授权的上游。
- **Android 原生能力**：36 个 MCP 工具，覆盖备忘录、待办、日历、提醒、闹钟、使用统计、
  设备状态与常用控制。
- **按最小权限运行**：每个客户端独立令牌、独立授权、可单独撤销；scope 只能收窄授权。

> **产品定位**：你在**自己的** Cloudflare 账户里部署**自己的** Worker，绑定**自己的**手机。
> 本仓库维护者部署的 Worker 不是面向他人的公共中转服务，也不应被当成公共网关。

---

## 1. 链路

```
MCP 客户端（ChatGPT / Claude Desktop / Codex / 微光机 / 任何标准客户端）
        │  HTTPS · MCP Streamable HTTP · Authorization: Bearer <令牌>
        ▼
Cloudflare Worker                        worker/src/index.ts
        │  ── 会话 ──────► Durable Object  McpSession
        │  ── OAuth ─────► Durable Object  OAuthStore（只存 SHA-256 摘要）
        │  ── 网关状态 ──► Durable Object  GatewayStore（服务 / 授权 / 令牌元数据）
        │
        │  WebSocket · phone-link-v1（设备令牌）
        ▼
Durable Object                           PhoneSession（连接代次、超时、请求配对）
        │
        ▼
Android App（本仓库 android/）           前台服务 + Kotlin/Compose
        │  进程内调用
        ▼
本机能力（Calendar Provider / AlarmClock / UsageStats / 本地 SQLite 风格存储）
```

一个请求只经过一次 Worker 和一次手机转发；Worker 不缓存工具结果，也不会在手机离线时伪造成功。

---

## 2. 当前状态（2026-10-31）

| 项 | 结果 |
| --- | --- |
| Worker `npx tsc --noEmit` | 通过 |
| Worker 测试 | **469 项 / 21 个文件全部通过**（`npx vitest run`） |
| Android JVM 测试 | **720 项 / 0 失败 / 7 跳过**（`:app:testDebugUnitTest`） |
| Android Lint | 通过（`abortOnError = true`） |
| Debug APK | `dist/Android-MCP-Hub-v1.6-debug.apk`（14 MB，SHA-256 `77c6751d…2af57c`） |
| 真实 Cloudflare 部署 | 本轮**未执行**；历史部署地址为 `https://android-mcp-hub.causticrry.workers.dev` |
| 真机 / 真实客户端验收 | **未执行**（需要设备权限与第三方账号，见文末清单） |

详细历史见 [TEST_REPORT.md](TEST_REPORT.md)、[docs/GATEWAY_V1.md](docs/GATEWAY_V1.md)、
[docs/ANDROID_GATEWAY_V15.md](docs/ANDROID_GATEWAY_V15.md)。

---

## 3. 本地验证

前置：Node ≥ 20、JDK 17、Android SDK（仅 Android 构建需要）。

```bash
npm install

# Worker：类型检查 + 单元/集成测试
npm run typecheck
npm run test

# 可选：真 HTTP + 真 WebSocket + 官方 MCP SDK 的本地端到端
npm run e2e

# Android
cd android
./gradlew :app:testDebugUnitTest :app:lintDebug
./gradlew :app:assembleDebug
```

产物：`android/app/build/outputs/apk/debug/app-debug.apk`；
交付副本见 `dist/Android-MCP-Hub-v1.6-debug.apk`，校验值在 `dist/SHA256SUMS.txt`。

> **不要**把令牌写进命令行参数、文件或聊天记录。需要生成 `DEVICE_TOKENS` 记录时，
> 令牌只走 stdin：`printf '%s' "$DEVICE_TOKEN" | npm run check:credential -- --device-id my-phone`。

---

## 4. 自己部署

完整步骤见 **[docs/SELF_HOSTING.md](docs/SELF_HOSTING.md)**，包括：

- Cloudflare 账户、Worker、四个 Durable Object 与迁移；
- `DEVICE_TOKENS`、`OAUTH_OWNER_SECRET`、`GATEWAY_ADMIN_TOKEN`、
  `GATEWAY_ANDROID_PAIRING_CODE`、`GATEWAY_UPSTREAM_SECRETS` 的生成与注入；
- 上游允许清单 `GATEWAY_ALLOWED_URLS` 的填写原则；
- 部署后自检、Android 配对、回滚与资源删除。

一句话版本（**先读完部署文档再执行**）：

```bash
npx wrangler@4.149.0 login
npx wrangler@4.149.0 secret put DEVICE_TOKENS --config worker/wrangler.jsonc
npx wrangler@4.149.0 secret put OAUTH_OWNER_SECRET --config worker/wrangler.jsonc
npx wrangler@4.149.0 secret put GATEWAY_ADMIN_TOKEN --config worker/wrangler.jsonc
npx wrangler@4.149.0 secret put GATEWAY_ANDROID_PAIRING_CODE --config worker/wrangler.jsonc
npx wrangler@4.149.0 deploy --config worker/wrangler.jsonc
```

开发者的线上 Worker **不会**替你的部署提供服务；每个部署都有自己的地址、凭据与数据。

---

## 5. 客户端接入

MCP 地址固定为：

```
https://<your-worker>.workers.dev/mcp
```

两种鉴权方式：

1. **URL + Bearer Token**（推荐给支持自定义 Header 的客户端）
   - 在 Android「服务 → 云端网关 → 访问令牌」或操作台 `/gateway` 签发；
   - 明文只显示一次；
   - 令牌只放在 `Authorization` 头，**不要**拼进 URL、查询参数或截图。
2. **OAuth 2.1 + PKCE**
   - 客户端走 RFC 9728 / 8414 发现、动态注册、同意页；
   - 需要配置 `OAUTH_OWNER_SECRET` 作为运维口令。

各客户端的实际配置方式、兼容性与限制见 **[docs/CLIENT_SETUP.md](docs/CLIENT_SETUP.md)**。
**不要**把「连上 Hub」理解成「自动获得所有上游工具」：每个 `ext_*` 工具仍需逐项授权。

---

## 6. Android 原生能力

29 个原生工具 + 7 个 Hub/设备基础工具，统一命名、独立 Schema、稳定错误码：

- `memo_*`、`todo_*`、`reminder_*`：本机持久化，不依赖第三方 App；
- `calendar_*`：Calendar Provider，按系统权限读写；
- `alarm_create` / `alarm_open_list`：系统 AlarmClock Intent；
- `device_get_usage_stats`：UsageStatsManager，需要「使用情况访问」；
- `device_get_status`、`device_set_volume`、`device_set_flashlight`、
  `device_open_app`、`device_open_settings`、`device_post_notification` 等设备能力。

授权是三层结构：只读总开关 → 写入总开关（默认关）→ 逐条工具开关（写入类默认关）。
删除类工具还要求调用方显式传 `confirm=true`。完整清单、权限与人工验收步骤见
**[docs/NATIVE_TOOLS.md](docs/NATIVE_TOOLS.md)**。

---

## 7. 配置项

敏感值一律通过 `wrangler secret put` 注入，不进 `wrangler.jsonc`、不进仓库。

| 名字 | 类型 | 说明 |
| --- | --- | --- |
| `DEVICE_TOKENS` | secret | 手机设备令牌表，只存 SHA-256 摘要；轮换时追加新记录并给旧的填 `revokedAt` |
| `OAUTH_OWNER_SECRET` | secret | 同意页口令；**未设置时整个 OAuth 面回 404** |
| `GATEWAY_ADMIN_TOKEN` | secret | 操作台 `/gateway` 与全局管理 API；不下发给任何客户端 |
| `GATEWAY_ANDROID_PAIRING_CODE` | secret | Android 配对码；未设置时 `/gateway/pair` 回 404 |
| `GATEWAY_UPSTREAM_SECRETS` | secret | 上游 Authorization 值，键为 `ownerId/secretRef`；服务只保存引用 |
| `GATEWAY_ENABLED` | var | `"true"` 才启用网关与操作台 |
| `GATEWAY_ALLOWED_URLS` | var | 上游**精确 URL** 允许清单（逗号分隔，无 query/hash） |
| `PHONE_PING_TIMEOUT_MS` | var | 手机响应超时，默认 `10000` |
| `MAX_PENDING_REQUESTS` | var | 单台手机在飞请求上限，默认 `16` |
| `MAX_PHONE_MESSAGE_BYTES` | var | 单帧上限，默认 `65536` |
| `OAUTH_REDIRECT_ALLOWLIST` | var | OAuth 允许的回调主机，默认 `chatgpt.com` |

所有数值都有上下限夹紧（`worker/src/config.ts`）；夹紧用于挡 `0`、负数与荒谬大值，
不替运维决定业务阈值。

---

## 8. 文档索引

| 文档 | 内容 |
| --- | --- |
| [docs/SELF_HOSTING.md](docs/SELF_HOSTING.md) | **自己部署**：前置条件、Secret、部署、自检、回滚、删除 |
| [docs/CLIENT_SETUP.md](docs/CLIENT_SETUP.md) | **客户端接入**：令牌 vs OAuth、ChatGPT / Claude Desktop / Codex / 微光机兼容性与排障 |
| [docs/NATIVE_TOOLS.md](docs/NATIVE_TOOLS.md) | 36 个工具的权限、确认语义、数据位置与人工验收步骤 |
| [docs/OPEN_SOURCE_AUDIT.md](docs/OPEN_SOURCE_AUDIT.md) | 开源前密钥扫描、依赖许可证清单与发布建议 |
| [docs/GATEWAY_V1.md](docs/GATEWAY_V1.md) | 通用网关 V1 的设计边界、安全模型与管理 API |
| [docs/ANDROID_GATEWAY_V15.md](docs/ANDROID_GATEWAY_V15.md) | Android 侧网关客户端与配对 |
| [ARCHITECTURE.md](ARCHITECTURE.md) | Durable Object 切分、连接代次、超时与重连 |
| [SECURITY.md](SECURITY.md) | 威胁模型、凭据模型与残余风险 |
| [docs/PROTOCOL.md](docs/PROTOCOL.md) | 内部 WSS 协议 v1 |
| [docs/OAUTH-DESIGN.md](docs/OAUTH-DESIGN.md) | OAuth 2.1 + PKCE 设计 |
| [android/README-ANDROID.md](android/README-ANDROID.md) | 前台服务类型与人工验证清单 |
| [TEST_REPORT.md](TEST_REPORT.md) | 历史测试证据与 NOT RUN 项 |

---

## 9. 已知限制

- **未做真机验收**：本轮的 Android 测试全部是 JVM 测试，`UsageStatsManager`、Calendar Provider、
  AlarmClock Intent、通知权限等路径需要真机手工验证（步骤见 [docs/NATIVE_TOOLS.md](docs/NATIVE_TOOLS.md)）。
- **未做真实客户端验收**：ChatGPT、Claude Desktop、Codex、微光机均未使用真实账号连接本部署。
- **未部署本轮代码**：线上历史地址运行的是更早版本；升级前请按 [docs/SELF_HOSTING.md](docs/SELF_HOSTING.md)
  走 dry-run 与迁移核对。
- **上游 OAuth 不自动续期**：上游服务需要运维在 `GATEWAY_UPSTREAM_SECRETS` 里提供 Authorization 值。
- **不允许任意 URL**：上游必须逐个加入 `GATEWAY_ALLOWED_URLS`，这是安全边界而非缺陷。
- **没有 LICENSE 文件**：仓库尚未附开源许可证，对外发布前需由仓库所有者选定；
  第三方依赖的许可证情况见 [docs/OPEN_SOURCE_AUDIT.md](docs/OPEN_SOURCE_AUDIT.md)。

---

## 10. 真实设备验收清单（需要你操作）

1. 安装 `app-debug.apk`，授予通知权限；
2. 在 App 填入网关地址、设备 ID、设备令牌并保存；
3. 在服务页切到「云端网关」，填入配对码完成配对；
4. 在客户端用 URL + Token 或 OAuth 连接 `/mcp`，确认只看到已授权工具；
5. 依次验证：`phone_ping`、备忘录 CRUD、待办完成、日历读写、提醒、闹钟 Intent、
   使用统计（需在系统设置里授予「使用情况访问」）；
6. 撤销一枚令牌，确认该客户端立即被拒绝；撤销前已完成的调用不会被追溯取消。
