# UI 架构（Stage 2 改版后）

本文描述 `feat/ui-revamp` 上 Stage 2 之后的 Android 客户端界面结构。写作范围**仅限界面层**：
`protocol/`、`net/`、`capability/`、`service/PhoneLinkService.kt`、`core/DeviceTokenStore.kt`、
`core/AndroidSecretCipher.kt`、`core/HubPrefs.kt` 与 `worker/` 一行未改
（见 `docs/UI-Stage2-Report.md` 的核对结果）。

- 改版前（Stage 0）：单屏 `ui/HubScreen.kt`（646 行）一条 `LazyColumn` 装下
  状态卡 + ping 卡 + 10 个配置输入框 + 日志。
- Stage 1：拆成 `hub/`（导航）+ `ui/theme/` + `ui/model/` + `ui/components/` +
  `ui/{home,tools,services,settings}/`，但**导航有两套写法**，真机上出现
  「点首页却进了工具页 / 回不去」（根因见 §1.2）。
- Stage 2（本轮）：导航收敛成一个入口；四个主页面的信息架构重排（首页瘦身、
  工具页只留工具、服务页诚实空状态、设置页拆成主页 + 三个二级页）；视觉密度收紧。

---

## 1. 页面导航结构

### 1.1 路由与页面

**唯一入口**：`MainActivity` → `hub/HubApp.kt` 的 `HubApp(viewModel)`。Activity 只负责
`enableEdgeToEdge()`、主题与 `Surface`，不含任何业务逻辑。

四个顶层标签（`enum HubTab`，`hub/Destinations.kt`）+ 八个二级路由（`object Routes`）：

| 路由 | 常量 | 页面 | 层级 |
| --- | --- | --- | --- |
| `home` | `HubTab.Home` | `ui/home/HomeScreen.kt` | 顶层 |
| `home/connection` | `Routes.CONNECTION_DETAIL` | `ui/home/ConnectionDetailScreen.kt` | 二级 |
| `tools` | `HubTab.Tools` | `ui/tools/ToolsScreen.kt` | 顶层 |
| `tools/detail/{toolId}` | `Routes.TOOL_DETAIL` + `Routes.ARG_TOOL_ID` | `ui/tools/ToolDetailScreen.kt` | 二级 |
| `tools/help` | `Routes.TOOLS_HELP` | `ui/tools/ToolsHelpScreen.kt` | 二级 |
| `services` | `HubTab.Services` | `ui/services/ServicesScreen.kt` | 顶层 |
| `settings` | `HubTab.Settings` | `ui/settings/SettingsScreen.kt` | 顶层 |
| `settings/connection` | `Routes.CONNECTION_SETTINGS` | `ui/settings/ConnectionSettingsScreen.kt` | 二级 |
| `settings/advanced` | `Routes.ADVANCED_SETTINGS` | `ui/settings/AdvancedSettingsScreen.kt` | 二级 |
| `settings/token` | `Routes.TOKEN_SETTINGS` | `ui/settings/TokenSettingsScreen.kt` | 二级 |
| `settings/diagnostics` | `Routes.DIAGNOSTICS` | `ui/settings/DiagnosticsScreen.kt` | 二级 |

`Routes.toolDetail(toolId)` 是构造详情路由的唯一方法。Stage 1 的
`services/add`（`AddServiceScreen`）已**删除** —— 那个表单不能保存任何东西，
把它留在正式 UI 里等于给用户一个走不通的流程；见 §7。

**底部导航栏**：`HubBottomBar`（`hub/HubApp.kt`）遍历 `HubTab.entries`，`selected`
来自 `currentBackStackEntryAsState()` 的路由反查（`hubTabOf(route)`），
**不是**自己维护的 `selectedIndex`。`currentTab == null`（当前在二级页面）时
`bottomBar` 直接不渲染 —— 二级页面不显示底部导航。

### 1.2 导航的唯一入口（Stage 2 修的缺陷）

`hub/TopLevelNavigation.kt` 是**唯一**允许发起顶层切换的地方：

```kotlin
fun NavHostController.navigateToTopLevel(tab: HubTab) {
    navigate(tab.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

fun topLevelNavDecision(currentTab: HubTab?, target: HubTab): TopLevelNavDecision
```

