// ============================================================================
//  运行期配置
// ============================================================================
//
//  设计原则：
//    1. **失败要可诊断，不要静默降级。** 所有解析问题都收集进 `problems`，
//       /healthz 会原样吐出来，测试也会断言「正常情况下 problems 必须为空」。
//    2. **敏感值只从 secret 来。** 这里只读 env，不做任何兜底默认值 ——
//       没配就是没配，鉴权直接失败，绝不生成一个"临时可用"的令牌。
//    3. **数值一律夹紧。** 用户能把超时配成 0 或 10 小时都没意义，
//       夹进一个合理区间比在别处到处判断更好。
//
//  这些值只影响本 Worker；手机侧有自己的一份（Android HubConfig），
//  两边的默认值必须一致，见 docs/PROTOCOL.md 第 7 节的表。
// ============================================================================

import type { Env } from "./env";

/** MCP 协议版本候选。必须与 docs/PROTOCOL.md 及 wrangler.jsonc 的默认值一致。 */
export const KNOWN_MCP_PROTOCOL_VERSIONS = ["2025-06-18", "2025-03-26", "2024-11-05"] as const;
export type McpProtocolVersion = (typeof KNOWN_MCP_PROTOCOL_VERSIONS)[number];

/** 内部设备通道的协议版本号。手机在 hello 帧里回显它。 */
export const PHONE_LINK_PROTOCOL_VERSION = "phone-link-v1";

/** 一条设备凭据。轮换 = 新增一条 + 给旧的那条填 revokedAt。 */
export interface DeviceCredential {
  deviceId: string;
  /** 设备明文令牌的 SHA-256，小写十六进制，64 字符。**从不**存明文。 */
  tokenSha256: string;
  label?: string;
  createdAt?: string;
  revokedAt?: string | null;
}

export interface RuntimeConfig {
  pingTimeoutMs: number;
  heartbeatIntervalMs: number;
  maxPendingRequests: number;
  maxPhoneMessageBytes: number;
  supportedProtocolVersions: string[];
  defaultDeviceId: string | null;
}

export interface AppConfig {
  runtime: RuntimeConfig;
  credentials: DeviceCredential[];
  /** 人类可读的配置问题清单。空数组 = 健康。 */
  problems: string[];
  hasMcpAccessToken: boolean;
  hasDeviceTokens: boolean;
}

const DEFAULTS = {
  pingTimeoutMs: 10_000,
  heartbeatIntervalMs: 30_000,
  maxPendingRequests: 16,
  maxPhoneMessageBytes: 65_536,
};

/**
 * 数值夹紧区间。超范围不报错，直接夹紧并记一条 problem（可诊断）。
 *
 * `pingTimeoutMs` 的下限刻意是 50ms 而不是 1s：任务书要求这些参数"必须可配置"，
 * 而一个只连本地回环的测试手机完全可以用几百毫秒的超时。上下限存在的意义只是
 * 挡掉 0 / 负数 / 荒谬的大值，不是替运维决定"多快算快"。
 * 这个区间与 PhoneSession 里单次 timeout_ms 的夹紧区间**保持一致** —— 两条路径
 * 对"最长等多久"的回答必须相同，否则配置值和实际行为会悄悄分叉。
 */
const CLAMPS = {
  pingTimeoutMs: [50, 60_000] as const,
  heartbeatIntervalMs: [5_000, 300_000] as const,
  maxPendingRequests: [1, 256] as const,
  maxPhoneMessageBytes: [1_024, 1_048_576] as const,
};

function parseBoundedInt(
  raw: string | undefined,
  fallback: number,
  bounds: readonly [number, number],
  name: string,
  problems: string[],
): number {
  if (raw === undefined || raw.trim() === "") return fallback;
  const n = Number(raw);
  if (!Number.isFinite(n) || !Number.isInteger(n)) {
    problems.push(`${name}: 不是整数（得到 ${JSON.stringify(raw)}），已回退到默认值 ${fallback}`);
    return fallback;
  }
  const [lo, hi] = bounds;
  if (n < lo || n > hi) {
    const clamped = Math.min(Math.max(n, lo), hi);
    problems.push(`${name}: ${n} 超出允许区间 [${lo}, ${hi}]，已夹紧为 ${clamped}`);
    return clamped;
  }
  return n;
}

