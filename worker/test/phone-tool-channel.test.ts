// ============================================================================
//  Gate 2 工作包 C —— Durable Object 上的通用工具通道
// ============================================================================
//
//  这一段测的是"ping 与 tool 两条通道共用同一台状态机"之后，账还对不对。
//
//  共用是有意的：`enqueueRequest()` 是全文件唯一一处"登记 → 发帧 → 等结果"
//  的实现。如果两条通道各写一份，任何一处顺序写错都会变成"只在某一条通道上
//  复现"的 bug —— 那是最难查的一类。
//
//  共用带来的风险同样要证明守住了：**两条通道不能互相结算**。
//  一条 phone_pong 不许结算 tool_request，一条 tool_result 不许结算 phone_ping，
//  哪怕它们的 request_id 恰好一样。
//
//  ⚠ 这个文件跑在 Node 上的 Workers 运行时**替身**里，不是真实 workerd。
// ============================================================================

import { describe, expect, it } from "vitest";
import {
  connectMockPhone,
  framesOfType,
  makeRig,
  phoneStatus,
  pingPhoneDirect,
  settle,
  TEST_DEVICE_ID,
  TEST_DEVICE_ID_2,
  toolPhoneDirect,
} from "./harness/env";

const DEVICE = TEST_DEVICE_ID;
const OTHER_DEVICE = TEST_DEVICE_ID_2;

const MOCK_BATTERY = {
  battery_level_percent: 42,
  charging: false,
  charging_source: "unavailable",
  power_save_mode: true,
};

describe("Gate 2 / C 工具通道：正常路径", () => {
  it("tool_request 走完整链路拿到 tool_result，并带上 RTT 与连接代次", async () => {
    const rig = await makeRig();
    const { phone } = await connectMockPhone(rig, DEVICE);

    const outcome = await toolPhoneDirect(rig, DEVICE, { toolName: "battery_get_status", timeoutMs: 2_000 });

    expect(outcome.ok).toBe(true);
    if (!outcome.ok) throw new Error("不该失败");
    expect(outcome.tool_name).toBe("battery_get_status");
    expect(outcome.device_id).toBe(DEVICE);
    expect(outcome.result).toEqual(MOCK_BATTERY);
    expect(outcome.connection_generation).toBe(1);
    expect(typeof outcome.rtt_ms).toBe("number");
    expect(typeof outcome.request_id).toBe("string");

    // 手机上确实收到了那一帧 —— "结果从手机来"这件事必须是可证的。
    const wireFrames = phone.received.map((raw) => JSON.parse(raw) as Record<string, unknown>);
    const toolRequests = wireFrames.filter((f) => f["type"] === "tool_request");
    expect(toolRequests.length).toBe(1);
    expect(toolRequests[0]!["tool_name"]).toBe("battery_get_status");

    const status = await phoneStatus(rig, DEVICE);
    const metrics = status["metrics"] as Record<string, number>;
    expect(metrics["toolRequestsSent"]).toBe(1);
    expect(metrics["toolResultsMatched"]).toBe(1);
    expect(status["pending_requests"]).toBe(0);
    expect(status["pending_mirror"]).toBe(0);
  });

  it("参数原样透传到手机（Worker 不替手机决定参数长什么样）", async () => {
    const rig = await makeRig();
    const { phone } = await connectMockPhone(rig, DEVICE);

    await toolPhoneDirect(rig, DEVICE, {
      toolName: "battery_get_status",
      arguments: { verbose: true },
      timeoutMs: 2_000,
    });

    const toolRequests = phone.received
      .map((raw) => JSON.parse(raw) as Record<string, unknown>)
      .filter((f) => f["type"] === "tool_request");
    expect(toolRequests[0]!["arguments"]).toEqual({ verbose: true });
  });

  it("`arguments` 不是对象时当成空对象，**不猜内容**（真正的校验在两端）", async () => {
    const rig = await makeRig();
    const { phone } = await connectMockPhone(rig, DEVICE);

    const outcome = await toolPhoneDirect(rig, DEVICE, {
      toolName: "battery_get_status",
      // 故意塞一个不是对象的东西
      arguments: "not-an-object" as unknown as Record<string, unknown>,
      timeoutMs: 2_000,
    });
    expect(outcome.ok).toBe(true);

    const toolRequests = phone.received
      .map((raw) => JSON.parse(raw) as Record<string, unknown>)
      .filter((f) => f["type"] === "tool_request");
    expect(toolRequests[0]!["arguments"]).toEqual({});
  });

  it("ping 与 tool 可以**同时在途**，各自被自己的 result 结算", async () => {
    const rig = await makeRig();
    await connectMockPhone(rig, DEVICE, "normal");

    const [ping, tool] = await Promise.all([
      pingPhoneDirect(rig, DEVICE, { requestId: "both-ping", timeoutMs: 3_000 }),
      toolPhoneDirect(rig, DEVICE, { requestId: "both-tool", toolName: "battery_get_status", timeoutMs: 3_000 }),
    ]);

    expect(ping.ok).toBe(true);
    expect(tool.ok).toBe(true);
    if (!tool.ok) throw new Error("不该失败");
    expect(tool.request_id).toBe("both-tool");
    expect(tool.result).toEqual(MOCK_BATTERY);

    const status = await phoneStatus(rig, DEVICE);
    const metrics = status["metrics"] as Record<string, number>;
    expect(metrics["pongsMatched"]).toBe(1);
    expect(metrics["toolResultsMatched"]).toBe(1);
    expect(status["pending_requests"]).toBe(0);
  });
});

