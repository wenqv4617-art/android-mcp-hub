// ============================================================================
//  PhoneSession Durable Object —— 状态机与竞态测试
// ============================================================================
//
//  任务书「七、测试要求 / Durable Object」列了 10 条，这个文件逐条对应：
//
//    1. 正常 ping/pong                             → "正常 ping/pong"
//    2. 请求超时                                   → "超时"
//    3. 错误 request_id                            → "错误的 request_id"
//    4. 重复 pong                                  → "重复 pong"
//    5. WebSocket 意外断开                          → "意外断开"
//    6. 断线后重新连接                              → "断线后重连"
//    7. 旧连接响应被拒绝                            → "旧连接的响应"
//    8. 并发请求正确关联                            → "并发请求"
//    9. 超出并发上限时正确失败                      → "并发上限"
//   10. 对象重建后不产生虚假成功响应                → "对象重建"
//
//  ⚠ 这些测试跑在 Node 上的 Workers 运行时**替身**里，不是真实 workerd。
//     见 worker/test/harness/runtime.ts 顶部说明与 TEST_REPORT.md 的 NOT RUN 小节。
// ============================================================================

import { describe, expect, it } from "vitest";
import {
  connectMockPhone,
  framesOfType,
  makeRig,
  parseFrame,
  PHONE_ROUTES,
  phoneStatus,
  pingPhoneDirect,
  settle,
  TEST_DEVICE_ID,
  TEST_DEVICE_ID_2,
} from "./harness/env";
import type { FakeDurableObjectStorage } from "./harness/runtime";

const DEVICE = TEST_DEVICE_ID;

describe("PhoneSession / 连接与鉴权", () => {
  it("升级成功后立刻下发 welcome，并带上协商好的运行参数", async () => {
    const rig = await makeRig();
    const { socket, response } = await connectMockPhone(rig, DEVICE);

    expect(response.status).toBe(101);

    const welcomes = framesOfType(socket, "welcome");
    expect(welcomes.length).toBe(1);
    expect(welcomes[0]!["device_id"]).toBe(DEVICE);
    expect(welcomes[0]!["connection_generation"]).toBe(1);
    expect(welcomes[0]!["protocol_version"]).toBe("phone-link-v1");
    expect(welcomes[0]!["ping_timeout_ms"]).toBe(10_000);
    expect(welcomes[0]!["max_pending_requests"]).toBe(16);
  });

  it("没有内部标记头的升级请求被拒绝（500），且计入 upgradesRejected", async () => {
    const rig = await makeRig();
    const response = await rig.phone.fetchOn(
      DEVICE,
      new Request("https://phone-session.internal/internal/phone/connect", {
        method: "GET",
        headers: { Upgrade: "websocket" },
      }),
    );
    expect(response.status).toBe(500);

    const status = await phoneStatus(rig, DEVICE);
    expect((status["metrics"] as Record<string, number>)["upgradesRejected"]).toBe(1);
    expect(status["connected"]).toBe(false);
  });

  it("非 WebSocket 的升级请求被拒绝（426）", async () => {
    const rig = await makeRig();
    const response = await rig.phone.fetchOn(
      DEVICE,
      new Request("https://phone-session.internal/internal/phone/connect", {
        method: "GET",
        headers: { "X-Phone-Session-Device": DEVICE },
      }),
    );
    expect(response.status).toBe(426);
  });

  it("手机上报的 hello 会被记录，device_id 不符只记账不断开", async () => {
    const rig = await makeRig();
    const logs: string[] = [];
    const { phone } = await connectMockPhone(rig, DEVICE, "hello-mismatch", {
      onLog: (l) => logs.push(l),
    });
    await settle();

    expect(phone.helloSeen).toBe(true);
    // 连接仍然在：不因为它自报了一个别的 device_id 就踢掉它。
    const status = await phoneStatus(rig, DEVICE);
    expect(status["connected"]).toBe(true);
    expect((status["metrics"] as Record<string, number>)["messagesRejected"]).toBe(1);
  });
});

describe("PhoneSession / 1. 正常 ping/pong", () => {
  it("转发 phone_ping 并收到配对的 phone_pong，RTT 由服务端测量", async () => {
    const rig = await makeRig();
    const { socket } = await connectMockPhone(rig, DEVICE);

    const outcome = await pingPhoneDirect(rig, DEVICE, { requestId: "req-normal-1" });

    expect(outcome.ok).toBe(true);
    if (!outcome.ok) return;
    expect(outcome.request_id).toBe("req-normal-1");
    expect(outcome.device_id).toBe(DEVICE);
    expect(outcome.connection_generation).toBe(1);
    expect(outcome.rtt_ms).toBeGreaterThanOrEqual(0);
    // RTT 是服务端算的：server_timestamp 是服务端发帧的时刻。
    expect(outcome.server_timestamp).toBeGreaterThan(1_600_000_000_000);
    // 手机确实收到了那条 ping，而且 request_id 一字不差。
    const pings = framesOfType(socket, "phone_ping");
    expect(pings.length).toBe(1);
    expect(pings[0]!["request_id"]).toBe("req-normal-1");
    expect(typeof pings[0]!["timestamp"]).toBe("number");

    const status = await phoneStatus(rig, DEVICE);
    const metrics = status["metrics"] as Record<string, number>;
    expect(metrics["pingsSent"]).toBe(1);
    expect(metrics["pongsMatched"]).toBe(1);
    expect(status["pending_requests"]).toBe(0);
    expect(status["pending_mirror"]).toBe(0);
  });

  it("手机不在线时 phone_ping 明确失败为 phone_offline（绝不伪造成功）", async () => {
    const rig = await makeRig();
    const outcome = await pingPhoneDirect(rig, DEVICE, { requestId: "req-offline" });

    expect(outcome.ok).toBe(false);
    if (outcome.ok) return;
    expect(outcome.error.code).toBe("phone_offline");
    expect(outcome.request_id).toBe("req-offline");
  });
});

describe("PhoneSession / 2. 请求超时", () => {
  it("手机不回 pong 时按配置超时，并且 pending 被清理", async () => {
    const rig = await makeRig({ vars: { PHONE_PING_TIMEOUT_MS: "200" } });
    await connectMockPhone(rig, DEVICE, "no-response");

    const started = Date.now();
    const outcome = await pingPhoneDirect(rig, DEVICE, { requestId: "req-timeout-1" });
    const elapsed = Date.now() - started;

    expect(outcome.ok).toBe(false);
    if (outcome.ok) return;
    expect(outcome.error.code).toBe("phone_timeout");
    expect(elapsed).toBeGreaterThanOrEqual(180);
    expect(elapsed).toBeLessThan(3_000);

    const status = await phoneStatus(rig, DEVICE);
    expect(status["pending_requests"]).toBe(0);
    expect(status["pending_mirror"]).toBe(0);
    expect((status["metrics"] as Record<string, number>)["timeouts"]).toBe(1);
    // 连接没断，只是这一次请求超时了。
    expect(status["connected"]).toBe(true);
  });

  it("单次请求可以用 timeout_ms 覆盖默认超时", async () => {
    const rig = await makeRig({ vars: { PHONE_PING_TIMEOUT_MS: "5000" } });
    await connectMockPhone(rig, DEVICE, "no-response");

    const started = Date.now();
    const outcome = await pingPhoneDirect(rig, DEVICE, { requestId: "req-timeout-2", timeoutMs: 150 });
    const elapsed = Date.now() - started;

    expect(outcome.ok).toBe(false);
    expect(elapsed).toBeLessThan(2_000);
  });

  it("超时靠 alarm 兜底：即使本地定时器没跑，存储里的过期请求也会被记为超时", async () => {
    const rig = await makeRig({ vars: { PHONE_PING_TIMEOUT_MS: "100" } });
    await connectMockPhone(rig, DEVICE, "no-response");

    // 起一个不 await 的 ping，让它落在 pending 里
    const pending = pingPhoneDirect(rig, DEVICE, { requestId: "req-alarm-1", timeoutMs: 100 });
    await settle();

    const state = rig.phone.ctxOf(DEVICE);
    const alarmAt = await state.storage.getAlarm();
    expect(alarmAt).not.toBeNull();
    const mirror = await state.storage.get<Record<string, unknown>>("pending");
    expect(Object.keys(mirror ?? {})).toContain("req-alarm-1");

    // 等待越过后手动触发 alarm 路径
    await new Promise((r) => setTimeout(r, 140));
    await state.runAlarm();
    await settle();

    const status = await phoneStatus(rig, DEVICE);
    expect(status["pending_mirror"]).toBe(0);
    const outcome = await pending;
    expect(outcome.ok).toBe(false);
  });
});

