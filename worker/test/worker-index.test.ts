// ============================================================================
//  Worker 入口 —— 路由 / healthz / 手机握手鉴权
// ============================================================================
//
//  这里测的是 index.ts 的那四条路由，以及"审计者真的会去断言"的几个字段：
//
//    · /healthz 明确写着 database="not-used" 且 d1_bindings=0
//    · /healthz 的 config_problems 在配置正确时是空的（否则就是 "degraded"）
//    · /phone/ws 缺设备头 / 缺令牌 / 令牌错 → 全都 401，且**不区分**设备是否存在
//      （不给攻击者一个枚举设备 ID 的接口）
//    · /phone/ws 非升级请求 → 426
//
//  ⚠ 跑在 Node 上的 Workers 运行时替身里，不是真实 workerd。见 TEST_REPORT.md。
// ============================================================================

import { describe, expect, it } from "vitest";
import worker from "../src/index";
import type { Env } from "../src/env";
import {
  makeRig,
  phoneUpgradeRequest,
  TEST_DEVICE_ID,
  TEST_DEVICE_ID_2,
  TEST_DEVICE_TOKEN,
  TEST_MCP_TOKEN,
  type TestRig,
} from "./harness/env";

/** 走一遍真实的 default export（含路由层）。 */
function call(rig: TestRig, request: Request): Promise<Response> {
  return worker.fetch(request, rig.env, {} as ExecutionContext);
}

function get(rig: TestRig, path: string, headers: Record<string, string> = {}): Promise<Response> {
  return call(rig, new Request(`https://android-mcp-hub.test${path}`, { method: "GET", headers }));
}

describe("Worker / 路由", () => {
  it("GET /healthz 是 JSON，且明确声明没有数据库", async () => {
    const rig = await makeRig();
    const response = await get(rig, "/healthz");
    expect(response.status).toBe(200);
    expect(response.headers.get("Content-Type")).toContain("application/json");
    expect(response.headers.get("Cache-Control")).toBe("no-store");

    const body = (await response.json()) as Record<string, unknown>;
    expect(body["status"]).toBe("ok");
    expect(body["service"]).toBe("android-mcp-hub");
    expect(body["database"]).toBe("not-used");
    expect(body["d1_bindings"]).toBe(0);
    expect(body["schema_version"]).toBe("gate0-do-v1");
    expect(body["durable_object_bindings"]).toEqual(["PHONE_SESSION", "MCP_SESSION"]);
    expect(body["config_problems"]).toEqual([]);

    const capabilities = body["capabilities"] as Record<string, unknown>;
    expect(capabilities["tools"]).toEqual(["phone_ping"]);
    expect(capabilities["server_initiated_notifications"]).toBe(false);

    const auth = body["auth"] as Record<string, unknown>;
    expect(auth["mcp_bearer_configured"]).toBe(true);
    expect(auth["device_credentials_configured"]).toBe(2);
    expect(auth["device_tokens_configured"]).toBe(true);
  });

  it("配置有问题时 healthz 变成 degraded 并列出问题（但不崩）", async () => {
    const rig = await makeRig({ vars: { MAX_PENDING_REQUESTS: "99999" } });
    const response = await get(rig, "/healthz");
    const body = (await response.json()) as Record<string, unknown>;
    expect(body["status"]).toBe("degraded");
    expect((body["config_problems"] as string[]).length).toBeGreaterThan(0);
  });

  it("healthz 不泄漏任何令牌（连哈希都不给）", async () => {
    const rig = await makeRig();
    const text = await (await get(rig, "/healthz")).text();
    expect(text).not.toContain(TEST_MCP_TOKEN);
    expect(text).not.toContain(TEST_DEVICE_TOKEN);
    expect(text).not.toContain("tokenSha256");
  });

  it("GET / 返回纯文本说明，不含凭据", async () => {
    const rig = await makeRig();
    const response = await get(rig, "/");
    expect(response.status).toBe(200);
    const text = await response.text();
    expect(text).toContain("android-mcp-hub");
    expect(text).toContain("/mcp");
    expect(text).toContain("/phone/ws");
    expect(text).not.toContain(TEST_MCP_TOKEN);
  });

  it("未知路径 → 404", async () => {
    const rig = await makeRig();
    expect((await get(rig, "/whatever")).status).toBe(404);
    expect((await get(rig, "/mcp/extra")).status).toBe(404);
    expect((await get(rig, "/phone/wsx")).status).toBe(404);
  });

  it("/mcp 路由被接上：未认证 POST 得到 401 而不是 404", async () => {
    const rig = await makeRig();
    const response = await call(
      rig,
      new Request("https://android-mcp-hub.test/mcp", {
        method: "POST",
        headers: { "Content-Type": "application/json", Accept: "application/json" },
        body: JSON.stringify({ jsonrpc: "2.0", id: 1, method: "initialize", params: {} }),
      }),
    );
    expect(response.status).toBe(401);
  });
});

