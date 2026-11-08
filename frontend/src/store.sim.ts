// 서버 없이 브라우저 안에서 돌아가는 저장소. 미리보기와 서버 없는 개발에 쓴다.
// 다른 유저들은 시뮬레이션 계정(bot)이 실제 유저처럼 호가를 내는 방식으로 흉내 낸다.
// 시스템이 호가를 받아주는 마켓메이커는 없다. 모든 체결은 계정 대 계정이다.
import { emit } from './storeCore'
import {
  type Book,
  type Order,
  type OrderType,
  type Side,
  cancelOrder as bookCancel,
  priceBand,
  stepPrice,
  submitOrder,
  tickSize,
  validateOrder,
} from './engine/orderbook'
import { detect } from './engine/fds'
import { type Candle, type CandlePage, type CandleUnit, addTrade, bucketStart, isMinute } from './chart/candles'
import { DEFAULT_TABS, type LeagueTabPrefs } from './data/leagueTabs'
import { DIVIDEND_RATE, LEAGUES, type League, SEED_POSTS, TEAMS, TODAY_MATCHES, teamById } from './data/teams'
import type { MatchDto, NewsDto, NoticeDto, OverviewDto } from './store.server'
import { ME, START_POINTS, type Holding, type Market, type Post, type OrderResult, type State, type User } from './storeTypes'

interface SimState extends State {
  seq: number
  bias: Record<string, number>
}

const STORAGE_KEY = 'fanchart:v1'
const TABS_KEY = 'fanchart:league-tabs'
const WATCH_KEY = 'fanchart:watchlist'
const BOT_NAMES = [
  '잠실불꽃', '광주호랑이', '보살팬', '페이커장인', '호랑이굴', '쌍둥이아빠', '사직노래방', '대구왕자',
  '마법사의제자', '인천상륙', '창원공룡', '고척돔지기', '스틸야드', '상암직관러', '전주성', 'midgap',
  '골드카드', '야구는9회말', '롤드컵가자', '배당장인',
]

