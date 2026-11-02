// 서버에 붙는 저장소. 시세 목록·내 계좌·랭킹·감시 기록은 주기적으로 받아오고,
// 보고 있는 구단의 호가와 체결은 WebSocket 실시간 시세로 받는다.
// 로그인은 세션 쿠키로 유지한다. 상태를 바꾸는 요청에는 서버가 준 XSRF-TOKEN 쿠키 값을 헤더로 돌려보낸다.
// 화면에서 내 계정은 ME로만 보인다.
import { type League, TEAMS } from './data/teams'
import type { Book, Order, OrderType, Side, Trade } from './engine/orderbook'
import type { Alert } from './engine/fds'
import { type FeedMessage, type TeamFeed, applyFeed } from './api/feed'
import { emit } from './storeCore'
import { ME, START_POINTS, type Market, type OrderResult, type Post, type State, type User } from './storeTypes'

const POLL_MS = 3000

interface QuoteDto { id: string; name: string; shortName: string; league: string; color: string; listed: boolean; basePrice: number; lastPrice: number; indicativePrice: number | null; indicativeVolume: number; viUntil: string | null; high: number; low: number; volume: number }
interface HoldingDto { teamId: string; available: number; locked: number; averagePrice: number }
interface AccountDto { id: string; nickname: string; availablePoints: number; lockedPoints: number; totalValue: number; holdings: HoldingDto[]; nicknameChangeableAt: string | null }
interface OrderDto { id: number; teamId: string; side: 'BUY' | 'SELL'; type: 'LIMIT' | 'MARKET'; price: number; quantity: number; remaining: number; createdAt: string }
interface MyTradeDto { id: number; teamId: string; side: 'BUY' | 'SELL'; price: number; quantity: number; executedAt: string }
interface RankingDto { rank: number; nickname: string; totalValue: number; returnRate: number; me: boolean }
interface AlertDto { kind: string; teamId: string; tradeId: number; nicknameA: string; nicknameB: string; message: string; detectedAt: string }
interface ReceiptDto { message: string }
/** 경기 배당이면 matchId와 WIN·DRAW, 대회 성적 수당이면 awardId와 성적 */
interface MyDividendDto { matchId: number | null; awardId: number | null; teamId: string; kind: string; shares: number; amount: number; paidAt: string }
const AWARD_LABELS: Record<string, string> = {
  CHAMPION: '우승 수당', RUNNER_UP: '준우승 수당', SEMIFINAL: '4강 수당', POSTSEASON: '포스트시즌 수당', REGULAR_FIRST: '정규 시즌 1위 수당',
}
export interface MatchDto {
  id: number; league: 'KBO' | 'K_LEAGUE' | 'LCK'; homeTeamId: string; awayTeamId: string; scheduledAt: string
  status: 'SCHEDULED' | 'FINAL' | 'CANCELED'; homeScore: number | null; awayScore: number | null
  dividendStatus: 'NONE' | 'PAID' | 'VOID' | 'REVIEW'
}
export interface OfferingDto {
  id: number; teamId: string; teamName: string; round: number; firstListing: boolean; price: number; totalShares: number
  maxPerAccount: number; subscribeFrom: string; subscribeTo: string; listingAt: string
  status: 'SUBSCRIBING' | 'ALLOCATED' | 'CANCELED'; allocatedShares: number; subscribers: number; requested: number
}
export interface SubscriptionDto { offeringId: number; quantity: number; deposit: number; allocated: number | null; createdAt: string }
interface PostDto { id: number; teamId: string; nickname: string; body: string; holder: boolean; createdAt: string; mine: boolean; reward: Post['reward'] | null }
export type ReportReason = 'SPAM' | 'ABUSE' | 'AD' | 'OTHER'
export type LeaderboardPeriod = 'TODAY' | 'WEEK' | 'MONTH' | 'SEASON'
export type LeaderboardSort = 'RETURN' | 'PNL'
export interface LeaderboardDto {
  period: LeaderboardPeriod; from: string; to: string
  entries: { rank: number; nickname: string; returnRate: number; pnl: number; trades: number; me: boolean }[]
  mine: { rank: number; returnRate: number; pnl: number; trades: number; reason: string | null } | null
}
/** @property demo 시연 서버의 가짜 광고를 쓸 수 있는지. 실제 광고는 앱의 광고 SDK로만 본다 */
export interface AdStatusDto { amount: number; dailyLimit: number; paidToday: number; demo: boolean }
interface AdSessionDto { id: string; status: 'PENDING' | 'PAID' | 'LIMIT' }
export interface LoginOptions { providers: { id: string; name: string; loginUrl: string }[]; devLogin: boolean }

let login: LoginOptions | null = null
let poller: ReturnType<typeof setInterval> | undefined
let retry: ReturnType<typeof setTimeout> | undefined
/**
 * 서버와의 연결 상태. 화면은 서버에서 받은 값만 보여주고, 받지 못했으면 그렇다고 알린다.
 * connecting: 아직 받은 적 없음, unreachable: 받은 적 없고 마지막 시도 실패,
 * live: 마지막 갱신 성공, stale: 받은 적은 있지만 마지막 갱신 실패(화면 값은 lastSync 시점)
 */
