import { CfWorkerJsonSchemaValidator } from "@modelcontextprotocol/sdk/validation/cfworker";
import type { Env } from "../env";
import { sha256Hex, timingSafeEqual } from "../util/crypto";
import { jsonResponse } from "../util/http";
import { boundedText, canonical, fingerprint, GatewayError, REQUEST_LIMIT, RESPONSE_LIMIT, validId, validateTarget } from "./security";
import { assertNoCredentials, discover, discoverOnClient, upstream, type DiscoveredTool, type Service } from "./upstream";

interface Grant { serviceId: string; tool: string; fingerprint: string; revision: string; mode: "read" | "confirm" }
interface ClientRecord { id: string; userId: string; charId: string; tokenSha256?: string; enabled: boolean; grants: Grant[] }
interface RegisteredService extends Service { revision: string; tools: DiscoveredTool[]; lastError: string | null }
interface Ticket { principal: string; name: string; argsHash: string; expires: number }
interface Audit { id: string; time: number; principal: string; serviceId: string; tool: string; outcome: string }
interface State { services: RegisteredService[]; clients: ClientRecord[]; tickets: Record<string, Ticket>; audit: Audit[] }
const empty = (): State => ({ services: [], clients: [], tickets: {}, audit: [] });
export const gatewayName = async (serviceId: string, tool: string): Promise<string> => `ext_${serviceId}_${(await sha256Hex(tool)).slice(0, 24)}`;

/** Single coordination object: persistent config, atomic confirmation consumption,
 * and bounded in-flight requests across all Worker isolates. No phone state. */
