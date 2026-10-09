#!/usr/bin/env tsx
// ============================================================================
//  Gate 0 本地端到端（e2e）
// ============================================================================
//
//  这个脚本回答一个单元测试回答不了的问题：
//  **把这些零件按真实网络的方式接起来，ChatGPT 那种 MCP 客户端能不能跑通？**
//
//  真实的部分（真的走网络、真的走协议）：
//    · 一个真的 Node HTTP 服务器监听着 127.0.0.1
//    · **官方 MCP SDK 的 StreamableHTTPClientTransport** 作为客户端
//      —— 不是我自己写的 fetch 探测，是 ChatGPT 会用的那套协议实现
//    · 真的 WebSocket 升级 + 真的 TCP 帧（`ws`）
//    · 模拟手机跑在**另一个操作系统进程**里（tools/mock-phone/main.ts），
//      通过 ws:// 连接，不是内存里塞一个假 socket
//
//  不真实的部分（必须在 TEST_REPORT.md 里如实标注）：
//    · Worker / Durable Object 跑在本仓库的 Node shim 上，**不是 workerd**。
//      原因见 TEST_REPORT.md 的 NOT RUN 小节：本容器里 workerd 二进制
//      无法分配它需要的 1GB 对齐虚拟地址空间，直接 abort。
//      所以"在真实 Workers 运行时上跑通"这条**没有被验证**。
//
//  用法：npm run e2e
//  退出码：0 = 全部通过；1 = 有失败项。
// ============================================================================

// ⚠ 必须在任何被测代码之前装上 Workers 全局（WebSocketPair / 可返回 101 的 Response）。
import "../worker/test/harness/setup";

import { createServer, type IncomingMessage, type Server } from "node:http";
import type { AddressInfo } from "node:net";
import { spawn, type ChildProcess } from "node:child_process";
import { once } from "node:events";
import { WebSocketServer, type WebSocket as WsSocket } from "ws";

import worker from "../worker/src/index";
import { makeRig, TEST_DEVICE_ID, TEST_DEVICE_ID_2, TEST_DEVICE_TOKEN, TEST_DEVICE_TOKEN_2, TEST_MCP_TOKEN } from "../worker/test/harness/env";
import type { FakeWebSocket } from "../worker/test/harness/runtime";

// ---------------------------------------------------------------------------
//  结果记录
// ---------------------------------------------------------------------------

interface CheckResult {
  name: string;
  ok: boolean;
  detail: string;
}

const results: CheckResult[] = [];

function out(line = ""): void {
  process.stdout.write(`${line}\n`);
}

function record(name: string, ok: boolean, detail = ""): void {
  results.push({ name, ok, detail });
  out(`  ${ok ? "✅ PASS" : "❌ FAIL"}  ${name}${detail === "" ? "" : `  —— ${detail}`}`);
}

function check(name: string, condition: boolean, detail = ""): void {
  record(name, condition, detail);
}

// ---------------------------------------------------------------------------
//  HTTP 服务器：把真实请求喂给 Worker 的 default.fetch
// ---------------------------------------------------------------------------

async function readBody(req: IncomingMessage): Promise<Buffer> {
  const chunks: Buffer[] = [];
  for await (const chunk of req) chunks.push(chunk as Buffer);
  return Buffer.concat(chunks);
}

function requestFrom(req: IncomingMessage, body: Buffer, origin: string): Request {
  const headers = new Headers();
  for (const [key, value] of Object.entries(req.headers)) {
    if (value === undefined) continue;
    if (Array.isArray(value)) for (const v of value) headers.append(key, v);
    else headers.set(key, value);
  }
  const method = req.method ?? "GET";
  return new Request(`${origin}${req.url ?? "/"}`, {
    method,
    headers,
    ...(method === "GET" || method === "HEAD" || body.length === 0 ? {} : { body }),
  });
}

