# Android 本机工具：清单、权限与验收

本页说明手机侧 36 个 MCP 工具的**能力边界**、**授权方式**、**系统权限**、
**返回的错误码**，以及需要真机操作的人工验收步骤。

> Worker 只声明工具和参数；**执行全部发生在手机上**。
> 手机又会在每次请求上重新检查：工具是否注册、参数是否合法、用户是否授权、
> 系统权限是否具备、高影响操作是否带确认。任何一层不通过都不会执行。

---

## 1. 三层授权 + 一次确认

Android App「工具与权限」页有三层开关，任何一层关闭都不会执行：

| 层 | 作用 | 默认 |
| --- | --- | --- |
| 只读总开关 | 关闭后**所有**工具都返回 `not_permitted` | 关（首次由用户打开） |
| 写入总开关 | 只影响写操作；只读工具不受它影响 | **关** |
| 逐工具开关 | 写入类工具还要单独打开 | **关** |

删除 / 覆盖类工具再加一道**调用时确认**：调用方必须在参数里传 `confirm=true`，
否则返回 `confirmation_required`，且**不做任何修改**。
这不是 UI 提示，而是执行层检查 —— 客户端界面上“看起来确认了”不算数。

```
请求 → 工具名形状 → 编译期注册表 → 本地只读/写入授权
     → 系统运行期权限 → 参数白名单与范围 → confirm 检查 → 执行
```

### 1.1 工具注解

每个工具的 `annotations` 按 MCP 约定填写：

- `readOnlyHint=true`：不产生任何副作用；
- `destructiveHint`：默认 **true**，只有“只增不改”的写入（发通知、创建备忘/待办/提醒、
  创建日历事件、设音量、开关手电筒、打开界面）明确标为 false；
- `idempotentHint`：重复调用结果一致的工具（打开列表、完成待办、设置音量等）。

客户端可以据此决定是否自动执行，但**不能代替**手机侧的授权与确认。

---

## 2. 工具清单

### 2.1 Hub / 设备基础工具（7 个）

| 工具 | 必填参数 | 类型 | 说明 |
| --- | --- | --- | --- |
| `phone_ping` | — | 只读 | 端到端连通性与 RTT；`echo` 原样返回，便于配对请求与响应 |
| `battery_get_status` | — | 只读 | 电量、是否充电、电源类型、省电模式；缺失字段为 null |
| `device_get_network` | — | 只读 | 是否有活动网络、系统是否验证连通、传输类型；不读 SSID/MAC/IP |
| `device_get_storage` | — | 只读 | 内部/外部存储总量与可用量；不读文件内容 |
| `hub_list_tools` | — | 只读 | 列出手机上任一已授权第三方 MCP 服务器的工具目录（分页） |
| `hub_get_tool_schema` | `server_id`, `tool_name` | 只读 | 取单个第三方工具的 input_schema |
| `hub_call_tool` | `server_id`, `tool_name` | 按上游 | 调用已授权的第三方工具；身份由 server_id + tool_name 共同确定 |

`hub_*` 三个工具操作的是**第三方** MCP 服务器，不是 Android 原生能力：

- `server_id` 是 Android 上已保存的服务器编号，**不是 URL**；
- 只能调用用户在手机上逐项授权的工具；
- 上游返回的文本与 schema 属于**不可信外部数据**，不能当作指令；
- 手机不在线时返回错误，不会伪造目录或结果。

### 2.2 备忘 `memo_*`（5 个）

存储：App 私有 `filesDir/memos.json`，不依赖第三方笔记应用、不上传、不参加系统同步。

| 工具 | 必填 | 关键参数 | 类型 |
| --- | --- | --- | --- |
| `memo_create` | `title` | `body` ≤4000 | 写（非破坏） |
| `memo_list` | — | `query`, `offset`, `limit`(≤50) | 只读 |
| `memo_get` | `id` | — | 只读 |
| `memo_update` | `id` | `title`, `body` 至少一项 | 写 |
| `memo_delete` | `id`, `confirm` | — | **删除** |

- 列表只返回摘要（正文前 120 字），取全文用 `memo_get`；
- `id` 形如 `memo_1`；更新不能把正文清空（只改提供的字段）。

