import type { ReactNode } from 'react'
import type { Team } from '../data/teams'

export const go = (path: string) => {
  location.hash = path
}

export const fmt = (n: number) => Math.round(n).toLocaleString('ko-KR')
export const pct = (r: number) => `${r > 0 ? '+' : ''}${(r * 100).toFixed(2)}%`
export const signed = (n: number) => `${n > 0 ? '+' : ''}${fmt(n)}`
export const tone = (n: number) => (n > 0 ? 'up' : n < 0 ? 'down' : '')

export function timeAgo(ts: number) {
  const m = Math.floor((Date.now() - ts) / 60_000)
  if (m < 1) return '방금'
  if (m < 60) return `${m}분 전`
  return `${Math.floor(m / 60)}시간 전`
}

export function Logo({ team, size = 40 }: { team: Team; size?: number }) {
  return (
    <span
      className="logo"
      style={{ background: team.color, width: size, height: size, fontSize: size * (team.short.length > 2 ? 0.28 : 0.34) }}
    >
      {team.short}
    </span>
  )
}

export function Header({ title, onBack, right }: { title?: string; onBack?: () => void; right?: ReactNode }) {
  return (
    <header className="topbar">
      {onBack ? (
        <button className="icon-btn" onClick={onBack} aria-label="뒤로">
          <svg width="24" height="24" viewBox="0 0 24 24"><path d="M15 5l-7 7 7 7" stroke="currentColor" strokeWidth="2.2" fill="none" strokeLinecap="round" strokeLinejoin="round" /></svg>
        </button>
      ) : (
        <span className="brand">팀트</span>
      )}
      {title && <span className="topbar-title">{title}</span>}
      <span className="topbar-right">{right}</span>
    </header>
  )
}

export function Segmented<T extends string>({ value, options, onChange }: { value: T; options: { value: T; label: string }[]; onChange: (v: T) => void }) {
  return (
    <div className="segmented">
      {options.map((o) => (
        <button key={o.value} className={o.value === value ? 'on' : ''} onClick={() => onChange(o.value)}>
          {o.label}
        </button>
      ))}
    </div>
  )
}

export function Chart({ points, base }: { points: number[]; base?: number }) {
  const W = 360
  const H = 170
  const pad = 10
  if (points.length < 2) points = [points[0], points[0]]
  const min = Math.min(...points, base ?? Infinity)
  const max = Math.max(...points, base ?? -Infinity)
  const span = max - min || 1
  const x = (i: number) => (i / (points.length - 1)) * W
  const y = (v: number) => pad + (1 - (v - min) / span) * (H - pad * 2)
  const d = points.map((v, i) => `${i ? 'L' : 'M'}${x(i).toFixed(1)},${y(v).toFixed(1)}`).join('')
  const ref = base ?? points[0]
  const cls = points[points.length - 1] >= ref ? 'up' : 'down'
  const maxIdx = points.indexOf(Math.max(...points))
  const minIdx = points.indexOf(Math.min(...points))
  return (
    <div className="chart-wrap">
      <svg viewBox={`0 0 ${W} ${H}`} className={`chart ${cls}`} preserveAspectRatio="none">
        {base !== undefined && <line x1="0" x2={W} y1={y(base)} y2={y(base)} className="base" />}
        <path d={`${d}L${W},${H}L0,${H}Z`} className="area" />
        <path d={d} className="line" vectorEffect="non-scaling-stroke" />
      </svg>
      {maxIdx !== minIdx && <span className="chart-label" style={{ top: `${(y(points[maxIdx]) / H) * 100}%`, left: `${Math.min(80, (x(maxIdx) / W) * 100)}%` }}>최고 {fmt(points[maxIdx])}</span>}
      {maxIdx !== minIdx && <span className="chart-label" style={{ top: `${(y(points[minIdx]) / H) * 100}%`, left: `${Math.min(80, (x(minIdx) / W) * 100)}%` }}>최저 {fmt(points[minIdx])}</span>}
    </div>
  )
}
