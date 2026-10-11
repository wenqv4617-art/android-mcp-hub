// ============================================================================
//  Gate 1.5 工作包 A —— 凭据撤销 + 服务端在线存活判定
// ============================================================================
//
//  阶段 1A 的报告把两件事记为 P1 已确认缺陷（`CONFIRMED_DEFECT`）：
//
//    D-1  撤销凭据不会终止**已建立**的连接：撤销之后新升级会被 401，
//         但那台已经连上的 socket 仍然 `readyState === 1`、`connected === true`，
//         撤销之后新发的请求**依然成功**。机制：撤销只在升级那一刻检查
//         （`worker/src/index.ts` → `activeCredentialsFor`），而 DO 根本
//         没有"撤销"这个输入。
//
//    D-2  半开连接在线状态误报：应用层完全静默、TCP 还开着的手机仍被报成
//         `connected === true`，请求要拖满 `timeout_ms` 才拿到 `phone_timeout`，
//         而不是快速 `phone_offline`。机制：`currentSocket()` 只看"当前代次
//         有没有已注册的 socket"，既不看 `readyState`，也不看最后一次收到帧的时间。
//
//  这个文件逐条证明两件事都修好了，而且**没有过度修正**：
//
//    · 撤销之后：新请求被拒、已建立的连接被关、在途请求显式失败、
//      撤销状态跨对象重建仍然生效、reset 不是绕过撤销的后门、
//      **别的设备毫发无伤**。
//    · 静默之后：`connected` 变 false、请求快速失败为 `phone_offline`、
//      在途请求被显式结算、而"还活着的连接"绝不会被早响的 alarm 误杀。
//
//  ⚠ 这个文件跑在 Node 上的 Workers 运行时**替身**里，不是真实 workerd。
//     真实驱逐的语义差异见阶段 1A 报告的 R-3。
// ============================================================================

import { describe, expect, it } from "vitest";
import {
  connectMockPhone,
  framesOfType,
  makeRig,
  phoneStatus,
  pingPhoneDirect,
  revokePhoneDirect,
  settle,
  TEST_DEVICE_ID,
  TEST_DEVICE_ID_2,
  toolPhoneDirect,
  upgradePhoneDirect,
} from "./harness/env";
import { readFileSync } from "node:fs";
import { join } from "node:path";

const DEVICE = TEST_DEVICE_ID;
const OTHER_DEVICE = TEST_DEVICE_ID_2;

/** 1 秒是本仓库 `CLAMPS.livenessTimeoutMs` 的下限 —— 测试用它把等待压到最短。 */
const TIGHT_LIVENESS_MS = 1_000;

function sleep(ms: number): Promise<void> {
  return new Promise((r) => setTimeout(r, ms));
}

// ---------------------------------------------------------------------------
//  A1 凭据撤销
// ---------------------------------------------------------------------------

