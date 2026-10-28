import { ME, totalValue, useStore } from '../store'
import { Header, fmt, pct, tone } from '../components/ui'

export default function Ranking() {
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
