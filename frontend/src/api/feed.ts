// 실시간 시세 메시지 처리. 서버는 구단마다 1씩 늘어나는 seq를 붙여 보낸다.
// 스냅숏 이후 seq가 건너뛰거나 epoch(서버 기동 번호)이 바뀌면 이어 붙이지 않고 다시 접속해 스냅숏부터 받는다.

export interface Level {
  price: number
  quantity: number
}

export interface FeedTrade {
  id: number
  price: number
  quantity: number
  aggressor: 'BUY' | 'SELL'
  executedAt: string
}

export interface FeedQuote {
  basePrice: number
  indicativePrice?: number | null
  indicativeVolume?: number
  lastPrice: number
  high: number
  low: number
  volume: number
}

export interface FeedMessage {
  type: 'snapshot' | 'update'
  teamId: string
  epoch: number
  seq: number
  quote: FeedQuote
  asks: Level[]
  bids: Level[]
  trades: FeedTrade[] // 최신이 앞
}

export interface TeamFeed {
  epoch: number
  seq: number
  quote: FeedQuote
  asks: Level[]
  bids: Level[]
  trades: FeedTrade[] // 최신이 앞
}

export const TRADES_KEPT = 200

/** 다음 상태를 돌려준다. null이면 이어 붙일 수 없으니 다시 접속해야 한다 */
export function applyFeed(current: TeamFeed | null, msg: FeedMessage): TeamFeed | null {
  if (msg.type === 'snapshot') return { ...pick(msg), trades: msg.trades.slice(0, TRADES_KEPT) }
  if (!current || msg.epoch !== current.epoch || msg.seq !== current.seq + 1) return null
  return { ...pick(msg), trades: [...msg.trades, ...current.trades].slice(0, TRADES_KEPT) }
}

function pick(msg: FeedMessage) {
  return { epoch: msg.epoch, seq: msg.seq, quote: msg.quote, asks: msg.asks, bids: msg.bids }
}