describe("PhoneSession / 3. 错误的 request_id", () => {
  it("收到不认识的 request_id 时回 error 帧并拒绝，不影响连接", async () => {
    const rig = await makeRig({ vars: { PHONE_PING_TIMEOUT_MS: "200" } });
    const { socket } = await connectMockPhone(rig, DEVICE, "wrong-request-id");

    const outcome = await pingPhoneDirect(rig, DEVICE, { requestId: "req-wrong-1" });
    expect(outcome.ok).toBe(false);
    if (outcome.ok) return;
    // 原来那条请求只会超时 —— **绝不**被那条乱报的 pong 完成。
    expect(outcome.error.code).toBe("phone_timeout");

    const errors = framesOfType(socket, "error");
    expect(errors.some((e) => e["code"] === "unknown_request_id")).toBe(true);

    const status = await phoneStatus(rig, DEVICE);
    expect((status["metrics"] as Record<string, number>)["pongsUnknown"]).toBe(1);
    expect(status["connected"]).toBe(true);
  });

  it("已超时清理掉的请求，其迟到的 pong 同样被拒绝", async () => {
    const rig = await makeRig({ vars: { PHONE_PING_TIMEOUT_MS: "80" } });
    const { socket } = await connectMockPhone(rig, DEVICE, "no-response");

    const outcome = await pingPhoneDirect(rig, DEVICE, { requestId: "req-late-1" });
    expect(outcome.ok).toBe(false);

    // 现在手动补一条针对该 request_id 的 pong（模拟"超时后才到达"）
    socket.send(JSON.stringify({ type: "phone_pong", request_id: "req-late-1", timestamp: Date.now() }));
    await settle();

    const status = await phoneStatus(rig, DEVICE);
    const metrics = status["metrics"] as Record<string, number>;
    expect(metrics["pongsUnknown"]).toBe(1);
    expect(metrics["pongsMatched"]).toBe(0);
    expect((framesOfType(socket, "error")).some((e) => e["code"] === "unknown_request_id")).toBe(true);
  });
});

describe("PhoneSession / 4. 重复 pong", () => {
  it("同一个请求回两次 pong：第一次成功，第二次被当作未知请求拒绝", async () => {
    const rig = await makeRig();
    const { socket } = await connectMockPhone(rig, DEVICE, "duplicate-pong");

    const outcome = await pingPhoneDirect(rig, DEVICE, { requestId: "req-dup-1" });
    expect(outcome.ok).toBe(true);
    await settle();

    const status = await phoneStatus(rig, DEVICE);
    const metrics = status["metrics"] as Record<string, number>;
    expect(metrics["pongsMatched"]).toBe(1);
    expect(metrics["pongsUnknown"]).toBe(1);

    const errors = framesOfType(socket, "error");
    expect(errors.filter((e) => e["code"] === "unknown_request_id").length).toBe(1);
  });
});

describe("PhoneSession / 5. WebSocket 意外断开", () => {
  it("断线时未完成的请求必须显式失败为 phone_disconnected", async () => {
    const rig = await makeRig();
    const { socket } = await connectMockPhone(rig, DEVICE, "no-response");

    const pending = pingPhoneDirect(rig, DEVICE, { requestId: "req-drop-1", timeoutMs: 5_000 });
    await settle();

    // 模拟网络掉线（不是正常关闭）
    socket.close(1006, "network gone");
    await settle();

    const outcome = await pending;
    expect(outcome.ok).toBe(false);
    if (outcome.ok) return;
    expect(outcome.error.code).toBe("phone_disconnected");
    expect(outcome.request_id).toBe("req-drop-1");

    const status = await phoneStatus(rig, DEVICE);
    expect(status["pending_requests"]).toBe(0);
    expect(status["pending_mirror"]).toBe(0);
    expect(status["connected"]).toBe(false);
  });

  it("断线时**绝不自动重放**：手机侧不会收到第二条 phone_ping", async () => {
    const rig = await makeRig();
    const { socket, phone } = await connectMockPhone(rig, DEVICE, "no-response");

    const pending = pingPhoneDirect(rig, DEVICE, { requestId: "req-noreplay-1", timeoutMs: 5_000 });
    await settle();
    socket.close(1006, "network gone");
    await pending;
    await settle();

    expect(phone.pingCount).toBe(1);
    expect(framesOfType(socket, "phone_ping").length).toBe(1);
  });
});

describe("PhoneSession / 6. 断线后重连", () => {
  it("重连后代次递增，新连接可以正常 ping", async () => {
    const rig = await makeRig();
    const first = await connectMockPhone(rig, DEVICE);
    expect(parseFrame(first.socket.receivedFrames.find((f) => f.includes("welcome"))!)["connection_generation"]).toBe(1);

    first.socket.close(1000, "bye");
    await settle();

    const second = await connectMockPhone(rig, DEVICE);
    const welcome = framesOfType(second.socket, "welcome")[0]!;
    expect(welcome["connection_generation"]).toBe(2);

    const outcome = await pingPhoneDirect(rig, DEVICE, { requestId: "req-reconnect-1" });
    expect(outcome.ok).toBe(true);
    if (!outcome.ok) return;
    expect(outcome.connection_generation).toBe(2);

    const status = await phoneStatus(rig, DEVICE);
    const metrics = status["metrics"] as Record<string, number>;
    expect(metrics["connectionsAccepted"]).toBe(2);
    expect(metrics["disconnects"]).toBeGreaterThanOrEqual(1);
  });

  it("新连接会用 close code 4001 关掉旧连接", async () => {
    const rig = await makeRig();
    const first = await connectMockPhone(rig, DEVICE);
    expect(first.socket.closeInfo).toBeNull();

    await connectMockPhone(rig, DEVICE, "normal");
    await settle();

    expect(first.socket.closeInfo).not.toBeNull();
    expect(first.socket.closeInfo!.code).toBe(4001);

    const status = await phoneStatus(rig, DEVICE);
    expect((status["metrics"] as Record<string, number>)["connectionReplaced"]).toBe(1);
  });
});

