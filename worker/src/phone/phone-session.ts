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

import {
  activeCredentialsFor,
  isCredentialActive,
  isDeviceRevoked,
  loadConfig,
  PHONE_LINK_PROTOCOL_VERSION,
  type AppConfig,
  type RuntimeConfig,
} from "../config";
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
  /**
   * ★ 集中审计问题 1：因为"这条连接绑定的凭据已经失效"而被拒的请求数。
   *
   * 与 `requestsRejectedRevoked`（设备级撤销）分开计数：轮换与吊销是两种不同的
   * 安全事件，合成一个数会让"到底发生过哪一种"变模糊。
   */
  credentialRejections: number;
  /**
   * ★ 集中审计问题 2：一次**受保护的重新授权** —— 撤销之后又用当前有效凭据
   * 成功建立了连接。这个数 > 0 就是"撤销不是永久封印，但必须重新出示有效凭据"
   * 的直接证据。
   */
  reauthorizationsApplied: number;
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
    credentialRejections: 0,
    reauthorizationsApplied: 0,
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
  /**
   * 建立这条连接时用它完成升级鉴权的**凭据指纹**（`tokenSha256`）。
   *
   * ★ 集中审计问题 1：以前附件只有 `{ deviceId, generation }`，于是"这个代次
   *   有一个 socket"就等于"这台设备可以被操作" —— 而任意一条该设备的凭据
   *   （包括轮换后那条**已被撤销**的旧凭据）开出来的连接都满足这个条件。
   *   轮换之后旧连接仍然是"当前代次的 socket"，phone_ping 照常被它完成。
   *   把指纹钉在连接上，才可能回答"这条连接是用哪条凭据开的、那条凭据今天还有效吗"。
   */
  credentialId: string;
}

/**
 * 最近一次观察到的撤销。**审计用，不是门禁。**
 *
 * ★ 集中审计问题 2：以前这里是 `STORAGE_KEY_REVOKED` 的一个**粘性布尔**，而且
 *   它是"这台设备被撤销了"的唯一判据 —— 一旦落盘就再也回不去：即使后来给这台
 *   设备配了有效的新令牌，DO 依然对每一次升级回 403。
 *
 *   安全状态必须与**当前凭据表**一致，否则"重新授权"在 DO 这一层永远不可能发生。
 *   现在门禁完全由当前配置推导（见 [revocationCheck]），这个记录只负责在
 *   status/日志里说明"最近一次撤销发生在何时、针对整台设备还是某一条凭据"。
 */
interface RevocationRecord {
  at: number;
  scope: "device" | "credential";
  credentialId: string | null;
  generation: number;
}

const STORAGE_KEY_GENERATION = "generation";
const STORAGE_KEY_METRICS = "metrics";
const STORAGE_KEY_PENDING = "pending";
const STORAGE_KEY_ACTIVITY = "last_activity_at";
/** 最近一次撤销的审计记录（见 [RevocationRecord]）。 */
const STORAGE_KEY_REVOCATION = "revocation";
/** 旧版本（Gate 1.5）写下的粘性布尔。只用来给审计记录补一个 `at`，不再是门禁。 */
const STORAGE_KEY_REVOKED_LEGACY = "revoked_device_ids";

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
/**
 * 内部标记头：这次升级**用哪一条凭据**通过了鉴权（`tokenSha256`）。
 *
 * ★ 集中审计问题 1：只有 Worker 知道"是哪条凭据匹配上的"，DO 必须被告知，
 *   才可能把连接绑定到具体凭据。缺失时 DO **拒绝**升级（fail-closed）——
 *   一个不可验证来源的连接比一条明确失败的连接危险得多。
 *   指纹不是令牌：它是 sha256 的十六进制，泄漏它也不能用来鉴权。
 */
