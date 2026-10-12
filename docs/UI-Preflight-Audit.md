# Android MCP Hub · UI 架构改版前置审计

> 本轮角色：**独立技术审计员**。目标是给即将进行的 Compose UI 改版一个可靠技术基线。
> **本轮只审计，不改架构、不改代码、不提交、不部署。**
>
> 阅读约定 —— 本文每条结论都带标签：
> - **【事实】**：本次实际读到的代码/产物/命令输出，附 `路径:行号`。
> - **【判断】**：基于事实的推理，可能被后续验证推翻。
> - **【建议】**：实施建议，属于下一轮的工作范围，本轮不执行。

---

## 1. 审计范围与实际基线

### 1.1 实际审计的分支与 SHA

**【事实】** 审计对象是仓库 `/root/android-mcp-hub`，本地 `git rev-parse HEAD` = `d05ca19eea3ec49c3fec27d9052f0062fbe1df09`，所在分支 `feat/gate2-core`；`git ls-remote origin` 显示远程 `refs/heads/feat/gate2-core` 与之**完全一致**（无未推送提交）。

| 项目 | 值 | 证据 |
| --- | --- | --- |
| 远程仓库 | `wenqv4617-art/android-mcp-hub`（私有） | 既有交付流程 |
| 默认分支 | **`gate0`** | `git ls-remote --symref origin HEAD` → `refs/heads/gate0` |
| `gate0` SHA | `39ef38eec151b4bb21b4e6cac933ec5221a12302` | `git ls-remote origin` |
| `feat/gate2-core` SHA | `d05ca19eea3ec49c3fec27d9052f0062fbe1df09` | `git ls-remote origin`，等于本地 HEAD |
| 远程分支总数 | **2 个**（`gate0`、`feat/gate2-core`） | `git ls-remote origin` |
| 是否存在 `main` | **不存在** | 同上 |
| `gate0..feat/gate2-core` | **14 个提交** | `git rev-list --count` |
| `feat/gate2-core..gate0` | **0 个提交** | 同上 → `gate0` 可 fast-forward 到 Gate 2 |
| 本地其它分支 | `test/gate1.5-reliability` = `055095f`，**是 `feat/gate2-core` 的祖先**，无未合并内容 | `git merge-base --is-ancestor` |
| tag / stash | **都没有** | `git tag`、`git stash list` 均空 |
| 工作树 | 仅一处未跟踪：`?? docs/Gate1.5-Stage0-Test-Baseline.md`（37,895 字节，按用户要求**保持不提交**） | `git status --short` |
| 仓库跟踪文件数 | 155 | `git ls-files \| wc -l` |

**【判断】** 当前**没有任何"游离在分支外的未合并功能"**：`gate0` 是 `feat/gate2-core` 的严格祖先，两个远程分支之外没有别的工作线。

### 1.2 本次实际跑过的验证（在同一 SHA 上）

**【事实】** 为了让"改版前基线"是真数字，审计期间实际执行了（只读，不改文件）：

| 检查 | 命令 | 结果 |
| --- | --- | --- |
| Worker 类型检查 | `npm run typecheck` | exit 0 |
| Worker 单测 | `npx vitest run --config worker/vitest.config.ts` | **14 个测试文件 / 331 条通过 / 0 失败** |
| 本地端到端 | `npm run e2e`（真 HTTP + 真 WebSocket + 官方 MCP SDK + mock 手机） | **78 通过 / 0 失败** |
| Android 单测 | `ANDROID_HOME=/opt/android-sdk ./gradlew --no-daemon :app:testDebugUnitTest --rerun` | BUILD SUCCESSFUL，19 个 XML 聚合 **255 条 / 0 failures / 0 errors / 0 skipped** |
| Android lint | `./gradlew --no-daemon :app:lintDebug` | **0 errors, 30 warnings** |
| Manifest 契约 | `./gradlew --no-daemon :app:verifyManifestClasses` | 全部命中 dex（含 `SplashActivity`） |
| 构建产物 | `:app:assembleDebug` | `android/app/build/outputs/apk/debug/app-debug.apk`，**13,074,903 字节**，sha256 `0f723bad099780c860e4284afd011e03be69e2ce25219272aa9a24d861e0f4b8` |

**【事实】** 未被执行的检查（**不得当成通过**）：真实 Cloudflare 部署、真机端到端联调、真实 `workerd` 运行时（本容器永久不可用，见 §9.4）、Compose UI 渲染/交互（无仪器测试条件，见 §8.4）。

### 1.3 关键文件路径清单

Android（`android/app/src/main/java/com/androidmcphub/app/`，22 个 .kt / 4575 行）：

| 文件 | 行数 | 职责 |
| --- | --- | --- |
| `ui/HubScreen.kt` | 646 | **当前唯一界面**（全部 Compose UI） |
| `ui/HubViewModel.kt` | 177 | 界面状态与动作 |
| `ui/Theme.kt` | 69 | 配色（`colorScheme` + 语义色） |
| `protocol/PhoneLink.kt` | 597 | 连接状态机、帧收发、工具请求处理 |
| `protocol/PhoneLinkProtocol.kt` | 345 | 帧解析/编码、错误码闭集 |
| `protocol/PingResponder.kt` | — | `phone_ping` 应答与去重 |
| `capability/CapabilityDispatcher.kt` | 354 | 能力注册表、白名单、7 步校验 |
| `capability/BatteryCapability.kt` | — | 唯一内置能力 `battery_get_status` |
| `service/PhoneLinkService.kt` | 445 | 前台服务、通知、`LinkBus`、能力白名单（唯一实例化点） |
| `net/PhoneLinkClient.kt` | 340 | OkHttp WebSocket、重连 |
| `core/HubPrefs.kt` | — | SharedPreferences 配置 + 能力总开关 |
| `core/DeviceTokenStore.kt` / `core/AndroidSecretCipher.kt` | — | 设备令牌存储与 Keystore 加密 |
| `SplashActivity.kt` / `MainActivity.kt` / `HubApplication.kt` | — | 启动页 / 单 Activity 宿主 / Application |

测试：`android/app/src/test/**`（19 个文件 / 4697 行 / 255 条，**全 JVM**）；Worker：`worker/test/**`（14 文件 / 331 条）+ `e2e/run-local-e2e.ts`（78 项）。

---

## 2. 当前 UI 架构概况

### 2.1 结构：单 Activity + 单屏 + 一文件到底

**【事实】**
- 入口：`SplashActivity`（`AndroidManifest.xml:35-43`，唯一带 `LAUNCHER` intent-filter）→ 900ms 后 `SplashActivity.kt:64-75` 显式启动 `MainActivity`。
- `MainActivity.kt:20-40`：`enableEdgeToEdge()`（:28）+ `setContent { AndroidMcpHubTheme { Surface { HubScreen() } } }`（:29-38）；根 Composable **无参数**，靠 `viewModel()` 默认取 VM（`HubScreen.kt:78`）。
- `HubScreen.kt` 一个 646 行文件里塞了 **1 个页面级 + 7 个 private 小块**：

| Composable | 行号 | 规模 | 性质 |
| --- | --- | --- | --- |
| `HubScreen(vm)` | :77-239 | 163 行 | 页面级，`LazyColumn` + 7 个 item |
| `StatusCard(state, nowMs, serverUrl, serviceRunning, 5×Color)` | :269-315 | 47 行 | 可复用 |
| `PingCard(lastPing, lastError, onClearError)` | :317-404 | 88 行 | 可复用 |
| `PingRow(label, value)` | :406-421 | 16 行 | 可复用 |
| `AdvancedSettingsCard(draft, tokenVisible, expanded, capabilitiesEnabled, 6 回调)` | :423-583 | **160 行** | 页面级规模（8 个表单字段 + 开关 + 保存） |
| `NumberField(label, value, onValueChange, decimal)` | :585-604 | 20 行 | 可复用 |
| `LogRow(entry)` | :606-641 | 36 行 | 可复用 |
| 非 Composable：`statusText` :242-253、`statusDetail` :255-267、`formatTime` :645-646 | — | — | **状态→文案映射，落在 UI 层** |

- 组件使用面很窄（**没有** `Scaffold`、`TopAppBar`、`AlertDialog`、`BottomSheet`、`NavigationBar`、`TabRow`、`IconButton`/`Icon`、`Checkbox`、`Snackbar`、`LazyRow`）；整屏只有一个 `LazyColumn`（:122，7 个 item）。
- `rememberSaveable` **全仓库 0 处**；唯一的 `remember` 是重连倒计时（:96-103）。

### 2.2 状态层：没有 UiState，是 10 条独立 StateFlow

**【事实】** `ui/HubViewModel.kt` 里没有任何 `UiState` 数据类（grep 为空），而是 10 条各自独立的 `StateFlow`：

```
draft :40          tokenVisible :45      advancedExpanded :50   capabilitiesEnabled :61
message :69        state :72             logs :75               lastPing :78
serviceRunning :81 lastError :87
```

动作方法：`updateDraft :89`、`toggleTokenVisible :94`、`toggleAdvanced :98`、`setCapabilitiesEnabled :105`、`dismissMessage :115`（**全仓库无调用者的死代码**）、`save :120`、`connect :136-155`、`clearLastError :158`、`clearToken :163`、`disconnect :170`、`clearLogs :174`。

**【事实】** `service/LinkBus.kt` **不存在**；`LinkBus` 是 `object`，定义在 `service/PhoneLinkService.kt:47-112`。字段只有 6 个：`log :50`、`state :53`、`logs :56`、`lastPing :59`、`lastError :71`、`serviceRunning :74`；写入口 `publishState :80`、`publishPing :94`、`publishServiceRunning :98`、`clearLastError :103`、`resetLink :108`。

