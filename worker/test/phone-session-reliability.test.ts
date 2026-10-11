// ============================================================================
//  PhoneSession —— Gate 1.5 阶段 1A：可靠性补强（竞态与结算路径）
// ============================================================================
//
//  这个文件**不重复** phone-session.test.ts 已经覆盖的东西。写之前先把已有覆盖
//  逐条对过一遍（见 Gate1.5-Stage1A-Reliability-Tests.md 的覆盖矩阵），只补
//  真正空着的四条：
//
//   R-B1  超时与 pong 在**边界**上竞争 —— 已有测试分别覆盖"超时"与"重复 pong"，
//         但没有一条把 pong 的时间点扫过 deadline 本身。这里做一次边界扫描，
//         断言的是**不变量**（恰好结算一次、表与镜像归零），不是"谁赢"。
//   R-B2  同一个 request_id 在第一个**仍未完成**时再次提交 —— 已有测试覆盖的是
//         409 本身，但没有验证"第一个请求不被影响、仍按自己的 timeout_ms 结算"。
//   R-B3  一个请求超时后，它的**迟到 pong** 在其它请求仍在等待时到达 —— 不得
//         串台、不得复活、不得污染 pending。
//   R-B6  发送失败路径 —— `handlePingRequest` 里发送是**直接 `socket.send()` 包在
//         try/catch 里**（不是 `safeSend()`），所以发送抛错必须当场以
//         `phone_disconnected` 结算。这条路径此前**一条测试都没有**。
//
//  ⚠ 跑在 Node 上的 Workers 运行时替身里，不是真实 workerd。见 TEST_REPORT.md。
// ============================================================================

import { describe, expect, it } from "vitest";
import {
  connectMockPhone,
  makeRig,
  phoneStatus,
  pingPhoneDirect,
  settle,
  TEST_DEVICE_ID,
} from "./harness/env";
import type { FakeWebSocket } from "./harness/runtime";

const DEVICE = TEST_DEVICE_ID;

/**
 * PhoneSession 落盘待处理镜像的键。
 * 与 `worker/src/phone/phone-session.ts:166` 的 `STORAGE_KEY_PENDING` 必须一致。
 */
const STORAGE_KEY_PENDING = "pending";

function metricsOf(status: Record<string, unknown>): Record<string, number> {
  return status["metrics"] as Record<string, number>;
}

/**
 * 有界等待。
 *
 * 用途只有一个：**等一个已经被排进事件队列的事件发生**（比如"迟到 pong 的定时器"），
 * 然后去断言不变量。它不用来"赌某个事件先发生"—— 谁先到由被测代码决定，
 * 断言只要求"无论谁先到，账都得对"。
 */
function waitAtLeast(ms: number): Promise<void> {
  return new Promise((r) => setTimeout(r, ms));
}

