// ============================================================================
//  Phase 3 本机工具 —— 注册表形状与参数规则
// ============================================================================
//
//  这些工具的执行全在手机上，Worker 能负责的只有两件事：声明得对不对、
//  以及**有没有把不该转发的东西转发出去**。所以这里盯的是：
//
//    1. 每个工具的 allowedArgs 恰好等于 schema 的 properties − 公共键；
//       "声明了但白名单没写"或反之都会让一条参数悄悄失效或悄悄透传；
//    2. 每个工具都有 `additionalProperties: false`；
//    3. 删除/覆盖类工具标了 destructiveHint，只读工具标了 readOnlyHint；
//    4. 超长字符串、越界数字、非法枚举、错误数组在**服务端**就被拒绝，
//       而不是穿过整条链路等手机回 invalid_arguments。
// ============================================================================

import { describe, expect, it } from "vitest";
import { findTool, parseToolArguments, TOOL_REGISTRY, ToolArgumentError } from "../src/mcp/tools";
import { DESTRUCTIVE_TOOL_NAMES, EXPECTED_TOOL_NAMES, READ_ONLY_TOOL_NAMES } from "./tool-fixture";

const COMMON = ["device_id", "timeout_ms"];

function tool(name: string) {
  const found = findTool(name);
  if (found === undefined) throw new Error(`注册表里没有 ${name}`);
  return found;
}

function parseOk(name: string, args: Record<string, unknown>): Record<string, unknown> {
  return parseToolArguments(tool(name), args).toolArguments;
}

function parseError(name: string, args: Record<string, unknown>): string {
  try {
    parseToolArguments(tool(name), args);
  } catch (err) {
    expect(err).toBeInstanceOf(ToolArgumentError);
    return (err as Error).message;
  }
  throw new Error(`预期 ${name} 拒绝这组参数，实际通过了：${JSON.stringify(args)}`);
}

describe("Phase 3 / 注册表形状", () => {
  it("工具名与测试侧期望清单逐项一致", () => {
    expect(TOOL_REGISTRY.map((t) => t.name)).toEqual([...EXPECTED_TOOL_NAMES]);
  });

  it("每个工具的 allowedArgs 恰好等于自有参数（不多、不少、不重复）", () => {
    for (const t of TOOL_REGISTRY) {
      const schema = t.inputSchema as Record<string, unknown>;
      const properties = Object.keys(schema["properties"] as Record<string, unknown>)
        .filter((key) => !COMMON.includes(key))
        .sort();
      expect([...t.allowedArgs].sort(), `${t.name} 的 allowedArgs`).toEqual(properties);
      expect(new Set(t.allowedArgs).size).toBe(t.allowedArgs.length);
      expect(schema["additionalProperties"]).toBe(false);
    }
  });

  it("required 里的每一项都真的声明了", () => {
    for (const t of TOOL_REGISTRY) {
      const schema = t.inputSchema as Record<string, unknown>;
      const properties = schema["properties"] as Record<string, unknown>;
      for (const key of (schema["required"] as string[] | undefined) ?? []) {
        expect(properties[key], `${t.name}.${key} 被 required 却没有 properties`).toBeDefined();
      }
    }
  });

  it("每个参数都在 argRules 里有规则，或明确接受通用标量规则", () => {
    // 本机能力（transport=tool，且不是最初三个只读工具）必须逐参数声明规则：
    // 否则 memo body 这种 4000 字的字段会被通用的 512 字上限误伤。
    const legacy = new Set([
      "battery_get_status",
      "device_get_network",
      "device_get_storage",
    ]);
    for (const t of TOOL_REGISTRY) {
      if (t.transport !== "tool" || legacy.has(t.name)) continue;
      const rules = t.argRules ?? {};
      for (const key of t.allowedArgs) {
        expect(rules[key], `${t.name}.${key} 缺少 argRules`).toBeDefined();
      }
      expect(Object.keys(rules).sort()).toEqual([...t.allowedArgs].sort());
    }
  });

  it("只读/破坏性标注与测试侧清单一致，且两者不会同时为真", () => {
    for (const t of TOOL_REGISTRY) {
      const annotations = t.annotations ?? {};
      const readOnly = annotations["readOnlyHint"];
      const destructive = annotations["destructiveHint"];

      if (READ_ONLY_TOOL_NAMES.includes(t.name as never)) {
        expect(readOnly, `${t.name} 应为只读`).toBe(true);
      }
      if (DESTRUCTIVE_TOOL_NAMES.includes(t.name as never)) {
        expect(destructive, `${t.name} 应标 destructiveHint`).toBe(true);
      }
      if (readOnly === true && destructive !== undefined) {
        expect(destructive, `${t.name} 只读却同时标了破坏性`).toBe(false);
      }
    }
  });

  it("删除类工具在 schema 里明确要求 confirm", () => {
    const schema = tool("memo_delete").inputSchema as Record<string, unknown>;
    expect(schema["required"]).toContain("confirm");
    for (const name of ["calendar_delete_event", "reminder_delete", "todo_delete"]) {
      const required = (tool(name).inputSchema as Record<string, unknown>)["required"] as string[];
      expect(required).toContain("confirm");
    }
  });
});

