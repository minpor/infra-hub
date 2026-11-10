import { defineConfig } from '@playwright/test'

export default defineConfig({
  testDir: './tests',
  timeout: 45000,
  workers: 1,
  outputDir: '../.local/playwright',
  use: { baseURL: 'http://127.0.0.1:15173', viewport: { width: 1440, height: 1000 }, trace: 'retain-on-failure' },
})
