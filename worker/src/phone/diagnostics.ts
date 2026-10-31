// ============================================================================
//  双端连接诊断 —— 结构化事件缓冲区（V1.6.3）
// ============================================================================
//
//  为什么要有这一层：Worker 已有的结构化日志是"给人看的一行行文本"，而且
//  云端控制台用户登录不上；手机端只能看到自己这一侧的现象。排查 4001 /
//  connection_reset 这类问题时，真正需要的是**按设备隔离、可以被 App 查询、
//  能回答"谁先关的、为什么关"的事件序列**。
//
//  设计约束（逐条对应任务书）：
//    · 每个事件字段都来自**闭集**或经过逐字段校验的标量；不存在"自由文本"
//      字段，因此对端送来的 close reason、异常 message、令牌都不可能落进来；
//    · 缓冲区有界：最多 [DIAG_MAX_EVENTS] 条，超过 [DIAG_MAX_AGE_MS] 的
//      事件在读写时清理，丢弃数量记在 `dropped` 里 —— 用户能看出"更早的
//      事件已经看不到了"，而不是以为没发生过；
//    · 不记录 MCP 工具参数、聊天内容、通知正文、Authorization、Cookie、
//      设备令牌或任何凭据形状的字符串；
//    · 服务端主动关闭必须记录**真实调用来源**（`server_initiated` + `caller`），
//      而不是只记录手机最终看到的错误码；
//    · 如果服务端没有观察到正常关闭帧，就如实记录 `cause` 与收到的关闭码
//      （可能是 1006），**不编造** close code。
// ============================================================================

/** 缓冲区容量上限（每个设备）。 */
export const DIAG_MAX_EVENTS = 200;
/** 事件保留时长；更早的事件在读写时清理。 */
export const DIAG_MAX_AGE_MS = 24 * 60 * 60 * 1000;
/** 同时跟踪的连接打开记录上限（用于算存活时长）。 */
export const DIAG_MAX_OPEN_TRACK = 8;
/** 同时跟踪的"服务端主动关闭意图"上限。 */
export const DIAG_MAX_CLOSE_INTENTS = 8;

/** 事件来源。 */
export const DIAG_SOURCES = ["worker", "durable_object", "android"] as const;
export type DiagSource = (typeof DIAG_SOURCES)[number];

/**
 * 事件类型（闭集）。
 *
 * `device_online` / `device_offline` 是 **DO 对"设备是否在线"的判定**，
 * 不是 WebSocket 层的握手细节 —— 两者在 `server_initiated` 上可能不同。
 */
export const DIAG_EVENT_TYPES = [
  "device_online",
  "device_offline",
  "hello",
  "replaced",
  "liveness_timeout",
  "revoked",
  "auth_failure",
  "client_report",
] as const;
export type DiagEventType = (typeof DIAG_EVENT_TYPES)[number];

/**
 * `detail` 允许出现的键，以及每个键的校验规则。
 *
 * 这是白名单：不在表里的键**整个丢弃**。值的规则同样是白名单 ——
 * 枚举值只能取给定集合，数字只能是非负整数，版本号必须匹配形状。
 * 这样"手机在诊断帧里塞一段自由文本"在类型层面就没有落点。
 */
type DetailRule =
  | { kind: "int"; max: number }
  | { kind: "bool" }
  | { kind: "enum"; values: readonly string[] }
  | { kind: "version" }
  /**
   * 异常类名：不是闭集（真实异常来自 OkHttp/Okio/TLS/厂商定制），
   * 但形状受白名单约束 —— 只可能是 `Foo`、`Foo.Bar`、`Foo$Bar` 这类标识符。
   * 不接受空串、空白、冒号、URL、Bearer 令牌等任何自由文本。
   */
  | { kind: "class_name" };

const CAUSES = ["close", "error", "replaced", "liveness_timeout", "revoked", "auth_failure", "client_reported"] as const;
const CALLERS = [
  "handle_connect",
  "alarm",
  "ping_request",
  "tool_request",
  "revoke",
  "revoked_message",
  "client_close",
  "terminate_sockets",
] as const;
const NETWORKS = ["wifi", "cellular", "ethernet", "vpn", "none", "other"] as const;
const SCREENS = ["on", "off", "unknown"] as const;
const AUTH_REASONS = ["missing_device_id_or_token", "no_active_credential", "token_mismatch"] as const;
const AUTH_SCOPES = ["device", "credential"] as const;
const AUTH_SOURCES = ["worker", "durable_object"] as const;
const CLIENT_EVENTS = [
  "connected",
  "disconnected",
  "reconnecting",
  "network_changed",
  "heartbeat_timeout",
  "authentication_failed",
  "user_disconnected",
  "service_destroyed",
  "service_restarted",
  "server_close",
  "socket_failure",
  "handshake_timeout",
  "transport_close",
] as const;
const FAILURE_CATEGORIES = [
  "dns_failure",
  "connect_timeout",
  "connect_refused",
  "network_unreachable",
  "tls_failure",
  "connection_reset",
  "io_failure",
  "protocol_failure",
  "runtime_exception",
  "unknown_exception",
] as const;
const LOCAL_CLOSE_SOURCES = [
  "ui_button",
  "notification_action",
  "unknown",
  "service_destroyed",
  "network_lost",
  "watchdog",
  "transport_close",
  "app_restore",
  "revoked",
  "server_close",
] as const;
const PROTOCOL_VERSIONS = ["phone-link-v1"] as const;

