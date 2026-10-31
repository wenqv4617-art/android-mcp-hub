// V1 gateway: optional /gateway management and ext_* relay share this Worker; Android paths are unchanged.
// ============================================================================
//  Android MCP Hub — Worker 入口
// ============================================================================
//
//  Gate 0 的全部对外面只有四条路径：
//
//    GET  /healthz    健康契约（给 CI / 部署后冒烟用）
//    GET  /           一句自我说明（给误入的人/工具看）
//    *    /mcp        MCP Streamable HTTP（POST / GET / DELETE）
//    GET  /phone/ws   手机的 WebSocket 升级入口（带设备鉴权）
//    GET  /phone/diagnostics  该设备的连接诊断（V1.6.3，同一套设备鉴权，只读）
//
//  Gate 1 增加了 OAuth 授权服务器面（见 `./oauth/endpoints` 的 `OAUTH_PATHS`）：
//
//    GET  /.well-known/oauth-protected-resource[/mcp]   RFC 9728 资源元数据
//    GET  /.well-known/oauth-authorization-server       RFC 8414 授权服务器元数据
//    GET  /.well-known/openid-configuration             OIDC 风格别名
//    *    /oauth/register                              RFC 7591 动态客户端注册
//    GET|POST /oauth/authorize                         授权端点（同意页）
//    POST /oauth/token                                 令牌端点（含刷新轮换）
//    POST /oauth/revoke                                RFC 7009 撤销
//
//  其余一律 404。
//
//  ── 隔离声明（对应任务书「二、最高优先级：生产环境隔离」）───────────────
//  这个模块**没有**任何 D1 / KV / R2 / Queue 的引用，也没有任何指向既有项目的
//  常量、URL、账户 ID 或密钥名。它只认三个绑定：PHONE_SESSION、MCP_SESSION、
//  OAUTH_STORE，以及从 env 读来的凭据。`env.ts` 里的类型定义是一道编译期断言：
//  如果有人往 wrangler.jsonc 里加了 D1 绑定，这里不会自动获得访问权。
// ============================================================================

import { loadConfig, activeCredentialsFor, allCredentialsFor, findCredential, isDeviceRevoked, type AppConfig } from "./config";
import type { PhoneSessionNamespace } from "./env";
import type { Env } from "./env";
import {
  PhoneSession,
  INTERNAL_CREDENTIAL_HEADER,
  INTERNAL_DEVICE_HEADER,
  ROUTE_CONNECT,
  ROUTE_DIAGNOSTICS,
} from "./phone/phone-session";
import { McpSession } from "./mcp/session-do";
import {
  handleMcpRequest,
  notifyRevocationInBackground,
  revokePhoneDeviceViaDurableObject as revokePhoneDevice,
  SERVER_NAME,
  SERVER_VERSION,
} from "./mcp/http";
import { OAuthStore } from "./oauth/store-do";
// Gate 2.5：健康检查与根路径的自述文档从工具注册表派生，避免与真实白名单漂移。
import { toolNames } from "./mcp/tools";
import { handleOAuthRequest, isOAuthPath } from "./oauth/endpoints";
import { jsonResponse, notFound, textResponse } from "./util/http";
import { logEvent } from "./util/log";
import { sha256Hex, timingSafeEqual } from "./util/crypto";

import { GatewayStore } from "./gateway/store-do";
import { gatewayUi } from "./gateway/ui";
import { gatewayEnabled, handleGatewayAdmin, handleGatewayAndroid, handleGatewayPair } from "./gateway/http";
export { PhoneSession, McpSession, OAuthStore, GatewayStore };

/** 健康契约里的 schema 版本。改动持久化结构时同步 +1。 */
const SCHEMA_VERSION = "gate0-do-v1";

