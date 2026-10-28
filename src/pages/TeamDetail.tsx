import { useState } from 'react'
import { teamById } from '../data/teams'
import { levels, priceBand, stepPrice, tickSize, type OrderType, type Side } from '../engine/orderbook'
import { ME, addPost, cancel, openOrders, placeOrder, showToast, totalShares, useStore } from '../store'
import { Chart, Header, Logo, Segmented, fmt, go, pct, signed, timeAgo, tone } from '../components/ui'

type Period = '1d' | '1w' | '1m' | '3m'
type Tab = 'book' | 'trades' | 'orders' | 'talk'

export default function TeamDetail({ teamId }: { teamId: string }) {
  const s = useStore()
  const team = teamById(teamId)
  const m = s.markets[teamId]
  const [period, setPeriod] = useState<Period>('1d')
  const [tab, setTab] = useState<Tab>('book')
  const [sheet, setSheet] = useState<{ side: Side; price: number } | null>(null)

  const diff = m.last - team.basePrice
  const points =
    period === '1d'
      ? m.intraday.map((p) => p.price)
      : [...m.daily.slice(period === '1w' ? -7 : period === '1m' ? -30 : -90), m.last]
  const myQty = totalShares(ME, teamId)
  const myAvg = s.users[ME].holdings[teamId]?.avgPrice ?? 0
  const myOrders = openOrders(ME).filter((o) => o.teamId === teamId)

  return (
    <>
      <Header onBack={() => history.length > 1 ? history.back() : go('home')} title={team.name} />
      <section className="detail-head">
        <div className="row-gap">
          <Logo team={team} size={28} />
          <span className="muted">{team.name} · {team.league}</span>
        </div>
        <div className="huge">{fmt(m.last)}P</div>
        <div className={`small ${tone(diff)}`}>
          어제보다 {signed(diff)}P ({pct(diff / team.basePrice)})
        </div>
      </section>

      <Chart points={points} base={period === '1d' ? team.basePrice : undefined} />
      <div className="pad">
        <Segmented
          value={period}
          onChange={setPeriod}
          options={[
            { value: '1d', label: '1일' },
            { value: '1w', label: '1주' },
            { value: '1m', label: '1달' },
            { value: '3m', label: '3달' },
          ]}
        />
      </div>

      {myQty > 0 && (
        <section className="card">
          <div className="row-between">
            <span className="muted">내 보유</span>
            <span className="strong">{myQty}주 · 평균 {fmt(myAvg)}P</span>
          </div>
          <div className="row-between top-gap">
            <span className="muted">평가손익</span>
            <span className={tone(m.last - myAvg)}>
              {signed((m.last - myAvg) * myQty)}P ({pct(m.last / myAvg - 1)})
            </span>
          </div>
        </section>
      )}

      <section className="card stats">
        <div><span className="muted">고가</span>{fmt(m.high)}</div>
        <div><span className="muted">저가</span>{fmt(m.low)}</div>
        <div><span className="muted">거래량</span>{fmt(m.volume)}</div>
        <div><span className="muted">상한가</span><span className="up">{fmt(priceBand(team.basePrice).upper)}</span></div>
        <div><span className="muted">하한가</span><span className="down">{fmt(priceBand(team.basePrice).lower)}</span></div>
        <div><span className="muted">기준가</span>{fmt(team.basePrice)}</div>
      </section>

      <div className="tabs">
        {([
          ['book', '호가'],
          ['trades', '체결'],
          ['orders', `내 주문${myOrders.length ? ` ${myOrders.length}` : ''}`],
          ['talk', '토론'],
        ] as [Tab, string][]).map(([v, l]) => (
          <button key={v} className={tab === v ? 'on' : ''} onClick={() => setTab(v)}>{l}</button>
        ))}
      </div>

      {tab === 'book' && <OrderBookView teamId={teamId} onPick={(price, side) => setSheet({ side, price })} />}
      {tab === 'trades' && <TradesView teamId={teamId} />}
      {tab === 'orders' && (
        <section className="card">
          {myOrders.length === 0 && <p className="empty">대기 중인 주문이 없어요</p>}
          {myOrders.map((o) => (
            <div key={o.id} className="order-row">
              <span className={o.side === 'buy' ? 'up' : 'down'}>{o.side === 'buy' ? '구매' : '판매'}</span>
              <span>{fmt(o.price)}P · {o.remaining}/{o.qty}주</span>
              <button className="chip-btn" onClick={() => { cancel(ME, teamId, o.id); showToast('주문을 취소했어요') }}>취소</button>
            </div>
          ))}
        </section>
      )}
      {tab === 'talk' && <TalkView teamId={teamId} />}

      <div className="cta">
        <button className="btn sell" onClick={() => setSheet({ side: 'sell', price: m.last })}>판매하기</button>
        <button className="btn buy" onClick={() => setSheet({ side: 'buy', price: m.last })}>구매하기</button>
      </div>

      {sheet && <OrderSheet teamId={teamId} side={sheet.side} initialPrice={sheet.price} onClose={() => setSheet(null)} />}
    </>
  )
}