const DETAIL_RULES: Readonly<Record<string, DetailRule>> = {
  cause: { kind: "enum", values: CAUSES },
  caller: { kind: "enum", values: CALLERS },
  network: { kind: "enum", values: NETWORKS },
  screen: { kind: "enum", values: SCREENS },
  auth_reason: { kind: "enum", values: AUTH_REASONS },
  auth_scope: { kind: "enum", values: AUTH_SCOPES },
  auth_source: { kind: "enum", values: AUTH_SOURCES },
  client_event: { kind: "enum", values: CLIENT_EVENTS },
  category: { kind: "enum", values: FAILURE_CATEGORIES },
  exception_class: { kind: "class_name" },
  local_close_source: { kind: "enum", values: LOCAL_CLOSE_SOURCES },
  protocol_version: { kind: "enum", values: PROTOCOL_VERSIONS },
  app_version: { kind: "version" },
  vpn: { kind: "bool" },
  replaced: { kind: "bool" },
  open_sockets: { kind: "int", max: 32 },
  replaced_count: { kind: "int", max: 32 },
  replaced_generation: { kind: "int", max: 1_000_000_000 },
  old_generation: { kind: "int", max: 1_000_000_000 },
  new_generation: { kind: "int", max: 1_000_000_000 },
  client_generation: { kind: "int", max: 1_000_000_000 },
  silent_ms: { kind: "int", max: 30 * 24 * 60 * 60 * 1000 },
  liveness_timeout_ms: { kind: "int", max: 30 * 24 * 60 * 60 * 1000 },
  heartbeat_interval_ms: { kind: "int", max: 24 * 60 * 60 * 1000 },
  heartbeat_age_ms: { kind: "int", max: 30 * 24 * 60 * 60 * 1000 },
  pid: { kind: "int", max: 4_194_304 },
  process_uptime_ms: { kind: "int", max: 365 * 24 * 60 * 60 * 1000 },
  attempt: { kind: "int", max: 10_000 },
  android_sdk: { kind: "int", max: 100 },
  pending_requests: { kind: "int", max: 10_000 },
};

/** 服务端自己写死的关闭原因标签。对端的自由文本永远不进这里。 */
export const SERVER_CLOSE_REASONS = [
  "replaced_by_newer_connection",
  "liveness_timeout",
  "device_credential_revoked",
  "client_closed",
  "peer_closed",
  "worker_closed",
  "unknown",
] as const;
export type ServerCloseReason = (typeof SERVER_CLOSE_REASONS)[number];

export type DiagDetailValue = string | number | boolean | null;

export interface DiagEvent {
  seq: number;
  /** 服务端接收/记录时刻（epoch ms）。 */
  at: number;
  /** 仅 `client_report`：手机自己报告的事件时刻（epoch ms，手机时钟）。 */
  client_at?: number;
  source: DiagSource;
  type: DiagEventType;
  /** 服务端视角的连接代次（客户端报告时以 socket 上的代次为准）。 */
  generation: number | null;
  /** 观察到的关闭码；没有观察到就为 null（不编造）。 */
  close_code: number | null;
  /** 服务端写死的原因标签，不是对端文本。 */
  close_reason: ServerCloseReason | null;
  /** true=服务端主动关闭；false=不是服务端发起；null=无法确定（如状态丢失）。 */
  server_initiated: boolean | null;
  duration_ms: number | null;
  detail: Record<string, DiagDetailValue>;
}

export interface DiagCloseIntent {
  code: number;
  reason: ServerCloseReason;
  caller: (typeof CALLERS)[number];
  at: number;
  generation: number;
}

export interface StoredDiagnostics {
  seq: number;
  events: DiagEvent[];
  /** generation → 建连时刻（用于算存活时长）。 */
  opens: Record<string, number>;
  /** generation → 服务端主动关闭意图。 */
  close_intents: Record<string, DiagCloseIntent>;
  /** 因为容量/过期而被丢弃的事件数。 */
  dropped: number;
  first_at: number | null;
}

