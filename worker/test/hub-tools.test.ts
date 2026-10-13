// ============================================================================
//  Gate 3 —— 聚合入口（hub_list_tools / hub_call_tool）
// ============================================================================
//
//  任务书/补丁要求 Worker 侧至少覆盖 10 件事，这个文件按那个顺序排：
//    1. 两个聚合工具注册（tools/list 里看得见）
//    2. 参数 Schema（default / required / 额外字段 / 越界）
//    3. 设备身份解析（先解析设备，再谈调用）
//    4. 工具身份解析（server_id + tool_name，**绝不仅凭 tool_name**）
//    5. 未授权调用拒绝（手机回的 not_permitted 如实传播，不被"包装成成功"）
//    6. 手机离线
//    7. 请求超时
//    8. 迟到/非法响应（形状不对的 result 不会变成"成功"）
//    9. 错误结果传播（第三方工具级 isError 与 Hub 级失败区分开）
//   10. 现有四工具不受影响（注册表前四项顺序不变、hub 通道不影响 tool 通道）
//
//  另外盯住三条"数据边界"（补丁 §5/§6 点名的）：
//    · 手机返回的目录是**不可信数据**：只有 authorized+online 的条目才带描述外传；
//    · 第三方结果**不能覆盖** Hub 身份字段（server_id/tool_name/tool_id/request_id…）；
//    · 未实现的多模态内容**如实上报**，不静默丢弃、不伪造。
//
//  ⚠ 这里跑的是 Node 上的 Workers 运行时替身，不是真实 workerd，也不是真机。
// ============================================================================

import { describe, expect, it } from "vitest";
import { loadConfig } from "../src/config";
import {
  HUB_LIMITS,
  HUB_PHONE_CALL_COMMAND,
  HUB_PHONE_LIST_COMMAND,
  hubCallOutcomeToResult,
  hubCatalogOutcomeToResult,
  hubToolId,
  validateHubArguments,
} from "../src/mcp/hub";
import {
  dispatchToolCall,
  findTool,
  HUB_CALL_TOOL_TOOL,
  HUB_LIST_TOOLS_TOOL,
  parseToolArguments,
  ToolArgumentError,
  toolNames,
} from "../src/mcp/tools";
import type { ToolOutcome } from "../src/phone/phone-session";
import { makeRig, TEST_DEVICE_ID } from "./harness/env";

/** 一句话说明：测试里出现的所有"手机返回"都长得**像**真的，但都是可控替身。 */
function phoneSuccess(toolName: string, result: Record<string, unknown>): ToolOutcome {
  return {
    ok: true,
    request_id: "hub-req-1",
    device_id: TEST_DEVICE_ID,
    tool_name: toolName,
    result,
    rtt_ms: 21,
    server_timestamp: 1_700_000_000_000,
    connection_generation: 3,
  };
}

function phoneFailure(toolName: string, code: "phone_offline" | "phone_timeout" | "not_permitted", message: string): ToolOutcome {
  return {
    ok: false,
    request_id: "hub-req-2",
    device_id: TEST_DEVICE_ID,
    tool_name: toolName,
    error: { code, message },
  };
}

const catalogTool = {
  server_id: "srv_alpha",
  tool_name: "search",
  display_name: "Search",
  description: "搜点什么",
  input_schema: { type: "object", properties: { q: { type: "string" } } },
  authorization_state: "authorized",
  availability: "online",
};

