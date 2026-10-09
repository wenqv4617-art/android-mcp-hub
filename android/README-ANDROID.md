# android-mcp-hub 的 Android 端

这是 `android-mcp-hub` 仓库的手机端应用：一个**用户主动开启**的前台服务，通过 WebSocket 连到用户**自己的** MCP Hub 中继，
让配对的 MCP 客户端能够到达这台设备。

本应用只做一件事：**维持一条命令通道，并把中继发来的保活探针原样应答**。
它不做工具管理、不扫描已安装应用、不启动其它应用、不做跨设备控制。

- 包名 / applicationId：`com.androidmcphub.app`
- 版本：`0.1.0`（versionCode 1）
- 最低系统：Android 8.0（API 26）；编译与目标 SDK：API 35

---

## 1. 构建与验收

```bash
cd android
ANDROID_HOME=/opt/android-sdk ./gradlew --no-daemon :app:assembleDebug
ANDROID_HOME=/opt/android-sdk ./gradlew --no-daemon :app:testDebugUnitTest
```

产物：`android/app/build/outputs/apk/debug/app-debug.apk`

`local.properties` 里写的是 `sdk.dir=/opt/android-sdk`，该文件已被仓库根目录的 `.gitignore` 忽略，不要提交。

工程用的是 **Version Catalog**（`gradle/libs.versions.toml`），并且 `settings.gradle.kts` 里开了
`RepositoriesMode.FAIL_ON_PROJECT_REPOS`，依赖仓库只能在 `settings.gradle.kts` 里声明一次。

### 1.1 在 aarch64 主机上构建（本工程当前的构建环境）

Google Maven 上的 aapt2（以及 `build-tools/34.0.0/aapt2`）**只有 x86_64 的可执行文件**。
在 aarch64 的 Linux/Android 容器里直接跑会得到：

```
aapt2: cannot execute: required file not found
> AAPT2 aapt2-8.7.3-12006047-linux Daemon #0: Daemon startup failed
```

本工程用一个 qemu-user 包装脚本解决这个问题，**只影响 aapt2 这一个原生工具**
（本工程没有 NDK/CMake，也没有 `.aidl`，d8/r8/apksigner/zipalign 都是 JVM 侧实现，不需要 x86_64 运行环境）：

```bash
# 1) 装 qemu-user（提供 /usr/bin/qemu-x86_64-static）
apt-get install -y --no-install-recommends qemu-user-static

# 2) 准备一个 amd64 sysroot（只需第一阶段，不会 chroot，也不动主机的 dpkg 状态）
apt-get install -y --no-install-recommends debootstrap
debootstrap --foreign --arch=amd64 --variant=minbase \
    --include=libstdc++6,zlib1g noble /opt/x86_64-root http://archive.ubuntu.com/ubuntu/
```

工程内已经带了包装脚本 `android/tools/aapt2`（`chmod 711`），它做的就是把参数转交给 qemu：

```sh
exec /usr/bin/qemu-x86_64-static -L /opt/x86_64-root /opt/android-sdk/build-tools/34.0.0/aapt2 "$@"
```

`android/gradle.properties` 里对应的那一行是：

```properties
android.aapt2FromMavenOverride=/root/android-mcp-hub/android/tools/aapt2
```

两个坑：

- **脚本文件名必须恰好是 `aapt2`，不能带 `.sh` 后缀。** AGP 的 `Aapt2FromMaven.create()`
  会检查 `override.endsWith("aapt2")`，不满足就直接抛
  `Custom AAPT2 location does not point to an AAPT2 executable: <路径>`，连插件都应用不了。
- aapt2 的版本 banner（`Android Asset Packaging Tool (aapt) 2.19-…`）**打在 stderr**，这是它的固有行为。

在真正的 x86_64 主机上构建时，把 `android.aapt2FromMavenOverride` 那一行注释掉即可，
`android/tools/aapt2` 可以留着不用（它只在被显式指向时才会被执行）。

### 1.2 单元测试需要 UTF-8 的 locale（`gradlew` 里已兜底）

单测方法名是用反引号写的中文（`fun \`收到 welcome 后进入已连接并记录代次（R6）\`()`），
Kotlin 会用方法名生成局部类名，于是类文件名里带中文。如果构建机的 `LANG` / `LC_ALL` 是空的
（容器里很常见），JDK 会把 `sun.jnu.encoding` 定成 `ANSI_X3.4-1968`，编译器写这些类文件时直接崩：

```
e: java.nio.file.InvalidPathException: Malformed input or input contains unmappable characters:
   .../PhoneLinkClientTest$真实链路上完成握手并回复 phone_pong$serverListener$1.class
```

