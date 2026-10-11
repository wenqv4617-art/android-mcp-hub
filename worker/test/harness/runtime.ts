// ============================================================================
//  Node 端 Workers 运行时替身（shim）
// ============================================================================
//
//  ── 为什么需要它 ──────────────────────────────────────────────────────────
//  真实的 Workers 运行时是 `workerd`。在本项目当前的开发容器（DSHA Android
//  runtime，aarch64）里 workerd **无法启动**：
//
//    $ node_modules/@cloudflare/workerd-linux-arm64/bin/workerd --version
//    MmapAligned() failed - unable to allocate with tag
//      (hint=..., size=1073741824, alignment=1073741824)
//    FATAL ERROR: Out of memory trying to allocate internal tcmalloc data
//    TCMalloc assumes a 48-bit virtual address space size
//    → exit 134 (SIGABRT)
//
//  `ulimit -v` 已经是 unlimited；`TCMALLOC_ADDRESS_BITS` 是 tcmalloc 的编译期
//  常量，LD_PRELOAD 覆盖不了静态链接进 workerd 的 tcmalloc。因此
//  `wrangler dev --local` 与 `@cloudflare/vitest-pool-workers` 都跑不起来。
//
//  ⇒ 这个 shim 是**测试替身**，不是运行时。它只实现本项目真正用到的那几个
//     Workers 特性，好让 Durable Object 的状态机逻辑能在 Node 里被真实执行。
//
//  ⚠ 诚实声明：基于 shim 的测试**不能**证明"在真实 workerd 上也通过"。
//     TEST_REPORT.md 里凡是需要 workerd 的测试都标为 NOT RUN 并附上面这段错误。
//
//  ── 实现的东西 ────────────────────────────────────────────────────────────
//    · DurableObjectStorage 的内存版（get/put/delete/list/getAlarm/setAlarm/
//      deleteAlarm/transaction），读写都做 structuredClone，模拟"跨存储边界"，
//      防止代码依赖对象同一性这种在真实运行时会被序列化打断的行为；
//    · WebSocketPair / WebSocket：成对的假 socket，支持 accept / send / close /
//      serializeAttachment，以及"未 accept 前先缓存投递"；
//    · DurableObjectState：acceptWebSocket(带 tag)、getWebSockets(tag)、
//      blockConcurrencyWhile、storage；
//    · DurableObjectNamespace：idFromName/get/fetch，外加 `evict()` 用来模拟
//      "对象被驱逐后重建"（保留存储、丢掉内存），这是 Gate 0 的一条硬要求。
// ============================================================================

export type SocketEventListener = (ev: unknown) => void;

// ---------------------------------------------------------------------------
//  WebSocket
// ---------------------------------------------------------------------------

export class FakeWebSocket {
  static readonly CONNECTING = 0;
  static readonly OPEN = 1;
  static readonly CLOSING = 2;
  static readonly CLOSED = 3;

  readyState = 0;
  peer: FakeWebSocket | null = null;

  /** 测试侧的可观测记录。 */
  readonly sent: string[] = [];
  /** 这个端点**收到**过的帧（测试断言服务端回了什么）。 */
  readonly receivedFrames: string[] = [];
  closeInfo: { code: number; reason: string } | null = null;

  private attachment: unknown = null;
  private readonly listeners = new Map<string, Set<SocketEventListener>>();
  private readonly queued: string[] = [];

  accept(): void {
    if (this.readyState === 0) {
      this.readyState = 1;
      queueMicrotask(() => this.dispatch("open", {}));
    }
    // ⚠ 刻意**不**顺手把对端也打开：两端各自 accept 才各自 OPEN，
    //    和 workerd 的语义一致（升级响应返回给客户端后，客户端还得自己 accept）。
    //    在此之前发给它的帧会进 queued，accept 时再补投 —— 这样测试可以在
    //    accept 之前先挂好 message 监听，不会漏掉 welcome 帧。
    const pending = this.queued.splice(0, this.queued.length);
    for (const data of pending) this.deliver(data);
  }

  /** 把一条帧投给自己：先记账，再触发 message 事件。 */
  deliver(data: string): void {
    this.receivedFrames.push(data);
    this.dispatch("message", { data });
  }

  send(data: string | ArrayBuffer | ArrayBufferView): void {
    if (this.readyState !== 1) throw new Error("WebSocket is not open (readyState=" + this.readyState + ")");
    const text = typeof data === "string" ? data : "<binary>";
    this.sent.push(text);
    const peer = this.peer;
    if (peer === null) return;
    const payload = text;
    queueMicrotask(() => {
      if (peer.readyState === 0) peer.queued.push(payload);
      else if (peer.readyState === 1) peer.deliver(payload);
    });
  }

