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
//  两条并存的路径，见下面 `verifyMcpAuth` 的注释：
//    1. 静态 `Authorization: Bearer <MCP_ACCESS_TOKEN>` —— 本机 e2e / 手工 curl；
//    2. OAuth 访问令牌（Gate 1 新增）—— ChatGPT 只能走这条。
//  两条**都**不成立时失败关闭（503 或 401），没有"放行"分支。
//
//  401 响应里的 `resource_metadata` 指针：**OAuth 启用时带、未启用时不带**。
//  带它是 RFC 9728 发现流程的入口；不带它是因为指向一个 404 比不指更糟。
//  Gate 0.3 的 K4 断言的是"未启用时不带"，那条断言依然成立。
// ============================================================================

import { gatewayEnabled, gatewayJson, gatewayPrincipal } from "../gateway/http";
import { boundedText, GatewayError, REQUEST_LIMIT } from "../gateway/security";
import { loadConfig, type AppConfig } from "../config";
import { ROUTE_PING, ROUTE_REVOKE, ROUTE_TOOL } from "../phone/phone-session";
import type { Env, McpSessionNamespace, PhoneSessionNamespace } from "../env";
import { MCP_SESSION_ROUTES } from "./session-do";
import { logEvent } from "../util/log";
import { timingSafeEqual } from "../util/crypto";
import { newSessionId } from "../util/ids";
import { jsonResponse, methodNotAllowed } from "../util/http";
import { OAUTH_ACCESS_TOKEN_PREFIX, OAUTH_PATHS, verifyAccessToken } from "../oauth/endpoints";
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
import { dispatchToolCall, TOOL_REGISTRY, toolNames } from "./tools";

export const MCP_SESSION_HEADER = "Mcp-Session-Id";
export const MCP_PROTOCOL_HEADER = "MCP-Protocol-Version";

export const SERVER_NAME = "android-mcp-hub";
export const SERVER_VERSION = "0.1.0-gate1";
export const SERVER_TITLE = "Android MCP Hub (Gate 1)";

// Gate 2.5：这份说明是给模型看的"能力自述"，因此**从注册表派生工具名**，
// 而不是手写一份会过期的清单。工具的真实定义（参数、Schema、通道）仍在 tools.ts。
const INSTRUCTIONS =
  `Android MCP Hub 的内置工具（共 ${toolNames().length} 个，全部只读）：${toolNames().join("、")}。` +
  "phone_ping 是连接诊断：向已通过 WebSocket 连上的 Android 手机发一条 ping 并等待 pong，" +
  "返回**服务端**测量的 RTT。" +
  "device_* 系列是手机能力查询，经 Durable Object 转发到已连上的手机，" +
  "由手机上的显式白名单执行；手机侧关掉能力总开关时会明确拒绝（not_permitted）。" +
  "手机没有连接时工具会以 isError=true 明确失败，不会伪造成功，也不会自动重放。";

type RpcPayload = JsonRpcSuccess | JsonRpcError;

