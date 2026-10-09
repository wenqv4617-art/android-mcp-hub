import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { defineConfig } from "vitest/config";

const here = dirname(fileURLToPath(import.meta.url));
// 配置文件放在 worker/ 下，但仓库根才是测试根（include 路径都从根写起）。
const root = resolve(here, "..");

export default defineConfig({
  root,
  test: {
    include: ["worker/test/**/*.test.ts"],
    setupFiles: [resolve(here, "test/harness/setup.ts")],
    environment: "node",
    testTimeout: 20_000,
    hookTimeout: 20_000,
    // 每个文件一个进程，避免跨文件共享模块级缓存（config.ts 有 memo）。
    pool: "forks",
    reporters: ["default"],
  },
});
