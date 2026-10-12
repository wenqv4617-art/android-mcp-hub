// ============================================================================
//  MCP 工具注册表 + 通用工具分发
// ============================================================================
//
//  Gate 0/1 只暴露一个工具（`phone_ping`），Gate 2 起是"显式注册表 + 两条通道"：
//
//    · `transport: "ping"` —— 沿用 Gate 1 的 phone_ping / phone_pong 字节协议，
//      一行都不改。这是**兼容性**要求：已经装在手机上的旧版 App 必须继续可用。
//    · `transport: "tool"` —— Gate 2 通用通道，走 tool_request / tool_result。
//      手机不认识它就直接不回，于是它必然超时失败 —— 这正是"旧 App + 新 Worker"
//      应有的行为，而不是让某个请求悄悄成功。
//
//  ⚠ 这个文件里**没有**任何"按名字找到函数并调用"的路径。工具只有两种：
//    在 `TOOL_REGISTRY` 里显式注册的，和不存在的。没有反射、没有动态加载、
//    没有把 arguments 当成代码的路径。
//
//  ⚠ 工具的成功路径**只有一条**：Worker → PhoneSession Durable Object →
//    真实 WebSocket → 手机的 phone_pong / tool_result。Worker 里没有任何地方
//    能"直接返回一个结果" —— 没有手机连接时，它必然是 isError。
// ============================================================================

import type { AppConfig } from "../config";
import { resolveDeviceId } from "../config";
import type { Env } from "../env";
import type { PingOutcome, ToolOutcome } from "../phone/phone-session";
import { logEvent } from "../util/log";
import { RPC_ERRORS } from "./jsonrpc";

/**
 * 工具走哪条通道。
 *
 * `"ping"` 是 Gate 1 遗留的专用通道（请求与响应都是一个专用帧类型）；
 * `"tool"` 是 Gate 2 的通用通道。新增工具**默认应该用 `"tool"`** ——
 * `"ping"` 上不再添任何工具，它只是为了让旧手机继续能 ping。
 */
export type ToolTransport = "ping" | "tool";

