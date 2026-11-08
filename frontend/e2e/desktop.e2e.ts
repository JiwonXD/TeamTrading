import AxeBuilder from '@axe-core/playwright'
import { type APIRequestContext, type Page, expect, test } from '@playwright/test'

// PC 폭(1440px)에서 서버 모드 화면을 확인한다. 모바일 흐름은 trading.e2e.ts가 맡는다.

async function signIn(page: Page, hash = '#home') {
  await page.goto(`/${hash}`)
  await page.getByPlaceholder('닉네임').fill(`pc${Date.now() % 1_000_000}${Math.floor(Math.random() * 100)}`)
  await page.getByRole('button', { name: '들어가기' }).click()
  await expect(page.locator('.desk-header')).toBeVisible()
}

/** 판매 호가가 여러 단계 있는 구단(데모 호가는 무작위라 구단을 고정하지 않는다) */
async function teamWithAsk(request: APIRequestContext) {
  let found: { id: string; name: string } | undefined
  await expect.poll(async () => {
    const teams: { id: string; name: string }[] = await (await request.get('/api/teams')).json()
    for (const t of teams) {
      const book = await (await request.get(`/api/teams/${t.id}/orderbook?depth=5`)).json()
      if (book.asks.length >= 3) {
        found = { id: t.id, name: t.name }
        return true
      }
    }
    return false
  }, { timeout: 30_000 }).toBe(true)
  return found!
}

test('desktop home shows the market table, side list and no accessibility violations', async ({ page }) => {
  await signIn(page)
  await expect(page.locator('.tabbar')).toHaveCount(0)
  await expect(page.locator('.dtable tbody tr')).toHaveCount(18)
  await expect(page.locator('.side-row')).toHaveCount(18)
  // 시장감시는 운영자만 본다
  await expect(page.getByRole('link', { name: '시장감시' })).toHaveCount(0)
  const axe = await new AxeBuilder({ page }).analyze()
  expect(axe.violations).toEqual([])
})

test('hiding a league in the tab editor also hides it from the side list filter', async ({ page }) => {
  await signIn(page)
  await expect(page.locator('.side-filter button', { hasText: 'LCK' })).toHaveCount(1)
  await page.getByRole('button', { name: '리그 탭 편집' }).click()
  const sheet = page.getByRole('dialog', { name: '리그 탭 편집' })
  await sheet.getByRole('checkbox', { name: /^LCK/ }).uncheck()
  await sheet.getByRole('button', { name: '저장' }).click()
  await expect(sheet).toBeHidden()
  await expect(page.locator('.league-tabs')).not.toContainText('LCK')
  await expect(page.locator('.side-filter button', { hasText: 'LCK' })).toHaveCount(0)
})

test('the side list league filter scrolls sideways with the mouse wheel and drag', async ({ page }) => {
  await signIn(page)
  const filter = page.locator('.side-filter-scroll')
  await expect(filter.locator('button', { hasText: 'LCK' })).toHaveCount(1)
  // 리그가 폭보다 많은 상황을 만들려고 줄을 좁힌다
  await filter.evaluate((el) => { el.style.width = '90px' })
  await expect(filter).toHaveClass(/more-right/)
  const box = (await filter.boundingBox())!
  await page.mouse.move(box.x + 30, box.y + box.height / 2)
  await page.mouse.wheel(0, 200)
  await expect.poll(() => filter.evaluate((el) => el.scrollLeft)).toBeGreaterThan(0)
  await expect(filter).toHaveClass(/more-left/)
  await page.mouse.wheel(0, -400)
  await expect.poll(() => filter.evaluate((el) => el.scrollLeft)).toBe(0)
  // 탭 위에서 눌러 끌어도 그 탭이 골라지지 않고 줄만 넘어간다
  const all = filter.getByRole('button', { name: '전체' })
  await filter.getByRole('button', { name: 'LCK' }).hover()
  await page.mouse.down()
  for (let i = 1; i <= 8; i++) await page.mouse.move(box.x + 60 - i * 10, box.y + box.height / 2)
  await page.mouse.up()
  await expect.poll(() => filter.evaluate((el) => el.scrollLeft)).toBeGreaterThan(0)
  await expect(all).toHaveClass(/on/)
})