describe("PhoneSession / 7. 旧连接的响应被拒绝", () => {
  it("新连接一建立，旧代次的待处理请求立刻被判为 phone_replaced；迟到的 pong 不能再结算任何东西", async () => {
    const rig = await makeRig();
    // 代次 1：故意不回 pong，好让请求挂在 pending 里
    await connectMockPhone(rig, DEVICE, "no-response");

    const state = rig.phone.ctxOf(DEVICE);
    const gen1ServerSocket = state.getWebSockets("gen:1")[0]!;
    expect(gen1ServerSocket).toBeDefined();

    const pending = pingPhoneDirect(rig, DEVICE, { requestId: "req-stale-1", timeoutMs: 5_000 });
    await settle();

    // 代次 2：新连接顶掉旧的
    await connectMockPhone(rig, DEVICE, "normal");
    await settle();

    // ★ Gate 0.1 审计 R1 之后，这里**不再**依赖旧 socket 的关闭事件、也不等 5 秒超时：
    //   新连接建立的那一刻，旧代次的请求就已经被主动结算了。
    const outcome = await pending;
    expect(outcome.ok).toBe(false);
    if (outcome.ok) return;
    expect(outcome.error.code).toBe("phone_replaced");
    expect(outcome.error.message).toContain("替换");

    // 模拟"旧连接在网络里还在飞的 pong 现在才到"：直接把它投给 DO。
    // 这是白盒注入 —— 真实场景里这条帧的 socket 已经被关掉了。
    const instance = rig.phone.instanceFor(DEVICE);
    await instance.webSocketMessage?.(
      gen1ServerSocket as never,
      JSON.stringify({ type: "phone_pong", request_id: "req-stale-1", timestamp: Date.now() }),
    );
    await settle();

    const status = await phoneStatus(rig, DEVICE);
    const metrics = status["metrics"] as Record<string, number>;
    // 请求已经被结算过，所以这条迟到的 pong 只能落到"未知请求"上 ——
    // 它**不可能**第二次结算同一个 request_id（"不允许重复结算"）。
    expect(metrics["pongsMatched"]).toBe(0);
    expect(metrics["pongsUnknown"]).toBe(1);
    expect(metrics["pendingReplacedOnConnect"]).toBe(1);
    expect(status["pending_requests"]).toBe(0);
    expect(status["pending_mirror"]).toBe(0);
  });

  it("旧 socket 上送来的 pong 不得完成**新代次**的请求（socket 代次二次校验）", async () => {
    const rig = await makeRig();
    await connectMockPhone(rig, DEVICE, "no-response");
    const state = rig.phone.ctxOf(DEVICE);
    const gen1ServerSocket = state.getWebSockets("gen:1")[0]!;

    // 代次 2 的新连接。同样让它故意不回，好把新代次的请求留在 pending 里。
    await connectMockPhone(rig, DEVICE, "no-response");
    await settle();

    const pending = pingPhoneDirect(rig, DEVICE, { requestId: "req-new-1", timeoutMs: 5_000 });
    await settle();

    // 请求属于代次 2（entry.generation === this.generation），所以第一个条件不成立；
    // 唯一能挡住它的是"pong 来的那条 socket 不是当前代次"。
    const instance = rig.phone.instanceFor(DEVICE);
    await instance.webSocketMessage?.(
      gen1ServerSocket as never,
      JSON.stringify({ type: "phone_pong", request_id: "req-new-1", timestamp: Date.now() }),
    );
    await settle();

    const outcome = await pending;
    expect(outcome.ok).toBe(false);
    if (outcome.ok) return;
    expect(outcome.error.code).toBe("invalid_pong");
    expect(outcome.error.message).toContain("过期连接");

    const status = await phoneStatus(rig, DEVICE);
    const metrics = status["metrics"] as Record<string, number>;
    expect(metrics["pongsStaleGeneration"]).toBe(1);
    expect(metrics["pongsMatched"]).toBe(0);
    expect(status["pending_requests"]).toBe(0);
  });
});

// ---------------------------------------------------------------------------
//  Gate 0.1 审计 R1：重连竞态
// ---------------------------------------------------------------------------

