// 앱 상태와 계좌 처리. 첫 버전은 서버 없이 브라우저 안에서 돌아가며,
// 다른 유저들은 시뮬레이션 계정(bot)이 실제 유저처럼 호가를 내는 방식으로 흉내 낸다.
// 시스템이 호가를 받아주는 마켓메이커는 없다. 모든 체결은 계정 대 계정이다.
import { useSyncExternalStore } from 'react'
import {
  type Book,
  type Order,
  type OrderType,
  type Side,
  type Trade,
  cancelOrder as bookCancel,
  priceBand,
  stepPrice,
  submitOrder,
  tickSize,
  validateOrder,
} from './engine/orderbook'
import { type Alert, detect } from './engine/fds'
import { DIVIDEND_RATE, SEED_POSTS, TEAMS, TODAY_MATCHES, teamById } from './data/teams'

export const ME = 'me'
export const START_POINTS = 1_000_000

export interface Holding {
  qty: number // 매도 주문에 묶이지 않은 수량
  avgPrice: number
}

export interface User {
  id: string
  name: string
  points: number // 매수 주문에 묶이지 않은 포인트
  holdings: Record<string, Holding>
  initialValue: number
  isBot: boolean
}

export interface Market {
  last: number
  high: number
  low: number
  volume: number
  daily: number[]
  intraday: { ts: number; price: number }[]
}

export interface Post {
  id: string
  teamId: string
  userId: string
  author: string
  body: string
  ts: number
}

export interface Dividend {
  matchId: string
  teamId: string
  qty: number
  amount: number
  ts: number
}

export interface State {
  seq: number
  users: Record<string, User>
  books: Record<string, Book>
  markets: Record<string, Market>
  trades: Trade[] // 최근 체결(전체), 최신이 앞
  alerts: Alert[]
  posts: Post[]
  dividends: Dividend[] // 내 배당 내역
  settled: string[]
  bias: Record<string, number>
}

const STORAGE_KEY = 'teamtrading:v1'
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

let state: State
let version = 0
const listeners = new Set<() => void>()
let toast: { msg: string; id: number } | null = null

function emit() {
  version++
  listeners.forEach((l) => l())
}

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
): { ok: boolean; message: string } {
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

function freshState(): State {
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
    const daily: number[] = [t.basePrice]
    for (let d = 1; d < 90; d++) {
      const prev = daily[0]
      daily.unshift(Math.round(prev * (1 + (r() - 0.5) * 0.05)))
    }
    markets[t.id] = {
      last: t.basePrice, high: t.basePrice, low: t.basePrice, volume: 0, daily,
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

function load(): State | null {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    return raw ? (JSON.parse(raw) as State) : null
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

state = load() ?? freshState()
if (state.seq === 0) seedBooks()
setInterval(botTick, 1500)
setInterval(save, 3000)

// ---------- React 연결 ----------

export function useStore(): State {
  useSyncExternalStore(
    (l) => {
      listeners.add(l)
      return () => listeners.delete(l)
    },
    () => version,
  )
  return state
}

export function showToast(msg: string) {
  toast = { msg, id: Date.now() }
  emit()
  setTimeout(() => {
    if (toast && Date.now() - toast.id >= 2400) {
      toast = null
      emit()
    }
  }, 2500)
}

export const getToast = () => toast