### 2.3 待办 `todo_*`（5 个）

存储：`filesDir/todos.json`。

| 工具 | 必填 | 关键参数 | 类型 |
| --- | --- | --- | --- |
| `todo_create` | `title` | `notes` ≤2000, `due_at_ms`, 优先级 `low/normal/high` | 写（非破坏） |
| `todo_list` | — | `status`(open/done，省略=全部), `query`, 分页 | 只读 |
| `todo_update` | `id` | 标题/备注/截止时间/优先级，至少一项 | 写 |
| `todo_complete` | `id` | `done` 默认 true；`false` 重新打开 | 写（幂等） |
| `todo_delete` | `id`, `confirm` | — | **删除** |

`todo_list` 中未完成的排在前、同组按截止时间排序，并给出 `overdue`。

### 2.4 提醒 `reminder_*`（5 个）

存储：`filesDir/reminders.json`；到点由 **AlarmManager + 本应用通知**提醒。

| 工具 | 必填 | 关键参数 | 类型 |
| --- | --- | --- | --- |
| `reminder_create` | `title`, `at_ms` | `note` | 写（非破坏） |
| `reminder_list` | — | `status`(scheduled/fired/done), 分页 | 只读 |
| `reminder_update` | `id` | 标题/备注/时间，至少一项；时间必须在未来 | 写（幂等） |
| `reminder_complete` | `id` | `done` 默认 true；`false` 重新打开 | 写（幂等） |
| `reminder_delete` | `id`, `confirm` | — | **删除** |

诚实的限制：

- 使用系统**非精确**闹钟（`setAndAllowWhileIdle`），省电模式下可能晚几分钟；
- 设备重启后需要**打开一次 App** 才会重新排程；
- 没有通知权限时仍会排程，但到点不会显示通知 —— 结果里的
  `notification_permission_granted=false` 会明确说明；
- 这是 Hub 自己的提醒，不会写入系统日历或第三方提醒应用。

### 2.5 日历 `calendar_*`（5 个）

数据来源：Android Calendar Provider（系统 / 已登录账户的日历）。

| 工具 | 必填 | 关键参数 | 类型 |
| --- | --- | --- | --- |
| `calendar_list_calendars` | — | — | 只读 |
| `calendar_list_events` | `start_ms`, `end_ms` | `calendar_id`, `query`, `limit`(≤50) | 只读 |
| `calendar_create_event` | `title`, `start_ms`, `end_ms` | `calendar_id`, `description`, `location`, `all_day`, `timezone`, `rrule`, `reminder_minutes` | 写（非破坏） |
| `calendar_update_event` | `event_id` | 任意要改的字段 | 写 |
| `calendar_delete_event` | `event_id`, `confirm` | — | **删除** |

重要的边界：

- **更新与删除只对 Hub 创建的事件开放**。`calendar_create_event` 成功后会记下
  `event_id` 到 `filesDir/calendar_owned.json`；对系统/其它应用创建的事件，
  更新或删除返回 `not_permitted`，不会去改别人的数据。
- **外部删除与 ID 复用**：事件可能在系统日历里被用户删掉。更新/删除前会先向
  Calendar Provider 确认目标仍存在；不存在时清掉本地归属记录并返回 `tool_failed`，
  不再发起系统写入。这样即使 Provider 把同一个行 ID 分配给新事件，也不会误伤。
  查询本身因权限不足而无法判断时按「仍存在」处理，交给系统写入返回真实错误。
- **卸载重装**：`calendar_owned.json` 随应用数据一起清除，重装后旧事件不再被视为
  Hub 创建，更新/删除会返回 `not_permitted`（失败关闭，不会扩大权限）。
- **并发修改**：Calendar Provider 没有条件更新/ETag，两方同时改同一事件时以最后一次
  写入为准；本工具不做自动重试，避免把用户的并发修改覆盖成旧值。
