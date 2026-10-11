// ============================================================================
//  OAuthStore — 授权服务器需要的**全部**状态
// ============================================================================
//
//  ── 为什么必须有状态（而不是"自己签个 JWT 就完了"）──────────────────────
//
//  ChatGPT 是**公开客户端**：它没有我们预先知道的密钥，重定向地址
//  （`https://chatgpt.com/connector/oauth/<callback_id>`）也是每次接入现生成的。
//  所以必须支持 RFC 7591 动态客户端注册（DCR）—— 那本身就是状态。
//
//  另外三件事也天然有状态：
//    · 授权码必须**一次性**（用过即废），否则重放能换到第二份令牌；
//    · 刷新令牌必须**轮换且可撤销**（RFC 9700 的建议），撤销就得有地方记；
//    · 同意页要防 CSRF/参数篡改，需要一个短命的、服务器记住的 consent_id。
//
//  ── 为什么是 Durable Object 而不是 D1 / KV ────────────────────────────────
//
//  任务书明令 **本轮不使用 D1**。KV 的写入是最终一致的，而"授权码只能用一次"
//  这种判定不能建立在最终一致之上（两个并发请求可能都读到"没用过"）。
//  DO 是单线程串行执行的，天然把"读—判—写"变成原子操作 —— 这正是安全状态
//  需要的东西。它也不是数据库：没有 SQL 端点、没有独立实例，数据只活在这个
//  对象自己的存储里。
//
//  ── 密钥存放策略 ──────────────────────────────────────────────────────────
//
//  令牌和授权码都**只存 SHA-256 摘要**，不存明文。理由与设备令牌一致：
//  万一有人 dump 了 DO 存储，拿到的东西不能直接当令牌用。查找时用摘要做键，
//  所以不需要遍历扫描。
// ============================================================================

import { sha256Hex, timingSafeEqual } from "../util/crypto";
import { logEvent } from "../util/log";

export interface OAuthClientRecord {
  clientId: string;
  clientName: string | null;
  redirectUris: string[];
  tokenEndpointAuthMethod: "none" | "client_secret_post" | "client_secret_basic";
  /** 公开客户端（none）没有密钥，此处为 null。 */
  clientSecretSha256: string | null;
  createdAt: number;
  /** 客户端从哪来：DCR 注册的，还是 CIMD（URL 形态 client_id）拉取来的。 */
  source: "dcr" | "cimd";
}

export interface OAuthCodeRecord {
  clientId: string;
  redirectUri: string;
  codeChallenge: string;
  scope: string;
  resource: string | null;
  createdAt: number;
  expiresAt: number;
}

export interface OAuthTokenRecord {
  kind: "access" | "refresh";
  clientId: string;
  scope: string;
  resource: string | null;
  createdAt: number;
  expiresAt: number;
  /** 轮换链：本刷新令牌由哪一个刷新令牌换来（摘要）。用于识别重放。 */
  rotatedFrom?: string;
}

export interface OAuthConsentRecord {
  clientId: string;
  clientName: string | null;
  redirectUri: string;
  state: string | null;
  codeChallenge: string;
  codeChallengeMethod: "S256";
  scope: string;
  resource: string | null;
  createdAt: number;
  expiresAt: number;
}

export interface OAuthClientSummary {
  clientId: string;
  clientName: string | null;
  redirectUris: string[];
  tokenEndpointAuthMethod: OAuthClientRecord["tokenEndpointAuthMethod"];
  hasSecret: boolean;
  createdAt: number;
  source: OAuthClientRecord["source"];
}

const PREFIX_CLIENT = "client:";
const PREFIX_CODE = "code:";
const PREFIX_ACCESS = "access:";
const PREFIX_REFRESH = "refresh:";
const PREFIX_CONSENT = "consent:";
const KEY_THROTTLE = "consent_throttle";

