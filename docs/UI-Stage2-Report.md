# UI Stage 2 交付报告

本报告对应任务书（用户在 Stage 1 之后下达的 Stage 2 统一改版要求）的 10 项交付物。
标记约定：**【事实】**＝可核对的结果；**【判断】**＝我的取舍与理由；**【建议】**＝后续动作。

---

## 0. 基线与分支

| 项 | 值 |
| --- | --- |
| 工作分支 | `feat/ui-revamp` |
| Stage 1 基线 | `daf4b2c0e5cbec4a51fa14e61c13eb60213bd833` |
| 本轮代码 tip | `22caf06`（`test(ui): 补导航决策、返回栈、主机名与主题 Token 测试`） |
| 本轮提交 | `df41718` build(依赖) → `d18f993` feat(主题/组件) → `c80c50f` feat(导航+信息架构) → `22caf06` test → `4c0920c` docs |
| 交付分支 tip（已推送） | `4c0920c`（`docs(ui): Stage 2 架构说明、人工验收清单与交付报告`），远端 `feat/ui-revamp` 已更新为 `daf4b2c..4c0920c` |
| 默认分支 `gate0` | `39ef38eec151b4bb21b4e6cac933ec5221a12302`，**本轮未动**（不合并、不推送） |
| 未跟踪且永不提交 | `docs/Gate1.5-Stage0-Test-Baseline.md`（保持原状） |

开工前核对过：`git rev-parse HEAD` = `daf4b2c…`、`git branch --show-current` = `feat/ui-revamp`，
与任务书给的 Stage 1 基线一致。

---

## 1. 本轮修改摘要

**【事实】** 相对 `daf4b2c`：已跟踪文件 20 个变更（+614 / −1028），新增未跟踪源码与测试 9 个 `.kt`，
删除 1 个文件，文档 3 个。

- **导航**：新增 `hub/TopLevelNavigation.kt`（`navigateToTopLevel` / `hubTabOf` /
  `topLevelNavDecision`），`hub/Destinations.kt` 路由表重排，`hub/HubApp.kt` 重写为 11 个
  destination、底栏选中态由返回栈反查、二级页面不显示底栏。
- **信息架构**：首页瘦身；工具页只留工具；服务页改为诚实空状态并删除 `AddServiceScreen`；
  设置页拆成「设置主页 + 服务器与设备配置 + 高级连接参数 + 设备令牌管理 + 连接诊断」。
- **视觉**：主题 Token 层收紧（`cardPadding` 14dp、`itemGap` 10dp、`rowMinHeight` 52dp、
  页面标题 22sp、卡片标题 18sp、次要正文 12sp）；`HubTextField` / `InfoRow` 去掉自带边距，
  改由父布局统一给间距；新增 `NavRow` 收敛三处重复的行组件；图标统一 `AutoMirrored`。
- **测试**：新增 3 个测试文件（导航决策、导航返回栈、主机名），同步主题 Token 断言；
  引入 Robolectric + Compose UI 测试规则（仅测试范围依赖）。

### 新增文件（9 个 .kt + 1 个删除）

`hub/TopLevelNavigation.kt`(61)、`ui/components/NavRow.kt`(100)、
`ui/home/ConnectionDetailScreen.kt`(154)、`ui/model/ServerHost.kt`(29)、
`ui/settings/ConnectionSettingsScreen.kt`(101)、`ui/settings/AdvancedSettingsScreen.kt`(122)、
`ui/settings/TokenSettingsScreen.kt`(92)、`ui/tools/ToolsHelpScreen.kt`(107)、
`test/hub/TopLevelNavigationTest.kt`(124)、`test/hub/HubAppNavigationTest.kt`(199)、
`test/ui/model/ServerHostTest.kt`(55)。
**删除**：`ui/services/AddServiceScreen.kt`（187 行）。

---

## 2. 导航问题的根因与修复（交付物 ③，**本轮重点**）

### 2.1 根因

**【事实】** 改版前存在两套顶层切换写法：

| 来源 | 写法 |
| --- | --- |
| 首页快捷入口（Stage 1） | `navController.navigate(HubTab.Tools.route)` —— 裸导航 |
| 底部导航栏 | `popUpTo(graph.findStartDestination().id) { saveState = true }` + `launchSingleTop = true` + `restoreState = true` |

