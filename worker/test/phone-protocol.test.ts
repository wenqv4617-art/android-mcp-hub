// ============================================================================
//  Gate 2 工作包 B —— 线上字节 → TS 对象 的那一层
// ============================================================================
//
//  `worker/src/phone/messages.ts` 是整个系统里**唯一**一个把不可信字节
//  变成对象的地方。它的一条原则值得单独写在这里：
//
//    **不认识的东西不抛异常，缺字段就当不合法，绝不猜默认值。**
//
//  因为"猜一个默认值"在这里的后果是：一条格式错误的帧被当成合法的，
//  于是某个已经超时的请求被改写成成功 —— 这正是本项目最不能接受的结果。
//
//  这个文件同时锁住"闭集"：错误码集合一旦被偷偷扩大或改名，
//  上层（工具结果、协议文档、手机侧）就会开始各说各话。
// ============================================================================

import { describe, expect, it } from "vitest";
import {
  encodeServerFrame,
  normalizeToolErrorCode,
  parseIncomingFrame,
  SERVER_ERROR_CODES,
  TOOL_RESULT_ERROR_CODES,
} from "../src/phone/messages";

describe("Gate 2 协议 / 上行帧解析", () => {
  it("phone_pong：合法帧被解析，缺 request_id 走 invalid 而不是 pong 路径", () => {
    const ok = parseIncomingFrame(JSON.stringify({ type: "phone_pong", request_id: "r1", timestamp: 123 }));
    expect(ok.type).toBe("phone_pong");
    if (ok.type !== "phone_pong") throw new Error("解析失败");
    expect(ok.request_id).toBe("r1");
    expect(ok.timestamp).toBe(123);

    // ⚠ 这一条尤其重要：缺 request_id 的 pong **不能**被当成一条"没人认领的 pong"，
    //    它必须走 malformed 路径 —— 否则它会在别处被误配对。
    const missing = parseIncomingFrame(JSON.stringify({ type: "phone_pong", timestamp: 123 }));
    expect(missing.type).toBe("invalid");
    if (missing.type !== "invalid") throw new Error("不该被解析成合法帧");
    expect(missing.reason).toBe("phone_pong_missing_request_id");

    const emptyId = parseIncomingFrame(JSON.stringify({ type: "phone_pong", request_id: "" }));
    expect(emptyId.type).toBe("invalid");

    const numericId = parseIncomingFrame(JSON.stringify({ type: "phone_pong", request_id: 42 }));
    expect(numericId.type).toBe("invalid");
  });

  it("hello：字段全是可选的，但给错了类型就是错的", () => {
    const bare = parseIncomingFrame(JSON.stringify({ type: "hello" }));
    expect(bare.type).toBe("hello");

    const full = parseIncomingFrame(
      JSON.stringify({ type: "hello", device_id: "d1", protocol_version: "phone-link-v1", app_version: "0.1.4", android_sdk: 34 }),
    );
    expect(full.type).toBe("hello");
    if (full.type !== "hello") throw new Error("解析失败");
    expect(full.device_id).toBe("d1");
    expect(full.android_sdk).toBe(34);

    // 可选字段给错了类型 → 那个字段被丢弃，但帧本身仍然是合法的 hello。
    //   理由：hello 是纯信息性帧，为一个可选字段的类型错误把整帧打成 malformed，
    //   会连带丢掉同一帧里其它正确的信息。**注意这里丢掉的是字段，不是帧。**
    const badSdk = parseIncomingFrame(JSON.stringify({ type: "hello", android_sdk: "34", app_version: "1.2.3" }));
    expect(badSdk.type).toBe("hello");
    if (badSdk.type !== "hello") throw new Error("解析失败");
    expect(badSdk.android_sdk).toBeUndefined();
    expect(badSdk.app_version).toBe("1.2.3");
  });

  it("heartbeat：timestamp 不是有限数字时降级成 undefined，但帧本身仍然合法", () => {
    const hb = parseIncomingFrame(JSON.stringify({ type: "heartbeat" }));
    expect(hb.type).toBe("heartbeat");
    if (hb.type !== "heartbeat") throw new Error("解析失败");
    expect(hb.timestamp).toBeUndefined();

    const weird = parseIncomingFrame(JSON.stringify({ type: "heartbeat", timestamp: "not-a-number" }));
    expect(weird.type).toBe("heartbeat");
    if (weird.type !== "heartbeat") throw new Error("解析失败");
    expect(weird.timestamp).toBeUndefined();
  });

  it("非 JSON / 非对象 / 缺 type → invalid（不是抛异常）", () => {
    expect(parseIncomingFrame("{not json").type).toBe("invalid");
    expect(parseIncomingFrame("[]").type).toBe("invalid");
    expect(parseIncomingFrame("null").type).toBe("invalid");
    expect(parseIncomingFrame("42").type).toBe("invalid");

    const noType = parseIncomingFrame(JSON.stringify({ request_id: "r1" }));
    expect(noType.type).toBe("invalid");
    if (noType.type !== "invalid") throw new Error("不该被解析成合法帧");
    expect(noType.reason).toBe("missing_type");

    const emptyType = parseIncomingFrame(JSON.stringify({ type: "" }));
    expect(emptyType.type).toBe("invalid");
  });

  it("不认识的 type 走 unknown 并把原名字带出来（向前兼容，不是错误）", () => {
    const unknown = parseIncomingFrame(JSON.stringify({ type: "some_future_frame", payload: { a: 1 } }));
    expect(unknown.type).toBe("unknown");
    if (unknown.type !== "unknown") throw new Error("不该被解析成合法帧");
    expect(unknown.raw_type).toBe("some_future_frame");
  });

  it("未知字段被忽略，不因此拒绝整帧（向前兼容）", () => {
    const weird = parseIncomingFrame(
      JSON.stringify({ type: "phone_pong", request_id: "r1", future_field: { deeply: ["nested"] } }),
    );
    expect(weird.type).toBe("phone_pong");
  });
});

