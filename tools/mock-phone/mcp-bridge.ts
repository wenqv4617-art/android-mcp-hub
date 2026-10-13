// ============================================================================
//  手机替身 ↔ 真实第三方 MCP 服务器 的桥
// ============================================================================
//
//  为什么需要它：`tools/mock-phone` 默认用一张**写死的**能力表回答 tool_request。
//  那能证明"帧会走、错误码会传"，但证明不了 Gate 3 真正要证明的事 ——
//  **第三方工具目录与结果是真从 MCP 协议里读出来的**，而不是替身编的。
//
//  接上这个桥之后，手机替身收到 `hub_list_tools` / `hub_call_tool` 时会真的：
//    1. 用 `tools/mcp-streamable-http-client.mjs`（同一份零依赖参考客户端）
//       对一台真实 MCP 服务器做 initialize 握手；
//    2. `tools/list` 拉全量目录（含分页）；
//    3. `tools/call` 真的调用第三方工具，把它的真实返回搬回来。
//
//  ⚠ 它是**测试脚手架**，不是产品代码：
//    · 只做协议搬运，不做任何授权判定之外的安全判断；
//    · 授权过滤（哪些工具算"用户已授权"）由 `--mcp-authorize` 决定，
//      与 Android 侧 `HubCatalog` 的过滤位置一致；
//    · 结果里的身份字段（server_id / tool_id / request_id…）由 Worker 自己贴，
//      这里**不生成**它们。
// ============================================================================

import { McpClientError, McpStreamableHttpClient } from "../mcp-streamable-http-client.mjs";
import { MockToolRefusal, type MockToolHandler } from "./client";

export interface McpBridgeOptions {
  /** 第三方 MCP 服务器地址（形如 http://127.0.0.1:8791/mcp）。 */
  url: string;
  /** 目录里这台服务器的 id（必须匹配 `^[a-z0-9][a-z0-9_-]{0,63}$`）。 */
  serverId: string;
  /** 目录里展示的服务器名；缺省用服务器自报的 serverInfo.name。 */
  serverName?: string;
  /** 单次请求超时；默认 5s。 */
  timeoutMs?: number;
  /**
   * 哪些工具算"已授权"。
   *
   * 缺省规则刻意保守：**声明了 destructiveHint 的工具一律视为未授权**，
   * 其余（含没有 annotations 的）视为已授权。这样 e2e 里必然存在"存在但没给你看"
   * 的工具，`other_tools_count` 才有东西可断言。
   */
  isAuthorized?: (toolName: string, annotations: Record<string, unknown> | undefined) => boolean;
  log?: (line: string) => void;
}

/** 一条目录工具（字段名必须与 `worker/src/mcp/hub.ts` 读的一致）。 */
interface CatalogToolEntry {
  server_id: string;
  tool_name: string;
  display_name: string;
  description: string;
  input_schema?: Record<string, unknown>;
  authorization_state: "authorized" | "not_authorized";
  availability: "online";
}

const AUTHORIZE_FLAG = "--mcp-authorize";

/**
 * 解析 `--mcp-authorize a,b,c`。
 *
 * 返回 `undefined` 表示"用缺省规则"；返回集合表示"只有这些算已授权"。
 * 传 `*` 表示全部授权（包括有副作用的），用它来单独验证"副作用工具也能被调"。
 */
export function parseAuthorizeList(raw: string | undefined): Set<string> | null | undefined {
  if (raw === undefined || raw.trim() === "") return undefined;
  if (raw.trim() === "*") return null;
  return new Set(
    raw
      .split(",")
      .map((name) => name.trim())
      .filter((name) => name !== ""),
  );
}