export default {
  async fetch(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
    const url = new URL(request.url);
    const config = loadConfig(env);

    try {
      if (url.pathname === "/healthz") return handleHealthz(env, config);
      if (url.pathname === "/" ) return handleRoot();
      if (request.method === "GET" && gatewayEnabled(env)) { const page = gatewayUi(url.pathname); if (page) return page; }
      if (url.pathname === "/gateway/admin") return await handleGatewayAdmin(request, env);
      if (url.pathname === "/gateway/pair") return await handleGatewayPair(request, env);
      if (url.pathname === "/gateway/android") return await handleGatewayAndroid(request, env);
      if (url.pathname === "/mcp") return await handleMcpRequest(request, env);
      if (url.pathname === "/phone/ws") return await handlePhoneUpgrade(request, env, config, ctx);
      // V1.6.3：设备自己读连接诊断的 HTTPS 入口。**不叫 `/logs`、不是公开路径** ——
      // 与 /phone/ws 同一套设备鉴权（X-Device-Id + Bearer），只返回该设备的缓冲区。
      if (url.pathname === "/phone/diagnostics") return await handlePhoneDiagnostics(request, env, config, ctx);
      // OAuth 面。`isOAuthPath` 只认精确路径集合，不做前缀匹配 ——
      // 前缀匹配会让 `/oauth/authorize/../../mcp` 这种形状落到错误的分支上。
      if (isOAuthPath(url.pathname)) return await handleOAuthRequest(request, env, config);
      return notFound(url.pathname);
    } catch {
      // 审计 R2：**绝不**记录 `String(err)` 或 `err.stack`。
      // 异常 message 会把上游响应体、请求头、甚至凭据原样带进来
      // （"failed to fetch https://…/token?client_secret=…" 这种形状真实存在），
      // 堆栈更会把整个调用链的局部变量摊在日志里。
      // 这里只留一个固定类别；要追细节请用 trace，不要用日志。
      logEvent("error", "worker.unhandled_error", {
        path: url.pathname,
        method: request.method,
        errorCode: "unhandled_exception",
      });
      return jsonResponse(
        { error: "internal_error", message: "服务端内部错误，已记录日志" },
        { status: 500 },
      );
    }
  },
} satisfies ExportedHandler<Env>;

// ---------------------------------------------------------------------------
//  /healthz
// ---------------------------------------------------------------------------

function handleHealthz(env: Env, config: AppConfig): Response {
  const body = {
    status: config.problems.length === 0 ? "ok" : "degraded",
    service: SERVER_NAME,
    version: SERVER_VERSION,
    environment: env.ENVIRONMENT ?? "unspecified",

    // ⚠ 字面量，不是变量：本服务**没有**数据库。
    //    这两个字段存在的意义，就是让审计者的脚本能断言"没有 D1"。
    database: "not-used",
    d1_bindings: 0,
    schema_version: SCHEMA_VERSION,

    // 本阶段允许的持久化只有 Durable Object 自带存储。
    durable_object_bindings: ["PHONE_SESSION", "MCP_SESSION", "OAUTH_STORE", ...(env.GATEWAY_STORE ? ["GATEWAY_STORE"] : [])],

    config_problems: config.problems,
    capabilities: {
      mcp_endpoint: "/mcp",
      phone_endpoint: "/phone/ws",
      // Gate 2.5：从工具注册表派生，避免这份自述文档与真实白名单漂移。
      tools: toolNames(),
      server_initiated_notifications: false,
      oauth_authorization_server: config.oauth.enabled,
      oauth_metadata_endpoint: config.oauth.enabled ? "/.well-known/oauth-authorization-server" : null,
      // 只暴露"开没开"，不暴露任何口令/令牌值。
      gateway_enabled: gatewayEnabled(env),
      gateway_android_pairing_configured: gatewayEnabled(env) && !!env.GATEWAY_ANDROID_PAIRING_CODE,
    },
    auth: {
      mcp_bearer_configured: config.hasMcpAccessToken,
      device_credentials_configured: config.credentials.length,
      device_tokens_configured: config.hasDeviceTokens,
      // ⚠ 只说"配没配"，绝不说口令是什么、有多长、摘要是什么。
      oauth_owner_secret_configured: config.oauth.enabled,
    },
    runtime: config.runtime,
  };

  return jsonResponse(body, { status: config.problems.length === 0 ? 200 : 200 });
}

function handleRoot(): Response {
  return textResponse(
    [
      `${SERVER_NAME} ${SERVER_VERSION}`,
      "",
      "Gate 1：Cloudflare Worker + Durable Object + Android 手机长连接。",
      `内置只读工具（共 ${toolNames().length} 个）：${toolNames().join("、")}。`,
      "可选通用 MCP 中转由 GATEWAY_ENABLED 启用；不使用 D1。",
      "",
      "端点：",
      "  GET  /healthz    健康检查",
      "  POST /mcp        MCP Streamable HTTP（需要 OAuth 2.1 访问令牌）",
      "  GET  /phone/ws   手机 WebSocket（需要设备令牌）",
      "  GET  /.well-known/oauth-protected-resource   RFC 9728 资源元数据",
      "  GET  /.well-known/oauth-authorization-server RFC 8414 授权服务器元数据",
      "",
    ].join("\n"),
    { status: 200 },
  );
}

