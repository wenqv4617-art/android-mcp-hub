// ============================================================================
//  MCP Streamable HTTP 入口 —— 协议一致性测试
// ============================================================================
//
//  对应任务书「七、测试要求 / Worker / MCP」：
//
//    - initialize 成功                    → "initialize"
//    - tools/list 返回 phone_ping          → "tools/list"
//    - tools/call 正确转发                 → "tools/call"
//    - JSON-RPC ID 正确对应                → 每个用例都断言 id 原样回
//    - 不支持的方法返回规范错误            → "不支持的方法"
//    - 无效参数被拒绝                      → "无效参数"
//    - 未认证请求被拒绝                    → "认证"
//
//  ⚠ 这里的 tools/call **走的是真实的转发链路**：
//       handleMcpRequest → env.PHONE_SESSION → PhoneSession DO → WebSocket → 模拟手机
//     Worker 里没有任何地方能凭空造出 pong。有一条用例专门守着这件事（见"转发链路"）。
//
//  ⚠ 跑在 Node 上的 Workers 运行时替身里，不是真实 workerd。见 TEST_REPORT.md。
// ============================================================================

import { describe, expect, it } from "vitest";
import { handleMcpRequest, MCP_PROTOCOL_HEADER, MCP_SESSION_HEADER } from "../src/mcp/http";
import {
  connectMockPhone,
  makeRig,
  TEST_DEVICE_ID,
  TEST_MCP_TOKEN,
  type TestRig,
} from "./harness/env";

const MCP_URL = "https://android-mcp-hub.test/mcp";

interface PostOptions {
  token?: string | null;
  sessionId?: string | null;
  protocolVersion?: string | null;
  accept?: string | null;
  contentType?: string | null;
  method?: string;
}

/** 直接打 handleMcpRequest —— 跳过 index.ts 的路由层，专测 MCP 协议本身。 */
async function postMcp(rig: TestRig, body: unknown, opts: PostOptions = {}): Promise<Response> {
  const headers = new Headers();
  headers.set("Accept", opts.accept ?? "application/json, text/event-stream");
  headers.set("Content-Type", opts.contentType ?? "application/json");
  if (opts.token !== null) headers.set("Authorization", `Bearer ${opts.token ?? TEST_MCP_TOKEN}`);
  if (opts.sessionId) headers.set(MCP_SESSION_HEADER, opts.sessionId);
  if (opts.protocolVersion) headers.set(MCP_PROTOCOL_HEADER, opts.protocolVersion);

  const request = new Request(MCP_URL, {
    method: opts.method ?? "POST",
    headers,
    ...(opts.method === "GET" || opts.method === "DELETE" ? {} : { body: JSON.stringify(body) }),
  });
  return handleMcpRequest(request, rig.env);
}

/** 解析一次 JSON-RPC 响应（同时兼容 text/event-stream 形态）。 */
async function readRpc(response: Response): Promise<Record<string, unknown>> {
  const text = await response.text();
  if (response.headers.get("Content-Type")?.includes("text/event-stream")) {
    const dataLine = text.split("\n").find((l) => l.startsWith("data: "));
    return JSON.parse(dataLine!.slice("data: ".length)) as Record<string, unknown>;
  }
  return JSON.parse(text) as Record<string, unknown>;
}

/** 走一趟完整的 initialize，返回会话 id。 */
async function initSession(rig: TestRig, protocolVersion = "2025-06-18"): Promise<string> {
  const response = await postMcp(rig, {
    jsonrpc: "2.0",
    id: 1,
    method: "initialize",
    params: {
      protocolVersion,
      capabilities: {},
      clientInfo: { name: "gate0-test-client", version: "0.0.1" },
    },
  });
  expect(response.status).toBe(200);
  const sessionId = response.headers.get(MCP_SESSION_HEADER);
  expect(sessionId).toBeTruthy();
  return sessionId!;
}

// ---------------------------------------------------------------------------
//  认证
// ---------------------------------------------------------------------------