export const INTERNAL_CREDENTIAL_HEADER = "X-Phone-Session-Credential";

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
   * ★ 集中审计问题 2：最近一次观察到的撤销（**审计记录，不是门禁**）。
   *
   * 落盘是为了跨驱逐还能说清"撤销发生过"，但**判据永远是当前配置**：
   * `revocationCheck()` 每次都重新读凭据表，因此
   *   · 撤销 → 立即拒绝（配置里没有有效凭据）；
   *   · 之后配了新令牌 → 新连接被接受（配置里有了有效凭据），并记一次
   *     `reauthorizationsApplied` —— 这就是那次**明确、受保护的状态转换**。
   */
  private revocation: RevocationRecord | null = null;
  /**
   * 这台 DO 实例最近一次**成功建连**的 deviceId（内存态，不是持久态）。
   *
   * 为什么需要它：`status.revoked` 要能回答"这台设备现在被撤销了吗"，而连接
   * 被撤销关闭之后 `currentSocket()` 就没了 —— 只按当前 socket 算会让一台刚被
   * 撤销的设备在 status 上显示 `revoked: false`，恰好与运维要看的相反。
   */
  private lastConnectedDeviceId: string | null = null;
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

      const record = await ctx.storage.get<RevocationRecord>(STORAGE_KEY_REVOCATION);
      if (record !== null && record !== undefined && typeof record.at === "number") {
        this.revocation = record;
      } else {
        // 旧版本的粘性布尔：读出来只补一条审计记录（`at` 无从得知，用 0 表示
        // "版本升级前发生的"）。**它不再是门禁** —— 门禁在 [revocationCheck]。
        const legacy = (await ctx.storage.get<boolean>(STORAGE_KEY_REVOKED_LEGACY)) === true;
        this.revocation = legacy ? { at: 0, scope: "device", credentialId: null, generation: this.generation } : null;
      }
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
        // ★ Gate 1.5 / A1 的第二道闸 + 集中审计问题 1/2。
        //   Worker 已经用凭据表拦过一次（撤销后新升级会 401），但 DO 自己也必须
        //   拒绝 —— 否则"先建立连接、撤销之后又被 Worker 用旧路径转发进来"
        //   这条窗口仍然存在。这里把判据改成**按当前配置推导**（见
        //   [revocationCheck]），因此重新授权之后不会再被永久封印。
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

    // 升级形状的校验排在内部凭据头**之前**：426 描述的是"这次请求的传输形状
    // 不对"，与内部记账无关。把缺失内部头排在前面会让一条普通 HTTP 请求收到
    // 500（看起来像服务端坏了），而它其实只是一次用错方法的调用。
    if (request.headers.get("Upgrade")?.toLowerCase() !== "websocket") {
      this.metrics.upgradesRejected += 1;
      await this.flushMetrics();
      return new Response(JSON.stringify({ error: "expected_websocket_upgrade" }), { status: 426 });
    }

    // ★ 集中审计问题 1：没有凭据指纹就**不接受**这条连接。
    //
    //   这条道路只可能由 Worker 打进来（内部路由没有公网映射），而 Worker
    //   永远会带上它。缺失意味着"我们无法回答这条连接是用哪条凭据开的"——
    //   那就宁可不建立连接（fail-closed），也不要一条无法复核来源的长连接。
    const credentialId = request.headers.get(INTERNAL_CREDENTIAL_HEADER);
    if (!credentialId) {
      this.metrics.upgradesRejected += 1;
      await this.flushMetrics();
      logEvent("warn", "phone_session.upgrade_rejected", { reason: "missing_internal_credential_header" });
      return new Response(JSON.stringify({ error: "missing_internal_credential_header" }), { status: 500 });
    }

    // ★ 集中审计问题 1/2：门禁按**当前配置**推导，而不是按一个落盘后就改不回去
    //   的布尔值。两种拒绝要分得清：
    //     · 设备级：这台设备此刻一条有效凭据都没有（真正的撤销/吊销）；
    //     · 凭据级：这条指纹对应的凭据已被撤销，但设备还有别的有效凭据
    //       （**轮换** —— 旧令牌被换掉了，整台设备并没有被撤销）。
    //   后者以前根本没有这个概念，于是轮换之后旧连接一直活着继续服务请求。
    const rejected = this.connectionRejection(deviceId, credentialId);
    if (rejected !== null) {
      this.metrics.upgradesRejected += 1;
      await this.flushMetrics();
      logEvent("warn", "phone_session.upgrade_rejected", { reason: rejected });
      return new Response(JSON.stringify({ error: rejected }), { status: 403 });
    }

    // ★ 集中审计问题 2：一次**明确的重新授权转换**。
    //
    //   走到这里说明：这台设备曾被撤销过（有审计记录），而现在它又用一条
    //   **当前配置里有效**的凭据通过了鉴权 —— 上面那道门禁刚刚验证过这一点。
    //   因此这次转换是受保护的：唯一的入口就是"拿有效的凭据完成升级"，
    //   `testing/reset` 依然清不掉撤销记录（它不是门禁，但也不是可 reset 的
    //   普通状态）。
    if (this.revocation !== null) {
      logEvent("info", "phone_session.reauthorized", {
        deviceId,
        previousScope: this.revocation.scope,
        previousAt: this.revocation.at,
      });
      this.revocation = null;
      this.metrics.reauthorizationsApplied += 1;
      await this.ctx.storage.delete(STORAGE_KEY_REVOCATION);
      await this.flushMetrics();
    }

    const pair = new WebSocketPair();
    const client = pair[0];
    const server = pair[1];

    const previousSockets = this.ctx.getWebSockets();

    // 代次先递增再接受：从这一刻起的任何 pong 只有在标着新代次时才可能被接受。
    this.generation += 1;
    const generation = this.generation;
    const attachment: SocketAttachment = { deviceId, generation, credentialId };
    server.serializeAttachment(attachment);
    // 记下"这台 DO 最近服务的是哪台设备"，让连接关掉之后 status 仍能回答
    // "这台设备现在被撤销了吗"（见 [lastConnectedDeviceId]）。
    this.lastConnectedDeviceId = deviceId;

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

    // ★ 集中审计问题 3：**连接建立之后必须立刻安排存活 alarm**。
    //
    //   审计复现的现象是"新连接建立后、还没有任何请求时 `getAlarm()` 返回 null"。
    //   原因很实在：在这之前只有一个地方会为存活阈值排 alarm，就是
    //   `enqueueRequest()` 与 `handleSocketGone()`；而一台刚连上、还没被请求
    //   碰过的手机两条路都没走过。
    //
    //   后果不是"少一个优化"，而是这条：**完全静默的连接永远不会被主动判死**。
    //   它只在"有人来 ping"或"对象被驱逐"时才被发现，于是 `status` 上的
    //   `connected` 可以一直是真的，而手机早就没了。
    await this.bestEffort("reschedule_alarm", () => this.rescheduleAlarm());

    return new Response(null, { status: 101, webSocket: client });
  }

  // ---------------------------------------------------------------------------
  //  集中审计问题 1/2：按**当前配置**推导的授权与撤销判据
  // ---------------------------------------------------------------------------

  /**
   * 当前配置（不是构造时的快照）。
   *
   * `loadConfig` 自己带按 cacheKey 的模块级缓存，所以在请求路径上调用它几乎
   * 不要钱；而**必须**用它而不是 `this.cfg` 的原因正是问题 2：给一台设备补一条
   * 新凭据（或撤销一条旧凭据）时，DO 实例可能还活着，它手上的快照已经过期了。
   */
  private currentConfig(): AppConfig {
    return loadConfig(this.env);
  }

  /** 这台设备此刻还有没有有效凭据（= 设备级撤销/吊销）。 */
  private deviceRevokedNow(deviceId: string): boolean {
    return isDeviceRevoked(this.currentConfig(), deviceId);
  }

  /**
   * 升级入口的门禁：这条连接该不该被接受。
   *
   * 返回 `null` = 接受；返回错误串 = 拒绝原因（会原样进响应体与日志）。
   * 判据全部来自当前配置，因此重新授权之后自动放行。
   */
  private connectionRejection(deviceId: string, credentialId: string): "device_revoked" | "credential_revoked" | null {
    const config = this.currentConfig();
    if (isDeviceRevoked(config, deviceId)) return "device_revoked";
    if (!isCredentialActive(config, deviceId, credentialId)) return "credential_revoked";
    return null;
  }

  /**
   * 请求入口与上行帧入口的门禁：**当前这条连接**绑定的凭据还有效吗？
   *
   * 这是问题 1 的核心修复点。以前两个请求入口只看 `this.revoked`（设备级、
   * 且是粘性布尔），于是"用旧凭据建立的连接"在轮换之后仍然能收发请求。
   * 现在每次请求、每个上行帧都要重新问一次配置。
   *
   * 返回 `null` = 放行；否则给出拒绝范围（设备级 / 凭据级）与涉及的 socket。
   */
  private revocationCheck(requestedDeviceId: string): { scope: "device" | "credential"; deviceId: string; credentialId: string | null; socket: WebSocket | null } | null {
    const config = this.currentConfig();
    const socket = this.currentSocket();
    const att = socket !== null ? (socket.deserializeAttachment() as SocketAttachment | null) : null;
    // 连接绑定的 deviceId 优先：它来自 Worker 的鉴权结果，比请求体里那个更可信。
    const deviceId = att?.deviceId ?? requestedDeviceId;

    if (isDeviceRevoked(config, deviceId)) {
      return { scope: "device", deviceId, credentialId: att?.credentialId ?? null, socket };
    }
    if (att !== null && !isCredentialActive(config, deviceId, att.credentialId)) {
      return { scope: "credential", deviceId, credentialId: att.credentialId, socket };
    }
    return null;
  }

  /**
   * 请求入口命中撤销门禁时的共同动作：记账 + 终结。
   *
   * 记账用 `credentialRejections` 单独统计"凭据级"拒绝（问题 1/2 的观测量），
   * 终结交给 [terminateSockets]，保证在途请求被**明确**判失败而不是拖到超时。
   */
  private async noteRevokedRequest(rejection: { scope: "device" | "credential"; credentialId: string | null }): Promise<void> {
    this.metrics.requestsRejectedRevoked += 1;
    if (rejection.scope === "credential") this.metrics.credentialRejections += 1;
    await this.bestEffort("flush_metrics", () => this.flushMetrics());
    await this.terminateSockets({
      scope: rejection.scope,
      credentialId: rejection.credentialId,
      reason: rejection.scope === "credential" ? "request_with_revoked_credential" : "request_with_revoked_device",
    });
  }

  /** 撤销门禁的对外文案：wire 码始终是闭集里的 `device_revoked`，靠 message 区分范围。 */
  private revokedMessage(scope: "device" | "credential"): string {
    return scope === "credential"
      ? "该连接绑定的设备凭据已被撤销或轮换，拒绝任何新的设备操作"
      : "设备凭据已被撤销，拒绝任何新的设备操作";
  }

  /**
   * 记下"发生过一次撤销"（审计用），并把记录落盘。
   *
   * 幂等性判据是 `(scope, credentialId)`：同一条凭据被重复通知不会重复换代次、
   * 也不会让 `revocationsApplied` 翻倍 —— 那个数要能当"真实安全事件次数"用。
   */
  private async recordRevocation(scope: "device" | "credential", credentialId: string | null, deviceId: string): Promise<boolean> {
    const already =
      this.revocation !== null &&
      this.revocation.scope === scope &&
      this.revocation.credentialId === credentialId;
    if (already) return false;

    this.revocation = { at: Date.now(), scope, credentialId, generation: this.generation };
    this.metrics.revocationsApplied += 1;
    await this.ctx.storage.put({
      [STORAGE_KEY_REVOCATION]: this.revocation,
      [STORAGE_KEY_METRICS]: this.metrics,
    });
    logEvent("warn", "phone_session.revocation_recorded", {
      deviceId,
      scope,
      credentialScoped: credentialId !== null,
    });
    return true;
  }

  /**
   * 把**指定范围**的连接与在途请求一并终结。
   *
   * 与 [handleRevoke] 的关系：这里是执行体，`handleRevoke` 是入口。
   * 分开的理由是问题 1/2 带来的新需求 —— 撤销范围不再只有"整台设备"，
   * 还有"这一条凭据"，而"在请求路径上发现连接已失效"也要走同一套终结逻辑。
   *
   * 代次一定 +1：关闭 socket 与 close 事件到达之间有窗口，不换代次的话一个
   * 在途的 pong/tool_result 仍然匹配得上，那正是"撤销之后还收到一次成功"。
   */
  private async terminateSockets(options: {
    scope: "device" | "credential";
    credentialId: string | null;
    reason: string;
  }): Promise<{ closed: number; pendingFailed: number }> {
    // 先选出"这次到底该终结哪些连接"，**再**决定要不要换代次 —— 顺序很重要，
    // 因为代次是 DO 级别的全局计数，"换一次"就等于宣布"所有在途请求都作废"
    // （`handlePong` 要求 `entry.generation === this.generation` **且**
    // socket 自己的代次也等于它）。
    const current = this.currentSocket();
    const targets = this.ctx.getWebSockets().filter((socket) => {
      if (options.scope !== "credential") return true;
      const att = socket.deserializeAttachment() as SocketAttachment | null;
      return att?.credentialId === options.credentialId;
    });

    // ★ 问题 2 的"不要误伤"，第二层：一次凭据级撤销**最多**只能影响到
    //   那条凭据自己的连接。
    //
    //   最典型的场景：旧凭据 A 开的那条连接早就被替换掉了，一条针对 A 的
    //   迟到/重复撤销通知此刻才到，而当前连接是新凭据 B 的。如果把 A 的连接
    //   一起算进"要换代次"，B 上所有在途请求会在 pong 回来时被判成
    //   "来自过期连接"，B 自己也会因为代次不符而无法再被 `currentSocket()`
    //   认出来。那这条撤销通知本身就成了拒绝服务路径 —— 正是要堵的东西。
    const touchesCurrent = options.scope === "device" || (current !== null && targets.includes(current));
    if (options.scope === "credential" && !touchesCurrent) {
      // 没有任何活着的连接属于那条凭据：什么都不动，只让审计记录留下。
      return { closed: 0, pendingFailed: 0 };
    }

    const deadGenerations = new Set(
      targets.map((socket) => (socket.deserializeAttachment() as SocketAttachment | null)?.generation ?? -1),
    );
    const pendingIds = [...this.pending.values()]
      .filter((entry) => (options.scope === "credential" ? deadGenerations.has(entry.generation) : true))
      .map((entry) => entry.requestId);
    for (const requestId of pendingIds) {
      this.metrics.pendingRevoked += 1;
      await this.failPending(requestId, "device_revoked", "设备凭据已被撤销，该请求被明确终止", "disconnect");
    }

    this.generation += 1;
    const generation = this.generation;
    await this.ctx.storage.put({
      [STORAGE_KEY_GENERATION]: generation,
      [STORAGE_KEY_METRICS]: this.metrics,
    });

    let closed = 0;
    for (const socket of targets) {
      try {
        // 先给一帧可读的原因，再关。手机侧拿不到 reason 时会无从判断
        // "是网络断了还是我被撤销了" —— 那会让它白重连很多次。
        this.safeSend(socket, { type: "error", code: "device_revoked", message: "设备凭据已被撤销，连接终止" });
        socket.close(CLOSE_CODE_REVOKED, "device credential revoked");
        closed += 1;
      } catch {
        // 已经关了 —— 无所谓。
      }
    }

    await this.bestEffort("reschedule_alarm", () => this.rescheduleAlarm());
    return { closed, pendingFailed: pendingIds.length };
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
   * 语义（任务书 A1 的四条要求 + 集中审计问题 1/2）：
   *   1. 撤销后不得接受新的设备操作请求 → 请求入口每次都用**当前配置**重新判定
   *      （见 [revocationCheck]），而不是看一个落盘的布尔值；
   *   2. 已建立连接在**可验证且距今仍然成立**的撤销通知到达后关闭 → 这里立刻
   *      close(4002)；"仍然成立"是 Gate 2 补上的：迟到的通知不得误伤已重新授权的连接；
   *   3. 未完成请求必须明确失败 → 全部结算成 `device_revoked`；
   *   4. 不得影响其他设备 → 撤销状态只存在**这台设备自己的** DO 里。
   *
   * ★ 问题 1/2 带来的两个新能力：
   *   · `credentialId`（可选）：**凭据级**撤销。只关掉那一条凭据开出来的连接。
   *     轮换（旧凭据 revokedAt 有值 + 新凭据上线）走的就是这条 —— 它**不是**
   *     设备撤销，所以绝不能把设备上其他（新）凭据的连接一起关掉。
   *   · 撤销记录本身不再是门禁：它只是一条审计记录。真正的判据永远是
   *     "当前配置里这条凭据/这台设备还有效吗"，因此给设备补一条新凭据之后，
   *     升级会被接受并记一次 `reauthorizationsApplied`。
   *
   * ★ Gate 2：通知是**异步、可能迟到**的，所以到达时必须**再问一次当前配置** ——
   *   目标此刻已经有效 ⇒ 这条通知已过期，直接忽略（不关连接、不换代次、不记账），
   *   响应里带 `stale: true`。详见 [handleRevoke] 内的注释。
   */
  private async handleRevoke(request: Request): Promise<Response> {
    let body: { deviceId?: unknown; reason?: unknown; credentialId?: unknown };
    try {
      body = (await request.json()) as typeof body;
    } catch {
      return json({ error: "invalid_json" }, 400);
    }
    const requestedDeviceId = typeof body.deviceId === "string" && body.deviceId !== "" ? body.deviceId : null;
    if (requestedDeviceId === null) return json({ error: "missing_device_id" }, 400);

    const reason = typeof body.reason === "string" && body.reason !== "" ? body.reason : "credential_revoked";
    // 空串与缺省等价："没有指定凭据"就是设备级撤销。
    const requestedCredentialId =
      typeof body.credentialId === "string" && body.credentialId !== "" ? body.credentialId : null;
    const scope: "device" | "credential" = requestedCredentialId === null ? "device" : "credential";

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

    // ★ Gate 2：撤销通知是**异步、可能迟到**的，所以到达时**必须再问一次当前配置**。
    //
    //   通知由 `worker/src/index.ts` 在"拒绝一次升级"时推出来（设备级、或凭据级），
    //   它要跨一次 HTTP、并排在这个 DO 正在处理的其他请求后面。在这段路上配置
    //   完全可能已经变了。最典型的一条时间线：
    //
    //     ① 旧配置里这台设备没有任何有效凭据（于是那次升级被拒，通知发出）；
    //     ② 运维给这台设备补上一条新凭据（设备重新有效）；
    //     ③ 手机用新凭据连上来，这条连接完全合法；
    //     ④ ①的那条通知现在才到。
    //
    //   如果到达时无条件执行，这条**过期**的通知会关掉一条合法的连接、并把它在途
    //   的请求结算成 `device_revoked` —— 一条迟到的通知就变成了一条拒绝服务路径
    //   （正是集中审计问题 2 要消除的那类误伤；凭据级同理：轮换掉的旧凭据被重新
    //   启用之后，关于它的迟到通知不能终结它）。
    //
    //   所以判据与升级入口 / 请求入口**完全一致**：配置才是门禁，通知只负责把
    //   "配置层已经成立的事实"立刻施加到活连接上。
    //     · 设备级：此刻这台设备还有没有有效凭据（`activeCredentialsFor` 为空即
    //       "所有凭据都已撤销"或"这台设备已被整个删掉"，两种都算仍然无效）；
    //     · 凭据级：那条指纹此刻还有效吗（`isCredentialActive` 对"配置里已经没有
    //       这条凭据"也返回 false）。
    //   目标此刻仍然无效 ⇒ 照常记账 + 终结；否则什么都不做：**不关连接、不换代次、
    //   也不记账**（否则 `revocationsApplied` 会把一次过期投递记成一次真实安全事件）。
    const config = this.currentConfig();
    const targetStillInvalid =
      scope === "device"
        ? activeCredentialsFor(config, requestedDeviceId).length === 0
        : !isCredentialActive(config, requestedDeviceId, requestedCredentialId as string);
    if (!targetStillInvalid) {
      logEvent("warn", "phone_session.revoke_stale_ignored", {
        deviceId: requestedDeviceId,
        scope,
        reason,
        currentGeneration: this.generation,
      });
      return json({
        ok: true,
        device_id: requestedDeviceId,
        revoked: false,
        stale: true,
        scope,
        already_revoked: false,
        closed: 0,
        pending_failed: 0,
        generation: this.generation,
      });
    }

    // 幂等：撤销通知**可能被重复投递**（每个被拒的升级都会推一条，
    // `worker/src/index.ts` 就是这么做的）。重复投递不该让指标翻倍 ——
    // 否则"撤销了几次"这个数就没法用来判断到底发生了多少次真实的安全事件。
    const isNewRevocation = await this.recordRevocation(scope, requestedCredentialId, requestedDeviceId);
    const alreadyRevoked = !isNewRevocation;

    // 幂等再次进来时这张表必然为空 —— 撤销之后没有任何入口能再登记请求。
    const terminated = await this.terminateSockets({
      scope,
      credentialId: requestedCredentialId,
      reason,
    });

    logEvent("warn", "phone_session.revoked", {
      deviceId: requestedDeviceId,
      scope,
      generation: this.generation,
      closed: terminated.closed,
      pendingFailed: terminated.pendingFailed,
      reason,
    });

    return json({
      ok: true,
      device_id: requestedDeviceId,
      revoked: true,
      scope,
      already_revoked: alreadyRevoked,
      closed: terminated.closed,
      pending_failed: terminated.pendingFailed,
      generation: this.generation,
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
    // ★ 集中审计问题 3：每一次"手机还活着"的证据（hello / 任何合法帧）都要把
    //   存活 deadline 往后推。不重排的话，一台只在 t=0 发过一次 hello、
    //   之后正常发心跳的手机，会在 `livenessTimeoutMs` 到点时被自己的旧 alarm 判死。
    await this.bestEffort("reschedule_alarm", () => this.rescheduleAlarm());
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

    // ★ 集中审计问题 1/2：撤销是不可绕过的**前置**判断，排在离线判断之前。
    //   顺序反过来会先报 phone_offline，把"这台设备被撤销了"这个更重要的
    //   事实藏起来 —— 审计时看不出撤销到底生效没有。
    //   判据来自**当前配置**（`revocationCheck`），不是落盘的粘性布尔：这样
    //   重新授权之后同一条路径会自动放行，而用旧凭据开着的连接会被当场拒。
    const rejection = this.revocationCheck(deviceId);
    if (rejection !== null) {
      await this.noteRevokedRequest(rejection);
      return json(this.failureFor("ping", requestId, deviceId, null, "device_revoked", this.revokedMessage(rejection.scope)), 403);
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

    // 与 [handlePingRequest] 同一套门禁（问题 1/2）：判据来自当前配置，
    // 且在离线判断之前 —— 撤销优先于"手机不在线"。
    const rejection = this.revocationCheck(deviceId);
    if (rejection !== null) {
      await this.noteRevokedRequest(rejection);
      return json(this.failureFor("tool", requestId, deviceId, toolName, "device_revoked", this.revokedMessage(rejection.scope)), 403);
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
    let boundCredentialActive: boolean | null = null;
    if (socket !== null) {
      const att = socket.deserializeAttachment() as SocketAttachment | null;
      connectedDeviceId = att?.deviceId ?? null;
      // ★ 问题 1：报告的是"这条连接绑定的凭据此刻还有效吗"，用的是当前配置。
      //   只暴露布尔值，**不暴露指纹本身** —— status 是给运维看的，不是给
      //   任何调用方索取凭据标识的地方。
      boundCredentialActive =
        att !== null ? isCredentialActive(this.currentConfig(), att.deviceId, att.credentialId) : null;
    }
    if (socket !== null && !this.socketIsLive(socket)) {
      // 观测用的计数器：每一次"底层开着但应用层已死"的读取都记一笔。
      // 一个持续 > 0 的值就是"这台设备正在半开状态里"的直接证据。
      this.metrics.staleStatusReads += 1;
      await this.bestEffort("flush_metrics", () => this.flushMetrics());
    }

    const statusDeviceId = connectedDeviceId ?? this.lastConnectedDeviceId;
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
      // ★ 集中审计问题 1/2：`revoked` 不再是落盘的粘性布尔，而是**按当前配置
      //   推导**出来的 —— 给设备补一条新凭据（重新授权）之后这里会自动变回
      //   false，同时 `revocation` 里保留"曾经被撤销过"的审计痕迹。
      //   设备身份优先取当前连接；连接已被撤销关闭时退回最近一次建连的设备
      //   （否则一台刚被撤销的设备会显示 revoked: false，正好看反）。
      //   两者都不知道时退回审计记录 —— 它至少能说明"这里发生过撤销"。
      revoked: statusDeviceId !== null ? this.deviceRevokedNow(statusDeviceId) : this.revocation !== null,
      /** 最近一次撤销事件的审计记录（含范围与代次）；从未发生过则为 null。 */
      revocation: this.revocation,
      /** 当前这条连接绑定的凭据是否仍然有效；没有连接时为 null。 */
      bound_credential_active: boundCredentialActive,
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
    // 注意：**不清撤销记录**。撤销是安全状态，测试要解除它必须在配置里给这台
    // 设备补一条有效凭据（重新授权），而不是"顺手 reset 一下" —— 否则 reset
    // 就成了绕过撤销的后门。★ 集中审计问题 2：这里读的是新的
    // `STORAGE_KEY_REVOCATION` 审计记录，不再是那个粘性布尔。
    this.revocation = (await this.ctx.storage.get<RevocationRecord>(STORAGE_KEY_REVOCATION)) ?? this.revocation;
    await this.ctx.storage.put({
      [STORAGE_KEY_PENDING]: {},
      [STORAGE_KEY_METRICS]: this.metrics,
    });
    return json({
      ok: true,
      // 门禁永远按当前配置推导；没有已知设备时退回审计记录。
      revoked: this.lastConnectedDeviceId !== null ? this.deviceRevokedNow(this.lastConnectedDeviceId) : this.revocation !== null,
      revocation: this.revocation,
    });
  }

  // ---------------------------------------------------------------------------
  //  WebSocket 事件（休眠 API）
  // ---------------------------------------------------------------------------

  async webSocketMessage(ws: WebSocket, message: string | ArrayBuffer): Promise<void> {
    await this.ready;

    // ★ 集中审计问题 1/2 的最后一道闸：这条 socket 绑定的凭据还有效吗？
    //
    // 位置是刻意的，三件事都靠它成立：
    //   1. 在 `noteActivity()` **之前** —— 失效的连接不许给自己的存活计时器续命，
    //      否则撤销之后它还能让 `connected` 一直是真的；
    //   2. 在帧解析与结算**之前** —— 撤销之后手机发来的任何东西（包括它以为
    //      还在途的 pong / tool_result）都不能被当作有效结果结算，
    //      这正是"撤销之后仍收到一次成功"的根因；
    //   3. 用 `ws` 自己的 attachment 判，而不是 `currentSocket()` —— 迟到帧
    //      可能来自一条已被替换掉的旧 socket，拿当前 socket 的身份去判会放行它。
    const sender = ws.deserializeAttachment() as SocketAttachment | null;
    if (sender !== null) {
      const config = this.currentConfig();
      const scope: "device" | "credential" | null = isDeviceRevoked(config, sender.deviceId)
        ? "device"
        : !isCredentialActive(config, sender.deviceId, sender.credentialId)
          ? "credential"
          : null;
      if (scope !== null) {
        this.metrics.messagesRejected += 1;
        this.metrics.credentialRejections += 1;
        await this.bestEffort("flush_metrics", () => this.flushMetrics());
        logEvent("warn", "phone_session.message_from_revoked_connection", {
          deviceId: sender.deviceId,
          scope,
          generation: sender.generation,
        });
        this.safeSend(ws, { type: "error", code: "device_revoked", message: this.revokedMessage(scope) });
        try {
          ws.close(CLOSE_CODE_REVOKED, "device credential revoked");
        } catch {
          // 已经关了 —— 无所谓。
        }
        await this.terminateSockets({
          scope,
          credentialId: sender.credentialId,
          reason: scope === "credential" ? "message_from_revoked_credential" : "message_from_revoked_device",
        });
        return;
      }
    }

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
   *
   * ★ 集中审计问题 3 的两处：
   *   1. 调用点。建连（`handleConnect`）与每一次活动（`noteActivity`，覆盖所有
   *      合法上行帧含心跳）都必须重排一次；只在登记请求时才排，会让一台
   *      "连上但还没人问过它"的手机根本没有 alarm（`getAlarm()` 为 null），
   *      于是静默到死也没人发现。
   *   2. 比较方式。以前是 `existing > next`（"只让 alarm 更早、绝不更晚"），
   *      那会让心跳**永远推迟不了**死亡时刻：第一次排下 `t0 + liveness` 之后，
   *      后续心跳算出的 deadline 更晚，条件不成立，alarm 就停在 `t0 + liveness`
   *      上，一台心跳完全正常的手机也会在该时刻被判死。改成 `existing !== next`
   *      之后 alarm 会跟着真实 deadline 走 —— 安全性不受影响，因为 `next` 取的
   *      是**全部**在途超时与存活阈值里的最小值，绝不会晚于任何一件真事。
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
    if (existing === null || existing !== next) {
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

/**
 * 供 Worker 侧构造请求用：把 deviceId 放进内部标记头。
 *
 * ★ 集中审计问题 1：`/connect` 还要带**这条连接是用哪条凭据鉴权通过的**。
 * 两个内部头都只有 Worker 能设置（DO 的公网路由是独立命名空间，请求头
 * 由 Worker 自己加），所以 `credentialId` 在这里是可信输入；DO 侧对
 * `/connect` **强制**要求它，缺了就 500 —— 免得将来某条新调用路径漏传而
 * 静默退化成"没有凭据绑定"。
 */
export function internalHeaders(deviceId: string, credentialId?: string): Record<string, string> {
  const headers: Record<string, string> = { [INTERNAL_DEVICE_HEADER]: deviceId };
  if (credentialId !== undefined) headers[INTERNAL_CREDENTIAL_HEADER] = credentialId;
  return headers;
}
