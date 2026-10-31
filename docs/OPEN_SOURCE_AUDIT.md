# 开源前审计：密钥、凭据与许可证

审计日期：2026-10-31
范围：当前分支 `feat/universal-mcp-gateway` 的工作树 + 全部 git 历史 + 依赖清单。

**结论先说：**

- 没有发现明文令牌、私钥、Cloudflare API Token 或签名密钥进入仓库；
- `test-reports/credential-check.log` 里有两枚 **SHA-256 摘要**（不是明文），
  按当前证据不可直接用于认证，但发布前建议连同该日志一起复核；
- 仓库**没有 LICENSE 文件**，对外发布前必须由仓库所有者选定；
- 运行时依赖全部是 MIT / Apache-2.0，未发现 GPL/AGPL 运行时依赖。

---

## 1. 扫描方法

本机没有安装 `gitleaks` / `trufflehog` / `git-secrets`，因此使用等价的定向正则扫描，
并对结果逐条人工判读：

```bash
# 1) 高信号前缀：私钥、GitHub/OpenAI/AWS 令牌
git grep -nEI 'sk-[A-Za-z0-9_-]{20,}|ghp_[A-Za-z0-9]{20,}|github_pat_|AKIA[0-9A-Z]{16}|-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----|xox[baprs]-'

# 2) Authorization 头里的长 Bearer 值
git grep -nEI 'Authorization[^,]{0,20}Bearer [A-Za-z0-9._~+/-]{20,}'

# 3) 64 位十六进制串（设备令牌摘要 / APK 校验值 / 明文令牌都可能长这样）
git grep -nEI '\b[0-9a-fA-F]{64}\b'

# 4) 历史里新增过的私钥 / 令牌前缀 / 64 位十六进制
git log --all -p -G 'BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY|sk-[A-Za-z0-9]{20,}|ghp_[A-Za-z0-9]{20,}|AKIA[0-9A-Z]{16}'
git log --all -p -G '[0-9a-f]{64}' -- . ':!package-lock.json' ':!docs' ':!TEST_REPORT.md' ':!test-reports'

# 5) 密钥文件与本地凭据文件是否被跟踪
git ls-files | grep -Ei '\.(jks|keystore|p12|pem|key)$'
git ls-files | grep -E '\.dev\.vars|\.env$|wrangler\.toml'
```

同时检查了 `.gitignore`、`.github/workflows/*.yml`、`worker/wrangler.jsonc` 与
`worker/.dev.vars.example`。

> 扫描器只能证明"没匹配到已知模式"，不能证明"绝对没有秘密"。
> 若怀疑某枚真实令牌曾经进过历史，正确做法是**轮换**，而不是删除提交。

---

## 2. 结果

### 2.1 工作树

| 检查项 | 结果 | 说明 |
| --- | --- | --- |
| 私钥（PEM） | 未发现 | — |
| OpenAI / GitHub / AWS / Slack 令牌前缀 | 未发现 | — |
| 长 Bearer 值 | 未发现 | 文档与测试里的 `Bearer <令牌>` 都是占位符 |
| 明文设备令牌 / 管理员令牌 / 配对码 | 未发现 | 只出现摘要或占位符 |
| `worker/.dev.vars` | 不存在，且被忽略 | 仓库里只有 `.dev.vars.example` |
| 签名材料（`.jks` / `.keystore` / `.p12`） | 未被跟踪 | `.gitignore` 覆盖；CI 从 GitHub Secrets 取 |
| `wrangler.jsonc` 中的 `account_id` / `route` | 不存在 | 部署目标由命令行显式提供 |

### 2.2 历史

高信号前缀（私钥、`sk-`、`ghp_`、`AKIA`）在全部历史中**没有命中**。

历史与工作树中出现过的 64 位十六进制串逐一核对后：

| 值（前缀） | 出现位置 | 性质 |
| --- | --- | --- |
| `2467a034…` | `dist/SHA256SUMS.txt`、`test-reports/android-build.log` | APK 文件校验值 |
| `0f7bfc37…` | 同上 | APK 文件校验值 |
| `c3539295…` | 同上 | APK 文件校验值 |
| `cbce07ea…` | 同上 | APK 文件校验值 |
| `f16226b5…` | 同上 | APK 文件校验值 |
| `77c6751d…` | `dist/SHA256SUMS.txt` | 本轮交付 APK 的校验值 |
| `00000000…` | `worker/.dev.vars.example` | 全零占位符 |