/**
 * 解析设备凭据表。
 *
 * 接受两种形状：
 *   - JSON 数组：`[{"deviceId":"…","tokenSha256":"…"}, …]`
 *   - 单个 JSON 对象：`{"deviceId":"…","tokenSha256":"…"}`
 *
 * 非法条目**丢弃并记账**，绝不"宽容地"放行一条半成品凭据。
 */
export function parseDeviceCredentials(raw: string | undefined): {
  credentials: DeviceCredential[];
  problems: string[];
} {
  const problems: string[] = [];
  if (raw === undefined || raw.trim() === "") {
    problems.push("DEVICE_TOKENS: 未配置 —— 任何手机都无法建立受信任连接");
    return { credentials: [], problems };
  }

  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    problems.push("DEVICE_TOKENS: 不是合法 JSON");
    return { credentials: [], problems };
  }

  const list = Array.isArray(parsed) ? parsed : [parsed];
  const credentials: DeviceCredential[] = [];

  list.forEach((entry, i) => {
    if (typeof entry !== "object" || entry === null) {
      problems.push(`DEVICE_TOKENS[${i}]: 不是对象`);
      return;
    }
    const e = entry as Record<string, unknown>;
    const deviceId = typeof e.deviceId === "string" ? e.deviceId.trim() : "";
    const tokenSha256 = typeof e.tokenSha256 === "string" ? e.tokenSha256.trim().toLowerCase() : "";

    if (deviceId === "") {
      problems.push(`DEVICE_TOKENS[${i}]: 缺少 deviceId`);
      return;
    }
    if (!/^[0-9a-f]{64}$/.test(tokenSha256)) {
      problems.push(`DEVICE_TOKENS[${i}] (${deviceId}): tokenSha256 必须是 64 位小写十六进制`);
      return;
    }
    credentials.push({
      deviceId,
      tokenSha256,
      ...(typeof e.label === "string" ? { label: e.label } : {}),
      ...(typeof e.createdAt === "string" ? { createdAt: e.createdAt } : {}),
      ...(e.revokedAt === null || typeof e.revokedAt === "string" ? { revokedAt: e.revokedAt as string | null } : {}),
    });
  });

  return { credentials, problems };
}

let cached: { key: string; value: AppConfig } | null = null;

/**
 * 从 env 求值出配置。同一个 isolate 内按 env 的"形状"缓存 ——
 * Workers 的 env 在一次 isolate 生命周期里是稳定的，缓存可以省掉每请求的 JSON 解析。
 */
