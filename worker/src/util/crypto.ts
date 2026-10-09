// ============================================================================
//  密码学小工具
// ============================================================================
//
//  只有两件事：算 SHA-256、做定时安全比较。
//
//  为什么令牌比对不能写 `a === b`：
//    JS 的字符串相等在第一个不同字符处短路返回，比较耗时与"前多少个字符猜对了"
//    相关。对手可以用几万次请求把 64 个十六进制字符一位一位试出来。
//    我们比的是**双方令牌的 SHA-256**（定长 64 个十六进制字符），
//    比较过程恒为固定次数，因此不泄漏前缀信息。
// ============================================================================

/** 十六进制小写 SHA-256。 */
export async function sha256Hex(input: string): Promise<string> {
  const data = new TextEncoder().encode(input);
  const digest = await crypto.subtle.digest("SHA-256", data);
  return toHex(new Uint8Array(digest));
}

export function toHex(bytes: Uint8Array): string {
  let out = "";
  for (const b of bytes) out += b.toString(16).padStart(2, "0");
  return out;
}

/**
 * 定长字符串的定时安全比较。
 *
 * 长度不同时**也**会跑完整轮循环（对公共长度逐字节异或，再叠加长度差），
 * 不使用提前 return —— 提前 return 本身就是一次长度信息泄漏。
 */
export function timingSafeEqual(a: string, b: string): boolean {
  // 预先把长度差并进 diff（校时器对定长摘要无意义，但保持通用）。
  let diff = a.length ^ b.length;
  const len = Math.max(a.length, b.length);
  for (let i = 0; i < len; i++) {
    // 越界位置取 0，保证循环次数只与 len 有关。
    const ca = i < a.length ? a.charCodeAt(i) : 0;
    const cb = i < b.length ? b.charCodeAt(i) : 0;
    diff |= ca ^ cb;
  }
  return diff === 0;
}
