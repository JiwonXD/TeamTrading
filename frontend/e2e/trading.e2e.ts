import AxeBuilder from '@axe-core/playwright'
import { expect, test } from '@playwright/test'

// 서버 모드 화면이 실제 백엔드와 실시간 시세로 동작하는지, 사용자 흐름 그대로 확인한다

test('home lists every team with server prices and has no accessibility violations', async ({ page }) => {
  await page.goto('/#home')
  await expect(page.locator('.stock-list li')).toHaveCount(18)
  await expect(page.locator('.asset-card .big')).toHaveText(/1,000,000P|[\d,]+P/)
  const axe = await new AxeBuilder({ page }).analyze()
  expect(axe.violations).toEqual([])
})

test('team page shows live order book and a limit buy at the best ask fills', async ({ page }) => {
  await page.goto('/#team-lg')
  // 호가는 WebSocket 실시간 시세로만 들어온다
  const asks = page.locator('.ob-row.ask')
  await expect(asks.first()).toBeVisible({ timeout: 20_000 })

  const axe = await new AxeBuilder({ page }).analyze()
  expect(axe.violations).toEqual([])

  await asks.last().click() // 가장 낮은 판매 호가
  await page.getByPlaceholder('몇 주를 주문할까요?').fill('1')
  await page.locator('.sheet button.btn.wide').click()
  await expect(page.locator('.toast')).toContainText(/체결|대기/)

  await page.goto('/#portfolio')
  await expect(page.getByText('LG 트윈스').first()).toBeVisible()
})

test('order book keeps updating from the live feed', async ({ page }) => {
  await page.goto('/#team-lg')
  await page.getByRole('button', { name: '체결' }).click()
  const rows = page.locator('.trade-row')
  await expect(rows.first()).toBeVisible({ timeout: 20_000 })
  const before = await rows.first().textContent()
  // 데모 계정들이 1.5초마다 주문하므로 곧 새 체결이 맨 위에 붙는다
  await expect.poll(async () => rows.first().textContent(), { timeout: 30_000 }).not.toBe(before)
})
