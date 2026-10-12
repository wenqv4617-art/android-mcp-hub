# Gate 2.5A+B 交付报告：内置工具目录统一 + 网络/存储能力

> 状态：已完成（自动化验证全绿；真机验证未做，见 §H）。
> 所有结论都标了【事实】/【判断】/【建议】，与本次交付的其它文档同一口径。

---

## §A 起始 SHA

| 项 | 值 |
| --- | --- |
| 仓库 | `https://github.com/wenqv4617-art/android-mcp-hub` |
| 开发分支 | `feat/gate2-5-native-tools` |
| **起始 SHA** | `69a6848c7c59c7570968e055d9b78641785fb49b` |
| 基线分支 | `feat/ui-revamp`（Stage 2 交付 tip，与起始 SHA 同一提交） |
| 未跟踪但**刻意不提交**的文件 | `docs/Gate1.5-Stage0-Test-Baseline.md`（历史任务书要求保持未跟踪） |

【事实】开工前核对了当前分支与 `HEAD`，未假设它仍在 Stage 1 的 `daf4b2c`；开工时
工作树干净，`feat/gate2-5-native-tools` 从 `69a6848` 新建，未触碰 `gate0`。

## §B 最终 SHA

| 项 | 值 |
| --- | --- |
| **代码与文档 tip** | `bc6db0cf46479b7e13f6d178e4666611c66e5dde` |
| 本报告提交 | 见下方"交付提交序列" |

交付提交序列（`feat/gate2-5-native-tools`，基于 `69a6848`）：

| 提交 | 内容 |
| --- | --- |
| `585f234` | 能力元数据契约、网络与存储能力、统一注册清单（含 `.gitignore` 忽略 `android/.kotlin`） |
| `33711d3` | 工具目录由注册清单派生、详情页展示声明权限 |
| `0a21ed5` | Worker 注册两个新工具、自述改为从注册表派生、E2E 与模拟手机扩展 |
| `b2eaf33` | Android 与 Worker 的新测试 |
| `e83151d` | 协议契约、架构说明与本报告 |
| `bc6db0c` | README 里实测测试数字更新为 28 类 / 347 条 |

【事实】本轮**没有**合并到 `gate0`、**没有**新建 `main`、**没有**触发任何部署。

## §C 修改文件清单

28 个文件，`+2204 / −95`（含测试与文档）。

### Android · 能力层（新增 4 + 修改 2）

| 文件 | 变更 |
| --- | --- |
| `android/app/src/main/java/com/androidmcphub/app/capability/CapabilityMetadata.kt` | **新增**。`CapabilityCategory` / `CapabilityArgument` / `CapabilityMetadata` |
| `android/app/src/main/java/com/androidmcphub/app/capability/NetworkCapability.kt` | **新增**。`device_get_network`：`RawNetworkReading` / `NetworkSource` / `AndroidNetworkSource` / `NetworkCapability` |
| `android/app/src/main/java/com/androidmcphub/app/capability/StorageCapability.kt` | **新增**。`device_get_storage`：`RawStorageReading` / `StorageSource` / `AndroidStorageSource` / `StorageCapability` |
| `android/app/src/main/java/com/androidmcphub/app/capability/BuiltInCapabilities.kt` | **新增**。本机内置能力的**唯一注册清单**（`ALL` / `metadata` / `names` / `create` / `androidCapabilities`） |
| `android/app/src/main/java/com/androidmcphub/app/capability/CapabilityDispatcher.kt` | `Capability` 增加 `metadata`；`init` 增加 `metadata.name == name` 一致性断言；新增 `catalog()`。**七步校验顺序与逻辑一行未改** |
| `android/app/src/main/java/com/androidmcphub/app/capability/BatteryCapability.kt` | 实现 `metadata`（`METADATA`）；读取逻辑未改 |
| `android/app/src/main/java/com/androidmcphub/app/service/PhoneLinkService.kt` | 构造分发器时改用 `BuiltInCapabilities.androidCapabilities(this)`；**前台服务生命周期、连接逻辑、`policy` 实时读开关等行为未改** |

### Android · UI（修改 4）