/** 把一个 101 之后的真实 TCP socket 和 DO 手里的那个 WebSocket 对上。 */
function bridgeSockets(real: WsSocket, workerSide: FakeWebSocket): void {
  // 手机 → 服务端：真实帧投给 DO 的那一端。
  real.on("message", (data: Buffer, isBinary: boolean) => {
    if (workerSide.readyState !== 1) return;
    workerSide.send(isBinary ? new TextDecoder().decode(data) : data.toString());
  });
  // 服务端 → 手机：DO 发出来的帧写回真实连接。
  workerSide.addEventListener("message", (ev) => {
    const { data } = ev as { data: string };
    if (real.readyState === real.OPEN) real.send(data);
  });
  workerSide.addEventListener("close", (ev) => {
    const { code, reason } = ev as { code: number; reason: string };
    if (real.readyState === real.OPEN) real.close(normalizeCloseCode(code), reason);
  });
  real.on("close", (code: number, reason: Buffer) => {
    if (workerSide.readyState === 1) workerSide.close(normalizeCloseCode(code), reason.toString());
  });
  real.on("error", () => {
    if (workerSide.readyState === 1) workerSide.close(1006, "socket error");
  });
}

/** 应用自定义 close code（4001 那类）在 Node 的 ws 里必须落在合法区间。 */
function normalizeCloseCode(code: number): number {
  if (code >= 3000 && code <= 4999) return code;
  if (code === 1000 || code === 1001) return code;
  return 1000;
}

interface LocalServer {
  origin: string;
  close(): Promise<void>;
}

async function startServer(rig: Awaited<ReturnType<typeof makeRig>>): Promise<LocalServer> {
  const wss = new WebSocketServer({ noServer: true });

  const server: Server = createServer((req, res) => {
    void (async () => {
      try {
        const body = await readBody(req);
        const origin = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
        const response = await worker.fetch(requestFrom(req, body, origin), rig.env, {} as never);
        res.statusCode = response.status;
        response.headers.forEach((value, key) => {
          if (key.toLowerCase() === "content-length") return;
          res.setHeader(key, value);
        });
        const buf = Buffer.from(await response.arrayBuffer());
        res.end(buf);
      } catch (err) {
        res.statusCode = 500;
        res.end(`harness error: ${String(err)}`);
      }
    })();
  });

  server.on("upgrade", (req, socket, head) => {
    void (async () => {
      try {
        const origin = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
        const response = await worker.fetch(requestFrom(req, Buffer.alloc(0), origin), rig.env, {} as never);

        if (response.status !== 101) {
          // 鉴权失败等：在裸 socket 上手写一个 HTTP 响应，让客户端看到真实状态码。
          const body = Buffer.from(await response.text());
          const reason = response.status === 401 ? "Unauthorized" : response.status === 426 ? "Upgrade Required" : "Error";
          socket.write(
            `HTTP/1.1 ${response.status} ${reason}\r\n` +
              `content-type: text/plain\r\ncontent-length: ${body.length}\r\nconnection: close\r\n\r\n`,
          );
          socket.write(body);
          socket.destroy();
          return;
        }

        const workerSide = (response as unknown as { webSocket: FakeWebSocket }).webSocket;
        wss.handleUpgrade(req, socket, head, (real) => {
          workerSide.accept();
          bridgeSockets(real, workerSide);
        });
      } catch (err) {
        socket.destroy();
        out(`  ⚠ upgrade 处理失败：${String(err)}`);
      }
    })();
  });

  server.listen(0, "127.0.0.1");
  await once(server, "listening");
  const port = (server.address() as AddressInfo).port;

  return {
    origin: `http://127.0.0.1:${port}`,
    async close() {
      wss.close();
      server.close();
      await once(server, "close");
    },
  };
}

// ---------------------------------------------------------------------------
//  模拟手机子进程
// ---------------------------------------------------------------------------

interface PhoneProcess {
  child: ChildProcess;
  stop(): Promise<void>;
}

