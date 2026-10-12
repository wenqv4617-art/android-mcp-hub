// ============================================================================
//  Gate 2.5A+B —— device_get_network / device_get_storage 的工具层行为
// ============================================================================
//
//  这两个能力的**读取逻辑**在 Android 侧的 JVM 单测里
//  （`NetworkCapabilityTest` / `StorageCapabilityTest`）。这里只盯三件事：
//    1. 它们在显式白名单里、能被 tools/list 看见、没有偷偷多出来的参数；
//    2. 成功结果原样摊平进 structuredContent（Worker 不加工、不补字段）；
//    3. 失败时如实返回错误码，**绝不在 Worker 内编一个成功结果**。
//
//  ⚠ 这里跑的是 Node 上的 Workers 运行时替身，不是真实 workerd，
//    也没有真实手机 —— 真实设备联调属于真机验证清单，不在这里冒充。
// ============================================================================

import { describe, expect, it } from "vitest";
import {
  DEVICE_GET_NETWORK_TOOL,
  DEVICE_GET_STORAGE_TOOL,
  dispatchToolCall,
  findTool,
  toolNames,
} from "../src/mcp/tools";
import type { ToolOutcome } from "../src/phone/phone-session";
import { loadConfig } from "../src/config";
import { makeRig, TEST_DEVICE_ID } from "./harness/env";

const networkOutcome: ToolOutcome = {
  ok: true,
  request_id: "net-req-1",
  device_id: TEST_DEVICE_ID,
  tool_name: "device_get_network",
  result: {
    connected: true,
    validated: true,
    transport: "wifi",
    transports: ["wifi"],
    metered: false,
    internet_capable: true,
  },
  rtt_ms: 21,
  server_timestamp: 1_700_000_000_000,
  connection_generation: 3,
};

const storageOutcome: ToolOutcome = {
  ok: true,
  request_id: "sto-req-1",
  device_id: TEST_DEVICE_ID,
  tool_name: "device_get_storage",
  result: {
    volume: "internal_data",
    total_bytes: 128_000_000_000,
    available_bytes: 48_000_000_000,
    used_bytes: 80_000_000_000,
    usage_percent: 62.5,
  },
  rtt_ms: 19,
  server_timestamp: 1_700_000_000_000,
  connection_generation: 3,
};

const pingInstead = async () => {
  throw new Error("这个工具不该走 ping 通道");
};

describe("工具层 / device_get_network", () => {
  it("注册在显式白名单里，tools/list 能看见", () => {
    expect(toolNames()).toContain("device_get_network");
    const tool = findTool("device_get_network");
    expect(tool).toBe(DEVICE_GET_NETWORK_TOOL);
    expect(tool?.description.length).toBeGreaterThan(20);
  });

  it("成功结果被原样摊平进 structuredContent", async () => {
    const rig = await makeRig();
    let seenToolName: string | undefined;

    const dispatched = await dispatchToolCall(
      loadConfig(rig.env),
      "device_get_network",
      { device_id: TEST_DEVICE_ID },
      pingInstead,
      async (_deviceId, toolName) => {
        seenToolName = toolName;
        return networkOutcome;
      },
    );

    expect(seenToolName).toBe("device_get_network");
    expect(dispatched.result?.isError).toBeFalsy();
    const structured = dispatched.result?.structuredContent as Record<string, unknown>;
    expect(structured["ok"]).toBe(true);
    expect(structured["tool_name"]).toBe("device_get_network");
    expect(structured["connected"]).toBe(true);
    expect(structured["validated"]).toBe(true);
    expect(structured["transport"]).toBe("wifi");
    expect(structured["transports"]).toEqual(["wifi"]);
    expect(structured["metered"]).toBe(false);
    expect(structured["internet_capable"]).toBe(true);
  });

  it("系统没报告的字段保持 null，而不会变成 false", async () => {
    const rig = await makeRig();
    const dispatched = await dispatchToolCall(
      loadConfig(rig.env),
      "device_get_network",
      { device_id: TEST_DEVICE_ID },
      pingInstead,
      async () => ({
        ...networkOutcome,
        result: {
          connected: false,
          validated: null,
          transport: "none",
          transports: [],
          metered: null,
          internet_capable: null,
        },
      }),
    );

    const structured = dispatched.result?.structuredContent as Record<string, unknown>;
    // "不知道" 与 "不是" 必须分得开：把 null 写成 false 就是编数据。
    expect(structured["validated"]).toBeNull();
    expect(structured["metered"]).toBeNull();
    expect(structured["internet_capable"]).toBeNull();
    expect(structured["connected"]).toBe(false);
    expect(structured["transport"]).toBe("none");
  });

  it("手机离线时如实失败，不伪造一个网络状态", async () => {
    const rig = await makeRig();
    const dispatched = await dispatchToolCall(
      loadConfig(rig.env),
      "device_get_network",
      { device_id: TEST_DEVICE_ID },
      pingInstead,
      async () => ({
        ...networkOutcome,
        ok: false,
        result: undefined,
        error: { code: "phone_offline", message: "该设备当前没有已建立的连接" },
      }),
    );

    expect(dispatched.result?.isError).toBe(true);
    const structured = dispatched.result?.structuredContent as Record<string, unknown>;
    expect(structured["ok"]).toBe(false);
    const error = structured["error"] as Record<string, unknown>;
    expect(error["code"]).toBe("phone_offline");
  });

  it("非法参数被拒绝，且不会去问手机", async () => {
    const rig = await makeRig();
    let called = 0;

    const dispatched = await dispatchToolCall(
      loadConfig(rig.env),
      "device_get_network",
      { device_id: TEST_DEVICE_ID, ssid: "家里的 Wi-Fi" },
      pingInstead,
      async () => {
        called += 1;
        return networkOutcome;
      },
    );

    expect(called).toBe(0);
    expect(dispatched.protocolError?.code).toBe(-32602);
  });

  it("服务端禁用之后得到 not_permitted，且不转发", async () => {
    const rig = await makeRig({ vars: { PHONE_DISABLED_TOOLS: "device_get_network" } });
    let called = 0;

    const dispatched = await dispatchToolCall(
      loadConfig(rig.env),
      "device_get_network",
      { device_id: TEST_DEVICE_ID },
      pingInstead,
      async () => {
        called += 1;
        return networkOutcome;
      },
    );

    expect(called).toBe(0);
    const structured = dispatched.result?.structuredContent as Record<string, unknown>;
    expect(structured["error_code"]).toBe("not_permitted");
  });

  it("声明了手机侧会返回的全部字段（契约一致性）", () => {
    const properties = DEVICE_GET_NETWORK_TOOL.outputSchema?.["properties"] as Record<string, unknown>;
    for (const key of [
      "ok",
      "request_id",
      "device_id",
      "tool_name",
      "rtt_ms",
      "server_timestamp",
      "connection_generation",
      "connected",
      "validated",
      "transport",
      "transports",
      "metered",
      "internet_capable",
      "error_code",
      "error_message",
    ]) {
      expect(Object.keys(properties)).toContain(key);
    }
  });
});

