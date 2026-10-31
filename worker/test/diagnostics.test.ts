// ============================================================================
//  V1.6.3 —— 双端连接诊断缓冲区与云端查询
// ============================================================================
//
//  这组用例要回答的**不是**"函数返回值对不对"，而是任务书里那几条可被审计的
//  性质：
//    · 设备 A 读不到设备 B 的诊断；未认证读不到任何诊断；
//    · 缓冲区有界、会过期，丢弃数量如实记账（"看不到"不等于"没发生过"）；
//    · 任何自由文本 / 令牌形状的东西都进不了事件；
//    · 服务端主动关闭记录的是**真实调用来源**，不是客户端最终看到的错误码；
//    · 重复连接被识别为替换，并写清被替换的代次；
//    · 查询不影响主 WebSocket（查完还能 ping 通）。
//
//  ⚠ 跑在 Node 上的 Workers 替身里，不是真实 workerd；限制见 TEST_REPORT.md。
// ============================================================================

import { describe, expect, it } from "vitest";
import worker from "../src/index";
import {
  buildEvent,
  DIAG_MAX_AGE_MS,
  DIAG_MAX_EVENTS,
  pruneEvents,
  restoreDiagnostics,
  sanitizeDetail,
  sanitizeDetailValue,
} from "../src/phone/diagnostics";
import {
  clearCapturedLogs,
  connectMockPhone,
  makeRig,
  pingPhoneDirect,
  settle,
  TEST_DEVICE_ID,
  TEST_DEVICE_ID_2,
  TEST_DEVICE_TOKEN,
  TEST_DEVICE_TOKEN_2,
  type TestRig,
} from "./harness/env";

const INTERNAL_DEVICE = "X-Phone-Session-Device";

interface DiagBody {
  ok: boolean;
  device_id: string | null;
  server_time: number;
  online: boolean;
  socket_open: boolean;
  stale: boolean;
  connection_generation: number;
  events: Array<Record<string, unknown>>;
  summary: Record<string, unknown>;
  buffer: Record<string, unknown>;
  close_codes: Record<string, string>;
}

/** 直接问 DO 的诊断内部路由（生产里只有 Worker 能到这里）。 */
async function queryDiag(
  rig: TestRig,
  deviceId: string,
  opts: { limit?: number; sinceMs?: number; deviceHeader?: string; method?: string } = {},
): Promise<{ status: number; body: DiagBody; raw: string }> {
  const params = new URLSearchParams();
  if (opts.limit !== undefined) params.set("limit", String(opts.limit));
  if (opts.sinceMs !== undefined) params.set("since_ms", String(opts.sinceMs));
  const suffix = params.toString() === "" ? "" : `?${params.toString()}`;
  const response = await rig.phone.fetchOn(
    deviceId,
    new Request(`https://phone-session.internal/internal/phone/diagnostics${suffix}`, {
      method: opts.method ?? "GET",
      headers: { [INTERNAL_DEVICE]: opts.deviceHeader ?? deviceId },
    }),
  );
  const raw = await response.text();
  return { status: response.status, body: JSON.parse(raw) as DiagBody, raw };
}

function seedEvent(seq: number, at: number, overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    seq,
    at,
    source: "durable_object",
    type: "device_online",
    generation: 1,
    close_code: null,
    close_reason: null,
    server_initiated: null,
    duration_ms: null,
    detail: {},
    ...overrides,
  };
}

/** 走一遍真实的 Worker 路由（含鉴权）。 */
function diagRequest(deviceId: string, token: string, init: { method?: string } = {}): Request {
  return new Request("https://android-mcp-hub.test/phone/diagnostics", {
    method: init.method ?? "GET",
    headers: { "X-Device-Id": deviceId, Authorization: `Bearer ${token}` },
  });
}

function call(rig: TestRig, request: Request): Promise<Response> {
  return worker.fetch(request, rig.env, {} as ExecutionContext);
}

// ---------------------------------------------------------------------------
//  纯函数：白名单、裁剪、损坏恢复
// ---------------------------------------------------------------------------

