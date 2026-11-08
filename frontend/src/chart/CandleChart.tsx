import { useEffect, useRef, useState } from 'react'
import type { Side } from '../engine/orderbook'
import { fmt } from '../components/ui'
import { type Candle, type CandleUnit, MINUTE_UNITS, PERIOD_UNITS, isMinute, saveMinuteUnit, savedMinuteUnit, unitLabel } from './candles'

const PAD = 0.08 // 가격 영역 위아래 여백
const VOL = 0.18 // 아래 거래량 영역 높이 비율
const GAP = 0.05 // 가격 영역과 거래량 영역 사이

const p2 = (n: number) => String(n).padStart(2, '0')
function kst(ts: number) {
  const d = new Date(ts + 9 * 3_600_000)
  return { y: d.getUTCFullYear(), mo: d.getUTCMonth() + 1, d: d.getUTCDate(), h: d.getUTCHours(), mi: d.getUTCMinutes() }
}
function axisTime(ts: number, unit: CandleUnit) {
  const t = kst(ts)
  if (isMinute(unit)) return `${p2(t.h)}:${p2(t.mi)}`
  if (unit === 'day' || unit === 'week') return `${t.mo}/${t.d}`
  return unit === 'month' ? `${t.y}.${t.mo}` : `${t.y}`
}
function tipTime(ts: number, unit: CandleUnit) {
  const t = kst(ts)
  if (isMinute(unit)) return `${t.mo}/${t.d} ${p2(t.h)}:${p2(t.mi)}`
  if (unit === 'day') return `${t.y}.${t.mo}.${t.d}`
  if (unit === 'week') return `${t.y}.${t.mo}.${t.d} 주`
  return unit === 'month' ? `${t.y}.${t.mo}` : `${t.y}년`
}

/**
 * 캔들 차트. 위는 봉, 아래는 거래량 막대다. 좌우로 끌면 지난 봉을 보고, 왼쪽 끝에 닿으면 이전 봉을 더 불러온다.
 * 마우스를 올리거나 터치하면 그 봉의 시가·고가·저가·종가·거래량을 보여주고,
 * onPick이 있으면 오른쪽 클릭으로 그 가격 주문 메뉴를 연다.
 * 단위가 바뀌면 처음 위치로 돌아가도록 부모가 key를 바꿔 준다.
 */
