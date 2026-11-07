import { type ReactNode, useState } from 'react'
import { devLogin, type LoginOptions } from '../store'
import { Header } from '../components/ui'

// 소셜 버튼 순서와 로고. 서버가 모르는 로그인 방법이 오면 기본 모양으로 뒤에 붙인다
const ORDER = ['google', 'kakao']
const PROVIDER_ICON: Record<string, ReactNode> = {
  google: (
    <svg width="18" height="18" viewBox="0 0 48 48" aria-hidden="true">
      <path fill="#EA4335" d="M24 9.5c3.54 0 6.71 1.22 9.21 3.6l6.85-6.85C35.9 2.38 30.47 0 24 0 14.62 0 6.51 5.38 2.56 13.22l7.98 6.19C12.43 13.72 17.74 9.5 24 9.5z" />
      <path fill="#4285F4" d="M46.98 24.55c0-1.57-.15-3.09-.38-4.55H24v9.02h12.94c-.58 2.96-2.26 5.48-4.78 7.18l7.73 6c4.51-4.18 7.09-10.36 7.09-17.65z" />
      <path fill="#FBBC05" d="M10.53 28.59c-.48-1.45-.76-2.99-.76-4.59s.27-3.14.76-4.59l-7.98-6.19C.92 16.46 0 20.12 0 24c0 3.88.92 7.54 2.56 10.78l7.97-6.19z" />
      <path fill="#34A853" d="M24 48c6.48 0 11.93-2.13 15.89-5.81l-7.73-6c-2.15 1.45-4.92 2.3-8.16 2.3-6.26 0-11.57-4.22-13.47-9.91l-7.98 6.19C6.51 42.62 14.62 48 24 48z" />
    </svg>
  ),
  kakao: (
    <svg width="18" height="18" viewBox="0 0 24 24" aria-hidden="true">
      <path fill="#000" d="M12 3C6.48 3 2 6.48 2 10.78c0 2.77 1.86 5.2 4.66 6.58l-.95 3.48c-.08.3.26.54.52.37l4.15-2.75c.53.06 1.07.1 1.62.1 5.52 0 10-3.48 10-7.78S17.52 3 12 3z" />
    </svg>
  ),
}

const FEATURES = [
  { label: '가입 100만P', icon: 'M12 3v18M17 7.5c0-1.9-2.2-3-5-3s-5 1.1-5 3 2.2 2.7 5 3.5 5 1.6 5 3.5-2.2 3-5 3-5-1.1-5-3' },
  { label: '실시간 호가', icon: 'M13 2L4 14h7l-1 8 9-12h-7z' },
  { label: '경기 배당', icon: 'M7 4h10v4a5 5 0 01-10 0zM5 5H3v2a3 3 0 003 3M19 5h2v2a3 3 0 01-3 3M12 13v4M8 21h8M9 17h6' },
  { label: '수익률 랭킹', icon: 'M4 20V10M10 20V4M16 20v-7M2 20h20' },
]

// 서버 모드에서 로그인하지 않았을 때 보이는 화면. 소셜 로그인은 서버의 /oauth2/authorization/{id}로 넘어간다
export default function Login({ options }: { options: LoginOptions }) {
  const [nickname, setNickname] = useState('')
  const [error, setError] = useState('')
  const failed = location.hash === '#login-failed'
  const blocked = location.hash === '#signup-blocked'
  const rank = (id: string) => (ORDER.includes(id) ? ORDER.indexOf(id) : ORDER.length)
  const providers = [...options.providers].sort((a, b) => rank(a.id) - rank(b.id))

  const submit = async () => {
    if (!nickname.trim()) return
    setError((await devLogin(nickname.trim())) ?? '')
  }

  return (
    <div className="login-page">
      <Header />
      <section className="login">
        <h2 className="login-title">팬차트</h2>
        <p className="login-sub">스포츠 구단을 사고파는 모의 거래소</p>

        {failed && <p className="login-error" role="alert">로그인하지 못했어요. 다시 시도해 주세요.</p>}
        {blocked && <p className="login-error" role="alert">탈퇴하고 30일이 지나야 다시 가입할 수 있어요.</p>}

        {providers.length > 0 && (
          <div className="login-providers">
            {providers.map((p) => (
              <a key={p.id} className={`login-provider ${p.id}`} href={p.loginUrl}>
                {PROVIDER_ICON[p.id]}
                {p.name} 로그인
              </a>
            ))}
          </div>
        )}

        {providers.length > 0 && options.devLogin && <div className="login-or">또는</div>}

        {options.devLogin && (
          <div className="login-guest">
            <input className="login-input" aria-label="닉네임" maxLength={20} placeholder="닉네임" value={nickname}
              onChange={(e) => setNickname(e.target.value)} onKeyDown={(e) => e.key === 'Enter' && submit()} />
            <button className="btn primary" disabled={!nickname.trim()} onClick={submit}>들어가기</button>
            <p className="muted xs">시연 서버에서는 닉네임만으로 시작할 수 있어요.</p>
          </div>
        )}
        {error && <p className="login-error" role="alert">{error}</p>}
        {providers.length === 0 && !options.devLogin && <p className="empty">지금은 로그인할 수 없어요.</p>}

        <ul className="login-features">
          {FEATURES.map((f) => (
            <li key={f.label}>
              <svg width="16" height="16" viewBox="0 0 24 24" aria-hidden="true">
                <path d={f.icon} fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
              </svg>
              {f.label}
            </li>
          ))}
        </ul>

        <p className="muted xs login-consent">계속하면 이용약관과 개인정보처리방침에 동의하는 것으로 봐요.</p>
      </section>

      <footer className="login-footer">
        <nav className="legal-links xs" aria-label="약관">
          <a href="#terms">이용약관</a>
          <a href="#privacy" className="strong">개인정보처리방침</a>
        </nav>
        <p className="muted xs">포인트는 현금으로 바꾸거나 다른 사람에게 보낼 수 없어요.</p>
      </footer>
    </div>
  )
}