  close(code = 1000, reason = ""): void {
    if (this.readyState === 3) return;
    this.readyState = 3;
    this.closeInfo = { code, reason };
    queueMicrotask(() => {
      this.dispatch("close", { code, reason, wasClean: code === 1000 });
      const peer = this.peer;
      if (peer !== null && peer.readyState !== 3) {
        peer.readyState = 3;
        peer.closeInfo = { code, reason };
        peer.dispatch("close", { code, reason, wasClean: code === 1000 });
      }
    });
  }

  /** 模拟"网络掉线"：不发 close 帧，直接两边都变成 CLOSED 并触发 close 事件。 */
  dropConnection(code = 1006, reason = "abnormal closure"): void {
    this.close(code, reason);
  }

  addEventListener(type: string, fn: SocketEventListener): void {
    let set = this.listeners.get(type);
    if (set === undefined) {
      set = new Set();
      this.listeners.set(type, set);
    }
    set.add(fn);
  }

  removeEventListener(type: string, fn: SocketEventListener): void {
    this.listeners.get(type)?.delete(fn);
  }

  serializeAttachment(value: unknown): void {
    // 真实运行时是结构化克隆，这里照做，避免测试依赖对象同一性。
    this.attachment = value === null || value === undefined ? null : structuredClone(value);
  }

  deserializeAttachment(): unknown {
    return this.attachment === null ? null : structuredClone(this.attachment);
  }

  dispatch(type: string, event: unknown): void {
    const set = this.listeners.get(type);
    if (set === undefined) return;
    for (const fn of [...set]) {
      try {
        fn(event);
      } catch (err) {
        this.dispatchErrors.push(err);
      }
    }
  }

  readonly dispatchErrors: unknown[] = [];
}

/** 对应 Cloudflare 的 `new WebSocketPair()`。 */
export function createWebSocketPair(): { 0: FakeWebSocket; 1: FakeWebSocket } {
  const client = new FakeWebSocket();
  const server = new FakeWebSocket();
  client.peer = server;
  server.peer = client;
  return { 0: client, 1: server };
}

// ---------------------------------------------------------------------------
//  DurableObjectStorage
// ---------------------------------------------------------------------------

/**
 * 一次故障注入（Gate 0.3）。
 *
 * 目的是把"存储坏了会怎么样"变成可断言的事实，而不是靠读代码猜。
 * 参考真实行为：`ctx.storage.put` 在对象被驱逐、存储后端不可用时**会抛**，
 * 而 DO 里一个没接住的异常会连带把整个调用栈丢掉。
 */
export interface InjectedFailure {
  /** 在哪个存储方法上失败。 */
  op: "get" | "put" | "setAlarm" | "getAlarm" | "deleteAlarm";
  /**
   * 只在这个存储键上失败。`undefined` = 所有键都失败。
   * `getAlarm` / `setAlarm` / `deleteAlarm` 没有键参数，只能不填。
   */
  key?: string;
  /** 还要失败几次。默认 1。 */
  times?: number;
  /** 触发时抛什么。默认一个普通 Error（不携带任何凭据）。 */
  error?: Error;
  /**
   * Gate 1：`true` = "悬挂"而不是"抛错" —— 这次调用**永不返回**。
   *
   * 真实世界里对应"存储后端长时间无响应 / 对象正在被驱逐"。它和 `error`
   * 走的是两条完全不同的代码路径：抛错靠 try/catch 兜住，不返回只能靠
   * "绝不把结算排在 I/O 之后"来兜住。没有这一种注入，"存储不返回会不会
   * 让调用方挂住"就只能靠读代码猜 —— 那是 Gate 0.3 审计 K13 的由来。
   */
  hang?: boolean;
  /** 已经命中几次 —— 测试可以断言"确实触发过"，避免门禁因为没生效而空过。 */
  hits: number;
}

/** 一个永远 pending 的 Promise。用于模拟"存储调用不返回"。 */
function neverSettles<T>(): Promise<T> {
  return new Promise<T>(() => {
    /* 故意什么都不做 */
  });
}

export class FakeDurableObjectStorage {
  private readonly map = new Map<string, unknown>();
  private alarmAt: number | null = null;

  /** 记录每一次写，测试可以断言"某次失败没有落盘"。 */
  readonly writes: string[] = [];

  /** 已登记的故障注入。 */
  readonly injected: InjectedFailure[] = [];

