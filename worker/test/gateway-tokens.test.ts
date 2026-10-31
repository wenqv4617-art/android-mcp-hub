// ============================================================================
//  网关访问令牌：签发 → 一次性展示 → 范围 → 撤销 → 过期 → 轮换
// ============================================================================
//
//  这组测试盯的是"令牌是不是真的只是一张受限的通行证"：
//
//    1. 明文只在签发响应里出现一次，之后的任何管理响应都只有元数据；
//    2. scope 只能**收窄** grant —— 只读令牌看不到、也调不了 confirm 模式的工具；
//    3. 撤销/过期立即生效，不需要等会话重连；
//    4. 轮换配对令牌不会连带作废管理员签发的其它令牌；
//    5. 旧版 `tokenSha256` 建出来的客户端仍然能认证（升级不打断现有部署）。
// ============================================================================

import { beforeAll, afterAll, describe, expect, it } from "vitest";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StreamableHTTPClientTransport } from "@modelcontextprotocol/sdk/client/streamableHttp.js";
import { makeRig } from "./harness/env";
import { ShimDurableObjectNamespace } from "./harness/runtime";
import { GatewayStore, gatewayName } from "../src/gateway/store-do";
import { sha256Hex } from "../src/util/crypto";
import { createUpstream, serveWorker, close } from "./gateway-fixture";
import type { Env } from "../src/env";

const CLIENT_TOKEN = /^hub_client_[a-f0-9]{8}_[a-f0-9]{64}$/;

