// ============================================================================
//  测试环境装配
// ============================================================================
//
//  这里把**真实**的 PhoneSession / McpSession 类接到 shim 的命名空间上 ——
//  测试跑的是产品代码本身，不是另写一份简化实现。
//
//  ⚠ 所有凭据都是明文的测试用常量，只存在于测试进程内存里。
//     仓库里没有任何真实令牌（见 SECURITY.md 第 6 节）。
// ============================================================================

import { PhoneSession } from "../../src/phone/phone-session";
import { McpSession } from "../../src/mcp/session-do";
import { OAuthStore } from "../../src/oauth/store-do";
import type { Env } from "../../src/env";
import { sha256Hex } from "../../src/util/crypto";
import { resetConfigCache } from "../../src/config";
import {
  attachMockPhone,
  MockPhone,
  type PhoneScenario,
  type SocketLike,
  type ToolBehavior,
} from "../../../tools/mock-phone/client";
import {
  ShimDurableObjectNamespace,
  type FakeDurableObjectState,
  type FakeWebSocket,
} from "./runtime";

export const TEST_MCP_TOKEN = "gate0-local-test-mcp-token-not-a-secret";
export const TEST_DEVICE_ID = "test-phone-1";
export const TEST_DEVICE_TOKEN = "gate0-local-test-device-token-not-a-secret";
export const TEST_DEVICE_ID_2 = "test-phone-2";
export const TEST_DEVICE_TOKEN_2 = "gate0-local-test-device-token-2-not-a-secret";
/** OAuth 同意页的口令。同样是明显的假值。 */
export const TEST_OAUTH_OWNER_SECRET = "gate1-local-test-owner-secret-not-a-secret";

/** 与 worker/src/phone/phone-session.ts 里的内部路由保持一致。 */
export const PHONE_ROUTES = {
  connect: "/internal/phone/connect",
  ping: "/internal/phone/ping",
  /** Gate 2：通用工具通道（工作包 B/C）。 */
  tool: "/internal/phone/tool",
  /** Gate 1.5 工作包 A：受保护的撤销入口，**没有**任何公网路由映射到它。 */
  revoke: "/internal/phone/revoke",
  status: "/internal/phone/status",
  reset: "/internal/phone/testing/reset",
} as const;

/** 与 worker/src/mcp/session-do.ts 里的内部路由保持一致。 */
export const MCP_SESSION_ROUTES = {
  create: "/internal/mcp-session/create",
  validate: "/internal/mcp-session/validate",
  terminate: "/internal/mcp-session/terminate",
} as const;

export interface TestRig {
  env: Env;
  phone: ShimDurableObjectNamespace;
  mcp: ShimDurableObjectNamespace;
  oauth: ShimDurableObjectNamespace;
  /**
   * 与 `DEVICE_TOKENS` 同源的凭据表（含**已撤销**条目，带明文 token）。
   *
   * ★ 集中审计问题 1：DO 现在要求 `/connect` 带"这次鉴权命中的凭据摘要"，
   * 所以测试必须能算出那个摘要 —— 明文 token 只存在于测试里，这也正是
   * 测试能推导指纹的原因。
   */
  credentials: TestCredential[];
}

export interface TestCredential {
  deviceId: string;
  token: string;
  tokenSha256: string;
  revokedAt: string | null;
}

export interface MakeRigOptions {
  mcpAccessToken?: string | null;
  deviceTokens?: Array<{ deviceId: string; token: string; revokedAt?: string | null; label?: string }> | null;
  vars?: Partial<Record<string, string>>;
  /**
   * OAuth 同意页口令。
   *
   * ⚠ 默认是 `undefined` = **不启用 OAuth**。这是刻意的：Gate 0 / 0.3 的既有
   * 断言（例如 `mcp-http.test.ts:102`「未启用时不带 resource_metadata」）
   * 必须继续在默认配置下成立。要测 OAuth 的用例显式传
   * `oauthOwnerSecret: TEST_OAUTH_OWNER_SECRET`。
   */
  oauthOwnerSecret?: string | null;
  /** 重定向主机白名单。默认与 wrangler.jsonc 一致（含 localhost，方便本地回环测试）。 */
  oauthRedirectAllowlist?: string;
}

