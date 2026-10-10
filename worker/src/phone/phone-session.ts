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
import { encodeServerFrame, parseIncomingFrame } from "./messages";

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
  lastRttMs: number | null;
  lastPingAt: number | null;
  lastPongAt: number | null;
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
    lastRttMs: null,
    lastPingAt: null,
    lastPongAt: null,
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
}

/** 内存中的待处理请求（含 resolve/reject）。 */
interface PendingRequest extends StoredPending {
  settle: (outcome: PingOutcome) => void;
  timer: ReturnType<typeof setTimeout> | null;
}

interface SocketAttachment {
  deviceId: string;
  generation: number;
}

const STORAGE_KEY_GENERATION = "generation";
const STORAGE_KEY_METRICS = "metrics";
const STORAGE_KEY_PENDING = "pending";

/**
 * 内部路由前缀。这些路径**只**能被 Worker 通过 DO 绑定访问，公网不可达。
 *
 * ⚠ 导出它们是有意的：Worker 侧转发时必须用同一个字符串。之前 Worker 转发的
 *   是公网 URL，而 DO 只认下面这些路径，于是每一次合法的手机握手都变成 404 ——
 *   让两边共用同一个常量，这类"两份字面量悄悄分叉"的 bug 就没有发生的土壤了。
 */
export const ROUTE_CONNECT = "/internal/phone/connect";
export const ROUTE_PING = "/internal/phone/ping";
export const ROUTE_STATUS = "/internal/phone/status";
export const ROUTE_RESET = "/internal/phone/testing/reset";

/** 内部标记头：证明这次升级已经过 Worker 的设备鉴权。 */
export const INTERNAL_DEVICE_HEADER = "X-Phone-Session-Device";

