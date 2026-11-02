import { describe, expect, it } from 'vitest'
import { type FeedMessage, applyFeed } from './feed'

const msg = (type: FeedMessage['type'], seq: number, tradeIds: number[] = [], epoch = 1): FeedMessage => ({
  type, teamId: 'lg', epoch, seq,
  quote: { basePrice: 18450, lastPrice: 18450, high: 18450, low: 18450, volume: 0 },
  asks: [{ price: 18500, quantity: seq }], bids: [],
  trades: tradeIds.map((id) => ({ id, price: 18450, quantity: 1, aggressor: 'BUY', executedAt: '2026-11-02T00:00:00Z' })),
})

describe('applyFeed', () => {
  it('starts from a snapshot and appends consecutive updates', () => {
    let s = applyFeed(null, msg('snapshot', 5, [2, 1]))
    s = applyFeed(s, msg('update', 6, [3]))
    s = applyFeed(s, msg('update', 7, [5, 4]))
    expect(s?.seq).toBe(7)
    expect(s?.asks[0].quantity).toBe(7)
    expect(s?.trades.map((t) => t.id)).toEqual([5, 4, 3, 2, 1])
  })

  it('asks to reconnect when an update is skipped', () => {
    const s = applyFeed(null, msg('snapshot', 5))
    expect(applyFeed(s, msg('update', 7))).toBeNull()
  })

  it('asks to reconnect when the server restarted', () => {
    const s = applyFeed(null, msg('snapshot', 5, [], 1))
    expect(applyFeed(s, msg('update', 6, [], 2))).toBeNull()
  })

  it('ignores updates before the first snapshot', () => {
    expect(applyFeed(null, msg('update', 1))).toBeNull()
  })
})
