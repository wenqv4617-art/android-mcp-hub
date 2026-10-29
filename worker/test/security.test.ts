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
import { basename, join, relative, resolve } from "node:path";
import { ERROR_CATEGORIES, sanitizeLogFields, UNCLASSIFIED_ERROR, WITHHELD } from "../src/util/log";
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
    // basename 而不是 split("/")：Windows 上的路径分隔符是反斜杠。
    (f) => !/\.(apk|aab|jar|so|png|jpg|jpeg|webp|keystore|jks)$/.test(f) && !SKIP_FILES.has(basename(f)),
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
        // 必须含至少一位数字才算"像随机 base62 凭据"。
        // 理由：这条规则要抓的是 40 位**随机**串；而恰好 40 个字符的**标识符**（全大写加下划线、
        // 或者纯驼峰）不含数字，会被误报 —— 本仓库的文档里就引用了
        // DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION（AndroidX 自动加的自定义权限）和
        // defaultExpressiveNavigationBarItemColors（Compose 内部类名）这两个正好 40 字符的名字。
        // 代价是量化的：随机 base62 的 40 位串一位数字都没有的概率是 (52/62)^40 ≈ 0.06%，
        // 也就是这条规则会漏掉约万分之六的真令牌。用"文件路径白名单"去消误报会开出更大的口子。
        if (!/[0-9]/.test(match[0])) continue;
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
    const srcFiles = textFiles().filter((f) => /^worker[\\/]src[\\/].*\.ts$/.test(rel(f)));
    expect(srcFiles.length).toBeGreaterThan(10);
    for (const f of srcFiles) {
      const text = stripComments(readFileSync(f, "utf8"));
      for (const token of ["D1Database", "env.DB", "KVNamespace", "R2Bucket", "new Queue"]) {
        expect(text, `${rel(f)} 的代码里不该出现 ${token}`).not.toContain(token);
      }
    }
  });

  it('Worker 源码里的 fetch 不使用 redirect: "error"（线上运行时只接受 follow / manual）', () => {
    const srcFiles = textFiles().filter((f) => /^worker[\\/]src[\\/].*\.ts$/.test(rel(f)));
    expect(srcFiles.length).toBeGreaterThan(10);
    for (const f of srcFiles) {
      const text = stripComments(readFileSync(f, "utf8"));
      expect(text, `${rel(f)} 用了 redirect: "error"，部署后会直接抛 TypeError`).not.toMatch(/redirect\s*:\s*["']error["']/);
    }
  });

  it("Env 类型显式列出四个 Durable Object 绑定，且没有任何 D1 / KV / R2 / Queue", () => {
    const text = stripComments(readFileSync(join(REPO_ROOT, "worker", "src", "env.ts"), "utf8"));

    // ★ 精确断言：`Env` 里**声明为 *Namespace 类型**的字段只能有这三个。
    //   这条断言的价值在于"新增一个绑定会立刻变红"—— 加绑定时必须显式改这里，
    //   于是"我们又多加了一块持久化"这件事不可能悄悄发生。
    const doBindings = [...text.matchAll(/^\s+([A-Z][A-Z0-9_]*)\??:\s*[A-Za-z]*Namespace\b/gm)].map((m) => m[1]!);
    expect(doBindings.sort()).toEqual(["GATEWAY_STORE", "MCP_SESSION", "OAUTH_STORE", "PHONE_SESSION"]);

    // 仍然没有任何数据库 / 存储 / 队列绑定（任务书：不使用 D1）。
    expect(text).not.toMatch(/\b(D1Database|KVNamespace|R2Bucket|Queue|Hyperdrive)\b/);

    const fieldNames = [...text.matchAll(/^\s+([A-Z][A-Z0-9_]*)\??:/gm)].map((m) => m[1]!);
    expect(fieldNames).not.toContain("DB");
    expect(fieldNames).not.toContain("DATABASE");
  });
});

//  A2. 工作流闸门：默认不许有部署能力，只允许一个具名的例外
// ---------------------------------------------------------------------------
//
//  任务书写着「不开启 GitHub Actions 自动部署」。这条最早是**结构上成立**的：
//  仓库里只有一个手动构建 APK 的工作流。后来 Gate 1 收尾加了一个在 ubuntu 上跑
//  测试的工作流（Termux 装不上 workerd），再后来用户明确要求一个**必须人工确认**
//  的部署工作流。
//
//  于是规则从"一律不许"变成"默认不许，且只允许一个具名的例外"：
//
//    * android-debug-apk.yml / worker-gate1-check.yml —— 纯测试类，**零部署能力**；
//    * deploy-worker.yml —— 唯一的部署工作流，必须同时满足 13 条约束（见下），
//      Gate 2 联调期间另加 1 条（分支与确认串必须一一对应），共 14 条。
//
//  两份规则都写成**纯函数**：输入文件内容，输出违规清单。理由是"只看当前那个文件"
//  证明不了任何东西 —— 下面那组反向测试把篡改过的内容喂进同一个函数，要求它必须报错。
//
//  ⚠ 这是**文本级**检查，不是 YAML 解析（仓库里没有 yaml 依赖，也不想为此引一个）。
//    它挡不住刻意用 YAML 花招绕过的人，但足以挡住无意的越界。

