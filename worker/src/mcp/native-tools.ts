// ============================================================================
//  Phase 3：Android 本机能力（由手机侧 Capability 执行）
// ============================================================================
//
// 这里只声明"有哪些工具、参数长什么样、结果里有什么"。真正的执行全部发生在
// 手机上：Worker 把 `tool_request` 发过去，手机侧的能力注册表决定能不能执行、
// 有没有授权、需不需要确认。Worker 里**没有**任何调用 Android API 的代码。
//
// 三条与安全有关的约定，新增工具时也必须遵守：
//   1. 每个工具的参数白名单 = `properties` 的键（由 [deviceTool] 自动推导），
//      服务端会拒绝白名单之外的任何字段；
//   2. 字符串/数字的上下限与 Android 侧 `CapabilityArgs` 一致或更严，
//      不让超长输入白白穿过整条链路才被手机拒绝；
//   3. 删除类工具必须把 `confirm` 写进 schema 并标 `destructive`，
//      手机侧在缺少 `confirm=true` 时返回 `confirmation_required`。
// ============================================================================

import { deviceTool, type ToolDefinition } from "./tool-schema";

// ---------------------------------------------------------------------------
//  闹钟（交给系统闹钟应用，不自己响铃）
// ---------------------------------------------------------------------------

const ALARM_CREATE = deviceTool({
  name: "alarm_create",
  title: "Create an alarm (via the system clock app)",
  description:
    "请手机上的系统闹钟应用创建一个闹钟：小时、分钟、可选标签、每周重复的星期。" +
    "本工具**不会**自己响铃，而是把请求交给系统闹钟应用；默认仍会打开它的确认界面，" +
    "skip_ui=true 时才尝试跳过（部分系统会忽略）。成功只代表\"已发出创建请求\"，" +
    "不代表闹钟一定已保存 —— 用户可能在确认界面上取消。标签是否生效取决于具体的闹钟应用。",
  properties: {
    hour: { type: "integer", minimum: 0, maximum: 23, description: "小时，0–23（设备本地时间）。" },
    minute: { type: "integer", minimum: 0, maximum: 59, description: "分钟，0–59。" },
    label: { type: "string", maxLength: 100, description: "可选标签，最多 100 字。" },
    repeat_days: {
      type: "array",
      items: { type: "integer", minimum: 1, maximum: 7 },
      maxItems: 7,
      description: "每周重复的星期，1=周一 … 7=周日；不传表示只响一次。",
    },
    skip_ui: { type: "boolean", description: "true = 尝试跳过系统闹钟确认界面，默认 false。" },
  },
  required: ["hour", "minute"],
  argRules: {
    hour: { kind: "integer", min: 0, max: 23 },
    minute: { kind: "integer", min: 0, max: 59 },
    label: { kind: "string", maxLength: 100 },
    repeat_days: { kind: "integer_array", maxItems: 7, min: 1, max: 7 },
    skip_ui: { kind: "boolean" },
  },
  outputProperties: {
    alarm_requested: { type: "boolean" },
    hour: { type: "integer" },
    minute: { type: "integer" },
    repeat_days: { type: "array", items: { type: "integer" } },
    skip_ui: { type: "boolean" },
    label_best_effort: { type: "boolean" },
  },
  destructive: false,
});

const ALARM_OPEN_LIST = deviceTool({
  name: "alarm_open_list",
  title: "Open the system alarm list",
  description: "打开系统闹钟应用的闹钟列表页。只打开界面，不创建、不修改、不删除任何闹钟。",
  outputProperties: { opened: { type: "string" } },
  destructive: false,
  idempotent: true,
});

// ---------------------------------------------------------------------------
//  日历（读写都受系统权限约束；修改/删除只对本应用创建的事件开放）
// ---------------------------------------------------------------------------

const CALENDAR_LIST_CALENDARS = deviceTool({
  name: "calendar_list_calendars",
  title: "List calendars (read-only)",
  description:
    "列出手机日历中可见的日历：显示名、时区、是否主日历、是否可写。需要日历读取权限；" +
    "拒绝时返回 permission_denied 并说明去哪开启。不返回账户邮箱等身份信息。",
  outputProperties: {
    count: { type: "integer" },
    calendars: {
      type: "array",
      items: {
        type: "object",
        properties: {
          calendar_id: { type: "string" },
          display_name: { type: "string" },
          timezone: { type: ["string", "null"] },
          primary: { type: "boolean" },
          writable: { type: "boolean" },
        },
      },
    },
  },
  readOnly: true,
});

