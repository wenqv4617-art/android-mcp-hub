// ============================================================================
//  模拟手机 WebSocket 客户端
// ============================================================================
//
//  两种用法：
//    1. 命令行：`npm run mock:phone -- --url ws://127.0.0.1:8787/phone/ws
//       --device test-phone-1 --token <token> --scenario normal`
//    2. 被测试引用（worker/test/*.test.ts），此时 socket 由 harness 注入。
//
//  `scenario` 是为了把 Gate 0 要求覆盖的那些**恶劣**情况变成一条命令：
//
//    normal           收到 phone_ping 就回 phone_pong（默认）
//    no-response      收到 ping 不回 —— 用来验证服务端超时
//    wrong-request-id 回一个服务端没发过的 request_id —— 必须被拒绝
//    duplicate-pong   同一请求回两次 —— 第二次必须被当作 unknown 拒绝
//    late             延迟 delayMs 再回 —— 用来验证"超时后到达的响应不得完成请求"
//    drop-after-ping  收到 ping 立刻断开 —— 未完成请求必须显式失败
//    noisy            穿插未知类型帧 / 非法 JSON / 超大帧 —— 都必须被拒绝且不影响后续
//    binary           发二进制帧 —— 必须被拒绝
//    hello-mismatch   hello 里报另一个 deviceId —— 只记账、不崩
//
//  Gate 2 起它还认识通用工具帧 `tool_request`，行为由独立的 `toolBehavior`
//  控制（见 [ToolBehavior]）。两者刻意分开：一个场景描述"这台手机对 ping 多坏"，
//  另一个描述"它对工具调用多坏"，组合起来才是完整的"恶劣手机"。
//
//  ⚠ 这个文件里**没有任何真实凭据**：token 一律从命令行/环境变量传入。
// ============================================================================

export type PhoneScenario =
  | "normal"
  | "no-response"
  | "wrong-request-id"
  | "duplicate-pong"
  | "late"
  | "drop-after-ping"
  | "noisy"
  | "binary"
  | "hello-mismatch";

export const PHONE_SCENARIOS: PhoneScenario[] = [
  "normal",
  "no-response",
  "wrong-request-id",
  "duplicate-pong",
  "late",
  "drop-after-ping",
  "noisy",
  "binary",
  "hello-mismatch",
];

/**
 * 模拟手机对 `tool_request` 的行为。
 *
 * `normal` 走的是**白名单 + 固定返回值**：它能回答的能力就是
 * [MOCK_CAPABILITIES] 里登记的那几个，别的名字一律回 `unsupported_tool`。
 * 这与真实的 Android 侧是同一个形状 —— 那一边也只有一张显式白名单。
 */
export type ToolBehavior =
  | "normal"
  | "no-response"
  | "wrong-request-id"
  | "duplicate"
  | "error"
  | "unsupported"
  | "reserved-key"
  | "malformed";

export const TOOL_BEHAVIORS: ToolBehavior[] = [
  "normal",
  "no-response",
  "wrong-request-id",
  "duplicate",
  "error",
  "unsupported",
  "reserved-key",
  "malformed",
];

/**
 * 模拟能力表：工具名 → 一个**无副作用**的固定返回值。
 *
 * 这些数字是编的，而且是刻意编得不像任何真实设备（42% / 未充电 / 省电模式）。
 * 它们的作用只有一个：让"这条结果确实从手机来"这件事在 E2E 里可断言。
 */
