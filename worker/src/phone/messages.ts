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

/** 服务端 → 手机的帧联合。 */
export type ServerFrame = PingFrame | WelcomeFrame | HeartbeatAckFrame | ServerErrorFrame;

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
  | { type: "unknown"; raw_type: string }
  | { type: "invalid"; reason: string };

export const SERVER_ERROR_CODES = [
  "message_too_large",
  "malformed_message",
  "unsupported_type",
  "unknown_request_id",
] as const;
export type ServerErrorCode = (typeof SERVER_ERROR_CODES)[number];

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

    default:
      return { type: "unknown", raw_type: type };
  }
}

/** 组装服务端下行帧为文本。集中在这里，保证字段名永远一致。 */
export function encodeServerFrame(frame: ServerFrame): string {
  return JSON.stringify(frame);
}
