// ============================================================================
//  ID 生成
// ============================================================================
//
//  request_id 是整套内部协议的**唯一配对键**（见 docs/PROTOCOL.md R1/R2）。
//  它必须是 UUID v4：
//    · 122 bit 随机，实务上不可能碰撞；
//    · 手机端能一眼看出"这是个不透明 ID"，不会被误当成序号去自增；
//    · 日志里出现时不会泄漏任何业务含义。
//
//  刻意**不**用自增序号：多台手机、多次重连、DO 重建之后，序号一定会错乱，
//  而错乱的序号会让"旧连接的响应完成新连接的请求"变成可能。
// ============================================================================

/** UUID v4（小写、带连字符）。用平台自带的 crypto.randomUUID。 */
export function newRequestId(): string {
  return crypto.randomUUID();
}

/** MCP 会话 ID。与 request_id 同源，但语义独立（面向 MCP 客户端）。 */
export function newSessionId(): string {
  return crypto.randomUUID();
}

/** 短随机串，用于日志关联（不是安全边界）。 */
export function shortId(): string {
  return crypto.randomUUID().slice(0, 8);
}