describe("PhoneSession / 11. 重连竞态（Gate 0.1 审计 R1）", () => {
  it("旧请求未完成时建立新连接：旧请求立刻拿到 phone_replaced，而不是干等到超时", async () => {
    const rig = await makeRig();
    await connectMockPhone(rig, DEVICE, "no-response");

    // 给一个很长的超时（30s）。如果实现是"靠超时或 close 事件清理"，
    // 这个 await 就会挂住 30 秒；R1 要求它必须**立刻**返回。
    const started = Date.now();
    const pending = pingPhoneDirect(rig, DEVICE, { requestId: "req-r1-fast", timeoutMs: 30_000 });
    await settle();

    await connectMockPhone(rig, DEVICE, "normal");
    await settle();

    const outcome = await pending;
    const elapsed = Date.now() - started;

    expect(outcome.ok).toBe(false);
    if (outcome.ok) return;
    expect(outcome.error.code).toBe("phone_replaced");
    // 断言"远早于超时"，把"其实还是走了超时路径"这种假通过排除掉。
    expect(elapsed).toBeLessThan(2_000);

    const metrics = (await phoneStatus(rig, DEVICE))["metrics"] as Record<string, number>;
    expect(metrics["timeouts"]).toBe(0);
    expect(metrics["pendingReplacedOnConnect"]).toBe(1);
  });

  it("旧连接的关闭事件**延迟**到达时，不会误伤新连接的待处理请求", async () => {
    const rig = await makeRig();
    await connectMockPhone(rig, DEVICE, "no-response");
    const gen1ServerSocket = rig.phone.ctxOf(DEVICE).getWebSockets("gen:1")[0]!;

    // 旧请求：会被替换时结算掉
    const stale = pingPhoneDirect(rig, DEVICE, { requestId: "req-old", timeoutMs: 30_000 });
    await settle();

    // 新连接（normal 场景：会正常回 pong）
    await connectMockPhone(rig, DEVICE, "normal");
    await settle();
    expect((await stale).ok).toBe(false);

    // 新代次的请求，先挂在 pending 上
    const fresh = pingPhoneDirect(rig, DEVICE, { requestId: "req-fresh", timeoutMs: 5_000 });
    await settle();

    // ★ 现在才投递旧 socket 的 close 事件（现实中它可能晚几秒、甚至永远不到）。
    const instance = rig.phone.instanceFor(DEVICE);
    await instance.webSocketClose?.(gen1ServerSocket as never, 4001, "replaced by a newer connection", true);
    await settle();

    // 新请求必须**毫发无损**：它属于当前代次，旧 socket 的关闭与它无关。
    const outcome = await fresh;
    expect(outcome.ok).toBe(true);
    if (!outcome.ok) return;
    expect(outcome.request_id).toBe("req-fresh");

    const status = await phoneStatus(rig, DEVICE);
    expect(status["pending_requests"]).toBe(0);
    expect(status["pending_mirror"]).toBe(0);
  });

  it("替换**之前**就已完成的请求不受影响（不会误杀已经答完的请求）", async () => {
    const rig = await makeRig();
    await connectMockPhone(rig, DEVICE, "normal");

    // 这一条在旧连接上正常答完了
    const early = await pingPhoneDirect(rig, DEVICE, { requestId: "req-early", timeoutMs: 5_000 });
    expect(early.ok).toBe(true);

    await connectMockPhone(rig, DEVICE, "normal");
    await settle();

    const status = await phoneStatus(rig, DEVICE);
    const metrics = status["metrics"] as Record<string, number>;
    // 已经结算掉的请求不该被替换流程再碰一次 —— pendingReplacedOnConnect 必须是 0，
    // 否则说明 failStalePending 把"全部 pending"清空了，而不是只清旧代次。
    expect(metrics["pendingReplacedOnConnect"]).toBe(0);
    expect(metrics["pongsMatched"]).toBe(1);
    expect(metrics["pongsUnknown"]).toBe(0);
    expect(status["pending_requests"]).toBe(0);
  });

  it("新旧连接并发响应：只有属于当前代次的那一条被接受，另一条不能复活旧请求", async () => {
    const rig = await makeRig();
    // 两侧都用 no-response：响应完全由测试注入，这样"哪一条先到"是确定的。
    await connectMockPhone(rig, DEVICE, "no-response");
    const gen1ServerSocket = rig.phone.ctxOf(DEVICE).getWebSockets("gen:1")[0]!;

    const stale = pingPhoneDirect(rig, DEVICE, { requestId: "req-both-old", timeoutMs: 30_000 });
    await settle();

    await connectMockPhone(rig, DEVICE, "no-response");
    await settle();

    const fresh = pingPhoneDirect(rig, DEVICE, { requestId: "req-both-new", timeoutMs: 5_000 });
    await settle();

    // 两条响应几乎同时到：一条从旧 socket 来（想复活已经被替换掉的旧请求），
    // 一条从新 socket 来（合法）。
    const instance = rig.phone.instanceFor(DEVICE);
    const gen2ServerSocket = rig.phone.ctxOf(DEVICE).getWebSockets("gen:2")[0]!;
    await instance.webSocketMessage?.(
      gen1ServerSocket as never,
      JSON.stringify({ type: "phone_pong", request_id: "req-both-old", timestamp: Date.now() }),
    );
    await instance.webSocketMessage?.(
      gen2ServerSocket as never,
      JSON.stringify({ type: "phone_pong", request_id: "req-both-new", timestamp: Date.now() }),
    );
    await settle();

    const staleOutcome = await stale;
    const freshOutcome = await fresh;

    // 旧请求：在替换那一刻就已经被判失败了，旧 pong **不能**把它复活。
    expect(staleOutcome.ok).toBe(false);
    if (!staleOutcome.ok) expect(staleOutcome.error.code).toBe("phone_replaced");
    // 新请求：正常成功，且 RTT / 代次都对。
    expect(freshOutcome.ok).toBe(true);
    if (freshOutcome.ok) {
      expect(freshOutcome.request_id).toBe("req-both-new");
      expect(freshOutcome.connection_generation).toBe(2);
    }

    const metrics = (await phoneStatus(rig, DEVICE))["metrics"] as Record<string, number>;
    expect(metrics["pongsMatched"]).toBe(1);
    expect(metrics["pendingReplacedOnConnect"]).toBe(1);
  });

  it("重连后新请求正常成功（替换没有把新连接弄坏）", async () => {
    const rig = await makeRig();
    await connectMockPhone(rig, DEVICE, "no-response");
    const stale = pingPhoneDirect(rig, DEVICE, { requestId: "req-c1", timeoutMs: 30_000 });
    await settle();

    const second = await connectMockPhone(rig, DEVICE, "normal");
    expect((await stale).ok).toBe(false);

    // 新连接上连发三次，全部成功，代次都是 2。
    for (const id of ["req-c2", "req-c3", "req-c4"]) {
      const outcome = await pingPhoneDirect(rig, DEVICE, { requestId: id, timeoutMs: 5_000 });
      expect(outcome.ok, `${id} 应成功`).toBe(true);
      if (outcome.ok) {
        expect(outcome.connection_generation).toBe(2);
        expect(outcome.rtt_ms).toBeGreaterThanOrEqual(0);
      }
    }

    const metrics = (await phoneStatus(rig, DEVICE))["metrics"] as Record<string, number>;
    expect(metrics["pongsMatched"]).toBe(3);
    expect(metrics["pongsStaleGeneration"]).toBe(0);
    expect(metrics["connectionsAccepted"]).toBe(2);
    expect(framesOfType(second.socket, "phone_ping")).toHaveLength(3);
  });

  it("同一个 request_id 绝不重复结算：指标只加一次，镜像与 alarm 同步收敛", async () => {
    const rig = await makeRig();
    await connectMockPhone(rig, DEVICE, "no-response");
    const gen1ServerSocket = rig.phone.ctxOf(DEVICE).getWebSockets("gen:1")[0]!;

    const pending = pingPhoneDirect(rig, DEVICE, { requestId: "req-once", timeoutMs: 30_000 });
    await settle();

    // 结算路径 1：新连接替换
    await connectMockPhone(rig, DEVICE, "normal");
    await settle();
    const first = await pending;
    expect(first.ok).toBe(false);

    // 结算路径 2：同一条 request_id 的迟到 pong（此时表里已经没有它了）
    const instance = rig.phone.instanceFor(DEVICE);
    await instance.webSocketMessage?.(
      gen1ServerSocket as never,
      JSON.stringify({ type: "phone_pong", request_id: "req-once", timestamp: Date.now() }),
    );
    await settle();

    // 结算路径 3：alarm 兜底再跑一遍（存储镜像里已经没有它了）
    await instance.alarm?.();
    await settle();

    const status = await phoneStatus(rig, DEVICE);
    const metrics = status["metrics"] as Record<string, number>;
    expect(metrics["pendingReplacedOnConnect"]).toBe(1);
    expect(metrics["pongsMatched"]).toBe(0);
    expect(metrics["timeouts"]).toBe(0);
    expect(status["pending_requests"]).toBe(0);
    expect(status["pending_mirror"]).toBe(0);
    // alarm 必须被清掉：没有待处理请求就不该留着定时器。
    expect(await rig.phone.ctxOf(DEVICE).storage.getAlarm()).toBeNull();
  });

  it("替换多个待处理请求时，每一个都各自拿到 phone_replaced（不会只处理第一个）", async () => {
    const rig = await makeRig();
    await connectMockPhone(rig, DEVICE, "no-response");

    const ids = ["req-m1", "req-m2", "req-m3", "req-m4", "req-m5"];
    const pendings = ids.map((requestId) => pingPhoneDirect(rig, DEVICE, { requestId, timeoutMs: 30_000 }));
    // 上面这些是并发发出的：先让 DO 把 5 个请求都登记好。
    await settle(24);
    expect((await phoneStatus(rig, DEVICE))["pending_requests"]).toBe(5);

    await connectMockPhone(rig, DEVICE, "normal");
    await settle(24);

    const outcomes = await Promise.all(pendings);
    for (let i = 0; i < outcomes.length; i += 1) {
      const outcome = outcomes[i]!;
      expect(outcome.ok, `${ids[i]} 应失败`).toBe(false);
      if (!outcome.ok) {
        expect(outcome.error.code).toBe("phone_replaced");
        expect(outcome.request_id).toBe(ids[i]);
      }
    }

    const status = await phoneStatus(rig, DEVICE);
    expect((status["metrics"] as Record<string, number>)["pendingReplacedOnConnect"]).toBe(5);
    expect(status["pending_requests"]).toBe(0);
    expect(status["pending_mirror"]).toBe(0);
  });
});

describe("PhoneSession / 8. 并发请求正确关联", () => {
  it("多个并发请求各回各的，request_id 与 RTT 一一对应", async () => {
    const rig = await makeRig();
    const { phone } = await connectMockPhone(rig, DEVICE, "late", { delayMs: 30 });

    const ids = ["req-c1", "req-c2", "req-c3", "req-c4", "req-c5"];
    const outcomes = await Promise.all(ids.map((id) => pingPhoneDirect(rig, DEVICE, { requestId: id, timeoutMs: 4_000 })));

    expect(phone.pingCount).toBe(5);
    for (let i = 0; i < ids.length; i += 1) {
      const outcome = outcomes[i]!;
      expect(outcome.ok).toBe(true);
      if (!outcome.ok) return;
      expect(outcome.request_id).toBe(ids[i]!);
      expect(outcome.rtt_ms).toBeGreaterThanOrEqual(25);
    }

    const status = await phoneStatus(rig, DEVICE);
    expect((status["metrics"] as Record<string, number>)["pongsMatched"]).toBe(5);
    expect(status["pending_requests"]).toBe(0);
  });

  it("只有一个请求没人回应时，只有它超时，其余照常成功", async () => {
    const rig = await makeRig({ vars: { PHONE_PING_TIMEOUT_MS: "250" } });
    // 第 1 个（0 基）ping 故意不回 —— 也就是三并发里中间那个。
    const { phone, socket } = await connectMockPhone(rig, DEVICE, "normal", { skipPingIndexes: [1] });

    const p1 = pingPhoneDirect(rig, DEVICE, { requestId: "req-k0" });
    const p2 = pingPhoneDirect(rig, DEVICE, { requestId: "req-k1" });
    const p3 = pingPhoneDirect(rig, DEVICE, { requestId: "req-k2" });

    const [r1, r2, r3] = await Promise.all([p1, p2, p3]);
    expect(r1.ok).toBe(true);
    expect(r2.ok).toBe(false);
    expect(r3.ok).toBe(true);
    if (r1.ok) expect(r1.request_id).toBe("req-k0");
    if (r3.ok) expect(r3.request_id).toBe("req-k2");
    if (r2.ok) return;
    expect(r2.error.code).toBe("phone_timeout");
    expect(r2.request_id).toBe("req-k1");

    // 三条 ping 都真的发出去了，只是中间那条没人回。
    expect(phone.pingCount).toBe(3);
    expect(framesOfType(socket, "phone_ping").length).toBe(3);

    const status = await phoneStatus(rig, DEVICE);
    const metrics = status["metrics"] as Record<string, number>;
    expect(metrics["timeouts"]).toBe(1);
    expect(metrics["pongsMatched"]).toBe(2);
    expect(status["pending_requests"]).toBe(0);
  });
});