`dist/SHA256SUMS.txt` 里的值与实际 APK 的哈希一致；它们是**公开校验值**，
不构成凭据。

### 2.3 需要复核的一项

`test-reports/credential-check.log` 含有：

- 一个 `deviceId`（`my-android-phone`）；
- 两枚 64 位十六进制 **SHA-256 摘要**。

判定：

- 文件里**没有明文令牌**，摘要本身不能直接用于 `Authorization`；
- 但摘要可以用于离线校验候选明文。如果当时那枚令牌是弱口令/短随机串，
  理论上可被字典穷举；
- 从文件内容看，这是 `check:credential` 的自检输出，属于测试记录。

处理建议（发布前二选一）：

1. 保留，但在文档中明确它是测试摘要、对应令牌已不再使用；
2. 删除该日志中的摘要行（保留命令与格式说明），并说明原因。

**不要**为了让扫描通过而伪造"已清理"的结论；如果那枚令牌曾经用过，轮换它。

### 2.4 公开地址

代码与文档里保留了历史部署地址 `https://android-mcp-hub.causticrry.workers.dev`。
它不是凭据，但会暴露部署者身份与可用端点。本轮已做两件事：

- Android 端的**默认网关地址改为空**（`GatewayViewModel.DEFAULT_GATEWAY_BASE_URL = ""`），
  新安装不再预填维护者的 Worker，避免把私有网关当成公共中转；
- README 明确"维护者的 Worker 不替你的部署提供服务"。

仍保留在历史文档/CI 配置里的地址不属于秘密；若你不希望公开，可在后续提交中
改为占位符（不会从 git 历史消失）。

### 2.5 测试与示例中的"令牌"

以下都是**明显的假值**，不构成泄漏：

- `gate0-local-test-device-token-not-a-secret`
- `GATE2-FAKE-ROTATED-TOKEN`
- `hub_client_workerd-fixture`
- `Bearer super-secret-token-value`（Header 策略单测）
- `.dev.vars.example` 中的 `REPLACE-WITH-…`

CI 全部通过 `${{ secrets.* }}` 读取真实值，没有把值写进 workflow。

---

## 3. 依赖许可证

### 3.1 Worker 运行时依赖（会被打包进 Worker）

| 包 | 版本 | 许可证 |
| --- | --- | --- |
| `@modelcontextprotocol/sdk` | 1.32.1 | MIT |
| `@cfworker/json-schema` | 4.1.1 | MIT |
| `ipaddr.js` | 2.2.0 | MIT |

### 3.2 Worker 开发依赖（不进入运行时产物）

| 包 | 版本 | 许可证 |
| --- | --- | --- |
| `wrangler` | 4.149.0 | MIT OR Apache-2.0 |
| `vitest` | 4.1.11 | MIT |
| `@cloudflare/vitest-pool-workers` | 0.23.0 | MIT |
| `typescript` | 5.9.3 | Apache-2.0 |
| `tsx` | 4.20.6 | MIT |
| `ws` | 8.18.3 | MIT |
| `@types/node` / `@types/ws` / `@cloudflare/workers-types` | — | MIT / Apache-2.0 |

完整树（含传递依赖）统计：MIT 254、Apache-2.0 41、ISC 10、BSD 系列 5、
MPL-2.0 12、LGPL-3.0-or-later 20、`Apache-2.0 AND LGPL-3.0-or-later` 6、
`MIT OR Apache-2.0` 6、CC0-1.0 1、0BSD 1。

MPL / LGPL 条目全部来自 `wrangler` 的构建工具链（`lightningcss`、`sharp` /
`@img/sharp-libvips-*`），属于**开发期二进制**，不会被 `wrangler deploy`
打包进 Worker，也不会随 APK 分发。若你要再分发 `node_modules` 或修改这些库，
需分别遵守 MPL-2.0 / LGPL-3.0 的义务；仅把它们当作构建工具使用不触发这些义务。

### 3.3 Android 运行时依赖（进入 APK）