describe("Gate 2 协议 / tool_result 校验矩阵", () => {
  const base = { type: "tool_result", request_id: "tr-1" };

  it("成功帧：result 必须是对象", () => {
    const ok = parseIncomingFrame(JSON.stringify({ ...base, ok: true, result: { a: 1 } }));
    expect(ok.type).toBe("tool_result");
    if (ok.type !== "tool_result") throw new Error("解析失败");
    expect(ok.ok).toBe(true);
    expect(ok.result).toEqual({ a: 1 });

    // result 缺失 → 归一成 {}（"成功但没有内容"是一个合法结论）。
    const empty = parseIncomingFrame(JSON.stringify({ ...base, ok: true }));
    expect(empty.type).toBe("tool_result");

    // result 是数组/字符串/数字 → 不合法。
    for (const bad of [[1, 2], "text", 5, true]) {
      const parsed = parseIncomingFrame(JSON.stringify({ ...base, ok: true, result: bad }));
      expect(parsed.type).toBe("invalid");
      if (parsed.type !== "invalid") throw new Error("不该被解析成合法帧");
      expect(parsed.reason).toBe("tool_result_bad_result");
    }
  });

  it("成功帧不允许同时带 error，失败帧不允许同时带 result", () => {
    const conflictingError = parseIncomingFrame(
      JSON.stringify({ ...base, ok: true, result: {}, error: { code: "tool_failed", message: "x" } }),
    );
    expect(conflictingError.type).toBe("invalid");
    if (conflictingError.type !== "invalid") throw new Error("不该被解析成合法帧");
    expect(conflictingError.reason).toBe("tool_result_conflicting_error");

    const conflictingResult = parseIncomingFrame(
      JSON.stringify({ ...base, ok: false, result: { a: 1 }, error: { code: "tool_failed", message: "x" } }),
    );
    expect(conflictingResult.type).toBe("invalid");
    if (conflictingResult.type !== "invalid") throw new Error("不该被解析成合法帧");
    expect(conflictingResult.reason).toBe("tool_result_conflicting_result");
  });

  it("失败帧必须有 error.code，且 message 缺失时也不能丢帧", () => {
    const missingError = parseIncomingFrame(JSON.stringify({ ...base, ok: false }));
    expect(missingError.type).toBe("invalid");
    if (missingError.type !== "invalid") throw new Error("不该被解析成合法帧");
    expect(missingError.reason).toBe("tool_result_missing_error");

    const missingCode = parseIncomingFrame(JSON.stringify({ ...base, ok: false, error: { message: "x" } }));
    expect(missingCode.type).toBe("invalid");

    const noMessage = parseIncomingFrame(JSON.stringify({ ...base, ok: false, error: { code: "tool_failed" } }));
    expect(noMessage.type).toBe("tool_result");
  });

  it("request_id 缺失 / 非字符串 / 空 → invalid", () => {
    for (const bad of [undefined, 42, ""]) {
      const parsed = parseIncomingFrame(
        JSON.stringify({ type: "tool_result", ...(bad === undefined ? {} : { request_id: bad }), ok: true, result: {} }),
      );
      expect(parsed.type).toBe("invalid");
      if (parsed.type !== "invalid") throw new Error("不该被解析成合法帧");
      expect(parsed.reason).toBe("tool_result_missing_request_id");
    }
  });

  it("ok 不是布尔 → invalid", () => {
    const parsed = parseIncomingFrame(JSON.stringify({ ...base, ok: "true", result: {} }));
    expect(parsed.type).toBe("invalid");
    if (parsed.type !== "invalid") throw new Error("不该被解析成合法帧");
    expect(parsed.reason).toBe("tool_result_missing_ok");
  });
});