describe("PhoneSession / 9. 超出并发上限", () => {
  it("超过 MAX_PENDING_REQUESTS 的请求立刻失败为 too_many_pending_requests", async () => {
    const rig = await makeRig({
      vars: { MAX_PENDING_REQUESTS: "2", PHONE_PING_TIMEOUT_MS: "400" },
    });
    await connectMockPhone(rig, DEVICE, "no-response");

    const first = pingPhoneDirect(rig, DEVICE, { requestId: "req-limit-1" });
    const second = pingPhoneDirect(rig, DEVICE, { requestId: "req-limit-2" });
    await settle();

    const statusBefore = await phoneStatus(rig, DEVICE);
    expect(statusBefore["pending_requests"]).toBe(2);

    const third = await pingPhoneDirect(rig, DEVICE, { requestId: "req-limit-3" });
    expect(third.ok).toBe(false);
    if (third.ok) return;
    expect(third.error.code).toBe("too_many_pending_requests");

    const status = await phoneStatus(rig, DEVICE);
    expect((status["metrics"] as Record<string, number>)["pendingRejectedOverLimit"]).toBe(1);

    await Promise.all([first, second]);
    const after = await phoneStatus(rig, DEVICE);
    expect(after["pending_requests"]).toBe(0);
  });
});

describe("PhoneSession / 10. 对象重建", () => {
  it("重建时把存储里遗留的待处理请求记为失败，绝不产生虚假成功", async () => {
    const rig = await makeRig({ vars: { PHONE_PING_TIMEOUT_MS: "300" } });
    await connectMockPhone(rig, DEVICE, "no-response");

    const pending = pingPhoneDirect(rig, DEVICE, { requestId: "req-rebuild-1", timeoutMs: 300 });
    await settle();

    const state = rig.phone.ctxOf(DEVICE);
    expect(Object.keys((await state.storage.get<Record<string, unknown>>("pending")) ?? {})).toContain(
      "req-rebuild-1",
    );

    // 驱逐对象：内存全丢，持久化状态保留 —— 等价于真实休眠后重建。
    rig.phone.evict(DEVICE);
    expect(rig.phone.constructionCount(DEVICE)).toBe(1);

    // 下一次请求会唤醒对象（触发重建）
    const status = await phoneStatus(rig, DEVICE);
    expect(rig.phone.constructionCount(DEVICE)).toBe(2);

    const metrics = status["metrics"] as Record<string, number>;
    expect(metrics["reconstructedPendingFailed"]).toBe(1);
    expect(status["pending_requests"]).toBe(0);
    expect(status["pending_mirror"]).toBe(0);
    // 存储里的遗留镜像必须被清空，否则会重复记账
    expect(await state.storage.get<Record<string, unknown>>("pending")).toEqual({});

    // 重建**不会**把那个请求补成成功：它在 150ms 内不会有任何结论。
    const early = await Promise.race([pending.then((o) => o), settle(150).then(() => null)]);
    expect(early).toBeNull();

    // 最终它走的是原实例本地定时器的超时路径 —— 依然是失败。
    const final = await pending;
    expect(final.ok).toBe(false);
    if (final.ok) return;
    expect(final.error.code).toBe("phone_timeout");
  });

  it("重建后代次从存储恢复，不会被重置成 0", async () => {
    const rig = await makeRig();
    await connectMockPhone(rig, DEVICE, "normal");
    await connectMockPhone(rig, DEVICE, "normal"); // 代次 2

    rig.phone.evict(DEVICE);
    const status = await phoneStatus(rig, DEVICE);
    expect(status["connection_generation"]).toBe(2);
    expect(rig.phone.constructionCount(DEVICE)).toBe(2);
  });
});

describe("PhoneSession / 消息边界", () => {
  it("非法 JSON、未知帧类型、缺字段的 pong 都被拒绝，且不影响后续 ping", async () => {
    const rig = await makeRig();
    const { socket } = await connectMockPhone(rig, DEVICE, "noisy");

    // 先自己发几条坏帧
    socket.send("{not json at all");
    socket.send(JSON.stringify({ type: "totally_unknown" }));
    socket.send(JSON.stringify({ type: "phone_pong" })); // 缺 request_id
    await settle();

    const errors = framesOfType(socket, "error");
    expect(errors.some((e) => e["code"] === "malformed_message")).toBe(true);
    expect(errors.some((e) => e["code"] === "unsupported_type")).toBe(true);

    // 连接依旧可用
    const outcome = await pingPhoneDirect(rig, DEVICE, { requestId: "req-noisy-1" });
    expect(outcome.ok).toBe(true);
  });

  it("超过 MAX_PHONE_MESSAGE_BYTES 的帧被拒绝为 message_too_large", async () => {
    const rig = await makeRig({ vars: { MAX_PHONE_MESSAGE_BYTES: "1024" } });
    const { socket } = await connectMockPhone(rig, DEVICE);

    socket.send(JSON.stringify({ type: "phone_pong", request_id: "x", padding: "y".repeat(2_000) }));
    await settle();

    const errors = framesOfType(socket, "error");
    expect(errors.some((e) => e["code"] === "message_too_large")).toBe(true);

    const status = await phoneStatus(rig, DEVICE);
    expect((status["metrics"] as Record<string, number>)["messagesRejected"]).toBe(1);
  });

  it("二进制帧被拒绝", async () => {
    const rig = await makeRig();
    const { socket } = await connectMockPhone(rig, DEVICE);

    const state = rig.phone.ctxOf(DEVICE);
    const serverSocket = state.getWebSockets("gen:1")[0]!;
    const instance = rig.phone.instanceFor(DEVICE);
    await instance.webSocketMessage?.(serverSocket as never, new ArrayBuffer(4));
    await settle();

    expect((framesOfType(socket, "error")).some((e) => e["code"] === "unsupported_type")).toBe(true);
  });
});

describe("PhoneSession / 多设备隔离", () => {
  it("两台设备各自一个 DO，互不影响", async () => {
    const rig = await makeRig();
    const a = await connectMockPhone(rig, DEVICE, "normal");
    const b = await connectMockPhone(rig, TEST_DEVICE_ID_2, "no-response");

    const ra = await pingPhoneDirect(rig, DEVICE, { requestId: "req-dev-a" });
    expect(ra.ok).toBe(true);

    const rb = await pingPhoneDirect(rig, TEST_DEVICE_ID_2, { requestId: "req-dev-b", timeoutMs: 120 });
    expect(rb.ok).toBe(false);

    expect(a.socket.receivedFrames.some((f) => f.includes("req-dev-b"))).toBe(false);
    expect(b.socket.receivedFrames.some((f) => f.includes("req-dev-a"))).toBe(false);
    expect(rig.phone.liveCount()).toBe(2);
  });
});

