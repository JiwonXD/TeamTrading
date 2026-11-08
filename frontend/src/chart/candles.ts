// 차트 봉 계산. 서버(CandleUnit.java)와 같은 경계를 쓴다: 한국 시간 기준, 분 단위는 자정부터 N분씩,
// 주는 월요일, 월은 1일, 년은 1월 1일에 시작한다.

export type CandleUnit = '1m' | '3m' | '5m' | '10m' | '15m' | '30m' | '60m' | '120m' | '240m' | 'day' | 'week' | 'month' | 'year'

export const MINUTE_UNITS: CandleUnit[] = ['1m', '3m', '5m', '10m', '15m', '30m', '60m', '120m', '240m']
export const PERIOD_UNITS: [CandleUnit, string][] = [['day', '일'], ['week', '주'], ['month', '월'], ['year', '년']]
export const isMinute = (u: CandleUnit) => u.endsWith('m') && u !== 'month'
export const unitLabel = (u: CandleUnit) => (isMinute(u) ? `${parseInt(u)}분` : PERIOD_UNITS.find(([v]) => v === u)![1])

export interface Candle { time: number; open: number; high: number; low: number; close: number; volume: number }
/** lastTradeId: 이 봉들에 들어간 가장 큰 체결 번호. more: 더 이전 봉이 있는지 */
export interface CandlePage { candles: Candle[]; lastTradeId: number; more: boolean }

const KST = 9 * 3_600_000
const DAY = 86_400_000

/** ts가 속한 봉의 시작 시각(ms) */
export function bucketStart(ts: number, unit: CandleUnit): number {
  const k = new Date(ts + KST) // UTC 필드로 한국 시간을 읽는다
  const y = k.getUTCFullYear()
  const m = k.getUTCMonth()
  const dayStart = Date.UTC(y, m, k.getUTCDate()) - KST
  switch (unit) {
    case 'day': return dayStart
    case 'week': return dayStart - ((k.getUTCDay() + 6) % 7) * DAY
    case 'month': return Date.UTC(y, m, 1) - KST
    case 'year': return Date.UTC(y, 0, 1) - KST
    default: {
      const n = parseInt(unit) * 60_000
      return dayStart + Math.floor((ts - dayStart) / n) * n
    }
  }
}

/** 새 체결을 마지막 봉에 더하거나 새 봉을 연다. 마지막 봉보다 이른 체결은 무시한다 */
export function addTrade(candles: Candle[], unit: CandleUnit, ts: number, price: number, qty: number): Candle[] {
  const time = bucketStart(ts, unit)
  const last = candles[candles.length - 1]
  if (last && time < last.time) return candles
  if (last && time === last.time)
    return [...candles.slice(0, -1), { ...last, high: Math.max(last.high, price), low: Math.min(last.low, price), close: price, volume: last.volume + qty }]
  return [...candles, { time, open: price, high: price, low: price, close: price, volume: qty }]
}

const MINUTE_KEY = 'fanchart:chart-minute'

/** 마지막으로 고른 분 단위. 처음엔 5분 */
export function savedMinuteUnit(): CandleUnit {
  try {
    const v = localStorage.getItem(MINUTE_KEY) as CandleUnit | null
    if (v && MINUTE_UNITS.includes(v)) return v
  } catch { /* 저장소를 못 쓰면 기본값 */ }
  return '5m'
}

export function saveMinuteUnit(unit: CandleUnit) {
  try { localStorage.setItem(MINUTE_KEY, unit) } catch { /* 이번 방문 동안만 기억한다 */ }
}