describe("Worker / 手机 WebSocket 握手鉴权", () => {
  it("正确的设备 id + 令牌可以升级，并拿到 101 与 socket", async () => {
    const rig = await makeRig();
    const response = await call(rig, phoneUpgradeRequest(TEST_DEVICE_ID, TEST_DEVICE_TOKEN));
    expect(response.status).toBe(101);
    expect((response as unknown as { webSocket: unknown }).webSocket).toBeTruthy();
  });

  it("缺少 X-Device-Id → 401", async () => {
    const rig = await makeRig();
    const response = await call(rig, phoneUpgradeRequest(TEST_DEVICE_ID, TEST_DEVICE_TOKEN, { includeDeviceHeader: false }));
    expect(response.status).toBe(401);
  });

  it("缺少 Authorization → 401", async () => {
    const rig = await makeRig();
    const response = await call(rig, phoneUpgradeRequest(TEST_DEVICE_ID, TEST_DEVICE_TOKEN, { includeAuth: false }));
    expect(response.status).toBe(401);
  });

  it("令牌错误 → 401", async () => {
    const rig = await makeRig();
    const response = await call(rig, phoneUpgradeRequest(TEST_DEVICE_ID, "not-the-right-token"));
    expect(response.status).toBe(401);
  });

  it("未知设备 id → 401，且与'令牌错误'无法区分（防止枚举设备 ID）", async () => {
    const rig = await makeRig();
    const unknownDevice = await call(rig, phoneUpgradeRequest("no-such-phone", TEST_DEVICE_TOKEN));
    const badToken = await call(rig, phoneUpgradeRequest(TEST_DEVICE_ID, "not-the-right-token"));

    expect(unknownDevice.status).toBe(401);
    expect(badToken.status).toBe(401);
    expect(await unknownDevice.text()).toBe(await badToken.text());
  });

  it("设备令牌不能拿来当设备 id 用 —— 两台设备的凭据不通用", async () => {
    const rig = await makeRig();
    // 设备 1 的 id + 设备 2 的令牌
    const response = await call(rig, phoneUpgradeRequest(TEST_DEVICE_ID, "gate0-local-test-device-token-2-not-a-secret"));
    expect(response.status).toBe(401);
  });

  it("已吊销的凭据被拒绝", async () => {
    const rig = await makeRig({
      deviceTokens: [
        {
          deviceId: TEST_DEVICE_ID,
          token: TEST_DEVICE_TOKEN,
          revokedAt: "2026-02-01T00:00:00Z",
        },
      ],
    });
    const response = await call(rig, phoneUpgradeRequest(TEST_DEVICE_ID, TEST_DEVICE_TOKEN));
    expect(response.status).toBe(401);
  });

  it("服务端没配 DEVICE_TOKENS 时失败关闭（401），不是放行", async () => {
    const rig = await makeRig({ deviceTokens: null });
    const response = await call(rig, phoneUpgradeRequest(TEST_DEVICE_ID, TEST_DEVICE_TOKEN));
    expect(response.status).toBe(401);
  });

  it("非 WebSocket 请求 → 426 Upgrade Required", async () => {
    const rig = await makeRig();
    const response = await call(rig, phoneUpgradeRequest(TEST_DEVICE_ID, TEST_DEVICE_TOKEN, { upgrade: "h2c" }));
    expect(response.status).toBe(426);
    expect(response.headers.get("Upgrade")).toBe("websocket");
  });

  it("两台设备各自连上，互不影响", async () => {
    const rig = await makeRig();
    const a = await call(rig, phoneUpgradeRequest(TEST_DEVICE_ID, TEST_DEVICE_TOKEN));
    const b = await call(rig, phoneUpgradeRequest(TEST_DEVICE_ID_2, "gate0-local-test-device-token-2-not-a-secret"));
    expect(a.status).toBe(101);
    expect(b.status).toBe(101);
    expect(rig.phone.liveCount()).toBe(2);
  });
});

describe("Worker / 环境契约", () => {
  it("healthz 的 runtime 里报出全部可配置参数（任务书要求'必须可配置'）", async () => {
    const rig = await makeRig();
    const body = (await (await get(rig, "/healthz")).json()) as Record<string, unknown>;
    const runtime = body["runtime"] as Record<string, unknown>;

    expect(runtime["pingTimeoutMs"]).toBe(10_000);
    expect(runtime["heartbeatIntervalMs"]).toBe(30_000);
    expect(runtime["maxPendingRequests"]).toBe(16);
    expect(runtime["maxPhoneMessageBytes"]).toBe(65_536);
    expect(runtime["supportedProtocolVersions"]).toEqual(["2025-06-18", "2025-03-26", "2024-11-05"]);
    expect(runtime["defaultDeviceId"]).toBeNull();
  });

  it("每个参数都能通过 env 覆盖", async () => {
    const rig = await makeRig({
      vars: {
        PHONE_PING_TIMEOUT_MS: "750",
        HEARTBEAT_INTERVAL_MS: "20000",
        MAX_PENDING_REQUESTS: "4",
        MAX_PHONE_MESSAGE_BYTES: "2048",
      },
    });
    const body = (await (await get(rig, "/healthz")).json()) as Record<string, unknown>;
    const runtime = body["runtime"] as Record<string, unknown>;

    expect(runtime["pingTimeoutMs"]).toBe(750);
    expect(runtime["heartbeatIntervalMs"]).toBe(20_000);
    expect(runtime["maxPendingRequests"]).toBe(4);
    expect(runtime["maxPhoneMessageBytes"]).toBe(2_048);
  });
});