**根因（【事实】+【判断】）**：Stage 1 里首页快捷入口用裸
`navController.navigate(HubTab.Tools.route)`，底部导航用
`popUpTo(起始页) { saveState = true } + launchSingleTop + restoreState`。
两种写法对返回栈的记账方式不同：裸 `navigate` 压上去的那一页没有走 `saveState`
路径，于是它和底部导航保存的栈不在同一本账上；之后任何一次
`popUpTo(起始页) { saveState = true }` 都会把当前栈（含那个裸压上去的页面）
存成"某一页的已保存状态"，`restoreState` 恢复出来的栈顶就不再是用户点的那一页
—— 表现正是真机上的「点首页却进了工具页」「首页回不去」「底栏高亮与实际页面对不上」。
【判断】的部分：任务书指出"存在两套逻辑但尚未证明是根因"，本轮通过
`HubAppNavigationTest` 的复现场景用例把它钉住了（见 §8）。

**没有删 `restoreState`**：删掉会让标签页的滚动位置与工具页的搜索/筛选状态一起丢失，
属于用新问题换旧问题。

### 1.3 返回键与状态保留

- 二级页面压栈，系统返回键回到来源页；二级页面内 `PageHeader(onBack = { popBackStack() })`
  与返回键行为一致。
- 四个顶层标签之间切换走 `navigateToTopLevel`，栈不会把四个 tab 堆成一长串
  （在首页之外的 tab 上按返回会回到首页，而不是逐个回退）。
- 同一 tab 重复点击不导航（`topLevelNavDecision` 返回 `StayOnCurrentTab`），页面不重建。
- `ToolsScreen` 的搜索词与分类筛选是 `rememberSaveable`；切走再回来仍在
  （`restoreState` + `saveState`）。
- 导航与业务解耦：`HubApp` 只把 `HubViewModel` **传给**页面；没有任何"进页面即连接"
  的 `LaunchedEffect`，`PhoneLinkService` 的生命周期只由 `connect()`/`disconnect()` 决定。

**一次性消息**：`HubViewModel.message` 在 `HubApp` 的 `LaunchedEffect(message)` 里
交给 `SnackbarHost` 显示后立即 `dismissMessage()`。界面层不弹 `Toast`
（唯一例外是诊断页复制 `request_id` 的短反馈）。

---

## 2. UI 状态来源

唯一方向：**协议状态 → 展示模型 → Composable**。

```
PhoneLinkService.LinkBus (StateFlow)          protocol/PhoneLink.kt LinkState
        │                                     （Idle/Connecting/Connected/Reconnecting/
        │                                      AuthFailed/Revoked/Error）
        ▼
ui/HubViewModel.kt  ── 原样转发 state/logs/lastPing/lastError/serviceRunning
        │                + events/diagnostics/batteryUnrestricted（V1.6.1）
        │                + draft/tokenVisible/capabilitiesEnabled/message
        ▼
ui/model/LinkStatusView.kt::toStatusView(serviceRunning)   ← 唯一的状态→文案/色调映射
        ▼
ui/home/HomeCards.kt、ui/home/ConnectionDetailScreen.kt、
ui/settings/DiagnosticsScreen.kt                           ← LinkStatusView + 诊断统计/事件（V1.6.1）
```

- `LinkStatusView(title, detail, tone: StatusTone, generation, retryAtMs, attempt)` 的映射
  函数是 `LinkState.toStatusView(serviceRunning)`（`ui/model/LinkStatusView.kt`）。
- 全仓库**只有一处** `when (state)` 做 UI 文案映射；页面里不再出现 `when (LinkState)`。
- `titleAt(nowMs)` / `remainingSeconds(nowMs)` 把"还有几秒重连"这类**时间相关**文案
  变成纯函数：`nowMs` 由调用方传入，测试里传固定值，所以不依赖系统时间。
  倒计时循环只在 `state is LinkState.Reconnecting` 时存在（`ui/home/HomeScreen.kt`）。
- `ui/model/ServerHost.kt::serverHostLabel(serverUrl)` 把长 URL 收成 `host:port`
  （空串 → 「（未填写）」），首页/设置入口只显示它；解析不了就原样返回，
  不做静默截断，也**不会**把 path/query 带进界面。
- UI 状态没有第二套来源：页面不读 `SharedPreferences`、不看 WebSocket、不碰协议帧、
  不碰 Keystore。设置页输入框绑定 `HubViewModel.draft`（`HubDraft`，全是字符串），
  数字解析与默认值回落仍发生在既有 `core/HubConfig.kt`。

**测试**：`ui/model/LinkStatusViewTest.kt` 覆盖七种状态；`ui/model/ServerHostTest.kt`
覆盖主机名提取与"不泄露查询参数"。

---

## 3. 工具目录的数据来源

工具页**没有假数据**，条目**由本机能力注册清单派生**（Gate 2.5 起是单一可信来源）。