describe("Gate 3 / 聚合入口：注册与 Schema", () => {
  it("两个聚合工具都在注册表里，且排在原有四个之后", () => {
    expect(toolNames().slice(0, 4)).toEqual([
      "phone_ping",
      "battery_get_status",
      "device_get_network",
      "device_get_storage",
    ]);
    expect(findTool("hub_list_tools")).toBe(HUB_LIST_TOOLS_TOOL);
    expect(findTool("hub_call_tool")).toBe(HUB_CALL_TOOL_TOOL);
  });

  it("聚合工具的 Schema 里**没有**任何服务器地址 / 认证头字段（ChatGPT 提供不了任意 URL）", () => {
    for (const tool of [HUB_LIST_TOOLS_TOOL, HUB_CALL_TOOL_TOOL]) {
      const props = Object.keys(tool.inputSchema["properties"] as Record<string, unknown>);
      expect(tool.inputSchema["additionalProperties"]).toBe(false);
      expect(props).toContain("server_id");
      for (const forbidden of ["url", "base_url", "headers", "authorization", "token", "transport"]) {
        expect(props).not.toContain(forbidden);
      }
    }
    // 参数里只有 Android 上已保存的 server_id + 该服务器上的原始工具名。
    expect(HUB_CALL_TOOL_TOOL.inputSchema["required"]).toEqual(["server_id", "tool_name"]);
  });
});

describe("Gate 3 / 聚合入口：参数校验", () => {
  it("hub_list_tools：server_id 可选；不合法或多余字段一律拒绝", () => {
    expect(parseToolArguments(HUB_LIST_TOOLS_TOOL, {})).toEqual({ toolArguments: {} });
    expect(parseToolArguments(HUB_LIST_TOOLS_TOOL, { server_id: "srv_alpha" }).server_id).toBe("srv_alpha");

    for (const bad of ["Srv_Alpha", "srv:alpha", "srv alpha", "-leading", "a".repeat(65), 7]) {
      expect(() => parseToolArguments(HUB_LIST_TOOLS_TOOL, { server_id: bad })).toThrow(ToolArgumentError);
    }
    // 多余字段照样走白名单 —— 聚合工具不是"什么都能塞"的后门。
    expect(() => parseToolArguments(HUB_LIST_TOOLS_TOOL, { url: "https://example.com/mcp" })).toThrow(
      /不认识的字段/,
    );
  });

  it("hub_call_tool：server_id 与 tool_name 必填，格式严格", () => {
    expect(() => parseToolArguments(HUB_CALL_TOOL_TOOL, {})).toThrow(/server_id 必填/);
    expect(() => parseToolArguments(HUB_CALL_TOOL_TOOL, { server_id: "srv_alpha" })).toThrow(ToolArgumentError);
    for (const bad of ["bad name", "bad.name", "bad/name", "", "a".repeat(129), 12]) {
      expect(() =>
        parseToolArguments(HUB_CALL_TOOL_TOOL, { server_id: "srv_alpha", tool_name: bad }),
      ).toThrow(ToolArgumentError);
    }
  });

  it("hub_call_tool：arguments 缺省为 {}，合法对象被**重建**（不是原样透传）", () => {
    const parsed = parseToolArguments(HUB_CALL_TOOL_TOOL, {
      server_id: "srv_alpha",
      tool_name: "search",
    });
    expect(parsed.hubArguments).toEqual({});

    const source = { q: "hi", limit: 3, nested: { ok: true, list: [1, "x", null] } };
    const parsed2 = parseToolArguments(HUB_CALL_TOOL_TOOL, {
      server_id: "srv_alpha",
      tool_name: "search",
      arguments: source,
    });
    expect(parsed2.hubArguments).toEqual(source);
    // 重建 = 新对象，第三方拿不到我们内部对象的引用。
    expect(parsed2.hubArguments).not.toBe(source);
  });

  it("arguments 的越界、危险键名与非有限数字全部拒绝", () => {
    // 探测原型链的键名
    expect(validateHubArguments(JSON.parse('{"__proto__":{"polluted":true}}')).ok).toBe(false);
    expect(validateHubArguments(JSON.parse('{"constructor":{}}')).ok).toBe(false);
    // 太大 / 太深 / 太多节点
    expect(validateHubArguments({ s: "x".repeat(HUB_LIMITS.maxArgumentString + 1) }).ok).toBe(false);
    expect(
      validateHubArguments({
        a: { b: { c: { d: { e: { tooDeep: true } } } } },
      }).ok,
    ).toBe(false);
    let wide: Record<string, unknown> = {};
    for (let i = 0; i < HUB_LIMITS.maxArgumentNodes + 10; i += 1) wide[`k${i}`] = i;
    expect(validateHubArguments(wide).ok).toBe(false);
    // 非有限数字与 JSON 里不该出现的类型
    expect(validateHubArguments({ nan: Number.NaN }).ok).toBe(false);
    expect(validateHubArguments({ inf: Number.POSITIVE_INFINITY }).ok).toBe(false);
    expect(validateHubArguments({ missing: undefined }).ok).toBe(false);
    // 数组太长
    expect(validateHubArguments({ list: new Array(HUB_LIMITS.maxArgumentArrayLength + 1).fill(1) }).ok).toBe(false);
    // 顶层不是对象
    expect(validateHubArguments([1, 2, 3]).ok).toBe(false);
    expect(validateHubArguments("字符串").ok).toBe(false);
  });
});