const CALENDAR_LIST_EVENTS = deviceTool({
  name: "calendar_list_events",
  title: "List calendar events (read-only)",
  description:
    "查询一段 Unix 毫秒时间区间内的日历事件；重复事件按发生实例展开。" +
    "可以按日历 ID 与关键字过滤，最多返回 50 条。区间必须 end_ms > start_ms 且不超过一年。" +
    "需要日历读取权限；系统没有返回的字段是 null，不会编造。",
  properties: {
    start_ms: { type: "integer", description: "区间开始（Unix 毫秒，含）。" },
    end_ms: { type: "integer", description: "区间结束（Unix 毫秒，不含），必须晚于 start_ms。" },
    calendar_id: { type: ["string", "integer"], description: "只看某个日历；不传表示所有可见日历。" },
    query: { type: "string", maxLength: 100, description: "标题/描述关键字。" },
    limit: { type: "integer", minimum: 1, maximum: 50, description: "返回条数，1–50，默认 20。" },
  },
  required: ["start_ms", "end_ms"],
  argRules: {
    start_ms: { kind: "integer", min: 0 },
    end_ms: { kind: "integer", min: 0 },
    calendar_id: { kind: "id", maxLength: 18 },
    query: { kind: "string", maxLength: 100 },
    limit: { kind: "integer", min: 1, max: 50 },
  },
  outputProperties: {
    start_ms: { type: "integer" },
    end_ms: { type: "integer" },
    returned: { type: "integer" },
    limit: { type: "integer" },
    has_more: { type: "boolean" },
    events: { type: "array", items: { type: "object" } },
  },
  readOnly: true,
});

const CALENDAR_CREATE_EVENT = deviceTool({
  name: "calendar_create_event",
  title: "Create a calendar event",
  description:
    "在手机日历中创建事件：标题、起止时间、日历、说明、地点、全天、时区、重复规则、提前提醒。" +
    "不传 calendar_id 时使用主日历。需要日历**写入**权限。创建成功后返回 event_id，" +
    "并记下\"这是 Hub 创建的\"，之后才能用 calendar_update_event / calendar_delete_event 修改或删除它。",
  properties: {
    title: { type: "string", maxLength: 200, description: "事件标题，1–200 字。" },
    start_ms: { type: "integer", description: "开始时间（Unix 毫秒）。" },
    end_ms: { type: "integer", description: "结束时间（Unix 毫秒），必须晚于开始。" },
    calendar_id: { type: ["string", "integer"], description: "目标日历 ID；不传用主日历。" },
    description: { type: "string", maxLength: 2000, description: "说明，最多 2000 字。" },
    location: { type: "string", maxLength: 300, description: "地点，最多 300 字。" },
    all_day: { type: "boolean", description: "是否为全天事件，默认 false。" },
    timezone: { type: "string", maxLength: 64, description: "时区 ID，例如 Asia/Shanghai；默认设备时区。" },
    rrule: {
      type: "string",
      maxLength: 500,
      description: "iCalendar 重复规则，必须以 FREQ= 开头，例如 FREQ=WEEKLY;BYDAY=MO。",
    },
    reminder_minutes: {
      type: "array",
      items: { type: "integer", minimum: 0, maximum: 43200 },
      maxItems: 10,
      description: "提前多少分钟提醒，例如 [10, 60]；每项 0–43200。",
    },
  },
  required: ["title", "start_ms", "end_ms"],
  argRules: {
    title: { kind: "string", maxLength: 200 },
    start_ms: { kind: "integer", min: 0 },
    end_ms: { kind: "integer", min: 0 },
    calendar_id: { kind: "id", maxLength: 18 },
    description: { kind: "string", maxLength: 2000 },
    location: { kind: "string", maxLength: 300 },
    all_day: { kind: "boolean" },
    timezone: { kind: "string", maxLength: 64 },
    rrule: { kind: "string", maxLength: 500 },
    reminder_minutes: { kind: "integer_array", maxItems: 10, min: 0, max: 43200 },
  },
  outputProperties: {
    event_id: { type: "string" },
    note: { type: "string" },
  },
  destructive: false,
});