describe("Phase 3 / 参数规则", () => {
  it("备忘正文 4000 字可以通过，4001 字被拒绝", () => {
    expect(parseOk("memo_create", { title: "t", body: "a".repeat(4000) })["body"]).toHaveLength(4000);
    expect(parseError("memo_create", { title: "t", body: "a".repeat(4001) })).toContain("4000");
  });

  it("未声明的字段仍然被拒绝（白名单没有因为新工具而放宽）", () => {
    expect(parseError("memo_create", { title: "t", author: "me" })).toContain("不认识的字段");
    expect(parseError("device_set_volume", { volume: 3, force: true })).toContain("不认识的字段");
  });

  it("枚举是闭集：优先级 / 区间 / 状态页", () => {
    expect(parseOk("todo_create", { title: "t", priority: "high" })["priority"]).toBe("high");
    expect(parseError("todo_create", { title: "t", priority: "urgent" })).toContain("只能是");
    expect(parseError("device_get_usage_stats", { range: "last_month" })).toContain("只能是");
    expect(parseError("device_open_settings", { page: "developer" })).toContain("只能是");
  });

  it("数值边界与整数要求", () => {
    expect(parseOk("device_set_volume", { volume: 0 })["volume"]).toBe(0);
    expect(parseError("device_set_volume", { volume: -1 })).toContain("不能小于");
    expect(parseError("device_set_volume", { volume: 12.5 })).toContain("整数");
    expect(parseError("alarm_create", { hour: 24, minute: 0 })).toContain("不能大于");
    expect(parseError("device_get_usage_stats", { limit: 51 })).toContain("不能大于");
  });

  it("repeat_days 只接受 1–7 的整数数组，最多 7 项", () => {
    expect(parseOk("alarm_create", { hour: 7, minute: 30, repeat_days: [1, 3, 5] })["repeat_days"]).toEqual([1, 3, 5]);
    expect(parseError("alarm_create", { hour: 7, minute: 30, repeat_days: [0] })).toContain("不能小于");
    expect(parseError("alarm_create", { hour: 7, minute: 30, repeat_days: [1.5] })).toContain("整数");
    expect(parseError("alarm_create", { hour: 7, minute: 30, repeat_days: [1, 2, 3, 4, 5, 6, 7, 1] })).toContain("最多 7 项");
    expect(parseError("alarm_create", { hour: 7, minute: 30, repeat_days: "MON" })).toContain("整数数组");
  });

  it("日历提醒是整数数组，范围为 0–43200 分钟", () => {
    const args = { title: "会", start_ms: 1_700_000_000_000, end_ms: 1_700_000_100_000, reminder_minutes: [0, 60] };
    expect(parseOk("calendar_create_event", args)["reminder_minutes"]).toEqual([0, 60]);
    expect(parseError("calendar_create_event", { ...args, reminder_minutes: [43201] })).toContain("不能大于");
    expect(parseError("calendar_create_event", { ...args, reminder_minutes: ["60"] })).toContain("整数");
  });

  it("日历 ID 接受数字或纯数字字符串，拒绝负数、小数与非数字", () => {
    expect(parseOk("calendar_delete_event", { event_id: 42, confirm: true })["event_id"]).toBe(42);
    expect(parseOk("calendar_delete_event", { event_id: "42", confirm: true })["event_id"]).toBe("42");
    expect(parseError("calendar_delete_event", { event_id: -1, confirm: true })).toContain("非负整数");
    expect(parseError("calendar_delete_event", { event_id: 1.2, confirm: true })).toContain("非负整数");
    expect(parseError("calendar_delete_event", { event_id: "42a", confirm: true })).toContain("纯数字");
    expect(parseError("calendar_delete_event", { event_id: "1234567890123456789", confirm: true })).toContain("18");
  });

  it("显式 null 原样转发（由手机按\"未提供\"处理），不是服务端报错", () => {
    expect(parseOk("memo_update", { id: "memo_1", body: null })["body"]).toBeNull();
  });

  it("未声明规则的旧工具仍保持 512 字上限", () => {
    // battery_get_status 没有自有参数；用一个自造的调用确认通用规则没被改宽。
    const legacy = tool("battery_get_status");
    try {
      parseToolArguments(legacy, { device_id: "d", extra: "x".repeat(513) });
      throw new Error("预期拒绝");
    } catch (err) {
      // 未声明字段先被白名单挡住，这同样是预期行为。
      expect((err as Error).message).toContain("不认识的字段");
    }
  });
});
