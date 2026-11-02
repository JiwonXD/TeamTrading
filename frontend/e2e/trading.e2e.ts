import AxeBuilder from '@axe-core/playwright'
import { type APIRequestContext, type Page, expect, test } from '@playwright/test'

// 서버 모드 화면이 실제 백엔드와 실시간 시세로 동작하는지, 사용자 흐름 그대로 확인한다.
// 데모 서버는 개발용 닉네임 로그인을 연다. 테스트마다 새 닉네임으로 새 계정을 만든다.

async function signIn(page: Page, hash = '#home') {
  await page.goto(`/${hash}`)
  await page.getByPlaceholder('닉네임').fill(`e2e${Date.now() % 1_000_000}${Math.floor(Math.random() * 100)}`)
  await page.getByRole('button', { name: '들어가기' }).click()
  await expect(page.locator('.tabbar, .cta').first()).toBeVisible()
}

/**
 * 판매 호가가 있는 구단. 데모 계정들의 주문은 무작위라 막 뜬 서버에서는 특정 구단에 판매 호가가 없을 수 있다.
 * 구단을 고정하면 테스트가 운에 따라 갈리므로 지금 판매 호가가 있는 구단을 고른다.
 */
async function teamWithAsk(request: APIRequestContext) {
  let found: { id: string; name: string } | undefined
  await expect.poll(async () => {
    const teams: { id: string; name: string }[] = await (await request.get('/api/teams')).json()
    for (const t of teams) {
      const book = await (await request.get(`/api/teams/${t.id}/orderbook?depth=1`)).json()
      if (book.asks.length > 0) {
        found = { id: t.id, name: t.name }
        return true
      }
    }
    return false
  }, { timeout: 30_000 }).toBe(true)
  return found!
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

test('team page shows live order book and a limit buy at the best ask fills', async ({ page, request }) => {
  const team = await teamWithAsk(request)
  await signIn(page, `#team-${team.id}`)
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
  await expect(page.getByText(team.name).first()).toBeVisible()
})

test("another user's trade reaches a watcher through the live feed", async ({ page, browser, request }) => {
  const team = await teamWithAsk(request)
  // 보는 사람: 주문하지 않고 체결 탭만 연다. 화면이 바뀐다면 WebSocket 실시간 시세로 받은 것이다
  await signIn(page, `#team-${team.id}`)
  await page.getByRole('button', { name: '체결' }).click()
  const rows = page.locator('.trade-row')
  const before = (await rows.count()) > 0 ? await rows.first().textContent() : null

  // 다른 사용자: 별도 브라우저 세션으로 로그인해 가장 낮은 판매 호가에 1주를 산다
  const other = await browser.newContext({ ...test.info().project.use, baseURL: test.info().project.use.baseURL })
  const trader = await other.newPage()
  await signIn(trader, `#team-${team.id}`)
  const asks = trader.locator('.ob-row.ask')
  await expect(asks.first()).toBeVisible({ timeout: 20_000 })
  await asks.last().click()
  await trader.getByPlaceholder('몇 주를 주문할까요?').fill('1')
  await trader.locator('.sheet button.btn.wide').click()
  await expect(trader.locator('.toast')).toContainText('체결')
  await other.close()

  await expect.poll(async () => ((await rows.count()) > 0 ? rows.first().textContent() : null), { timeout: 10_000 })
    .not.toBe(before)
})

test('home shows server matches with their dividend state', async ({ page }) => {
  // demo 프로필은 기동 직후 리그마다 시연용 가상 경기를 하나씩 잡는다
  await signIn(page)
  const matches = page.locator('.match')
  await expect(matches.first()).toBeVisible({ timeout: 20_000 })
  await expect(page.locator('.match-status').first()).toHaveText(/시작|경기 중|배당|취소|확인/)
  const axe = await new AxeBuilder({ page }).analyze()
  expect(axe.violations).toEqual([])
})

test('leaderboard ranks demo traders by period with my own row', async ({ page }) => {
  await signIn(page, '#ranking')
  // demo 프로필은 최소 체결 1회, 가입 기간 제한 없음
  await expect(page.locator('.rank-row').first()).toBeVisible({ timeout: 20_000 })
  await expect(page.locator('.rank-hero')).toContainText('순위 밖')
  const axe = await new AxeBuilder({ page }).analyze()
  expect(axe.violations).toEqual([])

  await page.getByRole('button', { name: '주간' }).click()
  await expect(page.locator('.rank-hero')).toContainText('마감 기준')
  await page.getByRole('button', { name: '손익' }).click()
  await expect(page.locator('.rank-hero')).toContainText('마감 기준')
})

test('a user subscribes to an open offering from the home list', async ({ page }) => {
  // demo 프로필은 기동 15초 뒤 구단 하나에 4분짜리 추가 공모를 연다
  await signIn(page)
  const row = page.locator('.offering-row').first()
  await expect(row).toBeVisible({ timeout: 40_000 })
  await row.click()

  const card = page.locator('.offering-card')
  await expect(card).toBeVisible()
  const axe = await new AxeBuilder({ page }).analyze()
  expect(axe.violations).toEqual([])

  await card.getByLabel('청약 수량').fill('2')
  await card.getByRole('button', { name: /P 청약/ }).click()
  await expect(page.locator('.toast')).toContainText('2주 청약했어요')
  await expect(card).toContainText('2주 청약')
  await card.getByRole('button', { name: '청약 취소' }).click()
  await expect(card.getByLabel('청약 수량')).toBeVisible()
})
