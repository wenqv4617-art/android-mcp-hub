# UI 架构（Stage 1 改版后）

本文描述 `feat/ui-revamp` 之后 Android 客户端的界面结构。写作范围**仅限界面层**：
`protocol/`、`net/`、`capability/`、`service/PhoneLinkService.kt`、`core/DeviceTokenStore.kt`、
`core/AndroidSecretCipher.kt` 与 `worker/` 一行未改（见 `docs/UI-Stage1-Report.md` 的核对结果）。

- 改版前：单屏 `ui/HubScreen.kt`（646 行）用一条 `LazyColumn` 装下
  状态卡 + ping 卡 + 10 个配置输入框 + 日志，所有视觉常量写在页面里。
- 改版后：`hub/`（导航）、`ui/theme/`（视觉 Token）、`ui/model/`（展示模型）、
  `ui/components/`（通用组件）、`ui/{home,tools,services,settings}/`（四个页面 + 三个子页）。

---

## 1. 页面导航结构

**唯一入口**：`MainActivity` → `hub/HubApp.kt:52` 的 `HubApp(viewModel)`。
Activity 只负责 `enableEdgeToEdge()`、主题与 `Surface`，不含任何业务逻辑
（改版前通知权限、剪贴板、连接按钮都在 `HubScreen` 里）。

**路由集中定义**（`hub/Destinations.kt`）：

| 路由 | 常量 | 页面 |
| --- | --- | --- |
| `home` | `HubTab.Home` | `ui/home/HomeScreen.kt` |
| `tools` | `HubTab.Tools` | `ui/tools/ToolsScreen.kt` |
| `services` | `HubTab.Services` | `ui/services/ServicesScreen.kt` |
| `settings` | `HubTab.Settings` | `ui/settings/SettingsScreen.kt` |
| `settings/diagnostics` | `Routes.DIAGNOSTICS` | `ui/settings/DiagnosticsScreen.kt` |
| `tools/detail/{toolId}` | `Routes.TOOL_DETAIL` + `Routes.ARG_TOOL_ID` | `ui/tools/ToolDetailScreen.kt` |
| `services/add` | `Routes.ADD_SERVICE` | `ui/services/AddServiceScreen.kt` |

`Routes.toolDetail(toolId)` 是构造详情路由的唯一方法 —— 拼字符串的地方只此一处。

**底部导航栏**：`HubBottomBar`（`hub/HubApp.kt:150`）遍历 `HubTab.entries`，
当前页由 `currentBackStackEntryAsState()` 的 destination route 反查
（`hub/HubApp.kt:58`）得出，**不是**自己维护的 `selectedIndex`。

**返回键行为**：子页（工具详情、添加服务、诊断）压栈，系统返回键回到来源页；
四个主页面之间切换用

```kotlin
navController.navigate(tab.route) {
    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}
```

因此：底部栏**不会**把四个 tab 堆成一个返回栈（连按返回不会逐个页面回退），
同一 tab 重复点击不会重建页面，回首页时 `rememberSaveable` 的状态（搜索词、
分类筛选、表单草稿）仍在。`hub/HubApp.kt:78` 只在这四个 tab 路由上显示底栏 ——
子页不显示，避免"子页点了底部栏跳到别的 tab 但返回栈里还压着旧子页"。

**导航与业务解耦**：`HubApp` 持有 `HubViewModel`，只把它**传给**页面；
页面之间不互相调用，一律走路由。导航层不知道 `LinkState` 是什么。

**状态保留**：ViewModel 由 Activity 作用域持有（`MainActivity` 的
`viewModel()`），切页不重建、不重连。`PhoneLinkService` 的生命周期只由
`connect()` / `disconnect()` 决定，与导航无关 —— 本轮没有新增任何"进页面即连接"的
`LaunchedEffect`。

**一次性消息**：`HubViewModel.message` 在 `HubApp.kt:60` 的
`LaunchedEffect(message)` 里交给 `SnackbarHost` 显示后立即 `dismissMessage()`。
界面层不再直接弹 `Toast`（唯一例外是诊断页复制 `request_id` 的短反馈，见 §5）。

---

## 2. UI 状态来源

唯一方向：**协议状态 → 展示模型 → Composable**。

