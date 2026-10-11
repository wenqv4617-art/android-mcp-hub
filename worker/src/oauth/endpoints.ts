// ============================================================================
//  OAuth 2.1 授权服务器端点（最小可用子集）
// ============================================================================
//
//  ── 为什么非做不可 ────────────────────────────────────────────────────────
//
//  ChatGPT 的「Add custom MCP server」只提供两种认证方式：**OAuth** 或
//  **No authentication**。它不支持自定义 API Key、静态 Bearer、自定义 header。
//  任务书又明令"不得为了省事禁用认证"，所以唯一的路就是实现 OAuth。
//
//  ── 本文件实现了什么 ──────────────────────────────────────────────────────
//
//    1. 受保护资源元数据      GET  /.well-known/oauth-protected-resource[/mcp]
//    2. 授权服务器元数据      GET  /.well-known/oauth-authorization-server
//                             （同时挂 /.well-known/openid-configuration 别名）
//    3. 动态客户端注册 DCR    POST /oauth/register        （RFC 7591）
//    4. 授权端点（同意页）     GET/POST /oauth/authorize    （RFC 6749 + PKCE）
//    5. 令牌端点              POST /oauth/token           （授权码 + 刷新轮换）
//    6. 撤销端点              POST /oauth/revoke          （RFC 7009）
//
//  ── 刻意**没有**做的事（以及为什么）──────────────────────────────────────
//
//    · 没有 `plain` PKCE —— 等于没有 PKCE。
//    · 没有隐式流（implicit）与密码流（password）—— 已被 OAuth 2.1 移除。
//    · 没有开放重定向 —— redirect_uri 必须与注册值**逐字节相等**。
//    · 没有把令牌放进 URL —— access/refresh token 只出现在 POST body 里；
//      URL 里只有一次性的 authorization code（这是 RFC 6749 规定的形态）。
//    · 没有用 Origin 头做任何信任判断 —— 它只是浏览器可伪造的请求头。
//      同意页的防篡改靠一次性 consent_id（服务器记住的），不靠来源。
//
//  ── 未配置时的行为 ────────────────────────────────────────────────────────
//
//  没有 `OAUTH_OWNER_SECRET` 就等于没有授权服务器：所有 OAuth 路由回 **404**
//  （不是 500）。这一点是官方要求里明写的 —— 未实现的发现路由回 5xx 会让
//  连接器把它当成基础设施故障而**永久失败**，回 404 才允许客户端走别的路。
// ============================================================================

import type { AppConfig } from "../config";
import type { Env } from "../env";
import { logEvent } from "../util/log";
import { sha256Hex, timingSafeEqual } from "../util/crypto";
import { jsonResponse, notFound } from "../util/http";
import { MAX_REDIRECT_URIS, newOpaqueToken, OAUTH_STORE_ROUTES, type OAuthClientRecord } from "./store-do";
import { HTML_HEADERS, renderConsentPage, renderErrorPage } from "./pages";

/** 授权服务器元数据里声明的端点路径。集中在这里，改一处即可。 */
export const OAUTH_PATHS = {
  protectedResource: "/.well-known/oauth-protected-resource",
  protectedResourceMcp: "/.well-known/oauth-protected-resource/mcp",
  authorizationServer: "/.well-known/oauth-authorization-server",
  openidConfiguration: "/.well-known/openid-configuration",
  authorize: "/oauth/authorize",
  token: "/oauth/token",
  register: "/oauth/register",
  revoke: "/oauth/revoke",
} as const;

/** 所有 OAuth 路径（index.ts 用它做路由分发）。 */
export const OAUTH_ROUTES: readonly string[] = Object.values(OAUTH_PATHS);

/**
 * 访问令牌的可见前缀（在调用 `newOpaqueToken("mcp_at")` 的地方定下来的）。
 *
 * 导出它是因为 `mcp/http.ts` 需要这个前置判断：只有长得像我们自己签发的令牌，
 * 才值得去问一次 OAuthStore。否则任何人拿一段随机字符串都会让 Worker 去敲 DO，
 * 公开端点就成了 DO 的放大器。
 */
export const OAUTH_ACCESS_TOKEN_PREFIX = "mcp_at_";

export function isOAuthPath(pathname: string): boolean {
  return (OAUTH_ROUTES as readonly string[]).includes(pathname);
}

// `config.oauth` 的类型定义在 `worker/src/config.ts`（`OAuthConfig`）——
// 刻意不在这里再定义一份，否则配置解析与消费者会各拿一个形状，改一边忘一边。

// ---- 元数据 ---------------------------------------------------------------

export function protectedResourceMetadata(request: Request, config: AppConfig): Response {
  const issuer = new URL(request.url).origin;
  return jsonResponse(
    {
      // 资源标识符必须与客户端用来连接 MCP 的 URL 一致。
      resource: `${issuer}/mcp`,
      authorization_servers: [issuer],
      scopes_supported: [config.oauth.scope],
      bearer_methods_supported: ["header"],
      resource_name: "Android MCP Hub",
    },
    { headers: corsHeaders(request) },
  );
}