- 查询区间必须 `end_ms > start_ms`，跨度不超过一年；重复事件按发生实例展开；
- 读取需要 `READ_CALENDAR`，写入需要 `WRITE_CALENDAR`（运行时权限）；
- 不返回账户邮箱等身份信息；系统没给的字段是 null，不编造；
- `rrule` 必须以 `FREQ=` 开头；`reminder_minutes` 数组为空表示移除提醒。

### 2.6 闹钟 `alarm_*`（2 个）

| 工具 | 必填 | 关键参数 | 类型 |
| --- | --- | --- | --- |
| `alarm_create` | `hour`, `minute` | `label`, `repeat_days`(1=周一…7=周日), `skip_ui` | 写（非破坏） |
| `alarm_open_list` | — | — | 打开界面 |

- 通过系统闹钟应用的 `ACTION_SET_ALARM` 创建，**Hub 自己不响铃**；
- 默认会打开系统确认界面，`skip_ui=true` 只是“尝试跳过”，部分 ROM 会忽略；
- 成功只代表“已向系统发出创建请求”，用户可能在确认界面取消 —— 结果文案也会这样说明；
- 标签是否生效取决于具体闹钟应用。

### 2.7 设备状态与控制（7 个）

| 工具 | 必填 | 关键参数 | 类型 | 权限 |
| --- | --- | --- | --- | --- |
| `device_get_status` | — | — | 只读 | 无 |
| `device_get_usage_stats` | — | `range`(today/yesterday/7d), `limit`(≤50), `include_system_apps` | 只读 | 使用情况访问（AppOps） |
| `device_set_volume` | `volume` | 0–1000 档位 | 写（幂等） | 无（媒体流） |
| `device_set_flashlight` | `on` | — | 写（幂等） | `CAMERA`（运行时） |
| `device_open_app` | `package_name` | 已安装应用包名 | 打开界面 | 无 |
| `device_open_settings` | `page` | `app_notifications` / `app_details` / `wifi` / `bluetooth` / `sound` / `display` / `battery` | 打开界面 | 无 |
| `device_post_notification` | `title`, `body` | ≤100 / ≤500 | 写（非破坏） | `POST_NOTIFICATIONS`（Android 13+） |

细节：

- `device_get_status`：屏幕是否亮/解锁、充电、媒体音量、手电筒、省电模式；
  不推断“已进入深度睡眠”这类系统没给的信息。
- `device_get_usage_stats`：前台时长由系统 UsageStatsManager 聚合，不同 ROM 可能偏小；
  没有授权时返回 `permission_denied`，**不会**把“没授权”说成“今天没用过应用”。
- `device_set_volume`：结果用 `requested_volume` / `media_volume` / `clamped`
  如实说明是否被系统边界夹取。
- `device_set_flashlight`：没有闪光灯或被系统拒绝时返回 `tool_unavailable` /
  `tool_failed`，不会假装已打开。
- `device_open_app` / `device_open_settings`：只接受白名单包名 / 页面，不接受任意
  Intent action 或 URL；`startActivity` 成功只说明“已发出请求”。
- `device_post_notification`：只影响本应用的通知渠道，不读、不改其它应用的通知。

---

## 3. 系统权限一览

| 权限 | 类型 | 用到的工具 | 怎么授予 |
| --- | --- | --- | --- |
| `READ_CALENDAR` | 运行时 | `calendar_list_*`、`calendar_update/delete` 读取校验 | 系统弹窗；拒绝后可在「应用详情 → 权限」重开 |
| `WRITE_CALENDAR` | 运行时 | `calendar_create/update/delete_event` | 同上 |
| `PACKAGE_USAGE_STATS` | 特殊（AppOps） | `device_get_usage_stats` | 设置 → 应用 → 特殊应用权限 → 使用情况访问 → 本应用 |
| `CAMERA` | 运行时 | `device_set_flashlight` | 系统弹窗；或应用详情 → 权限 → 相机 |
| `POST_NOTIFICATIONS` | 运行时（Android 13+） | `device_post_notification`、提醒到点通知 | 系统弹窗；或应用详情 → 通知 |
| `SET_ALARM` | 普通（安装时） | `alarm_create` | 无需手动授予 |
| 无 | — | 备忘、待办、音量、打开应用/设置、设备状态、网络、存储、`hub_*` | — |

