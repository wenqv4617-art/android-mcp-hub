/**
 * 零依赖的 MCP Streamable HTTP **参考客户端**（Node 侧）。
 *
 * 它有两个用途，都不是产品代码：
 *  1. `npm run e2e` 的 §⑫ 与 Android 侧的 `StreamableHttpInteropTest` 用它验证
 *     测试服务器本身是符合协议的（两个不同语言写的客户端都对上，才算数）；
 *  2. `e2e/run-local-e2e.ts` 里 mock 手机用它扮演"Android 上的第三方 MCP 客户端"，
 *     从而在开发环境里跑通「ChatGPT → Worker → DO → 手机 → 第三方 MCP 服务器」整条链路。
 *
 * 它**故意**手写协议细节（而不是用官方 SDK 的 client），理由和 Android 侧一样：
 * 我们要验证的是 `Mcp-Session-Id`、202 空响应、cursor 分页、`text/event-stream`
 * 这些具体行为，用 SDK 会把不兼容悄悄抹平。
 *
 * 支持范围（与本轮 Android 客户端的 P0 范围一致）：
 *  - initialize / notifications/initialized / tools/list（含 cursor 分页）/ tools/call
 *  - `application/json` 与 `text/event-stream` 两种响应形态
 *  - 会话 id 回传、协议版本协商、超时（AbortController）、响应大小上限
 *  - 自定义请求头（用于验证 Header 透传）
 * 不支持（P1/P2）：旧版 HTTP+SSE（GET 长连接）、stdio、服务器主动通知流。
 */

/** 客户端错误的封闭码集。Android 侧的同名实现必须收敛到同一组语义。 */
export const MCP_CLIENT_ERROR_CODES = [
  /** HTTP 状态码非 2xx。 */
  "mcp_http_error",
  /** 连不上（DNS/连接被拒/TLS/超时在传输层）。 */
  "mcp_server_unavailable",
  /** 响应不是合法 JSON、或 JSON-RPC 结构不合法、或不是我们要的响应。 */
  "mcp_protocol_error",
  /** 超过本地超时。 */
  "mcp_timeout",
  /** 响应体超过大小上限（在读完/读一半时就要拒绝）。 */
  "mcp_result_too_large",
];

export class McpClientError extends Error {
  constructor(code, message, extra = {}) {
    super(message);
    this.name = "McpClientError";
    this.code = code;
    Object.assign(this, extra);
  }
}

const DEFAULT_PROTOCOL_VERSION = "2025-06-18";
const SUPPORTED_PROTOCOL_VERSIONS = ["2025-06-18", "2025-03-26", "2024-11-05"];
const SESSION_HEADER = "Mcp-Session-Id";
const PROTOCOL_HEADER = "MCP-Protocol-Version";
const MAX_TOOL_PAGES = 50;

/** 从 `text/event-stream` 文本里取出每条 `data:` 载荷（本客户端只关心 JSON-RPC 消息）。 */
export function extractSseData(text) {
  const events = [];
  for (const rawLine of text.split(/\r?\n/)) {
    // 只认 data: 行；event:/id:/retry:/注释行按规范忽略。
    if (!rawLine.startsWith("data:")) continue;
    const payload = rawLine.slice(5).trimStart();
    if (payload.length > 0) events.push(payload);
  }
  return events;
}

export class McpStreamableHttpClient {
  /**
   * @param {object} options
   * @param {string} options.url MCP 端点（形如 https://host/mcp）
   * @param {Record<string,string>} [options.headers] 自定义请求头
   * @param {typeof fetch} [options.fetchImpl]
   * @param {string} [options.protocolVersion]
   * @param {number} [options.timeoutMs]
   * @param {number} [options.maxResponseBytes]
   * @param {string} [options.clientName]
   */
  constructor(options) {
    this.url = options.url;
    this.headers = { ...(options.headers || {}) };
    this.fetchImpl = options.fetchImpl || globalThis.fetch;
    this.requestedProtocolVersion = options.protocolVersion || DEFAULT_PROTOCOL_VERSION;
    this.timeoutMs = options.timeoutMs ?? 10_000;
    this.maxResponseBytes = options.maxResponseBytes ?? 256 * 1024;
    this.clientName = options.clientName || "mcp-streamable-http-client";
    this.sessionId = null;
    this.negotiatedProtocolVersion = null;
    this.serverInfo = null;
    this.nextId = 1;
  }