describe("Gate 1.5 / A1 凭据撤销", () => {
  it("撤销之后新升级被拒：DO 记住撤销状态，403 device_revoked，且计入 upgradesRejected", async () => {
    const rig = await makeRig();
    const first = await connectMockPhone(rig, DEVICE);
    expect(first.response.status).toBe(101);

    const revoked = await revokePhoneDirect(rig, DEVICE);
    expect(revoked.status).toBe(200);
    expect(revoked.body["ok"]).toBe(true);
    expect(revoked.body["revoked"]).toBe(true);

    // 撤销之后同一台设备再升级 —— 必须被 DO 自己挡掉，而不是靠 Worker 的 401。
    // （用 `upgradePhoneDirect`：被拒的响应里根本没有 socket，摸 webSocket 会崩。）
    const second = await upgradePhoneDirect(rig, DEVICE);
    expect(second.status).toBe(403);
    const body = (await second.json()) as Record<string, unknown>;
    expect(body["error"]).toBe("device_revoked");

    const status = await phoneStatus(rig, DEVICE);
    const metrics = status["metrics"] as Record<string, number>;
    expect(metrics["revocationsApplied"]).toBe(1);
    expect(metrics["upgradesRejected"]).toBe(1);
  });

  it("撤销会主动关掉**已建立**的连接（D-1 的核心）：先收到一条 error，再以 4002 关闭", async () => {
    const rig = await makeRig();
    const { socket } = await connectMockPhone(rig, DEVICE);
    expect(socket.readyState).toBe(1);

    await revokePhoneDirect(rig, DEVICE);
    await settle();

    // 顺序是有意的：先让手机知道原因，再关。反过来的话手机只会看到一个
    // 不明所以的 4002，日志里没法归因。
    const errors = framesOfType(socket, "error");
    expect(errors.length).toBe(1);
    expect(errors[0]!["code"]).toBe("device_revoked");

    expect(socket.readyState).toBe(3);
    expect(socket.closeInfo?.code).toBe(4002);

    // 撤销之后连"在线"都不该报。
    const status = await phoneStatus(rig, DEVICE);
    expect(status["connected"]).toBe(false);
    expect(status["revoked"]).toBe(true);
  });

  it("在途请求被明确结算为 device_revoked，绝不虚假成功，pending 与镜像一起清零", async () => {
    const rig = await makeRig();
    // 手机收到 ping 但不回应 —— 这样撤销发生时它必然还在途。
    await connectMockPhone(rig, DEVICE, "no-response");

    const inflight = pingPhoneDirect(rig, DEVICE, { timeoutMs: 30_000 });
    await settle();

    const before = await phoneStatus(rig, DEVICE);
    expect(before["pending_requests"]).toBe(1);
    expect((before["metrics"] as Record<string, number>)["pingsSent"]).toBe(1);

    await revokePhoneDirect(rig, DEVICE);
    const outcome = await inflight;

    expect(outcome.ok).toBe(false);
    if (outcome.ok) throw new Error("不该成功");
    expect(outcome.error.code).toBe("device_revoked");
    // 不是"等它超时" —— 撤销必须立刻结算，否则调用方要白等 30 秒。
    expect(outcome.error.message).toContain("撤销");

    const after = await phoneStatus(rig, DEVICE);
    expect(after["pending_requests"]).toBe(0);
    expect(after["pending_mirror"]).toBe(0);
    expect((after["metrics"] as Record<string, number>)["pendingRevoked"]).toBe(1);
    // ✅ 关键阴性断言：超时计数**不能**涨。涨了说明它是被"超时"结算的，
    //    也就是撤销其实没起作用，只是恰好赶在超时前后。
    expect((after["metrics"] as Record<string, number>)["timeouts"]).toBe(0);
  });

  it("撤销之后的新请求被拒（403 + requestsRejectedRevoked），不会落到那台已死的连接上", async () => {
    const rig = await makeRig();
    await connectMockPhone(rig, DEVICE);
    await revokePhoneDirect(rig, DEVICE);

    const outcome = await pingPhoneDirect(rig, DEVICE, { timeoutMs: 1_000 });
    expect(outcome.ok).toBe(false);
    if (outcome.ok) throw new Error("不该成功");
    // 必须报"被撤销"而不是"离线"：把撤销藏成 phone_offline 会让运维
    // 一直去查网络，永远查不到真正的原因。
    expect(outcome.error.code).toBe("device_revoked");

    const status = await phoneStatus(rig, DEVICE);
    expect((status["metrics"] as Record<string, number>)["requestsRejectedRevoked"]).toBe(1);
  });

  it("撤销一台设备**不影响**另一台：它的连接、状态、请求全部照常", async () => {
    const rig = await makeRig();
    const victim = await connectMockPhone(rig, DEVICE, "no-response");
    const bystander = await connectMockPhone(rig, OTHER_DEVICE);

    await revokePhoneDirect(rig, DEVICE);
    await settle();

    expect(victim.socket.readyState).toBe(3);

    // 邻座必须毫发无伤。
    expect(bystander.socket.readyState).toBe(1);
    const status = await phoneStatus(rig, OTHER_DEVICE);
    expect(status["connected"]).toBe(true);
    expect(status["revoked"]).toBe(false);
    expect((status["metrics"] as Record<string, number>)["revocationsApplied"]).toBe(0);

    const { phone } = await connectMockPhone(rig, OTHER_DEVICE, "normal");
    void phone;
    const outcome = await pingPhoneDirect(rig, OTHER_DEVICE, { timeoutMs: 2_000 });
    expect(outcome.ok).toBe(true);
  });

  it("撤销状态落盘、跨对象重建仍然生效（不依赖 DO 自动感知 Secret 变化）", async () => {
    const rig = await makeRig();
    await connectMockPhone(rig, DEVICE);
    await revokePhoneDirect(rig, DEVICE);
    expect(rig.phone.constructionCount(DEVICE)).toBe(1);

    // 模拟驱逐：内存全丢，存储保留。
    rig.phone.evict(DEVICE);
    expect(rig.phone.constructionCount(DEVICE)).toBe(1);

    const status = await phoneStatus(rig, DEVICE);
    expect(rig.phone.constructionCount(DEVICE)).toBe(2);
    expect(status["revoked"]).toBe(true);

    const reconnect = await upgradePhoneDirect(rig, DEVICE);
    expect(reconnect.status).toBe(403);
  });

  it("testing/reset **不能**清掉撤销状态（否则它就是一条绕过撤销的后门）", async () => {
    const rig = await makeRig();
    await connectMockPhone(rig, DEVICE);
    await revokePhoneDirect(rig, DEVICE);

    const reset = await rig.phone.fetchOn(DEVICE, new Request(`https://phone-session.internal${"/internal/phone/testing/reset"}`, { method: "POST" }));
    expect(reset.status).toBe(200);
    const body = (await reset.json()) as Record<string, unknown>;
    expect(body["revoked"]).toBe(true);

    const status = await phoneStatus(rig, DEVICE);
    expect(status["revoked"]).toBe(true);

    const reconnect = await upgradePhoneDirect(rig, DEVICE);
    expect(reconnect.status).toBe(403);
  });

  it("重复撤销是幂等的：指标只加一次，连接不会被重复关", async () => {
    const rig = await makeRig();
    const { socket } = await connectMockPhone(rig, DEVICE);

    await revokePhoneDirect(rig, DEVICE);
    const second = await revokePhoneDirect(rig, DEVICE);
    expect(second.status).toBe(200);
    expect(socket.readyState).toBe(3);

    expect(second.body["already_revoked"]).toBe(true);
    expect(second.body["closed"]).toBe(0);

    const status = await phoneStatus(rig, DEVICE);
    // ⚠ 这里断言的是"幂等"，即第二次调用不会让计数继续涨。
    //    撤销通知可能被重复投递（每个被拒的升级都推一条），所以重复必须无害。
    expect((status["metrics"] as Record<string, number>)["revocationsApplied"]).toBe(1);
  });

  it("撤销后的迟到 pong/tool_result 落在过期代次上，不能结算任何东西", async () => {
    const rig = await makeRig();
    const { socket } = await connectMockPhone(rig, DEVICE, "no-response");

    const state = rig.phone.ctxOf(DEVICE);
    const gen1ServerSocket = state.getWebSockets("gen:1")[0]!;

    const inflight = pingPhoneDirect(rig, DEVICE, { timeoutMs: 10_000 });
    await settle();
    const beforeStatus = await phoneStatus(rig, DEVICE);
    expect(beforeStatus["pending_requests"]).toBe(1);

    await revokePhoneDirect(rig, DEVICE);
    const outcome = await inflight;
    expect(outcome.ok).toBe(false);

    // 撤销换代次之后，旧连接上迟到的 pong 必须落到"代次不符"这条分支上。
    // 白盒注入：真实场景里这条帧的 socket 已经被关掉了，但网络里的字节还在飞。
    const instance = rig.phone.instanceFor(DEVICE);
    await instance.webSocketMessage?.(
      gen1ServerSocket as never,
      JSON.stringify({ type: "phone_pong", request_id: "req-revoked-1", timestamp: Date.now() }),
    );
    await settle();
    void socket;

    const status = await phoneStatus(rig, DEVICE);
    const metrics = status["metrics"] as Record<string, number>;
    // 撤销后 socket 已关，代次守卫与"没有当前 socket"都会让它进 pongsUnknown
    // 或 pongsStaleGeneration —— 两者都行，唯独不能是 matched。
    expect(metrics["pongsMatched"]).toBe(0);
    expect(status["pending_requests"]).toBe(0);
  });

  it("撤销通知自己带上设备身份：deviceId 对不上时拒绝执行（409）", async () => {
    const rig = await makeRig();
    await connectMockPhone(rig, DEVICE);

    // 直接往 DEVICE 的 DO 上发一条"撤销 OTHER_DEVICE"的请求。
    // 真实链路上 Worker 只会撤销它刚查出来已撤销的那台，但 DO 不该依赖调用方诚实。
    const response = await rig.phone.fetchOn(
      DEVICE,
      new Request("https://phone-session.internal/internal/phone/revoke", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ deviceId: OTHER_DEVICE }),
      }),
    );
    expect(response.status).toBe(409);
    const body = (await response.json()) as Record<string, unknown>;
    expect(body["error"]).toBe("device_mismatch");

    const status = await phoneStatus(rig, DEVICE);
    expect(status["revoked"]).toBe(false);
  });
});