export function authorizationServerMetadata(request: Request, config: AppConfig): Response {
  const issuer = new URL(request.url).origin;
  return jsonResponse(
    {
      issuer,
      authorization_endpoint: `${issuer}${OAUTH_PATHS.authorize}`,
      token_endpoint: `${issuer}${OAUTH_PATHS.token}`,
      registration_endpoint: `${issuer}${OAUTH_PATHS.register}`,
      revocation_endpoint: `${issuer}${OAUTH_PATHS.revoke}`,
      response_types_supported: ["code"],
      grant_types_supported: ["authorization_code", "refresh_token"],
      // ★ 官方要求里明写：这一项**必须**含 S256，否则 ChatGPT 拒绝接入。
      code_challenge_methods_supported: ["S256"],
      token_endpoint_auth_methods_supported: ["none", "client_secret_post", "client_secret_basic"],
      scopes_supported: [config.oauth.scope],
      // RFC 9207：声明我们会在授权响应里回 `iss`。
      authorization_response_iss_parameter_supported: true,
      service_documentation: `${issuer}/`,
    },
    { headers: corsHeaders(request) },
  );
}

// ---- 动态客户端注册（RFC 7591）--------------------------------------------

export async function handleRegister(request: Request, env: Env, config: AppConfig): Promise<Response> {
  if (!config.oauth.enabled) return notFound("OAuth 注册端点");

  if (request.method === "OPTIONS") return preflight(request);

  const body = await readJsonBody<Record<string, unknown>>(request);
  if (body === null) return oauthError(request, "invalid_client_metadata", "请求体必须是 JSON 对象", 400);

  const rawRedirects = Array.isArray(body["redirect_uris"]) ? body["redirect_uris"] : [];
  // 数组长度也要有界：每个元素都会被写进 DO 存储，不设上限就是一条灌存储的路径。
  if (rawRedirects.length > MAX_REDIRECT_URIS) {
    return oauthError(
      request,
      "invalid_client_metadata",
      `redirect_uris 最多 ${MAX_REDIRECT_URIS} 个`,
      400,
    );
  }
  const redirectUris: string[] = [];
  for (const candidate of rawRedirects) {
    if (typeof candidate !== "string") continue;
    const verdict = checkRedirectUri(candidate, config);
    if (!verdict.ok) {
      return oauthError(request, "invalid_redirect_uri", verdict.message, 400);
    }
    redirectUris.push(candidate);
  }
  if (redirectUris.length === 0) {
    return oauthError(request, "invalid_redirect_uri", "redirect_uris 不能为空", 400);
  }

  const method = typeof body["token_endpoint_auth_method"] === "string" ? body["token_endpoint_auth_method"] : "none";
  if (!["none", "client_secret_post", "client_secret_basic"].includes(method)) {
    return oauthError(request, "invalid_client_metadata", `不支持 token_endpoint_auth_method=${method}`, 400);
  }

  const created = await storeCall<{ client: { clientId: string; hasSecret: boolean }; clientSecret: string | null; created: boolean }>(
    env,
    OAUTH_STORE_ROUTES.clientRegister,
    {
      redirectUris,
      clientName: typeof body["client_name"] === "string" ? body["client_name"] : null,
      tokenEndpointAuthMethod: method,
      source: "dcr",
    },
  );
  // 429 是"到上限了"，不是"服务坏了" —— 混成 503 会让人去查根本不存在的基础设施故障。
  if (!created.ok && created.error === "store_http_429") {
    return oauthError(request, "temporarily_unavailable", "此服务器的客户端注册数量已达上限", 429);
  }
  if (!created.ok) return oauthError(request, "server_error", "客户端注册暂时不可用", 503);

  logEvent("info", "oauth.client_registered", { count: redirectUris.length, outcome: method });

  return jsonResponse(
    {
      client_id: created.value.client.clientId,
      client_id_issued_at: Math.floor(Date.now() / 1000),
      client_name: typeof body["client_name"] === "string" ? body["client_name"] : undefined,
      redirect_uris: redirectUris,
      token_endpoint_auth_method: method,
      grant_types: ["authorization_code", "refresh_token"],
      response_types: ["code"],
      scope: config.oauth.scope,
      ...(created.value.clientSecret !== null ? { client_secret: created.value.clientSecret } : {}),
    },
    { status: 201, headers: corsHeaders(request) },
  );
}

// ---- 授权端点 + 同意页 -----------------------------------------------------

/**
 * 把一页 HTML 套上 `HTML_HEADERS`（含 CSP）。
 *
 * 单独抽一个函数，是为了让下面每一处 `return` 都明确"这是在回一页 HTML"，
 * 而不是不小心把 `renderErrorPage()` 的字符串当成 `Response` 返回。
 */
function htmlResponse(body: string, status = 200): Response {
  return new Response(body, { status, headers: HTML_HEADERS });
}