**【事实】** ViewModel 只是**零转换转发**：`HubViewModel.kt:72-87` 直接 `= LinkBus.state` 等，**状态→中文文案/颜色的映射全部在 UI**（`statusText` :242、`statusDetail` :255、颜色映射 :281-287）。

**【事实】** `protocol/PhoneLink.kt:24-44` 的 `LinkState` 是 7 个变体的 sealed interface：`Idle :25`、`Connecting :26`、`Connected(generation: Long) :27`、`Reconnecting(attempt, delayMs, deadlineMs) :28`、`AuthFailed(detail) :29`、`Revoked(detail) :42`、`Error(message) :43`。

### 2.3 主题与样式：只有配色，没有字体/间距/形状体系

**【事实】**
- `ui/Theme.kt:14-22` 只配 `colorScheme`（`LightColors` / `DarkColors`；`BrandBlue #0D3B66 :11`、`BrandBlueDark #9EC5E8 :12`）。
- `MaterialTheme(...) :65-68` **既没有传 `typography=` 也没有传 `shapes=`** → 字体与圆角全部是 Material3 默认档（**判断**：这是"未自定义"，不是"自定义成默认值"）。
- 语义色单独一套：`StatusPalette :30-36`、`LightStatus :38-44`、`DarkStatus :46-52`、`statusPalette() :55-57`。
- **没有 `dimens.xml`**；`res/values/` 只有 `colors.xml`（2 项）、`strings.xml`（17 条）、`themes.xml`（2 个 style）。
- 硬编码统计：`HubScreen.kt` 里 **35 处 `.dp` 字面量**、**0 处 `.sp`**、6 处 `Color(0x…)`（:399、:610、:611 的深浅色三元式，与 `Theme.kt` 的 `Light/DarkStatus` 重复定义）；`Theme.kt` 里 13 处十六进制颜色 + `Color.White`。唯一自定义形状：`RoundedCornerShape(6.dp)`（:346）。

### 2.4 文案：100% 硬编码在 Kotlin

**【事实】** `res/values/strings.xml` 只有 17 条，全部是通知/前台服务类（`:3-30`），**没有一条主界面文案**；`ui/` 下 `R.string` / `stringResource` 出现 **0 次**。`HubScreen.kt` 里字符串字面量 68 个（含中文 30 个），`HubViewModel.kt` 也带中文字面量（:65/:109/:111/:125/:127/:140/:145/:149/:166）。

### 2.5 导航：没有导航

**【事实】** `androidx.navigation` 在 `libs.versions.toml` 与 `build.gradle.kts` 中 **0 处**；`NavHost`/`navController` 全仓库 0 处；无 Fragment；无对话框/底部弹窗；页面可见性靠 `advancedExpanded` 的 `if (!expanded) return@Column`（:452）和一个内联 `Text` 提示（:177-188）。通知栏的 `PendingIntent` 直接指向 `MainActivity`（`launchMode="singleTop"`）。

### 2.6 UI 与业务逻辑的耦合点

**【事实】** `HubScreen.kt` 的非 UI import 只有 5 个：`core.HubDraft :61`、`core.LogEntry :62`、`core.LogLevel :63`、`protocol.LinkState :64`、`protocol.PingOutcome :65`；**没有** import `service/`、`net/`、`capability/`、`core/HubPrefs`。

- **唯一实质越界**：UI 直接对协议层的 `LinkState` 做穷举 `when`（`:242`、`:255`、`:281-287`）。
- 直接用的平台 API：`ClipboardManager`/`Toast`（`:350-359`）、运行时通知权限（`:106-120`）。
- **UI 层不碰** WebSocket、HTTP、协议帧、Keystore、SharedPreferences（存储经 `core/HubPrefs.kt:23/:26`，服务经 `PhoneLinkService` 的两个静态方法）。
- **【事实】** Android 主源码里 **0 处** MCP / Cloudflare 概念（89 处 `mcp` 命中全是包名 `com.androidmcphub.app` 与注释里的 `example.workers.dev`）；手机只认 `phone-link-v1` 帧（`PhoneLinkProtocol.kt:7`）。

**【判断】** "UI 与 Cloudflare/MCP 协议解耦"这件事**现状是及格的**，唯一需要收口的是 `LinkState`/`PingOutcome` 直接进 UI。

### 2.7 用户动作 → 调用链（迁移可行性）

| 动作 | 触发点 | 链路 | 可否原样搬 |
| --- | --- | --- | --- |
| 「连接」 | `HubScreen.kt:163-168` | `vm::connect` → `HubViewModel.kt:136-155` → `PhoneLinkService.connect :430-437` → `ACTION_CONNECT :426` → `handleConnect :184-195` → `PhoneLinkClient` | 可 |
| 「断开」 | `:170-175` | `vm::disconnect` → `HubViewModel.kt:170-172` → `PhoneLinkService.disconnect :440-443` | 可 |
| 通知栏「断开」 | `PhoneLinkService.kt:406`（action :393-398） | 同上 | 可 |
| 高级设置 8 个字段 | `:454-540` | `vm::updateDraft` → `HubViewModel.kt:89-92` | 可（整块搬去「设置」） |
| 「保存」 | `:573-580` | `vm::save` → `HubViewModel.kt:120-129` → `HubPrefs.save :68` / `saveToken :93` | 可 |
| 能力总开关 | `:566-570` | `vm::setCapabilitiesEnabled` → `HubViewModel.kt:105-113` → `HubPrefs.saveCapabilitiesEnabled :64` | 可 |
| 「清空」日志 | `:219` | `vm::clearLogs` → `HubViewModel.kt:174-176` → `LinkBus.log.clear :58` | 可 |
| 「清除」最近错误 | `:394` | `vm::clearLastError` → `HubViewModel.kt:158-160` | 可 |
| 长按复制 `request_id` | `:350-360` | 直接 `ClipboardManager` + `Toast`（绕过 ViewModel） | 可，但**建议**收进 VM/工具层 |
| 启动页跳转 | `SplashActivity.kt:64-75` | `delay(900)` → `goToHub()` | 可 |

**【事实】** `PhoneLink.kt:472-492` 的工具请求处理只用环形日志记录（`log(LogLevel.INFO/WARN, "工具调用 tool=… 成功/失败…")`），**没有任何结构化事件送到 UI**。

---

## 3. 可复用组件清单（按现状可直接复用性排序）

| 组件（现位置） | 复用价值 | 需要的改造 | 标签 |
| --- | --- | --- | --- |
| `StatusCard`（`HubScreen.kt:269-315`） | 高：首页与服务页都要 | 提为公开、去掉 5 个 Color 参数改成从主题/展示模型取 | 【建议】 |
| `PingRow`（`:406-421`） | 高：通用 key-value 行，工具结果展示也能用 | 提为 `InfoRow(label, value)` 公开组件 | 【建议】 |
| `NumberField`（`:585-604`） | 高：设置页多处数字输入 | 提为公开、加错误态参数 | 【建议】 |
| `LogRow`（`:606-641`） | 中：只有服务/诊断页用 | 提为公开 `LogLine` | 【建议】 |
| `PingCard`（`:317-404`） | 中：诊断信息卡 | 把 `ClipboardManager`/`Toast` 逻辑挪出 UI，组件只负责渲染 | 【建议】 |
| `AndroidMcpHubTheme`（`Theme.kt:60-68`） | 高：全局唯一入口 | 补 `typography` / `shapes` / 间距常量 | 【建议】 |
| `StatusPalette` + `statusPalette()`（`Theme.kt:30-57`） | 高：语义色思路正确 | 保留，只把它与"展示模型"对齐（见 §4.1） | 【建议】 |
| `AdvancedSettingsCard`（`:423-583`） | 不适用跨页复用 | **整块成为「设置」页主体**，需按字段再拆小块 | 【建议】 |
| 「连接/断开/保存/清日志」等动作（`HubViewModel.kt`） | 高：四页共用 | 抽成共享的动作层（见 §4.4） | 【建议】 |
| 纯函数 `statusText`/`statusDetail`/`formatTime`（`:242-267`、`:643-646`） | 高：**可测**的纯逻辑 | 上移到展示模型层并补 JVM 单测 | 【建议】 |

**【判断】** 现有没有任何**跨页公开**组件 —— 7 个 Composable 全是同文件 `private`。拆四页时"提取公共组件"是**必做项**，不是可选项，否则会出现同一段 30 行的卡片代码在四个文件里各写一遍。

---

## 4. 需要调整的结构

### 4.1 状态映射必须从 UI 上移（P0）

**【事实】** `LinkState` → 文案/颜色的穷举 `when` 出现在 UI（`HubScreen.kt:242-253`、`:255-267`、`:281-287`），而 `LinkState` 有 7 个变体。

**【判断】** 拆成四页后，只要每页都显示一点连接状态（首页的大卡片、服务页的细节、设置页的"已连接/未连接"提示），这套 `when` 就会被复制 2–4 份；下次给 `LinkState` 加变体（编译器会强制每份都改）成本成倍。

**【建议】** 在 ViewModel/展示模型层产出一个纯数据模型，例如：

```kotlin
data class LinkStatusView(
  val title: String, val detail: String, val tone: StatusTone, // tone: Idle/Connecting/Ok/Warn/Error
  val generation: Long?, val retryAtMs: Long?, val actionLabel: String?,
)
fun LinkState.toView(serviceRunning: Boolean, nowMs: Long): LinkStatusView
```

UI 只读 `LinkStatusView`，不再 import `protocol.LinkState`。`PingOutcome` 同理（`PingOutcome.kt:49-63`）。

### 4.2 「工具」页目前**没有数据源**（P0）

