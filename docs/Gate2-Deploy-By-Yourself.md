# Gate 2：自己动手把 Worker 部署上去

这份是**方案 A** 的操作手册 —— 你在自己的机器上，用 `feat/gate2-core` 分支的代码部署，
`gate0` 分支不动；真机验证通过之后，再决定要不要合并。

> 全程不需要把任何令牌 / 口令 / API key 贴到聊天里，也不要写进任何文件。
> `DEVICE_TOKENS` 与 `OAUTH_OWNER_SECRET` 已经存在 Cloudflare 的 Worker 上，
> **一次代码部署不会碰它们**（不需要重新 `secret put`）。

## 0. 前置条件

| 项 | 要求 | 怎么确认 |
| --- | --- | --- |
| 代码 | 本仓库的 `feat/gate2-core` 分支 | `git log --oneline` 里应能看到 `1629442`（图标/启动页）与 `c61f457`（撤销时序修复）这两个提交 |
| Node | 24（与部署工作流一致；22+ 也可以） | `node -v` |
| Cloudflare 凭据 | 那个**专属账号**的 Workers 部署权限 | `npx wrangler@4.149.0 whoami` |
| Worker 名 | 必须是 `android-mcp-hub`，且已存在于**同一个账号** | 部署输出里会打印 `Uploaded android-mcp-hub` |

⚠️ 如果 `whoami` 显示的账号不是原来那个，`deploy` 会在**别的账号**下新建一个同名 Worker，
你的手机和 ChatGPT 连接器都指不到它。先确认账号再往下走。

如果本机没登录过 wrangler，用环境变量方式（在**自己的终端**里设，不要贴到任何地方）：

```bash
export CLOUDFLARE_API_TOKEN=...        # Workers Scripts: Edit 权限
export CLOUDFLARE_ACCOUNT_ID=...       # 32 位十六进制
```

## 1. 取代码

```bash
git clone https://github.com/wenqv4617-art/android-mcp-hub.git
cd android-mcp-hub
git checkout feat/gate2-core
git log --oneline -3
```

已经克隆过的话：

```bash
git fetch origin && git checkout feat/gate2-core && git pull --ff-only
```

## 2. 部署前本地校验（和部署工作流同一条流水线）

```bash
npm ci
npm run gate0        # = typecheck + 单测 + 本地端到端
```

期望结果（冻结树 `c6c5b66` 上实测）：

* `typecheck` 退出码 0
* Worker 单测 **14 个文件 / 327 条全过**
* 本地 E2E **78 条全过 / 0 失败**

任何一条红了就**不要部署**，把输出发我。

## 3. 先干跑一次（不写入线上）

```bash
npx wrangler@4.149.0 deploy --config worker/wrangler.jsonc --dry-run
```

看点：

* 脚本名是 `android-mcp-hub`，绑定是 `PHONE_SESSION` / `MCP_SESSION` / `OAUTH_STORE`
* `migrations` 里两个 tag（`v1` = PhoneSession + McpSession，`v2` = OAuthStore）**都已经应用过**，
  正常输出不会再要求新迁移。**不要改 `migrations`** —— 已经部署过的 tag 只能追加、不能改。

## 4. 正式部署

```bash
npx wrangler@4.149.0 deploy --config worker/wrangler.jsonc
```

这一步就是把 Gate 2（电池只读工具 + 迟到撤销通知修复）的 Worker 代码推上线。

## 5. 部署后立刻做只读验证

```bash
curl -s https://<你的 Worker 域名>/healthz
npm run smoke -- --base https://<你的 Worker 域名>
```

* `/healthz` 应回 200，`status` 正常，三个绑定都在
* `npm run smoke` 是**只读**自检（不需要任何秘密），逐条打 PASS / FAIL

## 6. 手机侧换新 APK

下载链接（在手机浏览器里打开就会下载，文件名 `AndroidMCPHub-Gate2-debug.apk`）：

```
http://127.0.0.1:8933/AndroidMCPHub-Gate2-debug.apk
```

* 手机上是**覆盖升级**（`com.androidmcphub.app` 已装，uid=10157），装完桌面图标会换成新的，
  点开先看到启动页（约 0.9 秒）再进主界面。
* 如果系统提示签名不一致（理论上不会，同一台机器同一 debug 签名），只能先卸载再装；
  卸载会清掉 App 里的设备配置，需要重新配对。
* 装完在 App 里打开「允许服务端调用本机只读能力」开关（**默认是关的**，不开就只能 `phone_ping`）。

## 7. 出了问题怎么回滚

```bash
# 方式一（最干净）：用 gate0 的代码重新部署，等于回到联调前的线上状态
git checkout gate0 && git pull --ff-only
npx wrangler@4.149.0 deploy --config worker/wrangler.jsonc
```

```bash
# 方式二：留在本分支，把 Gate 2 的提交按逆序 revert 掉再部署
git revert --no-commit 1629442 c61f457 && git commit -m "revert(gate2): 回滚 Gate 2 代码"
npx wrangler@4.149.0 deploy --config worker/wrangler.jsonc
```

⚠️ 一个**只和回滚有关**的细节：Gate 2 期间如果撤销过某台设备，撤销记录写在 Durable Object 里，
旧代码不认识这条新记录 —— 回滚后请对那台设备**再撤销一次**（或者直接轮换它的设备令牌）。

## 8. 这条路上不要做的事

* 不要把 `CLOUDFLARE_API_TOKEN` / `DEVICE_TOKENS` / `OAUTH_OWNER_SECRET` 贴到聊天或提交进仓库
* 不要在 `worker/wrangler.jsonc` 里加 `account_id` / `routes`（配置校验会直接失败）
* 不要为了部署去改 `migrations`
* 部署成功 ≠ 验证通过：真机 `battery_get_status` 与撤销时序仍要按
  `docs/Gate2-Device-Integration-Checklist.md` 走一遍