export const MOCK_CAPABILITIES: Record<string, (args: Record<string, unknown>) => Record<string, unknown>> = {
  battery_get_status: () => ({
    battery_level_percent: 42,
    charging: false,
    charging_source: "unavailable",
    power_save_mode: true,
  }),
  // Gate 2.5A+B：同样是编的固定值。刻意选一个"Wi-Fi、已验证"的组合，
  // 让 E2E 能断言字段确实是从手机这一侧过来的。
  device_get_network: () => ({
    connected: true,
    validated: true,
    transport: "wifi",
    transports: ["wifi"],
    metered: false,
    internet_capable: true,
  }),
  // 128 GB 总 / 48 GB 可用：used = 80 GB，usage_percent = 62.5，
  // 三个数字互相对得上，方便 E2E 直接验算关系而不是只验"有键"。
  device_get_storage: () => ({
    volume: "internal_data",
    total_bytes: 128_000_000_000,
    available_bytes: 48_000_000_000,
    used_bytes: 80_000_000_000,
    usage_percent: 62.5,
  }),
  // ---------------------------------------------------------------------
  // Gate 3：模拟"Android 手机 + 一台已连接的第三方 MCP 服务器"。
  //
  // 这两个命令名是**聚合保留名**，第三方工具名只出现在 arguments 里 ——
  // 所以这个替身同样不会把 `battery_get_status` 之类当成 hub 命令。
  //
  // 目录里**故意**放一个未授权的工具（`write_note`）：它必须只被计数、
  // 描述不外传。这样"目录边界"在 E2E 里也是可断言的，而不是只靠单测。
  // ---------------------------------------------------------------------
  hub_list_tools: () => ({
    servers: [{ server_id: "mock_server", server_name: "Mock MCP", availability: "online" }],
    tools: [
      {
        server_id: "mock_server",
        tool_name: "echo_text",
        display_name: "Echo text",
        description: "把传入的 text 原样返回（模拟第三方只读工具）",
        input_schema: {
          type: "object",
          properties: { text: { type: "string" } },
          required: ["text"],
          additionalProperties: false,
        },
        authorization_state: "authorized",
        availability: "online",
      },
      {
        server_id: "mock_server",
        tool_name: "write_note",
        display_name: "Write note",
        description: "写一条笔记（模拟**未授权**的第三方工具，不该外传）",
        input_schema: { type: "object", properties: { note: { type: "string" } } },
        authorization_state: "not_authorized",
        availability: "online",
      },
    ],
  }),
  hub_call_tool: (args) => {
    const toolName = typeof args["tool_name"] === "string" ? args["tool_name"] : "";
    const inner = args["arguments"];
    const innerRecord = typeof inner === "object" && inner !== null ? (inner as Record<string, unknown>) : {};
    const text = typeof innerRecord["text"] === "string" ? innerRecord["text"] : "";
    return {
      server_id: typeof args["server_id"] === "string" ? args["server_id"] : "",
      tool_name: toolName,
      content: [{ type: "text", text: `mock-mcp echo: ${text}` }],
      content_types: ["text"],
      is_error: false,
    };
  },
};

/** 模拟手机对一次 tool_request 的回复记录（测试用来断言"回了几个、什么形状"）。 */
export interface SentToolResult {
  requestId: string;
  toolName: string;
  ok: boolean;
  afterFrameIndex: number;
}

/**
 * 手机替身对 `tool_request` 的自定义处理（测试脚手架用）。
 *
 * 返回 `null` 表示"这个命令名不归我管" → 回落到 [MOCK_CAPABILITIES] 白名单；
 * 返回对象表示"这就是结果"；抛错表示"转发失败"，替身会回一个 `tool_failed`。
 *
 * 允许 `Promise`，因为真实链路（手机 → 第三方 MCP 服务器）本来就是异步的：
 * 桥接层要真的发 HTTP 请求，不能假装同步。
 */
export type MockToolHandler = (
  toolName: string,
  args: Record<string, unknown>,
) => Record<string, unknown> | MockToolRefusal | null | Promise<Record<string, unknown> | MockToolRefusal | null>;

/**
 * "手机拒绝这次调用" —— 是一个 `ok:false` 的 tool_result（帧层错误码闭集里的一员），
 * 不是工具自己报的失败。用它来区分两种失败：
 *   · 未授权 / 不认识这个工具 → 手机层拒绝（[MockToolRefusal]）；
 *   · 第三方工具执行失败 → `ok:true` + `is_error:true`（正常结果对象）。
 *
 * 用类（而不是 `{error:...}` 这种约定）是因为结果对象里本来就可能有一个叫
 * `error` 的字段，靠键名判断迟早会认错。
 */
export class MockToolRefusal {
  constructor(
    readonly code: string,
    readonly message: string,
  ) {}
}

/** 只依赖标准 WebSocket 的最小接口，好让测试注入假 socket。 */
export interface SocketLike {
  readyState: number;
  send(data: string | ArrayBuffer): void;
  close(code?: number, reason?: string): void;
  addEventListener(type: string, listener: (event: never) => void): void;
}

export interface MockPhoneOptions {
  scenario?: PhoneScenario;
  /** Gate 2：对 `tool_request` 的行为。默认 normal（走 [MOCK_CAPABILITIES]）。 */
  toolBehavior?: ToolBehavior;
  /** 只用于 hello 帧的自报字段，不参与鉴权。 */
  deviceId?: string;
  appVersion?: string;
  /** `late` 场景的延迟毫秒数。 */
  delayMs?: number;
  /**
   * 自定义 `tool_request` 处理，优先于本地白名单（仅 `toolBehavior === "normal"` 时生效）。
   * 统一给"手机替身真的去连一台第三方 MCP 服务器"用（见 `mcp-bridge.ts`）。
   */
  toolHandler?: MockToolHandler;
  /**
   * 对第 N 个（0 基）phone_ping **故意不回应**，其余的照常回。
   * 用来构造"并发请求里只有一个超时"的场景，验证请求之间不会串。
   */
  skipPingIndexes?: number[];
  /** 每条收到的原始帧都会先过这里（测试用来做断言）。 */
  onFrame?: (raw: string) => void;
  onLog?: (line: string) => void;
}

