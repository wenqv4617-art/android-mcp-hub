// ============================================================================
//  Gate 2 工作包 C —— 通用 Dispatcher（MCP 工具层）
// ============================================================================
//
//  工具层只做四件事：查注册表、校验参数、解析设备、决定走哪条通道。
//  它**不 import** Durable Object，也不自己产生副作用 —— 撤销通知这种
//  副作用由传输层收到 `revokedDeviceId` 之后去做。
//
//  这个文件盯的是三件容易悄悄坏掉的事：
//    1. 未知工具 / 未知参数 / 越界参数必须走**协议错误**，且不碰转发层；
//    2. 能力授权（设备解析、服务端禁用）必须排在"真的去问手机"之前；
//    3. 手机回来的东西不许污染信封（result 里塞 `ok`、`request_id` 之类）。
//
//  ⚠ 这里跑的仍是 Node 上的 Workers 运行时替身，不是真实 workerd。
// ============================================================================

import { describe, expect, it } from "vitest";
import { dispatchToolCall, toolOutcomeToResult } from "../src/mcp/tools";
import type { ToolOutcome } from "../src/phone/phone-session";
import { loadConfig } from "../src/config";
import { makeRig, TEST_DEVICE_ID, TEST_DEVICE_TOKEN } from "./harness/env";

const okTool: ToolOutcome = {
  ok: true,
  request_id: "tool-req-1",
  device_id: TEST_DEVICE_ID,
  tool_name: "battery_get_status",
  result: {
    battery_level_percent: 42,
    charging: false,
    charging_source: "unavailable",
    power_save_mode: true,
  },
  rtt_ms: 18,
  server_timestamp: 1_700_000_000_000,
  connection_generation: 1,
};

const failTool: ToolOutcome = {
  ok: false,
  request_id: "tool-req-2",
  device_id: TEST_DEVICE_ID,
  tool_name: "battery_get_status",
  error: { code: "tool_failed", message: "读电池状态时出了点问题" },
};

