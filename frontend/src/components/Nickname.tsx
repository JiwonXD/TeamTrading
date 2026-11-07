import { useState } from 'react'
import { changeNickname, getProfile, showToast, useStore } from '../store'

/** 닉네임 보기·바꾸기(서버 모드). 규칙은 서버가 검사하고, 화면은 서버가 알려 준 이유를 그대로 보여준다 */
export default function Nickname() {
  useStore()
  const profile = getProfile()
  const [editing, setEditing] = useState(false)
  const [value, setValue] = useState('')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)

  if (!profile) return null
  // 서버는 지금 바꿀 수 있으면 시각을 비워 보낸다
  const until = profile.changeableAt
    ? new Date(profile.changeableAt).toLocaleDateString('ko-KR', { month: 'long', day: 'numeric' })
    : null

  const open = () => {
    setValue(profile.nickname)
    setError('')
    setEditing(true)
  }

  const save = async () => {
    setBusy(true)
    const r = await changeNickname(value.trim())
    setBusy(false)
    if (!r.ok) return setError(r.message)
    setEditing(false)
    showToast(r.message)
  }

  return (
    <section className="card profile-card">
      <div>
        <span className="muted xs">닉네임</span>
        <h2>{profile.nickname}</h2>
        {until && <p className="muted xs">{until}부터 다시 바꿀 수 있어요</p>}
      </div>
      <button className="chip-btn" disabled={!!until} onClick={open}>바꾸기</button>
      {editing && (
        <div className="sheet-backdrop">
          <div className="sheet" role="dialog" aria-modal="true" aria-labelledby="nickname-title">
            <div className="sheet-grip" />
            <h2 id="nickname-title">닉네임 바꾸기</h2>
            <div className="field">
              <label htmlFor="nickname-input" className="muted xs">한글·영문·숫자·밑줄(_) 2~12자. 바꾸면 7일 동안 다시 바꿀 수 없어요</label>
              <input id="nickname-input" className="qty-input" maxLength={12} value={value} autoFocus
                onChange={(e) => setValue(e.target.value)} onKeyDown={(e) => e.key === 'Enter' && !busy && save()} />
            </div>
            {error && <p className="login-error" role="alert">{error}</p>}
            <button className="btn wide primary" disabled={busy || value.trim().length < 2} onClick={save}>바꾸기</button>
            <button className="btn wide plain" onClick={() => setEditing(false)}>취소</button>
          </div>
        </div>
      )}
    </section>
  )
}
