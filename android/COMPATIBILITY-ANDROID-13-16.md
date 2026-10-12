# Android 13–16 兼容性核对 —— `PhoneLinkService` 与前台服务

本文件回答 Gate 0.1 任务书第五节「Android 系统兼容性」的 9 个检查点。

**标签约定**（与 `docs/OAUTH-DESIGN.md` 一致）：

- `[官方规范]`：出自 developer.android.com / Play Console 帮助文档的原文规定，附链接。
- `[经验判断]`：基于规范的推断，**没有**官方明文，也没有真机验证。
- `[真机未验证]`：**必须在真机上跑才能确认**，本项目没有跑过。禁止把这一类当成通过。

---

## 0. 先交代本工程的版本现状（免得读者被下面的 API 号绕晕）

| 项 | 值 | 出处 |
| --- | --- | --- |
| `compileSdk` | **35** | `android/app/build.gradle.kts` |
| `targetSdk` | **35** | 同上 |
| `minSdk` | **26** | 同上 |
| `versionName` / `versionCode` | `0.1.1-gate0.1` / `2` | `android/gradle/libs.versions.toml:40-41` |
| 安装环境能装到哪一版 | 低于 `minSdk` 26 的设备直接装不上 | Manifest 的 `uses-sdk` 由 Gradle 注入 |

**`targetSdk` 这一轮没有升到 36，这是有意的，理由要写清楚：**

1. 本机的 Android SDK 只有 `platforms/android-35`（`/opt/android-sdk`），装 `android-36` 需要联网下载 platform + build-tools，属于"为了改一个数字去动整条构建链"。
2. 升 `targetSdk` 会**新引入**一批行为变更（见 §9），在没有真机验证的情况下升上去，等于把"未验证"的范围扩大。
3. 但**必须同时说明**：Android 16 上有一条新规**不看 targetSdk**（前台服务里再起后台任务要受 JobScheduler 配额约束），所以"targetSdk 35"并不等于"在 16 上什么都不变"。

**升级到 36 这件事本身：`[真机未验证]` + 未执行。** 需要 `android-36` platform，且必须在 Android 16 真机上跑一遍 §9 的清单。

---

## 1. 通知权限（Android 13 / API 33 引入）

| 版本 | 规定 | 我们的状态 |
| --- | --- | --- |
| 13 (33) | 有通知就必须在运行时拿 `POST_NOTIFICATIONS` | 在界面里用 `rememberLauncherForActivityResult` 主动弹一次；**拒绝也不阻止连接** |
| 14/15/16 | 同 13 | 同 13 |

`[官方规范]` 关键结论（这条最容易搞错）：**没有 `POST_NOTIFICATIONS` 一样可以启动前台服务，服务照常运行**；被拒绝的后果是"所有通知渠道被屏蔽，用户只能在**任务管理器**里看到前台服务的提示，通知抽屉里看不到"。
<https://developer.android.com/develop/ui/views/notifications/notification-permission>

所以我们的行为是对的，而且是刻意的：`service/PhoneLinkService.kt` 的 `updateNotification()` 整段包在 `runCatching` 里 —— 通知发不出去只丢一次 `notify`，**绝不影响 WebSocket 与心跳**。

**没有申请**的相邻权限：`SCHEDULE_EXACT_ALARM`、`USE_FULL_SCREEN_INTENT`、`SYSTEM_ALERT_WINDOW`（都不需要）。

`[真机未验证]`：拒绝通知权限后，连接与心跳仍然正常 —— 需要真机确认（`README-ANDROID.md` §8 第 10 条）。

---

## 2. 前台服务类型与权限声明（Android 14 / API 34 起强制）

`[官方规范]`：

- 目标 34+ 的应用，如果**没在 manifest 里声明服务类型**，`startForeground()` 抛 `MissingForegroundServiceTypeException`。
- 声明了类型但**没申请该类型对应的 `FOREGROUND_SERVICE_*` 权限** → 抛 `SecurityException`，服务起不来，可能直接崩。
- **`specialUse` 的运行时前提是 `None`** —— 只需要 `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE` 两条，不需要别的任何权限。
  <https://developer.android.com/about/versions/14/changes/fgs-types-required>

我们的声明（`android/app/src/main/AndroidManifest.xml:16,18,47-54`）：

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
...
<service android:name=".service.PhoneLinkService"
         android:exported="false"
         android:foregroundServiceType="specialUse">
    <property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
              android:value="Persistent user-initiated WebSocket command channel ..." />
