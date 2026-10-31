import { CfWorkerJsonSchemaValidator } from "@modelcontextprotocol/sdk/validation/cfworker";
import type { Env } from "../env";
import { randomHex, sha256Hex, timingSafeEqual } from "../util/crypto";
import { jsonResponse } from "../util/http";
import {
  boundedText,
  canonical,
  fingerprint,
  GatewayError,
  parseScopes,
  REQUEST_LIMIT,
  RESPONSE_LIMIT,
  scopeAllows,
  validId,
  validateTarget,
  type TokenScope,
} from "./security";
import { assertNoCredentials, discover, discoverOnClient, redactCredentials, upstream, type DiscoveredTool, type Service } from "./upstream";

interface Grant { serviceId: string; tool: string; fingerprint: string; revision: string; mode: "read" | "confirm" }

/**
 * 一条客户端访问令牌。
 *
 * 只存 SHA-256 摘要：明文在签发响应里出现一次，之后任何管理接口（包括 DO 内部
 * 的 list）都不会再返回它。`id` 是令牌明文里可以见到的 8 位十六进制标识，用来
 * 在界面上区分"哪一条"，以及让撤销操作不必碰摘要。
 */
interface AccessToken {
  id: string;
  label: string;
  digest: string;
  scopes: TokenScope[];
  /** pair = 设备配对自动签发（轮换配对令牌时整组替换）；issued = 管理员显式签发。 */
  source: "pair" | "issued";
  createdAt: number;
  expiresAt: number | null;
  lastUsedAt: number | null;
  revokedAt: number | null;
}

interface ClientRecord {
  id: string;
  userId: string;
  charId: string;
  enabled: boolean;
  grants: Grant[];
  tokens: AccessToken[];
  /** 旧版单令牌字段：只用于读取历史状态，写入时不再出现。 */
  tokenSha256?: string;
}
interface RegisteredService extends Service { revision: string; tools: DiscoveredTool[]; lastError: string | null; lastTestAt: number | null }
interface Ticket { principal: string; name: string; argsHash: string; expires: number }
interface Audit { id: string; time: number; principal: string; serviceId: string; tool: string; outcome: string }
/**
 * Android 配对产生的**受限**管理身份。
 *
 * 与全局 `GATEWAY_ADMIN_TOKEN` 刻意分开：它只能看/改自己 ownerId 名下的服务，
 * 只能给自己的 clientId 授权，不能创建别的客户端、不能签发别人的令牌。
 * 两个令牌都只存 SHA-256；明文只在配对响应里出现一次。
 */
interface AndroidAdmin {
  id: string;
  ownerId: string;
  clientId: string;
  tokenSha256: string;
  deviceId: string;
  deviceName: string;
  createdAt: number;
  lastPairedAt: number;
  revokedAt: number | null;
}
interface State { services: RegisteredService[]; clients: ClientRecord[]; tickets: Record<string, Ticket>; audit: Audit[]; androidAdmins: AndroidAdmin[] }
const empty = (): State => ({ services: [], clients: [], tickets: {}, audit: [], androidAdmins: [] });

/**
 * 旧对象里没有 androidAdmins / tokens；读取时补默认值，避免升级后把整份注册表
 * 读成空。旧版的单令牌字段会在**读取时**合成为一条新式令牌记录，写回后自然消失。
 */
function normalize(raw: Partial<State> | undefined): State {
  return {
    services: raw?.services ?? [],
    clients: (raw?.clients ?? []).map((client) => ({
      id: client.id,
      userId: client.userId,
      charId: client.charId,
      enabled: client.enabled,
      grants: client.grants ?? [],
      tokens: client.tokens ?? (client.tokenSha256
        ? [{
            id: legacyTokenId(client.tokenSha256),
            label: "旧版客户端令牌",
            digest: client.tokenSha256,
            scopes: ["read", "write"] as TokenScope[],
            source: "issued" as const,
            createdAt: 0,
            expiresAt: null,
            lastUsedAt: null,
            revokedAt: null,
          }]
        : []),
    })),
    tickets: raw?.tickets ?? {},
    audit: raw?.audit ?? [],
    androidAdmins: raw?.androidAdmins ?? [],
  };
}