describe("PhoneSession / R-B1 超时与 pong 在边界上竞争", () => {
  // deadline 附近逐毫秒扫：-3 / -1 / 0 / +1 / +3。
  // 断言的是**不变量**：恰好结算一次、pending 表与落盘镜像都归零、
  // 迟到的那一帧只能落在"未知 request_id"上，绝不能二次结算。
  for (const delta of [-3, -1, 0, 1, 3]) {
    it(`pong 相对超时偏移 ${delta}ms：恰好结算一次，表与镜像都归零`, async () => {
      const rig = await makeRig();
      const timeoutMs = 120;
      const delayMs = Math.max(0, timeoutMs + delta);
      const { socket } = await connectMockPhone(rig, DEVICE, "late", { delayMs });

      const requestId = `req-boundary-${delta}`;
      const outcome = await pingPhoneDirect(rig, DEVICE, { requestId, timeoutMs });

      // 两种结局都合法（真实竞态里谁先到由调度决定），但**必须**是其中之一。
      if (outcome.ok) {
        expect(typeof outcome.rtt_ms).toBe("number");
        expect(outcome.request_id).toBe(requestId);
      } else {
        expect(outcome.error.code).toBe("phone_timeout");
      }

      // 给"迟到的那一帧"一个确定会到达的机会，再看账。
      await waitAtLeast(delayMs + 40);
      await settle();

      const status = await phoneStatus(rig, DEVICE);
      expect(status["pending_requests"]).toBe(0);
      expect(status["pending_mirror"]).toBe(0);

      const m = metricsOf(status);
      // 只发了一帧 ping。
      expect(m["pingsSent"]).toBe(1);
      // 恰好结算一次：成功与超时**不可能同时**计数。
      expect(m["pongsMatched"]! + m["timeouts"]!).toBe(1);
      // 输的那一帧只能被当成未知请求，且只算一次。
      const expectedUnknown = m["timeouts"] === 1 ? 1 : 0;
      expect(m["pongsUnknown"]).toBe(expectedUnknown);
      // 迟到帧不得被当成非法帧（那是另一条路径）。
      expect(m["pongsInvalid"]).toBe(0);
      // 手机侧一共只收到一帧 phone_ping（绝不重放）。
      expect(
        socket.receivedFrames
          .map((raw) => JSON.parse(raw) as Record<string, unknown>)
          .filter((f) => f["type"] === "phone_ping").length,
      ).toBe(1);
    });
  }
});

describe("PhoneSession / R-B2 同一个 request_id 在未完成时重复提交", () => {
  it("第二个被 409 拒绝，第一个不受影响、仍按自己的 timeout_ms 结算", async () => {
    const rig = await makeRig();
    // 手机故意不回 pong，好让第一个请求**保持未完成**，从而确定性地复现"重复提交"。
    await connectMockPhone(rig, DEVICE, "no-response");

    const requestId = "req-dup-live";
    const first = pingPhoneDirect(rig, DEVICE, { requestId, timeoutMs: 300 });
    await settle(); // 确保第一个已经登记进 pending（登记发生在任何 await 之前）

    // 第二个用**完全不同**的超时（5000ms）。如果它把第一个挤掉或覆盖掉，
    // 第一个的结算时长就会跟着变 —— 这是本用例真正要钉住的东西。
    const second = await pingPhoneDirect(rig, DEVICE, { requestId, timeoutMs: 5_000 });
    expect(second.ok).toBe(false);
    if (!second.ok) {
      expect(second.error.code).toBe("duplicate_request_id");
      expect(second.request_id).toBe(requestId);
    }

    const startedAt = Date.now();
    const firstOutcome = await first;
    const elapsed = Date.now() - startedAt;

    expect(firstOutcome.ok).toBe(false);
    if (!firstOutcome.ok) {
      expect(firstOutcome.error.code).toBe("phone_timeout");
      // 第一个请求仍按自己的 300ms 结算，没有被第二个的 5000ms 改写。
      expect(elapsed).toBeLessThan(2_000);
    }

    await settle();
    const status = await phoneStatus(rig, DEVICE);
    expect(status["pending_requests"]).toBe(0);
    expect(status["pending_mirror"]).toBe(0);

    const m = metricsOf(status);
    expect(m["pendingRejectedDuplicate"]).toBe(1);
    expect(m["timeouts"]).toBe(1);
    expect(m["pongsMatched"]).toBe(0);
  });
});

