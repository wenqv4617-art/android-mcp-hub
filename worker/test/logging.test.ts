// ============================================================================
//  日志安全（Gate 0.1 审计 R2）
// ============================================================================
//
//  审计给了一张清单，要求逐条覆盖"令牌藏在哪里"。这些用例的共同断言只有一句：
//
//      **捕获到的日志文本里，一个字都不许出现那个令牌。**
//
//  刻意不写"某字段等于 [redacted]"这种中间态断言 —— 中间态是对的、输出还是
//  可能漏，那才是真实的失败模式。这里断言的是最终的 console 输出。
//
//  另外还有一条**静态**用例：worker/src 下除 log.ts 外不许出现 console.*。
//  没有它的话，任何人都能绕开 log.ts 的四道闸门直接打印，前面所有用例就都成了
//  对"我们记得用 logEvent"这个习惯的信任，而不是对机制的验证。
// ============================================================================

import { beforeEach, describe, expect, it } from "vitest";
import { readFileSync, readdirSync, statSync } from "node:fs";
import { join, relative, resolve } from "node:path";
import { SAFE_FIELDS, logEvent, sanitizeLogFields } from "../src/util/log";
import { capturedLogs, clearCapturedLogs, TEST_DEVICE_ID, TEST_DEVICE_TOKEN, TEST_MCP_TOKEN } from "./harness/env";

const REPO_ROOT = resolve(__dirname, "..", "..");
const SRC_ROOT = resolve(REPO_ROOT, "worker", "src");

/**
 * 一个**形状明确是凭据**的令牌。测试用它而不是 `TEST_DEVICE_TOKEN`，是因为
 * TEST_*_TOKEN 是刻意写的假值（39/42 字符、纯小写加连字符），用来验证
 * "闸 1 丢弃字段"够用，但验证不了"闸 4 整行终检" —— 形状不对就不会被扫到。
 */
const SHAPED_TOKEN = `ghp_${"A1b2C3d4E5f6G7h8I9j0K1l2M3n4P5q6R7s8"}`;

function logText(): string {
  return capturedLogs().join("\n");
}

/** 最近一条日志行，解析成对象。 */
function lastLine(): Record<string, unknown> {
  const all = capturedLogs();
  const raw = all[all.length - 1];
  if (raw === undefined) throw new Error("没有捕获到任何日志行");
  return JSON.parse(raw) as Record<string, unknown>;
}

beforeEach(() => {
  clearCapturedLogs();
});

