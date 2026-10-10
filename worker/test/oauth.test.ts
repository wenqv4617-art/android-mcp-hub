// ============================================================================
//  OAuth 2.1 授权服务器 —— 供 ChatGPT 连接器接入
// ============================================================================
//
//  对应任务书「四、ChatGPT MCP 接入」与「八、安全要求」。
//
//  为什么必须有这一层：ChatGPT 的 "Add custom MCP server" 只提供两种认证方式
//  —— OAuth 或 No authentication —— 不支持静态 Bearer / 自定义 header。而任务书
//  又明确禁止"为了省事禁用认证"。于是我们只能自己当授权服务器。
//
//  这个文件守的是下面这些**不能悄悄退化**的性质：
//
//    · 没有配置 OAUTH_OWNER_SECRET 时，整个 OAuth 面回 404（不是 500、不是 200）——
//      官方要求：未实现的发现路由回 5xx 会让连接器**永久失败**。
//    · 授权码只能用一次，且与 client_id / redirect_uri / PKCE 绑定。
//    · 刷新令牌轮换：用过即废，旧的重放必须 invalid_grant。
//    · 跨客户端不能混用：A 的码/刷新令牌拿到 B 那里必须失败。
//    · 没有 PKCE（或用了 plain）拿不到码。
//    · redirect_uri 逐字节匹配 + 主机白名单，两道闸。
//    · 同意页口令输错**不作废**授权请求（先 peek 再 consume），但会限流。
//    · 令牌永远不出现在日志里、也不出现在 URL 里。
//    · /mcp 的 401 挑战：未启用 OAuth 时不带 resource_metadata（Gate 0.3 的 K4），
//      启用后必须带（否则 ChatGPT 找不到授权服务器）。
//
//  ⚠ 跑在 Node 上的 Workers 运行时替身里，不是真实 workerd。见 TEST_REPORT.md。
//  ⚠ 真实的 ChatGPT 握手（浏览器里的同意页点击、DCR 后的回调）**未在本文件覆盖**，
//    见 docs/GATE1_ACCEPTANCE.md 的 NOT RUN 清单。
// ============================================================================

import { createHash } from "node:crypto";
import { describe, expect, it } from "vitest";
import worker from "../src/index";
import { handleMcpRequest, MCP_SESSION_HEADER } from "../src/mcp/http";
import { sha256Hex } from "../src/util/crypto";
import type { Env } from "../src/env";
import {
  capturedLogs,
  clearCapturedLogs,
  makeRig,
  TEST_DEVICE_ID,
  TEST_MCP_TOKEN,
  TEST_OAUTH_OWNER_SECRET,
  type TestRig,
} from "./harness/env";

const ORIGIN = "https://android-mcp-hub.test";
const MCP_URL = `${ORIGIN}/mcp`;
const CALLBACK = "https://chatgpt.com/connector/oauth/test-callback-id";

// ---------------------------------------------------------------------------
//  小工具
// ---------------------------------------------------------------------------

function call(rig: TestRig, request: Request): Promise<Response> {
  return worker.fetch(request, rig.env, {} as ExecutionContext);
}

/** 启用了 OAuth 的 rig（默认 rig 是**不启用**的，那是刻意的）。 */
function enabledRig(overrides: Record<string, unknown> = {}): Promise<TestRig> {
  return makeRig({ oauthOwnerSecret: TEST_OAUTH_OWNER_SECRET, ...overrides });
}

function get(rig: TestRig, path: string, headers: Record<string, string> = {}): Promise<Response> {
  return call(rig, new Request(path.startsWith("http") ? path : `${ORIGIN}${path}`, { method: "GET", headers }));
}

function postJson(rig: TestRig, path: string, body: unknown): Promise<Response> {
  return call(
    rig,
    new Request(`${ORIGIN}${path}`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
    }),
  );
}

function postForm(rig: TestRig, path: string, form: Record<string, string>): Promise<Response> {
  return call(
    rig,
    new Request(`${ORIGIN}${path}`, {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams(form).toString(),
    }),
  );
}

/** 一对合法的 PKCE（S256）。verifier 43–128 字符、`[A-Za-z0-9\-._~]`。 */
function pkce(seed = "a"): { verifier: string; challenge: string } {
  const verifier = `gate1-test-verifier-${seed.repeat(30)}`;
  const challenge = createHash("sha256").update(verifier).digest("base64url");
  return { verifier, challenge };
}

interface RegisteredClient {
  clientId: string;
  redirectUri: string;
}