function OrderBookView({ teamId, onPick }: { teamId: string; onPick: (price: number, side: Side) => void }) {
  const s = useStore()
  const book = s.books[teamId]
  const last = s.markets[teamId].last
  const base = teamById(teamId).basePrice
  const asks = levels(book.asks, 8).reverse()
  const bids = levels(book.bids, 8)
  const maxQty = Math.max(1, ...asks.map((l) => l.qty), ...bids.map((l) => l.qty))
  const mine = new Set(openOrders(ME).filter((o) => o.teamId === teamId).map((o) => o.price))
  const askTotal = book.asks.reduce((n, o) => n + o.remaining, 0)
  const bidTotal = book.bids.reduce((n, o) => n + o.remaining, 0)

  const row = (price: number, qty: number, side: 'ask' | 'bid') => (
    <button
      key={`${side}${price}`}
      className={`ob-row ${side} ${price === last ? 'current' : ''}`}
      onClick={() => onPick(price, side === 'ask' ? 'buy' : 'sell')}
    >
      <span className="ob-qty">
        {side === 'ask' && <><i style={{ width: `${(qty / maxQty) * 100}%` }} />{fmt(qty)}</>}
      </span>
      <span className={`ob-price ${tone(price - base)}`}>
        {fmt(price)}
        <small>{pct(price / base - 1)}</small>
        {mine.has(price) && <em>내 주문</em>}
      </span>
      <span className="ob-qty">
        {side === 'bid' && <><i style={{ width: `${(qty / maxQty) * 100}%` }} />{fmt(qty)}</>}
      </span>
    </button>
  )

  return (
    <section className="card orderbook">
      {asks.map((l) => row(l.price, l.qty, 'ask'))}
      {bids.map((l) => row(l.price, l.qty, 'bid'))}
      <div className="ob-total">
        <span>판매대기 {fmt(askTotal)}</span>
        <span>구매대기 {fmt(bidTotal)}</span>
      </div>
      <p className="hint">호가를 누르면 그 가격으로 주문할 수 있어요. 상대방은 익명으로 표시돼요.</p>
    </section>
  )
}

function TradesView({ teamId }: { teamId: string }) {
  const s = useStore()
  const trades = s.trades.filter((t) => t.teamId === teamId).slice(0, 30)
  return (
    <section className="card">
      {trades.length === 0 && <p className="empty">아직 체결이 없어요</p>}
      <div className="trade-head"><span>시간</span><span>체결가</span><span>수량</span></div>
      {trades.map((t) => (
        <div key={t.id} className="trade-row">
          <span className="muted">{new Date(t.ts).toLocaleTimeString('ko-KR', { hour12: false })}</span>
          <span>{fmt(t.price)}</span>
          <span className={t.aggressor === 'buy' ? 'up' : 'down'}>
            {t.qty}{(t.buyerId === ME || t.sellerId === ME) && ' · 나'}
          </span>
        </div>
      ))}
    </section>
  )
}

