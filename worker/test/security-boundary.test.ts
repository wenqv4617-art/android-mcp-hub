// ============================================================================
//  安全边界 —— Gate 1.5 阶段 1A：凭据撤销语义与半开连接
// ============================================================================
//
//  ⚠ 先读这一段再读断言。
//
//  这个文件**只补真正空着的部分**，不重复已有覆盖。写之前逐条对过：
//
//    D1 未配置设备凭据        → 已被覆盖（worker-index.test.ts
//                              "服务端没配 DEVICE_TOKENS 时失败关闭（401），不是放行"）
//    D2 错误设备令牌          → 已被覆盖（同上文件"令牌错误 → 401"、
//                              "未知设备 id → 401，且与'令牌错误'无法区分"）
//    D3 已撤销凭据的**新**连接 → 已被覆盖（同上文件"已吊销的凭据被拒绝"）
//    D6 日志脱敏（纯函数层）   → 已被覆盖（logging.test.ts 共 12 条）
//
//  这里新增的是四条**只在真实流程里才成立**的断言：
//
//    D4-a  同一个 rig 里"先连上、再撤销"：**新**连接被拒（撤销确实生效）
//    D4-b  同一个 rig 里"先连上、再撤销"：**已有**连接被就地终结，且新请求被明确拒绝
//          —— 阶段 0 报告把"已有连接照常服务"记为设计缺口 R-1。集中审计问题 1
//             之后该缺口已关闭，所以这条用例从"记录缺口"改成"守住修复"。
//    D5    半开连接（替身近似）：服务端仍报 connected，phone_ping 拖满 timeout
//          才失败（不是快速 phone_offline），pending 清理干净，无虚假成功
//    D6-f  认证失败的**真实请求流程**里，被呈递的令牌不得进入日志
//
//  ⚠ D4-b 的形状（先明确拒绝、再以 4002 终结）是修复后的**契约**，不是可选项：
//     撤而不关会留下一条还能继续收发帧的僵尸连接；关而不拒会让调用方以为
//     "只是网络抖了一下"，于是无限重试。
//
//  ⚠ 跑在 Node 上的 Workers 运行时替身里，不是真实 workerd。见 TEST_REPORT.md。
// ============================================================================

import { describe, expect, it } from "vitest";
import worker from "../src/index";
import type { Env } from "../src/env";
import { sha256Hex } from "../src/util/crypto";
import {
  capturedLogs,
  clearCapturedLogs,
  connectMockPhone,
  makeRig,
  phoneStatus,
  phoneUpgradeRequest,
  pingPhoneDirect,
  settle,
  TEST_DEVICE_ID,
  TEST_DEVICE_TOKEN,
  type TestRig,
} from "./harness/env";

const DEVICE = TEST_DEVICE_ID;
const TOKEN = TEST_DEVICE_TOKEN;

function call(rig: TestRig, request: Request): Promise<Response> {
  return worker.fetch(request, rig.env, {} as ExecutionContext);
}

function metricsOf(status: Record<string, unknown>): Record<string, number> {
  return status["metrics"] as Record<string, number>;
}

/**
 * 把 `DEVICE_TOKENS` 里这台设备的凭据标成已撤销 —— 等价于运维轮换凭据的动作。
 *
 * 刻意**不**调 `resetConfigCache()`：`loadConfig` 的缓存键里含 `env.DEVICE_TOKENS`
 * （`worker/src/config.ts:224`），所以改了它就一定会重新解析配置。
 * 少了这一层，"撤销真的生效了吗"就还得靠手动清缓存来保证，反而测不准。
 */
async function revokeDeviceCredential(rig: TestRig, deviceId: string, token: string): Promise<void> {
  (rig.env as unknown as { DEVICE_TOKENS?: string }).DEVICE_TOKENS = JSON.stringify([
    {
      deviceId,
      tokenSha256: await sha256Hex(token),
      label: "revoked mid-flight (stage 1A)",
      createdAt: "2026-01-01T00:00:00Z",
      revokedAt: "2026-10-11T00:00:00Z",
    },
  ]);
}