export interface SentPong {
  requestId: string;
  deviceTimestamp: number;
  /** 是在哪一帧之后发的（用于断言顺序）。 */
  afterFrameIndex: number;
}

/**
 * 协议状态机本身。与传输无关，因此可以脱离真实 socket 单测。
 */
export class MockPhone {
  readonly received: string[] = [];
  readonly sentPongs: SentPong[] = [];
  readonly sentFrames: string[] = [];
  /** Gate 2：回过的 tool_result（含被故意发错的那些）。 */
  readonly sentToolResults: SentToolResult[] = [];
  helloSeen = false;
  welcomeSeen = false;
  socketClosed: { code: number; reason: string } | null = null;
  /** drop-after-ping 场景置位：附着层看到它就把 socket 关掉。 */
  wantCloseAfterPing = false;

  private readonly scenario: PhoneScenario;
  private readonly toolBehavior: ToolBehavior;
  private readonly opts: MockPhoneOptions;
  /** 收到过多少个 phone_ping（0 基索引用）。 */
  pingCount = 0;
  /** 收到过多少个 tool_request（0 基索引用）。 */
  toolRequestCount = 0;

  constructor(opts: MockPhoneOptions = {}) {
    this.opts = opts;
    this.scenario = opts.scenario ?? "normal";
    this.toolBehavior = opts.toolBehavior ?? "normal";
  }

  /** 收到一条服务端帧时的行为。返回需要立刻回发的帧（已编码）。 */
  handleFrame(raw: string, send: (data: string | ArrayBuffer) => void): void {
    this.received.push(raw);
    this.opts.onFrame?.(raw);

    let frame: Record<string, unknown>;
    try {
      frame = JSON.parse(raw) as Record<string, unknown>;
    } catch {
      this.log(`收到非法 JSON，忽略：${truncate(raw)}`);
      return;
    }

    switch (frame["type"]) {
      case "welcome":
        this.welcomeSeen = true;
        this.log(
          `welcome: generation=${String(frame["connection_generation"])} ` +
            `heartbeat=${String(frame["heartbeat_interval_ms"])}ms ping_timeout=${String(frame["ping_timeout_ms"])}ms`,
        );
        if (this.scenario === "hello-mismatch") {
          this.sendJson(send, {
            type: "hello",
            device_id: "some-other-phone",
            app_version: this.opts.appVersion ?? "mock-0.1.0",
            protocol_version: "phone-link-v1",
          });
        } else {
          this.sendJson(send, {
            type: "hello",
            device_id: this.opts.deviceId ?? null,
            app_version: this.opts.appVersion ?? "mock-0.1.0",
            protocol_version: "phone-link-v1",
          });
        }
        this.helloSeen = true;
        return;

      case "heartbeat_ack":
        this.log("heartbeat_ack");
        return;

      case "error":
        this.log(`服务端 error: code=${String(frame["code"])} message=${String(frame["message"])}`);
        return;

      case "phone_ping": {
        const requestId = typeof frame["request_id"] === "string" ? frame["request_id"] : "";
        const index = this.pingCount;
        this.pingCount += 1;
        this.log(`phone_ping #${index} request_id=${requestId}`);
        if (this.scenario === "noisy") this.sendNoise(send);
        if (this.opts.skipPingIndexes?.includes(index) === true) {
          this.log(`按配置跳过第 ${index} 个 ping，不回 pong`);
          return;
        }
        this.respondToPing(send, requestId);
        return;
      }

      case "tool_request": {
        const requestId = typeof frame["request_id"] === "string" ? frame["request_id"] : "";
        const toolName = typeof frame["tool_name"] === "string" ? frame["tool_name"] : "";
        const rawArguments = frame["arguments"];
        const args =
          typeof rawArguments === "object" && rawArguments !== null && !Array.isArray(rawArguments)
            ? (rawArguments as Record<string, unknown>)
            : {};
        const index = this.toolRequestCount;
        this.toolRequestCount += 1;
        this.log(`tool_request #${index} tool=${toolName} request_id=${requestId}`);
        // 可能要走异步的第三方转发，所以这里不阻塞帧循环。
        void this.handleToolRequest(send, requestId, toolName, args);
        return;
      }

      default:
        this.log(`忽略未知帧类型：${String(frame["type"])}`);
        return;
    }
  }

