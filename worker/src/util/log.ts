// ============================================================================
//  结构化日志 + 凭据脱敏
// ============================================================================
//
//  任务书要求「不在日志中输出 Token」。靠"写日志的时候记得别带上"是靠不住的，
//  所以这里做成**结构性的**：所有日志都必须走 `logEvent()`，它在序列化之前
//  把任何看起来是凭据的键整个换掉。
//
//  被脱敏的键名（大小写不敏感、子串匹配）：
//    token / secret / authorization / password / credential / cookie / apikey
//
//  被脱敏的位置：任意深度（数组、嵌套对象都会走一遍）。
//  被脱敏的值：`"[redacted]"`，不是空串 —— 空串会让人误以为"这个字段没有值"。
// ============================================================================

const SENSITIVE_KEY_PATTERN = /token|secret|authorization|password|credential|cookie|apikey/i;

/** 深度上限，防止有人把循环结构喂进来。 */
const MAX_DEPTH = 6;

export function redact(value: unknown, depth = 0): unknown {
  if (depth > MAX_DEPTH) return "[depth-limit]";
  if (value === null || typeof value !== "object") return value;

  if (Array.isArray(value)) return value.map((v) => redact(v, depth + 1));

  const out: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(value as Record<string, unknown>)) {
    out[k] = SENSITIVE_KEY_PATTERN.test(k) ? "[redacted]" : redact(v, depth + 1);
  }
  return out;
}

export type LogLevel = "debug" | "info" | "warn" | "error";

export interface LogFields {
  [key: string]: unknown;
}

/**
 * 输出一条 JSON 日志。
 *
 * 故意用 console.log/console.error：在 Workers 上这会进 Workers Logs，
 * 部署后能用 `wrangler tail` 看到，且不需要额外依赖。
 */
export function logEvent(level: LogLevel, event: string, fields: LogFields = {}): void {
  const payload = {
    ts: new Date().toISOString(),
    level,
    event,
    ...(redact(fields) as LogFields),
  };
  const line = JSON.stringify(payload);
  if (level === "error") console.error(line);
  else if (level === "warn") console.warn(line);
  else console.log(line);
}