// ---------------------------------------------------------------------------
//  /phone/ws —— 手机 WebSocket 升级 + 设备鉴权
// ---------------------------------------------------------------------------

/**
 * `ctx` 一路传进来只为一件事：撤销通知必须挂到这次调用的生命周期上
 * （见 `notifyRevocationInBackground`）。类型允许 undefined —— Node 替身 harness
 * 与单测调用 `worker.fetch(request, env, {})` 时没有 `waitUntil`。
 */
async function handlePhoneUpgrade(
  request: Request,
  env: Env,
  config: AppConfig,
  ctx: ExecutionContext | undefined,
): Promise<Response> {
  if (request.headers.get("Upgrade")?.toLowerCase() !== "websocket") {
    return jsonResponse(
      { error: "upgrade_required", message: "这个端点只接受 WebSocket 升级请求" },
      { status: 426, headers: { Upgrade: "websocket" } },
    );
  }

  const deviceId = (request.headers.get("X-Device-Id") ?? "").trim();
  const authHeader = (request.headers.get("Authorization") ?? "").trim();
  const bearer = /^Bearer\s+(.+)$/i.exec(authHeader);

  if (deviceId === "" || bearer === null) {
    logEvent("warn", "phone.auth_failed", { reason: "missing_device_id_or_token" });
    return unauthorizedPhone();
  }

  const presented = bearer[1]!.trim();
  const candidates = activeCredentialsFor(config, deviceId);
  if (candidates.length === 0) {
    // 不区分"设备不存在"与"令牌错误"，避免被用来枚举设备 ID。
    logEvent("warn", "phone.auth_failed", { reason: "no_active_credential" });
    // V1.6.3：只有**确实存在过凭据**的设备才写诊断 —— 否则任意伪造的
    // deviceId 都能让 Worker 为它创建一个 DO 实例（一个不需要鉴权的放大器）。
    if (allCredentialsFor(config, deviceId).length > 0) {
      reportAuthFailureInBackground(ctx, env, deviceId, "no_active_credential");
    }

    // ★ Gate 1.5 / A1：如果这台设备**存在但凭据已被撤销**，那么"拒绝这次升级"
    //   只是把新连接挡在门外 —— 已经建立的那条连接还活着，它会继续接受
    //   phone_ping。撤销必须在观察到的时候就**推到 DO**，让 DO 换代次、
    //   结算在途请求并关掉旧 socket。
    //
    //   为什么在这里推：这是唯一一个"Worker 出于自身理由、必须处理某个
    //   deviceId 的撤销状态"的同步点，不需要新增任何公网撤销入口。
    //   推送是**尽力而为**的 —— 它失败不能让 401 变成 500（那会让手机侧
    //   看到"服务端坏了"，而实际上它只是没收到撤销通知）。真正的保证来自
    //   "每个工具调用都重新查授权"：撤销后的新请求一律 device_revoked，
    //   与这次推送成功与否无关。
    //
    //   ★ Gate 2：但"尽力而为"不等于"发了就不管"。这里原先写的是"调用一次
    //     跨 DO 的撤销、再 catch 掉失败"这种裸 fire-and-forget，而这个请求
    //     马上就要带着 401 返回 —— 响应一返回 isolate 就可能被回收，飞行中的
    //     通知会被掐掉。改成走 `notifyRevocationInBackground`（内部用
    //     `ctx.waitUntil` 把 promise 挂到这次调用的生命周期上）：语义不变
    //     （失败只记日志），但它真的有机会落地。
    if (isDeviceRevoked(config, deviceId)) {
      notifyRevocationInBackground(
        ctx,
        { deviceId, reason: "upgrade_after_revocation", credentialScoped: false },
        revokePhoneDevice(env, deviceId, "upgrade_after_revocation"),
      );
    }

    return unauthorizedPhone();
  }

  // 只比较摘要，明文令牌在鉴权函数返回后就不再被引用。
  //
  // ★ 集中审计问题 1/2：从 `some()` 改成 `find()` —— 不只回答"匹配上了没有"，
  //   还要知道**匹配的是哪一条凭据**，因为那个摘要就是这条连接的凭据身份，
  //   必须一路带到 DO 并钉在 socket 上（见 `SocketAttachment.credentialId`）。
  const digest = await sha256Hex(presented);
  const matchedCredential = candidates.find((c) => timingSafeEqual(digest, c.tokenSha256));
  if (matchedCredential === undefined) {
    logEvent("warn", "phone.auth_failed", { reason: "token_mismatch" });
    if (allCredentialsFor(config, deviceId).length > 0) {
      reportAuthFailureInBackground(ctx, env, deviceId, "token_mismatch");
    }
    // ★ 集中审计问题 2 的另一半：如果这个摘要属于**这台设备的一条已撤销凭据**
    //   （典型情形：令牌被轮换掉之后，手机还在用旧令牌重连），那这不是一次
    //   伪造尝试，而是一次"应该被终结的旧凭据"的自白。此时要把**凭据级**撤销
    //   通知推到 DO：只有那条旧凭据开出来的连接该死，用新凭据重连的连接必须
    //   活着 —— 否则一条迟到的重连请求就成了一条拒绝服务路径。
    //   ★ Gate 2：同上面的设备级分支，这条通知也必须挂到 `ctx.waitUntil` 上 ——
    //     这个请求同样马上返回 401，不登记生命周期就可能被掐掉。
    if (findCredential(config, deviceId, digest)?.revokedAt != null) {
      notifyRevocationInBackground(
        ctx,
        { deviceId, reason: "upgrade_with_rotated_credential", credentialScoped: true },
        revokePhoneDevice(env, deviceId, "upgrade_with_rotated_credential", digest),
      );
    }
    return unauthorizedPhone();
  }

  // 先记账再转发：这样"连上过但立刻掉了"也留有痕迹。
  const ns = env.PHONE_SESSION as unknown as PhoneSessionNamespace;
  const stub = ns.get(ns.idFromName(deviceId));

  const headers = new Headers(request.headers);
  headers.set(INTERNAL_DEVICE_HEADER, deviceId);
  // ★ 问题 1：把**这次鉴权实际命中的凭据摘要**放进内部头。DO 侧对 /connect
  //   强制要求它（缺失 500），并把它写进 socket attachment —— 之后每一个
  //   请求入口 / 上行帧都能回答"这条连接是用哪条凭据开的，它现在还有效吗"。
  headers.set(INTERNAL_CREDENTIAL_HEADER, matchedCredential.tokenSha256);
  // ⚠ 必须改写成 DO 的内部路由，不能把公网 URL 原样转发过去：
  //   DO 只认 /internal/phone/connect，拿 /phone/ws 去问它只会得到 404。
  //   内部路由名是 DO 的实现细节，公网路径空间由 Worker 自己拥有。
  const forwarded = new Request(`https://phone-session.internal${ROUTE_CONNECT}`, {
    method: "GET",
    headers,
    redirect: "manual",
  });

  logEvent("info", "phone.upgrade_forwarding", { deviceId, contract: candidates[0]?.label ?? null });
  return stub.fetch(forwarded);
}

