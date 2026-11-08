import { useEffect, useState } from 'react'
import { teamById } from '../data/teams'
import { levels, priceBand, stepPrice, tickSize, type OrderType, type Side } from '../engine/orderbook'
import { MAINTENANCE_MESSAGE, useMaintenance } from '../maintenance'
import { ME, cancel, getOfferings, openOrders, placeOrder, showToast, totalShares, useStore, watchTeam } from '../store'
import { OfferingCard } from '../components/Offering'
import { NewsNoticesView, TeamInfoView } from '../components/TeamInfo'
import { WatchStar } from '../components/WatchStar'
import { Logo, fmt, pct, signed, tone } from '../components/ui'
import { TalkView, TradesView } from '../pages/TeamDetail'
import { PriceChart } from './PriceChart'
import { rememberTeam } from './SearchPalette'
import { useFlash } from './useDesktop'

type Period = '1d' | '1w' | '1m' | '3m'
type Lower = 'trades' | 'orders'
type View = 'trade' | 'info' | 'notice' | 'talk'
const VIEWS: [View, string][] = [['trade', '차트 · 호가'], ['info', '구단정보'], ['notice', '뉴스 · 공시'], ['talk', '토론']]
const PERIODS: [Period, string][] = [['1d', '1일'], ['1w', '1주'], ['1m', '1달'], ['3m', '3달']]
const DAY = 86_400_000

/** PC 구단 화면: 차트 · 호가 · 주문을 한 화면에 함께 둔다(토스증권 웹 주문 화면 배치) */
export default function DesktopTeam({ teamId }: { teamId: string }) {
  const s = useStore()
  const team = teamById(teamId)
  const m = s.markets[teamId]
  const [view, setView] = useState<View>('trade')
  const [period, setPeriod] = useState<Period>('1d')
  const [lower, setLower] = useState<Lower>('trades')
  const [side, setSide] = useState<Side>('buy')
  const [price, setPrice] = useState(m.last)
  useEffect(() => watchTeam(teamId), [teamId])
  useEffect(() => rememberTeam(teamId), [teamId])

  const { upper, lower: floor } = priceBand(m.base)
  const snap = (p: number) => {
    const t = tickSize(p)
    return Math.min(upper, Math.max(floor, Math.round(p / t) * t))
  }
  const pick = (p: number, sd: Side) => { setPrice(snap(p)); setSide(sd) }

  const diff = m.last - m.base
  const flash = useFlash(m.last)
  const points = period === '1d'
    ? m.intraday
    : [...m.daily.slice(period === '1w' ? -7 : period === '1m' ? -30 : -90), m.last].map((price, i, a) => ({ price, ts: Date.now() - (a.length - 1 - i) * DAY }))
  const myQty = totalShares(ME, teamId)
  const myAvg = s.users[ME].holdings[teamId]?.avgPrice ?? 0
  const myOrders = openOrders(ME).filter((o) => o.teamId === teamId)
  const offering = getOfferings()?.find((o) => o.teamId === teamId && o.status !== 'CANCELED')
  const range = m.high - m.low
  const pos = range > 0 ? (m.last - m.low) / range : 0.5

  return (
    <div className="dt">
      <header className="dt-head">
        <div className="dt-title">
          <Logo team={team} size={40} />
          <div>
            <div className="row-gap"><h1>{team.name}</h1><span className="badge">{team.league}</span><WatchStar team={team} /></div>
            <div className="dt-price">
              <b key={flash.key} className={`cell-flash ${flash.className}`}>{fmt(m.last)}P</b>
              {m.listed
                ? <span className={tone(diff)}>어제보다 {signed(diff)}P ({pct(diff / m.base)})</span>
                : <span className="muted">{m.indicative ? `상장 전 동시호가 · 예상 체결가 ${fmt(m.indicative.price)}P (${fmt(m.indicative.volume)}주)` : '상장 전이에요. 공모 청약과 상장 동시호가를 거쳐 거래가 시작돼요'}</span>}
            </div>
          </div>
        </div>
        <dl className="dt-stats">
          <div className="dt-range">
            <dt>오늘 범위</dt>
            <dd>
              <span className="down">{fmt(m.low)}</span>
              <span className="range-bar"><i style={{ left: `${pos * 100}%` }} className={tone(diff)} /></span>
              <span className="up">{fmt(m.high)}</span>
            </dd>
          </div>
          <div><dt>상한가</dt><dd className="up">{fmt(upper)}</dd></div>
          <div><dt>하한가</dt><dd className="down">{fmt(floor)}</dd></div>
          <div><dt>기준가</dt><dd>{fmt(m.base)}</dd></div>
          <div><dt>거래량</dt><dd>{fmt(m.volume)}주</dd></div>
          {myQty > 0 && <div><dt>내 보유 · 평가손익</dt><dd>{myQty}주 · <span className={tone(m.last - myAvg)}>{signed((m.last - myAvg) * myQty)}P</span></dd></div>}
        </dl>
      </header>

      {m.listed && m.viUntil && (
        <p className="notice vi-notice" aria-live="polite">
          가격이 크게 움직여 변동성 완화장치(VI)가 발동했어요.{' '}
          {new Date(m.viUntil).toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false })}까지 지정가 주문을 모아 한 가격에 체결해요
          {m.indicative && ` · 예상 체결가 ${fmt(m.indicative.price)}P (${fmt(m.indicative.volume)}주)`}
        </p>
      )}

      <nav className="dt-tabs" role="tablist">
        {VIEWS.map(([v, l]) => (
          <button key={v} role="tab" aria-selected={view === v} className={view === v ? 'on' : ''} onClick={() => setView(v)}>{l}</button>
        ))}
      </nav>

      {offering && <OfferingCard offering={offering} />}

      {view === 'talk' ? (
        <div className="dt-talk"><TalkView teamId={teamId} /></div>
      ) : view === 'info' ? (
        <div className="dt-info"><TeamInfoView teamId={teamId} /></div>
      ) : view === 'notice' ? (
        <div className="dt-talk"><NewsNoticesView teamId={teamId} /></div>
      ) : (
        <div className="dt-grid">
          <section className="panel dt-chart">
            <div className="panel-head">
              <h2>차트</h2>
              <div className="pill-tabs small-tabs">
                {PERIODS.map(([v, l]) => <button key={v} className={period === v ? 'on' : ''} onClick={() => setPeriod(v)}>{l}</button>)}
              </div>
            </div>
            <PriceChart points={points} base={period === '1d' ? m.base : undefined} height={340} snap={snap} onPick={pick} />
          </section>

          <section className="panel dt-lower">
            <div className="panel-head">
              <div className="pill-tabs small-tabs" role="tablist">
                <button role="tab" aria-selected={lower === 'trades'} className={lower === 'trades' ? 'on' : ''} onClick={() => setLower('trades')}>체결</button>
                <button role="tab" aria-selected={lower === 'orders'} className={lower === 'orders' ? 'on' : ''} onClick={() => setLower('orders')}>내 주문{myOrders.length ? ` ${myOrders.length}` : ''}</button>
              </div>
            </div>
            {lower === 'trades' ? <TradesView teamId={teamId} /> : (
              <div className="dt-orders">
                {myOrders.length === 0 && <p className="empty">대기 중인 주문이 없어요</p>}
                {myOrders.map((o) => (
                  <div key={o.id} className="order-row">
                    <span className={o.side === 'buy' ? 'up' : 'down'}>{o.side === 'buy' ? '구매' : '판매'}</span>
                    <span>{fmt(o.price)}P · {o.remaining}/{o.qty}주</span>
                    <button className="chip-btn" onClick={() => cancel(ME, teamId, o.id).then(() => showToast('주문을 취소했어요'))}>취소</button>
                  </div>
                ))}
              </div>
            )}
          </section>

          <BookPanel teamId={teamId} onPick={pick} />
          <OrderPanel teamId={teamId} side={side} setSide={setSide} price={price} setPrice={setPrice} />
        </div>
      )}
    </div>
  )
}