// ---------------------------------------------------------------------------
//  Gate 0.3 审计发现项 3：结算路径的故障注入
// ---------------------------------------------------------------------------
//
//  要证明三件事：
//    1. **不永久挂起** —— 存储写失败 / 指标落盘失败 / alarm 调度失败时，
//       调用方仍然会拿到一个明确结果；
//    2. **不泄漏、不重复结算** —— 失败不会留下"登记了但没人管"的表项，
//       结算过的请求不会被再结算一次；
//    3. **不伪造成功** —— 手机一次 pong 都没回时，无论记账怎么失败，
//       都不可能得到 `ok: true`。
// ---------------------------------------------------------------------------

describe("PhoneSession / 12. 结算路径的故障注入（Gate 0.3 审计发现项 3）", () => {
  /** 拿到这台设备那个 DO 实例的假存储，用来注入故障。 */
  function storageOf(rig: Awaited<ReturnType<typeof makeRig>>): FakeDurableObjectStorage {
    return rig.phone.ctxOf(DEVICE).storage as FakeDurableObjectStorage;
  }

  it("结算时存储写、指标落盘、alarm 调度全部失败，调用方仍然拿到 phone_timeout", async () => {
    const rig = await makeRig({ vars: { PHONE_PING_TIMEOUT_MS: "120" } });
    const { socket } = await connectMockPhone(rig, DEVICE, "no-response");
    const storage = storageOf(rig);

    // 把结算路径上每一处记账都打坏。
    const pendingFail = storage.failNext("put", { key: "pending", times: 10 });
    const metricsFail = storage.failNext("put", { key: "metrics", times: 10 });
    storage.failNext("setAlarm", { times: 10 });
    storage.failNext("deleteAlarm", { times: 10 });

    const started = Date.now();
    const outcome = await pingPhoneDirect(rig, DEVICE, { requestId: "req-fi-1" });
    const elapsed = Date.now() - started;

    // ⚠ 这一条就是 Gate 0.3 的核心。加固之前，`settlePending` 里裸 await 的
    //   `storage.put` 一抛，`entry.settle()` 就永远不会执行，这里会**永远挂着**。
    expect(outcome.ok).toBe(false);
    if (outcome.ok) return;
    expect(outcome.error.code).toBe("phone_timeout");

    // 而且它确实是"等满超时"才返回的，不是被某条捷径提前完成的。
    expect(elapsed).toBeGreaterThanOrEqual(100);

    // 故障注入真的生效了（否则这个测试会变成空过）。
    expect(pendingFail.hits).toBeGreaterThanOrEqual(1);
    expect(metricsFail.hits).toBeGreaterThanOrEqual(1);

    const status = await phoneStatus(rig, DEVICE);
    // 表项没有泄漏：内存表与落盘镜像都干净了。
    expect(status["pending_requests"]).toBe(0);
    expect(status["pending_mirror"]).toBe(0);
    expect(status["connected"]).toBe(true);

    const metrics = status["metrics"] as Record<string, number>;
    expect(metrics["timeouts"]).toBe(1);
    // 手机一个 pong 都没回 —— 记账再糟也不能变出一次成功。
    expect(metrics["pongsMatched"]).toBe(0);
    expect(metrics["pingsSent"]).toBe(1);
    // 三类记账失败被分开计数，而不是被静默吞掉。
    expect(metrics["settleStorageFailures"]).toBeGreaterThanOrEqual(1);
    expect(metrics["settleMetricFailures"]).toBeGreaterThanOrEqual(1);
    expect(metrics["settleAlarmFailures"]).toBeGreaterThanOrEqual(1);

    expect(framesOfType(socket, "phone_ping").length).toBe(1);
  });

  it("并发超时叠加记账失败：8 个请求各自拿到结果，一个都不会被结算两次", async () => {
    const rig = await makeRig({ vars: { PHONE_PING_TIMEOUT_MS: "100", MAX_PENDING_REQUESTS: "16" } });
    await connectMockPhone(rig, DEVICE, "no-response");
    const storage = storageOf(rig);

    storage.failNext("put", { key: "pending", times: 100 });
    storage.failNext("put", { key: "metrics", times: 100 });

    const outcomes = await Promise.all(
      Array.from({ length: 8 }, (_, i) => pingPhoneDirect(rig, DEVICE, { requestId: `req-conc-${i}` })),
    );

    expect(outcomes.length).toBe(8);
    for (const outcome of outcomes) {
      expect(outcome.ok).toBe(false);
      if (!outcome.ok) expect(outcome.error.code).toBe("phone_timeout");
    }

    const status = await phoneStatus(rig, DEVICE);
    expect(status["pending_requests"]).toBe(0);
    const metrics = status["metrics"] as Record<string, number>;
    // 8 个请求 = 恰好 8 次超时。多一次就是重复结算，少一次就是漏结算。
    expect(metrics["timeouts"]).toBe(8);
    expect(metrics["pongsMatched"]).toBe(0);
    expect(metrics["pingsSent"]).toBe(8);
  });

  it("已经结算过的请求：再跑两次 alarm 不会重复结算，也不会多记一次超时", async () => {
    const rig = await makeRig({ vars: { PHONE_PING_TIMEOUT_MS: "80" } });
    await connectMockPhone(rig, DEVICE, "no-response");
    const state = rig.phone.ctxOf(DEVICE);

    const outcome = await pingPhoneDirect(rig, DEVICE, { requestId: "req-dup-settle-1" });
    expect(outcome.ok).toBe(false);

    const before = ((await phoneStatus(rig, DEVICE))["metrics"]) as Record<string, number>;
    expect(before["timeouts"]).toBe(1);

    // 手动把 alarm 跑两遍。镜像里已经没有这个请求了，所以两次都应该是空转。
    await state.runAlarm();
    await state.runAlarm();

    const after = ((await phoneStatus(rig, DEVICE))["metrics"]) as Record<string, number>;
    expect(after["timeouts"]).toBe(1);
    expect(after["pongsMatched"]).toBe(0);
    // alarm 空转不应该被算成"记账失败"。
    expect(after["settleStorageFailures"]).toBe(0);
    expect(after["settleAlarmFailures"]).toBe(0);
  });

  it("alarm 处理器自己的记账失败不会向外抛，指标照常记一笔", async () => {
    const rig = await makeRig({ vars: { PHONE_PING_TIMEOUT_MS: "300" } });
    await connectMockPhone(rig, DEVICE, "no-response");
    const state = rig.phone.ctxOf(DEVICE);
    const storage = storageOf(rig);

    // 起一个还没到期的请求（不 await，稍后再收）。
    const pending = pingPhoneDirect(rig, DEVICE, { requestId: "req-alarm-fi-1" });
    await settle();

    // 让 alarm 末尾那次 rescheduleAlarm 失败（它先 getAlarm，再决定是否 setAlarm）。
    const alarmFail = storage.failNext("getAlarm", { times: 5 });

    await expect(state.runAlarm()).resolves.toBeUndefined();
    expect(alarmFail.hits).toBeGreaterThanOrEqual(1);

    const outcome = await pending;
    expect(outcome.ok).toBe(false);
    if (!outcome.ok) expect(outcome.error.code).toBe("phone_timeout");

    const metrics = ((await phoneStatus(rig, DEVICE))["metrics"]) as Record<string, number>;
    expect(metrics["settleAlarmFailures"]).toBeGreaterThanOrEqual(1);
    expect(metrics["timeouts"]).toBe(1);
  });

  it("手机离线是纯决策路径：存储全坏也不影响 phone_offline 的判定", async () => {
    const rig = await makeRig();

    // ⚠ 先让 DO 实例被构造出来。构造期要读 generation / metrics / pending，
    //   在那之前注入 get 故障会让**状态装载**失败 —— 那是"对象装载不了"，
    //   不是本条要测的"决策路径不依赖存储"，两者不能混在一个断言里。
    await phoneStatus(rig, DEVICE);

    const storage = storageOf(rig);

    // 存储的每一个方法都失败。
    storage.failNext("get", { times: 10 });
    storage.failNext("put", { times: 10 });
    storage.failNext("getAlarm", { times: 10 });
    storage.failNext("setAlarm", { times: 10 });

    const outcome = await pingPhoneDirect(rig, DEVICE, { requestId: "req-offline-fi-1" });
    expect(outcome.ok).toBe(false);
    if (outcome.ok) return;
    expect(outcome.error.code).toBe("phone_offline");
  });

  it("存储恢复正常后记账照旧：结算过的请求不会在镜像里留下残影", async () => {
    const rig = await makeRig({ vars: { PHONE_PING_TIMEOUT_MS: "90" } });
    await connectMockPhone(rig, DEVICE, "no-response");
    const storage = storageOf(rig);

    // 第一次：只让那一次 pending 镜像写失败。
    storage.failNext("put", { key: "pending", times: 1 });
    const first = await pingPhoneDirect(rig, DEVICE, { requestId: "req-recover-1" });
    expect(first.ok).toBe(false);

    // 第二次：存储已恢复，正常路径必须仍然是"超时→结算→拿到结果"。
    const second = await pingPhoneDirect(rig, DEVICE, { requestId: "req-recover-2" });
    expect(second.ok).toBe(false);
    if (!second.ok) expect(second.error.code).toBe("phone_timeout");

    const status = await phoneStatus(rig, DEVICE);
    expect(status["pending_requests"]).toBe(0);
    expect(status["pending_mirror"]).toBe(0);
    const metrics = status["metrics"] as Record<string, number>;
    expect(metrics["timeouts"]).toBe(2);
    expect(metrics["pongsMatched"]).toBe(0);
    // 落盘镜像里最终不该再有这两个请求。
    const mirrored = await storage.get<Record<string, unknown>>("pending");
    expect(Object.keys(mirrored ?? {})).toEqual([]);
  });
});