**【事实】**
- `LinkBus`（`PhoneLinkService.kt:47-112`）只有 `state/logs/lastPing/lastError/serviceRunning`，**没有工具目录、没有工具活动**。
- 手机侧能力注册表是**编译期闭集**：`CapabilityDispatcher.kt:146` `registry = capabilities.associateBy { it.name }`，构造参数在 `:133`，**没有运行期注册接口**（注释 `:142-145` 明确）。
- 协议里没有"工具目录下发"帧：`welcome` 只带 `device_id`/`connection_generation`/`server_time`/`heartbeat_interval_ms`/`ping_timeout_ms`/`max_pending_requests`/`protocol_version`（`PhoneLinkProtocol.kt:184-196`）。
- 工具调用只写进环形日志：`PhoneLink.kt:481-488`。
- UI 里关于工具的唯一内容是一句**写死的文案**：`HubScreen.kt:557` "当前已启用的能力只有 battery_get_status…"。

**【判断】** 直接把当前的"设置页里那行开关"搬去「工具」页，会得到一个**空壳页**：没有工具列表、没有调用记录、没有结果。

**【建议】** 让「工具」页的数据来自"服务端工具目录 + 本机可执行能力"的交集，而不是硬编码：
1. 新增一条下行元数据（二选一：`welcome` 里带 `tools: [...]`，或连接后一次 `tool_catalog` 请求/响应）。
2. Android 侧用 `CapabilityDispatcher` 已注册的 `Capability`（`name` / `allowedArguments`，`CapabilityDispatcher.kt:55-71`）与目录求交集，产出 `ToolDescriptor`（展示名 + 参数形状 + 本机是否已启用）。
3. 工具活动（调用中/成功/失败/耗时）新增一条结构化事件通道（`LinkBus` 新增 `toolActivity`，由 `PhoneLink.kt:481-488` 处发布），UI 只渲染列表。

### 4.3 HubViewModel 需要按页拆分（P0/P1）

**【事实】** 现在 10 条 StateFlow + 11 个方法全在 `HubViewModel.kt`（177 行），被唯一页面消费。

**【判断】** 四页共用一个 VM 会导致：(a) 每页都能看到全部状态，边界模糊；(b) 设置页的表单 `draft` 与首页的 `state` 生命周期不同（前者要按用户输入活着，后者跟随服务）；(c) 未来工具页的 VM 需要 `toolActivity`，继续塞进同一个类会膨胀。

**【建议】** 保留一个**共享的动作层**（连接/断开/保存/清日志，可用一个 `LinkActions`/`HubContainer` 或把 `HubViewModel` 降级为 shared VM），每页再有薄 `ViewModel`：`HomeViewModel` / `ToolsViewModel` / `ServiceViewModel` / `SettingsViewModel`，各暴露一个 `UiState`。**不要**引入 Hilt/Koin，用默认 `viewModel()` + 手工传递即可（当前工程只有一个 `:app` 模块）。

### 4.4 主题体系与文案策略（P1）

**【事实】** 无 `typography`/`shapes`/`dimens`；35 处 `.dp` 硬编码；界面文案 0 处走资源。

**【建议】**
- 主题：`Theme.kt` 迁到 `ui/theme/`，补 `Type.kt`（至少 Title/Body/Label 三档）、`Shapes.kt`、`Spacing.kt`（4/8/16/24 四档常量），**先建 token 再拆页**，否则拆出来的是四份不同的间距。
- 文案：**不建议**在改版里一次性把 68 条字面量全搬进 `strings.xml`（噪声大、回归风险高）；建议**新页面新文案走 `stringResource`**，老卡片随搬迁顺手迁移，且**只迁中文界面文案**，日志/协议字符串留在 Kotlin。

### 4.5 工具名与工具清单的硬编码点（P1）

**【事实】**
- Worker 唯一注册表：`worker/src/mcp/tools.ts:182` `TOOL_REGISTRY = [PHONE_PING_TOOL, BATTERY_GET_STATUS_TOOL]`；工具对象接口 `tools.ts:38-53`（`name:39`、`title:40`、`description:41`、`transport:"ping"|"tool":42`、`allowedArgs:49`、`inputSchema:50`、`outputSchema?:51`、`annotations?:52`）。
- `tools/list` 的字段是**手写映射**：`worker/src/mcp/http.ts:450-463`（`name`/`title`/`description`/`inputSchema` + 可选 `outputSchema`/`annotations`），**不透出 `transport` 与 `allowedArgs`** → **加字段必须同时改这里**。
- `parseToolArguments`（`tools.ts:213`）白名单 = `COMMON_ARG_KEYS`（`device_id`/`timeout_ms`，`tools.ts:67`）∪ 工具 `allowedArgs` ∪ **无条件加入的 `"echo"`**（`tools.ts:222`）→ **【判断】** 任何未来工具都会静默接受 `echo`，与其 `allowedArgs` 声明不一致（是兼容还是遗留需人确认）。
- 工具名格式规则**三份独立副本**：`android/…/capability/CapabilityDispatcher.kt:19` `Regex("^[a-z][a-z0-9_]{0,63}$")`、`worker/src/config.ts:287` 同形状正则；`ToolDefinition.name` 本身**没有任何格式校验**（`tools.ts:38-53` 无正则/长度检查）→ **【判断】** 在 Worker 注册一个 `Battery_Get_Status` 或带横线的名字能编译通过，只在真机上被 `unsupported_tool` 拒掉（未找到补位守卫）。
- 清单不同步的**高危硬编码点**（按"最容易漏改"排序）：
  1. `worker/src/index.ts:111` `/healthz` 的 `capabilities.tools: ["phone_ping"]` —— 独立于 `TOOL_REGISTRY`，且**没有测试联动**（本次实测：确实仍是 `["phone_ping"]`）。
  2. `worker/src/mcp/http.ts:55` INSTRUCTIONS 文案 "当前只提供一个工具 phone_ping" —— 现状**已经不实**（已有 `battery_get_status`）。
  3. `worker/src/oauth/pages.ts:82` 同意页 "<strong>phone_ping</strong>" —— 现状**已经不实**。
  4. `android/…/ui/HubScreen.kt:557` UI 文案 —— 现状**已经不实**（只提 `battery_get_status`）。
  5. `tools/mock-phone/client.ts:87` `MOCK_CAPABILITIES` —— **第三张工具清单**，E2E 依赖它。
  6. `tools/gate1-smoke.mjs:74` 整表断言 `capabilities?.tools === ["phone_ping"]` —— 加工具必红（属故意守卫）。
- 手机侧真正的最小改动点**只有一处**：`android/…/service/PhoneLinkService.kt:214` 的 `capabilities = listOf(BatteryCapability(AndroidBatterySource(this)))`（名字取自 `BatteryCapability.NAME`，`BatteryCapability.kt:140`，**不是字面量**，这点是好的）。
- 通用管道是工具无关的：`enqueueRequest`（`phone-session.ts:1196`）、`settlePending`（`:1751`）、`CapabilityDispatcher.handle` 的 7 步校验（`:171-306`）、`encodeToolResult`（`PhoneLinkProtocol.kt:295-345`）、`toolOutcomeToResult`（`tools.ts:342-375`）—— **加工具不用碰它们**。
- 端到端链路（B 子代理逐段核对）：`http.ts:465` tools/call → `tools.ts:441` dispatchToolCall → `findTool :184` / `parseToolArguments :213` → `callPhoneTool :510` → `http.ts:105` → `phone-session.ts:445` 路由 → `:1120` handleToolRequest → 撤销/离线门禁 `:1152-1167` → 构造 `{type:"tool_request",…}` `:1170` → `enqueueRequest :1196` → 手机 `PhoneLinkProtocol.kt:171` 解析 → `PhoneLink.kt:472` → `CapabilityDispatcher.handle :171` → `encodeToolResult` → `phone-session.ts:1476` case "tool_result" → `handleToolResult :1677` → `settlePending :1751`。

### 4.6 其它需要小修的点（P2，非阻塞）

| 项 | 证据 | 说明 |
| --- | --- | --- |
| 死代码 | `HubViewModel.kt:115` `dismissMessage` | 全仓库无调用者 |
| 未使用 import | `HubScreen.kt:32` `CardDefaults` | 只出现 1 次（已实测） |
| 过期注释 | `PhoneLinkService.kt:102` | 说"界面『清空日志』顺带清 lastError"，实际 `clearLogs`（`HubViewModel.kt:174-176`）只清日志 |
| 版本号语义 | `PhoneLinkProtocol.kt:10` `APP_VERSION = "0.1.4-gate1"`、`libs.versions.toml:40` 同名同值、`http.ts:51` `SERVER_VERSION = "0.1.0-gate1"` | **【判断】** 已经发了 Gate 2 但版本串仍写 gate1，服务端无法用版本区分 Gate 1/Gate 2 客户端 |
| lint 噪音 | `HubScreen.kt:643` `ConstantLocale`（1 条）、`ApplySharedPref`（3 条，`HubPrefs`） | 都是 0 error |
| 陈旧证据 | `test-reports/vitest.log` 记 "8 passed / 223 tests"（现状 14 文件 / 331 条）；`dist/Android-MCP-Hub-Gate1-debug.apk`（10,318,558 B，sha256 `2467a034…`）为 Gate 1 产物，与当前 build 目录 APK 不是同一次 | 建议改版前收口 |
| 未入库基线 | `docs/Gate1.5-Stage0-Test-Baseline.md`（37,895 B，未跟踪） | 按用户要求保持不提交 |

---

## 5. 风险分级

### P0 —— 不改就会让改版失控

| # | 风险 | 依据 | 应对 |
| --- | --- | --- | --- |
| P0-1 | **UI 层零测试锚点**：`ui/` 下无任何单测（`src/test` 只有 capability/core/net/protocol/service），无 `@Preview`，无仪器测试 | §8.2、§8.4 | 改版第一步先给"状态→展示模型"的纯函数补 JVM 单测；每个搬迁步骤保持 `255 条全绿` |
| P0-2 | **状态映射散在 UI**，拆页必然复制 | §4.1 | 先落 `LinkStatusView` 映射再拆页 |
| P0-3 | **「工具」页无数据源** | §4.2 | 先定工具目录/活动通道，再建页面 |
| P0-4 | **单一巨型 VM**（10 StateFlow）被四页共享 | §4.3 | 拆薄 VM + 共享动作层 |