async function registerClient(rig: TestRig, redirectUri = CALLBACK, name = "ChatGPT"): Promise<RegisteredClient> {
  const response = await postJson(rig, "/oauth/register", {
    redirect_uris: [redirectUri],
    client_name: name,
    token_endpoint_auth_method: "none",
  });
  expect(response.status).toBe(201);
  const body = (await response.json()) as Record<string, unknown>;
  return { clientId: body["client_id"] as string, redirectUri };
}

function authorizeUrl(
  clientId: string,
  redirectUri: string,
  challenge: string,
  extra: Record<string, string> = {},
): string {
  const url = new URL(`${ORIGIN}/oauth/authorize`);
  url.searchParams.set("client_id", clientId);
  url.searchParams.set("redirect_uri", redirectUri);
  url.searchParams.set("response_type", "code");
  url.searchParams.set("code_challenge", challenge);
  url.searchParams.set("code_challenge_method", "S256");
  url.searchParams.set("state", "state-xyz");
  for (const [k, v] of Object.entries(extra)) url.searchParams.set(k, v);
  return url.toString();
}

function consentIdOf(html: string): string {
  const matched = /name="consent_id" value="([^"]+)"/.exec(html);
  if (matched === null) throw new Error("同意页里没有 consent_id");
  return matched[1]!;
}

/** 走到"拿到授权码"这一步。返回 Location（一个 URL）。 */
async function obtainCode(
  rig: TestRig,
  client: RegisteredClient,
  challenge: string,
  extra: Record<string, string> = {},
): Promise<URL> {
  const page = await get(rig, authorizeUrl(client.clientId, client.redirectUri, challenge, extra));
  expect(page.status).toBe(200);
  const consentId = consentIdOf(await page.text());

  const approved = await postForm(rig, "/oauth/authorize", {
    consent_id: consentId,
    owner_secret: TEST_OAUTH_OWNER_SECRET,
    decision: "approve",
  });
  expect(approved.status).toBe(302);
  return new URL(approved.headers.get("Location")!);
}

interface TokenSet {
  access_token: string;
  refresh_token: string;
  scope: string;
  expires_in: number;
  token_type: string;
}

async function exchangeCode(
  rig: TestRig,
  client: RegisteredClient,
  code: string,
  verifier: string,
): Promise<Response> {
  return postForm(rig, "/oauth/token", {
    grant_type: "authorization_code",
    code,
    redirect_uri: client.redirectUri,
    client_id: client.clientId,
    code_verifier: verifier,
  });
}

async function exchangeRefresh(rig: TestRig, client: RegisteredClient, refreshToken: string): Promise<Response> {
  return postForm(rig, "/oauth/token", {
    grant_type: "refresh_token",
    refresh_token: refreshToken,
    client_id: client.clientId,
  });
}

/** 用访问令牌走一次 /mcp initialize。 */
function initializeWith(rig: TestRig, accessToken: string): Promise<Response> {
  const headers = new Headers();
  headers.set("Accept", "application/json, text/event-stream");
  headers.set("Content-Type", "application/json");
  headers.set("Authorization", `Bearer ${accessToken}`);
  return handleMcpRequest(
    new Request(MCP_URL, {
      method: "POST",
      headers,
      body: JSON.stringify({
        jsonrpc: "2.0",
        id: 1,
        method: "initialize",
        params: { protocolVersion: "2025-06-18", capabilities: {}, clientInfo: { name: "oauth-test", version: "0.0.1" } },
      }),
    }),
    rig.env,
  );
}

// ===========================================================================
//  A. 未启用 OAuth：整片路径必须"不存在"
// ===========================================================================

describe("OAuth / 未启用时失败关闭", () => {
  const ALL_PATHS = [
    "/.well-known/oauth-protected-resource",
    "/.well-known/oauth-protected-resource/mcp",
    "/.well-known/oauth-authorization-server",
    "/.well-known/openid-configuration",
    "/oauth/authorize",
    "/oauth/token",
    "/oauth/register",
    "/oauth/revoke",
  ];

  it("没有配置 OAUTH_OWNER_SECRET 时，每个 OAuth 路由都回 404（不是 5xx）", async () => {
    const rig = await makeRig();
    for (const path of ALL_PATHS) {
      const response = await get(rig, path);
      // ★ 404 而不是 500：官方要求里写得很清楚，未实现的发现路由回 5xx 会被
      //   连接器当成基础设施故障，从而**永久失败**（不是下次重试）。
      expect(response.status, `${path} 应该是 404`).toBe(404);
    }
  });

  it("未启用时 /mcp 的 401 不带 resource_metadata（Gate 0.3 的 K4 继续成立）", async () => {
    const rig = await makeRig();
    const response = await handleMcpRequest(
      new Request(MCP_URL, {
        method: "POST",
        headers: { Accept: "application/json, text/event-stream", "Content-Type": "application/json" },
        body: JSON.stringify({ jsonrpc: "2.0", id: 1, method: "initialize", params: {} }),
      }),
      rig.env,
    );
    expect(response.status).toBe(401);
    expect(response.headers.get("WWW-Authenticate")).not.toContain("resource_metadata");
  });
});