```
PhoneLinkService.LinkBus (StateFlow)          protocol/PhoneLink.kt:24-48 LinkState
        │                                     （Idle/Connecting/Connected/Reconnecting/
        │                                      AuthFailed/Revoked/Error）
        ▼
ui/HubViewModel.kt  ── 原样转发 state/logs/lastPing/lastError/serviceRunning
        │                + draft/tokenVisible/capabilitiesEnabled/message
        ▼
ui/model/LinkStatusView.kt::toStatusView(serviceRunning)   ← 唯一的状态→文案/色调映射
        ▼
ui/home/HomeCards.kt、ui/settings/DiagnosticsScreen.kt     ← 只消费 LinkStatusView
```

- `LinkStatusView(title, detail, tone: StatusTone, generation, retryAtMs, attempt)`
  的映射函数是 **`LinkState.toStatusView(serviceRunning)`**（`ui/model/LinkStatusView.kt:60`）。
- 全仓库**只有一处** `when (state)` 做 UI 文案映射；页面里不再出现
  `when (LinkState)`（改版前散在 `HubScreen` 的 `statusText`/`statusDetail`/颜色三处）。
- `titleAt(nowMs)` / `remainingSeconds(nowMs)` 把"还有几秒重连"这类**时间相关**文案
  变成纯函数：`nowMs` 由调用方传入，测试里传固定值，所以不依赖系统时间。
  倒计时循环只在 `state is LinkState.Reconnecting` 时存在（`HomeScreen.kt:87`），
  其他状态没有常驻定时器。
- UI 状态没有第二套来源：页面不读 `SharedPreferences`、不看 WebSocket、不碰协议帧。
  设置页的输入框绑定的是 `HubViewModel.draft`（`HubDraft`，全是字符串），
  合法的数字解析与回落默认值发生在既有 `core/HubConfig.kt`，不在 Compose 里。

**测试**：`test/java/com/androidmcphub/app/ui/model/LinkStatusViewTest.kt`
覆盖 Idle / Connecting / Connected / Reconnecting / AuthFailed / Revoked / Error
七种状态（含 `serviceRunning` 的真/假分支、倒计时取整、代次与重试次数）。

---

## 3. 工具目录的数据来源

工具页**没有假数据**，但当前也确实只有**一个**内置能力。

- 真实来源：`capability/CapabilityDispatcher` 的执行白名单，唯一实例化点在
  `service/PhoneLinkService.kt:214`
  （`listOf(BatteryCapability(AndroidBatterySource(this)))`）。
- `protocol/PhoneLink.kt` 的工具请求处理**不经过 UI**，也**不向 UI 上报**工具目录
  （工具名只在环形日志里出现）。所以界面**无法**从连接层"问"到工具列表。
- 因此新增 `ui/model/ToolCatalog.kt`：一份**只读**的目录适配层，
  目前只有 `ToolCatalog.BatteryGetStatus`（id/name = `battery_get_status`，
  分类「设备」，来源「本机能力」，无参数、只读）。

它满足本轮的五条约束：

1. 不改能力执行白名单（执行仍在 `CapabilityDispatcher`，UI 不参与）；
2. 不改七步校验；
3. 不加任何动态执行入口（详情页没有"执行"按钮，见 `ui/tools/ToolDetailScreen.kt` 的类注释）；
4. 不改 Worker 协议；
5. UI 不维护第二套**可执行**白名单 —— `ToolCatalog` 只是展示数据，
   里面写错一个名字不会让任何东西变得可执行；名字对不对由
   `test/java/com/androidmcphub/app/ui/model/ToolCatalogTest.kt` 钉住
   （断言 `ToolCatalog.localNames` 与 `CapabilityDispatcher`
   用 `BatteryCapability(FakeBatterySource())` 注册出来的 `names()` 逐字一致）。

**技术限制（如实记录）**：`ToolCatalog` 是手写的，与 `PhoneLinkService` 的注册表
之间存在**重复定义**。它之所以还不能自动生成，是因为注册表在 `PhoneLinkService`
内部构造、没有对外暴露只读快照，而暴露它需要改受保护的 `service/PhoneLinkService.kt`。
本轮选择不改受保护代码，改用"单测钉住一致性"作为过渡；
后续收敛方案见 `docs/UI-Stage1-Report.md`「已知问题」。

