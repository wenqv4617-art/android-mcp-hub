/**
 * 部署前检查：把设备令牌转成 `DEVICE_TOKENS` 里那条记录。
 *
 * 为什么要有这个脚本：
 *   `DEVICE_TOKENS` 里存的是**令牌的 sha256**，不是令牌本身。手工算摘要有两个常见的坑：
 *     ① 多算/少算一个换行符 —— `echo "$TOKEN" | sha256sum` 会比 Worker 多算一个 `\n`，
 *        结果是"手机永远连不上，而且看不出哪里错了"；
 *     ② 摘要写成大写、或者被复制时截断 —— `loadConfig` 会拒收，但错误信息要等 Worker 起来才看得到。
 *   这个脚本用**和 Worker 完全同一份代码**（`worker/src/util/crypto.ts` 的 `sha256Hex`）算摘要，
 *   所以不存在"两套实现不一致"的可能。
 *
 * 安全约束（写死在实现里）：
 *   · 令牌**只从 stdin 读**，不接受命令行参数 —— 命令行参数会进 shell history，也会出现在 `ps` 里；
 *   · 输出里**只有摘要**，不回显令牌本身，也不回显它的长度以外的任何信息；
 *   · 只做本地计算，**不联网、不读任何配置文件**。
 *
 * 用法（在可信环境里执行，不要把这行贴到聊天窗口）：
 *   printf '%s' "$DEVICE_TOKEN" | npm run check:credential -- --device-id my-android-phone
 */

import { createHash } from "node:crypto";
import { sha256Hex, toHex } from "../worker/src/util/crypto";

interface Options {
  deviceId: string;
  label: string | null;
}

function parseArgs(argv: readonly string[]): Options {
  let deviceId: string | null = null;
  let label: string | null = null;
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === "--device-id") deviceId = argv[i + 1] ?? null;
    else if (arg === "--label") label = argv[i + 1] ?? null;
  }
  if (deviceId === null || deviceId.trim() === "") {
    console.error("缺少 --device-id。例：printf '%s' \"$DEVICE_TOKEN\" | npm run check:credential -- --device-id my-android-phone");
    process.exit(2);
  }
  return { deviceId: deviceId.trim(), label };
}

async function readStdin(): Promise<string> {
  const chunks: Buffer[] = [];
  for await (const chunk of process.stdin) chunks.push(chunk as Buffer);
  return Buffer.concat(chunks).toString("utf8");
}

/** 与 Worker 的 `loadConfig` 同一条规则：64 位小写十六进制。 */
const DIGEST_PATTERN = /^[0-9a-f]{64}$/;

async function main(): Promise<void> {
  const options = parseArgs(process.argv.slice(2));
  const raw = await readStdin();
  // 允许结尾带换行（`echo` 的常见写法），但**只**去掉末尾的 CR/LF；
  // 中间的空白是令牌的一部分，动了就会造成"摘要对不上"。
  const token = raw.replace(/[\r\n]+$/, "");

  if (token === "") {
    console.error("stdin 是空的 —— 没有读到设备令牌。");
    process.exit(2);
  }
  if (token !== token.trim()) {
    console.error("令牌的开头或结尾有空格。设备令牌通常不含空白；请确认你是不是多复制了什么。");
    process.exit(2);
  }

  const digest = await sha256Hex(token);

  // 自检：脚本算出来的摘要必须能被"另一条独立路径"复现（Node 原生 createHash），
  // 否则说明 sha256Hex 本身有问题，这时候不能把摘要给人用。
  const independent = toHex(createHash("sha256").update(token, "utf8").digest());
  if (independent !== digest || !DIGEST_PATTERN.test(digest)) {
    console.error("摘要自检失败：两条实现不一致，或摘要不是 64 位小写十六进制。请勿使用下面的结果。");
    process.exit(1);
  }

  const entry = {
    deviceId: options.deviceId,
    tokenSha256: digest,
    label: options.label ?? "Gate 1 设备",
    createdAt: new Date().toISOString(),
  };

  console.log("摘要自检通过（两条独立实现一致，64 位小写十六进制）。");
  console.log("令牌本身没有被打印出来，也不会被写进任何文件。");
  console.log("");
  console.log("把下面这行填进 DEVICE_TOKENS（多台设备时用数组，逗号分隔）：");
  console.log("");
  console.log(JSON.stringify([entry]));
  console.log("");
  console.log("写入 Secret 的命令（在可信环境执行）：");
  console.log("  npx wrangler@4.149.0 secret put DEVICE_TOKENS --config worker/wrangler.jsonc");
}

void main();
