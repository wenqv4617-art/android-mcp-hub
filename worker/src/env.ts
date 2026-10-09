// ============================================================================
//  绑定与环境变量类型
// ============================================================================
//
//  ⚠ 这个 interface 是**唯一**的环境契约。它刻意只有两个 Durable Object 绑定，
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

export interface Env {
  // ---- Durable Object 绑定（本阶段唯一的持久化）----------------------------
  /** 类名 PhoneSession（见 worker/src/phone/phone-session.ts）。每台手机一个对象。 */
  PHONE_SESSION: PhoneSessionNamespace;
  /** 类名 McpSession（见 worker/src/mcp/session-do.ts）。每个 MCP 会话一个对象。 */
  MCP_SESSION: McpSessionNamespace;

  // ---- 敏感值（只应来自 `wrangler secret put` / 本地 .dev.vars）-----------
  /** MCP 入口的访问令牌。与手机设备令牌完全分离。 */
  MCP_ACCESS_TOKEN?: string;
  /** 设备凭据表，JSON。见 config.ts 的 parseDeviceCredentials。 */
  DEVICE_TOKENS?: string;

  // ---- 非敏感运行参数（wrangler.jsonc 的 vars）----------------------------
  PHONE_PING_TIMEOUT_MS?: string;
  HEARTBEAT_INTERVAL_MS?: string;
  MAX_PENDING_REQUESTS?: string;
  MAX_PHONE_MESSAGE_BYTES?: string;
  SUPPORTED_MCP_PROTOCOL_VERSIONS?: string;
  DEFAULT_DEVICE_ID?: string;

  /** 仅用于日志标注，例如 "local" / "gate0-staging"。不参与任何鉴权判断。 */
  ENVIRONMENT?: string;
}
