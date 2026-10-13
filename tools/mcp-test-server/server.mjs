#!/usr/bin/env node
/**
 * 可控的 MCP Streamable HTTP **测试服务器**（零依赖，只用 node 内置模块）。
 *
 * 为什么自己写一个而不用官方 SDK 的 server：
 *  1. 本项目的 Android 侧 MCP 客户端是手写的，需要验证的是**协议细节**：
 *     `Mcp-Session-Id` 的建立与回传、`notifications/initialized` 的 202 空响应、
 *     `tools/list` 的 cursor 分页、JSON 与 `text/event-stream` 两种响应形态、
 *     协议版本协商、JSON-RPC 错误、HTTP 状态码、超大结果。
 *     这些必须能被**逐项开关**，官方 SDK 帮我们做得太"正确"了，反而测不到边界。
 *  2. 它同时是**真机联调**用的靶子：不在同一台机器上也能指过来（见 README）。
 *
 * 它**不是**产品代码，不参与打包，只在 `e2e/` 与人工联调里被启动。
 *
 * 用法：
 *   node tools/mcp-test-server/server.mjs --port 8791 [--name "Server A"] \
 *        [--page-size 2] [--mode json|sse] [--require-header X-API-Key=secret] \
 *        [--protocol-version 2025-06-18]
 *
 * 控制面（测试用，不实现任何鉴权 —— 它只监听回环地址）：
 *   POST /__test/config  { mode, pageSize, delayMs, requireHeader, largeBytes, failNext, protocolVersion }
 *   GET  /__test/state   → { name, config, sessions, calls, requestHeaders }
 *   POST /__test/reset   → 清空 sessions / calls / requestHeaders
 */

import { createServer } from "node:http";
import { randomUUID } from "node:crypto";

// ---------------------------------------------------------------------------
// 参数
// ---------------------------------------------------------------------------

function parseArgv(argv) {
  const out = {};
  for (let i = 0; i < argv.length; i += 1) {
    const token = argv[i];
    if (!token.startsWith("--")) continue;
    const key = token.slice(2);
    const next = argv[i + 1];
    if (next === undefined || next.startsWith("--")) {
      out[key] = "true";
    } else {
      out[key] = next;
      i += 1;
    }
  }
  return out;
}

const argv = parseArgv(process.argv.slice(2));

const DEFAULT_PORT = 8791;
const SUPPORTED_PROTOCOL_VERSIONS = ["2025-06-18", "2025-03-26", "2024-11-05"];
const NEWEST_PROTOCOL_VERSION = SUPPORTED_PROTOCOL_VERSIONS[0];

const state = {
  name: argv.name || process.env.MCP_TEST_SERVER_NAME || "mcp-test-server",
  /** 监听地址：默认只监听回环。局域网联调必须显式 --host 0.0.0.0（见 README）。 */
  host: argv.host || process.env.MCP_TEST_HOST || "127.0.0.1",
  port: Number(argv.port || process.env.MCP_TEST_PORT || DEFAULT_PORT),
  config: {
    /** "json" | "sse"：默认响应形态。 */
    mode: argv.mode || "json",
    /** > 0 时 tools/list 按这个页大小分页并给 nextCursor。 */
    pageSize: Number(argv["page-size"] || 0),
    /** 每个请求的人为延迟（毫秒），用于测超时。 */
    delayMs: 0,
    /** { name, value }：要求请求带上这个头，值不匹配则 401。 */
    requireHeader: null,
    /** huge_text 工具的默认返回字节数。 */
    largeBytes: Number(argv["large-bytes"] || 1_000_000),
    /** 下一次请求的人为故障：invalid_json | http_500 | jsonrpc_error | slow_body | empty_body | no_session_ok */
    failNext: null,
    /** initialize 时协商出来的版本；默认按请求回，非法请求回最新版。 */
    protocolVersion: argv["protocol-version"] || null,
    /** 是否强制要求后续请求带 Mcp-Session-Id。 */
    requireSession: true,
  },
  sessions: new Map(),
  calls: [],
  requestHeaders: [],
};