/** 新式令牌明文：`hub_client_<8 位 id>_<64 位 secret>`。id 用来快速定位记录。 */
const TOKEN_PATTERN = /^hub_client_([a-f0-9]{8})_[a-f0-9]{64}$/;
const TOKEN_ID_PATTERN = /^[a-f0-9]{8}$/;

function newTokenId(): string {
  const bytes = new Uint8Array(4);
  crypto.getRandomValues(bytes);
  return Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("");
}

/** 旧版令牌（`hub_client_` + 64 hex）没有内嵌 id；用摘要前缀合成一个稳定的展示 id。 */
function legacyTokenId(digest: string): string {
  return digest.slice(0, 8);
}

function mintToken(id: string): string {
  return `hub_client_${id}_${randomHex(32)}`;
}

function tokenIdFrom(plain: string): string | null {
  return TOKEN_PATTERN.exec(plain)?.[1] ?? null;
}

function activeToken(token: AccessToken, now: number): boolean {
  return token.revokedAt === null && (token.expiresAt === null || token.expiresAt > now);
}

export const gatewayName = async (serviceId: string, tool: string): Promise<string> => `ext_${serviceId}_${(await sha256Hex(tool)).slice(0, 24)}`;

/** Single coordination object: persistent config, atomic confirmation consumption,
 * and bounded in-flight requests across all Worker isolates. No phone state. */