describe("Gate 3 / 聚合入口：转发与身份", () => {
  it("hub_list_tools 用聚合命令名转发，且不带任何第三方向量", async () => {
    const rig = await makeRig();
    const seen: Array<{ toolName: string; args: Record<string, unknown> }> = [];
    const dispatched = await dispatchToolCall(
      loadConfig(rig.env),
      "hub_list_tools",
      { device_id: TEST_DEVICE_ID },
      async () => {
        throw new Error("聚合工具不该走 ping 通道");
      },
      async (_deviceId, toolName, args) => {
        seen.push({ toolName, args });
        return phoneSuccess(HUB_PHONE_LIST_COMMAND, { servers: [], tools: [] });
      },
    );

    expect(seen).toEqual([{ toolName: HUB_PHONE_LIST_COMMAND, args: {} }]);
    expect(dispatched.result?.isError).toBeFalsy();
  });

  it("★ 第三方工具名只出现在 arguments.tool_name 上，**绝不**变成手机命令名", async () => {
    const rig = await makeRig();
    const seen: Array<{ toolName: string; args: Record<string, unknown> }> = [];
    const dispatched = await dispatchToolCall(
      loadConfig(rig.env),
      "hub_call_tool",
      // 故意传一个**本机能力**的名字：它只能被当成第三方工具名转发，
      // 绝不能落进本机能力的执行路径（那正是补丁 §6 点名的第 10 条风险）。
      { device_id: TEST_DEVICE_ID, server_id: "srv_alpha", tool_name: "battery_get_status" },
      async () => {
        throw new Error("聚合工具不该走 ping 通道");
      },
      async (_deviceId, toolName, args) => {
        seen.push({ toolName, args });
        return phoneSuccess(HUB_PHONE_CALL_COMMAND, { content: [{ type: "text", text: "ok" }], is_error: false });
      },
    );

    expect(seen).toHaveLength(1);
    // 命令名是聚合保留名，不是 battery_get_status。
    expect(seen[0]?.toolName).toBe(HUB_PHONE_CALL_COMMAND);
    expect(seen[0]?.toolName).not.toBe("battery_get_status");
    // 身份完整透传：server_id + tool_name + 参数。
    expect(seen[0]?.args).toEqual({
      server_id: "srv_alpha",
      tool_name: "battery_get_status",
      arguments: {},
    });
    // 结果里的身份字段来自**请求**，不是手机的自述。
    const structured = dispatched.result?.structuredContent as Record<string, unknown>;
    expect(structured["server_id"]).toBe("srv_alpha");
    expect(structured["tool_name"]).toBe("battery_get_status");
    expect(structured["tool_id"]).toBe(hubToolId("srv_alpha", "battery_get_status"));
  });

  it("设备解析失败时**根本不转发**（未注册设备 / 已知撤销设备）", async () => {
    const rig = await makeRig();
    let called = 0;
    const dispatched = await dispatchToolCall(
      loadConfig(rig.env),
      "hub_call_tool",
      { device_id: "phone-that-does-not-exist", server_id: "srv_alpha", tool_name: "search" },
      async () => {
        called += 1;
        throw new Error("不该走 ping");
      },
      async () => {
        called += 1;
        throw new Error("不该转发");
      },
    );

    expect(called).toBe(0);
    const structured = dispatched.result?.structuredContent as Record<string, unknown>;
    expect(structured["error_code"]).toBe("unknown_device");
    expect(dispatched.result?.isError).toBe(true);
  });

  it("服务端禁用名单对聚合入口同样生效，且先于设备解析", async () => {
    const rig = await makeRig({ vars: { PHONE_DISABLED_TOOLS: "hub_call_tool" } });
    let called = 0;
    const dispatched = await dispatchToolCall(
      loadConfig(rig.env),
      "hub_call_tool",
      { device_id: "phone-that-does-not-exist", server_id: "srv_alpha", tool_name: "search" },
      async () => {
        called += 1;
        throw new Error("不该走 ping");
      },
      async () => {
        called += 1;
        throw new Error("不该转发");
      },
    );

    expect(called).toBe(0);
    const structured = dispatched.result?.structuredContent as Record<string, unknown>;
    expect(structured["error_code"]).toBe("not_permitted");
  });
});

