import { LEAGUES, type League, TEAMS } from '../data/teams'
import { useStore } from '../store'
import type { State } from '../storeTypes'
import { pct, tone } from './ui'

/** 상장한 구단들의 평균 등락률 */
export function leagueAvg(s: State, league: League) {
  const ts = TEAMS.filter((t) => t.league === league && s.markets[t.id].listed)
  return ts.length ? ts.reduce((n, t) => n + s.markets[t.id].last / s.markets[t.id].base - 1, 0) / ts.length : 0
}

/** 홈 맨 위 리그 카테고리 탭(토스증권의 국내·해외 구분처럼). 주소가 바뀌므로 뒤로 가기·공유가 된다 */
export function LeagueTabs({ current }: { current: League | null }) {
  const items = [{ href: '#home', label: '전체', on: current === null }, ...LEAGUES.map((l) => ({ href: `#league-${l.slug}`, label: l.id, on: current === l.id }))]
  return (
    <nav className="league-tabs" aria-label="리그">
      {items.map((i) => (
        <a key={i.href} href={i.href} className={i.on ? 'on' : ''} aria-current={i.on ? 'page' : undefined}>{i.label}</a>
      ))}
    </nav>
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