describe("工具层 / device_get_storage", () => {
  it("注册在显式白名单里，tools/list 能看见", () => {
    expect(toolNames()).toContain("device_get_storage");
    expect(findTool("device_get_storage")).toBe(DEVICE_GET_STORAGE_TOOL);
  });

  it("成功结果被原样摊平进 structuredContent", async () => {
    const rig = await makeRig();
    const dispatched = await dispatchToolCall(
      loadConfig(rig.env),
      "device_get_storage",
      { device_id: TEST_DEVICE_ID },
      pingInstead,
      async (_deviceId, toolName) => {
        expect(toolName).toBe("device_get_storage");
        return storageOutcome;
      },
    );

    expect(dispatched.result?.isError).toBeFalsy();
    const structured = dispatched.result?.structuredContent as Record<string, unknown>;
    expect(structured["tool_name"]).toBe("device_get_storage");
    expect(structured["volume"]).toBe("internal_data");
    expect(structured["total_bytes"]).toBe(128_000_000_000);
    expect(structured["available_bytes"]).toBe(48_000_000_000);
    expect(structured["used_bytes"]).toBe(80_000_000_000);
    expect(structured["usage_percent"]).toBe(62.5);
    // used = total - available 这条关系在转发之后仍然成立（Worker 不重算也不改）。
    expect(Number(structured["used_bytes"])).toBe(
      Number(structured["total_bytes"]) - Number(structured["available_bytes"]),
    );
  });

  it("容量读不到时如实失败，不返回一串 0", async () => {
    const rig = await makeRig();
    const dispatched = await dispatchToolCall(
      loadConfig(rig.env),
      "device_get_storage",
      { device_id: TEST_DEVICE_ID },
      pingInstead,
      async () => ({
        ...storageOutcome,
        ok: false,
        result: undefined,
        error: { code: "tool_unavailable", message: "存储卷不可用：系统报告的容量为 0" },
      }),
    );

    expect(dispatched.result?.isError).toBe(true);
    const structured = dispatched.result?.structuredContent as Record<string, unknown>;
    const error = structured["error"] as Record<string, unknown>;
    expect(error["code"]).toBe("tool_unavailable");
    // 失败时不该出现任何数字字段，免得被读成"手机是空的"。
    expect(structured["total_bytes"]).toBeUndefined();
  });

  it("非法参数被拒绝，且不会去问手机", async () => {
    const rig = await makeRig();
    let called = 0;

    const dispatched = await dispatchToolCall(
      loadConfig(rig.env),
      "device_get_storage",
      { device_id: TEST_DEVICE_ID, path: "/sdcard/DCIM" },
      pingInstead,
      async () => {
        called += 1;
        return storageOutcome;
      },
    );

    expect(called).toBe(0);
    expect(dispatched.protocolError?.code).toBe(-32602);
    // 参数错误里不应该把用户给的值回显出去（这属于"回显对端输入"的老坑）。
    expect(JSON.stringify(dispatched.protocolError)).not.toContain("/sdcard/DCIM");
  });

  it("声明了手机侧会返回的全部字段（契约一致性）", () => {
    const properties = DEVICE_GET_STORAGE_TOOL.outputSchema?.["properties"] as Record<string, unknown>;
    for (const key of [
      "ok",
      "request_id",
      "device_id",
      "tool_name",
      "rtt_ms",
      "server_timestamp",
      "connection_generation",
      "volume",
      "total_bytes",
      "available_bytes",
      "used_bytes",
      "usage_percent",
      "error_code",
      "error_message",
    ]) {
      expect(Object.keys(properties)).toContain(key);
    }
  });

  it("两个新工具都不会出现在其它通道上（transport 必须是 tool）", () => {
    expect(DEVICE_GET_NETWORK_TOOL.transport).toBe("tool");
    expect(DEVICE_GET_STORAGE_TOOL.transport).toBe("tool");
    expect(DEVICE_GET_NETWORK_TOOL.allowedArgs).toEqual([]);
    expect(DEVICE_GET_STORAGE_TOOL.allowedArgs).toEqual([]);
  });
});
