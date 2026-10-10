// ============================================================================
//  结构化日志 —— 安全字段**白名单**
// ============================================================================
//
//  ── 为什么从黑名单改成白名单（Gate 0.1 审计 R2）──────────────────────────
//
//  Gate 0 的做法是"按键名脱敏"：键名里出现 token/secret/authorization… 就替换掉。
//  这有个结构性漏洞 —— **它只防得住起对了名字的字段**。真实世界里凭据是这么漏的：
//
//      logEvent("warn", "upstream said", { payload: `token=${t}` });   // payload 不在黑名单里
//      logEvent("warn", "failed", { error: String(err) });             // 异常字符串里带 Authorization
//
//  现在反过来：**只有出现在白名单里的字段名才可能被记录**，其余一律丢弃。
//  一个字段能被写进日志，必须有人先把它加进 `SAFE_FIELDS` —— 那是个需要解释的动作，
//  而不是"顺手多传一个字段"。
//
//  ── 四道闸门 ──────────────────────────────────────────────────────────────
//
//    闸 1  键名白名单：不在 `SAFE_FIELDS` 里的键整个丢掉，不递归、不留记号。
//    闸 2  只收标量：白名单字段的值必须是 string/number/boolean/null。
//          传对象或数组 → 换成 "[not-a-scalar]"。这条直接掐死"令牌藏在嵌套结构里"。
//    闸 3  错误类别固定：`errorCode` 必须在 `ERROR_CATEGORIES` 里，否则记 "unclassified"。
//          所以异常堆栈、异常 message、上游返回体**没有**任何路径能进来。
//    闸 4  整行终检：把最终要输出的那一行再做一次凭据形状扫描，
//          命中就整行换成 `{"log_withheld":true}`。前三条万一有疏漏，这条兜底，
//          保证"输出的日志行里永远不会出现凭据形状的字符串"是个可断言的性质。
//
//  `droppedFields` 记录被闸 1 丢掉了几个字段 —— 用来发现"某个字段一直没被记上"
//  这类 bug，但**不记键名**（键名本身也可能被人塞进敏感信息）。
//
//  所有日志必须走 `logEvent()`。`worker/test/tools.test.ts` 里有一条静态扫描守着
//  "worker/src 下除本文件外不许直接 console.*"，防止有人另开一条不受这四道闸门
//  约束的日志通道。
// ============================================================================

/**
 * 允许出现在日志里的字段名。
 *
 * 收录标准：**这个字段的值不可能是凭据，且排障时确实需要**。
 * 值本身还要再过闸 2/闸 4，所以这里宽一点是安全的；窄一点才是。
 */
export const SAFE_FIELDS: ReadonlySet<string> = new Set([
  // 标识（deviceId 不是秘密：能连上就已经通过凭据鉴权了）
  "deviceId",
  "deviceClass",
  "authenticatedDeviceId",
  "claimedDeviceId",
  // 连接代次
  "generation",
  "socketGeneration",
  "currentGeneration",
  "connectionGeneration",
  // 请求与协议
  "requestId",
  "method",
  "path",
  "route",
  "protocolVersion",
  "requestedProtocolVersion",
  "negotiatedProtocolVersion",
  // 结论与原因（都是本仓库自己写死的字面量，不是外部输入）
  "reason",
  "cause",
  "outcome",
  "status",
  "errorCode",
  // 计数与度量
  "discarded",
  "replaced",
  "pending",
  "pendingMirror",
  "byteLength",
  "limit",
  "count",
  "idleMs",
  "rttMs",
  "elapsedMs",
  "closeCode",
  // 客户端自报的版本信息（hello 帧）
  "appVersion",
  "androidSdk",
  "contract",
]);

/**
 * 固定的错误类别。
 *
 * 审计要求"对需要保留的错误信息使用固定错误类别" —— 也就是说日志里可以出现
 * "storage_failure"，但不可以出现 `String(err)` 或者 `err.stack`。
 * 加新类别是显式的、需要 review 的动作。
 */
export const ERROR_CATEGORIES: ReadonlySet<string> = new Set([
  "bad_request",
  "unauthorized",
  "internal_error",
  "unclassified",
  "storage_failure",
  "socket_send_failed",
  "welcome_send_failed",
  "tool_dispatch_failed",
  "initialize_session_failed",
  "unhandled_exception",
  "message_too_large",
  "malformed_message",
  "unsupported_type",
  "unknown_request_id",
  "phone_replaced",
  "phone_disconnected",
]);

