// ============================================================================
//  McpSession — 每个 MCP 会话一个 Durable Object
// ============================================================================
//
//  Streamable HTTP 要求服务端能在 initialize 时下发 `Mcp-Session-Id`，
//  并在后续请求里校验它。会话状态必须**跨 isolate 一致**：同一个 session id
//  的下一个请求可能落在另一个 isolate 上，用内存 Map 会随机 404。
//  所以会话落进 Durable Object —— 一个会话一个对象，天然按 key 分片。
//
//  ⚠ 这里**不是**数据库：
//    · 不是 D1，没有 SQL、没有独立实例、没有外部连接串；
//    · 数据只活在这个对象自己的存储里，随会话过期而删除；
//    · 存的内容只有协议版本 + 客户端信息，没有任何用户数据。
// ============================================================================

import { logEvent } from "../util/log";

export interface McpSessionRecord {
  sessionId: string;
  protocolVersion: string;
  createdAt: number;
  lastSeenAt: number;
  clientInfo: { name?: string; version?: string } | null;
  initializeCount: number;
}

/** 会话空闲多久后回收。24 小时。 */
export const MCP_SESSION_TTL_MS = 24 * 60 * 60 * 1000;

const KEY_SESSION = "session";

const ROUTE_CREATE = "/internal/mcp-session/create";
const ROUTE_VALIDATE = "/internal/mcp-session/validate";
const ROUTE_TERMINATE = "/internal/mcp-session/terminate";

export class McpSession implements DurableObject {
  private readonly ctx: DurableObjectState;

  constructor(ctx: DurableObjectState, _env: unknown) {
    this.ctx = ctx;
  }

  async fetch(request: Request): Promise<Response> {
    const url = new URL(request.url);
    switch (url.pathname) {
      case ROUTE_CREATE:
        return this.handleCreate(request);
      case ROUTE_VALIDATE:
        return this.handleValidate(request);
      case ROUTE_TERMINATE:
        return this.handleTerminate();
      default:
        return json({ error: "not_found" }, 404);
    }
  }

  private async handleCreate(request: Request): Promise<Response> {
    let body: { sessionId?: unknown; protocolVersion?: unknown; clientInfo?: unknown };
    try {
      body = (await request.json()) as typeof body;
    } catch {
      return json({ error: "invalid_body" }, 400);
    }
    const sessionId = typeof body.sessionId === "string" ? body.sessionId : "";
    const protocolVersion = typeof body.protocolVersion === "string" ? body.protocolVersion : "";
    if (sessionId === "" || protocolVersion === "") {
      return json({ error: "missing_session_id_or_protocol_version" }, 400);
    }

    const clientInfo =
      typeof body.clientInfo === "object" && body.clientInfo !== null
        ? (body.clientInfo as { name?: string; version?: string })
        : null;

    const now = Date.now();
    const existing = await this.ctx.storage.get<McpSessionRecord>(KEY_SESSION);
    // ★ 协议版本"先到先得"：会话一旦建立，protocolVersion 就固定下来。
    //   为什么不让后来的 initialize 覆盖：validate() 是拿请求头跟 record.protocolVersion
    //   比对的，如果这里能被改写，那么"会话建立时协商的版本"就不再是一份可信的约定，
    //   校验也就跟着失去意义。第二条 initialize 只记账、不改版本。
    //   （当前 Worker 每次 initialize 都会生成全新的 session id，所以正常情况下
    //    根本走不到这条分支；这里写死是为了让这条不变量由**拥有状态的这一层**保证，
    //    而不是依赖调用方永远记得换 id。）
    if (existing !== undefined && existing.protocolVersion !== protocolVersion) {
      logEvent("warn", "mcp_session.protocol_version_ignored", {
        sessionId,
        established: existing.protocolVersion,
        offered: protocolVersion,
      });
    }
    const record: McpSessionRecord = {
      sessionId,
      protocolVersion: existing?.protocolVersion ?? protocolVersion,
      createdAt: existing?.createdAt ?? now,
      lastSeenAt: now,
      clientInfo: clientInfo ?? existing?.clientInfo ?? null,
      // 第二次 initialize 不重建会话，只记账（规范允许客户端重复 initialize）。
      initializeCount: (existing?.initializeCount ?? 0) + 1,
    };
    await this.ctx.storage.put(KEY_SESSION, record);
    await this.ctx.storage.setAlarm(now + MCP_SESSION_TTL_MS);

    return json({ ok: true, session: record });
  }

  private async handleValidate(request: Request): Promise<Response> {
    let body: { protocolVersion?: unknown } = {};
    try {
      body = (await request.json()) as typeof body;
    } catch {
      // 空 body 也允许：只做存在性校验。
    }

    const record = await this.ctx.storage.get<McpSessionRecord>(KEY_SESSION);
    if (record === undefined) {
      return json({ valid: false, reason: "unknown_session" }, 200);
    }

    const now = Date.now();
    if (now - record.lastSeenAt > MCP_SESSION_TTL_MS) {
      await this.ctx.storage.deleteAll();
      return json({ valid: false, reason: "expired" }, 200);
    }

    // 客户端在 initialize 之后带上 MCP-Protocol-Version 头时，必须与建立会话时一致。
    if (typeof body.protocolVersion === "string" && body.protocolVersion !== record.protocolVersion) {
      return json(
        { valid: false, reason: "protocol_version_mismatch", expected: record.protocolVersion },
        200,
      );
    }

    record.lastSeenAt = now;
    await this.ctx.storage.put(KEY_SESSION, record);
    return json({ valid: true, session: record });
  }

  private async handleTerminate(): Promise<Response> {
    const record = await this.ctx.storage.get<McpSessionRecord>(KEY_SESSION);
    await this.ctx.storage.deleteAll();
    logEvent("info", "mcp_session.terminated", { sessionId: record?.sessionId ?? null });
    return json({ ok: true, deleted: record !== undefined });
  }

  /** 空闲过期。 */
  async alarm(): Promise<void> {
    const record = await this.ctx.storage.get<McpSessionRecord>(KEY_SESSION);
    if (record === undefined) return;
    const now = Date.now();
    const idleMs = now - record.lastSeenAt;
    if (idleMs >= MCP_SESSION_TTL_MS) {
      await this.ctx.storage.deleteAll();
      logEvent("info", "mcp_session.expired", { sessionId: record.sessionId, idleMs });
      return;
    }
    await this.ctx.storage.setAlarm(record.lastSeenAt + MCP_SESSION_TTL_MS);
  }
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json; charset=utf-8" },
  });
}

export const MCP_SESSION_ROUTES = {
  create: ROUTE_CREATE,
  validate: ROUTE_VALIDATE,
  terminate: ROUTE_TERMINATE,
} as const;