describe("PhoneSession / R-B3 超时请求的迟到 pong 不得污染仍在等待的其它请求", () => {
  it("三个并发请求：中间那个超时，迟到 pong 只算未知，不串台也不复活", async () => {
    const rig = await makeRig();
    // skipPingIndexes 按 ping 的**到达顺序**计数：
    //   index 0 → 正常回、index 1 → 不回（会超时）、index 2 → 正常回
    const { socket } = await connectMockPhone(rig, DEVICE, "normal", { skipPingIndexes: [1] });

    const idA = "req-pollute-a";
    const idB = "req-pollute-b";
    const idC = "req-pollute-c";

    const a = pingPhoneDirect(rig, DEVICE, { requestId: idA, timeoutMs: 5_000 });
    await settle();
    const b = pingPhoneDirect(rig, DEVICE, { requestId: idB, timeoutMs: 150 });
    await settle();
    const c = pingPhoneDirect(rig, DEVICE, { requestId: idC, timeoutMs: 5_000 });

    const [ra, rc] = await Promise.all([a, c]);
    expect(ra.ok).toBe(true);
    expect(rc.ok).toBe(true);
    if (ra.ok && rc.ok) {
      // 各回各的：两边的 request_id 与 RTT 都没有被换过。
      expect(ra.request_id).toBe(idA);
      expect(rc.request_id).toBe(idC);
      expect(ra.rtt_ms).toBeGreaterThanOrEqual(0);
      expect(rc.rtt_ms).toBeGreaterThanOrEqual(0);
    }

    const rb = await b;
    expect(rb.ok).toBe(false);
    if (!rb.ok) expect(rb.error.code).toBe("phone_timeout");

    const before = await phoneStatus(rig, DEVICE);
    const beforeMetrics = metricsOf(before);
    expect(before["pending_requests"]).toBe(0);

    // B 的迟到 pong —— 这是"超时已经结算之后才到"的那一帧。
    socket.send(JSON.stringify({ type: "phone_pong", request_id: idB, timestamp: Date.now() }));
    await settle();

    const after = await phoneStatus(rig, DEVICE);
    const afterMetrics = metricsOf(after);

    // 只多了一笔"未知 request_id"，没有多出任何成功。
    expect(afterMetrics["pongsUnknown"]).toBe(beforeMetrics["pongsUnknown"]! + 1);
    expect(afterMetrics["pongsMatched"]).toBe(2);
    expect(afterMetrics["timeouts"]).toBe(1);
    expect(afterMetrics["pongsInvalid"]).toBe(0);
    expect(afterMetrics["pongsStaleGeneration"]).toBe(0);
    expect(after["pending_requests"]).toBe(0);
    expect(after["pending_mirror"]).toBe(0);
  });
});

