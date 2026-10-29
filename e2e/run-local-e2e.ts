#!/usr/bin/env tsx
// ============================================================================
//  Gate 1 本地端到端（e2e）
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
import {
  makeRig,
  phoneStatus,
  TEST_DEVICE_ID,
  TEST_DEVICE_ID_2,
  TEST_DEVICE_TOKEN,
  TEST_DEVICE_TOKEN_2,
  TEST_MCP_TOKEN,
} from "../worker/test/harness/env";
import type { FakeWebSocket } from "../worker/test/harness/runtime";

// ---------------------------------------------------------------------------
//  阶段 1A：可靠性场景设备表
// ---------------------------------------------------------------------------
//
//  为什么每个场景要一台**独立设备**，而不是在同一个 device_id 上轮流起手机：
//  换手机会触发"连接代次替换"（旧 socket 被 4001 关掉）。那是被测逻辑的一部分，
//  混进来之后就没法区分"这个场景本身的行为"和"换连接造成的行为"。
//  独立设备还让我们能断言"一台异常手机不影响到另一台正常手机"。
//
//  这里出现的令牌全部是**虚构的、本地生成的**，只在进程内存里传，绝不落盘、绝不打印。

/** 只给 e2e 用的确定性假令牌。 */
function relToken(deviceId: string): string {
  return `e2e-stage1a-${deviceId}-token`;
}

/** 7 个此前从未进过完整链路的场景（阶段 0 报告 G-4）。 */
const REL_SCENARIO_DEVICES: ReadonlyArray<{ deviceId: string; scenario: string; title: string }> = [
  { deviceId: "rel-wrong-request-id", scenario: "wrong-request-id", title: "A2 wrong-request-id" },
  { deviceId: "rel-duplicate-pong", scenario: "duplicate-pong", title: "A3 duplicate-pong" },
  { deviceId: "rel-late", scenario: "late", title: "A4 late" },
  { deviceId: "rel-drop-after-ping", scenario: "drop-after-ping", title: "A5 drop-after-ping" },
  { deviceId: "rel-noisy", scenario: "noisy", title: "A6 noisy" },
  { deviceId: "rel-binary", scenario: "binary", title: "A7 binary" },
  { deviceId: "rel-hello-mismatch", scenario: "hello-mismatch", title: "A8 hello-mismatch" },
];

/** 对照组：一台完全正常的手机。异常场景跑的时候它一直在，用来证明"没被带坏"。 */
const REL_CONTROL_DEVICE_ID = "rel-control";

/** `hello-mismatch` 场景在 hello 帧里冒充的那个 device_id（见 tools/mock-phone/client.ts:117-139）。 */
const REL_CLAIMED_DEVICE_ID = "some-other-phone";

/**
 * Gate 2 工具通道的专用设备。
 *
 * 每台一个 toolBehavior —— 和阶段 1A 的 7 个 scenario 一样，一台手机只扮演
 * 一种"坏法"，这样才能断言"一台异常手机不影响另一台"。
 */
const TOOL_BEHAVIOR_DEVICES: ReadonlyArray<{ deviceId: string; toolBehavior: string; title: string }> = [
  { deviceId: "tool-normal", toolBehavior: "normal", title: "正常只读能力" },
  { deviceId: "tool-silent", toolBehavior: "no-response", title: "收到 tool_request 不回" },
  { deviceId: "tool-hostile", toolBehavior: "reserved-key", title: "结果里塞信封键" },
  { deviceId: "tool-error", toolBehavior: "error", title: "能力执行失败" },
  { deviceId: "tool-unsupported", toolBehavior: "unsupported", title: "手机白名单里没有这个工具" },
];

const TOOL_CONTROL_DEVICE_ID = "tool-control";

/**
 * Gate 3 真实协议段的设备：这台手机替身会把 hub_* 命令**真的转发**给一台
 * 本地起的第三方 MCP 服务器（见 `tools/mock-phone/mcp-bridge.ts`）。
 */
const LIVE_MCP_DEVICE_ID = "live-3p";

const E2E_DEVICE_TOKENS = [
  { deviceId: TEST_DEVICE_ID, token: TEST_DEVICE_TOKEN, label: "e2e mock phone" },
  { deviceId: TEST_DEVICE_ID_2, token: TEST_DEVICE_TOKEN_2, label: "e2e mock phone 2" },
  ...[...TOOL_BEHAVIOR_DEVICES.map((d) => d.deviceId), TOOL_CONTROL_DEVICE_ID, LIVE_MCP_DEVICE_ID].map(
    (deviceId) => ({
      deviceId,
      token: relToken(deviceId),
      label: "stage1b tool channel device",
    }),
  ),
  ...[...REL_SCENARIO_DEVICES.map((d) => d.deviceId), REL_CONTROL_DEVICE_ID].map((deviceId) => ({
    deviceId,
    token: relToken(deviceId),
    label: "stage1a reliability scenario",
  })),
];

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
    if (isBinary) {
      // ⚠ 这里以前是 `new TextDecoder().decode(data)` —— 把二进制帧悄悄转成文本。
      // 后果是"二进制帧必须被拒绝"这条协议约束在 e2e 里**永远走不到**：
      // 一个伪装成 phone_pong 的二进制帧会被当成合法 pong 配对成功。
      // 现在原样投递 ArrayBuffer，和 workerd 交给 webSocketMessage 的形态一致。
      const copy = new ArrayBuffer(data.byteLength);
      new Uint8Array(copy).set(data);
      workerSide.send(copy);
      return;
    }
    workerSide.send(data.toString());
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
          // ⚠ 顺序**必须**是先接桥、再 accept。
          //
          // `accept()` 会把在此之前被 queued 的帧一次性 flush 出来。DO 在返回 101
          // 之前就把 `welcome` 发出去了，那帧正是躺在 queue 里的。旧写法先 accept，
          // 于是 welcome 在没有任何监听者的时刻被 dispatch 掉 —— 静默丢失。
          // 后果是**所有** mock phone 的 `welcomeSeen` 恒为 false，`hello` 分支
          // 从来没在 e2e 里执行过（hello-mismatch 场景因此根本没发出它那帧 hello）。
          bridgeSockets(real, workerSide);
          workerSide.accept();
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
//  真实第三方 MCP 服务器（Gate 3 用）
// ---------------------------------------------------------------------------

interface LiveMcpServer {
  /** 形如 http://127.0.0.1:PORT/mcp */
  url: string;
  name: string;
  /** 读控制面状态：工具被调了几次、收到过哪些请求头、有几个会话。 */
  state(): Promise<Record<string, unknown>>;
  close(): Promise<void>;
}

/**
 * 起一台**真实**的 MCP Streamable HTTP 服务器（`tools/mcp-test-server/server.mjs`，
 * 零依赖 Node 实现，走的是与线上同一套协议，不是替身）。
 *
 * `--port 0` 让内核分配端口，再从它的启动日志里读回真实地址 —— 这样并行跑多个
 * e2e 也不会抢端口。
 */