**【判断】** 裸 `navigate` 压上去的那一页没有走 `saveState` 记账，于是它与底栏保存的
返回栈不在同一本账上；此后任何一次 `popUpTo(起始页) { saveState = true }` 都会把**当前**
这个（含裸压上去页面的）栈存成"某一页的已保存状态"。等到 `restoreState = true` 去恢复时，
恢复出来的栈顶就不再是用户点的那一页 —— 表现正是真机上的「点首页却进了工具页」
「首页点不回去」「底栏高亮与实际页面对不上」。

### 2.2 修复方式

**【事实】** 所有顶层切换只剩一个入口：

```kotlin
// hub/TopLevelNavigation.kt
fun NavHostController.navigateToTopLevel(tab: HubTab) {
    navigate(tab.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
```

底栏与首页快捷入口都走它；「点当前标签」由 `topLevelNavDecision(currentTab, target)` 判定为
`StayOnCurrentTab`，不发导航。任务书要求"不要未经验证直接删 `restoreState`"——
**没有删**：删掉会让标签页滚动位置与工具页搜索/筛选状态一起丢失，属于用新问题换旧问题。

### 2.3 自动化验证（不是靠"看起来好了"）

**【事实】** `test/.../hub/HubAppNavigationTest.kt` 用真实 `HubApp` + 真实 `NavHostController`
（Robolectric + `createAndroidComposeRule<ComponentActivity>()`）跑 7 个用例，覆盖：

1. 冷启动落在首页；
2. 四个标签之间可以任意切换；
3. 连续点击当前标签不重复创建页面、不崩；
4. **从首页快捷入口进工具页之后能回到首页**（真机故障原场景，连做两次确认返回栈未被污染）；
5. 快捷入口与底栏进入的是同一个工具页；
6. 工具详情是二级页面：断言此时四个底栏标签的可点节点数均为 0，返回键回到工具页；
7. 工具页的系统返回键回到首页。

加上纯 JVM 的 `TopLevelNavigationTest`（路由↔标签映射、二级路由不被当成标签、4×4 全组合、
点当前标签不导航）与 `--tests "com.androidmcphub.app.hub.*"` 的过滤运行：
**【事实】** 全部通过（XML：`tests=7 failures=0 errors=0`）。

---

## 3. 页面信息架构调整（交付物 ④）

| 页面 | 改版前 | 现在 |
| --- | --- | --- |
| 首页 | 连接状态 + 2×3 设备概况 + ping 摘要 + 一张列出全部底栏标签的「快捷操作」大卡 + 最近活动 | 连接状态卡（连接/断开 + 「连接详情」入口）+ 设备概况 2×2 + 最近一次诊断摘要 + **两项**快捷入口（工具目录、连接诊断）+ 最近活动；不再出现服务器 URL / 令牌 / 超时参数 |
| 工具 | 工具列表 + 「其他来源」未开放卡 + 「关于 phone_ping」长篇实现说明 | 搜索 + 分类胶囊 + 统计行（本机能力 N 个 · 当前可用 M 个 + 授权状态标签）+ 工具卡片；实现原理搬到二级页「工具说明」（右上「说明」进入） |
| 服务 | 空状态 + 「支持的服务类型」卡 + 添加服务表单预览页（不可保存） | 「我的服务 0 个」+ 诚实空状态 + **禁用**的「添加 MCP 服务器（即将支持）」+ 「相关设置」入口；删掉服务类型卡、开发文档路径与不可保存的表单 |
| 设置 | 一页装 10 个输入框 + 开关 + 保存 + 诊断入口 + 关于 | 设置主页只有分组入口：服务器与设备配置 / 高级连接参数 / 设备令牌管理 / 能力授权开关 / 连接诊断 / 清空日志 / 清除最近一次错误 / 关于 |
| 二级页 | 8 个（含 `services/add`） | 8 个：连接详情、工具详情、工具说明、服务器与设备配置、高级连接参数、设备令牌管理、连接诊断（`services/add` 已删除） |

二级页统一规则：`PageHeader`（标题 22sp + 返回箭头）、不显示底部导航、系统返回键与返回箭头
行为一致、统一 16dp 页面边距、卡片间距 10dp。

