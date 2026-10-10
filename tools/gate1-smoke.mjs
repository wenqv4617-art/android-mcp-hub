#!/usr/bin/env node
// ============================================================================
//  Gate 1 部署后自检（阶段 B 的 B1–B4，一条命令）
//
//  为什么要有这个脚本：GATE1_DEPLOYMENT.md 第 6 节的 B1–B4 是一串 curl，
//  "期望"那一列是给人看的散文 —— 有没有真的对上，只有跑了才知道。这里把
//  那些期望写成**可执行的断言**，跑完给一张 PASS/FAIL 表。
//
//  用法（不产生任何云端资源，只是读）：
//    node tools/gate1-smoke.mjs --base https://android-mcp-hub.<你的子域>.workers.dev
//
//  ⚠ 它**不**打印任何令牌，也**不**需要你提供令牌：
//     凡是需要"错误凭据"的地方用的都是写死的假串；
//     凡是需要"正确凭据"的地方（B5 之后的真机项）它一概不做，
//     因为那需要你的秘密，而且属于阶段 B 后半段（要真机在线）。
//
//  退出码：0 = 全部 PASS；1 = 有 FAIL；2 = 用法/网络错误。
// ============================================================================

const USAGE = "用法：node tools/gate1-smoke.mjs --base https://<你的 Worker 主机>";
const FAKE_DEVICE_TOKEN = "gate1-smoke-wrong-device-token-not-a-secret";
const FAKE_BEARER = "gate1-smoke-wrong-bearer-not-a-secret";

function parseArgs(argv) {
  let base = null;
  for (let i = 0; i < argv.length; i++) {
    if (argv[i] === "--base") base = argv[++i];
    else if (argv[i] === "--help" || argv[i] === "-h") return { help: true };
    else throw new Error(`未知参数：${argv[i]}`);
  }
  return { base };
}

/** 断言小工具：把一个要求写成 (bool, 说明)。condition === null 表示"这次没验"，记作 SKIP。 */
function check(id, name, condition, detail) {
  return { id, name, ok: condition === null ? null : Boolean(condition), detail };
}

async function fetchSafe(url, init) {
  try {
    return await fetch(url, init);
  } catch (err) {
    // 只留类别，不留 message（message 可能带 URL 与查询串）。
    throw new Error(`请求失败（${err && err.name ? err.name : "Error"}）：${url.startsWith("http") ? new URL(url).origin : url}`);
  }
}

async function readJson(response) {
  const text = await response.text();
  try {
    return { json: JSON.parse(text), text };
  } catch {
    return { json: null, text };
  }
}

// ---------------------------------------------------------------------------
//  B1 · Worker 正常 + 配置无问题 + 三个 DO 都在 + 没有 D1 + OAuth 已启用
// ---------------------------------------------------------------------------
async function b1(base, out) {
  const response = await fetchSafe(`${base}/healthz`, { headers: { accept: "application/json" } });
  const { json } = await readJson(response);
  out.push(check("B1.1", "GET /healthz 回 200", response.status === 200, `status=${response.status}`));
  if (json === null) {
    out.push(check("B1.2", "/healthz 是 JSON", false, "响应不是合法 JSON"));
    return;
  }
  out.push(check("B1.2", "status=ok（config_problems 为空）", json.status === "ok", `status=${json.status} problems=${JSON.stringify(json.config_problems)}`));
  out.push(check("B1.3", "database=not-used 且 d1_bindings=0（没有数据库）", json.database === "not-used" && json.d1_bindings === 0, `database=${json.database} d1_bindings=${json.d1_bindings}`));
  const bindings = Array.isArray(json.durable_object_bindings) ? json.durable_object_bindings.slice().sort() : [];
  const want = ["MCP_SESSION", "OAUTH_STORE", "PHONE_SESSION"];
  out.push(check("B1.4", "三个 Durable Object 绑定都在", JSON.stringify(bindings) === JSON.stringify(want), `实际=${JSON.stringify(json.durable_object_bindings)}`));
  out.push(check("B1.5", "capabilities.oauth_authorization_server=true", json.capabilities?.oauth_authorization_server === true, `=${json.capabilities?.oauth_authorization_server}`));
  out.push(check("B1.6", "只暴露 phone_ping 这一个工具", JSON.stringify(json.capabilities?.tools) === JSON.stringify(["phone_ping"]), `tools=${JSON.stringify(json.capabilities?.tools)}`));
  out.push(check("B1.7", "oauth_owner_secret_configured=true（OAuth 面没被 404 掉）", json.auth?.oauth_owner_secret_configured === true, `=${json.auth?.oauth_owner_secret_configured}`));
  out.push(check("B1.8", "MCP 静态 Bearer **没有**配置（G1-K4：真实部署不要配）", json.auth?.mcp_bearer_configured === false, `mcp_bearer_configured=${json.auth?.mcp_bearer_configured}`));
}