const requireHeaderArg = argv["require-header"];
if (requireHeaderArg) {
  const idx = String(requireHeaderArg).indexOf("=");
  if (idx <= 0) {
    console.error("--require-header 的格式是 Name=Value");
    process.exit(2);
  }
  state.config.requireHeader = {
    name: String(requireHeaderArg).slice(0, idx),
    value: String(requireHeaderArg).slice(idx + 1),
  };
}

// ---------------------------------------------------------------------------
// 工具表（全部是假数据，但 schema 与 annotations 是按 MCP 规范写的）
// ---------------------------------------------------------------------------

const TOOLS = [
  {
    name: "echo_text",
    title: "回显文本",
    description: "把输入原样回显。测试用，无副作用。",
    inputSchema: {
      type: "object",
      properties: { text: { type: "string", description: "要回显的文本" } },
      required: ["text"],
      additionalProperties: false,
    },
    annotations: { readOnlyHint: true, idempotentHint: true, openWorldHint: false },
  },
  {
    name: "sum_numbers",
    title: "求和",
    description: "把两个数字相加。测试用，无副作用。",
    inputSchema: {
      type: "object",
      properties: { a: { type: "number" }, b: { type: "number" } },
      required: ["a", "b"],
      additionalProperties: false,
    },
    annotations: { readOnlyHint: true, idempotentHint: true },
  },
  {
    name: "shared_name",
    title: "同名工具探针",
    description: "返回本服务器的名字，用来验证多服务器同名工具不会串台。",
    inputSchema: { type: "object", properties: {}, additionalProperties: false },
    annotations: { readOnlyHint: true, idempotentHint: true },
  },
  {
    name: "slow_echo",
    title: "慢回显",
    description: "延迟 config.delayMs 之后回显，用于测客户端超时与取消。",
    inputSchema: {
      type: "object",
      properties: { text: { type: "string" } },
      required: ["text"],
      additionalProperties: false,
    },
    annotations: { readOnlyHint: true },
  },
  {
    name: "boom",
    title: "总是失败",
    description: "总是返回 isError 的工具结果（不是 JSON-RPC 错误）。",
    inputSchema: { type: "object", properties: {}, additionalProperties: false },
    annotations: { readOnlyHint: true },
  },
  {
    name: "huge_text",
    title: "超大结果",
    description: "返回指定字节数的文本，用于验证结果大小上限。",
    inputSchema: {
      type: "object",
      properties: { bytes: { type: "integer", minimum: 1 } },
      additionalProperties: false,
    },
    annotations: { readOnlyHint: true },
  },
  {
    name: "no_annotations",
    title: "没有 annotations",
    description: "不声明任何 annotations，用于验证客户端不把它当只读。",
    inputSchema: {
      type: "object",
      properties: { q: { type: "string" } },
      additionalProperties: false,
    },
  },
  {
    name: "destructive_write",
    title: "有副作用的写",
    description: "声明 destructiveHint，用于验证第一阶段不允许授权它。",
    inputSchema: {
      type: "object",
      properties: { target: { type: "string" } },
      required: ["target"],
      additionalProperties: false,
    },
    annotations: { readOnlyHint: false, destructiveHint: true, idempotentHint: false },
  },
];

// ---------------------------------------------------------------------------
// JSON-RPC / MCP 处理
// ---------------------------------------------------------------------------

function jsonrpcResult(id, result) {
  return { jsonrpc: "2.0", id, result };
}

function jsonrpcError(id, code, message, data) {
  const error = { code, message };
  if (data !== undefined) error.data = data;
  return { jsonrpc: "2.0", id: id ?? null, error };
}

