// ============================================================================
//  McpSession Durable Object —— ChatGPT 那条 MCP 会话
// ============================================================================
//
//  为什么会话状态要放进 DO 而不是"Worker 内存里一个 Map"：
//  Worker 的 isolate 随时会被回收、也随时会跑在别的机器上，内存 Map 会让
//  同一个 Mcp-Session-Id 时有时无。放进 DO 之后，会话归属到唯一一个实例，
//  协议要求的"会话必须存在且一致"才是真的成立的。
//
//  DO 的响应形状（来自 worker/src/mcp/session-do.ts）：
//    create    → { ok: true, session: McpSessionRecord }       需要 body.sessionId + body.protocolVersion
//    validate  → { valid: true, session } | { valid: false, reason, expected? }
//    terminate → { ok: true, deleted: boolean }
//  注意 validate 用 `valid` 而不是 `ok` —— 它不是"操作成功没"，而是"这个会话还有效吗"。
// ============================================================================

import { describe, expect, it } from "vitest";
import { MCP_SESSION_ROUTES, MCP_SESSION_TTL_MS } from "../src/mcp/session-do";
import { makeRig, type TestRig } from "./harness/env";

/** 直接朝 McpSession 实例发内部请求。 */
async function sessionCall(
  rig: TestRig,
  route: string,
  payload: Record<string, unknown>,
  sessionId = "session-under-test",
): Promise<{ status: number; body: Record<string, unknown> }> {
  const response = await rig.mcp.fetchOn(
    sessionId,
    new Request(`https://mcp-session.internal${route}`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload),
    }),
  );
  const text = await response.text();
  let body: Record<string, unknown> = {};
  try {
    body = text === "" ? {} : (JSON.parse(text) as Record<string, unknown>);
  } catch {
    body = { raw: text };
  }
  return { status: response.status, body };
}

describe("McpSession / 创建与校验", () => {
  it("create 记住协议版本与客户端信息", async () => {
    const rig = await makeRig();
    const created = await sessionCall(rig, MCP_SESSION_ROUTES.create, {
      sessionId: "session-under-test",
      protocolVersion: "2025-06-18",
      clientInfo: { name: "gate0-test-client", version: "1.0.0" },
    });
    expect(created.status).toBe(200);
    expect(created.body["ok"]).toBe(true);
    const session = created.body["session"] as Record<string, unknown>;
    expect(session["sessionId"]).toBe("session-under-test");
    expect(session["protocolVersion"]).toBe("2025-06-18");
    expect(session["initializeCount"]).toBe(1);

    const validated = await sessionCall(rig, MCP_SESSION_ROUTES.validate, {});
    expect(validated.body["valid"]).toBe(true);
    const seen = validated.body["session"] as Record<string, unknown>;
    expect(seen["protocolVersion"]).toBe("2025-06-18");
  });

  it("缺 sessionId 或 protocolVersion → 400，不建会话", async () => {
    const rig = await makeRig();
    const noVersion = await sessionCall(rig, MCP_SESSION_ROUTES.create, { sessionId: "x" });
    expect(noVersion.status).toBe(400);
    expect(noVersion.body["error"]).toBe("missing_session_id_or_protocol_version");

    const noId = await sessionCall(rig, MCP_SESSION_ROUTES.create, { protocolVersion: "2025-06-18" });
    expect(noId.status).toBe(400);
  });

  it("校验一个从没建过的会话 → valid:false / unknown_session（200，不是 500）", async () => {
    const rig = await makeRig();
    const validated = await sessionCall(rig, MCP_SESSION_ROUTES.validate, {}, "never-created");
    expect(validated.status).toBe(200);
    expect(validated.body["valid"]).toBe(false);
    expect(validated.body["reason"]).toBe("unknown_session");
  });

  it("协议版本与会话建立时不一致 → 拒绝并告知期望值", async () => {
    const rig = await makeRig();
    await sessionCall(rig, MCP_SESSION_ROUTES.create, {
      sessionId: "session-under-test",
      protocolVersion: "2025-06-18",
    });
    const validated = await sessionCall(rig, MCP_SESSION_ROUTES.validate, { protocolVersion: "2024-11-05" });
    expect(validated.body["valid"]).toBe(false);
    expect(validated.body["reason"]).toBe("protocol_version_mismatch");
    expect(validated.body["expected"]).toBe("2025-06-18");
  });

  it("不带协议版本头时也能校验（只做存在性检查）", async () => {
    const rig = await makeRig();
    await sessionCall(rig, MCP_SESSION_ROUTES.create, {
      sessionId: "session-under-test",
      protocolVersion: "2025-06-18",
    });
    const validated = await sessionCall(rig, MCP_SESSION_ROUTES.validate, {});
    expect(validated.body["valid"]).toBe(true);
  });

  it("★ 重复 initialize 不会悄悄改掉会话的协议版本", async () => {
    const rig = await makeRig();
    await sessionCall(rig, MCP_SESSION_ROUTES.create, {
      sessionId: "session-under-test",
      protocolVersion: "2025-06-18",
    });
    await sessionCall(rig, MCP_SESSION_ROUTES.create, {
      sessionId: "session-under-test",
      protocolVersion: "2024-11-05",
    });
    const validated = await sessionCall(rig, MCP_SESSION_ROUTES.validate, { protocolVersion: "2025-06-18" });
    expect(validated.body["valid"]).toBe(true);
    const session = validated.body["session"] as Record<string, unknown>;
    expect(session["protocolVersion"]).toBe("2025-06-18");
    // createdAt 保持首次建立的时间，initializeCount 递增 —— 说明是"续用"而不是"重建"。
    expect(session["initializeCount"]).toBe(2);
  });
});

