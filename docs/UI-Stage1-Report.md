# UI Stage 1 交付报告（Compose 模块化改版）

本报告对应任务书「UI Revamp Stage 1（1A–1E）」。所有结论都区分 **【事实】**（本机实际执行/实际读到的）
与 **【判断】**（设计取舍、经验推断）。**没有模拟器、没有真机**，所以本报告**不声称**完成任何视觉或交互验收，
真机部分全部列在 [UI-Stage1-Manual-QA.md](UI-Stage1-Manual-QA.md) 等待人工执行。

## 0. 基线

| 项 | 值 |
| --- | --- |
| 仓库 | `https://github.com/wenqv4617-art/android-mcp-hub.git` |
| 开发分支 | `feat/ui-revamp` |
| 基线分支 / 提交 | `feat/gate2-core` @ `d05ca19eea3ec49c3fec27d9052f0062fbe1df09` |
| 分流检查 | 【事实】开工前 `git status --short` 无未提交修改、`git branch --show-current` 在 `feat/gate2-core`、反向提交 0；`feat/ui-revamp` 当时不存在 |
| 未纳入提交的文件 | 【事实】`docs/Gate1.5-Stage0-Test-Baseline.md` 始终保持未跟踪状态，**没有**进入任何提交 |
| 未触碰的分支 | 【事实】`gate0` 未被修改，未创建 `main`，未合并任何分支，未触发任何部署 |

## 1. 修改的文件（相对基线）

| 文件 | 变化 |
| --- | --- |
| `android/app/src/main/java/com/androidmcphub/app/MainActivity.kt` | 改为 `enableEdgeToEdge()` + `AndroidMcpHubTheme { HubApp(viewModel) }`（原来直接挂 `HubScreen`） |
| `android/app/src/main/java/com/androidmcphub/app/ui/HubViewModel.kt` | **未修改**（原有 10 个 StateFlow 与全部业务动作 connect / disconnect / save / clearToken / clearLogs / clearLastError / setCapabilitiesEnabled / toggleTokenVisible / dismissMessage 已够用） |
| `android/app/src/main/java/com/androidmcphub/app/ui/HubScreen.kt` | **删除**（646 行） |
| `android/app/src/main/java/com/androidmcphub/app/ui/Theme.kt` | **删除**（69 行，`BrandBlue` / 旧 `statusPalette()`） |
| `android/app/build.gradle.kts` | 新增 `implementation(libs.androidx.navigation.compose)`（唯一新增依赖） |
| `android/gradle/libs.versions.toml` | 新增 `navigation = "2.8.5"` 与 `androidx-navigation-compose` |
| `android/README-ANDROID.md` | R6 需求行的界面落点、§5 界面整段、§5.3 边到边、目录树（共 4 处） |
| `android/COMPATIBILITY-ANDROID-13-16.md` | §9.1 内边距事实（共 1 处） |

【事实】`git diff --stat d05ca19 -- android/app/src/main/java/com/androidmcphub/app/ui/HubScreen.kt android/app/src/main/java/com/androidmcphub/app/ui/Theme.kt` = 两者合计 `-715` 行。

## 2. 新增的文件

### 2.1 主题与展示模型（1A）

