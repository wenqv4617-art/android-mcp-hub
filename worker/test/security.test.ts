// ============================================================================
//  安全约束 —— 把任务书里的"不许"变成会失败的测试
// ============================================================================
//
//  任务书「二、最高优先级：生产环境隔离」的 10 条里有好几条是"静态的"：
//  没有 D1、没有真实凭据、不引用既有项目。这类要求如果只写在文档里，
//  半年后没人记得；写成测试就会在有人违规的当下就炸。
//
//  分成两组：
//    A. 仓库静态检查 —— 扫描真实文件与配置
//    B. 运行时检查   —— 令牌不进日志、日志会脱敏
// ============================================================================

import { describe, expect, it } from "vitest";
import { readFileSync, readdirSync, statSync } from "node:fs";
import { join, relative, resolve } from "node:path";
import { redact } from "../src/util/log";
import { loadConfig } from "../src/config";
import {
  capturedLogs,
  clearCapturedLogs,
  connectMockPhone,
  makeRig,
  pingPhoneDirect,
  TEST_DEVICE_ID,
  TEST_DEVICE_TOKEN,
  TEST_MCP_TOKEN,
} from "./harness/env";

// ---------------------------------------------------------------------------
//  仓库扫描工具
// ---------------------------------------------------------------------------

const REPO_ROOT = resolve(__dirname, "..", "..");

const SKIP_DIRS = new Set([
  "node_modules",
  ".git",
  ".wrangler",
  ".gradle",
  "build",
  "dist",
  "coverage",
  ".idea",
  "captures",
  "test-reports",
  "e2e",
]);

function walk(dir: string, out: string[] = []): string[] {
  for (const entry of readdirSync(dir)) {
    if (SKIP_DIRS.has(entry)) continue;
    const full = join(dir, entry);
    const st = statSync(full);
    if (st.isDirectory()) walk(full, out);
    else if (st.size < 2_000_000) out.push(full);
  }
  return out;
}

/**
 * 可以安全当文本读的文件（跳过二进制/apk/jar）。
 *
 * ⚠ 也跳过 lockfile：`package-lock.json` 里全是 npm 包的 integrity 哈希，
 *   它们长得就像高熵令牌，扫它们只会制造假警报。我们关心的是**人写的东西**。
 */
const SKIP_FILES = new Set(["package-lock.json", "pnpm-lock.yaml", "yarn.lock"]);

function textFiles(): string[] {
  return walk(REPO_ROOT).filter(
    (f) => !/\.(apk|aab|jar|so|png|jpg|jpeg|webp|keystore|jks)$/.test(f) && !SKIP_FILES.has(f.split("/").pop()!),
  );
}