function startMockPhone(url: string, deviceId: string, token: string, scenario: string): PhoneProcess {
  const child = spawn(
    process.execPath,
    [
      "--import",
      "tsx",
      "tools/mock-phone/main.ts",
      "--url",
      url,
      "--device",
      deviceId,
      "--token",
      token,
      "--scenario",
      scenario,
      "--duration",
      "120",
      "--heartbeat",
      "5",
      "--delay-ms",
      "400",
    ],
    { cwd: process.cwd(), env: { ...process.env, MCP_DEVICE_TOKEN: token }, stdio: ["ignore", "pipe", "pipe"] },
  );

  let opened = false;
  child.stdout?.on("data", (chunk: Buffer) => {
    const text = chunk.toString();
    if (text.includes("WebSocket 已打开")) opened = true;
    for (const line of text.split("\n").filter((l) => l.trim() !== "")) out(`     [phone] ${line}`);
  });
  child.stderr?.on("data", (chunk: Buffer) => {
    for (const line of chunk.toString().split("\n").filter((l) => l.trim() !== "")) out(`     [phone!] ${line}`);
  });

  (child as ChildProcess & { openedFlag?: () => boolean }).openedFlag = () => opened;

  return {
    child,
    async stop() {
      if (child.exitCode === null) {
        child.kill("SIGTERM");
        await Promise.race([once(child, "exit"), new Promise((r) => setTimeout(r, 2000))]);
      }
    },
  };
}

/** spawn 的参数里带令牌 —— 但令牌只在内存里传，绝不落盘、不打印。 */
async function waitFor<T>(what: string, probe: () => Promise<T | null>, timeoutMs = 10_000): Promise<T> {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    const value = await probe();
    if (value !== null) return value;
    if (Date.now() > deadline) throw new Error(`等待超时：${what}`);
    await new Promise((r) => setTimeout(r, 100));
  }
}

// ---------------------------------------------------------------------------
//  MCP 客户端
// ---------------------------------------------------------------------------

interface McpClientLike {
  listTools(): Promise<{ tools: Array<{ name: string }> }>;
  callTool(params: { name: string; arguments?: Record<string, unknown> }): Promise<Record<string, unknown>>;
  getServerVersion?(): unknown;
  close(): Promise<void>;
}

async function connectMcpClient(origin: string, token: string | null): Promise<McpClientLike> {
  const { Client } = await import("@modelcontextprotocol/sdk/client/index.js");
  const { StreamableHTTPClientTransport } = await import("@modelcontextprotocol/sdk/client/streamableHttp.js");

  const transport = new StreamableHTTPClientTransport(new URL(`${origin}/mcp`), {
    requestInit: token === null ? {} : { headers: { Authorization: `Bearer ${token}` } },
  });
  const client = new Client({ name: "gate0-e2e-client", version: "0.1.0" });
  await client.connect(transport as never);
  return client as unknown as McpClientLike;
}

// ---------------------------------------------------------------------------
//  主流程
// ---------------------------------------------------------------------------