function BookPanel({ teamId, onPick }: { teamId: string; onPick: (price: number, side: Side) => void }) {
  const s = useStore()
  const book = s.books[teamId]
  const { last, base } = s.markets[teamId]
  const asks = levels(book.asks, 10).reverse()
  const bids = levels(book.bids, 10)
  const maxQty = Math.max(1, ...asks.map((l) => l.qty), ...bids.map((l) => l.qty))
  const mine = new Set(openOrders(ME).filter((o) => o.teamId === teamId).map((o) => o.price))
  const askTotal = book.asks.reduce((n, o) => n + o.remaining, 0)
  const bidTotal = book.bids.reduce((n, o) => n + o.remaining, 0)

  const row = (price: number, qty: number, sd: 'ask' | 'bid') => (
    <button
      key={`${sd}${price}`}
      className={`dob-row ${sd} ${price === last ? 'current' : ''}`}
      onClick={() => onPick(price, sd === 'ask' ? 'buy' : 'sell')}
      title={`${fmt(price)}P에 ${sd === 'ask' ? '구매' : '판매'} 주문 채우기`}
    >
      <span className="dob-qty">{sd === 'ask' && <><i style={{ width: `${(qty / maxQty) * 100}%` }} />{fmt(qty)}</>}</span>
      <span className={`dob-price ${tone(price - base)}`}>
        {fmt(price)}<small>{pct(price / base - 1)}</small>
        {mine.has(price) && <em>내 주문</em>}
      </span>
      <span className="dob-qty">{sd === 'bid' && <><i style={{ width: `${(qty / maxQty) * 100}%` }} />{fmt(qty)}</>}</span>
    </button>
  )

  return (
    <section className="panel dt-book" aria-label="호가">
      <div className="panel-head"><h2>호가</h2></div>
      <div className="dob-cols xs muted"><span>판매대기</span><span>가격</span><span>구매대기</span></div>
      <div className="dob">
        {asks.length === 0 && bids.length === 0 && <p className="empty">아직 호가가 없어요</p>}
        {asks.map((l) => row(l.price, l.qty, 'ask'))}
        {bids.map((l) => row(l.price, l.qty, 'bid'))}
      </div>
      <div className="dob-total xs"><span>{fmt(askTotal)}</span><span className="muted">잔량 합계</span><span>{fmt(bidTotal)}</span></div>
    </section>
  )
}