| 文件 | 行数 | 内容 |
| --- | --- | --- |
| `android/app/src/main/java/com/androidmcphub/app/ui/theme/Color.kt` | 109 | 任务书给定的 9 个颜色 Token（`#7775D8` / `#EEECFF` / `#F8F7FC` / `#FFFFFF` / `#252B49` / `#7B8298` / `#35A979` / `#D69A43` / `#D46B75`）转成的 `lightColorScheme` / `darkColorScheme`，加上保留语义色思路的 `statusPalette` 扩展 |
| `android/app/src/main/java/com/androidmcphub/app/ui/theme/Type.kt` | 74 | Typography 集中定义 |
| `android/app/src/main/java/com/androidmcphub/app/ui/theme/Shapes.kt` | 25 | 主卡片 20dp / 普通卡片 16dp / 按钮 12–16dp |
| `android/app/src/main/java/com/androidmcphub/app/ui/theme/Spacing.kt` | 46 | `spacing`（页面水平边距 16dp、itemGap 等）与 `HubSpacing` 访问器 |
| `android/app/src/main/java/com/androidmcphub/app/ui/theme/Theme.kt` | 88 | `AndroidMcpHubTheme`（Material 3、浅/深色、动态色【判断】关闭以保证设计 Token 稳定） |
| `android/app/src/main/java/com/androidmcphub/app/ui/model/LinkStatusView.kt` | 114 | `data class LinkStatusView(title, detail, tone, generation, retryAtMs)` + `LinkState.toStatusView(serviceRunning)` + `titleAt(nowMs)` 倒计时 |
| `android/app/src/main/java/com/androidmcphub/app/ui/model/ToolDescriptor.kt` | 124 | `ToolDescriptor(id, name, description, category, source, arguments, availability)`、`ToolSource`、`ToolCategory`、`ToolAvailability`、`availability(capabilitiesEnabled)`、`availabilityColor()` |
| `android/app/src/main/java/com/androidmcphub/app/ui/model/ToolCatalog.kt` | 62 | `ToolCatalog.local` / `byId(id)` / `filterTools(list, query, category)`，只读展示目录 |

### 2.2 通用组件（1A/1B）

| 文件 | 行数 | 内容 |
| --- | --- | --- |
| `.../ui/components/SectionCard.kt` | 79 | 卡片容器（标题 + 副标题 + 可选 trailing + 内容） |
| `.../ui/components/InfoRow.kt` | 55 | 通用「标签 / 值」行（含可选 trailing、可点击） |
| `.../ui/components/StatusPill.kt` | 57 | 状态/分类标签（可带圆点） |
| `.../ui/components/EmptyState.kt` | 70 | 空状态（图标 + 标题 + 说明 + 可选动作） |
| `.../ui/components/ToolCard.kt` | 148 | 工具卡片（图标 + 名称 + 说明 + 状态标签 + 点击。） |
| `.../ui/components/FormFields.kt` | 124 | `HubTextField` / `HubNumberField` / `TokenField` |
| `.../ui/components/SettingSwitchRow.kt` | 58 | 开关行（设置页能力总开关） |
| `.../ui/components/LogRow.kt` | 70 | 日志行（等宽时间 + 级别 + 文本） |
| `.../ui/components/PageHeader.kt` | 66 | 页头（标题 + 副标题 + 返回 + actions） |
| `.../ui/components/Time.kt` | 39 | `formatTime` / `formatDateTime`（`java.time`，Locale/ZoneId 走函数默认参数） |

### 2.3 导航与页面（1B–1D）

| 文件 | 行数 | 内容 |
| --- | --- | --- |
| `.../hub/HubApp.kt` | 197 | 单 Activity 入口：`Scaffold` + 底部导航 + `NavHost` + `SnackbarHost` + 通知权限 |
| `.../hub/Destinations.kt` | 33 | `HubTab`（首页/工具/服务/设置）与 `Routes`（工具详情/添加服务/连接诊断）路由常量 |
| `.../ui/home/HomeScreen.kt` | 164 | 首页骨架（连接状态 / 设备概况 / ping 摘要 / 最近活动 / 快捷入口 + 版本行） |
| `.../ui/home/HomeCards.kt` | 328 | 首页 6 个卡片 composable（`internal fun`），从 HomeScreen 拆出以避免巨型页面文件 |
| `.../ui/tools/ToolsScreen.kt` | 226 | 工具列表（搜索 + 分类筛选 + 可用状态 + 只读来源分组） |
| `.../ui/tools/ToolDetailScreen.kt` | 202 | 工具详情（说明 / 参数 Schema / 来源与可用性 / 执行授权说明） |
| `.../ui/services/ServicesScreen.kt` | 204 | 服务容器 + 空状态 + 添加入口 + 服务类型占位 |
| `.../ui/services/AddServiceScreen.kt` | 187 | 添加服务的表单结构预览（明确标注不保存、不连接） |
| `.../ui/settings/SettingsScreen.kt` | 281 | 现有全部配置入口（连接配置 / 超时与重连 / 能力授权 / 保存 / 诊断 / 关于） |
| `.../ui/settings/DiagnosticsScreen.kt` | 239 | 连接诊断（状态 / ping 明细 / 最近错误 / 日志） |