describe("诊断缓冲区 / 字段白名单", () => {
  it("detail 只保留白名单键，自由文本与令牌形状的值一律丢弃", () => {
    const detail = sanitizeDetail({
      cause: "replaced",
      caller: "handle_connect",
      network: "wifi",
      vpn: true,
      pid: 1234,
      app_version: "1.6.3-debug",
      android_sdk: 36,
      exception_class: "java.net.SocketException$Reset",
      note: "这是用户写的聊天内容",
      authorization: "Bearer abcdefghijklmnop",
      network_extra: "Bearer abcdefghijklmnop",
      attempt: -1,
      screen: null,
    });
    expect(detail).toEqual({
      android_sdk: 36,
      app_version: "1.6.3-debug",
      caller: "handle_connect",
      cause: "replaced",
      exception_class: "java.net.SocketException$Reset",
      network: "wifi",
      pid: 1234,
      screen: null,
      vpn: true,
    });
  });

  it("枚举值必须命中闭集：认不出的网络/来源被丢弃，不会原样落库", () => {
    const detail = sanitizeDetail({
      network: "Bearer sometokenvalue",
      caller: "evil_caller",
      cause: "made_up_cause",
      auth_scope: "device",
    });
    expect(detail).toEqual({ auth_scope: "device" });
  });

  it("异常类名只接受标识符形状，自由文本与超长值被丢弃", () => {
    expect(sanitizeDetailValue("exception_class", "EOFException")).toBe("EOFException");
    expect(sanitizeDetailValue("exception_class", "java.net.SocketTimeoutException")).toBe(
      "java.net.SocketTimeoutException",
    );
    for (const bad of [
      "java.net.SocketException: Connection reset",
      "Bearer abcdefghijklmnop",
      "9Lives",
      "A".repeat(65),
      "",
      "  EOFException",
      "EOFException\n",
    ]) {
      expect(sanitizeDetailValue("exception_class", bad)).toBeUndefined();
    }
    expect(sanitizeDetailValue("exception_class", 42)).toBeUndefined();
  });

  it("buildEvent 拒绝未知 source/type，关闭码越界不编造", () => {
    expect(
      buildEvent(1, Date.now(), { source: "hacker" as never, type: "device_online" }),
    ).toBeNull();
    expect(
      buildEvent(1, Date.now(), { source: "worker", type: "rm_rf" as never }),
    ).toBeNull();
    const badCode = buildEvent(2, Date.now(), { source: "worker", type: "device_offline", close_code: 9999 });
    expect(badCode?.close_code).toBeNull();
    const goodCode = buildEvent(3, Date.now(), { source: "worker", type: "device_offline", close_code: 4001 });
    expect(goodCode?.close_code).toBe(4001);
  });

  it("close_reason 只接受服务端自己的标签集合", () => {
    const withFreeText = buildEvent(1, Date.now(), {
      source: "android",
      type: "client_report",
      close_reason: "token=abc please read /etc/passwd" as never,
    });
    expect(withFreeText?.close_reason).toBeNull();
  });
});

describe("诊断缓冲区 / 有界与过期", () => {
  it("超过容量时只保留最近 DIAG_MAX_EVENTS 条，并如实记录丢弃数量", () => {
    const now = Date.now();
    const events = Array.from({ length: DIAG_MAX_EVENTS + 5 }, (_, i) => ({
      ...buildEvent(i + 1, now - 1000 + i, { source: "durable_object", type: "device_online" })!,
    }));
    const pruned = pruneEvents(events, now);
    expect(pruned.events).toHaveLength(DIAG_MAX_EVENTS);
    expect(pruned.dropped).toBe(5);
    expect(pruned.events[0]!.seq).toBe(6);
  });

  it("超过保留时长的事件会被清掉（不是被当成没发生过）", () => {
    const now = Date.now();
    const old = buildEvent(1, now - DIAG_MAX_AGE_MS - 60_000, { source: "durable_object", type: "device_online" })!;
    const fresh = buildEvent(2, now - 1000, { source: "durable_object", type: "device_online" })!;
    const pruned = pruneEvents([old, fresh], now);
    expect(pruned.events.map((e) => e.seq)).toEqual([2]);
    expect(pruned.dropped).toBe(1);
  });

  it("损坏的存储内容退化为空缓冲区，而不是让对象起不来", () => {
    expect(restoreDiagnostics(undefined)).toBeNull();
    expect(restoreDiagnostics("nope")).toBeNull();
    expect(restoreDiagnostics({ seq: 1 })).toBeNull();
    const restored = restoreDiagnostics({
      seq: 3,
      events: [{ garbage: true }, seedEvent(1, Date.now())],
      opens: { 2: 1000, bad: 5 },
      close_intents: { 2: { code: 4001, reason: "replaced_by_newer_connection", caller: "handle_connect", at: 1, generation: 2 } },
      dropped: 7,
      first_at: 1000,
    });
    expect(restored?.events).toHaveLength(1);
    expect(restored?.opens).toEqual({ "2": 1000 });
    expect(restored?.close_intents["2"]?.reason).toBe("replaced_by_newer_connection");
    expect(restored?.dropped).toBe(7);
  });
});

