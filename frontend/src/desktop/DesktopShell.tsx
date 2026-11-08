import { type ReactNode, useEffect, useState } from 'react'
import { type League, TEAMS, type Team, isOpen, leaguesWithTeams } from '../data/teams'
import { visibleLeagues } from '../data/leagueTabs'
import { ME, START_POINTS, canWatch, getConnection, getLeagueTabs, getProfile, getWatchlist, mode, totalShares, totalValue, useStore } from '../store'
import { Logo, fmt, pct, signed, tone } from '../components/ui'
import { leagueAvg } from '../components/League'
import { SearchPalette } from './SearchPalette'
import { WatchStar } from '../components/WatchStar'
import { useFlash } from './useDesktop'
import './desktop.css'

const NAV = [
  { path: 'home', label: '홈' },
  { path: 'portfolio', label: '내 투자' },
  { path: 'ranking', label: '랭킹' },
  { path: 'watch', label: '시장감시' },
]

const SIDEBAR_KEY = 'fanchart.sidebar'
const readSidebar = () => { try { return localStorage.getItem(SIDEBAR_KEY) !== 'closed' } catch { return true } }

/** 입력 중이 아닐 때 `/`, 언제든 Ctrl·⌘+K로 검색을 연다 */
function useSearchShortcut(open: () => void) {
  useEffect(() => {
    const on = (e: KeyboardEvent) => {
      const el = e.target as HTMLElement
      const typing = el.isContentEditable || ['INPUT', 'TEXTAREA', 'SELECT'].includes(el.tagName)
      if ((e.key === '/' && !typing) || (e.key.toLowerCase() === 'k' && (e.ctrlKey || e.metaKey))) {
        e.preventDefault()
        open()
      }
    }
    window.addEventListener('keydown', on)
    return () => window.removeEventListener('keydown', on)
  }, [open])
}

export default function DesktopShell({ hash, teamId, banner, toast, children }: {
  hash: string
  teamId: string | null
  banner: ReactNode
  toast: ReactNode
  children: ReactNode
}) {
  useStore()
  const [search, setSearch] = useState(false)
  const [sidebar, setSidebar] = useState(readSidebar)
  useSearchShortcut(() => setSearch(true))
  const toggleSidebar = () => {
    setSidebar((v) => {
      try { localStorage.setItem(SIDEBAR_KEY, v ? 'closed' : 'open') } catch { /* 다음 방문 때 기본값으로 */ }
      return !v
    })
  }
  const nickname = getProfile()?.nickname

  return (
    <div className={`desk ${sidebar ? '' : 'side-closed'}`}>
      <header className="desk-header">
        <a href="#home" className="desk-brand">
          <svg width="22" height="22" viewBox="0 0 24 24" aria-hidden="true"><path d="M3 17l5-5 4 4 8-9" fill="none" stroke="currentColor" strokeWidth="2.6" strokeLinecap="round" strokeLinejoin="round" /></svg>
          팬차트
        </a>
        <nav className="desk-nav" aria-label="주 메뉴">
          {NAV.filter((n) => n.path !== 'watch' || canWatch()).map((n) => {
            // 리그 화면(#league-…)은 홈 안의 카테고리라 홈에 불을 켠다
            const on = hash === n.path || (n.path === 'home' && hash.startsWith('league-'))
            return <a key={n.path} href={`#${n.path}`} className={on ? 'on' : ''} aria-current={on ? 'page' : undefined}>{n.label}</a>
          })}
        </nav>
        <button className="desk-search" onClick={() => setSearch(true)} aria-keyshortcuts="/ Control+K">
          <svg width="16" height="16" viewBox="0 0 24 24" aria-hidden="true"><circle cx="11" cy="11" r="7" fill="none" stroke="currentColor" strokeWidth="2" /><path d="M20 20l-3.5-3.5" stroke="currentColor" strokeWidth="2" strokeLinecap="round" /></svg>
          <kbd>/</kbd>를 눌러 구단을 검색하세요
        </button>
        <a href="#portfolio" className="desk-me">
          <span className="muted xs">{nickname ?? (mode === 'server' ? '내 계정' : '미리보기')}</span>
          <b>{fmt(totalValue(ME))}P</b>
        </a>
      </header>

      {banner}
      <div className="desk-body">
        <main className="desk-main" key={hash}>{children}</main>
        <Sidebar open={sidebar} onToggle={toggleSidebar} currentTeam={teamId} />
      </div>
      <Ticker />

      {search && <SearchPalette onClose={() => setSearch(false)} />}
      {toast}
    </div>
  )
}

type SideTab = 'watch' | 'live' | 'mine'
const SIDE_TABS: [SideTab, string][] = [['watch', '관심'], ['live', '실시간'], ['mine', '보유']]
const EMPTY: Record<SideTab, string> = {
  watch: '구단 이름 옆 별을 누르면 여기에 모여요',
  live: '구단이 없어요',
  mine: '보유한 구단이 없어요',
}