// ---------------------------------------------------------------------------
//  B2 · 发现路由（ChatGPT 接入的前提）
// ---------------------------------------------------------------------------
async function b2(base, out) {
  const pr = await fetchSafe(`${base}/.well-known/oauth-protected-resource`, { headers: { accept: "application/json" } });
  const { json: prJson } = await readJson(pr);
  out.push(check("B2.1", "GET /.well-known/oauth-protected-resource 回 200", pr.status === 200, `status=${pr.status}`));
  out.push(check("B2.2", "resource 指向 <host>/mcp", typeof prJson?.resource === "string" && prJson.resource.endsWith("/mcp"), `resource=${prJson?.resource}`));
  out.push(check("B2.3", "authorization_servers 非空", Array.isArray(prJson?.authorization_servers) && prJson.authorization_servers.length > 0, `=${JSON.stringify(prJson?.authorization_servers)}`));

  const as = await fetchSafe(`${base}/.well-known/oauth-authorization-server`, { headers: { accept: "application/json" } });
  const { json: asJson } = await readJson(as);
  out.push(check("B2.4", "GET /.well-known/oauth-authorization-server 回 200", as.status === 200, `status=${as.status}`));
  const methods = Array.isArray(asJson?.code_challenge_methods_supported) ? asJson.code_challenge_methods_supported : [];
  out.push(check("B2.5", "★ code_challenge_methods_supported 含 S256（缺了 ChatGPT 直接拒绝接入）", methods.includes("S256"), `=${JSON.stringify(methods)}`));
  for (const key of ["authorization_endpoint", "token_endpoint", "registration_endpoint", "revocation_endpoint"]) {
    out.push(check("B2.6", `元数据含 ${key}`, typeof asJson?.[key] === "string" && asJson[key].length > 0, `=${asJson?.[key]}`));
  }
  out.push(check("B2.7", "注册路径是 /oauth/register", typeof asJson?.registration_endpoint === "string" && asJson.registration_endpoint.endsWith("/oauth/register"), `=${asJson?.registration_endpoint}`));
}

// ---------------------------------------------------------------------------
//  B3 · /mcp 的鉴权：没有凭据必须 401，而且挑战里要能指到资源元数据
// ---------------------------------------------------------------------------
async function b3(base, out) {
  const noAuth = await fetchSafe(`${base}/mcp`, {
    method: "POST",
    headers: { "content-type": "application/json", accept: "application/json, text/event-stream" },
    body: JSON.stringify({ jsonrpc: "2.0", id: 1, method: "initialize", params: {} }),
  });
  const challenge = noAuth.headers.get("www-authenticate") ?? "";
  out.push(check("B3.1", "未认证 POST /mcp 回 401", noAuth.status === 401, `status=${noAuth.status}`));
  out.push(check("B3.2", "★ 401 带 resource_metadata 指针（Gate 0.3 的 K4 已反向）", challenge.includes("resource_metadata="), `challenge=${challenge}`));
  out.push(check("B3.3", "resource_metadata 指向 /.well-known/oauth-protected-resource", challenge.includes("/.well-known/oauth-protected-resource"), `challenge=${challenge}`));

  const bogus = await fetchSafe(`${base}/mcp`, {
    method: "POST",
    headers: { "content-type": "application/json", accept: "application/json, text/event-stream", authorization: `Bearer ${FAKE_BEARER}` },
    body: JSON.stringify({ jsonrpc: "2.0", id: 1, method: "initialize", params: {} }),
  });
  out.push(check("B3.4", "错误 Bearer 也必须 401（不是 200，也不是 500）", bogus.status === 401, `status=${bogus.status}`));
}