// ============================================================================
//  Gate 1 审计发现项 A / B / C
// ============================================================================
//
//  任务书「六、补充修复 Gate 0.3 审计遗留问题」列了三条，这里逐条对应：
//
//    A. `settlePending()` 在 `entry.settle()` 前仍 await 异步持久化
//       → "存储不返回"（不是"抛错"）也不能让调用方挂住
//    B. `handlePingRequest()` 允许重复 request_id 覆盖 pending
//       → 同 id 的第二个未完成请求必须被拒绝
//    C. DO 重建后遗留 pending 的处理
//       → 不产生虚假成功、不重复结算、不错误匹配迟到 pong
//
//  ⚠ 与 12 节的区别：12 节注入的是"抛错"，本节注入的是**永不返回**。
//     这两条路径的兜底机制完全不同 —— 抛错靠 try/catch，不返回只能靠
//     "结算绝不排在 I/O 之后"。Gate 0.3 只做了前者，K13 记的就是后者。
// ============================================================================

describe("PhoneSession / 13. Gate 1 审计发现项 A：存储不返回时仍必须结算", () => {
  function storageOf(rig: Awaited<ReturnType<typeof makeRig>>): FakeDurableObjectStorage {
    return rig.phone.ctxOf(DEVICE).storage as FakeDurableObjectStorage;
  }

  it("落盘永不返回：调用方仍在超时那一刻拿到 phone_timeout，而不是永远挂着", async () => {
    const rig = await makeRig({ vars: { PHONE_PING_TIMEOUT_MS: "300" } });
    await connectMockPhone(rig, DEVICE, "no-response");
    const storage = storageOf(rig);

    const pending = pingPhoneDirect(rig, DEVICE, { requestId: "req-hang-1" });
    // 先让帧真的发出去、镜像真的写完 —— 悬挂是从"结算"那一刻才开始的。
    await settle();

    // 从这一刻起，写 pending 镜像**永不 resolve**（既不抛错，也不返回）。
    const hang = storage.hangNext("put", { key: "pending", times: 100 });

    const started = Date.now();
    const raced = await Promise.race([pending, settle(1200).then(() => "HUNG" as const)]);
    const elapsed = Date.now() - started;

    // ⚠ 这一条就是 Gate 1 发现项 A 的核心。
    //   Gate 0.3 只把这三步降级成 bestEffort（不再抛错），结算**仍然**排在
    //   三次 await 之后。存储一旦"不返回"，`entry.settle()` 就永远不执行 ——
    //   这里会拿到 "HUNG"。修复后结算排在记账之前，与存储是否返回无关。
    expect(raced).not.toBe("HUNG");
    if (raced === "HUNG") return;
    expect(raced.ok).toBe(false);
    if (raced.ok) return;
    expect(raced.error.code).toBe("phone_timeout");
    expect(elapsed).toBeLessThan(1000);
    expect(hang.hits).toBeGreaterThanOrEqual(1);

    // 悬挂的只是"记账"，内存表该清干净还是清干净。
    storage.clearInjectedFailures();
    const status = await phoneStatus(rig, DEVICE);
    expect(status["pending_requests"]).toBe(0);
    const metrics = status["metrics"] as Record<string, number>;
    expect(metrics["timeouts"]).toBe(1);
    expect(metrics["pongsMatched"]).toBe(0);
    expect(metrics["pingsSent"]).toBe(1);
  });

  it("落盘 + 指标 + alarm 三个记账点全部永不返回：并发请求各自照常结算", async () => {
    const rig = await makeRig({ vars: { PHONE_PING_TIMEOUT_MS: "150", MAX_PENDING_REQUESTS: "16" } });
    await connectMockPhone(rig, DEVICE, "no-response");
    const storage = storageOf(rig);

    const pings = Array.from({ length: 3 }, (_, i) =>
      pingPhoneDirect(rig, DEVICE, { requestId: `req-hang-conc-${i}` }),
    );
    await settle();

    storage.hangNext("put", { key: "pending", times: 100 });
    storage.hangNext("put", { key: "metrics", times: 100 });
    storage.hangNext("getAlarm", { times: 100 });
    storage.hangNext("setAlarm", { times: 100 });

    const raced = await Promise.race([Promise.all(pings), settle(1200).then(() => "HUNG" as const)]);
    expect(raced).not.toBe("HUNG");
    if (raced === "HUNG") return;

    for (const outcome of raced) {
      expect(outcome.ok).toBe(false);
      if (!outcome.ok) expect(outcome.error.code).toBe("phone_timeout");
    }

    storage.clearInjectedFailures();
    const status = await phoneStatus(rig, DEVICE);
    expect(status["pending_requests"]).toBe(0);
    const metrics = status["metrics"] as Record<string, number>;
    // 3 个请求 = 恰好 3 次超时：存储再不返回，也不能多结算或少结算。
    expect(metrics["timeouts"]).toBe(3);
    expect(metrics["pongsMatched"]).toBe(0);
  });
});