export class GatewayStore {
  private inFlight = 0;
  private readonly perClient = new Map<string, number>();
  /** 配对失败限速：内存即可（单实例 DO，重启清零可接受）。 */
  private pairFailures = 0;
  private pairBlockedUntil = 0;
  constructor(private readonly ctx: DurableObjectState, private readonly env: Env) {}
  private async read(): Promise<State> { return normalize(await this.ctx.storage.get<Partial<State>>("gateway-v1")); }
  private async update<T>(fn: (state: State) => T | Promise<T>): Promise<T> {
    return this.ctx.storage.transaction(async txn => {
      const state = normalize(await txn.get<Partial<State>>("gateway-v1"));
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
      if (action === "/pair") return jsonResponse(await this.pair(p));
      if (action === "/android") {
        const admin = await this.androidPrincipal(p.digest);
        if (!admin) return jsonResponse({ error: "unauthorized" }, { status: 401 });
        const inner = p.request;
        if (typeof inner !== "object" || inner === null || Array.isArray(inner)) throw new GatewayError("invalid_request");
        if ((inner as Record<string, unknown>).action === "test") {
          if (this.inFlight >= 8) throw new GatewayError("concurrency_limit", 429);
          this.inFlight++;
          try { return jsonResponse(await this.androidAdmin(admin, inner as Record<string, any>)); } finally { this.inFlight--; }
        }
        return jsonResponse(await this.androidAdmin(admin, inner as Record<string, any>));
      }
      if (action === "/auth") return jsonResponse(await this.authenticate(p.digest));
      if (action === "/admin") {
        if (p.action !== "test") return jsonResponse(await this.admin(p));
        if (this.inFlight >= 8) throw new GatewayError("concurrency_limit", 429);
        this.inFlight++;
        try { return jsonResponse(await this.admin(p)); } finally { this.inFlight--; }
      }
      if (action !== "/list" && action !== "/call") throw new GatewayError("not_found", 404);
      const principal = typeof p.principal === "string" ? p.principal : "";
      const scopes = parseScopes(p.scopes);
      if (this.inFlight >= 8 || (this.perClient.get(principal) ?? 0) >= 2) throw new GatewayError("concurrency_limit", 429);
      this.inFlight++; this.perClient.set(principal, (this.perClient.get(principal) ?? 0) + 1);
      try {
        if (action === "/list") return jsonResponse({ tools: await this.list(principal, scopes) });
        return jsonResponse(await this.call(principal, p, scopes));
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
  /**
   * 配对：凭一个高熵口令换回**受限**管理令牌 + 该客户端自己的 MCP 令牌。
   *
   * 口令只从 Worker secret 读取，绝不下发；两个令牌都只存摘要，明文只在这里返回一次。
   * 同一个 deviceId 重新配对会轮换两个令牌（旧值立即失效），但保留已授的 grant。
   */
  private async pair(p: Record<string, any>): Promise<unknown> {
    const expected = (this.env.GATEWAY_ANDROID_PAIRING_CODE ?? "").trim();
    if (expected === "") throw new GatewayError("not_found", 404);
    const now = Date.now();
    if (now < this.pairBlockedUntil) throw new GatewayError("pairing_rate_limited", 429);

    const code = typeof p.code === "string" ? p.code : "";
    const deviceId = typeof p.deviceId === "string" ? p.deviceId.trim() : "";
    const deviceName = typeof p.deviceName === "string" ? p.deviceName.trim().slice(0, 64) : "";
    const codeOk = code.length >= 8 && code.length <= 256 &&
      timingSafeEqual(await sha256Hex(code), await sha256Hex(expected));
    if (!codeOk || !validId(deviceId)) {
      this.pairFailures++;
      if (this.pairFailures >= 10) {
        this.pairBlockedUntil = now + 60_000;
        this.pairFailures = 0;
      }
      throw new GatewayError("pairing_denied", 401);
    }
    this.pairFailures = 0;

    const suffix = (await sha256Hex(deviceId)).slice(0, 12);
    const ownerId = `android-${suffix}`;
    const adminId = `android-${suffix}`;
    const clientId = `client:android-${suffix}`;
    const managementToken = `hub_admin_${randomHex(32)}`;
    const tokenId = newTokenId();
    const clientToken = mintToken(tokenId);
    const managementDigest = await sha256Hex(managementToken);
    const clientDigest = await sha256Hex(clientToken);

    await this.update(s => {
      const others = s.androidAdmins.filter(a => a.deviceId !== deviceId && a.revokedAt === null);
      if (others.length >= 8) throw new GatewayError("pairing_limit", 429);
      const existing = s.androidAdmins.find(a => a.deviceId === deviceId);
      const record: AndroidAdmin = {
        id: adminId,
        ownerId,
        clientId,
        tokenSha256: managementDigest,
        deviceId,
        deviceName,
        createdAt: existing?.createdAt ?? now,
        lastPairedAt: now,
        revokedAt: null,
      };
      s.androidAdmins = [...s.androidAdmins.filter(a => a.deviceId !== deviceId), record];
      const oldClient = s.clients.find(c => c.id === clientId);
      s.clients = [...s.clients.filter(c => c.id !== clientId), {
        id: clientId,
        userId: ownerId,
        charId: "android",
        enabled: true,
        grants: oldClient?.grants ?? [],
        // 重新配对会换一枚新令牌；旧令牌随整条记录一起消失，立即失效。
        tokens: [{
          id: tokenId,
          label: "设备配对令牌",
          digest: clientDigest,
          scopes: ["read", "write"],
          source: "pair",
          createdAt: now,
          expiresAt: null,
          lastUsedAt: null,
          revokedAt: null,
        }],
      }];
    });

    return { managementToken, clientToken, tokenId, ownerId, clientId, deviceId, pairedAt: now };
  }

  /**
   * 令牌鉴权。返回 principal + 该令牌的作用范围；令牌记录里没有任何明文
   * 可以反推出来，唯一能被验证的就是"摘要一致"。
   *
   * 新式令牌内嵌 id，先按 id 收缩候选；旧式令牌没有 id，只能逐条比较摘要
   * （最多 64 条，每条一次常数时间比较，不构成放大）。
   */
  private async authenticate(digest: unknown): Promise<{ principal: string | null; scopes?: TokenScope[] }> {
    if (typeof digest !== "string" || !/^[a-f0-9]{64}$/.test(digest)) return { principal: null };
    const state = await this.read();
    const now = Date.now();
    for (const client of state.clients) {
      if (!client.enabled) continue;
      for (const token of client.tokens) {
        if (!activeToken(token, now) || !timingSafeEqual(token.digest, digest)) continue;
        // 使用时间按分钟粒度落盘：鉴权路径不能每来一个请求就写一次注册表。
        if (token.lastUsedAt === null || now - token.lastUsedAt > 60_000) {
          await this.touch(client.id, token.id);
        }
        return { principal: client.id, scopes: token.scopes };
      }
    }
    return { principal: null };
  }

  /** 尽力而为：记录最近使用时间。失败不影响本次鉴权结果。 */
  private async touch(clientId: string, tokenId: string): Promise<void> {
    try {
      await this.update((s) => {
        const token = s.clients.find((c) => c.id === clientId)?.tokens.find((t) => t.id === tokenId);
        const now = Date.now();
        if (token && (token.lastUsedAt === null || now - token.lastUsedAt > 60_000)) token.lastUsedAt = now;
      });
    } catch {
      // 使用统计不是鉴权条件；存储失败只丢一次统计。
    }
  }

  private async androidPrincipal(digest: unknown): Promise<AndroidAdmin | null> {
    if (typeof digest !== "string" || !/^[a-f0-9]{64}$/.test(digest)) return null;
    const state = await this.read();
    return state.androidAdmins.find(a => a.revokedAt === null && timingSafeEqual(a.tokenSha256, digest)) ?? null;
  }

  /** 受限管理面：所有 owner/service/client 关系由服务端钉死，客户端传进来的同名字段一律覆盖。 */
  private async androidAdmin(admin: AndroidAdmin, p: Record<string, any>): Promise<unknown> {
    const action = p.action;
    if (action === "list") {
      const full = await this.admin({ action: "list" }) as {
        services: Array<{ ownerId: string }>;
        clients: Array<{ id: string }>;
        audit: Array<{ serviceId: string }>;
      };
      const services = full.services.filter(s => s.ownerId === admin.ownerId);
      const ids = new Set(services.map(s => (s as unknown as { id: string }).id));
      return {
        services,
        clients: full.clients.filter(c => c.id === admin.clientId),
        audit: full.audit.filter(a => ids.has(a.serviceId)),
        android: {
          id: admin.id, ownerId: admin.ownerId, clientId: admin.clientId,
          deviceId: admin.deviceId, deviceName: admin.deviceName,
          createdAt: admin.createdAt, lastPairedAt: admin.lastPairedAt,
        },
      };
    }
    if (action === "put-service") {
      if (typeof p.id !== "string" || !validId(p.id)) throw new GatewayError("invalid_service");
      const existing = (await this.read()).services.find(s => s.id === p.id);
      if (existing && existing.ownerId !== admin.ownerId) throw new GatewayError("permission_denied", 403);
      p.ownerId = admin.ownerId;
      return this.admin(p);
    }
    if (action === "set-enabled" || action === "test") {
      const svc = await this.ownedService(admin, p.id);
      void svc;
      return this.admin(p);
    }
    if (action === "delete-service") {
      await this.ownedService(admin, p.id);
      return this.admin(p);
    }
    if (action === "grant" || action === "revoke") {
      await this.ownedService(admin, p.serviceId);
      p.clientId = admin.clientId;
      return this.admin(p);
    }
    if (action === "confirm") {
      if (typeof p.name !== "string") throw new GatewayError("invalid_confirmation");
      await this.ownedGatewayName(admin, p.name);
      p.clientId = admin.clientId;
      return this.admin(p);
    }
    if (action === "rotate-client-token") {
      const id = newTokenId();
      const clientToken = mintToken(id);
      const digest = await sha256Hex(clientToken);
      const now = Date.now();
      await this.update(s => {
        const c = s.clients.find(c => c.id === admin.clientId && c.userId === admin.ownerId);
        if (!c) throw new GatewayError("not_found", 404);
        // 只轮换配对令牌：管理员额外签发的令牌（可能有独立标签/过期/范围）保持原样。
        c.tokens = c.tokens.map(t => t.source === "pair" && t.revokedAt === null ? { ...t, revokedAt: now } : t);
        c.tokens.push({
          id, label: "设备配对令牌", digest, scopes: ["read", "write"], source: "pair",
          createdAt: now, expiresAt: null, lastUsedAt: null, revokedAt: null,
        });
        c.enabled = true;
      });
      return { clientToken, tokenId: id };
    }
    if (action === "issue-client-token" || action === "revoke-client-token") {
      // 受限身份只能操作自己的客户端；同名参数一律覆盖，不接受调用方指定别人的 clientId。
      p.clientId = admin.clientId;
      return this.admin(p);
    }
    if (action === "unpair") {
      await this.update(s => {
        const a = s.androidAdmins.find(a => a.id === admin.id);
        if (a) a.revokedAt = Date.now();
        const c = s.clients.find(c => c.id === admin.clientId);
        if (c) {
          c.enabled = false;
          const now = Date.now();
          for (const t of c.tokens) if (t.revokedAt === null) t.revokedAt = now;
        }
      });
      return { ok: true };
    }
    throw new GatewayError("invalid_action");
  }

  private async ownedService(admin: AndroidAdmin, id: unknown): Promise<RegisteredService> {
    if (typeof id !== "string" || !validId(id)) throw new GatewayError("not_found", 404);
    const svc = (await this.read()).services.find(s => s.id === id);
    // 404 而不是 403：不向一个受限客户端确认"别人的服务存在"。
    if (!svc || svc.ownerId !== admin.ownerId) throw new GatewayError("not_found", 404);
    return svc;
  }

  private async ownedGatewayName(admin: AndroidAdmin, name: string): Promise<void> {
    const state = await this.read();
    for (const svc of state.services.filter(s => s.ownerId === admin.ownerId)) {
      for (const tool of svc.tools) {
        if (await gatewayName(svc.id, tool.name) === name) return;
      }
    }
    throw new GatewayError("not_found", 404);
  }

  private async admin(p: Record<string, any>): Promise<unknown> {
    if (p.action === "list") {
      const s = await this.read();
      return {
        services: await Promise.all(s.services.map(async service => ({ ...service, toolMap: await Promise.all(service.tools.map(async tool => ({ original: tool.name, name: await gatewayName(service.id, tool.name), fingerprint: await fingerprint(tool) }))) }))),
        // 令牌只出现在这里，且**只**有元数据：摘要永远不出 DO。
        clients: s.clients.map(({ tokenSha256: _legacy, tokens, ...c }) => ({
          ...c,
          tokens: tokens.map(({ digest: _digest, ...t }) => t),
        })),
        audit: s.audit,
        androidAdmins: s.androidAdmins.map(({ tokenSha256, ...a }) => a),
      };
    }
    if (p.action === "put-service") {
      if (!validId(p.id) || !validId(p.ownerId) || typeof p.name !== "string" || p.name.length > 80 || typeof p.url !== "string" ||
          !["streamable-http", "sse"].includes(p.transport) || (p.secretRef !== undefined && !validId(p.secretRef)) ||
          (p.description !== undefined && (typeof p.description !== "string" || p.description.length > 300))) throw new GatewayError("invalid_service");
      validateTarget(p.url, this.env);
      const service: RegisteredService = { id: p.id, name: p.name, ownerId: p.ownerId, url: p.url, transport: p.transport,
        enabled: p.enabled === true, description: p.description, secretRef: p.secretRef, timeoutMs: Math.max(100, Math.min(30000, Number(p.timeoutMs) || 10000)),
        revision: crypto.randomUUID(), tools: [], lastError: null, lastTestAt: null };
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
    if (p.action === "delete-service") {
      if (!validId(p.id)) throw new GatewayError("not_found", 404);
      await this.update(s => {
        const svc = s.services.find(x => x.id === p.id);
        if (!svc) throw new GatewayError("not_found", 404);
        s.services = s.services.filter(x => x.id !== p.id);
        for (const c of s.clients) c.grants = c.grants.filter(g => g.serviceId !== p.id);
        // 未使用的确认票据 60 秒后自然过期；删除服务后它们也匹配不到任何 grant。
      });
      return { ok: true };
    }
    if (p.action === "revoke-android") {
      if (typeof p.id !== "string" && typeof p.deviceId !== "string") throw new GatewayError("invalid_request");
      await this.update(s => {
        const admin = s.androidAdmins.find(a => a.revokedAt === null &&
          (p.id === a.id || p.deviceId === a.deviceId));
        if (!admin) throw new GatewayError("not_found", 404);
        admin.revokedAt = Date.now();
        const client = s.clients.find(c => c.id === admin.clientId);
        if (client) {
          client.enabled = false;
          for (const token of client.tokens) if (token.revokedAt === null) token.revokedAt = Date.now();
        }
      });
      return { ok: true };
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
      const providedDigest = typeof p.tokenSha256 === "string" ? p.tokenSha256 : undefined;
      await this.update(s => {
        const old = s.clients.find(c => c.id === p.id);
        if (old && (old.userId !== p.userId || old.charId !== p.charId)) throw new GatewayError("immutable_identity");
        if (!old && s.clients.length >= 128) throw new GatewayError("client_limit");
        if (providedDigest && s.clients.some(c => c.id !== p.id && c.tokens.some(t => t.digest === providedDigest))) throw new GatewayError("duplicate_token");
        if (!p.id.startsWith("client:") && providedDigest) throw new GatewayError("invalid_client");
        const tokens = providedDigest
          ? [{
              id: newTokenId(),
              label: "静态客户端令牌",
              digest: providedDigest,
              scopes: ["read", "write"] as TokenScope[],
              source: "issued" as const,
              createdAt: Date.now(),
              expiresAt: null,
              lastUsedAt: null,
              revokedAt: null,
            }]
          : old?.tokens ?? [];
        s.clients = [...s.clients.filter(c => c.id !== p.id), { id: p.id, userId: p.userId, charId: p.charId,
          enabled: p.enabled === true, grants: old?.grants ?? [], tokens }];
      });
      return { ok: true };
    }
    if (p.action === "issue-client-token") {
      const id = newTokenId();
      const token = mintToken(id);
      const digest = await sha256Hex(token);
      const label = (typeof p.label === "string" ? p.label.trim() : "").slice(0, 64) || "访问令牌";
      // 默认 90 天：签发一个永不过期的凭据应该是显式选择，不是默认值。
      const days = p.expiresInDays === undefined ? 90 : Number(p.expiresInDays);
      if (!Number.isInteger(days) || days < 0 || days > 3650) throw new GatewayError("invalid_token_options");
      const scopes = parseScopes(p.scopes ?? ["read", "write"])!;
      const now = Date.now();
      const expiresAt = days === 0 ? null : now + days * 86_400_000;
      await this.update(s => {
        const c = s.clients.find(c => c.id === p.clientId && c.id.startsWith("client:"));
        if (!c) throw new GatewayError("not_found", 404);
        // 失效超过 7 天的记录不再保留（界面上已经没有人会去点它）。
        c.tokens = c.tokens.filter(t => activeToken(t, now) ||
          (t.revokedAt !== null && t.revokedAt > now - 7 * 86_400_000) ||
          (t.expiresAt !== null && t.expiresAt > now - 7 * 86_400_000));
        if (c.tokens.filter(t => activeToken(t, now)).length >= 8) throw new GatewayError("token_limit");
        const total = s.clients.reduce((n, x) => n + x.tokens.filter(t => activeToken(t, now)).length, 0);
        if (total >= 64) throw new GatewayError("token_limit");
        c.tokens.push({ id, label, digest, scopes, source: "issued", createdAt: now, expiresAt, lastUsedAt: null, revokedAt: null });
      });
      return { token, tokenId: id, label, scopes, expiresAt };
    }
    if (p.action === "revoke-client-token") {
      if (typeof p.clientId !== "string" || typeof p.tokenId !== "string" || !TOKEN_ID_PATTERN.test(p.tokenId)) {
        throw new GatewayError("invalid_request");
      }
      await this.update(s => {
        const c = s.clients.find(c => c.id === p.clientId);
        const token = c?.tokens.find(t => t.id === p.tokenId);
        if (!c || !token) throw new GatewayError("not_found", 404);
        if (token.revokedAt === null) token.revokedAt = Date.now();
      });
      return { ok: true };
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
    await this.update(s => {
      const svc = s.services.find(x => x.id === service.id && x.revision === service.revision);
      if (svc) { svc.tools = tools; svc.lastError = error; svc.lastTestAt = Date.now(); }
    });
  }
  private async list(principal: string, scopes?: TokenScope[]): Promise<DiscoveredTool[]> {
    const state = await this.read();
    const client = state.clients.find(c => c.id === principal && c.enabled);
    if (!client) return [];
    const output: DiscoveredTool[] = [];
    for (const service of state.services.filter(s => s.enabled && s.ownerId === client.userId)) {
      for (const tool of service.tools) {
        const fp = await fingerprint(tool);
        if (client.grants.some(g => g.serviceId === service.id && g.tool === tool.name && g.revision === service.revision && g.fingerprint === fp && scopeAllows(scopes, g.mode))) output.push({ ...tool, name: await gatewayName(service.id, tool.name) });
      }
    }
    if (new TextEncoder().encode(JSON.stringify(output)).byteLength > RESPONSE_LIMIT) throw new GatewayError("catalog_limit", 502);
    return output;
  }
  private async call(principal: string, p: Record<string, any>, scopes?: TokenScope[]): Promise<unknown> {
    const state = await this.read();
    const client = state.clients.find(c => c.id === principal && c.enabled);
    const args = p.arguments ?? {};
    if (!client || typeof args !== "object" || args === null || Array.isArray(args)) throw new GatewayError("permission_denied", 403);
    let selected: { service: RegisteredService; grant: Grant } | undefined;
    for (const grant of client.grants) {
      // scope 只能收窄 grant：一个只读令牌即使 grant 是 confirm 模式也选不中。
      if (!scopeAllows(scopes, grant.mode)) continue;
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
        // 工具自己报的失败（isError）是业务结果，不是传输失败：脱敏后原样回给调用方。
        // 只有协议/网络/超时问题才走下面的 GatewayError 分支。
        // 成功的工具结果若含上游凭据，仍然失败关闭（credential_in_result），
        // 不能把脱敏后的“成功”当成原结果交给调用方。
        const sanitized = result.isError ? (redactCredentials(result, this.env) as typeof result) : result;
        const encoded = JSON.stringify(sanitized);
        if (new TextEncoder().encode(encoded).byteLength > RESPONSE_LIMIT) throw new GatewayError("upstream_size_limit", 502);
        assertNoCredentials(sanitized, this.env);
        await this.finish(auditId!, result.isError ? "upstream_tool_error" : "success");
        return sanitized;
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