// ---------------------------------------------------------------------------
//  DO：真实的连接事件
// ---------------------------------------------------------------------------

describe("PhoneSession / 连接事件", () => {
  it("建连记录 device_online 与 hello，并报告在线状态与关闭码语义", async () => {
    const rig = await makeRig();
    await connectMockPhone(rig, TEST_DEVICE_ID);
    await settle();

    const { status, body } = await queryDiag(rig, TEST_DEVICE_ID);
    expect(status).toBe(200);
    expect(body.online).toBe(true);
    expect(body.socket_open).toBe(true);
    expect(body.connection_generation).toBe(1);

    const types = body.events.map((e) => e["type"]);
    expect(types).toContain("device_online");
    expect(types).toContain("hello");

    const hello = body.events.find((e) => e["type"] === "hello")!;
    expect(hello["source"]).toBe("android");
    expect(hello["detail"]).toMatchObject({ app_version: "mock-0.1.0", protocol_version: "phone-link-v1" });

    // 4001 的语义直接由云端给出，App 不需要内置一份可能过期的映射表。
    expect(body.close_codes["4001"]).toBe("replaced_by_newer_connection");
    expect(body.close_codes["4000"]).toBe("liveness_timeout");
    expect(body.close_codes["4002"]).toBe("device_credential_revoked");
  });

  it("新连接替换旧连接：记录被替换的代次，且关闭归因到 handle_connect", async () => {
    const rig = await makeRig();
    const first = await connectMockPhone(rig, TEST_DEVICE_ID);
    await settle();
    const firstGeneration = 1;

    await connectMockPhone(rig, TEST_DEVICE_ID);
    await settle();

    // 手机上看到的是 4001 —— 与任务书里的真机日志一致。
    expect(first.socket.closeInfo?.code).toBe(4001);

    const { body } = await queryDiag(rig, TEST_DEVICE_ID);
    const online = body.events.filter((e) => e["type"] === "device_online");
    expect(online).toHaveLength(2);
    expect(online[1]!["detail"]).toMatchObject({
      replaced: true,
      replaced_generation: firstGeneration,
      replaced_count: 1,
    });

    const replaced = body.events.find((e) => e["type"] === "replaced")!;
    expect(replaced).toMatchObject({
      generation: firstGeneration,
      close_code: 4001,
      close_reason: "replaced_by_newer_connection",
      server_initiated: true,
    });
    expect(replaced["detail"]).toMatchObject({ cause: "replaced", caller: "handle_connect", new_generation: 2 });
    // 存活时长能算出来就是数字；算不出（建连记录已被清理）就是 null —— 不猜。
    expect(replaced["duration_ms"] === null || typeof replaced["duration_ms"] === "number").toBe(true);

    // 服务端视角的断线：**服务端主动关闭**，原因不是"对端关的"。
    const offline = body.events.find((e) => e["type"] === "device_offline")!;
    expect(offline).toMatchObject({
      generation: firstGeneration,
      close_code: 4001,
      close_reason: "replaced_by_newer_connection",
      server_initiated: true,
    });
    expect(offline["detail"]).toMatchObject({ caller: "handle_connect" });

    expect(body.summary["replaced_events"]).toBe(1);
  });

  it("手机主动关闭（1000）：server_initiated=false，原因记 client_closed", async () => {
    const rig = await makeRig();
    const { socket } = await connectMockPhone(rig, TEST_DEVICE_ID);
    await settle();

    socket.close(1000, "phone going away");
    await settle();

    const { body } = await queryDiag(rig, TEST_DEVICE_ID);
    expect(body.online).toBe(false);
    const offline = body.events.find((e) => e["type"] === "device_offline")!;
    expect(offline).toMatchObject({
      close_code: 1000,
      close_reason: "client_closed",
      server_initiated: false,
    });
  });

  it("没有关闭帧的异常断连：close_code 如实为 null，不编造", async () => {
    const rig = await makeRig();
    const { socket } = await connectMockPhone(rig, TEST_DEVICE_ID);
    await settle();

    // 模拟 WebSocket error 路径（没有 close code）。
    // ⚠ 必须投到**服务端那一端**：`webSocketError` 是 DO 在 accept 时挂在
    //    自己这端的回调；客户端那一端的 error 事件根本到不了 DO。
    socket.peer!.dispatch("error", {});
    await settle();

    const { body } = await queryDiag(rig, TEST_DEVICE_ID);
    const offline = body.events.find((e) => e["type"] === "device_offline")!;
    expect(offline["close_code"]).toBeNull();
    expect(offline["close_reason"]).toBe("unknown");
    expect(offline["server_initiated"]).toBe(false);
  });
});