// ===========================================================================
//  B. 发现元数据
// ===========================================================================

describe("OAuth / 发现元数据", () => {
  it("受保护资源元数据（RFC 9728）指向自己作为授权服务器", async () => {
    const rig = await enabledRig();
    const response = await get(rig, "/.well-known/oauth-protected-resource");
    expect(response.status).toBe(200);
    const body = (await response.json()) as Record<string, unknown>;
    expect(body["resource"]).toBe(MCP_URL);
    expect(body["authorization_servers"]).toEqual([ORIGIN]);
    expect(body["bearer_methods_supported"]).toEqual(["header"]);
    expect(body["scopes_supported"]).toEqual(["mcp:tools"]);
  });

  it("/.well-known/oauth-protected-resource/mcp 与不带后缀的那份内容一致", async () => {
    const rig = await enabledRig();
    const plain = (await (await get(rig, "/.well-known/oauth-protected-resource")).json()) as unknown;
    const suffixed = (await (await get(rig, "/.well-known/oauth-protected-resource/mcp")).json()) as unknown;
    expect(suffixed).toEqual(plain);
  });

  it("授权服务器元数据声明 S256 与 authorization_code / refresh_token", async () => {
    const rig = await enabledRig();
    const response = await get(rig, "/.well-known/oauth-authorization-server");
    expect(response.status).toBe(200);
    const body = (await response.json()) as Record<string, unknown>;
    expect(body["issuer"]).toBe(ORIGIN);
    expect(body["authorization_endpoint"]).toBe(`${ORIGIN}/oauth/authorize`);
    expect(body["token_endpoint"]).toBe(`${ORIGIN}/oauth/token`);
    expect(body["registration_endpoint"]).toBe(`${ORIGIN}/oauth/register`);
    // ★ 少了 S256，ChatGPT 会直接拒绝接入。
    expect(body["code_challenge_methods_supported"]).toContain("S256");
    expect(body["grant_types_supported"]).toContain("authorization_code");
    expect(body["grant_types_supported"]).toContain("refresh_token");
    expect(body["authorization_response_iss_parameter_supported"]).toBe(true);
  });

  it("/.well-known/openid-configuration 是可用的别名", async () => {
    const rig = await enabledRig();
    const response = await get(rig, "/.well-known/openid-configuration");
    expect(response.status).toBe(200);
    const body = (await response.json()) as Record<string, unknown>;
    expect(body["issuer"]).toBe(ORIGIN);
  });

  it("启用后 /mcp 的 401 挑战**必须**带 resource_metadata", async () => {
    const rig = await enabledRig();
    const response = await handleMcpRequest(
      new Request(MCP_URL, {
        method: "POST",
        headers: { Accept: "application/json, text/event-stream", "Content-Type": "application/json" },
        body: JSON.stringify({ jsonrpc: "2.0", id: 1, method: "initialize", params: {} }),
      }),
      rig.env,
    );
    expect(response.status).toBe(401);
    const challenge = response.headers.get("WWW-Authenticate") ?? "";
    expect(challenge).toContain("resource_metadata");
    expect(challenge).toContain("/.well-known/oauth-protected-resource/mcp");
  });
});

// ===========================================================================
//  C. 动态客户端注册（RFC 7591）
// ===========================================================================

