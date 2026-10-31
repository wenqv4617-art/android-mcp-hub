import ipaddr from "ipaddr.js";
import type { Env } from "../env";
import { sha256Hex } from "../util/crypto";

export const REQUEST_LIMIT = 64 * 1024;
export const RESPONSE_LIMIT = 1024 * 1024;
export class GatewayError extends Error {
  constructor(readonly code: string, readonly status = 400) { super(code); }
}

/**
 * 访问令牌的作用范围。
 *
 *  `read`  —— 只允许直接调用「已审核只读」的 grant（mode=read）；
 *  `write` —— 另外允许调用「需确认」的 grant（mode=confirm，仍需一次性票据）。
 *
 * `write` 隐含 `read`：一个能走"需确认"流程的令牌，不必再额外勾一个只读权限。
 * grant 本身仍然是硬闸门 —— scope 只能**收窄**一个令牌能碰的 grant，不能扩大。
 */
export type TokenScope = "read" | "write";

/** 严格解析管理面传入的 scope；缺省表示完整访问（旧客户端保持原行为）。 */
export function parseScopes(value: unknown): TokenScope[] | undefined {
  if (value === undefined) return undefined;
  if (!Array.isArray(value) || value.length === 0 || value.length > 2) throw new GatewayError("invalid_scopes");
  const scopes = [...new Set(value)];
  if (!scopes.every((s): s is TokenScope => s === "read" || s === "write")) throw new GatewayError("invalid_scopes");
  return scopes.sort();
}

/**
 * 宽松读取存储里返回的 scope。
 *
 * 用于 Worker 侧解析 DO 的鉴权结果：认不出的值一律**丢弃**，全部丢弃后就是
 * 空数组 —— 空数组不允许任何 grant（失败关闭）。只有字段整个缺失才表示
 * "完整访问"，那是旧版 `/auth` 的响应形状。
 */
export function readScopes(value: unknown): TokenScope[] | undefined {
  if (value === undefined || value === null) return undefined;
  if (!Array.isArray(value)) return [];
  return [...new Set(value.filter((s): s is TokenScope => s === "read" || s === "write"))].sort();
}

/** 某个 scope 组合能否使用这种模式的 grant。 */
export function scopeAllows(scopes: TokenScope[] | undefined, mode: "read" | "confirm"): boolean {
  if (scopes === undefined) return true;
  if (scopes.includes("write")) return true;
  return mode === "read" && scopes.includes("read");
}

/** Bounded reads apply to chunked bodies too; Content-Length is never trusted. */
export async function boundedText(body: ReadableStream<Uint8Array> | null, limit: number): Promise<string> {
  if (!body) return "";
  const reader = body.getReader();
  const chunks: Uint8Array[] = [];
  let size = 0;
  try {
    for (;;) {
      const { value, done } = await reader.read();
      if (done) break;
      size += value.byteLength;
      if (size > limit) throw new GatewayError("size_limit", 413);
      chunks.push(value);
    }
  } catch (error) { await reader.cancel().catch(() => {}); throw error; }
  finally { reader.releaseLock(); }
  const all = new Uint8Array(size);
  let offset = 0;
  for (const chunk of chunks) { all.set(chunk, offset); offset += chunk.byteLength; }
  return new TextDecoder().decode(all);
}

export function canonical(value: unknown): string {
  if (Array.isArray(value)) return `[${value.map(canonical).join(",")}]`;
  if (value && typeof value === "object") return `{${Object.keys(value).sort().map(k => `${JSON.stringify(k)}:${canonical((value as Record<string, unknown>)[k])}`).join(",")}}`;
  return JSON.stringify(value) ?? "null";
}
export const fingerprint = (value: unknown): Promise<string> => sha256Hex(canonical(value));
export const validId = (v: unknown): v is string => typeof v === "string" && /^[a-z][a-z0-9-]{0,31}$/.test(v);

/** Exact operator-approved endpoints, no arbitrary host/path, credentials or IP literals. */
export function validateTarget(raw: string, env: Env): URL {
  let url: URL;
  try { url = new URL(raw); } catch { throw new GatewayError("target_denied"); }
  const localTest = env.ENVIRONMENT === "gateway-local-test" && url.hostname === "127.0.0.1";
  if (url.username || url.password || url.hash || url.search ||
      (!localTest && (url.protocol !== "https:" || (url.port !== "" && url.port !== "443") ||
        ipaddr.isValid(url.hostname.replace(/^\[|\]$/g, "")) || !url.hostname.includes(".") ||
        /\.(localhost|local|internal|home|lan|test|invalid)$/.test(url.hostname)))) throw new GatewayError("target_denied");
  if (localTest && !["http:", "https:"].includes(url.protocol)) throw new GatewayError("target_denied");
  const allow = (env.GATEWAY_ALLOWED_URLS ?? "").split(",").map(s => s.trim());
  if (!allow.includes(url.href)) throw new GatewayError("target_denied");
  return url;
}

export function publicAddress(value: string): boolean {
  try { return ipaddr.parse(value).range() === "unicast"; } catch { return false; }
}

/** DoH JSON 提供方，按顺序尝试。
 *
 * 线上实测 `cloudflare-dns.com` 的 DoH 端点从 Worker 子请求里连不通（TLS/连接被重置），
 * 只用一个提供方会让所有上游发现都变成 dns_unavailable。多个提供方互为退路，任意一个
 * 给出确定答案即可；全部不可达才算 “DNS 不可用”，仍然失败关闭。 */
const DOH_PROVIDERS: Array<(host: string, type: string) => string> = [
  (host, type) => `https://cloudflare-dns.com/dns-query?name=${encodeURIComponent(host)}&type=${type}`,
  (host, type) => `https://dns.google/resolve?name=${encodeURIComponent(host)}&type=${type}`,
];

interface DohAnswer { Status?: number; Answer?: { type: number; data: string }[] }

/** Workers cannot pin fetch's DNS resolution. DoH is an extra check; the exact
 * endpoint allowlist is the trust boundary against DNS rebinding (see report). */
export async function checkDns(url: URL, env: Env, signal: AbortSignal, network: typeof fetch = fetch): Promise<void> {
  if (env.ENVIRONMENT === "gateway-local-test" && url.hostname === "127.0.0.1") return;
  const answers = await Promise.all(["A", "AAAA"].map(async type => {
    // “确定答案”（NXDOMAIN / 私网地址）立刻拒绝；只有“提供方不可达”才换下一个。
    let unavailable = false;
    for (const provider of DOH_PROVIDERS) {
      let response: Response;
      try {
        response = await network(provider(url.hostname, type), {
          headers: { Accept: "application/dns-json" }, redirect: "manual", signal,
        });
      } catch (error) {
        if (error instanceof GatewayError) throw error;
        unavailable = true;
        continue;
      }
      if (response.status >= 300 && response.status < 400) { await response.body?.cancel(); unavailable = true; continue; }
      if (!response.ok) { unavailable = true; continue; }
      let data: DohAnswer;
      try {
        data = JSON.parse(await boundedText(response.body, 16384)) as DohAnswer;
      } catch (error) {
        if (error instanceof GatewayError) throw error;
        unavailable = true;
        continue;
      }
      if (data.Status !== 0) throw new GatewayError("dns_denied");
      return (data.Answer ?? []).filter(a => a.type === 1 || a.type === 28).map(a => a.data);
    }
    throw new GatewayError(unavailable ? "dns_unavailable" : "dns_denied", unavailable ? 503 : 400);
  }));
  const ips = answers.flat();
  if (!ips.length || ips.some(ip => !publicAddress(ip))) throw new GatewayError("dns_denied");
}