- **单一来源**：`capability/BuiltInCapabilities.kt` 的 `ALL` —— 一份
  `BuiltInCapability(metadata, factory)` 列表，登记本机有哪些内置能力。
  每个能力的元数据与实现放在同一个文件里（`BatteryCapability.METADATA`、
  `NetworkCapability.METADATA`、`StorageCapability.METADATA`）。
- **执行白名单仍然是 `capability/CapabilityDispatcher`**：`service/PhoneLinkService.kt`
  用 `BuiltInCapabilities.androidCapabilities(this)` 构造它；`CapabilityDispatcher.init`
  里有一条 `require(registry.values.all { it.metadata.name == it.name })`，
  把"界面看到的元数据"和"真实注册的能力"钉死（打错名字时构造直接抛异常，
  而不是安静地显示一个不存在的工具）。
- `protocol/PhoneLink.kt` 的工具请求处理**不经过 UI**，也**不向 UI 上报**工具目录
  （工具名只出现在环形日志里），所以 UI 侧仍走 `ui/model/ToolCatalog.kt`：
  `val local = BuiltInCapabilities.metadata.map { it.toToolDescriptor() }`
  —— 只读**派生**，不是手写清单。
- 当前三个内置能力（全部零参数、只读）：

| 工具 ID | 名称 | 分类 | 声明的权限 |
| --- | --- | --- | --- |
| `battery_get_status` | 电池状态 | 设备 | 无 |
| `device_get_network` | 网络状态 | 网络 | `ACCESS_NETWORK_STATE`（普通权限，安装即授予） |
| `device_get_storage` | 存储空间 | 存储 | 无 |

它满足五条约束：

1. 不改能力执行白名单（执行仍在 `CapabilityDispatcher`，UI 无法触发执行）；
2. 不改七步校验；
3. 不加任何动态执行入口（详情页没有"执行"按钮）；
4. 不改 Worker 协议；
5. UI 不维护第二套**可执行**白名单 —— `ToolCatalog` 是展示数据，
   与注册表的一致性由 `ui/model/ToolCatalogTest.kt`（比对真实
   `CapabilityDispatcher.names()`）与 `capability/BuiltInCapabilitiesTest.kt`
   共同钉住。

**仍然存在的重复定义（如实记录）**：Android 的 `BuiltInCapabilities` 与
Worker 的 `worker/src/mcp/tools.ts::TOOL_REGISTRY` 是**两处**登记，
因为两边是不同运行时、不能共享注册表。这靠契约测试对齐，而不是靠人记得改：
`worker/test/tools.test.ts` 整表断言工具名，`worker/test/device-tools.test.ts`
断言 `outputSchema` 覆盖手机侧会返回的全部字段，`e2e/run-local-e2e.ts`
断言工具列表恰好是这四个并真的走一遍全链路。

`phone_ping` **不**出现在工具列表里：它是连接层的诊断通道
（`protocol/PingResponder.kt`），不是本机能力，也不受能力总开关影响。
这段说明在 `ui/tools/ToolsHelpScreen.kt`（工具页正文优先显示工具，
而不是先让人读实现原理）。

---

## 4. 新增一个工具时，它怎么进入列表

以将来新增内置能力 `foo_get_bar` 为例：

1. 在 `capability/` 实现 `Capability`（名字需匹配
   `CapabilityDispatcher.TOOL_NAME_PATTERN = ^[a-z][a-z0-9_]{0,63}$`），
   带上 `metadata`（同一个文件里定义 `METADATA`）；
2. 在 `capability/BuiltInCapabilities.kt` 的 `ALL` 里加一条 —— **这是 Android 侧
   唯一需要登记的地方**，`service/PhoneLinkService.kt` 不再逐个列举能力；
3. Worker 侧在 `worker/src/mcp/tools.ts` 的 `TOOL_REGISTRY` 里加一条
   `ToolDefinition`，`outputSchema` 必须覆盖手机侧会返回的全部字段；
   `worker/test/tools.test.ts` 的整表断言会先红，逼着两处同步；
4. 页面**不需要**改动：`ToolsScreen` 由 `ToolCatalog.local` 驱动，搜索、筛选、
   卡片、详情模板全部通用。**不要**为单个工具加 `when (toolId) { ... }` 分支。

第三方（未来）：`ToolSource.ThirdPartyMcp` / `LocalHttpMcp` 两个枚举位已经在展示模型里
存在，`ToolDescriptor.source` 决定卡片上的来源标签；但**当前没有任何条目**属于它们。

---

## 5. 组件与职责