export interface Connection { status: 'connecting' | 'unreachable' | 'live' | 'stale'; lastSync: number | null }
let connection: Connection = { status: 'connecting', lastSync: null }
let account: AccountDto | null = null
let quotes: Record<string, QuoteDto> = {}
let orders: OrderDto[] = []
let myTrades: MyTradeDto[] = []
let rankings: RankingDto[] = []
let alerts: AlertDto[] = []
let matches: MatchDto[] = []
let myDividends: MyDividendDto[] = []
let offerings: OfferingDto[] = []
let mySubscriptions: SubscriptionDto[] = []
let adStatus: AdStatusDto | null = null
const feeds: Record<string, TeamFeed> = {}
/** 보고 있는 구단의 토론 글. 구독 중인 구단만 주기적으로 받아온다 */
const postsByTeam: Record<string, Post[]> = {}
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

const LEAGUES: Record<string, League> = { KBO: 'KBO', K_LEAGUE: 'K리그', LCK: 'LCK' }

/** 구단 목록은 서버가 정한다(운영자가 리그 구단을 등록할 수 있다). 목록이 바뀌었을 때만 바꿔 끼운다 */
function syncTeams(list: QuoteDto[]) {
  const same = list.length === TEAMS.length && list.every((q, i) => q.id === TEAMS[i].id && q.name === TEAMS[i].name)
  if (same) return
  const next = list.filter((q) => LEAGUES[q.league]).map((q) => ({
    id: q.id, name: q.name, short: q.shortName, league: LEAGUES[q.league], color: q.color, basePrice: q.basePrice,
  }))
  TEAMS.splice(0, TEAMS.length, ...next)
}

async function refresh() {
  const [q, a, o, t, r, al, m, d, of, sub, ad] = await Promise.all([
    api<QuoteDto[]>('GET', '/api/teams'),
    api<AccountDto>('GET', '/api/accounts/me'),
    api<OrderDto[]>('GET', '/api/accounts/me/orders'),
    api<MyTradeDto[]>('GET', '/api/accounts/me/trades?limit=50'),
    api<RankingDto[]>('GET', '/api/rankings'),
    api<AlertDto[]>('GET', '/api/surveillance/alerts?limit=50'),
    api<MatchDto[]>('GET', '/api/matches?limit=9'),
    api<MyDividendDto[]>('GET', '/api/accounts/me/dividends?limit=30'),
    api<OfferingDto[]>('GET', '/api/offerings?limit=10'),
    api<SubscriptionDto[]>('GET', '/api/accounts/me/subscriptions?limit=20'),
    api<AdStatusDto>('GET', '/api/accounts/me/ads'),
  ])
  await Promise.all(Object.keys(sockets).map(loadPosts))
  syncTeams(q)
  quotes = Object.fromEntries(q.map((x) => [x.id, x]))
  account = a
  orders = o
  myTrades = t
  rankings = r
  alerts = al
  matches = m
  myDividends = d
  offerings = of
  mySubscriptions = sub
  adStatus = ad
  connection = { status: 'live', lastSync: Date.now() }
  update()
}

function markFailed() {
  connection = { status: connection.lastSync ? 'stale' : 'unreachable', lastSync: connection.lastSync }
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
  loadPosts(teamId).then(update, () => {})
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
      base: q?.basePrice ?? team.basePrice, listed: quotes[team.id]?.listed ?? true,
      indicative: q?.indicativePrice != null ? { price: q.indicativePrice, volume: q.indicativeVolume ?? 0 } : undefined,
      viUntil: q?.viUntil ? Date.parse(q.viUntil) : undefined, last, high: q?.high ?? last, low: q?.low ?? last, volume: q?.volume ?? 0,
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

  const dividends = myDividends.map((d) => ({
    matchId: d.awardId != null ? `award-${d.awardId}` : String(d.matchId), teamId: d.teamId, qty: d.shares, amount: d.amount,
    ts: Date.parse(d.paidAt), kind: d.kind === 'DRAW' ? ('draw' as const) : ('win' as const), label: AWARD_LABELS[d.kind],
  }))
  const posts = Object.values(postsByTeam).flat()
  return { users, books, markets, trades, alerts: alertList, posts, dividends, settled: [] }
}

function update() {
  state = build()
  emit()
}

export const getState = () => state

/** 기간별 리더보드. 마감 집계가 들어 있어 3초 주기 갱신에 넣지 않고 화면이 필요할 때 받는다 */
export const fetchLeaderboard = (period: LeaderboardPeriod, sort: LeaderboardSort) =>
  api<LeaderboardDto>('GET', `/api/leaderboard?period=${period}&sort=${sort}&limit=50`)

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