| 文件 | 变更 |
| --- | --- |
| `android/app/src/main/java/com/androidmcphub/app/ui/model/ToolDescriptor.kt` | `ToolCategory` 增加 `Network` / `Storage`；`ToolDescriptor` 增加 `declaredPermissions` |
| `android/app/src/main/java/com/androidmcphub/app/ui/model/ToolCatalog.kt` | 由手写清单改为**从 `BuiltInCapabilities.metadata` 派生**，新增 `categoriesInUse()` 与 `toToolDescriptor()` |
| `android/app/src/main/java/com/androidmcphub/app/ui/tools/ToolsScreen.kt` | 分类筛选只画"有工具的分类" |
| `android/app/src/main/java/com/androidmcphub/app/ui/tools/ToolDetailScreen.kt` | 详情页显示"声明权限"（去掉 `android.permission.` 前缀）与一句"系统没给权限时读取会失败" |

### Android · 测试（新增 3 + 修改 4）

`capability/BuiltInCapabilitiesTest.kt`（新增）、`capability/NetworkCapabilityTest.kt`（新增）、
`capability/StorageCapabilityTest.kt`（新增）、`capability/CapabilityDispatcherTest.kt`（假能力补 `metadata`）、
`protocol/PhoneLinkTest.kt`（假工具补 `metadata`）、`ui/model/ToolCatalogTest.kt`（改为比对真实注册表）。

### Worker（修改 4 + 新增 1）

| 文件 | 变更 |
| --- | --- |
| `worker/src/mcp/tools.ts` | 新增 `DEVICE_GET_NETWORK_TOOL` / `DEVICE_GET_STORAGE_TOOL`；`TOOL_REGISTRY` 变成 4 条。**参数白名单、结果大小限制、超时、去重、通道机制均未改** |
| `worker/src/mcp/http.ts` | MCP `instructions` 文案改为**从注册表派生工具名**（原文写着"只提供一个工具 phone_ping"，Gate 2 时就已经不实） |
| `worker/src/index.ts` | `/healthz` 的 `capabilities.tools` 与根路径说明改为从注册表派生（原文写死 `["phone_ping"]`） |
| `worker/test/tools.test.ts` | 整表断言改成 4 个工具 + 新工具"零能力参数、只读幂等"用例 |
| `worker/test/tool-dispatch.test.ts` | 未知工具的 `known_tools` 断言扩成 4 个 |
| `worker/test/worker-index.test.ts` | 健康检查的工具清单断言扩成 4 个 |
| `worker/test/device-tools.test.ts` | **新增**。13 条用例，覆盖两个新工具的工具层行为与失败路径 |

### 测试夹具与文档

- `tools/mock-phone/client.ts`：`MOCK_CAPABILITIES` 补两个工具的**假**固定值（Wi-Fi/已验证；128 GB/48 GB/62.5%）。
- `e2e/run-local-e2e.ts`：工具列表断言改成 4 个；`toolCall` 支持指定工具名；新增两个新工具的全链路检查与"多余参数被拒"检查；会话复用检查的工具数从 2 改成 4。
- `docs/PROTOCOL.md`：§9 修正"能力总开关默认开"这处**与代码相反**的描述；新增 §10 两个新工具的字段契约。
- `docs/UI-Architecture.md`：§3「工具目录的数据来源」与 §4「新增一个工具时怎么进列表」按 Gate 2.5 的派生方式重写。

## §D 新增工具定义

两个工具都是 `transport = "tool"`、**零能力参数**（`allowedArgs: []`）、只读幂等。
`inputSchema` 只有信封参数 `device_id` / `timeout_ms`，且 `additionalProperties: false`。

### `device_get_network`

`{ "connected": boolean, "validated": boolean|null, "transport": string, "transports": string[], "metered": boolean|null, "internet_capable": boolean|null }`