interface McpHttpDeps {
  /** 注入点：真正的实现只做一件事 —— 通过 Durable Object 转发给手机。 */
  callPhonePing?: (env: Env, deviceId: string, timeoutMs: number | undefined) => Promise<import("../phone/phone-session").PingOutcome>;
  /** Gate 1.5：撤销通知的注入点（测试可注入假的）。 */
  revokeDevice?: (env: Env, deviceId: string, reason: string) => Promise<{ ok: boolean; status: number }>;
  /** Gate 2：通用工具通道的注入点。缺失时通用工具会明确 internal_error，不降级。 */
  callPhoneTool?: (
    env: Env,
    deviceId: string,
    toolName: string,
    args: Record<string, unknown>,
    timeoutMs: number | undefined,
  ) => Promise<import("../phone/phone-session").ToolOutcome>;
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

/**
 * Gate 2 的转发实现：同一台 DO、同一条 WebSocket，只是帧类型换成 tool_request。
 *
 * 注意它**不**复用 [callPhonePingViaDurableObject] 的路径：两条通道在 DO 里
 * 是两个内部路由（`/ping` 与 `/tool`），合并会让"这条请求走的是哪条通道"
 * 在日志里无法区分 —— 而兼容性审计最需要区分的恰恰就是这个。
 */
export async function callPhoneToolViaDurableObject(
  env: Env,
  deviceId: string,
  toolName: string,
  args: Record<string, unknown>,
  timeoutMs: number | undefined,
): Promise<import("../phone/phone-session").ToolOutcome> {
  const ns = env.PHONE_SESSION as unknown as PhoneSessionNamespace;
  const stub = ns.get(ns.idFromName(deviceId));
  // requestId 只在这里生成一次，两条日志都用它 —— 目的就是让 "Worker 看到多久、
  // 手机那一侧到底花了多久" 能用同一个 id 对上（手机侧的分阶段日志见 Android 的
  // `HubTrace`，它的 request_id 就是这个值）。
  const requestId = crypto.randomUUID();
  const startedAt = Date.now();
  logEvent("info", "hub.forward_started", { requestId, deviceId, method: toolName });
  const request = new Request(`https://phone-session.internal${ROUTE_TOOL}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      requestId,
      deviceId,
      toolName,
      arguments: args,
      ...(timeoutMs !== undefined ? { timeoutMs } : {}),
    }),
  });
  const response = await stub.fetch(request);
  const payload = (await response.json()) as import("../phone/phone-session").ToolOutcome;
  logEvent(payload.ok ? "info" : "warn", "hub.forward_settled", {
    requestId,
    deviceId,
    method: toolName,
    elapsedMs: Date.now() - startedAt,
    outcome: payload.ok ? "ok" : "failed",
    // 错误码取自协议闭集（TOOL_RESULT_ERROR_CODES），不是自由文本；
    // 它决定了"超时"与"未授权"在排障时能不能区分开。
    ...(payload.ok ? {} : { reason: payload.error.code }),
  });
  return payload;
}

/**
 * Gate 1.5 / A1 的撤销通知：让 DO 立即失效并关闭对应设备当前的连接。
 *
 * 谁能调用它：只有 Worker 内部（DO binding 只在本 Worker 里可见）。**没有**
 * 任何公网路由映射到 `ROUTE_REVOKE` —— 这是刻意的，撤销入口一旦公网可达，
 * 它本身就成了"谁能撤销谁的设备"的新攻击面。
 *
 * DO 侧还有一道交叉校验：它要求 body 里的 deviceId 与当前 socket 上挂着的
 * deviceId 一致，否则回 409。这样即使 Worker 内部某处把 deviceId 搞混，
 * 也不会出现"A 的凭据被撤销、B 的连接被关掉"。
 *
 * 这里是**尽力而为**：撤销的最终保证来自"每次工具调用都重新解析授权"，
 * 而不是来自这条推送（见 `worker/src/index.ts` 的 upgrade 分支注释）。
 */
/**
 * 通知 DO "这台设备（或它的某一条凭据）已被撤销"。
 *
 * ★ 集中审计问题 1/2：`credentialId` 是可选**范围**参数 —— 传了就只终结那条
 * 凭据开出来的连接（凭据轮换的旧令牌），不传就是整台设备级撤销。DO 侧靠
 * 它区分"设备级"与"凭据级"，范围信息只走 Worker→DO 的内部通道，不来自公网。
 */
export async function revokePhoneDeviceViaDurableObject(
  env: Env,
  deviceId: string,
  reason: string,
  credentialId?: string,
): Promise<{ ok: boolean; status: number }> {
  const ns = env.PHONE_SESSION as unknown as PhoneSessionNamespace;
  const stub = ns.get(ns.idFromName(deviceId));
  const request = new Request(`https://phone-session.internal${ROUTE_REVOKE}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ deviceId, reason, ...(credentialId !== undefined ? { credentialId } : {}) }),
  });
  const response = await stub.fetch(request);
  return { ok: response.ok, status: response.status };
}