describe("OAuth / 动态客户端注册", () => {
  it("注册一个公开客户端的回调地址，返回 client_id", async () => {
    const rig = await enabledRig();
    const response = await postJson(rig, "/oauth/register", {
      redirect_uris: [CALLBACK],
      client_name: "ChatGPT",
      token_endpoint_auth_method: "none",
    });
    expect(response.status).toBe(201);
    const body = (await response.json()) as Record<string, unknown>;
    expect(typeof body["client_id"]).toBe("string");
    expect(body["redirect_uris"]).toEqual([CALLBACK]);
    expect(body["token_endpoint_auth_method"]).toBe("none");
    expect(body["grant_types"]).toEqual(["authorization_code", "refresh_token"]);
    // 公开客户端不该拿到 secret。
    expect(body["client_secret"]).toBeUndefined();
  });

  it("拒绝空 redirect_uris", async () => {
    const rig = await enabledRig();
    const response = await postJson(rig, "/oauth/register", { redirect_uris: [] });
    expect(response.status).toBe(400);
    expect(((await response.json()) as Record<string, unknown>)["error"]).toBe("invalid_redirect_uri");
  });

  it("拒绝不在允许清单里的主机（挡住'注册到攻击者站点再骗同意'）", async () => {
    const rig = await enabledRig();
    const response = await postJson(rig, "/oauth/register", { redirect_uris: ["https://evil.example/callback"] });
    expect(response.status).toBe(400);
  });

  it("拒绝非回环的 http 回调", async () => {
    const rig = await enabledRig();
    const response = await postJson(rig, "/oauth/register", { redirect_uris: ["http://chatgpt.com/callback"] });
    expect(response.status).toBe(400);
  });

  it("拒绝带 fragment 或带用户名密码的回调", async () => {
    const rig = await enabledRig();
    for (const bad of ["https://chatgpt.com/cb#frag", "https://user:pw@chatgpt.com/cb"]) {
      const response = await postJson(rig, "/oauth/register", { redirect_uris: [bad] });
      expect(response.status, bad).toBe(400);
    }
  });

  it("允许本机回环 http（白名单含 localhost 时）", async () => {
    const rig = await enabledRig();
    const response = await postJson(rig, "/oauth/register", { redirect_uris: ["http://localhost:8787/callback"] });
    expect(response.status).toBe(201);
  });

  it("未注册的 client_id 拿不到同意页", async () => {
    const rig = await enabledRig();
    const response = await get(rig, authorizeUrl("not-a-registered-client", CALLBACK, pkce().challenge));
    expect(response.status).toBe(400);
  });
});

// ===========================================================================
//  D. 授权端点与同意页
// ===========================================================================