// ---------------------------------------------------------------------------
//  DO：客户端诊断上报
// ---------------------------------------------------------------------------

describe("PhoneSession / 客户端诊断上报", () => {
  it("把手机的观测写进同一时间线，保留两个时间戳", async () => {
    const rig = await makeRig();
    const { socket } = await connectMockPhone(rig, TEST_DEVICE_ID);
    await settle();

    const clientAt = Date.now() - 5_000;
    socket.send(
      JSON.stringify({
        type: "diag_event",
        event: "socket_failure",
        client_at: clientAt,
        category: "connection_reset",
        exception_class: "ProtocolException",
        generation: 1,
        network: "cellular",
        vpn: true,
        screen: "off",
        pid: 4242,
        attempt: 3,
      }),
    );
    await settle();

    const { body } = await queryDiag(rig, TEST_DEVICE_ID);
    const report = body.events.find((e) => e["type"] === "client_report")!;
    expect(report["source"]).toBe("android");
    expect(report["client_at"]).toBe(clientAt);
    expect(typeof report["at"]).toBe("number");
    expect(report["detail"]).toMatchObject({
      client_event: "socket_failure",
      category: "connection_reset",
      exception_class: "ProtocolException",
      network: "cellular",
      vpn: true,
      screen: "off",
      pid: 4242,
      attempt: 3,
      cause: "client_reported",
    });
  });

  it("自由文本与令牌形状的字段进不了缓冲区，也回不到查询响应里", async () => {
    const rig = await makeRig();
    clearCapturedLogs();
    const { socket } = await connectMockPhone(rig, TEST_DEVICE_ID);
    await settle();

    // ⚠ 这里刻意不用 `ghp_…` 之类的真实令牌前缀：仓库的安全扫描会把
    //    任何那种形状的字面量当成泄漏。用一个普通的 32 位十六进制串，
    //    它对本测试同样成立 —— 重点不是前缀，而是"任意自由文本都不该被存下来"。
    const secret = "8f14e45fceea167a5a36dedd4bea2543";
    socket.send(
      JSON.stringify({
        type: "diag_event",
        event: "socket_failure",
        category: "not_a_real_category",
        exception_class: secret,
        network: secret,
        app_version: "1.6.3",
        note: "用户的聊天内容",
        authorization: `Bearer ${secret}`,
        extra: { nested: secret },
      }),
    );
    await settle();

    const { body, raw } = await queryDiag(rig, TEST_DEVICE_ID);
    const report = body.events.find((e) => e["type"] === "client_report")!;
    expect(report["detail"]).toMatchObject({ client_event: "socket_failure", app_version: "1.6.3" });
    expect(JSON.stringify(report)).not.toContain(secret);
    expect(JSON.stringify(report)).not.toContain("聊天内容");
    expect(report["detail"]).not.toHaveProperty("category");
    expect(raw).not.toContain(secret);
  });

  it("认不出的事件名不落库（诊断通道不是任意写入入口）", async () => {
    const rig = await makeRig();
    const { socket } = await connectMockPhone(rig, TEST_DEVICE_ID);
    await settle();

    socket.send(JSON.stringify({ type: "diag_event", event: "totally_made_up" }));
    await settle();

    const { body } = await queryDiag(rig, TEST_DEVICE_ID);
    expect(body.events.some((e) => e["type"] === "client_report")).toBe(false);
  });

  it("每分钟最多接受 20 条上报，其余丢弃并计数", async () => {
    const rig = await makeRig();
    const { socket } = await connectMockPhone(rig, TEST_DEVICE_ID);
    await settle();

    for (let i = 0; i < 25; i += 1) {
      socket.send(JSON.stringify({ type: "diag_event", event: "reconnecting", attempt: i }));
    }
    await settle(20);

    const { body } = await queryDiag(rig, TEST_DEVICE_ID);
    const reports = body.events.filter((e) => e["type"] === "client_report");
    expect(reports).toHaveLength(20);

    const status = (await (
      await rig.phone.fetchOn(
        TEST_DEVICE_ID,
        new Request("https://phone-session.internal/internal/phone/status", { method: "GET" }),
      )
    ).json()) as { metrics: { diagReportsRejected: number } };
    expect(status.metrics.diagReportsRejected).toBe(5);
  });
});

