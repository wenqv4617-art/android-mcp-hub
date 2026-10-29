import { beforeAll, afterAll, describe, expect, it } from "vitest";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StreamableHTTPClientTransport } from "@modelcontextprotocol/sdk/client/streamableHttp.js";
import { makeRig } from "./harness/env";
import { ShimDurableObjectNamespace } from "./harness/runtime";
import { GatewayStore, gatewayName } from "../src/gateway/store-do";
import { sha256Hex } from "../src/util/crypto";
import { createUpstream, serveWorker, close } from "./gateway-fixture";
import type { Env } from "../src/env";

/**
 * Android 受限管理面：配对 → 管理自己的服务 → 用配对得到的 MCP 令牌真实调用。
 *
 * 这里刻意断言的是**权限边界**，而不只是 happy path：
 * 受限令牌不能管理别人的服务、不能给别人的 client 授权、撤销/轮换立即生效，
 * 且任何管理响应里都不出现令牌明文或摘要。
 */
describe("android pairing and scoped gateway management", () => {
  let rig: Awaited<ReturnType<typeof makeRig>>;
  let upstream: Awaited<ReturnType<typeof createUpstream>>;
  let other: typeof upstream;
  let hub: Awaited<ReturnType<typeof serveWorker>>;
  let ns: ShimDurableObjectNamespace;
  const pairingCode = "fixture-pairing-code-0123456789";
  const adminToken = "fixture-global-admin-token";
  const deviceId = "android-fixture-device";
  let managementToken = "";
  let clientToken = "";
  let ownerId = "";
  let clientId = "";
  const connected: Client[] = [];

  async function pair(code = pairingCode, device = deviceId) {
    const response = await fetch(`${hub.url}/gateway/pair`, {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ code, deviceId: device, deviceName: "Pixel fixture" }),
    });
    return { status: response.status, body: await response.json() as any };
  }
  async function android(body: unknown, bearer = managementToken) {
    const response = await fetch(`${hub.url}/gateway/android`, {
      method: "POST", headers: { Authorization: `Bearer ${bearer}`, "Content-Type": "application/json" },
      body: JSON.stringify(body),
    });
    return { status: response.status, body: await response.json() as any };
  }
  async function sdk(bearer: string) {
    const c = new Client({ name: "android-fixture", version: "1" }); connected.push(c);
    await c.connect(new StreamableHTTPClientTransport(new URL(`${hub.url}/mcp`), { requestInit: { headers: { Authorization: `Bearer ${bearer}` } } }));
    return c;
  }

  beforeAll(async () => {
    upstream = await createUpstream("android-alpha");
    other = await createUpstream("android-other");
    // ENVIRONMENT=gateway-local-test 是 security.ts 里唯一允许 127.0.0.1 夹具的开关。
    rig = await makeRig({ vars: { ENVIRONMENT: "gateway-local-test" } });
    Object.assign(rig.env, {
      GATEWAY_ENABLED: "true",
      GATEWAY_ADMIN_TOKEN: adminToken,
      GATEWAY_ANDROID_PAIRING_CODE: pairingCode,
      GATEWAY_ALLOWED_URLS: [`${upstream.url}/mcp`, `${other.url}/mcp`].join(","),
    });
    ns = new ShimDurableObjectNamespace((ctx, env) => new GatewayStore(ctx as never, env as Env), rig.env);
    rig.env.GATEWAY_STORE = ns as never;
    hub = await serveWorker(rig.env);

    // 一个与 Android 无关的操作员服务，用来验证跨 owner 隔离。
    const put = await fetch(`${hub.url}/gateway/admin`, {
      method: "POST", headers: { Authorization: `Bearer ${adminToken}`, "Content-Type": "application/json" },
      body: JSON.stringify({ action: "put-service", id: "operator-svc", name: "operator", ownerId: "alice", url: `${other.url}/mcp`, transport: "streamable-http", enabled: true }),
    });
    expect(put.status).toBe(200);

    const first = await pair();
    expect(first.status).toBe(200);
    managementToken = first.body.managementToken;
    clientToken = first.body.clientToken;
    ownerId = first.body.ownerId;
    clientId = first.body.clientId;
  });
  afterAll(async () => {
    for (const c of connected) await c.close().catch(() => {});
    if (hub) await close(hub.server);
    if (upstream) await upstream.stop();
    if (other) await other.stop();
  });

  it("rejects a wrong pairing code and hides pairing when unconfigured", async () => {
    expect((await pair("wrong-code-but-long-enough")).status).toBe(401);
    const saved = rig.env.GATEWAY_ANDROID_PAIRING_CODE;
    rig.env.GATEWAY_ANDROID_PAIRING_CODE = undefined;
    expect((await pair()).status).toBe(404);
    rig.env.GATEWAY_ANDROID_PAIRING_CODE = saved;
    expect((await fetch(`${hub.url}/gateway/android`, { method: "POST", headers: { "Content-Type": "application/json" }, body: "{}" })).status).toBe(401);
  });

  it("issues scoped tokens that are not the global admin token and stores only digests", async () => {
    expect(managementToken).toMatch(/^hub_admin_[a-f0-9]{64}$/);
    expect(clientToken).toMatch(/^hub_client_[a-f0-9]{64}$/);
    expect(managementToken).not.toBe(adminToken);
    expect(managementToken).not.toBe(clientToken);
    expect(ownerId).toMatch(/^android-[a-f0-9]{12}$/);
    expect(clientId).toBe(`client:${ownerId}`);

    // 直接看 DO 存储：两枚令牌都只以 SHA-256 摘要落盘。
    const raw = await ns.stateFor("gateway-v1").storage.get("gateway-v1") as any;
    expect(raw.androidAdmins[0].tokenSha256).toBe(await sha256Hex(managementToken));
    expect(raw.clients.find((c: any) => c.id === clientId).tokenSha256).toBe(await sha256Hex(clientToken));
    expect(JSON.stringify(raw)).not.toContain(managementToken);
    expect(JSON.stringify(raw)).not.toContain(clientToken);


    const listed = await android({ action: "list" });
    expect(listed.status).toBe(200);
    expect(JSON.stringify(listed.body)).not.toContain(managementToken);
    expect(JSON.stringify(listed.body)).not.toContain(clientToken);
  });

  it("creates, discovers, grants and calls only its own service end to end", async () => {
    const serviceId = "android-echo";
    const saved = await android({ action: "put-service", id: serviceId, name: "My MCP", url: `${upstream.url}/mcp`, transport: "streamable-http", enabled: true });
    expect(saved.status).toBe(200);
    expect(saved.body.service.ownerId).toBe(ownerId);

    const tested = await android({ action: "test", id: serviceId });
    expect(tested.status).toBe(200);
    expect(tested.body.tools.map((t: any) => t.name)).toContain("sum");

    const listed = await android({ action: "list" });
    expect(listed.status).toBe(200);
    expect(listed.body.services.map((s: any) => s.id)).toEqual([serviceId]);
    expect(JSON.stringify(listed.body)).not.toContain(managementToken);
    expect(JSON.stringify(listed.body)).not.toContain(clientToken);

    const fp = tested.body.fingerprints.find((t: any) => t.name === "sum").fingerprint;
    expect((await android({ action: "grant", serviceId, tool: "sum", mode: "read", fingerprint: fp })).status).toBe(200);
    // 客户端即使伪造 clientId，服务端也会覆盖成配对身份。
    expect((await android({ action: "grant", serviceId, tool: "sum", mode: "read", fingerprint: fp, clientId: "client:someone-else" })).status).toBe(200);

    const c = await sdk(clientToken);
    const tools = (await c.listTools()).tools;
    expect(tools.map(t => t.name)).toContain(await gatewayName(serviceId, "sum"));
    const result = await c.callTool({ name: await gatewayName(serviceId, "sum"), arguments: { a: 20, b: 22 } });
    expect(result.content).toEqual([{ type: "text", text: "42" }]);
    await c.close();
  });

  it("cannot touch another owner's service or client, and disabled services are rejected", async () => {
    expect((await android({ action: "set-enabled", id: "operator-svc", enabled: false })).status).toBe(404);
    expect((await android({ action: "delete-service", id: "operator-svc" })).status).toBe(404);
    expect((await android({ action: "test", id: "operator-svc" })).status).toBe(404);
    expect((await android({ action: "confirm", name: await gatewayName("operator-svc", "sum"), arguments: {} })).status).toBe(404);

    expect((await android({ action: "set-enabled", id: "android-echo", enabled: false })).status).toBe(200);
    const c = await sdk(clientToken);
    expect((await c.listTools()).tools).toEqual([]);
    expect((await c.callTool({ name: await gatewayName("android-echo", "sum"), arguments: { a: 1, b: 2 } })).isError).toBe(true);
    expect((await android({ action: "set-enabled", id: "android-echo", enabled: true })).status).toBe(200);
    await c.close();
  });

  it("issues argument-bound confirmation tickets only for its own tools", async () => {
    const tested = await android({ action: "test", id: "android-echo" });
    const fp = tested.body.fingerprints.find((t: any) => t.name === "write").fingerprint;
    expect((await android({ action: "grant", serviceId: "android-echo", tool: "write", mode: "confirm", fingerprint: fp })).status).toBe(200);
    const name = await gatewayName("android-echo", "write");
    const c = await sdk(clientToken);
    expect((await c.callTool({ name, arguments: {} })).isError).toBe(true);
    const ticket = await android({ action: "confirm", name, arguments: {} });
    expect(ticket.status).toBe(200);
    expect((await c.callTool({ name, arguments: {}, _meta: { "hub/confirmation": ticket.body.confirmation } })).isError).not.toBe(true);
    await c.close();
  });

  it("deletes its own service and removes its grants", async () => {
    expect((await android({ action: "put-service", id: "android-temp", name: "temp", url: `${other.url}/mcp`, transport: "streamable-http", enabled: true })).status).toBe(200);
    expect((await android({ action: "test", id: "android-temp" })).status).toBe(200);
    expect((await android({ action: "delete-service", id: "android-temp" })).status).toBe(200);
    const listed = await android({ action: "list" });
    expect(listed.body.services.map((s: any) => s.id)).not.toContain("android-temp");
  });

  it("rotates and revokes credentials immediately", async () => {
    const old = await sdk(clientToken);
    const rotated = await android({ action: "rotate-client-token" });
    expect(rotated.status).toBe(200);
    expect(rotated.body.clientToken).toMatch(/^hub_client_[a-f0-9]{64}$/);
    await expect(old.listTools()).rejects.toThrow();
    await old.close();
    clientToken = rotated.body.clientToken;
    const fresh = await sdk(clientToken);
    expect((await fresh.listTools()).tools.length).toBeGreaterThan(0);
    await fresh.close();

    const staleManagement = managementToken;
    const second = await pair();
    expect(second.status).toBe(200);
    expect(second.body.managementToken).not.toBe(staleManagement);
    expect((await android({ action: "list" }, staleManagement)).status).toBe(401);
    managementToken = second.body.managementToken;
    clientToken = second.body.clientToken;
  });

  it("unpairs: management and MCP tokens both stop working", async () => {
    const c = await sdk(clientToken);
    expect((await android({ action: "unpair" })).status).toBe(200);
    expect((await android({ action: "list" })).status).toBe(401);
    await expect(c.listTools()).rejects.toThrow();
    await c.close();
  });

  it("rate-limits repeated wrong pairing attempts and resets after success", async () => {
    expect((await pair()).status).toBe(200); // 清空此前失败计数
    for (let i = 0; i < 10; i++) expect((await pair(`wrong-pairing-code-${i}`)).status).toBe(401);
    expect((await pair("wrong-pairing-code-again")).status).toBe(429);
  });
});
