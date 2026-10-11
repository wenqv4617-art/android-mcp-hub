// ============================================================================
//  PhoneSession — 每台手机一个 Durable Object
// ============================================================================
//
//  这是整个 Gate 0 的**唯一有状态核心**。它负责：
//    · 持有该设备的那一条 WebSocket 长连接（休眠 API）；
//    · 把 MCP 侧的 phone_ping 变成一条 request_id 唯一的帧发出去；
//    · 把手机回来的 phone_pong 与请求精确配对，在本地测 RTT；
//    · 超时、断线、重连、旧连接响应 —— 全部按 docs/PROTOCOL.md R1–R13 处理。
//
//  ── 关于休眠（Hibernation）────────────────────────────────────────────────
//  用 `ctx.acceptWebSocket()` 意味着 DO 可以在连接开着的时候被驱逐，内存里的
//  一切都会丢。这带来一个必须回答的问题：
//
//      "如果一个请求正等着 pong，而 DO 被驱逐重建了，会怎样？"
//
//  答案必须**不能**是"它静默成功了"。本实现的处理是：
//    1. 每个待处理请求都同时写一份到 `ctx.storage`（"镜像"）；
//    2. 每个待处理请求记下当时的 `connection_generation`；
//    3. DO 重建时（构造函数里）发现存储里有遗留待处理请求 —— 说明等待它们的
//       那个调用栈已经随驱逐一起消失了 —— 于是**一律记为失败**并计入
//       `reconstructedPendingFailed`，绝不产生虚假成功；
//    4. 超时不只靠内存里的 setTimeout，还用 `storage.setAlarm()` 兜底，
//       这样即使进程没了，到点也会有一条 alarm 来把超时账记上。
//
//  ── 关于"旧连接覆盖新连接"────────────────────────────────────────────────
//  每次握手成功 `generation += 1` 并落盘。待处理请求记录生成时的代次，
//  pong 必须来自当前代次才可能被接受。同时新连接会主动关掉同设备的旧连接
//  （close code 4001）。两层保险：即使旧连接在关闭前挤进来一个 pong，
//  也会被代次检查挡住。
// ============================================================================

import { loadConfig, PHONE_LINK_PROTOCOL_VERSION, type RuntimeConfig } from "../config";
import type { Env } from "../env";
import { logEvent } from "../util/log";
import {
  encodeServerFrame,
  normalizeToolErrorCode,
  parseIncomingFrame,
  type ServerFrame,
  type ToolResultFrame,
} from "./messages";

// ---- 对外契约 ---------------------------------------------------------------

export type PhoneErrorCode =
  | "phone_offline"
  | "phone_timeout"
  | "phone_disconnected"
  | "phone_replaced"
  | "too_many_pending_requests"
  /** Gate 1：同一个 request_id 已经有一个未完成请求，拒绝覆盖（见 handlePingRequest）。 */
  | "duplicate_request_id"
  | "unknown_device"
  | "ambiguous_device"
  | "invalid_pong"
  /**
   * Gate 1.5 / A1：设备凭据已被撤销。
   *
   * 与 `phone_offline` 刻意分开：撤销是"这台设备不该再被操作"这个**安全状态**，
   * 而离线只是一个会自愈的瞬时状态。混在一起会让审计看不出撤销到底生效没有。
   */
  | "device_revoked"
  | "unsupported_tool"
  | "invalid_arguments"
  | "tool_failed"
  | "tool_unavailable"
  | "not_permitted"
  | "internal_error";

export interface PingSuccess {
  ok: true;
  request_id: string;
  device_id: string;
  rtt_ms: number;
  server_timestamp: number;
  device_timestamp: number;
  connection_generation: number;
}

export interface PingFailure {
  ok: false;
  request_id: string;
  device_id: string;
  error: { code: PhoneErrorCode; message: string };
}

export type PingOutcome = PingSuccess | PingFailure;

/** Gate 2：通用工具的返回。字段与 [PingSuccess] **刻意不同**，不做兼容折叠。 */
export interface ToolSuccess {
  ok: true;
  request_id: string;
  device_id: string;
  tool_name: string;
  result: Record<string, unknown>;
  rtt_ms: number;
  server_timestamp: number;
  connection_generation: number;
}

export interface ToolFailure {
  ok: false;
  request_id: string;
  device_id: string;
  tool_name: string;
  error: { code: PhoneErrorCode; message: string };
}

export type ToolOutcome = ToolSuccess | ToolFailure;

/** 两条通道共用的结算类型。pending 表对两者一视同仁。 */
export type SessionOutcome = PingOutcome | ToolOutcome;

export interface PhoneSessionMetrics {
  connectionsAccepted: number;
  connectionReplaced: number;
  authFailures: number;
  upgradesRejected: number;
  pingsSent: number;
  pongsMatched: number;
  pongsUnknown: number;
  pongsStaleGeneration: number;
  pongsInvalid: number;
  timeouts: number;
  disconnects: number;
  pendingRejectedOverLimit: number;
  /**
   * Gate 1 审计发现项 B：因为 request_id 与某个未完成请求重复而被拒绝的次数。
   *
   * **正常情况恒为 0** —— MCP 层每个 phone_ping 都用新生成的 UUID。
   * 非 0 说明调用方在重放同一个 request_id，那是必须被挡住的行为。
   */
  pendingRejectedDuplicate: number;
  messagesRejected: number;
  reconstructedPendingFailed: number;
  /** Gate 0.1 / R1：新连接替换旧连接时，被**主动**终结的旧代次待处理请求数。 */
  pendingReplacedOnConnect: number;
  /**
   * Gate 0.3：结算路径上"尽力而为"的记账动作失败了几次。
   *
   * 三个分开计数，是因为它们的后果不同：
   *  - 存储失败 → 对象被驱逐后超时账会丢；
   *  - 指标落盘失败 → 指标只在内存里；
   *  - alarm 调度失败 → 只能靠对象还活着时的那只内存定时器兜底。
   * 任何一种都不影响"调用方一定拿到结果"这个保证。
   */
  settleStorageFailures: number;
  settleMetricFailures: number;
  settleAlarmFailures: number;
  /** Gate 0.3：请求在"落盘/排 alarm"期间就被结算（几乎只可能是超时），因而没发帧。 */
  pingsSettledBeforeSend: number;
  /** Gate 1.5 / A1：撤销入口被调用了多少次（含重放同一条撤销通知）。 */
  revocationsApplied: number;
  /** Gate 1.5 / A1：被撤销挡下来的在途请求数。 */
  pendingRevoked: number;
  /** Gate 1.5 / A1：因为设备已撤销而被拒绝的**新**工具/ping 请求数。 */
  requestsRejectedRevoked: number;
  /** Gate 1.5 / A2：因为应用层静默超阈值而被判定死亡、主动断开的连接数。 */
  livenessTimeouts: number;
  /** Gate 1.5 / A2：`connected` 报告为 false、但底层 socket 其实还开着的次数（每次 status 各算一次）。 */
  staleStatusReads: number;
  /** Gate 2：真正发出去的 tool_request 帧数。 */
  toolRequestsSent: number;
  /** Gate 2：成功配对到在途 tool_request 的 tool_result 数。 */
  toolResultsMatched: number;
  /** Gate 2：找不到配对请求而被丢弃的 tool_result 数（重复响应会落在这里）。 */
  toolResultsUnknown: number;
  /** Gate 2：来自过期代次的 tool_result 数。 */
  toolResultsStaleGeneration: number;
  /** Gate 2：格式不合法（解析成 invalid）的 tool_result 数。 */
  toolResultsInvalid: number;
  lastRttMs: number | null;
  lastPingAt: number | null;
  lastPongAt: number | null;
  /** Gate 1.5 / A2：最后一次收到**任何可解析**手机帧的时刻（毫秒）。 */
  lastPhoneActivityAt: number | null;
  /** Gate 2：最后一次收到合法 tool_result 的时刻。 */
  lastToolResultAt: number | null;
}

