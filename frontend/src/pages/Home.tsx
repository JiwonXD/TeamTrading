import { useState } from 'react'
import { COMPETITION_LABEL, type League, TEAMS, TODAY_MATCHES, teamById } from '../data/teams'
import { ME, type MatchDto, START_POINTS, getMatches, getOfferings, mode, settleMatches, showToast, totalValue, useStore } from '../store'
import { OfferingList } from '../components/Offering'
import { Header, Logo, Segmented, fmt, go, pct, signed, tone } from '../components/ui'
import { LeagueStats, LeagueTabs } from '../components/League'
import { LeagueSchedule } from '../components/Schedule'

type Sort = 'volume' | 'up' | 'down'

/** league가 있으면 그 리그 카테고리 화면, 없으면 전체 홈 */
export default function Home({ league }: { league: League | null }) {
  const s = useStore()
  const [sort, setSort] = useState<Sort>('volume')

  const value = totalValue(ME)
  const profit = value - START_POINTS
  const rows = TEAMS.filter((t) => !league || t.league === league)
    .map((t) => ({ t, m: s.markets[t.id], chg: s.markets[t.id].last / s.markets[t.id].base - 1 }))
    .sort((a, b) =>
      sort === 'volume' ? b.m.volume * b.m.last - a.m.volume * a.m.last : sort === 'up' ? b.chg - a.chg : a.chg - b.chg,
    )
  const serverMatches = getMatches()
  const offerings = getOfferings()

  return (
    <>
      <Header />
      <LeagueTabs current={league} />
      {league ? <LeagueSchedule league={league} /> : (
        <section className="card asset-card" onClick={() => go('portfolio')}>
          <div className="muted">내 자산</div>
          <div className="big">{fmt(value)}P</div>
          <div className={`small ${tone(profit)}`}>
            {signed(profit)}P ({pct(profit / START_POINTS)})
          </div>
          <div className="row-between top-gap">
            <span className="muted">주문 가능 포인트</span>
            <span className="strong">{fmt(s.users[ME].points)}P</span>
          </div>
        </section>
      )}

      {offerings && <OfferingList offerings={league ? offerings.filter((o) => TEAMS.find((t) => t.id === o.teamId)?.league === league) : offerings} />}

      {!league && (serverMatches ? <ServerMatches matches={serverMatches} /> : <SimMatches />)}

      <section className="card list-card">
        <h2>{league ? `${league} 구단 시세` : '구단 시세'}</h2>
        {league && <LeagueStats league={league} />}
        <Segmented
          value={sort}
          onChange={setSort}
          options={[
            { value: 'volume', label: '거래대금' },
            { value: 'up', label: '상승' },
            { value: 'down', label: '하락' },
          ]}
        />
        <ul className="stock-list">
          {rows.length === 0 && <li className="empty">아직 상장한 구단이 없어요</li>}
          {rows.map(({ t, m, chg }, i) => (
            <li key={t.id} onClick={() => go(`team-${t.id}`)}>
              <span className="rank">{i + 1}</span>
              <Logo team={t} />
              <span className="stock-name">
                {t.name}
                <span className="muted xs">{t.league}</span>
              </span>
              <span className="stock-price">
                {fmt(m.last)}P
                {m.listed ? <span className={`xs ${tone(chg)}`}>{pct(chg)}</span> : <span className="xs muted">상장 전</span>}
              </span>
            </li>
          ))}
        </ul>
      </section>
      <p className="footnote">
        팬차트는 무료 포인트로 즐기는 모의투자 서비스예요. 포인트는 현금으로 바꾸거나 다른 계정에 보낼 수 없어요.
        {mode === 'server' ? ' 데모 서버에서는 시뮬레이션 계정도 일반 유저와 같은 방식으로 함께 거래해요.' : ' 이 미리보기에서 다른 유저들은 시뮬레이션 계정이에요.'}
      </p>
    </>
  )
}

/** 시뮬레이션 모드: 목업 경기와 배당 정산 버튼 */
export function SimMatches() {
  const s = useStore()
  const allSettled = TODAY_MATCHES.every((m) => s.settled.includes(m.id))
  return (
    <section className="card">
      <div className="row-between">
        <h2>오늘의 경기</h2>
        <button className="chip-btn" disabled={allSettled} onClick={() => showToast(settleMatches())}>
          {allSettled ? '정산 완료' : '배당 정산하기'}
        </button>
      </div>
      <div className="match-list">
        {TODAY_MATCHES.map((m) => {
          const h = teamById(m.home)
          const a = teamById(m.away)
          return (
            <div key={m.id} className="match">
              <span className="match-league">{m.league}</span>
              <span className={`match-team ${m.homeScore > m.awayScore ? 'win' : ''}`}>{h.short}</span>
              <span className="match-score">{m.homeScore} : {m.awayScore}</span>
              <span className={`match-team ${m.awayScore > m.homeScore ? 'win' : ''}`}>{a.short}</span>
            </div>
          )
        })}
      </div>
      <p className="hint">승리 구단 보유자에게 주당 기준가의 0.5%를 배당해요. 경기 결과는 목업 데이터예요.</p>
    </section>
  )
}


export function matchState(m: MatchDto) {
  if (m.status === 'CANCELED') return '경기 취소'
  if (m.dividendStatus === 'REVIEW') return '결과 확인 중'
  if (m.dividendStatus === 'PAID') return '배당 지급 완료'
  if (m.status === 'FINAL') return '경기 종료 · 배당 확정 대기'
  const start = Date.parse(m.scheduledAt)
  if (start > Date.now()) return `${new Date(start).toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit', hour12: false })} 시작`
  return '경기 중'
}

// 서버 모드: 서버가 받은 경기(실제 또는 demo 프로필의 시연용 가상 경기)와 배당 처리 상태
export function ServerMatches({ matches }: { matches: MatchDto[] }) {
  return (
    <section className="card">
      <h2>최근 경기</h2>
      {matches.length === 0 && <p className="empty">아직 등록된 경기가 없어요</p>}
      <div className="match-list">
        {matches.map((m) => {
          // 컵대회의 상장하지 않은 상대는 구단 id가 없고 이름만 있다
          const h = m.homeTeamId ? teamById(m.homeTeamId).short : m.homeName
          const a = m.awayTeamId ? teamById(m.awayTeamId).short : m.awayName
          const done = m.status === 'FINAL' && m.homeScore !== null && m.awayScore !== null
          return (
            <div key={m.id} className="match">
              <span className="match-league">{COMPETITION_LABEL[m.competition] ?? m.competition}</span>
              <span className={`match-team ${done && m.homeScore! > m.awayScore! ? 'win' : ''}`}>{h}</span>
              <span className="match-score">{done ? `${m.homeScore} : ${m.awayScore}` : 'vs'}</span>
              <span className={`match-team ${done && m.awayScore! > m.homeScore! ? 'win' : ''}`}>{a}</span>
              <span className="match-status">{matchState(m)}</span>
            </div>
          )
        })}
      </div>
      <p className="hint">
        이긴 구단 주식 1주당 KBO 40P · NBA 70P · LCK 160P · 분데스리가 240P · 그 밖의 축구 리그 210P, 무승부는 절반을 배당해요.
        컵대회 경기는 여기에 대회 배수(챔스·롤드컵 1.5배, 국내 컵 0.5배 등)를 곱해요. 경기 시작 시각에 가진 주식에만
        배당하고, 결과가 나온 뒤 정정 여부를 확인하는 시간을 두고 지급해요.
      </p>
    </section>
  )
}
