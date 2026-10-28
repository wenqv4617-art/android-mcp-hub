import ipaddr from "ipaddr.js";
import type { Env } from "../env";
import { sha256Hex } from "../util/crypto";

export const REQUEST_LIMIT = 64 * 1024;
export const RESPONSE_LIMIT = 1024 * 1024;
export class GatewayError extends Error {
  constructor(readonly code: string, readonly status = 400) { super(code); }
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

/** Workers cannot pin fetch's DNS resolution. DoH is an extra check; the exact
 * endpoint allowlist is the trust boundary against DNS rebinding (see report). */
export async function checkDns(url: URL, env: Env, signal: AbortSignal, network: typeof fetch = fetch): Promise<void> {
  if (env.ENVIRONMENT === "gateway-local-test" && url.hostname === "127.0.0.1") return;
  const answers = await Promise.all(["A", "AAAA"].map(async type => {
    const response = await network(`https://cloudflare-dns.com/dns-query?name=${encodeURIComponent(url.hostname)}&type=${type}`, {
      headers: { Accept: "application/dns-json" }, redirect: "error", signal,
    });
    if (!response.ok) throw new GatewayError("dns_denied");
    const data = JSON.parse(await boundedText(response.body, 16384)) as { Status?: number; Answer?: { type: number; data: string }[] };
    if (data.Status !== 0) throw new GatewayError("dns_denied");
    return (data.Answer ?? []).filter(a => a.type === 1 || a.type === 28).map(a => a.data);
  }));
  const ips = answers.flat();
  if (!ips.length || ips.some(ip => !publicAddress(ip))) throw new GatewayError("dns_denied");
}
