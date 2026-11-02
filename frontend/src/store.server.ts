// 서버에 붙는 저장소. 시세 목록·내 계좌·랭킹·감시 기록은 주기적으로 받아오고,
// 보고 있는 구단의 호가와 체결은 WebSocket 실시간 시세로 받는다.
// 로그인은 세션 쿠키로 유지한다. 상태를 바꾸는 요청에는 서버가 준 XSRF-TOKEN 쿠키 값을 헤더로 돌려보낸다.
// 화면에서 내 계정은 ME로만 보인다.
import { SEED_POSTS, TEAMS } from './data/teams'
import type { Book, Order, OrderType, Side, Trade } from './engine/orderbook'
import type { Alert } from './engine/fds'
import { type FeedMessage, type TeamFeed, applyFeed } from './api/feed'
import { emit } from './storeCore'
import { ME, START_POINTS, type Market, type OrderResult, type Post, type State, type User } from './storeTypes'

const POLL_MS = 3000

interface QuoteDto { id: string; lastPrice: number; high: number; low: number; volume: number }
interface HoldingDto { teamId: string; available: number; locked: number; averagePrice: number }
interface AccountDto { id: string; nickname: string; availablePoints: number; lockedPoints: number; totalValue: number; holdings: HoldingDto[] }
interface OrderDto { id: number; teamId: string; side: 'BUY' | 'SELL'; type: 'LIMIT' | 'MARKET'; price: number; quantity: number; remaining: number; createdAt: string }
interface MyTradeDto { id: number; teamId: string; side: 'BUY' | 'SELL'; price: number; quantity: number; executedAt: string }
interface RankingDto { rank: number; nickname: string; totalValue: number; returnRate: number; me: boolean }
interface AlertDto { kind: string; teamId: string; tradeId: number; nicknameA: string; nicknameB: string; message: string; detectedAt: string }
interface ReceiptDto { message: string }
export interface LoginOptions { providers: { id: string; name: string; loginUrl: string }[]; devLogin: boolean }

let login: LoginOptions | null = null
let poller: ReturnType<typeof setInterval> | undefined
let account: AccountDto | null = null
let quotes: Record<string, QuoteDto> = {}
let orders: OrderDto[] = []
let myTrades: MyTradeDto[] = []
let rankings: RankingDto[] = []
let alerts: AlertDto[] = []
const feeds: Record<string, TeamFeed> = {}
const posts: Post[] = SEED_POSTS.map((p, i) => ({
  id: `p${i}`, teamId: p.teamId, userId: 'seed', author: p.author, body: p.body, ts: Date.now() - p.minutesAgo * 60_000,
}))
let state: State = build()

// ---------- HTTP ----------

class ApiError extends Error {
  readonly status: number
  constructor(status: number, message: string) {
    super(message)
    this.status = status
  }
}

function csrfToken() {
  const m = document.cookie.match(/(?:^|; )XSRF-TOKEN=([^;]*)/)
  return m ? decodeURIComponent(m[1]) : ''
}