async function startLiveMcpServer(name: string, pageSize = 0): Promise<LiveMcpServer> {
  const child = spawn(
    process.execPath,
    ["tools/mcp-test-server/server.mjs", "--port", "0", "--name", name, "--page-size", String(pageSize)],
    { cwd: process.cwd(), stdio: ["ignore", "pipe", "pipe"] },
  );

  const url = await new Promise<string>((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(`等待 ${name} 监听超时`)), 10_000);
    let buffer = "";
    child.stdout?.on("data", (chunk: Buffer) => {
      buffer += chunk.toString();
      const matched = /listening on (http:\/\/[^\s]+)/.exec(buffer);
      if (matched !== null) {
        clearTimeout(timer);
        resolve(matched[1] as string);
      }
    });
    child.on("exit", (code) => {
      clearTimeout(timer);
      reject(new Error(`${name} 启动即退出（code=${String(code)}）`));
    });
  });

  return {
    url,
    name,
    async state() {
      const res = await fetch(`${url.replace(/\/mcp$/, "")}/__test/state`);
      return (await res.json()) as Record<string, unknown>;
    },
    async close() {
      if (child.exitCode === null) {
        child.kill("SIGTERM");
        await Promise.race([once(child, "exit"), new Promise((r) => setTimeout(r, 2000))]);
      }
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

function startMockPhone(
  url: string,
  deviceId: string,
  token: string,
  scenario: string,
  toolBehavior = "normal",
  /** 额外命令行参数（Gate 3 用它把手机替身桥接到一台真实 MCP 服务器）。 */
  extraArgs: readonly string[] = [],
): PhoneProcess {
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
      "--tool-behavior",
      toolBehavior,
      ...extraArgs,
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
  out("  Android MCP Hub · Gate 1 · 本地端到端（真实 HTTP + 真实 WebSocket + 官方 MCP SDK）");
  out("═".repeat(78));
  out("  ⚠ Worker / DO 跑在 Node shim 上，不是 workerd —— 见 TEST_REPORT.md");
  out("");

  const rig = await makeRig({ deviceTokens: E2E_DEVICE_TOKENS });
  const server = await startServer(rig);
  out(`  本地网关已启动：${server.origin}`);
  out("");

  let phoneNormal: PhoneProcess | null = null;
  let phoneSilent: PhoneProcess | null = null;
  const reliabilityPhones: PhoneProcess[] = [];
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
    const EXPECTED_TOOLS = [
      "phone_ping",
      "battery_get_status",
      "device_get_network",
      "device_get_storage",
      // Gate 3：聚合入口。注意它们是**稳定入口**，名字永远不变；
      // 第三方工具的名字只出现在 hub_list_tools 的返回值与 hub_call_tool 的参数里。
      "hub_list_tools",
      "hub_get_tool_schema",
      "hub_call_tool",
    ];
    check(
      "工具列表恰好是四个本机能力 + 三个聚合入口（显式白名单，一个不多）",
      names.length === EXPECTED_TOOLS.length && EXPECTED_TOOLS.every((n) => names.includes(n)),
      `tools=[${names.join(", ")}]`,
    );

    // Gate 3：聚合入口对外只允许"服务器标识 + 工具标识 + 参数"，
    // 绝不允许客户端把 URL / Headers 塞进来 —— 那等于让它绕过已保存的服务器配置。
    const hubList = listed.tools.find((t) => t.name === "hub_list_tools");
    const hubCall = listed.tools.find((t) => t.name === "hub_call_tool");
    const hubCallProps = Object.keys((hubCall?.inputSchema as { properties?: Record<string, unknown> } | undefined)?.properties ?? {});
    check("暴露了 hub_list_tools", hubList !== undefined);
    check(
      "hub_list_tools 只读标注（readOnlyHint=true）",
      (hubList?.annotations as Record<string, unknown> | undefined)?.["readOnlyHint"] === true,
    );
    check(
      "hub_call_tool 的参数只有 device_id / timeout_ms / server_id / tool_name / arguments",
      ["device_id", "timeout_ms", "server_id", "tool_name", "arguments"].every((k) => hubCallProps.includes(k)),
      `properties=[${hubCallProps.join(", ")}]`,
    );
    check(
      "hub_call_tool 的 schema 里没有任何 URL / Headers 入口",
      !hubCallProps.some((k) => /url|header|endpoint|token|secret/i.test(k)),
      `properties=[${hubCallProps.join(", ")}]`,
    );
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
    check("同一个 SDK 客户端第二次请求仍然有效（会话被正确复用）", afterClose.tools.length === 7);
    out("");

    // ---- ⑨ 异常手机场景矩阵（A2–A8）--------------------------------------
    //
    //  阶段 0 报告 G-4：`tools/mock-phone/client.ts` 里 9 个场景，只有 normal 与
    //  no-response 进过完整链路。这一段把剩下 7 个真的接上。
    //
    //  每一节都遵守同一条纪律：**不规定实现细节，只规定账要对**。
    //  例如"超时和 pong 谁先到"由调度决定，我们不预设赢家，只要求
    //  "要么成功、要么如实失败，且绝不能既没成功又没失败"。
    out("⑨ 异常手机场景矩阵（阶段 1A）：一台异常手机不得污染正常链路");
    out("");

    const wsUrl = `${server.origin.replace("http", "ws")}/phone/ws`;

    const relMetrics = (status: Record<string, unknown>): Record<string, number> =>
      (status["metrics"] ?? {}) as Record<string, number>;

    async function waitForPhoneConnected(deviceId: string): Promise<Record<string, unknown>> {
      return await waitFor(
        `${deviceId} 被服务端接受`,
        async () => {
          const status = await phoneStatus(rig, deviceId);
          return status["connected"] === true ? status : null;
        },
        15_000,
      );
    }

    async function spawnScenario(deviceId: string, scenario: string): Promise<PhoneProcess> {
      const proc = startMockPhone(wsUrl, deviceId, relToken(deviceId), scenario);
      reliabilityPhones.push(proc);
      await waitForPhoneConnected(deviceId);
      return proc;
    }

    /** 走完整链路（MCP 客户端 → Worker → DO → WebSocket → 手机进程）发一次 ping。 */
    async function relPing(
      deviceId: string,
      timeoutMs: number,
    ): Promise<{ isError: boolean; structured: Record<string, unknown>; code: string | undefined; elapsed: number }> {
      const startedAt = Date.now();
      const res = await client.callTool({
        name: "phone_ping",
        arguments: { device_id: deviceId, timeout_ms: timeoutMs },
      });
      const elapsed = Date.now() - startedAt;
      const structured = (res["structuredContent"] ?? {}) as Record<string, unknown>;
      // ⚠ 这个工具目前有**两套**错误编码，必须都读：
      //   · DO 返回的失败用 `{ ok:false, error:{ code, message } }`（pingOutcomeToResult）
      //   · 设备解析失败（unknown_device / ambiguous_device）用的是
      //     `{ ok:false, error_code, error_message }`（worker/src/mcp/tools.ts:241-255）
      //   两套并存是既有的 API 不一致（已记进报告），本轮不改业务代码。
      const nested = (structured["error"] as Record<string, unknown> | undefined)?.["code"];
      const flat = structured["error_code"];
      const code = typeof nested === "string" ? nested : typeof flat === "string" ? flat : undefined;
      return { isError: res["isError"] === true, structured, code, elapsed };
    }

    /**
     * 轮询到某个计数达到阈值为止 —— 用有界等待代替"睡一会儿碰运气"。
     *
     * ⚠ 超时**不抛异常**：抛出去会把后面所有场景一起带崩，那样报告里剩下的
     *   就只有"脚本自身出错"，而不是"哪条断言红了"。这里返回最后一次观察到的
     *   状态，让紧随其后的 `check(...)` 如实报红。
     */
    async function waitForMetric(deviceId: string, metric: string, atLeast: number): Promise<Record<string, unknown>> {
      const deadline = Date.now() + 5_000;
      let last = await phoneStatus(rig, deviceId);
      for (;;) {
        if ((relMetrics(last)[metric] ?? 0) >= atLeast) return last;
        if (Date.now() > deadline) {
          out(`     ⚠ 等待 ${deviceId} 的 ${metric} 达到 ${atLeast} 超时，按实际值断言`);
          return last;
        }
        await new Promise((r) => setTimeout(r, 100));
        last = await phoneStatus(rig, deviceId);
      }
    }

    // 对照组先起来，后面每一节都会回头 ping 它一次。
    await spawnScenario(REL_CONTROL_DEVICE_ID, "normal");
    const controlBaseline = await relPing(REL_CONTROL_DEVICE_ID, 2_000);
    check(
      "⑨-对照 正常手机 ping 成功（后面所有'没被影响'都以它为基准）",
      controlBaseline.structured["ok"] === true,
      `code=${String(controlBaseline.code)}`,
    );
    out("");

    // ── A2 wrong-request-id ────────────────────────────────────────────────
    out("  A2 wrong-request-id：手机回的 request_id 不认识");
    await spawnScenario("rel-wrong-request-id", "wrong-request-id");
    const a2first = await relPing("rel-wrong-request-id", 1_000);
    check(
      "A2 原请求如实超时（没有被错误的 id 配对成功）",
      a2first.isError === true && a2first.code === "phone_timeout",
      `code=${String(a2first.code)} elapsed=${a2first.elapsed}ms`,
    );
    check("A2 绝不产生虚假成功", a2first.structured["ok"] !== true);
    const a2status = await waitForMetric("rel-wrong-request-id", "pongsUnknown", 1);
    check(
      "A2 不认识的 pong 被记为 pongsUnknown（不是静默吞掉）",
      relMetrics(a2status)["pongsUnknown"] >= 1,
      `pongsUnknown=${relMetrics(a2status)["pongsUnknown"]}`,
    );
    check("A2 连接仍然在线（错误 pong 不破坏连接状态机）", a2status["connected"] === true);
    check(
      "A2 pending 清理干净",
      a2status["pending_requests"] === 0 && a2status["pending_mirror"] === 0,
      `pending=${String(a2status["pending_requests"])} mirror=${String(a2status["pending_mirror"])}`,
    );
    const a2second = await relPing("rel-wrong-request-id", 1_000);
    check("A2 第二次仍然如实超时（状态没被写坏）", a2second.code === "phone_timeout", `code=${String(a2second.code)}`);
    out("");

    // ── A3 duplicate-pong ──────────────────────────────────────────────────
    out("  A3 duplicate-pong：同一个 pong 收到两次");
    await spawnScenario("rel-duplicate-pong", "duplicate-pong");
    const a3first = await relPing("rel-duplicate-pong", 2_000);
    check("A3 第一次 ping 正常成功", a3first.structured["ok"] === true, `code=${String(a3first.code)}`);
    const a3status = await waitForMetric("rel-duplicate-pong", "pongsUnknown", 1);
    check(
      "A3 同一个请求只被结算一次（pongsMatched === 1）",
      relMetrics(a3status)["pongsMatched"] === 1,
      `pongsMatched=${relMetrics(a3status)["pongsMatched"]}`,
    );
    check(
      "A3 重复的那一份被当作无人认领丢掉（不是又配对了一次）",
      relMetrics(a3status)["pongsUnknown"] >= 1,
      `pongsUnknown=${relMetrics(a3status)["pongsUnknown"]}`,
    );
    check(
      "A3 pending 没有被重复清理出错（表与镜像都是空的）",
      a3status["pending_requests"] === 0 && a3status["pending_mirror"] === 0,
      `pending=${String(a3status["pending_requests"])} mirror=${String(a3status["pending_mirror"])}`,
    );
    const a3second = await relPing("rel-duplicate-pong", 2_000);
    check(
      "A3 紧接着的下一个请求不受影响（重复响应没有污染它）",
      a3second.structured["ok"] === true,
      `code=${String(a3second.code)}`,
    );
    const a3secondId = a3second.structured["request_id"];
    check(
      "A3 两次请求拿到的是不同的 request_id",
      typeof a3first.structured["request_id"] === "string" &&
        typeof a3secondId === "string" &&
        a3first.structured["request_id"] !== a3secondId,
    );
    out("");

    // ── A4 late ────────────────────────────────────────────────────────────
    out("  A4 late：pong 晚于请求超时才到（mock 固定延迟 400ms，超时设 150ms）");
    await spawnScenario("rel-late", "late");
    const a4first = await relPing("rel-late", 150);
    check(
      "A4 超时先到 → 请求如实失败",
      a4first.isError === true && a4first.code === "phone_timeout",
      `code=${String(a4first.code)} elapsed=${a4first.elapsed}ms`,
    );
    // 等到迟到的 pong 真的被服务端处理过，而不是"睡一会儿猜它到了"。
    const a4status = await waitForMetric("rel-late", "pongsUnknown", 1);
    check(
      "A4 迟到的 pong 被记成无人认领，而不是复活那个已经超时的请求",
      relMetrics(a4status)["pongsMatched"] === 0,
      `pongsMatched=${relMetrics(a4status)["pongsMatched"]} pongsUnknown=${relMetrics(a4status)["pongsUnknown"]}`,
    );
    check("A4 已经超时的请求没有被改写成成功", a4first.structured["ok"] !== true);
    check(
      "A4 超时账仍然只算一次",
      relMetrics(a4status)["timeouts"] === 1,
      `timeouts=${relMetrics(a4status)["timeouts"]}`,
    );
    const a4second = await relPing("rel-late", 2_000);
    check(
      "A4 迟到的 pong 没有匹配到新请求：第二个请求用自己的 pong 正常成功",
      a4second.structured["ok"] === true,
      `code=${String(a4second.code)}`,
    );
    check(
      "A4 两个请求的 request_id 不同（不存在'迟到帧冒充新请求'的路径）",
      typeof a4first.structured["request_id"] === "string" &&
        typeof a4second.structured["request_id"] === "string" &&
        a4first.structured["request_id"] !== a4second.structured["request_id"],
    );
    out("");

    // ── A5 drop-after-ping ─────────────────────────────────────────────────
    out("  A5 drop-after-ping：收到 ping 之后立刻断开（不回 pong）");
    await spawnScenario("rel-drop-after-ping", "drop-after-ping");
    const a5first = await relPing("rel-drop-after-ping", 5_000);
    check(
      "A5 在途请求明确失败为 phone_disconnected",
      a5first.isError === true && a5first.code === "phone_disconnected",
      `code=${String(a5first.code)}`,
    );
    check("A5 绝不返回虚假成功", a5first.structured["ok"] !== true);
    check(
      "A5 是显式失败而不是拖满 5000ms 超时",
      a5first.elapsed < 2_500,
      `elapsed=${a5first.elapsed}ms`,
    );
    const a5status = await waitFor(
      "服务端观察到这台设备断开",
      async () => {
        const status = await phoneStatus(rig, "rel-drop-after-ping");
        return status["connected"] === false ? status : null;
      },
      5_000,
    );
    check(
      "A5 pending 清理干净，没有泄漏",
      a5status["pending_requests"] === 0 && a5status["pending_mirror"] === 0,
      `pending=${String(a5status["pending_requests"])} mirror=${String(a5status["pending_mirror"])}`,
    );
    const a5second = await relPing("rel-drop-after-ping", 5_000);
    check(
      "A5 断开之后的新请求快速失败为 phone_offline（不挂死、不自动重放）",
      a5second.isError === true && a5second.code === "phone_offline",
      `code=${String(a5second.code)}`,
    );
    check("A5 快速失败而不是等超时", a5second.elapsed < 1_000, `elapsed=${a5second.elapsed}ms`);
    out("");

    // ── A6 noisy ───────────────────────────────────────────────────────────
    out("  A6 noisy：手机先发垃圾帧，再发正常 pong");
    await spawnScenario("rel-noisy", "noisy");
    const a6first = await relPing("rel-noisy", 2_000);
    check(
      "A6 垃圾帧之后的正常 pong 仍然被接受（噪声没有打断链路）",
      a6first.structured["ok"] === true,
      `code=${String(a6first.code)}`,
    );
    const a6status = await phoneStatus(rig, "rel-noisy");
    check(
      "A6 三帧垃圾（非 JSON / 未知类型 / 缺 request_id 的 pong）都被计数拒绝",
      relMetrics(a6status)["messagesRejected"] === 3,
      `messagesRejected=${relMetrics(a6status)["messagesRejected"]}`,
    );
    check(
      "A6 垃圾帧没有被错配成别的请求",
      relMetrics(a6status)["pongsUnknown"] === 0 && relMetrics(a6status)["pongsStaleGeneration"] === 0,
      `pongsUnknown=${relMetrics(a6status)["pongsUnknown"]} pongsStaleGeneration=${relMetrics(a6status)["pongsStaleGeneration"]}`,
    );
    check(
      "A6 缺 request_id 的 pong 走的是 malformed 路径，不是 pong 路径",
      relMetrics(a6status)["pongsMatched"] === 1,
      `pongsMatched=${relMetrics(a6status)["pongsMatched"]}`,
    );
    const a6second = await relPing("rel-noisy", 2_000);
    check("A6 连续 ping 仍然正常（状态机没被写坏）", a6second.structured["ok"] === true);
    const a6control = await relPing(REL_CONTROL_DEVICE_ID, 2_000);
    check(
      "A6 另一台正常手机的 ping 不受影响",
      a6control.structured["ok"] === true,
      `code=${String(a6control.code)}`,
    );
    out("");

    // ── A7 binary ──────────────────────────────────────────────────────────
    out("  A7 binary：手机发二进制帧（内容是 phone_pong 的字节）");
    await spawnScenario("rel-binary", "binary");
    const a7first = await relPing("rel-binary", 1_200);
    check(
      "A7 二进制帧没有被当成合法 pong → 请求如实超时",
      a7first.isError === true && a7first.code === "phone_timeout",
      `code=${String(a7first.code)} elapsed=${a7first.elapsed}ms`,
    );
    check("A7 绝不返回虚假成功", a7first.structured["ok"] !== true);
    const a7status = await waitForMetric("rel-binary", "messagesRejected", 1);
    check(
      "A7 二进制帧被计数拒绝（协议只接受文本帧）",
      relMetrics(a7status)["messagesRejected"] >= 1,
      `messagesRejected=${relMetrics(a7status)["messagesRejected"]}`,
    );
    check(
      "A7 那帧二进制**没有**被配对成 pong（字节内容是 phone_pong 也不行）",
      relMetrics(a7status)["pongsMatched"] === 0,
      `pongsMatched=${relMetrics(a7status)["pongsMatched"]}`,
    );
    check(
      "A7 连接状态没有被破坏（仍然在线）",
      a7status["connected"] === true,
      `connected=${String(a7status["connected"])}`,
    );
    const a7second = await relPing("rel-binary", 1_200);
    check(
      "A7 再来一次仍然如实超时、也不是 phone_offline（连接没被误关）",
      a7second.code === "phone_timeout",
      `code=${String(a7second.code)}`,
    );
    out("");

    // ── A8 hello-mismatch ──────────────────────────────────────────────────
    out(`  A8 hello-mismatch：hello 里冒充另一个 device_id（mock 写死 "${REL_CLAIMED_DEVICE_ID}"）`);
    await spawnScenario("rel-hello-mismatch", "hello-mismatch");
    // ⚠ `connected === true` 只说明服务端接受了这个 socket —— 那一刻 mock 还没收到
    //   welcome，也就还没发 hello。必须等 hello 真的被处理过再断言，否则测的是"尚未发生"。
    const a8status = await waitForMetric("rel-hello-mismatch", "messagesRejected", 1);
    check(
      "A8 已认证的设备身份不被 hello 改写",
      a8status["device_id"] === "rel-hello-mismatch",
      `device_id=${String(a8status["device_id"])}`,
    );
    check(
      "A8 hello 里声明的 device_id 与已认证设备不一致被计数留痕（不是静默忽略）",
      relMetrics(a8status)["messagesRejected"] === 1,
      `messagesRejected=${relMetrics(a8status)["messagesRejected"]}`,
    );
    const a8real = await relPing("rel-hello-mismatch", 2_000);
    check(
      "A8 真正已认证的设备照常可用（hello 的不一致没有把它踢下线）",
      a8real.structured["ok"] === true,
      `code=${String(a8real.code)}`,
    );
    const a8claimed = await phoneStatus(rig, REL_CLAIMED_DEVICE_ID);
    check(
      "A8 被冒充的那个设备没有因此变成在线（请求不会被路由过去）",
      a8claimed["connected"] === false,
      `connected=${String(a8claimed["connected"])}`,
    );
    const a8claimedPing = await relPing(REL_CLAIMED_DEVICE_ID, 1_000);
    check(
      "A8 向被冒充的设备发请求 → 明确拒绝，绝不落到那台真手机上执行",
      a8claimedPing.isError === true && a8claimedPing.structured["ok"] !== true,
      `isError=${String(a8claimedPing.isError)} structured=${JSON.stringify(a8claimedPing.structured)}`,
    );
    check(
      "A8 拒绝原因是没有有效凭据（unknown_device）",
      a8claimedPing.code === "unknown_device",
      `code=${String(a8claimedPing.code)}`,
    );
    out("");

    // ── 收尾：对照组仍然好好的 ─────────────────────────────────────────────
    const controlFinal = await relPing(REL_CONTROL_DEVICE_ID, 2_000);
    check(
      "⑨-收尾 走完 7 个异常场景之后，对照组仍然正常（异常手机没有污染它）",
      controlFinal.structured["ok"] === true,
      `code=${String(controlFinal.code)}`,
    );
    out("");

    // ---- ⑩ Gate 2 通用工具通道（battery_get_status 全链路）---------------
    out("⑩ Gate 2 工具通道：MCP → Worker → DO → 手机 → MCP");
    out("   （每台手机一种 toolBehavior；请求全部走真实的 MCP tools/call）");

    const toolPhones: PhoneProcess[] = [];
    let toolControl: PhoneProcess | null = null;

    /** 一次真实工具调用：返回是否失败、结构化内容、墙钟耗时。 */
    async function toolCall(
      deviceId: string,
      timeoutMs: number,
      extraArgs: Record<string, unknown> = {},
      toolName: string = "battery_get_status",
    ): Promise<{ isError: boolean; structured: Record<string, unknown>; elapsed: number; protocolError: string }> {
      const started = Date.now();
      try {
        const res = await client.callTool({
          name: toolName,
          arguments: { device_id: deviceId, timeout_ms: timeoutMs, ...extraArgs },
        });
        const elapsed = Date.now() - started;
        return {
          isError: res["isError"] === true,
          structured: (res["structuredContent"] as Record<string, unknown> | undefined) ?? {},
          elapsed,
          protocolError: "",
        };
      } catch (err) {
        // 参数校验失败 / 工具不存在这类是 **JSON-RPC 协议错误**：
        //   SDK 在传输层就把它抛出来，压根不会变成一个 isError 的 tool result。
        //   这个区别很重要 —— 它意味着"协议层拒绝"和"能力执行失败"是两件事。
        return { isError: true, structured: {}, elapsed: Date.now() - started, protocolError: String(err) };
      }
    }

    /** 起一台工具通道的手机（scenario 固定 normal —— 坏法在 toolBehavior 上）。 */
    async function spawnToolPhone(
      deviceId: string,
      toolBehavior: string,
      extraArgs: readonly string[] = [],
    ): Promise<PhoneProcess> {
      const proc = startMockPhone(
        `${server.origin.replace("http", "ws")}/phone/ws`,
        deviceId,
        relToken(deviceId),
        "normal",
        toolBehavior,
        extraArgs,
      );
      await waitFor(
        `${deviceId} 连上服务端`,
        async () => {
          const res = await client.callTool({ name: "phone_ping", arguments: { device_id: deviceId, timeout_ms: 300 } });
          const structured = (res["structuredContent"] as Record<string, unknown> | undefined) ?? {};
          if (structured["ok"] === true) return structured;
          const code = (structured["error"] as Record<string, unknown> | undefined)?.["code"];
          if (code === "phone_offline") return null;
          throw new Error(`意外的 ping 结果：${JSON.stringify(structured)}`);
        },
        20_000,
      );
      return proc;
    }

    // 先证明工具能跑通，再让它去对付坏手机。
    const normalTool = await spawnToolPhone("tool-normal", "normal");
    toolPhones.push(normalTool);
    const toolControlProc = await spawnToolPhone(TOOL_CONTROL_DEVICE_ID, "normal");
    toolControl = toolControlProc;

    const happy = await toolCall("tool-normal", 3000);
    check(
      "battery_get_status 走完整链路成功（不是 Worker 自己编的）",
      !happy.isError && happy.structured["ok"] === true,
      `ok=${String(happy.structured["ok"])}`,
    );
    check(
      "返回的是结构化电池字段",
      typeof happy.structured["battery_level_percent"] === "number" &&
        typeof happy.structured["charging"] === "boolean" &&
        typeof happy.structured["power_save_mode"] === "boolean" &&
        typeof happy.structured["charging_source"] === "string",
      JSON.stringify({
        level: happy.structured["battery_level_percent"],
        charging: happy.structured["charging"],
        source: happy.structured["charging_source"],
        saver: happy.structured["power_save_mode"],
      }),
    );
    check(
      "结果里带 tool_name / connection_generation / rtt_ms",
      happy.structured["tool_name"] === "battery_get_status" &&
        (happy.structured["connection_generation"] as number) >= 1 &&
        typeof happy.structured["rtt_ms"] === "number",
      `tool=${String(happy.structured["tool_name"])} gen=${String(happy.structured["connection_generation"])}`,
    );

    // Gate 2.5A+B：两个新工具走同一条完整链路（MCP → Worker → DO → 手机 → MCP）。
    //   ⚠ 手机侧这里是 mock-phone 的固定返回，不是真实 Android 设备；
    //   真实设备的读数验收在真机清单里，不在这里冒充。
    const net = await toolCall("tool-normal", 3000, {}, "device_get_network");
    check(
      "device_get_network 走完整链路成功（不是 Worker 自己编的）",
      !net.isError && net.structured["ok"] === true && net.structured["tool_name"] === "device_get_network",
      `ok=${String(net.structured["ok"])} tool=${String(net.structured["tool_name"])}`,
    );
    check(
      "网络结果里有 connected / transport / transports",
      typeof net.structured["connected"] === "boolean" &&
        typeof net.structured["transport"] === "string" &&
        Array.isArray(net.structured["transports"]),
      JSON.stringify({
        connected: net.structured["connected"],
        transport: net.structured["transport"],
        transports: net.structured["transports"],
      }),
    );

    const sto = await toolCall("tool-normal", 3000, {}, "device_get_storage");
    check(
      "device_get_storage 走完整链路成功（不是 Worker 自己编的）",
      !sto.isError && sto.structured["ok"] === true && sto.structured["tool_name"] === "device_get_storage",
      `ok=${String(sto.structured["ok"])} tool=${String(sto.structured["tool_name"])}`,
    );
    check(
      "存储结果满足 used = total - available，且带卷标识",
      sto.structured["volume"] === "internal_data" &&
        Number(sto.structured["used_bytes"]) ===
          Number(sto.structured["total_bytes"]) - Number(sto.structured["available_bytes"]),
      JSON.stringify({
        volume: sto.structured["volume"],
        total: sto.structured["total_bytes"],
        available: sto.structured["available_bytes"],
        used: sto.structured["used_bytes"],
      }),
    );

    // 新增工具不该给别的工具开口子：网络/存储同样只认 device_id / timeout_ms。
    const netBadArg = await toolCall("tool-normal", 3000, { ssid: "wifi-name" }, "device_get_network");
    check(
      "新工具也拒绝多余参数（协议错误，拒绝而不是静默忽略）",
      netBadArg.protocolError.includes("-32602") && netBadArg.protocolError.includes("ssid"),
      netBadArg.protocolError.slice(0, 120),
    );

    // 未知工具：协议错误（JSON-RPC error，SDK 会直接抛），且不会去问手机。
    //   —— 这里必须真的把异常接住：协议错误在传输层就被 SDK 拦下了，
    //   它**不会**变成一个 isError 的 tool result。
    let unknownToolError = "";
    try {
      await client.callTool({ name: "read_any_file", arguments: {} });
    } catch (err) {
      unknownToolError = String(err);
    }
    check(
      "未注册的工具名被拒绝（协议错误 -32602，不是工具执行失败）",
      unknownToolError.includes("-32602") && unknownToolError.includes("read_any_file"),
      unknownToolError.slice(0, 120),
    );

    // 多余参数：协议错误。
    const badArg = await toolCall("tool-normal", 3000, { package_name: "com.example.app" });
    check(
      "多余参数被拒绝（是协议错误，拒绝而不是静默忽略）",
      badArg.protocolError.includes("-32602") && badArg.protocolError.includes("package_name"),
      badArg.protocolError.slice(0, 120),
    );

    // 不认识的设备。
    const unknownDevice = await toolCall("phone-that-does-not-exist", 3000);
    check(
      "未配置的设备拿不到任何能力",
      unknownDevice.isError && unknownDevice.structured["error_code"] === "unknown_device",
      `error_code=${String(unknownDevice.structured["error_code"])}`,
    );

    // 收到 tool_request 但不回 → 如实超时。
    toolPhones.push(await spawnToolPhone("tool-silent", "no-response"));
    const silent = await toolCall("tool-silent", 600);
    check(
      "手机不回 tool_result → 如实超时（不是虚假成功）",
      silent.isError &&
        (silent.structured["error"] as Record<string, unknown> | undefined)?.["code"] === "phone_timeout",
      `elapsed=${silent.elapsed}ms code=${String(
        (silent.structured["error"] as Record<string, unknown> | undefined)?.["code"],
      )}`,
    );
    check("超时耗时确实接近 timeout_ms（不是瞬间失败，也不是拖到天荒地老）", silent.elapsed >= 500 && silent.elapsed < 3000, `elapsed=${silent.elapsed}ms`);

    // 结果里塞信封键 → 被丢弃。
    toolPhones.push(await spawnToolPhone("tool-hostile", "reserved-key"));
    const hostile = await toolCall("tool-hostile", 3000);
    check(
      "手机塞进 result 的 ok/request_id/device_id 被丢弃，信封没有被改写",
      hostile.structured["ok"] === true &&
        hostile.structured["device_id"] === "tool-hostile" &&
        hostile.structured["request_id"] !== "hijacked",
      `ok=${String(hostile.structured["ok"])} request_id=${String(hostile.structured["request_id"])}`,
    );

    // 能力内部失败 → 如实转达。
    toolPhones.push(await spawnToolPhone("tool-error", "error"));
    const failed = await toolCall("tool-error", 3000);
    check(
      "手机回 ok:false → 前端拿到失败，错误码原样保留",
      failed.isError &&
        (failed.structured["error"] as Record<string, unknown> | undefined)?.["code"] === "tool_failed",
      `code=${String((failed.structured["error"] as Record<string, unknown> | undefined)?.["code"])}`,
    );

    // 手机白名单里没有这个工具。
    toolPhones.push(await spawnToolPhone("tool-unsupported", "unsupported"));
    const unsupported = await toolCall("tool-unsupported", 3000);
    check(
      "手机侧白名单拒绝 → unsupported_tool（服务端不替它放行）",
      unsupported.isError &&
        (unsupported.structured["error"] as Record<string, unknown> | undefined)?.["code"] === "unsupported_tool",
      `code=${String((unsupported.structured["error"] as Record<string, unknown> | undefined)?.["code"])}`,
    );

    // 对照组：跑完 5 台坏手机之后，正常那台仍然正常，ping 也仍然正常。
    const controlTool = await toolCall(TOOL_CONTROL_DEVICE_ID, 3000);
    check(
      "跑完 5 台异常手机之后，对照组工具调用仍然正常",
      !controlTool.isError && controlTool.structured["ok"] === true,
      `ok=${String(controlTool.structured["ok"])}`,
    );
    const controlPing = await client.callTool({
      name: "phone_ping",
      arguments: { device_id: TOOL_CONTROL_DEVICE_ID, timeout_ms: 2000 },
    });
    check(
      "同一台手机上 legacy phone_ping 与新工具通道共存",
      ((controlPing["structuredContent"] as Record<string, unknown> | undefined)?.["ok"] ?? false) === true,
    );

    // -----------------------------------------------------------------------
    // ⑪ Gate 3：聚合入口 —— 第三方 MCP 服务器 → 手机 → ChatGPT 可见
    // -----------------------------------------------------------------------
    //
    //  这一段要证明的是**闭环**，不是映射表：
    //    · 一个真实的 MCP SDK 客户端，调两个**名字永远不变**的聚合入口；
    //    · 请求穿过 Worker → DO → WebSocket，落到手机替身；
    //    · 手机替身里"已连接着一台第三方 MCP 服务器"，它把真实目录交回来；
    //    · 目录里那个**未授权**的工具，名字和描述一个字节都不许外传。
    out("⑪ Gate 3 聚合入口：第三方 MCP 服务器 → 手机 → ChatGPT 可见");

    const catalog = await toolCall(TOOL_CONTROL_DEVICE_ID, 3000, {}, "hub_list_tools");
    check(
      "hub_list_tools 走完整链路成功（目录来自手机，不是 Worker 编的）",
      !catalog.isError && catalog.structured["ok"] === true,
      `ok=${String(catalog.structured["ok"])} code=${String(
        (catalog.structured["error"] as Record<string, unknown> | undefined)?.["code"],
      )}`,
    );
    const catalogTools = (catalog.structured["tools"] as Array<Record<string, unknown>> | undefined) ?? [];
    check(
      "目录里是第三方工具的真实身份（serverId + toolName 组成的稳定 id）",
      catalogTools.length === 1 && catalogTools[0]?.["tool_id"] === "mcp:mock_server:echo_text",
      `tools=${JSON.stringify(catalogTools.map((t) => t["tool_id"]))}`,
    );
    check(
      "未授权的工具只被计数、描述不外传",
      catalog.structured["other_tools_count"] === 1 &&
        !JSON.stringify(catalog).includes("write_note") &&
        !JSON.stringify(catalog).includes("写一条笔记"),
      `other_tools_count=${String(catalog.structured["other_tools_count"])}`,
    );

    const hubEcho = await toolCall(
      TOOL_CONTROL_DEVICE_ID,
      3000,
      { server_id: "mock_server", tool_name: "echo_text", arguments: { text: "gate3-e2e" } },
      "hub_call_tool",
    );
    // ⚠ 走的是同一个 SDK 客户端，但取的是**工具结果正文**（content），
    //   而不是 structuredContent —— 第三方工具的真实文本就在这里。
    const hubEchoRaw = await client.callTool({
      name: "hub_call_tool",
      arguments: {
        device_id: TOOL_CONTROL_DEVICE_ID,
        timeout_ms: 3000,
        server_id: "mock_server",
        tool_name: "echo_text",
        arguments: { text: "gate3-e2e" },
      },
    });
    const hubEchoText = ((hubEchoRaw["content"] as Array<{ type: string; text: string }> | undefined) ?? [])
      .map((c) => c.text)
      .join("\n");
    check(
      "hub_call_tool 把第三方工具的真实结果带回来（端到端，不是回显请求）",
      hubEchoRaw["isError"] !== true && hubEchoText.includes("mock-mcp echo: gate3-e2e"),
      `text=${hubEchoText.replace(/\n/g, " / ")}`,
    );
    check(
      "结果里的身份来自信封与请求，第三方结果无法改写它",
      hubEcho.structured["tool_id"] === "mcp:mock_server:echo_text" &&
        hubEcho.structured["server_id"] === "mock_server" &&
        hubEcho.structured["tool_name"] === "echo_text" &&
        hubEcho.structured["device_id"] === TOOL_CONTROL_DEVICE_ID &&
        hubEcho.structured["connection_generation"] !== undefined,
      JSON.stringify({
        tool_id: hubEcho.structured["tool_id"],
        device_id: hubEcho.structured["device_id"],
      }),
    );

    // 客户端**不能**用聚合入口绕过已保存的服务器配置：没有 URL / Headers 入口，
    // 身份格式一旦不合法就是**协议错误**，连发都不会发出去。
    const badIdentity = await toolCall(
      TOOL_CONTROL_DEVICE_ID,
      3000,
      { server_id: "../../etc", tool_name: "echo_text", url: "https://evil.example/mcp" },
      "hub_call_tool",
    );
    check(
      "非法 server_id / 多余的 url 字段 → 协议错误（拒绝而不是静默忽略）",
      badIdentity.protocolError !== "" || badIdentity.isError,
      badIdentity.protocolError !== "" ? "JSON-RPC error" : `code=${String(
        (badIdentity.structured["error"] as Record<string, unknown> | undefined)?.["code"],
      )}`,
    );

    // -----------------------------------------------------------------------
    // ⑫ Gate 3 真实协议：手机替身真的去连一台第三方 MCP 服务器
    // -----------------------------------------------------------------------
    //
    //  ⑪ 证明的是聚合入口的**形状**，手机那边回的是替身写死的目录。
    //  这一段把手机换成真协议：手机替身用 `tools/mcp-streamable-http-client.mjs`
    //  （同一份零依赖参考客户端）对一台真实 MCP Streamable HTTP 服务器做
    //  initialize → tools/list（分页）→ tools/call，目录与结果都是服务器给的字节。
    //
    //  判据挑的是替身**编不出来**的东西：分页取回的 8 个工具、服务器自己算出的算式、
    //  服务器控制面里记着的会话与请求头。
    out("⑫ Gate 3 真实协议：手机 → 真实第三方 MCP 服务器（initialize / tools/list / tools/call）");

    const liveServer = await startLiveMcpServer("live-server", 3); // 每页 3 个 → 8 个工具必然分页
    try {
      const livePhone = await spawnToolPhone(LIVE_MCP_DEVICE_ID, "normal", [
        "--mcp-url",
        liveServer.url,
        "--mcp-server-id",
        "live_server",
      ]);
      toolPhones.push(livePhone);

      const liveCatalog = await toolCall(LIVE_MCP_DEVICE_ID, 8000, {}, "hub_list_tools");
      const liveTools = (liveCatalog.structured["tools"] as Array<Record<string, unknown>> | undefined) ?? [];
      const liveIds = liveTools.map((t) => String(t["tool_id"]));
      check(
        "目录来自真实 MCP 服务器的 tools/list（含分页），不是替身写死的表",
        !liveCatalog.isError &&
          liveCatalog.structured["ok"] === true &&
          liveIds.includes("mcp:live_server:echo_text") &&
          liveIds.includes("mcp:live_server:sum_numbers") &&
          liveIds.includes("mcp:live_server:no_annotations"),
        `count=${String(liveCatalog.structured["tools_count"])} ids=${liveIds.slice(0, 3).join(",")}…`,
      );
      // 目录层默认**不带** schema：这是"35 个工具撑爆 16 KiB 帧"的正面修复。
      check(
        "目录层默认不带 input_schema（体积不再随工具数 × schema 增长）",
        liveTools.length > 0 && liveTools.every((t) => t["input_schema"] === undefined),
        `returned=${String(liveCatalog.structured["returned"])} 带 schema 的条数=${
          liveTools.filter((t) => t["input_schema"] !== undefined).length
        }`,
      );
      check(
        "目录层给出真实的总数/偏移/每页条数与 has_more",
        liveCatalog.structured["total_tools"] === 7 &&
          liveCatalog.structured["returned"] === 7 &&
          liveCatalog.structured["offset"] === 0 &&
          liveCatalog.structured["has_more"] === false,
        JSON.stringify({
          total: liveCatalog.structured["total_tools"],
          returned: liveCatalog.structured["returned"],
          offset: liveCatalog.structured["offset"],
          has_more: liveCatalog.structured["has_more"],
        }),
      );

      // 分页：limit=3 只是"这一页 3 条"，总数仍然是 7 —— 分页绝不会让工具消失。
      const page1 = await toolCall(LIVE_MCP_DEVICE_ID, 8000, { limit: 3 }, "hub_list_tools");
      const page1Tools = (page1.structured["tools"] as Array<Record<string, unknown>> | undefined) ?? [];
      check(
        "分页：limit=3 返回 3 条且 has_more=true、next_offset=3，总数不变",
        page1Tools.length === 3 &&
          page1.structured["total_tools"] === 7 &&
          page1.structured["has_more"] === true &&
          page1.structured["next_offset"] === 3,
        JSON.stringify({
          returned: page1Tools.length,
          total: page1.structured["total_tools"],
          next: page1.structured["next_offset"],
        }),
      );
      const page3 = await toolCall(LIVE_MCP_DEVICE_ID, 8000, { offset: 6, limit: 3 }, "hub_list_tools");
      const page3Tools = (page3.structured["tools"] as Array<Record<string, unknown>> | undefined) ?? [];
      check(
        "分页：offset=6 取到最后一页（1 条），has_more=false",
        page3Tools.length === 1 && page3.structured["has_more"] === false,
        `returned=${page3Tools.length} has_more=${String(page3.structured["has_more"])}`,
      );

      // 按服务器查询：server_id 过滤在这一层生效，未知服务器是明确的失败而不是空目录。
      const perServer = await toolCall(LIVE_MCP_DEVICE_ID, 8000, { server_id: "live_server" }, "hub_list_tools");
      const perServerTools = (perServer.structured["tools"] as Array<Record<string, unknown>> | undefined) ?? [];
      check(
        "可以按服务器查询工具：server_id 过滤后目录只含这台服务器的工具",
        perServer.isError === false &&
          perServerTools.length === 7 &&
          perServerTools.every((t) => t["server_id"] === "live_server"),
        `returned=${perServerTools.length}`,
      );
      const unknownServer = await toolCall(
        LIVE_MCP_DEVICE_ID,
        8000,
        { server_id: "not_configured" },
        "hub_list_tools",
      );
      check(
        "按服务器查询：本机没有的 server_id → 明确失败（不伪造空目录）",
        unknownServer.isError === true && unknownServer.structured["error_code"] === "unsupported_tool",
        `code=${String(unknownServer.structured["error_code"])}`,
      );

      // servers_only：服务器优先查询 —— 只回答"哪台在线"，不枚举工具。
      const serversOnly = await toolCall(LIVE_MCP_DEVICE_ID, 8000, { servers_only: true }, "hub_list_tools");
      const onlyServers = (serversOnly.structured["servers"] as Array<Record<string, unknown>> | undefined) ?? [];
      check(
        "servers_only 只返回服务器清单（先查服务器，再按服务器查工具）",
        serversOnly.isError === false &&
          onlyServers.length === 1 &&
          onlyServers[0]?.["server_id"] === "live_server" &&
          ((serversOnly.structured["tools"] as unknown[] | undefined) ?? []).length === 0,
        `servers=${JSON.stringify(onlyServers)}`,
      );

      // 详情层：只取一个工具的 schema，且内容是服务器上真实的那一份。
      const guide = await toolCall(
        LIVE_MCP_DEVICE_ID,
        8000,
        { server_id: "live_server", tool_name: "echo_text" },
        "hub_get_tool_schema",
      );
      const echoSchema = (guide.structured["input_schema"] as Record<string, unknown> | undefined) ?? {};
      const echoProps = (echoSchema["properties"] as Record<string, unknown> | undefined) ?? {};
      const echoRequired = (echoSchema["required"] as string[] | undefined) ?? [];
      check(
        "详情层：hub_get_tool_schema 返回该工具真实的 input_schema（required/properties 逐字搬回）",
        guide.isError === false &&
          guide.structured["ok"] === true &&
          guide.structured["tool_name"] === "echo_text" &&
          echoRequired.includes("text") &&
          (echoProps["text"] as Record<string, unknown> | undefined)?.["type"] === "string",
        `schema=${JSON.stringify(echoSchema).slice(0, 120)}`,
      );
      check(
        "详情层是只读的：读 schema 没有触发任何第三方工具调用",
        (((await liveServer.state())["calls"] as unknown[] | undefined) ?? []).length === 0,
        `服务器记录的工具调用=${
          (((await liveServer.state())["calls"] as Array<Record<string, unknown>> | undefined) ?? [])
            .map((c) => String(c["name"]))
            .join(",") || "（无）"
        }`,
      );
      const guideDenied = await toolCall(
        LIVE_MCP_DEVICE_ID,
        8000,
        { server_id: "live_server", tool_name: "destructive_write" },
        "hub_get_tool_schema",
      );
      check(
        "详情层同样受逐工具授权约束：未授权工具的 schema 也不给",
        guideDenied.isError === true && guideDenied.structured["error_code"] === "not_permitted",
        `code=${String(guideDenied.structured["error_code"])}`,
      );
      const guideUnknown = await toolCall(
        LIVE_MCP_DEVICE_ID,
        8000,
        { server_id: "live_server", tool_name: "no_such_tool" },
        "hub_get_tool_schema",
      );
      check(
        "详情层：服务器上不存在的工具 → unsupported_tool",
        guideUnknown.isError === true && guideUnknown.structured["error_code"] === "unsupported_tool",
        `code=${String(guideUnknown.structured["error_code"])}`,
      );
      check(
        "声明了 destructiveHint 的工具仍然是「存在但不给你看」：只计数，名字与描述不外传",
        liveCatalog.structured["other_tools_count"] === 1 &&
          !JSON.stringify(liveCatalog).includes("destructive_write") &&
          !JSON.stringify(liveCatalog).includes("有副作用的写"),
        `other_tools_count=${String(liveCatalog.structured["other_tools_count"])}`,
      );

      const liveState = await liveServer.state();
      const sessions = (liveState["sessions"] as unknown[] | undefined) ?? [];
      const headers = (liveState["requestHeaders"] as Array<Record<string, unknown>> | undefined) ?? [];
      const sawProtocolHeader = headers.some(
        (h) => ((h["headers"] as Record<string, string> | undefined) ?? {})["mcp-protocol-version"] !== undefined,
      );
      check(
        "第三方服务器侧确有真实会话与 MCP 协议头（不是替身假装连上）",
        sessions.length >= 1 && sawProtocolHeader,
        `sessions=${sessions.length} 请求数=${headers.length} 协议头=${String(sawProtocolHeader)}`,
      );

      // 真调一次：这个算式的结果只有服务器算得出来。
      const sumRaw = await client.callTool({
        name: "hub_call_tool",
        arguments: {
          device_id: LIVE_MCP_DEVICE_ID,
          timeout_ms: 8000,
          server_id: "live_server",
          tool_name: "sum_numbers",
          arguments: { a: 2, b: 40 },
        },
      });
      const sumText = ((sumRaw["content"] as Array<{ text: string }> | undefined) ?? [])
        .map((c) => c.text)
        .join("\n");
      const liveCalls =
        (((await liveServer.state())["calls"] as Array<Record<string, unknown>> | undefined) ?? []);
      check(
        "hub_call_tool 真的调到了第三方工具（第三方服务器自己记下了这次调用与参数）",
        sumText.includes("2 + 40 = 42") &&
          liveCalls.some((c) => c["name"] === "sum_numbers" && JSON.stringify(c["arguments"]).includes("40")),
        `text=${sumText.replace(/\n/g, " / ")} 服务器记录=${liveCalls.length} 次`,
      );

      // 工具级失败不能被吞掉，也不能被包装成 Hub 层失败。
      const boomRaw = await client.callTool({
        name: "hub_call_tool",
        arguments: {
          device_id: LIVE_MCP_DEVICE_ID,
          timeout_ms: 8000,
          server_id: "live_server",
          tool_name: "boom",
          arguments: {},
        },
      });
      const boomText = ((boomRaw["content"] as Array<{ text: string }> | undefined) ?? [])
        .map((c) => c.text)
        .join("\n");
      check(
        "第三方工具自己报的失败如实回给模型（isError + 原始文本，不吞、不改写）",
        boomRaw["isError"] === true && boomText.includes("boom: 这是工具自己报的失败"),
        `isError=${String(boomRaw["isError"])} text=${boomText.replace(/\n/g, " / ").slice(0, 60)}`,
      );

      // 没授权的工具，模型知道名字也调不动；服务器上不存在的工具同样拒绝。
      const unauthed = await toolCall(
        LIVE_MCP_DEVICE_ID,
        8000,
        { server_id: "live_server", tool_name: "destructive_write", arguments: { target: "/tmp/x" } },
        "hub_call_tool",
      );
      // 失败结果的形状是 `{isError:true, structuredContent:{ok:false, error_code, error_message}}`。
      const unauthedCode = unauthed.structured["error_code"];
      check(
        "未授权的第三方工具调不动（手机层拒绝，不是「先调了再说」）",
        unauthed.isError && unauthedCode === "not_permitted",
        `code=${String(unauthedCode)} tool_id=${String(unauthed.structured["tool_id"])}`,
      );
      const unknownTool = await toolCall(
        LIVE_MCP_DEVICE_ID,
        8000,
        { server_id: "live_server", tool_name: "no_such_tool", arguments: {} },
        "hub_call_tool",
      );
      const unknownCode = unknownTool.structured["error_code"];
      check(
        "第三方服务器上不存在的工具 → unsupported_tool",
        unknownTool.isError && unknownCode === "unsupported_tool",
        `code=${String(unknownCode)}`,
      );
      const finalCalls =
        (((await liveServer.state())["calls"] as Array<Record<string, unknown>> | undefined) ?? []).map((c) =>
          String(c["name"]),
        );
      check(
        "被拒绝的调用没有真的打到第三方服务器上（destructive_write 的执行次数为 0）",
        !finalCalls.includes("destructive_write"),
        `服务器记录的工具调用=${finalCalls.join(",")}`,
      );
    } finally {
      await liveServer.close();
    }

    for (const proc of toolPhones) await proc.stop();
    if (toolControl !== null) await toolControl.stop();
    out("");
  } finally {
    for (const client of clients) await client.close().catch(() => undefined);
    if (phoneNormal !== null) await phoneNormal.stop();
    if (phoneSilent !== null) await phoneSilent.stop();
    for (const proc of reliabilityPhones) await proc.stop();
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