export async function handleAuthorize(request: Request, env: Env, config: AppConfig): Promise<Response> {
  if (!config.oauth.enabled) return notFound("OAuth 授权端点");
  if (request.method === "GET") return authorizeGet(request, env, config);
  if (request.method === "POST") return authorizePost(request, env, config);
  return oauthError(request, "invalid_request", "只支持 GET / POST", 405);
}

async function authorizeGet(request: Request, env: Env, config: AppConfig): Promise<Response> {
  const url = new URL(request.url);
  const params = url.searchParams;

  const clientId = params.get("client_id") ?? "";
  const redirectUri = params.get("redirect_uri") ?? "";
  const responseType = params.get("response_type") ?? "";
  const state = params.get("state");
  const codeChallenge = params.get("code_challenge") ?? "";
  const codeChallengeMethod = params.get("code_challenge_method") ?? "";
  const scope = params.get("scope") ?? config.oauth.scope;
  const resource = params.get("resource");

  // 客户端解析：先查本地注册表；不是注册客户端时，接受 **URL 形态的 client_id**
  // （CIMD，MCP 规范 2026-07-28 的方向）。后者要真的去拉取那个 URL 并核对。
  const client = await resolveClient(env, config, clientId);
  if (client === null) {
    return htmlResponse(
      renderErrorPage("未知的 client_id", "这个授权请求来自一个没有注册过的客户端，已拒绝。"),
      400,
    );
  }

  // ★ redirect_uri 必须与注册值逐字节相等 —— 不做前缀匹配、不做大小写折叠。
  if (!client.redirectUris.includes(redirectUri)) {
    return htmlResponse(
      renderErrorPage(
        "redirect_uri 不匹配",
        "客户端请求的重定向地址与它注册时声明的不一致，已拒绝（不做模糊匹配）。",
      ),
      400,
    );
  }

  if (responseType !== "code") {
    return redirectError(redirectUri, "unsupported_response_type", state);
  }
  if (codeChallenge === "" || codeChallengeMethod !== "S256") {
    // 不给"没有 PKCE"留后门。
    return redirectError(redirectUri, "invalid_request", state, "必须使用 S256 的 PKCE");
  }
  const verdict = checkRedirectUri(redirectUri, config);
  if (!verdict.ok) return htmlResponse(renderErrorPage("redirect_uri 不被允许", verdict.message), 400);

  // RFC 8707：客户端带 resource 时必须指向我们自己的 MCP 端点。
  const issuer = new URL(request.url).origin;
  if (resource !== null && resource !== `${issuer}/mcp` && resource !== issuer) {
    return redirectError(redirectUri, "invalid_target", state);
  }

  const created = await storeCall<{ consentId: string }>(env, OAUTH_STORE_ROUTES.consentCreate, {
    clientId: client.clientId,
    clientName: client.clientName,
    redirectUri,
    state,
    codeChallenge,
    scope,
    resource,
    ttlMs: config.oauth.consentTtlMs,
  });
  if (!created.ok) {
    return htmlResponse(renderErrorPage("服务器暂时不可用", "无法创建授权请求，请稍后再试。"), 503);
  }

  return htmlResponse(
    renderConsentPage({
      consentId: created.value.consentId,
      clientId: client.clientId,
      clientName: client.clientName,
      redirectUri,
      scope,
      issuer,
    }),
  );
}