function Sidebar({ open, onToggle, currentTeam }: { open: boolean; onToggle: () => void; currentTeam: string | null }) {
  const s = useStore()
  const watchlist = getWatchlist()
  // 고르기 전에는 관심 구단이 있으면 관심, 없으면 실시간을 연다
  const [picked, setPicked] = useState<SideTab | null>(null)
  const tab = picked ?? (watchlist.length > 0 ? 'watch' : 'live')
  const [league, setLeague] = useState<League | 'all'>('all')
  const byValue = (a: Team, b: Team) => s.markets[b.id].volume * s.markets[b.id].last - s.markets[a.id].volume * s.markets[a.id].last
  const rows = (tab === 'watch'
    // 관심은 담은 순서대로 둔다
    ? watchlist.flatMap((id) => TEAMS.find((t) => t.id === id) ?? [])
    : (tab === 'mine' ? TEAMS.filter((t) => totalShares(ME, t.id) > 0) : TEAMS.filter(isOpen)).sort(byValue))
    .filter((t) => league === 'all' || t.league === league)

  if (!open) return (
    <aside className="desk-side closed" aria-label="구단 목록">
      <button className="side-toggle" onClick={onToggle} aria-label="구단 목록 펼치기" title="구단 목록 펼치기">
        <svg width="18" height="18" viewBox="0 0 24 24" aria-hidden="true"><path d="M13 6l-6 6 6 6M19 6l-6 6 6 6" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" /></svg>
      </button>
      <span className="side-rail-label">구단</span>
    </aside>
  )

  return (
    <aside className="desk-side" aria-label="구단 목록">
      <div className="side-head">
        <div className="side-tabs" role="tablist">
          {SIDE_TABS.map(([v, l]) => (
            <button key={v} role="tab" aria-selected={tab === v} className={tab === v ? 'on' : ''} onClick={() => setPicked(v)}>{l}</button>
          ))}
        </div>
        <button className="side-toggle" onClick={onToggle} aria-label="구단 목록 접기" title="구단 목록 접기">
          <svg width="18" height="18" viewBox="0 0 24 24" aria-hidden="true"><path d="M11 6l6 6-6 6M5 6l6 6-6 6" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" /></svg>
        </button>
      </div>
      <div className="side-filter">
        {(['all', ...visibleLeagues(getLeagueTabs()).filter((l) => TEAMS.some((t) => t.league === l.id)).map((l) => l.id)] as const).map((l) => (
          <button key={l} className={league === l ? 'on' : ''} onClick={() => setLeague(l)}>{l === 'all' ? '전체' : l}</button>
        ))}
        <span className="muted xs side-sort">{tab === 'watch' ? '담은 순' : '거래대금순'}</span>
      </div>
      <ul className="side-list">
        {rows.length === 0 && <li className="empty">{EMPTY[tab]}</li>}
        {rows.map((t, i) => <SideRow key={t.id} team={t} rank={tab === 'watch' ? null : i + 1} on={t.id === currentTeam} />)}
      </ul>
    </aside>
  )
}

/** rank가 없으면(관심 탭) 순위 대신 별을 두어 바로 뺄 수 있게 한다 */
function SideRow({ team, rank, on }: { team: Team; rank: number | null; on: boolean }) {
  const s = useStore()
  const m = s.markets[team.id]
  const flash = useFlash(m.last)
  const chg = m.last / m.base - 1
  return (
    <li className={rank === null ? 'side-watch' : undefined}>
      {rank === null && <WatchStar team={team} size={18} />}
      <a href={`#team-${team.id}`} className={`side-row ${on ? 'on' : ''}`} aria-current={on ? 'page' : undefined}>
        {rank !== null && <span className="side-rank">{rank}</span>}
        <Logo team={team} size={30} />
        <span className="side-name">{team.name}</span>
        <span className="side-price">
          <span key={flash.key} className={`num ${flash.className}`}>{fmt(m.last)}</span>
          <span className={`xs ${tone(chg)}`}>{m.listed ? pct(chg) : '상장 전'}</span>
        </span>
      </a>
    </li>
  )
}

/** 화면 맨 아래 띠: 리그별 평균 등락과 내 자산, 실시간 연결 상태 */
function Ticker() {
  const s = useStore()
  const conn = getConnection()
  const value = totalValue(ME)
  return (
    <footer className="desk-ticker xs">
      <span className={`live-dot ${conn.status === 'live' ? 'on' : ''}`}>{conn.status === 'live' ? '실시간' : '연결 확인 중'}</span>
      {leaguesWithTeams().map((l) => (
        <a key={l.slug} href={`#league-${l.slug}`}>{l.id} 평균 <b className={tone(leagueAvg(s, l.id))}>{pct(leagueAvg(s, l.id))}</b></a>
      ))}
      <span>내 자산 <b>{fmt(value)}P</b> <span className={tone(value - START_POINTS)}>{signed(value - START_POINTS)}P</span></span>
      <span className="ticker-right muted">포인트는 현금으로 바꾸거나 보낼 수 없어요 · <a href="#terms">이용약관</a> · <a href="#privacy">개인정보처리방침</a></span>
    </footer>
  )
}
