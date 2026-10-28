import type { Env } from "../env";
import { sha256Hex, timingSafeEqual } from "../util/crypto";
import { jsonResponse } from "../util/http";
import { boundedText, GatewayError, REQUEST_LIMIT } from "./security";

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
export async function gatewayPrincipal(env: Env, token: string): Promise<string | null> {
  if (!gatewayEnabled(env) || !token.startsWith("hub_client_")) return null;
  return (await gatewayJson<{ principal: string | null }>(env, "auth", { digest: await sha256Hex(token) })).principal;
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