const CALENDAR_UPDATE_EVENT = deviceTool({
  name: "calendar_update_event",
  title: "Update a Hub-created calendar event",
  description:
    "修改一个由 Android MCP Hub 创建的日历事件（至少提供一项要改的字段）。" +
    "**只能修改本应用创建的事件**；其它来源的事件返回 not_permitted，请在系统日历里改。" +
    "reminder_minutes 是整体替换：传空数组表示移除所有提醒。需要日历写入权限。",
  properties: {
    event_id: { type: ["string", "integer"], description: "事件 ID。" },
    title: { type: "string", maxLength: 200, description: "新标题，1–200 字。" },
    start_ms: { type: "integer", description: "新开始时间（Unix 毫秒）。" },
    end_ms: { type: "integer", description: "新结束时间（Unix 毫秒）。" },
    description: { type: "string", maxLength: 2000, description: "新说明，最多 2000 字。" },
    location: { type: "string", maxLength: 300, description: "新地点，最多 300 字。" },
    timezone: { type: "string", maxLength: 64, description: "新时区 ID。" },
    rrule: { type: "string", maxLength: 500, description: "新的 iCalendar 重复规则，必须以 FREQ= 开头。" },
    reminder_minutes: {
      type: "array",
      items: { type: "integer", minimum: 0, maximum: 43200 },
      maxItems: 10,
      description: "替换提醒设置；空数组表示移除提醒。",
    },
  },
  required: ["event_id"],
  argRules: {
    event_id: { kind: "id", maxLength: 18 },
    title: { kind: "string", maxLength: 200 },
    start_ms: { kind: "integer", min: 0 },
    end_ms: { kind: "integer", min: 0 },
    description: { kind: "string", maxLength: 2000 },
    location: { kind: "string", maxLength: 300 },
    timezone: { kind: "string", maxLength: 64 },
    rrule: { kind: "string", maxLength: 500 },
    reminder_minutes: { kind: "integer_array", maxItems: 10, min: 0, max: 43200 },
  },
  outputProperties: { event_id: { type: "string" } },
});

const CALENDAR_DELETE_EVENT = deviceTool({
  name: "calendar_delete_event",
  title: "Delete a Hub-created calendar event",
  description:
    "删除一个由 Android MCP Hub 创建的日历事件。删除不可恢复，因此**必须显式传 confirm=true**，" +
    "否则返回 confirmation_required。**只能删除本应用创建的事件**；其它来源的事件返回 not_permitted。" +
    "需要日历写入权限。",
  properties: {
    event_id: { type: ["string", "integer"], description: "事件 ID。" },
    confirm: { type: "boolean", description: "必须为 true，表示确认删除。" },
  },
  required: ["event_id", "confirm"],
  argRules: {
    event_id: { kind: "id", maxLength: 18 },
    confirm: { kind: "boolean" },
  },
  outputProperties: { deleted: { type: "boolean" }, event_id: { type: "string" } },
  destructive: true,
});

// ---------------------------------------------------------------------------
//  设备状态与控制
// ---------------------------------------------------------------------------

const DEVICE_GET_STATUS = deviceTool({
  name: "device_get_status",
  title: "Device status (read-only)",
  description:
    "读取手机即时状态：屏幕是否处于交互、锁屏界面是否还在、媒体音量与最大值、是否处于省电模式。" +
    "只读、不需要任何权限。屏幕/锁屏/音量/省电模式分别返回，**不替调用方推断\"已解锁\"**；" +
    "系统没给的字段是 null。",
  outputProperties: {
    screen_on: { type: ["boolean", "null"] },
    locked: { type: ["boolean", "null"] },
    media_volume: { type: ["integer", "null"] },
    media_volume_max: { type: ["integer", "null"] },
    power_save_mode: { type: ["boolean", "null"] },
  },
  readOnly: true,
});

