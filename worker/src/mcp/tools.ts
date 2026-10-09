// ============================================================================
//  MCP 工具注册表
// ============================================================================
//
//  Gate 0 **只暴露一个工具**：`phone_ping`。
//
//  ⚠ 这个工具的成功路径**只有一条**：Worker → PhoneSession Durable Object
//     → 真实 WebSocket → 手机的 phone_pong。Worker 里没有任何地方能"直接
//     返回一个 pong" —— 没有手机连接时，它必然是 isError。
//     这条约束有专门的测试（worker/test/mcp/tools.test.ts 的
//     "没有手机连接时绝不可能成功"）守着，评审可以照着这条测试读代码。
// ============================================================================

import type { AppConfig } from "../config";
import { resolveDeviceId } from "../config";
import type { Env } from "../env";
import type { PingOutcome } from "../phone/phone-session";
import { logEvent } from "../util/log";
import { RPC_ERRORS } from "./jsonrpc";

export interface ToolDefinition {
  name: string;
  title: string;
  description: string;
  inputSchema: Record<string, unknown>;
  outputSchema?: Record<string, unknown>;
  annotations?: Record<string, unknown>;
}

export interface ToolTextContent {
  type: "text";
  text: string;
}

export interface ToolCallResult {
  content: ToolTextContent[];
  structuredContent?: Record<string, unknown>;
  isError?: boolean;
}

export const PHONE_PING_TOOL: ToolDefinition = {
  name: "phone_ping",
  title: "Phone ping",
  description:
    "通过 Cloudflare Durable Object 向已连接的 Android 手机发送一次 phone_ping，并等待手机回 phone_pong。" +
    "往返时间（RTT）由服务端测量。手机离线、超时或连接中断时返回 isError=true 的结构化错误，" +
    "不会伪造成功响应，也不会自动重放。",
  inputSchema: {
    type: "object",
    properties: {
      device_id: {
        type: "string",
        description:
          "目标设备 ID。省略时使用服务端 DEFAULT_DEVICE_ID；若服务端只配置了一台设备则自动选中；否则报 ambiguous_device。",
      },
      timeout_ms: {
        type: "integer",
        minimum: 50,
        maximum: 60000,
        description: "本次等待 phone_pong 的超时（毫秒）。省略时用服务端 PHONE_PING_TIMEOUT_MS（默认 10000）。",
      },
      echo: {
        type: "string",
        maxLength: 512,
        description: "原样回显在结果里的字符串，便于确认这一轮调用对应哪一次请求。不会被发送到手机。",
      },
    },
    required: [],
    additionalProperties: false,
  },
  outputSchema: {
    type: "object",
    properties: {
      ok: { type: "boolean" },
      request_id: { type: "string" },
      device_id: { type: "string" },
      rtt_ms: { type: "number" },
      server_timestamp: { type: "number" },
      device_timestamp: { type: "number" },
      connection_generation: { type: "number" },
      error_code: { type: "string" },
      error_message: { type: "string" },
      echo: { type: "string" },
    },
    required: ["ok"],
  },
  annotations: {
    // 只读、幂等、不触碰外部世界（除了网络往返）—— 给客户端一个明确的提示。
    readOnlyHint: true,
    idempotentHint: true,
    openWorldHint: true,
  },
};

export const TOOL_REGISTRY: ToolDefinition[] = [PHONE_PING_TOOL];

export function findTool(name: string): ToolDefinition | undefined {
  return TOOL_REGISTRY.find((t) => t.name === name);
}

export class ToolArgumentError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "ToolArgumentError";
  }
}

interface PhonePingArgs {
  device_id?: string;
  timeout_ms?: number;
  echo?: string;
}

/**
 * 严格校验工具入参。
 *
 * 为什么不用现成的 JSON Schema 校验器：Gate 0 只有一个工具、三个可选字段，
 * 手写校验能保证"多余字段被拒绝"这类行为是可读的（读代码就能确认），
 * 而不是藏在某个库的默认值里。将来工具变多再换成通用校验器。
 */