test('stars in the market table fill the 관심 tab of the side list in the order they were added', async ({ page }) => {
  await signIn(page)
  const table = page.locator('.dtable')
  const side = page.locator('.desk-side')
  await table.getByRole('button', { name: 'T1 관심 구단' }).click()
  await table.getByRole('button', { name: '젠지 관심 구단' }).click()
  // 별만 눌렀으므로 구단 화면으로 넘어가지 않는다
  await expect(page).toHaveURL(/#home$/)
  await side.getByRole('tab', { name: '관심' }).click()
  await expect(side.locator('.side-name')).toHaveText(['T1', '젠지'])

  // 사이드바의 별로 바로 뺀다. 화면은 먼저 바뀌고 요청은 앞선 담기 요청 뒤에 차례로 나가므로,
  // 새로고침 전에 빼기 응답까지 기다린다(기다리지 않으면 아직 안 보낸 요청이 새로고침에 사라진다)
  const removed = page.waitForResponse((r) => r.request().method() === 'DELETE' && r.url().endsWith('/api/accounts/me/watchlist/t1'))
  await side.getByRole('button', { name: 'T1 관심 구단' }).click()
  expect((await removed).ok()).toBe(true)
  await expect(side.locator('.side-name')).toHaveText(['젠지'])
  await expect(table.getByRole('button', { name: 'T1 관심 구단' })).toHaveAttribute('aria-pressed', 'false')

  // 계정에 저장돼 새로고침해도 남고, 관심 구단이 있으면 관심 탭부터 연다
  await page.reload()
  await expect(side.getByRole('tab', { name: '관심' })).toHaveAttribute('aria-selected', 'true')
  await expect(side.locator('.side-name')).toHaveText(['젠지'])
  await page.goto('/#team-geng')
  await expect(page.locator('.dt-head').getByRole('button', { name: '젠지 관심 구단' })).toHaveAttribute('aria-pressed', 'true')
  const axe = await new AxeBuilder({ page }).analyze()
  expect(axe.violations).toEqual([])
})

test('schedule dates can be dragged sideways, a picked day moves to the center and the arrows move one day at a time', async ({ page }) => {
  await signIn(page)
  await page.locator('.league-tabs').getByRole('link', { name: 'LCK' }).click()
  const strip = page.locator('.sched-days')
  const selected = page.locator('.sched-day[aria-pressed="true"]')
  await expect(selected).toContainText('오늘')
  // 오늘은 맨 오른쪽이 아니라 왼쪽 가까이에 있어 앞으로의 날짜가 넓게 보인다
  const box = (await strip.boundingBox())!
  const todayBox = (await selected.boundingBox())!
  expect(todayBox.x - box.x).toBeLessThan(box.width / 4)
  // 띠 안의 날짜 칸이 페이지 가로 폭을 늘리지 않는다
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)

  // 누른 채 왼쪽으로 끌면 미래 날짜로 넘어가고, 끌기를 날짜 고르기로 치지 않는다
  const before = await strip.evaluate((el) => el.scrollLeft)
  const y = box.y + box.height / 2
  await page.mouse.move(box.x + box.width * 0.6, y)
  await page.mouse.down()
  for (let i = 1; i <= 8; i++) await page.mouse.move(box.x + box.width * 0.6 - i * 30, y)
  await page.mouse.up()
  await expect.poll(() => strip.evaluate((el) => el.scrollLeft)).toBeGreaterThan(before + 150)
  await expect(selected).toContainText('오늘')

  // 끌다가 날짜를 누르면 그 날이 띠 가운데로 온다
  const picked = strip.locator('.sched-day').nth(await strip.evaluate((el) => {
    const r = el.getBoundingClientRect()
    return [...el.children].findIndex((c) => c.getBoundingClientRect().left > r.left + r.width * 0.75)
  }))
  await picked.click()
  await expect(picked).toHaveAttribute('aria-pressed', 'true')
  await expect.poll(async () => {
    const [p, s] = [(await picked.boundingBox())!, (await strip.boundingBox())!]
    return Math.abs(p.x + p.width / 2 - (s.x + s.width / 2))
  }).toBeLessThan(2)

  // 화살표는 하루씩 옮긴다
  const tomorrow = new Date()
  tomorrow.setDate(tomorrow.getDate() + 1)
  await page.getByRole('button', { name: '오늘', exact: true }).click()
  await page.getByRole('button', { name: '다음 날' }).click()
  await expect(selected.locator('b')).toHaveText(`${tomorrow.getDate()}일`)
  await page.getByRole('button', { name: '오늘', exact: true }).click()
  await expect(selected).toContainText('오늘')
  await expect.poll(() => strip.evaluate((el) => el.scrollLeft)).toBe(before)
})

