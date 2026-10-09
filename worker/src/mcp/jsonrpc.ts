// ============================================================================
//  JSON-RPC 2.0 —— MCP 的传输底座
// ============================================================================
//
//  只实现 MCP 用得到的部分，并且刻意**不**实现批量请求（batch）：
//  MCP 2025-06-18 已经把 batch 从规范里去掉了，而 2024-11-05 的客户端也很少用。
//  收到数组时明确回 -32600 并说明原因，比"悄悄当成单条处理"安全得多。
// ============================================================================

export type JsonRpcId = string | number | null;

export interface JsonRpcRequest {
  jsonrpc: "2.0";
  id?: JsonRpcId;
  method: string;
  params?: unknown;
}

export interface JsonRpcSuccess {
  jsonrpc: "2.0";
  id: JsonRpcId;
  result: unknown;
}

export interface JsonRpcErrorObject {
  code: number;
  message: string;
  data?: unknown;
}

export interface JsonRpcError {
  jsonrpc: "2.0";
  id: JsonRpcId;
  error: JsonRpcErrorObject;
}

/** JSON-RPC 2.0 标准错误码。 */
export const RPC_ERRORS = {
  parseError: -32700,
  invalidRequest: -32600,
  methodNotFound: -32601,
  invalidParams: -32602,
  internalError: -32603,
} as const;

/** 应用自定义错误码（-32000 以下为服务端保留区间）。 */
export const RPC_SERVER_ERRORS = {
  /** 依赖的手机通道不可用。 */
  phoneUnavailable: -32001,
  /** 服务端配置不完整（例如缺 MCP_ACCESS_TOKEN）。 */
  misconfigured: -32002,
} as const;

export function success(id: JsonRpcId, result: unknown): JsonRpcSuccess {
  return { jsonrpc: "2.0", id, result };
}

export function failure(id: JsonRpcId, code: number, message: string, data?: unknown): JsonRpcError {
  return { jsonrpc: "2.0", id, error: data === undefined ? { code, message } : { code, message, data } };
}

export type ParseResult =
  | { kind: "request"; request: JsonRpcRequest }
  | { kind: "notification"; request: JsonRpcRequest }
  | { kind: "response" }
  | { kind: "error"; error: JsonRpcError };

/**
 * 解析一条上行 JSON-RPC 消息。
 *
 * 返回值区分四种情况，是因为这四种在 HTTP 层的**响应状态码和响应体完全不同**：
 *   request      → 200 + 结果
 *   notification → 202 + 空体
 *   response     → 202 + 空体（客户端对我们发起的请求的答复；Gate 0 不主动发起）
 *   error        → 200 + JSON-RPC error（id 可能为 null）
 */
export function parseMessage(raw: string): ParseResult {
  let value: unknown;
  try {
    value = JSON.parse(raw);
  } catch {
    return { kind: "error", error: failure(null, RPC_ERRORS.parseError, "Parse error: 请求体不是合法 JSON") };
  }

  if (Array.isArray(value)) {
    return {
      kind: "error",
      error: failure(null, RPC_ERRORS.invalidRequest, "Batch requests 不受支持（MCP 2025-06-18 已移除批量请求）"),
    };
  }

  if (typeof value !== "object" || value === null) {
    return { kind: "error", error: failure(null, RPC_ERRORS.invalidRequest, "Invalid Request: 顶层必须是对象") };
  }

  const obj = value as Record<string, unknown>;

  if (obj["jsonrpc"] !== "2.0") {
    const id = normalizeId(obj["id"]);
    return { kind: "error", error: failure(id, RPC_ERRORS.invalidRequest, 'Invalid Request: jsonrpc 必须是 "2.0"') };
  }

  // 没有 method 的，是**响应**（结果或错误），不是请求。
  if (typeof obj["method"] !== "string") {
    if ("result" in obj || "error" in obj) return { kind: "response" };
    const id = normalizeId(obj["id"]);
    return { kind: "error", error: failure(id, RPC_ERRORS.invalidRequest, "Invalid Request: 缺少 method") };
  }

  const method = obj["method"];
  if (method.startsWith("notifications/") || !("id" in obj)) {
    return {
      kind: "notification",
      request: {
        jsonrpc: "2.0",
        method,
        ...("params" in obj ? { params: obj["params"] } : {}),
      },
    };
  }

  const id = normalizeId(obj["id"]);
  if (id === null && obj["id"] !== null) {
    return { kind: "error", error: failure(null, RPC_ERRORS.invalidRequest, "Invalid Request: id 必须是字符串、数字或 null") };
  }

  return {
    kind: "request",
    request: {
      jsonrpc: "2.0",
      id,
      method,
      ...("params" in obj ? { params: obj["params"] } : {}),
    },
  };
}

function normalizeId(value: unknown): JsonRpcId {
  if (typeof value === "string" || typeof value === "number") return value;
  return null;
}