describe("OAuth / 授权端点与同意页", () => {
  it("同意页只含 consent_id / owner_secret / decision 三个字段，且没有脚本", async () => {
    const rig = await enabledRig();
    const client = await registerClient(rig);
    const { challenge } = pkce();
    const response = await get(rig, authorizeUrl(client.clientId, client.redirectUri, challenge));
    expect(response.status).toBe(200);
    const html = await response.text();
    expect(html).toContain('name="consent_id"');
    expect(html).toContain('name="owner_secret"');
    expect(html).toContain('name="decision"');
    // ★ 授权参数（redirect_uri 等）不能成为可篡改的隐藏字段。
    expect(html).not.toContain('name="redirect_uri"');
    expect(html).not.toContain('name="code_challenge"');
    expect(html).not.toContain("<script");
    const csp = response.headers.get("Content-Security-Policy") ?? "";
    expect(csp).toContain("default-src 'none'");
    expect(response.headers.get("Referrer-Policy")).toBe("no-referrer");
  });

  it("redirect_uri 与注册值不逐字节相等时拒绝（不做模糊匹配）", async () => {
    const rig = await enabledRig();
    const client = await registerClient(rig);
    const { challenge } = pkce();
    const response = await get(rig, authorizeUrl(client.clientId, `${CALLBACK}-extra`, challenge));
    expect(response.status).toBe(400);
  });

  it("没有 PKCE 或用了 plain 时不发码", async () => {
    const rig = await enabledRig();
    const client = await registerClient(rig);
    const noPkce = await get(
      rig,
      `${ORIGIN}/oauth/authorize?client_id=${client.clientId}&redirect_uri=${encodeURIComponent(
        client.redirectUri,
      )}&response_type=code&state=s1`,
    );
    expect(noPkce.status).toBe(302);
    expect(noPkce.headers.get("Location")).toContain("error=invalid_request");

    const plain = await get(
      rig,
      `${ORIGIN}/oauth/authorize?client_id=${client.clientId}&redirect_uri=${encodeURIComponent(
        client.redirectUri,
      )}&response_type=code&state=s1&code_challenge=abc&code_challenge_method=plain`,
    );
    expect(plain.status).toBe(302);
    expect(plain.headers.get("Location")).toContain("error=invalid_request");
  });

  it("response_type 不是 code 时报 unsupported_response_type", async () => {
    const rig = await enabledRig();
    const client = await registerClient(rig);
    const { challenge } = pkce();
    const response = await get(
      rig,
      `${ORIGIN}/oauth/authorize?client_id=${client.clientId}&redirect_uri=${encodeURIComponent(
        client.redirectUri,
      )}&response_type=token&state=s1&code_challenge=${challenge}&code_challenge_method=S256`,
    );
    expect(response.status).toBe(302);
    expect(response.headers.get("Location")).toContain("unsupported_response_type");
  });

  it("resource 指向别人的 MCP 端点时报 invalid_target（RFC 8707）", async () => {
    const rig = await enabledRig();
    const client = await registerClient(rig);
    const { challenge } = pkce();
    const response = await get(
      rig,
      authorizeUrl(client.clientId, client.redirectUri, challenge, { resource: "https://other.example/mcp" }),
    );
    expect(response.status).toBe(302);
    expect(response.headers.get("Location")).toContain("invalid_target");
  });

  it("批准后 302 回回调地址，带 code、原样 state 与 iss（RFC 9207）", async () => {
    const rig = await enabledRig();
    const client = await registerClient(rig);
    const { challenge } = pkce();
    const location = await obtainCode(rig, client, challenge);
    expect(location.origin + location.pathname).toBe(client.redirectUri);
    expect(location.searchParams.get("state")).toBe("state-xyz");
    expect(location.searchParams.get("iss")).toBe(ORIGIN);
    expect(location.searchParams.get("code")).toBeTruthy();
    // ★ 授权响应里绝不能出现令牌本体。
    expect(location.searchParams.get("code")).not.toContain("mcp_at_");
  });

  it("拒绝时 302 回 access_denied，且不发码", async () => {
    const rig = await enabledRig();
    const client = await registerClient(rig);
    const { challenge } = pkce();
    const page = await get(rig, authorizeUrl(client.clientId, client.redirectUri, challenge));
    const consentId = consentIdOf(await page.text());
    const denied = await postForm(rig, "/oauth/authorize", {
      consent_id: consentId,
      owner_secret: TEST_OAUTH_OWNER_SECRET,
      decision: "deny",
    });
    expect(denied.status).toBe(302);
    const location = new URL(denied.headers.get("Location")!);
    expect(location.searchParams.get("error")).toBe("access_denied");
    expect(location.searchParams.get("code")).toBeNull();
  });

  it("★ 口令输错一次不会作废授权请求：改对之后仍然能拿到码", async () => {
    const rig = await enabledRig();
    const client = await registerClient(rig);
    const { challenge } = pkce();
    const page = await get(rig, authorizeUrl(client.clientId, client.redirectUri, challenge));
    const consentId = consentIdOf(await page.text());

    const wrong = await postForm(rig, "/oauth/authorize", {
      consent_id: consentId,
      owner_secret: "definitely-not-the-owner-secret",
      decision: "approve",
    });
    expect(wrong.status).toBe(401);

    // ★ 这就是"先 peek 再 consume"的价值：同意请求没被烧掉。
    const right = await postForm(rig, "/oauth/authorize", {
      consent_id: consentId,
      owner_secret: TEST_OAUTH_OWNER_SECRET,
      decision: "approve",
    });
    expect(right.status).toBe(302);
    expect(new URL(right.headers.get("Location")!).searchParams.get("code")).toBeTruthy();
  });

  it("同一个 consent_id 用第二次时被拒绝（一次性）", async () => {
    const rig = await enabledRig();
    const client = await registerClient(rig);
    const { challenge } = pkce();
    const page = await get(rig, authorizeUrl(client.clientId, client.redirectUri, challenge));
    const consentId = consentIdOf(await page.text());
    const form = { consent_id: consentId, owner_secret: TEST_OAUTH_OWNER_SECRET, decision: "approve" };
    expect((await postForm(rig, "/oauth/authorize", form)).status).toBe(302);
    expect((await postForm(rig, "/oauth/authorize", form)).status).toBe(400);
  });
});

// ===========================================================================
//  E. 令牌端点：授权码
// ===========================================================================