const DEVICE_GET_USAGE_STATS = deviceTool({
  name: "device_get_usage_stats",
  title: "App usage time (read-only)",
  description:
    "读取手机应用使用时长：今天、昨天或最近 7 天，按前台时长降序，默认排除系统应用。" +
    "数据来自系统 UsageStatsManager，口径是\"应用处于前台\"的时长，不是屏幕点亮时长；" +
    "部分厂商 ROM 会延迟聚合，数字可能偏小。默认最多返回 10 个应用、最多 50。" +
    "需要「使用情况访问」特殊权限；没有授权时返回 permission_denied 并说明去哪开启，" +
    "**不会把空列表说成\"今天没用过应用\"**。不返回逐次切换事件、通知或位置。",
  properties: {
    range: {
      type: "string",
      enum: ["today", "yesterday", "7d"],
      description: "统计区间：today（默认）、yesterday、7d（含今天共 7 个本地日）。",
    },
    limit: { type: "integer", minimum: 1, maximum: 50, description: "返回应用数，1–50，默认 10。" },
    include_system_apps: { type: "boolean", description: "是否包含系统应用，默认 false。" },
  },
  argRules: {
    range: { kind: "string", enum: ["today", "yesterday", "7d"] },
    limit: { kind: "integer", min: 1, max: 50 },
    include_system_apps: { kind: "boolean" },
  },
  outputProperties: {
    range: { type: "string" },
    start_ms: { type: "integer" },
    end_ms: { type: "integer" },
    returned: { type: "integer" },
    has_more: { type: "boolean" },
    total_apps: { type: "integer" },
    total_foreground_ms: { type: "integer" },
    apps: { type: "array", items: { type: "object" } },
    caveats: { type: "array", items: { type: "string" } },
  },
  readOnly: true,
});

const DEVICE_OPEN_APP = deviceTool({
  name: "device_open_app",
  title: "Launch an installed app",
  description:
    "按包名启动一个已安装、且有桌面入口的应用。只接受合法包名；找不到应用时返回 tool_unavailable。" +
    "受 Android 后台启动限制，startActivity 成功**不代表界面一定弹出**，所以结果只说\"已发出启动请求\"。",
  properties: {
    package_name: {
      type: "string",
      maxLength: 128,
      pattern: "^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$",
      description: "应用包名，例如 com.android.settings。",
    },
  },
  required: ["package_name"],
  argRules: { package_name: { kind: "string", maxLength: 128 } },
  outputProperties: { launch_requested: { type: "boolean" }, package_name: { type: "string" } },
  destructive: false,
  idempotent: true,
});

const DEVICE_OPEN_SETTINGS = deviceTool({
  name: "device_open_settings",
  title: "Open a system settings page",
  description:
    "打开一个白名单内的系统设置页：本应用通知、本应用详情、Wi-Fi、蓝牙、声音、显示、省电。" +
    "不接受任意 action 或 URL，因此不能借此跳到未列出的页面。",
  properties: {
    page: {
      type: "string",
      enum: ["app_notifications", "app_details", "wifi", "bluetooth", "sound", "display", "battery"],
      description: "要打开的设置页。",
    },
  },
  required: ["page"],
  argRules: {
    page: {
      kind: "string",
      enum: ["app_notifications", "app_details", "wifi", "bluetooth", "sound", "display", "battery"],
    },
  },
  outputProperties: { opened: { type: "string" } },
  destructive: false,
  idempotent: true,
});

const DEVICE_POST_NOTIFICATION = deviceTool({
  name: "device_post_notification",
  title: "Post a notification from this app",
  description:
    "用 Android MCP Hub 自己的通知渠道发一条通知（标题 + 正文）。只影响本应用的通知，" +
    "不会读取、修改或撤销其它应用的通知。需要 Android 13+ 的通知权限，" +
    "没有授权时返回 permission_denied 并说明去哪开启。",
  properties: {
    title: { type: "string", maxLength: 100, description: "通知标题，最多 100 字。" },
    body: { type: "string", maxLength: 500, description: "通知正文，最多 500 字。" },
  },
  required: ["title", "body"],
  argRules: {
    title: { kind: "string", maxLength: 100 },
    body: { kind: "string", maxLength: 500 },
  },
  outputProperties: { posted: { type: "boolean" } },
  destructive: false,
});