describe("MCP / 认证", () => {
  it("没有 Authorization 头时 401，且带 WWW-Authenticate", async () => {
    const rig = await makeRig();
    const response = await postMcp(rig, { jsonrpc: "2.0", id: 1, method: "initialize", params: {} }, { token: null });

    expect(response.status).toBe(401);
    const challenge = response.headers.get("WWW-Authenticate") ?? "";
    expect(challenge).toContain('Bearer realm="android-mcp-hub"');
    expect(challenge).toContain('error="invalid_token"');
    // ⚠ Gate 0 没有授权服务器，因此**刻意**不指向 resource_metadata。
    expect(challenge).not.toContain("resource_metadata");
  });

  it("令牌错误时 401", async () => {
    const rig = await makeRig();
    const response = await postMcp(
      rig,
      { jsonrpc: "2.0", id: 1, method: "initialize", params: {} },
      { token: "wrong-token" },
    );
    expect(response.status).toBe(401);
  });

  it("服务端没配 MCP_ACCESS_TOKEN 时失败关闭（503），不是放行", async () => {
    const rig = await makeRig({ mcpAccessToken: null });
    const response = await postMcp(rig, { jsonrpc: "2.0", id: 1, method: "initialize", params: {} });
    expect(response.status).toBe(503);
    const body = (await response.json()) as Record<string, unknown>;
    expect(body["error"]).toBe("misconfigured");
  });

  it("GET /mcp 返回 405 并声明 Allow（明确不提供服务端主动通知流）", async () => {
    const rig = await makeRig();
    const response = await postMcp(rig, null, { method: "GET" });
    expect(response.status).toBe(405);
    expect(response.headers.get("Allow")).toBe("POST, DELETE");
  });
});

// ---------------------------------------------------------------------------
//  initialize
// ---------------------------------------------------------------------------

describe("MCP / initialize", () => {
  it("成功协商协议版本，并下发 Mcp-Session-Id", async () => {
    const rig = await makeRig();
    const response = await postMcp(rig, {
      jsonrpc: "2.0",
      id: "init-1",
      method: "initialize",
      params: {
        protocolVersion: "2025-06-18",
        capabilities: {},
        clientInfo: { name: "gate0-test-client", version: "0.0.1" },
      },
    });

    expect(response.status).toBe(200);
    expect(response.headers.get(MCP_SESSION_HEADER)).toBeTruthy();

    const body = await readRpc(response);
    expect(body["jsonrpc"]).toBe("2.0");
    expect(body["id"]).toBe("init-1");

    const result = body["result"] as Record<string, unknown>;
    expect(result["protocolVersion"]).toBe("2025-06-18");
    const serverInfo = result["serverInfo"] as Record<string, unknown>;
    expect(serverInfo["name"]).toBe("android-mcp-hub");
    expect(typeof serverInfo["version"]).toBe("string");

    // 只声明 tools，而且明确 listChanged=false（Gate 0 没有服务端主动通知）。
    const capabilities = result["capabilities"] as Record<string, unknown>;
    expect(capabilities["tools"]).toEqual({ listChanged: false });
    expect(capabilities["resources"]).toBeUndefined();
    expect(capabilities["prompts"]).toBeUndefined();
    expect(capabilities["logging"]).toBeUndefined();
  });

  it("不支持的协议版本回退到服务端最新版，而不是报错", async () => {
    const rig = await makeRig();
    const response = await postMcp(rig, {
      jsonrpc: "2.0",
      id: 2,
      method: "initialize",
      params: { protocolVersion: "1999-01-01", capabilities: {}, clientInfo: { name: "x", version: "1" } },
    });
    expect(response.status).toBe(200);
    const result = (await readRpc(response))["result"] as Record<string, unknown>;
    expect(result["protocolVersion"]).toBe("2025-06-18");
  });

  it("notifications/initialized 被接受（202，无响应体）", async () => {
    const rig = await makeRig();
    const sessionId = await initSession(rig);
    const response = await postMcp(
      rig,
      { jsonrpc: "2.0", method: "notifications/initialized" },
      { sessionId },
    );
    expect(response.status).toBe(202);
  });
});

// ---------------------------------------------------------------------------
//  tools/list
// ---------------------------------------------------------------------------

describe("MCP / tools/list", () => {
  it("只返回一个工具 phone_ping，且 schema 是只读的", async () => {
    const rig = await makeRig();
    const sessionId = await initSession(rig);

    const response = await postMcp(rig, { jsonrpc: "2.0", id: 10, method: "tools/list", params: {} }, { sessionId });
    expect(response.status).toBe(200);

    const body = await readRpc(response);
    expect(body["id"]).toBe(10);
    const tools = (body["result"] as Record<string, unknown>)["tools"] as Array<Record<string, unknown>>;

    expect(tools.length).toBe(1);
    expect(tools[0]!["name"]).toBe("phone_ping");
    expect(typeof tools[0]!["description"]).toBe("string");

    const schema = tools[0]!["inputSchema"] as Record<string, unknown>;
    expect(schema["type"]).toBe("object");
    expect(schema["additionalProperties"]).toBe(false);
    const props = schema["properties"] as Record<string, unknown>;
    expect(Object.keys(props).sort()).toEqual(["device_id", "echo", "timeout_ms"]);

    // 只读工具：这三个 hint 必须诚实。
    const annotations = tools[0]!["annotations"] as Record<string, unknown>;
    expect(annotations["readOnlyHint"]).toBe(true);
    expect(annotations["destructiveHint"]).toBeUndefined();
  });
});