describe("日志安全 / 令牌藏在哪里都漏不出去（审计 R2 清单）", () => {
  it("令牌出现在 payload 字段", () => {
    logEvent("warn", "upstream.said", { payload: `token=${SHAPED_TOKEN}` });
    logEvent("warn", "upstream.said", { payload: TEST_DEVICE_TOKEN });

    expect(logText()).not.toContain(SHAPED_TOKEN);
    expect(logText()).not.toContain(TEST_DEVICE_TOKEN);
    expect(lastLine()).not.toHaveProperty("payload");
  });

  it("令牌出现在 message 字段", () => {
    logEvent("error", "some.failure", { message: `Authorization: Bearer ${SHAPED_TOKEN}` });
    logEvent("error", "some.failure", { message: TEST_MCP_TOKEN });

    expect(logText()).not.toContain(SHAPED_TOKEN);
    expect(logText()).not.toContain(TEST_MCP_TOKEN);
    expect(lastLine()).not.toHaveProperty("message");
  });

  it("令牌出现在 error 字段（Gate 0 会漏的那一种）", () => {
    // Gate 0 的黑名单里有 token/secret/authorization…，唯独没有 `error`：
    // 当时 `error: String(err)` 是满仓库的写法，而异常文本里带凭据是常态。
    logEvent("warn", "phone_session.welcome_send_failed", { error: `Bearer ${SHAPED_TOKEN}` });

    expect(logText()).not.toContain(SHAPED_TOKEN);
    expect(lastLine()).not.toHaveProperty("error");
  });

  it("令牌藏在嵌套对象里", () => {
    // 两种藏法都要挡住：
    //   a) 白名单键 + 对象值   → 闸 2 换成 "[not-a-scalar]"
    //   b) 非白名单键 + 对象值 → 闸 1 整个丢掉，不递归
    logEvent("warn", "nested.a", { reason: { token: SHAPED_TOKEN } });
    logEvent("warn", "nested.b", { upstream: { headers: { authorization: `Bearer ${SHAPED_TOKEN}` } } });
    logEvent("warn", "nested.c", { deviceId: TEST_DEVICE_ID, extra: { deep: { deeper: SHAPED_TOKEN } } });

    expect(logText()).not.toContain(SHAPED_TOKEN);
    expect(logText()).not.toContain("A1b2C3d4");
    expect(lastLine()["deviceId"]).toBe(TEST_DEVICE_ID);
  });

  it("令牌藏在数组里", () => {
    logEvent("warn", "array.a", { reason: [SHAPED_TOKEN, "ok"] });
    logEvent("warn", "array.b", { list: [SHAPED_TOKEN], tags: ["a", SHAPED_TOKEN] });

    expect(logText()).not.toContain(SHAPED_TOKEN);
    expect(logText()).not.toContain("A1b2C3d4");
  });

  it("令牌出现在异常信息里", () => {
    try {
      throw new Error(`connect failed: Authorization: Bearer ${SHAPED_TOKEN}`);
    } catch (err) {
      // 这正是 Gate 0 的写法（也是审计要求消灭的写法）：把异常字符串和堆栈直接铺进日志。
      logEvent("error", "worker.unhandled_error", {
        path: "/mcp",
        method: "POST",
        error: String(err),
        stack: err instanceof Error ? err.stack : undefined,
        message: (err as Error).message,
      });
    }

    expect(logText()).not.toContain(SHAPED_TOKEN);
    expect(logText()).not.toContain("connect failed");
    expect(lastLine()).not.toHaveProperty("stack");
    // 白名单里的字段还应该在，否则这条测试可能只是"什么都没记"。
    expect(lastLine()["path"]).toBe("/mcp");
  });

  it("普通安全字段仍然正常记录（脱敏不能以丢光调试信息为代价）", () => {
    logEvent("info", "phone_session.connected", {
      deviceId: TEST_DEVICE_ID,
      generation: 3,
      replaced: 1,
      discarded: 2,
      reason: "token_mismatch",
      errorCode: "storage_failure",
      rttMs: 12.5,
      outcome: "pong",
      pending: 0,
      closeCode: 4001,
      contract: null,
      // 下面这些是**故意混进来**的、不该被记录的东西：
      authorization: `Bearer ${SHAPED_TOKEN}`,
      payload: "whatever",
    });

    const line = lastLine();
    expect(line["event"]).toBe("phone_session.connected");
    expect(line["level"]).toBe("info");
    expect(line["deviceId"]).toBe(TEST_DEVICE_ID);
    expect(line["generation"]).toBe(3);
    expect(line["replaced"]).toBe(1);
    expect(line["discarded"]).toBe(2);
    expect(line["reason"]).toBe("token_mismatch");
    expect(line["errorCode"]).toBe("storage_failure");
    expect(line["rttMs"]).toBe(12.5);
    expect(line["outcome"]).toBe("pong");
    expect(line["pending"]).toBe(0);
    expect(line["closeCode"]).toBe(4001);
    expect(line["contract"]).toBeNull();
    // 被丢掉的字段个数是要记账的 —— 否则"某个字段一直没被记上"永远发现不了。
    expect(line["droppedFields"]).toBe(2);
    expect(typeof line["ts"]).toBe("string");
  });

  it("闸 4 整行终检：连 event 名字里塞令牌都会被整行压掉", () => {
    // 故意绕过 sanitizeLogFields 能管到的地方 —— event 是位置参数，不受字段白名单约束。
    // 前三道闸拦不住它，只有"把最终那一行再扫一遍"能兜住。
    logEvent("info", `probe-${SHAPED_TOKEN}`, { deviceId: TEST_DEVICE_ID });

    const line = lastLine();
    expect(line["log_withheld"]).toBe(true);
    expect(line["reason"]).toBe("credential_shaped_string_detected");
    expect(line["event"]).not.toContain("ghp_");
    expect(logText()).not.toContain(SHAPED_TOKEN);
  });

  it("白名单本身是可审计的常量：没有任何凭据类字段混进去", () => {
    // 加字段是一个需要解释的动作。这条用例把"白名单里不该有什么"钉死。
    // 注意匹配的是**完整字段名**，不是子串：`authenticatedDeviceId` 里含 "auth"，
    // 但那是一个设备标识，不是凭据 —— 子串匹配会制造假警报。
    const FORBIDDEN_EXACT = /^(token|secret|password|credential|cookie|apikey|api_key|authorization|bearer|auth|headers?|body|payload|signature|sessionid|session_id)$/i;
    for (const name of SAFE_FIELDS) {
      expect(name, `${name} 不该出现在日志白名单里`).not.toMatch(FORBIDDEN_EXACT);
    }
    expect(SAFE_FIELDS.has("deviceId")).toBe(true);
    expect(SAFE_FIELDS.has("errorCode")).toBe(true);
    // 会话 id 是一张可复用该会话的凭证，必须不在白名单里。
    expect(SAFE_FIELDS.has("sessionId")).toBe(false);
    expect(SAFE_FIELDS.has("authorization")).toBe(false);
    expect(SAFE_FIELDS.has("payload")).toBe(false);
  });
});