describe("工具层 / Gate 2 通用分发", () => {
  it("battery_get_status 走 tool 通道，结果被摊平进 structuredContent", async () => {
    const rig = await makeRig();
    const seen: Array<{ deviceId: string; toolName: string; args: Record<string, unknown>; timeoutMs: number | undefined }> = [];
    const dispatched = await dispatchToolCall(
      loadConfig(rig.env),
      "battery_get_status",
      { device_id: TEST_DEVICE_ID },
      async () => {
        throw new Error("这个工具不该走 ping 通道");
      },
      async (deviceId, toolName, args, timeoutMs) => {
        seen.push({ deviceId, toolName, args, timeoutMs });
        return okTool;
      },
    );

    expect(seen).toEqual([
      { deviceId: TEST_DEVICE_ID, toolName: "battery_get_status", args: {}, timeoutMs: undefined },
    ]);
    const structured = dispatched.result?.structuredContent as Record<string, unknown>;
    expect(structured["ok"]).toBe(true);
    expect(structured["battery_level_percent"]).toBe(42);
    expect(structured["charging_source"]).toBe("unavailable");
    expect(structured["tool_name"]).toBe("battery_get_status");
    expect(structured["connection_generation"]).toBe(1);
    expect(dispatched.result?.isError).toBeFalsy();
  });

  it("工具通道未接线时明确失败，**绝不悄悄改用 ping 通道**", async () => {
    const rig = await makeRig();
    let pingCalls = 0;
    const dispatched = await dispatchToolCall(
      loadConfig(rig.env),
      "battery_get_status",
      { device_id: TEST_DEVICE_ID },
      async () => {
        pingCalls += 1;
        throw new Error("不该被调用");
      },
      // 故意不传 callPhoneTool
    );
    expect(pingCalls).toBe(0);
    expect(dispatched.result?.isError).toBe(true);
    const structured = dispatched.result?.structuredContent as Record<string, unknown>;
    expect(structured["error_code"]).toBe("internal_error");
  });

  it("★ 转发层抛错时结果是失败，绝不退化成成功", async () => {
    const rig = await makeRig();
    const dispatched = await dispatchToolCall(
      loadConfig(rig.env),
      "battery_get_status",
      { device_id: TEST_DEVICE_ID },
      async () => {
        throw new Error("不该被调用");
      },
      async () => {
        throw new Error("内部异常，连文案都不该泄漏出去");
      },
    );
    expect(dispatched.result?.isError).toBe(true);
    const structured = dispatched.result?.structuredContent as Record<string, unknown>;
    expect(structured["ok"]).toBe(false);
    expect(structured["error_message"]).toBe("内部错误：转发到手机时发生异常，详情见服务端日志");
    // 异常原文不许漏进模型上下文。
    expect(JSON.stringify(structured)).not.toContain("内部异常");
  });

  it("手机回的失败被如实转达（错误码原样保留）", async () => {
    const rig = await makeRig();
    const dispatched = await dispatchToolCall(
      loadConfig(rig.env),
      "battery_get_status",
      { device_id: TEST_DEVICE_ID },
      async () => {
        throw new Error("不该被调用");
      },
      async () => failTool,
    );
    expect(dispatched.result?.isError).toBe(true);
    const structured = dispatched.result?.structuredContent as Record<string, unknown>;
    expect(structured["ok"]).toBe(false);
    // ⚠ 已知的形状不一致（阶段 1A 报告的 D-3，P3，本轮刻意不改）：
    //   设备解析失败走**扁平** `error_code`/`error_message`，
    //   真正打到手机的失败走**嵌套** `error.code`/`error.message`
    //   （与 Gate 1 的 ping 失败形状一致）。两条都断言到，是为了让这个
    //   不一致**可见**，而不是假装它不存在。
    expect((structured["error"] as Record<string, unknown>)["code"]).toBe("tool_failed");
    expect((structured["error"] as Record<string, unknown>)["message"]).toBe("读电池状态时出了点问题");
  });

  it("★ 手机在 result 里塞信封键时被丢弃，绝不允许它改写 ok / request_id", () => {
    const hostile: ToolOutcome = {
      ok: true,
      request_id: "tool-req-3",
      device_id: TEST_DEVICE_ID,
      tool_name: "battery_get_status",
      result: {
        battery_level_percent: 99,
        ok: false,
        request_id: "hijacked",
        device_id: "another-phone",
        tool_name: "some_other_tool",
        error_code: "tool_failed",
      },
      rtt_ms: 5,
      server_timestamp: 1_700_000_000_000,
      connection_generation: 1,
    };
    const result = toolOutcomeToResult(hostile);
    const structured = result.structuredContent as Record<string, unknown>;

    expect(structured["ok"]).toBe(true);
    expect(structured["request_id"]).toBe("tool-req-3");
    expect(structured["device_id"]).toBe(TEST_DEVICE_ID);
    expect(structured["tool_name"]).toBe("battery_get_status");
    expect(structured["error_code"]).toBeUndefined();
    // 手机自己的字段照旧留着。
    expect(structured["battery_level_percent"]).toBe(99);
  });

  it("★ 未知工具是协议错误，且不会去碰转发层", async () => {
    const rig = await makeRig();
    let called = 0;
    const dispatched = await dispatchToolCall(
      loadConfig(rig.env),
      "read_any_file",
      {},
      async () => {
        called += 1;
        throw new Error("不该被调用");
      },
      async () => {
        called += 1;
        throw new Error("不该被调用");
      },
    );
    expect(called).toBe(0);
    expect(dispatched.result).toBeUndefined();
    expect(dispatched.protocolError?.code).toBe(-32602);
    const data = dispatched.protocolError?.data as Record<string, unknown>;
    expect(data["known_tools"]).toEqual([
      "phone_ping",
      "battery_get_status",
      "device_get_network",
      "device_get_storage",
      "hub_list_tools",
      "hub_call_tool",
    ]);
  });

  it("★ 未知参数被拒绝：battery_get_status 只认 device_id / timeout_ms / echo", async () => {
    const rig = await makeRig();
    let called = 0;
    const dispatched = await dispatchToolCall(
      loadConfig(rig.env),
      "battery_get_status",
      { device_id: TEST_DEVICE_ID, package_name: "com.example" },
      async () => {
        called += 1;
        throw new Error("不该被调用");
      },
      async () => {
        called += 1;
        throw new Error("不该被调用");
      },
    );
    expect(called).toBe(0);
    expect(dispatched.protocolError?.code).toBe(-32602);
    expect(dispatched.protocolError?.message).toContain("package_name");
  });

  it("★ timeout_ms 越界被拒绝（不静默夹紧：调用方必须知道自己的值没生效）", async () => {
    const rig = await makeRig();
    let called = 0;
    const dispatched = await dispatchToolCall(
      loadConfig(rig.env),
      "battery_get_status",
      { device_id: TEST_DEVICE_ID, timeout_ms: 5 },
      async () => {
        called += 1;
        throw new Error("不该被调用");
      },
      async () => {
        called += 1;
        throw new Error("不该被调用");
      },
    );
    expect(called).toBe(0);
    expect(dispatched.protocolError?.code).toBe(-32602);
  });

  it("★ 服务端禁用某个工具之后，调用它得到 not_permitted；且**先于设备解析**（不能用报错探测设备表）", async () => {
    const rig = await makeRig({ vars: { PHONE_DISABLED_TOOLS: "battery_get_status" } });
    let called = 0;
    const dispatched = await dispatchToolCall(
      loadConfig(rig.env),
      "battery_get_status",
      // device_id 故意用一个不存在的设备
      { device_id: "phone-that-does-not-exist" },
      async () => {
        called += 1;
        throw new Error("不该被调用");
      },
      async () => {
        called += 1;
        throw new Error("不该被调用");
      },
    );
    expect(called).toBe(0);
    const structured = dispatched.result?.structuredContent as Record<string, unknown>;
    // 关键：报的是"工具被禁用"，不是"设备不存在"。
    expect(structured["error_code"]).toBe("not_permitted");

    // 不禁用时，同一个请求回的是 unknown_device —— 证明上面不是因为设备恰好合法。
    const rig2 = await makeRig();
    const dispatched2 = await dispatchToolCall(
      loadConfig(rig2.env),
      "battery_get_status",
      { device_id: "phone-that-does-not-exist" },
      async () => {
        throw new Error("不该被调用");
      },
      async () => {
        throw new Error("不该被调用");
      },
    );
    const structured2 = dispatched2.result?.structuredContent as Record<string, unknown>;
    expect(structured2["error_code"]).toBe("unknown_device");
  });

  it("禁用名单只影响被禁的那个工具，ping 照旧", async () => {
    const rig = await makeRig({ vars: { PHONE_DISABLED_TOOLS: "battery_get_status" } });
    const dispatched = await dispatchToolCall(
      loadConfig(rig.env),
      "phone_ping",
      { device_id: TEST_DEVICE_ID },
      async () => ({
        ok: true,
        request_id: "p1",
        device_id: TEST_DEVICE_ID,
        connection_generation: 1,
        rtt_ms: 9,
        server_timestamp: 1,
        device_timestamp: 2,
      }),
    );
    expect(dispatched.result?.isError).toBeFalsy();
  });

  it("★ 已撤销设备：工具层只**报告** revokedDeviceId，不自己动手关连接", async () => {
    const rig = await makeRig({
      deviceTokens: [{ deviceId: TEST_DEVICE_ID, token: TEST_DEVICE_TOKEN, revokedAt: "2026-01-02T00:00:00Z" }],
    });
    let called = 0;
    const dispatched = await dispatchToolCall(
      loadConfig(rig.env),
      "battery_get_status",
      { device_id: TEST_DEVICE_ID },
      async () => {
        called += 1;
        throw new Error("不该被调用");
      },
      async () => {
        called += 1;
        throw new Error("不该被调用");
      },
    );
    expect(called).toBe(0);
    const structured = dispatched.result?.structuredContent as Record<string, unknown>;
    expect(structured["error_code"]).toBe("device_revoked");
    expect(dispatched.revokedDeviceId).toBe(TEST_DEVICE_ID);
  });

  it("echo 对所有工具都允许，并回填到结果里（Gate 1 兼容）", async () => {
    const rig = await makeRig();
    const dispatched = await dispatchToolCall(
      loadConfig(rig.env),
      "battery_get_status",
      { device_id: TEST_DEVICE_ID, echo: "trace-42" },
      async () => {
        throw new Error("不该被调用");
      },
      async () => okTool,
    );
    const structured = dispatched.result?.structuredContent as Record<string, unknown>;
    expect(structured["echo"]).toBe("trace-42");
  });
});

