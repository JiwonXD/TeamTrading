import { useCallback, useEffect, useRef, useState } from 'react'
import { fetchCandles, getState } from '../store'
import { subscribe } from '../storeCore'
import { type Candle, type CandleUnit, addTrade } from './candles'

interface Loaded { key: string; candles: Candle[]; lastTradeId: number; more: boolean }

/** 구단·단위별 봉. 처음 받은 뒤에는 실시간 체결로 마지막 봉을 갱신하고, loadMore로 이전 봉을 이어 받는다 */
export function useCandles(teamId: string, unit: CandleUnit) {
  const key = `${teamId}:${unit}`
  const [data, setData] = useState<Loaded | null>(null)
  const loadingMore = useRef(false)

  useEffect(() => {
    let alive = true
    fetchCandles(teamId, unit).then((p) => alive && setData({ key, ...p }), () => alive && setData({ key, candles: [], lastTradeId: 0, more: false }))
    return () => { alive = false }
  }, [key, teamId, unit])

  // 받은 봉에 들어가지 않은 체결(번호가 더 큰 체결)만 실시간으로 더한다
  useEffect(() => subscribe(() => setData((d) => {
    if (!d || d.key !== key) return d
    const fresh = getState().trades.filter((t) => t.teamId === teamId && Number(t.id) > d.lastTradeId).sort((a, b) => Number(a.id) - Number(b.id))
    if (!fresh.length) return d
    let candles = d.candles
    for (const t of fresh) candles = addTrade(candles, unit, t.ts, t.price, t.qty)
    return { ...d, candles, lastTradeId: Number(fresh[fresh.length - 1].id) }
  })), [key, teamId, unit])

  const loadMore = useCallback(() => {
    if (!data || data.key !== key || !data.more || !data.candles.length || loadingMore.current) return
    loadingMore.current = true
    fetchCandles(teamId, unit, data.candles[0].time)
      .then((p) => setData((d) => d && d.key === key ? { ...d, candles: [...p.candles, ...d.candles], more: p.more } : d))
      .catch(() => {})
      .finally(() => { loadingMore.current = false })
  }, [data, key, teamId, unit])

  const ready = data?.key === key
  return { candles: ready ? data.candles : null, more: ready && data.more, loadMore }
}