// ---------------------------------------------------------------------------
//  /phone/diagnostics —— 设备读自己的连接诊断（V1.6.3）
// ---------------------------------------------------------------------------

/**
 * 与 `/phone/ws` **同一套**设备鉴权：`X-Device-Id` + `Authorization: Bearer`。
 *
 * 不是"第二套密钥"：用的就是设备令牌，按 `activeCredentialsFor` 过滤之后再
 * 做 `sha256 + timingSafeEqual` 比对，与升级入口逐字相同。因此：
 *   · 未认证 → 401，且**不会**碰 DO（不存在的设备连路径都不存在）；
 *   · 设备 A 的令牌打不到设备 B 的缓冲区（DO 实例由**已鉴权**的 deviceId 选）；
 *   · 查询走独立 HTTP 请求，不碰主 WebSocket —— 连不上 WS 时仍能读云端已有日志。
 *
 * 只支持 `GET`。没有 `/logs`、没有"调试后门"、也没有任何写入口
 * （认证失败上报在 DO 内部路由里，公网不可达）。
 */
async function handlePhoneDiagnostics(
  request: Request,
  env: Env,
  config: AppConfig,
  ctx: ExecutionContext | undefined,
): Promise<Response> {
  if (request.method !== "GET") {
    return jsonResponse({ error: "method_not_allowed" }, { status: 405, headers: { Allow: "GET" } });
  }

  const deviceId = (request.headers.get("X-Device-Id") ?? "").trim();
  const authHeader = (request.headers.get("Authorization") ?? "").trim();
  const bearer = /^Bearer\s+(.+)$/i.exec(authHeader);
  if (deviceId === "" || bearer === null) {
    logEvent("warn", "phone.diag_auth_failed", { reason: "missing_device_id_or_token" });
    return unauthorizedPhone();
  }

  const presented = bearer[1]!.trim();
  const candidates = activeCredentialsFor(config, deviceId);
  if (candidates.length === 0) {
    logEvent("warn", "phone.diag_auth_failed", { reason: "no_active_credential" });
    if (allCredentialsFor(config, deviceId).length > 0) {
      reportAuthFailureInBackground(ctx, env, deviceId, "no_active_credential");
    }
    return unauthorizedPhone();
  }

  const digest = await sha256Hex(presented);
  const matchedCredential = candidates.find((c) => timingSafeEqual(digest, c.tokenSha256));
  if (matchedCredential === undefined) {
    logEvent("warn", "phone.diag_auth_failed", { reason: "token_mismatch" });
    if (allCredentialsFor(config, deviceId).length > 0) {
      reportAuthFailureInBackground(ctx, env, deviceId, "token_mismatch");
    }
    return unauthorizedPhone();
  }

  // 只转发白名单查询参数：DO 不认识的东西不该出现在它的 URL 里。
  const url = new URL(request.url);
  const query = new URLSearchParams();
  for (const key of ["limit", "since_ms"]) {
    const value = url.searchParams.get(key);
    if (value !== null) query.set(key, value);
  }
  const suffix = query.toString() === "" ? "" : `?${query.toString()}`;

  const ns = env.PHONE_SESSION as unknown as PhoneSessionNamespace;
  const stub = ns.get(ns.idFromName(deviceId));
  const headers = new Headers();
  headers.set(INTERNAL_DEVICE_HEADER, deviceId);
  // 与 /connect 一样带上命中的凭据指纹：DO 可以据此复核这条查询确实来自
  // 一条当前有效的凭据（指纹不是令牌，泄漏也不能用来鉴权）。
  headers.set(INTERNAL_CREDENTIAL_HEADER, matchedCredential.tokenSha256);
  try {
    const response = await stub.fetch(
      new Request(`https://phone-session.internal${ROUTE_DIAGNOSTICS}${suffix}`, {
        method: "GET",
        headers,
        redirect: "manual",
      }),
    );
    // 强制 no-store：诊断内容随每次连接变化，任何中间缓存都可能让用户看到
    // 过期的"当前是否在线"，那比看不到更糟。
    const out = new Response(response.body, {
      status: response.status,
      headers: {
        "Content-Type": response.headers.get("Content-Type") ?? "application/json",
        "Cache-Control": "no-store",
      },
    });
    logEvent("info", "phone.diag_queried", { deviceId, status: response.status });
    return out;
  } catch {
    logEvent("error", "phone.diag_query_failed", {
      deviceId,
      errorCode: "storage_failure",
      reason: "diagnostics_fetch_failed",
    });
    return jsonResponse({ error: "diagnostics_unavailable", message: "诊断缓冲区暂时不可读" }, { status: 503 });
  }
}