function emptyMetrics(): PhoneSessionMetrics {
  return {
    connectionsAccepted: 0,
    connectionReplaced: 0,
    authFailures: 0,
    upgradesRejected: 0,
    pingsSent: 0,
    pongsMatched: 0,
    pongsUnknown: 0,
    pongsStaleGeneration: 0,
    pongsInvalid: 0,
    timeouts: 0,
    disconnects: 0,
    pendingRejectedOverLimit: 0,
    pendingRejectedDuplicate: 0,
    messagesRejected: 0,
    reconstructedPendingFailed: 0,
    pendingReplacedOnConnect: 0,
    settleStorageFailures: 0,
    settleMetricFailures: 0,
    settleAlarmFailures: 0,
    pingsSettledBeforeSend: 0,
    revocationsApplied: 0,
    pendingRevoked: 0,
    requestsRejectedRevoked: 0,
    livenessTimeouts: 0,
    staleStatusReads: 0,
    toolRequestsSent: 0,
    toolResultsMatched: 0,
    toolResultsUnknown: 0,
    toolResultsStaleGeneration: 0,
    toolResultsInvalid: 0,
    lastRttMs: null,
    lastPingAt: null,
    lastPongAt: null,
    lastPhoneActivityAt: null,
    lastToolResultAt: null,
  };
}

/** 落盘用的待处理请求快照（不含函数，因此可序列化）。 */
interface StoredPending {
  requestId: string;
  deviceId: string;
  generation: number;
  sentAt: number;
  deadline: number;
  timeoutMs: number;
  /**
   * 这条请求是 ping 还是工具调用。
   *
   * 落盘时也要带上：对象被驱逐后重建时，失败结果的**形状**必须和原来那条请求
   * 对得上，否则调用方会收到一个缺字段的 JSON。旧版本写下的记录没有这两个
   * 字段，读取时按 `"ping"` 处理（见构造函数）。
   */
  kind?: "ping" | "tool";
  toolName?: string | null;
}

/** 内存中的待处理请求（含 resolve/reject）。 */
interface PendingRequest extends StoredPending {
  kind: "ping" | "tool";
  toolName: string | null;
  settle: (outcome: SessionOutcome) => void;
  timer: ReturnType<typeof setTimeout> | null;
}

interface SocketAttachment {
  deviceId: string;
  generation: number;
}

const STORAGE_KEY_GENERATION = "generation";
const STORAGE_KEY_METRICS = "metrics";
const STORAGE_KEY_PENDING = "pending";
const STORAGE_KEY_ACTIVITY = "last_activity_at";
/** Gate 1.5 / A1：撤销过哪些设备 ID。落盘，跨驱逐仍然生效。 */
const STORAGE_KEY_REVOKED = "revoked_device_ids";

/**
 * 内部路由前缀。这些路径**只**能被 Worker 通过 DO 绑定访问，公网不可达。
 *
 * ⚠ 导出它们是有意的：Worker 侧转发时必须用同一个字符串。之前 Worker 转发的
 *   是公网 URL，而 DO 只认下面这些路径，于是每一次合法的手机握手都变成 404 ——
 *   让两边共用同一个常量，这类"两份字面量悄悄分叉"的 bug 就没有发生的土壤了。
 */
export const ROUTE_CONNECT = "/internal/phone/connect";
export const ROUTE_PING = "/internal/phone/ping";
export const ROUTE_TOOL = "/internal/phone/tool";
export const ROUTE_REVOKE = "/internal/phone/revoke";
export const ROUTE_STATUS = "/internal/phone/status";
export const ROUTE_RESET = "/internal/phone/testing/reset";

/** 内部标记头：证明这次升级已经过 Worker 的设备鉴权。 */
export const INTERNAL_DEVICE_HEADER = "X-Phone-Session-Device";

/** 替换旧连接时使用的关闭码（4000–4999 为应用自定义区间）。 */
export const CLOSE_CODE_REPLACED = 4001;
/** Gate 1.5 / A1：凭据被撤销时使用的关闭码。客户端**不得**因此自动重连。 */
export const CLOSE_CODE_REVOKED = 4002;


export class PhoneSession implements DurableObject {
  private readonly ctx: DurableObjectState;
  private readonly env: Env;
  private cfg!: RuntimeConfig;
  private readonly ready: Promise<void>;

  /** 单调递增的连接代次。落盘，跨重建保持。 */
  private generation = 0;
  private pending = new Map<string, PendingRequest>();
  /** 与 `pending` 同步的落盘镜像。 */
  private storedPending: Record<string, StoredPending> = {};
  private metrics: PhoneSessionMetrics = emptyMetrics();
  /**
   * Gate 1.5 / A1：Service Worker 已经确认这台设备的凭据被撤销了。
   *
   * 落盘，因此跨驱逐仍然生效 —— 这正是 D-1 缺陷的另一半：原来 DO 里根本
   * 没有任何"撤销"这个概念，靠 Worker 的凭据表是拦不住**已经建好**的连接的。
   */
  private revoked = false;
  /**
   * Gate 1.5 / A2：最后一次收到**可解析**手机帧的时刻。
   *
   * 与 `connected` 分开：`connected` 回答"当前代次有没有 socket"，
   * 这个字段回答"这台手机最近还活着吗"。半开连接就是前者 true、后者陈旧。
   */
  private lastPhoneActivityAt = 0;

  constructor(ctx: DurableObjectState, env: Env) {
    this.ctx = ctx;
    this.env = env;

    // blockConcurrencyWhile：在状态装载完成之前，任何事件都不会被投递。
    // 这是"重建后不会先处理消息、后读到旧状态"的保证。
    this.ready = this.ctx.blockConcurrencyWhile(async () => {
      this.cfg = loadConfig(env).runtime;

      this.generation = (await ctx.storage.get<number>(STORAGE_KEY_GENERATION)) ?? 0;
      this.metrics = { ...emptyMetrics(), ...((await ctx.storage.get<PhoneSessionMetrics>(STORAGE_KEY_METRICS)) ?? {}) };

      this.revoked = (await ctx.storage.get<boolean>(STORAGE_KEY_REVOKED)) === true;
      const activity = await ctx.storage.get<number>(STORAGE_KEY_ACTIVITY);
      this.lastPhoneActivityAt = typeof activity === "number" && Number.isFinite(activity) ? activity : 0;

      const leftovers = (await ctx.storage.get<Record<string, StoredPending>>(STORAGE_KEY_PENDING)) ?? {};
      const leftoverIds = Object.keys(leftovers);
      if (leftoverIds.length > 0) {
        // 走到这里说明对象被驱逐/重启过：等待这些请求的调用栈已经不存在了。
        // 它们**不可能**再被"完成" —— 只能记为失败。这正是任务书要求的
        // "对象重建后不产生虚假成功响应"。
        this.metrics.reconstructedPendingFailed += leftoverIds.length;
        this.storedPending = {};
        await ctx.storage.put(STORAGE_KEY_PENDING, {});
        await ctx.storage.put(STORAGE_KEY_METRICS, this.metrics);
        logEvent("warn", "phone_session.leftover_pending_discarded", {
          deviceClass: "phone",
          discarded: leftoverIds.length,
          generation: this.generation,
        });
      } else {
        this.storedPending = {};
      }
    });
  }

  // ---------------------------------------------------------------------------
  //  HTTP 入口（只接受来自 Worker 的内部调用）
  // ---------------------------------------------------------------------------

  async fetch(request: Request): Promise<Response> {
    await this.ready;
    const url = new URL(request.url);

    switch (url.pathname) {
      case ROUTE_CONNECT:
        // ★ Gate 1.5 / A1 的第二道闸。Worker 已经用凭据表拦过一次（撤销后
        //   新升级会 401），但 DO 自己也必须拒绝 —— 否则"先建立连接、撤销
        //   之后又被 Worker 用旧路径转发进来"这条窗口仍然存在。
        if (this.revoked) {
          this.metrics.upgradesRejected += 1;
          await this.flushMetrics();
          logEvent("warn", "phone_session.upgrade_rejected", { reason: "device_revoked" });
          return json({ error: "device_revoked" }, 403);
        }
        return this.handleConnect(request);
      case ROUTE_PING:
        return this.handlePingRequest(request);
      case ROUTE_TOOL:
        return this.handleToolRequest(request);
      case ROUTE_REVOKE:
        return this.handleRevoke(request);
      case ROUTE_STATUS:
        return this.handleStatus();
      case ROUTE_RESET:
        return this.handleTestingReset();
      default:
        return new Response(JSON.stringify({ error: "not_found" }), {
          status: 404,
          headers: { "Content-Type": "application/json" },
        });
    }
  }