【事实】主源码新增 3,646 行，最大的新文件是 `HomeCards.kt` 的 328 行（改版前的 `HubScreen.kt` 是 646 行）。

### 2.4 新增测试（1A）

| 文件 | 行数 | 覆盖 |
| --- | --- | --- |
| `android/app/src/test/java/com/androidmcphub/app/ui/model/LinkStatusViewTest.kt` | 137 | `Idle` / `Connecting` / `Connected` / `Reconnecting` / `AuthFailed` / `Revoked` / `Error` 七种状态的映射与文案、代次、倒计时（注入固定 `nowMs`，不依赖系统时间） |
| `android/app/src/test/java/com/androidmcphub/app/ui/model/ToolCatalogTest.kt` | 110 | 目录唯一性、分类筛选、来源、`availability` 随总开关变化、以及与能力注册表的名字一致性 |
| `android/app/src/test/java/com/androidmcphub/app/ui/theme/ThemeTokenTest.kt` | 59 | 主题颜色/形状/间距 Token 的具体取值 |

### 2.5 文档

| 文件 | 说明 |
| --- | --- |
| `docs/UI-Architecture.md`（240 行） | 页面导航结构（路由表 + 返回键/状态保留配方）、UI 状态来源、工具目录数据来源与 5 条约束、新增工具进入列表的 4 步流程、组件与迁移映射、主题替换方式、未来第三方 MCP 接入路径 |
| `docs/UI-Stage1-Manual-QA.md`（79 行） | 人工验收清单（15 项主清单 + 3 项重点 + 6 项回归 + 回填模板 + 未实现功能清单），顶部明确声明**无运行截图、无一条已验证** |
| `docs/UI-Preflight-Audit.md`（665 行） | 本轮改版的前置审计（上一任务产物），随本分支入库作为改版依据 |
| 本文件 | 交付报告 |

## 3. 测试与构建结果【事实】

全部命令在 `d05ca19` 基线的同一条工作树（`feat/ui-revamp`）上执行。

| 项 | 命令 | 结果 |
| --- | --- | --- |
| Android 单测 | `./gradlew --no-daemon :app:testDebugUnitTest` | **22 个文件 / 279 条 / 0 失败 / 0 错误 / 0 跳过**（基线 19 类 255 条 → 新增 3 文件 24 条） |
| Android lint | `./gradlew --no-daemon :app:lintDebug`（删报告后强制重生成） | **0 errors / 29 warnings**（基线 30 条）。21 条来自 `android/gradle/libs.versions.toml` 的依赖版本提示，其余 8 条分布在 `service/PhoneLinkService.kt`(2)、`core/AndroidSecretCipher.kt`(2)、`core/HubPrefs.kt`(1)、`SplashActivity.kt`(1)、`AndroidManifest.xml`(1)；**新增 UI 文件零警告**。相比基线少的那条来自已删除的 `ui/HubScreen.kt`（`ConstantLocale`） |
| Manifest 类校验 | `./gradlew --no-daemon :app:verifyManifestClasses` | ✅ 7 个组件（HubApplication / SplashActivity / MainActivity / PhoneLinkService / InitializationProvider / PreviewActivity / ProfileInstallReceiver）全部能在 dex 中找到 |
| Debug APK | `./gradlew --no-daemon :app:assembleDebug` | 构建成功（见 §4） |
| Worker 类型检查 | `npm run typecheck` | 0 错误 |
| Worker 单测 | `npm test`（vitest） | 14 文件 / **331 通过** |
| 本地 E2E | `npm run e2e` | **78 通过 / 0 失败** |

**没有**通过删除、跳过或改写既有断言的方式制造通过：【事实】三个既有测试文件与测试数量均未减少，Android 侧测试总数从 255 增加到 279。

### 3.1 Worker 代码零修改【事实】