  // -------------------------------------------------------------------------
  // 传输层
  // -------------------------------------------------------------------------

  buildHeaders(extra = {}) {
    const headers = {
      "content-type": "application/json",
      // 规范要求两个都接受；服务器据此决定回 JSON 还是 SSE。
      accept: "application/json, text/event-stream",
      ...this.headers,
      ...extra,
    };
    if (this.sessionId) headers[SESSION_HEADER] = this.sessionId;
    if (this.negotiatedProtocolVersion) headers[PROTOCOL_HEADER] = this.negotiatedProtocolVersion;
    return headers;
  }

  async send(body, { expectBody = true } = {}) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), this.timeoutMs);
    let response;
    try {
      response = await this.fetchImpl(this.url, {
        method: "POST",
        headers: this.buildHeaders(),
        body: JSON.stringify(body),
        signal: controller.signal,
        redirect: "error", // 绝不把请求头（可能含凭据）带到别的地址去
      });
    } catch (error) {
      if (error?.name === "AbortError") {
        throw new McpClientError("mcp_timeout", `请求超过 ${this.timeoutMs} ms 未完成`);
      }
      throw new McpClientError("mcp_server_unavailable", `连不上 MCP 服务器：${error?.message || error}`);
    } finally {
      clearTimeout(timer);
    }

    const session = response.headers.get(SESSION_HEADER);
    if (session) this.sessionId = session;

    if (!response.ok) {
      let detail = "";
      try {
        detail = (await response.text()).slice(0, 200);
      } catch {
        /* 读不到 body 就算了 */
      }
      throw new McpClientError("mcp_http_error", `MCP 服务器返回 HTTP ${response.status}`, {
        status: response.status,
        detail,
      });
    }

    if (!expectBody) {
      // 通知的响应应当是 202 且无 body；把 body 读干净，避免连接泄漏。
      try {
        await response.text();
      } catch {
        /* ignore */
      }
      return null;
    }

    const contentType = String(response.headers.get("content-type") || "");
    const text = await this.readLimited(response);
    if (text.trim().length === 0) {
      throw new McpClientError("mcp_protocol_error", "服务器返回了空响应体");
    }

    const payloads = contentType.includes("text/event-stream")
      ? extractSseData(text)
      : [text.trim()];

    let lastError = null;
    for (const payload of payloads) {
      let parsed;
      try {
        parsed = JSON.parse(payload);
      } catch {
        throw new McpClientError("mcp_protocol_error", "响应不是合法 JSON");
      }
      // 一次 POST 可能带回多条消息（日志/通知），取与请求 id 匹配的那条。
      if (parsed && typeof parsed === "object" && "id" in parsed) return parsed;
      lastError = parsed;
    }
    if (lastError) return lastError;
    throw new McpClientError("mcp_protocol_error", "响应里没有 JSON-RPC 消息");
  }

  async readLimited(response) {
    const reader = response.body?.getReader?.();
    if (!reader) {
      const text = await response.text();
      if (Buffer.byteLength(text, "utf8") > this.maxResponseBytes) {
        throw new McpClientError("mcp_result_too_large", `响应超过 ${this.maxResponseBytes} 字节`);
      }
      return text;
    }
    const chunks = [];
    let size = 0;
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      size += value.byteLength;
      if (size > this.maxResponseBytes) {
        try {
          await reader.cancel();
        } catch {
          /* ignore */
        }
        throw new McpClientError("mcp_result_too_large", `响应超过 ${this.maxResponseBytes} 字节`);
      }
      chunks.push(Buffer.from(value));
    }
    return Buffer.concat(chunks).toString("utf8");
  }

  /** 发一条请求并校验 JSON-RPC 信封；错误对象转成 McpClientError。 */
  async request(method, params) {
    const id = this.nextId;
    this.nextId += 1;
    const message = { jsonrpc: "2.0", id, method };
    if (params !== undefined) message.params = params;
    const response = await this.send(message);

    if (!response || typeof response !== "object") {
      throw new McpClientError("mcp_protocol_error", "响应不是 JSON-RPC 对象");
    }
    if (response.error) {
      const code = typeof response.error.code === "number" ? response.error.code : null;
      throw new McpClientError(
        "mcp_protocol_error",
        `服务器返回 JSON-RPC 错误：${response.error.message || "未知错误"}`,
        { rpcCode: code },
      );
    }
    if (response.id !== id) {
      throw new McpClientError(
        "mcp_protocol_error",
        `响应 id 与请求不匹配（期望 ${id}）`,
      );
    }
    return response.result;
  }

  async notify(method, params) {
    const message = { jsonrpc: "2.0", method };
    if (params !== undefined) message.params = params;
    await this.send(message, { expectBody: false });
  }

  // -------------------------------------------------------------------------
  // 协议层
  // -------------------------------------------------------------------------

  async connect() {
    const result = await this.request("initialize", {
      protocolVersion: this.requestedProtocolVersion,
      capabilities: {},
      clientInfo: { name: this.clientName, version: "0.1.0" },
    });
    if (!result || typeof result !== "object") {
      throw new McpClientError("mcp_protocol_error", "initialize 没有返回 result");
    }
    const version = result.protocolVersion;
    if (typeof version !== "string" || version.length === 0) {
      throw new McpClientError("mcp_protocol_error", "initialize 没有返回 protocolVersion");
    }
    // 协商结果以服务器为准，但必须是我们认识的名字。
    if (!SUPPORTED_PROTOCOL_VERSIONS.includes(version)) {
      throw new McpClientError("mcp_protocol_error", `服务器要求的协议版本不受支持：${version}`);
    }
    this.negotiatedProtocolVersion = version;
    this.serverInfo = result.serverInfo ?? null;
    await this.notify("notifications/initialized");
    return { protocolVersion: version, serverInfo: this.serverInfo };
  }

  /** 拉全量工具列表（跟随 nextCursor，最多 MAX_TOOL_PAGES 页）。 */
  async listTools() {
    if (!this.negotiatedProtocolVersion) {
      throw new McpClientError("mcp_protocol_error", "listTools 之前必须先 connect()");
    }
    const tools = [];
    const seen = new Set();
    let cursor;
    for (let page = 0; page < MAX_TOOL_PAGES; page += 1) {
      const params = cursor ? { cursor } : {};
      const result = await this.request("tools/list", params);
      const list = Array.isArray(result?.tools) ? result.tools : null;
      if (!list) throw new McpClientError("mcp_protocol_error", "tools/list 没有返回 tools 数组");
      for (const tool of list) {
        if (!tool || typeof tool.name !== "string" || tool.name.length === 0) {
          throw new McpClientError("mcp_protocol_error", "tools/list 里有缺少 name 的工具");
        }
        if (seen.has(tool.name)) {
          throw new McpClientError("mcp_protocol_error", `tools/list 分页出现重复工具：${tool.name}`);
        }
        seen.add(tool.name);
        tools.push(tool);
      }
      cursor = typeof result.nextCursor === "string" && result.nextCursor.length > 0
        ? result.nextCursor
        : undefined;
      if (!cursor) return tools;
    }
    throw new McpClientError("mcp_protocol_error", `tools/list 分页超过 ${MAX_TOOL_PAGES} 页`);
  }

  async callTool(name, args) {
    if (!this.negotiatedProtocolVersion) {
      throw new McpClientError("mcp_protocol_error", "callTool 之前必须先 connect()");
    }
    const result = await this.request("tools/call", { name, arguments: args ?? {} });
    if (!result || typeof result !== "object") {
      throw new McpClientError("mcp_protocol_error", "tools/call 没有返回 result");
    }
    return result;
  }

  async close() {
    const sessionId = this.sessionId;
    this.sessionId = null;
    this.negotiatedProtocolVersion = null;
    if (!sessionId) return;
    try {
      await this.fetchImpl(this.url, {
        method: "DELETE",
        headers: { [SESSION_HEADER]: sessionId, ...this.headers },
        redirect: "error",
      });
    } catch {
      /* 关会话是尽力而为：服务器可能已经忘了它 */
    }
  }
}