export class GatewayStore {
  private inFlight = 0;
  private readonly perClient = new Map<string, number>();
  constructor(private readonly ctx: DurableObjectState, private readonly env: Env) {}
  private async read(): Promise<State> { return await this.ctx.storage.get<State>("gateway-v1") ?? empty(); }
  private async update<T>(fn: (state: State) => T | Promise<T>): Promise<T> {
    return this.ctx.storage.transaction(async txn => {
      const state = await txn.get<State>("gateway-v1") ?? empty();
      const result = await fn(state);
      if (new TextEncoder().encode(JSON.stringify(state)).byteLength > 120 * 1024) throw new GatewayError("registry_size_limit", 413);
      await txn.put("gateway-v1", state);
      return result;
    });
  }
  async fetch(request: Request): Promise<Response> {
    try {
      const p = JSON.parse(await boundedText(request.body, REQUEST_LIMIT)) as Record<string, any>;
      const action = new URL(request.url).pathname;
      if (action === "/auth") {
        const state = await this.read();
        const c = state.clients.find(c => c.enabled && c.tokenSha256 && typeof p.digest === "string" && timingSafeEqual(c.tokenSha256, p.digest));
        return jsonResponse({ principal: c?.id ?? null });
      }
      if (action === "/admin") {
        if (p.action !== "test") return jsonResponse(await this.admin(p));
        if (this.inFlight >= 8) throw new GatewayError("concurrency_limit", 429);
        this.inFlight++;
        try { return jsonResponse(await this.admin(p)); } finally { this.inFlight--; }
      }
      if (action !== "/list" && action !== "/call") throw new GatewayError("not_found", 404);
      const principal = typeof p.principal === "string" ? p.principal : "";
      if (this.inFlight >= 8 || (this.perClient.get(principal) ?? 0) >= 2) throw new GatewayError("concurrency_limit", 429);
      this.inFlight++; this.perClient.set(principal, (this.perClient.get(principal) ?? 0) + 1);
      try {
        if (action === "/list") return jsonResponse({ tools: await this.list(principal) });
        return jsonResponse(await this.call(principal, p));
      } finally {
        this.inFlight--;
        const remaining = (this.perClient.get(principal) ?? 1) - 1;
        if (remaining) this.perClient.set(principal, remaining); else this.perClient.delete(principal);
      }
    } catch (e) {
      const error = e instanceof GatewayError ? e : new GatewayError("gateway_failure", 503);
      return jsonResponse({ error: error.code }, { status: error.status });
    }
  }
  private async admin(p: Record<string, any>): Promise<unknown> {
    if (p.action === "list") {
      const s = await this.read();
      return { services: await Promise.all(s.services.map(async service => ({ ...service, toolMap: await Promise.all(service.tools.map(async tool => ({ original: tool.name, name: await gatewayName(service.id, tool.name), fingerprint: await fingerprint(tool) }))) }))), clients: s.clients.map(({ tokenSha256, ...c }) => c), audit: s.audit };
    }
    if (p.action === "put-service") {
      if (!validId(p.id) || !validId(p.ownerId) || typeof p.name !== "string" || p.name.length > 80 || typeof p.url !== "string" ||
          !["streamable-http", "sse"].includes(p.transport) || (p.secretRef !== undefined && !validId(p.secretRef))) throw new GatewayError("invalid_service");
      validateTarget(p.url, this.env);
      const service: RegisteredService = { id: p.id, name: p.name, ownerId: p.ownerId, url: p.url, transport: p.transport,
        enabled: p.enabled === true, secretRef: p.secretRef, timeoutMs: Math.max(100, Math.min(30000, Number(p.timeoutMs) || 10000)),
        revision: crypto.randomUUID(), tools: [], lastError: null };
      if (!Number.isFinite(service.timeoutMs)) throw new GatewayError("invalid_service");
      await this.update(s => {
        const old = s.services.find(x => x.id === service.id);
        if (old && old.ownerId !== service.ownerId) throw new GatewayError("immutable_owner");
        if (!old && s.services.length >= 16) throw new GatewayError("service_limit");
        s.services = [...s.services.filter(x => x.id !== service.id), service];
        for (const c of s.clients) c.grants = c.grants.filter(g => g.serviceId !== service.id);
      });
      return { service };
    }
    if (p.action === "set-enabled") {
      await this.update(s => { const svc = s.services.find(x => x.id === p.id); if (!svc) throw new GatewayError("not_found", 404); svc.enabled = p.enabled === true; });
      return { ok: true };
    }
    if (p.action === "test") {
      const service = (await this.read()).services.find(s => s.id === p.id);
      if (!service) throw new GatewayError("not_found", 404);
      try { const tools = await discover(service, this.env); await this.saveCatalog(service, tools, null); return { tools, fingerprints: await Promise.all(tools.map(async t => ({ name: t.name, fingerprint: await fingerprint(t) }))) }; }
      catch (e) { await this.saveCatalog(service, [], e instanceof GatewayError ? e.code : "upstream_failure"); throw e; }
    }
    if (p.action === "put-client") {
      if (typeof p.id !== "string" || !/^(legacy:owner|oauth:.{1,200}|client:[a-z][a-z0-9-]{0,31})$/.test(p.id) ||
        !validId(p.userId) || !validId(p.charId) || (p.tokenSha256 !== undefined && !/^[a-f0-9]{64}$/.test(p.tokenSha256))) throw new GatewayError("invalid_client");
      await this.update(s => {
        const old = s.clients.find(c => c.id === p.id);
        if (old && (old.userId !== p.userId || old.charId !== p.charId)) throw new GatewayError("immutable_identity");
        if (!old && s.clients.length >= 128) throw new GatewayError("client_limit");
        if (p.tokenSha256 && s.clients.some(c => c.id !== p.id && c.tokenSha256 === p.tokenSha256)) throw new GatewayError("duplicate_token");
        if (!p.id.startsWith("client:") && p.tokenSha256) throw new GatewayError("invalid_client");
        s.clients = [...s.clients.filter(c => c.id !== p.id), { id: p.id, userId: p.userId, charId: p.charId,
          enabled: p.enabled === true, tokenSha256: p.tokenSha256 ?? old?.tokenSha256, grants: old?.grants ?? [] }];
      });
      return { ok: true };
    }
    if (p.action === "issue-client-token") {
      const bytes = new Uint8Array(32); crypto.getRandomValues(bytes);
      const token = "hub_client_" + Array.from(bytes, b => b.toString(16).padStart(2, "0")).join("");
      const digest = await sha256Hex(token);
      await this.update(s => {
        const c = s.clients.find(c => c.id === p.clientId && c.id.startsWith("client:"));
        if (!c) throw new GatewayError("not_found", 404);
        c.tokenSha256 = digest;
      });
      return { token };
    }
    if (p.action === "grant" || p.action === "revoke") {
      await this.update(async s => {
        const c = s.clients.find(c => c.id === p.clientId);
        const svc = s.services.find(x => x.id === p.serviceId);
        if (!c || !svc || c.userId !== svc.ownerId) throw new GatewayError("permission_denied", 403);
        c.grants = c.grants.filter(g => !(g.serviceId === p.serviceId && g.tool === p.tool));
        if (p.action === "grant") {
          const tool = svc.tools.find(t => t.name === p.tool);
          if (!tool || !["read", "confirm"].includes(p.mode) || p.fingerprint !== await fingerprint(tool)) throw new GatewayError("definition_changed", 409);
          c.grants.push({ serviceId: svc.id, tool: tool.name, mode: p.mode, fingerprint: p.fingerprint, revision: svc.revision });
        }
      });
      return { ok: true };
    }
    if (p.action === "confirm") {
      if (typeof p.clientId !== "string" || typeof p.name !== "string" || !p.arguments || typeof p.arguments !== "object" || Array.isArray(p.arguments)) throw new GatewayError("invalid_confirmation");
      const token = crypto.randomUUID();
      const ticket: Ticket = { principal: p.clientId, name: p.name, argsHash: await fingerprint(p.arguments), expires: Date.now() + 60000 };
      await this.update(s => {
        for (const [k, v] of Object.entries(s.tickets)) if (v.expires < Date.now()) delete s.tickets[k];
        if (Object.keys(s.tickets).length >= 128) throw new GatewayError("confirmation_limit");
        s.tickets[token] = ticket;
      });
      return { confirmation: token, expiresAt: ticket.expires };
    }
    throw new GatewayError("invalid_action");
  }
  private async saveCatalog(service: RegisteredService, tools: DiscoveredTool[], error: string | null): Promise<void> {
    await this.update(s => { const svc = s.services.find(x => x.id === service.id && x.revision === service.revision); if (svc) { svc.tools = tools; svc.lastError = error; } });
  }
  private async list(principal: string): Promise<DiscoveredTool[]> {
    const state = await this.read();
    const client = state.clients.find(c => c.id === principal && c.enabled);
    if (!client) return [];
    const output: DiscoveredTool[] = [];
    for (const service of state.services.filter(s => s.enabled && s.ownerId === client.userId)) {
      for (const tool of service.tools) {
        const fp = await fingerprint(tool);
        if (client.grants.some(g => g.serviceId === service.id && g.tool === tool.name && g.revision === service.revision && g.fingerprint === fp)) output.push({ ...tool, name: await gatewayName(service.id, tool.name) });
      }
    }
    if (new TextEncoder().encode(JSON.stringify(output)).byteLength > RESPONSE_LIMIT) throw new GatewayError("catalog_limit", 502);
    return output;
  }
  private async call(principal: string, p: Record<string, any>): Promise<unknown> {
    const state = await this.read();
    const client = state.clients.find(c => c.id === principal && c.enabled);
    const args = p.arguments ?? {};
    if (!client || typeof args !== "object" || args === null || Array.isArray(args)) throw new GatewayError("permission_denied", 403);
    let selected: { service: RegisteredService; grant: Grant } | undefined;
    for (const grant of client.grants) {
      const service = state.services.find(s => s.id === grant.serviceId && s.enabled && s.ownerId === client.userId && s.revision === grant.revision);
      if (service && await gatewayName(service.id, grant.tool) === p.name) {
        if (selected) throw new GatewayError("mapping_collision", 503);
        selected = { service, grant };
      }
    }
    if (!selected) { await this.audit({ principal, serviceId: "", tool: "", outcome: "permission_denied" }); throw new GatewayError("permission_denied", 403); }
    const { service, grant } = selected;
    let auditId: string | undefined;
    try {
      return await upstream(service, this.env, async (sdk, signal) => {
        // Fetch the live definition on this very connection. SDK validates input
        // and output schemas; all metadata is untrusted and grants stay local.
        const tools = await discoverOnClient(sdk, signal, service.timeoutMs);
        assertNoCredentials(tools, this.env);
        const tool = tools.find(t => t.name === grant.tool);
        if (!tool || await fingerprint(tool) !== grant.fingerprint) throw new GatewayError("definition_changed", 403);
        if (!new CfWorkerJsonSchemaValidator().getValidator(tool.inputSchema)(args).valid) throw new GatewayError("invalid_arguments");
        auditId = crypto.randomUUID();
        await this.update(async s => {
          const c = s.clients.find(c => c.id === principal && c.enabled);
          if (!c?.grants.some(g => canonical(g) === canonical(grant)) || !s.services.some(x => x.id === service.id && x.enabled && x.revision === service.revision)) throw new GatewayError("permission_denied", 403);
          if (grant.mode === "confirm") {
            const ticket = typeof p.confirmation === "string" && /^[a-f0-9-]{36}$/.test(p.confirmation) ? s.tickets[p.confirmation] : undefined;
            if (!ticket || ticket.principal !== principal || ticket.name !== p.name || ticket.expires < Date.now() || ticket.argsHash !== await fingerprint(args)) throw new GatewayError("confirmation_required", 403);
            delete s.tickets[p.confirmation];
          }
          s.audit.push({ id: auditId!, time: Date.now(), principal, serviceId: service.id, tool: grant.tool, outcome: "started" });
          s.audit = s.audit.slice(-200);
        });
        // Never retry this call, including 404/session expiry or ambiguous errors.
        const result = await sdk.callTool({ name: grant.tool, arguments: args }, undefined, { signal, timeout: service.timeoutMs });
        if (result.isError) throw new GatewayError("upstream_tool_error", 502);
        const encoded = JSON.stringify(result);
        if (new TextEncoder().encode(encoded).byteLength > RESPONSE_LIMIT) throw new GatewayError("upstream_size_limit", 502);
        assertNoCredentials(result, this.env);
        await this.finish(auditId!, "success");
        return result;
      });
    } catch (e) {
      const error = e instanceof GatewayError ? e : new GatewayError("upstream_failure", 502);
      if (auditId) await this.finish(auditId, error.code);
      else await this.audit({ principal, serviceId: service.id, tool: grant.tool, outcome: error.code });
      await this.saveCatalog(service, service.tools, error.code);
      throw error;
    }
  }
  private async audit(a: Omit<Audit, "id" | "time">): Promise<void> {
    await this.update(s => { s.audit.push({ ...a, id: crypto.randomUUID(), time: Date.now() }); s.audit = s.audit.slice(-200); });
  }
  private async finish(id: string, outcome: string): Promise<void> {
    await this.update(s => { const row = s.audit.find(a => a.id === id); if (row) row.outcome = outcome; });
  }
}
