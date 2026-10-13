// ============================================================================
//  Gate 3 —— 聚合入口的完整链路（MCP HTTP → MCP_SESSION DO → PHONE_SESSION DO
//                      → 真实 WebSocket → 模拟手机 → 原路返回）
// ============================================================================
//
//  这个文件回答的是"闭环到底通没通"：不是问 hub.ts 的映射对不对（那在
//  hub-tools.test.ts 里），而是问**一条真正的 MCP 调用**能不能穿过现有的
//  Worker → DO → WebSocket 通道，落到一台手机替身上，再把结果带回来。
//
//  手机替身（tools/mock-phone/client.ts）在 Gate 3 里学会了两个聚合保留命令名：
//  `hub_list_tools` / `hub_call_tool`。它**故意**在目录里混了一个未授权工具，
//  这样"未授权工具的详情不外传"这条在链路级也可断言。
//
//  ⚠ 它仍然是 Node 上的 Workers 运行时替身 + 手机替身：
//    · 这**不是**真实 ChatGPT 调用；
//    · 这**不是**真实 Android 手机（真机验收见交付报告）。
// ============================================================================

import { describe, expect, it } from "vitest";
import { handleMcpRequest, MCP_SESSION_HEADER } from "../src/mcp/http";
import { connectMockPhone, makeRig, phoneStatus, TEST_DEVICE_ID, TEST_MCP_TOKEN, type TestRig } from "./harness/env";

const MCP_URL = "https://android-mcp-hub.test/mcp";

async function postMcp(rig: TestRig, body: unknown, sessionId?: string): Promise<Response> {
  const headers = new Headers();
  headers.set("Accept", "application/json, text/event-stream");
  headers.set("Content-Type", "application/json");
  headers.set("Authorization", `Bearer ${TEST_MCP_TOKEN}`);
  if (sessionId !== undefined) headers.set(MCP_SESSION_HEADER, sessionId);
  return handleMcpRequest(
    new Request(MCP_URL, { method: "POST", headers, body: JSON.stringify(body) }),
    rig.env,
  );
}

async function readRpc(response: Response): Promise<Record<string, unknown>> {
  const text = await response.text();
  if (response.headers.get("Content-Type")?.includes("text/event-stream")) {
    const dataLine = text.split("\n").find((l) => l.startsWith("data: "));
    return JSON.parse(dataLine!.slice("data: ".length)) as Record<string, unknown>;
  }
  return JSON.parse(text) as Record<string, unknown>;
}

async function initSession(rig: TestRig): Promise<string> {
  const response = await postMcp(rig, {
    jsonrpc: "2.0",
    id: 1,
    method: "initialize",
    params: { protocolVersion: "2025-06-18", capabilities: {}, clientInfo: { name: "gate3-test", version: "0.0.1" } },
  });
  expect(response.status).toBe(200);
  const sessionId = response.headers.get(MCP_SESSION_HEADER);
  expect(sessionId).toBeTruthy();
  return sessionId!;
}

/** 调一次 tools/call，返回工具结果本身（result 字段）。 */
async function callTool(
  rig: TestRig,
  sessionId: string,
  name: string,
  args: Record<string, unknown>,
): Promise<Record<string, unknown>> {
  const response = await postMcp(
    rig,
    { jsonrpc: "2.0", id: 7, method: "tools/call", params: { name, arguments: args } },
    sessionId,
  );
  expect(response.status).toBe(200);
  const body = await readRpc(response);
  expect(body["error"]).toBeUndefined();
  return body["result"] as Record<string, unknown>;
}

function textOf(result: Record<string, unknown>): string {
  return (result["content"] as Array<{ text: string }>).map((c) => c.text).join("\n");
}