**【判断】服务页为什么不留表单**：Stage 1 的 `AddServiceScreen` 长得像能用，但点
「保存并拉取工具」什么也不会发生。任务书明确禁止"看起来已经成功保存、实际却没有保存"的界面，
所以宁少一个假功能：入口禁用并标注「即将支持」，文案写明"现在不会保存任何服务配置，
也不会显示任何连接状态"。将来真实实现接入后，再按 `docs/UI-Architecture.md` §7 的落点重建。

---

## 4. 功能保留核对表（交付物 ⑤，任务书 §6 的 10 项）

| # | 功能 | 现在的位置 | 状态 |
| --- | --- | --- | --- |
| 1 | WebSocket 连接 / 断开 | 首页「连接状态」卡（Idle 才能连接；连接中/已连接只能断开） | 保留，逻辑未改 |
| 2 | 服务器 URL 配置 | 设置 → 服务器与设备配置 | 保留 |
| 3 | 设备 ID 配置 | 设置 → 服务器与设备配置 | 保留 |
| 4 | 设备令牌 输入 / 显示隐藏 / 清除 | 设置 → 设备令牌管理（同一 `TokenField`，同一 `HubViewModel` 动作） | 保留，安全行为未改 |
| 5 | 配置保存与原有校验 | 三个设置子页各自的「保存配置 / 保存令牌」→ `HubViewModel.save()` | 保留（`HubConfig` 校验与默认值回落未改） |
| 6 | 超时与重连参数 | 设置 → 高级连接参数（7 个数值：ping 超时 / 心跳周期 / 初始重连间隔 / 最大重连间隔 / 抖动比例 / 最大挂起请求数 / 单帧字节上限） | 保留，一个不少 |
| 7 | 本机能力授权总开关 | 设置主页「安全与授权」 | 保留；默认关闭；每次请求实时读取的行为未改 |
| 8 | 连接诊断与 ping 信息 | 设置主页 → 连接诊断（+ 首页最近一次诊断摘要的入口） | 保留（含 request_id 长按复制） |
| 9 | 日志查看与清理 | 设置主页 → 连接诊断（`N/200` + 级别色 + 时间格式） | 保留 |
| 10 | 最近一次错误的展示与清除 | 首页 ping 摘要显示错误文本；设置主页「清除最近一次错误」 | 保留 |

**【事实】** 没有任何一项被"移到二级页后找不到"：设置主页 3 个分组入口 + 诊断入口共 4 个点击
即可到达全部功能，首页直达连接/断开与诊断。

---

## 5. 自动化测试与构建结果（交付物 ⑥）

全部在同一工作树、同一次验证里完成（命令写在下面，均 **exit 0**）：

| 检查 | 命令 | 结果 |
| --- | --- | --- |
| Android 单测（含 Robolectric） | `./gradlew --no-daemon -Djava.net.preferIPv4Stack=true :app:testDebugUnitTest` | **25 个测试类 / 301 条 / 0 失败 / 0 错误 / 0 跳过**（Stage 1 基线 279；新增 22 条） |
| Android lint | `:app:lintDebug` | **0 errors / 38 warnings** |
| Manifest 类校验 | `:app:verifyManifestClasses` | ✅ 7 个组件全在 dex 里（11 个 dex / 14,606 类） |
| Debug APK | `:app:assembleDebug` | 构建成功（文件信息见 §7） |
| Worker 类型检查 | `npm run typecheck`（`worker/`） | 0 错误 |
| Worker 单测 | `npx vitest run` | 14 文件 / 331 通过 |
| 本地 E2E | `npm run e2e` | **78 通过 / 0 失败** |

**lint 的 38 条警告构成【事实】**：30 条来自 `android/gradle/libs.versions.toml` 的
依赖版本提示（含本轮新增的 Robolectric / `androidx.test.ext:junit` / `ui-test` /
`navigation-testing` 的"有更新版本"提示，以及 AGP 版本提示），8 条来自受保护或历史文件
（`PhoneLinkService.kt` 2、`AndroidSecretCipher.kt` 2、`HubPrefs.kt` 1、`SplashActivity.kt` 1、
`AndroidManifest.xml` 1）。**新增的 `hub/` 与 `ui/` 文件零警告**；Stage 1 遗留的
`ConstantLocale` 警告随旧 `HubScreen.kt` 删除而消失。