/**
 * 最多保留多少个客户端注册记录。
 *
 * `/oauth/register`（RFC 7591 DCR）**必须是公开的**：ChatGPT 在接入时会自己注册，
 * 我们没有办法在那一刻要求它先出示凭据。于是它天然是一个"未认证调用者能往里写"的入口。
 *
 * 而 `client:` 记录**没有 expiresAt**，`alarm()` 也就清不掉它们 ——
 * 也就是说，只要不加这道闸，任何人都能用一个循环把这块存储慢慢撑大
 * （每次 POST 换一个随机 client_id，就多一条记录）。
 *
 * 所以这里给一个上限。取值刻意远大于真实用量（单用户 + 单连接器 + 偶尔用别的
 * 客户端调试，通常个位数），让正常使用**永远碰不到**它，而循环灌入会在很早就被挡住。
 *
 * 为什么是"拒绝"而不是"淘汰最旧的"：被淘汰的记录一旦正是 ChatGPT 正在用的那个，
 * 它下次 authorize 就会拿着一个未知的 client_id 进来、用户侧表现为"突然要重新连"。
 * 拒绝新增最坏只是暂时挡住新客户端，不会破坏已经连上的。
 */
export const MAX_CLIENTS = 500;

/** 一次注册最多接受多少个 redirect_uri —— 避免有人用超长数组灌存储。 */
export const MAX_REDIRECT_URIS = 10;

/** 同意页连续失败多少次之后开始拒绝。 */
export const CONSENT_MAX_FAILURES = 10;
/** 拒绝窗口长度：窗口过去后自动放行，避免把自己永久锁死。 */
export const CONSENT_LOCKOUT_MS = 5 * 60 * 1000;

interface ThrottleRecord {
  failures: number;
  firstFailureAt: number;
  lockedUntil: number;
}

const ROUTES = {
  clientRegister: "/internal/oauth/client/register",
  clientGet: "/internal/oauth/client/get",
  codeCreate: "/internal/oauth/code/create",
  codeConsume: "/internal/oauth/code/consume",
  tokenIssue: "/internal/oauth/token/issue",
  accessGet: "/internal/oauth/access/get",
  refreshConsume: "/internal/oauth/refresh/consume",
  tokenRevoke: "/internal/oauth/token/revoke",
  consentCreate: "/internal/oauth/consent/create",
  consentGet: "/internal/oauth/consent/get",
  consentConsume: "/internal/oauth/consent/consume",
  throttleCheck: "/internal/oauth/throttle/check",
  throttleRecord: "/internal/oauth/throttle/record",
  stats: "/internal/oauth/stats",
} as const;

export const OAUTH_STORE_ROUTES = ROUTES;
export type OAuthStoreRoute = (typeof ROUTES)[keyof typeof ROUTES];

/**
 * 生成一个不透明令牌：`<prefix>_<32 字节 base64url>`。
 *
 * 用 `crypto.getRandomValues` 而不是 `crypto.randomUUID`：UUID v4 只有 122 bit
 * 熵，而令牌是可被暴力猜测就直接等于身份的东西，256 bit 才是合适的量级。
 */
export function newOpaqueToken(prefix: string): string {
  const bytes = new Uint8Array(32);
  crypto.getRandomValues(bytes);
  return `${prefix}_${base64url(bytes)}`;
}