export function parsePhonePingArgs(raw: unknown): PhonePingArgs {
  if (raw === undefined || raw === null) return {};

  if (typeof raw !== "object" || Array.isArray(raw)) {
    throw new ToolArgumentError("arguments 必须是一个对象");
  }

  const obj = raw as Record<string, unknown>;
  const allowed = new Set(["device_id", "timeout_ms", "echo"]);
  for (const key of Object.keys(obj)) {
    if (!allowed.has(key)) throw new ToolArgumentError(`arguments 里有不认识的字段：${key}`);
  }

  const out: PhonePingArgs = {};

  if (obj["device_id"] !== undefined) {
    if (typeof obj["device_id"] !== "string" || obj["device_id"].trim() === "") {
      throw new ToolArgumentError("device_id 必须是非空字符串");
    }
    if (obj["device_id"].length > 128) throw new ToolArgumentError("device_id 过长（>128）");
    out.device_id = obj["device_id"].trim();
  }

  if (obj["timeout_ms"] !== undefined) {
    if (typeof obj["timeout_ms"] !== "number" || !Number.isInteger(obj["timeout_ms"])) {
      throw new ToolArgumentError("timeout_ms 必须是整数");
    }
    if (obj["timeout_ms"] < 50 || obj["timeout_ms"] > 60_000) {
      throw new ToolArgumentError("timeout_ms 必须在 [50, 60000] 之间");
    }
    out.timeout_ms = obj["timeout_ms"];
  }

  if (obj["echo"] !== undefined) {
    if (typeof obj["echo"] !== "string") throw new ToolArgumentError("echo 必须是字符串");
    if (obj["echo"].length > 512) throw new ToolArgumentError("echo 过长（>512）");
    out.echo = obj["echo"];
  }

  return out;
}

/** 把 DO 的返回值变成工具调用结果。 */
export function pingOutcomeToResult(outcome: PingOutcome, echo?: string): ToolCallResult {
  const structured: Record<string, unknown> = { ...outcome };
  if (echo !== undefined) structured["echo"] = echo;

  if (outcome.ok) {
    return {
      content: [
        {
          type: "text",
          text:
            `phone_ping 成功：设备 ${outcome.device_id} 在 ${outcome.rtt_ms} ms 内返回 pong` +
            `（request_id=${outcome.request_id}，连接代次 ${outcome.connection_generation}）。` +
            (echo !== undefined ? ` echo=${echo}` : ""),
        },
      ],
      structuredContent: structured,
    };
  }

  return {
    content: [
      {
        type: "text",
        text: `phone_ping 失败：${outcome.error.code} — ${outcome.error.message}`,
      },
    ],
    structuredContent: structured,
    // 工具**执行**层面的失败用 isError 表达（协议层面的失败才用 JSON-RPC error）。
    isError: true,
  };
}

export interface ToolDispatchResult {
  /** 协议级错误（JSON-RPC error）。有值时不要走 content 路径。 */
  protocolError?: { code: number; message: string; data?: unknown };
  result?: ToolCallResult;
}

/**
 * 执行一次 tools/call。
 *
 * `callPhonePing` 由调用方注入 —— 这样工具层不直接依赖 Durable Object，
 * 测试可以注入一个假实现来验证"参数校验/错误映射"这些纯逻辑。
 * 真正的转发实现在 index.ts 里，且**只有那一条**路径。
 */
export async function dispatchToolCall(
  config: AppConfig,
  name: string,
  rawArgs: unknown,
  callPhonePing: (deviceId: string, timeoutMs: number | undefined) => Promise<PingOutcome>,
): Promise<ToolDispatchResult> {
  const tool = findTool(name);
  if (tool === undefined) {
    return {
      protocolError: {
        code: RPC_ERRORS.invalidParams,
        message: `Unknown tool: ${name}`,
        data: { known_tools: TOOL_REGISTRY.map((t) => t.name) },
      },
    };
  }

  let args: PhonePingArgs;
  try {
    args = parsePhonePingArgs(rawArgs);
  } catch (err) {
    return {
      protocolError: {
        code: RPC_ERRORS.invalidParams,
        message: err instanceof ToolArgumentError ? err.message : String(err),
      },
    };
  }

  const resolved = resolveDeviceId(config, args.device_id);
  if (!resolved.ok) {
    return {
      result: {
        content: [{ type: "text", text: `phone_ping 失败：${resolved.code} — ${resolved.message}` }],
        structuredContent: {
          ok: false,
          error_code: resolved.code,
          error_message: resolved.message,
          ...(args.echo !== undefined ? { echo: args.echo } : {}),
        },
        isError: true,
      },
    };
  }

  try {
    const outcome = await callPhonePing(resolved.deviceId, args.timeout_ms);
    return { result: pingOutcomeToResult(outcome, args.echo) };
  } catch (err) {
    logEvent("error", "tool.phone_ping_threw", { deviceId: resolved.deviceId, error: String(err) });
    return {
      result: {
        content: [{ type: "text", text: `phone_ping 失败：internal_error — ${String(err)}` }],
        structuredContent: {
          ok: false,
          error_code: "internal_error",
          error_message: String(err),
          ...(args.echo !== undefined ? { echo: args.echo } : {}),
        },
        isError: true,
      },
    };
  }
}

/** 供测试断言用：确认注册表里只有任务书允许的那个工具。 */
export function toolNames(): string[] {
  return TOOL_REGISTRY.map((t) => t.name);
}

export type { Env };