### P1 —— 必须在改版中一并处理

| # | 风险 | 依据 |
| --- | --- | --- |
| P1-1 | 没有 typography/dimens/shapes，35 处硬编码 `.dp` → 四页各自为政 | §2.3 |
| P1-2 | 界面文案 0 处走资源，拆页后维护成本放大 | §2.4 |
| P1-3 | 无 navigation 依赖与路由约定（通知→页面、系统返回键行为没有落点） | §2.5、§8.1 |
| P1-4 | 工具清单/文案硬编码 6 处（其中 3 处现在就已不实） | §4.5 |
| P1-5 | 工具名正则三份副本 + `ToolDefinition.name` 无校验 | §4.5 |

### P2 —— 可延后

| # | 项 | 依据 |
| --- | --- | --- |
| P2-1 | Compose UI 测试栈（androidTest + `ui-test-junit4` + runner）与截图回归 | §8.4：环境无模拟器，收益低 |
| P2-2 | CI 无 lint 门禁（K12） | `android-debug-apk.yml` 无 `lintDebug` |
| P2-3 | 陈旧证据/文档数字不一致（vitest.log 223 vs 331；lint 29 vs 30；`TEST_REPORT.md:95` 提到已删除的 `--print-classes`） | §4.6 |
| P2-4 | 死代码/未用 import/过期注释/版本串 | §4.6 |
| P2-5 | 通知与服务生命周期的自动化测试 | §8.3 |
| P2-6 | "60 秒授权确认"实现 | 用户任务书明确本轮不实现，见 §7 |

---

## 6. 推荐的 UI 开发基线

**【建议】**（三选一，按推荐度排序）

1. **推荐：从 `feat/gate2-core` @ `d05ca19` 拉一条新分支**（例如 `feat/ui-revamp`）。
   理由：它就是本次审计的实测基线（255 / 331 / 78 / 0 error 全绿），包含 Gate 2 的工具通道与撤销修复；`gate0` 可 fast-forward 到它，不会产生分叉。
2. 先把 `feat/gate2-core` 合并进 `gate0`（快进，无冲突），再从 `gate0` 拉 UI 分支。
   理由：与"默认分支"一致，GitHub 上的工作流/文档链接都指向它。
   代价：**这属于"合并进 gate0"**，按既有硬约束必须由你明确批准；且合并会让 `deploy-worker.yml` 的 Gate 2 临时部署路径失去意义（合并后应按计划撤掉那一段）。
3. 直接在 `feat/gate2-core` 上改 UI。
   代价：把"UI 改版"和"Gate 2 收尾"混在一条分支上，回滚粒度变差。**不推荐**。

**【事实】** 无论选哪条，`docs/Gate1.5-Stage0-Test-Baseline.md` 继续保持未跟踪；`main` 分支不存在，也不应新建（既有硬约束：不动 `main`、不自动部署）。

---

## 7. 权限与任务状态现状（含 60 秒确认的接口需求）

*(本节 = 权限专项只读审计 + 我本人对关键行号的复核；所有行号都在 `feat/gate2-core` @ `d05ca19` 上核对过。用户明确要求：**本轮不实现确认功能**，这里只列现状与接口需求。)*

### 7.1 现有权限策略

**【事实】Android 侧：一个布尔总开关**

- 存储：`core/HubPrefs.kt:162` `KEY_CAPABILITIES_ENABLED = "capabilities_enabled"`，默认 **false**（`HubPrefs.kt:171` `DEFAULT_CAPABILITIES_ENABLED = false`，仅"键不存在"时落 false）；读 `:61`、写 `:64-66`；该键被排除出"可下载配置"（`NON_CONFIG_KEYS :174`）。
- 策略读取时机：`service/PhoneLinkService.kt:213-216` 构造 `CapabilityDispatcher(capabilities = listOf(BatteryCapability(AndroidBatterySource(this))), policy = { prefs.capabilitiesEnabled() })` —— `policy` 是 **lambda，每次派发现读**（在 `capability/CapabilityDispatcher.kt:210` 求值）→ 用户改开关**不需要重连**。
- 关闭时的返回：`CapabilityDispatcher.kt:210-224` → `errorCode = NOT_PERMITTED`（wire 值 `PhoneLinkProtocol.kt:149`），message「本机已关闭只读能力调用（用户设置）」（`:221`）；Worker 归一为 `not_permitted`（`worker/src/phone/messages.ts:134-146`、`worker/src/phone/phone-session.ts:54-77`）。
- 运行时权限**只有 `POST_NOTIFICATIONS`**：声明 `AndroidManifest.xml:14`；请求点 `ui/HubScreen.kt:106-120`（连接成功后触发）；服务侧对无权限有降级（`PhoneLinkService.kt:344-360`：API 33 以下视为可发，`SecurityException` 有兜底）。其余 4 条是普通权限（INTERNET `:10`、ACCESS_NETWORK_STATE `:12`、FOREGROUND_SERVICE `:16`、FOREGROUND_SERVICE_SPECIAL_USE `:18`）。
- 界面上的开关就在设置卡片里：`HubScreen.kt:560-570`（文案「允许服务端调用本机只读能力」`:552`）。

**【事实】零信任：工具派发的 7 步（`capability/CapabilityDispatcher.kt:171-306`）**

1. `request_id` 去重（`:174-186`；窗口 `TOOL_DEDUP_CAPACITY = 256`，`:9`；重复帧回 `duplicate = true`，**不执行、不回帧**）
2. 工具名形状 + 白名单（`:188-196`；`TOOL_NAME_PATTERN = Regex("^[a-z][a-z0-9_]{0,63}$")` `:19`；未命中收敛成 `unsupported_tool`，且**不回显对端给的名字**，用 `<unknown>`，`:89`/`:194`）
3. 用户策略（`:210-224`，`NOT_PERMITTED`）
4. 多余参数（与 `Capability.allowedArguments` 比对）
5. 取值/类型（`Capability.validate :68`）
6. 执行（`Capability.execute :71`）
7. 错误码收敛（闭集 `PhoneLinkProtocol.kt:129-150`：`unsupported_tool`/`invalid_arguments`/`tool_failed`/`tool_unavailable`/`not_permitted`）

**【事实】Worker 侧的三道门**

- **升级阶段凭据**：`worker/src/index.ts:171-178`（必须 `X-Device-Id` + `Authorization: Bearer`，缺任一 → 401 且不提示细节）、`:181-184`（该设备无活跃凭据 → 401，防设备枚举）、`:220-221` + `:231-237`（sha256 + `timingSafeEqual`；不匹配 → 401，且"用已撤销旧凭据升级"时会推凭据级撤销通知）。
- **DO 侧凭据绑定**：`phone-session.ts:338`（内部头）、`:490`（缺头拒绝升级，fail-closed）、`:1406-1429`（每条上行消息用 `ws.attachment.credentialId` 复核，失效 → `error{device_revoked}` + `close(4002)`）。
- **撤销门禁**：`phone-session.ts:652-657`（`connectionRejection`）、`:668-682`（`revocationCheck`），在 ping（`:1062-1066`）与 tool（`:1149-1153`）路径上**先于**离线判断；拒绝码 `device_revoked`（HTTP 403）。
- **连接代次**：`SocketAttachment :272-285`；建连时 +1 并 `failStalePending`（`:542-576`）；旧代次回包丢弃（`:1636-1637` pong、`:1697-1699` tool_result）。
- **MCP 层还有**：`worker/src/mcp/tools.ts:449-457` 未知工具 → `invalidParams`；`:477-481` `PHONE_DISABLED_TOOLS` 命中 → `not_permitted`；`:484-494` `resolveDeviceId` → `unknown_device`/`ambiguous_device`/`device_revoked`；入口鉴权 `worker/src/mcp/http.ts:241-299`。

**【判断】** 现状是"**一个布尔总开关 + 编译期工具白名单**"，*没有*按工具、按参数、按次的授权概念，也没有"危险等级"。"60 秒确认"要加的是用户策略层里的**一条新状态**（第 8 个判定），而不是新的一套权限系统 —— 这一点决定了它不需要重构现有授权代码。

### 7.2 超时 / 去重 / 并发

**【事实】Worker（服务端）**

| 项 | 值 / 位置 |
| --- | --- |
| 请求超时默认值 | `worker/src/config.ts:93` `pingTimeoutMs: 10_000`（ping 与 tool **共用**：`phone-session.ts:1053`、`:1138` 缺省取它） |
| 超时夹取 | `[50, 60_000]`：`config.ts:120`、`phone-session.ts:1054`/`:1139` `Math.min(Math.max(Math.round(v),50),60_000)` |
| 工具参数 `timeout_ms` | 必须整数且在 `[50, 60000]`：`mcp/tools.ts:235-242`；schema `:96`/`:144` |
| 存活阈值 | `livenessTimeoutMs: 90_000`（`config.ts:97`，夹取 `[1_000, 900_000]` `:126`）；判活 `socketIsLive :995-999`；离线时**快速**失败而不拖满超时 `:1080-1091`/`:1159-1162` |
| 超时落点 | `enqueueRequest` 武装 `setTimeout`（`:1259-1261`）→ `settlePending(...,"timeout")`；结果码 `phone_timeout`（文案 `:1100` ping、`:1554-1557` 工具 alarm 分支）；**只有 metric `timeouts`++（`:1763`），没有独立日志事件**（对比存活超时 `phone_session.liveness_timeout :1020`） |
| 并发上限 | `maxPendingRequests: 16`（`config.ts:95`，夹取 `[1,256]` `:122`）；执行点 `phone-session.ts:1226-1231` → `too_many_pending_requests`；ping 与 tool **共用同一额度**（测试 `worker/test/phone-tool-channel.test.ts:338`） |
| 去重 | `pending` 表**键就是 `request_id`**（`:354`）；同 id 未完成 → **显式拒绝**（不是排队、不是幂等放行）：`duplicate_request_id`、HTTP 409、`pendingRejectedDuplicate++`（`:1218-1224`，注释 `:1208-1217` 说明"原实现会静默覆盖"）；两条通道共用同一张表 → 跨通道同 id 也拒（测试 `:182`） |
| 队列/背压 | **没有**：超限即拒 |
| MCP 侧挂起方式 | `mcp/http.ts:104-122` 直接 `await stub.fetch(DO)`（DO 内部再等手机回包）→ **HTTP 响应会被挂住到超时为止**；`tools/call` 派发链 `tools.ts:441` → `callPhoneTool :510` |