/**
 * ★ Gate 2：把撤销通知登记成一个**有生命周期保证**的后台任务。
 *
 * 为什么需要它：`revokePhoneDeviceViaDurableObject` 是一次**跨 DO 的 HTTP 调用**。
 * 两处调用点原先写的是 `void revokePhoneDevice(...).catch(...)` —— 发出去就不管了。
 * 在 Workers 上这是不安全的：响应一返回，运行时就可以回收这个 isolate，飞行中的
 * 子请求会被直接掐掉。而这条通知恰恰是在"拒绝一次升级"时才发出来的 —— 那是最
 * 可能紧接着就 `return unauthorizedPhone()`、让请求结束的时刻。通知丢了，撤销就
 * 只能等下一个工具调用才被发现，而**已经建立的那条连接仍然活着**。
 *
 * `ctx.waitUntil()` 是 Workers 的标准机制：把 promise 挂到这次调用的生命周期上，
 * 响应发出之后运行时仍然会等它落地。它**不**改变"尽力而为"的语义（失败只记日志、
 * 绝不把 401 变成 500），只是让尽力而为真的有机会成功。
 *
 * 两点必须说清楚：
 *  1. `ctx` 可能没有 `waitUntil`（Node 替身 harness、单测里只传 `{}`），此时退化成
 *     原来的"发了就不管"。因此**正确性绝不能依赖这条通知** —— 每个请求入口都要
 *     重新查配置（`phone-session.ts` 的 `revocationCheck`）。通知只负责让已经建立
 *     的连接尽快死掉，不负责"撤销生效"。
 *  2. 不做重试。重复投递由 DO 侧的幂等判据吸收；而"必须送达"本身就不成立。
 *
 * 返回值是那条已加过 `.catch` 的 promise：调用点可以忽略它（生产路径），测试可以
 * 确定性地 await 它，不必依赖 `settle()` 猜时间。
 */
export function notifyRevocationInBackground(
  ctx: ExecutionContext | undefined,
  details: { deviceId: string; reason: string; credentialScoped: boolean },
  notification: Promise<unknown>,
): Promise<void> {
  const settled = notification.then(
    () => undefined,
    () => {
      logEvent("warn", "phone.revoke_notify_failed", {
        deviceId: details.deviceId,
        reason: details.reason,
        credentialScoped: details.credentialScoped,
        errorCode: "revoke_notify_failed",
      });
    },
  );
  if (ctx !== undefined && typeof ctx.waitUntil === "function") ctx.waitUntil(settled);
  return settled;
}

export async function handleMcpRequest(request: Request, env: Env, deps: McpHttpDeps = {}): Promise<Response> {
  const config = loadConfig(env);
  const method = request.method.toUpperCase();
  const origin = request.headers.get("Origin");
  if (origin && origin !== new URL(request.url).origin) return jsonResponse({ error: "origin_denied" }, { status: 403 });

  const auth = await verifyMcpAuth(request, env, config);
  if (!auth.ok) return auth.response;

  switch (method) {
    case "POST":
      return handlePost(request, env, config, deps, auth.principal);
    case "GET":
      // 明确不支持服务端主动通知流。见文件头说明。
      return methodNotAllowed("POST, DELETE");
    case "DELETE":
      return handleDelete(request, env, auth.principal);
    default:
      return methodNotAllowed("POST, DELETE");
  }
}

// ---------------------------------------------------------------------------
//  认证
// ---------------------------------------------------------------------------

/**
 * `/mcp` 的鉴权。
 *
 * ── 两条并存的路径，没有第三条 ────────────────────────────────────────────
 *
 *   1. **静态 Bearer**（`MCP_ACCESS_TOKEN`）：给本机 e2e / 测试 / 手工 curl 用。
 *      它是"共享密钥"，不能给公开客户端 —— 但它是**认证**，不是无认证。
 *
 *   2. **OAuth 访问令牌**（Gate 1 新增）：ChatGPT 走这条。ChatGPT 的
 *      "Add custom MCP server" 只提供 OAuth / No authentication 两种选择，
 *      **不支持**静态 Bearer 或自定义 header，所以要走它就必须自己当授权服务器。
 *
 * 两条都不成立时一律 401。**没有**"两条都不配置就放行"的分支。
 */