function rng(seed: number) {
  return () => {
    seed |= 0
    seed = (seed + 0x6d2b79f5) | 0
    let t = Math.imul(seed ^ (seed >>> 15), 1 | seed)
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}

const randInt = (min: number, max: number) => min + Math.floor(Math.random() * (max - min + 1))
const pick = <T,>(arr: T[]) => arr[Math.floor(Math.random() * arr.length)]

let state: SimState

const nextId = () => `${++state.seq}`

export function lockedShares(userId: string, teamId: string) {
  let n = 0
  for (const o of state.books[teamId].asks) if (o.userId === userId) n += o.remaining
  return n
}

export function totalShares(userId: string, teamId: string) {
  return (state.users[userId].holdings[teamId]?.qty ?? 0) + lockedShares(userId, teamId)
}

export function lockedPoints(userId: string) {
  let n = 0
  for (const t of TEAMS)
    for (const o of state.books[t.id].bids) if (o.userId === userId) n += o.price * o.remaining
  return n
}

export function totalValue(userId: string) {
  let v = state.users[userId].points + lockedPoints(userId)
  for (const t of TEAMS) v += totalShares(userId, t.id) * state.markets[t.id].last
  return v
}

export function openOrders(userId: string): Order[] {
  const out: Order[] = []
  for (const t of TEAMS) {
    const b = state.books[t.id]
    for (const o of [...b.bids, ...b.asks]) if (o.userId === userId) out.push(o)
  }
  return out.sort((a, b) => b.ts - a.ts)
}

function addShares(u: User, teamId: string, qty: number, price: number) {
  const h = (u.holdings[teamId] ??= { qty: 0, avgPrice: 0 })
  const total = h.qty + lockedShares(u.id, teamId)
  h.avgPrice = Math.round((h.avgPrice * total + price * qty) / (total + qty))
  h.qty += qty
}

export function placeOrder(
  userId: string,
  teamId: string,
  side: Side,
  type: OrderType,
  price: number,
  qty: number,
): OrderResult {
  const team = teamById(teamId)
  const err = validateOrder({ type, price, qty }, team.basePrice)
  if (err) return { ok: false, message: err }
  const u = state.users[userId]
  const { upper } = priceBand(team.basePrice)
  // 시장가 매수는 상한가 기준으로 포인트를 묶어두고 체결 후 차액을 돌려준다
  const reservePrice = type === 'limit' ? price : upper

  if (side === 'buy') {
    const cost = reservePrice * qty
    if (u.points < cost) return { ok: false, message: '주문 가능 포인트가 부족해요' }
    u.points -= cost
  } else {
    const h = u.holdings[teamId]
    if (!h || h.qty < qty) return { ok: false, message: '판매 가능 수량이 부족해요' }
    h.qty -= qty
  }

  const order: Order = {
    id: nextId(), userId, teamId, side, type,
    price: type === 'limit' ? price : 0, qty, remaining: qty, ts: Date.now(),
  }
  const book = state.books[teamId]
  const market = state.markets[teamId]
  const result = submitOrder(book, order, nextId, order.ts)

  for (const t of result.trades) {
    const buyer = state.users[t.buyerId]
    const seller = state.users[t.sellerId]
    const buyerReserve = t.buyerId === userId ? reservePrice : t.price // 대기 매수 주문은 자기 지정가에 체결
    buyer.points += (buyerReserve - t.price) * t.qty
    addShares(buyer, teamId, t.qty, t.price)
    seller.points += t.price * t.qty

    const alerts = detect(t, market.last, state.trades, nextId)
    state.alerts.unshift(...alerts)
    state.trades.unshift(t)
    market.last = t.price
    market.high = Math.max(market.high, t.price)
    market.low = Math.min(market.low, t.price)
    market.volume += t.qty
    market.intraday.push({ ts: t.ts, price: t.price })
  }
  state.trades.length = Math.min(state.trades.length, 400)
  state.alerts.length = Math.min(state.alerts.length, 100)
  if (market.intraday.length > 400) market.intraday.splice(0, market.intraday.length - 400)

  if (result.canceledQty > 0) {
    if (side === 'buy') u.points += reservePrice * result.canceledQty
    else u.holdings[teamId].qty += result.canceledQty
  }

  const filled = qty - result.canceledQty - (result.resting?.remaining ?? 0)
  let message: string
  if (result.selfTradePrevented)
    message = `내 반대 주문과 체결될 수 없어 ${result.canceledQty}주를 취소했어요 (자기매매 방지)`
  else if (type === 'market' && result.canceledQty > 0)
    message = filled > 0 ? `${filled}주 체결, 남은 ${result.canceledQty}주는 취소됐어요` : '체결할 호가가 없어 취소됐어요'
  else if (filled === qty) message = `${qty}주 모두 체결됐어요`
  else if (filled > 0) message = `${filled}주 체결, ${qty - filled}주는 대기 중이에요`
  else message = '주문이 접수됐어요. 호가창에서 대기 중이에요'
  return { ok: true, message }
}

export function cancel(userId: string, teamId: string, orderId: string) {
  const book = state.books[teamId]
  const o = [...book.bids, ...book.asks].find((x) => x.id === orderId)
  if (!o || o.userId !== userId) return
  bookCancel(book, orderId)
  const u = state.users[userId]
  if (o.side === 'buy') u.points += o.price * o.remaining
  else u.holdings[teamId].qty += o.remaining
}

export function addPost(teamId: string, body: string) {
  state.posts.unshift({ id: nextId(), teamId, userId: ME, author: state.users[ME].name, body, ts: Date.now() })
  emit()
}

export function settleMatches(): string {
  const now = Date.now()
  let mine = 0
  let count = 0
  for (const m of TODAY_MATCHES) {
    if (state.settled.includes(m.id)) continue
    state.settled.push(m.id)
    count++
    if (m.homeScore === m.awayScore) continue
    const winner = m.homeScore > m.awayScore ? m.home : m.away
    const perShare = Math.round(teamById(winner).basePrice * DIVIDEND_RATE)
    for (const u of Object.values(state.users)) {
      const qty = totalShares(u.id, winner)
      if (qty === 0) continue
      u.points += qty * perShare
      if (u.id === ME) {
        mine += qty * perShare
        state.dividends.unshift({ matchId: m.id, teamId: winner, qty, amount: qty * perShare, ts: now })
      }
    }
  }
  emit()
  if (count === 0) return '오늘 경기는 이미 정산됐어요'
  return mine > 0 ? `배당 ${mine.toLocaleString()}P를 받았어요` : '정산 완료. 승리 구단을 보유하지 않아 배당이 없어요'
}

// ---------- 시뮬레이션 유저 ----------

function botOrder() {
  const team = pick(TEAMS)
  const bot = state.users[`bot${randInt(0, BOT_NAMES.length - 1)}`]
  const m = state.markets[team.id]
  const { upper, lower } = priceBand(team.basePrice)
  state.bias[team.id] = Math.max(-1, Math.min(1, state.bias[team.id] + (Math.random() - 0.5) * 0.3))
  const side: Side = Math.random() < 0.5 + state.bias[team.id] * 0.25 ? 'buy' : 'sell'
  if (Math.random() < 0.12) {
    placeOrder(bot.id, team.id, side, 'market', 0, randInt(1, 5))
    return
  }
  const aggressive = Math.random() < 0.4
  const ticks = aggressive ? -randInt(0, 2) : randInt(1, 5)
  const price = Math.min(upper, Math.max(lower, stepPrice(m.last, side === 'buy' ? -ticks : ticks)))
  placeOrder(bot.id, team.id, side, 'limit', price, randInt(1, 15))
}

// 현재가에서 멀어진 시뮬레이션 주문을 정리한다
function pruneFar(teamId: string) {
  const b = state.books[teamId]
  const range = tickSize(state.markets[teamId].last) * 12
  for (const o of [...b.bids, ...b.asks])
    if (o.userId !== ME && Math.abs(o.price - state.markets[teamId].last) > range) cancel(o.userId, teamId, o.id)
}

function botTick() {
  const now = Date.now()
  // 오래된 대기 주문은 취소해서 호가창이 현재가 주변에 머물게 한다
  for (const t of TEAMS) {
    const b = state.books[t.id]
    for (const o of [...b.bids, ...b.asks])
      if (o.userId !== ME && now - o.ts > 120_000 && Math.random() < 0.3) cancel(o.userId, t.id, o.id)
  }

  TEAMS.forEach((t) => pruneFar(t.id))
  for (let i = 0; i < 3; i++) botOrder()
  emit()
}

// ---------- 초기화 ----------

function freshState(): SimState {
  const r = rng(42)
  const users: Record<string, User> = {}
  users[ME] = { id: ME, name: '나', points: START_POINTS, holdings: {}, initialValue: START_POINTS, isBot: false }
  BOT_NAMES.forEach((name, i) => {
    const holdings: Record<string, Holding> = {}
    let value = 3_000_000
    for (const t of TEAMS) {
      const qty = 30 + Math.floor(r() * 50)
      holdings[t.id] = { qty, avgPrice: t.basePrice }
      value += qty * t.basePrice
    }
    users[`bot${i}`] = { id: `bot${i}`, name, points: 3_000_000, holdings, initialValue: value, isBot: true }
  })

  const books: Record<string, Book> = {}
  const markets: Record<string, Market> = {}
  const bias: Record<string, number> = {}
  const now = Date.now()
  for (const t of TEAMS) {
    books[t.id] = { bids: [], asks: [] }
    markets[t.id] = {
      base: t.basePrice, listed: true, last: t.basePrice, high: t.basePrice, low: t.basePrice, volume: 0,
      intraday: [{ ts: now - 60_000, price: t.basePrice }],
    }
    bias[t.id] = (r() - 0.5) * 1.2
  }

  const posts: Post[] = SEED_POSTS.map((p, i) => ({
    id: `p${i}`, teamId: p.teamId, userId: 'seed', author: p.author, body: p.body,
    ts: now - p.minutesAgo * 60_000,
  }))

  return {
    seq: 0, users, books, markets, trades: [], alerts: [], posts, dividends: [], settled: [], bias,
  }
}

function seedBooks() {
  for (const t of TEAMS) {
    for (let i = 1; i <= 8; i++) {
      placeOrder(`bot${randInt(0, BOT_NAMES.length - 1)}`, t.id, 'sell', 'limit', stepPrice(t.basePrice, i), randInt(5, 40))
      placeOrder(`bot${randInt(0, BOT_NAMES.length - 1)}`, t.id, 'buy', 'limit', stepPrice(t.basePrice, -i), randInt(5, 40))
    }
  }
  // 첫 화면부터 시세가 움직이도록 오늘 장중 거래를 미리 돌려둔다
  for (let i = 0; i < 1500; i++) {
    botOrder()
    if (i % 100 === 0) TEAMS.forEach((t) => pruneFar(t.id))
  }
  TEAMS.forEach((t) => pruneFar(t.id))
}

function load(): SimState | null {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    return raw ? (JSON.parse(raw) as SimState) : null
  } catch {
    return null
  }
}

