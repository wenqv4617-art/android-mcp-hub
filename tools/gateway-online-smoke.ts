/**
 * 线上网关验收（部署后由 GitHub Actions 执行，不替代本地 workerd 测试）。
 *
 * 这条脚本只做一件事：用**真实的公网 Worker** 走完 Android 服务页要走的同一条链路：
 *   配对 → 添加服务 → 连接上游 → 发现工具 → 未授权拒绝 → 授权 → /mcp 真实调用
 *   → 需确认票据 → 停用拒绝 → 坏地址拒绝 → 删除 → 解除配对 → 撤销后拒绝。
 *
 * 它**不打印**任何令牌：管理令牌、客户端令牌、配对码都只存在于进程内存里。
 * 上游只用精确 allowlist 中的公开文档 MCP；调用参数按工具 inputSchema 生成。
 *
 * 用法：
 *   GATEWAY_BASE_URL=https://android-mcp-hub.<subdomain>.workers.dev \
 *   GATEWAY_ANDROID_PAIRING_CODE=... \
 *   node --import tsx tools/gateway-online-smoke.ts
 *
 * 可选：
 *   SMOKE_SERVICE_URL  只测这一个上游（默认依次尝试公开文档 MCP）
 *   SMOKE_KEEP=1       结束后保留服务与配对（默认清理）
 *   SMOKE_BENCH_SAMPLES=N  额外采样 tools/list 与只读工具调用，输出 p50/p90（默认 0）
 */

import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StreamableHTTPClientTransport } from "@modelcontextprotocol/sdk/client/streamableHttp.js";

const BASE = (process.env.GATEWAY_BASE_URL ?? "").replace(/\/+$/, "");
const CODE = process.env.GATEWAY_ANDROID_PAIRING_CODE ?? "";
const DEVICE_ID = process.env.SMOKE_DEVICE_ID ?? "ci-gateway-smoke";
const KEEP = process.env.SMOKE_KEEP === "1";
const BENCH_SAMPLES = Math.min(50, Math.max(0, Number(process.env.SMOKE_BENCH_SAMPLES ?? "0") || 0));
const SERVICE_ID = "ci-smoke";
const BAD_SERVICE_ID = "ci-smoke-bad";

const CANDIDATES = process.env.SMOKE_SERVICE_URL
  ? [process.env.SMOKE_SERVICE_URL]
  : [
      "https://docs.mcp.cloudflare.com/mcp",
      "https://mcp.deepwiki.com/mcp",
      "https://learn.microsoft.com/api/mcp",
    ];

class SmokeFailure extends Error {}

function need(ok: unknown, message: string): asserts ok {
  if (!ok) throw new SmokeFailure(message);
}

function log(message: string): void {
  console.log(`[online-smoke] ${message}`);
}

async function jsonRequest(
  path: string,
  body: Record<string, unknown>,
  bearer?: string,
): Promise<Record<string, any>> {
  const response = await fetch(`${BASE}${path}`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Accept: "application/json",
      ...(bearer ? { Authorization: `Bearer ${bearer}` } : {}),
    },
    body: JSON.stringify(body),
    redirect: "error",
  });
  const text = await response.text();
  let parsed: Record<string, any> = {};
  try {
    parsed = JSON.parse(text) as Record<string, any>;
  } catch {
    // 错误响应不是 JSON 时也不要回显响应体。
  }
  if (!response.ok) {
    throw new SmokeFailure(`${path} -> HTTP ${response.status} ${typeof parsed.error === "string" ? parsed.error : ""}`.trim());
  }
  return parsed;
}

interface Pairing {
  managementToken: string;
  clientToken: string;
  ownerId: string;
  clientId: string;
}

async function pair(): Promise<Pairing> {
  const result = await jsonRequest("/gateway/pair", {
    code: CODE,
    deviceId: DEVICE_ID,
    deviceName: "GitHub Actions online smoke",
  });
  need(typeof result.managementToken === "string" && result.managementToken.startsWith("hub_admin_"), "配对未返回受限管理令牌");
  need(typeof result.clientToken === "string" && result.clientToken.startsWith("hub_client_"), "配对未返回 MCP 客户端令牌");
  return result as Pairing;
}