describe("gateway access tokens", () => {
  let rig: Awaited<ReturnType<typeof makeRig>>;
  let upstream: Awaited<ReturnType<typeof createUpstream>>;
  let hub: Awaited<ReturnType<typeof serveWorker>>;
  let ns: ShimDurableObjectNamespace;
  const pairingCode = "token-fixture-pairing-code-0123456789";
  const globalAdminToken = "token-fixture-global-admin";
  const deviceId = "token-fixture-device";
  let managementToken = "";
  let pairToken = "";
  let clientId = "";
  const connected: Client[] = [];

  async function android(body: unknown) {
    const response = await fetch(`${hub.url}/gateway/android`, {
      method: "POST",
      headers: { Authorization: `Bearer ${managementToken}`, "Content-Type": "application/json" },
      body: JSON.stringify(body),
    });
    return { status: response.status, body: await response.json() as any };
  }
  async function globalAdmin(body: unknown) {
    const response = await fetch(`${hub.url}/gateway/admin`, {
      method: "POST",
      headers: { Authorization: `Bearer ${globalAdminToken}`, "Content-Type": "application/json" },
      body: JSON.stringify(body),
    });
    return { status: response.status, body: await response.json() as any };
  }
  async function sdk(bearer: string) {
    const c = new Client({ name: "token-fixture", version: "1" });
    connected.push(c);
    await c.connect(new StreamableHTTPClientTransport(new URL(`${hub.url}/mcp`), {
      requestInit: { headers: { Authorization: `Bearer ${bearer}` } },
    }));
    return c;
  }
  async function issue(body: Record<string, unknown> = {}) {
    return android({ action: "issue-client-token", ...body });
  }
  async function tokensOfClient(): Promise<any[]> {
    const listed = await android({ action: "list" });
    return listed.body.clients.find((c: any) => c.id === clientId)?.tokens ?? [];
  }

  beforeAll(async () => {
    upstream = await createUpstream("token-alpha");
    rig = await makeRig({ vars: { ENVIRONMENT: "gateway-local-test" } });
    Object.assign(rig.env, {
      GATEWAY_ENABLED: "true",
      GATEWAY_ADMIN_TOKEN: globalAdminToken,
      GATEWAY_ANDROID_PAIRING_CODE: pairingCode,
      GATEWAY_ALLOWED_URLS: [`${upstream.url}/mcp`].join(","),
    });
    ns = new ShimDurableObjectNamespace((ctx, env) => new GatewayStore(ctx as never, env as Env), rig.env);
    rig.env.GATEWAY_STORE = ns as never;
    hub = await serveWorker(rig.env);

    const paired = await fetch(`${hub.url}/gateway/pair`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ code: pairingCode, deviceId, deviceName: "Token fixture" }),
    });
    const body = await paired.json() as any;
    managementToken = body.managementToken;
    pairToken = body.clientToken;
    clientId = body.clientId;

    expect((await android({ action: "put-service", id: "svc", name: "svc", url: `${upstream.url}/mcp`, transport: "streamable-http", enabled: true })).status).toBe(200);
    const tested = await android({ action: "test", id: "svc" });
    const fingerprint = (name: string) => tested.body.fingerprints.find((t: any) => t.name === name).fingerprint;
    expect((await android({ action: "grant", serviceId: "svc", tool: "status", mode: "read", fingerprint: fingerprint("status") })).status).toBe(200);
    expect((await android({ action: "grant", serviceId: "svc", tool: "write", mode: "confirm", fingerprint: fingerprint("write") })).status).toBe(200);
  });

  afterAll(async () => {
    for (const c of connected) await c.close().catch(() => {});
    if (hub) await close(hub.server);
    if (upstream) await upstream.stop();
  });

  it("签发时只返回一次明文，之后只暴露元数据", async () => {
    const issued = await issue({ label: "只读客户端", expiresInDays: 30, scopes: ["read"] });
    expect(issued.status).toBe(200);
    expect(issued.body.token).toMatch(CLIENT_TOKEN);
    expect(issued.body.scopes).toEqual(["read"]);
    expect(issued.body.expiresAt).toBeGreaterThan(Date.now());

    const tokens = await tokensOfClient();
    const meta = tokens.find((t: any) => t.id === issued.body.tokenId);
    expect(meta.label).toBe("只读客户端");
    expect(meta.scopes).toEqual(["read"]);
    expect(meta.revokedAt).toBeNull();
    // 摘要和明文都不能出现在管理响应里。
    expect(meta.digest).toBeUndefined();
    expect(JSON.stringify(await android({ action: "list" }))).not.toContain(issued.body.token);
  });

  it("只读令牌看不到、也调不了 confirm 模式的工具", async () => {
    const readToken = (await issue({ label: "scope-read", scopes: ["read"] })).body.token;
    const readClient = await sdk(readToken);
    const names = (await readClient.listTools()).tools.map(t => t.name);
    expect(names).toContain(await gatewayName("svc", "status"));
    expect(names).not.toContain(await gatewayName("svc", "write"));

    const writeName = await gatewayName("svc", "write");
    expect((await readClient.callTool({ name: writeName, arguments: {} })).isError).toBe(true);

    // 就算拿着一张合法的确认票据，只读令牌也不能越过 scope。
    const ticket = (await android({ action: "confirm", name: writeName, arguments: {} })).body.confirmation;
    expect((await readClient.callTool({ name: writeName, arguments: {}, _meta: { "hub/confirmation": ticket } })).isError).toBe(true);

    const writeToken = (await issue({ label: "scope-write", scopes: ["write"] })).body.token;
    const writeClient = await sdk(writeToken);
    expect((await writeClient.listTools()).tools.map(t => t.name)).toContain(writeName);
    const second = (await android({ action: "confirm", name: writeName, arguments: {} })).body.confirmation;
    expect((await writeClient.callTool({ name: writeName, arguments: {}, _meta: { "hub/confirmation": second } })).isError).not.toBe(true);
  });

  it("记录最近使用时间", async () => {
    const issued = await issue({ label: "usage" });
    const c = await sdk(issued.body.token);
    await c.listTools();
    const meta = (await tokensOfClient()).find((t: any) => t.id === issued.body.tokenId);
    expect(meta.lastUsedAt).toBeGreaterThan(0);
  });

  it("撤销单条令牌不影响其它令牌", async () => {
    const issued = await issue({ label: "to-revoke" });
    const doomed = await sdk(issued.body.token);
    expect((await doomed.listTools()).tools.length).toBeGreaterThan(0);
    expect((await android({ action: "revoke-client-token", tokenId: issued.body.tokenId })).status).toBe(200);
    await expect(doomed.listTools()).rejects.toThrow();
    const pairClient = await sdk(pairToken);
    expect((await pairClient.listTools()).tools.length).toBeGreaterThan(0);
  });

  it("过期令牌在服务端立即失效（不依赖客户端重连）", async () => {
    const issued = await issue({ label: "expiring" });
    const c = await sdk(issued.body.token);
    const storage = ns.stateFor("gateway-v1").storage;
    const raw = await storage.get("gateway-v1") as any;
    raw.clients.find((x: any) => x.id === clientId).tokens
      .find((t: any) => t.id === issued.body.tokenId).expiresAt = Date.now() - 1;
    await storage.put("gateway-v1", raw);
    await expect(c.listTools()).rejects.toThrow();
  });

  it("每个客户端的活动令牌有上限", async () => {
    let limitHit = false;
    for (let i = 0; i < 12; i++) {
      const issued = await issue({ label: `limit-${i}` });
      if (issued.status === 429 || issued.body.error === "token_limit") { limitHit = true; break; }
    }
    expect(limitHit).toBe(true);
    const now = Date.now();
    const active = (await tokensOfClient()).filter((t: any) =>
      t.revokedAt === null && (t.expiresAt === null || t.expiresAt > now));
    expect(active.length).toBeLessThanOrEqual(8);
  });

  it("轮换配对令牌只替换配对令牌", async () => {
    // 先清掉上限测试留下的活动令牌，避免签发被上限挡住。
    for (const t of await tokensOfClient()) {
      if (t.revokedAt === null && t.source === "issued") {
        expect((await android({ action: "revoke-client-token", tokenId: t.id })).status).toBe(200);
      }
    }
    const kept = await issue({ label: "kept-across-rotation" });
    const keptClient = await sdk(kept.body.token);
    const rotated = await android({ action: "rotate-client-token" });
    expect(rotated.status).toBe(200);
    expect(rotated.body.clientToken).toMatch(CLIENT_TOKEN);
    // 旧配对令牌立即失效，管理员签发的令牌不受影响。
    await expect(sdk(pairToken).then(c => c.listTools())).rejects.toThrow();
    expect((await keptClient.listTools()).tools.length).toBeGreaterThan(0);
    pairToken = rotated.body.clientToken;
    const fresh = await sdk(pairToken);
    expect((await fresh.listTools()).tools.length).toBeGreaterThan(0);
  });

  it("旧版 tokenSha256 创建的客户端仍可认证", async () => {
    const legacy = "hub_client_legacy-fixture-token";
    expect((await globalAdmin({
      action: "put-client", id: "client:legacy", userId: "alice", charId: "legacy-char",
      tokenSha256: await sha256Hex(legacy), enabled: true,
    })).status).toBe(200);
    const c = await sdk(legacy);
    expect((await c.listTools()).tools).toEqual([]);
  });
});