test('league tab shows that league\'s schedule and prices while the logo and 홈 menu stay', async ({ page }) => {
  await signIn(page)
  await page.locator('.league-tabs').getByRole('link', { name: 'LCK' }).click()
  await expect(page).toHaveURL(/#league-lck$/)
  await expect(page.locator('.desk-brand')).toContainText('팬차트')
  await expect(page.locator('.desk-nav [aria-current="page"]')).toHaveText('홈')
  await expect(page.locator('.dh-table-panel h2')).toHaveText('LCK 실시간 시세')
  // demo 프로필은 3분마다 리그별 시연 경기를 잡으므로 오늘 일정에 LCK 경기가 있다
  await expect(page.locator('.sched-match').first()).toBeVisible({ timeout: 20_000 })
  await expect(page.locator('.dtable tbody tr').first()).toBeVisible()
  await expect(page.locator('.dtable .dt-name .xs').filter({ hasNotText: 'LCK' })).toHaveCount(0)
  const axe = await new AxeBuilder({ page }).analyze()
  expect(axe.violations).toEqual([])

  await page.goBack()
  await expect(page).toHaveURL(/#home$/)
  await expect(page.locator('.dtable tbody tr')).toHaveCount(18)
  await expect(page.locator('.schedule')).toHaveCount(0)
})

test('slash opens team search and Enter moves to the team page', async ({ page, request }) => {
  const team = await teamWithAsk(request)
  await signIn(page)
  await page.keyboard.press('/')
  await page.getByRole('combobox').fill(team.name)
  await page.keyboard.press('Enter')
  await expect(page).toHaveURL(new RegExp(`#team-${team.id}$`))
  await expect(page.locator('.dt-head h1')).toHaveText(team.name)
})

test('clicking an ask fills the order panel and a 1-share buy goes through', async ({ page, request }) => {
  const team = await teamWithAsk(request)
  await signIn(page, `#team-${team.id}`)
  const asks = page.locator('.dob-row.ask')
  await expect(asks.first()).toBeVisible({ timeout: 20_000 })
  const axe = await new AxeBuilder({ page }).analyze()
  expect(axe.violations).toEqual([])

  const best = asks.last() // 가장 낮은 판매 호가
  // 데모 봇이 호가를 계속 바꾸므로 가격을 읽은 뒤 따로 누르면 그 사이 다른 호가를 누를 수 있다. 같은 순간에 읽고 누른다.
  // 가격 칸은 가격 글자 뒤에 등락률(<small>)이 붙어 있으므로 첫 글자 노드만 읽는다
  const price = await best.evaluate((row) => {
    const text = row.querySelector('.dob-price')!.firstChild!.textContent!
    ;(row as HTMLElement).click()
    return text
  })
  await expect(page.locator('.order-side .on')).toHaveText('구매')
  await expect(page.locator('#of-price')).toHaveValue(price)
  await page.locator('#of-qty').fill('1')
  await page.locator('.dt-order button.btn.wide').click()
  await expect(page.locator('.toast')).toContainText(/체결|대기/)
})

test('구단정보 sits between 차트 · 호가 and 토론 and shows dividends for the team\'s league', async ({ page }) => {
  await signIn(page, '#team-t1')
  const tabs = page.locator('.dt-tabs [role=tab]')
  await expect(tabs).toHaveText(['차트 · 호가', '구단정보', '뉴스 · 공시', '토론'])
  await tabs.filter({ hasText: '구단정보' }).click()
  await expect(page.locator('.info-win')).toContainText('1주당 160P')
  await expect(page.getByRole('region', { name: '대회 성적 수당' })).toContainText('3,000P')
  const axe = await new AxeBuilder({ page }).analyze()
  expect(axe.violations).toEqual([])
  await tabs.filter({ hasText: '뉴스 · 공시' }).click()
  await expect(page.getByRole('region', { name: '뉴스' })).toBeVisible()
  await page.locator('.news-sub').getByRole('button', { name: '공시' }).click()
  await expect(page.getByRole('region', { name: '공시' })).toBeVisible()
})
