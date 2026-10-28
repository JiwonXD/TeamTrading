import { describe, expect, it } from 'vitest'
import {
  type Book,
  type Order,
  cancelOrder,
  levels,
  priceBand,
  stepPrice,
  submitOrder,
  validateOrder,
} from './orderbook'
import { PAIR_THRESHOLD, detect } from './fds'

let seq = 0
const id = () => `id${++seq}`

function order(p: Partial<Order> & Pick<Order, 'userId' | 'side'>): Order {
  const qty = p.qty ?? 10
  return {
    id: id(),
    teamId: 't',
    type: 'limit',
    price: 10000,
    qty,
    remaining: qty,
    ts: 0,
    ...p,
  }
}

const empty = (): Book => ({ bids: [], asks: [] })

describe('matching', () => {
  it('rests a limit order that does not cross', () => {
    const b = empty()
    const r = submitOrder(b, order({ userId: 'a', side: 'buy', price: 9900 }), id, 1)
    expect(r.trades).toHaveLength(0)
    expect(b.bids).toHaveLength(1)
  })

  it('fills at the resting price with price-time priority', () => {
    const b = empty()
    submitOrder(b, order({ userId: 's1', side: 'sell', price: 10100, qty: 5 }), id, 1)
    submitOrder(b, order({ userId: 's2', side: 'sell', price: 10000, qty: 5 }), id, 2)
    submitOrder(b, order({ userId: 's3', side: 'sell', price: 10000, qty: 5 }), id, 3)
    const r = submitOrder(b, order({ userId: 'b', side: 'buy', price: 10100, qty: 12 }), id, 4)
    expect(r.trades.map((t) => [t.sellerId, t.price, t.qty])).toEqual([
      ['s2', 10000, 5],
      ['s3', 10000, 5],
      ['s1', 10100, 2],
    ])
    expect(b.asks[0].remaining).toBe(3)
    expect(r.resting).toBeNull()
  })

  it('cancels the unfilled part of a market order', () => {
    const b = empty()
    submitOrder(b, order({ userId: 's', side: 'sell', qty: 3 }), id, 1)
    const r = submitOrder(b, order({ userId: 'b', side: 'buy', type: 'market', price: 0 }), id, 2)
    expect(r.trades[0].qty).toBe(3)
    expect(r.canceledQty).toBe(7)
    expect(b.bids).toHaveLength(0)
  })

  it('prevents self trades by canceling the incoming order', () => {
    const b = empty()
    submitOrder(b, order({ userId: 'a', side: 'sell', qty: 5 }), id, 1)
    const r = submitOrder(b, order({ userId: 'a', side: 'buy', qty: 5 }), id, 2)
    expect(r.trades).toHaveLength(0)
    expect(r.selfTradePrevented).toBe(true)
    expect(r.canceledQty).toBe(5)
    expect(b.asks).toHaveLength(1)
    expect(b.bids).toHaveLength(0)
  })

  it('cancels a resting order and aggregates levels', () => {
    const b = empty()
    const o = order({ userId: 'a', side: 'buy', price: 9900 })
    submitOrder(b, o, id, 1)
    submitOrder(b, order({ userId: 'c', side: 'buy', price: 9900 }), id, 2)
    expect(levels(b.bids, 5)).toEqual([{ price: 9900, qty: 20 }])
    expect(cancelOrder(b, o.id)?.id).toBe(o.id)
    expect(levels(b.bids, 5)).toEqual([{ price: 9900, qty: 10 }])
  })
})

describe('validation', () => {
  it('enforces the ±30% price band and tick size', () => {
    expect(priceBand(10000)).toEqual({ upper: 13000, lower: 7000 })
    expect(validateOrder({ type: 'limit', price: 13010, qty: 1 }, 10000)).toMatch('상한가')
    expect(validateOrder({ type: 'limit', price: 6990, qty: 1 }, 10000)).toMatch('하한가')
    expect(validateOrder({ type: 'limit', price: 10005, qty: 1 }, 10000)).toMatch('호가 단위')
    expect(validateOrder({ type: 'limit', price: 10010, qty: 0 }, 10000)).toMatch('수량')
    expect(validateOrder({ type: 'limit', price: 10010, qty: 1 }, 10000)).toBeNull()
  })

  it('steps prices across tick boundaries', () => {
    expect(stepPrice(19990, 1)).toBe(20000)
    expect(stepPrice(20000, 1)).toBe(20050)
    expect(stepPrice(20000, -1)).toBe(19990)
  })
})

describe('fds', () => {
  const t = (buyerId: string, sellerId: string, price: number, ts: number) => ({
    id: id(), teamId: 't', price, qty: 1, buyerId, sellerId,
    buyOrderId: '', sellOrderId: '', aggressor: 'buy' as const, ts,
  })

  it('flags trades far from the last price', () => {
    expect(detect(t('a', 'b', 11500, 0), 10000, [], id)[0].kind).toBe('price_deviation')
    expect(detect(t('a', 'b', 10500, 0), 10000, [], id)).toHaveLength(0)
  })

  it('flags repeated trades between the same pair once', () => {
    const recent = Array.from({ length: PAIR_THRESHOLD - 1 }, (_, i) =>
      i % 2 ? t('b', 'a', 10000, i * 1000) : t('a', 'b', 10000, i * 1000),
    )
    const now = PAIR_THRESHOLD * 1000
    const alerts = detect(t('a', 'b', 10000, now), 10000, recent, id)
    expect(alerts.map((a) => a.kind)).toEqual(['repeated_counterparty'])
    const later = detect(t('a', 'b', 10000, now + 1), 10000, [...recent, t('a', 'b', 10000, now)], id)
    expect(later).toHaveLength(0)
  })
})