async function loadPosts(teamId: string) {
  const list = await api<PostDto[]>('GET', `/api/teams/${teamId}/posts?limit=30`)
  postsByTeam[teamId] = list.map((p) => ({
    id: String(p.id), teamId: p.teamId, userId: p.mine ? ME : `n:${p.nickname}`, author: p.nickname, body: p.body,
    ts: Date.parse(p.createdAt), holder: p.holder, reward: p.reward ?? undefined,
  }))
}

async function postAction(work: () => Promise<unknown>, teamId: string, done: string): Promise<OrderResult> {
  try {
    await work()
    await loadPosts(teamId)
    update()
    return { ok: true, message: done }
  } catch (e) {
    return { ok: false, message: e instanceof Error ? e.message : '처리하지 못했어요' }
  }
}

export const addPost = (teamId: string, body: string) =>
  postAction(() => api('POST', `/api/teams/${teamId}/posts`, { body }), teamId, '글을 올렸어요')

export const deletePost = (teamId: string, postId: string) =>
  postAction(() => api('DELETE', `/api/posts/${postId}`), teamId, '글을 지웠어요')

export const reportPost = (teamId: string, postId: string, reason: ReportReason) =>
  postAction(() => api('POST', `/api/posts/${postId}/reports`, { reason }), teamId, '신고했어요. 확인 후 처리할게요')

export const settleMatches = () => '경기 배당은 아직 서버에 없어요'

export const getLogin = () => login
export const getConnection = () => connection
export const getMatches = () => matches
export const getOfferings = () => offerings
export const getMySubscriptions = () => mySubscriptions

/** 공모 청약. 같은 수량으로 다시 보내면 서버가 처음 청약을 돌려준다 */
export async function subscribeOffering(offeringId: number, quantity: number): Promise<OrderResult> {
  try {
    await api('POST', `/api/offerings/${offeringId}/subscriptions`, { quantity })
    await refresh()
    return { ok: true, message: `${quantity}주 청약했어요` }
  } catch (e) {
    return { ok: false, message: e instanceof Error ? e.message : '청약하지 못했어요' }
  }
}

export async function cancelSubscription(offeringId: number): Promise<OrderResult> {
  try {
    await api('DELETE', `/api/offerings/${offeringId}/subscriptions/me`)
    await refresh()
    return { ok: true, message: '청약을 취소했어요' }
  } catch (e) {
    return { ok: false, message: e instanceof Error ? e.message : '취소하지 못했어요' }
  }
}

export const getAdStatus = () => adStatus

/** 광고 세션을 연다. 포인트는 광고 네트워크의 서버 콜백(시연 서버는 시청 완료)으로만 들어온다 */
export async function openAd(): Promise<{ id: string } | { error: string }> {
  try {
    return { id: (await api<AdSessionDto>('POST', '/api/accounts/me/ads')).id }
  } catch (e) {
    return { error: e instanceof Error ? e.message : '광고를 열지 못했어요' }
  }
}

/** 시연용 가짜 광고를 끝까지 봤다 */
export async function finishDemoAd(id: string): Promise<OrderResult> {
  try {
    const s = await api<AdSessionDto>('POST', `/api/accounts/me/ads/${id}/demo-complete`)
    await refresh()
    return s.status === 'PAID'
      ? { ok: true, message: `광고 보상 ${(adStatus?.amount ?? 0).toLocaleString('ko-KR')}P를 받았어요` }
      : { ok: false, message: '오늘 받을 수 있는 광고 보상을 모두 받았어요' }
  } catch (e) {
    return { ok: false, message: e instanceof Error ? e.message : '보상을 받지 못했어요' }
  }
}

/** 내 닉네임과, 바꾼 지 7일이 안 됐으면 다시 바꿀 수 있는 시각 */
export const getProfile = () =>
  account ? { nickname: account.nickname, changeableAt: account.nicknameChangeableAt ? Date.parse(account.nicknameChangeableAt) : null } : null

export async function changeNickname(nickname: string): Promise<OrderResult> {
  try {
    account = await api<AccountDto>('PUT', '/api/accounts/me/nickname', { nickname })
    await refresh()
    return { ok: true, message: `닉네임을 ${account.nickname}(으)로 바꿨어요` }
  } catch (e) {
    return { ok: false, message: e instanceof Error ? e.message : '닉네임을 바꾸지 못했어요' }
  }
}

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

/** 로그인을 확인하고 주기 갱신을 건다. 서버에 닿지 않으면 닿을 때까지 다시 시도한다 */
export function start() {
  clearInterval(poller)
  clearTimeout(retry)
  ensureLogin()
    .then(async (ok) => {
      if (!ok) return
      await refresh()
      poller = setInterval(() => refresh().catch(onPollError), POLL_MS)
    })
    .catch(() => {
      markFailed()
      retry = setTimeout(start, POLL_MS)
    })
}

function onPollError(e: unknown) {
  markFailed()
  // 서버가 다시 떠서 세션이 사라졌으면 로그인부터 다시 한다
  if (e instanceof ApiError && e.status === 401) start()
}