function toolResult(toolName, args) {
  switch (toolName) {
    case "echo_text":
      return {
        content: [{ type: "text", text: `echo:${args.text}` }],
        structuredContent: { echoed: args.text, server: state.name },
      };
    case "sum_numbers":
      return {
        content: [{ type: "text", text: `${args.a} + ${args.b} = ${args.a + args.b}` }],
        structuredContent: { sum: args.a + args.b, server: state.name },
      };
    case "shared_name":
      return {
        content: [{ type: "text", text: state.name }],
        structuredContent: { server: state.name },
      };
    case "slow_echo":
      return { content: [{ type: "text", text: `slow:${args.text}` }] };
    case "boom":
      return {
        isError: true,
        content: [{ type: "text", text: "boom: 这是工具自己报的失败" }],
      };
    case "huge_text": {
      const bytes = Number(args.bytes || state.config.largeBytes);
      return { content: [{ type: "text", text: "x".repeat(bytes) }] };
    }
    case "no_annotations":
      return { content: [{ type: "text", text: `no-annotations:${args.q ?? ""}` }] };
    case "destructive_write":
      return { content: [{ type: "text", text: `written:${args.target}` }] };
    default:
      return null;
  }
}

function listToolsPage(cursor, session) {
  const pageSize = session.pageSize ?? state.config.pageSize ?? 0;

  // 每个会话可以有自己的页大小（测试同名工具隔离时用得上）。
  const all = TOOLS;
  const start = cursor ? Number(cursor) : 0;
  if (!Number.isFinite(start) || start < 0 || start > all.length) {
    return { error: jsonrpcError(null, -32602, `cursor 越界：${cursor}`) };
  }
  if (pageSize <= 0) return { result: { tools: all } };
  const slice = all.slice(start, start + pageSize);
  const next = start + pageSize;
  const result = { tools: slice };
  if (next < all.length) result.nextCursor = String(next);
  return { result };
}

function negotiateProtocolVersion(requested) {
  if (state.config.protocolVersion) return state.config.protocolVersion;
  if (typeof requested === "string" && SUPPORTED_PROTOCOL_VERSIONS.includes(requested)) {
    return requested;
  }
  return NEWEST_PROTOCOL_VERSION;
}

async function handleMcpMessage(message, session) {
  const { id, method, params } = message;
  if (typeof method !== "string") {
    return jsonrpcError(id, -32600, "缺少 method");
  }

  switch (method) {
    case "initialize": {
      const version = negotiateProtocolVersion(params?.protocolVersion);
      return jsonrpcResult(id, {
        protocolVersion: version,
        capabilities: { tools: { listChanged: false } },
        serverInfo: { name: state.name, version: "0.0.1-test" },
        instructions: `测试服务器 ${state.name}，只提供假工具。`,
      });
    }
    case "notifications/initialized":
      return null; // 通知，不回 body（HTTP 202）
    case "ping":
      return jsonrpcResult(id, {});
    case "tools/list": {
      const page = listToolsPage(params?.cursor, session);
      if (page.error) return page.error;
      return jsonrpcResult(id, page.result);
    }
    case "tools/call": {
      const name = params?.name;
      const args = params?.arguments ?? {};
      if (typeof name !== "string") {
        return jsonrpcError(id, -32602, "tools/call 缺少 name");
      }
      const tool = TOOLS.find((t) => t.name === name);
      if (!tool) return jsonrpcError(id, -32602, `未知工具：${name}`);
      if (args === null || typeof args !== "object" || Array.isArray(args)) {
        return jsonrpcError(id, -32602, "arguments 必须是对象");
      }
      // 白名单式参数校验：不认识的字段 / 缺必填字段都拒绝（与 MCP 规范一致）。
      const allowed = Object.keys(tool.inputSchema.properties ?? {});
      const extra = Object.keys(args).filter((k) => !allowed.includes(k));
      if (tool.inputSchema.additionalProperties === false && extra.length > 0) {
        return jsonrpcError(id, -32602, `不认识的参数：${extra.join(",")}`);
      }
      const missing = (tool.inputSchema.required ?? []).filter(
        (k) => args[k] === undefined || args[k] === null,
      );
      if (missing.length > 0) {
        return jsonrpcError(id, -32602, `缺少必填参数：${missing.join(",")}`);
      }
      if (name === "slow_echo" && state.config.delayMs > 0) {
        await new Promise((resolve) => setTimeout(resolve, state.config.delayMs));
      }
      const result = toolResult(name, args);
      state.calls.push({ at: Date.now(), name, arguments: args, server: state.name });
      return jsonrpcResult(id, result);
    }
    default:
      return jsonrpcError(id, -32601, `不支持的方法：${method}`);
  }
}