/** 替换旧连接时使用的关闭码（4000–4999 为应用自定义区间）。 */
export const CLOSE_CODE_REPLACED = 4001;

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

  constructor(ctx: DurableObjectState, env: Env) {
    this.ctx = ctx;
    this.env = env;

    // blockConcurrencyWhile：在状态装载完成之前，任何事件都不会被投递。
    // 这是"重建后不会先处理消息、后读到旧状态"的保证。
    this.ready = this.ctx.blockConcurrencyWhile(async () => {
      this.cfg = loadConfig(env).runtime;

      this.generation = (await ctx.storage.get<number>(STORAGE_KEY_GENERATION)) ?? 0;
      this.metrics = { ...emptyMetrics(), ...((await ctx.storage.get<PhoneSessionMetrics>(STORAGE_KEY_METRICS)) ?? {}) };

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
        return this.handleConnect(request);
      case ROUTE_PING:
        return this.handlePingRequest(request);
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
      await this.settlePending(requestId, (r) => ({
        ok: false,
        request_id: r.requestId,
        device_id: r.deviceId,
        error: {
          code: "phone_replaced",
          message: `连接已被更新的连接替换（请求代次 ${r.generation}，当前代次 ${currentGeneration}）`,
        },
      }), "replaced");
    }

    return staleIds.length;
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

    const socket = this.currentSocket();
    if (socket === null) {
      return json<PingFailure>({
        ok: false,
        request_id: requestId,
        device_id: deviceId,
        error: { code: "phone_offline", message: "该设备当前没有已建立的连接" },
      });
    }

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
      logEvent("warn", "phone_session.duplicate_request_id", { requestId, deviceId });
      return json<PingFailure>(
        {
          ok: false,
          request_id: requestId,
          device_id: deviceId,
          error: {
            code: "duplicate_request_id",
            message: "该 request_id 已有一个未完成的请求，拒绝覆盖",
          },
        },
        409,
      );
    }

    if (this.pending.size >= this.cfg.maxPendingRequests) {
      this.metrics.pendingRejectedOverLimit += 1;
      await this.flushMetrics();
      return json<PingFailure>({
        ok: false,
        request_id: requestId,
        device_id: deviceId,
        error: {
          code: "too_many_pending_requests",
          message: `待处理请求已达上限 ${this.cfg.maxPendingRequests}`,
        },
      });
    }

    const sentAt = Date.now();
    const deadline = sentAt + timeoutMs;
    const generation = this.generation;

    let settle!: (outcome: PingOutcome) => void;
    const promise = new Promise<PingOutcome>((resolve) => {
      settle = resolve;
    });

    const record: StoredPending = { requestId, deviceId, generation, sentAt, deadline, timeoutMs };
    const entry: PendingRequest = {
      ...record,
      settle: (outcome) => {
        if (entry.timer !== null) clearTimeout(entry.timer);
        entry.timer = null;
        settle(outcome);
      },
      timer: null,
    };

    // ⚠ 顺序很重要：先把请求登记进表，再发帧。反过来会出现"pong 比登记还快"的窗口。
    this.pending.set(requestId, entry);

    // ── Gate 0.3 审计发现项 3：**先武装定时器，再做记账** ──────────────────
    // 原来的顺序是"落盘 → 排 alarm → 定时器"。落盘或 alarm 一抛错，这个已经登记
    // 进 `this.pending` 的请求就既没有定时器、也没有 alarm —— 表项泄漏，而且只能
    // 等下一次断线才可能被清掉。定时器是最后一道兜底，必须**最先**就位。
    entry.timer = setTimeout(() => {
      void this.settlePending(requestId, (r) => ({
        ok: false,
        request_id: r.requestId,
        device_id: r.deviceId,
        error: { code: "phone_timeout", message: `等待 phone_pong 超过 ${r.timeoutMs} ms` },
      }), "timeout");
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
      // 既然已经结算，**绝对不能**再发这一帧 —— 那会让手机回一个没人认领的 pong。
      this.metrics.pingsSettledBeforeSend += 1;
      await this.bestEffort("flush_metrics", () => this.flushMetrics());
      return json(await promise);
    }

    try {
      socket.send(encodeServerFrame({ type: "phone_ping", request_id: requestId, timestamp: sentAt }));
    } catch {
      // 审计 R2：这个 message 会一路回到 MCP 客户端（模型上下文里），
      // 所以也不能带 `String(err)`。
      await this.settlePending(requestId, (r) => ({
        ok: false,
        request_id: r.requestId,
        device_id: r.deviceId,
        error: { code: "phone_disconnected", message: "发送 phone_ping 失败" },
      }), "send_failed");
      return json<PingFailure>({
        ok: false,
        request_id: requestId,
        device_id: deviceId,
        error: { code: "phone_disconnected", message: "发送 phone_ping 失败" },
      });
    }

    this.metrics.pingsSent += 1;
    this.metrics.lastPingAt = sentAt;
    await this.bestEffort("flush_metrics", () => this.flushMetrics());

    const outcome = await promise;
    return json(outcome);
  }

  private async handleStatus(): Promise<Response> {
    const socket = this.currentSocket();
    let connectedDeviceId: string | null = null;
    if (socket !== null) {
      const att = socket.deserializeAttachment() as SocketAttachment | null;
      connectedDeviceId = att?.deviceId ?? null;
    }
    return json({
      connected: socket !== null,
      device_id: connectedDeviceId,
      connection_generation: this.generation,
      open_sockets: this.ctx.getWebSockets().length,
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
      entry.settle({
        ok: false,
        request_id: entry.requestId,
        device_id: entry.deviceId,
        error: { code: "phone_disconnected", message: "testing reset" },
      });
    }
    this.pending.clear();
    this.storedPending = {};
    this.metrics = emptyMetrics();
    await this.ctx.storage.put({
      [STORAGE_KEY_PENDING]: {},
      [STORAGE_KEY_METRICS]: this.metrics,
    });
    return json({ ok: true });
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

    switch (frame.type) {
      case "phone_pong":
        await this.handlePong(ws, frame.request_id, frame.timestamp);
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
   * 只做一件事：把存储里已经超过 deadline 的待处理请求记为超时。
   * 这条路径存在，是为了让"进程被驱逐过"的情况下超时账仍然准确。
   */
  async alarm(): Promise<void> {
    await this.ready;
    const now = Date.now();
    const expired = Object.values(this.storedPending).filter((p) => p.deadline <= now);
    for (const record of expired) {
      await this.settlePending(record.requestId, (r) => ({
        ok: false,
        request_id: r.requestId,
        device_id: r.deviceId,
        error: { code: "phone_timeout", message: `等待 phone_pong 超过 ${r.timeoutMs} ms（alarm）` },
      }), "timeout_alarm");
    }
    // 记账失败不能让 alarm 处理器抛出去：一抛，下一次 alarm 的调度就更没指望了。
    await this.bestEffort("reschedule_alarm", () => this.rescheduleAlarm());
  }

  // ---------------------------------------------------------------------------
  //  内部辅助
  // ---------------------------------------------------------------------------

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
      await this.settlePending(requestId, (r) => ({
        ok: false,
        request_id: r.requestId,
        device_id: r.deviceId,
        error: {
          code: "invalid_pong",
          message: `pong 来自过期连接（请求代次 ${r.generation}，当前信号代次 ${this.generation}，socket 代次 ${socketGeneration}）`,
        },
      }), "stale_pong");
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
   * 统一的"结算"入口：保证指标只加一次、镜像只删一次、定时器只清一次。
   *
   * 这是**唯一**允许把请求从 `pending` 里拿走的地方。`this.pending.delete()`
   * 出现在本函数之外，就意味着"内存表、落盘镜像、定时器"这三样有办法不同步。
   * 开头的 `entry === undefined → return` 就是"不允许重复结算"的守卫：
   * 第二次调用什么也不做，`metrics` 也不会被加第二遍。
   */
  private async settlePending(
    requestId: string,
    build: (record: StoredPending) => PingOutcome,
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
      await this.settlePending(requestId, (r) => ({
        ok: false,
        request_id: r.requestId,
        device_id: r.deviceId,
        error: { code: "phone_disconnected", message: `等待期间连接断开（${cause}）` },
      }), "disconnect");
    }
  }

  private safeSend(ws: WebSocket, frame: Parameters<typeof encodeServerFrame>[0]): void {
    try {
      ws.send(encodeServerFrame(frame));
    } catch {
      // 审计 R2：只记固定类别，不记 String(err)。
      logEvent("warn", "phone_session.send_failed", { errorCode: "socket_send_failed" });
    }
  }

  /** 当前代次的活跃 socket；没有就是"手机离线"。 */
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
    step: "persist_pending" | "flush_metrics" | "reschedule_alarm",
    action: () => Promise<void>,
  ): Promise<boolean> {
    try {
      await action();
      return true;
    } catch {
      if (step === "persist_pending") this.metrics.settleStorageFailures += 1;
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

  private async rescheduleAlarm(): Promise<void> {
    const deadlines = Object.values(this.storedPending).map((p) => p.deadline);
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