export function emptyDiagnostics(): StoredDiagnostics {
  return { seq: 0, events: [], opens: {}, close_intents: {}, dropped: 0, first_at: null };
}

function isInt(value: unknown): value is number {
  return typeof value === "number" && Number.isFinite(value) && Number.isInteger(value);
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function clampInt(value: unknown, max: number): number | null {
  if (!isInt(value) || value < 0) return null;
  return Math.min(value, max);
}

/**
 * 校验一个 `detail` 值。返回 `undefined` 表示"这个键/值不能记录"，调用方丢弃。
 *
 * 注意 `null` 是合法的显式值（例如"未知"），与"字段不存在"区分开。
 */
export function sanitizeDetailValue(key: string, value: unknown): DiagDetailValue | undefined {
  if (value === null) return null;
  const rule = DETAIL_RULES[key];
  if (rule === undefined) return undefined;
  switch (rule.kind) {
    case "int":
      return clampInt(value, rule.max) ?? undefined;
    case "bool":
      return typeof value === "boolean" ? value : undefined;
    case "enum":
      return typeof value === "string" && (rule.values as readonly string[]).includes(value) ? value : undefined;
    case "version":
      // 版本号只允许数字/点/连字符/字母，长度受限；"Bearer xxx" 这类东西过不了。
      return typeof value === "string" && /^[0-9A-Za-z._-]{1,20}$/.test(value) ? value : undefined;
    case "class_name":
      // 标识符形状（可带包名/内部类分隔符），最长 64；空格、冒号、斜杠、引号都过不了。
      return typeof value === "string" && /^[A-Za-z_$][A-Za-z0-9_$.]{0,63}$/.test(value)
        ? value
        : undefined;
    default:
      return undefined;
  }
}

/** 只保留白名单键；按固定顺序输出，保证同一输入产生同一份 JSON（便于测试与 diff）。 */
export function sanitizeDetail(raw: unknown): Record<string, DiagDetailValue> {
  if (!isPlainObject(raw)) return {};
  const out: Record<string, DiagDetailValue> = {};
  for (const key of Object.keys(DETAIL_RULES).sort()) {
    if (!(key in raw)) continue;
    const value = sanitizeDetailValue(key, raw[key]);
    if (value !== undefined) out[key] = value;
  }
  return out;
}

function sanitizeCloseCode(code: unknown): number | null {
  // WebSocket 关闭码是 1000–4999（含应用私有区间）；0 表示"没有关闭帧"。
  if (!isInt(code) || code < 0 || code > 4999) return null;
  return code;
}

function sanitizeGeneration(value: unknown): number | null {
  return clampInt(value, 1_000_000_000);
}

/**
 * 把一条输入收敛成可存储的事件。
 *
 * 未知的 `type` / `source` 直接返回 null —— 诊断通道不能成为"往缓冲区里
 * 写任意类型"的入口。
 */
export function buildEvent(
  seq: number,
  at: number,
  input: {
    source: DiagSource;
    type: DiagEventType;
    generation?: number | null;
    close_code?: number | null;
    close_reason?: ServerCloseReason | null;
    server_initiated?: boolean | null;
    duration_ms?: number | null;
    client_at?: number | null;
    detail?: unknown;
  },
): DiagEvent | null {
  if (!(DIAG_SOURCES as readonly string[]).includes(input.source)) return null;
  if (!(DIAG_EVENT_TYPES as readonly string[]).includes(input.type)) return null;
  const event: DiagEvent = {
    seq,
    at,
    source: input.source,
    type: input.type,
    generation: sanitizeGeneration(input.generation),
    close_code: sanitizeCloseCode(input.close_code),
    close_reason:
      input.close_reason !== null &&
      input.close_reason !== undefined &&
      (SERVER_CLOSE_REASONS as readonly string[]).includes(input.close_reason)
        ? input.close_reason
        : null,
    server_initiated: typeof input.server_initiated === "boolean" ? input.server_initiated : null,
    duration_ms: clampInt(input.duration_ms, 365 * 24 * 60 * 60 * 1000),
    detail: sanitizeDetail(input.detail),
  };
  if (input.client_at !== null && input.client_at !== undefined && isInt(input.client_at) && input.client_at > 0) {
    event.client_at = input.client_at;
  }
  return event;
}

/** 按时间与容量裁剪；返回新数组与因裁剪丢弃的条数。 */
export function pruneEvents(events: DiagEvent[], now: number): { events: DiagEvent[]; dropped: number } {
  const cutoff = now - DIAG_MAX_AGE_MS;
  let kept = events.filter((e) => e.at >= cutoff);
  let dropped = events.length - kept.length;
  if (kept.length > DIAG_MAX_EVENTS) {
    dropped += kept.length - DIAG_MAX_EVENTS;
    kept = kept.slice(kept.length - DIAG_MAX_EVENTS);
  }
  return { events: kept, dropped };
}

/** 只保留最近 N 个键（对象键是数字代次的字符串形式）。 */
export function capNumericRecord<T>(record: Record<string, T>, max: number): Record<string, T> {
  const keys = Object.keys(record)
    .filter((k) => /^\d+$/.test(k))
    .sort((a, b) => Number(a) - Number(b));
  if (keys.length <= max) return record;
  const out: Record<string, T> = {};
  for (const key of keys.slice(keys.length - max)) out[key] = record[key]!;
  return out;
}

/**
 * 校验从存储里读回的诊断状态。
 *
 * 存储是本仓库自己写的，但仍要按不可信输入处理：一条损坏的记录不能让整个
 * DO 构造失败。任何不认识的结构都退化成空缓冲区，并在 `dropped` 上记一笔
 * 需要被看见的丢弃（这里直接丢掉整份状态，由调用方决定是否记录）。
 */
export function restoreDiagnostics(raw: unknown): StoredDiagnostics | null {
  if (!isPlainObject(raw)) return null;
  const seq = clampInt(raw["seq"], Number.MAX_SAFE_INTEGER) ?? 0;
  const eventsRaw = raw["events"];
  if (!Array.isArray(eventsRaw)) return null;

  const events: DiagEvent[] = [];
  for (const candidate of eventsRaw) {
    if (!isPlainObject(candidate)) continue;
    const rebuilt = buildEvent(
      clampInt(candidate["seq"], Number.MAX_SAFE_INTEGER) ?? 0,
      clampInt(candidate["at"], Number.MAX_SAFE_INTEGER) ?? 0,
      {
        source: candidate["source"] as DiagSource,
        type: candidate["type"] as DiagEventType,
        generation: candidate["generation"] as number | null | undefined,
        close_code: candidate["close_code"] as number | null | undefined,
        close_reason: candidate["close_reason"] as ServerCloseReason | null,
        server_initiated: candidate["server_initiated"] as boolean | null | undefined,
        duration_ms: candidate["duration_ms"] as number | null | undefined,
        client_at: candidate["client_at"] as number | null | undefined,
        detail: candidate["detail"],
      },
    );
    if (rebuilt !== null) events.push(rebuilt);
  }

  const opens: Record<string, number> = {};
  if (isPlainObject(raw["opens"])) {
    for (const [key, value] of Object.entries(raw["opens"] as Record<string, unknown>)) {
      const ms = clampInt(value, Number.MAX_SAFE_INTEGER);
      if (/^\d+$/.test(key) && ms !== null) opens[key] = ms;
    }
  }

  const closeIntents: Record<string, DiagCloseIntent> = {};
  if (isPlainObject(raw["close_intents"])) {
    for (const [key, value] of Object.entries(raw["close_intents"] as Record<string, unknown>)) {
      if (!isPlainObject(value)) continue;
      const code = sanitizeCloseCode(value["code"]);
      const reason = value["reason"];
      const caller = value["caller"];
      const at = clampInt(value["at"], Number.MAX_SAFE_INTEGER);
      const generation = clampInt(value["generation"], Number.MAX_SAFE_INTEGER);
      if (code === null || at === null || generation === null) continue;
      if (typeof reason !== "string" || !(SERVER_CLOSE_REASONS as readonly string[]).includes(reason)) continue;
      if (typeof caller !== "string" || !(CALLERS as readonly string[]).includes(caller)) continue;
      closeIntents[key] = { code, reason: reason as ServerCloseReason, caller: caller as DiagCloseIntent["caller"], at, generation };
    }
  }

  // 存储里的事件多于容量上限时（旧版本写入、或上次裁剪没来得及落盘），
  // 读回时截断；被截掉的条数必须累进 `dropped` —— 否则用户会以为"缓冲区里
  // 就只有这么多事件"，而实际上是有更早的事件被丢了。
  const capped = capEvents(events);
  const cappedAway = events.length - capped.length;

  return {
    seq,
    events: capped,
    opens: capNumericRecord(opens, DIAG_MAX_OPEN_TRACK),
    close_intents: capNumericRecord(closeIntents, DIAG_MAX_CLOSE_INTENTS),
    dropped: (clampInt(raw["dropped"], Number.MAX_SAFE_INTEGER) ?? 0) + cappedAway,
    first_at: clampInt(raw["first_at"], Number.MAX_SAFE_INTEGER),
  };
}

function capEvents(events: DiagEvent[]): DiagEvent[] {
  return events.length <= DIAG_MAX_EVENTS ? events : events.slice(events.length - DIAG_MAX_EVENTS);
}