// ---------------------------------------------------------------------------
// HTTP 层
// ---------------------------------------------------------------------------

function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    req.on("data", (c) => chunks.push(c));
    req.on("end", () => resolve(Buffer.concat(chunks).toString("utf8")));
    req.on("error", reject);
  });
}

function sendJson(res, status, payload, extraHeaders = {}) {
  const body = payload === null ? "" : JSON.stringify(payload);
  res.writeHead(status, {
    "content-type": "application/json; charset=utf-8",
    "content-length": Buffer.byteLength(body),
    ...extraHeaders,
  });
  res.end(body);
}

function sendEmpty(res, status, extraHeaders = {}) {
  res.writeHead(status, { "content-length": "0", ...extraHeaders });
  res.end();
}

function sendSse(res, payload) {
  const body = `event: message\ndata: ${JSON.stringify(payload)}\n\n`;
  res.writeHead(200, {
    "content-type": "text/event-stream; charset=utf-8",
    "cache-control": "no-cache",
    connection: "keep-alive",
    "content-length": Buffer.byteLength(body),
  });
  res.end(body);
}

function wantsSse(req, url) {
  const override = req.headers["x-test-mcp-response"];
  if (override === "sse") return true;
  if (override === "json") return false;
  if (url.searchParams.get("mode") === "sse") return true;
  if (url.searchParams.get("mode") === "json") return false;
  return state.config.mode === "sse";
}

function acceptsOk(req) {
  const accept = String(req.headers.accept || "");
  return accept.includes("application/json") || accept.includes("text/event-stream");
}

function checkRequiredHeader(req) {
  const required = state.config.requireHeader;
  if (!required) return null;
  const got = req.headers[String(required.name).toLowerCase()];
  if (got !== required.value) {
    return { status: 401, body: { error: "unauthorized", message: `缺少或错误的头：${required.name}` } };
  }
  return null;
}

/** 记录收到的请求头（测试断言用）。**会包含敏感头** —— 这是本地测试服务器，仅回环可达。 */
function recordRequest(req, url, body) {
  state.requestHeaders.push({
    at: Date.now(),
    path: url.pathname,
    headers: { ...req.headers },
  });
  if (state.requestHeaders.length > 200) state.requestHeaders.shift();
  state.lastBody = body;
}