function admin(token: string, action: Record<string, unknown>): Promise<Record<string, any>> {
  return jsonRequest("/gateway/android", action, token);
}

interface ToolRow {
  name: string;
  description?: string;
  inputSchema?: Record<string, any>;
  gatewayName: string;
  fingerprint: string;
}

interface ServiceRow {
  id: string;
  name: string;
  url: string;
  enabled: boolean;
  lastError?: string | null;
  lastTestAt?: number | null;
  tools?: Array<{ name: string; inputSchema?: Record<string, any> }>;
  toolMap?: Array<{ original: string; name: string; fingerprint: string }>;
}

async function list(token: string): Promise<{ services: ServiceRow[] }> {
  return (await admin(token, { action: "list" })) as { services: ServiceRow[] };
}

function toolsOf(service: ServiceRow): ToolRow[] {
  const map = new Map((service.toolMap ?? []).map((row) => [row.original, row]));
  return (service.tools ?? []).flatMap((tool) => {
    const mapped = map.get(tool.name);
    if (!mapped) return [];
    return [{
      name: tool.name,
      description: typeof (tool as { description?: string }).description === "string"
        ? (tool as { description?: string }).description
        : "",
      inputSchema: tool.inputSchema,
      gatewayName: mapped.name,
      fingerprint: mapped.fingerprint,
    }];
  });
}

/** 生成一个能通过常见 inputSchema 的示例参数；宁可跳过也不猜危险字段。 */
function sampleFor(key: string, schema: Record<string, any>): unknown {
  const type = Array.isArray(schema.type) ? schema.type[0] : schema.type;
  if (type === "number" || type === "integer") return 1;
  if (type === "boolean") return true;
  if (type === "array") return [];
  if (type === "object") return {};
  if (/repo/i.test(key)) return "microsoft/vscode";
  if (/query|search|question|prompt|term/i.test(key)) return "Durable Objects";
  return "hello";
}

function argumentsFor(tool: ToolRow): Record<string, unknown> | null {
  const schema = tool.inputSchema ?? {};
  const properties = (schema.properties ?? {}) as Record<string, Record<string, any>>;
  const required = Array.isArray(schema.required) ? schema.required.map(String) : [];
  const args: Record<string, unknown> = {};
  for (const key of required) {
    const property = properties[key] ?? {};
    const type = Array.isArray(property.type) ? property.type[0] : property.type;
    if (type && !["string", "number", "integer", "boolean", "array", "object"].includes(type)) return null;
    if (type === "array" && Array.isArray(property.items) && property.items.length > 0) return null;
    args[key] = sampleFor(key, property);
  }
  if (required.length === 0) {
    const preferred = ["query", "q", "search", "question", "prompt"].find((key) => properties[key]);
    if (preferred) args[preferred] = sampleFor(preferred, properties[preferred]!);
  }
  return args;
}

function textOf(result: any): string {
  const content = Array.isArray(result?.content) ? result.content : [];
  return content
    .map((item: any) => (typeof item?.text === "string" ? item.text : ""))
    .filter(Boolean)
    .join("\n");
}

/**
 * 应当被拒绝的调用。MCP 服务器有两种合法的拒绝方式：JSON-RPC 错误（SDK 抛错），
 * 或工具结果 `isError: true`（网关就是这样回错误码的）。两种都算拒绝，但必须带错误码。
 */
async function rejected(
  client: Client,
  name: string,
  args: Record<string, unknown>,
  expected: RegExp,
  ticket?: string,
): Promise<string> {
  let code: string;
  try {
    const result: any = await client.callTool({
      name,
      arguments: args,
      ...(ticket ? { _meta: { "hub/confirmation": ticket } } : {}),
    } as any);
    need(result?.isError === true, "调用没有被拒绝：返回了正常结果");
    code = textOf(result);
  } catch (error) {
    code = error instanceof Error ? error.message : String(error);
  }
  need(code.length > 0, "拒绝结果没有错误码");
  need(expected.test(code), `拒绝原因不符：期望 ${expected}，实际 ${code.slice(0, 120)}`);
  return code;
}

