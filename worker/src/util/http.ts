// ============================================================================
//  HTTP 小工具
// ============================================================================

const BASE_HEADERS: Record<string, string> = {
  // 明确禁止缓存：MCP 会话是有状态的，被中间层缓存会出非常难查的错。
  "Cache-Control": "no-store",
  // 不让浏览器把响应当 HTML 解析（我们不返回 HTML，但这是纵深防御）。
  "X-Content-Type-Options": "nosniff",
};

export function jsonResponse(body: unknown, init: ResponseInit = {}): Response {
  const headers = new Headers(BASE_HEADERS);
  headers.set("Content-Type", "application/json; charset=utf-8");
  for (const [k, v] of Object.entries(init.headers ?? {})) headers.set(k, v as string);
  return new Response(JSON.stringify(body), { ...init, headers });
}

export function textResponse(body: string, init: ResponseInit = {}): Response {
  const headers = new Headers(BASE_HEADERS);
  headers.set("Content-Type", "text/plain; charset=utf-8");
  for (const [k, v] of Object.entries(init.headers ?? {})) headers.set(k, v as string);
  return new Response(body, { ...init, headers });
}

export function emptyResponse(init: ResponseInit = {}): Response {
  const headers = new Headers(BASE_HEADERS);
  for (const [k, v] of Object.entries(init.headers ?? {})) headers.set(k, v as string);
  return new Response(null, { ...init, headers });
}

export function methodNotAllowed(allow: string): Response {
  return jsonResponse(
    { error: "method_not_allowed", allow },
    { status: 405, headers: { Allow: allow } },
  );
}

export function notFound(what: string): Response {
  return jsonResponse({ error: "not_found", message: `${what} 不存在` }, { status: 404 });
}