**【事实】** 没有通过删除、跳过或修改既有断言来制造通过：Stage 1 的 279 条断言全部保留，
`ThemeTokenTest` 只按新 Token 值同步（值本身是任务书给的区间内的取值）。

**【事实】** Worker 零修改：

```
$ git diff --stat daf4b2c -- worker .github
（无输出）
```

---

## 6. 受保护范围逐项核对（【事实】）

| 范围 | 本轮是否修改 |
| --- | --- |
| `android/app/src/main/java/com/androidmcphub/app/protocol/**` | 未修改 |
| `.../net/**` | 未修改 |
| `.../capability/**` | 未修改 |
| `.../service/PhoneLinkService.kt` | 未修改 |
| `.../core/DeviceTokenStore.kt`、`core/AndroidSecretCipher.kt`、`core/HubPrefs.kt`、`core/HubLog.kt`、`core/HubConfig.kt` | 未修改（`HubPrefs` 键名与默认值未动） |
| `worker/**`、`.github/workflows/deploy-worker.yml` | 未修改（diff 为空） |
| 能力总开关默认值 | 仍为关闭（`DEFAULT_CAPABILITIES_ENABLED = false`） |
| 七步校验、错误码闭集、连接代次校验、请求去重 | 未修改 |
| 令牌加密存储方式 | 未修改（仍走 `AndroidSecretCipher`，界面只显示"已保存（加密存储）"） |
| 生产 Cloudflare 配置 | 未修改，未部署 |
| `gate0` 分支 | 未修改、未合并、未推送 |

**【事实】** 报告、测试与提交信息里**没有出现**任何设备令牌明文；`HubAppNavigationTest`
构造的是真实 `HubViewModel`，但从未写入令牌内容。

---

## 7. APK 与交付方式（交付物 ⑧⑨）

| 项 | 值 |
| --- | --- |
| 路径 | `android/app/build/outputs/apk/debug/app-debug.apk` |
| 大小 | **13,554,409 字节**（Stage 1 为 13,591,018，−36,609 字节 ≈ −36 KB） |
| SHA-256 | `998677896e4985982d1081eb642488110bf0a6e7c034c0b2ff81add5f6024d09` |
| 签名 | debug 签名（可直接 `adb install -r`）；包名 `com.androidmcphub.app` |

**【判断】** 体积略降的原因：删掉整页 `AddServiceScreen`、工具页删掉两张说明卡、
首页删掉快捷操作大卡，抵消了 Robolectric 只进测试范围不打包、以及新增二级页的成本。

**下载方式**：本机已有的下载服务（`/root/dsh-apk-dl.py`，只监听 `127.0.0.1:8933`、
白名单式）新增条目 `AndroidMCPHub-UI-Stage2-debug.apk`，并新增了三份文档。
在**手机上**打开：

- APK：`http://127.0.0.1:8933/AndroidMCPHub-UI-Stage2-debug.apk`
- 交付报告：`http://127.0.0.1:8933/UI-Stage2交付报告.md`
- 人工验收清单：`http://127.0.0.1:8933/UI-Stage2人工验收清单.md`
- 架构说明：`http://127.0.0.1:8933/UI架构说明.md`

（DSH 桥接的下载服务跑在手机本机的 127.0.0.1 上，所以**要在手机上打开这个地址**，
不是在电脑上；它不做鉴权，仅本机可访问。若你不希望安装 debug 包，可以只用分支代码。）

---

## 8. 已知限制（交付物 ⑦）与未实现功能

**已知限制**

1. **没有真机、没有模拟器**：本环境 `adb devices` 无设备，因此**没有做视觉验收**，
   交付物里**没有运行截图**；`docs/UI-Stage2-Manual-QA.md` 的每一条都要人在设备上跑。
   Robolectric 只能证明导航决策与返回栈行为，**不能**证明渲染、间距观感与系统栏避让。
2. **工具目录仍是手写只读目录**：`ui/model/ToolCatalog.kt` 与 `PhoneLinkService` 内部的
   能力注册表之间存在重复定义，靠 `ToolCatalogTest` 逐字钉住。要真正收敛，需要让
   `service/PhoneLinkService.kt` 暴露只读快照 —— 属于受保护范围，本轮没动。