describe("Gate 2 / C 工具通道：两条通道绝不互相结算", () => {
  it("phone_pong 不能结算同一 request_id 的 tool_request（一次都不行）", async () => {
    const rig = await makeRig();
    // ⚠ `scenario` 与 `toolBehavior` 是两个维度：`no-response` 只关掉了 pong。
    // 想在工具通道上制造"不回应"，必须另外显式传 toolBehavior。
    const { socket } = await connectMockPhone(rig, DEVICE, "no-response", { toolBehavior: "no-response" });

    const pending = toolPhoneDirect(rig, DEVICE, {
      requestId: "cross-channel-1",
      toolName: "battery_get_status",
      timeoutMs: 2_000,
    });
    await settle();

    // 一台被改坏/搞混的手机，把 tool_request 当 ping 回了 pong。
    socket.send(JSON.stringify({ type: "phone_pong", request_id: "cross-channel-1", timestamp: Date.now() }));

    const outcome = await pending;
    expect(outcome.ok).toBe(false);
    if (outcome.ok) throw new Error("不该成功");
    // 明确失败，而不是"假装成功"或"静默挂到超时"。
    expect(outcome.error.code).toBe("invalid_pong");
    expect(outcome.error.message).toContain("tool_request");

    const status = await phoneStatus(rig, DEVICE);
    const metrics = status["metrics"] as Record<string, number>;
    expect(metrics["pongsInvalid"]).toBe(1);
    expect(metrics["pongsMatched"]).toBe(0);
    expect(metrics["toolResultsMatched"]).toBe(0);
  });

  it("tool_result 不能结算同一 request_id 的 phone_ping", async () => {
    const rig = await makeRig();
    const { socket } = await connectMockPhone(rig, DEVICE, "no-response");

    const pending = pingPhoneDirect(rig, DEVICE, { requestId: "cross-channel-2", timeoutMs: 2_000 });
    await settle();

    socket.send(
      JSON.stringify({ type: "tool_result", request_id: "cross-channel-2", ok: true, result: { hello: "world" } }),
    );

    const outcome = await pending;
    expect(outcome.ok).toBe(false);
    if (outcome.ok) throw new Error("不该成功");
    expect(outcome.error.code).toBe("internal_error");

    const status = await phoneStatus(rig, DEVICE);
    const metrics = status["metrics"] as Record<string, number>;
    expect(metrics["toolResultsInvalid"]).toBe(1);
    expect(metrics["toolResultsMatched"]).toBe(0);
    expect(metrics["pongsMatched"]).toBe(0);
  });

  it("同一 request_id 已经在 ping 通道上挂着时，tool 通道再登记它会被 409 拒绝", async () => {
    const rig = await makeRig();
    await connectMockPhone(rig, DEVICE, "no-response");

    const ping = pingPhoneDirect(rig, DEVICE, { requestId: "shared-id", timeoutMs: 2_000 });
    await settle();

    const tool = await toolPhoneDirect(rig, DEVICE, {
      requestId: "shared-id",
      toolName: "battery_get_status",
      timeoutMs: 2_000,
    });
    expect(tool.ok).toBe(false);
    if (tool.ok) throw new Error("不该成功");
    expect(tool.error.code).toBe("duplicate_request_id");

    // 原来那个请求必须不受影响，仍然按自己的超时结算。
    const pingOutcome = await ping;
    expect(pingOutcome.ok).toBe(false);
    if (pingOutcome.ok) throw new Error("不该成功");
    expect(pingOutcome.error.code).toBe("phone_timeout");
  });
});

