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
  fingerprintOf,
  framesOfType,
  makeRig,
  phoneStatus,
  pingPhoneDirect,
  revokePhoneDirect,
  setCredentials,
  settle,
  TEST_DEVICE_ID,
  TEST_DEVICE_ID_2,
  TEST_DEVICE_TOKEN,
  TEST_DEVICE_TOKEN_2,
  toolPhoneDirect,
  upgradePhoneDirect,
} from "./harness/env";
import { notifyRevocationInBackground } from "../src/mcp/http";
import { readFileSync } from "node:fs";
import { join } from "node:path";

const DEVICE = TEST_DEVICE_ID;
const OTHER_DEVICE = TEST_DEVICE_ID_2;

/** 1 秒是本仓库 `CLAMPS.livenessTimeoutMs` 的下限 —— 测试用它把等待压到最短。 */
const TIGHT_LIVENESS_MS = 1_000;

function sleep(ms: number): Promise<void> {
  return new Promise((r) => setTimeout(r, ms));
}

type Rig = Awaited<ReturnType<typeof makeRig>>;

/**
 * ★ 集中审计问题 1/2 之后，"撤销"这件事有两个**必须分开**的层面：
 *
 *   1. **门禁**（配置层）：这台设备此刻还有没有有效凭据。判据是当前配置，
 *      所以它可逆 —— 补一条新凭据就是重新授权。
 *   2. **推送**（DO 层）：`/internal/phone/revoke` 通知。它负责立刻终结已建立的
 *      连接、结算在途请求、换代次、记审计。它**不是**门禁（否则就成了那个
 *      永远回不去的粘性布尔）。
 *
 * 生产链路上两者总是同时发生（Worker 只有在配置说"已撤销"时才会推），所以
 * 测试要断言真实语义就必须把两边都摆出来 —— 只推配置不改，或只改配置不推，
 * 都只是这套机制的一半。
 */
async function revokeInConfig(rig: Rig): Promise<void> {
  await setCredentials(rig, [
    { deviceId: DEVICE, token: TEST_DEVICE_TOKEN, revokedAt: "2026-01-02T00:00:00Z" },
    { deviceId: OTHER_DEVICE, token: TEST_DEVICE_TOKEN_2 },
  ]);
}

// ---------------------------------------------------------------------------
//  A1 凭据撤销
// ---------------------------------------------------------------------------