/** 组装一个"像生产 env"的对象，但三个 DO 绑定都指向 shim。 */
export async function makeRig(options: MakeRigOptions = {}): Promise<TestRig> {
  resetConfigCache();

  const credentials = await resolveCredentials(options);

  const phoneNsHolder: { ns: ShimDurableObjectNamespace | null } = { ns: null };
  const mcpNsHolder: { ns: ShimDurableObjectNamespace | null } = { ns: null };
  const oauthNsHolder: { ns: ShimDurableObjectNamespace | null } = { ns: null };

  const env = {
    get PHONE_SESSION(): ShimDurableObjectNamespace {
      if (phoneNsHolder.ns === null) throw new Error("PHONE_SESSION 用了但还没装配");
      return phoneNsHolder.ns;
    },
    get MCP_SESSION(): ShimDurableObjectNamespace {
      if (mcpNsHolder.ns === null) throw new Error("MCP_SESSION 用了但还没装配");
      return mcpNsHolder.ns;
    },
    get OAUTH_STORE(): ShimDurableObjectNamespace {
      if (oauthNsHolder.ns === null) throw new Error("OAUTH_STORE 用了但还没装配");
      return oauthNsHolder.ns;
    },
    ...(options.mcpAccessToken === null
      ? {}
      : { MCP_ACCESS_TOKEN: options.mcpAccessToken ?? TEST_MCP_TOKEN }),
    ...(await deviceTokensJson(options)),
    OAUTH_REDIRECT_ALLOWLIST: options.oauthRedirectAllowlist ?? "chatgpt.com,localhost",
    ...(options.oauthOwnerSecret === null || options.oauthOwnerSecret === undefined
      ? {}
      : { OAUTH_OWNER_SECRET: options.oauthOwnerSecret }),
    ...(options.vars ?? {}),
  } as unknown as Env;

  const phone = new ShimDurableObjectNamespace(
    (ctx: FakeDurableObjectState, e: unknown) => new PhoneSession(ctx as never, e as Env),
    env,
  );
  const mcp = new ShimDurableObjectNamespace(
    (ctx: FakeDurableObjectState, e: unknown) => new McpSession(ctx as never, e as Env),
    env,
  );
  const oauth = new ShimDurableObjectNamespace(
    (ctx: FakeDurableObjectState, e: unknown) => new OAuthStore(ctx as never, e as Env),
    env,
  );
  phoneNsHolder.ns = phone;
  mcpNsHolder.ns = mcp;
  oauthNsHolder.ns = oauth;

  // getter 每次都返回同一个实例，但 env 对象本身要保持稳定引用
  resetConfigCache();
  return { env, phone, mcp, oauth, credentials };
}

/** 把 `MakeRigOptions.deviceTokens` 解析成带摘要的凭据表（含已撤销条目）。 */
async function resolveCredentials(options: MakeRigOptions): Promise<TestCredential[]> {
  if (options.deviceTokens === null) return [];
  const list =
    options.deviceTokens ??
    [
      { deviceId: TEST_DEVICE_ID, token: TEST_DEVICE_TOKEN, label: "local mock phone" },
      { deviceId: TEST_DEVICE_ID_2, token: TEST_DEVICE_TOKEN_2, label: "local mock phone 2" },
    ];
  return Promise.all(
    list.map(async (d) => ({
      deviceId: d.deviceId,
      token: d.token,
      tokenSha256: await sha256Hex(d.token),
      revokedAt: d.revokedAt ?? null,
    })),
  );
}

/** 这台设备**当前有效**的第一条凭据指纹；没有有效凭据时返回 null。 */
export function activeFingerprint(rig: TestRig, deviceId: string): string | null {
  return rig.credentials.find((c) => c.deviceId === deviceId && c.revokedAt === null)?.tokenSha256 ?? null;
}

/** 指定明文令牌对应的指纹（无论它是否已被撤销）；不在表里则返回 null。 */
export function fingerprintOf(rig: TestRig, deviceId: string, token: string): string | null {
  return rig.credentials.find((c) => c.deviceId === deviceId && c.token === token)?.tokenSha256 ?? null;
}

