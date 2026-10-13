// ============================================================================
//  `tools/mcp-streamable-http-client.mjs` 的类型声明
// ============================================================================
//
//  参考客户端刻意写成零依赖的 `.mjs`（它要能被 `node xxx.mjs` 直接跑，也要能被
//  Dart/Java 那边照着抄），所以这里补一份声明文件，让 TypeScript 侧的
//  `tools/mock-phone/mcp-bridge.ts` 能用它 —— 只声明**实际用到**的那部分，
//  声明与实现不一致时以 `.mjs` 为准（那份文件是行为规范）。
// ============================================================================

export type McpClientErrorCode =
  | "mcp_http_error"
  | "mcp_server_unavailable"
  | "mcp_protocol_error"
  | "mcp_timeout"
  | "mcp_result_too_large";

export declare class McpClientError extends Error {
  readonly code: McpClientErrorCode;
  readonly httpStatus?: number;
  readonly rpcCode?: number;
  readonly method?: string;
}

export interface McpRemoteTool {
  name: string;
  title?: string;
  description?: string;
  inputSchema?: Record<string, unknown>;
  annotations?: Record<string, unknown>;
  [key: string]: unknown;
}

export interface McpCallToolResult {
  content?: Array<Record<string, unknown>>;
  isError?: boolean;
  structuredContent?: Record<string, unknown>;
  [key: string]: unknown;
}

export interface McpStreamableHttpClientOptions {
  url: string;
  headers?: Record<string, string>;
  fetchImpl?: typeof fetch;
  protocolVersion?: string;
  timeoutMs?: number;
  maxResponseBytes?: number;
  clientName?: string;
}

export declare class McpStreamableHttpClient {
  constructor(options: McpStreamableHttpClientOptions);

  readonly url: string;
  sessionId: string | null;
  serverInfo: Record<string, unknown> | null;

  connect(): Promise<{ protocolVersion: string; serverInfo: Record<string, unknown> | null }>;
  listTools(): Promise<McpRemoteTool[]>;
  callTool(name: string, args: Record<string, unknown>): Promise<McpCallToolResult>;
  close(): Promise<void>;
}

export declare function extractSseData(text: string): string[];
export declare const MCP_CLIENT_ERROR_CODES: readonly McpClientErrorCode[];
