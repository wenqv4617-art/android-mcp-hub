import { CfWorkerJsonSchemaValidator } from "@modelcontextprotocol/sdk/validation/cfworker";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StreamableHTTPClientTransport } from "@modelcontextprotocol/sdk/client/streamableHttp.js";
import { SSEClientTransport } from "@modelcontextprotocol/sdk/client/sse.js";
import type { Tool } from "@modelcontextprotocol/sdk/types.js";
import type { Env } from "../env";
import { checkDns, GatewayError, RESPONSE_LIMIT, validateTarget } from "./security";

export interface Service {
  id: string; name: string; ownerId: string; url: string;
  transport: "streamable-http" | "sse"; enabled: boolean;
  /** 可选的人类说明；不参与连接，只用于管理界面。 */
  description?: string;
  secretRef?: string; timeoutMs: number;
}
export type DiscoveredTool = Tool;

/** A fresh SDK connection per operation prevents cross-tenant session reuse.
 * No fallback after calls, no application retries, no unsolicited sampling. */
export async function upstream<T>(service: Service, env: Env, operation: (client: Client, signal: AbortSignal) => Promise<T>): Promise<T> {
  const url = validateTarget(service.url, env);
  const controller = new AbortController();
  const client = new Client({ name: "mcp-hub-gateway", version: "1.0.0" }, { capabilities: {}, jsonSchemaValidator: new CfWorkerJsonSchemaValidator() });
  let timedOut = false;
  let timer: ReturnType<typeof setTimeout>;
  const deadline = new Promise<never>((_, reject) => {
    timer = setTimeout(() => { timedOut = true; controller.abort(); reject(new GatewayError("upstream_timeout", 504)); }, service.timeoutMs);
  });
  try {
    const run = async (): Promise<T> => {
      let authorization: string | undefined;
      if (service.secretRef) {
        const secrets = JSON.parse(env.GATEWAY_UPSTREAM_SECRETS ?? "{}") as Record<string, string>;
        authorization = secrets[`${service.ownerId}/${service.secretRef}`];
        if (typeof authorization !== "string" || !authorization || /[\r\n]/.test(authorization)) throw new GatewayError("credential_unavailable", 503);
      }
      // SDK gets only this fetch: every request including the SSE endpoint event
      // is checked, redirects rejected, downstream headers never forwarded.
      const guardedFetch = async (input: string | URL | Request, init?: RequestInit): Promise<Response> => {
        const target = new URL(typeof input === "string" ? input : input instanceof URL ? input.href : input.url);
        const base = new URL(target.href); base.search = "";
        validateTarget(base.href, env);
        if (target.origin !== url.origin) throw new GatewayError("target_denied");
        await checkDns(target, env, controller.signal);
        const headers = new Headers(init?.headers);
        headers.delete("Authorization"); headers.delete("Cookie"); headers.delete("Host");
        if (authorization) headers.set("Authorization", authorization);
        const response = await fetch(target.href, { ...init, headers, redirect: "manual", signal: controller.signal });
        if (response.status >= 300 && response.status < 400) { await response.body?.cancel(); throw new GatewayError("redirect_denied"); }
        if (!response.ok && response.status !== 405) { await response.body?.cancel(); throw new GatewayError("upstream_unavailable", 502); }
        if (!response.body) return response;
        // SSE stays streaming. The lifetime deadline bounds even endless streams.
        let bytes = 0;
        const reader = response.body.getReader();
        const body = new ReadableStream<Uint8Array>({
          async pull(c) {
            try {
              const part = await reader.read();
              if (part.done) { c.close(); return; }
              bytes += part.value.byteLength;
              if (bytes > RESPONSE_LIMIT) throw new GatewayError("upstream_size_limit", 502);
              c.enqueue(part.value);
            } catch (e) { await reader.cancel().catch(() => {}); c.error(e); }
          },
          async cancel() { await reader.cancel().catch(() => {}); },
        });
        return new Response(body, { status: response.status, headers: response.headers });
      };
      const options = { fetch: guardedFetch as never, requestInit: { redirect: "manual" as const } };
      const transport = service.transport === "sse"
        ? new SSEClientTransport(url, options)
        : new StreamableHTTPClientTransport(url, { ...options, reconnectionOptions: { maxRetries: 0, initialReconnectionDelay: 100, maxReconnectionDelay: 100, reconnectionDelayGrowFactor: 1 } });
      await client.connect(transport, { timeout: service.timeoutMs, signal: controller.signal });
      return operation(client, controller.signal);
    };
    return await Promise.race([run(), deadline]);
  } catch (e) {
    if (e instanceof GatewayError) throw e;
    // SDK/运行时可能把内部错误重新包一层；先看 cause，别把真实原因降级成笼统的失败。
    const cause = (e as { cause?: unknown } | null)?.cause;
    if (cause instanceof GatewayError) throw cause;
    if (timedOut) throw new GatewayError("upstream_timeout", 504);
    // fetch 失败在 Workers/Node 里都是 TypeError（"Network connection lost." / ECONNRESET）。
    // 只暴露类别，不暴露 message —— 它可能带上游地址或响应体。
    if (e instanceof TypeError || cause instanceof TypeError) throw new GatewayError("upstream_network", 502);
    throw new GatewayError("upstream_failure", 502);
  } finally {
    clearTimeout(timer!);
    controller.abort();
    await client.close().catch(() => {});
  }
}

