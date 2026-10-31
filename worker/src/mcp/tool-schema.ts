// ============================================================================
//  工具定义的数据结构与构造器
// ============================================================================
//
// 这里只放"工具长什么样"，不放任何执行逻辑。本机能力（Phase 3）有二十多个，
// 如果每个都手写 inputSchema/outputSchema/annotations，重复部分会淹没真正重要的
// 差异（参数规则、只读与否、是否破坏性），也更容易漏掉 `additionalProperties: false`。
//
// ⚠ 构造器只负责**声明**，不负责**校验** —— 真正的执行依据仍然是
//    `parseToolArguments()` 里的手写白名单与 [ArgumentRule]。inputSchema 是给
//    客户端看的说明，客户端可以不遵守，服务端不能依赖它。
// ============================================================================

/** 工具走哪条通道（与 tools.ts 中的说明保持一致）。 */
export type ToolTransport = "ping" | "tool" | "hub_catalog" | "hub_schema" | "hub_tool";

/**
 * 单个参数的**服务端**校验规则。
 *
 * 不写规则 = 沿用通用通道的默认规则（字符串 ≤512、数字有限、布尔/null 原样）。
 * 规则的取值上限与 Android 侧 `CapabilityArgs` 保持一致：手机仍然是最终裁判，
 * 这里负责在请求离开服务端之前挡住明显越界/超长的输入。
 */
export type ArgumentRule =
  | { kind: "string"; maxLength?: number; enum?: readonly string[] }
  | { kind: "integer"; min?: number; max?: number; enum?: readonly number[] }
  | { kind: "boolean" }
  /**
   * 日历事件 ID / 日历 ID：Android 侧同时接受数字或数字字符串。
   * 这里也接受两种形状，但要求是纯数字且不超过 18 位（与手机侧一致）。
   */
  | { kind: "id"; maxLength?: number }
  | { kind: "integer_array"; maxItems: number; min?: number; max?: number }
  | { kind: "string_array"; maxItems: number; maxLength?: number; enum?: readonly string[] };

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
  /** 按参数名给出的服务端校验规则；未列出的参数走默认规则。 */
  argRules?: Readonly<Record<string, ArgumentRule>>;
  inputSchema: Record<string, unknown>;
  outputSchema?: Record<string, unknown>;
  annotations?: Record<string, unknown>;
}

/** 所有工具都有的两个参数。 */
export const COMMON_ARG_KEYS = ["device_id", "timeout_ms"] as const;

export const DEVICE_ID_SCHEMA = {
  type: "string",
  maxLength: 128,
  description:
    "目标设备 ID。省略时使用服务端 DEFAULT_DEVICE_ID；若服务端只有一台**有效**设备则自动选中；否则报 ambiguous_device。凭据已被撤销的设备一律报 device_revoked。",
} as const;

export const TIMEOUT_SCHEMA = {
  type: "integer",
  minimum: 50,
  maximum: 60000,
  description: "本次等待手机响应的超时（毫秒）。省略时用服务端 PHONE_PING_TIMEOUT_MS（默认 10000）。",
} as const;

/** 结果信封：所有本机工具共用。手机返回的同名键会被丢弃，不能覆盖这些值。 */
const RESULT_ENVELOPE_PROPERTIES = {
  ok: { type: "boolean" },
  request_id: { type: "string" },
  device_id: { type: "string" },
  tool_name: { type: "string" },
  rtt_ms: { type: "number" },
  server_timestamp: { type: "number" },
  connection_generation: { type: "number" },
  error_code: { type: "string" },
  error_message: { type: "string" },
} as const;

export interface DeviceToolSpec {
  name: string;
  title: string;
  description: string;
  /** 工具自己的参数（公共的 device_id / timeout_ms 由构造器补上）。 */
  properties?: Record<string, Record<string, unknown>>;
  required?: readonly string[];
  argRules?: Record<string, ArgumentRule>;
  /** 结果里除信封之外的字段。 */
  outputProperties?: Record<string, Record<string, unknown>>;
  readOnly?: boolean;
  /** 删除/覆盖类操作。写操作但只增不改的（发通知、创建备忘）应为 false。 */
  destructive?: boolean;
  idempotent?: boolean;
}

/**
 * 构造一个走通用通道（`transport: "tool"`）的本机能力工具。
 *
 * `allowedArgs` 直接由 `properties` 的键推出：这样"声明了参数但没进白名单"
 * 或"白名单里有参数却没声明"都不可能发生。
 */
export function deviceTool(spec: DeviceToolSpec): ToolDefinition {
  const properties = spec.properties ?? {};
  const allowedArgs = Object.keys(properties);

  return {
    name: spec.name,
    title: spec.title,
    description: spec.description,
    transport: "tool",
    allowedArgs,
    ...(spec.argRules !== undefined ? { argRules: spec.argRules } : {}),
    inputSchema: {
      type: "object",
      properties: {
        device_id: DEVICE_ID_SCHEMA,
        timeout_ms: TIMEOUT_SCHEMA,
        ...properties,
      },
      required: spec.required ?? [],
      additionalProperties: false,
    },
    outputSchema: {
      type: "object",
      properties: {
        ...RESULT_ENVELOPE_PROPERTIES,
        ...(spec.outputProperties ?? {}),
      },
      required: ["ok"],
    },
    annotations: {
      readOnlyHint: spec.readOnly === true,
      // 默认 **true**（MCP 规范如此）：只有明确声明"只增不改"的写操作才关闭它，
      // 否则客户端会把"创建/删除"当成可随意重试的操作。
      destructiveHint: spec.destructive ?? (spec.readOnly === true ? false : true),
      idempotentHint: spec.idempotent ?? spec.readOnly === true,
      openWorldHint: true,
    },
  };
}