export function base64url(bytes: Uint8Array): string {
  let binary = "";
  for (const b of bytes) binary += String.fromCharCode(b);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

/**
 * 校验 PKCE：`BASE64URL(SHA256(verifier)) === challenge`（S256）。
 *
 * 只支持 S256，**不支持 `plain`**：plain 等于没有 PKCE，而 ChatGPT 的元数据
 * 要求里也写明了 `code_challenge_methods_supported` 必须含 S256。
 */
export async function verifyPkceS256(verifier: string, challenge: string): Promise<boolean> {
  if (verifier.length < 43 || verifier.length > 128) return false;
  if (!/^[A-Za-z0-9\-._~]+$/.test(verifier)) return false;
  const digest = await sha256Hex(verifier);
  const bytes = new Uint8Array(32);
  for (let i = 0; i < 32; i++) bytes[i] = parseInt(digest.slice(i * 2, i * 2 + 2), 16);
  return timingSafeEqual(base64url(bytes), challenge);
}

export class OAuthStore implements DurableObject {
  private readonly ctx: DurableObjectState;

  constructor(ctx: DurableObjectState, _env: unknown) {
    this.ctx = ctx;
  }

  async fetch(request: Request): Promise<Response> {
    const url = new URL(request.url);
    switch (url.pathname) {
      case ROUTES.clientRegister:
        return this.handleClientRegister(request);
      case ROUTES.clientGet:
        return this.handleClientGet(request);
      case ROUTES.codeCreate:
        return this.handleCodeCreate(request);
      case ROUTES.codeConsume:
        return this.handleCodeConsume(request);
      case ROUTES.tokenIssue:
        return this.handleTokenIssue(request);
      case ROUTES.accessGet:
        return this.handleAccessGet(request);
      case ROUTES.refreshConsume:
        return this.handleRefreshConsume(request);
      case ROUTES.tokenRevoke:
        return this.handleTokenRevoke(request);
      case ROUTES.consentCreate:
        return this.handleConsentCreate(request);
      case ROUTES.consentGet:
        return this.handleConsentGet(request);
      case ROUTES.consentConsume:
        return this.handleConsentConsume(request);
      case ROUTES.throttleCheck:
        return this.handleThrottleCheck();
      case ROUTES.throttleRecord:
        return this.handleThrottleRecord(request);
      case ROUTES.stats:
        return this.handleStats();
      default:
        return json({ error: "not_found" }, 404);
    }
  }

  // ---- 客户端注册 --------------------------------------------------------

  private async handleClientRegister(request: Request): Promise<Response> {
    const body = await readJson<{
      redirectUris?: unknown;
      clientName?: unknown;
      tokenEndpointAuthMethod?: unknown;
      clientId?: unknown;
      source?: unknown;
    }>(request);
    if (body === null) return json({ error: "invalid_body" }, 400);

    const redirectUris = Array.isArray(body.redirectUris)
      ? body.redirectUris.filter((u): u is string => typeof u === "string")
      : [];
    if (redirectUris.length === 0) return json({ error: "missing_redirect_uris" }, 400);

    const method = normalizeAuthMethod(body.tokenEndpointAuthMethod);
    const clientId = typeof body.clientId === "string" && body.clientId !== ""
      ? body.clientId
      : `mcp_${base64url(randomBytes(16))}`;

    // 同 id 重复注册：只在完全没有记录时创建，避免覆盖已有的 redirect_uris
    // （否则任何人拿同一个 client_id 再注册一次就能改掉重定向目标）。
    const existing = await this.ctx.storage.get<OAuthClientRecord>(PREFIX_CLIENT + clientId);
    if (existing !== undefined) {
      return json({ client: summarize(existing), clientSecret: null, created: false });
    }

    // 上限闸门。放在 existing 检查**之后**：同一个 client_id 重复注册不算新增，
    // 不该被这道闸挡住（那是正常流程的一部分）。
    //
    // list 的 limit 让这里不需要把全部记录读进来 —— 我们只关心"到没到上限"。
    const probe = await this.ctx.storage.list({ prefix: PREFIX_CLIENT, limit: MAX_CLIENTS });
    if (probe.size >= MAX_CLIENTS) {
      logEvent("warn", "oauth.client_register_rejected", {
        reason: "client_limit_reached",
        limit: MAX_CLIENTS,
      });
      return json({ error: "client_limit_reached", limit: MAX_CLIENTS }, 429);
    }

    const clientSecret = method === "none" ? null : newOpaqueToken("mcp_cs");
    const record: OAuthClientRecord = {
      clientId,
      clientName: typeof body.clientName === "string" ? body.clientName.slice(0, 200) : null,
      redirectUris,
      tokenEndpointAuthMethod: method,
      clientSecretSha256: clientSecret === null ? null : await sha256Hex(clientSecret),
      createdAt: Date.now(),
      source: body.source === "cimd" ? "cimd" : "dcr",
    };
    await this.ctx.storage.put(PREFIX_CLIENT + clientId, record);
    await this.scheduleCleanup();
    return json({ client: summarize(record), clientSecret, created: true });
  }

  private async handleClientGet(request: Request): Promise<Response> {
    const body = await readJson<{ clientId?: unknown; clientSecret?: unknown }>(request);
    if (body === null || typeof body.clientId !== "string") return json({ error: "invalid_body" }, 400);

    const record = await this.ctx.storage.get<OAuthClientRecord>(PREFIX_CLIENT + body.clientId);
    if (record === undefined) return json({ found: false });

    // 带密钥来查的时候顺手校验密钥 —— 让调用方只需一次往返就能判定 "client 身份"。
    if (typeof body.clientSecret === "string" && record.clientSecretSha256 !== null) {
      const digest = await sha256Hex(body.clientSecret);
      if (!timingSafeEqual(digest, record.clientSecretSha256)) {
        return json({ found: true, secretValid: false });
      }
      return json({ found: true, secretValid: true, client: summarize(record) });
    }

    return json({ found: true, secretValid: record.clientSecretSha256 === null, client: summarize(record) });
  }

  // ---- 授权码 ------------------------------------------------------------

  private async handleCodeCreate(request: Request): Promise<Response> {
    const body = await readJson<{
      code?: unknown;
      clientId?: unknown;
      redirectUri?: unknown;
      codeChallenge?: unknown;
      scope?: unknown;
      resource?: unknown;
      ttlMs?: unknown;
    }>(request);
    if (body === null) return json({ error: "invalid_body" }, 400);
    if (typeof body.code !== "string" || typeof body.clientId !== "string" || typeof body.redirectUri !== "string") {
      return json({ error: "missing_fields" }, 400);
    }
    if (typeof body.codeChallenge !== "string") return json({ error: "missing_code_challenge" }, 400);

    const now = Date.now();
    const ttlMs = typeof body.ttlMs === "number" && Number.isFinite(body.ttlMs) ? body.ttlMs : 5 * 60_000;
    const record: OAuthCodeRecord = {
      clientId: body.clientId,
      redirectUri: body.redirectUri,
      codeChallenge: body.codeChallenge,
      scope: typeof body.scope === "string" ? body.scope : "",
      resource: typeof body.resource === "string" ? body.resource : null,
      createdAt: now,
      expiresAt: now + ttlMs,
    };
    await this.ctx.storage.put(PREFIX_CODE + (await sha256Hex(body.code)), record);
    await this.scheduleCleanup();
    return json({ ok: true });
  }

  /**
   * 一次性消费授权码。
   *
   * ★ 关键：读取与删除在同一个 DO 请求里完成，中间没有 await 让出控制权，
   *   所以两个并发的 token 请求不可能都拿到 `ok: true`。
   */
  private async handleCodeConsume(request: Request): Promise<Response> {
    const body = await readJson<{
      code?: unknown;
      clientId?: unknown;
      redirectUri?: unknown;
      codeVerifier?: unknown;
    }>(request);
    if (body === null) return json({ error: "invalid_body" }, 400);
    if (typeof body.code !== "string" || typeof body.clientId !== "string") {
      return json({ ok: false, error: "invalid_request" }, 200);
    }

    const key = PREFIX_CODE + (await sha256Hex(body.code));
    const record = await this.ctx.storage.get<OAuthCodeRecord>(key);
    if (record === undefined) return json({ ok: false, error: "invalid_grant" }, 200);
    await this.ctx.storage.delete(key);

    if (Date.now() > record.expiresAt) return json({ ok: false, error: "invalid_grant" }, 200);
    if (record.clientId !== body.clientId) return json({ ok: false, error: "invalid_grant" }, 200);
    if (typeof body.redirectUri === "string" && body.redirectUri !== record.redirectUri) {
      return json({ ok: false, error: "invalid_grant" }, 200);
    }
    if (typeof body.codeVerifier !== "string" || !(await verifyPkceS256(body.codeVerifier, record.codeChallenge))) {
      return json({ ok: false, error: "invalid_grant" }, 200);
    }

    return json({ ok: true, record });
  }

  // ---- 令牌 --------------------------------------------------------------

  private async handleTokenIssue(request: Request): Promise<Response> {
    const body = await readJson<{
      accessToken?: unknown;
      refreshToken?: unknown;
      clientId?: unknown;
      scope?: unknown;
      resource?: unknown;
      accessTtlMs?: unknown;
      refreshTtlMs?: unknown;
      rotatedFrom?: unknown;
    }>(request);
    if (body === null) return json({ error: "invalid_body" }, 400);
    if (typeof body.accessToken !== "string" || typeof body.refreshToken !== "string" || typeof body.clientId !== "string") {
      return json({ error: "missing_fields" }, 400);
    }

    const now = Date.now();
    const accessTtlMs = numberOr(body.accessTtlMs, 60 * 60_000);
    const refreshTtlMs = numberOr(body.refreshTtlMs, 30 * 24 * 60 * 60_000);
    const scope = typeof body.scope === "string" ? body.scope : "";
    const resource = typeof body.resource === "string" ? body.resource : null;

    const access: OAuthTokenRecord = {
      kind: "access",
      clientId: body.clientId,
      scope,
      resource,
      createdAt: now,
      expiresAt: now + accessTtlMs,
    };
    const refresh: OAuthTokenRecord = {
      kind: "refresh",
      clientId: body.clientId,
      scope,
      resource,
      createdAt: now,
      expiresAt: now + refreshTtlMs,
      ...(typeof body.rotatedFrom === "string" ? { rotatedFrom: body.rotatedFrom } : {}),
    };

    await this.ctx.storage.put({
      [PREFIX_ACCESS + (await sha256Hex(body.accessToken))]: access,
      [PREFIX_REFRESH + (await sha256Hex(body.refreshToken))]: refresh,
    });
    await this.scheduleCleanup();
    return json({ ok: true });
  }

  private async handleAccessGet(request: Request): Promise<Response> {
    const body = await readJson<{ accessToken?: unknown }>(request);
    if (body === null || typeof body.accessToken !== "string") return json({ error: "invalid_body" }, 400);

    const record = await this.ctx.storage.get<OAuthTokenRecord>(PREFIX_ACCESS + (await sha256Hex(body.accessToken)));
    if (record === undefined) return json({ valid: false, reason: "unknown_token" });
    if (Date.now() > record.expiresAt) {
      await this.ctx.storage.delete(PREFIX_ACCESS + (await sha256Hex(body.accessToken)));
      return json({ valid: false, reason: "expired" });
    }
    return json({ valid: true, record });
  }

  /**
   * 消费刷新令牌（轮换）。
   *
   * ★ 同样是"读出即删除"，保证一个刷新令牌只能用一次 —— 这正是 RFC 9700
   *   要求的轮换。旧令牌被重放时要么查不到（已被换掉），要么落在同一个 DO
   *   串行队列里排在真正的那次之后，结果一致：第二次必定 `invalid_grant`。
   */
  private async handleRefreshConsume(request: Request): Promise<Response> {
    const body = await readJson<{ refreshToken?: unknown; clientId?: unknown }>(request);
    if (body === null) return json({ error: "invalid_body" }, 400);
    if (typeof body.refreshToken !== "string" || typeof body.clientId !== "string") {
      return json({ ok: false, error: "invalid_request" }, 200);
    }

    const digest = await sha256Hex(body.refreshToken);
    const key = PREFIX_REFRESH + digest;
    const record = await this.ctx.storage.get<OAuthTokenRecord>(key);
    if (record === undefined) return json({ ok: false, error: "invalid_grant" }, 200);
    await this.ctx.storage.delete(key);

    if (Date.now() > record.expiresAt) return json({ ok: false, error: "invalid_grant" }, 200);
    if (record.clientId !== body.clientId) return json({ ok: false, error: "invalid_grant" }, 200);

    return json({ ok: true, record, tokenDigest: digest });
  }

  private async handleTokenRevoke(request: Request): Promise<Response> {
    const body = await readJson<{ token?: unknown }>(request);
    if (body === null || typeof body.token !== "string") return json({ error: "invalid_body" }, 400);
    const digest = await sha256Hex(body.token);
    // RFC 7009：撤销一个不存在的令牌也必须回 200，不能成为"这个令牌存在吗"的探针。
    await this.ctx.storage.delete([PREFIX_ACCESS + digest, PREFIX_REFRESH + digest]);
    return json({ ok: true });
  }

  // ---- 同意页 ------------------------------------------------------------

  private async handleConsentCreate(request: Request): Promise<Response> {
    const body = await readJson<{
      clientId?: unknown;
      clientName?: unknown;
      redirectUri?: unknown;
      state?: unknown;
      codeChallenge?: unknown;
      scope?: unknown;
      resource?: unknown;
      ttlMs?: unknown;
    }>(request);
    if (body === null) return json({ error: "invalid_body" }, 400);
    if (typeof body.clientId !== "string" || typeof body.redirectUri !== "string" || typeof body.codeChallenge !== "string") {
      return json({ error: "missing_fields" }, 400);
    }

    const consentId = base64url(randomBytes(24));
    const now = Date.now();
    const record: OAuthConsentRecord = {
      clientId: body.clientId,
      clientName: typeof body.clientName === "string" ? body.clientName : null,
      redirectUri: body.redirectUri,
      state: typeof body.state === "string" ? body.state : null,
      codeChallenge: body.codeChallenge,
      codeChallengeMethod: "S256",
      scope: typeof body.scope === "string" ? body.scope : "",
      resource: typeof body.resource === "string" ? body.resource : null,
      createdAt: now,
      expiresAt: now + numberOr(body.ttlMs, 10 * 60_000),
    };
    await this.ctx.storage.put(PREFIX_CONSENT + consentId, record);
    await this.scheduleCleanup();
    return json({ consentId });
  }

  /**
   * 只读地看一眼同意请求。
   *
   * 为什么要有"只看不删"这一步：同意页提交时如果先消费再验口令，那么**输错
   * 一次口令就会把整个授权请求作废**，用户得回到 ChatGPT 从头再来一遍。
   * 所以流程是：先 peek → 验口令 → 才 consume。consume 仍然是一次性的，
   * 只是把"作废"推迟到真正决定的那一刻。
   */
  private async handleConsentGet(request: Request): Promise<Response> {
    const body = await readJson<{ consentId?: unknown }>(request);
    if (body === null || typeof body.consentId !== "string") return json({ error: "invalid_body" }, 400);

    const record = await this.ctx.storage.get<OAuthConsentRecord>(PREFIX_CONSENT + body.consentId);
    if (record === undefined) return json({ ok: false, error: "unknown_consent" }, 200);
    if (Date.now() > record.expiresAt) return json({ ok: false, error: "expired_consent" }, 200);
    return json({ ok: true, record });
  }

  private async handleConsentConsume(request: Request): Promise<Response> {
    const body = await readJson<{ consentId?: unknown }>(request);
    if (body === null || typeof body.consentId !== "string") return json({ error: "invalid_body" }, 400);

    const key = PREFIX_CONSENT + body.consentId;
    const record = await this.ctx.storage.get<OAuthConsentRecord>(key);
    if (record === undefined) return json({ ok: false, error: "unknown_consent" }, 200);
    // 一次性：无论批准还是拒绝都用掉，防止"反复提交同一个 consent_id"。
    await this.ctx.storage.delete(key);
    if (Date.now() > record.expiresAt) return json({ ok: false, error: "expired_consent" }, 200);
    return json({ ok: true, record });
  }

  // ---- 同意页失败节流 ----------------------------------------------------

  private async handleThrottleCheck(): Promise<Response> {
    const t = (await this.ctx.storage.get<ThrottleRecord>(KEY_THROTTLE)) ?? {
      failures: 0,
      firstFailureAt: 0,
      lockedUntil: 0,
    };
    const now = Date.now();
    if (t.lockedUntil > now) {
      return json({ allowed: false, retryAfterMs: t.lockedUntil - now });
    }
    return json({ allowed: true, failures: t.failures });
  }

  private async handleThrottleRecord(request: Request): Promise<Response> {
    const body = await readJson<{ ok?: unknown }>(request);
    if (body === null || typeof body.ok !== "boolean") return json({ error: "invalid_body" }, 400);
    const now = Date.now();

    if (body.ok) {
      await this.ctx.storage.delete(KEY_THROTTLE);
      return json({ ok: true, locked: false });
    }

    const prev = (await this.ctx.storage.get<ThrottleRecord>(KEY_THROTTLE)) ?? {
      failures: 0,
      firstFailureAt: now,
      lockedUntil: 0,
    };
    // 窗口过期就重新计数，避免几天前的失败累积到把运维锁在外面。
    const firstFailureAt = now - prev.firstFailureAt > CONSENT_LOCKOUT_MS ? now : prev.firstFailureAt;
    const failures = now - prev.firstFailureAt > CONSENT_LOCKOUT_MS ? 1 : prev.failures + 1;
    const lockedUntil = failures >= CONSENT_MAX_FAILURES ? now + CONSENT_LOCKOUT_MS : 0;

    await this.ctx.storage.put(KEY_THROTTLE, { failures, firstFailureAt, lockedUntil });
    await this.scheduleCleanup(lockedUntil || now + CONSENT_LOCKOUT_MS);
    return json({ ok: true, locked: lockedUntil > 0, failures });
  }

  // ---- 自检 / 清理 -------------------------------------------------------

  private async handleStats(): Promise<Response> {
    const counts = { clients: 0, codes: 0, accessTokens: 0, refreshTokens: 0, consents: 0 };
    for (const prefix of Object.keys(counts) as (keyof typeof counts)[]) {
      const map = await this.ctx.storage.list({ prefix: PREFIX_OF[prefix] });
      counts[prefix] = map.size;
    }
    return json({ counts });
  }

  /** 过期清理。不删除还没过期的项。 */
  async alarm(): Promise<void> {
    const now = Date.now();
    const expired: string[] = [];
    for (const prefix of [PREFIX_CODE, PREFIX_ACCESS, PREFIX_REFRESH, PREFIX_CONSENT]) {
      const map = await this.ctx.storage.list<{ expiresAt: number }>({ prefix });
      for (const [key, value] of map) {
        if (value !== undefined && typeof value.expiresAt === "number" && value.expiresAt <= now) {
          expired.push(key);
        }
      }
    }
    if (expired.length > 0) await this.ctx.storage.delete(expired);
    await this.ctx.storage.setAlarm(now + 60 * 60 * 1000);
  }

  /**
   * 安排一次清理闹钟。
   *
   * Durable Object 在空闲后会被驱逐，内存里的一切都会丢 —— 所以过期清理
   * 不能只靠"下次请求顺手删"，得有个闹钟把对象唤醒。这就是它存在的理由。
   */
  private async scheduleCleanup(notBefore?: number): Promise<void> {
    const target = notBefore ?? Date.now() + 60 * 60 * 1000;
    const existing = await this.ctx.storage.getAlarm();
    if (existing === null || existing > target) {
      await this.ctx.storage.setAlarm(target);
    }
  }
}

const PREFIX_OF = {
  clients: PREFIX_CLIENT,
  codes: PREFIX_CODE,
  accessTokens: PREFIX_ACCESS,
  refreshTokens: PREFIX_REFRESH,
  consents: PREFIX_CONSENT,
} as const;

function summarize(record: OAuthClientRecord): OAuthClientSummary {
  return {
    clientId: record.clientId,
    clientName: record.clientName,
    redirectUris: record.redirectUris,
    tokenEndpointAuthMethod: record.tokenEndpointAuthMethod,
    hasSecret: record.clientSecretSha256 !== null,
    createdAt: record.createdAt,
    source: record.source,
  };
}

export function normalizeAuthMethod(
  raw: unknown,
): OAuthClientRecord["tokenEndpointAuthMethod"] {
  if (raw === "client_secret_post") return "client_secret_post";
  if (raw === "client_secret_basic") return "client_secret_basic";
  // 缺省与任何无法识别的值都落到 none：**公开客户端 + PKCE** 是这里的默认强度，
  // 而不是"没写就当作有密钥"。真正需要密钥的部署必须显式声明。
  return "none";
}

function numberOr(raw: unknown, fallback: number): number {
  return typeof raw === "number" && Number.isFinite(raw) && raw > 0 ? Math.floor(raw) : fallback;
}

function randomBytes(n: number): Uint8Array {
  const bytes = new Uint8Array(n);
  crypto.getRandomValues(bytes);
  return bytes;
}

async function readJson<T>(request: Request): Promise<T | null> {
  try {
    const value = (await request.json()) as unknown;
    if (typeof value !== "object" || value === null) return null;
    return value as T;
  } catch {
    return null;
  }
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store" },
  });
}
