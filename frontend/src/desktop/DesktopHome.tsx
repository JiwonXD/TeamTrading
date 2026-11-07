import { useState } from 'react'
import { type League, TEAMS, type Team } from '../data/teams'
import { ME, START_POINTS, getMatches, getOfferings, lockedPoints, mode, totalValue, useStore } from '../store'
import { OfferingList } from '../components/Offering'
import { WatchStar } from '../components/WatchStar'
import { Logo, fmt, go, pct, signed, timeAgo, tone } from '../components/ui'
import { ServerMatches, SimMatches } from '../pages/Home'
import { LeagueStats, LeagueTabs } from '../components/League'
import { LeagueSchedule } from '../components/Schedule'
import { PriceChart } from './PriceChart'
import { useFlash } from './useDesktop'

type SortKey = 'value' | 'chg' | 'last' | 'volume'

const COLUMNS: { key: SortKey | null; label: string; cls?: string }[] = [
  { key: null, label: '순위 · 구단' },
  { key: 'last', label: '현재가', cls: 'num' },
  { key: 'chg', label: '등락률', cls: 'num' },
  { key: 'volume', label: '거래량', cls: 'num' },
  { key: 'value', label: '거래대금', cls: 'num' },
  { key: null, label: '오늘 범위', cls: 'range-col' },
]