| 依赖 | 许可证 |
| --- | --- |
| AndroidX Core / Lifecycle / Activity / Navigation | Apache-2.0 |
| Jetpack Compose（UI / Foundation / Material3） | Apache-2.0 |
| Kotlin 标准库 / kotlinx-coroutines | Apache-2.0 |
| OkHttp / Okio | Apache-2.0 |

APK 内没有 GPL/AGPL/MPL/LGPL 代码。

### 3.4 Android 测试依赖（不进入 APK）

| 依赖 | 许可证 | 说明 |
| --- | --- | --- |
| JUnit 4 | EPL-1.0 | 仅测试 |
| Robolectric | MIT | 仅测试 |
| MockWebServer | Apache-2.0 | 仅测试 |
| `org.json:json` | JSON License（"Good, not Evil"） | 仅 JVM 测试；Android 运行时用系统内置实现 |

JSON License 属于宽松但措辞特殊的许可证，OSI 未认证。它只用于测试，
不随 APK 分发；如果你计划把测试代码作为独立库再分发，需要单独评估。

---

## 4. 许可证文件：当前缺失

仓库根目录**没有** `LICENSE` / `COPYING` 文件。这意味着：

- 默认版权法下，他人没有获得复制、修改、分发的许可；
- "公开仓库"不等于"开源"；
- 本审计**不能**替仓库所有者选许可证。

建议（需所有者拍板）：

| 选项 | 适合 | 注意 |
| --- | --- | --- |
| **MIT** | 希望尽量低摩擦地被个人项目复用 | 无专利授权条款 |
| **Apache-2.0** | 希望有明确专利授权与 NOTICE 机制 | 需要保留 NOTICE、附带许可证副本 |
| **AGPL-3.0** | 希望托管服务也必须回馈 | 与"私有网关"定位冲突，客户端/服务端都会被覆盖 |

选定后需要：

1. 在根目录加入 `LICENSE`（Apache-2.0 还需 `NOTICE`）；
2. 在 README 顶部加一段许可证声明；
3. 为 Apache-2.0 依赖补 `THIRD_PARTY_NOTICES.md`（至少列出 AndroidX、Compose、
   Kotlin、OkHttp、MCP SDK）；
4. 若发布 APK，在「关于」页或随附文件中提供相同的许可证文本。

---

## 5. 发布前检查清单

- [ ] 选定并加入 `LICENSE`（当前**未完成**，阻塞正式开源发布）；
- [ ] 复核 `test-reports/credential-check.log` 中的两枚摘要，确认对应令牌已废弃或轮换；
- [ ] 确认 `worker/.dev.vars`、`android/local.properties`、任何 `.jks` 都未被跟踪；
- [ ] 确认 GitHub Secrets 中保存的是**轮换后**的值，而不是曾经出现在本地文件里的值；
- [ ] 若曾经把真实令牌贴进 Issue / 聊天 / 提交，先轮换，再考虑清理历史；
- [ ] 运行一次 `npm run typecheck && npm run test` 与
      `./gradlew :app:testDebugUnitTest :app:lintDebug`；
- [ ] 用 `git log --all -p -G '<真实令牌片段>'` 自查一次（片段只在本机终端使用，
      不要写进任何文件）；
- [ ] 发布说明中如实写明：未做真机验收、未做真实客户端验收；本轮代码已部署到维护者测试 Worker
      （版本与运行链接见 README），但这**不**代表读者自己的部署已完成。

---

## 6. 残余风险

1. **历史不可撤销**：即使删除文件，旧的提交仍可能被克隆。凭据泄漏的正确处置是轮换。
2. **摘要不是零风险**：低熵明文的 SHA-256 可被穷举；设备令牌应使用 32 字节随机值
   （`SELF_HOSTING.md` 已给出生成命令）。
3. **公开地址**：历史文档中的 Worker 地址会继续存在；它不带凭据，但会暴露端点。
4. **测试夹具**：假令牌形状与真令牌相似，未来的扫描工具可能误报；保持
   `not-a-secret` / `fixture` 这类显式命名可降低噪音。
5. **依赖漂移**：本清单基于当前 `package-lock.json`；升级依赖后需重新生成。
