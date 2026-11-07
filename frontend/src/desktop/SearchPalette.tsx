import { useEffect, useRef, useState } from 'react'
import { TEAMS, type Team } from '../data/teams'
import { useStore } from '../store'
import { Logo, fmt, go, pct, tone } from '../components/ui'

const RECENT_KEY = 'fanchart.recentTeams'

function readRecent(): string[] {
  try {
    const v = JSON.parse(localStorage.getItem(RECENT_KEY) ?? '[]')
    return Array.isArray(v) ? v.filter((id) => TEAMS.some((t) => t.id === id)) : []
  } catch {
    return []
  }
}

export function rememberTeam(id: string) {
  try {
    localStorage.setItem(RECENT_KEY, JSON.stringify([id, ...readRecent().filter((x) => x !== id)].slice(0, 5)))
  } catch { /* 저장소를 못 쓰면 최근 본 구단만 빠진다 */ }
}

const matches = (t: Team, q: string) => [t.name, t.short, t.league, t.id].some((s) => s.toLowerCase().includes(q))

/** 찾은 글자를 파란색으로(토스증권 웹 검색 결과처럼) */
function Highlight({ text, q }: { text: string; q: string }) {
  const i = q ? text.toLowerCase().indexOf(q) : -1
  if (i < 0) return <>{text}</>
  return <>{text.slice(0, i)}<mark>{text.slice(i, i + q.length)}</mark>{text.slice(i + q.length)}</>
}

/** `/` 또는 Ctrl+K로 여는 구단 검색. ↑↓로 고르고 Enter로 이동, Esc로 닫는다 */
export function SearchPalette({ onClose }: { onClose: () => void }) {
  const s = useStore()
  const [q, setQ] = useState('')
  const [active, setActive] = useState(0)
  const list = useRef<HTMLUListElement>(null)
  const query = q.trim().toLowerCase()
  const recent = readRecent()
  const results = query
    ? TEAMS.filter((t) => matches(t, query))
    : [...recent.map((id) => TEAMS.find((t) => t.id === id)!), ...TEAMS.filter((t) => !recent.includes(t.id))]

  useEffect(() => { list.current?.querySelector('[aria-selected="true"]')?.scrollIntoView({ block: 'nearest' }) }, [active])

  const open = (t: Team | undefined) => {
    if (!t) return
    rememberTeam(t.id)
    go(`team-${t.id}`)
    onClose()
  }

  return (
    <div className="palette-backdrop" onPointerDown={onClose}>
      <div className="palette" role="dialog" aria-label="구단 검색" onPointerDown={(e) => e.stopPropagation()}>
        <div className="palette-input">
          <svg width="18" height="18" viewBox="0 0 24 24" aria-hidden="true"><circle cx="11" cy="11" r="7" fill="none" stroke="currentColor" strokeWidth="2" /><path d="M20 20l-3.5-3.5" stroke="currentColor" strokeWidth="2" strokeLinecap="round" /></svg>
          <input
            autoFocus
            role="combobox"
            aria-expanded="true"
            aria-controls="palette-list"
            aria-activedescendant={results[active] ? `pal-${results[active].id}` : undefined}
            placeholder="구단 이름, 리그로 검색"
            value={q}
            onChange={(e) => { setQ(e.target.value); setActive(0) }}
            onKeyDown={(e) => {
              if (e.key === 'ArrowDown') { e.preventDefault(); setActive((a) => Math.min(results.length - 1, a + 1)) }
              else if (e.key === 'ArrowUp') { e.preventDefault(); setActive((a) => Math.max(0, a - 1)) }
              else if (e.key === 'Enter') open(results[active])
              else if (e.key === 'Escape') { if (q) setQ(''); else onClose() }
            }}
          />
          {q && <button className="palette-clear" aria-label="지우기" onClick={() => setQ('')}>×</button>}
        </div>
        <div className="palette-section muted xs">{query ? `구단 ${results.length}` : recent.length ? '최근 본 구단 · 전체' : '전체 구단'}</div>
        <ul className="palette-list" id="palette-list" role="listbox" ref={list}>
          {results.length === 0 && <li className="empty">'{q}'에 맞는 구단이 없어요</li>}
          {results.map((t, i) => {
            const m = s.markets[t.id]
            return (
              <li
                key={t.id}
                id={`pal-${t.id}`}
                role="option"
                aria-selected={i === active}
                className={i === active ? 'on' : ''}
                onPointerMove={() => setActive(i)}
                onClick={() => open(t)}
              >
                <Logo team={t} size={32} />
                <span className="palette-name">
                  <span><Highlight text={t.name} q={query} /></span>
                  <span className="muted xs">{t.league}{recent.includes(t.id) && !query ? ' · 최근' : ''}</span>
                </span>
                <span className="palette-price">
                  {fmt(m.last)}P
                  <span className={`xs ${tone(m.last - m.base)}`}>{m.listed ? pct(m.last / m.base - 1) : '상장 전'}</span>
                </span>
                <kbd className="palette-enter" aria-hidden="true">↵</kbd>
              </li>
            )
          })}
        </ul>
        <div className="palette-foot xs muted">
          <span><kbd>↑</kbd><kbd>↓</kbd> 이동</span>
          <span><kbd>Enter</kbd> 열기</span>
          <span><kbd>Esc</kbd> {q ? '지우기' : '닫기'}</span>
        </div>
      </div>
    </div>
  )
}