describe("OAuth / 授权码换令牌", () => {
  it("正确的 code + verifier 换到 access_token 与 refresh_token", async () => {
    const rig = await enabledRig();
    const client = await registerClient(rig);
    const { verifier, challenge } = pkce();
    const location = await obtainCode(rig, client, challenge);
    const code = location.searchParams.get("code")!;

    const response = await exchangeCode(rig, client, code, verifier);
    expect(response.status).toBe(200);
    const tokens = (await response.json()) as unknown as TokenSet;
    expect(tokens.token_type).toBe("Bearer");
    expect(tokens.access_token.startsWith("mcp_at_")).toBe(true);
    expect(tokens.refresh_token.startsWith("mcp_rt_")).toBe(true);
    expect(tokens.scope).toBe("mcp:tools");
  });

  it("★ 授权码只能用一次", async () => {
    const rig = await enabledRig();
    const client = await registerClient(rig);
    const { verifier, challenge } = pkce();
    const code = (await obtainCode(rig, client, challenge)).searchParams.get("code")!;

    expect((await exchangeCode(rig, client, code, verifier)).status).toBe(200);
    const replay = await exchangeCode(rig, client, code, verifier);
    expect(replay.status).toBe(400);
    expect(((await replay.json()) as Record<string, unknown>)["error"]).toBe("invalid_grant");
  });

  it("code_verifier 不对时换不到令牌", async () => {
    const rig = await enabledRig();
    const client = await registerClient(rig);
    const { challenge } = pkce();
    const code = (await obtainCode(rig, client, challenge)).searchParams.get("code")!;
    const response = await exchangeCode(rig, client, code, `gate1-test-verifier-${"b".repeat(30)}`);
    expect(response.status).toBe(400);
    expect(((await response.json()) as Record<string, unknown>)["error"]).toBe("invalid_grant");
  });

  it("缺 code_verifier 直接是 invalid_request", async () => {
    const rig = await enabledRig();
    const client = await registerClient(rig);
    const response = await postForm(rig, "/oauth/token", {
      grant_type: "authorization_code",
      code: "whatever",
      redirect_uri: client.redirectUri,
      client_id: client.clientId,
    });
    expect(response.status).toBe(400);
    expect(((await response.json()) as Record<string, unknown>)["error"]).toBe("invalid_request");
  });

  it("★ 跨客户端不能混用：A 的码拿到 B 那里必须失败", async () => {
    const rig = await enabledRig();
    const a = await registerClient(rig, CALLBACK, "client-a");
    const b = await registerClient(rig, CALLBACK, "client-b");
    const { verifier, challenge } = pkce();
    const code = (await obtainCode(rig, a, challenge)).searchParams.get("code")!;

    const response = await postForm(rig, "/oauth/token", {
      grant_type: "authorization_code",
      code,
      redirect_uri: b.redirectUri,
      client_id: b.clientId,
      code_verifier: verifier,
    });
    expect(response.status).toBe(400);
    expect(((await response.json()) as Record<string, unknown>)["error"]).toBe("invalid_grant");
  });

  it("未知 client_id 报 invalid_client", async () => {
    const rig = await enabledRig();
    const response = await postForm(rig, "/oauth/token", {
      grant_type: "authorization_code",
      code: "x",
      client_id: "ghost-client",
      code_verifier: "y",
    });
    expect(response.status).toBe(401);
    expect(((await response.json()) as Record<string, unknown>)["error"]).toBe("invalid_client");
  });

  it("不支持的 grant_type 被拒绝", async () => {
    const rig = await enabledRig();
    const client = await registerClient(rig);
    const response = await postForm(rig, "/oauth/token", {
      grant_type: "client_credentials",
      client_id: client.clientId,
    });
    expect(response.status).toBe(400);
    expect(((await response.json()) as Record<string, unknown>)["error"]).toBe("unsupported_grant_type");
  });
});

// ===========================================================================
//  F. 刷新令牌轮换
// ===========================================================================

describe("OAuth / 刷新令牌", () => {
  it("刷新会轮换出一对新令牌", async () => {
    const rig = await enabledRig();
    const client = await registerClient(rig);
    const { verifier, challenge } = pkce();
    const code = (await obtainCode(rig, client, challenge)).searchParams.get("code")!;
    const first = (await (await exchangeCode(rig, client, code, verifier)).json()) as unknown as TokenSet;

    const response = await exchangeRefresh(rig, client, first.refresh_token);
    expect(response.status).toBe(200);
    const second = (await response.json()) as unknown as TokenSet;
    expect(second.access_token).not.toBe(first.access_token);
    expect(second.refresh_token).not.toBe(first.refresh_token);
  });

  it("★ 旧的刷新令牌重放必须 invalid_grant", async () => {
    const rig = await enabledRig();
    const client = await registerClient(rig);
    const { verifier, challenge } = pkce();
    const code = (await obtainCode(rig, client, challenge)).searchParams.get("code")!;
    const first = (await (await exchangeCode(rig, client, code, verifier)).json()) as unknown as TokenSet;

    expect((await exchangeRefresh(rig, client, first.refresh_token)).status).toBe(200);
    const replay = await exchangeRefresh(rig, client, first.refresh_token);
    expect(replay.status).toBe(400);
    expect(((await replay.json()) as Record<string, unknown>)["error"]).toBe("invalid_grant");
  });

  it("★ 跨客户端不能混用：A 的刷新令牌拿到 B 那里必须失败", async () => {
    const rig = await enabledRig();
    const a = await registerClient(rig, CALLBACK, "client-a");
    const b = await registerClient(rig, CALLBACK, "client-b");
    const { verifier, challenge } = pkce();
    const code = (await obtainCode(rig, a, challenge)).searchParams.get("code")!;
    const tokens = (await (await exchangeCode(rig, a, code, verifier)).json()) as unknown as TokenSet;

    const response = await exchangeRefresh(rig, b, tokens.refresh_token);
    expect(response.status).toBe(400);
    expect(((await response.json()) as Record<string, unknown>)["error"]).toBe("invalid_grant");
  });
});