// ============================================================================
//  Gate 2 工作包 E —— battery_get_status 的工具层行为
// ============================================================================
//
//  Android 侧真正的读数逻辑在 JVM 单测里（`BatteryCapabilityTest`）；这里只盯
//  "手机把结果交给 Worker 之后，Worker 有没有原样、诚实地转达"。
// ============================================================================

describe("工具层 / battery_get_status", () => {
  it("读不到的字段以 null 原样透传，绝不会变成字符串 \"null\" 或整键消失", async () => {
    const rig = await makeRig();
    const nullTool: ToolOutcome = {
      ...okTool,
      result: {
        battery_level_percent: null,
        charging: null,
        charging_source: "unavailable",
        power_save_mode: null,
      },
    };

    const dispatched = await dispatchToolCall(
      loadConfig(rig.env),
      "battery_get_status",
      { device_id: TEST_DEVICE_ID },
      async () => {
        throw new Error("这个工具不该走 ping 通道");
      },
      async () => nullTool,
    );

    const structured = dispatched.result?.structuredContent as Record<string, unknown>;
    // JSON 里的 null 必须还是 null。"null" / undefined / 缺键都会让模型
    // 把"读不到"读成"电量是 0（或未知的某个值）"。
    expect(structured["battery_level_percent"]).toBeNull();
    expect(structured["charging"]).toBeNull();
    expect(structured["power_save_mode"]).toBeNull();
    expect(structured["charging_source"]).toBe("unavailable");
    expect(Object.prototype.hasOwnProperty.call(structured, "battery_level_percent")).toBe(true);
  });

  it("timeout_ms 取上界（60000）通过，且被原样传给 DO", async () => {
    const rig = await makeRig();
    let seenTimeout: number | undefined;
    const dispatched = await dispatchToolCall(
      loadConfig(rig.env),
      "battery_get_status",
      { device_id: TEST_DEVICE_ID, timeout_ms: 60_000 },
      async () => {
        throw new Error("不该被调用");
      },
      async (_deviceId, _toolName, _args, timeoutMs) => {
        seenTimeout = timeoutMs;
        return okTool;
      },
    );

    expect(seenTimeout).toBe(60_000);
    expect(dispatched.result?.isError).toBeFalsy();
  });

  it("能力执行失败时是 isError，且失败码与文案来自手机", async () => {
    const rig = await makeRig();
    const dispatched = await dispatchToolCall(
      loadConfig(rig.env),
      "battery_get_status",
      { device_id: TEST_DEVICE_ID },
      async () => {
        throw new Error("不该被调用");
      },
      async () => ({ ...failTool, error: { code: "tool_unavailable", message: "系统不提供电池信息" } }),
    );

    expect(dispatched.result?.isError).toBe(true);
    const structured = dispatched.result?.structuredContent as Record<string, unknown>;
    const error = structured["error"] as Record<string, unknown>;
    expect(error["code"]).toBe("tool_unavailable");
    expect(error["message"]).toBe("系统不提供电池信息");
    expect(structured["ok"]).toBe(false);
  });
});