  /**
   * 登记一次故障注入。
   *
   * 语义是"接下来 [times] 次匹配 (op, key) 的调用抛错"，命中即递减。
   * 刻意不做成"永久失败"：大多数测试只想让**结算的那一次**写失败，
   * 永久失败会让后续的断言（比如"之后还能正常工作"）没法写。
   */
  failNext(op: InjectedFailure["op"], opts: { key?: string; times?: number; error?: Error } = {}): InjectedFailure {
    const failure: InjectedFailure = {
      op,
      ...(opts.key !== undefined ? { key: opts.key } : {}),
      times: opts.times ?? 1,
      ...(opts.error !== undefined ? { error: opts.error } : {}),
      hits: 0,
    };
    this.injected.push(failure);
    return failure;
  }

  /**
   * 登记一次"悬挂"注入（Gate 1 审计发现项 A）。
   *
   * 语义是"接下来 [times] 次匹配 (op, key) 的调用**永不返回**"：不抛错、
   * 也不落盘，就一直 pending 着。
   */
  hangNext(op: InjectedFailure["op"], opts: { key?: string; times?: number } = {}): InjectedFailure {
    const failure: InjectedFailure = {
      op,
      ...(opts.key !== undefined ? { key: opts.key } : {}),
      times: opts.times ?? 1,
      hang: true,
      hits: 0,
    };
    this.injected.push(failure);
    return failure;
  }

  /** 清空所有还没用完的故障注入。 */
  clearInjectedFailures(): void {
    this.injected.length = 0;
  }

  /** 命中检查：返回命中的那条注入（并消耗一次），没命中返回 null。 */
  private hit(op: InjectedFailure["op"], key: string | undefined): InjectedFailure | null {
    for (const failure of this.injected) {
      if (failure.op !== op) continue;
      if (failure.key !== undefined && failure.key !== key) continue;
      const remaining = failure.times ?? 1;
      if (remaining <= 0) continue;
      failure.times = remaining - 1;
      failure.hits += 1;
      return failure;
    }
    return null;
  }

  /**
   * 命中"抛错"型注入就抛；命中"悬挂"型则把它返回给调用方 ——
   * 由调用方去永不返回（这样才能保住各个方法的返回类型）。
   */
  private maybeFail(op: InjectedFailure["op"], key: string | undefined): InjectedFailure | null {
    const failure = this.hit(op, key);
    if (failure === null) return null;
    if (failure.hang === true) return failure;
    throw failure.error ?? new Error(`injected ${op} failure`);
  }

  async get<T>(key: string): Promise<T | undefined>;
  async get<T>(keys: string[]): Promise<Map<string, T>>;
  async get<T>(keyOrKeys: string | string[]): Promise<T | undefined | Map<string, T>> {
    if (Array.isArray(keyOrKeys)) {
      const out = new Map<string, T>();
      for (const key of keyOrKeys) {
        if (this.maybeFail("get", key) !== null) return neverSettles<Map<string, T>>();
        const value = this.map.get(key);
        if (value !== undefined) out.set(key, structuredClone(value) as T);
      }
      return out;
    }
    if (this.maybeFail("get", keyOrKeys) !== null) return neverSettles<T>();
    const value = this.map.get(keyOrKeys);
    return value === undefined ? undefined : (structuredClone(value) as T);
  }

  async put<T>(keyOrEntries: string | Record<string, unknown>, value?: T): Promise<void> {
    if (typeof keyOrEntries === "string") {
      if (this.maybeFail("put", keyOrEntries) !== null) return neverSettles<void>();
      this.map.set(keyOrEntries, structuredClone(value));
      this.writes.push(keyOrEntries);
      return;
    }
    let hanging = false;
    for (const k of Object.keys(keyOrEntries)) {
      if (this.maybeFail("put", k) !== null) hanging = true;
    }
    for (const [k, v] of Object.entries(keyOrEntries)) {
      this.map.set(k, structuredClone(v));
      this.writes.push(k);
    }
    if (hanging) return neverSettles<void>();
  }

  // ⚠ 这里必须支持**数组删除**。真实的 Durable Object 存储 API 有两种形态：
  //     delete(key: string): Promise<boolean>
  //     delete(keys: string[]): Promise<number>
  //   早先这个替身只接受 string，于是 `storage.delete([k1, k2])` 会走进
  //   `Map.delete(array)` —— 静默返回 false、**一个键都没删**。OAuth 的撤销端点
  //   正是用数组删访问令牌与刷新令牌的，所以这个替身缺陷会让"撤销之后令牌仍然可用"
  //   这类测试**以错误的理由变绿**。Gate 1 修掉了它（见 TEST_REPORT 的替身偏差清单）。
  async delete(key: string): Promise<boolean>;
  async delete(keys: string[]): Promise<number>;
  async delete(keyOrKeys: string | string[]): Promise<boolean | number> {
    if (Array.isArray(keyOrKeys)) {
      let deleted = 0;
      for (const key of keyOrKeys) {
        if (this.map.delete(key)) deleted += 1;
      }
      return deleted;
    }
    return this.map.delete(keyOrKeys);
  }

