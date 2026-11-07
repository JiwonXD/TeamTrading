import { useEffect, useRef, useState } from 'react'
import type { Side } from '../engine/orderbook'
import { fmt, pct } from '../components/ui'

export interface ChartPoint { price: number; ts?: number }

const W = 1000
const PAD_Y = 0.08 // 위아래 여백(높이 비율)
const GRID = 4

const time = (ts: number) => new Date(ts).toLocaleString('ko-KR', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit', hour12: false })

/**
 * PC용 가격 차트. 마우스를 올리면 십자선과 그 위치의 가격, 가장 가까운 점의 값을 보여주고
 * 오른쪽 클릭하면 그 가격으로 주문을 채우는 메뉴를 연다(토스증권 웹 차트의 동작을 참고).
 */
export function PriceChart({ points, base, height = 320, snap, onPick }: {
  points: ChartPoint[]
  /** 기준가(전일 종가). 점선으로 그리고 색(오름·내림)의 기준이 된다 */
  base?: number
  height?: number
  /** 오른쪽 클릭한 가격을 호가단위·가격제한폭에 맞춘다 */
  snap?: (price: number) => number
  onPick?: (price: number, side: Side) => void
}) {
  const box = useRef<HTMLDivElement>(null)
  const [hover, setHover] = useState<{ x: number; y: number } | null>(null)
  const [menu, setMenu] = useState<{ x: number; y: number; price: number } | null>(null)

  useEffect(() => {
    if (!menu) return
    const close = () => setMenu(null)
    const key = (e: KeyboardEvent) => e.key === 'Escape' && close()
    window.addEventListener('pointerdown', close)
    window.addEventListener('keydown', key)
    return () => { window.removeEventListener('pointerdown', close); window.removeEventListener('keydown', key) }
  }, [menu])

  const data = points.length >= 2 ? points : [points[0], points[0]]
  const prices = data.map((p) => p.price)
  const lo = Math.min(...prices, base ?? Infinity)
  const hi = Math.max(...prices, base ?? -Infinity)
  const span = hi - lo || Math.max(1, hi * 0.01)
  const min = lo - span * PAD_Y
  const max = hi + span * PAD_Y
  const xr = (i: number) => i / (data.length - 1) // 0~1
  const yr = (v: number) => 1 - (v - min) / (max - min) // 0~1
  const d = data.map((p, i) => `${i ? 'L' : 'M'}${(xr(i) * W).toFixed(1)},${(yr(p.price) * height).toFixed(1)}`).join('')
  const ref = base ?? data[0].price
  const last = data[data.length - 1].price
  const cls = last > ref ? 'up' : last < ref ? 'down' : 'flat'
  const grid = Array.from({ length: GRID + 1 }, (_, i) => max - ((max - min) * i) / GRID)

  const at = (clientX: number, clientY: number) => {
    const r = box.current!.getBoundingClientRect()
    return { x: Math.min(1, Math.max(0, (clientX - r.left) / r.width)), y: Math.min(1, Math.max(0, (clientY - r.top) / r.height)), r }
  }
  const priceAt = (y: number) => max - (max - min) * y
  const idx = hover ? Math.round(hover.x * (data.length - 1)) : -1
  const near = idx >= 0 ? data[idx] : null

  return (
    <div
      ref={box}
      className={`pchart ${cls}`}
      style={{ height }}
      onPointerMove={(e) => { const p = at(e.clientX, e.clientY); setHover({ x: p.x, y: p.y }) }}
      onPointerLeave={() => setHover(null)}
      onContextMenu={(e) => {
        if (!onPick) return
        e.preventDefault()
        const p = at(e.clientX, e.clientY)
        const raw = priceAt(p.y)
        setMenu({ x: e.clientX - p.r.left, y: e.clientY - p.r.top, price: snap ? snap(raw) : Math.round(raw) })
      }}
    >
      <svg viewBox={`0 0 ${W} ${height}`} preserveAspectRatio="none" aria-hidden="true">
        <defs>
          <linearGradient id={`pc-fill-${cls}`} x1="0" x2="0" y1="0" y2="1">
            <stop offset="0" stopColor="currentColor" stopOpacity="0.18" />
            <stop offset="1" stopColor="currentColor" stopOpacity="0" />
          </linearGradient>
        </defs>
        {grid.map((g) => <line key={g} className="pc-grid" x1="0" x2={W} y1={yr(g) * height} y2={yr(g) * height} />)}
        {base !== undefined && <line className="pc-base" x1="0" x2={W} y1={yr(base) * height} y2={yr(base) * height} />}
        <path d={`${d}L${W},${height}L0,${height}Z`} fill={`url(#pc-fill-${cls})`} />
        <path d={d} className="pc-line" />
      </svg>

      {/* 오른쪽 가격 축 */}
      {grid.map((g, i) => (
        <span key={g} className="pc-axis" style={{ top: `${(i / GRID) * 100}%` }}>{fmt(g)}</span>
      ))}
      <span className={`pc-tag ${cls}`} style={{ top: `${yr(last) * 100}%` }}>{fmt(last)}</span>

      {hover && !menu && (
        <>
          <i className="pc-cross-x" style={{ left: `${xr(idx) * 100}%` }} />
          <i className="pc-cross-y" style={{ top: `${hover.y * 100}%` }} />
          <span className="pc-tag cursor" style={{ top: `${hover.y * 100}%` }}>{fmt(priceAt(hover.y))}</span>
          <i className={`pc-dot ${cls}`} style={{ left: `${xr(idx) * 100}%`, top: `${yr(near!.price) * 100}%` }} />
          <div className="pc-tip" style={{ left: `${xr(idx) * 100}%`, transform: `translateX(${hover.x > 0.7 ? 'calc(-100% - 12px)' : '12px'})` }}>
            <b>{fmt(near!.price)}P</b>
            {base !== undefined && <span className={near!.price > base ? 'up' : near!.price < base ? 'down' : ''}>{pct(near!.price / base - 1)}</span>}
            {near!.ts && <span className="muted">{time(near!.ts)}</span>}
          </div>
        </>
      )}

      {menu && (
        <div className="ctx-menu" role="menu" style={{ left: menu.x, top: menu.y }} onPointerDown={(e) => e.stopPropagation()}>
          <button role="menuitem" onClick={() => { onPick!(menu.price, 'buy'); setMenu(null) }}>{fmt(menu.price)}P에 구매 주문</button>
          <button role="menuitem" onClick={() => { onPick!(menu.price, 'sell'); setMenu(null) }}>{fmt(menu.price)}P에 판매 주문</button>
        </div>
      )}
      {onPick && <span className="pc-help">오른쪽 클릭으로 그 가격에 주문</span>}
    </div>
  )
}
