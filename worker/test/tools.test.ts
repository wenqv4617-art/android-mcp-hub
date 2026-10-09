// ============================================================================
//  工具层 —— 参数校验 + "绝不在 Worker 内伪造 pong"
// ============================================================================
//
//  这个文件里最重要的一条不是参数校验，而是最后那组"转发证明"：
//  工具层拿到的 callPhonePing 是**注入**的，所以可以让它在测试里直接抛错，
//  证明成功路径除了走转发之外没有别的出路。任务书写得很清楚：
//  「不得在 Worker 内直接伪造 pong」—— 这条不能只靠代码评审相信。
// ============================================================================

import { describe, expect, it } from "vitest";
import {
  dispatchToolCall,
  parsePhonePingArgs,
  PHONE_PING_TOOL,
  pingOutcomeToResult,
  ToolArgumentError,
  toolNames,
} from "../src/mcp/tools";
import type { PingOutcome } from "../src/phone/phone-session";
import { loadConfig } from "../src/config";
import { makeRig, TEST_DEVICE_ID } from "./harness/env";

const okOutcome: PingOutcome = {
  ok: true,
  request_id: "req-1",
  device_id: TEST_DEVICE_ID,
  connection_generation: 1,
  rtt_ms: 12,
  server_timestamp: 1_700_000_000_000,
  device_timestamp: 1_700_000_000_012,
};

const failOutcome: PingOutcome = {
  ok: false,
  request_id: "req-2",
  device_id: TEST_DEVICE_ID,
  error: { code: "phone_offline", message: "该设备当前没有已建立的连接" },
};

describe("工具层 / 注册表", () => {
  it("只暴露 phone_ping 一个工具", () => {
    expect(toolNames()).toEqual(["phone_ping"]);
    expect(PHONE_PING_TOOL.name).toBe("phone_ping");
  });

  it("inputSchema 是 object 且禁止多余字段", () => {
    expect(PHONE_PING_TOOL.inputSchema["type"]).toBe("object");
    expect(PHONE_PING_TOOL.inputSchema["additionalProperties"]).toBe(false);
  });

  it("工具声明为只读、幂等（phone_ping 不改任何东西）", () => {
    expect(PHONE_PING_TOOL.annotations?.["readOnlyHint"]).toBe(true);
    expect(PHONE_PING_TOOL.annotations?.["idempotentHint"]).toBe(true);
  });
});

describe("工具层 / 参数解析", () => {
  it("空参数是合法的（device_id 可以推断）", () => {
    expect(parsePhonePingArgs({})).toEqual({});
  });

  it("接受合法字段", () => {
    expect(parsePhonePingArgs({ device_id: "p1", timeout_ms: 100, echo: "hi" })).toEqual({
      device_id: "p1",
      timeout_ms: 100,
      echo: "hi",
    });
  });

  it("多余字段被拒绝", () => {
    expect(() => parsePhonePingArgs({ device_id: "p1", evil: 1 })).toThrow(ToolArgumentError);
  });

  it("类型不对被拒绝", () => {
    expect(() => parsePhonePingArgs({ device_id: 5 })).toThrow(ToolArgumentError);
    expect(() => parsePhonePingArgs({ echo: 5 })).toThrow(ToolArgumentError);
    expect(() => parsePhonePingArgs({ timeout_ms: "100" })).toThrow(ToolArgumentError);
  });

  it("timeout_ms 越界被拒绝", () => {
    expect(() => parsePhonePingArgs({ timeout_ms: 49 })).toThrow(ToolArgumentError);
    expect(() => parsePhonePingArgs({ timeout_ms: 60_001 })).toThrow(ToolArgumentError);
    expect(() => parsePhonePingArgs({ timeout_ms: 1.5 })).toThrow(ToolArgumentError);
    expect(parsePhonePingArgs({ timeout_ms: 50 })["timeout_ms"]).toBe(50);
    expect(parsePhonePingArgs({ timeout_ms: 60_000 })["timeout_ms"]).toBe(60_000);
  });

  it("arguments 不是对象被拒绝", () => {
    expect(() => parsePhonePingArgs("nope")).toThrow(ToolArgumentError);
    expect(() => parsePhonePingArgs([1, 2])).toThrow(ToolArgumentError);
  });

  it("arguments 为 null / undefined 视作没带参数（不是错误）", () => {
    expect(parsePhonePingArgs(null)).toEqual({});
    expect(parsePhonePingArgs(undefined)).toEqual({});
  });
});