function save() {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(state))
  } catch {
    // 저장이 막힌 환경에서는 새로고침 시 초기화된다
  }
}

export function reset() {
  state = freshState()
  seedBooks()
  save()
  emit()
}

/** 시뮬레이션 미리보기는 계정이 없으므로 리그 탭 설정을 이 브라우저에 둔다 */
export function getLeagueTabs(): LeagueTabPrefs {
  try {
    return { ...DEFAULT_TABS, ...JSON.parse(localStorage.getItem(TABS_KEY) ?? '{}') }
  } catch {
    return DEFAULT_TABS
  }
}

export function saveLeagueTabs(prefs: LeagueTabPrefs): OrderResult {
  try {
    localStorage.setItem(TABS_KEY, JSON.stringify(prefs))
  } catch {
    return { ok: false, message: '이 브라우저에는 저장할 수 없어요' }
  }
  emit()
  return { ok: true, message: '리그 탭을 저장했어요' }
}

/** 시뮬레이션 미리보기의 관심 구단도 이 브라우저에 둔다 */
let watchlist: string[] | null = null
export function getWatchlist(): string[] {
  if (!watchlist) {
    try {
      const saved = JSON.parse(localStorage.getItem(WATCH_KEY) ?? '[]')
      watchlist = Array.isArray(saved) ? saved.filter((id): id is string => typeof id === 'string') : []
    } catch {
      watchlist = []
    }
  }
  return watchlist
}