async function authorizePost(request: Request, env: Env, config: AppConfig): Promise<Response> {
  const origin = new URL(request.url).origin;
  const form = await readForm(request);
  if (form === null) return htmlResponse(renderErrorPage("请求格式不对", "同意页必须用表单提交。"), 400);

  const throttled = await storeCall<{ allowed: boolean; retryAfterMs?: number }>(
    env,
    OAUTH_STORE_ROUTES.throttleCheck,
  );
  if (throttled.ok && !throttled.value.allowed) {
    const seconds = Math.ceil((throttled.value.retryAfterMs ?? 0) / 1000);
    return htmlResponse(
      renderErrorPage("尝试次数过多", `口令连续输错太多次，请 ${seconds} 秒后再试。`),
      429,
    );
  }

  const presentedConsentId = form["consent_id"] ?? "";

  // 第 1 步：**只读**地取出授权请求。刻意不在这里消费 —— 先消费再验口令的话，
  // 输错一次口令就会把这个 consent 作废，用户得回 ChatGPT 从头再来一遍。
  const peeked = await storeCall<{ ok: boolean; record?: ConsentRecordShape; error?: string }>(
    env,
    OAUTH_STORE_ROUTES.consentGet,
    { consentId: presentedConsentId },
  );
  if (!peeked.ok || !peeked.value.ok || peeked.value.record === undefined) {
    return htmlResponse(
      renderErrorPage("授权请求已失效", "这个同意页已经用过或已过期，请在 ChatGPT 里重新发起连接。"),
      400,
    );
  }
  const record = peeked.value.record;

  // 第 2 步：明确拒绝。拒绝也要把一次性 consent 用掉，避免页面被反复提交。
  if (form["decision"] === "deny") {
    await storeCall(env, OAUTH_STORE_ROUTES.consentConsume, { consentId: presentedConsentId });
    logEvent("info", "oauth.consent_denied", {});
    return redirectError(record.redirectUri, "access_denied", record.state, undefined, origin);
  }

  // 第 3 步：验口令。失败**不消费** consent，只记一次失败（有节流兜底）。
  const ownerSecret = (env.OAUTH_OWNER_SECRET ?? "").trim();
  const presented = form["owner_secret"] ?? "";
  if (!(await constantTimeEqual(presented, ownerSecret))) {
    await storeCall(env, OAUTH_STORE_ROUTES.throttleRecord, { ok: false });
    logEvent("warn", "oauth.consent_rejected", { reason: "bad_owner_secret", errorCode: "unauthorized" });
    return htmlResponse(
      renderErrorPage("口令不对", "没有批准授权。刷新这一页可以重试，或检查 OAUTH_OWNER_SECRET 配置。"),
      401,
    );
  }
  await storeCall(env, OAUTH_STORE_ROUTES.throttleRecord, { ok: true });

  // 第 4 步：真正消费那个一次性 consent。拿不到说明有人和我们竞赛，
  // 或者这一页被提交了第二次 —— 两种情况都必须拒绝签发授权码。
  const consumed = await storeCall<{ ok: boolean; record?: ConsentRecordShape; error?: string }>(
    env,
    OAUTH_STORE_ROUTES.consentConsume,
    { consentId: presentedConsentId },
  );
  if (!consumed.ok || !consumed.value.ok) {
    return htmlResponse(
      renderErrorPage("授权请求已失效", "这个同意页已经被用过了，请在 ChatGPT 里重新发起连接。"),
      400,
    );
  }

  const code = newOpaqueToken("mcp_code");
  const saved = await storeCall<{ ok: boolean }>(env, OAUTH_STORE_ROUTES.codeCreate, {
    code,
    clientId: record.clientId,
    redirectUri: record.redirectUri,
    codeChallenge: record.codeChallenge,
    scope: record.scope,
    resource: record.resource,
    ttlMs: config.oauth.codeTtlMs,
  });
  if (!saved.ok || !saved.value.ok) {
    return htmlResponse(renderErrorPage("服务器暂时不可用", "无法签发授权码，请稍后再试。"), 503);
  }

  logEvent("info", "oauth.consent_granted", {});

  const target = new URL(record.redirectUri);
  target.searchParams.set("code", code);
  if (record.state !== null) target.searchParams.set("state", record.state);
  // RFC 9207 的 iss：明确告诉客户端"这个授权响应确实来自本授权服务器"，
  // 这正是防 OAuth mix-up 攻击的那一项。ChatGPT 也会据此选择重定向地址。
  target.searchParams.set("iss", new URL(request.url).origin);
  return Response.redirect(target.toString(), 302);
}

// ---- 令牌端点 --------------------------------------------------------------

export async function handleToken(request: Request, env: Env, config: AppConfig): Promise<Response> {
  if (!config.oauth.enabled) return notFound("OAuth 令牌端点");
  if (request.method === "OPTIONS") return preflight(request);
  if (request.method !== "POST") return oauthError(request, "invalid_request", "令牌端点只接受 POST", 405);

  const params = await readTokenParams(request);
  if (params === null) {
    return oauthError(request, "invalid_request", "请求体必须是 form-urlencoded 或 JSON", 400);
  }

  const grantType = params["grant_type"] ?? "";
  const clientId = params["client_id"] ?? "";
  const clientSecret = params["client_secret"] ?? null;

  const auth = await authenticateClient(env, clientId, clientSecret, request);
  if (!auth.ok) return oauthError(request, "invalid_client", auth.message, 401);

  if (grantType === "authorization_code") {
    return tokenFromCode(request, env, config, auth.client, params);
  }
  if (grantType === "refresh_token") {
    return tokenFromRefresh(request, env, config, auth.client, params);
  }
  return oauthError(request, "unsupported_grant_type", `不支持的 grant_type=${grantType}`, 400);
}

async function tokenFromCode(
  request: Request,
  env: Env,
  config: AppConfig,
  client: OAuthClientRecord,
  params: Record<string, string>,
): Promise<Response> {
  const code = params["code"] ?? "";
  const redirectUri = params["redirect_uri"] ?? "";
  const codeVerifier = params["code_verifier"] ?? "";
  if (code === "" || codeVerifier === "") {
    return oauthError(request, "invalid_request", "缺少 code 或 code_verifier", 400);
  }

  const consumed = await storeCall<{ ok: boolean; error?: string; record?: CodeShape }>(
    env,
    OAUTH_STORE_ROUTES.codeConsume,
    { code, clientId: client.clientId, redirectUri, codeVerifier },
  );
  if (!consumed.ok) return oauthError(request, "server_error", "令牌服务暂时不可用", 503);
  if (!consumed.value.ok || consumed.value.record === undefined) {
    logEvent("warn", "oauth.token_rejected", {
      reason: consumed.value.error ?? "invalid_grant",
      errorCode: "unauthorized",
    });
    return oauthError(request, "invalid_grant", "授权码无效、已用过或已过期", 400);
  }

  const record = consumed.value.record;
  const issued = await issueTokens(env, config, client.clientId, record.scope, record.resource);
  if (!issued.ok) return oauthError(request, "server_error", "签发令牌失败", 503);

  logEvent("info", "oauth.token_issued", { outcome: "authorization_code" });
  return tokenResponse(request, issued.value, record.scope);
}

