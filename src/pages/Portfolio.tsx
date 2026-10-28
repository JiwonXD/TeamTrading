import { TEAMS, teamById } from '../data/teams'
import { ME, START_POINTS, cancel, lockedPoints, openOrders, reset, showToast, totalShares, totalValue, useStore } from '../store'
import { Header, Logo, fmt, go, pct, signed, timeAgo, tone } from '../components/ui'

export default function Portfolio() {
  const s = useStore()
  const me = s.users[ME]
  const value = totalValue(ME)
  const profit = value - START_POINTS
  const holdings = TEAMS.map((t) => ({ t, qty: totalShares(ME, t.id), avg: me.holdings[t.id]?.avgPrice ?? 0, last: s.markets[t.id].last }))
    .filter((h) => h.qty > 0)
  const invested = holdings.reduce((n, h) => n + h.qty * h.last, 0)
  const orders = openOrders(ME)
  const myTrades = s.trades.filter((t) => t.buyerId === ME || t.sellerId === ME).slice(0, 20)

  return (
    <>
      <Header title="내 투자" right={<button className="text-btn" onClick={() => { reset(); showToast('시즌을 처음부터 다시 시작했어요') }}>초기화</button>} />
      <section className="card">
        <div className="muted">총 자산</div>
        <div className="big">{fmt(value)}P</div>
        <div className={`small ${tone(profit)}`}>{signed(profit)}P ({pct(profit / START_POINTS)})</div>
        <div className="split top-gap">
          <div><span className="muted xs">주문 가능</span><b>{fmt(me.points)}P</b></div>
          <div><span className="muted xs">주문 대기</span><b>{fmt(lockedPoints(ME))}P</b></div>
          <div><span className="muted xs">구단 평가금</span><b>{fmt(invested)}P</b></div>
        </div>
      </section>

      <section className="card list-card">
        <h2>보유 구단</h2>
        {holdings.length === 0 && <p className="empty">아직 보유한 구단이 없어요. 홈에서 응원하는 구단을 찾아보세요.</p>}
        <ul className="stock-list">
          {holdings.map(({ t, qty, avg, last }) => (
            <li key={t.id} onClick={() => go(`team-${t.id}`)}>
              <Logo team={t} />
              <span className="stock-name">
                {t.name}
                <span className="muted xs">{qty}주 · 평균 {fmt(avg)}P</span>
              </span>
              <span className="stock-price">
                {fmt(qty * last)}P
                <span className={`xs ${tone(last - avg)}`}>{signed((last - avg) * qty)} ({pct(last / avg - 1)})</span>
              </span>
            </li>
          ))}
        </ul>
      </section>

      <section className="card">
        <h2>대기 중인 주문</h2>
        {orders.length === 0 && <p className="empty">대기 중인 주문이 없어요</p>}
        {orders.map((o) => (
          <div key={o.id} className="order-row">
            <span className={o.side === 'buy' ? 'up' : 'down'}>{teamById(o.teamId).short} {o.side === 'buy' ? '구매' : '판매'}</span>
            <span>{fmt(o.price)}P · {o.remaining}/{o.qty}주</span>
            <button className="chip-btn" onClick={() => { cancel(ME, o.teamId, o.id); showToast('주문을 취소했어요') }}>취소</button>
          </div>
        ))}
      </section>

      <section className="card">
        <h2>체결 내역</h2>
        {myTrades.length === 0 && <p className="empty">아직 체결된 주문이 없어요</p>}
        {myTrades.map((t) => (
          <div key={t.id} className="order-row">
            <span className={t.buyerId === ME ? 'up' : 'down'}>{teamById(t.teamId).short} {t.buyerId === ME ? '구매' : '판매'}</span>
            <span>{fmt(t.price)}P · {t.qty}주</span>
            <span className="muted xs">{timeAgo(t.ts)}</span>
          </div>
        ))}
      </section>

      <section className="card">
        <h2>배당 내역</h2>
        {s.dividends.length === 0 && <p className="empty">홈의 오늘의 경기에서 배당을 정산할 수 있어요</p>}
        {s.dividends.map((d) => (
          <div key={d.matchId} className="order-row">
            <span>{teamById(d.teamId).name} 승리</span>
            <span>{d.qty}주</span>
            <span className="up">+{fmt(d.amount)}P</span>
          </div>
        ))}
      </section>
    </>
  )
}