async function verifyMcpAuth(
  request: Request,
  env: Env,
  config: AppConfig,
): Promise<{ ok: true; principal: string } | { ok: false; response: Response }> {
  if (!config.hasMcpAccessToken && !config.oauth.enabled && !gatewayEnabled(env)) {
    // 失败关闭：既没有静态令牌、也没有 OAuth，任何人都不许进。
    return {
      ok: false,
      response: jsonResponse(
        {
          error: "misconfigured",
          message: "服务端既没有配置 MCP_ACCESS_TOKEN、也没有启用 OAuth，/mcp 拒绝所有请求（失败关闭）",
        },
        { status: 503 },
      ),
    };
  }

  const header = request.headers.get("Authorization") ?? "";
  const match = /^Bearer\s+(.+)$/i.exec(header.trim());
  if (match === null) {
    return { ok: false, response: unauthorized(request, config, "缺少 Authorization: Bearer <token>") };
  }

  const presented = match[1]!.trim();

  // 路径 1：静态 Bearer。先比它，因为它是常数时间比较、不碰存储。
  if (config.hasMcpAccessToken) {
    const expected = (env.MCP_ACCESS_TOKEN ?? "").trim();
    if (timingSafeEqual(presented, expected)) return { ok: true, principal: "legacy:owner" };
  }

  const principal = await gatewayPrincipal(env, presented);
  if (principal) return { ok: true, principal };

  // 路径 2：OAuth 访问令牌。令牌只以 SHA-256 摘要存在 OAuthStore 里，
  // 所以这里必须真的去问一次存储 —— 这是刻意的：没有"本地自校验 JWT"，
  // 也就没有"撤销了但还能用到过期"的窗口。
  //
  // ⚠ 前置条件是 `presented` 得长得像我们自己签的令牌（前缀 `mcp_at_`）。
  //    没有这个前置，任何人拿一段随机字符串都会让 Worker 去敲一次 DO，
  //    等于把一个公开端点变成 DO 的放大器。
  if (config.oauth.enabled && presented.startsWith(OAUTH_ACCESS_TOKEN_PREFIX)) {
    const origin = new URL(request.url).origin;
    const verdict = await verifyAccessToken(env, config, presented, `${origin}/mcp`);
    if (verdict.ok) return { ok: true, principal: `oauth:${verdict.clientId}` };
    logEvent("warn", "mcp.auth_failed", { reason: "invalid_oauth_token", path: new URL(request.url).pathname });
  } else {
    logEvent("warn", "mcp.auth_failed", { reason: "invalid_token", path: new URL(request.url).pathname });
  }

  return { ok: false, response: unauthorized(request, config, "令牌无效") };
}

/**
 * 401 响应。
 *
 * ── `resource_metadata` 什么时候带、什么时候不带 ──────────────────────────
 *
 * 它只在 OAuth **已启用**时出现。这不是可选的装饰：RFC 9728 的发现流程里，
 * 客户端（ChatGPT）拿到 401 后会去读 `WWW-Authenticate` 里的
 * `resource_metadata` 指针，顺着它找到授权服务器；没有这个指针，它就只能
 * 靠猜 `/.well-known/...` 的固定路径。
 *
 * 反过来，OAuth **没启用**时绝不能带 —— 指向一个 404 的地址比不指更糟，
 * 它会让客户端的发现流程走到一个"看起来配置过、实际什么都没有"的分支上。
 *
 * Gate 0.3 的 K4（`worker/test/mcp-http.test.ts:102`）断言的是"未启用时不带"，
 * 那条断言依然成立；启用后的反向断言是 Gate 1 新增的。
 */
function unauthorized(request: Request, config: AppConfig, message: string): Response {
  let challenge = 'Bearer realm="android-mcp-hub", error="invalid_token"';
  if (config.oauth.enabled) {
    const origin = new URL(request.url).origin;
    challenge += `, resource_metadata="${origin}${OAUTH_PATHS.protectedResourceMcp}"`;
  }
  return jsonResponse({ error: "unauthorized", message }, { status: 401, headers: { "WWW-Authenticate": challenge } });
}

