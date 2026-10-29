# V1.5：Android 服务管理接线、受限配对与真实 MCP 闭环

本轮目标是把已有的 Cloudflare 通用 MCP 网关接到 Android「服务」页，并交付一个可安装的 APK；
不是再写一套网关或再写一套 MCP 引擎。线上 Worker、`GatewayStore`、官方 MCP SDK、
`/mcp`、工具指纹与逐项 grant 均复用 V1 已有实现。

## 1. 认证模型（关键）

**全局 `GATEWAY_ADMIN_TOKEN` 从不进入 APK。** 它没有出现在 Kotlin、`BuildConfig`、
Android 资源、Gradle、Assets 或 Git 中，也不是 Android 的长期凭据。

Android 使用一条独立的、受限的配对路径：

1. 管理员在 Worker 配置一枚高熵 `GATEWAY_ANDROID_PAIRING_CODE` Secret；
2. 手机在「服务 → 云端网关」输入网关根地址与这枚口令；
3. `POST /gateway/pair` 在 `GatewayStore` 内校验口令（摘要 + 定时安全比较，失败限速），
   返回两枚令牌：
   - `hub_admin_*`：只能管理**这台设备自己的**服务，不能管理别的设备、不能创建任意客户端；
   - `hub_client_*`：只能通过 `/mcp` 调用自己已获授权的网关工具；
4. 两枚令牌在服务端只保存 SHA-256 摘要；明文只返回一次；重新配对会轮换旧令牌，但保留 grant；
5. 手机用 Android Keystore（AES-256-GCM）分别加密两枚令牌后存入私有
   SharedPreferences `gateway_client_v1`，状态层与日志里只有「是否已配对」。

服务端 owner 由 `sha256(deviceId)` 派生，限制 8 台设备；管理面所有读写都强制 owner/client
范围。访问别人的服务返回 404（不确认其存在）。配对失败 10 次/分钟触发 429。

撤销路径：手机「解除配对」→ `unpair`（服务端吊销 admin 记录并停用 client）；
管理员也可以从全局管理面 `revoke-android`。配对口令轮换只需更新 Secret，不影响其他 Secret。

## 2. Android 数据层与界面

新增（纯 Kotlin，可被 JVM 单测覆盖）：

| 文件 | 作用 |
| --- | --- |
| `gateway/GatewayModels.kt` | 服务、工具、授权模式、草稿、快照等界面模型 |
| `gateway/GatewayUrls.kt` | 网关地址准入与固定中文错误码映射；不回显响应体 |
| `gateway/GatewayApi.kt` | `OkHttpGatewayApi`：Bearer 只进请求头、无重定向、2 MiB 上限、错误只取 `error` 码 |
| `gateway/GatewayCredentialsStore.kt` | Keystore 加密存储；设备标识来自既有 Hub 设备 ID 或 ANDROID_ID 摘要 |
| `gateway/GatewayMcpCaller.kt` | 复用现有 `McpTransportFactory`/Streamable HTTP，把网关当普通 MCP 服务器调用 |
| `gateway/GatewayState.kt` | 配对、列表、增删改、启停、测试、grant/revoke、真实调用与错误映射 |
| `ui/gateway/GatewayViewModel.kt` | 组装上述依赖；默认网关地址取 Hub 配置，否则用已部署地址 |

界面复用现有设计语言，只增加「本机直连 / 云端网关」切换，默认仍是本机直连：

- **服务列表**：名称、协议、主机、启用状态、工具数/已授权数、最近错误；加载/空/错误态；
- **添加/编辑**：名称、URL、说明、传输方式（自动检测优先 Streamable HTTP，失败回退 SSE）、
  启用、`secretRef`（只传引用，不传密钥）、保存后自动测试并发现；
- **详情**：完整 URL、协议、连接状态、最近测试时间、上游凭据引用、revision；
  启用/停用、重新发现、编辑、二次确认删除；
- **工具**：名称、说明、输入 Schema、只读/需确认 badge、按工具授权/撤销、
  真实调用测试（JSON 参数）；「需确认」先申请 60 秒一次性票据，再放入
  `_meta["hub/confirmation"]`；
- 破坏性操作二次确认；长 URL/名称截断；键盘 `imePadding`；令牌不展示、不写日志。