`-Dsun.jnu.encoding=UTF-8` **在 JDK 17 上无效**（实测仍为 `ANSI_X3.4-1968`），只能在启动 JVM 之前设好 locale。
所以 `android/gradlew` 开头加了一段兜底：`LANG` 与 `LC_ALL` 都为空时导出 `LC_ALL=C.UTF-8`
（glibc 内置，不需要 `locale-gen`），已经显式设过的环境不动。手动指定时请注意
**必须是已生成的 locale**，例如 `LANG=en_US.UTF-8` 在没生成该 locale 的机器上会回退到 ANSI，照样崩。

如果不想依赖这段兜底，把这 90 个测试方法名改成 ASCII 也可以（Kotlin 标识符用中文只是好看，
中文注释与中文文案不受影响）。

---

## 2. 权限与前台服务

### 2.1 只申请了 5 个权限

| 权限 | 用途 |
| --- | --- |
| `INTERNET` | 建立 WebSocket 连接 |
| `ACCESS_NETWORK_STATE` | 用 `registerDefaultNetworkCallback()` 感知网络恢复，立刻重连 |
| `POST_NOTIFICATIONS` | Android 13+ 显示前台服务通知（**用户拒绝也照常连接**，只是看不到通知） |
| `FOREGROUND_SERVICE` | 前台服务基础权限 |
| `FOREGROUND_SERVICE_SPECIAL_USE` | 下面的 `specialUse` 类型 |

没有存储、位置、相机、通讯录、短信、电话权限，没有 `QUERY_ALL_PACKAGES`，
没有 `RECEIVE_BOOT_COMPLETED`、`WAKE_LOCK`、`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`。

### 2.2 为什么前台服务类型是 `specialUse`

这是**唯一诚实**的选择：

- **不是 `dataSync`**：本应用不同步数据，也不执行用户提交的传输任务。`dataSync` 的语义是「把用户数据传到别处」，套在这里名不副实。
- **不是 `connectedDevice`**：这个类型要求与外部设备（蓝牙、USB、NFC 之类）配对。本应用连的是用户自己的服务端中继，不是外设。
- **不是 `shortService`**：这是一条**长期**保持的连接，不是「几分钟内结束的短任务」。
- **`specialUse` 就是给「其它类型都覆盖不到的用例」准备的**：Android 官方文档明确这么写。
  这里的确没有更贴切的类型可选，所以用 `specialUse` 并如实填写用途说明，而不是挑一个限制更宽松、
  但语义不对的类型去糊弄系统。

声明原文（`AndroidManifest.xml`）：

```xml
<service
    android:name=".service.PhoneLinkService"
    android:exported="false"
    android:foregroundServiceType="specialUse">
    <property
        android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
        android:value="Persistent user-initiated WebSocket command channel to the user's own Android MCP Hub relay; receives and answers keepalive/ping frames so a paired MCP client can reach this device. No location, media, or file access." />
</service>
```

`android:exported="false"`：这个服务只能被本应用自己启动，其它应用无法拉起它。

### 2.3 代价

- 上架 **Google Play 需要提交 `specialUse` 用途说明并接受审核**（这个类型属于需要人工说明的用途）。
- 本应用定位是**自用/内测，不上架**，因此这个代价可接受；如果将来要上架，需要重新评估类型与权限。

### 2.4 启动与停止

**启动**：只有一种方式 —— 用户在前台点界面上的「连接」按钮，
由 `MainActivity` → `HubViewModel.connect()` → `PhoneLinkService.connect(context)` 触发 `startForegroundService`。

- **不做**开机自启（没有 `RECEIVE_BOOT_COMPLETED`，也没有 `BootReceiver`）。
- **不会**被系统在后台悄悄拉起：服务在 `onStartCommand` 里返回 `START_NOT_STICKY`，
  进程被杀掉后系统不会重建它。
- **不申请**电池优化白名单（没有 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`）：
  想让连接长时间保持，就把 App 放在最近任务里别划掉，这是用户可见、可撤销的选择。

**停止**：用户点界面上的「断开」，或者下拉通知栏点通知上的「断开」动作按钮
（`PendingIntent` 打到服务的 `ACTION_DISCONNECT`）：

```
handleDisconnect() → client.stop() → stopForeground(STOP_FOREGROUND_REMOVE) + stopSelf()
```

用户断开后 `PhoneLink` 会置位 `userStopped`，此后**任何**意外断开事件都不会再触发重连（单测有覆盖）。

此外还有两条自动停止的兜底：

- **会话看门狗**：连续 5 分钟没有收到任何服务端帧，就记日志、把状态置为「出错」并停止服务
  （避免一个已经死掉的通道长期占着前台服务）。
- **心跳超时**：连续 2 个心跳周期没收到任何服务端帧，判定链路已死并进入退避重连。

---

## 3. 连接协议

连接：`GET {wsUrl}`，**不带 query**；地址形如 `wss://<host>/phone/ws`（也接受 `ws://`、`https://`、`http://` 写法，内部会规范化，并丢掉 query 与 fragment）。