  private async handleConnect(request: Request): Promise<Response> {
    const deviceId = request.headers.get(INTERNAL_DEVICE_HEADER);
    if (!deviceId) {
      this.metrics.upgradesRejected += 1;
      await this.flushMetrics();
      logEvent("warn", "phone_session.upgrade_rejected", { reason: "missing_internal_device_header" });
      return new Response(JSON.stringify({ error: "missing_internal_device_header" }), { status: 500 });
    }

    if (request.headers.get("Upgrade")?.toLowerCase() !== "websocket") {
      this.metrics.upgradesRejected += 1;
      await this.flushMetrics();
      return new Response(JSON.stringify({ error: "expected_websocket_upgrade" }), { status: 426 });
    }

    const pair = new WebSocketPair();
    const client = pair[0];
    const server = pair[1];

    const previousSockets = this.ctx.getWebSockets();

    // 代次先递增再接受：从这一刻起的任何 pong 只有在标着新代次时才可能被接受。
    this.generation += 1;
    const generation = this.generation;
    const attachment: SocketAttachment = { deviceId, generation };
    server.serializeAttachment(attachment);

    this.ctx.acceptWebSocket(server, [`device:${deviceId}`, `gen:${generation}`]);

    // 关掉旧连接。用应用自定义关闭码，手机侧可以据此区分"被替换"与"网络断了"。
    for (const old of previousSockets) {
      if (old === server) continue;
      this.metrics.connectionReplaced += 1;
      try {
        old.close(CLOSE_CODE_REPLACED, "replaced by a newer connection");
      } catch {
        // 已经关掉了 —— 无所谓。
      }
    }

    this.metrics.connectionsAccepted += 1;
    await this.ctx.storage.put({
      [STORAGE_KEY_GENERATION]: generation,
      [STORAGE_KEY_METRICS]: this.metrics,
    });

    // ★ Gate 0.1 审计 R1：**主动**终结旧代次遗留的待处理请求。
    //
    //   改之前只依赖两条被动路径：旧 socket 的 close 事件，或者超时。
    //   两条都不可靠 ——
    //     · close 事件可能很晚才到（或根本不到：手机直接被替换、进程没了、
    //       网络黑洞），而 handleSocketGone 看到代次不符还会**直接返回**，
    //       于是这些请求一直挂在 `pending` 里占着并发额度，直到自己超时；
    //     · 在那段窗口里，它们的 request_id 仍然在表里，一个来自旧连接的
    //       畸形/迟到 pong 就有了配对对象。
    //   现在改成在这一刻就结算：调用方立刻拿到明确的 phone_replaced，
    //   而不是等 10 秒后拿到一个含糊的 phone_timeout。这也符合 R8
    //   ——我们**没有**把旧请求重放到新连接上，只是明确地告诉它失败了。
    //
    //   实现上刻意复用 settlePending()：它是唯一的结算入口，因此
    //   pending 内存表、落盘镜像、setTimeout、alarm 四者一定同时收敛。
    const replacedPending = await this.failStalePending(generation);

    logEvent("info", "phone_session.connected", {
      deviceId,
      generation,
      replaced: this.metrics.connectionReplaced,
      discarded: replacedPending,
    });

    try {
      server.send(
        encodeServerFrame({
          type: "welcome",
          device_id: deviceId,
          connection_generation: generation,
          server_time: Date.now(),
          heartbeat_interval_ms: this.cfg.heartbeatIntervalMs,
          ping_timeout_ms: this.cfg.pingTimeoutMs,
          max_pending_requests: this.cfg.maxPendingRequests,
          protocol_version: PHONE_LINK_PROTOCOL_VERSION,
        }),
      );
    } catch {
      // 审计 R2：不记 String(err)。
      logEvent("warn", "phone_session.welcome_send_failed", {
        deviceId,
        errorCode: "welcome_send_failed",
      });
    }

    // ★ Gate 1.5 / A2：刚刚完成了一次**真实的**应用层握手（我们发出 welcome，
    //   手机侧要处理它才会继续）。把存活计时器从这里起算，而不是从"TCP 连上"
    //   起算 —— 否则一台连上就僵死的手机要等满一个完整阈值才被判死。
    await this.noteActivity();

    return new Response(null, { status: 101, webSocket: client });
  }

  /**
   * 把所有**非当前代次**的待处理请求显式判失败（审计 R1）。
   *
   * 返回被终结的个数，供日志/指标使用。
   *
   * 为什么按 `entry.generation !== currentGeneration` 筛，而不是"清空全部"：
   * 新连接刚刚建立的那一刻，理论上不可能存在属于新代次的请求（还没有人
   * 用新 socket 发过 ping），但按代次筛选让这个函数在**任何调用时机**都安全
   * —— 将来若有人在别处复用它会得到正确的语义，而不是把好请求一起杀掉。
   */
  private async failStalePending(currentGeneration: number): Promise<number> {
    const staleIds = [...this.pending.values()]
      .filter((entry) => entry.generation !== currentGeneration)
      .map((entry) => entry.requestId);

    for (const requestId of staleIds) {
      this.metrics.pendingReplacedOnConnect += 1;
      await this.settlePending(requestId, (r) => this.buildFailure(r, "phone_replaced", `连接已被更新的连接替换（请求代次 ${r.generation}，当前代次 ${currentGeneration}）`), "replaced");
    }

    return staleIds.length;
  }

  // ---------------------------------------------------------------------------
  //  Gate 1.5 / A1：凭据撤销
  // ---------------------------------------------------------------------------

  /**
   * 撤销入口。**只有** Worker 能调到它（公网没有这个路径）。
   *
   * 语义（任务书 A1 的四条要求逐条对应）：
   *   1. 撤销后不得接受新的设备操作请求 → `this.revoked = true`，`fetch()` 里
   *      的 ROUTE_CONNECT 与两个请求入口都会先看这个标志；
   *   2. 已建立连接在**可验证的**撤销通知到达后关闭 → 这里立刻 close(4002)；
   *   3. 未完成请求必须明确失败 → 全部结算成 `device_revoked`；
   *   4. 不得影响其他设备 → 撤销状态只存在**这台设备自己的** DO 里。
   *
   * 为什么代次也要 +1：关闭 socket 与 close 事件到达之间有窗口。如果不换
   * 代次，一个在途的 tool_result/pong 仍然带着旧代次匹配得上 —— 那正是
   * "撤销之后还收到一次成功"这种最糟的观测。换了代次，它就变成
   * `pongsStaleGeneration` / `toolResultsStaleGeneration`，永远结算不成成功。
   */
  private async handleRevoke(request: Request): Promise<Response> {
    let body: { deviceId?: unknown; reason?: unknown };
    try {
      body = (await request.json()) as typeof body;
    } catch {
      return json({ error: "invalid_json" }, 400);
    }
    const requestedDeviceId = typeof body.deviceId === "string" && body.deviceId !== "" ? body.deviceId : null;
    if (requestedDeviceId === null) return json({ error: "missing_device_id" }, 400);

    const reason = typeof body.reason === "string" && body.reason !== "" ? body.reason : "credential_revoked";

    // 交叉校验：撤销指令带错了设备 ID 时**不能**把别人的连接关掉。
    // DO 实例名就是设备 ID，所以这几乎不可能发生 —— 但"几乎不可能"不是保证，
    // 而这一步几乎不要钱。
    const bound = this.currentSocket();
    const attached = bound ? (bound.deserializeAttachment() as SocketAttachment | null) : null;
    if (attached !== null && attached.deviceId !== requestedDeviceId) {
      logEvent("warn", "phone_session.revoke_device_mismatch", {
        deviceId: requestedDeviceId,
        currentGeneration: this.generation,
      });
      return json({ error: "device_mismatch" }, 409);
    }

    // 幂等：撤销通知**可能被重复投递**（每个被拒的升级都会推一条，
    // `worker/src/index.ts` 就是这么做的）。重复投递不该让指标翻倍，
    // 也不该白白再换一次代次 —— 否则"撤销了几次"这个数就没法用来判断
    // 到底发生了多少次真实的安全事件。
    const alreadyRevoked = this.revoked;
    this.revoked = true;
    if (!alreadyRevoked) this.generation += 1;
    const generation = this.generation;
    if (!alreadyRevoked) this.metrics.revocationsApplied += 1;

    // 幂等再次进来时这张表必然为空 —— 撤销之后没有任何入口能再登记请求。
    const pendingIds = [...this.pending.keys()];
    for (const requestId of pendingIds) {
      this.metrics.pendingRevoked += 1;
      await this.failPending(requestId, "device_revoked", "设备凭据已被撤销，该请求被明确终止", "disconnect");
    }

    // 先把"已撤销"落盘再关 socket：反过来的话，进程在两者之间挂掉就会留下
    // "连接关了但撤销没记住"的状态，而那种状态会允许一次重连成功。
    await this.ctx.storage.put({
      [STORAGE_KEY_GENERATION]: generation,
      [STORAGE_KEY_REVOKED]: true,
      [STORAGE_KEY_METRICS]: this.metrics,
    });

    let closed = 0;
    for (const socket of this.ctx.getWebSockets()) {
      try {
        // 先给一帧可读的原因，再关。手机侧拿不到关闭帧的 reason 时会无从判断
        // "是网络断了还是我被撤销了" —— 那会让它白重连很多次。
        this.safeSend(socket, { type: "error", code: "device_revoked", message: "设备凭据已被撤销，连接终止" });
        socket.close(CLOSE_CODE_REVOKED, "device credential revoked");
        closed += 1;
      } catch {
        // 已经关了 —— 无所谓。
      }
    }

    logEvent("warn", "phone_session.revoked", {
      deviceId: requestedDeviceId,
      generation,
      closed,
      pendingFailed: pendingIds.length,
      reason,
    });

    return json({
      ok: true,
      device_id: requestedDeviceId,
      revoked: true,
      already_revoked: alreadyRevoked,
      closed,
      pending_failed: pendingIds.length,
      generation,
    });
  }