/** 只允许手动触发的**纯测试**工作流：不许有任何部署能力。 */
const TEST_ONLY_WORKFLOWS = [
  "android-debug-apk.yml",
  "android-online-acceptance.yml",
  "android-device-acceptance.yml",
  "android-release-apk.yml",
  "worker-gate1-check.yml",
] as const;

/** 唯一的部署工作流。它存在的意义就是"部署"，所以对它适用另一套规则。 */
const DEPLOY_WORKFLOW = "deploy-worker.yml";

/**
 * 允许被这份工作流**手动**部署的分支白名单。
 *
 * `feat/gate2-core` 是 Gate 2 联调期间进白名单的**一次性**临时路径：用户只有手机，
 * 没法在本地终端部署，所以让 Actions 从那条分支手动派发；它必须逐字输入另一个
 * 确认串 `DEPLOY-GATE2`，确认串与分支一一对应，不能互换。
 * `feat/gate2-5-native-tools` 是同一种临时路径（Gate 2.5 内置工具目录 + 网络/存储
 * 能力），确认串是 `DEPLOY-GATE2-5`。
 * 两条临时路径并入 gate0 之后，要连同下面那两条确认串约束、以及对应的反向用例
 * 一起删掉 —— 白名单回到只有 `gate0`。
 */
const DEPLOY_BRANCH_ALLOWLIST: readonly string[] = [
  "gate0",
  "feat/gate2-core",
  "feat/gate2-5-native-tools",
];

const WORKFLOW_ALLOWLIST: readonly string[] = [...TEST_ONLY_WORKFLOWS, DEPLOY_WORKFLOW];