**【事实】Android（手机端）**

- 配置默认：`core/HubConfig.kt:15-20` `pingTimeoutMs = 10_000`、`heartbeatIntervalMs = 30_000`、`maxPendingRequests = 16`；可被服务端 `welcome` 覆盖（`protocol/PhoneLink.kt:412` 写 `pingTimeoutMs`、`:436` 记日志）。
- **没有请求级超时/看门狗**。只有会话级：`service/PhoneLinkService.kt:422` `STALE_SESSION_TIMEOUT_MS = 5 * 60 * 1000`、`:424` `WATCHDOG_INTERVAL_MS = 60_000`（`:139`/`:152` `postDelayed`、`:291-302` `checkStaleSession`）；链路静默判死 `PhoneLink.kt:386-394`（阈值 `max(2*heartbeat, pingTimeout)`）；OkHttp `connect/write Timeout(15s)`、`readTimeout(0)`（`net/PhoneLinkClient.kt:334-336`）。
- 去重：ping 侧 `PING_DEDUP_CAPACITY = 256`（`PhoneLinkProtocol.kt:16`）+ `PingResponder.firstSeen`（`PingResponder.kt:23-34`、`:101`）；工具侧 `TOOL_DEDUP_CAPACITY = 256`（`CapabilityDispatcher.kt:9`）。

**【判断】** "60 秒确认"会一次性踩到四个现成上限：① 请求超时上限**恰好**是 `60_000`（`phone-session.ts:1054`/`:1139`）；② 确认期间仍占 `maxPendingRequests = 16` 的额度；③ MCP 客户端（ChatGPT）自身超时不可控；④ 手机进程被系统杀死后 `START_NOT_STICKY`（`PhoneLinkService.kt:168`）不会自恢复，确认没有落点。

### 7.3 异步任务基础

**【事实】**

- **Worker 有完整的"挂起 + 落盘 + 闹钟结算"基础**（这是加确认流程最好的落点）：`pending: Map<string, PendingRequest>`（`:354`）、持久镜像 `storedPending`（`:356`，键 `:307`，字段 `StoredPending :246-262` = requestId/deviceId/generation/sentAt/deadline/timeoutMs/kind/toolName）、`PendingRequest.settle/timer`（`:265-270`）、`kind` 只有 `"ping" | "tool"`（`:260`）、`deadline = sentAt + timeoutMs`（`:1234`）、`alarm()`（`:1548-1562`）与 `rescheduleAlarm`（`:1915-1930`）、构造时清理残留（`:418` `phone_session.leftover_pending_discarded`）。
- **另有现成的"创建 → 查询 → 一次性消费 → 过期清理"形态可抄**：OAuth 同意记录 `worker/src/oauth/store-do.ts:487-535`（`consentId` 随机 `:528`、"先删后验"防反复提交 `:531-533`、清理 `:589-616`，含"DO 空闲即失内存"的理由 `:604-608`）。
- **"任务 / 进度 / 取消"概念完全不存在**：`worker/src` 与 Android 主源码里 `progress|cancel|job_id|task_id` 零命中（唯一命中是 `net/PhoneLinkClient.kt:274` 的 `webSocket.cancel()`）。
- **Android UI 无法感知在途请求**：`HubViewModel` 只有能力开关与即时动作；`PhoneLinkService` 的通知里只有一个「断开」action（`:391-398`）；`PhoneLink.kt:472-492` 的工具请求同步跑完即回，**不保存 request 生命周期**，只写环形日志。
- Worker 内部其实有可观测数据：`handleStatus :1305-1357` 暴露 `pending_requests :1353`、`pending_mirror :1354`、`connection_generation :1348`、`ms_since_last_activity :1351`、`metrics :1356`；但该路由（`ROUTE_STATUS :324`）**只在 DO 内部**，MCP 侧没有出口 → **【判断】** 前端要展示"进行中的请求"，今天只有两条路：新开一条事件通道，或让 `/status` 对 MCP 层开口（等于新增一个工具）。
- 【事实】界面里连"请求数上限"都只是只读展示：`HubScreen.kt:533-534`。

### 7.4 加"60 秒授权确认"需要的新接口（本轮**不实现**，只列需求）

**【建议】** 做成**协议级、与 UI 解耦**的一条通道，最小接口集如下：

1. **协议帧（三帧，两侧必须同步扩）**
   - 下行 `tool_confirm_request { request_id, tool_name, args_digest（参数摘要，不上行原值）, expires_at, prompt }`
   - 上行 `tool_confirm_result { request_id, decision: "approve" | "deny", decided_at, connection_generation }`
   - 下行 `tool_confirm_cancel { request_id, reason }`（服务端超时/断连时收口）
   - **不能复用**现有帧：`ServerError`（`worker/src/phone/messages.ts:39-43`）是单向、无回执通道；`ToolRequest`（`:52-64`）/`ToolResult`（`:72-78`）只有 `ok/result/error`，没有"用户尚未决定"的语义；`Welcome`（`:23-32`）只在建连时下发一次。要同步扩的联合类型与解析分支：`messages.ts:81`/`:105-111`/`:153-240` 与 `PhoneLinkProtocol.kt:24-92`/`:171`。
2. **错误码闭集各加一条**（现闭集 `messages.ts:113-121`/`:134-146`、`PhoneLinkProtocol.kt:129-150`）：建议 `not_confirmed`（用户拒绝）与 `confirmation_timeout`（确认窗口过期）。**不要**复用 `not_permitted` —— 那是"总开关关"的语义，混在一起审计就分不清"用户没开能力"和"用户拒绝了这次"。
3. **待确认队列（手机端，`CapabilityDispatcher` 之后的新一层）**
   - 数据结构：`request_id`、工具名、参数摘要、`expires_at`、状态（`pending`/`approved`/`denied`/`expired`）、连接代次。
   - 建议**一次只允许一个待确认请求**（UI 与超时管理简单）；超限是拒绝还是排队需要定。
   - 与去重窗口的交互必须先定：`dedup.firstSeen`（`CapabilityDispatcher.kt:174-186`，容量 256）会让"拒绝后同 id 重发"被窗口吞掉。
4. **时间预算必须由 Worker 显式决定（本次审计发现的真设计冲突）**：确认窗口 60s 与请求超时上限 60s 同值，若两者共享同一个 `deadline`（`phone-session.ts:1234`），工具执行预算会变成 **0**。两条出路（**必须二选一**）：
   - (a) 放宽确认场景的请求超时上限（例如允许 `timeout_ms` 到 90–120s），确认窗口计入总预算；
   - (b) 确认成功后**立即返回一个 pending 结果**，执行结果由客户端二次调用（或 MCP resource/轮询）取回 —— 这需要 MCP 层新增"查询/续取"语义。
   - **【判断】** (a) 改动小，但会拉长 MCP 请求的挂起时间（ChatGPT 侧超时未知）；(b) 更稳，但要动 MCP 语义。**本轮只提出问题，不做选择。**
5. **Worker 侧状态**：可在同一 DO 里复用 pending 机制（给 `kind` 加第三个值 `"tool_confirm"`，复用 `settlePending`/`timer`/`storedPending`），或另立 `storedConfirmations`（新 `STORAGE_KEY`，参照 `:307`）。**必须落盘**（DO 空闲即失内存，`:418` 会丢弃残留）。一次性消费语义抄 `store-do.ts:528-533`；TTL = `now + 60_000`。
6. **Android UI 需要一个全局确认宿主**：今天主源码里**没有任何 `Dialog`/`AlertDialog`**（grep 为空）→ 需要新增 `ConfirmDialog` 公共组件 + 根 Scaffold 级宿主（四页共享）；倒计时只能用 `expires_at` 差值渲染，**不能在 UI 里计时**；还要有"确认中"的可见状态（见 §7.3：今天连在途请求都看不到）。
7. **可选通知**：确认请求到达时发通知（复用 `PhoneLinkService` 的通知渠道与前台服务），点击进入确认；但 `POST_NOTIFICATIONS` 可能被用户拒绝，**不能把确认的唯一入口放在通知里**。
8. **安全约束（必须写进实现）**：
   - 决定绑定 `request_id` + `connection_generation`，换代次即作废（旧代次回包会被 `:1697-1699` 丢弃 —— 这既是保护也是坑）；
   - 参数摘要用摘要值（本机展示可用原文，但**上行与日志不传原值**）；
   - 拒绝理由不上行；
   - 一次性消费（防两次 approval 触发两次执行：客户端去重只挡第二个工具帧，挡不住服务端收到两条决定）。
9. **测试要求**：JVM 单测覆盖"确认期间超时 / 断连 / 换代次 / 重复确认"四种收敛；Worker 侧仿 `worker/test/phone-tool-channel.test.ts:232`（手机不回应 → 如实 `phone_timeout`）新增"未确认"用例。