const DEVICE_SET_FLASHLIGHT = deviceTool({
  name: "device_set_flashlight",
  title: "Turn the flashlight on or off",
  description:
    "打开或关闭手机手电筒。需要相机权限；没有闪光灯或系统拒绝时如实返回 tool_unavailable/tool_failed，" +
    "不会假装已经打开。",
  properties: { on: { type: "boolean", description: "true 打开，false 关闭。" } },
  required: ["on"],
  argRules: { on: { kind: "boolean" } },
  outputProperties: { torch_on: { type: "boolean" } },
  destructive: false,
  idempotent: true,
});

const DEVICE_SET_VOLUME = deviceTool({
  name: "device_set_volume",
  title: "Set media volume",
  description:
    "把媒体音量设为指定档位（绝对值），只影响媒体流。超出系统最大值时会被夹到边界，" +
    "结果里 requested_volume / media_volume / clamped 会如实说明是否发生了夹取。",
  properties: {
    volume: { type: "integer", minimum: 0, maximum: 1000, description: "目标音量档位，0 表示静音。" },
  },
  required: ["volume"],
  argRules: { volume: { kind: "integer", min: 0, max: 1000 } },
  outputProperties: {
    media_volume: { type: "integer" },
    media_volume_max: { type: "integer" },
    requested_volume: { type: "integer" },
    clamped: { type: "boolean" },
  },
  destructive: false,
  idempotent: true,
});

// ---------------------------------------------------------------------------
//  备忘
// ---------------------------------------------------------------------------

const MEMO_CREATE = deviceTool({
  name: "memo_create",
  title: "Create a memo in the phone's local store",
  description:
    "在手机本地创建一条备忘（标题 + 可选正文）。数据只存在 Android MCP Hub 的私有存储里，" +
    "不上传、不同步到任何第三方。标题 1–200 字，正文最多 4000 字。",
  properties: {
    title: { type: "string", maxLength: 200, description: "备忘标题，1–200 字。" },
    body: { type: "string", maxLength: 4000, description: "备忘正文，最多 4000 字。" },
  },
  required: ["title"],
  argRules: {
    title: { kind: "string", maxLength: 200 },
    body: { kind: "string", maxLength: 4000 },
  },
  outputProperties: {
    id: { type: "string" },
    title: { type: "string" },
    created_at_ms: { type: "integer" },
  },
  destructive: false,
});

const MEMO_LIST = deviceTool({
  name: "memo_list",
  title: "List memos (titles + short previews)",
  description:
    "列出手机本地的备忘，可按关键字过滤、分页。列表只返回**摘要**（正文前 120 字），" +
    "要看全文请用 memo_get。最多 50 条一页。",
  properties: {
    query: { type: "string", maxLength: 100, description: "可选关键字（标题或正文包含即命中）。" },
    offset: { type: "integer", minimum: 0, maximum: 100000, description: "跳过多少条，默认 0。" },
    limit: { type: "integer", minimum: 1, maximum: 50, description: "返回条数，1–50，默认 20。" },
  },
  argRules: {
    query: { kind: "string", maxLength: 100 },
    offset: { kind: "integer", min: 0, max: 100000 },
    limit: { kind: "integer", min: 1, max: 50 },
  },
  outputProperties: {
    total: { type: "integer" },
    offset: { type: "integer" },
    limit: { type: "integer" },
    returned: { type: "integer" },
    has_more: { type: "boolean" },
    memos: { type: "array", items: { type: "object" } },
  },
  readOnly: true,
});

const MEMO_GET = deviceTool({
  name: "memo_get",
  title: "Read one memo",
  description: "按 ID 读取一条备忘的完整标题与正文。ID 必须来自 memo_create / memo_list。",
  properties: { id: { type: "string", maxLength: 32, description: "备忘 ID，例如 memo_1。" } },
  required: ["id"],
  argRules: { id: { kind: "string", maxLength: 32 } },
  outputProperties: {
    id: { type: "string" },
    title: { type: "string" },
    body: { type: "string" },
    created_at_ms: { type: "integer" },
    updated_at_ms: { type: "integer" },
  },
  readOnly: true,
});