- `transport` 取值：`wifi` / `cellular` / `ethernet` / `vpn` / `bluetooth` / `wifi_aware` / `lowpan` / `usb` / `thread` / `satellite` / `unknown` / `none`。
- 多传输并存时按 VPN > 以太网 > Wi-Fi > USB > 蜂窝 > 蓝牙 > Wi-Fi Aware > LoWPAN > Thread > 卫星 取"主要传输类型"，`transports` 给出完整列表。
- 系统没报告的字段回 `null`，**不猜成 `false`**。
- **不返回漫游状态**（可靠的 `isNetworkRoaming` 需要 `READ_PHONE_STATE`，靠网络类型反推就是编数据）。
- 不读 SSID / BSSID / MAC / IP / SIM 标识，不主动访问外部站点"测试"联网。

### `device_get_storage`

`{ "volume": "internal_data", "total_bytes": number, "available_bytes": number, "used_bytes": number, "usage_percent": number }`

- `used_bytes = total - available`，含义是**文件系统已占用**，**不是**"用户文件占用"。
- `usage_percent` 是一位小数的**数字**（如 `62.5`），不是字符串。
- 容量为 0 / 卷不可访问时明确失败（`tool_unavailable`），绝不返回一串 0。
- 不申请 `MANAGE_EXTERNAL_STORAGE` / `READ_MEDIA_*` / 任何传统整盘权限，不枚举用户文件。

## §E 权限变化

| 项 | 结论 |
| --- | --- |
| 新增 Android 权限 | **无**。【事实】`git diff` 里 `android/app/src/main/AndroidManifest.xml` 未修改；`ACCESS_NETWORK_STATE` 在 Gate 1 就为连接服务声明过 |
| 新增运行时权限请求 | **无**。【事实】`CapabilityMetadata.requiresRuntimePermission` 对三个能力都是 `false` |
| 新增的"声明权限"展示 | `device_get_network` 声明 `android.permission.ACCESS_NETWORK_STATE`（普通权限，安装即授予），只在工具详情页显示，不触发任何请求 |
| 令牌 / OAuth / 设备认证 | **未改动** |

## §F 测试命令与结果

全部在同一工作树、同一次提交前跑完。【事实】

| 命令 | 结果 |
| --- | --- |
| `cd android && ./gradlew --no-daemon -Djava.net.preferIPv4Stack=true :app:testDebugUnitTest :app:lintDebug :app:verifyManifestClasses :app:assembleDebug` | **BUILD SUCCESSFUL**；单测 **28 个测试类 / 347 条用例 / 0 failures / 0 errors / 0 skipped**（Stage 2 基线：25 类 / 301 条） |
| `:app:lintDebug` | **0 errors, 38 warnings**（与 Stage 2 同数；30 条来自 `android/gradle/libs.versions.toml` 的依赖版本提示，其余 8 条是既有代码/清单；**本轮新增文件零警告**） |
| `:app:verifyManifestClasses` | ✅ Manifest 里每个组件类都能在 dex 里找到 |
| `npm run typecheck` | **0 错误** |
| `npm run test`（vitest） | **15 个文件 / 345 条通过**（Stage 2 基线：14 文件 / 331 条） |
| `npm run e2e`（本地端到端） | **83 通过 / 0 失败**（Stage 2 基线：78 通过 / 0 失败） |
| `git diff --stat 69a6848 -- worker` | 有改动（本轮**故意**改了 Worker：工具注册表 + 自述文案）；**OAuth / 设备认证 / WebSocket 协议 / DO 会话逻辑未改** |
| `git diff --stat 69a6848 -- .github` | **空输出**，部署配置未改 |

### §十 25 项测试要求的落点

