import { createServer, type Server as HttpServer, type IncomingMessage, type ServerResponse } from "node:http";
import { Server } from "@modelcontextprotocol/sdk/server/index.js";
import { StreamableHTTPServerTransport } from "@modelcontextprotocol/sdk/server/streamableHttp.js";
import { SSEServerTransport } from "@modelcontextprotocol/sdk/server/sse.js";
import { ListToolsRequestSchema, CallToolRequestSchema, type Tool } from "@modelcontextprotocol/sdk/types.js";
import worker from "../src/index";
import type { Env } from "../src/env";

export async function listen(server: HttpServer): Promise<string> {
  await new Promise<void>(resolve => server.listen(0, "127.0.0.1", resolve));
  return `http://127.0.0.1:${(server.address() as { port: number }).port}`;
}
export async function close(server: HttpServer): Promise<void> {
  server.closeAllConnections();
  await new Promise<void>(resolve => server.close(() => resolve()));
}
export async function bodyOf(req: IncomingMessage): Promise<string> {
  let body = ""; for await (const part of req) body += part.toString(); return body;
}
export async function serveWorker(env: Env): Promise<{ url: string; server: HttpServer }> {
  const server = createServer(async (req, res) => {
    try {
      const body = await bodyOf(req);
      const headers = new Headers();
      for (const [name, value] of Object.entries(req.headers)) if (value !== undefined) headers.set(name, Array.isArray(value) ? value.join(",") : value);
      const response = await worker.fetch(new Request(`http://${req.headers.host}${req.url}`, { method: req.method, headers, ...(body ? { body } : {}) }), env, { waitUntil() {} } as never);
      res.writeHead(response.status, Object.fromEntries(response.headers)); res.end(await response.text());
    } catch { res.writeHead(500); res.end(); }
  });
  return { url: await listen(server), server };
}

/** Real official SDK server fixture, supporting both transports. */
export async function createUpstream(label = "alpha") {
  const calls: { name: string; arguments: unknown; authorization?: string }[] = [];
  const headers: (string | undefined)[] = [];
  let description = "Stable tool definition";
  let behavior: "normal" | "redirect" | "disconnect" | "large" | "credential" | "hang" = "normal";
  let paginated = false;
  const legacy = new Map<string, SSEServerTransport>();
  const sessions = new Set<Server>();
  const tools = (): Tool[] => [
    { name: "status", description, inputSchema: { type: "object", properties: {}, additionalProperties: false } },
    { name: "sum", description: "Sum two numbers", inputSchema: { type: "object", properties: { a: { type: "number" }, b: { type: "number" } }, required: ["a", "b"], additionalProperties: false } },
    ...["forbidden", "write", "failure", "slow"].map(name => ({ name, inputSchema: { type: "object" as const, properties: {} } })),
  ];
  const sdk = (auth?: string) => {
    const s = new Server({ name: `test-${label}`, version: "1" }, { capabilities: { tools: {} } });
    sessions.add(s);
    s.setRequestHandler(ListToolsRequestSchema, async request => paginated ? (request.params?.cursor === "rest" ? { tools: tools().slice(3) } : { tools: tools().slice(0, 3), nextCursor: "rest" }) : { tools: tools() });
    s.setRequestHandler(CallToolRequestSchema, async request => {
      calls.push({ name: request.params.name, arguments: request.params.arguments, authorization: auth });
      if (request.params.name === "slow") await new Promise(r => setTimeout(r, 600));
      if (request.params.name === "failure") return { isError: true, content: [{ type: "text", text: "Bearer fixture-upstream-secret hidden internal error" }] };
      const args = request.params.arguments ?? {};
      return { content: [{ type: "text", text: behavior === "credential" ? "fixture-upstream-secret" : behavior === "large" ? "x".repeat(1100000) : request.params.name === "sum" ? String(Number(args.a) + Number(args.b)) : label }] };
    });
    return s;
  };
  const server = createServer(async (req: IncomingMessage, res: ServerResponse) => {
    headers.push(req.headers.authorization);
    if (behavior === "redirect") { res.writeHead(307, { Location: "http://169.254.169.254/latest/meta-data" }); res.end(); return; }
    if (behavior === "hang") return;
    if (behavior === "disconnect") { req.socket.destroy(); return; }
    try {
      if (req.url === "/sse") {
        const transport = new SSEServerTransport("/messages", res as never);
        legacy.set(transport.sessionId, transport);
        await sdk(req.headers.authorization).connect(transport);
        return;
      }
      if (req.url?.startsWith("/messages?")) {
        const transport = legacy.get(new URL(req.url, "http://localhost").searchParams.get("sessionId") ?? "");
        if (!transport) { res.writeHead(404); res.end(); return; }
        await transport.handlePostMessage(req as never, res as never, JSON.parse(await bodyOf(req)));
        return;
      }
      if (req.url === "/mcp") {
        if (req.method === "GET") { res.writeHead(405); res.end(); return; }
        const transport = new StreamableHTTPServerTransport({ sessionIdGenerator: undefined, enableJsonResponse: true });
        const s = sdk(req.headers.authorization);
        res.on("close", () => { void transport.close(); void s.close(); sessions.delete(s); });
        await s.connect(transport);
        await transport.handleRequest(req as never, res as never, JSON.parse(await bodyOf(req)));
        return;
      }
      res.writeHead(404); res.end();
    } catch { if (!res.headersSent) res.writeHead(500); res.end(); }
  });
  const url = await listen(server);
  return { url, calls, headers, tools, setPagination: (v: boolean) => { paginated = v; }, setDescription: (s: string) => { description = s; }, setBehavior: (s: typeof behavior) => { behavior = s; },
    async stop() { for (const s of sessions) await s.close(); await close(server); } };
}