export function assertNoCredentials(value: unknown, env: Env): void {
  const encoded = JSON.stringify(value);
  const secrets = Object.values(JSON.parse(env.GATEWAY_UPSTREAM_SECRETS ?? "{}") as Record<string, string>);
  if (secrets.some(secret => typeof secret === "string" && secret.length > 0 &&
    (encoded.includes(JSON.stringify(secret).slice(1, -1)) || (secret.startsWith("Bearer ") && encoded.includes(JSON.stringify(secret.slice(7)).slice(1, -1)))))) throw new GatewayError("credential_in_result", 502);
}

/**
 * 把上游结果里出现的凭据替换成 `[redacted]`，而不是把整条工具结果丢掉。
 *
 * 工具结果可能是业务错误（`isError: true`）—— 它的原始文本对调用方有用，
 * 不能因为里面碰巧带了一枚上游凭据就降级成笼统的网关错误。先脱敏，再交给
 * `assertNoCredentials` 做最后一道校验；校验仍失败时调用方按协议错误处理。
 */
export function redactCredentials(value: unknown, env: Env): unknown {
  const secrets = Object.values(JSON.parse(env.GATEWAY_UPSTREAM_SECRETS ?? "{}") as Record<string, string>)
    .filter((secret): secret is string => typeof secret === "string" && secret.length > 0)
    .flatMap(secret => secret.startsWith("Bearer ") ? [secret, secret.slice(7)] : [secret])
    .filter(secret => secret.length > 0)
    .sort((a, b) => b.length - a.length);
  if (secrets.length === 0) return value;
  const redact = (text: string): string => {
    let output = text;
    for (const secret of secrets) output = output.split(secret).join("[redacted]");
    return output;
  };
  const walk = (node: unknown): unknown => {
    if (typeof node === "string") return redact(node);
    if (Array.isArray(node)) return node.map(walk);
    if (node !== null && typeof node === "object") {
      return Object.fromEntries(Object.entries(node).map(([key, child]) => [key, walk(child)]));
    }
    return node;
  };
  return walk(value);
}

export async function discoverOnClient(client: Client, signal: AbortSignal, timeoutMs: number): Promise<DiscoveredTool[]> {
  const tools: DiscoveredTool[] = [];
  let cursor: string | undefined;
  const seen = new Set<string>();
  do {
    const result = await client.listTools(cursor ? { cursor } : {}, { signal, timeout: timeoutMs });
    tools.push(...result.tools);
    if (tools.length > 256 || new TextEncoder().encode(JSON.stringify(tools)).byteLength > RESPONSE_LIMIT) throw new GatewayError("catalog_limit", 502);
    cursor = result.nextCursor;
    if (cursor && seen.has(cursor)) throw new GatewayError("catalog_limit", 502);
    if (cursor) seen.add(cursor);
  } while (cursor);
  if (new Set(tools.map(t => t.name)).size !== tools.length || tools.some(t => !/^[a-zA-Z0-9_.-]{1,128}$/.test(t.name))) throw new GatewayError("invalid_catalog", 502);
  return tools;
}
export async function discover(service: Service, env: Env): Promise<DiscoveredTool[]> {
  return upstream(service, env, async (client, signal) => {
    const tools = await discoverOnClient(client, signal, service.timeoutMs);
    assertNoCredentials(tools, env);
    return tools;
  });
}