async function withClient<T>(token: string, fn: (client: Client) => Promise<T>): Promise<T> {
  const client = new Client({ name: "android-mcp-hub-online-smoke", version: "1.0.0" }, { capabilities: {} });
  const transport = new StreamableHTTPClientTransport(new URL(`${BASE}/mcp`), {
    requestInit: { headers: { Authorization: `Bearer ${token}` } },
  });
  try {
    await client.connect(transport);
    return await fn(client);
  } finally {
    await client.close().catch(() => {});
  }
}

function percentile(sorted: readonly number[], p: number): number {
  if (sorted.length === 0) return 0;
  const index = Math.min(sorted.length - 1, Math.max(0, Math.ceil((p / 100) * sorted.length) - 1));
  return sorted[index]!;
}

/** 线上可选采样：只记录耗时与样本数，不打印响应内容或令牌。 */
async function sample(
  label: string,
  count: number,
  invoke: () => Promise<void>,
): Promise<void> {
  if (count <= 0) return;
  const times: number[] = [];
  for (let i = 0; i < count; i++) {
    const started = performance.now();
    await invoke();
    times.push(performance.now() - started);
  }
  times.sort((a, b) => a - b);
  const round = (value: number) => Math.round(value * 10) / 10;
  log(
    `BENCH ${label}: samples=${count} p50=${round(percentile(times, 50))}ms ` +
    `p90=${round(percentile(times, 90))}ms min=${round(times[0]!)}ms ` +
    `max=${round(times[times.length - 1]!)}ms cache=none`,
  );
}