  /**
   * Gate 1.5 / A2：记录"这台手机刚有真实活动"。
   *
   * 落盘是必要的：DO 被驱逐后重建时，如果把它当成 0，一台刚刚被撤销/僵死的
   * 手机会因为 `lastPhoneActivityAt === 0` 而被判成永远在线。
   */
  private async noteActivity(): Promise<void> {
    this.lastPhoneActivityAt = Date.now();
    this.metrics.lastPhoneActivityAt = this.lastPhoneActivityAt;
    this.bestEffort("persist_activity", () => this.ctx.storage.put(STORAGE_KEY_ACTIVITY, this.lastPhoneActivityAt));
    this.bestEffort("flush_metrics", () => this.flushMetrics());
  }

  /**
   * Gate 1.5 / A2：当前 socket 在应用层还活着吗？
   *
   * 与 [currentSocket] 的差别就是 D-2 的修复点：`currentSocket()` 只回答
   * "这个代次有没有已注册的 socket"，而半开连接**也满足**那个条件。
   */
  private socketIsLive(socket: WebSocket | null): boolean {
    if (socket === null) return false;
    if (this.lastPhoneActivityAt === 0) return false;
    return Date.now() - this.lastPhoneActivityAt <= this.cfg.livenessTimeoutMs;
  }

  /** 静默了多久（毫秒）。没有活动记录时返回 null，调用方可以据此区分"从没说过话"。 */
  private silenceMs(): number | null {
    if (this.lastPhoneActivityAt === 0) return null;
    return Date.now() - this.lastPhoneActivityAt;
  }

  /**
   * 判死一台静默过久的手机：关 socket、结算在途请求、记账。
   *
   * 返回是否真的执行了判死。**不自动重放任何请求** —— 在途请求一律明确失败。
   */
  private async dropStaleConnection(trigger: string): Promise<boolean> {
    const socket = this.currentSocket();
    if (socket === null || this.socketIsLive(socket)) return false;

    this.metrics.livenessTimeouts += 1;
    const generation = this.generation;
    const silence = this.silenceMs();

    logEvent("warn", "phone_session.liveness_timeout", {
      deviceId: this.attachedDeviceId(),
      generation,
      reason: trigger,
      livenessTimeoutMs: this.cfg.livenessTimeoutMs,
      silenceMs: silence,
    });

    for (const requestId of [...this.pending.keys()]) {
      await this.failPending(requestId, "phone_disconnected", `等待期间链路静默超过 ${this.cfg.livenessTimeoutMs} ms，已判定连接死亡`, "disconnect");
    }

    try {
      socket.close(4000, "liveness timeout");
    } catch {
      // 已经关了。
    }
    await this.flushMetrics();
    return true;
  }

  private async handlePingRequest(request: Request): Promise<Response> {
    let body: { requestId?: unknown; timeoutMs?: unknown; deviceId?: unknown };
    try {
      body = (await request.json()) as typeof body;
    } catch {
      return json({ ok: false, error: { code: "internal_error", message: "请求体不是合法 JSON" } }, 400);
    }

    const requestId = typeof body.requestId === "string" ? body.requestId : "";
    if (requestId === "") {
      return json({ ok: false, error: { code: "internal_error", message: "缺少 requestId" } }, 400);
    }
    const requestedTimeout = typeof body.timeoutMs === "number" ? body.timeoutMs : this.cfg.pingTimeoutMs;
    const timeoutMs = Math.min(Math.max(Math.round(requestedTimeout), 50), 60_000);
    const deviceId = typeof body.deviceId === "string" ? body.deviceId : "unknown";

    // ★ Gate 1.5 / A1：撤销是不可绕过的**前置**判断，排在离线判断之前。
    //   顺序反过来会先报 phone_offline，把"这台设备被撤销了"这个更重要的
    //   事实藏起来 —— 审计时看不出撤销到底生效没有。
    if (this.revoked) {
      this.metrics.requestsRejectedRevoked += 1;
      await this.bestEffort("flush_metrics", () => this.flushMetrics());
      return json(this.failureFor("ping", requestId, deviceId, null, "device_revoked", "设备凭据已被撤销，拒绝任何新的设备操作"), 403);
    }

    const socket = this.currentSocket();
    if (socket === null) {
      return json<PingFailure>({
        ok: false,
        request_id: requestId,
        device_id: deviceId,
        error: { code: "phone_offline", message: "该设备当前没有已建立的连接" },
      });
    }

    // ★ Gate 1.5 / A2（D-2 的修复点）：socket 存在 ≠ 手机活着。
    //   半开连接必须**快速**报离线，而不是拖满 timeoutMs。
    if (!this.socketIsLive(socket)) {
      await this.dropStaleConnection("ping_request");
      return json<PingFailure>({
        ok: false,
        request_id: requestId,
        device_id: deviceId,
        error: {
          code: "phone_offline",
          message: `该设备已静默超过 ${this.cfg.livenessTimeoutMs} ms，应用层判定链路已死`,
        },
      });
    }

    const enqueued = await this.enqueueRequest({
      requestId,
      deviceId,
      timeoutMs,
      kind: "ping",
      toolName: null,
      frame: { type: "phone_ping", request_id: requestId, timestamp: Date.now() },
      timeoutMessage: (r) => `等待 phone_pong 超过 ${r.timeoutMs} ms`,
      sendFailureMessage: "发送 phone_ping 失败",
    });

    if (enqueued.kind === "rejected") return enqueued.response;
    return json(await enqueued.promise);
  }

