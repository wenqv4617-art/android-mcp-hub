import { beforeAll, afterAll, describe, expect, it } from "vitest";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StreamableHTTPClientTransport } from "@modelcontextprotocol/sdk/client/streamableHttp.js";
import { makeRig, TEST_MCP_TOKEN, connectMockPhone, TEST_DEVICE_ID, capturedLogs } from "./harness/env";
import { ShimDurableObjectNamespace } from "./harness/runtime";
import { GatewayStore, gatewayName } from "../src/gateway/store-do";
import { sha256Hex } from "../src/util/crypto";
import { publicAddress, validateTarget, checkDns, boundedText } from "../src/gateway/security";
import { createUpstream, serveWorker, close } from "./gateway-fixture";
import type { Env } from "../src/env";

describe("universal gateway: real HTTP + official SDK through Worker", () => {
  let rig: Awaited<ReturnType<typeof makeRig>>;
  let a: Awaited<ReturnType<typeof createUpstream>>;
  let b: typeof a;
  let hub: Awaited<ReturnType<typeof serveWorker>>;
  let ns: ShimDurableObjectNamespace;
  const token = "hub_client_fixture-downstream-token";
  const adminToken = "fixture-admin-only-token";
  let client: Client;
  const connected: Client[] = [];
  async function admin(body: unknown, bearer = adminToken) {
    const response = await fetch(`${hub.url}/gateway/admin`, { method: "POST", headers: { Authorization: `Bearer ${bearer}`, "Content-Type": "application/json" }, body: JSON.stringify(body) });
    return { status: response.status, body: await response.json() as any };
  }
  async function sdk(bearer = token) {
    const c = new Client({ name: "integration-client", version: "1" }); connected.push(c);
    await c.connect(new StreamableHTTPClientTransport(new URL(`${hub.url}/mcp`), { requestInit: { headers: { Authorization: `Bearer ${bearer}` } } }));
    return c;
  }
  async function service(id: string, url = `${a.url}/mcp`, transport = "streamable-http", timeoutMs = 1500) {
    expect((await admin({ action: "put-service", id, name: id, ownerId: "alice", url, transport, timeoutMs, enabled: true, secretRef: "fixture" })).status).toBe(200);
    return (await admin({ action: "test", id })).body;
  }
  async function grant(id: string, tool: string, mode = "read", clientId = "client:glimmer") {
    const test = await admin({ action: "test", id });
    return admin({ action: "grant", clientId, serviceId: id, tool, mode, fingerprint: test.body.fingerprints.find((t: any) => t.name === tool).fingerprint });
  }
  beforeAll(async () => {
    a = await createUpstream("alpha"); b = await createUpstream("beta");
    rig = await makeRig({ vars: { ENVIRONMENT: "gateway-local-test" } });
    Object.assign(rig.env, { GATEWAY_ENABLED: "true", GATEWAY_ADMIN_TOKEN: adminToken,
      GATEWAY_ALLOWED_URLS: [`${a.url}/mcp`, `${a.url}/sse`, `${a.url}/messages`, `${b.url}/mcp`].join(","),
      GATEWAY_UPSTREAM_SECRETS: JSON.stringify({ "alice/fixture": "Bearer fixture-upstream-secret" }) });
    ns = new ShimDurableObjectNamespace((ctx, env) => new GatewayStore(ctx as never, env as Env), rig.env);
    rig.env.GATEWAY_STORE = ns as never;
    hub = await serveWorker(rig.env);
    expect((await admin({ action: "put-client", id: "client:glimmer", userId: "alice", charId: "char-one", tokenSha256: await sha256Hex(token), enabled: true })).status).toBe(200);
    client = await sdk();
  });
  afterAll(async () => { for (const c of connected) await c.close().catch(() => {}); if (hub) await close(hub.server); if (a) await a.stop(); if (b) await b.stop(); });

  it("starts deny-by-default and separates admin credentials", async () => {
    expect((await client.listTools()).tools).toEqual([]);
    expect((await admin({ action: "list" }, token)).status).toBe(401);
    await expect(sdk("hub_client_unknown")).rejects.toThrow();
  });
  it("registers and discovers with official Streamable HTTP; grants exact tools", async () => {
    const discovered = await service("alpha"); expect(discovered.tools.map((t: any) => t.name)).toContain("sum");
    expect((await client.listTools()).tools).toEqual([]);
    expect((await grant("alpha", "status")).status).toBe(200);
    expect((await grant("alpha", "sum")).status).toBe(200);
    const tools = (await client.listTools()).tools;
    expect(tools).toHaveLength(2);
    expect(tools.find(t => t.name.startsWith("ext_alpha_"))?.inputSchema).toEqual(a.tools()[0]?.inputSchema);
    expect(a.headers.filter(Boolean).every(x => x === "Bearer fixture-upstream-secret")).toBe(true);
  });
  it("calls parameterized tool end to end and never forwards downstream Authorization", async () => {
    const result = await client.callTool({ name: await gatewayName("alpha", "sum"), arguments: { a: 2, b: 40 } });
    expect(result.content).toEqual([{ type: "text", text: "42" }]);
    expect(a.calls.at(-1)).toMatchObject({ name: "sum", arguments: { a: 2, b: 40 }, authorization: "Bearer fixture-upstream-secret" });
  });
  it("denies an ungranted write and rejects invalid arguments before execution", async () => {
    const count = a.calls.length;
    expect((await client.callTool({ name: await gatewayName("alpha", "forbidden"), arguments: {} })).isError).toBe(true);
    expect((await client.callTool({ name: await gatewayName("alpha", "sum"), arguments: { a: "bad" } })).isError).toBe(true);
    expect(a.calls).toHaveLength(count);
    await expect(client.callTool({ name: "phone_ping", arguments: {} })).rejects.toThrow("permission_denied");
  });
  it("routes identical upstream names to separate stable namespaces", async () => {
    await service("beta", `${b.url}/mcp`); await grant("beta", "status");
    const an = await gatewayName("alpha", "status"), bn = await gatewayName("beta", "status");
    expect(an).not.toBe(bn); expect(an.length).toBeLessThanOrEqual(64);
    expect((await client.callTool({ name: an, arguments: {} })).content).toEqual([{ type: "text", text: "alpha" }]);
    expect((await client.callTool({ name: bn, arguments: {} })).content).toEqual([{ type: "text", text: "beta" }]);
  });
  it("disabling hides tools and denies calls; enabling keeps stable identities", async () => {
    await admin({ action: "set-enabled", id: "beta", enabled: false });
    expect((await client.listTools()).tools.some(t => t.name.startsWith("ext_beta_"))).toBe(false);
    expect((await client.callTool({ name: await gatewayName("beta", "status"), arguments: {} })).isError).toBe(true);
    await admin({ action: "set-enabled", id: "beta", enabled: true });
    expect((await client.listTools()).tools.map(t => t.name)).toContain(await gatewayName("beta", "status"));
  });
  it("revocation updates discovery and calls immediately", async () => {
    await admin({ action: "revoke", clientId: "client:glimmer", serviceId: "beta", tool: "status" });
    expect((await client.listTools()).tools.some(t => t.name.startsWith("ext_beta_"))).toBe(false);
    expect((await client.callTool({ name: await gatewayName("beta", "status"), arguments: {} })).isError).toBe(true);
  });
  it("persists services and grants across object eviction", async () => {
    ns.evict("gateway-v1");
    expect((await client.listTools()).tools).toHaveLength(2);
    expect((await admin({ action: "list" })).body.services).toHaveLength(2);
  });
  it("tool definition changes invalidate prior grants before any call", async () => {
    const count = a.calls.length; a.setDescription("Changed dangerous semantics");
    expect((await client.callTool({ name: await gatewayName("alpha", "status"), arguments: {} })).isError).toBe(true);
    expect(a.calls).toHaveLength(count);
    await admin({ action: "test", id: "alpha" });
    expect((await client.listTools()).tools).toHaveLength(1);
    a.setDescription("Stable tool definition"); await admin({ action: "test", id: "alpha" });
  });
  it("requires an argument-bound single-use confirmation for explicit write permission", async () => {
    await grant("alpha", "write", "confirm");
    const name = await gatewayName("alpha", "write");
    expect((await client.callTool({ name, arguments: {} })).isError).toBe(true);
    const ticket = (await admin({ action: "confirm", clientId: "client:glimmer", name, arguments: {} })).body.confirmation;
    expect((await client.callTool({ name, arguments: { different: true }, _meta: { "hub/confirmation": ticket } })).isError).toBe(true);
    const count = a.calls.length;
    expect((await client.callTool({ name, arguments: {}, _meta: { "hub/confirmation": ticket } })).isError).not.toBe(true);
    expect((await client.callTool({ name, arguments: {}, _meta: { "hub/confirmation": ticket } })).isError).toBe(true);
    expect(a.calls.length - count).toBe(1);
  });
  it("sanitizes upstream tool errors and audit rows, never logging credentials", async () => {
    await grant("alpha", "failure");
    const result = await client.callTool({ name: await gatewayName("alpha", "failure"), arguments: {} });
    expect(result.isError).toBe(true); expect(JSON.stringify(result)).not.toContain("fixture-upstream-secret");
    const data = (await admin({ action: "list" })).body;
    expect(data.audit.some((a: any) => a.outcome === "upstream_tool_error")).toBe(true);
    for (const text of [JSON.stringify(data), capturedLogs().join("\n")]) {
      expect(text).not.toContain("fixture-upstream-secret"); expect(text).not.toContain(token); expect(text).not.toContain(adminToken);
    }
  });
  it("bounds upstream calls with no automatic replay", async () => {
    await service("slow", `${a.url}/mcp`, "streamable-http", 200); await grant("slow", "slow");
    const count = a.calls.filter(c => c.name === "slow").length; const start = Date.now();
    const result = await client.callTool({ name: await gatewayName("slow", "slow"), arguments: {} });
    expect(result.isError).toBe(true); expect(Date.now() - start).toBeLessThan(1000);
    expect(a.calls.filter(c => c.name === "slow").length - count).toBe(1);
  });
  it("supports legacy SSE through isolated official SDK adapter", async () => {
    const result = await service("legacy", `${a.url}/sse`, "sse");
    expect(result.tools).toHaveLength(6); await grant("legacy", "sum");
    expect((await client.callTool({ name: await gatewayName("legacy", "sum"), arguments: { a: 4, b: 5 } })).content).toEqual([{ type: "text", text: "9" }]);
  });
  it("rejects redirects, disconnects and oversized upstream results", async () => {
    const name = await gatewayName("alpha", "status");
    for (const behavior of ["redirect", "disconnect", "large"] as const) {
      a.setBehavior(behavior);
      expect((await client.callTool({ name, arguments: {} })).isError).toBe(true);
    }
    a.setBehavior("normal");
  });
  it("keeps other users and Char permissions isolated", async () => {
    await admin({ action: "put-client", id: "client:bob", userId: "bob", charId: "char-one", enabled: true });
    expect((await grant("alpha", "status", "read", "client:bob")).status).toBe(403);
    await admin({ action: "put-client", id: "client:other-char", userId: "alice", charId: "char-two", tokenSha256: await sha256Hex("hub_client_otherchar"), enabled: true });
    const other = await sdk("hub_client_otherchar"); expect((await other.listTools()).tools).toEqual([]);
    expect((await other.callTool({ name: await gatewayName("alpha", "status"), arguments: {} })).isError).toBe(true);
  });
  it("regresses Android ping and native reads through existing MCP route", async () => {
    const phone = await connectMockPhone(rig, TEST_DEVICE_ID);
    const owner = await sdk(TEST_MCP_TOKEN);
    expect((await owner.listTools()).tools.map(t => t.name)).toContain("phone_ping");
    for (const name of ["phone_ping", "battery_get_status", "device_get_network", "device_get_storage"]) {
      expect((await owner.callTool({ name, arguments: { device_id: TEST_DEVICE_ID } })).isError).not.toBe(true);
    }
    phone.socket.close();
  });
  it("supports independent client revocation without reinitializing MCP", async () => {
    await admin({ action: "put-client", id: "client:glimmer", userId: "alice", charId: "char-one", enabled: false });
    await expect(client.listTools()).rejects.toThrow();
  });
});