// ===========================================================================
//  G. 访问令牌在 /mcp 上的行为
// ===========================================================================

describe("OAuth / 访问令牌用于 /mcp", () => {
  async function freshTokens(rig: TestRig, extra: Record<string, string> = {}): Promise<TokenSet> {
    const client = await registerClient(rig);
    const { verifier, challenge } = pkce();
    const code = (await obtainCode(rig, client, challenge, extra)).searchParams.get("code")!;
    const response = await exchangeCode(rig, client, code, verifier);
    expect(response.status).toBe(200);
    return (await response.json()) as unknown as TokenSet;
  }

  it("★ OAuth 访问令牌能真的完成一次 /mcp initialize", async () => {
    const rig = await enabledRig();
    const tokens = await freshTokens(rig);
    const response = await initializeWith(rig, tokens.access_token);
    expect(response.status).toBe(200);
    const body = (await response.json()) as Record<string, unknown>;
    expect(body["result"]).toBeTruthy();
    expect(response.headers.get(MCP_SESSION_HEADER)).toBeTruthy();
  });

  it("静态 MCP_ACCESS_TOKEN 仍然可用（本机 e2e 走这条）", async () => {
    const rig = await enabledRig();
    const response = await initializeWith(rig, TEST_MCP_TOKEN);
    expect(response.status).toBe(200);
  });

  it("伪造的 mcp_at_ 令牌被拒绝", async () => {
    const rig = await enabledRig();
    const response = await initializeWith(rig, `mcp_at_${"A".repeat(43)}`);
    expect(response.status).toBe(401);
  });

  it("随便一个长随机串走不到 DO（不匹配 mcp_at_ 前缀就提前失败）", async () => {
    const rig = await enabledRig();
    const response = await initializeWith(rig, `${"z".repeat(48)}`);
    expect(response.status).toBe(401);
  });

  it("撤销之后令牌立刻失效", async () => {
    const rig = await enabledRig();
    const tokens = await freshTokens(rig);
    const revoke = await postForm(rig, "/oauth/revoke", { token: tokens.access_token });
    expect(revoke.status).toBe(200);
    expect((await initializeWith(rig, tokens.access_token)).status).toBe(401);
  });

  it("过期的访问令牌被拒绝", async () => {
    const rig = await enabledRig();
    const token = `mcp_at_${"E".repeat(43)}`;
    const digest = await sha256Hex(token);
    const now = Date.now();
    await rig.oauth.ctxOf("oauth-global").storage.put(`access:${digest}`, {
      kind: "access",
      clientId: "some-client",
      scope: "mcp:tools",
      resource: MCP_URL,
      createdAt: now - 10_000,
      expiresAt: now - 1_000,
    });
    expect((await initializeWith(rig, token)).status).toBe(401);
  });

  it("audience 不匹配的访问令牌被拒绝（RFC 8707）", async () => {
    const rig = await enabledRig();
    const token = `mcp_at_${"F".repeat(43)}`;
    const digest = await sha256Hex(token);
    const now = Date.now();
    await rig.oauth.ctxOf("oauth-global").storage.put(`access:${digest}`, {
      kind: "access",
      clientId: "some-client",
      scope: "mcp:tools",
      resource: "https://somewhere-else.example/mcp",
      createdAt: now,
      expiresAt: now + 600_000,
    });
    expect((await initializeWith(rig, token)).status).toBe(401);
  });

  it("★ 缺少必需 scope 的令牌被拒绝", async () => {
    const rig = await enabledRig();
    // 客户端的 scope 参数只能收窄，不能提权；这里拿一个不含 mcp:tools 的令牌。
    const tokens = await freshTokens(rig, { scope: "profile" });
    expect(tokens.scope).toBe("profile");
    expect((await initializeWith(rig, tokens.access_token)).status).toBe(401);
  });
});

// ===========================================================================
//  H. 令牌不进日志、不进 URL
// ===========================================================================