请求头：

| 头 | 值 |
| --- | --- |
| `Authorization` | `Bearer <用户在界面里填的设备令牌>` |
| `X-Device-Id` | `<用户在界面里填的设备 ID>` |
| `X-Client-Version` | `android-mcp-hub/0.1.0` |

服务端 → 手机：

```json
{"type":"welcome","device_id":"…","connection_generation":3,"server_time":1760000000000,
 "heartbeat_interval_ms":30000,"ping_timeout_ms":10000,"max_pending_requests":16,
 "protocol_version":"phone-link-v1"}
{"type":"phone_ping","request_id":"uuid","timestamp":1760000000123}
{"type":"heartbeat_ack","server_time":1760000000456}
{"type":"error","code":"message_too_large","message":"…"}
```

手机 → 服务端：

```json
{"type":"hello","device_id":"…","protocol_version":"phone-link-v1","app_version":"0.1.0","android_sdk":35}
{"type":"phone_pong","request_id":"<与 ping 逐字符相同>","timestamp":1760000000155}
{"type":"heartbeat","timestamp":1760000000500}
```

规则（实现位置 + 单测）：

| 规则 | 实现 | 单测 |
| --- | --- | --- |
| **R1** 收到 `phone_ping` 必须用**同一个** `request_id` 回 `phone_pong`，`timestamp` 取本机当前时间 | `protocol/PhoneLink.kt` 的 `handlePing()` | `PhoneLinkTest`、`PhoneLinkProtocolTest` |
| **R2** 同一个 `request_id` 只回一次（有界 LRU，容量 256） | `protocol/PingResponder.kt` 的 `RequestIdLru` | `PingResponderTest`、`PhoneLinkTest` |
| **R3** 每 `heartbeatIntervalMs` 发一个 `{"type":"heartbeat"}` | `PhoneLink.onTick()` | `PhoneLinkTest` |
| **R4** 未知 `type`（以及非法 JSON、缺 `request_id`）→ 忽略并记日志，**不回**错误帧 | `PhoneLinkProtocol.parseIncoming()` + `PhoneLink.onTextFrame()` | `PhoneLinkProtocolTest`、`PhoneLinkTest` |
| **R5** 单帧超过 `maxFrameBytes`（默认 65536）→ **不回**、记日志（按 **UTF-8 字节数**判断，不是字符数） | `PhoneLink.onTextFrame()` 最前面 | `PhoneLinkTest` |
| **R6** 收到 `welcome` → 记日志，并把 `connection_generation` 展示在界面上 | `PhoneLink.handleWelcome()` + `HubScreen` | `PhoneLinkTest` |

关于 RTT：显示的是 `(本机收到时刻 − ping 里的 timestamp) × 2`，也就是**假设两端时钟已由 NTP 同步**。
刻意**不**用 `welcome` 里的 `server_time` 去修正：`welcome` 是单向消息，用它估出的偏移量本身已经吸收了那一次的单程延迟，
再拿去修正只会把真实延迟「减没」。`PingResponder.clockOffsetMs` 只用于日志诊断。

### 3.1 连接生命周期

- 用户点「连接」→ 启动前台服务 → 建连 → 收到 `welcome` 才算「已连接（代次 N）」。
- 意外断开 → 指数退避 + 抖动重连：`delay = min(initial × 2ⁿ, max) × (1 ± jitter)`，`n` 是连续失败次数，
  连上之后归零。默认 1s 起、最多 60s、±20% 抖动。
- `401` / `403` → 判定**认证失败**，不自动重连（令牌错了重试没意义），等用户改令牌后手动重连。
- 服务器地址无效 / 令牌为空 / 配置非法 → 直接进「出错」状态，也不重连。
- 网络恢复（`onAvailable`）→ 如果正处于「用户已连接但没连上」，立刻重连并**重置退避**。
- 建连看门狗：发出 Open 后 20 秒内既没握上手也没报错，就当成失败进入退避（避免挂在「连接中」不动）。
- 所有状态变化写进环形日志，最多 200 条，最新的在最上面。

---

## 4. 配置与持久化