describe("Gate 2 / C 工具通道：异常与边界", () => {
  it("重复的 tool_result：只结算一次，第二次被记为 unknown 并回一条 error", async () => {
    const rig = await makeRig();
    const { socket, phone } = await connectMockPhone(rig, DEVICE, "normal", { toolBehavior: "duplicate" });

    const outcome = await toolPhoneDirect(rig, DEVICE, { toolName: "battery_get_status", timeoutMs: 2_000 });
    expect(outcome.ok).toBe(true);
    expect(phone.sentToolResults.length).toBe(2);

    const status = await phoneStatus(rig, DEVICE);
    const metrics = status["metrics"] as Record<string, number>;
    expect(metrics["toolResultsMatched"]).toBe(1);
    expect(metrics["toolResultsUnknown"]).toBeGreaterThanOrEqual(1);
    expect(status["pending_requests"]).toBe(0);
    expect(status["pending_mirror"]).toBe(0);

    // 服务端必须回一条 unknown_request_id，而不是默默丢掉 —— 否则手机
    // 无从知道自己多发了一条。
    const errors = framesOfType(socket, "error");
    expect(errors.some((e) => e["code"] === "unknown_request_id")).toBe(true);

    // 而且它还能继续正常工作。
    const again = await toolPhoneDirect(rig, DEVICE, { toolName: "battery_get_status", timeoutMs: 2_000 });
    expect(again.ok).toBe(true);
  });

  it("手机对 tool_request 完全不回应 → 如实 phone_timeout，且连接不被误关", async () => {
    const rig = await makeRig();
    await connectMockPhone(rig, DEVICE, "normal", { toolBehavior: "no-response" });

    const started = Date.now();
    const outcome = await toolPhoneDirect(rig, DEVICE, { toolName: "battery_get_status", timeoutMs: 400 });
    const elapsed = Date.now() - started;

    expect(outcome.ok).toBe(false);
    if (outcome.ok) throw new Error("不该成功");
    expect(outcome.error.code).toBe("phone_timeout");
    expect(outcome.error.message).toContain("battery_get_status");
    expect(elapsed).toBeGreaterThanOrEqual(350);

    const status = await phoneStatus(rig, DEVICE);
    const metrics = status["metrics"] as Record<string, number>;
    expect(metrics["timeouts"]).toBe(1);
    expect(metrics["toolResultsMatched"]).toBe(0);
    // 超时不该被当成"手机死了"。
    expect(status["connected"]).toBe(true);
    expect(metrics["livenessTimeouts"]).toBe(0);
    expect(status["pending_requests"]).toBe(0);
  });

  it("手机回了一个没配对过的 request_id → 记为 unknown，原请求如实超时", async () => {
    const rig = await makeRig();
    await connectMockPhone(rig, DEVICE, "normal", { toolBehavior: "wrong-request-id" });

    const outcome = await toolPhoneDirect(rig, DEVICE, { toolName: "battery_get_status", timeoutMs: 500 });
    expect(outcome.ok).toBe(false);
    if (outcome.ok) throw new Error("不该成功");
    expect(outcome.error.code).toBe("phone_timeout");

    const status = await phoneStatus(rig, DEVICE);
    const metrics = status["metrics"] as Record<string, number>;
    expect(metrics["toolResultsUnknown"]).toBeGreaterThanOrEqual(1);
    expect(metrics["toolResultsMatched"]).toBe(0);
  });

  it("缺 request_id 的 tool_result 被当成 malformed 拒绝，请求照常超时，服务端不崩", async () => {
    const rig = await makeRig();
    await connectMockPhone(rig, DEVICE, "normal", { toolBehavior: "malformed" });

    const outcome = await toolPhoneDirect(rig, DEVICE, { toolName: "battery_get_status", timeoutMs: 400 });
    expect(outcome.ok).toBe(false);
    if (outcome.ok) throw new Error("不该成功");
    expect(outcome.error.code).toBe("phone_timeout");

    const status = await phoneStatus(rig, DEVICE);
    const metrics = status["metrics"] as Record<string, number>;
    expect(metrics["messagesRejected"]).toBeGreaterThanOrEqual(1);
    expect(metrics["toolResultsMatched"]).toBe(0);
    // 服务端活着，而且还能继续处理请求。
    expect(status["connected"]).toBe(true);
    rig.phone.evict(DEVICE);
    expect(rig.phone.constructionCount(DEVICE)).toBe(1);
  });

  it("手机回 ok:false → 失败被如实转达，错误码原样保留", async () => {
    const rig = await makeRig();
    await connectMockPhone(rig, DEVICE, "normal", { toolBehavior: "error" });

    const outcome = await toolPhoneDirect(rig, DEVICE, { toolName: "battery_get_status", timeoutMs: 2_000 });
    expect(outcome.ok).toBe(false);
    if (outcome.ok) throw new Error("不该成功");
    expect(outcome.error.code).toBe("tool_failed");
    expect(outcome.error.message).toBe("模拟能力执行失败");
    expect(outcome.tool_name).toBe("battery_get_status");
  });

  it("手机回一个**不认识**的错误码 → 归一化成 tool_failed，绝不因为码陌生就丢帧", async () => {
    const rig = await makeRig();
    const { socket } = await connectMockPhone(rig, DEVICE, "no-response", { toolBehavior: "no-response" });

    const pending = toolPhoneDirect(rig, DEVICE, { requestId: "weird-code", toolName: "battery_get_status", timeoutMs: 2_000 });
    await settle();
    socket.send(
      JSON.stringify({
        type: "tool_result",
        request_id: "weird-code",
        ok: false,
        error: { code: "some_future_code_v9", message: "未来版本才会有的码" },
      }),
    );

    const outcome = await pending;
    expect(outcome.ok).toBe(false);
    if (outcome.ok) throw new Error("不该成功");
    // 丢帧会让这条请求白等到超时 —— 宁可归一化成一个已知的码。
    expect(outcome.error.code).toBe("tool_failed");
    expect(outcome.error.message).toBe("未来版本才会有的码");
  });

  it("手机白名单里没有的工具名 → unsupported_tool（不是静默成功，也不是 internal_error）", async () => {
    const rig = await makeRig();
    await connectMockPhone(rig, DEVICE, "normal");

    const outcome = await toolPhoneDirect(rig, DEVICE, { toolName: "definitely_not_a_tool", timeoutMs: 2_000 });
    expect(outcome.ok).toBe(false);
    if (outcome.ok) throw new Error("不该成功");
    expect(outcome.error.code).toBe("unsupported_tool");

    const status = await phoneStatus(rig, DEVICE);
    expect((status["metrics"] as Record<string, number>)["toolResultsMatched"]).toBe(1);
  });

  it("ping 与 tool 共用同一个并发额度，且拒绝时互不干扰", async () => {
    const rig = await makeRig({ vars: { MAX_PENDING_REQUESTS: "2" } });
    await connectMockPhone(rig, DEVICE, "no-response", { toolBehavior: "no-response" });

    const first = pingPhoneDirect(rig, DEVICE, { requestId: "cap-1", timeoutMs: 2_000 });
    await settle();
    const second = toolPhoneDirect(rig, DEVICE, { requestId: "cap-2", toolName: "battery_get_status", timeoutMs: 2_000 });
    await settle();

    const third = await toolPhoneDirect(rig, DEVICE, { requestId: "cap-3", toolName: "battery_get_status", timeoutMs: 2_000 });
    expect(third.ok).toBe(false);
    if (third.ok) throw new Error("不该成功");
    expect(third.error.code).toBe("too_many_pending_requests");

    const status = await phoneStatus(rig, DEVICE);
    expect(status["pending_requests"]).toBe(2);

    void first;
    void second;
  });

  it("新连接顶掉旧连接时，旧代次在途的 tool_request 被明确结算为 phone_replaced", async () => {
    const rig = await makeRig();
    await connectMockPhone(rig, DEVICE, "no-response", { toolBehavior: "no-response" });

    const pending = toolPhoneDirect(rig, DEVICE, { requestId: "gen-tool-1", toolName: "battery_get_status", timeoutMs: 5_000 });
    await settle();

    await connectMockPhone(rig, DEVICE, "normal");
    await settle();

    const outcome = await pending;
    expect(outcome.ok).toBe(false);
    if (outcome.ok) throw new Error("不该成功");
    expect(outcome.error.code).toBe("phone_replaced");

    // 而且新连接上的新请求照常工作。
    const fresh = await toolPhoneDirect(rig, DEVICE, { toolName: "battery_get_status", timeoutMs: 2_000 });
    expect(fresh.ok).toBe(true);
    if (!fresh.ok) throw new Error("不该失败");
    expect(fresh.connection_generation).toBe(2);
  });

  it("工具请求不污染别的设备：A 的 pending 与 B 的完全独立", async () => {
    const rig = await makeRig();
    await connectMockPhone(rig, DEVICE, "normal", { toolBehavior: "no-response" });
    await connectMockPhone(rig, OTHER_DEVICE, "normal");

    const stalled = toolPhoneDirect(rig, DEVICE, { toolName: "battery_get_status", timeoutMs: 400 });
    await settle();

    const other = await toolPhoneDirect(rig, OTHER_DEVICE, { toolName: "battery_get_status", timeoutMs: 2_000 });
    expect(other.ok).toBe(true);

    const stalledOutcome = await stalled;
    expect(stalledOutcome.ok).toBe(false);
    if (stalledOutcome.ok) throw new Error("不该成功");
    expect(stalledOutcome.error.code).toBe("phone_timeout");

    const statusOther = await phoneStatus(rig, OTHER_DEVICE);
    expect(statusOther["pending_requests"]).toBe(0);
    expect((statusOther["metrics"] as Record<string, number>)["timeouts"]).toBe(0);
    expect(statusOther["connected"]).toBe(true);
  });

  it("DTO 重建之后，遗留的 tool pending 记为失败而不是虚假成功", async () => {
    const rig = await makeRig();
    await connectMockPhone(rig, DEVICE, "no-response", { toolBehavior: "no-response" });

    const pending = toolPhoneDirect(rig, DEVICE, { requestId: "evict-tool-1", toolName: "battery_get_status", timeoutMs: 5_000 });
    await settle();

    // 驱逐之前，存储里的镜像必须已经写下去了 —— 否则"重建时能发现遗留"
    // 这件事根本无从谈起。
    const state = rig.phone.ctxOf(DEVICE);
    expect(Object.keys((await state.storage.get<Record<string, unknown>>("pending")) ?? {})).toContain(
      "evict-tool-1",
    );

    // 在途时驱逐 —— 内存里的 timer 与 pending 表全丢，存储里的镜像还在。
    rig.phone.evict(DEVICE);

    // 下一次请求唤醒对象（触发重建）。
    const status = await phoneStatus(rig, DEVICE);
    const metrics = status["metrics"] as Record<string, number>;
    expect(metrics["reconstructedPendingFailed"]).toBeGreaterThanOrEqual(1);
    expect(status["pending_mirror"]).toBe(0);
    expect(await state.storage.get<Record<string, unknown>>("pending")).toEqual({});

    // ⚠ 保真度缺口（阶段 0 审计的 R-3）：Node 替身里，被驱逐实例的本地
    // setTimeout 还在，所以这条请求最终由它结算成 phone_timeout；
    // 真实 workerd 驱逐会带走内存定时器与在途 fetch，那边应当以
    // phone_disconnected 结束。两种都必须是**失败**，绝不能是虚假成功，
    // 也不能挂着不还。
    const outcome = await pending;
    expect(outcome.ok).toBe(false);
    if (outcome.ok) throw new Error("不该成功");
    expect(["phone_timeout", "phone_disconnected", "phone_replaced"]).toContain(outcome.error.code);
    expect(metrics["toolResultsMatched"]).toBe(0);
  });
});