describe("工具层 / 结果映射", () => {
  it("成功的 ping 映射成 isError 缺省的结果，并带上 RTT", () => {
    const result = pingOutcomeToResult(okOutcome, "hi");
    expect(result.isError).toBeFalsy();
    const structured = result.structuredContent as Record<string, unknown>;
    expect(structured["ok"]).toBe(true);
    expect(structured["rtt_ms"]).toBe(12);
    expect(structured["echo"]).toBe("hi");
    expect(result.content[0]!.text).toContain("12");
  });

  it("失败的 ping 用 isError=true 表达，而不是协议错误", () => {
    const result = pingOutcomeToResult(failOutcome);
    expect(result.isError).toBe(true);
    const structured = result.structuredContent as Record<string, unknown>;
    expect(structured["ok"]).toBe(false);
    expect((structured["error"] as Record<string, unknown>)["code"]).toBe("phone_offline");
  });
});

describe("工具层 / 转发证明", () => {
  it("device_id 被原样透传给转发层", async () => {
    const rig = await makeRig();
    const seen: Array<{ deviceId: string; timeoutMs: number | undefined }> = [];
    const result = await dispatchToolCall(
      loadConfig(rig.env),
      "phone_ping",
      { device_id: TEST_DEVICE_ID, timeout_ms: 250 },
      async (deviceId, timeoutMs) => {
        seen.push({ deviceId, timeoutMs });
        return okOutcome;
      },
    );
    expect(seen).toEqual([{ deviceId: TEST_DEVICE_ID, timeoutMs: 250 }]);
    expect(result.result?.isError).toBeFalsy();
  });

  it("★ 不认识的 device_id 直接失败，且**根本不会调用转发层**（防止拿 device_id 探测设备）", async () => {
    const rig = await makeRig();
    let called = 0;
    const dispatched = await dispatchToolCall(
      loadConfig(rig.env),
      "phone_ping",
      { device_id: "phone-that-does-not-exist" },
      async () => {
        called += 1;
        return okOutcome;
      },
    );
    expect(called).toBe(0);
    expect(dispatched.result?.isError).toBe(true);
    const structured = dispatched.result?.structuredContent as Record<string, unknown>;
    expect(structured["error_code"]).toBe("unknown_device");
  });

  it("未知工具是协议错误（-32602），不是工具执行失败", async () => {
    const rig = await makeRig();
    const dispatched = await dispatchToolCall(loadConfig(rig.env), "reboot_phone", {}, async () => okOutcome);
    expect(dispatched.result).toBeUndefined();
    expect(dispatched.protocolError?.code).toBe(-32602);
  });

  it("参数非法时**根本不会调用转发层**", async () => {
    const rig = await makeRig();
    let called = 0;
    const dispatched = await dispatchToolCall(
      loadConfig(rig.env),
      "phone_ping",
      { device_id: 123 },
      async () => {
        called += 1;
        return okOutcome;
      },
    );
    expect(called).toBe(0);
    expect(dispatched.protocolError?.code).toBe(-32602);
  });

  it("★ 转发层抛错时结果是失败，绝不退化成成功", async () => {
    const rig = await makeRig();
    const dispatched = await dispatchToolCall(loadConfig(rig.env), "phone_ping", { device_id: TEST_DEVICE_ID }, async () => {
      throw new Error("网络炸了");
    });
    expect(dispatched.result?.isError).toBe(true);
    const structured = dispatched.result?.structuredContent as Record<string, unknown>;
    expect(structured["ok"]).toBe(false);
  });

  it("★ 转发层不返回 pong 时，工具层不可能自己编一个 pong 出来", async () => {
    const rig = await makeRig();
    // 一个永远不 resolve 的转发层：如果工具层有任何"本地兜底"路径，
    // 这个用例就会在超时后拿到成功结果。
    const never = new Promise<PingOutcome>(() => {});
    const raced = await Promise.race([
      dispatchToolCall(loadConfig(rig.env), "phone_ping", { device_id: TEST_DEVICE_ID }, () => never).then(
        () => "returned",
      ),
      new Promise((r) => setTimeout(() => r("still-waiting"), 120)),
    ]);
    expect(raced).toBe("still-waiting");
  });

  it("★ 转发层返回失败时结果就是失败——工具层不会把它翻成成功", async () => {
    const rig = await makeRig();
    const dispatched = await dispatchToolCall(loadConfig(rig.env), "phone_ping", { device_id: TEST_DEVICE_ID }, async () => failOutcome);
    expect(dispatched.result?.isError).toBe(true);
    const structured = dispatched.result?.structuredContent as Record<string, unknown>;
    expect(structured["ok"]).toBe(false);
    expect((structured["error"] as Record<string, unknown>)["code"]).toBe("phone_offline");
  });
});