/** 闸 3 的兜底值：类别不在上表里就记这个。 */
export const UNCLASSIFIED_ERROR = "unclassified";

/** 闸 2 的占位符。 */
export const NOT_A_SCALAR = "[not-a-scalar]";

/**
 * 闸 4 用的凭据形状。**故意写得宽**：宁可误杀一整行日志，也不要漏掉一条凭据。
 *
 *   · GitHub 令牌           ghp_ / gho_ / ghu_ / ghs_ / ghr_
 *   · OpenAI 风格           sk-…
 *   · PEM 私钥头            -----BEGIN … PRIVATE KEY-----
 *   · Authorization 头      Bearer <东西>
 *   · 高熵串                长度 ≥ 40 的 base64/hex/url-safe 连续串
 *     （sha256 hex 也是 64 位，会被命中 —— 但我们从不记录摘要，所以这是安全的误杀）
 */
export const CREDENTIAL_VALUE_PATTERN =
  /(gh[pousr]_[A-Za-z0-9]{20,}|sk-[A-Za-z0-9_-]{16,}|-----BEGIN [A-Z ]*PRIVATE KEY-----|[Bb]earer[ :=]+\S{8,}|\b[A-Za-z0-9_+/=-]{40,}\b)/;

/** 形状可疑时的替换值。 */
export const WITHHELD = "[redacted]";

function looksLikeCredential(value: string): boolean {
  return CREDENTIAL_VALUE_PATTERN.test(value);
}

export type LogLevel = "debug" | "info" | "warn" | "error";

export interface LogFields {
  [key: string]: unknown;
}

export interface SanitizeResult {
  /** 只含白名单字段、且值都已过闸 2/闸 3 的对象。 */
  safe: Record<string, string | number | boolean | null>;
  /** 被闸 1/闸 2 丢掉的字段个数。 */
  droppedFields: number;
}

/**
 * 把调用方给的字段过成可安全记录的形式。
 *
 * 这是纯函数，测试可以直接调它；但**它不负责闸 4**（闸 4 需要看到完整的一行）。
 */
export function sanitizeLogFields(fields: LogFields): SanitizeResult {
  const safe: Record<string, string | number | boolean | null> = {};
  let droppedFields = 0;

  for (const [key, value] of Object.entries(fields)) {
    // 闸 1：键名白名单。
    if (!SAFE_FIELDS.has(key)) {
      droppedFields += 1;
      continue;
    }

    // 闸 2：只收标量。
    if (value === null || typeof value === "number" || typeof value === "boolean") {
      safe[key] = value;
      continue;
    }
    if (typeof value === "string") {
      safe[key] = looksLikeCredential(value) ? WITHHELD : value;
      continue;
    }
    // 对象 / 数组 / 函数 / undefined / symbol / bigint
    safe[key] = NOT_A_SCALAR;
  }

  // 闸 3：错误类别必须来自固定表。
  if (typeof safe["errorCode"] === "string" && !ERROR_CATEGORIES.has(safe["errorCode"])) {
    safe["errorCode"] = UNCLASSIFIED_ERROR;
  }

  return { safe, droppedFields };
}

/**
 * 输出一条 JSON 日志。
 *
 * 故意用 console.log/console.error：在 Workers 上这会进 Workers Logs，
 * 部署后能用 `wrangler tail` 看到，且不需要额外依赖。
 *
 * ⚠ 本文件是 worker/src 下**唯一**允许直接调用 console.* 的地方。
 */
export function logEvent(level: LogLevel, event: string, fields: LogFields = {}): void {
  const { safe, droppedFields } = sanitizeLogFields(fields);

  const payload: Record<string, unknown> = {
    ts: new Date().toISOString(),
    level,
    event,
    ...safe,
  };
  if (droppedFields > 0) payload["droppedFields"] = droppedFields;

  let line = JSON.stringify(payload);

  // 闸 4：整行终检。前三道闸若有疏漏，这里宁可把整行压成一条"我拒绝输出"的记录。
  //   注意 `event` 也**不原样保留** —— 触发终检的很可能正是它（event 名是位置参数，
  //   不受字段白名单约束），把它带上去等于把命中物又输出了一遍。
  if (looksLikeCredential(line)) {
    line = JSON.stringify({
      ts: payload["ts"],
      level,
      event: "log_withheld",
      log_withheld: true,
      reason: "credential_shaped_string_detected",
    });
  }

  if (level === "error") console.error(line);
  else if (level === "warn") console.warn(line);
  else console.log(line);
}