const server = createServer(async (req, res) => {
  const url = new URL(req.url, `http://${req.headers.host || "127.0.0.1"}`);

  try {
    // ---- 控制面 ---------------------------------------------------------
    if (url.pathname === "/__test/state") {
      return sendJson(res, 200, {
        name: state.name,
        config: state.config,
        sessions: [...state.sessions.keys()],
        calls: state.calls,
        requestHeaders: state.requestHeaders,
      });
    }
    if (url.pathname === "/__test/reset" && req.method === "POST") {
      state.sessions.clear();
      state.calls = [];
      state.requestHeaders = [];
      state.config.failNext = null;
      return sendJson(res, 200, { ok: true });
    }
    if (url.pathname === "/__test/config" && req.method === "POST") {
      const raw = await readBody(req);
      const patch = raw ? JSON.parse(raw) : {};
      Object.assign(state.config, patch);
      return sendJson(res, 200, { ok: true, config: state.config });
    }
    if (url.pathname === "/__test/health") {
      return sendJson(res, 200, { ok: true, name: state.name });
    }

    // ---- MCP 端点 -------------------------------------------------------
    if (url.pathname !== "/mcp") {
      return sendJson(res, 404, { error: "not_found" });
    }

    if (req.method === "DELETE") {
      const sessionId = req.headers["mcp-session-id"];
      state.sessions.delete(sessionId);
      return sendEmpty(res, 204);
    }
    if (req.method === "GET") {
      // 主动 SSE 流：本测试服务器不推送任何东西，回 405 让客户端知道要退回 POST。
      return sendJson(res, 405, { error: "method_not_allowed", message: "本测试服务器不支持 GET SSE 流" });
    }
    if (req.method !== "POST") {
      return sendEmpty(res, 405);
    }

    const raw = await readBody(req);
    recordRequest(req, url, raw);

    const headerProblem = checkRequiredHeader(req);
    if (headerProblem) return sendJson(res, headerProblem.status, headerProblem.body);

    if (!acceptsOk(req)) {
      return sendJson(res, 406, {
        jsonrpc: "2.0",
        id: null,
        error: { code: -32600, message: "Accept 必须包含 application/json 或 text/event-stream" },
      });
    }

    // 人为故障开关（每次只触发一次）。
    const failNext = state.config.failNext;
    if (failNext) {
      state.config.failNext = null;
      switch (failNext) {
        case "invalid_json":
          res.writeHead(200, { "content-type": "application/json" });
          return res.end("this is { not json");
        case "http_500":
          return sendJson(res, 500, { error: "internal" });
        case "empty_body":
          return sendJson(res, 200, null);
        case "jsonrpc_error":
          return sendJson(res, 200, jsonrpcError(null, -32000, "测试服务器主动返回的错误"));
        case "slow_body":
          await new Promise((resolve) => setTimeout(resolve, 60_000));
          return sendJson(res, 200, jsonrpcResult(1, {}));
        default:
          break;
      }
    }

    let message;
    try {
      message = JSON.parse(raw);
    } catch {
      return sendJson(res, 200, jsonrpcError(null, -32700, "Parse error"));
    }

    // 会话管理：initialize 建立会话（回 Mcp-Session-Id），之后的请求必须带上。
    const sessionHeader = req.headers["mcp-session-id"];
    const isInitialize = message?.method === "initialize";
    let session = sessionHeader ? state.sessions.get(sessionHeader) : undefined;
    const extraHeaders = {};

    if (isInitialize) {
      const sessionId = randomUUID();
      session = { id: sessionId, createdAt: Date.now(), pageSize: null };
      state.sessions.set(sessionId, session);
      extraHeaders["mcp-session-id"] = sessionId;
    } else if (state.config.requireSession && !session && message?.method !== "notifications/initialized") {
      return sendJson(res, 400, jsonrpcError(message?.id ?? null, -32000, "缺少或未知的 Mcp-Session-Id"));
    }

    // 每个响应都可以有各自的延迟（慢工具走工具内部延迟，这里只用来测初始化慢）。
    const body = await handleMcpMessage(message, session || { pageSize: null });

    if (body === null) {
      // 通知：按规范回 202 且无 body。
      return sendEmpty(res, 202, extraHeaders);
    }
    if (body.error && body.id === null && state.config.failNext === "jsonrpc_error") {
      return sendJson(res, 200, body, extraHeaders);
    }

    if (wantsSse(req, url)) return sendSse(res, body);
    return sendJson(res, 200, body, extraHeaders);
  } catch (error) {
    return sendJson(res, 500, { error: "test_server_crash", message: String(error?.message || error) });
  }
});

server.listen(state.port, state.host, () => {
  // `--port 0` 让内核挑端口，所以这里必须回读**真正绑上**的端口再打印；
  // 打印 state.port 会把 0 写进日志，调用方就解析不出地址了。
  const address = server.address();
  const boundPort = typeof address === "object" && address !== null ? address.port : state.port;
  state.port = boundPort;
  const url = `http://${state.host === "0.0.0.0" ? "127.0.0.1" : state.host}:${boundPort}/mcp`;
  console.log(`[mcp-test-server] ${state.name} listening on ${url}`);
  console.log(`[mcp-test-server] mode=${state.config.mode} pageSize=${state.config.pageSize}`);
  if (state.config.requireHeader) {
    console.log(`[mcp-test-server] requires header ${state.config.requireHeader.name}`);
  }
});

process.on("SIGTERM", () => server.close(() => process.exit(0)));
process.on("SIGINT", () => server.close(() => process.exit(0)));
