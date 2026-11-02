import { useEffect, useState } from 'react'
import { finishDemoAd, getAdStatus, openAd, showToast, useStore } from '../store'
import { fmt } from './ui'

/** 서버는 세션을 연 뒤 15초가 지나야 지급한다. 화면 시계는 응답을 받은 뒤부터 세므로 1초 여유를 둔다 */
const DEMO_SECONDS = 16

/**
 * 광고 보고 포인트 받기. 실제 광고는 앱(광고 SDK)에서만 볼 수 있어 웹에서는 시연 서버의 가짜 광고만 보여준다.
 * 포인트는 화면이 아니라 서버가 확인한 뒤에 들어온다.
 */
export default function AdReward() {
  useStore()
  const status = getAdStatus()
  const [session, setSession] = useState<string | null>(null)
  const [left, setLeft] = useState(DEMO_SECONDS)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    if (!session) return
    if (left <= 0) {
      finishDemoAd(session).then((r) => {
        showToast(r.message)
        setSession(null)
      })
      return
    }
    const t = setTimeout(() => setLeft((n) => n - 1), 1000)
    return () => clearTimeout(t)
  }, [session, left])

  if (!status?.demo) return null
  const remaining = status.dailyLimit - status.paidToday

  const start = async () => {
    setBusy(true)
    const r = await openAd()
    setBusy(false)
    if ('error' in r) return showToast(r.error)
    setLeft(DEMO_SECONDS)
    setSession(r.id)
  }

  return (
    <section className="card ad-card">
      <div>
        <h2>광고 보고 {fmt(status.amount)}P 받기</h2>
        <p className="muted xs">오늘 {status.paidToday}/{status.dailyLimit}회 · 시연용 광고</p>
      </div>
      <button className="chip-btn" disabled={busy || remaining <= 0} onClick={start}>
        {remaining <= 0 ? '내일 다시' : '광고 보기'}
      </button>
      {session && (
        <div className="sheet-backdrop">
          <div className="sheet ad-sheet" role="dialog" aria-modal="true" aria-labelledby="ad-title">
            <div className="sheet-grip" />
            <h2 id="ad-title">시연용 광고</h2>
            <p className="muted">실제 서비스에서는 앱에서 광고를 끝까지 보면 광고 회사가 서버에 알려 주고, 그때 포인트가 들어와요.</p>
            <div className="ad-count" aria-live="polite">{left > 0 ? `${left}초 남았어요` : '확인 중'}</div>
            <button className="btn wide primary" onClick={() => setSession(null)}>그만 보기</button>
          </div>
        </div>
      )}
    </section>
  )
}