  async deleteAll(): Promise<void> {
    this.map.clear();
  }

  async list<T>(options?: { prefix?: string; limit?: number }): Promise<Map<string, T>> {
    const out = new Map<string, T>();
    const prefix = options?.prefix ?? "";
    const limit = options?.limit ?? Infinity;
    for (const [k, v] of this.map) {
      if (!k.startsWith(prefix)) continue;
      if (out.size >= limit) break;
      out.set(k, structuredClone(v) as T);
    }
    return out;
  }

  async getAlarm(): Promise<number | null> {
    if (this.maybeFail("getAlarm", undefined) !== null) return neverSettles<number | null>();
    return this.alarmAt;
  }

  async setAlarm(scheduledTime: number | Date): Promise<void> {
    if (this.maybeFail("setAlarm", undefined) !== null) return neverSettles<void>();
    this.alarmAt = scheduledTime instanceof Date ? scheduledTime.getTime() : scheduledTime;
  }

  async deleteAlarm(): Promise<void> {
    if (this.maybeFail("deleteAlarm", undefined) !== null) return neverSettles<void>();
    this.alarmAt = null;
  }

  async transaction<T>(fn: (txn: FakeDurableObjectStorage) => Promise<T>): Promise<T> {
    return fn(this);
  }

  async sync(): Promise<void> {
    /* 内存实现没有需要同步的东西 */
  }

  /** 测试辅助：直接看底层有没有这个键（不走克隆）。 */
  has(key: string): boolean {
    return this.map.has(key);
  }
}

// ---------------------------------------------------------------------------
//  DurableObjectState
// ---------------------------------------------------------------------------

interface AcceptedSocket {
  ws: FakeWebSocket;
  tags: string[];
}

export interface ShimDurableObject {
  fetch?(request: Request): Response | Promise<Response>;
  alarm?(): void | Promise<void>;
  webSocketMessage?(ws: unknown, message: string | ArrayBuffer): void | Promise<void>;
  webSocketClose?(ws: unknown, code: number, reason: string, wasClean: boolean): void | Promise<void>;
  webSocketError?(ws: unknown, error: unknown): void | Promise<void>;
}

export class FakeDurableObjectState {
  readonly storage = new FakeDurableObjectStorage();
  private readonly sockets: AcceptedSocket[] = [];
  private blocking: Promise<unknown> = Promise.resolve();
  /** 由 namespace 在实例化后回填。 */
  owner: ShimDurableObject | null = null;

  /** 记录所有未被捕获的事件处理异常 —— 测试可以断言它为 null。 */
  readonly handlerErrors: unknown[] = [];

  id = { name: "unknown", toString: () => "unknown", equals: () => false };

  async blockConcurrencyWhile<T>(fn: () => Promise<T>): Promise<T> {
    const run = (async () => fn())();
    this.blocking = run.catch((err) => {
      this.handlerErrors.push(err);
    });
    return run;
  }

  acceptWebSocket(ws: FakeWebSocket, tags?: string[]): void {
    this.sockets.push({ ws, tags: tags ?? [] });
    ws.accept();

    ws.addEventListener("message", (ev) => {
      const data = (ev as { data: string }).data;
      void this.guard(() => this.owner?.webSocketMessage?.(ws, data));
    });
    ws.addEventListener("close", (ev) => {
      const e = ev as { code: number; reason: string; wasClean: boolean };
      const idx = this.sockets.findIndex((s) => s.ws === ws);
      if (idx >= 0) this.sockets.splice(idx, 1);
      void this.guard(() => this.owner?.webSocketClose?.(ws, e.code, e.reason, e.wasClean));
    });
    ws.addEventListener("error", (ev) => {
      void this.guard(() => this.owner?.webSocketError?.(ws, ev));
    });
  }

  getWebSockets(tag?: string): FakeWebSocket[] {
    return this.sockets.filter((s) => tag === undefined || s.tags.includes(tag)).map((s) => s.ws);
  }

  async runAlarm(): Promise<void> {
    await this.guard(() => this.owner?.alarm?.());
  }

