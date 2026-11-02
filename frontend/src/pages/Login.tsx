import { useState } from 'react'
import { devLogin, type LoginOptions } from '../store'
import { Header } from '../components/ui'

// 서버 모드에서 로그인하지 않았을 때 보이는 화면. 소셜 로그인은 서버의 /oauth2/authorization/{id}로 넘어간다
export default function Login({ options }: { options: LoginOptions }) {
  const [nickname, setNickname] = useState('')
  const [error, setError] = useState('')
  const failed = location.hash === '#login-failed'

  const submit = async () => {
    if (!nickname.trim()) return
    setError((await devLogin(nickname.trim())) ?? '')
  }

  return (
    <>
      <Header />
      <section className="card login">
        <h2>스포츠 구단을 사고파는 모의 거래소</h2>
        <p className="muted small">
          가입하면 1,000,000P를 드려요. 포인트는 현금으로 바꾸거나 다른 사람에게 보낼 수 없어요.
        </p>
        {failed && <p className="login-error" role="alert">로그인하지 못했어요. 다시 시도해 주세요.</p>}
        {options.providers.map((p) => (
          <a key={p.id} className="login-provider" href={p.loginUrl}>{p.name}로 계속하기</a>
        ))}
        {options.devLogin && (
          <div className="field">
            <label htmlFor="dev-nickname" className="muted xs">개발용 로그인(시연 서버 전용)</label>
            <input id="dev-nickname" className="qty-input" maxLength={20} placeholder="닉네임" value={nickname}
              onChange={(e) => setNickname(e.target.value)} onKeyDown={(e) => e.key === 'Enter' && submit()} />
            <button className="btn primary wide" disabled={!nickname.trim()} onClick={submit}>들어가기</button>
          </div>
        )}
        {error && <p className="login-error" role="alert">{error}</p>}
        {options.providers.length === 0 && !options.devLogin && <p className="empty">지금은 로그인할 수 없어요.</p>}
      </section>
    </>
  )
}
