// 测试侧的**期望工具清单**，与生产代码分开维护。
//
// 这几张表刻意不从 `tools.ts` 派生：如果期望值也跟着注册表走，"多了一个没人
// 审过的工具"就永远测不出来。注册表变化时这里必须一起改 —— 这正是要的摩擦。
//
// 前七项的顺序同时被旧客户端与多条回归断言依赖：
//   phone_ping → battery → network → storage → 三个聚合入口。
// Phase 3 的二十九个本机能力整体追加在后面，自身按名字排序（与手机侧
// BuiltInCapabilities.names 一致）。

export const EXPECTED_TOOL_NAMES = [
  "phone_ping",
  "battery_get_status",
  "device_get_network",
  "device_get_storage",
  "hub_list_tools",
  "hub_call_tool",
  "hub_get_tool_schema",
  // Phase 3：Android 本机能力。
  "alarm_create",
  "alarm_open_list",
  "calendar_create_event",
  "calendar_delete_event",
  "calendar_list_calendars",
  "calendar_list_events",
  "calendar_update_event",
  "device_get_status",
  "device_get_usage_stats",
  "device_open_app",
  "device_open_settings",
  "device_post_notification",
  "device_set_flashlight",
  "device_set_volume",
  "memo_create",
  "memo_delete",
  "memo_get",
  "memo_list",
  "memo_update",
  "reminder_complete",
  "reminder_create",
  "reminder_delete",
  "reminder_list",
  "reminder_update",
  "todo_complete",
  "todo_create",
  "todo_delete",
  "todo_list",
  "todo_update",
] as const;

/** 只读取、不改动任何数据（或只发出无副作用的系统界面请求）的本机工具。 */
export const READ_ONLY_TOOL_NAMES = [
  "phone_ping",
  "battery_get_status",
  "device_get_network",
  "device_get_storage",
  "hub_list_tools",
  "hub_get_tool_schema",
  "calendar_list_calendars",
  "calendar_list_events",
  "device_get_status",
  "device_get_usage_stats",
  "memo_get",
  "memo_list",
  "reminder_list",
  "todo_list",
] as const;

/**
 * 删除/覆盖类工具：必须标 `destructiveHint: true`。
 *
 * 更新类也在里面：它们**覆盖**已有数据，旧值不可恢复，按 MCP 规范不是"纯追加更新"，
 * 客户端不应自动放行。四个删除工具另外还要求手机侧 `confirm=true`。
 */
export const DESTRUCTIVE_TOOL_NAMES = [
  "calendar_delete_event",
  "calendar_update_event",
  "memo_delete",
  "memo_update",
  "reminder_delete",
  "reminder_update",
  "todo_delete",
  "todo_update",
] as const;