describe("Gate 1.5 / A1 凭据撤销", () => {
  it("配置里没有有效凭据 ⇒ 新升级被拒：403 device_revoked，且计入 upgradesRejected", async () => {
    const rig = await makeRig({
      deviceTokens: [
        { deviceId: DEVICE, token: TEST_DEVICE_TOKEN, revokedAt: "2026-01-02T00:00:00Z" },
        { deviceId: OTHER_DEVICE, token: TEST_DEVICE_TOKEN_2 },
      ],
    });

    // 撤销之后同一台设备再升级 —— 必须被 DO 自己挡掉，而不是靠 Worker 的 401。
    // （用 `upgradePhoneDirect`：被拒的响应里根本没有 socket，摸 webSocket 会崩。）
    //
    // 显式带上那条**已撤销**凭据的指纹：这正是手机还在用旧令牌重连的形状。
    const second = await upgradePhoneDirect(rig, DEVICE, {
      credentialFingerprint: fingerprintOf(rig, DEVICE, TEST_DEVICE_TOKEN),
    });
    expect(second.status).toBe(403);
    const body = (await second.json()) as Record<string, unknown>;
    expect(body["error"]).toBe("device_revoked");

    const status = await phoneStatus(rig, DEVICE);
    const metrics = status["metrics"] as Record<string, number>;
    expect(metrics["upgradesRejected"]).toBe(1);
    // ⚠ 阴性断言：配置态的撤销**不经过**撤销推送，所以 revocationsApplied 必须
    //    还是 0。"谁改了这个数"要能分得清。
    expect(metrics["revocationsApplied"]).toBe(0);
  });

  it("升级请求不带内部凭据指纹时 fail-closed：500，绝不建立无法复核来源的长连接", async () => {
    const rig = await makeRig();
    const response = await upgradePhoneDirect(rig, DEVICE, { credentialFingerprint: null });
    expect(response.status).toBe(500);
    const body = (await response.json()) as Record<string, unknown>;
    expect(body["error"]).toBe("missing_internal_credential_header");

    const status = await phoneStatus(rig, DEVICE);
    expect(status["socket_open"]).toBe(false);
    expect((status["metrics"] as Record<string, number>)["upgradesRejected"]).toBe(1);
  });

  it("拿另一台设备的凭据指纹来连这台设备：403 credential_revoked（跨设备冒名也不行）", async () => {
    const rig = await makeRig();
    const response = await upgradePhoneDirect(rig, DEVICE, {
      credentialFingerprint: fingerprintOf(rig, OTHER_DEVICE, TEST_DEVICE_TOKEN_2),
    });
    expect(response.status).toBe(403);
    const body = (await response.json()) as Record<string, unknown>;
    expect(body["error"]).toBe("credential_revoked");
    expect((await phoneStatus(rig, DEVICE))["socket_open"]).toBe(false);
  });

  it("撤销会主动关掉**已建立**的连接（D-1 的核心）：先收到一条 error，再以 4002 关闭", async () => {
    const rig = await makeRig();
    const { socket } = await connectMockPhone(rig, DEVICE);
    expect(socket.readyState).toBe(1);

    // 生产顺序：Worker 先在配置层看到"这台设备没有有效凭据了"，然后把撤销推给 DO。
    await revokeInConfig(rig);
    const revoked = await revokePhoneDirect(rig, DEVICE);
    expect(revoked.status).toBe(200);
    expect(revoked.body["ok"]).toBe(true);
    expect(revoked.body["revoked"]).toBe(true);
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
    expect(status["socket_open"]).toBe(false);
    // ★ 连接已经被关掉了，但 status 仍然要说得出"这台设备现在被撤销了" ——
    //   判据是配置（设备身份退回最近一次建连的那台），不是"还有没有 socket"。
    expect(status["revoked"]).toBe(true);
    expect((status["revocation"] as Record<string, unknown>)["scope"]).toBe("device");
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

    // ★ Gate 2：撤销通知只在"目标此刻仍然无效"时才会执行（见 handleRevoke 的
    //   `targetStillInvalid`）。所以必须先让配置层失效 —— 这也正是生产顺序：
    //   Worker 只有在配置里查出来"已撤销"时才会推这条通知。
    await revokeInConfig(rig);
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

  it("撤销之后的新请求被拒（403 + requestsRejectedRevoked）：判据是**当前配置**，不是粘性布尔", async () => {
    const rig = await makeRig();
    const { socket } = await connectMockPhone(rig, DEVICE);
    expect(socket.readyState).toBe(1);

    // ★ 这是集中审计问题 1 的核心断言：连接建立**之后**配置里那条凭据被撤销。
    //   旧实现只看一个落盘的布尔值，这条请求会**成功**发到已经不该被服务的手机；
    //   现在请求入口每次都重新读配置，当场拒绝，并把那条连接一并终结。
    await revokeInConfig(rig);

    const outcome = await pingPhoneDirect(rig, DEVICE, { timeoutMs: 1_000 });
    expect(outcome.ok).toBe(false);
    if (outcome.ok) throw new Error("不该成功");
    // 必须报"被撤销"而不是"离线"：把撤销藏成 phone_offline 会让运维
    // 一直去查网络，永远查不到真正的原因。
    expect(outcome.error.code).toBe("device_revoked");
    expect(outcome.error.message).toContain("撤销");

    await settle();
    // 请求路径发现失效之后必须**顺手终结**那条连接，而不是留一条能继续
    // 收发帧的僵尸连接。
    expect(socket.readyState).toBe(3);
    expect(socket.closeInfo?.code).toBe(4002);

    const status = await phoneStatus(rig, DEVICE);
    const metrics = status["metrics"] as Record<string, number>;
    expect(metrics["requestsRejectedRevoked"]).toBe(1);
    // 明文令牌之外的阴性断言：它**没有**被发出去过（`pingsSent` 不涨），
    // 也没有被当成超时结算。
    expect(metrics["pingsSent"]).toBe(0);
    expect(metrics["timeouts"]).toBe(0);
  });

  it("撤销一台设备**不影响**另一台：它的连接、状态、请求全部照常", async () => {
    const rig = await makeRig();
    const victim = await connectMockPhone(rig, DEVICE, "no-response");
    const bystander = await connectMockPhone(rig, OTHER_DEVICE);

    // ★ Gate 2：撤销通知只在"目标此刻仍然无效"时才会执行（见 handleRevoke 的
    //   `targetStillInvalid`）。所以必须先让配置层失效 —— 这也正是生产顺序：
    //   Worker 只有在配置里查出来"已撤销"时才会推这条通知。
    await revokeInConfig(rig);
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

  it("撤销状态跨对象重建仍然生效：门禁来自配置，审计记录落盘", async () => {
    const rig = await makeRig();
    await connectMockPhone(rig, DEVICE);
    await revokeInConfig(rig);
    await revokePhoneDirect(rig, DEVICE);
    expect(rig.phone.constructionCount(DEVICE)).toBe(1);

    // 模拟驱逐：内存全丢，存储保留。
    rig.phone.evict(DEVICE);
    expect(rig.phone.constructionCount(DEVICE)).toBe(1);

    const status = await phoneStatus(rig, DEVICE);
    expect(rig.phone.constructionCount(DEVICE)).toBe(2);
    // ★ 两条独立的证据：门禁（配置里没有有效凭据）与审计（撤销记录从存储里读回来）。
    expect(status["revoked"]).toBe(true);
    expect((status["revocation"] as Record<string, unknown>)["scope"]).toBe("device");

    const reconnect = await upgradePhoneDirect(rig, DEVICE, {
      credentialFingerprint: fingerprintOf(rig, DEVICE, TEST_DEVICE_TOKEN),
    });
    expect(reconnect.status).toBe(403);
  });

  it("testing/reset **不能**清掉撤销状态（否则它就是一条绕过撤销的后门）", async () => {
    const rig = await makeRig();
    await connectMockPhone(rig, DEVICE);
    await revokeInConfig(rig);
    await revokePhoneDirect(rig, DEVICE);

    const reset = await rig.phone.fetchOn(DEVICE, new Request(`https://phone-session.internal${"/internal/phone/testing/reset"}`, { method: "POST" }));
    expect(reset.status).toBe(200);
    const body = (await reset.json()) as Record<string, unknown>;
    expect(body["revoked"]).toBe(true);
    expect((body["revocation"] as Record<string, unknown>)["scope"]).toBe("device");

    const status = await phoneStatus(rig, DEVICE);
    expect(status["revoked"]).toBe(true);
    expect(status["revocation"]).not.toBeNull();

    const reconnect = await upgradePhoneDirect(rig, DEVICE, {
      credentialFingerprint: fingerprintOf(rig, DEVICE, TEST_DEVICE_TOKEN),
    });
    expect(reconnect.status).toBe(403);
  });

  it("重复撤销是幂等的：指标只加一次，连接不会被重复关", async () => {
    const rig = await makeRig();
    const { socket } = await connectMockPhone(rig, DEVICE);

    // ★ Gate 2：撤销通知只在"目标此刻仍然无效"时才会执行（见 handleRevoke 的
    //   `targetStillInvalid`）。所以必须先让配置层失效 —— 这也正是生产顺序：
    //   Worker 只有在配置里查出来"已撤销"时才会推这条通知。
    await revokeInConfig(rig);
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

    // ★ Gate 2：撤销通知只在"目标此刻仍然无效"时才会执行（见 handleRevoke 的
    //   `targetStillInvalid`）。所以必须先让配置层失效 —— 这也正是生产顺序：
    //   Worker 只有在配置里查出来"已撤销"时才会推这条通知。
    await revokeInConfig(rig);
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
//  A1 · 集中审计问题 2：撤销必须**可逆**（重新授权），且轮换不得误伤
// ---------------------------------------------------------------------------

describe("Gate 1.5 / A1 撤销是可逆的：重新授权转换与凭据轮换", () => {
  it("撤销之后补一条新凭据 ⇒ 重新授权：能连上、能服务请求，并把撤销记录结算掉", async () => {
    const rig = await makeRig();
    const first = await connectMockPhone(rig, DEVICE);
    const oldFingerprint = fingerprintOf(rig, DEVICE, TEST_DEVICE_TOKEN)!;

    // 完整的撤销：配置层（门禁）+ 推送（终结连接与在途请求）。
    await revokeInConfig(rig);
    await revokePhoneDirect(rig, DEVICE);
    await settle();
    expect(first.socket.readyState).toBe(3);
    expect((await phoneStatus(rig, DEVICE))["revoked"]).toBe(true);

    // ★ 运维给这台设备配了一条**新**令牌 —— 这就是"同一把设备的新凭据"，
    //   也是"撤销不是永久死刑"的全部依据。
    const NEW_TOKEN = "AUDIT-FAKE-NEW-DEVICE-TOKEN";
    await setCredentials(rig, [
      { deviceId: DEVICE, token: NEW_TOKEN },
      { deviceId: OTHER_DEVICE, token: TEST_DEVICE_TOKEN_2 },
    ]);

    const reconnected = await connectMockPhone(rig, DEVICE, "normal", {
      credentialFingerprint: fingerprintOf(rig, DEVICE, NEW_TOKEN),
    });
    expect(reconnected.response.status).toBe(101);

    // 撤销记录必须被**明确结算**：留着一张"这设备被撤销过"的旧记录，
    // 就是把人送回审计指控的那个"粘性布尔"。
    const status = await phoneStatus(rig, DEVICE);
    expect(status["revoked"]).toBe(false);
    expect(status["revocation"]).toBeNull();
    const m = status["metrics"] as Record<string, number>;
    expect(m["reauthorizationsApplied"]).toBe(1);

    // 重新授权之后，请求必须真的能通 —— 只改状态不恢复服务等于没修。
    const outcome = await pingPhoneDirect(rig, DEVICE, { timeoutMs: 2_000 });
    expect(outcome.ok).toBe(true);

    // 旧指纹（已撤销的那条）仍然进不来：可逆不等于旧凭据复活。
    const withOld = await upgradePhoneDirect(rig, DEVICE, { credentialFingerprint: oldFingerprint });
    expect(withOld.status).toBe(403);
    const body = (await withOld.json()) as Record<string, unknown>;
    expect(body["error"]).toBe("credential_revoked");
  });

  it("凭据轮换：旧凭据的连接被终结，新凭据的连接照常服务，迟到通知**不会**误伤新连接", async () => {
    const rig = await makeRig();
    const OLD_TOKEN = TEST_DEVICE_TOKEN;
    const NEW_TOKEN = "AUDIT-FAKE-ROTATED-TOKEN";

    await connectMockPhone(rig, DEVICE);
    const oldFingerprint = fingerprintOf(rig, DEVICE, OLD_TOKEN)!;

    // 轮换：旧凭据被标撤销，新凭据加进来。设备**没有**被撤销。
    await setCredentials(rig, [
      { deviceId: DEVICE, token: OLD_TOKEN, revokedAt: "2026-01-02T00:00:00Z" },
      { deviceId: DEVICE, token: NEW_TOKEN },
      { deviceId: OTHER_DEVICE, token: TEST_DEVICE_TOKEN_2 },
    ]);

    // 一台还在用旧令牌的手机尝试重连时，Worker 会推一条**凭据级**撤销。
    const rotated = await revokePhoneDirect(rig, DEVICE, "upgrade_with_rotated_credential", {
      credentialId: oldFingerprint,
    });
    expect(rotated.status).toBe(200);
    expect(rotated.body["scope"]).toBe("credential");
    expect(rotated.body["closed"]).toBe(1);
    await settle();

    // 新凭据立刻就能连上，并且**设备级**状态是"没被撤销" ——
    // 轮换不是撤销，把它算成撤销会让所有轮换都变成一次故障。
    const fresh = await connectMockPhone(rig, DEVICE, "normal", {
      credentialFingerprint: fingerprintOf(rig, DEVICE, NEW_TOKEN),
    });
    expect(fresh.response.status).toBe(101);

    const status = await phoneStatus(rig, DEVICE);
    expect(status["revoked"]).toBe(false);
    expect(status["bound_credential_active"]).toBe(true);

    // ★ 要害：一条针对**旧**凭据的迟到/重复通知又来了。
    //   它必须只影响那条早就不存在的连接 —— 既不能关掉新连接，也不能把
    //   新连接上的在途请求判死（否则撤销通知本身就是一条 DoS 路径）。
    const inFlight = pingPhoneDirect(rig, DEVICE, { requestId: "req-after-rotated", timeoutMs: 3_000 });
    const late = await revokePhoneDirect(rig, DEVICE, "late_duplicate_of_rotation", {
      credentialId: oldFingerprint,
    });
    expect(late.status).toBe(200);
    expect(late.body["closed"]).toBe(0);

    const outcome = await inFlight;
    expect(outcome.ok).toBe(true);
    expect(fresh.socket.readyState).toBe(1);
    expect((await phoneStatus(rig, DEVICE))["connected"]).toBe(true);

    // 新连接这一刻仍然是"用有效凭据开的"，所以它不该被拒绝服务。
    const after = await pingPhoneDirect(rig, DEVICE, { requestId: "req-after-late", timeoutMs: 3_000 });
    expect(after.ok).toBe(true);
  });

  it("拿被轮换掉的旧凭据去连：403 credential_revoked（而不是 device_revoked）", async () => {
    const rig = await makeRig();
    const OLD_TOKEN = TEST_DEVICE_TOKEN;
    const NEW_TOKEN = "AUDIT-FAKE-ROTATED-TOKEN-2";
    await setCredentials(rig, [
      { deviceId: DEVICE, token: OLD_TOKEN, revokedAt: "2026-01-02T00:00:00Z" },
      { deviceId: DEVICE, token: NEW_TOKEN },
      { deviceId: OTHER_DEVICE, token: TEST_DEVICE_TOKEN_2 },
    ]);

    // 两种拒绝必须分得清：设备级 = "这台设备没了"，凭据级 = "这把钥匙换掉了"。
    // 后者如果报成前者，运维会去查错方向。
    const response = await upgradePhoneDirect(rig, DEVICE, {
      credentialFingerprint: fingerprintOf(rig, DEVICE, OLD_TOKEN),
    });
    expect(response.status).toBe(403);
    const body = (await response.json()) as Record<string, unknown>;
    expect(body["error"]).toBe("credential_revoked");

    // 而设备级撤销是**另一种**形状。
    await setCredentials(rig, [
      { deviceId: DEVICE, token: OLD_TOKEN, revokedAt: "2026-01-02T00:00:00Z" },
      { deviceId: OTHER_DEVICE, token: TEST_DEVICE_TOKEN_2 },
    ]);
    const deviceWide = await upgradePhoneDirect(rig, DEVICE, {
      credentialFingerprint: fingerprintOf(rig, DEVICE, OLD_TOKEN),
    });
    expect(deviceWide.status).toBe(403);
    expect(((await deviceWide.json()) as Record<string, unknown>)["error"]).toBe("device_revoked");
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

    // 只改配置、**不推**撤销通知：连接此刻还开着。这是集中审计问题 1 最尖锐的
    // 形状 —— 旧实现只看落盘的粘性布尔值（这时还是 false），于是会把这个工具
    // 调用原封不动转发给一台已经不该被服务的手机。
    await revokeInConfig(rig);

    const outcome = await toolPhoneDirect(rig, DEVICE, { toolName: "battery_get_status", timeoutMs: 1_000 });
    expect(outcome.ok).toBe(false);
    if (outcome.ok) throw new Error("不该成功");
    // 必须报"被撤销"：报 phone_offline 会把人送去查网络，而设备其实好好连着。
    expect(outcome.error.code).toBe("device_revoked");

    // 阴性断言：既没转发给手机，也没留下待结算的请求。
    const m = (await phoneStatus(rig, DEVICE))["metrics"] as Record<string, number>;
    expect(m["requestsRejectedRevoked"]).toBe(1);
    expect(m["toolRequestsSent"]).toBe(0);
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

// ---------------------------------------------------------------------------
//  Gate 2 · 迟到的撤销通知
// ---------------------------------------------------------------------------
//
//  撤销通知是**异步、跨 DO、可能迟到**的：它由 `worker/src/index.ts` 在"拒绝一次
//  升级"时推出来，要跨一次 HTTP、并排在这个 DO 正在处理的其他请求后面。在这段路
//  上配置完全可能已经变了。这一组用例把那条时间线摆出来：
//
//     旧配置发出通知 → 配置层重新有效（补新凭据 / 把凭据重新启用）
//       → 手机连上来 → 旧通知才到
//
//  要求：**过期**的通知不得终结连接、不得结算在途请求、不得记成一次真实撤销；
//  而"配置里确实仍然无效"的通知必须照常终结 —— 两条方向都要有断言，只测一边
//  等于把撤销能力整个拆掉却还显示绿灯。
// ---------------------------------------------------------------------------

describe("Gate 2 迟到的撤销通知：过期通知不得误伤，仍然有效的通知照常终结", () => {
  it("迟到的**设备级**通知不得误伤已重新授权的连接：新连接与在途请求都继续工作", async () => {
    const rig = await makeRig();

    // ① 旧配置：这台设备没有任何有效凭据 —— 那次被拒的升级就是从这里发出通知的。
    await revokeInConfig(rig);

    // ② 运维补了一条新凭据，设备重新有效。⚠ 此刻那条旧通知**还在路上**。
    const NEW_TOKEN = "GATE2-FAKE-LATE-NOTIFY-TOKEN";
    await setCredentials(rig, [
      { deviceId: DEVICE, token: NEW_TOKEN },
      { deviceId: OTHER_DEVICE, token: TEST_DEVICE_TOKEN_2 },
    ]);

    // ③ 手机用新凭据连上来。这条连接完全合法。
    const fresh = await connectMockPhone(rig, DEVICE, "normal", {
      credentialFingerprint: fingerprintOf(rig, DEVICE, NEW_TOKEN),
    });
    expect(fresh.response.status).toBe(101);
    expect(fresh.socket.readyState).toBe(1);

    // ④ 在途请求也一起摆好：迟到的通知绝不能把它判死（否则通知本身成了 DoS 路径）。
    const inFlight = pingPhoneDirect(rig, DEVICE, { requestId: "req-late-device-notify", timeoutMs: 3_000 });

    // ⑤ 旧配置那条设备级通知现在才到。
    const late = await revokePhoneDirect(rig, DEVICE, "late_device_notification");
    expect(late.status).toBe(200);
    expect(late.body["ok"]).toBe(true);
    // ★ 要害一：没有执行任何撤销动作。
    expect(late.body["revoked"]).toBe(false);
    expect(late.body["stale"]).toBe(true);
    expect(late.body["closed"]).toBe(0);
    expect(late.body["pending_failed"]).toBe(0);

    // ★ 要害二：在途请求必须照常成功 —— 不是"快速失败得好看"，是真的一点没受影响。
    const outcome = await inFlight;
    expect(outcome.ok).toBe(true);

    await settle();
    expect(fresh.socket.readyState).toBe(1);
    const status = await phoneStatus(rig, DEVICE);
    expect(status["connected"]).toBe(true);
    expect(status["revoked"]).toBe(false);
    // ★ 要害三：过期投递**不许**记成一次真实安全事件，否则
    //   `revocationsApplied` 与 `pendingRevoked` 就不再是运维能信的观测量。
    const metrics = status["metrics"] as Record<string, number>;
    expect(metrics["revocationsApplied"]).toBe(0);
    expect(metrics["pendingRevoked"]).toBe(0);
    expect(status["revocation"]).toBeNull();
  });

  it("迟到的**凭据级**通知不得误伤重新有效的凭据：那条凭据的连接照常服务", async () => {
    const rig = await makeRig();
    const OLD_TOKEN = TEST_DEVICE_TOKEN;
    const conn = await connectMockPhone(rig, DEVICE, "normal");
    const fingerprint = fingerprintOf(rig, DEVICE, OLD_TOKEN)!;

    // ① 这条凭据一度被标撤销（那正是"轮换掉了、会发凭据级通知"的时刻）……
    await setCredentials(rig, [
      { deviceId: DEVICE, token: OLD_TOKEN, revokedAt: "2026-01-02T00:00:00Z" },
      { deviceId: OTHER_DEVICE, token: TEST_DEVICE_TOKEN_2 },
    ]);
    // ② ……但运维把这次轮换回滚了，同一把令牌重新变成有效。旧通知仍在路上。
    await setCredentials(rig, [
      { deviceId: DEVICE, token: OLD_TOKEN },
      { deviceId: OTHER_DEVICE, token: TEST_DEVICE_TOKEN_2 },
    ]);

    const inFlight = pingPhoneDirect(rig, DEVICE, { requestId: "req-late-credential-notify", timeoutMs: 3_000 });
    const late = await revokePhoneDirect(rig, DEVICE, "late_credential_notification", {
      credentialId: fingerprint,
    });
    expect(late.status).toBe(200);
    expect(late.body["scope"]).toBe("credential");
    expect(late.body["revoked"]).toBe(false);
    expect(late.body["stale"]).toBe(true);
    expect(late.body["closed"]).toBe(0);
    expect(late.body["pending_failed"]).toBe(0);

    const outcome = await inFlight;
    expect(outcome.ok).toBe(true);
    await settle();
    expect(conn.socket.readyState).toBe(1);
    expect(conn.socket.closeInfo ?? null).toBeNull();

    const status = await phoneStatus(rig, DEVICE);
    expect(status["bound_credential_active"]).toBe(true);
    expect((status["metrics"] as Record<string, number>)["revocationsApplied"]).toBe(0);
  });

  it("正向对照：设备**确实**仍被撤销时，通知照常终结连接与在途请求", async () => {
    const rig = await makeRig();
    await connectMockPhone(rig, DEVICE, "no-response");

    const inFlight = pingPhoneDirect(rig, DEVICE, { requestId: "req-device-still-revoked", timeoutMs: 10_000 });
    await settle();
    expect((await phoneStatus(rig, DEVICE))["pending_requests"]).toBe(1);

    // 配置层撤销 + 通知。这一次通知到达时目标**仍然**无效 —— 必须执行。
    await revokeInConfig(rig);
    const revoked = await revokePhoneDirect(rig, DEVICE, "device_still_revoked");
    expect(revoked.status).toBe(200);
    expect(revoked.body["revoked"]).toBe(true);
    expect(revoked.body["stale"]).toBeUndefined();
    expect(revoked.body["closed"]).toBe(1);
    expect(revoked.body["pending_failed"]).toBe(1);

    const outcome = await inFlight;
    expect(outcome.ok).toBe(false);
    if (outcome.ok) throw new Error("不该成功");
    expect(outcome.error.code).toBe("device_revoked");
  });

  it("正向对照：那条凭据**确实**已撤销时，凭据级通知照常终结它的连接与在途请求", async () => {
    const rig = await makeRig();
    const OLD_TOKEN = TEST_DEVICE_TOKEN;
    const conn = await connectMockPhone(rig, DEVICE, "no-response");
    const fingerprint = fingerprintOf(rig, DEVICE, OLD_TOKEN)!;

    const inFlight = pingPhoneDirect(rig, DEVICE, { requestId: "req-credential-still-revoked", timeoutMs: 10_000 });
    await settle();

    // 轮换：旧凭据被标撤销、新凭据上线。设备本身**没有**被撤销。
    await setCredentials(rig, [
      { deviceId: DEVICE, token: OLD_TOKEN, revokedAt: "2026-01-02T00:00:00Z" },
      { deviceId: DEVICE, token: "GATE2-FAKE-ROTATED-TOKEN" },
      { deviceId: OTHER_DEVICE, token: TEST_DEVICE_TOKEN_2 },
    ]);

    const revoked = await revokePhoneDirect(rig, DEVICE, "credential_still_revoked", {
      credentialId: fingerprint,
    });
    expect(revoked.status).toBe(200);
    expect(revoked.body["scope"]).toBe("credential");
    expect(revoked.body["revoked"]).toBe(true);
    expect(revoked.body["stale"]).toBeUndefined();
    expect(revoked.body["closed"]).toBe(1);
    expect(revoked.body["pending_failed"]).toBe(1);

    const outcome = await inFlight;
    expect(outcome.ok).toBe(false);
    if (outcome.ok) throw new Error("不该成功");
    expect(outcome.error.code).toBe("device_revoked");

    await settle();
    expect(conn.socket.readyState).toBe(3);
    expect(conn.socket.closeInfo?.code).toBe(4002);
    // 轮换不是撤销：设备级状态必须仍然是"没有被撤销"。
    expect((await phoneStatus(rig, DEVICE))["revoked"]).toBe(false);
  });
});

// ---------------------------------------------------------------------------
//  Gate 2 · 撤销通知的生命周期保证
// ---------------------------------------------------------------------------
//
//  行为断言（上面的 describe）证明"迟到的通知无害"，但还差一半：
//  **通知会不会根本没送到？** 原先两处调用点写的是
//  `void revokePhoneDevice(...).catch(...)` —— 而它们紧接着就 `return 401`。
//  响应一返回，运行时就可以回收 isolate，飞行中的通知被掐掉，撤销就静默丢失。
//  这三条用例钉住修复后的契约。
// ---------------------------------------------------------------------------

describe("Gate 2 撤销通知的生命周期", () => {
  it("通知被登记到 ctx.waitUntil：响应返回之后运行时仍会等它落地", async () => {
    const registered: Promise<unknown>[] = [];
    let finished = false;
    const ctx = {
      waitUntil: (promise: Promise<unknown>) => {
        registered.push(promise);
      },
      passThroughOnException: () => {},
    } as unknown as ExecutionContext;

    const notification = (async () => {
      await new Promise((resolve) => setTimeout(resolve, 5));
      finished = true;
    })();

    const settled = notifyRevocationInBackground(
      ctx,
      { deviceId: DEVICE, reason: "lifecycle_test", credentialScoped: false },
      notification,
    );

    // ★ 要害：promise 真的被交给了运行时（不是"发了就不管"）。
    expect(registered.length).toBe(1);
    expect(finished).toBe(false);
    await settled;
    expect(finished).toBe(true);
    expect(registered[0]).toBe(settled);
  });

  it("通知失败只记日志、返回的 promise 永不 reject —— 401 绝不能变成 500", async () => {
    const ctx = {
      waitUntil: () => {},
      passThroughOnException: () => {},
    } as unknown as ExecutionContext;

    const failing = Promise.reject(new Error("DO unreachable"));
    await expect(
      notifyRevocationInBackground(
        ctx,
        { deviceId: DEVICE, reason: "lifecycle_test_failure", credentialScoped: true },
        failing,
      ),
    ).resolves.toBeUndefined();
  });

  it("ctx 没有 waitUntil（Node 替身 / 单测只传 {}）时退化为后台执行且不抛", async () => {
    let ran = false;
    const settled = notifyRevocationInBackground(
      {} as ExecutionContext,
      { deviceId: DEVICE, reason: "lifecycle_test_no_ctx", credentialScoped: false },
      Promise.resolve().then(() => {
        ran = true;
      }),
    );
    await settled;
    expect(ran).toBe(true);
    // 退化路径同样不能抛。
    await expect(
      notifyRevocationInBackground(
        undefined,
        { deviceId: DEVICE, reason: "lifecycle_test_undefined_ctx", credentialScoped: false },
        Promise.reject(new Error("no ctx, still must not throw")),
      ),
    ).resolves.toBeUndefined();
  });

  it("代码级守卫：两处通知都必须经过 notifyRevocationInBackground，不许退回 `void …`", () => {
    const root = join(__dirname, "..", "..");
    const indexSource = readFileSync(join(root, "worker", "src", "index.ts"), "utf8");

    // 两处发送点（设备级 upgrade_after_revocation / 凭据级 upgrade_with_rotated_credential）
    // 都必须走那个会把 promise 交给 `ctx.waitUntil` 的助手。
    expect(indexSource.match(/notifyRevocationInBackground\(/g)?.length).toBe(2);
    // 反向断言：`void revokePhoneDevice(` 这个"发了就不管"的形状不许再出现。
    expect(indexSource).not.toMatch(/void\s+revokePhoneDevice\s*\(/);
    // 而内部路由字面量仍然不许出现在公网入口（沿用 A1 的守卫）。
    expect(indexSource).not.toContain("/internal/phone/revoke");
  });
});