// ---------------------------------------------------------------------------
//  tools/call —— 走真实转发链路
// ---------------------------------------------------------------------------

describe("MCP / tools/call 转发链路", () => {
  it("手机在线时，tools/call 真的把 phone_ping 发到手机并拿到 pong", async () => {
    const rig = await makeRig();
    const { socket, phone } = await connectMockPhone(rig, TEST_DEVICE_ID, "normal");
    const sessionId = await initSession(rig);

    const response = await postMcp(
      rig,
      {
        jsonrpc: "2.0",
        id: 42,
        method: "tools/call",
        params: { name: "phone_ping", arguments: { device_id: TEST_DEVICE_ID, echo: "hello-gate0" } },
      },
      { sessionId },
    );

    expect(response.status).toBe(200);
    const body = await readRpc(response);
    expect(body["id"]).toBe(42);

    const result = body["result"] as Record<string, unknown>;
    expect(result["isError"]).toBeFalsy();
    const structured = result["structuredContent"] as Record<string, unknown>;
    expect(structured["ok"]).toBe(true);
    expect(structured["device_id"]).toBe(TEST_DEVICE_ID);
    expect(typeof structured["rtt_ms"]).toBe("number");
    expect(structured["echo"]).toBe("hello-gate0");

    // ★ 关键断言：手机那一侧**真的**收到过一条 phone_ping，request_id 一致。
    expect(phone.pingCount).toBe(1);
    const pings = socket.receivedFrames.map((f) => JSON.parse(f) as Record<string, unknown>).filter((f) => f["type"] === "phone_ping");
    expect(pings.length).toBe(1);
    expect(pings[0]!["request_id"]).toBe(structured["request_id"]);
  });

  it("手机离线时以 isError=true 明确失败，绝不伪造成功", async () => {
    const rig = await makeRig();
    const sessionId = await initSession(rig);

    const response = await postMcp(
      rig,
      {
        jsonrpc: "2.0",
        id: 43,
        method: "tools/call",
        params: { name: "phone_ping", arguments: { device_id: TEST_DEVICE_ID } },
      },
      { sessionId },
    );

    expect(response.status).toBe(200);
    const body = await readRpc(response);
    if ("error" in body) throw new Error("离线不该是协议错误，应该是工具执行失败");
    const result = body["result"] as Record<string, unknown>;
    expect(result["isError"]).toBe(true);
    const structured = result["structuredContent"] as Record<string, unknown>;
    expect(structured["ok"]).toBe(false);
    expect((structured["error"] as Record<string, unknown>)["code"]).toBe("phone_offline");
  });

  it("转发确实经过了 Durable Object：DO 的指标被累加", async () => {
    const rig = await makeRig();
    await connectMockPhone(rig, TEST_DEVICE_ID, "normal");
    const sessionId = await initSession(rig);

    await postMcp(
      rig,
      {
        jsonrpc: "2.0",
        id: 44,
        method: "tools/call",
        params: { name: "phone_ping", arguments: { device_id: TEST_DEVICE_ID } },
      },
      { sessionId },
    );

    const status = await (await import("./harness/env")).phoneStatus(rig, TEST_DEVICE_ID);
    const metrics = status["metrics"] as Record<string, number>;
    expect(metrics["pingsSent"]).toBe(1);
    expect(metrics["pongsMatched"]).toBe(1);
  });
});

// ---------------------------------------------------------------------------
//  JSON-RPC 错误处理
// ---------------------------------------------------------------------------