// ---------------------------------------------------------------------------
//  A1 · 传输层（Worker 侧）：撤销设备的新请求必须在**进入 DO 之前**就被拒
// ---------------------------------------------------------------------------

describe("Gate 1.5 / A1 传输层：撤销之后的工具调用", () => {
  it("撤销的设备：工具调用被拒为 device_revoked，并顺带推一条撤销通知给 DO", async () => {
    const rig = await makeRig({
      deviceTokens: [{ deviceId: DEVICE, token: "gate2-local-test-device-token-not-a-secret", revokedAt: "2026-01-02T00:00:00Z" }],
    });

    const { dispatchToolCall } = await import("../src/mcp/tools");
    const config = await import("../src/config").then((m) => m.loadConfig(rig.env));
    const dispatched = await dispatchToolCall(config, "phone_ping", { device_id: DEVICE }, async () => {
      throw new Error("绝不该走到转发：设备已撤销");
    });

    expect(dispatched.result?.isError).toBe(true);
    const structured = dispatched.result?.structuredContent as Record<string, unknown>;
    expect(structured["ok"]).toBe(false);
    expect(structured["error_code"]).toBe("device_revoked");
    // ✅ 这一条是给传输层的信号：只**报告**，副作用留给传输层做。
    expect(dispatched.revokedDeviceId).toBe(DEVICE);
  });

  it("没被撤销的设备：同样的调用照常转发（证明上面那条不是无条件拒绝）", async () => {
    const rig = await makeRig();
    const { dispatchToolCall } = await import("../src/mcp/tools");
    const config = await import("../src/config").then((m) => m.loadConfig(rig.env));

    let forwarded = 0;
    const dispatched = await dispatchToolCall(config, "phone_ping", { device_id: DEVICE }, async (deviceId, timeoutMs) => {
      forwarded += 1;
      void deviceId;
      void timeoutMs;
      return {
        ok: true,
        request_id: "r1",
        device_id: DEVICE,
        rtt_ms: 1,
        server_timestamp: 1,
        device_timestamp: 1,
        connection_generation: 1,
      };
    });

    expect(forwarded).toBe(1);
    expect(dispatched.revokedDeviceId).toBeUndefined();
    expect(dispatched.result?.isError).toBeUndefined();
  });

  it("**所有**凭据都被撤销的设备，即便不传 device_id 也不会被推断出来（D-1 的根因）", async () => {
    const rig = await makeRig({
      deviceTokens: [{ deviceId: DEVICE, token: "gate2-local-test-device-token-not-a-secret", revokedAt: "2026-01-02T00:00:00Z" }],
    });
    const config = await import("../src/config").then((m) => m.loadConfig(rig.env));
    const { resolveDeviceId } = await import("../src/config");

    // 旧实现里这一档用的是 `config.credentials` 全表（不筛 revokedAt），
    // 于是"唯一一台设备被撤销之后，不传 device_id 的请求依然会被推断到它身上"。
    const resolved = resolveDeviceId(config, undefined);
    expect(resolved.ok).toBe(false);
    if (resolved.ok) throw new Error("不该解析成功");
    expect(resolved.code).toBe("device_revoked");
    expect(resolved.deviceId).toBe(DEVICE);
  });
});