// Keep policy tests independent of live network and local-test bypasses.
describe("gateway security policy", () => {
  it.each(["127.0.0.1", "169.254.169.254", "10.2.3.4", "172.16.0.1", "192.168.1.1", "100.64.0.1", "0.0.0.0", "::1", "fc00::1", "fe80::1", "::ffff:127.0.0.1", "2001:db8::1"])("denies non-public address %s", ip => expect(publicAddress(ip)).toBe(false));
  it("only accepts public unicast answers", () => { expect(publicAddress("1.1.1.1")).toBe(true); expect(publicAddress("2606:4700:4700::1111")).toBe(true); });
  it.each(["http://example.com/mcp", "https://127.0.0.1/mcp", "https://[::1]/mcp", "https://metadata.google.internal/mcp", "https://example.com:8443/mcp", "https://u:p@example.com/mcp", "https://example.com/mcp?key=secret", "https://unlisted.example/mcp"])("denies unsafe or unlisted URL %s", raw => expect(() => validateTarget(raw, { GATEWAY_ALLOWED_URLS: "https://example.com/mcp" } as Env)).toThrow());
  it("requires explicit allowlist and checks both DNS families", async () => {
    const env = { GATEWAY_ALLOWED_URLS: "https://example.com/mcp" } as Env;
    const url = validateTarget("https://example.com/mcp", env); let count = 0;
    await expect(checkDns(url, env, new AbortController().signal, (async () => { count++; return new Response(JSON.stringify({ Status: 0, Answer: [{ type: 1, data: "169.254.169.254" }] })); }) as typeof fetch)).rejects.toThrow();
    expect(count).toBe(2);
  });
  it("bounds chunked request bodies", async () => {
    const stream = new ReadableStream<Uint8Array>({ start(c) { c.enqueue(new Uint8Array(32)); c.enqueue(new Uint8Array(32)); c.close(); } });
    await expect(boundedText(stream, 48)).rejects.toThrow("size_limit");
  });
});
