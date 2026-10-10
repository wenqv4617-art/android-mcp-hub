// ============================================================================
//  绑定与环境变量类型
// ============================================================================
//
//  ⚠ 这个 interface 是**唯一**的环境契约。它刻意只有三个 Durable Object 绑定、
//     没有任何 D1Database / KVNamespace / R2Bucket / Queue 字段 ——
//     如果哪天有人在 wrangler.jsonc 里加了 D1，编译期在这里就会对不上，
//     这是一道刻意留下的"编译期断言"。
//
//  敏感字段全部 optional：没配就是 undefined，配置层负责把它变成一条 problem，
//  而不是让 Worker 带着空令牌继续跑。
// ============================================================================

// 说明：`DurableObjectNamespace<T>` 的泛型参数要求 T 带 RPC brand
// （`Rpc.__DURABLE_OBJECT_BRAND`）。本项目**不使用 DO RPC**，只走 `.get().fetch()`，
// 所以这里刻意不写类型参数 —— 写上一个不带 brand 的类名只会换来一行编译错误，
// 而那个 brand 对本项目没有任何运行时含义。
export type PhoneSessionNamespace = DurableObjectNamespace;
export type McpSessionNamespace = DurableObjectNamespace;
export type OAuthStoreNamespace = DurableObjectNamespace;

export interface Env {
  // ---- Durable Object 绑定（本阶段唯一的持久化）----------------------------
  /** 类名 PhoneSession（见 worker/src/phone/phone-session.ts）。每台手机一个对象。 */
  PHONE_SESSION: PhoneSessionNamespace;
  /** 类名 McpSession（见 worker/src/mcp/session-do.ts）。每个 MCP 会话一个对象。 */
  MCP_SESSION: McpSessionNamespace;
  /**
   * 类名 OAuthStore（见 worker/src/oauth/store-do.ts）。
   *
   * Gate 1 新增。**刻意是单个实例**（`idFromName("oauth-global")`）——
   * "授权码只能用一次"这个判定需要原子的读—判—写，而单个 DO 的单线程串行
   * 执行正好提供了这个语义；拆成多个实例反而会把这个保证打破。
   */
  OAUTH_STORE: OAuthStoreNamespace;

  // ---- 敏感值（只应来自 `wrangler secret put` / 本地 .dev.vars）-----------
  /** MCP 入口的访问令牌。与手机设备令牌完全分离。 */
  MCP_ACCESS_TOKEN?: string;
  /** 设备凭据表，JSON。见 config.ts 的 parseDeviceCredentials。 */
  DEVICE_TOKENS?: string;
  /**
   * OAuth 同意页要求输入的服务拥有者口令。
   *
   * 为什么需要它：ChatGPT 是**公开客户端**（没有 client_secret），任何能访问
   * 我们授权端点的人都可以发起一个授权请求。如果同意页只按一下"批准"就发放
   * 令牌，这个 OAuth 就退化成了"无认证开放代理"。这个口令把"发起授权请求"和
   * "真的批准"分开：只有知道口令的人才能把授权码交出去。
   *
   * **未设置时整个 OAuth 面回 404**（而不是回 5xx，也不是回一个能用的空实现）。
   */
  OAUTH_OWNER_SECRET?: string;

  // ---- 非敏感运行参数（wrangler.jsonc 的 vars）----------------------------
  PHONE_PING_TIMEOUT_MS?: string;
  HEARTBEAT_INTERVAL_MS?: string;
  MAX_PENDING_REQUESTS?: string;
  MAX_PHONE_MESSAGE_BYTES?: string;
  SUPPORTED_MCP_PROTOCOL_VERSIONS?: string;
  DEFAULT_DEVICE_ID?: string;

  /** OAuth 访问令牌的 scope。默认 `mcp:tools`。 */
  OAUTH_SCOPE?: string;
  /** 访问令牌有效期（毫秒）。默认 1 小时。 */
  OAUTH_ACCESS_TOKEN_TTL_MS?: string;
  /** 刷新令牌有效期（毫秒）。默认 30 天。 */
  OAUTH_REFRESH_TOKEN_TTL_MS?: string;
  /** 授权码有效期（毫秒）。默认 5 分钟（RFC 6749 建议不超过 10 分钟）。 */
  OAUTH_CODE_TTL_MS?: string;
  /** 同意请求（也就是同意页本身）的有效期（毫秒）。默认 10 分钟。 */
  OAUTH_CONSENT_TTL_MS?: string;
  /**
   * 允许的重定向主机白名单，逗号分隔。默认 `chatgpt.com`。
   *
   * 这是 redirect_uri 的**第二道**闸：第一道是"必须与客户端注册值逐字节相等"，
   * 第二道是"主机必须在白名单里"。它挡的是"攻击者先注册一个指向自己站点的
   * redirect_uri，再想办法骗运维在同意页上点批准"这条路径。
   *
   * 只有白名单里显式写了 `localhost` 时才放行 http 的本机回环地址。
   */
  OAUTH_REDIRECT_ALLOWLIST?: string;

  /** 仅用于日志标注，例如 "local" / "gate0-staging"。不参与任何鉴权判断。 */
  ENVIRONMENT?: string;
}