describe("MCP / JSON-RPC 错误处理", () => {
  it("不支持的方法返回 404 + -32601 method not found", async () => {
    const rig = await makeRig();
    const sessionId = await initSession(rig);

    const response = await postMcp(rig, { jsonrpc: "2.0", id: 7, method: "resources/list", params: {} }, { sessionId });
    // ⚠ HTTP 状态必须是 404，不能是 200 —— 即便 JSON-RPC 的 error.code 已经是
    //    正确的 -32601。新版官方连接器把 "200 + -32601" 理解成"服务器实现了一个
    //    我们读不懂的方法"，从而**永久失败**；只有 404 才会让它回退到 initialize。
    expect(response.status).toBe(404);
    const body = await readRpc(response);
    expect(body["id"]).toBe(7);
    const error = body["error"] as Record<string, unknown>;
    expect(error["code"]).toBe(-32601);
    expect(typeof error["message"]).toBe("string");
  });

  it("JSON 语法错误返回 -32700 parse error，HTTP 400", async () => {
    const rig = await makeRig();
    const headers = new Headers();
    headers.set("Accept", "application/json");
    headers.set("Content-Type", "application/json");
    headers.set("Authorization", `Bearer ${TEST_MCP_TOKEN}`);
    const response = await handleMcpRequest(
      new Request(MCP_URL, { method: "POST", headers, body: "{ this is not json" }),
      rig.env,
    );
    expect(response.status).toBe(400);
    const error = (await readRpc(response))["error"] as Record<string, unknown>;
    expect(error["code"]).toBe(-32700);
  });

  it("JSON-RPC 批次（数组）被明确拒绝为 -32600", async () => {
    const rig = await makeRig();
    const response = await postMcp(rig, [{ jsonrpc: "2.0", id: 1, method: "ping" }]);
    expect(response.status).toBe(400);
    const error = (await readRpc(response))["error"] as Record<string, unknown>;
    expect(error["code"]).toBe(-32600);
  });

  it("MCP ping 返回空结果对象", async () => {
    const rig = await makeRig();
    const sessionId = await initSession(rig);
    const body = await readRpc(await postMcp(rig, { jsonrpc: "2.0", id: 8, method: "ping" }, { sessionId }));
    expect(body["id"]).toBe(8);
    expect(body["result"]).toEqual({});
  });

  it("数字 id、字符串 id、null id 都原样回显", async () => {
    const rig = await makeRig();
    const sessionId = await initSession(rig);
    for (const id of [0, "abc-123", null]) {
      const body = await readRpc(await postMcp(rig, { jsonrpc: "2.0", id, method: "ping" }, { sessionId }));
      expect(body["id"]).toBe(id);
    }
  });
});

// ---------------------------------------------------------------------------
//  无效参数
// ---------------------------------------------------------------------------

describe("MCP / 无效参数", () => {
  it("tools/call 缺少 name → -32602 invalid params", async () => {
    const rig = await makeRig();
    const sessionId = await initSession(rig);
    const response = await postMcp(rig, { jsonrpc: "2.0", id: 20, method: "tools/call", params: {} }, { sessionId });
    expect(response.status).toBe(200);
    const error = (await readRpc(response))["error"] as Record<string, unknown>;
    expect(error["code"]).toBe(-32602);
  });

  it("调用不存在的工具 → -32602", async () => {
    const rig = await makeRig();
    const sessionId = await initSession(rig);
    const response = await postMcp(
      rig,
      { jsonrpc: "2.0", id: 21, method: "tools/call", params: { name: "reboot_phone", arguments: {} } },
      { sessionId },
    );
    expect(response.status).toBe(200);
    const error = (await readRpc(response))["error"] as Record<string, unknown>;
    expect(error["code"]).toBe(-32602);
  });

  it("phone_ping 的多余字段被拒绝（additionalProperties: false 是认真的）", async () => {
    const rig = await makeRig();
    const sessionId = await initSession(rig);
    const response = await postMcp(
      rig,
      {
        jsonrpc: "2.0",
        id: 22,
        method: "tools/call",
        params: { name: "phone_ping", arguments: { device_id: TEST_DEVICE_ID, sneaky: true } },
      },
      { sessionId },
    );
    expect(response.status).toBe(200);
    const error = (await readRpc(response))["error"] as Record<string, unknown>;
    expect(error["code"]).toBe(-32602);
  });

  it("phone_ping 的 timeout_ms 越界被拒绝", async () => {
    const rig = await makeRig();
    const sessionId = await initSession(rig);
    for (const bad of [0, -1, 999_999, "1000", 1.5]) {
      const response = await postMcp(
        rig,
        {
          jsonrpc: "2.0",
          id: 23,
          method: "tools/call",
          params: { name: "phone_ping", arguments: { timeout_ms: bad } },
        },
        { sessionId },
      );
      const error = (await readRpc(response))["error"] as Record<string, unknown>;
      expect(error["code"], `timeout_ms=${JSON.stringify(bad)} 应被拒绝`).toBe(-32602);
    }
  });

  it("phone_ping 的 echo 超长被拒绝", async () => {
    const rig = await makeRig();
    const sessionId = await initSession(rig);
    const response = await postMcp(
      rig,
      {
        jsonrpc: "2.0",
        id: 24,
        method: "tools/call",
        params: { name: "phone_ping", arguments: { echo: "x".repeat(600) } },
      },
      { sessionId },
    );
    const error = (await readRpc(response))["error"] as Record<string, unknown>;
    expect(error["code"]).toBe(-32602);
  });
});