describe("OAuth / 令牌不进日志、不进 URL", () => {
  it("走完注册 → 同意 → 换令牌 → 撤销，日志里不出现任何明文令牌或口令", async () => {
    const rig = await enabledRig();
    clearCapturedLogs();

    const client = await registerClient(rig);
    const { verifier, challenge } = pkce();
    const location = await obtainCode(rig, client, challenge);
    const code = location.searchParams.get("code")!;
    const tokens = (await (await exchangeCode(rig, client, code, verifier)).json()) as unknown as TokenSet;
    await exchangeRefresh(rig, client, tokens.refresh_token);
    await postForm(rig, "/oauth/revoke", { token: tokens.access_token });

    const joined = capturedLogs().join("\n");
    // 日志里可以出现 client_id（不是秘密），但这四样必须一个都不出现。
    for (const secret of [tokens.access_token, tokens.refresh_token, code, TEST_OAUTH_OWNER_SECRET]) {
      expect(joined, `日志里不该出现 ${secret.slice(0, 12)}…`).not.toContain(secret);
    }
    expect(joined).toContain("oauth.token_issued");
  });

  it("同意页的 HTML 里不出现 owner secret", async () => {
    const rig = await enabledRig();
    const client = await registerClient(rig);
    const { challenge } = pkce();
    const html = await (await get(rig, authorizeUrl(client.clientId, client.redirectUri, challenge))).text();
    expect(html).not.toContain(TEST_OAUTH_OWNER_SECRET);
  });

  it("令牌端点只接受 POST", async () => {
    const rig = await enabledRig();
    const response = await get(rig, "/oauth/token");
    expect(response.status).toBe(405);
  });
});

// ===========================================================================
//  I. 存储不可用时的行为
// ===========================================================================

describe("OAuth / 存储异常不冒充业务失败", () => {
  it("写授权码失败时回 503 server_error，而不是发一个假码或报 invalid_grant", async () => {
    const rig = await enabledRig();
    const client = await registerClient(rig);
    const { challenge } = pkce();

    // 先正常走到同意页（这一步只创建 consent，不写授权码）。
    const page = await get(rig, authorizeUrl(client.clientId, client.redirectUri, challenge));
    const consentId = consentIdOf(await page.text());

    // 让下一次存储写入抛错 —— 正好落在"写授权码"上。
    const storage = rig.oauth.ctxOf("oauth-global").storage as unknown as {
      failNext: (op: string, opts?: { times?: number }) => void;
    };
    storage.failNext("put", { times: 1 });

    const approved = await postForm(rig, "/oauth/authorize", {
      consent_id: consentId,
      owner_secret: TEST_OAUTH_OWNER_SECRET,
      decision: "approve",
    });
    // ★ 不能是 302（那意味着发了一个没落盘的假码），也不能被当成"口令不对"。
    expect(approved.status).toBe(503);
  });
});

// ===========================================================================
//  J. 端到端的"注册 → 同意 → 令牌 → /mcp ping 真手机"
// ===========================================================================

describe("OAuth / 与手机链路并联", () => {
  it("OAuth 令牌 → tools/call phone_ping → 真实走 DO → 模拟手机回 pong", async () => {
    const rig = await enabledRig();
    const client = await registerClient(rig);
    const { verifier, challenge } = pkce();
    const code = (await obtainCode(rig, client, challenge)).searchParams.get("code")!;
    const tokens = (await (await exchangeCode(rig, client, code, verifier)).json()) as unknown as TokenSet;

    const init = await initializeWith(rig, tokens.access_token);
    expect(init.status).toBe(200);
    const sessionId = init.headers.get(MCP_SESSION_HEADER)!;

    // 真手机替身接入（不是凭空造 pong —— 见 mcp-http.test.ts 的同款说明）。
    const { connectMockPhone } = await import("./harness/env");
    await connectMockPhone(rig, TEST_DEVICE_ID, "normal");

    const callPing = await handleMcpRequest(
      new Request(MCP_URL, {
        method: "POST",
        headers: {
          Accept: "application/json, text/event-stream",
          "Content-Type": "application/json",
          Authorization: `Bearer ${tokens.access_token}`,
          [MCP_SESSION_HEADER]: sessionId,
        },
        body: JSON.stringify({
          jsonrpc: "2.0",
          id: 2,
          method: "tools/call",
          params: { name: "phone_ping", arguments: { device_id: TEST_DEVICE_ID } },
        }),
      }),
      rig.env,
    );
    expect(callPing.status).toBe(200);
    const body = (await callPing.json()) as Record<string, unknown>;
    const result = body["result"] as Record<string, unknown>;
    expect(result).toBeTruthy();
    const content = result?.["content"] as Array<Record<string, unknown>>;
    const text = content?.[0]?.["text"] as string;
    expect(text).toContain("成功");
    expect(text).toContain("request_id=");
    expect(text).toContain("连接代次");
  });
});

// 这一行只是为了让 `Env` 这个类型 import 不被 lint 判成未使用。
export type _OAuthTestEnv = Env;