describe("日志安全 / 不允许绕过统一日志机制", () => {
  /** worker/src 下的所有 .ts 文件。 */
  function srcFiles(dir: string, out: string[] = []): string[] {
    for (const entry of readdirSync(dir)) {
      const full = join(dir, entry);
      if (statSync(full).isDirectory()) srcFiles(full, out);
      else if (full.endsWith(".ts")) out.push(full);
    }
    return out;
  }

  it("worker/src 下除 util/log.ts 外没有任何 console.* 调用", () => {
    const offenders: string[] = [];
    for (const file of srcFiles(SRC_ROOT)) {
      if (file.endsWith(join("util", "log.ts"))) continue;
      const text = readFileSync(file, "utf8");
      // 去掉注释与字符串里的巧合（本仓库里没有，但保持扫描器本身可读）。
      const stripped = text.replace(/\/\*[\s\S]*?\*\//g, "").replace(/^\s*\/\/.*$/gm, "");
      for (const match of stripped.matchAll(/\bconsole\s*\.\s*\w+/g)) {
        offenders.push(`${relative(REPO_ROOT, file)}: ${match[0]}`);
      }
    }
    expect(offenders).toEqual([]);
  });

  it("源代码里不存在把异常字符串铺进日志的写法", () => {
    // 静态兜底：即使将来有人新增了一处 logEvent，只要他还写 `String(err)` /
    // `err.stack` 往日志字段里塞，这条就会红。
    const offenders: string[] = [];
    for (const file of srcFiles(SRC_ROOT)) {
      if (file.endsWith(join("util", "log.ts"))) continue;
      const lines = readFileSync(file, "utf8").split("\n");
      lines.forEach((line, index) => {
        const code = line.replace(/\/\/.*$/, "");
        if (/\bString\s*\(\s*(err|error|e)\s*\)/.test(code) || /\b(err|error)\.stack\b/.test(code)) {
          offenders.push(`${relative(REPO_ROOT, file)}:${index + 1}`);
        }
      });
    }
    expect(offenders).toEqual([]);
  });

  it("sanitizeLogFields 是不抛异常的纯函数（日志路径自己不能成为故障点）", () => {
    // 日志失败不该把请求带down。喂一堆奇怪的值，只要求它不抛。
    const weird: Array<Record<string, unknown>> = [
      {},
      { deviceId: undefined },
      { deviceId: Symbol("s") as unknown as string },
      { reason: () => "x" },
      { pending: Number.NaN },
      { generation: Number.POSITIVE_INFINITY },
      { deviceId: "a".repeat(10_000) },
    ];
    for (const fields of weird) {
      expect(() => sanitizeLogFields(fields)).not.toThrow();
    }
    // NaN / Infinity 会被 JSON.stringify 变成 null —— 这是可接受的降级，
    // 但"长字符串被当凭据替换掉"必须成立（它确实是 40+ 连续串）。
    expect(sanitizeLogFields({ deviceId: "a".repeat(10_000) }).safe["deviceId"]).toBe("[redacted]");
  });
});