describe("安全边界 / D4 连接建立之后才撤销凭据", () => {
  it("D4-a 同一个 rig 里先连上再撤销：新连接被 401 拒绝（撤销确实生效）", async () => {
    const rig = await makeRig();

    // 撤销之前：可以升级。
    const before = await call(rig, phoneUpgradeRequest(DEVICE, TOKEN));
    expect(before.status).toBe(101);
    await before.body?.cancel();

    await revokeDeviceCredential(rig, DEVICE, TOKEN);

    // 撤销之后：同一个 rig、同一个 env 对象，只是凭据没了。
    const after = await call(rig, phoneUpgradeRequest(DEVICE, TOKEN));
    expect(after.status).toBe(401);
    expect(after.headers.get("WWW-Authenticate")).toContain("Bearer");
  });

  it("D4-b【R-1 缺口已关闭】同一个 rig 里先连上再撤销：已有连接被 4002 终结，且新请求被明确拒绝", async () => {
    const rig = await makeRig();

    // 先按正常流程连上一台手机（走 DO 的升级路径，和真实链路一致）。
    const { socket } = await connectMockPhone(rig, DEVICE, "normal");
    expect((await phoneStatus(rig, DEVICE))["connected"]).toBe(true);

    // 手机上还有**在途**请求时撤销凭据 —— 最坏情况：撤销那一刻正在等一个 pong。
    // 在途请求按原样结算：撤销不是"把已经答应的回答撕掉"，它只禁止**新**动作。
    const inFlight = pingPhoneDirect(rig, DEVICE, { requestId: "req-revoke-inflight", timeoutMs: 2_000 });
    await settle();

    await revokeDeviceCredential(rig, DEVICE, TOKEN);

    // 1) 在途请求照常结算。
    const inFlightOutcome = await inFlight;
    expect(inFlightOutcome.ok).toBe(true);

    // 2) ★ 要害：撤销之后**新**发起的 phone_ping 必须失败，而且是明确的
    //    `device_revoked` —— 不是超时、不是 phone_offline。
    //    工厂重置、手机丢失、凭据泄漏这三种场景下，这正是必须堵住的口子（旧行为是照常成功）。
    const afterRevoke = await pingPhoneDirect(rig, DEVICE, { requestId: "req-revoke-after", timeoutMs: 2_000 });
    expect(afterRevoke.ok).toBe(false);
    if (afterRevoke.ok) throw new Error("撤销之后的请求不该成功");
    expect(afterRevoke.error.code).toBe("device_revoked");

    // 3) 拒绝的同时那条连接必须被终结 —— 否则留下的是一条"还能主动上传"的僵尸连接。
    await settle();
    expect(socket.readyState).toBe(3);
    expect(socket.closeInfo?.code).toBe(4002);

    // 4) 两层判断现在**一致**：Worker 层对新连接 401，DO 层对已有连接 4002 + 拒新请求。
    const status = await phoneStatus(rig, DEVICE);
    expect(status["connected"]).toBe(false);
    expect(status["revoked"]).toBe(true);

    const newUpgrade = await call(rig, phoneUpgradeRequest(DEVICE, TOKEN));
    expect(newUpgrade.status).toBe(401);
    await newUpgrade.body?.cancel();

    // 5) 计数变化留痕，方便报告核对。
    const m = metricsOf(status);
    expect(m["authFailures"]).toBe(0); // DO 自己不做鉴权，所以这里恒为 0
    expect(m["requestsRejectedRevoked"]).toBe(1);
  });
});

describe("安全边界 / D5 半开连接（替身近似）", () => {
  it("D5-a 服务端仍报 connected、ping 拖满 timeout 才失败、pending 清理干净、无虚假成功", async () => {
    const rig = await makeRig();
    // `no-response`：手机完成 welcome/hello 握手之后就再也不回任何 pong。
    // 这是替身里最接近"TCP 还连着、对端应用已经死了"的形态。
    await connectMockPhone(rig, DEVICE, "no-response");

    const status = await phoneStatus(rig, DEVICE);
    expect(status["connected"]).toBe(true);
    expect(status["open_sockets"]).toBeGreaterThan(0);

    const timeoutMs = 300;
    const startedAt = Date.now();
    const outcome = await pingPhoneDirect(rig, DEVICE, { requestId: "req-halfopen-1", timeoutMs });
    const elapsed = Date.now() - startedAt;

    // 不得有虚假成功。
    expect(outcome.ok).toBe(false);
    // **误报的形态**：服务端认为自己在线，于是只能拖满 timeout，
    // 而不是快速给出 phone_offline。
    if (!outcome.ok) expect(outcome.error.code).toBe("phone_timeout");
    expect(elapsed).toBeGreaterThanOrEqual(timeoutMs - 20);

    await settle();
    const after = await phoneStatus(rig, DEVICE);
    expect(after["pending_requests"]).toBe(0);
    expect(after["pending_mirror"]).toBe(0);
    expect(metricsOf(after)["timeouts"]).toBe(1);
    expect(metricsOf(after)["pongsMatched"]).toBe(0);
  });

  it("D5-b 对照：socket 真的关掉之后，同一个 ping 会**快速**失败为 phone_offline", async () => {
    const rig = await makeRig();
    const { socket } = await connectMockPhone(rig, DEVICE, "no-response");

    socket.close(1006, "network gone");
    await settle();
    expect((await phoneStatus(rig, DEVICE))["connected"]).toBe(false);

    const startedAt = Date.now();
    const outcome = await pingPhoneDirect(rig, DEVICE, { requestId: "req-halfopen-2", timeoutMs: 5_000 });
    const elapsed = Date.now() - startedAt;

    expect(outcome.ok).toBe(false);
    if (!outcome.ok) expect(outcome.error.code).toBe("phone_offline");
    // 决策路径不等待：立刻返回，而不是拖满 5000ms。
    expect(elapsed).toBeLessThan(500);
  });

  it("D5-c 半开期间连接被换掉：新的代次照常可用，旧的半开 socket 不再算数", async () => {
    const rig = await makeRig();
    const stale = await connectMockPhone(rig, DEVICE, "no-response");
    expect((await phoneStatus(rig, DEVICE))["connected"]).toBe(true);

    // 手机重装后重新连上 —— 代次推进。
    const fresh = await connectMockPhone(rig, DEVICE, "normal");
    await settle();

    // 旧 socket 被服务端以 4001 关掉。
    expect(stale.socket.readyState).toBe(3);
    expect(stale.socket.closeInfo?.code).toBe(4001);

    const outcome = await pingPhoneDirect(rig, DEVICE, { requestId: "req-halfopen-3", timeoutMs: 2_000 });
    expect(outcome.ok).toBe(true);
    if (outcome.ok) expect(outcome.connection_generation).toBeGreaterThanOrEqual(2);
    expect(fresh.phone.pingCount).toBe(1);
  });
});