/**
 * 把一次**已发生**的升级鉴权失败写进该设备的诊断流（尽力而为）。
 *
 * 调用方必须先确认"这台设备在配置里确实存在"（`allCredentialsFor` 非空）——
 * 否则任意伪造的 deviceId 都会让 Worker 为它创建一个 DO 实例，那是一条
 * 不需要鉴权的资源放大器。写入本身在 DO 里还有 10 秒去重 / 每小时 30 条上限。
 */
function reportAuthFailureInBackground(
  ctx: ExecutionContext | undefined,
  env: Env,
  deviceId: string,
  reason: "missing_device_id_or_token" | "no_active_credential" | "token_mismatch",
): void {
  const task = (async () => {
    try {
      const ns = env.PHONE_SESSION as unknown as PhoneSessionNamespace;
      const stub = ns.get(ns.idFromName(deviceId));
      await stub.fetch(
        new Request(`https://phone-session.internal${ROUTE_DIAGNOSTICS}`, {
          method: "POST",
          headers: {
            [INTERNAL_DEVICE_HEADER]: deviceId,
            "Content-Type": "application/json",
          },
          body: JSON.stringify({ auth_reason: reason, auth_scope: "device", auth_source: "worker" }),
        }),
      );
    } catch {
      logEvent("warn", "phone.diag_auth_report_failed", {
        deviceId,
        errorCode: "storage_failure",
        reason: "auth_failure_report_failed",
      });
    }
  })();
  if (ctx !== undefined && typeof ctx.waitUntil === "function") ctx.waitUntil(task);
  else void task;
}

function unauthorizedPhone(): Response {
  return jsonResponse(
    { error: "unauthorized", message: "device authentication failed" },
    { status: 401, headers: { "WWW-Authenticate": 'Bearer realm="android-mcp-hub-phone"' } },
  );
}
