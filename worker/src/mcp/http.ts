// ============================================================================
//  MCP Streamable HTTP 入口
// ============================================================================
//
//  只实现一个端点：`/mcp`。三种方法各自的行为（全部有测试钉住）：
//
//    POST   /mcp   唯一的真正的入口。承载 initialize / tools/list / tools/call /
//                  ping / notifications 全部 JSON-RPC 消息。
//    GET    /mcp   返回 405（Allow: POST, DELETE）。
//                  规范允许服务端在"不提供服务器主动通知"时这样做。Gate 0 没有
//                  任何服务端主动推送的通知，所以把这条通道**明确关掉**比留一个
//                  永远不会有数据的 SSE 流更容易审计。
//    DELETE /mcp   终止会话，返回 204。
//
//  ── 认证 ──────────────────────────────────────────────────────────────────
//  `Authorization: Bearer <MCP_ACCESS_TOKEN>`。这是 Gate 0 的临时方案，
//  与手机设备令牌完全分离（见 SECURITY.md 第 3 节）。
//  未配置令牌时**失败关闭**：返回 503，而不是"放行"。
//  401 响应**故意不**带 `resource_metadata` 指针 —— 因为在 Gate 0 里还不存在
//  授权服务器，指向一个不存在的地址只会让 ChatGPT 的接入流程卡在更难看的地方。
//  这条决定与后续 OAuth 方案写在 docs/OAUTH-DESIGN.md。
// ============================================================================

import { loadConfig, type AppConfig } from "../config";
import { ROUTE_PING } from "../phone/phone-session";
import type { Env, McpSessionNamespace, PhoneSessionNamespace } from "../env";
import { MCP_SESSION_ROUTES } from "./session-do";
import { logEvent } from "../util/log";
import { timingSafeEqual } from "../util/crypto";
import { newSessionId } from "../util/ids";
import { jsonResponse, methodNotAllowed } from "../util/http";
import {
  failure,
  parseMessage,
  RPC_ERRORS,
  RPC_SERVER_ERRORS,
  success,
  type JsonRpcError,
  type JsonRpcId,
  type JsonRpcSuccess,
} from "./jsonrpc";
import { dispatchToolCall, TOOL_REGISTRY } from "./tools";

export const MCP_SESSION_HEADER = "Mcp-Session-Id";
export const MCP_PROTOCOL_HEADER = "MCP-Protocol-Version";

export const SERVER_NAME = "android-mcp-hub";
export const SERVER_VERSION = "0.1.0-gate0";
export const SERVER_TITLE = "Android MCP Hub (Gate 0)";

const INSTRUCTIONS =
  "这是 Android MCP Hub 的 Gate 0 骨架。当前只提供一个工具 phone_ping：" +
  "向已通过 WebSocket 连上的 Android 手机发一条 ping 并等待 pong，返回服务端测量的 RTT。" +
  "手机没有连接时工具会以 isError=true 明确失败，不会伪造成功，也不会自动重放。";

type RpcPayload = JsonRpcSuccess | JsonRpcError;

interface McpHttpDeps {
  /** 注入点：真正的实现只做一件事 —— 通过 Durable Object 转发给手机。 */
  callPhonePing?: (env: Env, deviceId: string, timeoutMs: number | undefined) => Promise<import("../phone/phone-session").PingOutcome>;
}