export function CandleChart({ candles, unit, height, step = 8, more, onLoadMore, snap, onPick }: {
  /** null이면 불러오는 중 */
  candles: Candle[] | null
  unit: CandleUnit
  height: number
  /** 봉 하나가 차지하는 너비(px) */
  step?: number
  more?: boolean
  onLoadMore?: () => void
  snap?: (price: number) => number
  onPick?: (price: number, side: Side) => void
}) {
  const box = useRef<HTMLDivElement>(null)
  const [width, setWidth] = useState(0)
  const [fromRight, setFromRight] = useState(0) // 오른쪽 끝에서 가려진 봉 수
  const [hover, setHover] = useState<{ x: number; y: number } | null>(null)
  const [menu, setMenu] = useState<{ x: number; y: number; price: number } | null>(null)
  const drag = useRef<{ x: number; from: number; moved: boolean } | null>(null)

  useEffect(() => {
    const el = box.current
    if (!el) return
    const ro = new ResizeObserver(() => setWidth(el.clientWidth))
    ro.observe(el)
    setWidth(el.clientWidth)
    return () => ro.disconnect()
  }, [])

  useEffect(() => {
    if (!menu) return
    const close = () => setMenu(null)
    const key = (e: KeyboardEvent) => e.key === 'Escape' && close()
    window.addEventListener('pointerdown', close)
    window.addEventListener('keydown', key)
    return () => { window.removeEventListener('pointerdown', close); window.removeEventListener('keydown', key) }
  }, [menu])

  const list = candles ?? []
  const n = list.length
  const count = Math.max(5, Math.floor(width / step))
  const maxFrom = Math.max(0, n - count)
  const fr = Math.min(fromRight, maxFrom)
  const end = n - fr
  const start = Math.max(0, end - count)
  const vis = list.slice(start, end)

  // 왼쪽 끝까지 보면 이전 봉을 더 받는다
  useEffect(() => {
    if (more && n > 0 && start === 0) onLoadMore?.()
  }, [more, n, start, onLoadMore])

  const ph = height * (1 - VOL - GAP) // 가격 영역 높이
  const lo = vis.length ? Math.min(...vis.map((c) => c.low)) : 0
  const hi = vis.length ? Math.max(...vis.map((c) => c.high)) : 1
  const span = hi - lo || Math.max(1, hi * 0.01)
  const min = lo - span * PAD
  const max = hi + span * PAD
  const y = (v: number) => (1 - (v - min) / (max - min)) * ph
  const priceAt = (py: number) => max - (max - min) * (py / ph)
  const maxVol = Math.max(1, ...vis.map((c) => c.volume))
  const vy = (v: number) => height - (v / maxVol) * height * VOL
  const left = width - vis.length * step // 첫 봉의 왼쪽. 봉은 오른쪽 끝에 붙는다
  const cx = (i: number) => left + (i + 0.5) * step
  const bw = Math.max(1, Math.round(step * 0.64))
  // 시가와 종가가 같으면 직전 봉 종가와 비교한 색을 쓴다
  const tone = (i: number) => {
    const c = list[i]
    const ref = c.close !== c.open ? c.open : list[i - 1]?.close ?? c.open
    return c.close >= ref ? 'up' : 'down'
  }
  // 가격 눈금은 1·2·5 단위로 4칸 안팎
  const raw = (max - min) / 4
  const mag = 10 ** Math.floor(Math.log10(raw))
  const tick = Math.max(1, [1, 2, 5, 10].map((k) => k * mag).find((v) => v >= raw)!)
  const grid: number[] = []
  for (let g = Math.ceil(min / tick) * tick; g <= max; g += tick) grid.push(g)
  const lastC = list[n - 1]
  const labelEvery = Math.max(Math.ceil(72 / step), Math.ceil(vis.length / 5)) // 시간 글자끼리 겹치지 않게

  const local = (e: React.PointerEvent | React.MouseEvent) => {
    const r = box.current!.getBoundingClientRect()
    return { x: Math.min(r.width, Math.max(0, e.clientX - r.left)), y: Math.min(height, Math.max(0, e.clientY - r.top)) }
  }
  const idx = hover ? Math.min(vis.length - 1, Math.max(0, Math.floor((hover.x - left) / step))) : -1
  const near = idx >= 0 ? vis[idx] : null

  return (
    <div
      ref={box}
      className="cchart"
      style={{ height }}
      onPointerDown={(e) => {
        if (e.button !== 0) return
        drag.current = { x: e.clientX, from: fr, moved: false }
        if (e.pointerType !== 'mouse') setHover(null)
      }}
      onPointerMove={(e) => {
        const d = drag.current
        if (d) {
          const dx = e.clientX - d.x
          if (Math.abs(dx) > 4) d.moved = true
          if (d.moved) {
            setFromRight(Math.min(maxFrom, Math.max(0, d.from + Math.round(dx / step))))
            setHover(null)
            return
          }
        }
        if (e.pointerType === 'mouse') setHover(local(e))
      }}
      onPointerUp={(e) => {
        const d = drag.current
        drag.current = null
        if (d && !d.moved && e.pointerType !== 'mouse') setHover(local(e))
      }}
      onPointerCancel={() => { drag.current = null }}
      onPointerLeave={(e) => { drag.current = null; if (e.pointerType === 'mouse') setHover(null) }}
      onContextMenu={(e) => {
        if (!onPick) return
        e.preventDefault()
        const p = local(e)
        if (p.y > ph) return
        const raw = priceAt(p.y)
        setMenu({ x: p.x, y: p.y, price: snap ? snap(raw) : Math.round(raw) })
      }}
    >
      {candles && n === 0 && <span className="cc-empty">아직 체결이 없어요</span>}
      {width > 0 && vis.length > 0 && (
        <>
          <svg width={width} height={height} aria-hidden="true">
            {grid.map((g) => <line key={g} className="cc-grid" x1="0" x2={width} y1={y(g)} y2={y(g)} />)}
            {vis.map((c, i) => (
              <g key={c.time} className={`cc-${tone(start + i)}`}>
                <line x1={cx(i)} x2={cx(i)} y1={y(c.high)} y2={y(c.low)} />
                <rect x={cx(i) - bw / 2} width={bw} y={y(Math.max(c.open, c.close))} height={Math.max(1, Math.abs(y(c.open) - y(c.close)))} />
                <rect className="cc-vol" x={cx(i) - bw / 2} width={bw} y={vy(c.volume)} height={height - vy(c.volume)} />
              </g>
            ))}
          </svg>

          {grid.map((g) => <span key={g} className="cc-axis" style={{ top: y(g) }}>{fmt(Math.round(g))}</span>)}
          <span className={`cc-tag ${tone(n - 1)}`} style={{ top: Math.min(ph, Math.max(0, y(lastC.close))) }}>{fmt(lastC.close)}</span>
          {vis.map((c, i) => (vis.length - 1 - i) % labelEvery === 0 && i > 0 && (
            <span key={c.time} className="cc-time" style={{ left: cx(i) }}>{axisTime(c.time, unit)}</span>
          ))}
        </>
      )}

      {near && !menu && hover && (
        <>
          <i className="cc-cross-x" style={{ left: cx(idx) }} />
          {hover.y <= ph && (
            <>
              <i className="cc-cross-y" style={{ top: hover.y }} />
              <span className="cc-tag cursor" style={{ top: hover.y }}>{fmt(Math.round(priceAt(hover.y)))}</span>
            </>
          )}
          <div className="cc-tip" style={{ left: cx(idx), transform: `translateX(${cx(idx) > width * 0.6 ? 'calc(-100% - 12px)' : '12px'})` }}>
            <span className="muted">{tipTime(near.time, unit)}</span>
            <span>시가 <b>{fmt(near.open)}</b></span>
            <span>고가 <b className="up">{fmt(near.high)}</b></span>
            <span>저가 <b className="down">{fmt(near.low)}</b></span>
            <span>종가 <b>{fmt(near.close)}</b></span>
            <span>거래량 <b>{fmt(near.volume)}</b></span>
          </div>
        </>
      )}

      {menu && (
        <div className="ctx-menu" role="menu" style={{ left: menu.x, top: menu.y }} onPointerDown={(e) => e.stopPropagation()}>
          <button role="menuitem" onClick={() => { onPick!(menu.price, 'buy'); setMenu(null) }}>{fmt(menu.price)}P에 구매 주문</button>
          <button role="menuitem" onClick={() => { onPick!(menu.price, 'sell'); setMenu(null) }}>{fmt(menu.price)}P에 판매 주문</button>
        </div>
      )}
      {onPick && <span className="cc-help">오른쪽 클릭으로 그 가격에 주문</span>}
    </div>
  )
}