/** 去掉 YAML 注释行 —— 工作流开头的注释里写着"没有任何 deploy 步骤"，不剥掉会误命中。 */
function stripYamlComments(text: string): string {
  return text
    .split("\n")
    .filter((line) => !/^\s*#/.test(line))
    .join("\n");
}

const WORKFLOW_DIR = join(REPO_ROOT, ".github", "workflows");

/** 仓库里所有工作流文件；**没有 CI 也是一种合法状态**，所以这里不报错、回空数组。 */
function workflowFiles(): string[] {
  try {
    return readdirSync(WORKFLOW_DIR)
      .filter((f) => /\.ya?ml$/.test(f))
      .map((f) => join(WORKFLOW_DIR, f));
  } catch {
    return [];
  }
}

function readWorkflow(name: string): string {
  return readFileSync(join(WORKFLOW_DIR, name), "utf8");
}

/** 取某个 key 之下、缩进更深的那一段（文本级近似：够用，且不会误伤同级的下一段）。 */
function blockAfter(body: string, key: string): string {
  const lines = body.split("\n");
  const start = lines.findIndex((l) => new RegExp(`^\\s*${key}:`).test(l));
  if (start < 0) return "";
  const indent = (lines[start]!.match(/^\s*/) ?? [""])[0].length;
  const buf: string[] = [];
  for (let i = start + 1; i < lines.length; i++) {
    const line = lines[i]!;
    if (line.trim() === "") continue;
    if ((line.match(/^\s*/) ?? [""])[0].length <= indent) break;
    buf.push(line);
  }
  return buf.join("\n");
}

/**
 * 自动部署触发器。
 * `workflow_dispatch` 是"人来点"，其余六种都是"合一次代码就自动跑"，一律不许有。
 */
function autoTriggerViolations(body: string): string[] {
  const out: string[] = [];

  if (!/^\s*workflow_dispatch:/m.test(body)) {
    out.push("必须有 workflow_dispatch（只能手动触发）");
  }
  const auto = body.match(
    /^\s{2}(push|pull_request|pull_request_target|schedule|repository_dispatch|workflow_run):/m,
  );
  if (auto) out.push(`不得有自动触发器 ${auto[1]}:`);

  return out;
}

/** 测试类工作流的规则：**一条部署能力都不许有**。 */
function testOnlyWorkflowViolations(fileName: string, content: string): string[] {
  const body = stripYamlComments(content);
  const out = autoTriggerViolations(body);

  // 禁的是"能力"而不是"单词"：注释里提一句 wrangler 无所谓，
  // 真正危险的是这条命令能跑起来、并且有凭据可用。
  // 唯一允许被测试工作流读取的 Secrets：Android 验收用的配对口令，以及
  // **交付 APK 的签名材料**。两者都不具备部署能力 —— 签名密钥只能用来给 APK
  // 签名，配对码只能让一台设备连上已经部署好的 Worker。
  // 其余任何 `secrets.*` 仍然一律视为越界：白名单是逐字列出来的，不是通配。
  const ALLOWED_TEST_SECRETS =
    /secrets\.(GATEWAY_ANDROID_PAIRING_CODE|ANDROID_KEYSTORE_BASE64|ANDROID_KEYSTORE_PASSWORD|ANDROID_KEY_ALIAS|ANDROID_KEY_PASSWORD)\b/g;

  const forbidden: Array<[string, RegExp]> = [
    ["部署命令", /\bwrangler\b[^\n]*\bdeploy\b/i],
    ["wrangler-action", /wrangler-action/i],
    ["Cloudflare 账号/API 凭据", /CLOUDFLARE_(API_TOKEN|API_KEY|ACCOUNT_ID|ZONE_ID)/i],
    // 先剔掉白名单里的引用，剩下的 `secrets.` 才算违规。
    ["GitHub Secrets 注入", /secrets\.|secrets\[/],
    ["云凭据入参", /\bapiToken\b|\bapi_token\b|\bapiKey\b|\bapi_key\b/],
  ];
  for (const [what, re] of forbidden) {
    const haystack =
      what === "GitHub Secrets 注入" ? body.replace(ALLOWED_TEST_SECRETS, "ALLOWED_SECRET") : body;
    if (re.test(haystack)) out.push(`不得包含${what}`);
  }

  // id-token: write 是 OIDC 换云厂商凭据的前提，等于把部署能力交出去。
  if (/:\s*write\b|id-token/i.test(body)) out.push("不得有写权限或 id-token");
  if (/^permissions:/m.test(body) && !/contents:\s*read/.test(body)) {
    out.push("permissions 必须是只读的（contents: read）");
  }

  return out.map((v) => `${fileName}: ${v}`);
}

/**
 * 任务书对部署工作流提的 13 条，逐条对应下面的检查。
 * Gate 2 / Gate 2.5 联调期间另加第 14 条：分支与确认串必须一一对应（见第 4 / 13 条
 * 之后那一段，三条人工路径各自配对，不能互换）。
 */
function deployWorkflowViolations(fileName: string, content: string): string[] {
  const body = stripYamlComments(content);
  // 1 / 7 —— 只能手动触发，不许有 push / PR / 定时 / 链式触发
  const out = autoTriggerViolations(body);

  // 2 / 3 —— 必须有一个 confirm_deploy 输入，且 required: true
  if (!/^\s*confirm_deploy:/m.test(body)) {
    out.push("必须声明 confirm_deploy 输入");
  } else if (!/^\s*required:\s*true\s*$/m.test(blockAfter(body, "confirm_deploy"))) {
    out.push("confirm_deploy 必须是 required: true");
  }

  // 4 / 13 —— 部署 job 必须同时钉住"分支是 gate0"和"人工输入了 DEPLOY"
  const gate = body
    .split("\n")
    .filter((l) => /^\s*if:/.test(l))
    .find((l) => /github\.ref/.test(l));
  if (gate === undefined) {
    out.push("部署 job 必须有基于 github.ref 与 confirm_deploy 的执行条件");
  } else {
    if (!/github\.ref\s*==\s*'refs\/heads\/gate0'/.test(gate)) {
      out.push("执行条件必须要求 github.ref == 'refs/heads/gate0'");
    }
    if (!/inputs\.confirm_deploy\s*==\s*'DEPLOY'/.test(gate)) {
      out.push("执行条件必须要求 inputs.confirm_deploy == 'DEPLOY'");
    }
    // Gate 2 / Gate 2.5 临时路径：分支与确认串必须**一一对应** —— 只钉分支不行，也不能把
    // 确认串对调（gate0 ↔ DEPLOY，feat/gate2-core ↔ DEPLOY-GATE2，
    // feat/gate2-5-native-tools ↔ DEPLOY-GATE2-5）。
    // （并入 gate0 之后删掉这一段。）
    const gateOneLine = gate.replace(/\s+/g, " ");
    if (!/refs\/heads\/gate0'\s*&&\s*inputs\.confirm_deploy\s*==\s*'DEPLOY'/.test(gateOneLine)) {
      out.push("gate0 分支必须与确认串 'DEPLOY' 配对");
    }
    if (
      gateOneLine.includes("refs/heads/feat/gate2-core") &&
      !/refs\/heads\/feat\/gate2-core'\s*&&\s*inputs\.confirm_deploy\s*==\s*'DEPLOY-GATE2'/.test(
        gateOneLine,
      )
    ) {
      out.push("feat/gate2-core 分支必须与确认串 'DEPLOY-GATE2' 配对");
    }
    // 注意顺序：这一条必须在 gate2-core 之后，且用 `==` 的精确匹配 —— 'DEPLOY-GATE2'
    // 是 'DEPLOY-GATE2-5' 的前缀，反过来也成立，所以只能靠精确串区分，不能靠 startsWith。
    if (
      gateOneLine.includes("refs/heads/feat/gate2-5-native-tools") &&
      !/refs\/heads\/feat\/gate2-5-native-tools'\s*&&\s*inputs\.confirm_deploy\s*==\s*'DEPLOY-GATE2-5'/.test(
        gateOneLine,
      )
    ) {
      out.push("feat/gate2-5-native-tools 分支必须与确认串 'DEPLOY-GATE2-5' 配对");
    }
  }
  const branches = [...body.matchAll(/refs\/heads\/([A-Za-z0-9._/-]+)/g)].map((m) => m[1]!);
  const otherBranches = [...new Set(branches)].filter((b) => !DEPLOY_BRANCH_ALLOWLIST.includes(b));
  if (otherBranches.length > 0) out.push(`不得引用未批准的分支：${otherBranches.join(", ")}`);

  // 5 / 6 —— 权限只能有 contents: read，不许 write、不许 id-token
  const perms = blockAfter(body, "permissions")
    .split("\n")
    .map((l) => l.trim())
    .filter(Boolean);
  if (perms.length !== 1 || perms[0] !== "contents: read") {
    out.push(`permissions 必须且只能有 contents: read（当前：${perms.join(" / ") || "空"}）`);
  }
  if (/id-token/i.test(body)) out.push("不得有 id-token");
  if (/:\s*write\b/i.test(body)) out.push("不得有 write 权限");

  // 8 —— 不许出现别的云平台的部署凭据
  const otherCloud: Array<[string, RegExp]> = [
    ["AWS", /AWS_(ACCESS_KEY_ID|SECRET_ACCESS_KEY|SESSION_TOKEN)|aws-actions\//i],
    ["GCP", /GOOGLE_APPLICATION_CREDENTIALS|google-github-actions|GCP_SA_KEY/i],
    ["Azure", /AZURE_(CREDENTIALS|CLIENT_SECRET|SUBSCRIPTION_ID)|azure\/login/i],
    ["阿里云/腾讯云", /ALIBABA_CLOUD_|TENCENTCLOUD_|ALIYUN_/i],
    ["其它托管平台", /VERCEL_TOKEN|NETLIFY_AUTH_TOKEN|heroku|flyctl/i],
  ];
  for (const [what, re] of otherCloud) {
    if (re.test(body)) out.push(`不得出现 ${what} 的部署凭据`);
  }

  // 9 —— Cloudflare 凭据**只能**通过 GitHub Secrets 引用，不许硬编码明文。
  //      注意：只是"提到"这个名字（echo / test -n）不算违规，赋值成非 Secrets 才算。
  for (const line of body.split("\n")) {
    if (!/CLOUDFLARE_[A-Z_]+/.test(line)) continue;
    const assign = line.match(/^\s*(?:-\s*)?(?:export\s+)?[A-Za-z_][A-Za-z0-9_]*\s*[:=]\s*(.*)$/);
    if (assign === null) continue; // 不是赋值，是引用/校验/提示文案
    if (!/\$\{\{\s*secrets\.[A-Za-z0-9_]+\s*\}\}/.test(assign[1]!)) {
      out.push(`Cloudflare 凭据只能通过 GitHub Secrets 引用：${line.trim()}`);
    }
  }

  // 10 / 11 —— 部署目标只能是 android-mcp-hub，且不许配置别的账号
  if (!/android-mcp-hub/.test(body)) out.push("必须明确部署目标为 android-mcp-hub");
  const named = [...body.matchAll(/--name[= ]+([A-Za-z0-9._-]+)/g)].map((m) => m[1]!);
  const wrongName = [...new Set(named)].filter((n) => n !== "android-mcp-hub");
  if (wrongName.length > 0) out.push(`不得用 --name 部署到别的 Worker：${wrongName.join(", ")}`);
  for (const key of [
    "account_id",
    "routes",
    "route",
    "d1_databases",
    "kv_namespaces",
    "r2_buckets",
    "queues",
    "hyperdrive",
  ]) {
    if (!body.includes(`'${key}'`) && !body.includes(`"${key}"`)) {
      out.push(`必须校验 wrangler 配置里没有 ${key}`);
    }
  }

  // 12 —— 部署之前必须先跑测试与 wrangler dry-run
  const lines = body.split("\n");
  const at = (re: RegExp): number => lines.findIndex((l) => re.test(l));
  const iTest = at(/npm run test/);
  const iE2e = at(/npm run e2e/);
  const iDry = at(/\bwrangler\b[^\n]*\bdeploy\b[^\n]*--dry-run/);
  const iReal = lines.findIndex(
    (l) => /\bwrangler\b[^\n]*\bdeploy\b/.test(l) && !/--dry-run/.test(l),
  );

  if (iDry < 0) out.push("部署前必须跑一次 wrangler … --dry-run");
  if (iReal < 0) out.push("必须有一条真正的部署命令（不带 --dry-run）");
  if (iTest < 0) out.push("部署前必须跑 npm run test");
  if (iE2e < 0) out.push("部署前必须跑 npm run e2e");
  if (iDry >= 0 && iReal >= 0 && iReal < iDry) out.push("真正的部署必须排在 dry-run 之后");
  if (iDry >= 0 && iTest >= 0 && iTest > iDry) out.push("npm run test 必须排在 dry-run 之前");
  if (iDry >= 0 && iE2e >= 0 && iE2e > iDry) out.push("npm run e2e 必须排在 dry-run 之前");

  return out.map((v) => `${fileName}: ${v}`);
}

/** 工作流清单本身也是受约束的：出现未批准的文件、或少了已批准的文件，都要红。 */
function workflowListViolations(names: string[]): string[] {
  if (names.length === 0) return []; // 一个工作流都没有也是合法状态（那更不可能自动部署）

  const out: string[] = [];
  const unexpected = names.filter((n) => !WORKFLOW_ALLOWLIST.includes(n));
  const missing = WORKFLOW_ALLOWLIST.filter((n) => !names.includes(n));
  if (unexpected.length > 0) out.push(`出现了未批准的工作流：${unexpected.join(", ")}`);
  if (missing.length > 0) out.push(`缺少已批准的工作流：${missing.join(", ")}`);
  return out;
}

/** 按文件名分派到对应的规则集。 */
function workflowViolations(fileName: string, content: string): string[] {
  return fileName === DEPLOY_WORKFLOW
    ? deployWorkflowViolations(fileName, content)
    : testOnlyWorkflowViolations(fileName, content);
}

describe("安全 / 不开启任何自动部署", () => {
  it("测试类工作流只在手动触发时运行（push / PR / 定时 / 链式触发一律不许有）", () => {
    for (const name of TEST_ONLY_WORKFLOWS) {
      const body = stripYamlComments(readWorkflow(name));
      expect(autoTriggerViolations(body), name).toEqual([]);
    }
  });

  it("测试类工作流没有任何部署能力：没有部署命令、没有云凭据、没有 GitHub Secrets", () => {
    for (const name of TEST_ONLY_WORKFLOWS) {
      expect(testOnlyWorkflowViolations(name, readWorkflow(name)), name).toEqual([]);
    }
  });

  it("测试类工作流权限是只读的（不许有 write、不许有 id-token）", () => {
    for (const name of TEST_ONLY_WORKFLOWS) {
      const body = stripYamlComments(readWorkflow(name));

      // id-token: write 是 OIDC 换云厂商凭据的前提，等于把部署能力交出去。
      expect(body, name).not.toMatch(/:\s*write\b|id-token/i);
      if (/^permissions:/m.test(body)) {
        expect(body, name).toMatch(/contents:\s*read/);
      }
    }
  });

  it("部署工作流同时满足 14 条约束（唯一被允许的例外）", () => {
    expect(deployWorkflowViolations(DEPLOY_WORKFLOW, readWorkflow(DEPLOY_WORKFLOW))).toEqual([]);
    for (const name of TEST_ONLY_WORKFLOWS) {
      expect(workflowViolations(name, readWorkflow(name)), name).toEqual([]);
    }
  });

  it("部署 job 的执行条件真值表：只有两条人工确认路径能通过", () => {
    // 上面那些是**文本级**检查。这一条把 if 条件真的拿来求值，证明"分支 + 确认串"
    // 的组合语义：普通 push（inputs 为空）永远为假 —— 这是"push 不会部署"的根据，
    // 而不只是"文件里没写 push 触发器"。
    const gate = readWorkflow(DEPLOY_WORKFLOW)
      .split("\n")
      .filter((l) => /^\s*if:/.test(l))
      .find((l) => /github\.ref/.test(l))!;
    const expr = gate
      .split("if:")[1]!
      .trim()
      .replace(/^\$\{\{/, "")
      .replace(/\}\}$/, "")
      .trim();
    // 剥掉 ${{ }} 之后，GitHub 表达式与 JS 在 == / && / || / 括号上语义一致，
    // 所以这里用 Function 求值（不为测试引一个表达式引擎，也不改被测文件）。
    const evaluate = (ref: string, confirm: string | null): unknown =>
      new Function("github", "inputs", `return (${expr});`)(
        { ref },
        confirm === null ? {} : { confirm_deploy: confirm },
      );

    expect(expr).toContain("github.ref");
    expect(evaluate("refs/heads/gate0", "DEPLOY")).toBe(true);
    expect(evaluate("refs/heads/feat/gate2-core", "DEPLOY-GATE2")).toBe(true);
    expect(evaluate("refs/heads/feat/gate2-5-native-tools", "DEPLOY-GATE2-5")).toBe(true);

    // 分支与确认串必须一一配对，不能互换
    expect(evaluate("refs/heads/gate0", "DEPLOY-GATE2")).toBe(false);
    expect(evaluate("refs/heads/feat/gate2-core", "DEPLOY")).toBe(false);
    expect(evaluate("refs/heads/feat/gate2-5-native-tools", "DEPLOY")).toBe(false);
    expect(evaluate("refs/heads/feat/gate2-5-native-tools", "DEPLOY-GATE2")).toBe(false);
    expect(evaluate("refs/heads/feat/gate2-core", "DEPLOY-GATE2-5")).toBe(false);

    // push / 不填 / 瞎填 / 别的分支 一律不部署
    expect(evaluate("refs/heads/gate0", null)).toBe(false);
    expect(evaluate("refs/heads/feat/gate2-core", null)).toBe(false);
    expect(evaluate("refs/heads/feat/gate2-5-native-tools", null)).toBe(false);
    expect(evaluate("refs/heads/gate0", "")).toBe(false);
    expect(evaluate("refs/heads/feat/gate2-core", "deploy-gate2")).toBe(false);
    expect(evaluate("refs/heads/feat/gate2-5-native-tools", "deploy-gate2-5")).toBe(false);
    expect(evaluate("refs/heads/feat/gate2-5-native-tool", "DEPLOY-GATE2-5")).toBe(false);
    expect(evaluate("refs/heads/feat/gate2-5-native-tools-2", "DEPLOY-GATE2-5")).toBe(false);
    expect(evaluate("refs/heads/main", "DEPLOY")).toBe(false);
    expect(evaluate("refs/heads/main", "DEPLOY-GATE2")).toBe(false);
    expect(evaluate("refs/heads/main", "DEPLOY-GATE2-5")).toBe(false);
    expect(evaluate("refs/heads/gate2-core", "DEPLOY-GATE2")).toBe(false);
  });

  it("工作流清单是明确的（新增一个工作流必须显式改这里）", () => {
    // 与"DO 绑定必须恰好三个"同一种模式：受安全约束的东西要显式列出来，
    // 免得以后有人顺手加一个工作流，而没人回头看一眼它能不能部署。
    const names = workflowFiles()
      .map((f) => basename(f))
      .sort();

    expect(workflowListViolations(names)).toEqual([]);
    expect(names).toEqual([...WORKFLOW_ALLOWLIST].sort());
  });
});

// ---------------------------------------------------------------------------
//  A3. 部署工作流的**反向测试**：篡改必须被拒绝
// ---------------------------------------------------------------------------
//
//  上面那几条只能证明"当前这个文件是合规的"。这一组把文件**改坏**，再喂给同一个
//  纯函数，要求它必须报出违规 —— 否则那些规则就只是装饰。

describe("安全 / 部署工作流的篡改必须被拒绝", () => {
  /** 在真实文件上做一处改动，返回违规清单（空数组 = 这道闸没挡住）。 */
  function violationsAfter(mutate: (src: string) => string): string[] {
    const mutated = mutate(readWorkflow(DEPLOY_WORKFLOW));
    expect(mutated, "改动必须真的生效，否则这条测试是假的").not.toEqual(
      readWorkflow(DEPLOY_WORKFLOW),
    );
    return deployWorkflowViolations(DEPLOY_WORKFLOW, mutated);
  }

  it("原文件是合规的（反向测试的基准）", () => {
    expect(deployWorkflowViolations(DEPLOY_WORKFLOW, readWorkflow(DEPLOY_WORKFLOW))).toEqual([]);
  });

  it("删掉 DEPLOY 确认条件 → 必须被拒绝", () => {
    const v = violationsAfter((s) => s.replace(/ && inputs\.confirm_deploy == 'DEPLOY'/, ""));
    expect(v.join("\n")).toMatch(/confirm_deploy/);
  });

  it("把 gate0 改成别的分支 → 必须被拒绝", () => {
    const v = violationsAfter((s) => s.replaceAll("refs/heads/gate0", "refs/heads/main"));
    expect(v.join("\n")).toMatch(/gate0|未批准的分支/);
  });

  it("把 Gate 2 临时路径挂到未批准的分支 → 必须被拒绝", () => {
    const v = violationsAfter((s) =>
      s.replaceAll("refs/heads/feat/gate2-core", "refs/heads/gate2-temp"),
    );
    expect(v.join("\n")).toMatch(/未批准的分支：gate2-temp/);
  });

  it("删掉 Gate 2 的确认串（只钉分支）→ 必须被拒绝", () => {
    const v = violationsAfter((s) =>
      s.replace(" && inputs.confirm_deploy == 'DEPLOY-GATE2'", ""),
    );
    expect(v.join("\n")).toMatch(/DEPLOY-GATE2/);
  });

  it("删掉 Gate 2.5 的确认串（只钉分支）→ 必须被拒绝", () => {
    const v = violationsAfter((s) =>
      s.replace(" && inputs.confirm_deploy == 'DEPLOY-GATE2-5'", ""),
    );
    expect(v.join("\n")).toMatch(/DEPLOY-GATE2-5/);
  });

  it("把 Gate 2.5 临时路径挂到未批准的分支 → 必须被拒绝", () => {
    const v = violationsAfter((s) =>
      s.replaceAll("refs/heads/feat/gate2-5-native-tools", "refs/heads/gate2-5-temp"),
    );
    expect(v.join("\n")).toMatch(/未批准的分支：gate2-5-temp/);
  });

  it("把 Gate 2.5 与 Gate 2 的确认串对调 → 必须被拒绝", () => {
    const v = violationsAfter((s) =>
      s
        .replace(
          "'refs/heads/feat/gate2-core' && inputs.confirm_deploy == 'DEPLOY-GATE2'",
          "'refs/heads/feat/gate2-core' && inputs.confirm_deploy == 'DEPLOY-GATE2-5'",
        )
        .replace(
          "'refs/heads/feat/gate2-5-native-tools' && inputs.confirm_deploy == 'DEPLOY-GATE2-5'",
          "'refs/heads/feat/gate2-5-native-tools' && inputs.confirm_deploy == 'DEPLOY-GATE2'",
        ),
    );
    expect(v.join("\n")).toMatch(/配对/);
  });

  it("把两个分支的确认串对调 → 必须被拒绝", () => {
    const v = violationsAfter((s) =>
      s
        .replace(
          "'refs/heads/gate0' && inputs.confirm_deploy == 'DEPLOY'",
          "'refs/heads/gate0' && inputs.confirm_deploy == 'DEPLOY-GATE2'",
        )
        .replace(
          "'refs/heads/feat/gate2-core' && inputs.confirm_deploy == 'DEPLOY-GATE2'",
          "'refs/heads/feat/gate2-core' && inputs.confirm_deploy == 'DEPLOY'",
        ),
    );
    expect(v.join("\n")).toMatch(/配对/);
  });

  it("加上 push 自动触发 → 必须被拒绝", () => {
    const v = violationsAfter((s) => s.replace("on:\n", "on:\n  push:\n"));
    expect(v.join("\n")).toMatch(/自动触发器 push/);
  });

  it("加上 id-token: write → 必须被拒绝", () => {
    const v = violationsAfter((s) => s.replace("permissions:\n", "permissions:\n  id-token: write\n"));
    expect(v.join("\n")).toMatch(/id-token|contents: read/);
  });

  it("把 Cloudflare 凭据改成硬编码明文 → 必须被拒绝", () => {
    const v = violationsAfter((s) => s.replace(/\$\{\{\s*secrets\.CLOUDFLARE_API_TOKEN\s*\}\}/g, "0123456789abcdef0123456789abcdef"));
    expect(v.join("\n")).toMatch(/GitHub Secrets/);
  });

  it("把部署排到 dry-run 之前 → 必须被拒绝", () => {
    const v = violationsAfter((s) => {
      const dry = "        run: npx wrangler deploy --config worker/wrangler.jsonc --dry-run";
      const real =
        "        run: npx wrangler deploy --config worker/wrangler.jsonc\n";
      // 把真正的部署那一行移到 dry-run 之前：直接把 dry-run 那行改造成不带 --dry-run
      return s.replace(dry, real.trimEnd());
    });
    expect(v.join("\n")).toMatch(/dry-run/);
  });

  it("在测试类工作流里加一条部署命令 → 必须被拒绝", () => {
    const v = testOnlyWorkflowViolations(
      "worker-gate1-check.yml",
      readWorkflow("worker-gate1-check.yml") +
        "\n      - name: 顺手部署一下\n        run: npx wrangler deploy\n",
    );
    expect(v.join("\n")).toMatch(/部署命令/);
  });

  it("在测试类工作流里注入 GitHub Secrets → 必须被拒绝", () => {
    const v = testOnlyWorkflowViolations(
      "android-debug-apk.yml",
      readWorkflow("android-debug-apk.yml") + "\n        env:\n          T: ${{ secrets.SOME_TOKEN }}\n",
    );
    expect(v.join("\n")).toMatch(/Secrets/);
  });

  it("添加一个未经批准的新工作流 → 必须被拒绝", () => {
    const v = workflowListViolations([
      ...WORKFLOW_ALLOWLIST,
      "rogue.yml",
    ]);
    expect(v.join("\n")).toMatch(/未批准的工作流：rogue\.yml/);
  });

  it("少一个已批准的工作流 → 必须被拒绝", () => {
    const v = workflowListViolations(WORKFLOW_ALLOWLIST.filter((n) => n !== DEPLOY_WORKFLOW));
    expect(v.join("\n")).toMatch(/缺少已批准的工作流/);
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

  it("日志只记录白名单字段：不在白名单里的键一个都不出现（即使它看起来很无辜）", () => {
    // Gate 0.1 / R2：这条测试取代了 Gate 0 那条"按键名脱敏"的测试。
    // 断言强度是**变强**不是变弱：旧版只证明"名字起对了的字段会被替换"，
    // 新版直接证明"没被显式允许的字段根本不会出现在输出里" ——
    // 后者才防得住 `payload: token` 这类事故。
    const { safe } = sanitizeLogFields({
      deviceId: TEST_DEVICE_ID,
      Authorization: `Bearer ${TEST_DEVICE_TOKEN}`,
      token: TEST_DEVICE_TOKEN,
      tokenSha256: "abc",
      nested: { password: "hunter2", apiKey: "k", ok: "fine" },
    });

    expect(safe["deviceId"]).toBe(TEST_DEVICE_ID);
    for (const forbidden of ["Authorization", "token", "tokenSha256", "nested"]) {
      expect(safe, `${forbidden} 不该出现在日志字段里`).not.toHaveProperty(forbidden);
    }
    expect(JSON.stringify(safe)).not.toContain("hunter2");
  });

  it("白名单字段的值再被扫一遍：凭据形状的字符串一律替换", () => {
    const shaped = `ghp_${"A1b2C3d4E5f6G7h8I9j0K1l2M3n4O5p6Q7r8"}`;
    const { safe } = sanitizeLogFields({ deviceId: shaped, reason: "token_mismatch" });

    expect(safe["deviceId"]).toBe(WITHHELD);
    expect(safe["reason"]).toBe("token_mismatch");
    expect(JSON.stringify(safe)).not.toContain("ghp_");
  });

  it("错误类别必须来自固定表，不在表里的一律降级成 unclassified", () => {
    // 这条是"不记录未经处理的异常字符串"的机械保证：
    // 有人若想 logEvent(..., { errorCode: String(err) })，值会被换掉。
    const { safe } = sanitizeLogFields({
      errorCode: "Error: connect ECONNREFUSED 10.0.0.1:5432",
    });
    expect(safe["errorCode"]).toBe(UNCLASSIFIED_ERROR);
    expect(JSON.stringify(safe)).not.toContain("ECONNREFUSED");

    // 反过来，合法类别原样保留。
    expect(sanitizeLogFields({ errorCode: "storage_failure" }).safe["errorCode"]).toBe("storage_failure");
    expect(ERROR_CATEGORIES.has("unclassified")).toBe(true);
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
