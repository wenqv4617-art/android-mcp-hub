// ============================================================================
//  内部设备通道的消息类型与解析
// ============================================================================
//
//  规范见 docs/PROTOCOL.md。这里的解析器是**唯一**允许把线上字节变成
//  TypeScript 对象的地方 —— 所有别处都只处理已解析的联合类型，
//  这样"手机发来一个怪东西"就只可能在一个文件里出问题。
//
//  解析原则：
//    · 不认识的东西**不抛异常**，返回 `Unknown` 或 `Invalid`，由调用方决定记账方式；
//    · 缺字段就是不合法，绝不猜默认值（猜出来的 request_id 会把响应配错）；
//    · 类型检查严格（`request_id` 必须是字符串，不能是数字）。
// ============================================================================

/** 服务端 → 手机：要求一次 ping 往返。 */
export interface PingFrame {
  type: "phone_ping";
  request_id: string;
  timestamp: number;
}

/** 服务端 → 手机：连接建立后的第一帧。 */
export interface WelcomeFrame {
  type: "welcome";
  device_id: string;
  connection_generation: number;
  server_time: number;
  heartbeat_interval_ms: number;
  ping_timeout_ms: number;
  max_pending_requests: number;
  protocol_version: string;
}

export interface HeartbeatAckFrame {
  type: "heartbeat_ack";
  server_time: number;
}

export interface ServerErrorFrame {
  type: "error";
  code: string;
  message: string;
}

// ---- Gate 2：通用工具帧 -----------------------------------------------------
//
// 与 phone_ping/phone_pong 并存，**不替换**它。两者的差别只有一个：
// ping 的语义写死在协议里（"回一个 pong 就行"），tool_request 的语义由
// `tool_name` 和 `arguments` 决定 —— 所以工具名必须走显式白名单。

/** 服务端 → 手机：请求执行一个具名只读能力。 */
export interface ToolRequestFrame {
  type: "tool_request";
  request_id: string;
  /** 工具名。必须命中服务端与手机两侧共用的白名单。 */
  tool_name: string;
  /**
   * 工具参数。**原样透传**给手机，由手机再校验一次。
   *
   * 传 undefined 与传 `{}` 在本协议里等价；这里统一规范化成 `{}`，
   * 免得手机侧要处理三种"没有参数"的写法。
   */
  arguments: Record<string, unknown>;
}

/**
 * 手机 → 服务端：工具执行结果。
 *
 * `ok: true` 时 `error` 必须缺省，`ok: false` 时 `result` 必须缺省 ——
 * 解析器不接受"又成功又失败"的帧，那种帧一律按 invalid 丢掉。
 */
export interface ToolResultFrame {
  type: "tool_result";
  request_id: string;
  ok: boolean;
  result?: Record<string, unknown>;
  error?: { code: string; message: string };
}

/** 服务端 → 手机的帧联合。 */
export type ServerFrame = PingFrame | WelcomeFrame | HeartbeatAckFrame | ServerErrorFrame | ToolRequestFrame;

/** 手机 → 服务端：对 ping 的应答。 */
export interface PongFrame {
  type: "phone_pong";
  request_id: string;
  timestamp: number;
}

/** 手机 → 服务端：连接后可选的自报家门。 */
export interface HelloFrame {
  type: "hello";
  device_id?: string;
  protocol_version?: string;
  app_version?: string;
  android_sdk?: number;
}

/** 手机 → 服务端：应用层心跳。 */
export interface PhoneHeartbeatFrame {
  type: "heartbeat";
  timestamp?: number;
}

export type IncomingFrame =
  | PongFrame
  | HelloFrame
  | PhoneHeartbeatFrame
  | ToolResultFrame
  | { type: "unknown"; raw_type: string }
  | { type: "invalid"; reason: string };

export const SERVER_ERROR_CODES = [
  "message_too_large",
  "malformed_message",
  "unsupported_type",
  "unknown_request_id",
  /** Gate 1.5 / A1：设备凭据已撤销，连接必须结束。 */
  "device_revoked",
] as const;
export type ServerErrorCode = (typeof SERVER_ERROR_CODES)[number];

/**
 * `tool_result.error.code` 的闭集（Gate 2）。
 *
 * 与 [SERVER_ERROR_CODES] 是**两套**东西，刻意分开：
 *   · SERVER_ERROR_CODES 描述"这条服务端下行帧为什么被拒绝"；
 *   · 这一套描述"手机没能完成这个工具，原因是什么"。
 *
 * 手机侧报一个不在闭集里的 code 不算协议错误（仍然会结算那个请求），
 * 但会被归一到 `tool_failed`，并在 message 里保留原始字符串 ——
 * 这样新版本手机加一个新 code 不会把旧版本服务端的匹配逻辑搞乱。
 */