/** 봉 단위 고르기(토스증권식): 분 단위 드롭다운 + 일·주·월·년. 고른 분 단위는 다음 방문에도 기억한다 */
export function UnitPicker({ unit, onChange }: { unit: CandleUnit; onChange: (u: CandleUnit) => void }) {
  const [minute, setMinute] = useState(savedMinuteUnit)
  const [open, setOpen] = useState(false)
  const ref = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!open) return
    const close = (e: PointerEvent) => !ref.current?.contains(e.target as Node) && setOpen(false)
    const key = (e: KeyboardEvent) => e.key === 'Escape' && setOpen(false)
    window.addEventListener('pointerdown', close)
    window.addEventListener('keydown', key)
    return () => { window.removeEventListener('pointerdown', close); window.removeEventListener('keydown', key) }
  }, [open])

  return (
    <div className="unit-picker">
      <div className="up-minute" ref={ref}>
        <button className={isMinute(unit) ? 'on' : ''} aria-haspopup="listbox" aria-expanded={open} onClick={() => setOpen(!open)}>
          {unitLabel(minute)}
          <svg width="10" height="10" viewBox="0 0 10 10" aria-hidden="true"><path d="M2 3.5 5 6.5 8 3.5" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" /></svg>
        </button>
        {open && (
          <div role="listbox" aria-label="분 단위">
            {MINUTE_UNITS.map((u) => (
              <button key={u} role="option" aria-selected={u === unit} className={u === unit ? 'on' : ''} onClick={() => { saveMinuteUnit(u); setMinute(u); onChange(u); setOpen(false) }}>
                {unitLabel(u)}
              </button>
            ))}
          </div>
        )}
      </div>
      {PERIOD_UNITS.map(([u, l]) => (
        <button key={u} className={unit === u ? 'on' : ''} onClick={() => onChange(u)}>{l}</button>
      ))}
    </div>
  )
}