describe("PhoneSession / 14. Gate 1 审计发现项 B：重复 request_id 不得覆盖", () => {
  it("同一个 request_id 的第二个未完成请求被拒绝为 409 duplicate_request_id", async () => {
    const rig = await makeRig({ vars: { PHONE_PING_TIMEOUT_MS: "300" } });
    const { socket } = await connectMockPhone(rig, DEVICE, "no-response");

    const first = pingPhoneDirect(rig, DEVICE, { requestId: "req-dup-id-1" });
    await settle();

    // 第二次用**同一个** request_id。修复前这里会把第一个 entry 覆盖掉：
    // 第一个的 Promise 永远不 resolve，而 settlePending 再也找不到它。
    const second = await rig.phone.fetchOn(
      DEVICE,
      new Request(`https://phone-session.internal${PHONE_ROUTES.ping}`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ requestId: "req-dup-id-1", deviceId: DEVICE }),
      }),
    );

    expect(second.status).toBe(409);
    const secondBody = (await second.json()) as { ok: boolean; error?: { code: string } };
    expect(secondBody.ok).toBe(false);
    expect(secondBody.error?.code).toBe("duplicate_request_id");

    // 第二个请求**没有**发出任何帧：手机不该收到两条同 id 的 phone_ping。
    const sent = framesOfType(socket, "phone_ping").filter((f) => f["request_id"] === "req-dup-id-1");
    expect(sent.length).toBe(1);

    // 第一个请求仍然活着，并照常走到超时 —— 它没有被替身或被遗忘。
    const firstOutcome = await first;
    expect(firstOutcome.ok).toBe(false);
    if (firstOutcome.ok) return;
    expect(firstOutcome.error.code).toBe("phone_timeout");

    const status = await phoneStatus(rig, DEVICE);
    expect(status["pending_requests"]).toBe(0);
    expect(status["pending_mirror"]).toBe(0);
    const metrics = status["metrics"] as Record<string, number>;
    expect(metrics["pendingRejectedDuplicate"]).toBe(1);
    expect(metrics["timeouts"]).toBe(1);
    expect(metrics["pongsMatched"]).toBe(0);
  });

  it("不同 request_id 不受影响：拒绝重复之后，新请求照常登记、发帧并超时", async () => {
    const rig = await makeRig({ vars: { PHONE_PING_TIMEOUT_MS: "200" } });
    const { socket } = await connectMockPhone(rig, DEVICE, "no-response");

    const first = pingPhoneDirect(rig, DEVICE, { requestId: "req-dup-guard-1" });
    await settle();

    const rejected = await pingPhoneDirect(rig, DEVICE, { requestId: "req-dup-guard-1" });
    expect(rejected.ok).toBe(false);
    if (!rejected.ok) expect(rejected.error.code).toBe("duplicate_request_id");

    // 换一个 id：正常登记、正常发帧 —— 拒绝重复不会波及其它请求。
    const fresh = pingPhoneDirect(rig, DEVICE, { requestId: "req-dup-guard-2" });
    await settle();

    expect(framesOfType(socket, "phone_ping").map((f) => f["request_id"])).toEqual([
      "req-dup-guard-1",
      "req-dup-guard-2",
    ]);

    const [a, b] = await Promise.all([first, fresh]);
    expect(a.ok).toBe(false);
    expect(b.ok).toBe(false);
    if (!b.ok) expect(b.error.code).toBe("phone_timeout");

    const metrics = ((await phoneStatus(rig, DEVICE))["metrics"]) as Record<string, number>;
    expect(metrics["pendingRejectedDuplicate"]).toBe(1);
    expect(metrics["timeouts"]).toBe(2);
    expect(metrics["pongsMatched"]).toBe(0);
  });
});

describe("PhoneSession / 15. Gate 1 审计发现项 C：DO 重建后的遗留 pending", () => {
  it("遗留镜像被丢弃，随后到达的同 id pong 既不匹配也不伪造成功", async () => {
    const rig = await makeRig({ vars: { PHONE_PING_TIMEOUT_MS: "500" } });

    // 先让**第一个**实例完整构造并装载结束（任何一次 fetch 都会 await this.ready）。
    // 不做这一步的话，种镜像与构造函数里的 blockConcurrencyWhile 会抢跑：
    // storage.put 的 map.set 是同步的，而构造函数的续体挂在微任务上，先后不确定。
    await phoneStatus(rig, DEVICE);

    // 手工种一条"上一世"留下的待处理镜像 —— 这正是 DO 被驱逐后存储里会有的东西：
    // 记录还在，但那个 await 它的调用栈已经不存在了。
    const state0 = rig.phone.ctxOf(DEVICE);
    await state0.storage.put("pending", {
      "req-ghost-1": {
        requestId: "req-ghost-1",
        deviceId: DEVICE,
        generation: 1,
        sentAt: Date.now() - 1000,
        deadline: Date.now() - 500,
        timeoutMs: 500,
      },
    });

    // 驱逐：内存全丢，持久化状态保留。
    rig.phone.evict(DEVICE);

    // 连上一台**不回 pong** 的手机（建连会把对象唤醒 → 触发重建）。
    const { socket } = await connectMockPhone(rig, DEVICE, "no-response");
    expect(rig.phone.constructionCount(DEVICE)).toBe(2);

    // 重建时应当把遗留镜像丢掉并记一笔，绝不"补一个成功"。
    let status = await phoneStatus(rig, DEVICE);
    let metrics = status["metrics"] as Record<string, number>;
    expect(metrics["reconstructedPendingFailed"]).toBe(1);
    expect(status["pending_requests"]).toBe(0);
    expect(status["pending_mirror"]).toBe(0);

    // 现在送来一条针对那个遗留 request_id 的**迟到 pong**。
    socket.send(JSON.stringify({ type: "phone_pong", request_id: "req-ghost-1", timestamp: Date.now() }));
    await settle();

    status = await phoneStatus(rig, DEVICE);
    metrics = status["metrics"] as Record<string, number>;
    expect(metrics["pongsMatched"]).toBe(0); // 绝不伪造成功
    expect(metrics["pongsUnknown"]).toBe(1); // 明确判为"没有配对的待处理请求"
    expect(metrics["timeouts"]).toBe(0); // 也不该有人替它多记一笔超时
    expect(status["pending_requests"]).toBe(0);

    const errors = framesOfType(socket, "error");
    expect(errors.some((e) => e["code"] === "unknown_request_id")).toBe(true);
    // 重建后没人发过 phone_ping —— 遗留请求绝不自动重放（R8）。
    expect(framesOfType(socket, "phone_ping").length).toBe(0);

    // 镜像必须被真正清空，否则每次重建都会重复记一笔。
    expect(await state0.storage.get<Record<string, unknown>>("pending")).toEqual({});
  });

  it("重建后同一 request_id 可以被**新的**请求使用（遗留的那个已经不存在了）", async () => {
    const rig = await makeRig({ vars: { PHONE_PING_TIMEOUT_MS: "400" } });

    // 同上：先等第一个实例构造完毕，保证种下的镜像一定被重建路径读到。
    await phoneStatus(rig, DEVICE);

    const state0 = rig.phone.ctxOf(DEVICE);
    await state0.storage.put("pending", {
      "req-ghost-2": {
        requestId: "req-ghost-2",
        deviceId: DEVICE,
        generation: 1,
        sentAt: Date.now() - 1000,
        deadline: Date.now() - 500,
        timeoutMs: 500,
      },
    });
    rig.phone.evict(DEVICE);

    await connectMockPhone(rig, DEVICE, "no-response");
    expect(rig.phone.constructionCount(DEVICE)).toBe(2);

    // 遗留表项已被丢弃，所以这个 id 在内存里是**空**的 —— 不该被误判成"重复"。
    const outcome = await pingPhoneDirect(rig, DEVICE, { requestId: "req-ghost-2", timeoutMs: 200 });
    expect(outcome.ok).toBe(false);
    if (outcome.ok) return;
    expect(outcome.error.code).toBe("phone_timeout");

    const metrics = ((await phoneStatus(rig, DEVICE))["metrics"]) as Record<string, number>;
    expect(metrics["pendingRejectedDuplicate"]).toBe(0);
    expect(metrics["pongsMatched"]).toBe(0);
    expect(metrics["reconstructedPendingFailed"]).toBe(1);
  });
});