function TalkView({ teamId }: { teamId: string }) {
  const s = useStore()
  const [body, setBody] = useState('')
  const posts = s.posts.filter((p) => p.teamId === teamId)
  const myQty = totalShares(ME, teamId)
  const submit = () => {
    if (!body.trim()) return
    addPost(teamId, body.trim())
    setBody('')
  }
  return (
    <section className="card">
      <div className="talk-input">
        <input value={body} maxLength={200} placeholder="이 구단에 대한 생각을 남겨주세요" onChange={(e) => setBody(e.target.value)} onKeyDown={(e) => e.key === 'Enter' && submit()} />
        <button className="chip-btn" onClick={submit}>등록</button>
      </div>
      {posts.length === 0 && <p className="empty">첫 글을 남겨보세요</p>}
      {posts.map((p) => (
        <div key={p.id} className="post">
          <div className="row-gap">
            <span className="strong">{p.author}</span>
            {p.userId === ME && myQty > 0 && <span className="badge">{myQty}주 보유</span>}
            <span className="muted xs">{timeAgo(p.ts)}</span>
          </div>
          <p>{p.body}</p>
        </div>
      ))}
    </section>
  )
}

function OrderSheet({ teamId, side, initialPrice, onClose }: { teamId: string; side: Side; initialPrice: number; onClose: () => void }) {
  const s = useStore()
  const team = teamById(teamId)
  const { upper, lower } = priceBand(team.basePrice)
  const [type, setType] = useState<OrderType>('limit')
  const [price, setPrice] = useState(initialPrice)
  const [qtyText, setQtyText] = useState('')
  const qty = Number(qtyText) || 0
  const me = s.users[ME]
  const unitPrice = type === 'limit' ? price : side === 'buy' ? upper : s.markets[teamId].last
  const maxQty = side === 'buy' ? Math.floor(me.points / (type === 'limit' ? price : upper)) : me.holdings[teamId]?.qty ?? 0
  const label = side === 'buy' ? '구매' : '판매'

  const submit = () => {
    const r = placeOrder(ME, teamId, side, type, price, qty)
    showToast(r.message)
    if (r.ok) onClose()
  }

  return (
    <div className="sheet-backdrop" onClick={onClose}>
      <div className="sheet" onClick={(e) => e.stopPropagation()}>
        <div className="sheet-grip" />
        <h3>{team.name} {label}하기</h3>
        <Segmented
          value={type}
          onChange={setType}
          options={[
            { value: 'limit', label: '지정가' },
            { value: 'market', label: '시장가' },
          ]}
        />
        <label className="field">
          <span className="muted">가격</span>
          {type === 'limit' ? (
            <div className="stepper">
              <button onClick={() => setPrice((p) => Math.max(lower, stepPrice(p, -1)))}>−</button>
              <input
                inputMode="numeric"
                value={price}
                onChange={(e) => setPrice(Number(e.target.value.replace(/\D/g, '')) || 0)}
              />
              <button onClick={() => setPrice((p) => Math.min(upper, stepPrice(p, 1)))}>+</button>
            </div>
          ) : (
            <div className="readonly">가장 유리한 호가부터 바로 체결</div>
          )}
          <span className="muted xs">하한 {fmt(lower)} · 상한 {fmt(upper)} · 호가단위 {tickSize(price)}</span>
        </label>
        <label className="field">
          <span className="muted">수량</span>
          <input className="qty-input" inputMode="numeric" placeholder="몇 주를 주문할까요?" value={qtyText} onChange={(e) => setQtyText(e.target.value.replace(/\D/g, ''))} />
          <div className="chips">
            {[0.1, 0.25, 0.5, 1].map((r) => (
              <button key={r} className="chip" onClick={() => setQtyText(String(Math.floor(maxQty * r)))}>
                {r === 1 ? '최대' : `${r * 100}%`}
              </button>
            ))}
          </div>
          <span className="muted xs">
            {side === 'buy' ? `주문 가능 ${fmt(me.points)}P · 최대 ${fmt(maxQty)}주` : `판매 가능 ${fmt(maxQty)}주`}
          </span>
        </label>
        <div className="row-between total">
          <span className="muted">{type === 'market' && side === 'buy' ? '최대 예상 금액' : '예상 금액'}</span>
          <span className="strong">{fmt(unitPrice * qty)}P</span>
        </div>
        <button className={`btn wide ${side}`} disabled={qty <= 0} onClick={submit}>{label}하기</button>
      </div>
    </div>
  )
}