// ---------------------------------------------------------------------------
//  DO：查询语义
// ---------------------------------------------------------------------------

describe("PhoneSession / 查询", () => {
  it("查询不碰主连接：查完仍能 ping 通", async () => {
    const rig = await makeRig();
    await connectMockPhone(rig, TEST_DEVICE_ID);
    await settle();

    const before = await queryDiag(rig, TEST_DEVICE_ID);
    expect(before.body.online).toBe(true);

    const outcome = await pingPhoneDirect(rig, TEST_DEVICE_ID);
    expect(outcome.ok).toBe(true);

    // 查询本身也不产生连接事件，更不会关闭 socket。
    const after = await queryDiag(rig, TEST_DEVICE_ID);
    expect(after.body.socket_open).toBe(true);
    expect(after.body.events.filter((e) => e["type"] === "device_offline")).toHaveLength(0);
  });

  it("limit 只截断返回条数，不改变缓冲区本身", async () => {
    const rig = await makeRig();
    const now = Date.now();
    const events = Array.from({ length: 30 }, (_, i) => seedEvent(i + 1, now - 30_000 + i * 100));
    const storage = rig.phone.ctxOf(TEST_DEVICE_ID).storage;
    await storage.put("diagnostics", { seq: 30, events, opens: {}, close_intents: {}, dropped: 0, first_at: events[0]!["at"] });
    rig.phone.evict(TEST_DEVICE_ID);

    const limited = await queryDiag(rig, TEST_DEVICE_ID, { limit: 5 });
    expect(limited.body.events).toHaveLength(5);
    expect(limited.body.buffer["size"]).toBe(30);

    const full = await queryDiag(rig, TEST_DEVICE_ID, { limit: 200 });
    expect(full.body.events).toHaveLength(30);
  });

  it("缓冲区有界：读回时裁剪到上限，并保留 dropped 计数", async () => {
    const rig = await makeRig();
    const now = Date.now();
    const events = Array.from({ length: DIAG_MAX_EVENTS + 12 }, (_, i) => seedEvent(i + 1, now - 60_000 + i));
    const storage = rig.phone.ctxOf(TEST_DEVICE_ID).storage;
    await storage.put("diagnostics", { seq: events.length, events, opens: {}, close_intents: {}, dropped: 0, first_at: events[0]!["at"] });
    rig.phone.evict(TEST_DEVICE_ID);

    const { body } = await queryDiag(rig, TEST_DEVICE_ID, { limit: 200 });
    expect(body.events).toHaveLength(DIAG_MAX_EVENTS);
    expect(body.buffer["dropped"]).toBe(12);
    expect(body.buffer["size"]).toBe(DIAG_MAX_EVENTS);
  });

  it("过期事件在读取时清理，查询结果说明丢弃了多少", async () => {
    const rig = await makeRig();
    const now = Date.now();
    const events = [
      seedEvent(1, now - DIAG_MAX_AGE_MS - 120_000),
      seedEvent(2, now - DIAG_MAX_AGE_MS - 60_000),
      seedEvent(3, now - 1000),
    ];
    const storage = rig.phone.ctxOf(TEST_DEVICE_ID).storage;
    await storage.put("diagnostics", { seq: 3, events, opens: {}, close_intents: {}, dropped: 0, first_at: events[0]!["at"] });
    rig.phone.evict(TEST_DEVICE_ID);

    const { body } = await queryDiag(rig, TEST_DEVICE_ID);
    expect(body.events.map((e) => e["seq"])).toEqual([3]);
    expect(body.buffer["dropped"]).toBe(2);
  });

  it("诊断缓冲区跨对象驱逐保留", async () => {
    const rig = await makeRig();
    await connectMockPhone(rig, TEST_DEVICE_ID);
    await settle();
    rig.phone.evict(TEST_DEVICE_ID);

    const { body } = await queryDiag(rig, TEST_DEVICE_ID);
    expect(body.events.some((e) => e["type"] === "device_online")).toBe(true);
    // 驱逐后没有活 socket —— 如实报告离线，而不是沿用旧状态。
    expect(body.online).toBe(false);
  });

  it("设备头与 DO 实例不一致时拒绝（防止转发错设备）", async () => {
    const rig = await makeRig();
    const { status } = await queryDiag(rig, TEST_DEVICE_ID, { deviceHeader: TEST_DEVICE_ID_2 });
    expect(status).toBe(409);
  });
});