| 组件 | 文件 | 职责 |
| --- | --- | --- |
| `PageHeader` | `ui/components/PageHeader.kt` | 页面标题（`headlineSmall`）/副标题（单行省略）/返回键/右侧动作 |
| `SectionCard` | `ui/components/SectionCard.kt` | 统一卡片（标题、副标题、trailing、圆角 16dp、内边距 14dp） |
| `InfoRow` | `ui/components/InfoRow.kt` | 标签 + 值（可选等宽字体；`maxLines` + 省略号防长 URL 撑卡片） |
| `NavRow` | `ui/components/NavRow.kt` | **二级入口行**（图标 + 标题 + 副标题 + chevron，最小高度 52dp），首页快捷入口、设置页各分组入口、服务页相关设置共用 |
| `StatusPill` | `ui/components/StatusPill.kt` | 状态胶囊（启用/未开放/需要授权…） |
| `EmptyState` | `ui/components/EmptyState.kt` | 空状态（图标 + 标题 + 说明 + 可选动作） |
| `ToolCard` | `ui/components/ToolCard.kt` | 工具卡片；`availabilityColor()` / `sourceColor()` 只接受枚举 |
| `SettingSwitchRow` | `ui/components/SettingSwitchRow.kt` | 标题 + 说明 + 开关（整行可点，`Role.Switch`） |
| `HubTextField` / `HubNumberField` / `TokenField` | `ui/components/FormFields.kt` | 表单输入（含令牌打码/显示隐藏/清除） |
| `LogRow` | `ui/components/LogRow.kt` | 一行日志（时间 + 级别 + 文本，级别色走主题） |
| `formatTime` / `formatDateTime` | `ui/components/Time.kt` | `java.time` 格式化；`ms <= 0` → `—` |

**间距归父布局**（Stage 2 修的一类外观问题）：`HubTextField`、`InfoRow`、
`SettingSwitchRow`、`NavRow` 内部**不再自带上下外边距**；块之间由
`LazyColumn(verticalArrangement = spacedBy(spacing.itemGap))` 与卡片内
`Column(spacedBy(spacing.sm))` 统一给。Stage 1 的「组件 8dp + 父布局 8dp」
双份间距是设置页被垫长一屏多的直接原因。

页面专属组件留在页面模块里：首页卡片在 `ui/home/HomeCards.kt`，
工具页的帮助卡在 `ui/tools/ToolsHelpScreen.kt`，不为每一个简单控件建文件。

---

## 6. 主题如何替换

视觉规范集中在 `ui/theme/`，页面里**没有**颜色/圆角/间距字面量。

| 文件 | 内容 |
| --- | --- |
| `ui/theme/Color.kt` | `object HubColors`（浅色 + `*Dark`）、`LightColorScheme` / `DarkColorScheme` |
| `ui/theme/Type.kt` | `HubTypography`：页面标题 22sp / 卡片标题 16–18sp / 正文 14–15sp / 次要 12–13sp / 标签 11–12sp |
| `ui/theme/Shapes.kt` | `HubShapes`：extraSmall 8 / small 12 / medium 16 / large 20 / extraLarge 28 |
| `ui/theme/Spacing.kt` | `HubSpacing`（xs 4 / sm 8 / md 12 / lg 16 / xl 24 / xxl 32；`screenHorizontal = lg`、`cardPadding = 14dp`、`itemGap = 10dp`、`rowMinHeight = 52dp`）+ `LocalHubSpacing` + `MaterialTheme.spacing` |
| `ui/theme/Theme.kt` | `AndroidMcpHubTheme`、`StatusPalette`、`statusPalette`、`statusToneColor(tone)` |

- 换色：只改 `ui/theme/Color.kt` 的 `HubColors`；换字体/字号：只改 `Type.kt`；
  换圆角/间距：只改 `Shapes.kt` / `Spacing.kt`。
- Stage 2 的密度调整都在 Token 层：`cardPadding` 16→14dp、`itemGap` 12→10dp、
  新增 `rowMinHeight = 52dp`、`titleLarge` 20→18sp、`bodySmall` 13→12sp、
  页面标题 24→22sp，并新增 `rowMinHeight`。这些值都在视觉规范给的区间内
  （卡片内边距 12–16、卡片之间 10–12、普通列表行 48–56dp），取偏小值。
- 触摸目标不靠压缩实现：可点行统一 `defaultMinSize(minHeight = 52.dp)`，
  底栏仍是 M3 标准 `NavigationBar`（只把图标从 24dp 收到 22dp）。
- 状态语义色：`statusPalette`（idle/connecting/connected/reconnecting/failed）由主题提供，
  **保留**改版前 `StatusPalette` 的思路，深色模式另有一套；页面用
  `statusToneColor(view.tone)` 取色。