  /**
   * `tool_request` 的异步外壳：先给自定义 [MockPhoneOptions.toolHandler] 一次机会，
   * 它说"不归我管"（`null`）才落到下面那个同步的白名单实现。
   *
   * 只在 `toolBehavior === "normal"` 时桥接：其它 behavior 是用来构造协议的坏情况的，
   * 混进真实网络只会让失败原因变模糊。
   */
  private async handleToolRequest(
    send: (data: string | ArrayBuffer) => void,
    requestId: string,
    toolName: string,
    args: Record<string, unknown>,
  ): Promise<void> {
    const handler = this.opts.toolHandler;
    if (handler !== undefined && this.toolBehavior === "normal") {
      let bridged: Record<string, unknown> | MockToolRefusal | null;
      try {
        bridged = await handler(toolName, args);
      } catch (error) {
        const detail = error instanceof Error ? error.name : "unknown";
        this.log(`toolHandler 抛错（${detail}），回 tool_failed`);
        this.sendToolResult(send, requestId, toolName, false, null, {
          code: "tool_failed",
          message: "模拟手机的第三方转发失败",
        });
        return;
      }
      if (bridged instanceof MockToolRefusal) {
        this.log(`toolHandler 拒绝了 ${toolName}：${bridged.code}`);
        this.sendToolResult(send, requestId, toolName, false, null, {
          code: bridged.code,
          message: bridged.message,
        });
        return;
      }
      if (bridged !== null) {
        this.log(`toolHandler 处理了 ${toolName}（异步）`);
        this.sendToolResult(send, requestId, toolName, true, bridged);
        return;
      }
    }
    this.respondToToolRequest(send, requestId, toolName, args);
  }

  /**
   * 对一次 `tool_request` 的回复。
   *
   * 默认路径刻意是**白名单**：不在 [MOCK_CAPABILITIES] 里的工具名一律回
   * `unsupported_tool`。这样"Worker 把没注册的工具名发过来了"在 E2E 里
   * 会变成一条可观测的失败，而不是一个"手机居然也答应了"的假成功。
   */
  private respondToToolRequest(
    send: (data: string | ArrayBuffer) => void,
    requestId: string,
    toolName: string,
    args: Record<string, unknown>,
  ): void {
    switch (this.toolBehavior) {
      case "no-response":
        this.log("toolBehavior=no-response：故意不回 tool_result");
        return;

      case "wrong-request-id":
        this.sendToolResult(send, `${requestId}-not-a-real-id`, toolName, true, {});
        return;

      case "duplicate":
        this.sendToolResult(send, requestId, toolName, true, this.mockResultFor(toolName, args));
        this.sendToolResult(send, requestId, toolName, true, this.mockResultFor(toolName, args));
        return;

      case "error":
        this.sendToolResult(send, requestId, toolName, false, null, {
          code: "tool_failed",
          message: "模拟能力执行失败",
        });
        return;

      case "unsupported":
        this.sendToolResult(send, requestId, toolName, false, null, {
          code: "unsupported_tool",
          message: `模拟手机不认识工具 ${toolName}`,
        });
        return;

      case "reserved-key":
        // 一台被改造过的手机试图用 result 覆盖信封字段。
        this.sendToolResult(send, requestId, toolName, true, {
          ...(this.mockResultFor(toolName, args) ?? {}),
          ok: false,
          request_id: "hijacked",
          device_id: "another-phone",
        });
        return;

      case "malformed":
        // 缺 request_id 的 tool_result —— 服务端必须当成 invalid 拒绝，而不是崩。
        this.sendToolResult(send, null, toolName, true, this.mockResultFor(toolName, args));
        return;

      default: {
        const result = this.mockResultFor(toolName, args);
        if (result === null) {
          this.log(`toolBehavior=normal：本地白名单里没有 ${toolName}，回 unsupported_tool`);
          this.sendToolResult(send, requestId, toolName, false, null, {
            code: "unsupported_tool",
            message: `模拟手机的白名单里没有工具 ${toolName}`,
          });
          return;
        }
        this.sendToolResult(send, requestId, toolName, true, result);
        return;
      }
    }
  }

  /** 本地白名单查表；不在表里返回 null（调用方负责回 unsupported_tool）。 */
  private mockResultFor(toolName: string, args: Record<string, unknown>): Record<string, unknown> | null {
    const capability = MOCK_CAPABILITIES[toolName];
    return capability === undefined ? null : capability(args);
  }