// ---------------------------------------------------------------------------
//  B4 · /phone/ws 的设备鉴权
// ---------------------------------------------------------------------------
async function b4(base, out) {
  // 先证明"路径存在"：不带 Upgrade 头应当是 426，而不是 404。
  const noUpgrade = await fetchSafe(`${base}/phone/ws`, { headers: { "x-device-id": "smoke-device", authorization: `Bearer ${FAKE_DEVICE_TOKEN}` } });
  out.push(check("B4.1", "非升级请求回 426（说明路由在，不是 404）", noUpgrade.status === 426, `status=${noUpgrade.status}`));

  // 错误设备令牌必须 401。Upgrade 头在 fetch 里可能被运行时丢掉，
  // 那时连 426 都拿不到 —— 如实标 SKIP 并给出等价的 curl，不假装通过。
  let withUpgrade;
  try {
    withUpgrade = await fetchSafe(`${base}/phone/ws`, {
      headers: { upgrade: "websocket", connection: "Upgrade", "x-device-id": "smoke-device", authorization: `Bearer ${FAKE_DEVICE_TOKEN}` },
    });
  } catch (err) {
    out.push(check("B4.2", "错误设备令牌回 401", null, `SKIP：本运行时不让带 Upgrade 头（${err.message}）。请用：curl -si "${base}/phone/ws" -H 'Upgrade: websocket' -H 'X-Device-Id: smoke-device' -H 'Authorization: Bearer <错令牌>'`));
    return;
  }
  const status = withUpgrade.status;
  out.push(check("B4.2", "错误设备令牌回 401", status === 401, status === 426 ? `status=426（Upgrade 头被运行时丢掉了，等价于 B4.1；请用文档里的 curl 复核）` : `status=${status}`));
}

// ---------------------------------------------------------------------------
//  B7' · 没有手机在线时，绝不伪造成功
// ---------------------------------------------------------------------------
async function b7(base, out) {
  const response = await fetchSafe(`${base}/mcp`, {
    method: "POST",
    headers: { "content-type": "application/json", accept: "application/json, text/event-stream", authorization: `Bearer ${FAKE_BEARER}` },
    body: JSON.stringify({ jsonrpc: "2.0", id: 1, method: "tools/call", params: { name: "phone_ping", arguments: {} } }),
  });
  out.push(check("B7'", "用假凭据调 phone_ping 拿不到成功（只会 401）", response.status === 401, `status=${response.status}（真实调用要看阶段 B 后半段，需要正确 OAuth 令牌 + 手机在线）`));
}

// ---------------------------------------------------------------------------

async function main() {
  let args;
  try {
    args = parseArgs(process.argv.slice(2));
  } catch (err) {
    console.error(err.message);
    console.error(USAGE);
    process.exit(2);
  }
  if (args.help) {
    console.log(USAGE);
    process.exit(0);
  }
  if (!args.base) {
    console.error(USAGE);
    process.exit(2);
  }
  let base;
  try {
    base = new URL(args.base).origin;
  } catch {
    console.error(`--base 不是合法 URL：${args.base}`);
    process.exit(2);
  }

  const out = [];
  console.log(`Gate 1 部署后自检 · ${base}\n（只读：本脚本不发任何会改变状态的东西，也不需要你的任何秘密）\n`);
  try {
    await b1(base, out);
    await b2(base, out);
    await b3(base, out);
    await b4(base, out);
    await b7(base, out);
  } catch (err) {
    console.error(`\n网络或响应错误，自检中止：${err.message}`);
    process.exit(2);
  }

  let failed = 0;
  let skipped = 0;
  for (const row of out) {
    const mark = row.ok === null ? "SKIP" : row.ok ? "PASS" : "FAIL";
    if (row.ok === false) failed += 1;
    if (row.ok === null) skipped += 1;
    console.log(`${mark}  ${row.id.padEnd(6)} ${row.name}`);
    if (row.ok !== true) console.log(`${" ".repeat(6)}       ↳ ${row.detail}`);
  }
  const passed = out.filter((r) => r.ok === true).length;
  console.log(`\n合计：${passed} PASS / ${failed} FAIL / ${skipped} SKIP`);
  if (failed > 0) {
    console.log("有 FAIL —— 先按 GATE1_DEPLOYMENT.md 第 6 节逐条查，再看 GATE1_CHATGPT_SETUP.md 第 6 节的排障表。");
    process.exit(1);
  }
  console.log("B1–B4 这一段的期望都对上了。接下来是阶段 B 后半段：手机 App 连上（见 GATE1_DEPLOYMENT §5.1），");
  console.log("然后用真实 OAuth 令牌做一次 phone_ping —— 那一步不能靠脚本，必须真机在线。");
  process.exit(0);
}

await main();