**【判断】** 这批接口要同时动 `protocol/`（帧）+ `capability/`（新判定层）+ `service/`（状态通道与通知）+ `ui/`（弹窗与可见状态）**四层**，还要与 Worker 同步改协议与错误码闭集。所以"本轮不实现"是对的：**先把 UI 骨架、展示模型和状态通道做出来，等协议定稿再动确认**。

---

## 8. 构建与测试

### 8.1 构建方式

**【事实】** `android/settings.gradle.kts:31-32` 只有 `:app` 一个模块；`RepositoriesMode.FAIL_ON_PROJECT_REPOS`（`:24`）。`android/app/build.gradle.kts`：`namespace`/`applicationId` = `com.androidmcphub.app`（`:13`/`:17`）、`compileSdk=35`（`:14`）、`minSdk=26`（`:18`）、`targetSdk=35`（`:19`）、Java 17（`:38-41`、`:96-100`）、`buildFeatures{compose=true}`（`:43-46`）、`lint{abortOnError=true}`（`:76-78`）、`unitTests.isReturnDefaultValues=false`（`:80-87`，刻意不返回默认值）；**无** flavors、**无** signingConfig、**无** `testInstrumentationRunner`、**无** managedDevices。工具链：AGP 8.7.3、Kotlin 2.0.21、Gradle wrapper 8.11.1、composeBom 2024.12.01（`libs.versions.toml:11/:14/:21`、`gradle-wrapper.properties:3`）。

**【事实】** `android/gradle.properties:26` 有 `android.aapt2FromMavenOverride=/root/android-mcp-hub/android/tools/aapt2`（本机 aarch64 的 qemu 包装；`android-debug-apk.yml:38-41` 在 x86_64 CI 里 sed 关掉它）。**【判断】** 这一行是本地能构建的前提，**不要删**。

### 8.2 依赖与测试条件

**【事实】** `android/app/build.gradle.kts:102-129`：implementation 12 条（core-ktx / lifecycle ×3 / activity-compose / compose-bom / ui / ui-tooling-preview / foundation / material3 / coroutines-android / okhttp）、debugImplementation `ui-tooling`、testImplementation 4 条（junit / org.json / coroutines-test / mockwebserver）。**完全没有**：navigation-compose、androidx.test、ui-test-junit4、ui-test-manifest、espresso、robolectric、truth/hamcrest/mockk/turbine；**没有任何 `androidTestImplementation`**。

**【事实】** `android/app/src/androidTest` **不存在**（`ls` 报错，`git ls-files` 只有 `src/main`、`src/test`）；3 份 GitHub 工作流也**从未执行过**。

### 8.3 测试覆盖现状

**【事实】** Android **255 条 / 19 类，全部 JVM**（实测）：

| 分类 | 条数 | 文件 |
| --- | --- | --- |
| 线上字节与协议 | 105 | `PhoneLinkTest` 53、`PhoneLinkProtocolTest` 15、`ToolChannelProtocolTest` 15、`PingResponderTest` 9、`LogRedactionTest` 13 |
| 连接/重连/可靠性 | 67 | `ConnectGateTest` 21、`LinkBusTest` 13、`BackoffTest` 9、`SocketFailureTest` 9、`PhoneLinkClientTest` 8、`PhoneLinkReliabilityTest` 7 |
| 配置/持久化/地址/日志 | 40 | `DeviceTokenStoreTest` 18、`HubConfigTest` 8、`WsUrlTest` 8、`HubLogTest` 4、`CapabilitySwitchDefaultTest` 2 |
| 能力分发（Gate 2） | 41 | `CapabilityDispatcherTest` 22、`BatteryCapabilityTest` 19 |
| Manifest 契约 | 2 | `ManifestComponentTest` 2 |

**【事实】** Worker **331 条 / 14 文件**（实测，分类）：OAuth 2.1（`oauth.test.ts`）、MCP 传输与会话（`mcp-http.test.ts`、`mcp-session.test.ts`）、PhoneSession 核心（`phone-session.test.ts` 46、`phone-session-reliability.test.ts`、`phone-lifecycle.test.ts` 37）、协议/工具通道（`phone-protocol.test.ts`、`phone-tool-channel.test.ts`）、工具层（`tools.test.ts`、`tool-dispatch.test.ts`）、安全与日志（`security.test.ts` 35、`security-boundary.test.ts`、`logging.test.ts`、`worker-index.test.ts`）。
> **【事实】** 静态 `grep` 计数会得到 327（与 `docs/Gate2-Device-Integration-Checklist.md:271` 的 327 一致），**运行结果以 331 为准**（`docs/Gate1.5-Gate2-Core-Audit-Fixes.md:332-338` 的逐文件表已过期：写 phone-lifecycle 29 / security 31）。

**【事实】** **零覆盖区域**：`ui/HubScreen.kt`(646 行) 渲染与交互、`ui/HubViewModel.kt`(177 行)、`ui/Theme.kt`（无测试、无 `@Preview`）；`service/PhoneLinkService.kt`(445 行) 的前台服务生命周期/通知渠道/`onStartCommand`（只有其纯逻辑部件被 `ConnectGateTest`/`LinkBusTest` 覆盖）；`SplashActivity`/`MainActivity`；`POST_NOTIFICATIONS` 与 `updateNotification` 的权限路径（只靠 lint 的 `MissingPermission` 代偿）；无截图/视觉回归测试。

### 8.4 GitHub Actions（3 个，全部只有 `workflow_dispatch`）

**【事实】**

| 工作流 | 触发/job if | 关键步骤 | 产物 | 备注 |
| --- | --- | --- | --- | --- |
| `.github/workflows/android-debug-apk.yml` | `workflow_dispatch`（`:13-14`）；job **无 if**（`:20-22`） | checkout@v4 → JDK 17 → `setup-android@v3`（**未钉 SDK 版本**）→ sed 关 aapt2 override（`:38-41`）→ `:app:testDebugUnitTest`（`:43-45`）→ `:app:assembleDebug`（`:47-49`）→ `verify_manifest_classes.py`（`:55-57`）→ sha256 + step summary（`:59-70`） | `Android-MCP-Hub-debug-apk`（apk+sha256.txt，`if-no-files-found: error`，30 天） | **不跑 lint**；无 `timeout-minutes`、无 `concurrency` |
| `.github/workflows/deploy-worker.yml` | `workflow_dispatch` + 必填 `confirm_deploy`（`:14-20`）；job if = `(ref=='refs/heads/gate0' && input=='DEPLOY') \|\| (ref=='refs/heads/feat/gate2-core' && input=='DEPLOY-GATE2')`（`:31`） | 校验 wrangler 配置/DO 类名（`:46-77`）→ `npm ci` → typecheck+test+e2e（`:82-86`）→ `--dry-run`（`:88-89`）→ 校验两个 Secret（`:91-99`）→ 真 deploy（`:101-105`） | 无 | `timeout-minutes: 20`；concurrency `android-mcp-hub-manual-deploy`/`cancel-in-progress:false` |
| `.github/workflows/worker-gate1-check.yml` | `workflow_dispatch`（`:26-27`）；job 无 if（`:38-41`） | checkout → Node 24 + cache → `npm ci` → typecheck/test/e2e 各 `tee test-reports/*.log`（`:76-92`）→ 失败 tail 60 行（`:96-113`） | **仅失败时**：`worker-gate1-logs`，7 天 | `timeout-minutes: 20`；concurrency `worker-gate1-check-${{ github.ref }}` |

**【事实】** 三份工作流全部 `permissions: contents: read`，无 push/PR/定时触发 → **推送代码不会产生任何 run**（最近一次 run 仍是 `2026-10-11T07:38:27Z` 的 `gate0` 手动态）。

**【事实】** lint 的 CI 兜底缺失（残余风险 K12，`TEST_REPORT.md:738`）；不过仓库里有 `test-reports/lint-gate.log` 记录手工注入故障时 lint 确实会 `BUILD FAILED`（`MissingPermission`）。

### 8.5 产物

**【事实】** APK 输出路径在任务里硬编码：`android/app/build.gradle.kts:156` → `android/app/build/outputs/apk/debug/app-debug.apk`（当前 13,074,903 字节 / sha256 `0f723bad099780c860e4284afd011e03be69e2ce25219272aa9a24d861e0f4b8`）。**无 signingConfig** → 每次用本机默认 debug keystore 自动签名（`test-reports/android-build.log` 记录 `CN=Android Debug`），**签名指纹不可复现**。`android/tools/verify_manifest_classes.py`（278 行，自解析二进制 AXML + dex class_defs，不依赖 aapt2；只用 `--apk`/`--quiet`）挂在 `:app` 的 `check` 上（`build.gradle.kts:147-163`），本次实测通过。

---

## 9. 建议的模块与目录结构

**【建议】** 不新增 Gradle 模块（**判断**：本项目只有一个 app、无多团队并行，多模块只会增加构建与版本管理成本），只在 `:app` 内按包分层：

```
android/app/src/main/java/com/androidmcphub/app/
├── hub/                        # 应用壳与导航（新）
│   ├── HubApp.kt               # 根 Scaffold + NavigationBar + NavHost
│   └── Destinations.kt         # 路由常量（Home / Tools / Service / Settings）
├── ui/
│   ├── theme/                  # 由现有 Theme.kt 迁入并补齐
│   │   ├── Theme.kt            # androidMcpHubTheme()
│   │   ├── Color.kt            # lightColorScheme/darkColorScheme + StatusPalette
│   │   ├── Type.kt             # 三档字体
│   │   └── Spacing.kt          # 4/8/16/24 token
│   ├── components/             # 跨页公共组件（新）
│   │   ├── SectionCard.kt      # 统一卡片容器（标题 + 内容 + 可选操作）
│   │   ├── InfoRow.kt          # 由 PingRow 提炼
│   │   ├── LabeledNumberField.kt
│   │   ├── LogLine.kt          # 由 LogRow 提炼
│   │   ├── StatusPill.kt / EmptyState.kt / ConfirmDialog.kt
│   │   └── ToolCard.kt         # 元数据驱动（工具目录就绪后启用）
│   ├── model/                  # 纯数据展示模型（无 Compose 依赖，可 JVM 单测）
│   │   ├── LinkStatusView.kt   # LinkState → 文案/色调/动作
│   │   ├── ToolDescriptor.kt   # 工具目录项（服务端元数据 ∩ 本机能力）
│   │   └── ToolActivity.kt     # 调用中/成功/失败/耗时
│   ├── home/    HomeScreen.kt    HomeViewModel.kt
│   ├── tools/   ToolsScreen.kt   ToolsViewModel.kt
│   ├── service/ ServiceScreen.kt ServiceViewModel.kt   # 注意与既有 service/ 包名区分
│   └── settings/SettingsScreen.kt SettingsViewModel.kt
└── （protocol/ net/ capability/ core/ service/ 原样不动）
```