```
$ git diff --stat d05ca19eea3ec49c3fec27d9052f0062fbe1df09 -- worker .github
（空输出）
```

即 `worker/**` 与 `.github/workflows/deploy-worker.yml` 一行未动 —— 符合任务书「不得修改 Worker 协议、不得改生产部署配置」。

## 4. APK

| 项 | 值 |
| --- | --- |
| 路径 | `android/app/build/outputs/apk/debug/app-debug.apk` |
| 大小 | **13,591,018 字节** |
| sha256 | `fa0bf4b12564fd9199ec7a36c57fb083ea520ef2502807dcfe7a7c813917bb71` |
| 基线 APK（`d05ca19`） | 13,074,903 字节 / `0f723bad099780c860e4284afd011e03be69e2ce25219272aa9a24d861e0f4b8` |

【判断】体积增加约 516 KB，主要来自 Navigation Compose 及 Material 3 图标集合的额外代码；这是本轮唯一新增依赖的代价。

## 5. 是否修改受保护文件

【事实】**没有修改任何一个受保护文件**。逐项核对：

| 受保护对象 | 状态 |
| --- | --- |
| `android/.../protocol/**` | 未修改 |
| `android/.../net/**` | 未修改 |
| `android/.../capability/**`（含 `CapabilityDispatcher` 七步校验） | 未修改 |
| `android/.../service/PhoneLinkService.kt` | 未修改 |
| `android/.../core/DeviceTokenStore.kt` / `AndroidSecretCipher.kt` / `HubPrefs.kt` | 未修改（`HubPrefs` 的键名与默认值全部保留） |
| `worker/**`、`.github/workflows/deploy-worker.yml` | 未修改（见 §3.1） |
| 生产 Cloudflare 配置 | 未触碰，未部署 |
| 设备令牌 | 未读取、未输出；日志/截图/提交记录中不含令牌 |
| 错误码闭集 / 令牌加密方式 / 能力总开关默认关闭 / 连接代次校验 / 请求去重 / 重连策略 / 前台服务生命周期 / 日志脱敏 / 通知权限 | 全部保持原有实现 |
| `docs/Gate1.5-Stage0-Test-Baseline.md` | 保持未跟踪，未提交 |

【事实】实际改动的非 UI 文件只有两个：`android/README-ANDROID.md` 与 `android/COMPATIBILITY-ANDROID-13-16.md`（都是描述界面的文档，且原文已因删除 `ui/HubScreen.kt` 而失真）。

## 6. 与任务书「完成标准」的逐条对照

| # | 标准 | 状态 |
| --- | --- | --- |
| 1 | 四页可正常导航 | 【事实】路由已实现并编译通过；【判断】页面切换效果需真机确认（QA 清单 A2/A3） |
| 2 | UI 整体符合参考设计方向 | 【判断】Token 与组件已按给定颜色/圆角/间距实现；**视觉验收未做**（无真机） |
| 3 | 现有配置入口完整保留 | 【事实】服务器 URL、设备 ID、设备令牌（显示/隐藏/清除）、ping 超时、心跳周期、初始与最大重连间隔、抖动比例、最大挂起请求数、单帧字节上限、能力总开关、保存、诊断、日志查看与清空，全部在设置页与诊断页 |
| 4 | 连接与断开仍可用 | 【事实】动作仍是 `HubViewModel.connect/disconnect`，UI 只做调用；【判断】真机行为需回归（QA C1） |
| 5 | 令牌安全机制未改变 | 【事实】`HubPrefs` / `DeviceTokenStore` / `AndroidSecretCipher` 未修改 |
| 6 | 能力总开关默认关闭 | 【事实】默认值未改，开关仍每次请求实时读取 |
| 7 | 工具列表由真实数据驱动 | 【事实】数据来自 `ui/model/ToolCatalog.kt`，并由 `ToolCatalogTest` 钉住与能力注册表的名字一致 |
| 8 | 服务页面定位正确 | 【事实】只做容器/空状态/入口，明确显示「尚未添加 MCP 服务」，无假服务卡、假连接状态 |
| 9 | UI 不直接依赖网络与协议执行层 | 【事实】`ui/` 与 `hub/` 下没有任何 WebSocket / `CapabilityDispatcher` / 协议帧的直接调用，只经 `HubViewModel` 的 `StateFlow` 与既有动作 |
| 10 | 主题集中管理 | 【事实】颜色/字体/圆角/间距都在 `ui/theme/`，页面里没有硬编码视觉规范 |
| 11 | 不出现新的巨型页面文件 | 【事实】最大新文件 328 行（`HomeCards.kt`），原 646 行的 `HubScreen.kt` 已拆解删除 |
| 12 | Android 构建与既有测试通过 | 【事实】见 §3 |
| 13 | Worker 代码没有修改 | 【事实】见 §3.1 |
| 14 | 不修改生产部署配置 | 【事实】未触碰 |
| 15 | 提供 APK / 代码 / 报告 / 人工验收清单 | APK（§4）、分支（§0）、本报告、[UI-Stage1-Manual-QA.md](UI-Stage1-Manual-QA.md) |