/**
 * ★ 集中审计问题 1/2：在**同一个 rig 里**改写凭据表（并让配置缓存失效）。
 *
 * 为什么必须有它：这两条修复的本质都是"状态**转换**"，而静态布景只能测转换
 * 前后的某一边。有了它才能断言：
 *   · 一条连接建立之后凭据被撤销 → 同一个 DO 实例上的下一个请求立刻 403；
 *   · 撤销之后在配置里补上新凭据 → **重新授权**真的能恢复服务（粘性布尔做不到）；
 *   · 凭据轮换只终结旧凭据的连接，用新凭据重建的连接不受影响。
 *
 * 写入的是 `env.DEVICE_TOKENS`（与 `makeRig` 同一条路径）。`DEVICE_TOKENS` 在
 * `loadConfig` 的缓存键里，所以 `resetConfigCache()` 之后新的判定立刻生效。
 */
export async function setCredentials(
  rig: TestRig,
  list: Array<{ deviceId: string; token: string; label?: string; revokedAt?: string | null }>,
): Promise<void> {
  rig.credentials = await resolveCredentials({ deviceTokens: list });
  (rig.env as unknown as { DEVICE_TOKENS?: string }).DEVICE_TOKENS = JSON.stringify(
    rig.credentials.map((c, index) => ({
      deviceId: c.deviceId,
      tokenSha256: c.tokenSha256,
      label: list[index]?.label ?? "test",
      createdAt: "2026-01-01T00:00:00Z",
      revokedAt: c.revokedAt,
    })),
  );
  resetConfigCache();
}

/**
 * `/connect` 内部凭据头的取值。
 *
 * 三态语义（这是测试里表达"伪造/轮换/缺头"三种布景的唯一入口）：
 *   · `undefined`（默认）→ 带上该设备当前**有效**凭据的指纹；
 *   · 明确的字符串      → 带这一条（例如上一轮被轮换掉的旧指纹）；
 *   · `null`            → **故意不带**，用于断言 DO 的
 *     `500 missing_internal_credential_header`。
 */
function connectCredentialHeader(
  rig: TestRig,
  deviceId: string,
  opts: { credentialFingerprint?: string | null },
): string | null {
  if (opts.credentialFingerprint === null) return null;
  if (typeof opts.credentialFingerprint === "string") return opts.credentialFingerprint;
  return activeFingerprint(rig, deviceId);
}

async function deviceTokensJson(options: MakeRigOptions): Promise<Record<string, string>> {
  if (options.deviceTokens === null) return {};
  const list =
    options.deviceTokens ??
    [
      { deviceId: TEST_DEVICE_ID, token: TEST_DEVICE_TOKEN, label: "local mock phone" },
      { deviceId: TEST_DEVICE_ID_2, token: TEST_DEVICE_TOKEN_2, label: "local mock phone 2" },
    ];

  const entries = await Promise.all(
    list.map(async (d) => ({
      deviceId: d.deviceId,
      tokenSha256: await sha256Hex(d.token),
      label: d.label ?? "test",
      createdAt: "2026-01-01T00:00:00Z",
      revokedAt: d.revokedAt ?? null,
    })),
  );
  return { DEVICE_TOKENS: JSON.stringify(entries) };
}

// ---------------------------------------------------------------------------
//  手机侧连接辅助
// ---------------------------------------------------------------------------

/** 构造一次手机 WebSocket 升级请求（与 Worker 的 /phone/ws 期望完全一致）。 */
export function phoneUpgradeRequest(
  deviceId: string,
  token: string,
  opts: { path?: string; includeDeviceHeader?: boolean; includeAuth?: boolean; upgrade?: string } = {},
): Request {
  const headers = new Headers();
  headers.set("Upgrade", opts.upgrade ?? "websocket");
  headers.set("Connection", "Upgrade");
  if (opts.includeDeviceHeader !== false) headers.set("X-Device-Id", deviceId);
  if (opts.includeAuth !== false) headers.set("Authorization", `Bearer ${token}`);
  return new Request(`https://android-mcp-hub.test${opts.path ?? "/phone/ws"}`, {
    method: "GET",
    headers,
  });
}

/**
 * 直接把"已鉴权的升级请求"打进 PhoneSession DO，**不碰 webSocket**。
 *
 * 与 [connectPhoneDirect] 的差别：这条路在升级被拒（401/403/426）时也能用 ——
 * 那种响应里根本没有 socket，任何 `response.webSocket.accept()` 都会崩。
 * 需要断言"这台设备被拒绝了"时用这个。
 */