describe("Gate 3 / 聚合入口：失败与错误传播", () => {
  it("手机离线 / 超时 / 未授权：Hub 层失败如实传播，且**不被误报成功**", () => {
    for (const [code, message] of [
      ["phone_offline", "该设备当前没有已建立的连接"],
      ["phone_timeout", "手机在超时前没有回应"],
      ["not_permitted", "该工具未获用户授权"],
    ] as const) {
      const failed = hubCallOutcomeToResult(
        phoneFailure(HUB_PHONE_CALL_COMMAND, code, message),
        "srv_alpha",
        "search",
      );
      expect(failed.isError).toBe(true);
      const structured = failed.structuredContent as Record<string, unknown>;
      expect(structured["ok"]).toBe(false);
      expect(structured["error_code"]).toBe(code);
      expect(structured["error_message"]).toBe(message);
      expect(failed.content[0]?.text).toContain(code);
    }
  });

  it("第三方工具**执行层面**的失败（is_error）按 MCP 约定包成 isError 结果，而不是 Hub 错误", () => {
    const result = hubCallOutcomeToResult(
      phoneSuccess(HUB_PHONE_CALL_COMMAND, {
        content: [{ type: "text", text: "第三方说：参数 q 不能为空" }],
        content_types: ["text"],
        is_error: true,
      }),
      "srv_alpha",
      "search",
    );

    expect(result.isError).toBe(true);
    const structured = result.structuredContent as Record<string, unknown>;
    expect(structured["mcp_is_error"]).toBe(true);
    // 关键：第三方说了什么必须原样带给模型，而不是只给一个 Hub 错误码。
    expect(result.content.map((c) => c.text).join("\n")).toContain("参数 q 不能为空");
  });

  it("★ 第三方结果不能覆盖 Hub 身份字段（request_id / device_id / server_id / tool_id / ok）", () => {
    const result = hubCallOutcomeToResult(
      phoneSuccess(HUB_PHONE_CALL_COMMAND, {
        content: [{ type: "text", text: "hi" }],
        is_error: false,
        // 下面这些全部来自"一台被改造过的手机"：
        ok: false,
        request_id: "evil-request",
        device_id: "evil-device",
        tool_name: "evil-tool",
        server_id: "evil-server",
        tool_id: "mcp:evil:evil",
        rtt_ms: 999_999,
        connection_generation: 42,
      }),
      "srv_alpha",
      "search",
    );

    const structured = result.structuredContent as Record<string, unknown>;
    expect(structured["ok"]).toBe(true);
    expect(structured["request_id"]).toBe("hub-req-1");
    expect(structured["device_id"]).toBe(TEST_DEVICE_ID);
    expect(structured["tool_name"]).toBe("search");
    expect(structured["server_id"]).toBe("srv_alpha");
    expect(structured["tool_id"]).toBe("mcp:srv_alpha:search");
    expect(structured["rtt_ms"]).toBe(21);
    expect(structured["connection_generation"]).toBe(3);
  });

  it("大文本被截断并如实标注；未实现的多模态**不静默丢弃**", () => {
    const huge = "x".repeat(HUB_LIMITS.maxTextChars + 100);
    const result = hubCallOutcomeToResult(
      phoneSuccess(HUB_PHONE_CALL_COMMAND, {
        content: [
          { type: "text", text: huge },
          { type: "image", data: "BASE64", mimeType: "image/png" },
          { type: "resource", uri: "file:///etc/passwd" },
        ],
        content_types: ["text", "image", "resource"],
        unsupported_content_types: ["image", "resource"],
        is_error: false,
      }),
      "srv_alpha",
      "search",
    );

    const structured = result.structuredContent as Record<string, unknown>;
    expect(structured["text_truncated"]).toBe(true);
    expect(structured["truncated"]).toBe(true);
    expect(structured["unsupported_content_types"]).toEqual(["image", "resource"]);
    // 只有 text 进入 content（第一段是我们自己的摘要行，第二段是第三方文本）：
    // 图片/资源的原始数据**没有**被抄进来。
    expect(result.content).toHaveLength(2);
    expect(result.content.every((c) => c.type === "text")).toBe(true);
    expect(result.content[1]?.text.length).toBeLessThanOrEqual(HUB_LIMITS.maxTextChars);
    expect(JSON.stringify(structured)).not.toContain("BASE64");
    expect(JSON.stringify(structured)).not.toContain("/etc/passwd");
  });

  it("structured_content 过大或不是对象时明确标注被省略", () => {
    const big = hubCallOutcomeToResult(
      phoneSuccess(HUB_PHONE_CALL_COMMAND, {
        content: [{ type: "text", text: "hi" }],
        structured_content: { blob: "y".repeat(HUB_LIMITS.maxStructuredChars + 1) },
      }),
      "srv_alpha",
      "search",
    );
    const bigStructured = big.structuredContent as Record<string, unknown>;
    expect(bigStructured["structured_content"]).toBeUndefined();
    expect(bigStructured["structured_content_omitted"]).toBe("too_large_or_not_object");

    const nonObject = hubCallOutcomeToResult(
      phoneSuccess(HUB_PHONE_CALL_COMMAND, { content: [], structured_content: [1, 2, 3] }),
      "srv_alpha",
      "search",
    );
    expect((nonObject.structuredContent as Record<string, unknown>)["structured_content_omitted"]).toBe(
      "too_large_or_not_object",
    );
  });

  it("形状不对的目录（不是对象、字段类型错）不会崩，也不会变成一堆「成功」的工具", () => {
    const weird = hubCatalogOutcomeToResult(
      phoneSuccess(HUB_PHONE_LIST_COMMAND, {
        servers: "not-an-array",
        tools: [null, 42, "x", { server_id: "bad id", tool_name: "search" }, { server_id: "srv_alpha" }],
      }),
    );
    const structured = weird.structuredContent as Record<string, unknown>;
    expect(structured["ok"]).toBe(true);
    expect(structured["tools"]).toEqual([]);
    expect(structured["tools_count"]).toBe(0);
    // 五个条目里没有一个合法 → 全部计入 other_tools_count，并且**没有**描述外传。
    expect(structured["other_tools_count"]).toBe(5);
    expect(JSON.stringify(structured)).not.toContain("search");
  });
});