`core/HubConfig.kt` 的 `HubConfig` 是 data class，全部字段都能在界面「高级设置」里改：

| 字段 | 默认值 |
| --- | --- |
| `serverUrl` | `""`（占位，必须自己填，形如 `wss://<host>/phone/ws`） |
| `deviceId` | `test-phone-1` |
| `pingTimeoutMs` | `10000` |
| `heartbeatIntervalMs` | `30000` |
| `reconnectInitialDelayMs` | `1000` |
| `reconnectMaxDelayMs` | `60000` |
| `reconnectJitterRatio` | `0.2` |
| `maxPendingRequests` | `16` |
| `maxFrameBytes` | `65536` |

- 这些配置存在 `SharedPreferences("hub_settings")`（`MODE_PRIVATE`），键值映射是纯函数 `HubConfig.toMap() / fromMap()`，因此可 JVM 单测。
- **设备令牌单独存**（键 `device_token`），不属于 `HubConfig`：
  不进 `toString()`、不写日志、不出现在任何日志文本里，连接日志只记录 `host` 与设备 ID。
- `android:allowBackup="false"`：禁止 Android 自动备份，令牌不会被云备份带走。
- `res/xml/network_security_config.xml` 里默认 `cleartextTrafficPermitted="false"`：
  生产环境走 `wss://`。**本地联调 `ws://`（明文）需要自己改这个文件**，不要为了让明文连通就长期开着。

---

## 5. 界面

只有一屏 `ui/HubScreen.kt`：

1. 「连接」/「断开」按钮（按状态互斥）。
2. 状态卡片：已断开 / 连接中 / 已连接（代次 N）/ 重连等待（第 N 次，Xs 后）/ 认证失败 / 出错，配不同颜色。
3. 「最近一次 ping」卡片：`request_id`（等宽字体，**长按复制**）、RTT（毫秒）、服务端时间戳。
4. 「最近连接日志」列表（时间 + 级别 + 文本，最多 200 条，可清空）。
5. 可折叠「高级设置」：服务器 URL、设备 ID、设备令牌（密码框，带显示/隐藏切换）、上述数值参数、一个「保存」按钮。

---

## 6. 代码结构

```
app/src/main/java/com/androidmcphub/app/
├── MainActivity.kt              # 唯一的 Activity，只负责挂 Compose
├── HubApplication.kt            # 进程启动打一条日志
├── core/
│   ├── HubConfig.kt             # 配置 + 表单草稿 + toMap/fromMap（纯逻辑）
│   ├── HubPrefs.kt              # SharedPreferences 读写（令牌单独存）
│   ├── Backoff.kt               # 指数退避 + 抖动（纯函数，注入 Random）
│   ├── HubLog.kt                # 200 条环形日志
│   └── WsUrl.kt                 # ws/wss → http/https 规范化
├── protocol/
│   ├── PhoneLinkProtocol.kt     # 帧解析与编码（org.json）
│   ├── PingResponder.kt         # request_id 去重 LRU + pong 生成（纯逻辑）
│   └── PhoneLink.kt             # 连接状态机（纯逻辑，输出 LinkAction 让网络层执行）
├── net/PhoneLinkClient.kt       # OkHttp WebSocket 封装，把 LinkAction 落到真实 socket
├── service/PhoneLinkService.kt  # 前台服务 + 通知 + 网络回调 + 看门狗（LinkBus 做进程内状态总线）
└── ui/                          # HubViewModel / HubScreen / Theme
```

分层的要点：**状态机不碰 Android 框架**，它只返回 `LinkAction`（`Open` / `Close` / `Send(frame)` / `ScheduleReconnect(delay)`），
由 `PhoneLinkClient` 执行。因此所有协议规则和连接生命周期都能在 JVM 上单测，不需要模拟器。

---

## 7. 单元测试

```bash
ANDROID_HOME=/opt/android-sdk ./gradlew --no-daemon :app:testDebugUnitTest
```

覆盖内容：

- `BackoffTest`：退避第 0/1/2/3 次的递增、封顶、抖动区间（固定 seed）、边界（负数、jitter 越界、initial=0）。
- `HubConfigTest`：默认值、`toMap`/`fromMap` 往返、忽略令牌键、非法值回落、`validate()`、表单草稿解析。
- `HubLogTest`：环形容量、最新在上、监听器快照。
- `WsUrlTest`：scheme 映射、去 query/fragment、非法输入。
- `PhoneLinkProtocolTest`：welcome / phone_ping / heartbeat_ack / error / 未知 type / 非法 JSON / 缺 type / 缺 request_id；`encodePong` 的 `request_id` 逐字符相同与转义。
- `PingResponderTest`：去重、RTT 计算、LRU 淘汰顺序、容量上限、reset、时钟未同步时不出现负数。
- `PhoneLinkTest`：R1–R6 与整个生命周期（退避递增、401 不重连、用户断开后不再重连、网络恢复重置退避、握手超时、心跳超时、静默计时被任意服务端帧刷新）。
- `PhoneLinkClientTest`：用 **MockWebServer** 在 JVM 上跑**真实 WebSocket 链路**，验证请求头、hello、pong 的回包、401 认证失败、地址无效与令牌为空时的拒绝连接。