**系统权限缺失**（`permission_denied`）与**用户在 App 里关掉工具**（`not_permitted`）
是两件事，修复路径不同：前者去系统设置，后者回 App「工具与权限」。

---

## 4. 错误码

所有工具返回统一信封（`ok`、`request_id`、`device_id`、`tool_name`、`rtt_ms`、
`server_timestamp`、`connection_generation`），失败时还有 `error_code` / `error_message`。

| 错误码 | 含义 | 下一步 |
| --- | --- | --- |
| `unsupported_tool` | 工具名不在本机注册表（或形状非法） | 升级 App / 检查工具名；不会回显原始工具名 |
| `invalid_arguments` | 缺字段、类型错、多出字段、取值越界 | 按 `inputSchema` 修正；服务端也会先挡一层 |
| `not_permitted` | 用户在 App 里关掉了只读/写入总开关或该工具 | App →「工具与权限」打开 |
| `permission_denied` | Android 系统权限未授予 | 按上一节的路径授予后重试 |
| `confirmation_required` | 删除/覆盖类工具没带 `confirm=true` | 先向用户确认，再带 `confirm=true` 重试 |
| `tool_unavailable` | 工具存在但设备/系统不支持（无闪光灯、系统版本不够） | 如实告知用户；不要假装成功 |
| `tool_failed` | 执行期异常（系统服务拿不到、API 抛错） | 可重试一次；持续失败则上报设备信息时去掉标识 |

Worker 侧还会在请求到达手机之前返回：`unknown_tool`、`invalid_arguments`、
`device_offline`、`device_revoked`、`ambiguous_device`、`phone_timeout`、
`concurrency_limit` 等。完整列表见 [GATEWAY_V1.md](GATEWAY_V1.md) 与
[docs/CLIENT_SETUP.md](CLIENT_SETUP.md) 第 4 节。

---

## 5. 数据位置与隐私

| 数据 | 位置 | 是否离开设备 |
| --- | --- | --- |
| 备忘 | `filesDir/memos.json` | 只在被调用时随结果返回 |
| 待办 | `filesDir/todos.json` | 同上 |
| 提醒 | `filesDir/reminders.json` | 同上 |
| Hub 创建的日历事件 ID | `filesDir/calendar_owned.json` | 否 |
| 日历 / 使用统计 / 电量 / 网络 / 存储 | 系统 Provider / 系统服务 | 只在被调用时随结果返回 |
| 配对令牌、设备令牌 | Android Keystore 加密存储 | 只用于鉴权，不下发 |

Worker **不缓存**工具结果；手机离线时不伪造成功。日志里不记录令牌、
不记录完整参数（敏感字段按白名单收敛），服务端只保留固定错误类别。

---

## 6. 真机人工验收步骤

以下步骤**需要一台真实 Android 设备**，本轮没有执行。建议按顺序做，
每步都记录“通过 / 失败 + 现象”。

### 6.1 安装与基础连通

1. 安装 `android/app/build/outputs/apk/debug/app-debug.apk`；
2. 首次启动授予通知权限（Android 13+ 会弹窗）；
3. 在设置里填网关地址、设备 ID、设备令牌并保存；
4. 确认前台服务已启动（通知栏有常驻通知）；
5. 从任意客户端调用 `phone_ping`，确认 `ok=true` 且 `rtt_ms` 合理；
6. 断网 / 杀掉 App，再次调用，确认返回 `device_offline` 而不是伪造结果。

### 6.2 授权梯度

1. 关闭只读总开关，调用 `battery_get_status` → 期望 `not_permitted`；
2. 打开只读、关闭写入总开关，调用 `memo_create` → 期望 `not_permitted`；
3. 打开写入总开关、单独关闭 `memo_create` → 仍期望 `not_permitted`；
4. 打开该工具后重试 → 成功。

### 6.3 备忘 / 待办 / 提醒

1. `memo_create` → `memo_list`（确认只返回摘要）→ `memo_get` → `memo_update`
   → 不带 `confirm` 调 `memo_delete`（期望 `confirmation_required`，数据仍在）
   → 带 `confirm=true` 删除（数据消失）；