  /**
   * Gate 2：通用工具请求入口。
   *
   * 与 [handlePingRequest] 共用同一套 pending 机制（见 [enqueueRequest]），
   * 差别只在三处：帧类型、超时文案、以及失败结果的形状里多一个 `tool_name`。
   *
   * `toolName` 与 `arguments` **在这里不做语义校验** —— 校验发生在两层：
   *   · Worker 的 MCP 工具注册表（工具名、参数 schema、设备授权）；
   *   · 手机自己的白名单（工具名、参数、本地授权策略）。
   * 这一层只保证"请求能被正确配对与结算"。多校验一层并不会更安全，
   * 但会让"服务端与手机的白名单不一致"这种问题变得难查。
   */
  private async handleToolRequest(request: Request): Promise<Response> {
    let body: { requestId?: unknown; timeoutMs?: unknown; deviceId?: unknown; toolName?: unknown; arguments?: unknown };
    try {
      body = (await request.json()) as typeof body;
    } catch {
      return json({ ok: false, error: { code: "internal_error", message: "请求体不是合法 JSON" } }, 400);
    }

    const requestId = typeof body.requestId === "string" ? body.requestId : "";
    if (requestId === "") {
      return json({ ok: false, error: { code: "internal_error", message: "缺少 requestId" } }, 400);
    }
    const deviceId = typeof body.deviceId === "string" ? body.deviceId : "unknown";
    const toolName = typeof body.toolName === "string" ? body.toolName : "";
    if (toolName === "") {
      return json(this.failureFor("tool", requestId, deviceId, null, "unsupported_tool", "缺少 toolName"), 400);
    }

    const requestedTimeout = typeof body.timeoutMs === "number" ? body.timeoutMs : this.cfg.pingTimeoutMs;
    const timeoutMs = Math.min(Math.max(Math.round(requestedTimeout), 50), 60_000);

    // 参数规范化成对象：`undefined` / 非对象一律当空对象，**不猜**它的内容。
    // 真正的参数校验在 Worker 与手机两侧，这里只保证自己序列化出来的帧合法。
    const rawArgs = body.arguments;
    const args: Record<string, unknown> =
      typeof rawArgs === "object" && rawArgs !== null && !Array.isArray(rawArgs) ? (rawArgs as Record<string, unknown>) : {};

    if (this.revoked) {
      this.metrics.requestsRejectedRevoked += 1;
      await this.bestEffort("flush_metrics", () => this.flushMetrics());
      return json(this.failureFor("tool", requestId, deviceId, toolName, "device_revoked", "设备凭据已被撤销，拒绝任何新的设备操作"), 403);
    }

    const socket = this.currentSocket();
    if (socket === null) {
      return json(this.failureFor("tool", requestId, deviceId, toolName, "phone_offline", "该设备当前没有已建立的连接"));
    }
    if (!this.socketIsLive(socket)) {
      await this.dropStaleConnection("tool_request");
      return json(this.failureFor("tool", requestId, deviceId, toolName, "phone_offline", `该设备已静默超过 ${this.cfg.livenessTimeoutMs} ms，应用层判定链路已死`));
    }

    const enqueued = await this.enqueueRequest({
      requestId,
      deviceId,
      timeoutMs,
      kind: "tool",
      toolName,
      frame: { type: "tool_request", request_id: requestId, tool_name: toolName, arguments: args },
      timeoutMessage: (r) => `等待 tool_result（${r.toolName ?? "unknown"}）超过 ${r.timeoutMs} ms`,
      sendFailureMessage: "发送 tool_request 失败",
    });

    if (enqueued.kind === "rejected") return enqueued.response;
    return json(await enqueued.promise);
  }

  /**
   * ping 与 tool 共用的**唯一**登记/发送路径。
   *
   * 这段顺序是整个文件里最不能改动的东西，所以刻意只写一遍：
   *
   *   1. 拒绝重复 request_id（否则第二个请求会把第一个的 settle 闭包覆盖掉，
   *      第一个调用方永久挂起）；
   *   2. 检查并发上限；
   *   3. **先** `pending.set` 再发帧（否则会出现"响应比登记还快"的窗口）；
   *   4. **先**武装内存定时器，再做落盘与排 alarm（Gate 0.3 发现项 3：
   *      记账一抛错，表项就既没定时器也没 alarm，只能等断线才被清掉）；
   *   5. 记账期间就被结算了 → **绝不发帧**（否则手机回一个没人认领的响应）；
   *   6. 发帧失败 → 立刻按 `phone_disconnected` 结算。
   *
   * 6 条里任何一条在两个入口里写法不一致，都会变成"只在某一条通道上复现"
   * 的 bug。这就是它必须是一个函数的原因。
   */
  private async enqueueRequest(params: {
    requestId: string;
    deviceId: string;
    timeoutMs: number;
    kind: "ping" | "tool";
    toolName: string | null;
    frame: ServerFrame;
    timeoutMessage: (entry: PendingRequest) => string;
    sendFailureMessage: string;
  }): Promise<{ kind: "rejected"; response: Response } | { kind: "accepted"; promise: Promise<SessionOutcome> }> {
    const { requestId, deviceId, timeoutMs, kind, toolName } = params;

    // ── Gate 1 审计发现项 B：同一个 request_id 不得存在两个未完成请求 ──────
    // 原实现直接 `this.pending.set(requestId, entry)`：第二个同 id 请求会把
    // 第一个 entry **覆盖**掉。后果有两层 ——
    //   1. 第一个请求的 `settle` 闭包再没有人持有，它的 Promise 永远不 resolve
    //      （调用方永久挂起）；
    //   2. `settlePending` 也就再也找不到它，于是那条镜像只能等 DO 重建才被清掉。
    //
    // 这里选择**显式拒绝**，而不是"让第二个请求搭第一个的便车"：搭便车意味着
    // 两个调用方共享同一个结算结果，而它们的超时长度、deviceId 都可能不同，
    // 语义上更糟。MCP 层每次都生成新的 UUID，所以正常情况下这条守卫不会触发。
    if (this.pending.has(requestId)) {
      this.metrics.pendingRejectedDuplicate += 1;
      await this.bestEffort("flush_metrics", () => this.flushMetrics());
      logEvent("warn", "phone_session.duplicate_request_id", { requestId, deviceId, kind });
      const failure = this.failureFor(kind, requestId, deviceId, toolName, "duplicate_request_id", "该 request_id 已有一个未完成的请求，拒绝覆盖");
      return { kind: "rejected", response: json(failure, 409) };
    }

    if (this.pending.size >= this.cfg.maxPendingRequests) {
      this.metrics.pendingRejectedOverLimit += 1;
      await this.flushMetrics();
      const failure = this.failureFor(kind, requestId, deviceId, toolName, "too_many_pending_requests", `待处理请求已达上限 ${this.cfg.maxPendingRequests}`);
      return { kind: "rejected", response: json(failure) };
    }

    const sentAt = Date.now();
    const deadline = sentAt + timeoutMs;
    const generation = this.generation;

    let settle!: (outcome: SessionOutcome) => void;
    const promise = new Promise<SessionOutcome>((resolve) => {
      settle = resolve;
    });

    const record: StoredPending = { requestId, deviceId, generation, sentAt, deadline, timeoutMs, kind, toolName };
    const entry: PendingRequest = {
      ...record,
      kind,
      toolName,
      settle: (outcome) => {
        if (entry.timer !== null) clearTimeout(entry.timer);
        entry.timer = null;
        settle(outcome);
      },
      timer: null,
    };

    // ⚠ 顺序很重要：先把请求登记进表，再发帧。反过来会出现"响应比登记还快"的窗口。
    this.pending.set(requestId, entry);

    // ── Gate 0.3 审计发现项 3：**先武装定时器，再做记账** ──────────────────
    entry.timer = setTimeout(() => {
      void this.settlePending(requestId, (r) => this.buildFailure(r, "phone_timeout", params.timeoutMessage(r)), "timeout");
    }, timeoutMs);

    // 落盘镜像 + alarm 兜底。即使对象随后被驱逐，超时账也不会丢。
    // 这两步失败只降级为"驱逐后这笔超时账会丢"，不会让请求挂住。
    this.storedPending[requestId] = record;
    await this.bestEffort("persist_pending", () =>
      this.ctx.storage.put(STORAGE_KEY_PENDING, this.storedPending),
    );
    await this.bestEffort("reschedule_alarm", () => this.rescheduleAlarm());

    if (!this.pending.has(requestId)) {
      // 记账期间就已经被结算了（timeoutMs 很短 + 存储很慢时会真的发生）。
      // 既然已经结算，**绝对不能**再发这一帧 —— 那会让手机回一个没人认领的响应。
      this.metrics.pingsSettledBeforeSend += 1;
      await this.bestEffort("flush_metrics", () => this.flushMetrics());
      return { kind: "accepted", promise };
    }

    const socket = this.currentSocket();
    if (socket === null) {
      // 记账期间连接没了。这不是 send 失败，但结局一样：明确失败，不重放。
      await this.failPending(requestId, "phone_disconnected", params.sendFailureMessage, "send_failed");
      return { kind: "accepted", promise };
    }

    if (kind === "tool") this.metrics.toolRequestsSent += 1;
    try {
      socket.send(encodeServerFrame(params.frame));
    } catch {
      // 审计 R2：这个 message 会一路回到 MCP 客户端（模型上下文里），
      // 所以也不能带 `String(err)`。
      await this.failPending(requestId, "phone_disconnected", params.sendFailureMessage, "send_failed");
      return { kind: "accepted", promise };
    }

    if (kind === "ping") {
      this.metrics.pingsSent += 1;
      this.metrics.lastPingAt = sentAt;
    }
    await this.bestEffort("flush_metrics", () => this.flushMetrics());

    return { kind: "accepted", promise };
  }