3. **Android 16 边到边只做了代码层适配**（`Scaffold` innerPadding + NavHost 的
   `imePadding()`），没有在 Android 16 设备上确认过系统栏遮挡。
4. **深色模式只到 Token 层**，没有应用内主题切换开关（设计稿的"主题与外观"未实现）。
5. **依赖升级提示仍在**（lint 30 条来自 `libs.versions.toml`）：本轮刻意不动生产依赖版本，
   避免把 UI 改版扩大成依赖升级。
6. **二级页比 Stage 1 多了 4 个**（服务器与设备配置、高级连接参数、设备令牌管理、工具说明）。
   这是任务书 §4.4 要求的拆分；代价是设置里的一次操作可能多一跳，边界是"单一普通操作不再往下拆"。
7. **`HubViewModel` 未拆**：设置三个子页共用同一个 `HubViewModel.draft`，因此"改了一个子页
   未保存就切到另一个子页"时草稿是共享的（与改版前行为一致）。**【建议】**后续若按页拆分草稿，
   需先确认保存语义，属于任务书 §7 允许记为技术债的范围。

**未实现功能（不要当成缺陷）**

1. 第三方 MCP 服务器的真实接入（列表、添加、工具发现、认证存储）——本轮不实现；
2. 本地 HTTP MCP 中转；
3. 协议级授权状态机与「允许 / 拒绝」交互（`ToolDetailScreen` 只写了将来落点，没有假按钮）；
4. 工具执行入口（工具页/详情页都不能执行工具，执行仍只由 Worker 经连接下发）；
5. 授权/执行任务的持久化与查询接口；
6. 应用内主题切换。

---

## 9. 真机测试清单（交付物 ⑩）

见 `docs/UI-Stage2-Manual-QA.md`。重点顺序：**A 段（导航可靠性，10 条）→ D 段（10 项功能完整性）
→ B 段（信息架构）→ C 段（视觉/密度）→ E 段（不许退化的三件事）→ F 段（令牌安全与失败状态）**。
清单末尾有回填模板与"明确的未实现功能"。

---

## 10. 任务书完成标准逐条对照

| 标准 | 结果 |
| --- | --- |
| 四个主页面职责清晰 | ✅ 首页=控制台、工具=目录、服务=第三方管理入口、设置=分组入口 |
| 导航可靠 | ✅ 单一入口 + `topLevelNavDecision`；7 条 Robolectric 返回栈测试通过（**真机待人工确认**） |
| 已有功能完整 | ✅ §4 的 10 项全部可到达、可操作 |
| 令牌安全未变 | ✅ 存储与显示方式未改；报告/测试/提交无明文 |
| 能力总开关默认关闭 | ✅ 未改 |
| 工具列表由真实数据驱动 | ⚠️ 由只读目录驱动，与该目录一致；目录与注册表仍靠单测钉住（限制 2） |
| 服务页定位正确 | ✅ 诚实空状态，无假服务/假连接/假工具数 |
| UI 不直接依赖网络与协议执行层 | ✅ 页面只依赖 `HubViewModel` 暴露的展示状态 |
| 主题集中管理 | ✅ 颜色/字号/圆角/间距全在 `ui/theme/`，页面无字面量 |
| 不出现新的巨型页面文件 | ✅ 最大页面文件 `HubApp.kt` 254 行（旧 `HubScreen.kt` 646 行） |
| 不返回一个巨型 `HubScreen.kt` | ✅ 未重新合并 |
| Android 构建与既有测试通过 | ✅ 301 条单测 / lint 0 errors / APK 构建成功 |
| Worker 代码没有修改 | ✅ diff 为空；typecheck + 331 单测 + 78 E2E 全过 |
| 不修改生产部署配置 | ✅ 未修改、未部署 |
| 提供不互相矛盾、且与现实一致的说明 | ✅ 本文档 + `docs/UI-Architecture.md` + 人工清单，**没有截图时不宣称已验收** |
| 视觉紧凑、页面不过长 | ⚠️ Token 层与结构层已收紧（**观感需真机确认**） |