**【建议】** 命名注意：现有 `com.androidmcphub.app.service` 是前台服务包，UI 侧不要再建同名包；建议 UI 侧用 `ui/service/`（已在上面体现）。

**【建议】** `MainActivity.kt:29-38` 只改成 `setContent { AndroidMcpHubTheme { HubApp() } }`；`SplashActivity` 行为不动。

---

## 10. 推荐的实施顺序

**【建议】**（每一步都以"可构建 + 既有 255/331/78 全绿"为通过条件）

1. **冻结基线**：把 §1.2 的实测数字与 APK sha256 记进 `docs/`（并把 `test-reports/vitest.log` 等陈旧日志刷新，或明确标注时间），使得"改版前/后"可比。
2. **补测试锚点**：新 `ui/model/` 里的纯映射函数先写 + 先测 JVM 单测（`LinkState → LinkStatusView`、`PingOutcome → 展示模型`）。**这是唯一可以先写代码不碰 UI 的步骤。**
3. **主题与展示模型**：`ui/theme/` 补 typography/shapes/spacing；`StatusPalette` 保留；UI 改读 `LinkStatusView`（此时仍单页，行为零变化）。
4. **抽公共组件**：把 `StatusCard`/`PingRow`/`NumberField`/`LogRow` 提到 `ui/components/` 并参数化，仍单页渲染。
5. **引入 Navigation Compose + 四页骨架**：`HubApp` + `NavigationBar`；先把现有全部内容**原样**放进「首页」（其余三页先给空壳 + `EmptyState`）。此时行为与今天一致，可发布一个内部版本回归。
6. **搬「设置」页**：`AdvancedSettingsCard`（160 行）整体迁移，顺手按字段拆小块；能力开关随页迁移。
7. **搬「服务」页**：连接细节 + 日志 + 前台服务状态（不新增数据源）。
8. **打通「工具」页数据源**（§4.2 的三件事：工具目录、本机能力交集、工具活动通道），再把工具页从静态文案改成数据驱动列表。
9. **可选收尾**：备份/恢复配置、工具页详情与结果展示、Compose Preview 与（若环境允许）UI 测试栈。

**【判断】** 第 5 步是"能回滚的最小完整态"：即使第 6–8 步拖延，四页骨架 + 首页已可用。

---

## 11. 必须保留的现有功能（改版中不许丢）

**【事实/建议】**
1. **连接/断开**两条路径，以及通知栏的「断开」动作（`PhoneLinkService.kt:393-398`、`:406`）。
2. **令牌安全**：`DeviceTokenStore` + `AndroidSecretCipher`（Keystore 别名 `android-mcp-hub.device-token.v1`，`AndroidSecretCipher.kt:101`）；「显示/隐藏」与「清除已保存的令牌」必须仍然可用，且**明文只在用户点开时出现**。
3. **能力总开关的默认关闭语义**：`HubPrefs.kt:171 DEFAULT_CAPABILITIES_ENABLED = false`，且**每次请求现读**（`PhoneLinkService.kt:215` `policy = { prefs.capabilitiesEnabled() }`）→ 开关不需要重连即生效。改版不许改成"连接时快照"。
4. **日志环形缓冲**（`HubLog`，最多 200 条，最新在前）与「清空日志」；**错误与令牌脱敏**（`LogRedactionTest`）。
5. **通知权限的抬手请求与前台服务**（`PhoneLinkService` 的 `POST_NOTIFICATIONS`、`FOREGROUND_SERVICE_SPECIAL_USE`、`PROPERTY_SPECIAL_USE_FGS_SUBTYPE`）。
6. **启动页行为**：`savedInstanceState != null` 不重播、900ms 后跳转、只读海报 `drawable-nodpi/splash_poster.png`；`LAUNCHER` intent-filter **只属于 `SplashActivity`**（`AndroidManifest.xml:35-42`），`MainActivity` 保持 `exported="true"` + `singleTop` 以便通知点击直达（`:50-54`）。
7. **边到边**：`enableEdgeToEdge()` + 根 `safeDrawingPadding()`（`HubScreen.kt:123-127`）—— 拆页后每个页面都要有等价处理。
8. **零信任边界**：`CapabilityDispatcher` 的 7 步校验顺序（去重 → 形状/命中 → 策略 → 多余参数 → 取值 → 执行 → 收敛）与错误码闭集（`PhoneLinkProtocol.kt:133-150`）；`unsupported_tool` 不回显对端工具名（`CapabilityDispatcher.kt:89`/`:194` 的 `<unknown>`）。UI 改版**不许**为了"一键开关某工具"绕过 dispatcher。
9. **协议与 Worker 侧全部不动**（除非单独任务批准）。
10. **既有测试的断言不许为了"适配 UI"而改**：255 条 Android + 331 条 Worker + 78 项 E2E 是当前唯一回归网。

---

## 12. 可以延后处理的问题

**【建议】**（对应 §5 的 P2 与用户"不要提前引入复杂框架"的约束）
1. Compose UI 测试栈（`androidTest` + `ui-test-junit4` + `ui-test-manifest` + `AndroidJUnitRunner` + 模拟器）与截图/视觉回归 —— **环境无模拟器**，先靠 JVM 单测 + 真机人工清单。
2. 完整工具展示元数据（分类、图标、单位、枚举、危险等级、是否需要确认、预期耗时）：先做 `name/title/description/inputSchema` 驱动的最小版。
3. 深色主题精修、动态取色、字号体系全量替换。
4. 通知点击深链到指定页面（需要 navigation + `PendingIntent` 改造，可与第 5 步一起做，也可延后）。
5. `index.ts:111`、`http.ts:55`、`oauth/pages.ts:82`、`HubScreen.kt:557` 的文案/清单收敛（建议在"加第 3 个工具"之前做，不阻塞 UI 骨架）。
6. lint CI 门禁（K12）、陈旧证据日志、死代码/未用 import/过期注释清理。
7. 第三方 MCP 工具的统一展示模型（**需要协议级改动**，见 §13 第 5 问）。
8. 「60 秒授权确认」的实现（用户明确本轮不实现；接口需求见 §7.4）。

---

## 13. 八个重点问题的回答

### 1) 是否适合拆成「首页、工具、服务、设置」四个主页面？

**【判断】适合，但四个页面"含金量"差别很大：**

| 页面 | 现有内容 | 结论 |
| --- | --- | --- |
| 首页 | `StatusCard` + 连接/断开按钮 + 一次性提示 | **纯搬家**（今天就有） |
| 服务 | 连接细节、`PingCard`（诊断）、日志、前台服务状态 | **纯搬家**（数据源齐全） |
| 设置 | `AdvancedSettingsCard` 的 8 字段 + 令牌 + 能力开关 + 保存 | **纯搬家**（最大的单块） |
| 工具 | **只有一行写死的文案**（`HubScreen.kt:557`） | **需要新数据源**（§4.2），否则是空壳 |

**【建议】** 拆，但顺序上把「工具」页放最后（第 8 步），并且**先打通数据源再建页面**，不要先建一个静态页再想办法填数据。
**【建议】** 四页之外保留"连接中/错误"等一次性提示的归宿：现在用内联 `Text`（`:177-188`），四页后建议统一为 `Snackbar`（根 Scaffold 级），否则每页都要复制一段提示区。

### 2) 哪些现有组件应保留？

**【建议】** 保留（逻辑与外观都在可用水准）：
- `StatusCard`（`:269-315`）、`PingCard`（`:317-404`）、`PingRow`（`:406-421`）、`NumberField`（`:585-604`）、`LogRow`（`:606-641`）—— 保留**逻辑**，提为公开并把状态/平台调用参数化。
- `AndroidMcpHubTheme` + `StatusPalette`/`statusPalette()`（`Theme.kt:30-68`）—— 保留，补齐 typography/shapes/间距。
- `HubViewModel` 的**全部动作方法**（连接/断开/保存/清令牌/开关/清日志/清错误）—— 保留为共享动作面。
- 纯函数 `statusText`/`statusDetail`/`formatTime`（`:242-267`、`:643-646`）—— 保留逻辑，**搬去展示模型层**。
- `SplashActivity` / `MainActivity` 的窗口与边到边处理、`HubApplication` 的最小行为 —— 全部保留。

**【建议】** 不建议保留的形态：`AdvancedSettingsCard` 作为一个 160 行的巨型 Composable —— 内容保留，形状重拆。

### 3) 哪些组件应提取为公共组件？