export function toggleWatch(teamId: string): OrderResult {
  const list = getWatchlist()
  const on = !list.includes(teamId)
  watchlist = on ? [...list, teamId] : list.filter((id) => id !== teamId)
  try {
    localStorage.setItem(WATCH_KEY, JSON.stringify(watchlist))
  } catch {
    /* 이번 방문 동안만 기억한다 */
  }
  emit()
  return { ok: true, message: on ? '관심 구단에 담았어요' : '관심 구단에서 뺐어요' }
}

export function start() {
  state = load() ?? freshState()
  if (state.seq === 0) seedBooks()
  setInterval(botTick, 1500)
  setInterval(save, 3000)
}

export const getState = (): State => state

/** 리그 화면 달력. 미리보기에는 경기 일정이 없으므로 목업 경기를 오늘 저녁 일정으로 보여준다 */
export function fetchSchedule(league: League, from: Date, to: Date): Promise<MatchDto[]> {
  const at = new Date()
  at.setHours(18, 30, 0, 0)
  if (at < from || at >= to) return Promise.resolve([])
  return Promise.resolve(TODAY_MATCHES.filter((m) => m.league === league).map((m, i) => ({
    id: i + 1, league: LEAGUES.find((l) => l.id === m.league)!.code, competition: LEAGUES.find((l) => l.id === m.league)!.code,
    homeTeamId: m.home, homeName: null, awayTeamId: m.away, awayName: null,
    scheduledAt: at.toISOString(), status: 'FINAL', homeScore: m.homeScore, awayScore: m.awayScore,
    dividendStatus: state.settled.includes(m.id) ? 'PAID' : 'NONE',
  })))
}

// 미리보기의 구단정보. 금액은 서버 배당 규칙(DividendPolicy)의 리그 승리 배당을 옮겨 적은 예시다
const SIM_WIN_DPS: Record<League, number> = { KBO: 40, LCK: 160, 프리미어리그: 210, 라리가: 210, 세리에A: 210, 분데스리가: 240, NBA: 70, K리그: 210 }
const AWARD_MULTIPLES: [string, number][] = [
  ['CHAMPION', 1], ['RUNNER_UP', 0.6], ['SEMIFINAL', 0.35], ['QUARTERFINAL', 0.25], ['POSTSEASON', 0.15], ['REGULAR_FIRST', 0.3],
]