export async function upgradePhoneDirect(
  rig: TestRig,
  deviceId: string,
  opts: { credentialFingerprint?: string | null } = {},
): Promise<Response> {
  const headers = new Headers();
  headers.set("Upgrade", "websocket");
  headers.set("Connection", "Upgrade");
  headers.set("X-Phone-Session-Device", deviceId);
  const credential = connectCredentialHeader(rig, deviceId, opts);
  if (credential !== null) headers.set("X-Phone-Session-Credential", credential);
  return await rig.phone.fetchOn(
    deviceId,
    new Request(`https://phone-session.internal${PHONE_ROUTES.connect}`, { method: "GET", headers }),
  );
}

export interface PhoneConnection {
  /** 手机这一端的 socket。 */
  socket: FakeWebSocket;
  /** 升级响应（status 101）。 */
  response: Response;
  state: FakeDurableObjectState;
}

/**
 * 直接把"已鉴权的升级请求"打进 PhoneSession DO。
 *
 * 注意：这里跳过的是 Worker 的 `handlePhoneUpgrade`（那部分由 index.test.ts 单独覆盖），
 * 打到 DO 的请求仍然带着内部标记头 —— 与生产路径一致。
 */
export async function connectPhoneDirect(
  rig: TestRig,
  deviceId: string,
  opts: { credentialFingerprint?: string | null } = {},
): Promise<PhoneConnection> {
  const headers = new Headers();
  headers.set("Upgrade", "websocket");
  headers.set("Connection", "Upgrade");
  headers.set("X-Phone-Session-Device", deviceId);
  const credential = connectCredentialHeader(rig, deviceId, opts);
  if (credential !== null) headers.set("X-Phone-Session-Credential", credential);

  const forwarded = new Request(`https://phone-session.internal${PHONE_ROUTES.connect}`, {
    method: "GET",
    headers,
  });

  const response = await rig.phone.fetchOn(deviceId, forwarded);
  const socket = (response as unknown as { webSocket: FakeWebSocket }).webSocket;
  const state = rig.phone.ctxOf(deviceId);
  // 手机端也要 accept 才会开始收帧。
  socket.accept();
  await state.settle();
  return { socket, response, state };
}

/** 直接给 PhoneSession DO 发一次 phone_ping 内部请求。 */
export async function pingPhoneDirect(
  rig: TestRig,
  deviceId: string,
  body: { requestId?: string; timeoutMs?: number } = {},
): Promise<import("../../src/phone/phone-session").PingOutcome> {
  const request = new Request(`https://phone-session.internal${PHONE_ROUTES.ping}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      requestId: body.requestId ?? crypto.randomUUID(),
      deviceId,
      ...(body.timeoutMs !== undefined ? { timeoutMs: body.timeoutMs } : {}),
    }),
  });
  const response = await rig.phone.fetchOn(deviceId, request);
  return (await response.json()) as import("../../src/phone/phone-session").PingOutcome;
}

/** 读 PhoneSession 的 status（含 metrics）。 */

/** Gate 2：直接走内部工具通道发一次 tool_request。 */
export async function toolPhoneDirect(
  rig: TestRig,
  deviceId: string,
  body: { toolName: string; arguments?: Record<string, unknown>; requestId?: string; timeoutMs?: number } ,
): Promise<import("../../src/phone/phone-session").ToolOutcome> {
  const request = new Request(`https://phone-session.internal${PHONE_ROUTES.tool}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      requestId: body.requestId ?? crypto.randomUUID(),
      deviceId,
      toolName: body.toolName,
      arguments: body.arguments ?? {},
      ...(body.timeoutMs !== undefined ? { timeoutMs: body.timeoutMs } : {}),
    }),
  });
  const response = await rig.phone.fetchOn(deviceId, request);
  return (await response.json()) as import("../../src/phone/phone-session").ToolOutcome;
}