export const TOOL_RESULT_ERROR_CODES = [
  /** 手机的白名单里没有这个工具名。 */
  "unsupported_tool",
  /** 参数结构不合法（缺字段 / 类型不对 / 多出字段）。 */
  "invalid_arguments",
  /** 执行期抛异常。 */
  "tool_failed",
  /** 能力本身可用，但当前拿不到数据（例如传感器未就绪）。 */
  "tool_unavailable",
  /** 本地授权策略拒绝执行该能力。 */
  "not_permitted",
] as const;
export type ToolResultErrorCode = (typeof TOOL_RESULT_ERROR_CODES)[number];

/**
 * 解析一帧手机上行消息。
 *
 * @param raw 原始文本帧
 */
export function parseIncomingFrame(raw: string): IncomingFrame {
  let value: unknown;
  try {
    value = JSON.parse(raw);
  } catch {
    return { type: "invalid", reason: "not_json" };
  }

  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    return { type: "invalid", reason: "not_an_object" };
  }

  const obj = value as Record<string, unknown>;
  const type = obj["type"];
  if (typeof type !== "string" || type === "") {
    return { type: "invalid", reason: "missing_type" };
  }

  switch (type) {
    case "phone_pong": {
      const requestId = obj["request_id"];
      if (typeof requestId !== "string" || requestId === "") {
        return { type: "invalid", reason: "phone_pong_missing_request_id" };
      }
      const ts = obj["timestamp"];
      return {
        type: "phone_pong",
        request_id: requestId,
        timestamp: typeof ts === "number" && Number.isFinite(ts) ? ts : 0,
      };
    }

    case "hello": {
      const frame: HelloFrame = { type: "hello" };
      if (typeof obj["device_id"] === "string") frame.device_id = obj["device_id"];
      if (typeof obj["protocol_version"] === "string") frame.protocol_version = obj["protocol_version"];
      if (typeof obj["app_version"] === "string") frame.app_version = obj["app_version"];
      if (typeof obj["android_sdk"] === "number") frame.android_sdk = obj["android_sdk"];
      return frame;
    }

    case "heartbeat": {
      const ts = obj["timestamp"];
      return {
        type: "heartbeat",
        timestamp: typeof ts === "number" && Number.isFinite(ts) ? ts : undefined,
      };
    }

    case "tool_result": {
      const requestId = obj["request_id"];
      if (typeof requestId !== "string" || requestId === "") {
        return { type: "invalid", reason: "tool_result_missing_request_id" };
      }
      const ok = obj["ok"];
      if (typeof ok !== "boolean") {
        return { type: "invalid", reason: "tool_result_missing_ok" };
      }
      if (ok) {
        const result = obj["result"];
        if (result !== undefined && (typeof result !== "object" || result === null || Array.isArray(result))) {
          return { type: "invalid", reason: "tool_result_bad_result" };
        }
        // `ok: true` 时不接受 error 字段：一个"既成功又失败"的帧无法解释，
        // 与其猜哪一半是真的，不如整帧丢弃（调用方会走超时，不会虚假成功）。
        if (obj["error"] !== undefined) {
          return { type: "invalid", reason: "tool_result_conflicting_error" };
        }
        return { type: "tool_result", request_id: requestId, ok: true, result: (result as Record<string, unknown> | undefined) ?? {} };
      }
      const err = obj["error"];
      if (typeof err !== "object" || err === null || Array.isArray(err)) {
        return { type: "invalid", reason: "tool_result_missing_error" };
      }
      const errObj = err as Record<string, unknown>;
      const code = typeof errObj["code"] === "string" && errObj["code"] !== "" ? errObj["code"] : null;
      if (code === null) return { type: "invalid", reason: "tool_result_missing_error_code" };
      const message = typeof errObj["message"] === "string" ? errObj["message"] : "";
      if (obj["result"] !== undefined) {
        return { type: "invalid", reason: "tool_result_conflicting_result" };
      }
      return { type: "tool_result", request_id: requestId, ok: false, error: { code, message } };
    }

    default:
      return { type: "unknown", raw_type: type };
  }
}

/**
 * 把手机报来的 `tool_result.error.code` 归一到一个闭集成员。
 *
 * 认不出来的 code **不丢帧**，只是降级成 `tool_failed` ——
 * 丢帧会让一个已经拿到结果的请求白白超时，那是更糟的失败方式。
 */
export function normalizeToolErrorCode(code: string): ToolResultErrorCode {
  return (TOOL_RESULT_ERROR_CODES as readonly string[]).includes(code) ? (code as ToolResultErrorCode) : "tool_failed";
}

/** 组装服务端下行帧为文本。集中在这里，保证字段名永远一致。 */
export function encodeServerFrame(frame: ServerFrame): string {
  return JSON.stringify(frame);
}
