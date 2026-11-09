import { useState } from 'react'
import { type OfferingDto, cancelSubscription, getMySubscriptions, requireLogin, showToast, subscribeOffering, useStore } from '../store'
import { fmt, go } from './ui'

const time = (iso: string) =>
  new Date(iso).toLocaleString('ko-KR', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit', hour12: false })

/** 경쟁률. 청약 수량 ÷ 공모 수량 */
export const competition = (o: OfferingDto) => `${(o.requested / o.totalShares).toFixed(2)} : 1`

export function offeringState(o: OfferingDto, now = Date.now()) {
  if (o.status === 'CANCELED') return '공모 취소'
  if (o.status === 'ALLOCATED') return Date.parse(o.listingAt) > now ? `${time(o.listingAt)} 상장` : '배정 완료'
  if (Date.parse(o.subscribeFrom) > now) return `${time(o.subscribeFrom)} 청약 시작`
  if (Date.parse(o.subscribeTo) > now) return `${time(o.subscribeTo)} 마감`
  return '배정 중'
}

/** 청약 중이거나 상장한 지 1시간이 안 된 공모 */
const live = (offerings: OfferingDto[], now = Date.now()) =>
  offerings.filter((o) => o.status === 'SUBSCRIBING' || (o.status === 'ALLOCATED' && Date.parse(o.listingAt) > now - 3_600_000))

const isOpen = (o: OfferingDto, now = Date.now()) =>
  o.status === 'SUBSCRIBING' && Date.parse(o.subscribeFrom) <= now && now < Date.parse(o.subscribeTo)

/** 배정이 끝나고 상장 시각 전(동시호가) */
const preOpen = (o: OfferingDto, now = Date.now()) => o.status === 'ALLOCATED' && Date.parse(o.listingAt) > now

// 화면은 서버 모드 3초 갱신마다 다시 그려지므로 시각에 따른 상태도 그때 함께 바뀐다

/** 홈: 청약 중이거나 곧 상장하는 공모 */
export function OfferingList({ offerings }: { offerings: OfferingDto[] }) {
  const rows = live(offerings)
  if (rows.length === 0) return null
  return (
    <section className="card">
      <h2>공모주 청약</h2>
      {rows.map((o) => (
        <button key={o.id} className="offering-row" onClick={() => go(`team-${o.teamId}`)}>
          <span className="offering-name">
            <span className="strong">{o.teamName}</span>
            <span className="muted xs">{o.firstListing ? '신규 상장' : '추가 공모'} · {fmt(o.price)}P · {fmt(o.totalShares)}주</span>
          </span>
          <span className="offering-meta">
            <span className="strong">{competition(o)}</span>
            <span className="muted xs">{offeringState(o)}</span>
          </span>
        </button>
      ))}
    </section>
  )
}

/** 구단 화면: 청약하기, 내 청약, 배정 결과 */
export function OfferingCard({ offering: o }: { offering: OfferingDto }) {
  useStore()
  const [qty, setQty] = useState('')
  const [busy, setBusy] = useState(false)
  const mine = getMySubscriptions().find((s) => s.offeringId === o.id)
  const open = isOpen(o)
  const n = Number(qty)
  const valid = Number.isInteger(n) && n >= 1 && n <= o.maxPerAccount

  const run = async (work: () => Promise<{ ok: boolean; message: string }>) => {
    setBusy(true)
    const r = await work()
    setBusy(false)
    showToast(r.message)
    if (r.ok) setQty('')
  }

  return (
    <section className="card offering-card">
      <div className="row-between">
        <h2>{o.firstListing ? '신규 상장 공모' : '추가 공모'}</h2>
        <span className="offering-state">{offeringState(o)}</span>
      </div>
      <dl className="offering-terms">
        <div><dt>공모가</dt><dd>{fmt(o.price)}P</dd></div>
        <div><dt>공모 수량</dt><dd>{fmt(o.totalShares)}주</dd></div>
        <div><dt>1인 한도</dt><dd>{fmt(o.maxPerAccount)}주</dd></div>
        <div><dt>경쟁률</dt><dd>{competition(o)} ({fmt(o.subscribers)}명)</dd></div>
      </dl>
      {mine ? (
        <div className="row-between top-gap">
          <span>
            {mine.allocated === null
              ? `${fmt(mine.quantity)}주 청약 · ${fmt(mine.deposit)}P 묶임`
              : `${fmt(mine.quantity)}주 중 ${fmt(mine.allocated)}주 배정 · ${fmt(mine.deposit - mine.allocated * o.price)}P 환불`}
          </span>
          {open && (
            <button className="chip-btn" disabled={busy} onClick={() => run(() => cancelSubscription(o.id))}>청약 취소</button>
          )}
        </div>
      ) : open ? (
        <div className="offering-form top-gap">
          <input
            inputMode="numeric"
            aria-label="청약 수량"
            placeholder={`몇 주 청약할까요? (최대 ${o.maxPerAccount}주)`}
            value={qty}
            onChange={(e) => setQty(e.target.value.replace(/\D/g, ''))}
          />
          <button className="btn primary" disabled={!valid || busy} onClick={() => requireLogin() && run(() => subscribeOffering(o.id, n))}>
            {valid ? `${fmt(n * o.price)}P 청약` : '청약하기'}
          </button>
        </div>
      ) : null}
      {o.firstListing && preOpen(o) && (
        <p className="notice" role="status">
          상장 전 동시호가 중이에요. 지정가 주문을 모아 {time(o.listingAt)}에 한 가격으로 체결해요. 체결 수량이 가장 많은
          가격이 시초가가 되고, 매수 주문은 지정가와 시초가의 차액을 돌려받아요.
        </p>
      )}
      <p className="hint">
        청약금은 청약 수량 × 공모가를 바로 묶어요. 마감 뒤 공모 수량의 절반은 청약자 모두에게 똑같이, 나머지는 청약 수량에
        비례해 나누고 남은 청약금은 돌려드려요.{o.firstListing ? ' 첫 상장이라 청약 중에는 거래할 수 없어요.' : ''}
      </p>
    </section>
  )
}