  private async handleStatus(): Promise<Response> {
    const socket = this.currentSocket();
    let connectedDeviceId: string | null = null;
    if (socket !== null) {
      const att = socket.deserializeAttachment() as SocketAttachment | null;
      connectedDeviceId = att?.deviceId ?? null;
    }
    if (socket !== null && !this.socketIsLive(socket)) {
      // 观测用的计数器：每一次"底层开着但应用层已死"的读取都记一笔。
      // 一个持续 > 0 的值就是"这台设备正在半开状态里"的直接证据。
      this.metrics.staleStatusReads += 1;
      await this.bestEffort("flush_metrics", () => this.flushMetrics());
    }

    return json({
      // ★ Gate 1.5 / A2：`connected` 的语义**收紧了** —— 它现在回答
      //   "这台设备此刻真的可以被操作"，而不是"这个代次有一个 socket 对象"。
      //   半开连接下前者 false、后者 true，两者都暴露给调用方，以便区分。
      connected: this.socketIsLive(socket),
      device_id: connectedDeviceId,
      /** 底层 WebSocket 是否仍然注册着（**不代表手机活着**）。 */
      socket_open: socket !== null,
      /** socket 开着但应用层已静默超阈值 —— 也就是"半开连接"。 */
      stale: socket !== null && !this.socketIsLive(socket),
      /** 设备凭据是否已被撤销。撤销过的设备不会再被接受任何操作。 */
      revoked: this.revoked,
      connection_generation: this.generation,
      open_sockets: this.ctx.getWebSockets().length,
      last_phone_activity_at: this.lastPhoneActivityAt === 0 ? null : this.lastPhoneActivityAt,
      ms_since_last_activity: this.silenceMs(),
      liveness_timeout_ms: this.cfg.livenessTimeoutMs,
      pending_requests: this.pending.size,
      pending_mirror: Object.keys(this.storedPending).length,
      config: this.cfg,
      metrics: this.metrics,
    });
  }

  /**
   * 测试专用：清空待处理表与指标。
   * 公网不可达（只有 Worker 的 DO 绑定能打到这个路径），因此不需要额外的鉴权。
   */
  private async handleTestingReset(): Promise<Response> {
    for (const entry of this.pending.values()) {
      if (entry.timer !== null) clearTimeout(entry.timer);
      entry.settle(this.failureFor(entry.kind, entry.requestId, entry.deviceId, entry.toolName, "phone_disconnected", "testing reset"));
    }
    this.pending.clear();
    this.storedPending = {};
    this.metrics = emptyMetrics();
    // 注意：**不清 revoked**。撤销是安全状态，测试要解除它必须显式重建对象，
    // 而不是"顺手 reset 一下" —— 否则 reset 就成了绕过撤销的后门。
    this.revoked = (await this.ctx.storage.get<boolean>(STORAGE_KEY_REVOKED)) === true;
    await this.ctx.storage.put({
      [STORAGE_KEY_PENDING]: {},
      [STORAGE_KEY_METRICS]: this.metrics,
    });
    return json({ ok: true, revoked: this.revoked });
  }

  // ---------------------------------------------------------------------------
  //  WebSocket 事件（休眠 API）
  // ---------------------------------------------------------------------------

  async webSocketMessage(ws: WebSocket, message: string | ArrayBuffer): Promise<void> {
    await this.ready;

    if (typeof message !== "string") {
      // Gate 0 只用文本帧。二进制帧一律拒绝，避免"两种编码两条路径"。
      this.metrics.messagesRejected += 1;
      await this.flushMetrics();
      this.safeSend(ws, { type: "error", code: "unsupported_type", message: "只接受文本帧" });
      return;
    }

    const byteLength = new TextEncoder().encode(message).byteLength;
    if (byteLength > this.cfg.maxPhoneMessageBytes) {
      this.metrics.messagesRejected += 1;
      await this.flushMetrics();
      logEvent("warn", "phone_session.message_too_large", { byteLength, limit: this.cfg.maxPhoneMessageBytes });
      this.safeSend(ws, {
        type: "error",
        code: "message_too_large",
        message: `帧超过 ${this.cfg.maxPhoneMessageBytes} 字节`,
      });
      return;
    }

    const frame = parseIncomingFrame(message);

    // ★ Gate 1.5 / A2：只有**可解析**的帧才算"手机活着"。
    //
    //   为什么不是"收到任何字节就算活着"：一台半开的手机如果还在往我们这边
    //   灌垃圾字节（或者只是 TCP 层有重传），那是**更**需要判死的情形，
    //   绝不能让它把存活计时器一直续下去。用解析结果当判据，垃圾帧既被
    //   `messagesRejected` 记账、又不会刷新存活时间。
    if (frame.type !== "unknown" && frame.type !== "invalid") {
      await this.noteActivity();
    }

    switch (frame.type) {
      case "phone_pong":
        await this.handlePong(ws, frame.request_id, frame.timestamp);
        return;

      case "tool_result":
        await this.handleToolResult(ws, frame);
        return;

      case "hello": {
        const att = ws.deserializeAttachment() as SocketAttachment | null;
        if (frame.device_id !== undefined && att !== null && frame.device_id !== att.deviceId) {
          // 只记账、不断开：Gate 0 的选择，且这条信息本身不构成安全问题
          // （能连上就已经通过这些凭据鉴权了）。
          this.metrics.messagesRejected += 1;
          await this.flushMetrics();
          logEvent("warn", "phone_session.hello_device_mismatch", {
            authenticatedDeviceId: att.deviceId,
            claimedDeviceId: frame.device_id,
          });
        } else {
          logEvent("info", "phone_session.hello", {
            deviceId: frame.device_id ?? att?.deviceId,
            appVersion: frame.app_version,
            androidSdk: frame.android_sdk,
            protocolVersion: frame.protocol_version,
          });
        }
        return;
      }

      case "heartbeat":
        this.safeSend(ws, { type: "heartbeat_ack", server_time: Date.now() });
        return;

      case "unknown":
        this.metrics.messagesRejected += 1;
        await this.flushMetrics();
        this.safeSend(ws, {
          type: "error",
          code: "unsupported_type",
          message: `不认识的帧类型：${frame.raw_type}`,
        });
        return;

      case "invalid":
        this.metrics.messagesRejected += 1;
        await this.flushMetrics();
        this.safeSend(ws, { type: "error", code: "malformed_message", message: frame.reason });
        return;
    }
  }

  async webSocketClose(ws: WebSocket, code: number, _reason: string, _wasClean: boolean): Promise<void> {
    await this.ready;
    // 审计 R2：**不**把 close 的 reason 字符串记进日志。它来自对端，是任意的
    // 自由文本 —— 一台被改造过的手机完全可以把令牌塞在里面喂给我们的日志。
    // 我们只留自己生成的原因标签和数值关闭码。
    await this.handleSocketGone(ws, "close", code);
  }

  async webSocketError(ws: WebSocket, _error: unknown): Promise<void> {
    await this.ready;
    // 同理：`String(error)` 是未经处理的异常字符串，不记。
    await this.handleSocketGone(ws, "error", null);
  }

  /**
   * 睡眠对象的定时器入口。
   *
   * 做两件事：
   *   1. 把存储里已经超过 deadline 的待处理请求记为超时 —— 这条路径存在，
   *      是为了让"进程被驱逐过"的情况下超时账仍然准确；
   *   2. ★ Gate 1.5 / A2：把已经静默超过存活阈值的连接判死并清理。
   *      没有第 2 条的话，半开连接只会在"有人来 ping"的时候才被发现，
   *      而一台没人在操作的手机可以一直以 connected=true 挂在 status 上。
   */
  async alarm(): Promise<void> {
    await this.ready;
    const now = Date.now();
    const expired = Object.values(this.storedPending).filter((p) => p.deadline <= now);
    for (const record of expired) {
      await this.settlePending(record.requestId, (r) =>
        this.buildFailure(r, "phone_timeout", r.kind === "tool"
          ? `等待 tool_result（${r.toolName ?? "unknown"}）超过 ${r.timeoutMs} ms（alarm）`
          : `等待 phone_pong 超过 ${r.timeoutMs} ms（alarm）`),
      "timeout_alarm");
    }
    await this.dropStaleConnection("alarm");
    // 记账失败不能让 alarm 处理器抛出去：一抛，下一次 alarm 的调度就更没指望了。
    await this.bestEffort("reschedule_alarm", () => this.rescheduleAlarm());
  }