/** Gate 1.5 / A1：直接调受保护的撤销入口。 */
export async function revokePhoneDirect(
  rig: TestRig,
  deviceId: string,
  reason = "test_revoke",
  opts: { credentialId?: string | null } = {},
): Promise<{ status: number; body: Record<string, unknown> }> {
  const request = new Request(`https://phone-session.internal${PHONE_ROUTES.revoke}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      deviceId,
      reason,
      ...(opts.credentialId !== undefined ? { credentialId: opts.credentialId } : {}),
    }),
  });
  const response = await rig.phone.fetchOn(deviceId, request);
  return { status: response.status, body: (await response.json()) as Record<string, unknown> };
}

export async function phoneStatus(rig: TestRig, deviceId: string): Promise<Record<string, unknown>> {
  const response = await rig.phone.fetchOn(
    deviceId,
    new Request(`https://phone-session.internal${PHONE_ROUTES.status}`, { method: "GET" }),
  );
  return (await response.json()) as Record<string, unknown>;
}

// ---------------------------------------------------------------------------
//  "接上一台模拟手机"
// ---------------------------------------------------------------------------

export interface ConnectedPhone {
  phone: MockPhone;
  socket: FakeWebSocket;
  response: Response;
  state: FakeDurableObjectState;
}

/**
 * 建连 + 挂上模拟手机 + accept。
 *
 * 顺序很关键：**先挂监听、再 accept** —— 因为 welcome 帧是在升级响应的那一刻
 * 就已经发出的，晚挂监听会漏掉它。
 */
export async function connectMockPhone(
  rig: TestRig,
  deviceId: string,
  scenario: PhoneScenario = "normal",
  opts: {
    delayMs?: number;
    onLog?: (line: string) => void;
    appVersion?: string;
    /** 让这台手机对第 N 个（0 基）ping 故意保持沉默。 */
    skipPingIndexes?: number[];
    /** Gate 2：这台手机对 tool_request 的行为（默认 normal）。 */
    toolBehavior?: ToolBehavior;
    /** 见 [connectCredentialHeader]：默认带当前有效凭据指纹，`null` = 故意不带。 */
    credentialFingerprint?: string | null;
  } = {},
): Promise<ConnectedPhone> {
  const headers = new Headers();
  headers.set("Upgrade", "websocket");
  headers.set("Connection", "Upgrade");
  headers.set("X-Phone-Session-Device", deviceId);
  const credential = connectCredentialHeader(rig, deviceId, opts);
  if (credential !== null) headers.set("X-Phone-Session-Credential", credential);

  const response = await rig.phone.fetchOn(
    deviceId,
    new Request(`https://phone-session.internal${PHONE_ROUTES.connect}`, { method: "GET", headers }),
  );
  const socket = (response as unknown as { webSocket: FakeWebSocket }).webSocket;
  const state = rig.phone.ctxOf(deviceId);

  const phone = new MockPhone({
    scenario,
    deviceId,
    delayMs: opts.delayMs,
    appVersion: opts.appVersion,
    skipPingIndexes: opts.skipPingIndexes,
    toolBehavior: opts.toolBehavior,
    onLog: opts.onLog,
  });
  attachMockPhone(phone, socket as unknown as SocketLike);

  socket.accept();
  await settle();
  return { phone, socket, response, state };
}

/** 等所有在途的 microtask / 定时器回调跑完。 */
export async function settle(times = 8): Promise<void> {
  for (let i = 0; i < times; i += 1) await new Promise((r) => setTimeout(r, 0));
}

// ---------------------------------------------------------------------------
//  日志（见 harness/setup.ts 的捕获器）
// ---------------------------------------------------------------------------

interface LogBuffer {
  lines: string[];
  clear(): void;
}

function logBuffer(): LogBuffer {
  const buf = (globalThis as unknown as { __LOG_BUFFER?: LogBuffer }).__LOG_BUFFER;
  if (buf === undefined) throw new Error("日志捕获器没装上，检查 vitest setupFiles");
  return buf;
}

/** 目前为止捕获到的所有日志行。 */
export function capturedLogs(): string[] {
  return logBuffer().lines;
}

/** 清空日志缓冲区（每个用例开头调一次，好做"本用例没泄漏"的断言）。 */
export function clearCapturedLogs(): void {
  logBuffer().clear();
}

/** 解析一条服务端帧。 */
export function parseFrame(raw: string): Record<string, unknown> {
  return JSON.parse(raw) as Record<string, unknown>;
}

/** socket 上**收到**的、类型为 type 的所有帧。 */
export function framesOfType(socket: { receivedFrames: string[] }, type: string): Array<Record<string, unknown>> {
  return socket.receivedFrames.map(parseFrame).filter((f) => f["type"] === type);
}