- 深色模式：`AndroidMcpHubTheme(darkTheme = isSystemInDarkTheme())` 自动跟随系统；
  `values-night/themes.xml` 只负责 Activity 启动时（Compose 之前）的窗口底色。
- **字体缩放**：所有字号都走 `MaterialTheme.typography`，没有 `sp` 硬编码之外的干预，
  也不通过缩小字号来"适配"大字体 —— 尊重系统字体缩放。

`ui/theme/ThemeTokenTest.kt` 钉住色值、圆角与间距的 dp 值（改错会红）。

---

## 7. 未来第三方 MCP 服务如何接入 UI

本轮**只留位置，不实现**。当前服务页是一个**诚实的空状态**：标题「服务」、
「我的服务」卡片里 `StatusPill("0 个")` +「尚未添加 MCP 服务」+ 一个
`enabled = false` 的「添加 MCP 服务器（即将支持）」按钮，并明确写着
"现在不会保存任何服务配置，也不会显示任何连接状态"。

【判断】为什么不保留 Stage 1 的 `AddServiceScreen`（表单预览）：它长得像能用，
但点「保存并拉取工具」什么也不会发生 —— 这正是任务书 §4.3 禁止的
"看起来已经成功保存、实际却没有保存"。宁少一个假功能。

将来接入时的界面落点：

1. **服务列表**：把 `ui/services/ServicesScreen.kt` 的空状态换成 `LazyColumn` +
   `ServiceCard`，数据来自只读的服务仓库（形如 `List<ServiceDescriptor>`）。
   **不要**把 Cloudflare/phone-link 的连接状态搬进来 —— 那是本应用自己的连接，
   诊断入口在设置页。
2. **添加流程**：重新引入一个**能真的保存**的表单页（名称/分组/传输协议/URL/请求头，
   即设计稿字段），接上真实实现后才允许出现"保存并拉取工具"；要求：
   凭据先加密再落盘（`core/AndroidSecretCipher` 的思路），禁止明文；
   保存成功才显示"已接入"，失败要显示原因。
3. **工具目录**：服务的工具发现结果 → `ToolDescriptor(source = ThirdPartyMcp)`
   → 进同一个 `ToolCatalog`（本机 + 第三方合流），工具页会自动出现新的来源分组；
   `id` 需要带来源前缀，避免"同名不同源"。
4. **授权确认**：敏感操作需要手机确认（60 秒窗口、超时默认拒绝）。
   `ui/tools/ToolDetailScreen.kt` 的「执行授权」一节已把这件事写进文案，届时做成
   通用组件（`AlertDialog` 或底部确认卡），承载
   `PENDING/APPROVED/DENIED/EXPIRED/EXECUTING/SUCCEEDED/FAILED` 状态显示，
   并绑定**具体参数**；授权超时与工具执行超时是两个独立的量。
   本轮**没有**实现协议级授权状态机，因此界面上没有"允许/拒绝"按钮。

---

## 8. 测试

| 测试 | 文件 | 覆盖 |
| --- | --- | --- |
| 主题 Token | `test/.../ui/theme/ThemeTokenTest.kt` | 色值、圆角、间距 dp |
| 展示模型 | `test/.../ui/model/LinkStatusViewTest.kt` | 七种 `LinkState` → `LinkStatusView` |
| 工具目录 | `test/.../ui/model/ToolCatalogTest.kt` | 目录与本机注册表逐字一致 |
| 主机名 | `test/.../ui/model/ServerHostTest.kt` | 缩短规则 + 不泄露 query |
| 导航决策 | `test/.../hub/TopLevelNavigationTest.kt` | 路由↔标签映射、同标签不导航、4×4 全组合 |
| 导航返回栈 | `test/.../hub/HubAppNavigationTest.kt` | **真实 `HubApp` + 真实 `NavHostController`**（Robolectric + Compose UI 测试规则）：冷启动落首页、四标签互切、连点同一标签、首页快捷入口→工具页→底栏回首页（**真机故障原场景**）、二级页面隐藏底栏、返回键回到工具页/首页 |

Robolectric 让"没有模拟器也能跑真机式导航测试"成为可能，代价是构建机必须能下载
`robolectric` / `ui-test-junit4` / `navigation-testing`（首次构建需要网络）。
另外，构建机若是 `linux-aarch64`，需要 `@ConscryptMode(OFF)`（Robolectric 依赖的
`conscrypt-openjdk-uber` 只带 x86_64 原生库）—— 这个注解只关掉测试进程里的
TLS provider，不影响被测代码。