describe("PhoneSession / R-B6 phone_ping 发送失败路径", () => {
  it("server 侧 socket 已不可写：当场按 phone_disconnected 结算，不是干等到超时", async () => {
    const rig = await makeRig();
    const { socket, phone } = await connectMockPhone(rig, DEVICE, "normal");

    // 服务端持有的是 phone 侧 socket 的对端。
    const serverSide = socket.peer as FakeWebSocket | null;
    expect(serverSide).not.toBeNull();
    // CLOSING(2)：`FakeWebSocket.send()` 会抛 —— 与真实 workerd 里
    // "socket 正在关闭但 close 事件还没派发"是同一个窗口。
    (serverSide as FakeWebSocket).readyState = 2;

    // 前提校验：这条用例**确实**造出了"发送会抛"的条件。
    // 没有这一行，一次没生效的注入会让整条用例以错误的理由变绿。
    expect(() => (serverSide as FakeWebSocket).send("probe")).toThrow();

    const timeoutMs = 5_000;
    const startedAt = Date.now();
    const outcome = await pingPhoneDirect(rig, DEVICE, { requestId: "req-sendfail-1", timeoutMs });
    const elapsed = Date.now() - startedAt;

    expect(outcome.ok).toBe(false);
    if (!outcome.ok) {
      expect(outcome.error.code).toBe("phone_disconnected");
      // 错误消息是**固定文案**，绝不把 `String(err)` 带回来（审计 R2）。
      expect(outcome.error.message).toBe("发送 phone_ping 失败");
      expect(outcome.error.message).not.toContain("Error");
      expect(outcome.error.message).not.toContain("readyState");
    }

    // 关键区分：走的是"发送失败"而不是"超时"。5000ms 的超时如果生效，
    // 这里根本等不到。
    expect(elapsed).toBeLessThan(1_000);

    const status = await phoneStatus(rig, DEVICE);
    expect(status["pending_requests"]).toBe(0);
    expect(status["pending_mirror"]).toBe(0);
    const m = metricsOf(status);
    // 帧根本没发出去 ⇒ 不计"已发送"，也不计超时。
    expect(m["pingsSent"]).toBe(0);
    expect(m["timeouts"]).toBe(0);
    expect(m["pongsMatched"]).toBe(0);
    // 连接本身没有被这次发送失败弄坏。
    expect(status["connected"]).toBe(true);
    // 手机侧**一帧都没收到**：发送在 socket 层就失败了，帧没有离开服务端。
    expect(phone.pingCount).toBe(0);
  });

  it("发送失败后手机重连：新代次照常 ping 成功，失败路径不残留状态", async () => {
    const rig = await makeRig();
    const broken = await connectMockPhone(rig, DEVICE, "normal");
    (broken.socket.peer as FakeWebSocket).readyState = 2;

    const failed = await pingPhoneDirect(rig, DEVICE, { requestId: "req-sendfail-2", timeoutMs: 2_000 });
    expect(failed.ok).toBe(false);
    if (!failed.ok) expect(failed.error.code).toBe("phone_disconnected");

    // 手机重新连上（新代次）。
    const fresh = await connectMockPhone(rig, DEVICE, "normal");
    const ok = await pingPhoneDirect(rig, DEVICE, { requestId: "req-sendfail-3", timeoutMs: 2_000 });
    expect(ok.ok).toBe(true);
    if (ok.ok) {
      expect(ok.connection_generation).toBeGreaterThanOrEqual(2);
      expect(ok.rtt_ms).toBeGreaterThanOrEqual(0);
    }

    await settle();
    const status = await phoneStatus(rig, DEVICE);
    expect(status["pending_requests"]).toBe(0);
    expect(status["pending_mirror"]).toBe(0);
    expect(metricsOf(status)["pendingReplacedOnConnect"]).toBe(0);
    expect(fresh.phone.pingCount).toBe(1);
  });

  it("记账比超时还慢时：结算发生在发送之前，绝不发出没人认领的那一帧", async () => {
    const rig = await makeRig();
    const { socket, state, phone } = await connectMockPhone(rig, DEVICE, "normal");

    // 让落盘待处理镜像这一步慢到超过请求超时（timeoutMs 的下界是 50ms）。
    // 定时器在记账**之前**就已武装，所以超时会先到 —— 这正是
    // `pingsSettledBeforeSend` 分支存在的理由。
    state.storage.delayNext("put", { key: STORAGE_KEY_PENDING, delayMs: 400 });

    const startedAt = Date.now();
    const outcome = await pingPhoneDirect(rig, DEVICE, { requestId: "req-slowsink-1", timeoutMs: 50 });
    const elapsed = Date.now() - startedAt;

    expect(outcome.ok).toBe(false);
    if (!outcome.ok) expect(outcome.error.code).toBe("phone_timeout");
    // 结算是被超时定时器打出来的，不是等落盘写完。
    expect(elapsed).toBeGreaterThanOrEqual(40);

    await waitAtLeast(500);
    await settle();

    const status = await phoneStatus(rig, DEVICE);
    expect(status["pending_requests"]).toBe(0);
    expect(status["pending_mirror"]).toBe(0);

    const m = metricsOf(status);
    expect(m["pingsSettledBeforeSend"]).toBe(1);
    // **核心断言**：既然已经结算，就绝不能把 phone_ping 发出去 ——
    // 否则手机会回一个没人认领的 pong，并且手机侧的工作是白做的。
    expect(m["pingsSent"]).toBe(0);
    expect(phone.pingCount).toBe(0);
    expect(
      socket.receivedFrames
        .map((raw) => JSON.parse(raw) as Record<string, unknown>)
        .filter((f) => f["type"] === "phone_ping").length,
    ).toBe(0);
    expect(m["timeouts"]).toBe(1);
  });
});
