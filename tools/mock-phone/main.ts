#!/usr/bin/env node
// ============================================================================
//  模拟手机 —— 命令行入口
// ============================================================================
//
//  用法：
//    npm run mock:phone -- \
//      --url ws://127.0.0.1:8787/phone/ws \
//      --device test-phone-1 \
//      --token "$MCP_DEVICE_TOKEN" \
//      --scenario normal \
//      --duration 30
//
//  ⚠ 令牌只从命令行参数或环境变量 `MCP_DEVICE_TOKEN` 读，**任何情况下都不写进
//     源码或配置文件**。没给令牌就直接退出，不会尝试匿名连接。
//
//  这个脚本用 `ws`（而不是 Node 内置的 WebSocket）是因为内置实现不允许设置
//  自定义请求头，而这个握手必须在 `Authorization` 头里带设备令牌 ——
//  把令牌塞进 URL query 会被各级日志记下来，是明确的坏做法。
// ============================================================================

import { WebSocket } from "ws";
import {
  attachMockPhone,
  MockPhone,
  PHONE_SCENARIOS,
  TOOL_BEHAVIORS,
  type PhoneScenario,
  type ToolBehavior,
} from "./client";

interface CliOptions {
  url: string;
  device: string;
  token: string;
  scenario: PhoneScenario;
  /** Gate 2：对 tool_request 的行为，与 scenario 独立。 */
  toolBehavior: ToolBehavior;
  durationMs: number;
  heartbeatMs: number;
  delayMs: number;
}

function parseArgs(argv: string[]): CliOptions {
  const get = (name: string): string | undefined => {
    const i = argv.indexOf(`--${name}`);
    return i >= 0 ? argv[i + 1] : undefined;
  };

  const url = get("url") ?? process.env["MOCK_PHONE_URL"] ?? "ws://127.0.0.1:8787/phone/ws";
  const device = get("device") ?? process.env["MOCK_PHONE_DEVICE"] ?? "";
  const token = get("token") ?? process.env["MCP_DEVICE_TOKEN"] ?? "";
  const scenarioRaw = (get("scenario") ?? "normal") as PhoneScenario;
  const toolBehaviorRaw = (get("tool-behavior") ?? "normal") as ToolBehavior;

  if (!PHONE_SCENARIOS.includes(scenarioRaw)) {
    fail(`未知 scenario "${scenarioRaw}"，可选：${PHONE_SCENARIOS.join(", ")}`);
  }
  if (!TOOL_BEHAVIORS.includes(toolBehaviorRaw)) {
    fail(`未知 tool-behavior "${toolBehaviorRaw}"，可选：${TOOL_BEHAVIORS.join(", ")}`);
  }
  if (device === "") fail("缺少 --device（或 MOCK_PHONE_DEVICE）");
  if (token === "") {
    console.error("错误：没有提供设备令牌。");
    console.error("请用 `--token <值>` 或环境变量 `MCP_DEVICE_TOKEN` 传入（脚本不会把令牌写进任何文件）。");
    process.exit(2);
  }

  return {
    url,
    device,
    token,
    scenario: scenarioRaw,
    toolBehavior: toolBehaviorRaw,
    durationMs: Number(get("duration") ?? "30") * 1000,
    heartbeatMs: Number(get("heartbeat") ?? "30") * 1000,
    delayMs: Number(get("delay-ms") ?? "500"),
  };
}

function fail(message: string): never {
  console.error(`参数错误：${message}`);
  console.error("运行 `npm run mock:phone -- --help` 查看用法。");
  process.exit(2);
}

function printHelp(): void {
  console.log(`模拟手机 WebSocket 客户端

  --url <ws-url>        默认 ws://127.0.0.1:8787/phone/ws
  --device <device-id>  必填。设备 ID。
  --token <token>       必填。设备令牌，建议改用环境变量 MCP_DEVICE_TOKEN。
  --scenario <name>     默认 normal。可选：${PHONE_SCENARIOS.join(", ")}
  --duration <秒>       默认 30。到期自动退出。
  --heartbeat <秒>      默认 30。心跳间隔。
  --delay-ms <毫秒>     仅 late 场景使用，默认 500。
  --tool-behavior <name>  Gate 2：对 tool_request 的行为，默认 normal。可选：${TOOL_BEHAVIORS.join(", ")}
  --help                显示本帮助。
`);
}

async function main(): Promise<void> {
  const argv = process.argv.slice(2);
  if (argv.includes("--help") || argv.includes("-h")) {
    printHelp();
    return;
  }
  const opts = parseArgs(argv);

  const phone = new MockPhone({
    scenario: opts.scenario,
    toolBehavior: opts.toolBehavior,
    deviceId: opts.device,
    delayMs: opts.delayMs,
    onLog: (line) => console.log(`[phone] ${line}`),
  });

  console.log(`[phone] 连接 ${opts.url}`);
  console.log(`[phone] device=${opts.device} scenario=${opts.scenario} tool-behavior=${opts.toolBehavior}`);

  const socket = new WebSocket(opts.url, {
    headers: {
      // 令牌只在内存里过一遍，绝不落盘、绝不打印。
      Authorization: `Bearer ${opts.token}`,
      "X-Device-Id": opts.device,
    },
  });

  attachMockPhone(phone, socket as unknown as Parameters<typeof attachMockPhone>[1]);

  let heartbeatTimer: NodeJS.Timeout | null = null;

  socket.on("open", () => {
    console.log("[phone] WebSocket 已打开");
    heartbeatTimer = setInterval(() => {
      if (socket.readyState === WebSocket.OPEN) {
        socket.send(JSON.stringify({ type: "heartbeat", timestamp: Date.now() }));
      }
    }, opts.heartbeatMs);
  });

  socket.on("error", (err: Error) => {
    console.error(`[phone] socket error: ${err.message}`);
  });

  socket.on("unexpected-response", (_req: unknown, res: { statusCode?: number }) => {
    console.error(`[phone] 握手被拒绝：HTTP ${res.statusCode ?? "?"}`);
    process.exitCode = 1;
  });

  socket.on("close", (code: number, reason: Buffer) => {
    console.log(`[phone] 连接关闭 code=${code} reason=${reason.toString()}`);
  });

  const stop = (): void => {
    if (heartbeatTimer !== null) clearInterval(heartbeatTimer);
    console.log(
    `[phone] 退出。收到 ${phone.received.length} 帧，回了 ${phone.sentPongs.length} 个 pong、` +
      `${phone.sentToolResults.length} 个 tool_result。`,
  );
    try {
      socket.close(1000, "done");
    } catch {
      /* 已经关了 */
    }
  };

  setTimeout(() => {
    stop();
    // 给 close 帧一点时间飞出去
    setTimeout(() => process.exit(0), 200);
  }, opts.durationMs);
}

main().catch((err) => {
  console.error(`[phone] 未捕获错误：${String(err)}`);
  process.exit(1);
});