**【建议】**（放 `ui/components/`）
| 新组件 | 来源 | 复用处 |
| --- | --- | --- |
| `SectionCard(title, action, content)` | 现在 4 处 `Card`（`:289`/`:322`/`:436`）直接铺开 | 四页统一卡片外观 |
| `InfoRow(label, value)` | `PingRow` | 服务页、工具结果、设置页只读项 |
| `LabeledNumberField(...)` | `NumberField` | 设置页 3 处数字输入 |
| `LogLine(entry)` | `LogRow` | 服务页 |
| `StatusPill(tone, text)` | 现在的状态色块（`:281-287`） | 首页/服务页/导航栏角标 |
| `EmptyState(title, hint)` | 无（新） | 工具页无工具/服务页无日志 |
| `ConfirmDialog(...)` | 无（新） | 清令牌、断开、未来的 60 秒确认 |
| `ToolCard(descriptor)` | 无（新，数据驱动） | 工具页 50 个工具 |
| `SettingSwitchRow(...)` | 现在的能力开关（`:560-570`） | 设置页/工具页 |

### 4) 是否需要 Navigation Compose？

**【判断】需要。** 理由（事实支撑）：① 拆四页必须有路由，否则靠 `when` 手写页面切换会在第 3 个页面开始失控；② **系统返回键与通知点击**需要"当前在哪个页面"的概念（现在通知直指 `MainActivity`，无页面概念）；③ 未来的工具详情页/结果页天然是第二层导航；④ 引入成本只有一条依赖（`androidx.navigation:navigation-compose`），不牵动现有代码。

**【建议】** 用 `navigation-compose` 的**字符串路由 + `NavigationBar`**；**不要**引入 Hilt、不要用类型安全路由的实验 API、不要多模块。**【建议】** 版本在 `libs.versions.toml` 钉死（与 composeBom 2024.12.01 / Kotlin 2.0.21 兼容的一版），**新增依赖属于改版任务，本轮不做**。

**【判断】** 备选方案（不引依赖，用 `sealed class Screen` + `when` 切换 + 自己处理返回键）可行但会在"返回栈/深链/状态恢复"三件事上重复造轮子，不划算。

### 5) 如何保证未来新增 50 个工具时不改工具列表主体？

**【事实】** 今天有三张并列的工具清单（Worker `TOOL_REGISTRY`（`tools.ts:182`）、Android 编译期白名单（`PhoneLinkService.kt:214`）、mock 手机 `MOCK_CAPABILITIES`（`tools/mock-phone/client.ts:87`）），而**手机完全不知道服务端有哪些工具**（无目录下发）。

**【建议】** 让 UI 渲染"数据"而不是"清单"：
1. **服务端元数据先行**：`ToolDefinition`（`tools.ts:38-53`）补展示字段（至少中文 `title`、`category`、`requiresConfirmation`、`expectedDurationMs`、参数单位/枚举）；**必须同时改 `http.ts:450-463` 的手写映射**（显式字段白名单，最容易漏）。
2. **目录下发**：`welcome` 增加 `tools:[{name,title,description,category,inputSchema}]`（或连接后一次 `tool_catalog` 往返）。此时"加工具"= Worker 注册表加一条 + 手机白名单加一条，**UI 零改动**。
3. **UI 侧模型**：`ToolsScreen` 只消费 `List<ToolDescriptor>`（`name`/`title`/`description`/`enabled`/`lastActivity`），`LazyColumn` + `ToolCard`，不做 `when(toolName)`。
4. **收敛硬编码**：`index.ts:111` 改为从 `TOOL_REGISTRY` 派生；`http.ts:55` INSTRUCTIONS 与 `oauth/pages.ts:82` 改为按注册表生成；`HubScreen.kt:557` 的写死文案随工具页改版删除；`tools/gate1-smoke.mjs:74` 的整表断言改为"必须包含 phone_ping"或改为按注册表长度断言。
5. **工具名规则单一来源**：把 `CapabilityDispatcher.kt:19` 与 `worker/src/config.ts:287` 的两份正则收敛成一处权威定义（建议 Worker 侧导出 + Android 侧引用同一文档化形状），并给 `ToolDefinition.name` 加校验（今天没有）。

**【判断】** 做到 1–3 之后，新增 50 个工具的 UI 成本 ≈ 0；做到 4–5 之后，文档/健康检查/冒烟测试也不会漂移。

### 6) 如何避免 UI 与 Cloudflare 通信、MCP 协议实现耦合？

**【事实】** 现状已经比较干净：UI 层 import 只有 `core.HubDraft/LogEntry/LogLevel` 与 `protocol.LinkState/PingOutcome`；Android 主源码 0 处 MCP/Cloudflare 概念；网络在 `net/`、协议在 `protocol/`、服务在 `service/`。

**【建议】** 把这条边界写成改版规范（可在 `README-ANDROID.md` 加一节）：
1. UI 只允许依赖三样东西：页面的 `UiState`、事件回调、`ui/model/` 里的纯展示模型。
2. UI **不许** import `net/`、`protocol/` 的帧类型、`capability/`、以及任何 JSON（`org.json`）。今天唯一越界的 `LinkState`/`PingOutcome` 通过 §4.1 的展示模型消除。
3. 所有网络/协议/存储调用继续只经由 `PhoneLinkService`/`HubPrefs`。
4. UI 里不做重试、不做超时、不拼帧、不知道 Cloudflare；"手机 ↔ Worker"的语义只出现在 `protocol/` 与 Worker 代码里。
5. 给这条边界加一个可执行的守卫（**建议**，非必须）：一条 JVM 单测扫描 `ui/**` 的 import 白名单，越界即失败（成本低、防回归强）。

### 7) 哪些现有代码绝对不应在 UI 改版中修改？

**【建议】**
| 区域 | 理由 |
| --- | --- |
| `protocol/**`（`PhoneLink.kt`、`PhoneLinkProtocol.kt`、`PingResponder.kt`） | 帧解析/编码、去重、7 个 `LinkState` 语义、错误码闭集是线上契约，有 105 条单测锁定 |
| `service/PhoneLinkService.kt` | 前台服务/通知/`LinkBus`/能力白名单实例化点（`:214`）。**只允许**在新增能力时改这一行 |
| `core/**`（`HubPrefs` 键名与默认值、`DeviceTokenStore`、`AndroidSecretCipher`、`HubConfig`、`WsUrl`、`Backoff`、`SocketFailure`、`HubLog`） | 安全与持久化契约（令牌加密别名、默认关闭语义、URL 校验）；改键名会让用户已保存的配置丢失 |
| `net/**`（`PhoneLinkClient`） | 重连/退避/半开连接判定，有可靠性测试锁定 |
| `capability/**`（`CapabilityDispatcher`、`BatteryCapability`） | 零信任校验顺序与错误码闭集 |
| `worker/**` 全部 | 本轮审计范围为 Android UI；Worker 改动需单独任务批准（且 `deploy-worker.yml` 的 Gate 2 闸门与 `security.test.ts` 的 15 条约束要同步） |
| `.github/workflows/**` | 部署闸门与分支配对约束 |
| `android/app/src/main/AndroidManifest.xml` 的既有组件声明 | `LAUNCHER` 归属、`MainActivity` 的 `exported`/`singleTop`、服务的 `specialUse` 类型都有理由与测试（`ManifestComponentTest`） |
| 既有测试文件（19 类 255 条 / 331 条 Worker / 78 项 E2E） | 唯一回归网；只允许"新增"，不允许为适配 UI 而改断言 |
| `android/gradle.properties:26` 的 aapt2 override | 删了本机就不能构建 |
| `docs/Gate1.5-Stage0-Test-Baseline.md` | 按用户要求保持未提交 |

### 8) 哪些架构问题必须先修、哪些可延后？

**【建议】** 必须先修（P0，见 §5）：状态映射上移（§4.1）、工具页数据源（§4.2）、VM 拆薄（§4.3）、UI 层测试锚点（§8.3）。
**【建议】** 延后（P2，见 §12）：Compose UI 测试栈与截图回归、完整工具元数据、深色主题精修、通知深链、文案资源化全量迁移、CI lint 门禁、60 秒确认、第三方工具统一展示模型。

---

## 14. 附：本次审计的证据边界

**【事实】** 已实测：`git ls-remote`/`rev-list`/`merge-base`、`npm run typecheck`、`vitest` 全量、`npm run e2e`、`gradlew :app:testDebugUnitTest --rerun`、`:app:lintDebug`、`:app:verifyManifestClasses`、`:app:assembleDebug`，以及 APK 的字节数与 sha256。
**【事实】** 未执行（**不得当成通过**）：真实 Cloudflare 部署；真机（手机）端到端联调；真实 `workerd`（本容器永久不可用 —— `MmapAligned() failed` / `TCMalloc assumes a 48-bit virtual address space`，exit 134，见 `test-reports/workerd-not-run.log`）；Compose UI 渲染/交互（无模拟器、无仪器测试栈）；ChatGPT 真实连接。
**【事实】** 本轮**未修改任何源码、未创建提交、未推送**；工作树与审计开始时一致（仅 `?? docs/Gate1.5-Stage0-Test-Baseline.md`）。
**【事实】** 工具与 ping 的 `requestId` 由 MCP 层**每次调用新生成**（`worker/src/mcp/http.ts:82`/`:119` 的 `crypto.randomUUID()`），DO 侧不改号 —— 这是"去重"能按 request_id 做的前提。

**待人工复核的推断**（§14 其余内容都是已核对的事实；以下三条只有静态阅读支撑）

1. `protocol/PhoneLinkProtocol.kt:83` 的注释写「服务端会一直等到超时（默认 15s）才拿到 `phone_timeout`」，与 `worker/src/config.ts:93` 的 `10_000` **不一致** → **【判断】** 注释陈旧（Worker 侧未找到 15s 常量）。
2. Android 是否消费服务端下发的 `welcome.max_pending_requests`（`PhoneLinkProtocol.kt:190`）做本地限流：只找到 `PhoneLink.kt:436` 把它写进日志，**未发现任何强制逻辑** → **【判断】** 实际不消费。
3. `net/PhoneLinkClient.kt:336` 的 `readTimeout(0)` 是否等价于"WebSocket 没有读超时"取决于 OkHttp 对 WS 的实现细节，未做运行时验证。