function OrderPanel({ teamId, side, setSide, price, setPrice }: {
  teamId: string
  side: Side
  setSide: (s: Side) => void
  price: number
  setPrice: (p: number | ((p: number) => number)) => void
}) {
  const s = useStore()
  const m = s.markets[teamId]
  const { upper, lower } = priceBand(m.base)
  const [type, setType] = useState<OrderType>('limit')
  const [qtyText, setQtyText] = useState('')
  const [busy, setBusy] = useState(false)
  const qty = Number(qtyText) || 0
  const me = s.users[ME]
  const unitPrice = type === 'limit' ? price : side === 'buy' ? upper : m.last
  const maxQty = side === 'buy' ? Math.floor(me.points / Math.max(1, type === 'limit' ? price : upper)) : me.holdings[teamId]?.qty ?? 0
  const label = side === 'buy' ? '구매' : '판매'
  const maintenance = useMaintenance()

  const submit = async () => {
    setBusy(true)
    const r = await placeOrder(ME, teamId, side, type, price, qty)
    setBusy(false)
    showToast(r.message)
    if (r.ok) setQtyText('')
  }

  return (
    <section className={`panel dt-order ${side}`} aria-label="주문">
      <div className="order-side" role="tablist">
        <button role="tab" aria-selected={side === 'buy'} className={side === 'buy' ? 'on buy' : ''} onClick={() => setSide('buy')}>구매</button>
        <button role="tab" aria-selected={side === 'sell'} className={side === 'sell' ? 'on sell' : ''} onClick={() => setSide('sell')}>판매</button>
      </div>

      <div className="of-row">
        <span className="of-label">주문 방식</span>
        <div className="segmented">
          <button className={type === 'limit' ? 'on' : ''} onClick={() => setType('limit')}>지정가</button>
          <button className={type === 'market' ? 'on' : ''} onClick={() => setType('market')}>시장가</button>
        </div>
      </div>

      <div className="of-row">
        <label className="of-label" htmlFor="of-price">{label} 가격</label>
        {type === 'limit' ? (
          <div className="of-input">
            <input id="of-price" inputMode="numeric" value={fmt(price)} onChange={(e) => setPrice(Number(e.target.value.replace(/\D/g, '')) || 0)} />
            <span className="muted of-unit">P</span>
            <button aria-label="한 호가 내리기" onClick={() => setPrice((p) => Math.max(lower, stepPrice(p, -1)))}>−</button>
            <button aria-label="한 호가 올리기" onClick={() => setPrice((p) => Math.min(upper, stepPrice(p, 1)))}>+</button>
          </div>
        ) : <div className="of-input readonly">가장 유리한 호가부터 바로 체결</div>}
      </div>

      <div className="of-row">
        <label className="of-label" htmlFor="of-qty">수량</label>
        <div className="of-input">
          <input id="of-qty" inputMode="numeric" placeholder={`최대 ${fmt(maxQty)}`} value={qtyText} onChange={(e) => setQtyText(e.target.value.replace(/\D/g, ''))} />
          <span className="muted of-unit">주</span>
          <button aria-label="1주 빼기" onClick={() => setQtyText(String(Math.max(0, qty - 1)))}>−</button>
          <button aria-label="1주 더하기" onClick={() => setQtyText(String(qty + 1))}>+</button>
        </div>
      </div>
      <div className="of-pcts">
        {[0.1, 0.25, 0.5, 1].map((r) => (
          <button key={r} onClick={() => setQtyText(String(Math.floor(maxQty * r)))}>{r === 1 ? '최대' : `${r * 100}%`}</button>
        ))}
      </div>

      <div className="of-total">
        <span className="muted">{type === 'market' && side === 'buy' ? '최대 예상 금액' : '예상 금액'}</span>
        <b>{fmt(unitPrice * qty)}P</b>
      </div>
      {maintenance && <p className="notice" aria-live="polite">{MAINTENANCE_MESSAGE}</p>}
      <button className={`btn wide ${side}`} disabled={qty <= 0 || busy || maintenance} onClick={submit}>{qty > 0 ? `${fmt(qty)}주 ${label}하기` : `${label}하기`}</button>
    </section>
  )
}