export function createMcpBridge(options: McpBridgeOptions): MockToolHandler {
  const log = options.log ?? (() => undefined);
  const isAuthorized =
    options.isAuthorized ??
    ((_name: string, annotations: Record<string, unknown> | undefined) => annotations?.["destructiveHint"] !== true);

  let client: McpStreamableHttpClient | null = null;
  let connecting: Promise<void> | null = null;
  /** 握手时服务器自报的名字，目录里用它当 display server_name。 */
  let reportedName: string | null = null;

  async function ensureConnected(): Promise<McpStreamableHttpClient> {
    if (client !== null) return client;
    if (connecting === null) {
      const pending = (async () => {
        const created = new McpStreamableHttpClient({
          url: options.url,
          clientName: "mock-phone-bridge",
          timeoutMs: options.timeoutMs ?? 5_000,
        });
        const handshake = await created.connect();
        const name = handshake.serverInfo?.["name"];
        reportedName = typeof name === "string" && name !== "" ? name : null;
        client = created;
        log(
          `已连上第三方 MCP 服务器 ${options.url}（自报名字 ${reportedName ?? "（未提供）"}，` +
            `协议 ${handshake.protocolVersion}）`,
        );
      })();
      connecting = pending.catch((error: unknown) => {
        // 连不上不让这次的 Promise 永久留在缓存里：下一帧要能重试。
        connecting = null;
        throw error;
      });
    }
    await connecting;
    if (client === null) throw new Error("第三方 MCP 客户端没有建立");
    return client;
  }

  async function catalog(): Promise<Record<string, unknown>> {
    const live = await ensureConnected();
    const tools = await live.listTools();

    const authorized: CatalogToolEntry[] = [];
    let otherToolsCount = 0;
    for (const tool of tools) {
      const annotations = (tool.annotations ?? undefined) as Record<string, unknown> | undefined;
      if (!isAuthorized(tool.name, annotations)) {
        // 与 Android 的 HubCatalog 一致：未授权的工具**不进**目录数组，
        // 只让计数变大 —— 名字与描述都不外传。
        otherToolsCount += 1;
        continue;
      }
      const entry: CatalogToolEntry = {
        server_id: options.serverId,
        tool_name: tool.name,
        display_name: typeof tool.title === "string" && tool.title !== "" ? tool.title : tool.name,
        description: typeof tool.description === "string" ? tool.description : "",
        authorization_state: "authorized",
        availability: "online",
      };
      if (tool.inputSchema !== undefined) entry.input_schema = tool.inputSchema;
      authorized.push(entry);
    }

    log(`真实目录：${tools.length} 个工具，其中已授权 ${authorized.length} 个，未授权 ${otherToolsCount} 个`);
    return {
      servers: [
        {
          server_id: options.serverId,
          server_name: options.serverName ?? reportedName ?? options.serverId,
          availability: "online",
        },
      ],
      tools: authorized,
      other_tools_count: otherToolsCount,
    };
  }

  async function call(
    args: Record<string, unknown>,
  ): Promise<Record<string, unknown> | MockToolRefusal> {
    const live = await ensureConnected();
    const toolName = typeof args["tool_name"] === "string" ? args["tool_name"] : "";
    const inner = asRecord(args["arguments"]) ?? {};

    // 与 Android 侧 McpHubRouter 同样的两道门：服务器上没这个工具 → 手机层拒绝；
    // 有这个工具但用户没授权 → 手机层拒绝。**模型知道工具名也没用**。
    const known = await live.listTools();
    const tool = known.find((item) => item.name === toolName);
    if (tool === undefined) {
      return new MockToolRefusal("unsupported_tool", `第三方服务器上没有工具 ${toolName}`);
    }
    if (!isAuthorized(toolName, tool.annotations as Record<string, unknown> | undefined)) {
      return new MockToolRefusal("not_permitted", `第三方工具 ${toolName} 没有被授权`);
    }

    let result: Awaited<ReturnType<McpStreamableHttpClient["callTool"]>>;
    try {
      result = await live.callTool(toolName, inner);
    } catch (error) {
      // 第三方调用失败要变成 **ok:true + is_error:true**（工具级失败），
      // 而不是让 Hub 层报错 —— 这正是 Android 侧 "SERVER_REJECTED → tool_failed"
      // 那条映射的同一条约定：模型应该看得见第三方说了什么。
      const detail =
        error instanceof McpClientError ? `${error.code}：${error.message}` : String((error as Error).message ?? error);
      log(`第三方工具 ${toolName} 调用失败：${detail}`);
      return {
        server_id: options.serverId,
        tool_name: toolName,
        content: [{ type: "text", text: `第三方服务器调用失败（${detail}）` }],
        content_types: ["text"],
        is_error: true,
      };
    }

    const content = Array.isArray(result.content) ? result.content : [];
    const textParts = content
      .filter((item) => item["type"] === "text" && typeof item["text"] === "string")
      .map((item) => item["text"] as string);

    const payload: Record<string, unknown> = {
      server_id: options.serverId,
      tool_name: toolName,
      content: content.map((item) => ({ ...item })),
      content_types: [...new Set(content.map((item) => String(item["type"])))],
      is_error: result.isError === true,
    };
    if (typeof result.structuredContent === "object" && result.structuredContent !== null) {
      payload["structured_content"] = result.structuredContent;
    }
    log(`第三方工具 ${toolName} 返回 ${textParts.length} 段文本，is_error=${String(result.isError === true)}`);
    return payload;
  }

  return async (toolName, args) => {
    if (toolName === "hub_list_tools") return catalog();
    if (toolName === "hub_call_tool") return call(args);
    // 其它命令名不归这个桥管，交回替身自己的白名单。
    return null;
  };
}

function asRecord(value: unknown): Record<string, unknown> | null {
  if (typeof value !== "object" || value === null || Array.isArray(value)) return null;
  return value as Record<string, unknown>;
}

/** 供 `main.ts` 解析 `--mcp-authorize` 用，避免两边各写一份字符串常量。 */
export const MCP_AUTHORIZE_FLAG = AUTHORIZE_FLAG;