  // ---------------------------------------------------------------------------
  //  内部辅助
  // ---------------------------------------------------------------------------

  /**
   * 组装一条失败结果。**形状取决于这条请求原本是哪一种** ——
   * ping 的失败不带 `tool_name`，工具的失败必须带。混用会让 MCP 层
   * 拿到一个和它发出去的东西对不上的结构。
   */
  private failureFor(
    kind: "ping" | "tool",
    requestId: string,
    deviceId: string,
    toolName: string | null,
    code: PhoneErrorCode,
    message: string,
  ): SessionOutcome {
    if (kind === "tool") {
      return { ok: false, request_id: requestId, device_id: deviceId, tool_name: toolName ?? "unknown", error: { code, message } };
    }
    return { ok: false, request_id: requestId, device_id: deviceId, error: { code, message } };
  }

  private buildFailure(record: PendingRequest, code: PhoneErrorCode, message: string): SessionOutcome {
    return this.failureFor(record.kind, record.requestId, record.deviceId, record.toolName, code, message);
  }

  /** 按 request_id 结算成一条失败。找不到就什么也不做（重复结算守卫在 settlePending 里）。 */
  private async failPending(
    requestId: string,
    code: PhoneErrorCode,
    message: string,
    cause: "timeout" | "timeout_alarm" | "disconnect" | "send_failed" | "replaced" | "stale_pong",
  ): Promise<void> {
    await this.settlePending(requestId, (r) => this.buildFailure(r, code, message), cause);
  }

  /** 当前 socket 上挂着的设备 ID（没有 socket 时返回 null）。 */
  private attachedDeviceId(): string | null {
    const socket = this.currentSocket();
    if (socket === null) return null;
    const att = socket.deserializeAttachment() as SocketAttachment | null;
    return att?.deviceId ?? null;
  }

  private async handlePong(ws: WebSocket, requestId: string, deviceTimestamp: number): Promise<void> {
    const entry = this.pending.get(requestId);

    if (entry === undefined) {
      // 可能是：迟到的响应、伪造的响应、或者已经被超时清理掉的响应。
      // 三种都必须拒绝 —— 而且**绝不能**因为"看起来像"就去完成别的请求。
      this.metrics.pongsUnknown += 1;
      await this.bestEffort("flush_metrics", () => this.flushMetrics());
      logEvent("warn", "phone_session.pong_unknown_request", { requestId, generation: this.generation });
      this.safeSend(ws, {
        type: "error",
        code: "unknown_request_id",
        message: "没有与之配对的待处理请求",
      });
      return;
    }

    const att = ws.deserializeAttachment() as SocketAttachment | null;
    const socketGeneration = att?.generation ?? -1;

    if (entry.generation !== this.generation || socketGeneration !== this.generation) {
      // R5：旧连接的响应不得完成新连接的请求。
      //
      // 这里也走 settlePending 而不是手写一遍删除逻辑（Gate 0.1 / R1）：
      // 手写版本漏掉了 rescheduleAlarm()，会让一个已经没人在等的 alarm 继续挂着。
      // 全部结算路径收敛到一个函数，是"内存表 / 落盘镜像 / 定时器三者一致"
      // 唯一能长期成立的写法。
      this.metrics.pongsStaleGeneration += 1;
      await this.settlePending(requestId, (r) => this.buildFailure(r, "invalid_pong", `pong 来自过期连接（请求代次 ${r.generation}，当前信号代次 ${this.generation}，socket 代次 ${socketGeneration}）`), "stale_pong");
      return;
    }

    if (entry.kind !== "ping") {
      // 一个 pong 来配对一个 tool_request：形状对不上，必须明确失败而不是
      // 结成一个缺字段的成功。这类帧只可能来自被改造过的手机。
      this.metrics.pongsInvalid += 1;
      await this.settlePending(requestId, (r) => this.buildFailure(r, "invalid_pong", "该 request_id 对应的是一条 tool_request，不能用 phone_pong 结算"), "stale_pong");
      return;
    }

    // RTT 只在服务端用本地时钟测量：手机自报的 timestamp 仅作记录。
    const rttMs = Date.now() - entry.sentAt;

    this.metrics.pongsMatched += 1;
    this.metrics.lastRttMs = rttMs;
    this.metrics.lastPongAt = Date.now();
    await this.settlePending(requestId, (r) => ({
      ok: true,
      request_id: r.requestId,
      device_id: r.deviceId,
      rtt_ms: rttMs,
      server_timestamp: r.sentAt,
      device_timestamp: deviceTimestamp,
      connection_generation: r.generation,
    }), "pong");
  }

  /**
   * Gate 2：结算一条 `tool_result`。
   *
   * 与 [handlePong] 一一对应，差别只在四件事：
   *   · 不认识 / 重复的 request_id → `toolResultsUnknown`（重复响应落在这里，
   *     所以"重复响应不会重复结算"是可观测的，不用去读代码才敢下结论）；
   *   · 代次不符 → `toolResultsStaleGeneration` + 明确失败；
   *   · 手机报的 error.code 归一化到闭集（认不出来的降级成 `tool_failed`，
   *     但**不丢帧** —— 丢帧会让一个已经拿到结果的请求白白超时）；
   *   · 成功结果的形状是 `{tool_name, result}`，不是 ping 的那套字段。
   */
  private async handleToolResult(ws: WebSocket, frame: ToolResultFrame): Promise<void> {
    const entry = this.pending.get(frame.request_id);

    if (entry === undefined) {
      this.metrics.toolResultsUnknown += 1;
      await this.bestEffort("flush_metrics", () => this.flushMetrics());
      logEvent("warn", "phone_session.tool_result_unknown_request", {
        requestId: frame.request_id,
        generation: this.generation,
      });
      this.safeSend(ws, {
        type: "error",
        code: "unknown_request_id",
        message: "没有与之配对的待处理请求",
      });
      return;
    }

    const att = ws.deserializeAttachment() as SocketAttachment | null;
    const socketGeneration = att?.generation ?? -1;
    if (entry.generation !== this.generation || socketGeneration !== this.generation) {
      this.metrics.toolResultsStaleGeneration += 1;
      await this.settlePending(frame.request_id, (r) => this.buildFailure(r, "phone_disconnected", `tool_result 来自过期连接（请求代次 ${r.generation}，当前信号代次 ${this.generation}，socket 代次 ${socketGeneration}）`), "stale_pong");
      return;
    }

    if (entry.kind !== "tool") {
      this.metrics.toolResultsInvalid += 1;
      await this.settlePending(frame.request_id, (r) => this.buildFailure(r, "internal_error", "该 request_id 对应的是一条 phone_ping，不能用 tool_result 结算"), "stale_pong");
      return;
    }

    const rttMs = Date.now() - entry.sentAt;
    this.metrics.lastToolResultAt = Date.now();

    if (!frame.ok) {
      const rawCode = frame.error?.code ?? "tool_failed";
      const code = normalizeToolErrorCode(rawCode);
      this.metrics.toolResultsMatched += 1;
      logEvent("info", "phone_session.tool_result_failed", {
        deviceId: entry.deviceId,
        requestId: frame.request_id,
        toolName: entry.toolName ?? undefined,
        errorCode: code,
        rawErrorCode: rawCode === code ? undefined : rawCode,
        rttMs,
      });
      await this.settlePending(frame.request_id, (r) =>
        this.buildFailure(r, code, frame.error?.message !== undefined && frame.error.message !== "" ? frame.error.message : `工具执行失败（${rawCode}）`),
      "pong");
      return;
    }

    this.metrics.toolResultsMatched += 1;
    await this.settlePending(frame.request_id, (r) => ({
      ok: true,
      request_id: r.requestId,
      device_id: r.deviceId,
      tool_name: r.toolName ?? "unknown",
      result: frame.result ?? {},
      rtt_ms: rttMs,
      server_timestamp: r.sentAt,
      connection_generation: r.generation,
    }), "pong");
  }