const MEMO_UPDATE = deviceTool({
  name: "memo_update",
  title: "Update a memo",
  description: "修改一条备忘的标题和/或正文（至少提供一项）。不会创建新记录。",
  properties: {
    id: { type: "string", maxLength: 32, description: "备忘 ID。" },
    title: { type: "string", maxLength: 200, description: "新标题，1–200 字。" },
    body: { type: "string", maxLength: 4000, description: "新正文，最多 4000 字。" },
  },
  required: ["id"],
  argRules: {
    id: { kind: "string", maxLength: 32 },
    title: { kind: "string", maxLength: 200 },
    body: { kind: "string", maxLength: 4000 },
  },
  outputProperties: { id: { type: "string" }, updated_at_ms: { type: "integer" } },
});

const MEMO_DELETE = deviceTool({
  name: "memo_delete",
  title: "Delete a memo",
  description:
    "删除一条备忘。删除不可恢复，**必须显式传 confirm=true**，否则返回 confirmation_required。" +
    "未确认时不会改动任何数据。",
  properties: {
    id: { type: "string", maxLength: 32, description: "备忘 ID。" },
    confirm: { type: "boolean", description: "必须为 true，表示确认删除。" },
  },
  required: ["id", "confirm"],
  argRules: {
    id: { kind: "string", maxLength: 32 },
    confirm: { kind: "boolean" },
  },
  outputProperties: { deleted: { type: "boolean" }, id: { type: "string" } },
  destructive: true,
});

// ---------------------------------------------------------------------------
//  待办
// ---------------------------------------------------------------------------

const TODO_CREATE = deviceTool({
  name: "todo_create",
  title: "Create a todo",
  description:
    "在手机本地创建一条待办：标题、可选备注、截止时间（Unix 毫秒）、优先级（low/normal/high）。" +
    "数据只存在本应用私有存储里，不上传。",
  properties: {
    title: { type: "string", maxLength: 200, description: "待办标题，1–200 字。" },
    notes: { type: "string", maxLength: 2000, description: "备注，最多 2000 字。" },
    due_at_ms: { type: "integer", description: "截止时间（Unix 毫秒），可省略。" },
    priority: { type: "string", enum: ["low", "normal", "high"], description: "优先级，默认 normal。" },
  },
  required: ["title"],
  argRules: {
    title: { kind: "string", maxLength: 200 },
    notes: { kind: "string", maxLength: 2000 },
    due_at_ms: { kind: "integer" },
    priority: { kind: "string", enum: ["low", "normal", "high"] },
  },
  outputProperties: {
    id: { type: "string" },
    title: { type: "string" },
    priority: { type: "string" },
    due_at_ms: { type: ["integer", "null"] },
  },
  destructive: false,
});

const TODO_LIST = deviceTool({
  name: "todo_list",
  title: "List todos",
  description:
    "列出手机本地的待办：未完成的在前，同组按截止时间排序。可按 open/done 过滤、按关键字搜索、分页。" +
    "结果里 overdue 表示\"未完成且截止时间已过\"。",
  properties: {
    status: { type: "string", enum: ["open", "done"], description: "只看 open 或 done；不传表示全部。" },
    query: { type: "string", maxLength: 100, description: "可选关键字（标题或备注包含即命中）。" },
    offset: { type: "integer", minimum: 0, maximum: 100000, description: "跳过多少条，默认 0。" },
    limit: { type: "integer", minimum: 1, maximum: 50, description: "返回条数，1–50，默认 20。" },
  },
  argRules: {
    status: { kind: "string", enum: ["open", "done"] },
    query: { kind: "string", maxLength: 100 },
    offset: { kind: "integer", min: 0, max: 100000 },
    limit: { kind: "integer", min: 1, max: 50 },
  },
  outputProperties: {
    total: { type: "integer" },
    offset: { type: "integer" },
    limit: { type: "integer" },
    returned: { type: "integer" },
    has_more: { type: "boolean" },
    todos: { type: "array", items: { type: "object" } },
  },
  readOnly: true,
});