// ---------------------------------------------------------------------------
//  A2 在线存活判定
// ---------------------------------------------------------------------------

describe("Gate 1.5 / A2 半开连接与在线存活判定", () => {
  it("应用层静默超过阈值后：connected=false，但 socket_open 仍为 true（半开被如实描述）", async () => {
    const rig = await makeRig({ vars: { PHONE_LIVENESS_TIMEOUT_MS: String(TIGHT_LIVENESS_MS) } });
    const { socket } = await connectMockPhone(rig, DEVICE);
    expect(socket.readyState).toBe(1);

    const fresh = await phoneStatus(rig, DEVICE);
    expect(fresh["connected"]).toBe(true);
    expect(fresh["stale"]).toBe(false);
    expect(fresh["liveness_timeout_ms"]).toBe(TIGHT_LIVENESS_MS);

    await sleep(TIGHT_LIVENESS_MS + 150);

    const stale = await phoneStatus(rig, DEVICE);
    // ✅ 核心修正：`connected` 不再是"有没有 socket"，而是"这条 socket 还活着吗"。
    expect(stale["connected"]).toBe(false);
    expect(stale["socket_open"]).toBe(true);
    expect(stale["stale"]).toBe(true);
    expect((stale["metrics"] as Record<string, number>)["staleStatusReads"]).toBeGreaterThanOrEqual(1);
  });

  it("半开连接上的请求**快速**失败为 phone_offline，而不是拖满 timeout_ms（D-2 的核心）", async () => {
    const rig = await makeRig({ vars: { PHONE_LIVENESS_TIMEOUT_MS: String(TIGHT_LIVENESS_MS) } });
    await connectMockPhone(rig, DEVICE);
    await sleep(TIGHT_LIVENESS_MS + 150);

    const started = Date.now();
    const outcome = await pingPhoneDirect(rig, DEVICE, { timeoutMs: 10_000 });
    const elapsed = Date.now() - started;

    expect(outcome.ok).toBe(false);
    if (outcome.ok) throw new Error("不该成功");
    expect(outcome.error.code).toBe("phone_offline");
    // 10 秒的超时预算，实际必须在很小的一段时间内失败。留 2 秒余量足够宽。
    expect(elapsed).toBeLessThan(2_000);

    const status = await phoneStatus(rig, DEVICE);
    expect((status["metrics"] as Record<string, number>)["livenessTimeouts"]).toBeGreaterThanOrEqual(1);
  });

  it("判定链路死亡时，在途请求被显式结算为 phone_disconnected，绝不虚假成功", async () => {
    const rig = await makeRig({ vars: { PHONE_LIVENESS_TIMEOUT_MS: String(TIGHT_LIVENESS_MS) } });
    // 这台手机对自己的 ping 不回应，好让请求留在途。
    await connectMockPhone(rig, DEVICE, "no-response");

    const inflight = pingPhoneDirect(rig, DEVICE, { timeoutMs: 30_000 });
    await settle();

    // ⚠ 这里刻意**不是**"再发一个请求去触发判定" —— 那样只能证明新请求被拒，
    //    证明不了在途的那个被结算。用 alarm 触发（rescheduleAlarm 会把
    //    `lastPhoneActivityAt + livenessTimeoutMs` 也排进去）。
    const instance = rig.phone.instanceFor(DEVICE);
    await sleep(TIGHT_LIVENESS_MS + 150);
    await instance.alarm?.();
    await settle();

    const outcome = await inflight;
    expect(outcome.ok).toBe(false);
    if (outcome.ok) throw new Error("不该成功");
    expect(outcome.error.code).toBe("phone_disconnected");

    const status = await phoneStatus(rig, DEVICE);
    const metrics = status["metrics"] as Record<string, number>;
    expect(status["pending_requests"]).toBe(0);
    expect(status["pending_mirror"]).toBe(0);
    expect(metrics["livenessTimeouts"]).toBeGreaterThanOrEqual(1);
    // 早响的 alarm 不等于超时：这条请求是被"链路已死"结算的。
    expect(metrics["timeouts"]).toBe(0);
  });

  it("**阴性对照**：活着的连接不会被提前响的 alarm 误杀", async () => {
    const rig = await makeRig({ vars: { PHONE_LIVENESS_TIMEOUT_MS: String(TIGHT_LIVENESS_MS) } });
    const { socket, phone } = await connectMockPhone(rig, DEVICE, "normal");

    // 立刻手动跑一次 alarm（此时距离上次活动几乎为 0）。
    const instance = rig.phone.instanceFor(DEVICE);
    await instance.alarm?.();
    await settle();

    expect(socket.readyState).toBe(1);
    const status = await phoneStatus(rig, DEVICE);
    expect(status["connected"]).toBe(true);
    expect((status["metrics"] as Record<string, number>)["livenessTimeouts"]).toBe(0);

    // 而且它还能正常干活。
    const outcome = await pingPhoneDirect(rig, DEVICE, { timeoutMs: 2_000 });
    expect(outcome.ok).toBe(true);
    expect(phone.received.length).toBeGreaterThan(0);
  });

  it("任何**合法的**上行帧都会刷新存活时间（心跳确实在续命）", async () => {
    const rig = await makeRig({ vars: { PHONE_LIVENESS_TIMEOUT_MS: String(TIGHT_LIVENESS_MS) } });
    const { socket } = await connectMockPhone(rig, DEVICE);

    // 分两段睡，每段都不到阈值，中间塞一条 heartbeat。
    await sleep(Math.floor(TIGHT_LIVENESS_MS / 2));
    // ⚠ 方向：`socket` 是**手机这一端**，所以要用 `send` 把帧发给服务端。
    //     `deliver` 是把帧投给自己，那样服务端根本收不到（这条一开始就写错过）。
    socket.send(JSON.stringify({ type: "heartbeat", timestamp: Date.now() }));
    await settle();
    await sleep(Math.floor(TIGHT_LIVENESS_MS / 2) + 100);

    // 累计已经超过阈值，但因为在中间收到了合法帧，连接必须仍然算活着。
    const status = await phoneStatus(rig, DEVICE);
    expect(status["connected"]).toBe(true);
    expect(status["stale"]).toBe(false);
  });

  it("垃圾帧**不**刷新存活时间（那正是更该判死的情形）", async () => {
    const rig = await makeRig({ vars: { PHONE_LIVENESS_TIMEOUT_MS: String(TIGHT_LIVENESS_MS) } });
    const { socket } = await connectMockPhone(rig, DEVICE);

    await sleep(Math.floor(TIGHT_LIVENESS_MS / 2));
    socket.send("这不是 JSON");
    await settle();
    await sleep(Math.floor(TIGHT_LIVENESS_MS / 2) + 100);

    const status = await phoneStatus(rig, DEVICE);
    const metrics = status["metrics"] as Record<string, number>;
    expect(metrics["messagesRejected"]).toBeGreaterThanOrEqual(1);
    // 如果垃圾帧也算"活动"，这里就会是 true —— 那等于"只要一直发垃圾就不掉线"。
    expect(status["connected"]).toBe(false);
  });

  it("链路死了之后重连可以自愈：新的代次重新起算存活时间", async () => {
    const rig = await makeRig({ vars: { PHONE_LIVENESS_TIMEOUT_MS: String(TIGHT_LIVENESS_MS) } });
    await connectMockPhone(rig, DEVICE);
    await sleep(TIGHT_LIVENESS_MS + 150);

    expect((await phoneStatus(rig, DEVICE))["connected"]).toBe(false);

    const { response } = await connectMockPhone(rig, DEVICE, "normal");
    expect(response.status).toBe(101);

    const status = await phoneStatus(rig, DEVICE);
    expect(status["connected"]).toBe(true);
    expect(status["stale"]).toBe(false);
    expect(status["connection_generation"]).toBe(2);

    const outcome = await pingPhoneDirect(rig, DEVICE, { timeoutMs: 2_000 });
    expect(outcome.ok).toBe(true);
  });

  it("断开连接会清零活动时间：不会在重连的瞬间被读成 stale 的纯误报", async () => {
    const rig = await makeRig({ vars: { PHONE_LIVENESS_TIMEOUT_MS: String(TIGHT_LIVENESS_MS) } });
    const { socket, state } = await connectMockPhone(rig, DEVICE);

    socket.close(1006, "gone");
    await state.settle();

    const status = await phoneStatus(rig, DEVICE);
    expect(status["connected"]).toBe(false);
    expect(status["socket_open"]).toBe(false);
    // 没有 socket 的时候不该记 staleStatusReads —— "半开"和"没连"是两回事。
    expect((status["metrics"] as Record<string, number>)["staleStatusReads"]).toBe(0);
  });
});