## 3. 服务端新增接口

除既有 `POST /gateway/admin` 外：

| 路径 | 认证 | 行为 |
| --- | --- | --- |
| `POST /gateway/pair` | 配对码 | 换回受限管理令牌 + 客户端令牌；未启用/未配置时 404 |
| `POST /gateway/android` | `hub_admin_*` | 受限管理面；DO 内强制 owner/client 范围 |

受限管理面支持：`list`、`put-service`、`set-enabled`、`test`、`delete-service`、
`grant`、`revoke`、`confirm`、`rotate-client-token`、`unpair`。
全局管理面新增 `delete-service` 与 `revoke-android`；全局令牌仍然只用于运维。

## 4. 环境开关

| 名称 | 类型 | 说明 |
| --- | --- | --- |
| `GATEWAY_ENABLED` | var | `true` 才暴露 `/gateway/*`、`/gateway` 管理页与网关工具 |
| `GATEWAY_ALLOWED_URLS` | var | **精确 URL** 逗号列表；生产仅 HTTPS/443、无 IP/凭据/查询/片段 |
| `GATEWAY_ANDROID_PAIRING_CODE` | Secret | Android 配对码；未设置时 `/gateway/pair` 404 |
| `GATEWAY_ADMIN_TOKEN` | Secret | 仅全局运维面；**不下发、不进 APK** |
| `GATEWAY_UPSTREAM_SECRETS` | Secret | `ownerId/secretRef → Authorization` 的 JSON；手机只填引用名 |

本轮部署启用 `GATEWAY_ENABLED=true`，allowlist 只包含三个公开文档 MCP 端点：

- `https://docs.mcp.cloudflare.com/mcp`
- `https://mcp.deepwiki.com/mcp`
- `https://learn.microsoft.com/api/mcp`

新增上游必须改配置并重走人工确认部署，客户端无法在运行时扩展。

## 5. 部署与线上验收

部署仍复用 `.github/workflows/deploy-worker.yml`（唯一部署工作流，手动触发）：

- `gate0` + 确认串 `DEPLOY`；
- 先 typecheck / 全部 Worker 测试 / 本地 e2e / `wrangler deploy --dry-run`；
- 再真正 `wrangler deploy`，只注入/轮换 `GATEWAY_ANDROID_PAIRING_CODE`，
  不覆盖其他 Secret；
- 部署后执行 `tools/gateway-online-smoke.ts`，用真实公网 Worker 完成：
  配对 → 添加服务 → 发现工具 → 未授权拒绝 → 只读真实调用 → 重开会话持久化 →
  需确认无票据拒绝/有票据成功/票据不可重放 → 停用拒绝 → 坏地址拒绝 → 删除 → 解除配对。

本地 `npm run dev` / `npm run e2e` / `test:gateway:workerd` 只证明协议实现，
**不代替**上述线上验收。

## 6. 测试

- Worker：`npm run typecheck`、`npm test`、`npm run e2e`、`npm run test:gateway:workerd`；
  新增 `worker/test/gateway-android.test.ts` 覆盖配对、范围隔离、真实 SDK 调用、
  确认票据、轮换/撤销与限速。
- Android：`:app:testDebugUnitTest` 新增 `GatewayUrlsTest`、`GatewaySnapshotParseTest`、
  `OkHttpGatewayApiTest`、`GatewayStateTest`；覆盖地址准入、令牌不进状态、
  unauthorized 清理、Streamable→SSE 回退、grant 指纹、confirm 票据、停用/坏地址。
- APK：`.github/workflows/android-debug-apk.yml` 手动构建、跑单测、校验 Manifest/dex、
  上传 artifact 与 SHA-256。

## 7. 未完成/边界

- 真机或模拟器上的安装与手工点击验收需要在有设备的环境完成；没有设备时不得宣称完成。
- 上游只允许公开、无凭据的文档 MCP；未实现第三方 OAuth 授权托管、任意域名自助注册。
- `mode=confirm` 依赖可信管理面签发一次性票据；上游读工具本身不产生业务幂等键。
- `/mcp` 的 tools/list 来自探测快照；上游目录变化后需重新发现，调用时仍核对实时指纹。