// ---------------------------------------------------------------------------
//  DO：认证失败上报
// ---------------------------------------------------------------------------

describe("PhoneSession / 认证失败上报", () => {
  it("记录一次认证失败，10 秒内重复上报被去重", async () => {
    const rig = await makeRig();
    const post = () =>
      rig.phone.fetchOn(
        TEST_DEVICE_ID,
        new Request("https://phone-session.internal/internal/phone/diagnostics", {
          method: "POST",
          headers: { [INTERNAL_DEVICE]: TEST_DEVICE_ID, "Content-Type": "application/json" },
          body: JSON.stringify({ auth_reason: "token_mismatch", auth_scope: "device", auth_source: "worker" }),
        }),
      );

    const first = (await (await post()).json()) as { recorded: boolean };
    const second = (await (await post()).json()) as { recorded: boolean };
    expect(first.recorded).toBe(true);
    expect(second.recorded).toBe(false);

    const { body } = await queryDiag(rig, TEST_DEVICE_ID);
    const failures = body.events.filter((e) => e["type"] === "auth_failure");
    expect(failures).toHaveLength(1);
    expect(failures[0]).toMatchObject({ source: "worker" });
    expect(failures[0]!["detail"]).toMatchObject({ auth_reason: "token_mismatch", auth_source: "worker" });
  });

  it("伪造的 auth_reason 进不了缓冲区", async () => {
    const rig = await makeRig();
    await rig.phone.fetchOn(
      TEST_DEVICE_ID,
      new Request("https://phone-session.internal/internal/phone/diagnostics", {
        method: "POST",
        headers: { [INTERNAL_DEVICE]: TEST_DEVICE_ID, "Content-Type": "application/json" },
        body: JSON.stringify({ auth_reason: "token=abc", auth_scope: "device", auth_source: "worker" }),
      }),
    );
    const { body } = await queryDiag(rig, TEST_DEVICE_ID);
    const failure = body.events.find((e) => e["type"] === "auth_failure")!;
    expect(failure["detail"]).not.toHaveProperty("auth_reason");
  });
});