/** 真正的转发实现：Worker → PhoneSession DO → WebSocket → 手机。 */
export async function callPhonePingViaDurableObject(
  env: Env,
  deviceId: string,
  timeoutMs: number | undefined,
): Promise<import("../phone/phone-session").PingOutcome> {
  const ns = env.PHONE_SESSION as unknown as PhoneSessionNamespace;
  const stub = ns.get(ns.idFromName(deviceId));
  const request = new Request(`https://phone-session.internal${ROUTE_PING}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      requestId: crypto.randomUUID(),
      deviceId,
      ...(timeoutMs !== undefined ? { timeoutMs } : {}),
    }),
  });
  const response = await stub.fetch(request);
  const payload = (await response.json()) as import("../phone/phone-session").PingOutcome;
  return payload;
}

export async function handleMcpRequest(request: Request, env: Env, deps: McpHttpDeps = {}): Promise<Response> {
  const config = loadConfig(env);
  const method = request.method.toUpperCase();

  const auth = verifyMcpAuth(request, env, config);
  if (!auth.ok) return auth.response;

  switch (method) {
    case "POST":
      return handlePost(request, env, config, deps);
    case "GET":
      // 明确不支持服务端主动通知流。见文件头说明。
      return methodNotAllowed("POST, DELETE");
    case "DELETE":
      return handleDelete(request, env);
    default:
      return methodNotAllowed("POST, DELETE");
  }
}

// ---------------------------------------------------------------------------
//  认证
// ---------------------------------------------------------------------------

function verifyMcpAuth(
  request: Request,
  env: Env,
  config: AppConfig,
): { ok: true } | { ok: false; response: Response } {
  if (!config.hasMcpAccessToken) {
    // 失败关闭：没有配置令牌，任何人都不许进。
    return {
      ok: false,
      response: jsonResponse(
        {
          error: "misconfigured",
          message: "服务端没有配置 MCP_ACCESS_TOKEN，/mcp 拒绝所有请求（失败关闭）",
        },
        { status: 503 },
      ),
    };
  }

  const header = request.headers.get("Authorization") ?? "";
  const match = /^Bearer\s+(.+)$/i.exec(header.trim());
  if (match === null) {
    return { ok: false, response: unauthorized("缺少 Authorization: Bearer <token>") };
  }

  const presented = match[1]!.trim();
  const expected = (env.MCP_ACCESS_TOKEN ?? "").trim();
  if (!timingSafeEqual(presented, expected)) {
    logEvent("warn", "mcp.auth_failed", { reason: "invalid_token", path: new URL(request.url).pathname });
    return { ok: false, response: unauthorized("令牌无效") };
  }

  return { ok: true };
}

function unauthorized(message: string): Response {
  return jsonResponse(
    { error: "unauthorized", message },
    {
      status: 401,
      headers: {
        // ⚠ 故意不带 resource_metadata：Gate 0 还没有授权服务器。
        //    指向不存在的地址只会让客户端接入流程卡得更难看。见 docs/OAUTH-DESIGN.md。
        "WWW-Authenticate": 'Bearer realm="android-mcp-hub", error="invalid_token"',
      },
    },
  );
}

// ---------------------------------------------------------------------------
//  POST /mcp
// ---------------------------------------------------------------------------

async function handlePost(
  request: Request,
  env: Env,
  config: AppConfig,
  deps: McpHttpDeps,
): Promise<Response> {
  const accept = request.headers.get("Accept") ?? "";
  if (!accept.includes("application/json") && !accept.includes("text/event-stream")) {
    return jsonResponse(
      {
        error: "not_acceptable",
        message: 'Accept 必须包含 "application/json" 或 "text/event-stream"',
      },
      { status: 406 },
    );
  }

  const contentType = (request.headers.get("Content-Type") ?? "").split(";")[0]!.trim().toLowerCase();
  if (contentType !== "application/json") {
    return jsonResponse(
      { error: "unsupported_media_type", message: "Content-Type 必须是 application/json" },
      { status: 415 },
    );
  }

  const raw = await request.text();
  const parsed = parseMessage(raw);

  if (parsed.kind === "error") {
    return rpcResponse(parsed.error, 400, accept, null);
  }

  const sessionIdHeader = readSessionHeader(request);

  if (parsed.kind === "response") {
    return rpcResponse(null, 202, accept, null);
  }

  const { request: rpc } = parsed;

  // ---- 协议版本头校验（initialize 之外都适用）----------------------------
  const protocolHeader = request.headers.get(MCP_PROTOCOL_HEADER);
  if (rpc.method !== "initialize" && protocolHeader !== null) {
    if (!config.runtime.supportedProtocolVersions.includes(protocolHeader)) {
      return rpcResponse(
        failure(rpc.id ?? null, RPC_ERRORS.invalidRequest, `不支持的 MCP-Protocol-Version: ${protocolHeader}`, {
          supported: config.runtime.supportedProtocolVersions,
        }),
        400,
        accept,
        null,
      );
    }
  }

  // ---- 会话校验（initialize 之外都必须带会话）---------------------------
  if (rpc.method !== "initialize") {
    if (sessionIdHeader === null) {
      return rpcResponse(
        failure(rpc.id ?? null, RPC_ERRORS.invalidRequest, `缺少 ${MCP_SESSION_HEADER} 头，请先调用 initialize`),
        400,
        accept,
        null,
      );
    }
    const verdict = await validateSession(env, sessionIdHeader, protocolHeader ?? undefined);
    if (!verdict.valid) {
      if (verdict.reason === "protocol_version_mismatch") {
        return rpcResponse(
          failure(rpc.id ?? null, RPC_ERRORS.invalidRequest, "MCP-Protocol-Version 与会话建立时不一致", {
            expected: verdict.expected,
          }),
          400,
          accept,
          sessionIdHeader,
        );
      }
      return rpcResponse(
        failure(rpc.id ?? null, RPC_ERRORS.invalidRequest, "会话不存在或已过期，请重新 initialize"),
        404,
        accept,
        null,
      );
    }
  }

  // ---- 通知 ---------------------------------------------------------------
  if (parsed.kind === "notification") {
    logEvent("debug", "mcp.notification", { method: rpc.method, sessionId: sessionIdHeader });
    return rpcResponse(null, 202, accept, sessionIdHeader);
  }

  // ---- 真正的请求分发 -----------------------------------------------------
  const outcome = await dispatchRpc(rpc.method, rpc.id ?? null, rpc.params, env, config, deps);
  return rpcResponse(outcome.payload, outcome.status, accept, outcome.sessionId ?? sessionIdHeader);
}

interface DispatchedRpc {
  payload: RpcPayload;
  status: number;
  sessionId?: string | null;
}

async function dispatchRpc(
  method: string,
  id: JsonRpcId,
  params: unknown,
  env: Env,
  config: AppConfig,
  deps: McpHttpDeps,
): Promise<DispatchedRpc> {
  switch (method) {
    case "initialize":
      return handleInitialize(env, config, id, params);

    case "ping":
      return { payload: success(id, {}), status: 200 };

    case "tools/list":
      return {
        payload: success(id, {
          tools: TOOL_REGISTRY.map((t) => ({
            name: t.name,
            title: t.title,
            description: t.description,
            inputSchema: t.inputSchema,
            ...(t.outputSchema !== undefined ? { outputSchema: t.outputSchema } : {}),
            ...(t.annotations !== undefined ? { annotations: t.annotations } : {}),
          })),
        }),
        status: 200,
      };

    case "tools/call": {
      const p = (params ?? {}) as Record<string, unknown>;
      const name = typeof p["name"] === "string" ? p["name"] : "";
      if (name === "") {
        return {
          payload: failure(id, RPC_ERRORS.invalidParams, "tools/call 缺少 name"),
          status: 200,
        };
      }
      const callPhonePing = deps.callPhonePing ?? callPhonePingViaDurableObject;
      const dispatched = await dispatchToolCall(config, name, p["arguments"], (deviceId, timeoutMs) =>
        callPhonePing(env, deviceId, timeoutMs),
      );
      if (dispatched.protocolError !== undefined) {
        return {
          payload: failure(id, dispatched.protocolError.code, dispatched.protocolError.message, dispatched.protocolError.data),
          status: 200,
        };
      }
      return { payload: success(id, dispatched.result), status: 200 };
    }

    default:
      return {
        payload: failure(id, RPC_ERRORS.methodNotFound, `Method not found: ${method}`),
        status: 200,
      };
  }
}

async function handleInitialize(
  env: Env,
  config: AppConfig,
  id: JsonRpcId,
  params: unknown,
): Promise<DispatchedRpc> {
  const p = (params ?? {}) as Record<string, unknown>;
  const requested = typeof p["protocolVersion"] === "string" ? p["protocolVersion"] : "";
  const supported = config.runtime.supportedProtocolVersions;
  const negotiated = supported.includes(requested) ? requested : supported[0]!;

  const clientInfo = (p["clientInfo"] ?? null) as { name?: string; version?: string } | null;
  const sessionId = newSessionId();

  try {
    await createSession(env, sessionId, negotiated, clientInfo);
  } catch (err) {
    logEvent("error", "mcp.initialize_session_failed", { error: String(err) });
    return {
      payload: failure(id, RPC_SERVER_ERRORS.misconfigured, `无法创建 MCP 会话：${String(err)}`),
      status: 500,
    };
  }

  logEvent("info", "mcp.initialize", {
    sessionId,
    requestedProtocolVersion: requested,
    negotiatedProtocolVersion: negotiated,
    clientName: clientInfo?.name,
    clientVersion: clientInfo?.version,
  });

  return {
    payload: success(id, {
      protocolVersion: negotiated,
      capabilities: {
        // Gate 0 只声明 tools。刻意**不**声明 resources / prompts / logging /
        // completions —— 声明了却返回 -32601 是更糟的体验。
        tools: { listChanged: false },
      },
      serverInfo: { name: SERVER_NAME, title: SERVER_TITLE, version: SERVER_VERSION },
      instructions: INSTRUCTIONS,
    }),
    status: 200,
    sessionId,
  };
}

// ---------------------------------------------------------------------------
//  DELETE /mcp
// ---------------------------------------------------------------------------

async function handleDelete(request: Request, env: Env): Promise<Response> {
  const sessionId = readSessionHeader(request);
  if (sessionId === null) {
    return jsonResponse(
      { error: "bad_request", message: `缺少 ${MCP_SESSION_HEADER} 头` },
      { status: 400 },
    );
  }

  const ns = env.MCP_SESSION as unknown as McpSessionNamespace;
  const stub = ns.get(ns.idFromName(sessionId));
  const res = await stub.fetch(
    new Request(`https://mcp-session.internal${MCP_SESSION_ROUTES.terminate}`, { method: "POST" }),
  );
  const body = (await res.json()) as { deleted?: boolean };

  if (body.deleted !== true) {
    return jsonResponse({ error: "not_found", message: "会话不存在或已过期" }, { status: 404 });
  }
  return new Response(null, { status: 204, headers: { "Cache-Control": "no-store", [MCP_SESSION_HEADER]: sessionId } });
}

