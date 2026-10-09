// ============================================================================
//  vitest setup：把 Node 缺失的几个 Workers 全局补上
// ============================================================================
//
//  只补三样，都是被测代码真的用到的：
//
//  1. `WebSocketPair` —— Worker/DO 创建成对 socket 用的构造器。
//     这里接到 harness/runtime.ts 的假 socket 上。
//
//  2. `Response` —— Node 的原生 Response **不允许 status=101**（undici 会抛
//     "init[\"status\"] must be in the range of 200 to 599"），而 DO 的
//     WebSocket 升级路径必须返回 `new Response(null, {status: 101, webSocket})`。
//     所以这里包一层子类：status 101 时先用 200 骗过原生构造，再把真实状态
//     与 webSocket 句柄挂回来。其他状态一律原样交给原生实现，行为不变。
//
//  3. 一个更容易读的 `structuredClone` 兜底（Node 24 自带，这里只是断言它存在）。
//
//  4. **日志捕获**：把 console.log/warn/error 收进一个缓冲区，默认不打印。
//     这不只是为了测试输出好看 —— 有了缓冲区，"任何令牌都不许出现在日志里"
//     就从一句口号变成了可以断言的约束（见 security.test.ts）。
//     需要看日志时设 `GATE0_VERBOSE=1`。
//
//  ⚠ 这个文件的存在本身就是一条环境限制的证据，见 TEST_REPORT.md 的
//     「NOT RUN」小节。
// ============================================================================

import { createWebSocketPair, type FakeWebSocket } from "./runtime";

const NativeResponse = globalThis.Response;

class ShimResponse extends NativeResponse {
  /** 这里放宽成 WebSocket 以匹配 Response 的形状，实际是 FakeWebSocket。 */
  override readonly webSocket: WebSocket | null;
  private readonly realStatus: number;

  constructor(body?: BodyInit | null, init?: ResponseInit & { webSocket?: FakeWebSocket }) {
    const { webSocket, ...rest } = init ?? {};
    const status = rest.status ?? 200;
    // 原生 Response 不接受 101，先降级构造，再把真实状态盖回来。
    if (status === 101) super(null, { ...rest, status: 200 });
    else super(body, rest);
    this.realStatus = status;
    this.webSocket = (webSocket ?? null) as unknown as WebSocket | null;
  }

  override get status(): number {
    return this.realStatus;
  }

  override get ok(): boolean {
    return this.realStatus >= 200 && this.realStatus < 300;
  }
}

globalThis.Response = ShimResponse as unknown as typeof Response;

(globalThis as unknown as { WebSocketPair: unknown }).WebSocketPair = function WebSocketPair() {
  return createWebSocketPair();
};

if (typeof structuredClone !== "function") {
  throw new Error("这个 Node 没有 structuredClone，测试 harness 无法保证存储边界语义");
}

// ---------------------------------------------------------------------------
//  日志捕获
// ---------------------------------------------------------------------------

const verbose = process.env["GATE0_VERBOSE"] === "1";

interface LogBuffer {
  lines: string[];
  clear(): void;
}

const buffer: string[] = [];

(globalThis as unknown as { __LOG_BUFFER: LogBuffer }).__LOG_BUFFER = {
  lines: buffer,
  clear() {
    buffer.length = 0;
  },
};

function capture(stream: "out" | "err", original: (...args: unknown[]) => void) {
  return (...args: unknown[]) => {
    const line = args
      .map((a) => (typeof a === "string" ? a : safeStringify(a)))
      .join(" ");
    buffer.push(line);
    if (verbose) {
      if (stream === "err") process.stderr.write(line + "\n");
      else process.stdout.write(line + "\n");
    }
    void original;
  };
}

function safeStringify(value: unknown): string {
  try {
    return JSON.stringify(value) ?? String(value);
  } catch {
    return String(value);
  }
}

console.log = capture("out", console.log.bind(console));
console.warn = capture("err", console.warn.bind(console));
console.error = capture("err", console.error.bind(console));

export {};