  /**
   * 统一的"结算"入口：保证指标只加一次、镜像只删一次、定时器只清一次。
   *
   * 这是**唯一**允许把请求从 `pending` 里拿走的地方。`this.pending.delete()`
   * 出现在本函数之外，就意味着"内存表、落盘镜像、定时器"这三样有办法不同步。
   * 开头的 `entry === undefined → return` 就是"不允许重复结算"的守卫：
   * 第二次调用什么也不做，`metrics` 也不会被加第二遍。
   */
  private async settlePending(
    requestId: string,
    build: (record: PendingRequest) => SessionOutcome,
    cause: "pong" | "timeout" | "timeout_alarm" | "disconnect" | "send_failed" | "replaced" | "stale_pong",
  ): Promise<void> {
    const entry = this.pending.get(requestId);
    if (entry === undefined) return;
    this.pending.delete(requestId);

    if (entry.timer !== null) clearTimeout(entry.timer);
    entry.timer = null;

    if (cause === "timeout" || cause === "timeout_alarm") this.metrics.timeouts += 1;

    delete this.storedPending[requestId];

    // ── Gate 0.3 审计发现项 3 + Gate 1 审计发现项 A ──────────────────────
    //
    // 历史：这三步记账原本是裸 await。`ctx.storage.put` 抛一次（存储不可用 /
    // DO 正在被驱逐），后面的 `entry.settle(...)` 就永远不执行 —— 而
    // `this.pending` 里**已经删掉了**这个请求，于是没有任何人还能再结算它，
    // 调用方（Worker → MCP 客户端）永久挂起。
    //
    // Gate 0.3 把它们降级成 `bestEffort`（不再抛错）。但"不抛错"不等于
    // "一定会返回"：存储层若**永不 resolve**（长时间无响应、DO 正在被驱逐），
    // 结算仍然被无限期推迟 —— 这正是 Gate 1 审计发现项 A 指出的残余缺陷。
    //
    // Gate 1 的修复是把顺序彻底倒过来：**先结算，后记账**。
    //   · `entry.settle()` 只做一件事：resolve 那个 Promise。它不碰 I/O，
    //     所以"调用方一定拿到结果"这条保证从此不依赖任何存储操作。
    //   · 记账三步退化成"尽力而为的副作用"，失败记一笔，挂住也只挂住
    //     当前这个 async 调用栈（最坏情况：镜像过期、指标只留在内存里）。
    //
    // 代价要说清楚：resolve 与 `storage.put` 之间不再有先后保证，DO 若在
    // 写入落盘前被驱逐，镜像里的超时账可能丢。这可以接受 —— 镜像本来就只是
    // "DO 重建时把遗留请求标记为失败"的辅助信息（见构造函数的残留处理），
    // 而挂起调用方是不可接受的。
    entry.settle(build({ ...entry }));

    await this.bestEffort("persist_pending", () =>
      this.ctx.storage.put(STORAGE_KEY_PENDING, this.storedPending),
    );
    await this.bestEffort("flush_metrics", () => this.flushMetrics());
    await this.bestEffort("reschedule_alarm", () => this.rescheduleAlarm());
  }

  private async handleSocketGone(ws: WebSocket, cause: string, closeCode: number | null): Promise<void> {
    const att = ws.deserializeAttachment() as SocketAttachment | null;
    const socketGeneration = att?.generation ?? -1;

    this.metrics.disconnects += 1;
    await this.bestEffort("flush_metrics", () => this.flushMetrics());

    logEvent("info", "phone_session.socket_gone", {
      cause,
      closeCode,
      socketGeneration,
      currentGeneration: this.generation,
    });

    if (socketGeneration !== this.generation) {
      // 被替换掉的老连接的关闭事件 —— 与当前连接无关，不要动当前的待处理请求。
      //
      // Gate 0.1 / R1 之后，属于老代次的待处理请求**已经在 handleConnect 里
      // 被结算掉了**，所以这里"什么都不做"不再意味着"它们要干等到超时"。
      // 这个 early return 现在只负责一件事：别去碰新代次的请求。
      return;
    }

    // R7：断线时未完成请求必须**显式失败**。绝不自动重放（R8）。
    const openIds = [...this.pending.keys()];
    for (const requestId of openIds) {
      await this.failPending(requestId, "phone_disconnected", `等待期间连接断开（${cause}）`, "disconnect");
    }

    // ★ Gate 1.5 / A2：socket 没了就把存活时刻清零。
    //   不清零的话，一台手机断线重连之前 `silenceMs()` 会一直增长，
    //   重连后立刻就被下一个 status 读成 stale —— 那是一个纯粹的误报。
    this.lastPhoneActivityAt = 0;

    // 连接没了，"存活阈值"这个 deadline 也就不存在了。重新排一次 alarm：
    // 待处理请求都已在上面结算（每个都各自重排过），一个都没有时这一步会把
    // alarm **清掉** —— 否则会留下一个"为已经不存在的连接准备的"定时器，
    // 让一个本该休眠的对象反复醒来。
    await this.bestEffort("reschedule_alarm", () => this.rescheduleAlarm());
  }

  private safeSend(ws: WebSocket, frame: Parameters<typeof encodeServerFrame>[0]): void {
    try {
      ws.send(encodeServerFrame(frame));
    } catch {
      // 审计 R2：只记固定类别，不记 String(err)。
      logEvent("warn", "phone_session.send_failed", { errorCode: "socket_send_failed" });
    }
  }

  /**
   * 当前代次的活跃 socket；没有就是"手机离线"。
   *
   * ⚠ 这个函数**只**回答"这个代次有没有 socket 对象"。它**不能**回答
   *   "手机还活着吗" —— 半开连接在这两个问题上有不同答案，而 D-2 缺陷
   *   正是把后者误当成前者。需要后者时用 [socketIsLive]。
   */
  private currentSocket(): WebSocket | null {
    const sockets = this.ctx.getWebSockets(`gen:${this.generation}`);
    for (const ws of sockets) {
      const att = ws.deserializeAttachment() as SocketAttachment | null;
      if (att !== null && att.generation === this.generation) return ws;
    }
    return null;
  }

  /**
   * 把一个**记账**动作降级为"尽力而为"：失败就记一笔并返回 false，绝不向外抛。
   *
   * 只用于"失败了也要继续把结果交给调用方"的动作，绝不用于鉴权、代次推进、
   * 请求登记这些**决定性**动作 —— 那些失败必须让整个操作失败。
   *
   * 注意日志只用固定类别与固定 `reason`，不记异常原文（审计 R2）。
   */
  private async bestEffort(
    step: "persist_pending" | "flush_metrics" | "reschedule_alarm" | "persist_activity",
    action: () => Promise<void>,
  ): Promise<boolean> {
    try {
      await action();
      return true;
    } catch {
      if (step === "persist_pending" || step === "persist_activity") this.metrics.settleStorageFailures += 1;
      else if (step === "flush_metrics") this.metrics.settleMetricFailures += 1;
      else this.metrics.settleAlarmFailures += 1;

      logEvent("error", "phone_session.bookkeeping_failed", {
        errorCode: step === "reschedule_alarm" ? "alarm_schedule_failed" : "storage_failure",
        reason: step,
        generation: this.generation,
      });
      return false;
    }
  }

  private async flushMetrics(): Promise<void> {
    await this.ctx.storage.put(STORAGE_KEY_METRICS, this.metrics);
  }

  /**
   * 把 alarm 排到"最早一件会到期的事"上。
   *
   * Gate 1.5 / A2 起它要多考虑一件事：**存活阈值**。没有这条的话，一台
   * 手机在 t=0 连上、然后彻底静默，对象就会一直睡着直到下一次请求或驱逐 ——
   * 也就是说"半开连接"永远不会被主动清理，只在有人来问的时候才被看见。
   */
  private async rescheduleAlarm(): Promise<void> {
    const deadlines = Object.values(this.storedPending).map((p) => p.deadline);
    if (this.currentSocket() !== null && this.lastPhoneActivityAt > 0) {
      deadlines.push(this.lastPhoneActivityAt + this.cfg.livenessTimeoutMs);
    }
    if (deadlines.length === 0) {
      const existing = await this.ctx.storage.getAlarm();
      if (existing !== null) await this.ctx.storage.deleteAlarm();
      return;
    }
    const next = Math.min(...deadlines);
    const existing = await this.ctx.storage.getAlarm();
    if (existing === null || existing > next) {
      await this.ctx.storage.setAlarm(next);
    }
  }
}

function json<T>(body: T, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json; charset=utf-8" },
  });
}

/** 供 Worker 侧构造请求用：把 deviceId 放进内部标记头。 */
export function internalHeaders(deviceId: string): Record<string, string> {
  return { [INTERNAL_DEVICE_HEADER]: deviceId };
}