// ---------------------------------------------------------------------------
//  会话 DO 调用
// ---------------------------------------------------------------------------

function sessionStub(env: Env, sessionId: string): DurableObjectStub {
  const ns = env.MCP_SESSION as unknown as McpSessionNamespace;
  return ns.get(ns.idFromName(sessionId));
}

async function createSession(
  env: Env,
  sessionId: string,
  protocolVersion: string,
  clientInfo: unknown,
): Promise<void> {
  const res = await sessionStub(env, sessionId).fetch(
    new Request(`https://mcp-session.internal${MCP_SESSION_ROUTES.create}`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ sessionId, protocolVersion, clientInfo }),
    }),
  );
  if (!res.ok) throw new Error(`session create failed: HTTP ${res.status}`);
}

type SessionVerdict =
  | { valid: true }
  | { valid: false; reason: "unknown_session" | "expired" | "protocol_version_mismatch"; expected?: string };

async function validateSession(env: Env, sessionId: string, protocolVersion?: string): Promise<SessionVerdict> {
  const res = await sessionStub(env, sessionId).fetch(
    new Request(`https://mcp-session.internal${MCP_SESSION_ROUTES.validate}`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(protocolVersion === undefined ? {} : { protocolVersion }),
    }),
  );
  const body = (await res.json()) as SessionVerdict;
  return body;
}