// ---------------------------------------------------------------------------
//  会话与协议头
// ---------------------------------------------------------------------------

describe("MCP / 会话与协议头", () => {
  it("initialize 之外的请求缺少 Mcp-Session-Id → 400", async () => {
    const rig = await makeRig();
    const response = await postMcp(rig, { jsonrpc: "2.0", id: 30, method: "tools/list" }, { sessionId: null });
    expect(response.status).toBe(400);
    const error = (await readRpc(response))["error"] as Record<string, unknown>;
    expect(error["code"]).toBe(-32600);
  });

  it("会话 id 不存在 → 404", async () => {
    const rig = await makeRig();
    const response = await postMcp(
      rig,
      { jsonrpc: "2.0", id: 31, method: "tools/list" },
      { sessionId: "00000000-0000-4000-8000-000000000000" },
    );
    expect(response.status).toBe(404);
  });

  it("协议版本头不受支持 → 400", async () => {
    const rig = await makeRig();
    const sessionId = await initSession(rig);
    const response = await postMcp(
      rig,
      { jsonrpc: "2.0", id: 32, method: "tools/list" },
      { sessionId, protocolVersion: "2020-01-01" },
    );
    expect(response.status).toBe(400);
  });

  it("协议版本头与会话建立时不一致 → 400", async () => {
    const rig = await makeRig();
    const sessionId = await initSession(rig, "2025-06-18");
    const response = await postMcp(
      rig,
      { jsonrpc: "2.0", id: 33, method: "tools/list" },
      { sessionId, protocolVersion: "2024-11-05" },
    );
    expect(response.status).toBe(400);
  });

  it("Accept 不含可接受的类型 → 406", async () => {
    const rig = await makeRig();
    const response = await postMcp(rig, { jsonrpc: "2.0", id: 34, method: "initialize", params: {} }, { accept: "text/plain" });
    expect(response.status).toBe(406);
  });

  it("Content-Type 不是 application/json → 415", async () => {
    const rig = await makeRig();
    const response = await postMcp(
      rig,
      { jsonrpc: "2.0", id: 35, method: "initialize", params: {} },
      { contentType: "text/plain" },
    );
    expect(response.status).toBe(415);
  });

  it("客户端只要 text/event-stream 时，响应是单事件 SSE 流", async () => {
    const rig = await makeRig();
    const response = await postMcp(
      rig,
      {
        jsonrpc: "2.0",
        id: 36,
        method: "initialize",
        params: { protocolVersion: "2025-06-18", capabilities: {}, clientInfo: { name: "sse-client", version: "1" } },
      },
      { accept: "text/event-stream" },
    );
    expect(response.status).toBe(200);
    expect(response.headers.get("Content-Type")).toContain("text/event-stream");
    const body = await readRpc(response);
    expect(body["id"]).toBe(36);
  });

  it("DELETE 结束会话后，该会话再用就 404", async () => {
    const rig = await makeRig();
    const sessionId = await initSession(rig);

    const del = await postMcp(rig, null, { method: "DELETE", sessionId });
    expect(del.status).toBe(204);

    const after = await postMcp(rig, { jsonrpc: "2.0", id: 37, method: "tools/list" }, { sessionId });
    expect(after.status).toBe(404);
  });

  it("DELETE 未知会话 → 404；缺会话头 → 400", async () => {
    const rig = await makeRig();
    const unknown = await postMcp(rig, null, { method: "DELETE", sessionId: "11111111-1111-4111-8111-111111111111" });
    expect(unknown.status).toBe(404);

    const missing = await postMcp(rig, null, { method: "DELETE", sessionId: null });
    expect(missing.status).toBe(400);
  });

  it("会话头里的非法字符被拒绝（防注入）", async () => {
    const rig = await makeRig();
    const response = await postMcp(
      rig,
      { jsonrpc: "2.0", id: 38, method: "tools/list" },
      { sessionId: "bad id with spaces" },
    );
    expect(response.status).toBe(400);
  });
});