`phone_ping` **不**出现在工具列表里：它是连接层的诊断通道
（`protocol/PingResponder.kt`），不是本机能力，也不受能力总开关影响。
工具页底部专门有一段文案说明这件事。

---

## 4. 新增一个工具时，它怎么进入列表

以将来新增内置能力 `foo_get_bar` 为例：

1. 在 `capability/` 实现 `Capability`（名字需匹配
   `CapabilityDispatcher.TOOL_NAME_PATTERN = ^[a-z][a-z0-9_]{0,63}$`），
   并在 `service/PhoneLinkService.kt:214` 的 `listOf(...)` 里注册 —— 这一步属于受保护
   范围，需要按安全流程走；
2. 在 `ui/model/ToolCatalog.kt` 加一条 `ToolDescriptor`
   （id / name / 一句话说明 / 分类 / 来源 `BuiltIn` / 参数表 / 是否只读）；
3. `ToolCatalogTest` 会因为第 2 步没做而先失败（它断言"展示目录与注册表一致"），
   逼着两处同步——这是刻意的：与其让界面悄悄少一个工具，不如让单测红。
4. 页面**不需要**任何改动：`ToolsScreen` 由 `ToolCatalog.local` 驱动，
   筛选、搜索、卡片、详情模板全部通用。**不要**为单个工具加
   `when (toolId) { ... }` 分支（`ToolDescriptor` 的类注释里写了原因）。

第三方（未来）：`ToolSource.ThirdPartyMcp` / `LocalHttpMcp` 两个枚举位已经在
展示模型里存在，`ToolDescriptor.source` 决定卡片上的来源标签；
但**当前没有任何条目**属于它们 —— 工具页的「其他来源」是一段明确的"未开放"占位，
不是几张看起来能用的假卡片。

---

## 5. 组件与职责

| 组件 | 文件 | 职责 |
| --- | --- | --- |
| `PageHeader` | `ui/components/PageHeader.kt` | 页面标题/副标题/返回键/右侧动作（`WindowInsets(0,0,0,0)`：系统栏内边距由外层管） |
| `SectionCard` | `ui/components/SectionCard.kt` | 统一卡片（标题、副标题、trailing、圆角、内边距） |
| `InfoRow` | `ui/components/InfoRow.kt` | 标签 + 值（可选等宽字体，供时间戳/ID 对齐） |
| `StatusPill` | `ui/components/StatusPill.kt` | 状态胶囊（启用/未开放/需要授权…） |
| `EmptyState` | `ui/components/EmptyState.kt` | 空状态（图标 + 标题 + 说明 + 可选动作） |
| `ToolCard` | `ui/components/ToolCard.kt` | 工具卡片；`availabilityColor()` / `sourceColor()` 只接受枚举 |
| `SettingSwitchRow` | `ui/components/SettingSwitchRow.kt` | 标题 + 说明 + 开关 |
| `HubTextField` / `HubNumberField` / `TokenField` | `ui/components/FormFields.kt` | 表单输入（含令牌打码/显示隐藏/清除） |
| `LogRow` | `ui/components/LogRow.kt` | 一行日志（时间 + 级别 + 文本，级别色走主题） |
| `formatTime` / `formatDateTime` | `ui/components/Time.kt` | `java.time` 格式化；`ms <= 0` → `—` |

迁移映射（改版前 → 改版后）：`StatusCard` → 首页 `ConnectionStatusCard`；
`PingCard` → 诊断页「最近一次 ping」；`PingRow` → 通用 `InfoRow`；
`AdvancedSettingsCard` → 设置页「连接配置」+「超时与重连」；
`NumberField` → `HubNumberField`；`LogRow` → 通用 `LogRow`；
`statusText`/`statusDetail` → `toStatusView()`。
原 `ui/HubScreen.kt` 与旧 `ui/Theme.kt` 已删除（`ui/` 下不再有第二套主题或第二个入口）。

---

## 6. 主题如何替换

视觉规范集中在 `ui/theme/`，页面里**没有**颜色/圆角/间距字面量。

