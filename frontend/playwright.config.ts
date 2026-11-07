import { defineConfig, devices } from '@playwright/test'

// 백엔드(demo 프로필)와 `npm run dev:server`가 떠 있는 상태에서 실행한다. CI에서는 워크플로가 둘을 먼저 띄운다.
export default defineConfig({
  testDir: 'e2e',
  testMatch: '*.e2e.ts',
  timeout: 60_000,
  retries: 0,
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:5173',
    trace: 'retain-on-failure',
  },
  projects: [{ name: 'mobile', use: { ...devices['Pixel 7'] } }],
})