async function tokenFromRefresh(
  request: Request,
  env: Env,
  config: AppConfig,
  client: OAuthClientRecord,
  params: Record<string, string>,
): Promise<Response> {
  const refreshToken = params["refresh_token"] ?? "";
  if (refreshToken === "") return oauthError(request, "invalid_request", "缺少 refresh_token", 400);

  const consumed = await storeCall<{ ok: boolean; error?: string; record?: TokenShape; tokenDigest?: string }>(
    env,
    OAUTH_STORE_ROUTES.refreshConsume,
    { refreshToken, clientId: client.clientId },
  );
  if (!consumed.ok) return oauthError(request, "server_error", "令牌服务暂时不可用", 503);
  if (!consumed.value.ok || consumed.value.record === undefined) {
    logEvent("warn", "oauth.refresh_rejected", {
      reason: consumed.value.error ?? "invalid_grant",
      errorCode: "unauthorized",
    });
    return oauthError(request, "invalid_grant", "刷新令牌无效、已轮换或已过期", 400);
  }

  const record = consumed.value.record;
  const issued = await issueTokens(
    env,
    config,
    client.clientId,
    record.scope,
    record.resource,
    consumed.value.tokenDigest,
  );
  if (!issued.ok) return oauthError(request, "server_error", "签发令牌失败", 503);

  logEvent("info", "oauth.token_issued", { outcome: "refresh_token" });
  // ★ 轮换：旧刷新令牌在 store 里已经被删除，这里返回的是**新的一对**。
  return tokenResponse(request, issued.value, record.scope);
}

async function issueTokens(
  env: Env,
  config: AppConfig,
  clientId: string,
  scope: string,
  resource: string | null,
  rotatedFrom?: string,
): Promise<{ ok: true; value: { accessToken: string; refreshToken: string; expiresIn: number } } | { ok: false }> {
  const accessToken = newOpaqueToken("mcp_at");
  const refreshToken = newOpaqueToken("mcp_rt");
  const stored = await storeCall<{ ok: boolean }>(env, OAUTH_STORE_ROUTES.tokenIssue, {
    accessToken,
    refreshToken,
    clientId,
    scope,
    resource,
    accessTtlMs: config.oauth.accessTokenTtlMs,
    refreshTtlMs: config.oauth.refreshTokenTtlMs,
    ...(rotatedFrom !== undefined ? { rotatedFrom } : {}),
  });
  if (!stored.ok || !stored.value.ok) return { ok: false };
  return {
    ok: true,
    value: { accessToken, refreshToken, expiresIn: Math.floor(config.oauth.accessTokenTtlMs / 1000) },
  };
}

function tokenResponse(
  request: Request,
  issued: { accessToken: string; refreshToken: string; expiresIn: number },
  scope: string,
): Response {
  return jsonResponse(
    {
      access_token: issued.accessToken,
      token_type: "Bearer",
      expires_in: issued.expiresIn,
      refresh_token: issued.refreshToken,
      scope,
    },
    { headers: corsHeaders(request) },
  );
}

// ---- 撤销（RFC 7009）-------------------------------------------------------

export async function handleRevoke(request: Request, env: Env, config: AppConfig): Promise<Response> {
  if (!config.oauth.enabled) return notFound("OAuth 撤销端点");
  if (request.method === "OPTIONS") return preflight(request);
  if (request.method !== "POST") return oauthError(request, "invalid_request", "撤销端点只接受 POST", 405);

  const params = await readTokenParams(request);
  if (params === null) return oauthError(request, "invalid_request", "请求体格式不对", 400);
  const token = params["token"] ?? "";
  if (token === "") return oauthError(request, "invalid_request", "缺少 token", 400);

  await storeCall(env, OAUTH_STORE_ROUTES.tokenRevoke, { token });
  // 无论令牌是否存在都回 200（RFC 7009），避免这个端点变成"令牌是否存在"的探针。
  return jsonResponse({}, { headers: corsHeaders(request) });
}

// ---- 供 /mcp 使用的访问令牌校验 --------------------------------------------

export type AccessTokenVerdict =
  | { ok: true; clientId: string; scope: string }
  | { ok: false; reason: "oauth_disabled" | "unknown_token" | "expired" | "insufficient_scope" | "store_unavailable" };

