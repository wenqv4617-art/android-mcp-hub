import type { Env } from "../env";
import { sha256Hex, timingSafeEqual } from "../util/crypto";
import { jsonResponse } from "../util/http";
import { boundedText, GatewayError, readScopes, REQUEST_LIMIT, type TokenScope } from "./security";

export const gatewayEnabled = (env: Env): boolean => env.GATEWAY_ENABLED === "true" && !!env.GATEWAY_STORE;
export async function gatewayRequest(env: Env, action: string, body: unknown): Promise<Response> {
  if (!gatewayEnabled(env)) throw new GatewayError("gateway_disabled", 503);
  const ns = env.GATEWAY_STORE!;
  return ns.get(ns.idFromName("gateway-v1")).fetch(new Request(`https://gateway.internal/${action}`, {
    method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body),
  }));
}
export async function gatewayJson<T>(env: Env, action: string, body: unknown): Promise<T> {
  const response = await gatewayRequest(env, action, body);
  const data = await response.json() as T & { error?: string };
  if (!response.ok) throw new GatewayError(data.error ?? "gateway_failure", response.status);
  return data;
}
/**
 * 客户端令牌鉴权。
 *
 * 返回值**必须**带上令牌的作用范围：`undefined` 表示完整访问（旧版 `/auth`
 * 响应或非网关身份），空数组表示什么都不许。这里对认不出的 scope 采取
 * 失败关闭 —— 不能因为存储里多了一个未知字符串就把权限放大。
 */
export async function gatewayPrincipal(
  env: Env,
  token: string,
): Promise<{ principal: string; scopes?: TokenScope[] } | null> {
  if (!gatewayEnabled(env) || !token.startsWith("hub_client_")) return null;
  const verdict = await gatewayJson<{ principal: string | null; scopes?: unknown }>(env, "auth", { digest: await sha256Hex(token) });
  if (!verdict.principal) return null;
  return { principal: verdict.principal, scopes: readScopes(verdict.scopes) };
}
/**
 * Android 配对入口。
 *
 * 只有同时满足「网关开启」和「配置了配对口令」时才存在；否则一律 404，
 * 不给探测者一个"这里有个配对接口"的信号。
 */
export async function handleGatewayPair(request: Request, env: Env): Promise<Response> {
  if (!gatewayEnabled(env) || !env.GATEWAY_ANDROID_PAIRING_CODE) return jsonResponse({ error: "not_found" }, { status: 404 });
  const origin = request.headers.get("Origin");
  if (origin && origin !== new URL(request.url).origin) return jsonResponse({ error: "origin_denied" }, { status: 403 });
  if (request.method !== "POST") return jsonResponse({ error: "method_not_allowed" }, { status: 405, headers: { Allow: "POST" } });
  try {
    if (!request.headers.get("Content-Type")?.startsWith("application/json")) throw new GatewayError("content_type", 415);
    const body = JSON.parse(await boundedText(request.body, REQUEST_LIMIT));
    return withNoStore(await gatewayRequest(env, "pair", body));
  } catch (e) {
    const err = e instanceof GatewayError ? e : new GatewayError("invalid_request");
    return jsonResponse({ error: err.code }, { status: err.status });
  }
}

/**
 * 受限 Android 管理面。
 *
 * Bearer 是配对换来的 `hub_admin_*`，不是全局 `GATEWAY_ADMIN_TOKEN`。
 * Worker 只把 SHA-256 摘要转给 DO，DO 再按 ownerId/clientId 做服务端授权。
 */
export async function handleGatewayAndroid(request: Request, env: Env): Promise<Response> {
  if (!gatewayEnabled(env)) return jsonResponse({ error: "not_found" }, { status: 404 });
  const token = /^Bearer\s+(.+)$/i.exec(request.headers.get("Authorization") ?? "")?.[1] ?? "";
  if (!token.startsWith("hub_admin_")) return jsonResponse({ error: "unauthorized" }, { status: 401 });
  const origin = request.headers.get("Origin");
  if (origin && origin !== new URL(request.url).origin) return jsonResponse({ error: "origin_denied" }, { status: 403 });
  if (request.method !== "POST") return jsonResponse({ error: "method_not_allowed" }, { status: 405, headers: { Allow: "POST" } });
  try {
    if (!request.headers.get("Content-Type")?.startsWith("application/json")) throw new GatewayError("content_type", 415);
    const inner = JSON.parse(await boundedText(request.body, REQUEST_LIMIT));
    if (typeof inner !== "object" || inner === null || Array.isArray(inner)) throw new GatewayError("invalid_request");
    const response = await gatewayRequest(env, "android", { digest: await sha256Hex(token), request: inner });
    return withNoStore(response);
  } catch (e) {
    const err = e instanceof GatewayError ? e : new GatewayError("invalid_request");
    return jsonResponse({ error: err.code }, { status: err.status });
  }
}

function withNoStore(response: Response): Response {
  const headers = new Headers(response.headers);
  headers.set("Cache-Control", "no-store");
  return new Response(response.body, { status: response.status, headers });
}

/** Operator API only. No credential/config APIs are reachable with MCP tokens. */
export async function handleGatewayAdmin(request: Request, env: Env): Promise<Response> {
  if (!gatewayEnabled(env) || !env.GATEWAY_ADMIN_TOKEN) return jsonResponse({ error: "not_found" }, { status: 404 });
  const token = /^Bearer\s+(.+)$/i.exec(request.headers.get("Authorization") ?? "")?.[1] ?? "";
  if (!timingSafeEqual(await sha256Hex(token), await sha256Hex(env.GATEWAY_ADMIN_TOKEN))) return jsonResponse({ error: "unauthorized" }, { status: 401 });
  // CSRF/DNS-rebinding protection; API callers normally omit Origin.
  const origin = request.headers.get("Origin");
  if (origin && origin !== new URL(request.url).origin) return jsonResponse({ error: "origin_denied" }, { status: 403 });
  if (request.method !== "POST") return jsonResponse({ error: "method_not_allowed" }, { status: 405, headers: { Allow: "POST" } });
  try {
    if (!request.headers.get("Content-Type")?.startsWith("application/json")) throw new GatewayError("content_type", 415);
    return await gatewayRequest(env, "admin", JSON.parse(await boundedText(request.body, REQUEST_LIMIT)));
  } catch (e) { const err = e instanceof GatewayError ? e : new GatewayError("invalid_request"); return jsonResponse({ error: err.code }, { status: err.status }); }
}