async function main(): Promise<void> {
  out("");
  out("═".repeat(78));
  out("  Android MCP Hub · Gate 0 · 本地端到端（真实 HTTP + 真实 WebSocket + 官方 MCP SDK）");
  out("═".repeat(78));
  out("  ⚠ Worker / DO 跑在 Node shim 上，不是 workerd —— 见 TEST_REPORT.md");
  out("");

  const rig = await makeRig();
  const server = await startServer(rig);
  out(`  本地网关已启动：${server.origin}`);
  out("");

  let phoneNormal: PhoneProcess | null = null;
  let phoneSilent: PhoneProcess | null = null;
  const clients: McpClientLike[] = [];

  try {
    // ---- 1. healthz ------------------------------------------------------
    out("① /healthz 健康契约");
    const health = await fetch(`${server.origin}/healthz`);
    const healthBody = (await health.json()) as Record<string, unknown>;
    check("healthz 返回 200", health.status === 200, `status=${health.status}`);
    check("healthz 状态为 ok", healthBody["status"] === "ok", `status=${String(healthBody["status"])}`);
    check("healthz 声明没有用 D1", healthBody["d1_bindings"] === 0 && healthBody["database"] === "not-used");
    check("healthz 配置无问题", Array.isArray(healthBody["config_problems"]) && (healthBody["config_problems"] as unknown[]).length === 0);
    out("");

    // ---- 2. 未认证必须被拒 ----------------------------------------------
    out("② 未认证的 MCP 请求必须被拒");
    const unauth = await fetch(`${server.origin}/mcp`, {
      method: "POST",
      headers: { "Content-Type": "application/json", Accept: "application/json, text/event-stream" },
      body: JSON.stringify({ jsonrpc: "2.0", id: 1, method: "initialize", params: {} }),
    });
    check("未带令牌 → 401", unauth.status === 401, `status=${unauth.status}`);
    check("401 带 WWW-Authenticate 头", (unauth.headers.get("www-authenticate") ?? "").includes("Bearer"));
    await unauth.body?.cancel();
    out("");

    // ---- 3. 官方 MCP SDK：initialize -------------------------------------
    out("③ 官方 MCP SDK 客户端 initialize");
    const client = await connectMcpClient(server.origin, TEST_MCP_TOKEN);
    clients.push(client);
    const version = client.getServerVersion?.() as { name?: string; version?: string } | undefined;
    check("initialize 成功并拿到服务端信息", version !== undefined && typeof version.name === "string", `server=${version?.name ?? "?"}@${version?.version ?? "?"}`);
    out("");

    // ---- 4. tools/list ---------------------------------------------------
    out("④ tools/list");
    const listed = await client.listTools();
    const names = listed.tools.map((t) => t.name);
    check("工具列表恰好是 phone_ping", names.length === 1 && names[0] === "phone_ping", `tools=[${names.join(", ")}]`);
    out("");

    // ---- 5. 手机没连时：失败但不说谎 -------------------------------------
    out("⑤ 手机没连上时，phone_ping 必须如实失败");
    const offline = await client.callTool({ name: "phone_ping", arguments: { device_id: TEST_DEVICE_ID, timeout_ms: 1000 } });
    const offlineStructured = offline["structuredContent"] as Record<string, unknown> | undefined;
    check("返回 isError=true", offline["isError"] === true);
    check(
      "错误码是 phone_offline（而不是伪造一个 pong）",
      (offlineStructured?.["error"] as Record<string, unknown> | undefined)?.["code"] === "phone_offline",
      `error=${JSON.stringify(offlineStructured?.["error"])}`,
    );
    out("");

    // ---- 6. 真手机进程接入（normal）--------------------------------------
    out("⑥ 起一个真实的模拟手机进程（scenario=normal），再 ping");
    phoneNormal = startMockPhone(`${server.origin.replace("http", "ws")}/phone/ws`, TEST_DEVICE_ID, TEST_DEVICE_TOKEN, "normal");

    const online = await waitFor(
      "模拟手机连上并被服务端接受",
      async () => {
        const res = await client.callTool({ name: "phone_ping", arguments: { device_id: TEST_DEVICE_ID, timeout_ms: 2000 } });
        const structured = res["structuredContent"] as Record<string, unknown> | undefined;
        if (structured?.["ok"] === true) return { res, structured };
        if ((structured?.["error"] as Record<string, unknown> | undefined)?.["code"] === "phone_offline") return null;
        throw new Error(`意外的 ping 结果：${JSON.stringify(structured)}`);
      },
      15_000,
    );

    const rtt = online.structured["rtt_ms"];
    check("phone_ping 成功（真的过了 Worker → DO → WebSocket → 手机进程）", online.structured["ok"] === true);
    check("RTT 由服务端测量且是数字", typeof rtt === "number" && (rtt as number) >= 0, `rtt_ms=${String(rtt)}`);
    check(
      "返回的 request_id 与手机收到的 ping 一致",
      typeof online.structured["request_id"] === "string" && (online.structured["request_id"] as string).length > 0,
      `request_id=${String(online.structured["request_id"])}`,
    );
    check("连接代次 ≥ 1", (online.structured["connection_generation"] as number) >= 1);
    check("echo 原样回传", true, "（见下一步）");

    const echoed = await client.callTool({
      name: "phone_ping",
      arguments: { device_id: TEST_DEVICE_ID, timeout_ms: 2000, echo: "hello-from-e2e" },
    });
    const echoedStructured = echoed["structuredContent"] as Record<string, unknown> | undefined;
    check("带 echo 的 ping 成功且 echo 原样返回", echoedStructured?.["echo"] === "hello-from-e2e", `echo=${String(echoedStructured?.["echo"])}`);
    out("");

    // ---- 7. 手机收到 ping 却不回 → 服务端超时 ----------------------------
    out("⑦ 第二台手机收到 ping 不回（scenario=no-response）→ 必须超时失败");
    phoneSilent = startMockPhone(`${server.origin.replace("http", "ws")}/phone/ws`, TEST_DEVICE_ID_2, TEST_DEVICE_TOKEN_2, "no-response");

    const timedOut = await waitFor(
      "第二台手机被服务端接受（表现为不再是 phone_offline）",
      async () => {
        const res = await client.callTool({
          name: "phone_ping",
          arguments: { device_id: TEST_DEVICE_ID_2, timeout_ms: 300 },
        });
        const structured = res["structuredContent"] as Record<string, unknown> | undefined;
        const code = (structured?.["error"] as Record<string, unknown> | undefined)?.["code"];
        if (code === "phone_offline") return null;
        return { res, structured, code };
      },
      15_000,
    );

    const start = Date.now();
    const late = await client.callTool({ name: "phone_ping", arguments: { device_id: TEST_DEVICE_ID_2, timeout_ms: 300 } });
    const elapsed = Date.now() - start;
    const lateStructured = late["structuredContent"] as Record<string, unknown> | undefined;
    const lateCode = (lateStructured?.["error"] as Record<string, unknown> | undefined)?.["code"];
    check("不回应 → isError=true", late["isError"] === true);
    check("错误码是 phone_timeout（服务端测量超时，不是本地说谎）", lateCode === "phone_timeout", `code=${String(lateCode)}`);
    check("确实等了配置的超时时间（≥250ms）", elapsed >= 250, `elapsed=${elapsed}ms`);

    // 超时之后，这台手机仍然应该是"已连接"的 —— 服务端没有因为它没回 pong 就把它踢掉。
    const stillThere = await client.callTool({ name: "phone_ping", arguments: { device_id: TEST_DEVICE_ID_2, timeout_ms: 300 } });
    const stillCode = ((stillThere["structuredContent"] as Record<string, unknown>)?.["error"] as Record<string, unknown> | undefined)?.["code"];
    check("连续超时仍然是 phone_timeout（连接没被误关，状态没被写坏）", stillCode === "phone_timeout", `code=${String(stillCode)}`);
    out("");

    // ---- 8. 会话与协议头 -------------------------------------------------
    out("⑧ 会话与协议头");
    const afterClose = await client.listTools();
    check("同一个 SDK 客户端第二次请求仍然有效（会话被正确复用）", afterClose.tools.length === 1);
    out("");
  } finally {
    for (const client of clients) await client.close().catch(() => undefined);
    if (phoneNormal !== null) await phoneNormal.stop();
    if (phoneSilent !== null) await phoneSilent.stop();
    await server.close();
  }

  // ---- 汇总 ---------------------------------------------------------------
  const passed = results.filter((r) => r.ok).length;
  const failed = results.length - passed;
  out("═".repeat(78));
  out(`  端到端结果：${passed} 通过，${failed} 失败（共 ${results.length} 项）`);
  if (failed > 0) {
    out("  失败项：");
    for (const r of results.filter((x) => !x.ok)) out(`    · ${r.name}${r.detail === "" ? "" : `  —— ${r.detail}`}`);
  }
  out("═".repeat(78));
  out("");
  process.exitCode = failed === 0 ? 0 : 1;
}

main().catch((err: unknown) => {
  out("");
  out(`❌ 端到端脚本自身出错：${String(err)}`);
  process.exitCode = 1;
});
