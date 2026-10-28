import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { unstable_dev } from "wrangler";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StreamableHTTPClientTransport } from "@modelcontextprotocol/sdk/client/streamableHttp.js";
import { createUpstream } from "../worker/test/gateway-fixture";

// No cloud account or real credential required. Uses actual workerd/DO SQLite.
const upstream = await createUpstream("workerd-upstream");
const token = "hub_client_workerd-fixture";
const adminToken = "workerd-fixture-admin";
const clients: Client[] = [];
let app: Awaited<ReturnType<typeof unstable_dev>> | undefined;
let passed = 0;
function check(value: unknown, message: string) { assert.ok(value, message); passed++; console.log(`PASS ${message}`); }
try {
  app = await unstable_dev("worker/src/index.ts", {
    config: "worker/wrangler.jsonc", local: true, port: 0, inspectorPort: 0,
    logLevel: "error", persist: false,
    vars: { GATEWAY_ENABLED: "true", ENVIRONMENT: "gateway-local-test", GATEWAY_ADMIN_TOKEN: adminToken,
      GATEWAY_ALLOWED_URLS: `${upstream.url}/mcp,${upstream.url}/sse,${upstream.url}/messages`,
      GATEWAY_UPSTREAM_SECRETS: JSON.stringify({ "alice/test": "Bearer workerd-upstream-fixture" }),
      MCP_ACCESS_TOKEN: "workerd-fixture-owner", OAUTH_OWNER_SECRET: "workerd-fixture-oauth-owner" },
    experimental: { disableExperimentalWarning: true, disableDevRegistry: true, watch: false },
  });
  const origin = `http://${app.address}:${app.port}`;
  async function admin(body: unknown) {
    const response = await app!.fetch("/gateway/admin", { method: "POST", headers: { Authorization: `Bearer ${adminToken}`, "Content-Type": "application/json" }, body: JSON.stringify(body) });
    assert.equal(response.status, 200, await response.clone().text());
    return await response.json() as any;
  }
  async function client(bearer: string) {
    const c = new Client({ name: "workerd-test-client", version: "1" }); clients.push(c);
    await c.connect(new StreamableHTTPClientTransport(new URL(`${origin}/mcp`), { requestInit: { headers: { Authorization: `Bearer ${bearer}` } } }));
    return c;
  }
  await admin({ action: "put-client", id: "client:workerd", userId: "alice", charId: "one", tokenSha256: createHash("sha256").update(token).digest("hex"), enabled: true });
  const c = await client(token);
  check((await c.listTools()).tools.length === 0, "actual DO starts deny-by-default");
  await admin({ action: "put-service", id: "fixture", name: "Fixture", ownerId: "alice", url: `${upstream.url}/mcp`, transport: "streamable-http", secretRef: "test", enabled: true, timeoutMs: 2000 });
  const discovered = await admin({ action: "test", id: "fixture" });
  check(discovered.tools.length === 6, "workerd discovers upstream over real HTTP");
  await admin({ action: "grant", clientId: "client:workerd", serviceId: "fixture", tool: "sum", mode: "read", fingerprint: discovered.fingerprints.find((t: any) => t.name === "sum").fingerprint });
  let tools = (await c.listTools()).tools;
  check(tools.length === 1, "workerd exposes only granted tool");
  const result = await c.callTool({ name: tools[0]!.name, arguments: { a: 20, b: 22 } });
  check(JSON.stringify(result.content) === JSON.stringify([{ type: "text", text: "42" }]), "official SDK -> Worker -> actual DO -> SDK upstream -> 42");
  await admin({ action: "set-enabled", id: "fixture", enabled: false });
  check((await c.listTools()).tools.length === 0, "service disable removes tool");
  check((await c.callTool({ name: tools[0]!.name, arguments: { a: 1, b: 1 } })).isError === true, "disabled call denied");
  await admin({ action: "set-enabled", id: "fixture", enabled: true });
  await admin({ action: "grant", clientId: "client:workerd", serviceId: "fixture", tool: "write", mode: "confirm", fingerprint: discovered.fingerprints.find((t: any) => t.name === "write").fingerprint });
  tools = (await c.listTools()).tools;
  const writeName = `ext_fixture_${createHash("sha256").update("write").digest("hex").slice(0, 24)}`;
  const ticket = await admin({ action: "confirm", clientId: "client:workerd", name: writeName, arguments: {} });
  const count = upstream.calls.filter(c => c.name === "write").length;
  const concurrent = await Promise.all([1, 2].map(() => c.callTool({ name: writeName, arguments: {}, _meta: { "hub/confirmation": ticket.confirmation } })));
  check(concurrent.filter(r => r.isError !== true).length === 1 && upstream.calls.filter(c => c.name === "write").length - count === 1, "actual SQLite transaction consumes confirmation once under concurrency");
  await admin({ action: "put-service", id: "legacy", name: "SSE", ownerId: "alice", url: `${upstream.url}/sse`, transport: "sse", secretRef: "test", enabled: true, timeoutMs: 2000 });
  const legacy = await admin({ action: "test", id: "legacy" });
  check(legacy.tools.length === 6, "legacy SSE discovery works inside workerd");
  await admin({ action: "grant", clientId: "client:workerd", serviceId: "legacy", tool: "status", mode: "read", fingerprint: legacy.fingerprints.find((t: any) => t.name === "status").fingerprint });
  const legacyName = `ext_legacy_${createHash("sha256").update("status").digest("hex").slice(0, 24)}`;
  check((await c.callTool({ name: legacyName, arguments: {} })).isError !== true, "legacy SSE call works inside workerd");
  // Exercise existing OAuth endpoints over HTTP, including PKCE and audience.
  const redirectUri = "https://chatgpt.com/connector/oauth/test-fixture";
  const registered = await app.fetch("/oauth/register", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ redirect_uris: [redirectUri], token_endpoint_auth_method: "none" }) });
  assert.equal(registered.status, 201); const registration = await registered.json() as any;
  const verifier = "workerd-fixture-verifier-abcdefghijklmnopqrstuvwxyz123456";
  const authorize = new URL(`${origin}/oauth/authorize`);
  for (const [k, v] of Object.entries({ client_id: registration.client_id, redirect_uri: redirectUri, response_type: "code", code_challenge: createHash("sha256").update(verifier).digest("base64url"), code_challenge_method: "S256", resource: `${origin}/mcp`, state: "fixture" })) authorize.searchParams.set(k, v);
  const page = await app.fetch(authorize.href); const html = await page.text();
  const consent = /name="consent_id" value="([^"]+)"/.exec(html)?.[1]; assert.ok(consent, html);
  const approved = await app.fetch("/oauth/authorize", { method: "POST", redirect: "manual", headers: { "Content-Type": "application/x-www-form-urlencoded" }, body: new URLSearchParams({ consent_id: consent, owner_secret: "workerd-fixture-oauth-owner", decision: "approve" }).toString() });
  assert.equal(approved.status, 302); const code = new URL(approved.headers.get("Location")!).searchParams.get("code")!;
  const exchanged = await app.fetch("/oauth/token", { method: "POST", headers: { "Content-Type": "application/x-www-form-urlencoded" }, body: new URLSearchParams({ grant_type: "authorization_code", code, redirect_uri: redirectUri, client_id: registration.client_id, code_verifier: verifier, resource: `${origin}/mcp` }).toString() });
  assert.equal(exchanged.status, 200, await exchanged.clone().text()); const credentials = await exchanged.json() as any;
  await admin({ action: "put-client", id: `oauth:${registration.client_id}`, userId: "alice", charId: "chatgpt", enabled: true });
  await admin({ action: "grant", clientId: `oauth:${registration.client_id}`, serviceId: "fixture", tool: "sum", mode: "read", fingerprint: discovered.fingerprints.find((t: any) => t.name === "sum").fingerprint });
  const oauth = await client(credentials.access_token);
  const sumName = `ext_fixture_${createHash("sha256").update("sum").digest("hex").slice(0, 24)}`;
  check((await oauth.callTool({ name: sumName, arguments: { a: 40, b: 2 } })).isError !== true, "real OAuth PKCE token authorizes explicitly granted remote tool");
  check((await oauth.listTools()).tools.some(t => t.name === "phone_ping"), "existing OAuth Android tool discovery preserved");
  console.log(`GATEWAY WORKERD: ${passed} passed`);
} finally {
  for (const c of clients) await c.close().catch(() => {});
  await app?.stop(); await upstream.stop();
}
