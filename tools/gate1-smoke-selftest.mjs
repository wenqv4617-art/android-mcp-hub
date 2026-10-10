#!/usr/bin/env node
// ============================================================================
//  tools/gate1-smoke.mjs 的自检
//
//  为什么需要它：那个脚本的断言是照着 GATE1_DEPLOYMENT.md 的"期望"写的，
//  如果断言本身写错了（例如把字段名写错、或者条件下反），对着一个真部署跑
//  只会得到"看起来 PASS"的假绿。所以这里用一个**本地假服务器**回放正确的
//  响应形状，先证明脚本能判 PASS；再把响应改坏，证明它**真的会**报 FAIL。
//
//  用法：node tools/gate1-smoke-selftest.mjs
//  退出码：0 = 两种情形都符合预期；1 = 脚本自身有问题。
// ============================================================================

import { createServer } from "node:http";
import { spawn } from "node:child_process";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const HERE = dirname(fileURLToPath(import.meta.url));
const SMOKE = join(HERE, "gate1-smoke.mjs");
const HOST = "https://fixture.example.workers.dev";

/** 假服务器：按 mode 决定返回"正确"还是"某处坏掉"的响应。 */
function startFixture(mode) {
  const server = createServer((req, res) => {
    const send = (status, body, headers = {}) => {
      res.writeHead(status, { "content-type": "application/json", ...headers });
      res.end(JSON.stringify(body));
    };
    if (req.url === "/healthz") {
      return send(200, {
        status: mode === "degraded" ? "degraded" : "ok",
        service: "android-mcp-hub",
        version: "0.1.0-gate1",
        environment: "production",
        database: "not-used",
        d1_bindings: 0,
        schema_version: "gate0-do-v1",
        durable_object_bindings: ["PHONE_SESSION", "MCP_SESSION", "OAUTH_STORE"],
        config_problems: mode === "degraded" ? ["出了点问题"] : [],
        capabilities: {
          mcp_endpoint: "/mcp",
          phone_endpoint: "/phone/ws",
          tools: ["phone_ping"],
          server_initiated_notifications: false,
          oauth_authorization_server: true,
          oauth_metadata_endpoint: "/.well-known/oauth-authorization-server",
        },
        auth: {
          mcp_bearer_configured: false,
          device_credentials_configured: 1,
          device_tokens_configured: true,
          oauth_owner_secret_configured: true,
        },
        runtime: {},
      });
    }
    if (req.url === "/.well-known/oauth-protected-resource") {
      return send(200, {
        resource: `${HOST}/mcp`,
        authorization_servers: [HOST],
        scopes_supported: ["phone.ping"],
        bearer_methods_supported: ["header"],
        resource_name: "Android MCP Hub",
      });
    }
    if (req.url === "/.well-known/oauth-authorization-server") {
      return send(200, {
        issuer: HOST,
        authorization_endpoint: `${HOST}/oauth/authorize`,
        token_endpoint: `${HOST}/oauth/token`,
        registration_endpoint: `${HOST}/oauth/register`,
        revocation_endpoint: `${HOST}/oauth/revoke`,
        response_types_supported: ["code"],
        grant_types_supported: ["authorization_code", "refresh_token"],
        code_challenge_methods_supported: mode === "no-s256" ? ["plain"] : ["S256"],
        token_endpoint_auth_methods_supported: ["none"],
        scopes_supported: ["phone.ping"],
        authorization_response_iss_parameter_supported: true,
      });
    }
    if (req.url === "/mcp") {
      const challenge =
        mode === "no-resource-metadata"
          ? 'Bearer realm="android-mcp-hub", error="invalid_token"'
          : `Bearer realm="android-mcp-hub", error="invalid_token", resource_metadata="${HOST}/.well-known/oauth-protected-resource/mcp"`;
      return send(401, { error: "unauthorized" }, { "www-authenticate": challenge });
    }
    if (req.url === "/phone/ws") {
      if (!req.headers.upgrade) return send(426, { error: "upgrade_required" });
      return send(401, { error: "unauthorized" });
    }
    return send(404, { error: "not_found" });
  });
  return new Promise((resolve) => {
    server.listen(0, "127.0.0.1", () => resolve({ server, port: server.address().port }));
  });
}

