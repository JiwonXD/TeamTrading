import { useState } from 'react'
import { DEFAULT_TABS, type LeagueTabPrefs, arrangeLeagues } from '../data/leagueTabs'
import { type League, TEAMS } from '../data/teams'
import { getLeagueTabs, saveLeagueTabs, showToast, useStore } from '../store'
import type { State } from '../storeTypes'
import { pct, tone } from './ui'

/** 상장한 구단들의 평균 등락률 */
export function leagueAvg(s: State, league: League) {
  const ts = TEAMS.filter((t) => t.league === league && s.markets[t.id].listed)
  return ts.length ? ts.reduce((n, t) => n + s.markets[t.id].last / s.markets[t.id].base - 1, 0) / ts.length : 0
}

/**
 * 홈 맨 위 리그 카테고리 탭(토스증권의 국내·해외 구분처럼). 주소가 바뀌므로 뒤로 가기·공유가 된다.
 * 순서와 보일 리그는 사람마다 고를 수 있다. 숨긴 리그도 주소로 들어오면 그 탭은 보여 길을 잃지 않게 한다
 */
export function LeagueTabs({ current }: { current: League | null }) {
  useStore()
  const [editing, setEditing] = useState(false)
  const prefs = getLeagueTabs()
  const shown = arrangeLeagues(prefs).filter((e) => !e.hidden || e.league.id === current).map((e) => e.league)
  const items = [{ href: '#home', label: '전체', on: current === null }, ...shown.map((l) => ({ href: `#league-${l.slug}`, label: l.id, on: current === l.id }))]
  return (
    <div className="league-bar">
      <nav className="league-tabs" aria-label="리그">
        {items.map((i) => (
          <a key={i.href} href={i.href} className={i.on ? 'on' : ''} aria-current={i.on ? 'page' : undefined}>{i.label}</a>
        ))}
      </nav>
      <button className="league-edit" onClick={() => setEditing(true)} aria-label="리그 탭 편집" title="리그 탭 편집">
        <svg width="20" height="20" viewBox="0 0 24 24" aria-hidden="true"><path d="M4 7h10M18 7h2M4 17h4M12 17h8" stroke="currentColor" strokeWidth="2" strokeLinecap="round" /><circle cx="16" cy="7" r="2" stroke="currentColor" strokeWidth="2" fill="none" /><circle cx="10" cy="17" r="2" stroke="currentColor" strokeWidth="2" fill="none" /></svg>
      </button>
      {editing && <LeagueTabsEditor prefs={prefs} onClose={() => setEditing(false)} />}
    </div>
  )
}

/** 리그 탭 편집: 보일 리그를 고르고 위·아래로 순서를 바꾼다. 저장을 눌러야 반영된다 */
function LeagueTabsEditor({ prefs, onClose }: { prefs: LeagueTabPrefs; onClose: () => void }) {
  const [rows, setRows] = useState(() => arrangeLeagues(prefs))
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)

  const move = (i: number, d: -1 | 1) => setRows((r) => {
    const next = [...r]
    ;[next[i], next[i + d]] = [next[i + d], next[i]]
    return next
  })
  const toggle = (i: number) => setRows((r) => r.map((e, j) => (j === i ? { ...e, hidden: !e.hidden } : e)))

  const save = async () => {
    setBusy(true)
    const r = await saveLeagueTabs({ order: rows.map((e) => e.league.code), hidden: rows.filter((e) => e.hidden).map((e) => e.league.code) })
    setBusy(false)
    if (!r.ok) return setError(r.message)
    showToast(r.message)
    onClose()
  }

  return (
    <div className="sheet-backdrop">
      <div className="sheet" role="dialog" aria-modal="true" aria-labelledby="league-edit-title">
        <div className="sheet-grip" />
        <h2 id="league-edit-title">리그 탭 편집</h2>
        <p className="muted xs">보고 싶은 리그만 켜고, 화살표로 순서를 바꿔요. 전체 탭은 늘 맨 앞이에요</p>
        <ul className="league-edit-list">
          {rows.map((e, i) => (
            <li key={e.league.code}>
              <label>
                <input type="checkbox" checked={!e.hidden} onChange={() => toggle(i)} />
                <span>{e.league.id}</span>
                <span className="muted xs">{e.league.sport}</span>
              </label>
              <button className="icon-btn" disabled={i === 0} onClick={() => move(i, -1)} aria-label={`${e.league.id} 위로`}>
                <svg width="18" height="18" viewBox="0 0 24 24" aria-hidden="true"><path d="M6 15l6-6 6 6" stroke="currentColor" strokeWidth="2.2" fill="none" strokeLinecap="round" strokeLinejoin="round" /></svg>
              </button>
              <button className="icon-btn" disabled={i === rows.length - 1} onClick={() => move(i, 1)} aria-label={`${e.league.id} 아래로`}>
                <svg width="18" height="18" viewBox="0 0 24 24" aria-hidden="true"><path d="M6 9l6 6 6-6" stroke="currentColor" strokeWidth="2.2" fill="none" strokeLinecap="round" strokeLinejoin="round" /></svg>
              </button>
            </li>
          ))}
        </ul>
        <button className="link-btn" onClick={() => setRows(arrangeLeagues(DEFAULT_TABS))}>기본 순서로 되돌리기</button>
        {error && <p className="login-error" role="alert">{error}</p>}
        <button className="btn wide primary" disabled={busy} onClick={save}>저장</button>
        <button className="btn wide plain" onClick={onClose}>취소</button>
      </div>
    </div>
  )
}

/** 리그 구단 시세 위 한 줄 요약: 리그 평균 등락, 오른·내린 구단 수 */
export function LeagueStats({ league }: { league: League }) {
  const s = useStore()
  const listed = TEAMS.filter((t) => t.league === league && s.markets[t.id].listed)
  const up = listed.filter((t) => s.markets[t.id].last > s.markets[t.id].base).length
  const down = listed.filter((t) => s.markets[t.id].last < s.markets[t.id].base).length
  const avg = leagueAvg(s, league)
  return (
    <p className="league-stats small">
      <span>리그 평균 <b className={tone(avg)}>{pct(avg)}</b></span>
      <span>오른 구단 <b className="up">{up}</b></span>
      <span>내린 구단 <b className="down">{down}</b></span>
    </p>
  )
}