describe("Gate 3 / 聚合入口全链路", () => {
  it("★ hub_list_tools 穿过整条链路拿到真实目录，且未授权工具只计数、不外传描述", async () => {
    const rig = await makeRig();
    const { phone } = await connectMockPhone(rig, TEST_DEVICE_ID);
    const sessionId = await initSession(rig);

    const result = await callTool(rig, sessionId, "hub_list_tools", { device_id: TEST_DEVICE_ID });
    expect(result["isError"]).toBeFalsy();

    const structured = result["structuredContent"] as Record<string, unknown>;
    expect(structured["ok"]).toBe(true);
    expect(structured["device_id"]).toBe(TEST_DEVICE_ID);
    expect(structured["tool_name"]).toBe("hub_list_tools");
    expect(typeof structured["rtt_ms"]).toBe("number");
    expect(typeof structured["connection_generation"]).toBe("number");

    const tools = structured["tools"] as Array<Record<string, unknown>>;
    expect(tools).toHaveLength(1);
    expect(tools[0]?.["tool_id"]).toBe("mcp:mock_server:echo_text");
    expect(tools[0]?.["authorization_state"]).toBe("authorized");
    expect(tools[0]?.["availability"]).toBe("online");
    expect(structured["other_tools_count"]).toBe(1);

    // 未授权那个工具的名字与描述一个字节都不许出现在返回里。
    const wire = JSON.stringify(result);
    expect(wire).not.toContain("write_note");
    expect(wire).not.toContain("写一条笔记");

    // 手机上确实收到过一条 tool_request，命令名是聚合保留名。
    const frames = phone.received.map((raw) => JSON.parse(raw) as Record<string, unknown>);
    const toolRequests = frames.filter((f) => f["type"] === "tool_request");
    expect(toolRequests).toHaveLength(1);
    expect(toolRequests[0]?.["tool_name"]).toBe("hub_list_tools");

    const status = await phoneStatus(rig, TEST_DEVICE_ID);
    expect(status["pending_requests"]).toBe(0);
  });

  it("★ hub_call_tool 按 (server_id, tool_name) 转发，第三方结果原样带回", async () => {
    const rig = await makeRig();
    const { phone } = await connectMockPhone(rig, TEST_DEVICE_ID);
    const sessionId = await initSession(rig);

    const result = await callTool(rig, sessionId, "hub_call_tool", {
      device_id: TEST_DEVICE_ID,
      server_id: "mock_server",
      tool_name: "echo_text",
      arguments: { text: "hello-gate3" },
    });
    expect(result["isError"]).toBeFalsy();

    const structured = result["structuredContent"] as Record<string, unknown>;
    expect(structured["ok"]).toBe(true);
    expect(structured["server_id"]).toBe("mock_server");
    expect(structured["tool_name"]).toBe("echo_text");
    expect(structured["tool_id"]).toBe("mcp:mock_server:echo_text");
    expect(structured["mcp_is_error"]).toBe(false);
    expect(textOf(result)).toContain("mock-mcp echo: hello-gate3");

    // 手机上收到的那一帧：命令名是保留名，第三方身份在 arguments 里。
    const toolRequests = phone.received
      .map((raw) => JSON.parse(raw) as Record<string, unknown>)
      .filter((f) => f["type"] === "tool_request");
    expect(toolRequests).toHaveLength(1);
    expect(toolRequests[0]?.["tool_name"]).toBe("hub_call_tool");
    expect(toolRequests[0]?.["arguments"]).toEqual({
      server_id: "mock_server",
      tool_name: "echo_text",
      arguments: { text: "hello-gate3" },
    });
  });

  it("手机不认识聚合命令（旧版 App）时如实失败，**不伪造空目录**", async () => {
    const rig = await makeRig();
    await connectMockPhone(rig, TEST_DEVICE_ID, "normal", { toolBehavior: "unsupported" });
    const sessionId = await initSession(rig);

    const result = await callTool(rig, sessionId, "hub_list_tools", { device_id: TEST_DEVICE_ID });
    expect(result["isError"]).toBe(true);
    const structured = result["structuredContent"] as Record<string, unknown>;
    expect(structured["ok"]).toBe(false);
    expect(structured["error_code"]).toBe("unsupported_tool");
    // 没有 tools 字段 = 没有目录，而不是"目录是空的"。
    expect(structured["tools"]).toBeUndefined();
  });

  it("手机离线时 hub_call_tool 如实报 phone_offline", async () => {
    const rig = await makeRig();
    const sessionId = await initSession(rig);

    const result = await callTool(rig, sessionId, "hub_call_tool", {
      device_id: TEST_DEVICE_ID,
      server_id: "mock_server",
      tool_name: "echo_text",
    });
    expect(result["isError"]).toBe(true);
    const structured = result["structuredContent"] as Record<string, unknown>;
    expect(structured["error_code"]).toBe("phone_offline");
    // 失败结果**也要**带目标身份：`hub_call_tool` 的 outputSchema 把 server_id /
    // tool_id 声明成 required，少了它们官方 SDK 会判整次响应不合法
    // （客户端 outputSchema 校验 → JSON-RPC -32602），失败原因就看不见了。
    expect(structured["server_id"]).toBe("mock_server");
    expect(structured["tool_id"]).toBe("mcp:mock_server:echo_text");
  });

  it("手机不回 tool_result 时超时结算，且不把在途请求留在 pending 里", async () => {
    const rig = await makeRig();
    await connectMockPhone(rig, TEST_DEVICE_ID, "normal", { toolBehavior: "no-response" });
    const sessionId = await initSession(rig);

    const result = await callTool(rig, sessionId, "hub_list_tools", {
      device_id: TEST_DEVICE_ID,
      timeout_ms: 60,
    });
    expect(result["isError"]).toBe(true);
    const structured = result["structuredContent"] as Record<string, unknown>;
    expect(structured["error_code"]).toBe("phone_timeout");

    const status = await phoneStatus(rig, TEST_DEVICE_ID);
    expect(status["pending_requests"]).toBe(0);
    expect(status["pending_mirror"]).toBe(0);
  });

  it("第三方工具执行失败（toolBehavior=error）以 Hub 错误码 tool_failed 传播", async () => {
    const rig = await makeRig();
    await connectMockPhone(rig, TEST_DEVICE_ID, "normal", { toolBehavior: "error" });
    const sessionId = await initSession(rig);

    const result = await callTool(rig, sessionId, "hub_call_tool", {
      device_id: TEST_DEVICE_ID,
      server_id: "mock_server",
      tool_name: "echo_text",
    });
    expect(result["isError"]).toBe(true);
    const structured = result["structuredContent"] as Record<string, unknown>;
    expect(structured["error_code"]).toBe("tool_failed");
  });

  it("参数不合法在**协议层**就被拒绝：一个字节都不发到手机", async () => {
    const rig = await makeRig();
    const { phone } = await connectMockPhone(rig, TEST_DEVICE_ID);
    const sessionId = await initSession(rig);

    const response = await postMcp(
      rig,
      {
        jsonrpc: "2.0",
        id: 9,
        method: "tools/call",
        // server_id 里带斜杠 + 一个多余的 url 字段：两处都该被拒。
        params: { name: "hub_call_tool", arguments: { server_id: "a/b", tool_name: "echo_text", url: "https://x" } },
      },
      sessionId,
    );
    const body = await readRpc(response);
    expect((body["error"] as Record<string, unknown>)["code"]).toBe(-32602);

    const toolRequests = phone.received
      .map((raw) => JSON.parse(raw) as Record<string, unknown>)
      .filter((f) => f["type"] === "tool_request");
    expect(toolRequests).toHaveLength(0);
  });
});
