import { defineConfig, devices } from '@playwright/test'

/**
 * E2E 真实浏览器验证批（TASK-227）：chromium 单浏览器，route-mock 模式（page.route 拦截
 * /record/api/* 与 /user/api/* 返回固定夹具），webServer 起 vite preview（生产构建产物）。
 * 零后端依赖、零全栈起服务；E2E 本地执行不入 CI；证据（运行输出/trace）落 gitignored .trae/tmp。
 * 注意：web/package.json 为 "type": "module"，本文件按 ESM 解析（用 import.meta.dirname 而非 __dirname）。
 */
const e2eDir = import.meta.dirname

export default defineConfig({
  testDir: e2eDir,
  testMatch: '**/*.e2e.ts',
  outputDir: import.meta.dirname + '/../../.trae/tmp/e2e-results',
  timeout: 30_000,
  expect: { timeout: 10_000 },
  fullyParallel: false,
  workers: 1,
  forbidOnly: !!process.env.CI,
  retries: 0,
  reporter: [['list']],
  use: {
    baseURL: 'http://127.0.0.1:4179',
    trace: 'on',
    screenshot: 'only-on-failure',
    video: 'off',
    headless: true,
  },
  webServer: {
    command: 'pnpm build && pnpm preview --port 4179 --host 127.0.0.1',
    cwd: import.meta.dirname + '/..',
    port: 4179,
    reuseExistingServer: false,
    timeout: 180_000,
  },
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
  ],
})