async function main(): Promise<void> {
  need(/^https:\/\/[^/]+$/.test(BASE), "GATEWAY_BASE_URL 必须是 https 根地址");
  need(CODE.length >= 8, "缺少 GATEWAY_ANDROID_PAIRING_CODE");

  // Cloudflare 的新版本/Secret 在边缘生效有短暂延迟；这里最多等 60 秒，
  // 避免把「刚部署完还在传播」误判成「配置没生效」。
  let capabilities: Record<string, unknown> = {};
  let lastHealth = "";
  for (let attempt = 0; attempt < 30; attempt++) {
    try {
      const health = await fetch(`${BASE}/healthz?ready=${Date.now()}`, { redirect: "error", cache: "no-store" });
      if (health.ok) {
        // /healthz 把网关开关放在 capabilities 里；顶层没有这两个字段。
        const body = (await health.json()) as Record<string, any>;
        capabilities = (body.capabilities ?? {}) as Record<string, unknown>;
        lastHealth = `HTTP ${health.status} capabilities=${JSON.stringify(capabilities)}`;
        if (capabilities.gateway_enabled === true && capabilities.gateway_android_pairing_configured === true) break;
      } else {
        lastHealth = `HTTP ${health.status}`;
      }
    } catch (error) {
      lastHealth = error instanceof Error ? error.message : String(error);
    }
    await new Promise((resolve) => setTimeout(resolve, 2000));
  }
  need(capabilities.gateway_enabled === true, `线上网关未启用（最后一次：${lastHealth || "healthz 未返回"}）`);
  need(capabilities.gateway_android_pairing_configured === true, "线上未配置 Android 配对口令");
  log("healthz：网关已启用，配对已配置");

  const pairing = await pair();
  log("配对成功：拿到受限管理令牌与客户端令牌（不打印值）");

  let selected: { url: string; tools: ToolRow[] } | null = null;
  const failures: string[] = [];
  for (const url of CANDIDATES) {
    try {
      await admin(pairing.managementToken, {
        action: "put-service",
        id: SERVICE_ID,
        ownerId: pairing.ownerId,
        name: "CI smoke upstream",
        description: "GitHub Actions 线上验收用；可随时删除",
        url,
        transport: "streamable-http",
        enabled: true,
        timeoutMs: 20000,
      });
      const tested = await admin(pairing.managementToken, { action: "test", id: SERVICE_ID });
      const rows = await list(pairing.managementToken);
      const service = rows.services.find((row) => row.id === SERVICE_ID);
      const tools = service ? toolsOf(service) : [];
      need(tools.length > 0, "上游发现 0 个工具");
      selected = { url, tools };
      log(`上游可用：${url}（发现 ${tools.length} 个工具，测试返回 ${Array.isArray(tested.tools) ? tested.tools.length : 0} 个）`);
      break;
    } catch (error) {
      failures.push(`${url}: ${error instanceof Error ? error.message : String(error)}`);
      log(`上游不可用，尝试下一个：${url}`);
    }
  }
  need(selected !== null, `所有候选上游都失败：${failures.join(" | ")}`);

  // 重新读取列表：证明服务与工具目录已经持久化，而不是只存在于 test 响应里。
  const persisted = (await list(pairing.managementToken)).services.find((row) => row.id === SERVICE_ID);
  need(persisted != null, "服务没有出现在 list 结果里");
  need(persisted!.enabled === true, "服务未启用");
  need(persisted!.lastTestAt != null, "缺少最近测试时间");
  const tools = toolsOf(persisted!);
  need(tools.length === selected!.tools.length, "持久化后的工具数量不一致");

  const candidates = tools.filter((tool) => argumentsFor(tool) !== null);
  need(candidates.length > 0, "没有可自动构造参数的工具");
  const chosen = candidates.find((tool) => /search|read|list|get|docs|wiki/i.test(tool.name)) ?? candidates[0]!;
  const args = argumentsFor(chosen)!;
  log(`选择工具：${chosen.name}（参数键：${Object.keys(args).join(", ") || "无"}）`);

  // 未授权：MCP 工具列表必须为空，直接调用必须被拒绝。
  await withClient(pairing.clientToken, async (client) => {
    const before = await client.listTools();
    need(before.tools.length === 0, "未授权时 /mcp 不应暴露任何工具");
    await rejected(client, chosen.gatewayName, args, /permission_denied/);
  });
  log("未授权：工具列表为空，调用被拒绝");

  // 只读授权 → 列表出现网关工具 → 真实调用。
  await admin(pairing.managementToken, {
    action: "grant",
    serviceId: SERVICE_ID,
    tool: chosen.name,
    mode: "read",
    fingerprint: chosen.fingerprint,
  });
  let readText = "";
  await withClient(pairing.clientToken, async (client) => {
    const after = await client.listTools();
    need(after.tools.some((tool) => tool.name === chosen.gatewayName), "授权后 /mcp 未列出网关工具");
    const result = await client.callTool({ name: chosen.gatewayName, arguments: args });
    need(result.isError !== true, "只读调用返回 isError");
    readText = textOf(result);
    need(readText.length > 0, "只读调用没有返回任何文本");

    if (BENCH_SAMPLES > 0) {
      await sample("tools/list", BENCH_SAMPLES, async () => {
        const listed = await client.listTools();
        need(listed.tools.some((tool) => tool.name === chosen.gatewayName), "采样时工具列表丢失授权工具");
      });
      await sample("ext_read_call", BENCH_SAMPLES, async () => {
        const sampled = await client.callTool({ name: chosen.gatewayName, arguments: args });
        need(sampled.isError !== true, "采样时只读调用返回 isError");
      });
    }
  });
  log(`只读真实调用成功：${readText.replace(/\s+/g, " ").slice(0, 120)}...`);

  // 重开会话：授权与定义指纹必须仍然有效（持久化闭环）。
  await withClient(pairing.clientToken, async (client) => {
    const toolsAgain = await client.listTools();
    need(toolsAgain.tools.some((tool) => tool.name === chosen.gatewayName), "重开会话后授权丢失");
  });
  log("重开会话：授权仍有效");

  // 需确认：没有票据必须拒绝；有票据必须成功，且票据只能用一次。
  await admin(pairing.managementToken, {
    action: "grant",
    serviceId: SERVICE_ID,
    tool: chosen.name,
    mode: "confirm",
    fingerprint: chosen.fingerprint,
  });
  let ticket = "";
  await withClient(pairing.clientToken, async (client) => {
    await rejected(client, chosen.gatewayName, args, /confirmation_required/);
  });
  const confirmed = await admin(pairing.managementToken, {
    action: "confirm",
    name: chosen.gatewayName,
    arguments: args,
  });
  ticket = String(confirmed.confirmation ?? "");
  need(/^[a-f0-9-]{36}$/.test(ticket), "服务端未返回合法确认票据");
  await withClient(pairing.clientToken, async (client) => {
    const result = await client.callTool({
      name: chosen.gatewayName,
      arguments: args,
      _meta: { "hub/confirmation": ticket },
    } as any);
    need(result.isError !== true, "带票据的确认调用失败");
  });
  await withClient(pairing.clientToken, async (client) => {
    await rejected(client, chosen.gatewayName, args, /confirmation_required/, ticket);
  });
  log("需确认：无票据拒绝、有票据成功、票据不可重放");

  // 停用：工具从列表消失，调用被拒绝。
  await admin(pairing.managementToken, { action: "set-enabled", id: SERVICE_ID, enabled: false });
  await withClient(pairing.clientToken, async (client) => {
    const hidden = await client.listTools();
    need(hidden.tools.length === 0, "停用后仍能看到工具");
    await rejected(client, chosen.gatewayName, args, /permission_denied/);
  });
  await admin(pairing.managementToken, { action: "set-enabled", id: SERVICE_ID, enabled: true });
  log("停用：工具隐藏，调用被拒绝");

  // 坏地址：直接拒绝，且不落盘。
  let targetDenied = false;
  try {
    await admin(pairing.managementToken, {
      action: "put-service",
      id: BAD_SERVICE_ID,
      ownerId: pairing.ownerId,
      name: "bad",
      url: "http://example.com/mcp",
      transport: "streamable-http",
      enabled: true,
    });
  } catch (error) {
    targetDenied = error instanceof Error && error.message.includes("target_denied");
  }
  need(targetDenied, "非 allowlist / 非 HTTPS 地址没有被拒绝");
  need((await list(pairing.managementToken)).services.every((row) => row.id !== BAD_SERVICE_ID), "坏服务被写入");
  log("坏地址：target_denied，未落盘");

  if (KEEP) {
    log(`SMOKE_KEEP=1：保留服务 ${SERVICE_ID} 与配对`);
    return;
  }

  await admin(pairing.managementToken, { action: "delete-service", id: SERVICE_ID });
  need((await list(pairing.managementToken)).services.every((row) => row.id !== SERVICE_ID), "删除服务失败");
  await admin(pairing.managementToken, { action: "unpair" });
  log("清理完成：服务已删除，配对已撤销");

  // 撤销必须真的生效：解除配对会同时吊销该客户端的所有令牌。
  let revokedError = "";
  try {
    await withClient(pairing.clientToken, async (client) => {
      await client.listTools();
    });
  } catch (error) {
    revokedError = error instanceof Error ? error.message : String(error);
  }
  need(revokedError.length > 0, "解除配对后客户端令牌仍然可用");
  need(
    /401|unauthor|revok|invalid_token/i.test(revokedError),
    `撤销后的拒绝原因不符：${revokedError.slice(0, 120)}`,
  );
  log("撤销：解除配对后客户端令牌被拒绝");

  log("ONLINE GATEWAY ACCEPTANCE: PASS");
}

main().catch((error) => {
  console.error(`[online-smoke] FAIL: ${error instanceof Error ? error.message : String(error)}`);
  process.exitCode = 1;
});