// ⚠ 必须用**异步** spawn：spawnSync 会阻塞本进程的事件循环，
//   而假服务器就跑在本进程里 —— 那样它永远回不了响应，子进程只能一直等。
function runSmoke(base) {
  return new Promise((resolve) => {
    const child = spawn(process.execPath, [SMOKE, "--base", base], { stdio: ["ignore", "pipe", "pipe"] });
    let stdout = "";
    let stderr = "";
    child.stdout.on("data", (chunk) => { stdout += chunk; });
    child.stderr.on("data", (chunk) => { stderr += chunk; });
    child.on("close", (code) => resolve({ code, stdout, stderr }));
  });
}

const cases = [];
function record(name, ok, detail) {
  cases.push({ name, ok, detail });
  console.log(`${ok ? "PASS" : "FAIL"}  ${name}`);
  if (!ok) console.log(`        ↳ ${detail}`);
}

async function withFixture(mode, fn) {
  const { server, port } = await startFixture(mode);
  try {
    return await fn(`http://127.0.0.1:${port}`);
  } finally {
    server.close();
  }
}

// 情形 1：响应都对 → 脚本必须 PASS（exit 0），且 B2.5 的 S256 断言在列。
{
  const { code, stdout } = await withFixture("ok", runSmoke);
  if (process.argv.includes("--show")) {
    console.log("---- gate1-smoke.mjs 在「全对」的假服务器上的完整输出 ----");
    console.log(stdout.trimEnd());
    console.log("---- 以上为样例输出，下面才是自检结果 ----\n");
  }
  record("正确响应 → 退出码 0", code === 0, `exit=${code}\n${stdout}`);
  record("正确响应 → 全部 PASS、没有 FAIL", stdout.includes("0 FAIL") && !stdout.includes("FAIL  "), stdout.split("\n").slice(-3).join(" | "));
  record("正确响应 → 确实跑了 S256 那一条", stdout.includes("S256") && stdout.includes("PASS  B2.5"), "输出里没看到 B2.5 的 S256 断言");
}

// 情形 2：元数据里没有 S256 → 必须 FAIL（这正是 ChatGPT 拒绝接入的原因）
{
  const { code, stdout } = await withFixture("no-s256", runSmoke);
  record("缺 S256 → 退出码 1", code === 1, `exit=${code}`);
  record("缺 S256 → 报 FAIL 且指到 B2.5", stdout.includes("FAIL  B2.5"), stdout.split("\n").filter((l) => l.startsWith("FAIL")).join(" | "));
}

// 情形 3：401 挑战里没有 resource_metadata → 必须 FAIL（这是 Gate 0.3 的 K4 反向后的要求）
{
  const { code, stdout } = await withFixture("no-resource-metadata", runSmoke);
  record("401 缺 resource_metadata → 退出码 1", code === 1, `exit=${code}`);
  record("401 缺 resource_metadata → 报 FAIL 且指到 B3.2", stdout.includes("FAIL  B3.2"), stdout.split("\n").filter((l) => l.startsWith("FAIL")).join(" | "));
}

// 情形 4：healthz 报 degraded → 必须 FAIL
{
  const { code, stdout } = await withFixture("degraded", runSmoke);
  record("healthz degraded → 退出码 1", code === 1, `exit=${code}`);
  record("healthz degraded → 报 FAIL 且指到 B1.2", stdout.includes("FAIL  B1.2"), stdout.split("\n").filter((l) => l.startsWith("FAIL")).join(" | "));
}

// 情形 5：目标不可达 → 必须是 exit 2（用法/网络错误），不是"看起来通过了"
{
  const result = await runSmoke("http://127.0.0.1:1");
  record("目标不可达 → 退出码 2", result.code === 2, `exit=${result.code} stderr=${result.stderr.trim().split("\n")[0]}`);
}

const failed = cases.filter((c) => !c.ok);
console.log(`\n自检合计：${cases.length - failed.length} PASS / ${failed.length} FAIL`);
process.exit(failed.length === 0 ? 0 : 1);