export async function verifyAccessToken(
  env: Env,
  config: AppConfig,
  token: string,
  expectedResource: string,
): Promise<AccessTokenVerdict> {
  if (!config.oauth.enabled) return { ok: false, reason: "oauth_disabled" };

  const result = await storeCall<{ valid: boolean; reason?: string; record?: TokenShape }>(
    env,
    OAUTH_STORE_ROUTES.accessGet,
    { accessToken: token },
  );
  if (!result.ok) return { ok: false, reason: "store_unavailable" };
  if (!result.value.valid || result.value.record === undefined) {
    return { ok: false, reason: result.value.reason === "expired" ? "expired" : "unknown_token" };
  }

  const record = result.value.record;
  if (Date.now() > record.expiresAt) return { ok: false, reason: "expired" };

  // 令牌必须是为**这个**资源签发的：别的资源（例如将来的第二类端点）的令牌
  // 不能拿来调用 /mcp。这就是 RFC 8707 的 audience 约束在服务端的落点。
  if (record.resource !== null && record.resource !== expectedResource) {
    return { ok: false, reason: "insufficient_scope" };
  }

  const granted = record.scope.split(" ").filter((s) => s !== "");
  if (!granted.includes(config.oauth.scope)) return { ok: false, reason: "insufficient_scope" };

  return { ok: true, clientId: record.clientId, scope: record.scope };
}

// ---- 内部小工具 ------------------------------------------------------------

interface CodeShape {
  clientId: string;
  redirectUri: string;
  scope: string;
  resource: string | null;
  expiresAt: number;
}

interface TokenShape {
  clientId: string;
  scope: string;
  resource: string | null;
  expiresAt: number;
}

interface ConsentRecordShape {
  clientId: string;
  redirectUri: string;
  state: string | null;
  codeChallenge: string;
  scope: string;
  resource: string | null;
}

type StoreResult<T> = { ok: true; value: T } | { ok: false; error: string };

// ---- 总入口 ---------------------------------------------------------------

/**
 * OAuth 面的唯一入口。`worker/src/index.ts` 只用这一个函数。
 *
 * ── 未启用时为什么一律回 404（而不是 500、也不是 200 空实现）───────────────
 *
 * ChatGPT 连接器的接入流程会去探测若干发现路由。官方要求里明确写了：
 * **未实现的发现路由必须回 404**，回 5xx 会被当成"基础设施故障"从而让连接器
 * **永久失败**（不是"这次失败，下次重试"）。所以没配 `OAUTH_OWNER_SECRET` 时，
 * 这一整片路径必须"看起来不存在"，而不是"看起来坏了"。
 *
 * 这也让 Gate 0.3 的一条既有断言继续保持成立：未启用 OAuth 时 401 响应里
 * **不带** `resource_metadata`（K4），因为指过去也是一个 404。
 */
export async function handleOAuthRequest(
  request: Request,
  env: Env,
  config: AppConfig,
): Promise<Response> {
  const url = new URL(request.url);

  // 预检不依赖配置：即使 OAuth 关闭，也给一个明确的 CORS 答复，
  // 比让浏览器把它当成网络错误更容易排查。
  if (request.method === "OPTIONS") return preflight(request);

  if (!config.oauth.enabled) return notFound(url.pathname);

  switch (url.pathname) {
    // RFC 9728。带 `/mcp` 后缀的那个是给"资源就在 /mcp 上"的客户端用的，
    // 内容是同一份 —— 两个都提供是因为 ChatGPT 两种顺序都出现过。
    case OAUTH_PATHS.protectedResource:
    case OAUTH_PATHS.protectedResourceMcp:
      if (request.method !== "GET") return oauthError(request, "invalid_request", "只支持 GET", 405);
      return protectedResourceMetadata(request, config);

    // RFC 8414 + OIDC 风格别名。ChatGPT 只要求**其一**可用。
    case OAUTH_PATHS.authorizationServer:
    case OAUTH_PATHS.openidConfiguration:
      if (request.method !== "GET") return oauthError(request, "invalid_request", "只支持 GET", 405);
      return authorizationServerMetadata(request, config);

    case OAUTH_PATHS.register:
      return handleRegister(request, env, config);
    case OAUTH_PATHS.authorize:
      return handleAuthorize(request, env, config);
    case OAUTH_PATHS.token:
      return handleToken(request, env, config);
    case OAUTH_PATHS.revoke:
      return handleRevoke(request, env, config);
    default:
      return notFound(url.pathname);
  }
}

function storeStub(env: Env): DurableObjectStub {
  return env.OAUTH_STORE.get(env.OAUTH_STORE.idFromName("oauth-global"));
}

/**
 * 调一次 OAuthStore。
 *
 * 刻意**不把存储异常抛出去**：授权端点的调用方必须能区分"凭据不对"和
 * "后端暂时不可用"，否则会把一次后端抖动变成一条误导性的 invalid_grant 日志。
 * 异常本身不记录（审计 R2：不外泄内部字符串），只记一个固定类别。
 */
async function storeCall<T>(env: Env, path: string, body?: unknown): Promise<StoreResult<T>> {
  try {
    const response = await storeStub(env).fetch(
      new Request(`https://oauth-store.internal${path}`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        ...(body === undefined ? {} : { body: JSON.stringify(body) }),
      }),
    );
    if (!response.ok) return { ok: false, error: `store_http_${response.status}` };
    return { ok: true, value: (await response.json()) as T };
  } catch {
    logEvent("error", "oauth.store_unavailable", { errorCode: "storage_failure" });
    return { ok: false, error: "store_unavailable" };
  }
}

