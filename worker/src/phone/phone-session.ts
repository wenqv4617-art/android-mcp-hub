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
  | "too_many_pending_requests"
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
  messagesRejected: number;
  reconstructedPendingFailed: number;
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
    messagesRejected: 0,
    reconstructedPendingFailed: 0,
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

    logEvent("info", "phone_session.connected", {
      deviceId,
      generation,
      replaced: this.metrics.connectionReplaced,
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
    } catch (err) {
      logEvent("warn", "phone_session.welcome_send_failed", { deviceId, error: String(err) });
    }

    return new Response(null, { status: 101, webSocket: client });
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

    // 落盘镜像 + alarm 兜底。即使对象随后被驱逐，超时账也不会丢。
    this.storedPending[requestId] = record;
    await this.ctx.storage.put(STORAGE_KEY_PENDING, this.storedPending);
    await this.rescheduleAlarm();

    entry.timer = setTimeout(() => {
      void this.settlePending(requestId, (r) => ({
        ok: false,
        request_id: r.requestId,
        device_id: r.deviceId,
        error: { code: "phone_timeout", message: `等待 phone_pong 超过 ${r.timeoutMs} ms` },
      }), "timeout");
    }, timeoutMs);

    try {
      socket.send(encodeServerFrame({ type: "phone_ping", request_id: requestId, timestamp: sentAt }));
    } catch (err) {
      await this.settlePending(requestId, (r) => ({
        ok: false,
        request_id: r.requestId,
        device_id: r.deviceId,
        error: { code: "phone_disconnected", message: `发送 phone_ping 失败：${String(err)}` },
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
    await this.flushMetrics();

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
            authenticated: att.deviceId,
            claimed: frame.device_id,
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

  async webSocketClose(ws: WebSocket, code: number, reason: string, _wasClean: boolean): Promise<void> {
    await this.ready;
    await this.handleSocketGone(ws, `close:${code}`, reason);
  }

  async webSocketError(ws: WebSocket, error: unknown): Promise<void> {
    await this.ready;
    await this.handleSocketGone(ws, "error", String(error));
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
    await this.rescheduleAlarm();
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
      await this.flushMetrics();
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
      this.metrics.pongsStaleGeneration += 1;
      this.pending.delete(requestId);
      if (entry.timer !== null) clearTimeout(entry.timer);
      entry.timer = null;
      delete this.storedPending[requestId];
      await this.ctx.storage.put(STORAGE_KEY_PENDING, this.storedPending);
      await this.flushMetrics();
      entry.settle({
        ok: false,
        request_id: requestId,
        device_id: entry.deviceId,
        error: {
          code: "invalid_pong",
          message: `pong 来自过期连接（请求代次 ${entry.generation}，当前信号代次 ${this.generation}，socket 代次 ${socketGeneration}）`,
        },
      });
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

  /** 统一的"结算"入口：保证指标只加一次、镜像只删一次、定时器只清一次。 */
  private async settlePending(
    requestId: string,
    build: (record: StoredPending) => PingOutcome,
    cause: "pong" | "timeout" | "timeout_alarm" | "disconnect" | "send_failed",
  ): Promise<void> {
    const entry = this.pending.get(requestId);
    if (entry === undefined) return;
    this.pending.delete(requestId);

    if (entry.timer !== null) clearTimeout(entry.timer);
    entry.timer = null;

    if (cause === "timeout" || cause === "timeout_alarm") this.metrics.timeouts += 1;

    delete this.storedPending[requestId];
    await this.ctx.storage.put(STORAGE_KEY_PENDING, this.storedPending);
    await this.flushMetrics();
    await this.rescheduleAlarm();

    entry.settle(build({ ...entry }));
  }

  private async handleSocketGone(ws: WebSocket, cause: string, detail: string): Promise<void> {
    const att = ws.deserializeAttachment() as SocketAttachment | null;
    const socketGeneration = att?.generation ?? -1;

    this.metrics.disconnects += 1;
    await this.flushMetrics();

    logEvent("info", "phone_session.socket_gone", {
      cause,
      detail,
      socketGeneration,
      currentGeneration: this.generation,
    });

    if (socketGeneration !== this.generation) {
      // 被替换掉的老连接的关闭事件 —— 与当前连接无关，不要动当前的待处理请求。
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
    } catch (err) {
      logEvent("warn", "phone_session.send_failed", { error: String(err) });
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
