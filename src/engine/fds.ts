// 이상거래 탐지(FDS). 포인트를 특정 계정에 몰아주는 우회 이전 패턴을 잡는 것이 목적이다.
import type { Trade } from './orderbook'

export type AlertKind = 'price_deviation' | 'repeated_counterparty'

export interface Alert {
  id: string
  kind: AlertKind
  teamId: string
  userIds: [string, string]
  message: string
  ts: number
}

export const DEVIATION_RATE = 0.1 // 직전 체결가 대비 10% 이상 벗어난 체결
export const PAIR_WINDOW_MS = 10 * 60 * 1000
export const PAIR_THRESHOLD = 5 // 같은 종목에서 같은 두 계정이 창 안에서 5번 이상 체결

export function detect(
  trade: Trade,
  prevPrice: number,
  recent: Trade[], // trade 이전의 체결 내역
  nextId: () => string,
): Alert[] {
  const alerts: Alert[] = []
  const pair: [string, string] = [trade.buyerId, trade.sellerId]

  const deviation = Math.abs(trade.price - prevPrice) / prevPrice
  if (deviation >= DEVIATION_RATE) {
    alerts.push({
      id: nextId(),
      kind: 'price_deviation',
      teamId: trade.teamId,
      userIds: pair,
      message: `직전 체결가 대비 ${(deviation * 100).toFixed(1)}% 벗어난 가격에 체결`,
      ts: trade.ts,
    })
  }

  const samePair = recent.filter(
    (t) =>
      t.teamId === trade.teamId &&
      trade.ts - t.ts <= PAIR_WINDOW_MS &&
      ((t.buyerId === trade.buyerId && t.sellerId === trade.sellerId) ||
        (t.buyerId === trade.sellerId && t.sellerId === trade.buyerId)),
  ).length
  if (samePair + 1 === PAIR_THRESHOLD) {
    alerts.push({
      id: nextId(),
      kind: 'repeated_counterparty',
      teamId: trade.teamId,
      userIds: pair,
      message: `10분 안에 같은 상대와 ${PAIR_THRESHOLD}회 이상 체결`,
      ts: trade.ts,
    })
  }
  return alerts
}