// ---------------------------------------------------------------------------
//  POST /mcp
// ---------------------------------------------------------------------------

async function handlePost(
  request: Request,
  env: Env,
  config: AppConfig,
  deps: McpHttpDeps,
  principal: string,
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

  let raw: string;
  try { raw = await boundedText(request.body, REQUEST_LIMIT); }
  catch { return jsonResponse({ error: "request_too_large" }, { status: 413 }); }
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
    const verdict = await validateSession(env, sessionIdHeader, protocolHeader ?? undefined, principal);
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
    // 审计 R2：故意**不**记 sessionId（它是一张可复用该会话的凭证）。
    logEvent("debug", "mcp.notification", { method: rpc.method });
    return rpcResponse(null, 202, accept, sessionIdHeader);
  }

  // ---- 真正的请求分发 -----------------------------------------------------
  const outcome = await dispatchRpc(rpc.method, rpc.id ?? null, rpc.params, env, config, deps, principal);
  return rpcResponse(outcome.payload, outcome.status, accept, outcome.sessionId ?? sessionIdHeader);
}

interface DispatchedRpc {
  payload: RpcPayload;
  status: number;
  sessionId?: string | null;
}

async function listGatewayTools(env: Env, principal: string): Promise<unknown[]> {
  try { return (await gatewayJson<{ tools: unknown[] }>(env, "list", { principal })).tools; }
  catch { logEvent("warn", "gateway.list_failed", { errorCode: "internal_error" }); return []; }
}