async function api<T>(method: string, path: string, body?: unknown): Promise<T> {
  const res = await fetch(path, {
    method,
    headers: { 'Content-Type': 'application/json', ...(method === 'GET' ? {} : { 'X-XSRF-TOKEN': csrfToken() }) },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  if (!res.ok) {
    const err = await res.json().catch(() => null)
    throw new ApiError(res.status, err?.message ?? '서버와 통신하지 못했어요')
  }
  return res.status === 204 ? (undefined as T) : res.json()
}

/** 로그인돼 있으면 true. 아니면 로그인 화면에 보여줄 방법을 받아 둔다 */
async function ensureLogin(): Promise<boolean> {
  try {
    account = await api<AccountDto>('GET', '/api/accounts/me')
    login = null
    return true
  } catch (e) {
    if (!(e instanceof ApiError && e.status === 401)) throw e
    account = null
    login = await api<LoginOptions>('GET', '/api/auth/options')
    update()
    return false
  }
}

async function refresh() {
  const [q, a, o, t, r, al] = await Promise.all([
    api<QuoteDto[]>('GET', '/api/teams'),
    api<AccountDto>('GET', '/api/accounts/me'),
    api<OrderDto[]>('GET', '/api/accounts/me/orders'),
    api<MyTradeDto[]>('GET', '/api/accounts/me/trades?limit=50'),
    api<RankingDto[]>('GET', '/api/rankings'),
    api<AlertDto[]>('GET', '/api/surveillance/alerts?limit=50'),
  ])
  quotes = Object.fromEntries(q.map((x) => [x.id, x]))
  account = a
  orders = o
  myTrades = t
  rankings = r
  alerts = al
  update()
}

// ---------- 실시간 시세 ----------

const sockets: Record<string, { ws: WebSocket; watchers: number }> = {}

function connect(teamId: string) {
  const proto = location.protocol === 'https:' ? 'wss' : 'ws'
  const ws = new WebSocket(`${proto}://${location.host}/ws/teams/${teamId}`)
  ws.onmessage = (e) => {
    const next = applyFeed(feeds[teamId] ?? null, JSON.parse(e.data) as FeedMessage)
    if (next) {
      feeds[teamId] = next
      update()
    } else {
      // 업데이트가 빠졌거나 서버가 다시 떴다. 새 스냅숏부터 받는다
      delete feeds[teamId]
      ws.close()
    }
  }
  ws.onclose = () => {
    const sub = sockets[teamId]
    if (sub?.ws === ws && sub.watchers > 0) setTimeout(() => sockets[teamId]?.watchers && (sub.ws = connect(teamId)), 1000)
  }
  return ws
}

export function watchTeam(teamId: string): () => void {
  const sub = (sockets[teamId] ??= { ws: connect(teamId), watchers: 0 })
  sub.watchers++
  return () => {
    sub.watchers--
    if (sub.watchers > 0) return
    delete sockets[teamId]
    delete feeds[teamId]
    sub.ws.close()
  }
}

// ---------- 화면 상태로 변환 ----------

const lower = (s: string) => s.toLowerCase() as Side & OrderType

function toOrder(o: OrderDto): Order {
  return {
    id: String(o.id), userId: ME, teamId: o.teamId, side: lower(o.side), type: lower(o.type),
    price: o.price, qty: o.quantity, remaining: o.remaining, ts: Date.parse(o.createdAt),
  }
}

/** 호가는 가격별 합계로 오므로 단계마다 주문 하나로 표현한다 */
function toBook(teamId: string, feed: TeamFeed | undefined): Book {
  const level = (side: Side) => (l: { price: number; quantity: number }): Order => ({
    id: `${side}${l.price}`, userId: '', teamId, side, type: 'limit', price: l.price, qty: l.quantity, remaining: l.quantity, ts: 0,
  })
  return { bids: (feed?.bids ?? []).map(level('buy')), asks: (feed?.asks ?? []).map(level('sell')) }
}

function build(): State {
  const mine = new Map(myTrades.map((t) => [t.id, t]))
  const trades: Trade[] = []
  const seen = new Set<number>()
  for (const [teamId, feed] of Object.entries(feeds))
    for (const t of feed.trades) {
      const m = mine.get(t.id)
      seen.add(t.id)
      trades.push({
        id: String(t.id), teamId, price: t.price, qty: t.quantity, aggressor: lower(t.aggressor),
        buyerId: m?.side === 'BUY' ? ME : '', sellerId: m?.side === 'SELL' ? ME : '',
        buyOrderId: '', sellOrderId: '', ts: Date.parse(t.executedAt),
      })
    }
  for (const t of myTrades)
    if (!seen.has(t.id))
      trades.push({
        id: String(t.id), teamId: t.teamId, price: t.price, qty: t.quantity, aggressor: lower(t.side),
        buyerId: t.side === 'BUY' ? ME : '', sellerId: t.side === 'SELL' ? ME : '',
        buyOrderId: '', sellOrderId: '', ts: Date.parse(t.executedAt),
      })
  trades.sort((a, b) => b.ts - a.ts || Number(b.id) - Number(a.id))

  const markets: Record<string, Market> = {}
  const books: Record<string, Book> = {}
  for (const team of TEAMS) {
    const feed = feeds[team.id]
    const q = feed?.quote ?? quotes[team.id]
    const last = q?.lastPrice ?? team.basePrice
    const intraday = (feed?.trades ?? []).map((t) => ({ ts: Date.parse(t.executedAt), price: t.price })).reverse()
    markets[team.id] = {
      last, high: q?.high ?? last, low: q?.low ?? last, volume: q?.volume ?? 0,
      daily: [], intraday: intraday.length ? intraday : [{ ts: Date.now(), price: last }],
    }
    books[team.id] = toBook(team.id, feed)
  }

  const users: Record<string, User> = {}
  users[ME] = {
    id: ME, name: account?.nickname ?? '나', points: account?.availablePoints ?? 0, initialValue: START_POINTS, isBot: false,
    holdings: Object.fromEntries((account?.holdings ?? []).map((h) => [h.teamId, { qty: h.available, avgPrice: h.averagePrice }])),
  }
  for (const r of rankings)
    if (!r.me) users[`rank:${r.nickname}`] = { id: `rank:${r.nickname}`, name: r.nickname, points: 0, holdings: {}, initialValue: START_POINTS, isBot: false }

  const alertList: Alert[] = alerts.map((a, i) => ({
    id: `${a.tradeId}-${i}`, kind: a.kind === 'PRICE_DEVIATION' ? 'price_deviation' : 'repeated_counterparty',
    teamId: a.teamId, userIds: [a.nicknameA, a.nicknameB], message: a.message, ts: Date.parse(a.detectedAt),
  }))

  return { users, books, markets, trades, alerts: alertList, posts, dividends: [], settled: [] }
}

function update() {
  state = build()
  emit()
}

export const getState = () => state

// ---------- 화면에서 부르는 동작 ----------

export function totalShares(userId: string, teamId: string) {
  if (userId !== ME) return 0
  const h = account?.holdings.find((x) => x.teamId === teamId)
  return h ? h.available + h.locked : 0
}

export const lockedPoints = (userId: string) => (userId === ME ? account?.lockedPoints ?? 0 : 0)

export function totalValue(userId: string) {
  if (userId === ME) return account?.totalValue ?? START_POINTS
  return rankings.find((r) => `rank:${r.nickname}` === userId)?.totalValue ?? 0
}

export const openOrders = (userId: string): Order[] => (userId === ME ? orders.map(toOrder) : [])

/**
 * 주문 번호(clientOrderId)를 먼저 정해 보낸다. 응답을 받지 못하면 같은 번호로 한 번 더 보내고,
 * 서버는 이미 받은 주문이면 처음 결과를 그대로 돌려준다.
 */
export async function placeOrder(_userId: string, teamId: string, side: Side, type: OrderType, price: number, qty: number): Promise<OrderResult> {
  const body = { clientOrderId: crypto.randomUUID(), teamId, side: side.toUpperCase(), type: type.toUpperCase(), price: type === 'limit' ? price : 0, quantity: qty }
  try {
    let receipt: ReceiptDto
    try {
      receipt = await api<ReceiptDto>('POST', '/api/orders', body)
    } catch (e) {
      if (e instanceof ApiError) throw e
      receipt = await api<ReceiptDto>('POST', '/api/orders', body)
    }
    await refresh()
    return { ok: true, message: receipt.message }
  } catch (e) {
    return { ok: false, message: e instanceof Error ? e.message : '주문하지 못했어요' }
  }
}

export async function cancel(_userId: string, _teamId: string, orderId: string) {
  await api('DELETE', `/api/orders/${orderId}`).catch(() => {})
  await refresh()
}

export function addPost(teamId: string, body: string) {
  // 토론 글은 아직 서버에 저장하지 않는다
  posts.unshift({ id: `local${Date.now()}`, teamId, userId: ME, author: account?.nickname ?? '나', body, ts: Date.now() })
  update()
}

export const settleMatches = () => '경기 배당은 아직 서버에 없어요'

export const getLogin = () => login

/** 개발·시연 서버에서만 열려 있는 닉네임 로그인 */
export async function devLogin(nickname: string): Promise<string | null> {
  try {
    await api('POST', '/api/auth/dev-login', { nickname })
    start()
    return null
  } catch (e) {
    return e instanceof Error ? e.message : '로그인하지 못했어요'
  }
}

export function reset() {
  api('POST', '/api/auth/logout').catch(() => {}).finally(start)
}

export function start() {
  clearInterval(poller)
  ensureLogin()
    .then(async (ok) => {
      if (!ok) return
      await refresh()
      poller = setInterval(() => refresh().catch(() => {}), POLL_MS)
    })
    .catch(() => {})
}
