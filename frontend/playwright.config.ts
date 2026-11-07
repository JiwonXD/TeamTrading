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
  projects: [
    { name: 'mobile', testMatch: 'trading.e2e.ts', use: { ...devices['Pixel 7'] } },
    // 1024px 이상은 PC 레이아웃(위 메뉴 · 오른쪽 구단 목록 · 차트·호가·주문 동시 배치)
    { name: 'desktop', testMatch: 'desktop.e2e.ts', use: { ...devices['Desktop Chrome'], viewport: { width: 1440, height: 900 } } },
  ],
})