const TODO_UPDATE = deviceTool({
  name: "todo_update",
  title: "Update a todo",
  description: "修改一条待办的标题、备注、截止时间或优先级（至少提供一项）。不会创建新记录。",
  properties: {
    id: { type: "string", maxLength: 32, description: "待办 ID。" },
    title: { type: "string", maxLength: 200, description: "新标题，1–200 字。" },
    notes: { type: "string", maxLength: 2000, description: "新备注，最多 2000 字。" },
    due_at_ms: { type: "integer", description: "新截止时间（Unix 毫秒）。" },
    priority: { type: "string", enum: ["low", "normal", "high"], description: "low / normal / high。" },
  },
  required: ["id"],
  argRules: {
    id: { kind: "string", maxLength: 32 },
    title: { kind: "string", maxLength: 200 },
    notes: { kind: "string", maxLength: 2000 },
    due_at_ms: { kind: "integer" },
    priority: { kind: "string", enum: ["low", "normal", "high"] },
  },
  outputProperties: { id: { type: "string" }, updated_at_ms: { type: "integer" } },
});

const TODO_COMPLETE = deviceTool({
  name: "todo_complete",
  title: "Complete or reopen a todo",
  description: "把一条待办标记为完成（默认），或传 done=false 重新打开。重复调用结果一致。",
  properties: {
    id: { type: "string", maxLength: 32, description: "待办 ID。" },
    done: { type: "boolean", description: "true=完成（默认），false=重新打开。" },
  },
  required: ["id"],
  argRules: {
    id: { kind: "string", maxLength: 32 },
    done: { kind: "boolean" },
  },
  outputProperties: { id: { type: "string" }, status: { type: "string" } },
  destructive: false,
  idempotent: true,
});

const TODO_DELETE = deviceTool({
  name: "todo_delete",
  title: "Delete a todo",
  description:
    "删除一条待办。删除不可恢复，**必须显式传 confirm=true**，否则返回 confirmation_required。",
  properties: {
    id: { type: "string", maxLength: 32, description: "待办 ID。" },
    confirm: { type: "boolean", description: "必须为 true，表示确认删除。" },
  },
  required: ["id", "confirm"],
  argRules: {
    id: { kind: "string", maxLength: 32 },
    confirm: { kind: "boolean" },
  },
  outputProperties: { deleted: { type: "boolean" }, id: { type: "string" } },
  destructive: true,
});

// ---------------------------------------------------------------------------
//  本地提醒（AlarmManager 非精确闹钟）
// ---------------------------------------------------------------------------

const REMINDER_CREATE = deviceTool({
  name: "reminder_create",
  title: "Create a local reminder",
  description:
    "在手机上创建一条本地提醒，到点由本应用发通知。时间必须是将来的 Unix 毫秒。" +
    "使用系统**非精确**闹钟（不需要精确闹钟权限），省电模式下可能延后几分钟；" +
    "设备重启后需要打开一次应用才会重新排程（结果里的 timing_note 会说明）。" +
    "通知权限未授予时仍会排程，但 notification_permission_granted=false。",
  properties: {
    title: { type: "string", maxLength: 200, description: "提醒标题，1–200 字。" },
    at_ms: { type: "integer", description: "提醒时间（Unix 毫秒），必须晚于现在。" },
    note: { type: "string", maxLength: 1000, description: "可选备注，最多 1000 字。" },
  },
  required: ["title", "at_ms"],
  argRules: {
    title: { kind: "string", maxLength: 200 },
    at_ms: { kind: "integer", min: 0 },
    note: { kind: "string", maxLength: 1000 },
  },
  outputProperties: {
    id: { type: "string" },
    title: { type: "string" },
    at_ms: { type: "integer" },
    status: { type: "string" },
    notification_permission_granted: { type: "boolean" },
    timing_note: { type: "string" },
  },
  destructive: false,
});

const REMINDER_LIST = deviceTool({
  name: "reminder_list",
  title: "List local reminders",
  description:
    "列出手机本地的提醒，可按状态（scheduled / fired / done）过滤并分页，按提醒时间排序。",
  properties: {
    status: {
      type: "string",
      enum: ["scheduled", "fired", "done"],
      description: "只看某个状态；不传表示全部。",
    },
    offset: { type: "integer", minimum: 0, maximum: 100000, description: "跳过多少条，默认 0。" },
    limit: { type: "integer", minimum: 1, maximum: 50, description: "返回条数，1–50，默认 20。" },
  },
  argRules: {
    status: { kind: "string", enum: ["scheduled", "fired", "done"] },
    offset: { kind: "integer", min: 0, max: 100000 },
    limit: { kind: "integer", min: 1, max: 50 },
  },
  outputProperties: {
    total: { type: "integer" },
    offset: { type: "integer" },
    limit: { type: "integer" },
    returned: { type: "integer" },
    has_more: { type: "boolean" },
    reminders: { type: "array", items: { type: "object" } },
  },
  readOnly: true,
});