async function resolveClient(env: Env, config: AppConfig, clientId: string): Promise<OAuthClientRecord | null> {
  if (clientId === "") return null;

  const registered = await storeCall<{ found: boolean; client?: OAuthClientRecord }>(
    env,
    OAUTH_STORE_ROUTES.clientGet,
    { clientId },
  );
  if (registered.ok && registered.value.found && registered.value.client !== undefined) {
    return registered.value.client;
  }

  // URL 形态的 client_id（CIMD）。MCP 规范 2026-07-28 起用这个取代 DCR，
  // 所以即便今天的 ChatGPT 用 DCR，也必须能接住未来这种形态。
  if (!clientId.startsWith("https://")) return null;
  return resolveClientIdMetadataDocument(env, config, clientId);
}

/**
 * 拉取并校验 CIMD 文档。
 *
 * 安全约束（少一条都可能变成劫持入口）：
 *   · 只接受 https，且不许带凭据/端口以外的花活；
 *   · 文档里的 `client_id` 必须**逐字节等于** URL 本身 —— 否则这个 URL 就成了
 *     一个可以假冒任意 client_id 的跳板；
 *   · redirect_uris 同样要过主机白名单与精确匹配；
 *   · 超时 3 秒，失败即拒绝（绝不"拉不到就放行"）。
 */
async function resolveClientIdMetadataDocument(
  env: Env,
  config: AppConfig,
  clientId: string,
): Promise<OAuthClientRecord | null> {
  let url: URL;
  try {
    url = new URL(clientId);
  } catch {
    return null;
  }
  if (url.protocol !== "https:") return null;
  if (url.username !== "" || url.password !== "") return null;

  let document: Record<string, unknown>;
  try {
    const response = await fetch(clientId, {
      headers: { Accept: "application/json" },
      signal: AbortSignal.timeout(3000),
      redirect: "error",
    });
    if (!response.ok) return null;
    const parsed = (await response.json()) as unknown;
    if (typeof parsed !== "object" || parsed === null) return null;
    document = parsed as Record<string, unknown>;
  } catch {
    logEvent("warn", "oauth.cimd_fetch_failed", { errorCode: "unclassified" });
    return null;
  }

  if (document["client_id"] !== clientId) return null;
  const uris = Array.isArray(document["redirect_uris"]) ? document["redirect_uris"] : [];
  const redirectUris: string[] = [];
  for (const candidate of uris) {
    if (typeof candidate !== "string") continue;
    if (!checkRedirectUri(candidate, config).ok) return null;
    redirectUris.push(candidate);
  }
  if (redirectUris.length === 0) return null;

  const saved = await storeCall<{ client: OAuthClientRecord }>(env, OAUTH_STORE_ROUTES.clientRegister, {
    clientId,
    redirectUris,
    clientName: typeof document["client_name"] === "string" ? document["client_name"] : null,
    tokenEndpointAuthMethod: "none",
    source: "cimd",
  });
  if (!saved.ok) return null;
  logEvent("info", "oauth.cimd_client_accepted", {});
  return {
    clientId,
    clientName: typeof document["client_name"] === "string" ? document["client_name"] : null,
    redirectUris,
    tokenEndpointAuthMethod: "none",
    clientSecretSha256: null,
    createdAt: Date.now(),
    source: "cimd",
  };
}

async function authenticateClient(
  env: Env,
  clientId: string,
  clientSecret: string | null,
  request: Request,
): Promise<{ ok: true; client: OAuthClientRecord } | { ok: false; message: string }> {
  // client_secret_basic：凭据在 Authorization 头里。
  let id = clientId;
  let secret = clientSecret;
  const header = request.headers.get("Authorization");
  if (header !== null && header.toLowerCase().startsWith("basic ")) {
    const decoded = decodeBase64(header.slice(6).trim());
    if (decoded !== null) {
      const idx = decoded.indexOf(":");
      if (idx >= 0) {
        id = decodeURIComponent(decoded.slice(0, idx));
        secret = decodeURIComponent(decoded.slice(idx + 1));
      }
    }
  }

  if (id === "") return { ok: false, message: "缺少 client_id" };

  const found = await storeCall<{ found: boolean; secretValid: boolean; client?: OAuthClientRecord }>(
    env,
    OAUTH_STORE_ROUTES.clientGet,
    { clientId: id, ...(secret !== null ? { clientSecret: secret } : {}) },
  );
  if (!found.ok) return { ok: false, message: "客户端校验暂时不可用" };
  if (!found.value.found || found.value.client === undefined) return { ok: false, message: "未知的 client_id" };
  if (!found.value.secretValid) return { ok: false, message: "client 凭据不正确" };

  // 令牌端点的 client_id 必须与授权码/刷新令牌绑定的那个一致 ——
  // 这就是"防止跨客户端令牌混用"的那一道闸。
  return { ok: true, client: found.value.client };
}

