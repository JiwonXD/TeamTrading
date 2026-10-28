// 가격-시간 우선 원칙의 연속 매매 매칭 엔진.
// 계좌(포인트/보유수량) 검증은 이 모듈 밖(store)에서 하고, 여기서는 호가와 체결만 다룬다.
// 서버로 옮길 때 그대로 포팅할 수 있도록 순수 함수로 유지한다.

export type Side = 'buy' | 'sell'
export type OrderType = 'limit' | 'market'

export interface Order {
  id: string
  userId: string
  teamId: string
  side: Side
  type: OrderType
  price: number // 시장가 주문은 0
  qty: number
  remaining: number
  ts: number
}

export interface Trade {
  id: string
  teamId: string
  price: number
  qty: number
  buyerId: string
  sellerId: string
  buyOrderId: string
  sellOrderId: string
  aggressor: Side
  ts: number
}

export interface Book {
  bids: Order[] // 가격 내림차순, 같은 가격은 먼저 들어온 순
  asks: Order[] // 가격 오름차순, 같은 가격은 먼저 들어온 순
}

export interface MatchResult {
  trades: Trade[]
  resting: Order | null // 체결 후 호가창에 남은 지정가 주문
  canceledQty: number // 시장가 잔량 또는 자기매매 방지로 취소된 수량
  selfTradePrevented: boolean
}

export const PRICE_LIMIT_RATE = 0.3

// KRX 호가가격단위를 단순화한 버전
export function tickSize(price: number): number {
  if (price < 2000) return 1
  if (price < 5000) return 5
  if (price < 20000) return 10
  if (price < 50000) return 50
  return 100
}

export function priceBand(basePrice: number): { upper: number; lower: number } {
  const rawUpper = basePrice * (1 + PRICE_LIMIT_RATE)
  const rawLower = basePrice * (1 - PRICE_LIMIT_RATE)
  const upper = Math.floor(rawUpper / tickSize(rawUpper)) * tickSize(rawUpper)
  const lower = Math.ceil(rawLower / tickSize(rawLower)) * tickSize(rawLower)
  return { upper, lower }
}

export function stepPrice(price: number, steps: number): number {
  let p = price
  const dir = Math.sign(steps)
  for (let i = 0; i < Math.abs(steps); i++) {
    // 내려갈 때는 한 단계 아래 가격대의 호가단위를 써야 경계에서 단위가 맞는다
    p += dir * (dir > 0 ? tickSize(p) : tickSize(p - 1))
  }
  return p
}

export function validateOrder(
  o: Pick<Order, 'type' | 'price' | 'qty'>,
  basePrice: number,
): string | null {
  if (!Number.isInteger(o.qty) || o.qty <= 0) return '수량은 1주 이상의 정수여야 해요'
  if (o.type === 'market') return null
  const { upper, lower } = priceBand(basePrice)
  if (o.price > upper) return `상한가(${upper.toLocaleString()})보다 높게 주문할 수 없어요`
  if (o.price < lower) return `하한가(${lower.toLocaleString()})보다 낮게 주문할 수 없어요`
  if (o.price % tickSize(o.price) !== 0)
    return `호가 단위(${tickSize(o.price)})에 맞지 않는 가격이에요`
  return null
}

function crosses(incoming: Order, resting: Order): boolean {
  if (incoming.type === 'market') return true
  return incoming.side === 'buy' ? resting.price <= incoming.price : resting.price >= incoming.price
}

function insert(book: Book, order: Order) {
  const list = order.side === 'buy' ? book.bids : book.asks
  const worse =
    order.side === 'buy'
      ? (o: Order) => o.price < order.price
      : (o: Order) => o.price > order.price
  const idx = list.findIndex(worse)
  if (idx === -1) list.push(order)
  else list.splice(idx, 0, order)
}

export function submitOrder(
  book: Book,
  order: Order,
  nextId: () => string,
  now: number,
): MatchResult {
  const opposite = order.side === 'buy' ? book.asks : book.bids
  const trades: Trade[] = []
  let selfTradePrevented = false

  while (order.remaining > 0 && opposite.length > 0) {
    const best = opposite[0]
    if (!crosses(order, best)) break
    // 자기매매 방지(Cancel Newest): 내 주문끼리 체결될 상황이면 새 주문의 잔량을 취소한다
    if (best.userId === order.userId) {
      selfTradePrevented = true
      break
    }
    const qty = Math.min(order.remaining, best.remaining)
    order.remaining -= qty
    best.remaining -= qty
    const buy = order.side === 'buy' ? order : best
    const sell = order.side === 'sell' ? order : best
    trades.push({
      id: nextId(),
      teamId: order.teamId,
      price: best.price,
      qty,
      buyerId: buy.userId,
      sellerId: sell.userId,
      buyOrderId: buy.id,
      sellOrderId: sell.id,
      aggressor: order.side,
      ts: now,
    })
    if (best.remaining === 0) opposite.shift()
  }

  let resting: Order | null = null
  let canceledQty = 0
  if (order.remaining > 0) {
    if (order.type === 'limit' && !selfTradePrevented) {
      insert(book, order)
      resting = order
    } else {
      canceledQty = order.remaining
      order.remaining = 0
    }
  }
  return { trades, resting, canceledQty, selfTradePrevented }
}

export function cancelOrder(book: Book, orderId: string): Order | null {
  for (const list of [book.bids, book.asks]) {
    const idx = list.findIndex((o) => o.id === orderId)
    if (idx !== -1) return list.splice(idx, 1)[0]
  }
  return null
}

export interface Level {
  price: number
  qty: number
}

// 같은 가격의 주문을 합쳐 호가창 표시용 단계로 만든다
export function levels(orders: Order[], depth: number): Level[] {
  const out: Level[] = []
  for (const o of orders) {
    const last = out[out.length - 1]
    if (last && last.price === o.price) last.qty += o.remaining
    else {
      if (out.length === depth) break
      out.push({ price: o.price, qty: o.remaining })
    }
  }
  return out
}