const REMINDER_UPDATE = deviceTool({
  name: "reminder_update",
  title: "Update a local reminder",
  description:
    "修改一条未完成提醒的标题、备注或时间（至少提供一项）。改时间会重新排程；" +
    "时间必须是将来。已完成的提醒需要先用 reminder_complete(done=false) 重新打开。",
  properties: {
    id: { type: "string", maxLength: 32, description: "提醒 ID。" },
    title: { type: "string", maxLength: 200, description: "新标题，1–200 字。" },
    note: { type: "string", maxLength: 1000, description: "新备注，最多 1000 字。" },
    at_ms: { type: "integer", description: "新的提醒时间（Unix 毫秒），必须是将来。" },
  },
  required: ["id"],
  argRules: {
    id: { kind: "string", maxLength: 32 },
    title: { kind: "string", maxLength: 200 },
    note: { kind: "string", maxLength: 1000 },
    at_ms: { kind: "integer", min: 0 },
  },
  outputProperties: { id: { type: "string" }, at_ms: { type: "integer" }, status: { type: "string" } },
  idempotent: true,
});

const REMINDER_COMPLETE = deviceTool({
  name: "reminder_complete",
  title: "Complete or reopen a reminder",
  description:
    "把提醒标记为完成（默认，并取消排程），或传 done=false 重新打开。" +
    "重新打开时提醒时间必须仍在将来，否则返回 invalid_arguments。",
  properties: {
    id: { type: "string", maxLength: 32, description: "提醒 ID。" },
    done: { type: "boolean", description: "true=完成（默认），false=重新打开。" },
  },
  required: ["id"],
  argRules: {
    id: { kind: "string", maxLength: 32 },
    done: { kind: "boolean" },
  },
  outputProperties: { id: { type: "string" }, status: { type: "string" } },
  destructive: false,
  idempotent: true,
});

const REMINDER_DELETE = deviceTool({
  name: "reminder_delete",
  title: "Delete a local reminder",
  description:
    "删除一条提醒并取消它的排程。删除不可恢复，**必须显式传 confirm=true**，" +
    "否则返回 confirmation_required 且不会取消系统闹钟。",
  properties: {
    id: { type: "string", maxLength: 32, description: "提醒 ID。" },
    confirm: { type: "boolean", description: "必须为 true，表示确认删除。" },
  },
  required: ["id", "confirm"],
  argRules: {
    id: { kind: "string", maxLength: 32 },
    confirm: { kind: "boolean" },
  },
  outputProperties: { deleted: { type: "boolean" }, id: { type: "string" } },
  destructive: true,
});

/**
 * Phase 3 新增的全部本机工具。
 *
 * 顺序按工具名排序（与本机注册表 `BuiltInCapabilities.names` 一致），
 * 便于人工比对"服务端认识的工具"与"手机愿意执行的工具"是否一一对应。
 */
export const NATIVE_TOOLS: readonly ToolDefinition[] = [
  ALARM_CREATE,
  ALARM_OPEN_LIST,
  CALENDAR_CREATE_EVENT,
  CALENDAR_DELETE_EVENT,
  CALENDAR_LIST_CALENDARS,
  CALENDAR_LIST_EVENTS,
  CALENDAR_UPDATE_EVENT,
  DEVICE_GET_STATUS,
  DEVICE_GET_USAGE_STATS,
  DEVICE_OPEN_APP,
  DEVICE_OPEN_SETTINGS,
  DEVICE_POST_NOTIFICATION,
  DEVICE_SET_FLASHLIGHT,
  DEVICE_SET_VOLUME,
  MEMO_CREATE,
  MEMO_DELETE,
  MEMO_GET,
  MEMO_LIST,
  MEMO_UPDATE,
  REMINDER_COMPLETE,
  REMINDER_CREATE,
  REMINDER_DELETE,
  REMINDER_LIST,
  REMINDER_UPDATE,
  TODO_COMPLETE,
  TODO_CREATE,
  TODO_DELETE,
  TODO_LIST,
  TODO_UPDATE,
];