| # | 要求 | 落在哪 | 结果 |
| --- | --- | --- | --- |
| 1 | 网络：Wi-Fi | `NetworkCapabilityTest`「Wi-Fi 已验证、非计费」 | ✅ |
| 2 | 网络：蜂窝 | `NetworkCapabilityTest`「蜂窝未验证、计费」 | ✅ |
| 3 | 网络：无活动网络 | `NetworkCapabilityTest`「没有活动网络」 | ✅ |
| 4 | 网络：系统未验证 | 同上（`validated = false` 与 `null` 分开断言） | ✅ |
| 5 | 网络：VPN | `NetworkCapabilityTest`「VPN 与 Wi-Fi 并存」 | ✅ |
| 6 | 网络：权限不足 / API 异常 | `NetworkCapabilityTest`「连接服务不可用」+「数据源抛异常」 | ✅ |
| 7 | 存储：容量正常 | `StorageCapabilityTest` 五字段 + `used = total − available` | ✅ |
| 8 | 存储：容量为零 | `StorageCapabilityTest`「容量为 0 → tool_unavailable」 | ✅ |
| 9 | 存储：API 异常 | `StorageCapabilityTest`「数据源抛异常」 | ✅ |
| 10 | 总开关关闭 → 拒绝 | 两个能力的测试各有一条：`not_permitted` 且数据源 `reads == 0` | ✅ |
| 11 | 未注册工具 → 拒绝 | `CapabilityDispatcherTest`（既有）+ `BuiltInCapabilitiesTest` 元数据一致性 | ✅ |
| 12 | 重复 `request_id` 不重复执行 | `CapabilityDispatcherTest`（既有，去重步骤未改） | ✅ |
| 13 | 结果不超过协议限制 | `CapabilityDispatcherTest`（既有 `maxResultBytes` 用例） | ✅ |
| 14 | UI 目录与执行白名单一致 | `ToolCatalogTest`「目录与真实注册表逐字一致」+ `BuiltInCapabilitiesTest.catalog()` | ✅ |
| 15 | Worker：`tools/list` 含新工具 | `tools.test.ts` 整表断言 + `device-tools.test.ts`「注册在白名单里」 | ✅ |
| 16 | Worker：新工具成功转发 | `device-tools.test.ts` 两个成功用例 | ✅ |
| 17 | Worker：新工具失败正确返回 | `device-tools.test.ts`「手机离线」「容量读不到」 | ✅ |
| 18 | Worker：非法参数被拒 | `device-tools.test.ts` 两条（`ssid` / `path`），并断言**没有**去问手机 | ✅ |
| 19 | Worker：原有工具保持兼容 | `tools.test.ts` / `tool-dispatch.test.ts` / `mcp-http.test.ts` 全绿 | ✅ |
| 20 | OAuth 与设备认证不回归 | `oauth.test.ts` / `security-boundary.test.ts` / `phone-lifecycle.test.ts` 全绿 | ✅ |
| 21 | 端到端：查真实网络 | `e2e` 「device_get_network 走完整链路」— **mock phone 的固定值** | ⚠️ 本地端到端通过；**真机未做** |
| 22 | 端到端：查真实存储 | `e2e` 「device_get_storage 走完整链路」— 同样 | ⚠️ 本地端到端通过；**真机未做** |
| 23 | 端到端：总开关关闭后两项被拒 | Android JVM 两条（见 #10） | ✅ JVM 层；真机未做 |
| 24 | 端到端：手机离线 → 明确错误 | `e2e` 「手机没连上时工具如实失败」（phone_offline）+ `device-tools.test.ts` | ✅ |
| 25 | 不破坏 phone_ping 与 battery_get_status | `e2e` 对照组 + 既有全绿 | ✅ |

**必须区分的测试层级（不要混着说）**：

- **单元测试**：Android 28 类 / 347 条（真跑过）；Worker vitest 15 文件 / 345 条（真跑过）。
- **模拟联调 / 本地端到端**：`npm run e2e`，83 项，走真实的 Worker 代码 + 真实的 WebSocket + **模拟手机**（`tools/mock-phone`），不走真实 Android 设备、不接 ChatGPT。
- **Android 真机联调**：**未做**（环境无模拟器、无 adb 设备）。
- **ChatGPT 实际调用**：**未做**，且**必须等新 Worker 版本部署之后**才可能做 —— 在那之前不能声称"ChatGPT 已经能查到网络/存储"。

## §G APK 构建状态与下载位置

| 项 | 值 |
| --- | --- |
| 路径（容器内） | `android/app/build/outputs/apk/debug/app-debug.apk` |
| 大小 | **13,603,134 字节**（Stage 2 APK：13,554,409，+48,725） |
| SHA-256 | `a35349696151f8a10e548a2dd8523423f0a24043b085ac1b20e0fe135ca9fb5c` |
| 构建命令 | `./gradlew --no-daemon -Djava.net.preferIPv4Stack=true :app:assembleDebug` |
| 下载 | 见本文件所在提交说明与交付消息（容器内 `http://127.0.0.1:8933/` 白名单服务） |