/**
 * redirect_uri 主机白名单。
 *
 * DCR 的本质是"不预先认识客户端"，所以光靠注册值精确匹配还不足以挡住
 * "先注册一个指向攻击者站点的 redirect_uri，再骗运维去点同意"这条路。
 * 因此再加一层主机白名单，默认只放行 `chatgpt.com`。
 */
function checkRedirectUri(
  raw: string,
  config: AppConfig,
): { ok: true } | { ok: false; message: string } {
  let url: URL;
  try {
    url = new URL(raw);
  } catch {
    return { ok: false, message: `redirect_uri 不是合法 URL` };
  }
  if (url.username !== "" || url.password !== "") {
    return { ok: false, message: "redirect_uri 不允许带用户名密码" };
  }
  if (url.hash !== "") return { ok: false, message: "redirect_uri 不允许带 fragment" };

  const allowedLocal = config.oauth.allowedRedirectHosts.includes("localhost");
  const isLocal = url.hostname === "localhost" || url.hostname === "127.0.0.1" || url.hostname === "[::1]";
  if (url.protocol !== "https:") {
    // 本地回环允许 http，方便本机联调；其余一律 https。
    if (!(url.protocol === "http:" && isLocal && allowedLocal)) {
      return { ok: false, message: "redirect_uri 必须是 https（本机回环除外）" };
    }
  }
  if (isLocal && allowedLocal) return { ok: true };

  const host = url.hostname.toLowerCase();
  const matched = config.oauth.allowedRedirectHosts.some(
    (entry) => host === entry || host.endsWith(`.${entry}`),
  );
  if (!matched) {
    return { ok: false, message: `redirect_uri 的主机 ${host} 不在允许清单里（OAUTH_REDIRECT_ALLOWLIST）` };
  }
  return { ok: true };
}

function redirectError(
  redirectUri: string,
  error: string,
  state: string | null,
  description?: string,
  issuer?: string,
): Response {
  const target = new URL(redirectUri);
  target.searchParams.set("error", error);
  if (description !== undefined) target.searchParams.set("error_description", description);
  if (state !== null) target.searchParams.set("state", state);
  if (issuer !== undefined) target.searchParams.set("iss", issuer);
  return Response.redirect(target.toString(), 302);
}

function oauthError(request: Request, error: string, message: string, status: number): Response {
  logEvent("warn", "oauth.error", { reason: error, errorCode: "bad_request" });
  return jsonResponse({ error, error_description: message }, { status, headers: corsHeaders(request) });
}

/**
 * CORS。
 *
 * 这些端点本来就是给**别的源**上的客户端（ChatGPT）用的，所以放行任意来源；
 * 之所以安全，是因为它们不使用 Cookie、也不根据 Origin 做任何信任判断 ——
 * 凭据一律显式放在请求体/Authorization 头里。
 */
function corsHeaders(request: Request): Record<string, string> {
  const origin = request.headers.get("Origin");
  return {
    "Access-Control-Allow-Origin": origin ?? "*",
    "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
    "Access-Control-Allow-Headers": "Content-Type, Authorization",
    "Access-Control-Max-Age": "600",
    Vary: "Origin",
  };
}

function preflight(request: Request): Response {
  return new Response(null, { status: 204, headers: corsHeaders(request) });
}

async function readForm(request: Request): Promise<Record<string, string> | null> {
  const contentType = request.headers.get("Content-Type") ?? "";
  try {
    if (contentType.includes("application/x-www-form-urlencoded")) {
      const text = await request.text();
      return Object.fromEntries(new URLSearchParams(text));
    }
    if (contentType.includes("application/json")) {
      const parsed = (await request.json()) as unknown;
      if (typeof parsed !== "object" || parsed === null) return null;
      const out: Record<string, string> = {};
      for (const [k, v] of Object.entries(parsed as Record<string, unknown>)) {
        if (typeof v === "string") out[k] = v;
      }
      return out;
    }
  } catch {
    return null;
  }
  return null;
}

async function readTokenParams(request: Request): Promise<Record<string, string> | null> {
  const params = await readForm(request);
  if (params !== null) return params;
  // 也接受 query string（有些老客户端把客户端凭据放在 URL 上；**令牌本体不接受**）。
  const url = new URL(request.url);
  if ([...url.searchParams.keys()].length === 0) return null;
  return Object.fromEntries(url.searchParams);
}

async function readJsonBody<T>(request: Request): Promise<T | null> {
  try {
    const parsed = (await request.json()) as unknown;
    if (typeof parsed !== "object" || parsed === null) return null;
    return parsed as T;
  } catch {
    return null;
  }
}

/** 定时安全比较，两侧都先做 SHA-256 归一化到定长。 */
async function constantTimeEqual(a: string, b: string): Promise<boolean> {
  if (b === "") return false;
  const [da, db] = await Promise.all([sha256Hex(a), sha256Hex(b)]);
  return timingSafeEqual(da, db);
}

function decodeBase64(input: string): string | null {
  try {
    return atob(input);
  } catch {
    return null;
  }
}