  private sendToolResult(
    send: (data: string | ArrayBuffer) => void,
    requestId: string | null,
    toolName: string,
    ok: boolean,
    result: Record<string, unknown> | null,
    error?: { code: string; message: string },
  ): void {
    const frame: Record<string, unknown> = {
      type: "tool_result",
      // requestId 为 null 时**故意不带这个字段**：用来构造 malformed 帧。
      ...(requestId !== null ? { request_id: requestId } : {}),
      ok,
      ...(ok && result !== null ? { result } : {}),
      ...(!ok && error !== undefined ? { error } : {}),
    };
    this.sentToolResults.push({ requestId: requestId ?? "<missing>", toolName, ok, afterFrameIndex: this.received.length });
    this.sendJson(send, frame);
  }

  private respondToPing(send: (data: string | ArrayBuffer) => void, requestId: string): void {
    switch (this.scenario) {
      case "no-response":
        this.log("scenario=no-response：故意不回 pong");
        return;

      case "drop-after-ping":
        this.log("scenario=drop-after-ping：立刻关闭连接");
        this.wantCloseAfterPing = true;
        return;

      case "wrong-request-id":
        this.sendPong(send, `${requestId}-not-a-real-id`);
        return;

      case "duplicate-pong":
        this.sendPong(send, requestId);
        this.sendPong(send, requestId);
        return;

      case "late":
        setTimeout(() => this.sendPong(send, requestId), this.opts.delayMs ?? 500);
        return;

      case "binary": {
        const buf = new TextEncoder().encode(JSON.stringify({ type: "phone_pong", request_id: requestId })).buffer;
        this.sentFrames.push("<binary>");
        this.log("scenario=binary：发二进制帧");
        send(buf as ArrayBuffer);
        return;
      }

      default:
        this.sendPong(send, requestId);
        return;
    }
  }

  private sendNoise(send: (data: string | ArrayBuffer) => void): void {
    this.sentFrames.push("{not json");
    send("{not json");
    this.sendJson(send, { type: "definitely_not_a_frame_type" });
    this.sendJson(send, { type: "phone_pong" }); // 缺 request_id → invalid
  }

  private sendPong(send: (data: string | ArrayBuffer) => void, requestId: string): void {
    const deviceTimestamp = Date.now();
    this.sentPongs.push({ requestId, deviceTimestamp, afterFrameIndex: this.received.length });
    this.sendJson(send, { type: "phone_pong", request_id: requestId, timestamp: deviceTimestamp });
  }

  private sendJson(send: (data: string | ArrayBuffer) => void, frame: Record<string, unknown>): void {
    const raw = JSON.stringify(frame);
    this.sentFrames.push(raw);
    send(raw);
  }

  log(line: string): void {
    this.opts.onLog?.(line);
  }
}

function truncate(text: string): string {
  return text.length <= 120 ? text : `${text.slice(0, 120)}…`;
}

/**
 * 把 MockPhone 接到一个真实（或伪造）socket 上。
 *
 * @returns `stop()` —— 关闭 socket 并摘掉监听。
 */
export function attachMockPhone(
  phone: MockPhone,
  socket: SocketLike,
  opts: { isOpen?: () => boolean } = {},
): { stop: () => void; whenOpen: Promise<void> } {
  let openResolve!: () => void;
  const whenOpen = new Promise<void>((resolve) => {
    openResolve = resolve;
  });

  const isOpen = opts.isOpen ?? (() => socket.readyState === 1);
  if (isOpen()) openResolve();
  else {
    socket.addEventListener("open", () => openResolve());
  }

  socket.addEventListener("message", (event: never) => {
    const data = (event as unknown as { data: string }).data;
    phone.handleFrame(typeof data === "string" ? data : "<binary-in>", (out) => {
      socket.send(out);
    });
    if (phone.wantCloseAfterPing) {
      phone.wantCloseAfterPing = false;
      try {
        socket.close(1001, "mock phone going away");
      } catch {
        /* 已经关了 */
      }
    }
  });

  socket.addEventListener("close", (event: never) => {
    const e = event as unknown as { code: number; reason: string };
    phone.socketClosed = { code: e.code ?? 1006, reason: e.reason ?? "" };
    phone.log(`连接关闭：code=${e.code} reason=${e.reason ?? ""}`);
  });

  return {
    whenOpen,
    stop: () => {
      try {
        socket.close(1000, "client stopping");
      } catch {
        /* 已经关了 */
      }
    },
  };
}