describe("安全边界 / D6 认证失败真实流程的日志脱敏", () => {
  it("D6-f 被呈递的令牌、Authorization 头都不进日志，且失败本身有固定类别的记录", async () => {
    const rig = await makeRig();
    clearCapturedLogs();

    // 一个形状醒目、绝不可能在别处出现的假令牌 —— 只用测试虚构凭据。
    //
    // ⚠ 它的**长度是刻意压到 40 个字符以下、且不含数字**的：本仓库自己的
    //   `worker/test/security.test.ts` 会扫"40 位 base62 且含数字"的随机串。
    //   第一版那串假令牌正好 40 个字符、含数字，被那条守卫抓了出来 ——
    //   守卫是对的，改的是我，不是它。
    const presented = "STAGE1A-FAKE-TOKEN-NEVER-REAL";
    const response = await call(rig, phoneUpgradeRequest(DEVICE, presented));
    expect(response.status).toBe(401);
    await response.body?.cancel();

    const logs = capturedLogs().join("\n");

    // 1) 令牌本身不出现。
    expect(logs).not.toContain(presented);
    expect(logs).not.toContain("STAGE1A-FAKE-TOKEN");
    // 2) 认证头的名字也不出现（避免"把整个头打出来"的写法）。
    expect(logs).not.toContain("Authorization");
    expect(logs).not.toContain("Bearer");
    // 3) 但也**不能什么都不记** —— 失败必须留下可审计的固定类别。
    expect(logs).toContain("phone.auth_failed");
    expect(logs).toContain("token_mismatch");
  });

  it("D6-g 缺设备头 / 缺令牌这两条拒绝路径的日志同样只有固定类别，没有请求原文", async () => {
    const rig = await makeRig();
    clearCapturedLogs();

    const missingHeader = await call(rig, phoneUpgradeRequest(DEVICE, TOKEN, { includeDeviceHeader: false }));
    expect(missingHeader.status).toBe(401);
    await missingHeader.body?.cancel();

    const missingAuth = await call(rig, phoneUpgradeRequest(DEVICE, TOKEN, { includeAuth: false }));
    expect(missingAuth.status).toBe(401);
    await missingAuth.body?.cancel();

    const logs = capturedLogs().join("\n");
    expect(logs).toContain("missing_device_id_or_token");
    expect(logs).not.toContain(TOKEN);
    expect(logs).not.toContain("Authorization");
  });
});

// ---------------------------------------------------------------------------
//  能力边界（**不是测试**，写在这里是因为读者会问"那真实半开呢"）
// ---------------------------------------------------------------------------
//
//  D5 造的是"应用层不再回帧"，**不是** TCP 半开。真实半开（对端掉电、NAT 超时、
//  射频丢失）下 OS 不派发 close，workerd 是否/何时触发 `webSocketClose` 取决于
//  运行时与超时设置。本容器跑不起 workerd（TCMalloc 48-bit 地址空间），
//  这一条的状态是 **REAL_DEVICE_REQUIRED**，不写成 PASS。
//
//  刻意不把这段话包成一个永远为真的 `it()` —— 那种测试只会让数字变好看，
//  一条永远不会红的断言不是证据。