export interface ToolDefinition {
  name: string;
  title: string;
  description: string;
  transport: ToolTransport;
  /**
   * `arguments` 里**允许出现**的键（除公共键 `device_id` / `timeout_ms` 之外）。
   *
   * 白名单而不是黑名单：黑名单漏一个键就是"一个新字段悄悄透传到手机上"，
   * 而白名单漏一个键只会让某个功能用不了 —— 后者是可诊断的。
   */
  allowedArgs: readonly string[];
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

/** 所有工具都有的两个参数。 */
const COMMON_ARG_KEYS = ["device_id", "timeout_ms"] as const;

const DEVICE_ID_SCHEMA = {
  type: "string",
  maxLength: 128,
  description:
    "目标设备 ID。省略时使用服务端 DEFAULT_DEVICE_ID；若服务端只有一台**有效**设备则自动选中；否则报 ambiguous_device。凭据已被撤销的设备一律报 device_revoked。",
} as const;

const TIMEOUT_SCHEMA = {
  type: "integer",
  minimum: 50,
  maximum: 60000,
  description: "本次等待手机响应的超时（毫秒）。省略时用服务端 PHONE_PING_TIMEOUT_MS（默认 10000）。",
} as const;

export const PHONE_PING_TOOL: ToolDefinition = {
  name: "phone_ping",
  title: "Phone ping",
  description:
    "通过 Cloudflare Durable Object 向已连接的 Android 手机发送一次 phone_ping，并等待手机回 phone_pong。" +
    "往返时间（RTT）由服务端测量。手机离线、超时或连接中断时返回 isError=true 的结构化错误，" +
    "不会伪造成功响应，也不会自动重放。",
  transport: "ping",
  allowedArgs: ["echo"],
  inputSchema: {
    type: "object",
    properties: {
      device_id: DEVICE_ID_SCHEMA,
      timeout_ms: TIMEOUT_SCHEMA,
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

export const BATTERY_GET_STATUS_TOOL: ToolDefinition = {
  name: "battery_get_status",
  title: "Battery status (read-only)",
  description:
    "读取已授权 Android 手机当前的电池状态：电量百分比、是否正在充电、充电来源、是否处于省电模式。" +
    "只读、无副作用、不申请任何 Android 危险权限，不上传设备标识符，不读取用户文件。" +
    "系统没有提供某个字段时该字段返回 null 或 \"unavailable\"，**不会编造数值**。",
  transport: "tool",
  // 刻意不接受任何能力参数：这个能力没有可调项，多一个键就多一条要审的路径。
  allowedArgs: [],
  inputSchema: {
    type: "object",
    properties: {
      device_id: DEVICE_ID_SCHEMA,
      timeout_ms: TIMEOUT_SCHEMA,
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
      tool_name: { type: "string" },
      rtt_ms: { type: "number" },
      server_timestamp: { type: "number" },
      connection_generation: { type: "number" },
      battery_level_percent: { type: ["integer", "null"] },
      charging: { type: ["boolean", "null"] },
      charging_source: { type: "string" },
      power_save_mode: { type: ["boolean", "null"] },
      error_code: { type: "string" },
      error_message: { type: "string" },
    },
    required: ["ok"],
  },
  annotations: {
    readOnlyHint: true,
    idempotentHint: true,
    // 读的是**远端设备**的状态，所以是 openWorld —— 与 phone_ping 同理。
    openWorldHint: true,
  },
};

export const DEVICE_GET_NETWORK_TOOL: ToolDefinition = {
  name: "device_get_network",
  title: "Network status (read-only)",
  description:
    "读取已授权 Android 手机当前的网络连接状态：是否有活动网络、系统是否验证过联网能力、" +
    "主要传输类型（wifi/cellular/vpn/ethernet/...）、所有传输类型、是否按流量计费。" +
    "只读、无副作用，不申请定位权限、不读取 SSID/BSSID/MAC/IP/SIM 等标识，" +
    "**不会主动访问外部网站来试探网络**。connected 只表示有活动网络，不代表互联网一定可用；" +
    "系统没有报告的字段返回 null，**不会推测**。",
  transport: "tool",
  // 与 battery_get_status 同理：没有可调项，多一个键就多一条要审的路径。
  allowedArgs: [],
  inputSchema: {
    type: "object",
    properties: {
      device_id: DEVICE_ID_SCHEMA,
      timeout_ms: TIMEOUT_SCHEMA,
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
      tool_name: { type: "string" },
      rtt_ms: { type: "number" },
      server_timestamp: { type: "number" },
      connection_generation: { type: "number" },
      connected: { type: "boolean" },
      validated: { type: ["boolean", "null"] },
      // 主要传输类型；无活动网络时是 "none"，拿不到类型时是 "unknown"。
      transport: { type: "string" },
      transports: { type: "array", items: { type: "string" } },
      metered: { type: ["boolean", "null"] },
      internet_capable: { type: ["boolean", "null"] },
      error_code: { type: "string" },
      error_message: { type: "string" },
    },
    required: ["ok"],
  },
  annotations: {
    readOnlyHint: true,
    idempotentHint: true,
    openWorldHint: true,
  },
};

export const DEVICE_GET_STORAGE_TOOL: ToolDefinition = {
  name: "device_get_storage",
  title: "Storage capacity (read-only)",
  description:
    "读取已授权 Android 手机内部存储（含内部共享存储）的总容量、可用容量与已用比例。" +
    "只读、无副作用，不申请 MANAGE_EXTERNAL_STORAGE / READ_MEDIA_* 等任何文件权限，" +
    "不枚举用户文件、不读取文件内容。used_bytes = total_bytes - available_bytes，" +
    "表示**这个文件系统已被占用**的空间（系统分区、应用数据、缓存都在里面），" +
    "不等于\"用户文件占用了多少\"。",
  transport: "tool",
  allowedArgs: [],
  inputSchema: {
    type: "object",
    properties: {
      device_id: DEVICE_ID_SCHEMA,
      timeout_ms: TIMEOUT_SCHEMA,
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
      tool_name: { type: "string" },
      rtt_ms: { type: "number" },
      server_timestamp: { type: "number" },
      connection_generation: { type: "number" },
      // 统计的是哪个卷；当前恒为 "internal_data"。
      volume: { type: "string" },
      total_bytes: { type: "number" },
      available_bytes: { type: "number" },
      used_bytes: { type: "number" },
      usage_percent: { type: ["number", "null"] },
      error_code: { type: "string" },
      error_message: { type: "string" },
    },
    required: ["ok"],
  },
  annotations: {
    readOnlyHint: true,
    idempotentHint: true,
    openWorldHint: true,
  },
};

/**
 * 唯一的一张工具清单。`tools/list` 直接读它，`tools/call` 也只认它。
 *
 * 顺序稳定（不排序、不动态生成）是为了让 `tools/list` 的输出对客户端可预测，
 * 也让测试可以用 `toEqual([...])` 这种"整表断言"而不是"包含断言"。
 */
export const TOOL_REGISTRY: ToolDefinition[] = [
  PHONE_PING_TOOL,
  BATTERY_GET_STATUS_TOOL,
  DEVICE_GET_NETWORK_TOOL,
  DEVICE_GET_STORAGE_TOOL,
];

export function findTool(name: string): ToolDefinition | undefined {
  return TOOL_REGISTRY.find((t) => t.name === name);
}

export class ToolArgumentError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "ToolArgumentError";
  }
}

/** 解析后的工具调用参数：公共段 + 透传给手机的能力参数。 */
export interface ParsedToolArgs {
  device_id?: string;
  timeout_ms?: number;
  /** 只用于回显的字符串（目前只有 phone_ping 有）。不会被发给手机。 */
  echo?: string;
  /** 真正发给手机的能力参数（不含公共键与 echo）。 */
  toolArguments: Record<string, unknown>;
}

/**
 * 严格校验工具入参。
 *
 * 为什么不用现成的 JSON Schema 校验器：本项目的卖点之一就是"读代码就能确认
 * 多余字段会被拒绝"。手写校验让 `additionalProperties: false` 这件事落在
 * 我们自己的代码里，而不是藏在某个库的默认值里 —— 而 `inputSchema` 是给
 * 客户端看的**说明**，不是服务端的执行依据（客户端可以撒谎）。
 */
export function parseToolArguments(tool: ToolDefinition, raw: unknown): ParsedToolArgs {
  const out: ParsedToolArgs = { toolArguments: {} };
  if (raw === undefined || raw === null) return out;

  if (typeof raw !== "object" || Array.isArray(raw)) {
    throw new ToolArgumentError("arguments 必须是一个对象");
  }

  const obj = raw as Record<string, unknown>;
  const allowed = new Set<string>([...COMMON_ARG_KEYS, ...tool.allowedArgs, "echo"]);
  for (const key of Object.keys(obj)) {
    if (!allowed.has(key)) throw new ToolArgumentError(`arguments 里有不认识的字段：${key}`);
  }

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

  for (const key of tool.allowedArgs) {
    if (key === "echo") continue;
    const value = obj[key];
    if (value === undefined) continue;
    // 透传参数只允许**标量**。对象/数组会让手机侧的解析面变大很多，
    // 而当前没有任何能力需要它 —— 真需要时再加，并且那时候要一起加测试。
    if (typeof value === "string") {
      if (value.length > 512) throw new ToolArgumentError(`${tool.name}.${key} 过长（>512）`);
      out.toolArguments[key] = value;
    } else if (typeof value === "number") {
      if (!Number.isFinite(value)) throw new ToolArgumentError(`${tool.name}.${key} 必须是有限数字`);
      out.toolArguments[key] = value;
    } else if (typeof value === "boolean" || value === null) {
      out.toolArguments[key] = value;
    } else {
      throw new ToolArgumentError(`${tool.name}.${key} 只允许字符串、数字、布尔或 null`);
    }
  }

  return out;
}

/** `phone_ping` 专用：保持 Gate 1 时期就导出的函数与形状不变（测试仍在用它）。 */
export function parsePhonePingArgs(raw: unknown): { device_id?: string; timeout_ms?: number; echo?: string } {
  const parsed = parseToolArguments(PHONE_PING_TOOL, raw);
  const out: { device_id?: string; timeout_ms?: number; echo?: string } = {};
  if (parsed.device_id !== undefined) out.device_id = parsed.device_id;
  if (parsed.timeout_ms !== undefined) out.timeout_ms = parsed.timeout_ms;
  if (parsed.echo !== undefined) out.echo = parsed.echo;
  return out;
}

/**
 * 结果里**属于信封**的键。手机返回的 `result` 里如果出现这些键，
 * 必须丢掉：否则一台被改造过的手机可以用 `{"ok":true}` 覆盖掉失败信封，
 * 或者伪造 `request_id` 去污染别的请求的语义。
 */
const RESERVED_RESULT_KEYS = new Set([
  "ok",
  "request_id",
  "device_id",
  "tool_name",
  "rtt_ms",
  "server_timestamp",
  "device_timestamp",
  "connection_generation",
  "error_code",
  "error_message",
  "echo",
]);

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

/**
 * 把 DO 的工具返回值变成工具调用结果。
 *
 * 手机返回的 `result` 会被**摊平**进 structuredContent 顶层（而不是嵌一层
 * `result`）—— 这样输出 schema 是平的、模型读起来也少一层。摊平的前提是
 * 信封键不可覆盖，见 [RESERVED_RESULT_KEYS]。
 */
export function toolOutcomeToResult(outcome: ToolOutcome, echo?: string): ToolCallResult {
  if (!outcome.ok) {
    const structured: Record<string, unknown> = { ...outcome };
    if (echo !== undefined) structured["echo"] = echo;
    return {
      content: [{ type: "text", text: `${outcome.tool_name} 失败：${outcome.error.code} — ${outcome.error.message}` }],
      structuredContent: structured,
      isError: true,
    };
  }

  const flattened: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(outcome.result)) {
    if (RESERVED_RESULT_KEYS.has(key)) {
      logEvent("warn", "tool.result_reserved_key_dropped", {
        toolName: outcome.tool_name,
        // 键名来自手机输入，只记"发生了一次丢弃"，不把它写进日志字段。
        errorCode: "reserved_key_dropped",
      });
      continue;
    }
    flattened[key] = value;
  }

  const structured: Record<string, unknown> = {
    ok: true,
    request_id: outcome.request_id,
    device_id: outcome.device_id,
    tool_name: outcome.tool_name,
    rtt_ms: outcome.rtt_ms,
    server_timestamp: outcome.server_timestamp,
    connection_generation: outcome.connection_generation,
    ...flattened,
  };
  if (echo !== undefined) structured["echo"] = echo;

  return {
    content: [
      {
        type: "text",
        text:
          `${outcome.tool_name} 成功：设备 ${outcome.device_id} 在 ${outcome.rtt_ms} ms 内返回结果` +
          `（request_id=${outcome.request_id}，连接代次 ${outcome.connection_generation}）。` +
          JSON.stringify(flattened),
      },
    ],
    structuredContent: structured,
  };
}

export interface ToolDispatchResult {
  /** 协议级错误（JSON-RPC error）。有值时不要走 content 路径。 */
  protocolError?: { code: number; message: string; data?: unknown };
  result?: ToolCallResult;
  /**
   * 这次调用撞上了"设备存在、但凭据已被撤销"。
   *
   * 工具层只**报告**这件事，不自己做副作用 —— 它不 import Durable Object。
   * 传输层（http.ts）看到它就去通知 DO 关掉那台设备的旧连接。
   */
  revokedDeviceId?: string;
}

/** 转发一次 phone_ping（由 index.ts 注入真实实现）。 */
export type PhonePingCaller = (deviceId: string, timeoutMs: number | undefined) => Promise<PingOutcome>;

/** 转发一次通用工具调用（由 index.ts 注入真实实现）。 */
export type PhoneToolCaller = (
  deviceId: string,
  toolName: string,
  args: Record<string, unknown>,
  timeoutMs: number | undefined,
) => Promise<ToolOutcome>;

/** 统一的"设备解析失败 / 能力被禁"结果 —— 与工具种类无关，形状保持一致。 */
function deviceRefusalResult(toolName: string, code: string, message: string, echo?: string): ToolCallResult {
  return {
    content: [{ type: "text", text: `${toolName} 失败：${code} — ${message}` }],
    structuredContent: {
      ok: false,
      error_code: code,
      error_message: message,
      ...(echo !== undefined ? { echo } : {}),
    },
    isError: true,
  };
}

/**
 * 执行一次 tools/call。
 *
 * `callPhonePing` / `callPhoneTool` 由调用方注入 —— 这样工具层不直接依赖
 * Durable Object，测试可以注入一个假实现来验证"参数校验/能力授权/错误映射"
 * 这些纯逻辑。真正的转发实现在 index.ts 里，且**只有那一条**路径。
 *
 * 参数顺序上后两个是可选的：`callPhonePing` 一直是必填（Gate 1 的测试按
 * 4 参调用），`callPhoneTool` 缺失时，需要通用通道的工具一律返回
 * `internal_error` —— **绝不**降级成"假装成功"或悄悄改用 ping 通道。
 */
export async function dispatchToolCall(
  config: AppConfig,
  name: string,
  rawArgs: unknown,
  callPhonePing: PhonePingCaller,
  callPhoneTool?: PhoneToolCaller,
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

  // ① 参数校验：**在设备解析之前**。一个参数都不合法的请求不该先去做设备推断，
  //    否则错误信息会取决于服务端配了几台设备，变成"难复现"的那类问题。
  let args: ParsedToolArgs;
  try {
    args = parseToolArguments(tool, rawArgs);
  } catch (err) {
    return {
      protocolError: {
        code: RPC_ERRORS.invalidParams,
        // 审计 R2：只回我们**自己写死**的校验文案。`ToolArgumentError.message`
        // 全部是本仓库构造的字符串，不含任何外部输入；其余异常一律换成固定文案。
        message: err instanceof ToolArgumentError ? err.message : "参数校验失败",
      },
    };
  }

  // ② 能力授权之二：服务端禁用清单。放在注册表之后、设备解析之前 —— 被禁用的
  //    工具不该通过"设备存不存在"的报错把服务端的设备表**探测**出来。
  if (config.runtime.disabledTools.includes(tool.name)) {
    return {
      result: deviceRefusalResult(tool.name, "not_permitted", `工具 ${tool.name} 已被服务端禁用（PHONE_DISABLED_TOOLS）`, args.echo),
    };
  }

  // ③ 能力授权之一：设备解析。未知设备、设备已撤销、多台无法唯一推断都在这。
  const resolved = resolveDeviceId(config, args.device_id);
  if (!resolved.ok) {
    // 「设备存在但凭据已撤销」是唯一一种需要传输层**动手**的解析失败：
    // 别的失败（ID 写错、多台推断不出）没有任何东西需要关掉。
    const revokedDeviceId =
      resolved.code === "device_revoked" && resolved.deviceId !== undefined ? resolved.deviceId : undefined;
    return {
      result: deviceRefusalResult(tool.name, resolved.code, resolved.message, args.echo),
      ...(revokedDeviceId !== undefined ? { revokedDeviceId } : {}),
    };
  }

  try {
    if (tool.transport === "ping") {
      const outcome = await callPhonePing(resolved.deviceId, args.timeout_ms);
      return { result: pingOutcomeToResult(outcome, args.echo) };
    }

    if (callPhoneTool === undefined) {
      // 配置错误（不是调用方的错）：通用通道没有被接上。明确失败，不猜。
      logEvent("error", "tool.transport_unavailable", { toolName: tool.name, errorCode: "transport_unavailable" });
      return {
        result: deviceRefusalResult(tool.name, "internal_error", "内部错误：通用工具通道未启用，详情见服务端日志", args.echo),
      };
    }

    const outcome = await callPhoneTool(resolved.deviceId, tool.name, args.toolArguments, args.timeout_ms);
    return { result: toolOutcomeToResult(outcome, args.echo) };
  } catch {
    // 审计 R2：`String(err)` 既会进日志、也会顺着 tool result 回到模型上下文。
    //   两条路都不该出现内部字符串，所以这里两边都用固定文案。
    logEvent("error", "tool.phone_call_threw", {
      deviceId: resolved.deviceId,
      toolName: tool.name,
      errorCode: "tool_dispatch_failed",
    });
    return {
      result: deviceRefusalResult(tool.name, "internal_error", "内部错误：转发到手机时发生异常，详情见服务端日志", args.echo),
    };
  }
}

/** 供测试断言用：注册表是显式白名单，这里把它的名字按注册顺序列出来。 */
export function toolNames(): string[] {
  return TOOL_REGISTRY.map((t) => t.name);
}

export type { Env };