【事实】APK 里包含两个新能力与元数据契约；`used_bytes` / `usage_percent` 的数字是**运行时真读**，
构建产物里没有任何硬编码读数（假数字只存在于 `tools/mock-phone` 与单测夹具里）。

【判断】体积增加约 47.6 KB，来源是新增的 4 个 capability 类与 1 个注册清单类（含它们的
字符串常量）。与 Stage 1→2 那次 +516 KB（Navigation Compose 与图标）不同，这次没有任何新依赖。

## §H 尚未完成的真机验证

**没有模拟器也没有真机连接，所以本节全部是"待做"，不是"已通过"。** 【事实】

装上新 APK、连上部署好的 Worker 之后，按顺序做：

1. 设置页打开「允许服务端调用本机只读能力」（**首次安装默认是关的**）。
2. 工具页应看到 **3 个本机能力**：电池状态、网络状态、存储空间；分类芯片只有「设备 / 网络 / 存储」。不再出现「文件 / 媒体 / 通知」空分类。
3. 点开「网络状态」详情：应显示分类「网络」、只读、**声明权限 `ACCESS_NETWORK_STATE`**、参数为空。
4. 让 ChatGPT（或 MCP 客户端）调用 `device_get_network`：把返回的 `transport` / `validated` / `metered` 与系统「设置 → 网络」页对照；**关掉 Wi-Fi 只留蜂窝再调一次**，`transport` 应从 `wifi` 变成 `cellular`。
5. 调用 `device_get_storage`：`total_bytes` 换算成 GB 后应与系统「设置 → 存储」页的总容量一致；`available_bytes` 允许因系统缓存波动有几 GB 差距；核对 `used_bytes = total − available`、`usage_percent` 与页面上显示的百分比量级一致。
6. **关掉能力总开关**（不重连），再调用这两个工具：都应返回 `not_permitted`，且**不应**有任何读数被读取。
7. 跨 VPN 验证（可选）：开 VPN 后 `transport` 应为 `vpn`，`transports` 里同时含 `vpn` 与底层类型（如 `wifi`）。
8. 回归：`phone_ping` 与 `battery_get_status` 仍正常；断网/飞行模式下 `device_get_network` 应返回 `connected: false` + `transport: "none"`，而不是报错或返回旧值。
9. 弱网/异常路径：手机离线时调用两个工具都应 `isError=true` + `phone_offline`，不挂死。

## §I 发现的风险与如实记录的问题

| # | 级别 | 内容 |
| --- | --- | --- |
| R1 | 中 | **`tools/gate1-smoke.mjs:74` 的 B1.6 断言**（"只暴露 phone_ping 这一个工具"）在 Gate 2.5 之后必然失败。它是 Gate 1 的开发向 smoke 脚本（`npm run smoke`），**不在 `npm run gate0` 链里**，也不在 CI 里。本轮没有改它（避免扩大范围），需要在下次维护 smoke 时同步。 |
| R2 | 中 | **Worker 与 Android 是两处工具登记**（`worker/src/mcp/tools.ts::TOOL_REGISTRY` 与 `capability/BuiltInCapabilities.kt::ALL`）。这是跨运行时的固有重复，靠契约测试对齐（整表断言 + `outputSchema` 字段覆盖断言 + E2E 全链路）。**不允许**为了"消重"把注册表搬到运行时共享。 |
| R3 | 低 | **漫游状态刻意不返回**。可靠的 `isNetworkRoaming` 需要 `READ_PHONE_STATE`；用"蜂窝 + 某运营商"反推漫游就是编数据。将来若要这个字段，应单独评估权限，而不是在现有工具里猜。 |
| R4 | 低 | `volume` 目前只有 `internal_data` 一个取值（经 `StatFs(filesDir)` 统计内部卷），**不代表外置 SD 卡**。将来若加外置卷，应新增字段或新增工具，而不是让 `volume` 出现第二种含义。 |
| R5 | 低 | `device_get_storage` 的 `used_bytes` 是**文件系统已占用**，与系统设置里"用户数据"口径不同（差在系统占用与保留块）。工具描述与详情页都已写明，但仍可能被误读成"用户文件占用"。 |
| R6 | 低 | 本轮**没有**跨设备动态工具同步：Worker 注册表是静态的。手机上装旧版本（不认识新工具）时会回 `unsupported_tool` —— 这是设计上的正确行为，但用户会看到一条失败，需要在文档里说清楚。 |
| R7 | 低 | **ChatGPT 侧的真实可见性依赖部署**。注册表改了但 Worker 没部署时，`tools/list` 里看不到新工具；在用户批准部署之前不得声称端到端已通过。 |
| R8 | 低 | 环境限制：无模拟器 / 无真机，Compose UI 与真机读数全部未验证（§H）。 |