describe("McpSession / 生命周期", () => {
  it("terminate 之后会话就不认了", async () => {
    const rig = await makeRig();
    await sessionCall(rig, MCP_SESSION_ROUTES.create, {
      sessionId: "session-under-test",
      protocolVersion: "2025-06-18",
    });
    const terminated = await sessionCall(rig, MCP_SESSION_ROUTES.terminate, {});
    expect(terminated.body["ok"]).toBe(true);
    expect(terminated.body["deleted"]).toBe(true);

    const validated = await sessionCall(rig, MCP_SESSION_ROUTES.validate, {});
    expect(validated.body["valid"]).toBe(false);
    expect(validated.body["reason"]).toBe("unknown_session");
  });

  it("terminate 一个不存在的会话，如实返回 deleted=false（不假装成功）", async () => {
    const rig = await makeRig();
    const terminated = await sessionCall(rig, MCP_SESSION_ROUTES.terminate, {}, "never-created");
    expect(terminated.body["ok"]).toBe(true);
    expect(terminated.body["deleted"]).toBe(false);
  });

  it("不同 sessionId 落在不同的 DO 实例上，互不可见", async () => {
    const rig = await makeRig();
    await sessionCall(
      rig,
      MCP_SESSION_ROUTES.create,
      { sessionId: "session-a", protocolVersion: "2025-06-18" },
      "session-a",
    );
    const other = await sessionCall(rig, MCP_SESSION_ROUTES.validate, {}, "session-b");
    expect(other.body["valid"]).toBe(false);
    // 两个 sessionId 就是两个 DO 实例 —— 这正是"会话天然按 key 分片"的证据。
    expect(rig.mcp.liveCount()).toBeGreaterThanOrEqual(2);
  });

  it("未知内部路由 → 404（DO 只认自己那几条路由，没有后门）", async () => {
    const rig = await makeRig();
    const response = await rig.mcp.fetchOn(
      "session-x",
      new Request("https://mcp-session.internal/internal/mcp-session/backdoor", { method: "POST" }),
    );
    expect(response.status).toBe(404);
  });

  it("TTL 是正数且不超过一天（会话不能永久驻留）", () => {
    expect(MCP_SESSION_TTL_MS).toBeGreaterThan(0);
    expect(MCP_SESSION_TTL_MS).toBeLessThanOrEqual(24 * 60 * 60 * 1000);
  });
});