describe("Gate 2 协议 / 闭集与归一化", () => {
  it("没见过的错误码被归一化成 tool_failed，而不是丢帧", () => {
    // 丢帧 = 让一个已经拿到结论的请求白等到超时。超时是"不知道"，
    // 这里明明"知道失败了"，不该退化成更差的信息。
    expect(normalizeToolErrorCode("tool_failed")).toBe("tool_failed");
    expect(normalizeToolErrorCode("unsupported_tool")).toBe("unsupported_tool");
    expect(normalizeToolErrorCode("invalid_arguments")).toBe("invalid_arguments");
    expect(normalizeToolErrorCode("tool_unavailable")).toBe("tool_unavailable");
    expect(normalizeToolErrorCode("not_permitted")).toBe("not_permitted");
    expect(normalizeToolErrorCode("some_future_code_v9")).toBe("tool_failed");
    expect(normalizeToolErrorCode("")).toBe("tool_failed");
  });

  it("工具失败码是闭集，且与文档一致", () => {
    expect([...TOOL_RESULT_ERROR_CODES]).toEqual([
      "unsupported_tool",
      "invalid_arguments",
      "tool_failed",
      "tool_unavailable",
      "not_permitted",
    ]);
  });

  it("服务端错误码是闭集；Gate 2 只加了 device_revoked 一个", () => {
    expect([...SERVER_ERROR_CODES]).toEqual([
      "message_too_large",
      "malformed_message",
      "unsupported_type",
      "unknown_request_id",
      "device_revoked",
    ]);
  });

  it("下行帧编码就是 JSON.stringify（不给线上格式留第二套实现）", () => {
    const encoded = encodeServerFrame({ type: "heartbeat_ack", server_time: 123 });
    expect(JSON.parse(encoded)).toEqual({ type: "heartbeat_ack", server_time: 123 });

    const toolFrame = encodeServerFrame({
      type: "tool_request",
      request_id: "tr-1",
      tool_name: "battery_get_status",
      arguments: {},
    });
    expect(JSON.parse(toolFrame)).toEqual({
      type: "tool_request",
      request_id: "tr-1",
      tool_name: "battery_get_status",
      arguments: {},
    });
  });
});
