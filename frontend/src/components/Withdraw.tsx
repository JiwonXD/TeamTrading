import { useState } from 'react'
import { showToast, withdraw } from '../store'

/** 탈퇴(서버 모드). 되돌릴 수 없으므로 시트에서 한 번 더 확인한다 */
export default function Withdraw() {
  const [open, setOpen] = useState(false)
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)

  const confirm = async () => {
    setBusy(true)
    const r = await withdraw()
    setBusy(false)
    if (!r.ok) return setError(r.message)
    setOpen(false)
    showToast(r.message)
  }

  return (
    <>
      <button className="text-btn" onClick={() => { setError(''); setOpen(true) }}>탈퇴하기</button>
      {open && (
        <div className="sheet-backdrop">
          <div className="sheet" role="dialog" aria-modal="true" aria-labelledby="withdraw-title">
            <div className="sheet-grip" />
            <h2 id="withdraw-title">정말 탈퇴할까요?</h2>
            <ul className="muted small withdraw-notes">
              <li>대기 중인 주문은 모두 취소돼요.</li>
              <li>남은 포인트와 구단 주식은 되돌려 받을 수 없어요.</li>
              <li>같은 계정으로는 30일이 지나야 다시 가입할 수 있어요.</li>
            </ul>
            {error && <p className="login-error" role="alert">{error}</p>}
            <button className="btn wide danger" disabled={busy} onClick={confirm}>탈퇴할게요</button>
            <button className="btn wide plain" onClick={() => setOpen(false)}>취소</button>
          </div>
        </div>
      )}
    </>
  )
}
