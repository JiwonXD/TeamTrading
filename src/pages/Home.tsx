import { useState } from 'react'
import { type League, TEAMS, TODAY_MATCHES, teamById } from '../data/teams'
import { ME, START_POINTS, settleMatches, showToast, totalValue, useStore } from '../store'
import { Header, Logo, Segmented, fmt, go, pct, signed, tone } from '../components/ui'

type Sort = 'volume' | 'up' | 'down'

export default function Home() {
  const s = useStore()
  const [league, setLeague] = useState<League | 'all'>('all')
  const [sort, setSort] = useState<Sort>('volume')

  const value = totalValue(ME)
  const profit = value - START_POINTS
  const rows = TEAMS.filter((t) => league === 'all' || t.league === league)
    .map((t) => ({ t, m: s.markets[t.id], chg: s.markets[t.id].last / t.basePrice - 1 }))
    .sort((a, b) =>
      sort === 'volume' ? b.m.volume * b.m.last - a.m.volume * a.m.last : sort === 'up' ? b.chg - a.chg : a.chg - b.chg,
    )
  const allSettled = TODAY_MATCHES.every((m) => s.settled.includes(m.id))

  return (
    <>
      <Header />
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

      <section className="card list-card">
        <h2>구단 시세</h2>
        <div className="chips">
          {(['all', 'KBO', 'K리그', 'LCK'] as const).map((l) => (
            <button key={l} className={`chip ${league === l ? 'on' : ''}`} onClick={() => setLeague(l)}>
              {l === 'all' ? '전체' : l}
            </button>
          ))}
        </div>
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
                <span className={`xs ${tone(chg)}`}>{pct(chg)}</span>
              </span>
            </li>
          ))}
        </ul>
      </section>
      <p className="footnote">
        팀트레이딩(팀트)은 무료 포인트로 즐기는 모의투자 서비스예요. 포인트는 현금으로 바꾸거나 다른 계정에 보낼 수 없어요.
        이 미리보기에서 다른 유저들은 시뮬레이션 계정이에요.
      </p>
    </>
  )
}