// ---------------------------------------------------------------------------
//  A2 · 通用工具通道也遵守同一套存活判定
// ---------------------------------------------------------------------------

describe("Gate 1.5 / A2 工具通道上的存活判定", () => {
  it("半开连接上的 tool_request 快速失败为 phone_offline", async () => {
    const rig = await makeRig({ vars: { PHONE_LIVENESS_TIMEOUT_MS: String(TIGHT_LIVENESS_MS) } });
    await connectMockPhone(rig, DEVICE);
    await sleep(TIGHT_LIVENESS_MS + 150);

    const started = Date.now();
    const outcome = await toolPhoneDirect(rig, DEVICE, { toolName: "battery_get_status", timeoutMs: 10_000 });
    const elapsed = Date.now() - started;

    expect(outcome.ok).toBe(false);
    if (outcome.ok) throw new Error("不该成功");
    expect(outcome.error.code).toBe("phone_offline");
    expect(elapsed).toBeLessThan(2_000);
  });

  it("撤销后的 tool_request 被拒为 device_revoked（不是 phone_offline）", async () => {
    const rig = await makeRig();
    await connectMockPhone(rig, DEVICE);
    await revokePhoneDirect(rig, DEVICE);

    const outcome = await toolPhoneDirect(rig, DEVICE, { toolName: "battery_get_status", timeoutMs: 1_000 });
    expect(outcome.ok).toBe(false);
    if (outcome.ok) throw new Error("不该成功");
    expect(outcome.error.code).toBe("device_revoked");
  });
});

// ---------------------------------------------------------------------------
//  代码级守卫：撤销入口**不得**被映射到任何公网路由
// ---------------------------------------------------------------------------

describe("Gate 1.5 / A1 撤销入口的公网可见性", () => {
  it("ROUTE_REVOKE 只出现在受保护的 DO 内部路径里，没有任何公网 fetch 分支引用它", () => {
    const root = join(__dirname, "..", "..");
    const indexSource = readFileSync(join(root, "worker", "src", "index.ts"), "utf8");

    // `worker/src/index.ts` 的 fetch 是唯一把公网 URL 映射到内部的地方。
    // 它**不允许**出现撤销路由的字面量：撤销只能由 Worker 内部主动调用。
    expect(indexSource).not.toContain("/internal/phone/revoke");
    // 但它必须真的会去推撤销通知（那条链路是 index.ts → http.ts → DO）。
    expect(indexSource).toContain("revokePhoneDevice");
  });
});