// ---------------------------------------------------------------------------
//  响应封装
// ---------------------------------------------------------------------------

export function readSessionHeader(request: Request): string | null {
  const raw = request.headers.get(MCP_SESSION_HEADER);
  if (raw === null) return null;
  const trimmed = raw.trim();
  // 只接受可见 ASCII、且长度受限 —— 防止 header 注入与超长键。
  if (trimmed === "" || trimmed.length > 128) return null;
  if (!/^[\x21-\x7e]+$/.test(trimmed)) return null;
  return trimmed;
}

/**
 * 把 JSON-RPC 负载变成 HTTP 响应。
 *
 * @param payload null 表示"没有响应体"（通知 / 终止）。
 */
function rpcResponse(
  payload: RpcPayload | null,
  status: number,
  accept: string,
  sessionId: string | null,
): Response {
  const headers: Record<string, string> = {};
  if (sessionId !== null && sessionId !== undefined) headers[MCP_SESSION_HEADER] = sessionId;

  if (payload === null) {
    return new Response(null, { status, headers: { ...headers, "Cache-Control": "no-store" } });
  }

  // 客户端只接受 SSE 时，用单事件流回应（规范允许两种中的任一种）。
  if (!accept.includes("application/json") && accept.includes("text/event-stream")) {
    const body = `event: message\ndata: ${JSON.stringify(payload)}\n\n`;
    return new Response(body, {
      status,
      headers: {
        ...headers,
        "Content-Type": "text/event-stream; charset=utf-8",
        "Cache-Control": "no-store",
      },
    });
  }

  return jsonResponse(payload, { status, headers });
}