async function dispatchRpc(
  method: string,
  id: JsonRpcId,
  params: unknown,
  env: Env,
  config: AppConfig,
  deps: McpHttpDeps,
  principal: string,
): Promise<DispatchedRpc> {
  switch (method) {
    case "initialize":
      return handleInitialize(env, config, id, params, principal);

    case "ping":
      return { payload: success(id, {}), status: 200 };

    case "tools/list":
      return {
        payload: success(id, {
          tools: [...(principal.startsWith("client:") ? [] : TOOL_REGISTRY.map((t) => ({
            name: t.name,
            title: t.title,
            description: t.description,
            inputSchema: t.inputSchema,
            ...(t.outputSchema !== undefined ? { outputSchema: t.outputSchema } : {}),
            ...(t.annotations !== undefined ? { annotations: t.annotations } : {}),
          }))), ...(gatewayEnabled(env) ? await listGatewayTools(env, principal) : [])],
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
      if (name.startsWith("ext_")) {
        try {
          const result = await gatewayJson(env, "call", { principal, name, arguments: p["arguments"], confirmation: (p["_meta"] as Record<string, unknown> | undefined)?.["hub/confirmation"] });
          return { payload: success(id, result), status: 200 };
        } catch (e) {
          const code = e instanceof GatewayError ? e.code : "gateway_failure";
          return { payload: success(id, { isError: true, content: [{ type: "text", text: code }] }), status: 200 };
        }
      }
      if (principal.startsWith("client:")) return { payload: failure(id, -32602, "permission_denied"), status: 200 };
      const callPhonePing = deps.callPhonePing ?? callPhonePingViaDurableObject;
      const callPhoneTool = deps.callPhoneTool ?? callPhoneToolViaDurableObject;
      const dispatched = await dispatchToolCall(
        config,
        name,
        p["arguments"],
        (deviceId, timeoutMs) => callPhonePing(env, deviceId, timeoutMs),
        (deviceId, toolName, toolArgs, timeoutMs) => callPhoneTool(env, deviceId, toolName, toolArgs, timeoutMs),
      );
      if (dispatched.revokedDeviceId !== undefined) {
        // 设备存在但凭据已被撤销。先让 DO 失效（尽力而为），再回明确错误。
        const revoke = deps.revokeDevice ?? revokePhoneDeviceViaDurableObject;
        await revoke(env, dispatched.revokedDeviceId, "tool_call_after_revocation").catch(() => {
          logEvent("warn", "phone.revoke_notify_failed", { deviceId: dispatched.revokedDeviceId, errorCode: "revoke_notify_failed" });
        });
      }
      if (dispatched.protocolError !== undefined) {
        return {
          payload: failure(id, dispatched.protocolError.code, dispatched.protocolError.message, dispatched.protocolError.data),
          status: 200,
        };
      }
      return { payload: success(id, dispatched.result), status: 200 };
    }

    default:
      // ⚠ 未实现的方法必须回 **HTTP 404**，不能回 200 —— 即便 JSON-RPC 的
      //    error.code 已经是正确的 `-32601`。
      //
      //    原因（实测结论，不是推测）：新版官方连接器会用 `server/discover`
      //    之类的方法探测服务端能力。如果服务端回 "200 + -32601"，客户端会把它
      //    理解成"这个服务器实现了一个我们不认识的方法"，于是**永久失败**；
      //    回 404 才会被理解成"这个方法不存在"，从而回退到 `initialize`。
      //    这正是官方要求"未实现的 RPC 应回 404 + -32601"的原因。
      return {
        payload: failure(id, RPC_ERRORS.methodNotFound, `Method not found: ${method}`),
        status: 404,
      };
  }
}

async function handleInitialize(
  env: Env,
  config: AppConfig,
  id: JsonRpcId,
  params: unknown,
  principal: string,
): Promise<DispatchedRpc> {
  const p = (params ?? {}) as Record<string, unknown>;
  const requested = typeof p["protocolVersion"] === "string" ? p["protocolVersion"] : "";
  const supported = config.runtime.supportedProtocolVersions;
  const negotiated = supported.includes(requested) ? requested : supported[0]!;

  const clientInfo = (p["clientInfo"] ?? null) as { name?: string; version?: string } | null;
  const sessionId = newSessionId();

  try {
    await createSession(env, sessionId, negotiated, clientInfo, principal);
  } catch {
    // 审计 R2：日志记固定类别；回给客户端的 message 也用固定文案。
    // `String(err)` 会顺着 JSON-RPC 错误回到客户端 —— 那是把内部细节
    // （DO 名字、存储错误、甚至绑定信息）送出去，比写进日志更糟。
    logEvent("error", "mcp.initialize_session_failed", { errorCode: "initialize_session_failed" });
    return {
      payload: failure(id, RPC_SERVER_ERRORS.misconfigured, "无法创建 MCP 会话"),
      status: 500,
    };
  }

  logEvent("info", "mcp.initialize", {
    // 审计 R2：不记 sessionId（可复用会话的凭证）。
    //   也不记 clientInfo.name / clientInfo.version —— 它们是**客户端自报的自由文本**，
    //   属于请求体内容，按审计要求"不记录请求体"一律不落日志。
    requestedProtocolVersion: requested,
    negotiatedProtocolVersion: negotiated,
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

async function handleDelete(request: Request, env: Env, principal: string): Promise<Response> {
  const sessionId = readSessionHeader(request);
  if (sessionId === null) {
    return jsonResponse(
      { error: "bad_request", message: `缺少 ${MCP_SESSION_HEADER} 头` },
      { status: 400 },
    );
  }

  const verdict = await validateSession(env, sessionId, undefined, principal);
  if (!verdict.valid) return jsonResponse({ error: "not_found" }, { status: 404 });
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
  principal: string,
): Promise<void> {
  const res = await sessionStub(env, sessionId).fetch(
    new Request(`https://mcp-session.internal${MCP_SESSION_ROUTES.create}`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ sessionId, protocolVersion, clientInfo, principal }),
    }),
  );
  if (!res.ok) throw new Error(`session create failed: HTTP ${res.status}`);
}

type SessionVerdict =
  | { valid: true }
  | { valid: false; reason: "unknown_session" | "expired" | "protocol_version_mismatch"; expected?: string };

async function validateSession(env: Env, sessionId: string, protocolVersion?: string, principal?: string): Promise<SessionVerdict> {
  const res = await sessionStub(env, sessionId).fetch(
    new Request(`https://mcp-session.internal${MCP_SESSION_ROUTES.validate}`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ protocolVersion, principal }),
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