| 文件 | 内容 |
| --- | --- |
| `ui/theme/Color.kt` | `object HubColors`（浅色 + `*Dark` 各 11 个色值）、`LightColorScheme` / `DarkColorScheme` |
| `ui/theme/Type.kt` | `HubTypography`（headlineSmall … labelSmall） |
| `ui/theme/Shapes.kt` | `HubShapes`：extraSmall 8 / small 12 / medium 16 / large 20 / extraLarge 28 |
| `ui/theme/Spacing.kt` | `HubSpacing`（xs 4 / sm 8 / md 12 / lg 16 / xl 24 / xxl 32；`screenHorizontal = lg`、`cardPadding = lg`、`itemGap = md`）+ `LocalHubSpacing` + `MaterialTheme.spacing` |
| `ui/theme/Theme.kt` | `AndroidMcpHubTheme`、`StatusPalette`、`statusPalette`、`statusToneColor(tone)` |

- 换色：只改 `ui/theme/Color.kt` 的 `HubColors` 常量；
- 换字体/字号：只改 `ui/theme/Type.kt`；
- 换圆角/间距：只改 `Shapes.kt` / `Spacing.kt`；
- 页面取间距一律 `MaterialTheme.spacing.xxx`，取圆角一律
  `MaterialTheme.shapes.{small,medium,large}`（`SectionCard` 默认 `medium`=16dp，
  首页主状态卡显式传 `shapes.large`=20dp，与设计稿的"主卡 20 / 普通卡 16"一致）。
- 状态语义色：`statusPalette`（idle/connecting/connected/reconnecting/failed）
  由主题提供，**保留**改版前 `StatusPalette` 的思路，且深色模式另有一套；
  页面用 `statusToneColor(view.tone)` 取色，不再自己写 `isSystemInDarkTheme() ? A : B`。
- 深色模式：`AndroidMcpHubTheme(darkTheme = isSystemInDarkTheme())` 自动跟随系统；
  `values-night/themes.xml` 只负责 Activity 启动时（Compose 之前）的窗口底色。

`test/java/com/androidmcphub/app/ui/theme/ThemeTokenTest.kt` 钉住
设计稿给的色值与 shape 的 dp 值（改错颜色会红）。

---

## 7. 未来第三方 MCP 服务如何接入 UI

本轮**只留位置，不实现**。将来接入时，界面侧的落点已经确定：

1. **服务列表**：`ui/services/ServicesScreen.kt` 现在的空状态
   （"尚未添加 MCP 服务"）换成 `LazyColumn` 的 `ServiceCard`；
   数据来自一个只读的服务仓库（形如 `List<ServiceDescriptor>`），
   **不要**把 Cloudflare/phone-link 的连接状态搬进来（那是本应用的连接，
   诊断入口在设置页）。
2. **添加流程**：`ui/services/AddServiceScreen.kt` 的表单字段
   （名称/分组/传输协议/URL/请求头）就是设计稿的字段；落地时把
   "保存并拉取工具"接到真实实现上，并要求：
   - 凭据必须先加密（`core/AndroidSecretCipher` 的思路）才能落盘，禁止明文；
   - 保存成功才允许显示"已接入"，连接失败要显示失败原因，
     **不许**出现"点了保存但什么都没发生"或"假成功提示"。
3. **工具目录**：服务的工具发现结果 → `ToolDescriptor(source = ThirdPartyMcp)`
   → 进入同一个 `ToolCatalog`（合并本机 + 第三方），
   `ToolsScreen` 的「其他来源」分组自动生效；`id` 需要带来源前缀，
   避免"同名不同源"。
4. **授权确认**：将来的敏感操作需要手机确认（60 秒窗口、超时默认拒绝）。
   `ui/tools/ToolDetailScreen.kt`「执行授权」一节已经把这件事写进文案，
   届时做成通用组件（`AlertDialog` 或底部确认卡），承载
   `PENDING/APPROVED/DENIED/EXPIRED/EXECUTING/SUCCEEDED/FAILED`
   状态显示，并绑定**具体参数**；授权超时与工具执行超时是两个独立的量。
   本轮**没有**实现协议级授权状态机，因此界面上没有"允许/拒绝"按钮。