// ---------------------------------------------------------------------------
//  Worker 入口：鉴权与设备隔离
// ---------------------------------------------------------------------------

describe("Worker / 诊断入口鉴权", () => {
  it("未认证读不到任何诊断", async () => {
    const rig = await makeRig();
    await connectMockPhone(rig, TEST_DEVICE_ID);
    await settle();

    const noAuth = await call(
      rig,
      new Request("https://android-mcp-hub.test/phone/diagnostics", { method: "GET", headers: { "X-Device-Id": TEST_DEVICE_ID } }),
    );
    expect(noAuth.status).toBe(401);
    expect(await noAuth.text()).not.toContain("device_online");

    const noDevice = await call(
      rig,
      new Request("https://android-mcp-hub.test/phone/diagnostics", {
        method: "GET",
        headers: { Authorization: `Bearer ${TEST_DEVICE_TOKEN}` },
      }),
    );
    expect(noDevice.status).toBe(401);
  });

  it("令牌错 → 401，且响应里没有任何事件", async () => {
    const rig = await makeRig();
    await connectMockPhone(rig, TEST_DEVICE_ID);
    await settle();

    const res = await call(rig, diagRequest(TEST_DEVICE_ID, "wrong-token"));
    expect(res.status).toBe(401);
    const text = await res.text();
    expect(text).not.toContain("device_online");
    expect(text).not.toContain("events");
  });

  it("设备 A 的令牌打不开设备 B 的诊断", async () => {
    const rig = await makeRig();
    await connectMockPhone(rig, TEST_DEVICE_ID_2);
    await settle();

    // A 的令牌 + B 的设备 ID：在 B 的凭据表里找不到 A 的令牌 → 401。
    const cross = await call(rig, diagRequest(TEST_DEVICE_ID_2, TEST_DEVICE_TOKEN));
    expect(cross.status).toBe(401);
    expect(await cross.text()).not.toContain("device_online");

    // B 自己的令牌才能读到 B 的事件。
    const own = await call(rig, diagRequest(TEST_DEVICE_ID_2, TEST_DEVICE_TOKEN_2));
    expect(own.status).toBe(200);
    const body = (await own.json()) as DiagBody;
    expect(body.device_id).toBe(TEST_DEVICE_ID_2);
    expect(body.events.some((e) => e["type"] === "device_online")).toBe(true);
  });

  it("已认证设备只读自己的缓冲区", async () => {
    const rig = await makeRig();
    await connectMockPhone(rig, TEST_DEVICE_ID);
    await connectMockPhone(rig, TEST_DEVICE_ID_2);
    await settle();

    const res = await call(rig, diagRequest(TEST_DEVICE_ID, TEST_DEVICE_TOKEN));
    expect(res.status).toBe(200);
    expect(res.headers.get("Cache-Control")).toBe("no-store");
    const body = (await res.json()) as DiagBody;
    expect(body.device_id).toBe(TEST_DEVICE_ID);
    expect(body.events.length).toBeGreaterThan(0);
  });

  it("未知设备不会被查询请求创建成 DO", async () => {
    const rig = await makeRig();
    const before = rig.phone.names();
    const res = await call(rig, diagRequest("never-configured-device", "whatever-token"));
    expect(res.status).toBe(401);
    expect(rig.phone.names()).toEqual(before);
    expect(before).not.toContain("never-configured-device");
  });

  it("只接受 GET：其他方法 405，且不读缓冲区", async () => {
    const rig = await makeRig();
    const res = await call(rig, diagRequest(TEST_DEVICE_ID, TEST_DEVICE_TOKEN, { method: "POST" }));
    expect(res.status).toBe(405);
    expect(res.headers.get("Allow")).toBe("GET");
  });

  it("没有公开的 /logs 路径", async () => {
    const rig = await makeRig();
    const res = await call(rig, new Request("https://android-mcp-hub.test/logs", { method: "GET" }));
    expect(res.status).toBe(404);
  });
});
