import AxeBuilder from '@axe-core/playwright'
import { type Page, expect, test } from '@playwright/test'

// 서버 모드 화면이 실제 백엔드와 실시간 시세로 동작하는지, 사용자 흐름 그대로 확인한다.
// 데모 서버는 개발용 닉네임 로그인을 연다. 테스트마다 새 닉네임으로 새 계정을 만든다.

async function signIn(page: Page, hash = '#home') {
  await page.goto(`/${hash}`)
  await page.getByPlaceholder('닉네임').fill(`e2e${Date.now() % 1_000_000}${Math.floor(Math.random() * 100)}`)
  await page.getByRole('button', { name: '들어가기' }).click()
  await expect(page.locator('.tabbar, .cta').first()).toBeVisible()
}

test('login page has no accessibility violations and logging out returns to it', async ({ page }) => {
  await page.goto('/#home')
  await expect(page.getByPlaceholder('닉네임')).toBeVisible()
  const axe = await new AxeBuilder({ page }).analyze()
  expect(axe.violations).toEqual([])

  await signIn(page)
  await page.goto('/#portfolio')
  await page.getByRole('button', { name: '로그아웃' }).click()
  await expect(page.getByPlaceholder('닉네임')).toBeVisible()
})

test('home lists every team with server prices and has no accessibility violations', async ({ page }) => {
  await signIn(page)
  await expect(page.locator('.stock-list li')).toHaveCount(18)
  await expect(page.locator('.asset-card .big')).toHaveText(/1,000,000P|[\d,]+P/)
  const axe = await new AxeBuilder({ page }).analyze()
  expect(axe.violations).toEqual([])
})

test('team page shows live order book and a limit buy at the best ask fills', async ({ page }) => {
  await signIn(page, '#team-lg')
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
  await signIn(page, '#team-lg')
  await page.getByRole('button', { name: '체결' }).click()
  const rows = page.locator('.trade-row')
  await expect(rows.first()).toBeVisible({ timeout: 20_000 })
  const before = await rows.first().textContent()
  // 데모 계정들이 1.5초마다 주문하므로 곧 새 체결이 맨 위에 붙는다
  await expect.poll(async () => rows.first().textContent(), { timeout: 30_000 }).not.toBe(before)
})