/** league가 있으면 그 리그 카테고리 화면, 없으면 전체 홈 */
export default function DesktopHome({ league }: { league: League | null }) {
  const s = useStore()
  const [sort, setSort] = useState<{ key: SortKey; desc: boolean }>({ key: 'value', desc: true })
  const [preview, setPreview] = useState<string | null>(null)

  const rows = TEAMS.filter((t) => !league || t.league === league)
    .map((t) => {
      const m = s.markets[t.id]
      return { t, m, chg: m.last / m.base - 1, sortVal: { value: m.volume * m.last, chg: m.last / m.base - 1, last: m.last, volume: m.volume } }
    })
    .sort((a, b) => (sort.desc ? -1 : 1) * (a.sortVal[sort.key] - b.sortVal[sort.key]))
  const shownId = preview && rows.some((r) => r.t.id === preview) ? preview : rows[0]?.t.id
  const serverMatches = getMatches()
  const offerings = getOfferings()
  const movers = [...TEAMS].filter((t) => s.markets[t.id].listed)
    .sort((a, b) => Math.abs(s.markets[b.id].last / s.markets[b.id].base - 1) - Math.abs(s.markets[a.id].last / s.markets[a.id].base - 1))
    .slice(0, 3)

  return (
    <div className="dh">
      <h1 className="sr-only">{league ? `팬차트 ${league}` : '팬차트 홈'}</h1>
      <LeagueTabs current={league} />
      {league ? <LeagueSchedule league={league} /> : (
        <section className="dh-top">
          <AssetCard />
          <div className="dh-movers">
            {movers.map((t) => <MoverCard key={t.id} team={t} />)}
          </div>
        </section>
      )}

      {offerings && <OfferingList offerings={league ? offerings.filter((o) => TEAMS.find((t) => t.id === o.teamId)?.league === league) : offerings} />}

      <section className="dh-board">
        <div className="panel dh-table-panel">
          <div className="panel-head">
            <h2>{league ? `${league} 실시간 시세` : '실시간 구단 시세'}</h2>
            {league && <LeagueStats league={league} />}
          </div>
          <table className="dtable">
            <thead>
              <tr>
                {COLUMNS.map((c) => (
                  <th key={c.label} className={c.cls} aria-sort={c.key && sort.key === c.key ? (sort.desc ? 'descending' : 'ascending') : undefined}>
                    {c.key ? (
                      <button className={`sort-btn ${sort.key === c.key ? 'on' : ''}`} onClick={() => setSort((p) => ({ key: c.key!, desc: p.key === c.key ? !p.desc : true }))}>
                        {c.label}
                        <span className="sort-arrow" aria-hidden="true">{sort.key === c.key ? (sort.desc ? '↓' : '↑') : '↕'}</span>
                      </button>
                    ) : c.label}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody onPointerLeave={() => setPreview(null)}>
              {rows.length === 0 && <tr><td colSpan={COLUMNS.length} className="empty">아직 상장한 구단이 없어요</td></tr>}
              {rows.map((r, i) => (
                <TeamRow key={r.t.id} team={r.t} rank={i + 1} active={r.t.id === shownId} onHover={() => setPreview(r.t.id)} />
              ))}
            </tbody>
          </table>
          <p className="hint">줄에 마우스를 올리면 오른쪽에 미리 보여주고, 누르면 주문 화면으로 가요.</p>
        </div>
        {shownId && <Preview teamId={shownId} />}
      </section>

      {!league && <section className="dh-bottom">
        {serverMatches ? <ServerMatches matches={serverMatches} /> : <SimMatches />}
        <section className="card">
          <h2>팬차트는</h2>
          <p className="hint">
            무료 포인트로 즐기는 스포츠 구단 모의 거래소예요. 포인트는 현금으로 바꾸거나 다른 계정에 보낼 수 없어요.
            {mode === 'server' ? ' 데모 서버에서는 시뮬레이션 계정도 일반 유저와 같은 방식으로 함께 거래해요.' : ' 이 미리보기에서 다른 유저들은 시뮬레이션 계정이에요.'}
          </p>
          <p className="hint">단축키: <kbd>/</kbd> 검색 · 구단 화면 차트에서 오른쪽 클릭으로 그 가격에 주문</p>
        </section>
      </section>}
    </div>
  )
}

function AssetCard() {
  const s = useStore()
  const value = totalValue(ME)
  const profit = value - START_POINTS
  return (
    <a href="#portfolio" className="panel dh-asset">
      <span className="muted">내 자산</span>
      <b className="dh-asset-value">{fmt(value)}P</b>
      <span className={`small ${tone(profit)}`}>{signed(profit)}P ({pct(profit / START_POINTS)})</span>
      <span className="dh-asset-split">
        <span><span className="muted xs">주문 가능</span>{fmt(s.users[ME].points)}P</span>
        <span><span className="muted xs">주문 대기</span>{fmt(lockedPoints(ME))}P</span>
      </span>
    </a>
  )
}

/** 오늘 가장 크게 움직인 구단. 작은 차트와 함께 */
function MoverCard({ team }: { team: Team }) {
  const s = useStore()
  const m = s.markets[team.id]
  const chg = m.last / m.base - 1
  return (
    <a href={`#team-${team.id}`} className="panel mover">
      <span className="row-gap"><Logo team={team} size={24} /><b>{team.name}</b></span>
      <span className="mover-price">{fmt(m.last)}P <span className={tone(chg)}>{pct(chg)}</span></span>
      <Spark points={m.intraday.map((p) => p.price)} base={m.base} />
    </a>
  )
}

function Spark({ points, base }: { points: number[]; base: number }) {
  const W = 120
  const H = 36
  const ps = points.length > 1 ? points : [points[0], points[0]]
  const lo = Math.min(...ps, base)
  const hi = Math.max(...ps, base)
  const y = (v: number) => H - 2 - ((v - lo) / (hi - lo || 1)) * (H - 4)
  const d = ps.map((v, i) => `${i ? 'L' : 'M'}${((i / (ps.length - 1)) * W).toFixed(1)},${y(v).toFixed(1)}`).join('')
  const last = ps[ps.length - 1]
  return (
    <svg className={`spark ${tone(last - base) || 'flat'}`} viewBox={`0 0 ${W} ${H}`} preserveAspectRatio="none" aria-hidden="true">
      <line x1="0" x2={W} y1={y(base)} y2={y(base)} className="spark-base" />
      <path d={d} />
    </svg>
  )
}

function TeamRow({ team, rank, active, onHover }: { team: Team; rank: number; active: boolean; onHover: () => void }) {
  const s = useStore()
  const m = s.markets[team.id]
  const chg = m.last / m.base - 1
  const flash = useFlash(m.last)
  // 오늘 범위: 저가~고가 막대 위에 현재가 위치(토스증권 웹의 1일 범위 표시를 참고)
  const span = m.high - m.low
  const pos = span > 0 ? (m.last - m.low) / span : 0.5
  return (
    <tr className={active ? 'active' : ''} onPointerEnter={onHover} onClick={() => go(`team-${team.id}`)}>
      <td>
        <div className="dt-team-cell">
          <WatchStar team={team} size={18} />
          <a href={`#team-${team.id}`} className="dt-team" onFocus={onHover}>
            <span className="dt-rank">{rank}</span>
            <Logo team={team} size={32} />
            <span className="dt-name">{team.name}<span className="muted xs">{team.league}</span></span>
          </a>
        </div>
      </td>
      <td className="num"><span key={flash.key} className={`cell-flash ${flash.className}`}>{fmt(m.last)}P</span></td>
      <td className={`num ${tone(chg)}`}>{m.listed ? pct(chg) : <span className="muted">상장 전</span>}</td>
      <td className="num">{fmt(m.volume)}주</td>
      <td className="num">{fmt(m.volume * m.last)}P</td>
      <td className="range-col">
        <span className="range" title={`저가 ${fmt(m.low)} · 고가 ${fmt(m.high)}`}>
          <span className="range-bar"><i style={{ left: `${pos * 100}%` }} className={tone(chg)} /></span>
        </span>
      </td>
    </tr>
  )
}

/** 마우스를 올린 구단 미리보기: 오늘 차트, 주요 값, 최근 토론 */
function Preview({ teamId }: { teamId: string }) {
  const s = useStore()
  const team = TEAMS.find((t) => t.id === teamId)!
  const m = s.markets[teamId]
  const diff = m.last - m.base
  const posts = s.posts.filter((p) => p.teamId === teamId).slice(0, 2)
  return (
    <aside className="panel dh-preview" aria-live="polite">
      <a href={`#team-${teamId}`} className="row-gap dh-preview-head">
        <Logo team={team} size={36} />
        <span className="dt-name"><b>{team.name}</b><span className="muted xs">{team.league}</span></span>
      </a>
      <div className="dh-preview-price">{fmt(m.last)}P</div>
      <div className={`small ${tone(diff)}`}>{m.listed ? `어제보다 ${signed(diff)}P (${pct(diff / m.base)})` : '상장 전'}</div>
      <PriceChart points={m.intraday} base={m.base} height={180} />
      <dl className="kv">
        <div><dt>고가</dt><dd>{fmt(m.high)}</dd></div>
        <div><dt>저가</dt><dd>{fmt(m.low)}</dd></div>
        <div><dt>거래량</dt><dd>{fmt(m.volume)}</dd></div>
        <div><dt>기준가</dt><dd>{fmt(m.base)}</dd></div>
      </dl>
      {posts.length > 0 && (
        <div className="dh-preview-talk">
          <span className="muted xs">최근 토론</span>
          {posts.map((p) => <p key={p.id} className="small"><b>{p.author}</b> {p.body} <span className="muted xs">{timeAgo(p.ts)}</span></p>)}
        </div>
      )}
      <button className="btn primary dh-preview-go" onClick={() => go(`team-${teamId}`)}>주문하러 가기</button>
    </aside>
  )
}
