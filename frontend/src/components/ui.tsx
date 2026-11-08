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
    // 구단 이름이 항상 옆에 있으므로 로고는 장식으로 둔다
    <span
      aria-hidden="true"
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
        title ? <span className="brand">팬차트</span> : <h1 className="brand">팬차트</h1>
      )}
      {title && <h1 className="topbar-title">{title}</h1>}
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