之所以能在 JVM 上跑 org.json 与 WebSocket：`org.json` 在 Android 运行时是自带的，但在 JVM 单测里是抛异常的 stub，
所以 `testImplementation` 里额外引了 `org.json:json:20240303`（真实现）与 `okhttp3:mockwebserver:4.12.0`。
这两条依赖**只进单元测试**，不进 APK。

---

## 8. 需要在真机上人工验证的清单

下面这些依赖真实系统行为、真实网络与真实用户交互，**自动化（JVM 单测）覆盖不到**，需要装到真机上手工过一遍。
本仓库不写 `androidTest`（没有模拟器/真机在 CI 里），所以这一节就是验收清单本身。

| # | 要验证的事 | 期望结果 | 为什么自动化覆盖不到 |
| --- | --- | --- | --- |
| 1 | 安装后首次点「连接」，通知栏出现前台服务通知 | 通知存在、文案为「正在连接…」→「已连接到 <host>」，有「断开」按钮 | 通知渲染与前台服务提升要真实系统 |
| 2 | Android 13+ 首次点「连接」弹权限询问 | 允许 → 通知可见；**拒绝 → 依然能连接成功**，不崩溃、不弹错误 | 运行时权限对话框是系统 UI |
| 3 | 点通知上的「断开」 | 连接断开、服务停止、通知消失（`STOP_FOREGROUND_REMOVE`） | `PendingIntent` → Service 的真实投递 |
| 4 | 点界面「断开」后再拔插网络 | **不会**自动重连，状态停在「已断开」 | 需要真实网络事件 |
| 5 | 拔网线/开飞行模式再恢复 | 状态进入「重连等待（第 N 次，Xs 后）」，网络恢复后立刻重连成功，退避计数归零 | `registerDefaultNetworkCallback` 只在真机生效 |
| 6 | 把服务端关掉 | 反复重连，间隔按 1s/2s/4s/…封顶 60s，带抖动 | 需要真实服务端配合 |
| 7 | 用错的令牌连接 | 状态显示「认证失败（HTTP 401/403）」，**不**自动重连 | 需要服务端返回真实 401 |
| 8 | 拔掉服务端网线（不关闭进程） | 约 2 个心跳周期后判定链路已死并自动重连 | 真实半开连接 |
| 9 | 长按「最近一次 ping」的 `request_id` | 复制成功（提示 + 剪贴板内容一致） | 系统剪贴板与长按手势 |
| 10 | 手机重启 | **不会**自动启动服务、不会自动连接 | 没有 `RECEIVE_BOOT_COMPLETED`，需要真机确认「确实没起来」 |
| 11 | 从最近任务里划掉 App | 服务停止、通知消失，不会出现幽灵连接（服务返回 `START_NOT_STICKY`） | 进程优先级与系统回收策略 |
| 12 | 深色模式切换 | 界面文字与状态色仍可读，启动不白屏 | 系统主题切换 |
| 13 | 屏幕旋转 | 状态、日志、输入框内容不丢（`ViewModel` 持有） | 系统配置变更 |
| 14 | 令牌是否泄露 | `adb logcat` 里搜不到令牌；`SharedPreferences` 文件里有令牌但不出现在任何日志/崩溃信息中 | 需要抓真实日志确认 |
| 15 | 会话看门狗 | 服务端静默 5 分钟以上 → 服务自动停止并显示「会话超时」 | 需要长时间运行 |
| 16 | 明文联调（可选） | 若用 `ws://` 直连本地服务端，需要先改 `network_security_config.xml`，确认改动只影响本地 | 网络安全配置只在真机生效 |

---

## 9. 已知限制

- 没有 `androidTest`/仪器测试（环境里没有模拟器）。第 8 节是真机验证的替代方案。
- 界面只做了单屏，没有历史图表、没有多设备管理（按任务要求，不做工具管理与跨设备控制）。
- 服务被系统杀掉的极端情况下，只有用户重新点「连接」才会恢复 —— 这是刻意的取舍，不做任何后台自启。
