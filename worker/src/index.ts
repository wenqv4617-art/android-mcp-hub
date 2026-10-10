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

import { loadConfig, activeCredentialsFor, type AppConfig } from "./config";
import type { PhoneSessionNamespace } from "./env";
import type { Env } from "./env";
import { PhoneSession, INTERNAL_DEVICE_HEADER, ROUTE_CONNECT } from "./phone/phone-session";
import { McpSession } from "./mcp/session-do";
import { handleMcpRequest, SERVER_NAME, SERVER_VERSION } from "./mcp/http";
import { OAuthStore } from "./oauth/store-do";
import { handleOAuthRequest, isOAuthPath } from "./oauth/endpoints";
import { jsonResponse, notFound, textResponse } from "./util/http";
import { logEvent } from "./util/log";
import { sha256Hex, timingSafeEqual } from "./util/crypto";

export { PhoneSession, McpSession, OAuthStore };

/** 健康契约里的 schema 版本。改动持久化结构时同步 +1。 */
const SCHEMA_VERSION = "gate0-do-v1";

export default {
  async fetch(request: Request, env: Env, _ctx: ExecutionContext): Promise<Response> {
    const url = new URL(request.url);
    const config = loadConfig(env);

    try {
      if (url.pathname === "/healthz") return handleHealthz(env, config);
      if (url.pathname === "/" ) return handleRoot();
      if (url.pathname === "/mcp") return await handleMcpRequest(request, env);
      if (url.pathname === "/phone/ws") return await handlePhoneUpgrade(request, env, config);
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
    durable_object_bindings: ["PHONE_SESSION", "MCP_SESSION", "OAUTH_STORE"],

    config_problems: config.problems,
    capabilities: {
      mcp_endpoint: "/mcp",
      phone_endpoint: "/phone/ws",
      tools: ["phone_ping"],
      server_initiated_notifications: false,
      oauth_authorization_server: config.oauth.enabled,
      oauth_metadata_endpoint: config.oauth.enabled ? "/.well-known/oauth-authorization-server" : null,
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
      "Gate 0 骨架：Cloudflare Worker + Durable Object + Android 手机长连接。",
      "本阶段不接入任何本地 MCP 应用，也不使用 D1。",
      "",
      "端点：",
      "  GET  /healthz    健康检查",
      "  POST /mcp        MCP Streamable HTTP（需要 Authorization: Bearer）",
      "  GET  /phone/ws   手机 WebSocket（需要设备令牌）",
      "",
    ].join("\n"),
    { status: 200 },
  );
}

// ---------------------------------------------------------------------------
//  /phone/ws —— 手机 WebSocket 升级 + 设备鉴权
// ---------------------------------------------------------------------------

async function handlePhoneUpgrade(request: Request, env: Env, config: AppConfig): Promise<Response> {
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
    return unauthorizedPhone();
  }

  // 只比较摘要，明文令牌在鉴权函数返回后就不再被引用。
  const digest = await sha256Hex(presented);
  const matched = candidates.some((c) => timingSafeEqual(digest, c.tokenSha256));
  if (!matched) {
    logEvent("warn", "phone.auth_failed", { reason: "token_mismatch" });
    return unauthorizedPhone();
  }

  // 先记账再转发：这样"连上过但立刻掉了"也留有痕迹。
  const ns = env.PHONE_SESSION as unknown as PhoneSessionNamespace;
  const stub = ns.get(ns.idFromName(deviceId));

  const headers = new Headers(request.headers);
  headers.set(INTERNAL_DEVICE_HEADER, deviceId);
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

function unauthorizedPhone(): Response {
  return jsonResponse(
    { error: "unauthorized", message: "device authentication failed" },
    { status: 401, headers: { "WWW-Authenticate": 'Bearer realm="android-mcp-hub-phone"' } },
  );
}