export function fetchOverview(teamId: string): Promise<OverviewDto> {
  const team = teamById(teamId)
  const code = LEAGUES.find((l) => l.id === team.league)!.code
  const holders = Object.values(state.users).filter((u) => totalShares(u.id, teamId) > 0)
  return Promise.resolve({
    teamId, league: code,
    shares: holders.reduce((n, u) => n + totalShares(u.id, teamId), 0), holders: holders.length,
    winDps: SIM_WIN_DPS[team.league], confirmDelayMinutes: 360,
    competitions: [{ competition: code, weight: 1, winDps: SIM_WIN_DPS[team.league], championDps: 3000 }],
    awards: AWARD_MULTIPLES.map(([award, m]) => ({ award, dps: 3000 * m })),
    upcoming: [],
  })
}

/** 미리보기는 뉴스 키가 없으므로 준비 중으로 보인다 */
export const fetchNews = (): Promise<NewsDto> => Promise.resolve({ available: false, articles: [] })

/** 미리보기의 공시는 이 탭에서만 기억한다(새로고침하면 처음 예시로) */
let simNotices: NoticeDto[] = [{
  id: 1, teamId: null, title: '팬차트 공시 탭을 열었어요',
  body: '경기 일정, 배당 지급, 운영 안내처럼 매수 판단에 필요한 소식을 이곳에 올려요.',
  createdAt: new Date().toISOString(), updatedAt: null,
}]

export const fetchNotices = (teamId: string): Promise<NoticeDto[]> =>
  Promise.resolve(simNotices.filter((n) => n.teamId === null || n.teamId === teamId))

export function writeNotice(teamId: string | null, title: string, body: string): OrderResult {
  simNotices = [{ id: Math.max(0, ...simNotices.map((n) => n.id)) + 1, teamId, title, body, createdAt: new Date().toISOString(), updatedAt: null }, ...simNotices]
  return { ok: true, message: '공시를 올렸어요' }
}

export function editNotice(id: number, title: string, body: string): OrderResult {
  simNotices = simNotices.map((n) => (n.id === id ? { ...n, title, body, updatedAt: new Date().toISOString() } : n))
  return { ok: true, message: '공시를 고쳤어요' }
}

export function deleteNotice(id: number): OrderResult {
  simNotices = simNotices.filter((n) => n.id !== id)
  return { ok: true, message: '공시를 지웠어요' }
}

/**
 * 미리보기 차트 봉. 이번 방문의 시뮬레이션 체결로 만든 봉 앞에 구단·단위별로 고정된 가상 과거 봉을 붙인다.
 * 과거 봉은 더 불러오지 않는다
 */
export function fetchCandles(teamId: string, unit: CandleUnit, before?: number): Promise<CandlePage> {
  if (before) return Promise.resolve({ candles: [], lastTradeId: 0, more: false })
  const mine = state.trades.filter((t) => t.teamId === teamId).reverse()
  let real: Candle[] = []
  for (const t of mine) real = addTrade(real, unit, t.ts, t.price, t.qty)
  const vol = isMinute(unit) ? 0.0015 * Math.sqrt(parseInt(unit)) : { day: 0.02, week: 0.045, month: 0.09, year: 0.3 }[unit as 'day']
  const r = rng([...teamId + unit].reduce((h, ch) => h * 31 + ch.charCodeAt(0), 7))
  const past: Candle[] = []
  let time = real[0]?.time ?? bucketStart(Date.now(), unit)
  let close = real[0]?.open ?? state.markets[teamId].last
  for (let i = 0; i < 150; i++) {
    time = bucketStart(time - 1, unit)
    const open = Math.max(1, Math.round(close / (1 + (r() - 0.5) * 2 * vol)))
    const high = Math.round(Math.max(open, close) * (1 + r() * vol * 0.6))
    const low = Math.round(Math.min(open, close) * (1 - r() * vol * 0.6))
    past.unshift({ time, open, high, low, close, volume: 5 + Math.floor(r() * 200) })
    close = open
  }
  return Promise.resolve({ candles: [...past, ...real], lastTradeId: Math.max(0, ...state.trades.map((t) => Number(t.id))), more: false })
}
