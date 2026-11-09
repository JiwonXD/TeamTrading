import AxeBuilder from '@axe-core/playwright'
import { type APIRequestContext, type Page, expect, test } from '@playwright/test'

// 서버 모드 화면이 실제 백엔드와 실시간 시세로 동작하는지, 사용자 흐름 그대로 확인한다.
// 데모 서버는 개발용 닉네임 로그인을 연다. 테스트마다 새 닉네임으로 새 계정을 만든다.

async function signIn(page: Page, hash = '#home') {
  await page.goto('/#login')
  await page.getByPlaceholder('닉네임').fill(`e2e${Date.now() % 1_000_000}${Math.floor(Math.random() * 100)}`)
  await page.getByRole('button', { name: '들어가기' }).click()
  await expect(page.locator('.tabbar')).toBeVisible()
  await page.goto(`/${hash}`)
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
      // 앞선 테스트나 데모 계정이 호가 한두 개를 가져가도 남도록, 판매 호가가 여러 단계 있는 구단을 고른다
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

test('login page has no accessibility violations and logging out returns to it', async ({ page }) => {
  await page.goto('/#login')
  await expect(page.getByPlaceholder('닉네임')).toBeVisible()
  const axe = await new AxeBuilder({ page }).analyze()
  expect(axe.violations).toEqual([])

  await signIn(page)
  await page.goto('/#portfolio')
  await page.getByRole('button', { name: '로그아웃' }).click()
  await expect(page.getByPlaceholder('닉네임')).toBeVisible()
})

test('signing up with an id and password, then logging back in with it', async ({ page }) => {
  const id = `e2e${Date.now() % 100_000_000}${Math.floor(Math.random() * 100)}`
  await page.goto('/#login')
  await page.getByRole('button', { name: '회원가입' }).click()
  await page.getByLabel('아이디').fill(id)
  await page.getByLabel('비밀번호', { exact: true }).fill('e2epass123')
  await page.getByLabel('비밀번호 확인').fill('e2epass12')
  await expect(page.getByText('비밀번호가 서로 달라요')).toBeVisible()
  await expect(page.getByRole('button', { name: '가입하기' })).toBeDisabled()
  await page.getByLabel('비밀번호 확인').fill('e2epass123')
  await expect(page.getByText('쓸 수 있는 아이디예요')).toBeVisible()
  await page.getByRole('button', { name: '가입하기' }).click()
  await expect(page.locator('.tabbar, .cta').first()).toBeVisible()

  // 아이디로 가입한 계정은 내 투자 화면에 로그인 수단(소셜 연동)이 보인다. 연동에서 돌아오면 결과를 알려 준다
  await page.goto('/?link=taken#portfolio')
  await expect(page.getByRole('status').filter({ hasText: '다른 계정에 이미 연결된 소셜 계정이에요' })).toBeVisible()
  await expect(page.locator('.login-methods')).toContainText(id)
  await expect(page).toHaveURL(/\/#portfolio$/)
  await page.getByRole('button', { name: '로그아웃' }).click()
  await page.getByLabel('아이디').fill(id)
  await page.getByLabel('비밀번호').fill('wrongpass1')
  await page.getByRole('button', { name: '로그인', exact: true }).click()
  await expect(page.getByRole('alert')).toHaveText('아이디 또는 비밀번호가 맞지 않아요')
  await page.getByLabel('비밀번호').fill('e2epass123')
  await page.getByRole('button', { name: '로그인', exact: true }).click()
  await expect(page.locator('.tabbar, .cta').first()).toBeVisible()
})

test('league tab shows the schedule and only that league, and a team in a match opens its order page', async ({ page }) => {
  await signIn(page)
  await page.locator('.league-tabs').getByRole('link', { name: 'KBO' }).click()
  await expect(page).toHaveURL(/#league-kbo$/)
  await expect(page.locator('.topbar .brand')).toHaveText('팬차트')
  await expect(page.locator('.tabbar a.on')).toContainText('홈')
  await expect(page.locator('.stock-list li').first()).toBeVisible()
  await expect(page.locator('.stock-name .xs').filter({ hasNotText: 'KBO' })).toHaveCount(0)
  // demo 프로필은 3분마다 리그별 시연 경기를 잡으므로 오늘 일정에 KBO 경기가 있다
  const firstMatch = page.locator('.sched-match').first()
  await expect(firstMatch).toBeVisible({ timeout: 20_000 })
  await expect(page.locator('.sched-day[aria-pressed="true"]')).toContainText('오늘')
  const axe = await new AxeBuilder({ page }).analyze()
  expect(axe.violations).toEqual([])

  await firstMatch.locator('.sched-team').first().click()
  await expect(page).toHaveURL(/#team-[a-z0-9-]+$/)
  await expect(page.locator('.cta')).toBeVisible()
})

test('league tabs can be reordered and hidden, and the choice is kept on the account', async ({ page }) => {
  await signIn(page)
  const tabs = page.locator('.league-tabs a')
  await expect(tabs.nth(1)).toHaveText('KBO')
  await page.getByRole('button', { name: '리그 탭 편집' }).click()
  const sheet = page.getByRole('dialog', { name: '리그 탭 편집' })
  await expect(sheet.getByRole('button', { name: 'KBO 위로' })).toBeDisabled()
  await sheet.getByRole('button', { name: 'LCK 위로' }).click()
  await sheet.getByRole('checkbox', { name: /^K리그/ }).uncheck()
  const axe = await new AxeBuilder({ page }).analyze()
  expect(axe.violations).toEqual([])
  await sheet.getByRole('button', { name: '저장' }).click()
  await expect(sheet).toBeHidden()
  await expect(tabs.nth(1)).toHaveText('LCK')
  await expect(tabs.nth(2)).toHaveText('KBO')
  await expect(page.locator('.league-tabs')).not.toContainText('K리그')

  // 계정에 저장되므로 새로 열어도 그대로다
  await page.reload()
  await expect(tabs.nth(1)).toHaveText('LCK')
  await expect(page.locator('.league-tabs')).not.toContainText('K리그')
  // 숨긴 리그도 주소로 들어오면 그 탭은 보인다
  await page.goto('/#league-kleague')
  await expect(page.locator('.league-tabs a.on')).toHaveText('K리그')

  // 취소하면 바뀌지 않고, 기본 순서로 되돌릴 수 있다
  await page.getByRole('button', { name: '리그 탭 편집' }).click()
  await sheet.getByRole('button', { name: '기본 순서로 되돌리기' }).click()
  await sheet.getByRole('button', { name: '취소' }).click()
  await expect(tabs.nth(1)).toHaveText('LCK')
  await page.getByRole('button', { name: '리그 탭 편집' }).click()
  await sheet.getByRole('button', { name: '기본 순서로 되돌리기' }).click()
  await sheet.getByRole('button', { name: '저장' }).click()
  await expect(tabs.nth(1)).toHaveText('KBO')
  await expect(page.locator('.league-tabs')).toContainText('K리그')
})

test('home lists every team with server prices and has no accessibility violations', async ({ page }) => {
  await signIn(page)
  await expect(page.locator('.stock-list li')).toHaveCount(18)
  // 시장감시는 운영자만 보므로 일반 계정의 아래 탭은 홈·내 투자·랭킹
  await expect(page.locator('.tabbar a')).toHaveText(['홈', '내 투자', '랭킹'])
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
  await page.locator('.sheet .qty-input').fill('1')
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
  await trader.locator('.sheet .qty-input').fill('1')
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

test('discussion posts are stored on the server and others can report them', async ({ page, browser }) => {
  const text = `오늘 경기 기대돼요 ${Date.now() % 100_000}`
  await signIn(page, '#team-lotte')
  await page.getByRole('button', { name: '토론' }).click()
  await page.getByLabel('토론 글').fill(text)
  await page.getByRole('button', { name: '등록' }).click()
  await expect(page.locator('.toast')).toContainText('글을 올렸어요')
  const mine = page.locator('.post', { hasText: text })
  await expect(mine).toContainText('보상 대기')
  const axe = await new AxeBuilder({ page }).analyze()
  expect(axe.violations).toEqual([])

  // 다른 사용자도 같은 글을 본다(서버 저장)
  const other = await browser.newContext({ ...test.info().project.use, baseURL: test.info().project.use.baseURL })
  const reader = await other.newPage()
  await signIn(reader, '#team-lotte')
  await reader.getByRole('button', { name: '토론' }).click()
  const seen = reader.locator('.post', { hasText: text })
  await expect(seen).toBeVisible()
  await seen.getByRole('button', { name: '신고' }).click()
  await seen.getByRole('button', { name: '도배' }).click()
  await expect(reader.locator('.toast')).toContainText('신고했어요')
  await other.close()

  await mine.getByRole('button', { name: '삭제' }).click()
  await expect(page.locator('.post', { hasText: text })).toHaveCount(0)
})

test('a demo ad pays points only after the server confirms the watch', async ({ page }) => {
  await signIn(page, '#portfolio')
  const card = page.locator('.ad-card')
  await expect(card).toContainText('오늘 0/10회')
  const before = await page.locator('.split b').first().textContent()

  await card.getByRole('button', { name: '광고 보기' }).click()
  const dialog = page.getByRole('dialog', { name: '시연용 광고' })
  await expect(dialog).toBeVisible()
  // 시트가 올라오는 동안은 반투명이라 명암비가 낮게 잡힌다. 애니메이션이 끝난 뒤 검사한다
  await dialog.evaluate((el) => Promise.all(el.getAnimations().map((a) => a.finished)))
  const axe = await new AxeBuilder({ page }).analyze()
  expect(axe.violations).toEqual([])

  // 서버가 시청 시간을 확인한 뒤에만 지급한다
  await expect(page.locator('.toast')).toContainText('1,000P를 받았어요', { timeout: 30_000 })
  await expect(dialog).toBeHidden()
  await expect(card).toContainText('오늘 1/10회')
  await expect(page.locator('.split b').first()).not.toHaveText(before!)
})

test('changing my nickname follows the server rules and locks it for a week', async ({ page }) => {
  await signIn(page, '#portfolio')
  const card = page.locator('.profile-card')
  await expect(card).toContainText(/투자자\d{6}/)

  await card.getByRole('button', { name: '바꾸기' }).click()
  const dialog = page.getByRole('dialog', { name: '닉네임 바꾸기' })
  await dialog.evaluate((el) => Promise.all(el.getAnimations().map((a) => a.finished)))
  const axe = await new AxeBuilder({ page }).analyze()
  expect(axe.violations).toEqual([])

  const input = dialog.getByLabel(/2~12자/)
  await input.fill('팬차트운영자')
  await dialog.getByRole('button', { name: '바꾸기' }).click()
  await expect(dialog.getByRole('alert')).toContainText('운영자')

  const name = `닉${Date.now() % 1_000_000}`
  await input.fill(name)
  await dialog.getByRole('button', { name: '바꾸기' }).click()
  await expect(page.locator('.toast')).toContainText(`${name}(으)로 바꿨어요`)
  await expect(dialog).toBeHidden()
  await expect(card.locator('h2')).toHaveText(name)
  await expect(card).toContainText('부터 다시 바꿀 수 있어요')
  await expect(card.getByRole('button', { name: '바꾸기' })).toBeDisabled()
})

test('terms and privacy are readable before logging in and from my page', async ({ page }) => {
  await page.goto('/#login')
  await page.getByRole('link', { name: '개인정보처리방침' }).click()
  await expect(page.getByRole('heading', { name: '개인정보처리방침' })).toBeVisible()
  await expect(page.locator('.legal')).toContainText('이름, 이메일, 전화번호, 프로필 사진은 받지 않아요')
  const axe = await new AxeBuilder({ page }).analyze()
  expect(axe.violations).toEqual([])
  await page.getByRole('button', { name: '뒤로' }).click()
  await expect(page.getByPlaceholder('닉네임')).toBeVisible()

  await signIn(page, '#portfolio')
  await page.locator('.legal-links').getByRole('link', { name: '이용약관' }).click()
  await expect(page.getByRole('heading', { name: '이용약관' })).toBeVisible()
  await expect(page.locator('.legal')).toContainText('현금이나 상품으로 바꿀 수 없고')
})

test('withdrawing logs me out and blocks signing up again with the same login', async ({ page }) => {
  const nickname = `bye${Date.now() % 1_000_000}`
  await page.goto('/#portfolio')
  await page.getByPlaceholder('닉네임').fill(nickname)
  await page.getByRole('button', { name: '들어가기' }).click()
  await expect(page.locator('.tabbar')).toBeVisible()

  await page.getByRole('button', { name: '탈퇴하기' }).click()
  const dialog = page.getByRole('dialog', { name: '정말 탈퇴할까요?' })
  await dialog.evaluate((el) => Promise.all(el.getAnimations().map((a) => a.finished)))
  const axe = await new AxeBuilder({ page }).analyze()
  expect(axe.violations).toEqual([])
  await dialog.getByRole('button', { name: '취소' }).click()
  await expect(dialog).toBeHidden()

  await page.getByRole('button', { name: '탈퇴하기' }).click()
  await dialog.getByRole('button', { name: '탈퇴할게요' }).click()
  await expect(page.locator('.toast')).toContainText('탈퇴했어요')
  await expect(page.getByPlaceholder('닉네임')).toBeVisible()

  await page.getByPlaceholder('닉네임').fill(nickname)
  await page.getByRole('button', { name: '들어가기' }).click()
  await expect(page.getByRole('alert')).toContainText('30일이 지나야')
})

// 개발 서버의 /src/api/ 모듈은 막지 않도록 경로로 고른다
const serverApi = (url: URL) => url.pathname.startsWith('/api/')

test('never shows default numbers without the server and marks values as stale when the server drops', async ({ page }) => {
  // 서버에 한 번도 닿지 못하면 기본 자산·기준가 대신 연결 상태만 보여준다
  await page.route(serverApi, (route) => route.abort())
  await page.goto('/#home')
  await expect(page.getByText('서버에 연결할 수 없어요')).toBeVisible()
  await expect(page.locator('.asset-card')).toHaveCount(0)

  // 다시 닿으면 스스로 연결한다
  await page.unroute(serverApi)
  await expect(page.locator('.asset-card')).toBeVisible()

  // 받은 뒤 끊기면 화면 값이 언제 기준인지 알린다
  await page.route(serverApi, (route) => route.abort())
  await expect(page.getByRole('alert')).toContainText('기준 값이에요')
  await page.unroute(serverApi)
  await expect(page.getByRole('alert')).toHaveCount(0)
})

test('a star on the team page adds it to 관심 구단 on home, which stays after reload and the star there removes it', async ({ page }) => {
  await signIn(page, '#team-t1')
  const star = page.getByRole('button', { name: 'T1 관심 구단' })
  await expect(star).toHaveAttribute('aria-pressed', 'false')
  await star.click()
  await expect(star).toHaveAttribute('aria-pressed', 'true')
  await expect(page.getByRole('status')).toContainText('관심 구단에 담았어요')

  await page.goto('/#home')
  const card = page.locator('.watch-card')
  await expect(card.locator('.stock-list li')).toHaveCount(1)
  await expect(card).toContainText('T1')
  await page.reload()
  await expect(card.locator('.stock-list li')).toHaveCount(1)
  const axe = await new AxeBuilder({ page }).analyze()
  expect(axe.violations).toEqual([])

  await card.getByRole('button', { name: 'T1 관심 구단' }).click()
  await expect(card.locator('.stock-list li')).toHaveCount(0)
  await expect(card).toContainText('별을 누르면 여기에 모여요')
  // 별만 눌렀으므로 구단 화면으로 넘어가지 않는다
  await expect(page).toHaveURL(/#home$/)
})

test('정보 tab shows the dividend terms from the server and 뉴스·공시 is read-only for users', async ({ page }) => {
  await signIn(page, '#team-t1')
  await page.locator('.tabs').getByRole('button', { name: '정보' }).click()
  // LCK 1승 배당(DividendPolicy)과 국제 대회 배수
  await expect(page.locator('.info-win')).toContainText('1주당 160P')
  const table = page.getByRole('region', { name: '대회별 배당' })
  await expect(table.locator('tbody tr').first()).toContainText('LCK')
  await expect(table).toContainText('롤드컵')
  await expect(table).not.toContainText('챔스')
  // 긴 설명은 화면에 바로 두지 않고 물음표를 눌러야 보인다
  await expect(page.getByText('10P 단위로 반올림')).toHaveCount(0)
  await table.getByRole('button', { name: '대회별 배당 안내' }).click()
  await expect(page.getByRole('complementary', { name: '대회별 배당 안내' })).toContainText('10P 단위로 반올림')
  const axe = await new AxeBuilder({ page }).analyze()
  expect(axe.violations).toEqual([])
  await page.keyboard.press('Escape')
  await expect(page.getByRole('complementary', { name: '대회별 배당 안내' })).toHaveCount(0)

  await page.locator('.tabs').getByRole('button', { name: '뉴스·공시' }).click()
  // 시연 서버는 뉴스 키가 없다
  await expect(page.getByRole('region', { name: '뉴스' })).toContainText('뉴스는 준비 중이에요')
  await page.locator('.news-sub').getByRole('button', { name: '공시' }).click()
  await expect(page.getByRole('region', { name: '공시' })).toBeVisible()
  await expect(page.getByRole('button', { name: '공시 쓰기' })).toHaveCount(0)
})

test('anyone can look around without logging in, and an order asks to log in first then comes back', async ({ page }) => {
  await page.goto('/')
  await expect(page.getByPlaceholder('닉네임')).toHaveCount(0)
  await expect(page.locator('.asset-card')).toContainText('로그인하고 확인하기')
  await expect(page.locator('.list-card .stock-list li').first()).toBeVisible()

  await page.goto('/#team-t1')
  await page.locator('.tabs').getByRole('button', { name: '토론' }).click()
  await expect(page.getByLabel('토론 글')).toBeVisible()
  // 관심 구단 담기는 계정이 필요하다
  await page.getByRole('button', { name: 'T1 관심 구단' }).click()
  await expect(page).toHaveURL(/#login$/)
  await page.getByRole('button', { name: '뒤로' }).click()
  await expect(page).toHaveURL(/#team-t1$/)

  await page.getByRole('button', { name: '구매하기' }).click()
  await expect(page).toHaveURL(/#login$/)
  await page.getByPlaceholder('닉네임').fill(`e2e${Date.now() % 1_000_000}`)
  await page.getByRole('button', { name: '들어가기' }).click()
  await expect(page).toHaveURL(/#team-t1$/)
  await page.getByRole('button', { name: '구매하기' }).click()
  await expect(page.locator('.sheet')).toBeVisible()
})
