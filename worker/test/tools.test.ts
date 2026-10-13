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
  BATTERY_GET_STATUS_TOOL,
  DEVICE_GET_NETWORK_TOOL,
  DEVICE_GET_STORAGE_TOOL,
  dispatchToolCall,
  HUB_CALL_TOOL_TOOL,
  HUB_LIST_TOOLS_TOOL,
  parsePhonePingArgs,
  PHONE_PING_TOOL,
  pingOutcomeToResult,
  TOOL_REGISTRY,
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
  it("注册表是显式白名单：Gate 3 之后是本机四工具 + 两个聚合入口，一个不多", () => {
    // 这条断言刻意用 `toEqual` 整表比对而不是 `toContain`：
    // "多了一个没人审过的工具"必须让测试变红，而不是悄悄通过。
    // Gate 1 时期这里是 `["phone_ping"]`；Gate 2 加了 battery_get_status；
    // Gate 2.5A+B 加了 device_get_network 与 device_get_storage；
    // Gate 3 在**末尾**追加两个聚合入口（前四个的顺序保持不变）。
    expect(toolNames()).toEqual([
      "phone_ping",
      "battery_get_status",
      "device_get_network",
      "device_get_storage",
      "hub_list_tools",
      "hub_call_tool",
    ]);
    expect(PHONE_PING_TOOL.name).toBe("phone_ping");
    expect(BATTERY_GET_STATUS_TOOL.name).toBe("battery_get_status");
    expect(DEVICE_GET_NETWORK_TOOL.name).toBe("device_get_network");
    expect(DEVICE_GET_STORAGE_TOOL.name).toBe("device_get_storage");
    expect(HUB_LIST_TOOLS_TOOL.name).toBe("hub_list_tools");
    expect(HUB_CALL_TOOL_TOOL.name).toBe("hub_call_tool");
  });

  it("两个新工具走 tool 通道、零能力参数、只读幂等", () => {
    for (const tool of [DEVICE_GET_NETWORK_TOOL, DEVICE_GET_STORAGE_TOOL]) {
      expect(tool.transport).toBe("tool");
      // 和 battery_get_status 一样：没有可调项，多一个键就多一条要审的路径。
      expect(tool.allowedArgs).toEqual([]);
      expect(tool.annotations?.["readOnlyHint"]).toBe(true);
      expect(tool.annotations?.["idempotentHint"]).toBe(true);
      expect(tool.inputSchema["additionalProperties"]).toBe(false);
      expect(Object.keys(tool.inputSchema["properties"] as Record<string, unknown>).sort()).toEqual([
        "device_id",
        "timeout_ms",
      ]);
    }
  });

  it("每个工具都声明了自己的通道，且通道只有 ping / tool / hub_catalog / hub_tool 四种", () => {
    // 通道决定这个工具走 legacy phone_ping、Gate 2 的 tool_request，
    // 还是 Gate 3 的聚合命令名。写错一个字符就会让某个工具静默走错路线，
    // 所以这里逐条断言。
    expect(PHONE_PING_TOOL.transport).toBe("ping");
    expect(BATTERY_GET_STATUS_TOOL.transport).toBe("tool");
    expect(HUB_LIST_TOOLS_TOOL.transport).toBe("hub_catalog");
    expect(HUB_CALL_TOOL_TOOL.transport).toBe("hub_tool");
    for (const tool of TOOL_REGISTRY) {
      expect(["ping", "tool", "hub_catalog", "hub_tool"]).toContain(tool.transport);
    }
  });

  it("聚合入口的 annotations 是诚实的：列目录只读，调用第三方工具**不是**只读", () => {
    // hub_list_tools 只查目录 —— 只读、幂等。
    expect(HUB_LIST_TOOLS_TOOL.annotations?.["readOnlyHint"]).toBe(true);
    expect(HUB_LIST_TOOLS_TOOL.annotations?.["idempotentHint"]).toBe(true);
    // hub_call_tool 会执行第三方服务器上的东西，那边有没有副作用 Hub 不知道。
    // 这里必须如实标 false，不能给模型"随便调都安全"的暗示。
    expect(HUB_CALL_TOOL_TOOL.annotations?.["readOnlyHint"]).toBe(false);
    expect(HUB_CALL_TOOL_TOOL.annotations?.["idempotentHint"]).toBe(false);
  });

  it("每个工具都禁止多余字段，且 allowedArgs 里的名字都在 inputSchema 里出现过", () => {
    for (const tool of TOOL_REGISTRY) {
      expect(tool.inputSchema["type"]).toBe("object");
      expect(tool.inputSchema["additionalProperties"]).toBe(false);
      const props = Object.keys(tool.inputSchema["properties"] as Record<string, unknown>);
      // 白名单里的键必须真的在 schema 里 —— 否则会出现"文档里没有、但代码接受"
      // 的隐藏参数，那正是最难审的一类。
      for (const key of tool.allowedArgs) expect(props).toContain(key);
    }
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
