import { useEffect, useState } from 'react'
import { type LeaderboardDto, type LeaderboardPeriod, type LeaderboardSort, ME, fetchLeaderboard, mode, totalValue, useStore } from '../store'
import { Header, Segmented, fmt, pct, signed, tone } from '../components/ui'

export default function Ranking() {
  return mode === 'server' ? <ServerRanking /> : <SimRanking />
}

const PERIODS: { value: LeaderboardPeriod; label: string }[] = [
  { value: 'TODAY', label: '오늘' }, { value: 'WEEK', label: '주간' }, { value: 'MONTH', label: '월간' }, { value: 'SEASON', label: '시즌' },
]
const SORTS: { value: LeaderboardSort; label: string }[] = [{ value: 'RETURN', label: '수익률' }, { value: 'PNL', label: '손익' }]
const REFRESH_MS = 15_000

const day = (d: string) => d.slice(5).replace('-', '.')

/** 서버 리더보드. 수익률은 시간가중수익률이라 가입·광고·게시글 보상은 수익으로 치지 않고 배당은 수익으로 친다 */
function ServerRanking() {
  const [period, setPeriod] = useState<LeaderboardPeriod>('TODAY')
  const [sort, setSort] = useState<LeaderboardSort>('RETURN')
  const [board, setBoard] = useState<{ key: string; data: LeaderboardDto } | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let alive = true
    const load = () =>
      fetchLeaderboard(period, sort)
        .then((data) => { if (alive) { setBoard({ key: period + sort, data }); setError(null) } })
        .catch((e: Error) => { if (alive) setError(e.message) })
    load()
    const timer = setInterval(load, REFRESH_MS)
    return () => { alive = false; clearInterval(timer) }
  }, [period, sort])

  // 기간·정렬을 바꾸면 새 결과가 올 때까지 이전 결과를 보이지 않는다
  const shown = board?.key === period + sort ? board.data : null
  const mine = shown?.mine
  const value = (rate: number, pnl: number) => (sort === 'PNL' ? `${signed(pnl)}P` : pct(rate))
  const sub = (rate: number, pnl: number) => (sort === 'PNL' ? pct(rate) : `${signed(pnl)}P`)

  return (
    <>
      <Header title="수익률 랭킹" />
      <div className="pad">
        <Segmented value={period} options={PERIODS} onChange={setPeriod} />
        <Segmented value={sort} options={SORTS} onChange={setSort} />
      </div>
      <section className="card rank-hero">
        <div className="muted">
          {!shown ? '불러오는 중' : period === 'TODAY' ? '오늘 지금까지' : shown.from > shown.to ? '첫 마감 전' : `${day(shown.from)} ~ ${day(shown.to)} 마감 기준`} · 내 순위
        </div>
        {mine ? (
          <>
            <div className="big">
              {mine.rank > 0 ? `${mine.rank}위` : '순위 밖'}{' '}
              <span className={`small ${tone(sort === 'PNL' ? mine.pnl : mine.returnRate)}`}>{value(mine.returnRate, mine.pnl)}</span>
            </div>
            {mine.reason && <p className="hint">{mine.reason}</p>}
          </>
        ) : (
          shown && <p className="hint">이 기간에 마감된 기록이 아직 없어요.</p>
        )}
        <p className="hint">오늘은 지금 시세로, 나머지는 매일 자정 마감 가격으로 계산해요. 활동 보상은 수익에 넣지 않고 배당은 넣어요.</p>
      </section>
      <section className="card" aria-busy={!shown}>
        {error && <p className="hint" role="alert">{error}</p>}
        {shown && shown.entries.length === 0 && <p className="hint">아직 순위에 오른 사람이 없어요.</p>}
        {shown?.entries.map((r) => (
          <div key={r.rank} className={`rank-row ${r.me ? 'me' : ''}`}>
            <span className={`rank ${r.rank <= 3 ? 'top' : ''}`}>{r.rank}</span>
            <span className="rank-name">{r.nickname}</span>
            <span className="rank-val">
              <span className={tone(sort === 'PNL' ? r.pnl : r.returnRate)}>{value(r.returnRate, r.pnl)}</span>
              <span className="muted xs">{sub(r.returnRate, r.pnl)} · {r.trades}회</span>
            </span>
          </div>
        ))}
      </section>
    </>
  )
}

function SimRanking() {
  const s = useStore()
  const rows = Object.values(s.users)
    .map((u) => ({ u, value: totalValue(u.id), rate: totalValue(u.id) / u.initialValue - 1 }))
    .sort((a, b) => b.rate - a.rate)
  const myRank = rows.findIndex((r) => r.u.id === ME) + 1

  return (
    <>
      <Header title="수익률 랭킹" />
      <section className="card rank-hero">
        <div className="muted">시즌 1 · 내 순위</div>
        <div className="big">{myRank}위 <span className="muted small">/ {rows.length}명</span></div>
        <p className="hint">시즌이 끝나면 포인트가 초기화되고 순위에 따라 명예 배지를 드려요.</p>
      </section>
      <section className="card">
        {rows.map((r, i) => (
          <div key={r.u.id} className={`rank-row ${r.u.id === ME ? 'me' : ''}`}>
            <span className={`rank ${i < 3 ? 'top' : ''}`}>{i + 1}</span>
            <span className="rank-name">{r.u.name}</span>
            <span className="rank-val">
              <span className={tone(r.rate)}>{pct(r.rate)}</span>
              <span className="muted xs">{fmt(r.value)}P</span>
            </span>
          </div>
        ))}
      </section>
    </>
  )
}