## 7. 已知问题

1. **【事实】没有任何真机/模拟器验证。** 本环境无模拟器、无连接设备，`./gradlew connectedAndroidTest` 无法执行；因此视觉、手势、返回键、通知点击、深色模式、字体放大、前后台切换全部**未经运行验证**。所有相关结论在本环境中都只是编译期与单元测试层面的。
2. **【事实】`ui/tools/ToolDetailScreen.kt` 末尾的 `private fun ToolIdentityBlock(...)` 未被使用**（lint 未报错，Kotlin 编译器只给警告级别的可见性提示）。它是一次重构的残留，可在下一轮删除。
3. **【判断】工具目录是"只读展示目录"，不是运行时查询。** `ToolCatalog.local` 与 `capability/CapabilityDispatcher` 的注册表由单测 `ToolCatalogTest` 保证名字一致；新增一个本机能力时，必须同步改这两处，否则单测会失败。这是任务书允许的"标记后续需收敛的重复定义"，**不是**双份可执行白名单（目录里没有任何执行入口）。
4. **【判断】服务的分组/连接状态是占位。** 服务页与添加页都没有实现第三方 MCP 客户端，因此不存在真实的服务列表、连接状态、工具发现结果。界面文案已经如实说明，但这也意味着「服务」页目前没有任何"可用功能"。
5. **【判断】首页「设备概况」刻意少放了指标。** 只显示有真实来源的型号、系统版本、前台服务、连接代次、本机工具数与能力开关；参考设计稿里的电量/温度/存储/网络制式没有现成数据源（需要经 `CapabilityDispatcher` 执行本机能力才能拿到），本轮不放占位数字。
6. **【事实】依赖提示 21 条仍然存在。** `libs.versions.toml` 里 Compose BOM / 各 androidx 库有可升级版本，本轮只加了 `navigation = "2.8.5"`（与现有 Compose BOM 同期），没有做整体升级 —— 升级属于本轮范围外的风险操作。
7. **【判断】深色模式只做了 Token 层面。** `darkColorScheme` 已按同一套语义色定义，但**没有任何真机截图**证明对比度合适。

## 8. 未实现的功能（明确不做，不是"没做完"）

1. 第三方 MCP 服务器接入（客户端、OAuth、凭据加密存储、工具发现）—— 本轮只做 UI 容器与占位。
2. 本地 HTTP MCP 中转 —— 同上。
3. 协议级授权状态机（`PENDING/APPROVED/DENIED/EXPIRED/EXECUTING/SUCCEEDED/FAILED`）与 60 秒确认窗口 —— 本轮只在工具详情页写了说明性文案，**没有**任何「允许/拒绝」按钮接入真实执行流程。
4. 工具执行入口 —— 工具详情页**刻意没有**执行按钮（执行只能由服务端经 phone-link-v1 下发并走七步校验）。
5. 任务持久化与查询接口 —— 授权/任务状态目前只在内存中，任务书已把它列为后续工作。
6. 截图 —— 没有运行环境，不提供截图；设计示意图不是运行截图，未混入本报告。