export function loadConfig(env: Env): AppConfig {
  const cacheKey = [
    env.PHONE_PING_TIMEOUT_MS,
    env.HEARTBEAT_INTERVAL_MS,
    env.MAX_PENDING_REQUESTS,
    env.MAX_PHONE_MESSAGE_BYTES,
    env.SUPPORTED_MCP_PROTOCOL_VERSIONS,
    env.DEFAULT_DEVICE_ID,
    env.DEVICE_TOKENS,
    env.MCP_ACCESS_TOKEN === undefined ? "<unset>" : "<set>",
  ].join("|");

  if (cached && cached.key === cacheKey) return cached.value;

  const problems: string[] = [];

  const supportedProtocolVersions = (env.SUPPORTED_MCP_PROTOCOL_VERSIONS ?? KNOWN_MCP_PROTOCOL_VERSIONS.join(","))
    .split(",")
    .map((s) => s.trim())
    .filter((s) => s !== "");
  if (supportedProtocolVersions.length === 0) {
    problems.push("SUPPORTED_MCP_PROTOCOL_VERSIONS: 解析结果为空，已回退到内置清单");
    supportedProtocolVersions.push(...KNOWN_MCP_PROTOCOL_VERSIONS);
  }

  const creds = parseDeviceCredentials(env.DEVICE_TOKENS);
  problems.push(...creds.problems);

  const hasMcpAccessToken = (env.MCP_ACCESS_TOKEN ?? "").trim() !== "";
  if (!hasMcpAccessToken) {
    problems.push("MCP_ACCESS_TOKEN: 未配置 —— /mcp 会以 503 拒绝所有请求（失败关闭）");
  }

  const config: AppConfig = {
    runtime: {
      pingTimeoutMs: parseBoundedInt(env.PHONE_PING_TIMEOUT_MS, DEFAULTS.pingTimeoutMs, CLAMPS.pingTimeoutMs, "PHONE_PING_TIMEOUT_MS", problems),
      heartbeatIntervalMs: parseBoundedInt(env.HEARTBEAT_INTERVAL_MS, DEFAULTS.heartbeatIntervalMs, CLAMPS.heartbeatIntervalMs, "HEARTBEAT_INTERVAL_MS", problems),
      maxPendingRequests: parseBoundedInt(env.MAX_PENDING_REQUESTS, DEFAULTS.maxPendingRequests, CLAMPS.maxPendingRequests, "MAX_PENDING_REQUESTS", problems),
      maxPhoneMessageBytes: parseBoundedInt(env.MAX_PHONE_MESSAGE_BYTES, DEFAULTS.maxPhoneMessageBytes, CLAMPS.maxPhoneMessageBytes, "MAX_PHONE_MESSAGE_BYTES", problems),
      supportedProtocolVersions,
      defaultDeviceId: env.DEFAULT_DEVICE_ID?.trim() || null,
    },
    credentials: creds.credentials,
    problems,
    hasMcpAccessToken,
    hasDeviceTokens: creds.credentials.length > 0,
  };

  cached = { key: cacheKey, value: config };
  return config;
}

/** 测试用：清掉模块级缓存，避免上一个用例的 env 串到下一个。 */
export function resetConfigCache(): void {
  cached = null;
}

/** 某设备当前**未撤销**的凭据。 */
export function activeCredentialsFor(config: AppConfig, deviceId: string): DeviceCredential[] {
  return config.credentials.filter((c) => c.deviceId === deviceId && (c.revokedAt === null || c.revokedAt === undefined));
}

/**
 * 推断本次 `phone_ping` 该发给哪台设备。
 *
 * 优先级：显式参数 > DEFAULT_DEVICE_ID > 唯一一台配置了凭据的设备。
 * 推断不出来就**明确报错**，绝不"随便挑一台" —— 那会把指令发到错误的手机上。
 */
export function resolveDeviceId(
  config: AppConfig,
  requested: string | undefined,
): { ok: true; deviceId: string } | { ok: false; code: "unknown_device" | "ambiguous_device"; message: string } {
  if (requested !== undefined) {
    const creds = activeCredentialsFor(config, requested);
    if (creds.length === 0) {
      return { ok: false, code: "unknown_device", message: `device_id "${requested}" 没有任何有效凭据` };
    }
    return { ok: true, deviceId: requested };
  }

  if (config.runtime.defaultDeviceId) {
    const creds = activeCredentialsFor(config, config.runtime.defaultDeviceId);
    if (creds.length > 0) return { ok: true, deviceId: config.runtime.defaultDeviceId };
  }

  const known = [...new Set(config.credentials.map((c) => c.deviceId))];
  if (known.length === 1) return { ok: true, deviceId: known[0]! };

  return {
    ok: false,
    code: "ambiguous_device",
    message: known.length === 0
      ? "服务端未配置任何设备，请先配置 DEVICE_TOKENS"
      : `未指定 device_id，且服务端配置了 ${known.length} 台设备，无法唯一推断`,
  };
}