</service>
```

自查结果：类型声明有、对应权限有、`<property>` 说明有、`exported="false"`、权限清单总共 5 条且没有一条是多余的。

---

## 3. 前台服务的启动限制（Android 12 / API 31 起）

`[官方规范]`：目标 31+ 的应用**在后台时不能启动前台服务**，否则抛 `ForegroundServiceStartNotAllowedException`。豁免场景包括：从用户可见状态迁移过来、收到 `BOOT_COMPLETED`/`LOCKED_BOOT_COMPLETED`/`MY_PACKAGE_REPLACED`、高优先级 FCM、"**用户在应用界面上做了一个操作**"、精确闹钟、地理围栏/活动识别、当前输入法、设备/资料所有者、CompanionDeviceManager + `REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND`、电池优化已关闭、`SYSTEM_ALERT_WINDOW`（目标 35+ 还要求悬浮窗**当前可见**）。
<https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start>

本应用所处的情形：用户**在可见界面上点「连接」**，这属于"用户可见状态 + 用户操作"两个豁免。`[经验判断]` 但这**不是无条件的** —— 调用 `startForegroundService()` 的那一刻 Activity 必须真的是可见/已恢复的；如果用户在点完之后立刻切走、或者从通知栏的 action 触发，就可能落到"后台启动"。

**这正是 Gate 0.1 审计 R3 要修的东西**：我们不再假设"一定成功"，而是把它当成一次**可能失败的操作**来处理（`service/ConnectGate.kt`）：

```kotlin
ConnectStartupSequence(
    enterForeground = { goForeground(buildNotification(LinkState.Connecting)) },
    startLink       = { startLink() },
    onAbort         = { category -> abortStartup(category) },
).run()
```

- `goForeground()` 返回 `ForegroundResult.Started` / `Failed(category)`，**返回 Unit 的旧签名已经删掉**（旧版本失败被 `onFailure` 吞掉，然后照样建连 —— 这就是缺陷本身）。
- 失败时：不建连、丢掉连接对象、状态置 `Error`（文案取自固定类别）、撤通知、`stopSelf()`，**并且不安排任何重连**。
- 异常**只按类名**归类（`ForegroundServiceStartNotAllowedException` / `MissingForegroundServiceTypeException` / `SecurityException` / `IllegalStateException` / `RuntimeException` / 未知），**不用 `is` 判断** —— 那两个类只在 API 31+/34+ 存在，低版本设备上直接引用会去加载不存在的类。归类结果落在一个**闭集**里，异常 message 不进日志。

`[真机未验证]`：在后台（例如刚切到别的 app）触发连接时，"拒绝启动且不建立任何 WebSocket"这条需要真机确认（`README-ANDROID.md` §8 第 14d 条）。

---

## 4. 前台服务超时（Android 15 / API 35 起）

`[官方规范]`：**6 小时/24 小时**的上限**只适用于 `dataSync` 和 `mediaProcessing`**（后者是 15 新增）；到点会调 `Service.onTimeout(int,int)`，几秒内不 `stopSelf()` 就抛 `RemoteServiceException`。**`specialUse` 不在名单里，没有超时。** `shortService` 另有更严的上限。
<https://developer.android.com/develop/background-work/services/fgs/timeout>

⇒ 一个"长期保持"的通道**不能**用 `dataSync` 来糊弄（那样会被系统在 6 小时后掐掉）。这反过来印证了 §7 的类型选择。

---

## 5. 应用退到后台 / 锁屏后的连接行为

`[官方规范]`：Doze 会"暂停网络访问"，而且官方文档**明确写着**："If your app requires a persistent connection to the network to receive messages, use Firebase Cloud Messaging (FCM) if possible." —— 这句话就是冲着"长连接"这类场景说的。
<https://developer.android.com/training/monitoring-device-state/doze-standby>

`[官方规范]`：应用待机（App Standby）的"非空闲"豁免里包含"应用有进程处于前台，**无论是 Activity 还是前台服务**"。所以跑着前台服务的应用不会被丢进 idle bucket。

`[经验判断]` —— 这两条要分开看，**不要合并成"前台服务 = Doze 也能联网"**：
前台服务把它挡在 idle bucket 之外，但**没有任何官方文档说它豁免 Doze 的网络挂起**。所以长 Doze 窗口仍可能把 socket 掐断。我们的应对不是"保证不断"，而是"断了能恢复"：`PhoneLinkService` 有网络回调 + `PhoneLink` 的指数退避重连（含抖动），并且**手动断开后禁止自动重连**。

`[真机未验证]`：锁屏 30 分钟、Doze 若干小时后 socket 的真实存活情况 —— 需要真机长时间观察。**这是本项目已知的最大不确定性之一**，不要当成已验证。

---

## 6. Wi-Fi ↔ 移动网络切换、飞行模式

`[官方规范]`：`registerDefaultNetworkCallback()` 跟踪的是**系统当前选定的默认网络**；它的 `onLost()` 只表示"不再是默认网络"（那条网络可能还连着）。要看"别的可用网络"得用 `NetworkRequest` + `registerNetworkCallback()`。两者都**没有**被文档承诺"能在 Doze 里维持网络"——只有 `requestNetwork()` 会主动把网络拉起来。
<https://developer.android.com/develop/connectivity/network-ops/reading-network-state>

`[经验判断]`：我们的需求只是"有没有可用的默认网络、它变了没有"，所以 `registerDefaultNetworkCallback()` 是更省的那个选择，然后在 `onAvailable` 时重新拨号。当前实现就是这样（`service/PhoneLinkService.kt` 的 `registerNetworkCallback()` → `client?.onNetworkAvailable()`）。

飞行模式：`[官方规范]` 关掉无线电会拆掉默认网络 → `onLost`。恢复后 `onAvailable` 触发重连。**前提是用户没有手动断过**（手动断开后 `userStopped` 为真，自动重连整个被禁止）。

`[真机未验证]`：切 Wi-Fi↔移动、开关飞行模式后的自动恢复。

---

## 7. 用户主动断开后的资源释放

代码路径（`service/PhoneLinkService.kt`）：`ACTION_DISCONNECT` → `handleDisconnect()` → `client?.stop()` → `client = null` → `stopForegroundService()`（`ServiceCompat.stopForeground(STOP_FOREGROUND_REMOVE)` + `stopSelf()`）。

`[官方规范]` 相关的两条：①`stopForeground(STOP_FOREGROUND_REMOVE)` 会同时撤掉通知；②`stopSelf()` 之后系统会在 `onDestroy()` 里回收（我们的 `onDestroy` 里注销网络回调、停 watchdog、关 OkHttp 的 `Dispatcher`/连接池）。
`[经验判断]` WebSocket 的关闭需要等对端回 `Close` 还是直接掐，OkHttp 的 `webSocket.close()`/`cancel()` 语义不同：我们用 `cancel()`（立即释放）而不是优雅握手，因为"用户点了断开"就该立刻断，不必等对端。

`[真机未验证]`：点断开后 socket 真的关掉、通知真的消失、进程里没有残留线程。

---

## 8. 系统结束应用进程后的状态恢复

**这是刻意不做的**：`onStartCommand` 返回 `START_NOT_STICKY`，进程被杀后**不会**被系统自动重建服务。

理由：这个应用的第一原则是"用户手动开启、用户手动关闭"。如果系统在杀进程后又把它拉起来，就等于**用户没同意的情况下**重新建立了一条通往用户服务器的连接。任务书第六节要求"手动停止后禁止自动重连"，这条精神在"进程被杀"这个场景上同样适用。

代价（要诚实说）：进程被杀之后，**直到用户再次打开应用并点「连接」之前，这条链路是断的**；此时 ChatGPT 那边调 `phone_ping` 会拿到 `phone_offline`。这是设计选择，不是 bug。

`[官方规范]` 补充：目标 34+ 的应用从 `BOOT_COMPLETED` 广播里启动某些类型的前台服务也受限；我们**没有申请** `RECEIVE_BOOT_COMPLETED`，也**不注册**任何开机广播 —— 从源头上就没有"自启动"这条路。

---

## 9. 边到边（edge-to-edge）与返回手势：升到 36 需要处理的两件事

### 9.1 边到边

`[官方规范]`：`windowOptOutEdgeToEdgeEnforcement` 在 Android 16（目标 36）上**被弃用且已禁用** —— 想退出边到边是做不到的（只有目标 36 的应用跑在 Android 15 设备上时才还生效）。`WindowInsets.safeDrawing` = `systemBars` + `displayCutout` **+ `ime`**。
<https://developer.android.com/about/versions/16/behavior-changes-16> · <https://developer.android.com/develop/ui/compose/layouts/insets>

排查结果（`grep -rn` 全仓库）：
- `windowOptOutEdgeToEdgeEnforcement` —— **零处引用**，没有要删的。
- `setStatusBarColor` / `setNavigationBarColor` / `layoutInDisplayCutoutMode` —— **零处引用**，所以"新版本变空操作/行为改变"与我们无关。
- `windowSoftInputMode` —— **零处引用**（默认值）。`[官方规范]` `adjustResize` 在边到边下不再真正 resize 窗口，所以靠它兜键盘是行不通的；我们用 inset 处理。
- 内边距**只在唯一一个地方**应用：`hub/HubApp.kt` 的 `Scaffold` 给出 `innerPadding`，套在 `NavHost` 上；页面内部不再各自 `safeDrawingPadding()`。（UI Stage 1 改版前是 `ui/HubScreen.kt:123` 的根部 `Modifier.safeDrawingPadding()`，该文件已删除。）
- `NavHost` 上另加 `Modifier.imePadding()`：`Scaffold` 默认的 `contentWindowInsets` **不含** `ime`，设置页的输入框需要它。系统栏与键盘分属两个 inset，不会叠加成双倍内边距。
- 本轮新增：`MainActivity.onCreate` 里显式调用 `enableEdgeToEdge()`。`[经验判断]` 在低版本上它让布局走同一条路径，避免"在 Android 14 上正常、在 16 上被系统栏盖住"这类只在新机上出现的差异。
  （`enableEdgeToEdge` 来自 `androidx.activity`，该依赖本来就因为 `activity-compose` 在场，没有新增依赖。）

### 9.2 返回手势 / 预测性返回

`[官方规范]`：目标 36 时预测性返回默认开启，`onBackPressed` 不再被调用、`KEYCODE_BACK` 不再派发；不相容的做法是设 `android:enableOnBackInvokedCallback="false"`。

本应用**根本没有拦截返回**：`grep -rn "onBackPressed"` 零处命中，也没有 `OnBackInvokedCallback`、没有 `BackHandler`。所以返回键/手势就是"结束这个 Activity"，无论开不开预测性返回都一样。⇒ 这一项**不需要改代码**，也**不需要**去设那个"关掉预测性返回"的开关（设了反而是给自己加一条没必要的兼容分支）。

### 9.3 Android 16 上还有一条不看 targetSdk 的规则

`[官方规范]`：**从前台服务里启动的后台任务（JobScheduler / WorkManager / DownloadManager），现在要遵守各自的运行配额**，而且这条**与 targetSdk 无关**，只要设备是 Android 16 就生效；想豁免要用 user-initiated data transfer job。
<https://developer.android.com/develop/background-work/services/fgs/changes>

我们的现状：**一个 JobScheduler / WorkManager / DownloadManager 都没用**（依赖里没有 `work-runtime`，代码里也没有 `JobScheduler`）。前台服务里跑的是 OkHttp 的 WebSocket 和一个 `ScheduledExecutorService` —— 都不是"系统后台任务"。⇒ **这条规则当前不适用**。但这是一个"以后加定时任务时要记得"的坑，写在手册里。

### 9.4 升到 targetSdk 36 还需要知道的

`[官方规范]` 目标 35→36 的行为变更里，与本应用**有关**的只有上面 9.1/9.2；`BODY_SENSORS` 拆成 `android.permissions.health` 与我们无关（我们没有任何传感器权限）。另外"后台 Activity 启动（BAL）"：目标 34+ 的 `PendingIntent` 发送方需要用 `ActivityOptions.setPendingIntentBackgroundActivityStartMode()` 显式选择 —— 我们**没有**任何 `PendingIntent` 会去启动 Activity（通知的 `contentIntent` 是 `Activity` 的 `getActivity()`，它是用户点击触发的，不属于后台启动）。

---

## 10. `specialUse` 是否合规，以及替代方案的兼容性影响

任务书要求："前台服务类型必须符合 Android 官方要求。如果现有 `specialUse` 声明不适合实际用途，应提出符合规范的替代方案，并说明兼容性影响。"

`[官方规范]` 三种候选类型的**硬性前提**对照（<https://developer.android.com/about/versions/14/changes/fgs-types-required>）：

| 类型 | 必需的 manifest 权限 | 运行时前提 | 超时 | 实际用途是否匹配 |
| --- | --- | --- | --- | --- |
| `specialUse`（**当前采用**） | `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE` | **None** | 无 | "不属于其他任何类型"的用户可感知长连接 |
| `connectedDevice` | `FOREGROUND_SERVICE_CONNECTED_DEVICE` **外加至少一条**：`CHANGE_NETWORK_STATE`/`CHANGE_WIFI_STATE`/`CHANGE_WIFI_MULTICAST_STATE`/`NFC`/`TRANSMIT_IR`，或已授予的 `BLUETOOTH_CONNECT`/`BLUETOOTH_ADVERTISE`/`BLUETOOTH_SCAN`/`UWB_RANGING`，或 `UsbManager.requestPermission()` | 视上面选哪条 | 无 | ❌ 它指"与**外部设备**交互"。我们的 ws 是走互联网连 Cloudflare，没有外设 |
| `dataSync` | `FOREGROUND_SERVICE_DATA_SYNC` | None | **Android 15+ 目标 35+ 时 6 小时/24 小时** | ❌ 6 小时后会被系统掐掉，"长期保持"直接不成立 |

**结论：保持 `specialUse`。** 三条理由：

1. **它不会超时**，而 `dataSync` 会 —— 对一个"长期通道"来说这是决定性的。
2. **它的运行时前提是"无"**，不需要为了凑类型去申请 `CHANGE_NETWORK_STATE`、蓝牙或 USB 这类与功能无关的权限。任务书明令"不要为了维持后台连接而申请不必要的高风险权限"，选 `specialUse` 正好避免了这件事。
3. `connectedDevice` 的语义是"与外部设备交互"，把它套在这条互联网连接上是**在撒谎** —— 而任务书明令不使用虚假的类型绕过系统限制。

**选 `specialUse` 的代价（必须说清楚）**：

- `[官方规范]` 目标 34+ 要在 Play Console 填一份**前台服务声明**（功能说明、被延迟/中断时对用户的影响、演示视频链接、用途），而且 `specialUse` 在类型表里**没有预置用途**，审阅者读的就是 `<property>` 里那段自由文本，所以那段文字必须能自证必要性。**对侧载/调试包不影响安装与运行，只在提交商店时才是门槛。**
  <https://support.google.com/googleplay/android-developer/answer/13392821>
- `[经验判断]` 措辞上我们刻意写成"Persistent user-initiated WebSocket command channel to the user's own Android MCP Hub relay; ... No location, media, or file access."—— 把"用户发起""连的是用户自己的服务器""不碰位置/媒体/文件"三件事说在前面，因为这三件正是审阅者最会追问的。

---

## 11. 本文件里"没有验证过"的部分（照抄进 TEST_REPORT 的 NOT RUN 清单）

全部为 `[真机未验证]`，**一条都没有在设备上跑过**，因为本项目的构建环境是"能在容器里编译、但没有可用的真机/模拟器"：

1. 拒绝 `POST_NOTIFICATIONS` 后连接与心跳仍正常（§1）。
2. 后台触发连接被系统拒绝时**不建立任何 WebSocket**（§3）。
3. 锁屏 30 分钟 / Doze 数小时后 socket 是否存活、能否自愈（§5）。
4. Wi-Fi ↔ 移动网络切换后自动重连（§6）。
5. 开关飞行模式后自动重连（§6）。
6. 点「断开」后 socket、通知、线程是否都释放干净（§7）。
7. 进程被杀后"不自动恢复连接"这一期望行为（§8）。
8. 在 Android 13 / 14 / 15 / 16 四种系统版本上，边到边与系统栏内边距的观感（§9.1）。
9. `targetSdk` 升到 36 后的整体回归（**本轮未执行升级**，见 §0）。

**JVM 单元测试不能替代上面任何一条。** 本轮新增的 Android 单测只覆盖纯逻辑（`ConnectGate` 的归类与顺序、`DeviceTokenStore` 的三态与编解码、`PhoneLinkClient` 的凭据失败路径），它们**不触碰** Service、Notification、Keystore、网络回调 —— 本工程没有引 Robolectric，也跑不了这些。