2. `todo_create` → `todo_list`（open 在前、`overdue` 正确）→ `todo_complete`
   → `todo_complete(done=false)` 重新打开 → `todo_delete`；
3. `reminder_create` 设到 2 分钟后 → 锁屏等待，确认通知出现；
   到设置里关闭本应用通知权限后再建一个 → 结果里
   `notification_permission_granted=false`，并说明不会弹通知；
   重启手机后不打开 App → 确认提醒不会自行恢复（已知限制）。

### 6.4 日历

1. 未授予日历权限时调 `calendar_list_calendars` → `permission_denied`，
   文案指向系统设置；
2. 授予读权限 → `calendar_list_calendars` 返回日历列表（不含账户邮箱）；
3. 授予写权限 → `calendar_create_event`（明天 10:00–11:00）→ 在系统日历里确认事件存在；
4. 对**系统自带**事件调 `calendar_update_event` / `calendar_delete_event`
   → 期望 `not_permitted`，系统事件不变；
5. 对第 3 步创建的事件更新标题、再删除（带 `confirm=true`）→ 系统日历同步变化。

### 6.5 闹钟与设备控制

1. `alarm_create(hour, minute)` → 系统闹钟应用打开确认界面 → 确认后闹钟存在；
   `skip_ui=true` 在部分 ROM 上仍会弹界面，属预期；
2. `alarm_open_list` → 打开系统闹钟列表；
3. `device_set_volume(0)` → 媒体静音；`device_set_volume(9999)` → 返回
   `clamped=true` 且实际为最大值；
4. `device_set_flashlight(on=true)` → 手电筒亮；`false` → 灭；
   无闪光灯设备 → `tool_unavailable`；
5. `device_open_app("com.android.settings")` → 打开系统设置；
   不存在的包名 → `tool_unavailable`；
6. `device_open_settings("app_notifications")` → 打开本应用通知设置；
7. `device_post_notification` → 通知栏出现本应用通知。

### 6.6 使用统计

1. 未授予「使用情况访问」→ `device_get_usage_stats` 返回 `permission_denied`；
2. 系统设置 → 特殊应用权限 → 使用情况访问 → 授予本应用；
3. 正常使用几个应用后调用，确认 `range=today` 有数据且不含系统应用
   （除非 `include_system_apps=true`）；
4. 对比系统「数字健康」的数值，允许 ROM 聚合差异，但不应出现“全是 0”的伪造结果。

### 6.7 第三方工具（`hub_*`）

1. 在 App 里添加一个 Streamable HTTP 上游并完成工具授权；
2. `hub_list_tools` → 只出现已授权工具，默认不带 schema；
3. `hub_get_tool_schema` → 返回该工具的 input_schema；
4. `hub_call_tool` 调用一个只读工具 → 结果与上游一致；
5. 调一个未授权工具 → 结构化错误，不执行；
6. 断开上游网络 → `hub_call_tool` 返回上游不可用，不重放、不缓存。

### 6.8 与网关联动

1. 在「云端网关 → 访问令牌」签发只读令牌，用客户端调用写工具 → 看不到/调不了；
2. 签发读写令牌，调用 `memo_delete` 不带 `confirm` → `confirmation_required`；
3. 撤销令牌 → 该客户端下一次调用立即返回 401；
4. 解除配对 → 手机侧客户端令牌失效，其它客户端令牌不受影响。

---

## 7. 新增工具时要遵守的约定

1. 在 `android/.../capability/` 实现 `Capability`，并在
   `BuiltInCapabilities.ALL` 注册（**唯一**注册点）；
2. 在 `worker/src/mcp/native-tools.ts` 用 `deviceTool(...)` 声明同名工具，
   参数白名单由 `properties` 自动推导；
3. 删除/覆盖类必须把 `confirm` 写进 schema、标 `destructive: true`，
   并在 `confirmationProblem()` 里检查；
4. 参数上下限与 Android 侧 `CapabilityArgs` 一致或更严；
5. 补 Worker 侧清单测试与 Android 侧能力测试；系统权限缺失时返回
   `permission_denied`，不要返回空数据冒充成功。