/** 去掉注释，只留真正的代码 —— 避免"注释里提到 D1"被当成"用了 D1"。 */
function stripComments(text: string): string {
  return text.replace(/\/\*[\s\S]*?\*\//g, "").replace(/^\s*\/\/.*$/gm, "");
}

function rel(path: string): string {
  return relative(REPO_ROOT, path);
}

// ---------------------------------------------------------------------------
//  A. 仓库静态检查
// ---------------------------------------------------------------------------

describe("安全 / 仓库里不许有真实凭据", () => {
  it("没有 GitHub 令牌形状的字符串", () => {
    const hits = textFiles().filter((f) => /gh[pousr]_[A-Za-z0-9]{20,}/.test(readFileSync(f, "utf8")));
    expect(hits.map(rel)).toEqual([]);
  });

  it("没有 PEM 私钥", () => {
    const hits = textFiles().filter((f) => /-----BEGIN [A-Z ]*PRIVATE KEY-----/.test(readFileSync(f, "utf8")));
    expect(hits.map(rel)).toEqual([]);
  });

  it("没有 Cloudflare API 令牌形状的字符串（40 位 base62 且非占位符）", () => {
    // 已知占位符 / 测试常量除外。
    const allow = /REPLACE|PLACEHOLDER|example|not-a-secret|0{16,}/i;
    const hits: string[] = [];
    for (const f of textFiles()) {
      const text = readFileSync(f, "utf8");
      for (const match of text.matchAll(/\b[A-Za-z0-9_-]{40}\b/g)) {
        if (allow.test(text.slice(Math.max(0, match.index - 60), match.index + 60))) continue;
        if (/^[0-9a-f]{40}$/.test(match[0])) continue; // sha256 hex 之类
        hits.push(`${rel(f)}: ${match[0].slice(0, 6)}…`);
      }
    }
    expect(hits).toEqual([]);
  });

  it("没有 .dev.vars / .env 之类的真实配置文件（只允许示例）", () => {
    const suspicious = walk(REPO_ROOT)
      .map(rel)
      .filter((p) => /(^|\/)(\.dev\.vars|\.env|\.env\..+|secrets\.json|credentials\.json)$/.test(p));
    expect(suspicious).toEqual([]);
  });

  it(".gitignore 覆盖了凭据与构建产物", () => {
    const ignore = readFileSync(join(REPO_ROOT, ".gitignore"), "utf8");
    for (const pattern of [".dev.vars", "node_modules", ".wrangler", "*.keystore", "*.jks", "local.properties"]) {
      expect(ignore, `.gitignore 应忽略 ${pattern}`).toContain(pattern);
    }
  });
});

describe("安全 / 与既有生产项目完全隔离", () => {
  it("没有任何指向既有项目的名字、域名或仓库", () => {
    const forbidden = [/jianchuan/i, /见川/, /wenqv4617-art\/jianchuan/];
    // ⚠ 这个测试文件自己当然得写下这些词（不然没法扫），所以跳过它。
    const selfPath = __filename;
    const hits = textFiles().filter((f) => {
      if (f === selfPath) return false;
      const text = readFileSync(f, "utf8");
      return forbidden.some((re) => re.test(text));
    });
    expect(hits.map(rel)).toEqual([]);
  });

  it("wrangler 配置里没有 account_id / zone / route / 环境分区", () => {
    const raw = readFileSync(join(REPO_ROOT, "worker", "wrangler.jsonc"), "utf8");
    // 去掉注释再判断，避免注释里提到这些字段名就误报。
    const stripped = raw.replace(/\/\*[\s\S]*?\*\//g, "").replace(/^\s*\/\/.*$/gm, "");
    for (const key of ["account_id", "zone_name", "route", "routes", "workers_dev"]) {
      expect(stripped, `wrangler.jsonc 不该出现 ${key}`).not.toMatch(new RegExp(`"${key}"`));
    }
  });

  it("wrangler 配置里没有任何数据库 / 存储绑定", () => {
    const stripped = readFileSync(join(REPO_ROOT, "worker", "wrangler.jsonc"), "utf8")
      .replace(/\/\*[\s\S]*?\*\//g, "")
      .replace(/^\s*\/\/.*$/gm, "");
    for (const key of ["d1_databases", "kv_namespaces", "r2_buckets", "queues", "hyperdrive", "analytics_engine_datasets"]) {
      expect(stripped, `wrangler.jsonc 不该出现 ${key}`).not.toMatch(new RegExp(`"${key}"`));
    }
  });

  it("源代码里没有 D1 / KV / R2 的用法", () => {
    const srcFiles = textFiles().filter((f) => /worker\/src\/.*\.ts$/.test(rel(f)));
    expect(srcFiles.length).toBeGreaterThan(10);
    for (const f of srcFiles) {
      const text = stripComments(readFileSync(f, "utf8"));
      for (const token of ["D1Database", "env.DB", "KVNamespace", "R2Bucket", "new Queue"]) {
        expect(text, `${rel(f)} 的代码里不该出现 ${token}`).not.toContain(token);
      }
    }
  });

  it("Env 类型只有两个 Durable Object 绑定", () => {
    const text = stripComments(readFileSync(join(REPO_ROOT, "worker", "src", "env.ts"), "utf8"));
    const bindings = [...text.matchAll(/^\s+(PHONE_SESSION|MCP_SESSION|MCP_ACCESS_TOKEN|DEVICE_TOKENS|ENVIRONMENT|DEFAULT_DEVICE_ID|[A-Z_]+)\??:/gm)];
    const names = bindings.map((m) => m[1]!);
    expect(names).toContain("PHONE_SESSION");
    expect(names).toContain("MCP_SESSION");
    expect(names).not.toContain("DB");
    expect(names).not.toContain("D1Database");
  });
});

// ---------------------------------------------------------------------------
//  B. 运行时检查
// ---------------------------------------------------------------------------

describe("安全 / 令牌不进日志", () => {
  it("手机连接 + 鉴权失败 + 成功 ping 的全过程中，日志里不出现任何明文令牌", async () => {
    const rig = await makeRig();
    clearCapturedLogs();

    // 一次成功的连接 + ping
    await connectMockPhone(rig, TEST_DEVICE_ID, "normal");
    await pingPhoneDirect(rig, TEST_DEVICE_ID, { requestId: "sec-1" });

    // 一次失败的鉴权（错误令牌会被写进日志路径）
    const { default: worker } = await import("../src/index");
    const badHeaders = new Headers();
    badHeaders.set("Upgrade", "websocket");
    badHeaders.set("Connection", "Upgrade");
    badHeaders.set("X-Device-Id", TEST_DEVICE_ID);
    badHeaders.set("Authorization", "Bearer this-is-a-wrong-token-value");
    await worker.fetch(new Request("https://android-mcp-hub.test/phone/ws", { method: "GET", headers: badHeaders }), rig.env, {} as ExecutionContext);

    const logs = capturedLogs().join("\n");
    expect(logs.length).toBeGreaterThan(0);
    expect(logs).not.toContain(TEST_DEVICE_TOKEN);
    expect(logs).not.toContain(TEST_MCP_TOKEN);
    expect(logs).not.toContain("this-is-a-wrong-token-value");
  });

  it("日志里出现 deviceId 是可以的（不是秘密），但令牌字段一律脱敏", () => {
    const redacted = redact({
      deviceId: TEST_DEVICE_ID,
      Authorization: `Bearer ${TEST_DEVICE_TOKEN}`,
      token: TEST_DEVICE_TOKEN,
      tokenSha256: "abc",
      nested: { password: "hunter2", apiKey: "k", ok: "fine" },
    }) as Record<string, unknown>;

    expect(redacted["deviceId"]).toBe(TEST_DEVICE_ID);
    expect(redacted["Authorization"]).toBe("[redacted]");
    expect(redacted["token"]).toBe("[redacted]");
    expect(redacted["tokenSha256"]).toBe("[redacted]");
    const nested = redacted["nested"] as Record<string, unknown>;
    expect(nested["password"]).toBe("[redacted]");
    expect(nested["apiKey"]).toBe("[redacted]");
    expect(nested["ok"]).toBe("fine");
  });

  it("配置里读出来的凭据只有摘要，没有明文", async () => {
    const rig = await makeRig();
    const config = loadConfig(rig.env);
    const serialized = JSON.stringify(config);
    expect(serialized).not.toContain(TEST_DEVICE_TOKEN);
    // 摘要应当是 64 位小写 hex
    for (const cred of config.credentials) {
      expect(cred.tokenSha256).toMatch(/^[0-9a-f]{64}$/);
    }
  });
});