  /** 等所有在途事件处理跑完 —— 测试里用来避免依赖固定 sleep。 */
  async settle(): Promise<void> {
    await this.blocking;
    for (let i = 0; i < 12; i += 1) await new Promise((r) => setTimeout(r, 0));
  }

  private async guard(fn: () => unknown): Promise<void> {
    try {
      await fn();
    } catch (err) {
      this.handlerErrors.push(err);
    }
    // 让链式 microtask 继续跑
    await Promise.resolve();
  }
}

// ---------------------------------------------------------------------------
//  DurableObjectNamespace
// ---------------------------------------------------------------------------

export class ShimDurableObjectId {
  constructor(readonly name: string) {}
  toString(): string {
    return this.name;
  }
  equals(other: unknown): boolean {
    return other instanceof ShimDurableObjectId ? other.name === this.name : false;
  }
}

type Factory = (ctx: FakeDurableObjectState, env: unknown) => ShimDurableObject;

export class ShimDurableObjectNamespace {
  /** name → 存活实例。`evict()` 之后这里没有条目，但 `storages` 还在。 */
  private readonly live = new Map<string, { instance: ShimDurableObject; ctx: FakeDurableObjectState }>();
  private readonly storages = new Map<string, FakeDurableObjectStorage>();
  private readonly liveConstructors = new Map<string, number>();

  constructor(
    private readonly factory: Factory,
    private readonly env: unknown,
  ) {}

  idFromName(name: string): ShimDurableObjectId {
    return new ShimDurableObjectId(name);
  }

  idFromString(id: string): ShimDurableObjectId {
    return new ShimDurableObjectId(id);
  }

  newUniqueId(): ShimDurableObjectId {
    return new ShimDurableObjectId(crypto.randomUUID());
  }

  get(id: ShimDurableObjectId): { fetch: (req: Request) => Promise<Response>; id: ShimDurableObjectId } {
    return {
      id,
      fetch: (request: Request) => this.fetchOn(id.name, request),
    };
  }

  /** 类 DurableObjectStub 的调用入口。 */
  fetchOn(name: string, request: Request): Promise<Response> {
    const ctx = this.stateFor(name);
    const instance = this.instanceFor(name, ctx);
    const fn = instance.fetch;
    if (typeof fn !== "function") {
      return Promise.resolve(new Response("no fetch handler", { status: 500 }));
    }
    return Promise.resolve(fn.call(instance, request));
  }

  /** 测试辅助：直接拿到 state（看指标、socket、alarm）。 */
  stateFor(name: string): FakeDurableObjectState {
    const entry = this.live.get(name);
    if (entry !== undefined) return entry.ctx;
    const ctx = new FakeDurableObjectState();
    const storage = this.storages.get(name);
    if (storage !== undefined) {
      // 复用旧存储：这就是"对象被驱逐后重建，持久化状态还在"。
      // （socket 是内存态，真实休眠里会活下来，这里刻意不保留 —— 见文件头说明。）
      (ctx as { storage: FakeDurableObjectStorage }).storage = storage;
    } else {
      this.storages.set(name, ctx.storage);
    }
    ctx.id = { name, toString: () => name, equals: () => false };
    return ctx;
  }

  /** 取实例；不存在就新建（模拟 DO 被唤醒）。 */
  instanceFor(name: string, ctxIn?: FakeDurableObjectState): ShimDurableObject {
    const entry = this.live.get(name);
    if (entry !== undefined) return entry.instance;
    const ctx = ctxIn ?? this.stateFor(name);
    this.storages.set(name, ctx.storage);
    const instance = this.factory(ctx, this.env);
    ctx.owner = instance;
    this.live.set(name, { instance, ctx });
    this.liveConstructors.set(name, (this.liveConstructors.get(name) ?? 0) + 1);
    return instance;
  }

  /** 模拟对象被驱逐：内存全丢，存储保留。 */
  evict(name: string): void {
    this.live.delete(name);
  }

  /** 这台对象一共被构造过几次（重建测试用）。 */
  constructionCount(name: string): number {
    return this.liveConstructors.get(name) ?? 0;
  }

  liveCount(): number {
    return this.live.size;
  }

  names(): string[] {
    return [...this.storages.keys()];
  }

  /** 直接拿到某个对象的 state（必要时先唤醒它）。 */
  ctxOf(name: string): FakeDurableObjectState {
    this.instanceFor(name);
    const entry = this.live.get(name);
    if (entry === undefined) throw new Error("unreachable");
    return entry.ctx;
  }
}
