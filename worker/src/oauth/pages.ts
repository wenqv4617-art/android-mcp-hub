// ============================================================================
//  同意页 HTML
// ============================================================================
//
//  ── 这一页为什么必须存在 ──────────────────────────────────────────────────
//
//  任务书要求 OAuth 必须"用户明确授权"。没有同意页的 OAuth 就等于
//  "任何人拿到 client_id 就能换到调用手机的工具"，那不叫授权，叫开放代理。
//
//  ── 三件刻意为之的事 ──────────────────────────────────────────────────────
//
//  1. 表单里**只有** consent_id / owner_secret / decision 三个字段。
//     授权请求的全部参数（client、redirect_uri、PKCE challenge、state）都留在
//     服务端（OAuthStore 的 consent 记录）里。这样浏览器端**无法篡改**它们 ——
//     隐藏字段方案看似简单，但篡改一个重定向地址就能把授权码送到别处去。
//
//  2. consent_id 是 24 字节随机数，且一次性。这就是本页的 CSRF 防护：
//     攻击者无法猜到这个 id，也就无法替你提交"批准"。
//     刻意**不**用 Origin/Referer 做判断 —— 那些头是浏览器给的、也可能被绕过。
//
//  3. 页面里没有任何脚本、没有外部资源（CSP `default-src 'none'`）。
//     一个纯静态表单是最小攻击面：没有 XSS 可注入的地方，也没有第三方请求
//     会把"某个运维正在给自己授权"这件事泄漏出去。
//
//  所有插入 HTML 的动态值都过 `escapeHtml`。这里唯一的动态值来自客户端
//  （client_name / redirect_uri / scope），所以这一步不是形式主义。
// ============================================================================

export interface ConsentPageInput {
  consentId: string;
  clientId: string;
  clientName: string | null;
  redirectUri: string;
  scope: string;
  issuer: string;
}

const STYLE = `
  :root { color-scheme: light dark; }
  * { box-sizing: border-box; }
  body {
    margin: 0; padding: 24px; min-height: 100vh;
    display: flex; align-items: center; justify-content: center;
    font: 15px/1.6 -apple-system, BlinkMacSystemFont, "Segoe UI", "Noto Sans SC", sans-serif;
    background: #0f1115; color: #e8eaed;
  }
  main { width: 100%; max-width: 520px; background: #171a21; border: 1px solid #2a2f3a; border-radius: 14px; padding: 26px; }
  h1 { margin: 0 0 4px; font-size: 19px; }
  p.sub { margin: 0 0 20px; color: #9aa4b2; font-size: 13px; }
  dl { margin: 0 0 20px; padding: 14px; background: #10131a; border-radius: 10px; border: 1px solid #232833; }
  dt { color: #9aa4b2; font-size: 12px; margin-top: 10px; }
  dt:first-child { margin-top: 0; }
  dd { margin: 2px 0 0; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 12.5px; word-break: break-all; }
  label { display: block; font-size: 13px; color: #c3cad6; margin-bottom: 6px; }
  input[type=password] {
    width: 100%; padding: 10px 12px; border-radius: 9px; border: 1px solid #333a47;
    background: #0d1016; color: #e8eaed; font-size: 14px;
  }
  .row { display: flex; gap: 10px; margin-top: 18px; }
  button { flex: 1; padding: 11px 14px; border-radius: 9px; border: 1px solid #333a47; font-size: 14px; cursor: pointer; }
  button.approve { background: #2f6bff; border-color: #2f6bff; color: #fff; font-weight: 600; }
  button.deny { background: transparent; color: #c3cad6; }
  footer { margin-top: 18px; color: #6f7885; font-size: 11.5px; line-height: 1.5; }
  .warn { margin: 0 0 18px; padding: 10px 12px; border-radius: 9px; background: #2a1e12; border: 1px solid #4a3418; color: #ffce8a; font-size: 12.5px; }
`;

export function renderConsentPage(input: ConsentPageInput): string {
  const shownName = input.clientName ?? input.clientId;
  return `<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>授权 Android MCP Hub</title>
<style>${STYLE}</style>
</head>
<body>
<main>
  <h1>授权一个 MCP 客户端</h1>
  <p class="sub">${escapeHtml(input.issuer)} · Android MCP Hub</p>

  <p class="warn">批准之后，这个客户端将能够调用 <strong>phone_ping</strong>，把你的手机当作一次网络往返的目标。</p>

  <dl>
    <dt>客户端</dt>
    <dd>${escapeHtml(shownName)}</dd>
    <dt>客户端 ID</dt>
    <dd>${escapeHtml(input.clientId)}</dd>
    <dt>授权后重定向到</dt>
    <dd>${escapeHtml(input.redirectUri)}</dd>
    <dt>申请的权限范围</dt>
    <dd>${escapeHtml(input.scope === "" ? "(未声明)" : input.scope)}</dd>
  </dl>

  <form method="post" action="/oauth/authorize" autocomplete="off">
    <input type="hidden" name="consent_id" value="${escapeHtml(input.consentId)}">
    <label for="owner_secret">服务拥有者口令（OAUTH_OWNER_SECRET）</label>
    <input id="owner_secret" name="owner_secret" type="password" required autocomplete="current-password" autofocus>
    <div class="row">
      <button class="deny" type="submit" name="decision" value="deny">拒绝</button>
      <button class="approve" type="submit" name="decision" value="approve">批准</button>
    </div>
  </form>

  <footer>
    这一页没有任何脚本，也不会向第三方发出请求。<br>
    如果你没有在 ChatGPT 里主动添加过这个 MCP 服务器，请点"拒绝"。
  </footer>
</main>
</body>
</html>`;
}

export function renderErrorPage(title: string, message: string): string {
  return `<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${escapeHtml(title)}</title>
<style>${STYLE}</style>
</head>
<body>
<main>
  <h1>${escapeHtml(title)}</h1>
  <p class="sub">${escapeHtml(message)}</p>
  <footer>Android MCP Hub · 授权服务器</footer>
</main>
</body>
</html>`;
}

/**
 * HTML 转义。
 *
 * 覆盖五个字符就够（`&` 必须最先替换，否则会把后面替换出来的实体再一次转义）。
 * 引号也转 —— 动态值既出现在文本节点里，也出现在 `value="…"` 属性里。
 */
export function escapeHtml(value: string): string {
  return value
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#39;");
}

/** 同意页与错误页的响应头。CSP 只允许内联样式，不允许任何外部资源与脚本。 */
export const HTML_HEADERS: Record<string, string> = {
  "Content-Type": "text/html; charset=utf-8",
  "Cache-Control": "no-store",
  "X-Content-Type-Options": "nosniff",
  "Content-Security-Policy":
    "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'",
  "Referrer-Policy": "no-referrer",
};