describe("Gate 3 / 目录映射：不可信数据边界", () => {
  it("只列 authorized + online 的工具；未授权/离线的**只计数**，描述不外传", () => {
    const result = hubCatalogOutcomeToResult(
      phoneSuccess(HUB_PHONE_LIST_COMMAND, {
        servers: [{ server_id: "srv_alpha", server_name: "Alpha", availability: "online" }],
        tools: [
          catalogTool,
          { ...catalogTool, tool_name: "secret_write", authorization_state: "not_authorized" },
          { ...catalogTool, tool_name: "offline_tool", availability: "offline" },
          { ...catalogTool, tool_name: "stale_tool", authorization_state: "stale" },
        ],
      }),
    );

    const structured = result.structuredContent as Record<string, unknown>;
    expect(structured["tools_count"]).toBe(1);
    expect(structured["other_tools_count"]).toBe(3);
    const tools = structured["tools"] as Array<Record<string, unknown>>;
    expect(tools).toEqual([
      {
        server_id: "srv_alpha",
        tool_name: "search",
        tool_id: "mcp:srv_alpha:search",
        display_name: "Search",
        description: "搜点什么",
        authorization_state: "authorized",
        availability: "online",
        input_schema: { type: "object", properties: { q: { type: "string" } } },
      },
    ]);
    // 未授权工具的名字与描述一个都不许出现在结果里。
    const serialized = JSON.stringify(structured);
    expect(serialized).not.toContain("secret_write");
    expect(serialized).not.toContain("offline_tool");
    expect(serialized).not.toContain("stale_tool");
  });

  it("失败结果也满足自己声明的 outputSchema（缺 required 字段会把失败变成协议错误）", () => {
    // 这一条来自 e2e 里一次真实的踩坑：`hub_call_tool` 的 outputSchema 要求
    // server_id / tool_id，而失败结果当时没带 → 官方 MCP SDK 校验不过，
    // 直接把"手机离线 / 没授权 / 超时"变成 JSON-RPC -32602 协议错误，
    // 模型一个字的原因都看不到。所以这里对着**声明本身**断言，防止再漂。
    const failure: ToolOutcome = {
      ok: false,
      request_id: "hub-req-fail",
      device_id: TEST_DEVICE_ID,
      tool_name: HUB_PHONE_CALL_COMMAND,
      error: { code: "not_permitted", message: "第三方工具没有被授权" },
    };

    const requiredOf = (tool: { outputSchema?: Record<string, unknown> }): string[] =>
      ((tool.outputSchema?.["required"] as string[] | undefined) ?? []);

    const callStructured = hubCallOutcomeToResult(failure, "srv_alpha", "search", "hi")
      .structuredContent as Record<string, unknown>;
    for (const key of requiredOf(HUB_CALL_TOOL_TOOL)) {
      expect(Object.keys(callStructured)).toContain(key);
    }
    // 目标身份用请求里的值，而不是手机回的信封名。
    expect(callStructured["tool_name"]).toBe("search");
    expect(callStructured["tool_id"]).toBe(hubToolId("srv_alpha", "search"));
    expect(callStructured["error_code"]).toBe("not_permitted");

    const catalogStructured = hubCatalogOutcomeToResult({ ...failure, tool_name: HUB_PHONE_LIST_COMMAND })
      .structuredContent as Record<string, unknown>;
    for (const key of requiredOf(HUB_LIST_TOOLS_TOOL)) {
      expect(Object.keys(catalogStructured)).toContain(key);
    }
    // 目录失败没有"目标工具"，就不该凭空编一个 server_id 出来。
    expect(catalogStructured["server_id"]).toBeUndefined();
  });

  it("手机自报的 other_tools_count 会被算进结果（真实 Android 会先滤掉未授权工具）", () => {
    // 这一条盯的是一个**跨组件**约定：Android 的 HubCatalog 在发之前就把未授权的工具
    // 从数组里删掉了，只把数量报上来。服务端如果只数数组里剩下的条目，模型看到的
    // 永远是 0 —— "没有偷偷藏东西"这句话就没有可验证的形式了。
    const result = hubCatalogOutcomeToResult(
      phoneSuccess(HUB_PHONE_LIST_COMMAND, {
        servers: [{ server_id: "srv_alpha", server_name: "Alpha", availability: "online" }],
        tools: [catalogTool],
        other_tools_count: 2,
      }),
    );
    const structured = result.structuredContent as Record<string, unknown>;
    expect(structured["tools_count"]).toBe(1);
    expect(structured["other_tools_count"]).toBe(2);
  });

  it("自报数与服务端自己判掉的条目相加，且非法/离谱的值不会失控", () => {
    const base = {
      servers: [],
      tools: [
        catalogTool,
        { ...catalogTool, tool_name: "secret_write", authorization_state: "not_authorized" },
      ],
    };
    const mixed = hubCatalogOutcomeToResult(
      phoneSuccess(HUB_PHONE_LIST_COMMAND, { ...base, other_tools_count: 4 }),
    );
    // 手机报 4 个 + 服务端自己判掉 1 个 = 5。
    expect((mixed.structuredContent as Record<string, unknown>)["other_tools_count"]).toBe(5);

    // 手机说的数不可信到"不是一个数"时按 0 算（不是 NaN、不是字符串）。
    for (const weird of ["3", -1, 2.5, Number.NaN, null, undefined]) {
      const result = hubCatalogOutcomeToResult(
        phoneSuccess(HUB_PHONE_LIST_COMMAND, { servers: [], tools: [catalogTool], other_tools_count: weird }),
      );
      expect((result.structuredContent as Record<string, unknown>)["other_tools_count"]).toBe(0);
    }

    // 报一个天文数字也只在封顶值上停住。
    const huge = hubCatalogOutcomeToResult(
      phoneSuccess(HUB_PHONE_LIST_COMMAND, { servers: [], tools: [], other_tools_count: 10_000_000 }),
    );
    expect((huge.structuredContent as Record<string, unknown>)["other_tools_count"]).toBe(
      HUB_LIMITS.maxReportedOtherTools,
    );
  });

  it("目录文本明确标注第三方描述是不可信数据", () => {
    const result = hubCatalogOutcomeToResult(
      phoneSuccess(HUB_PHONE_LIST_COMMAND, { servers: [], tools: [catalogTool] }),
    );
    const text = result.content.map((c) => c.text).join("\n");
    expect(text).toContain("不可信");
    expect(text).toContain("mcp:srv_alpha:search");
  });

  it("工具数超过上限时截断并标注 truncated；超大 schema 被丢掉但工具仍在", () => {
    const many = Array.from({ length: HUB_LIMITS.maxTools + 3 }, (_, i) => ({
      ...catalogTool,
      tool_name: `tool_${i}`,
    }));
    const bigSchema = { ...catalogTool, tool_name: "big_schema", input_schema: { blob: "z".repeat(HUB_LIMITS.maxSchemaChars + 1) } };
    const result = hubCatalogOutcomeToResult(
      phoneSuccess(HUB_PHONE_LIST_COMMAND, { servers: [], tools: [...many, bigSchema] }),
    );

    const structured = result.structuredContent as Record<string, unknown>;
    expect(structured["tools_count"]).toBe(HUB_LIMITS.maxTools);
    expect(structured["truncated"]).toBe(true);
    const tools = structured["tools"] as Array<Record<string, unknown>>;
    // big_schema 排在最后，已经被上限挤掉 → 它计入 other_tools_count。
    expect(tools.find((t) => t["tool_name"] === "big_schema")).toBeUndefined();
    expect(structured["schemas_dropped"]).toBe(0);

    // 单独验证"schema 过大只丢 schema、工具保留"。
    const single = hubCatalogOutcomeToResult(
      phoneSuccess(HUB_PHONE_LIST_COMMAND, { servers: [], tools: [bigSchema] }),
    );
    const singleStructured = single.structuredContent as Record<string, unknown>;
    expect(singleStructured["tools_count"]).toBe(1);
    expect(singleStructured["schemas_dropped"]).toBe(1);
    const singleTools = singleStructured["tools"] as Array<Record<string, unknown>>;
    expect(singleTools[0]?.["tool_name"]).toBe("big_schema");
    expect(singleTools[0]?.["input_schema"]).toBeUndefined();
    expect(JSON.stringify(singleStructured)).not.toContain("zzzz");
  });

  it("目录失败（手机离线）时**不伪造空目录**，而是如实报错", () => {
    const failed = hubCatalogOutcomeToResult(phoneFailure(HUB_PHONE_LIST_COMMAND, "phone_offline", "手机不在线"));
    expect(failed.isError).toBe(true);
    const structured = failed.structuredContent as Record<string, unknown>;
    expect(structured["ok"]).toBe(false);
    expect(structured["error_code"]).toBe("phone_offline");
    expect(structured["tools"]).toBeUndefined();
  });
});
