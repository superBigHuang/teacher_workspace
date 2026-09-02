import { defineConfig } from '@playwright/test'

// Playwright E2E 配置：前端 5173，无头 chromium，失败自动截图 + trace
export default defineConfig({
  testDir: './tests',
  timeout: 180000, // 全站走查为单一大测试（14+ step、正常 ~80s），余量放宽到 3 分钟，防外部接口慢导致级联超时
  expect: { timeout: 15000 },
  use: {
    baseURL: 'http://localhost:5173',
    headless: true,
    actionTimeout: 20000,
    navigationTimeout: 30000,
    screenshot: 'only-on-error',
    trace: 'retain-on-failure',
    viewport: { width: 1600, height: 1000 },
    locale: 'zh-CN'
  },
  reporter: [['list'], ['html', { open: 'never', outputDir: 'playwright-report' }]]
})
