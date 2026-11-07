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
  const axe = await new AxeBuilder({ page }).analyze()
  expect(axe.violations).toEqual([])
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
  // 가격 칸은 가격 글자 뒤에 등락률(<small>)이 붙어 있으므로 첫 글자 노드만 읽는다
  const price = await best.locator('.dob-price').evaluate((el) => el.firstChild!.textContent!)
  await best.click()
  await expect(page.locator('.order-side .on')).toHaveText('구매')
  await expect(page.locator('#of-price')).toHaveValue(price)
  await page.locator('#of-qty').fill('1')
  await page.locator('.dt-order button.btn.wide').click()
  await expect(page.locator('.toast')).toContainText(/체결|대기/)
})