**本轮修掉的两处不实描述（历史遗留，如实记录）**：

- `worker/src/mcp/http.ts` 的 MCP `instructions` 从 Gate 1 起就写着"当前只提供一个工具 phone_ping"，Gate 2 加了 `battery_get_status` 之后已经**不实**。现改为从注册表派生工具名。
- `/healthz` 的 `capabilities.tools` 与根路径说明写死 `["phone_ping"]`，同样不实。现改为 `toolNames()` 派生。
- `docs/PROTOCOL.md` §9 写着"开关默认**开**"，与 `HubPrefs.DEFAULT_CAPABILITIES_ENABLED = false`（由 `CapabilitySwitchDefaultTest` 钉住）**正好相反**。已改成正确的安全默认描述。

## §J 下一阶段（媒体能力）接入建议

【建议】媒体能力（播放/暂停/上一首/下一首/曲目信息）比这两个查询工具危险得多，建议按下面的顺序做，
**不要**直接照抄本轮的"加一条 `ToolDefinition`"：

1. **先定"有副作用的工具"这条新通道**。当前通道里所有工具都是 `readOnlyHint: true`、
   `idempotentHint: true`，七步校验也默认"执行无副作用"。媒体控制会**改变设备状态**，
   必须引入与只读工具不同的路径（至少要有 `readOnlyHint: false` + 明确的用户授权语义）。
2. **媒体控制需要 `MediaSession` 或通知访问权限**。这两者都不是普通权限：
   `NotificationListenerService` 要在系统设置里手动授权，`MediaSessionManager` 需要通知访问。
   **不要**为了"能跑起来"改成本应用自己持有一个播放器 —— 那不是"控制用户的音乐"，是另一个 App。
3. **先做读取、后做控制**。读取"当前是否有媒体会话、播放状态、曲目名/艺术家"（`media_get_status`）
   与 `battery_get_status` 同级；控制类（play/pause/next）必须等协议级授权状态机落地之后再开。
4. **协议侧要补的东西**（本阶段刻意没做）：任务持久化与查询接口、重复确认不得重复执行、
   授权绑定具体参数、断线/换代次使旧请求失效、授权超时默认拒绝。
5. **UI 侧的准备度**：`ui/tools/ToolDetailScreen.kt` 已经为"有副作用的工具"留了一句说明，
   但**没有**"允许/拒绝"按钮 —— 加按钮之前必须先把状态机落地，否则就是一个点了没用的按钮。
6. **权限最小化**：媒体接入不要顺手申请通讯录、短信、定位、`MANAGE_EXTERNAL_STORAGE`。
   每次新增权限都应在报告里单独列出理由。

---

## 附：本轮"没有做"的事（范围控制）

- 第三方 MCP 客户端接入 / 第三方工具聚合 / 本地 HTTP MCP 中转：**未做**。
- 协议级授权状态机、人工授权弹窗：**未做**（§十一 范围外）。
- 长任务、后台任务、任务持久化：**未做**。
- 生产 Worker 部署：**未做**（等用户批准）。
- UI 整体重构：**未做**（只改了工具页分类筛选与详情页一行）。
