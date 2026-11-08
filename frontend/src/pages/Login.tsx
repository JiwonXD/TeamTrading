import { type FormEvent, useState } from 'react'
import { checkLoginId, confirmPasswordReset, devLogin, passwordLogin, requestPasswordReset, signUp, type LoginOptions } from '../store'
import { Header } from '../components/ui'
import { HelpTip } from '../components/HelpTip'
import { ProviderIcon } from '../components/ProviderIcon'

type Mode = 'login' | 'signup' | 'find' | 'reset'

// 소셜 버튼 순서. 서버가 모르는 로그인 방법이 오면 기본 모양으로 뒤에 붙인다
const ORDER = ['google', 'kakao']
const FEATURES = [
  { label: '가입 100만P', icon: 'M12 3v18M17 7.5c0-1.9-2.2-3-5-3s-5 1.1-5 3 2.2 2.7 5 3.5 5 1.6 5 3.5-2.2 3-5 3-5-1.1-5-3' },
  { label: '실시간 호가', icon: 'M13 2L4 14h7l-1 8 9-12h-7z' },
  { label: '경기 배당', icon: 'M7 4h10v4a5 5 0 01-10 0zM5 5H3v2a3 3 0 003 3M19 5h2v2a3 3 0 01-3 3M12 13v4M8 21h8M9 17h6' },
  { label: '수익률 랭킹', icon: 'M4 20V10M10 20V4M16 20v-7M2 20h20' },
]

// 서버 모드에서 로그인하지 않았을 때 보이는 화면. 아이디·비밀번호 로그인과 가입, 비밀번호 찾기(메일 발송이 설정된 서버만),
// 소셜 로그인(서버의 /oauth2/authorization/{id}로 넘어간다)이 있다. 메일의 재설정 링크는 #reset-{토큰}으로 들어온다
export default function Login({ options }: { options: LoginOptions }) {
  const resetToken = location.hash.startsWith('#reset-') ? location.hash.slice('#reset-'.length) : ''
  const [mode, setMode] = useState<Mode>(resetToken ? 'reset' : 'login')
  const [nickname, setNickname] = useState('')
  const [loginId, setLoginId] = useState('')
  const [password, setPassword] = useState('')
  const [password2, setPassword2] = useState('')
  const [email, setEmail] = useState('')
  const [idCheck, setIdCheck] = useState<{ available: boolean; message: string } | null>(null)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [busy, setBusy] = useState(false)
  const failed = location.hash === '#login-failed'
  const blocked = location.hash === '#signup-blocked'
  const rank = (id: string) => (ORDER.includes(id) ? ORDER.indexOf(id) : ORDER.length)
  const providers = [...options.providers].sort((a, b) => rank(a.id) - rank(b.id))

  const go = (next: Mode) => {
    setMode(next)
    setPassword('')
    setPassword2('')
    setIdCheck(null)
    setError('')
    setNotice('')
  }

  const run = async (e: FormEvent, work: () => Promise<string | null>, done?: () => void) => {
    e.preventDefault()
    if (busy) return
    setBusy(true)
    setError('')
    const err = await work()
    setBusy(false)
    if (err) setError(err)
    else done?.()
  }

  const submitDev = async () => {
    if (!nickname.trim()) return
    setError((await devLogin(nickname.trim())) ?? '')
  }

  const mismatch = password2 !== '' && password !== password2

  if (mode === 'signup') return (
    <div className="login-page">
      <Header title="회원가입" onBack={() => go('login')} />
      <section className="login login-step">
        <form className="login-form" onSubmit={(e) => run(e, () => signUp(loginId.trim(), password, email.trim()))}>
          <input className="login-input" aria-label="아이디" placeholder="아이디 (영문·숫자 4~20자)" autoComplete="username"
            maxLength={20} autoCapitalize="none" value={loginId}
            onChange={(e) => { setLoginId(e.target.value); setIdCheck(null) }}
            onBlur={async () => setIdCheck(loginId.trim() ? await checkLoginId(loginId.trim()) : null)} />
          {idCheck && <p className={`login-hint ${idCheck.available ? 'ok' : 'bad'}`}>{idCheck.message}</p>}
          <input className="login-input" type="password" aria-label="비밀번호" placeholder="비밀번호 (영문·숫자 8자 이상)"
            autoComplete="new-password" maxLength={64} value={password} onChange={(e) => setPassword(e.target.value)} />
          <input className="login-input" type="password" aria-label="비밀번호 확인" placeholder="비밀번호 확인"
            autoComplete="new-password" maxLength={64} value={password2} onChange={(e) => setPassword2(e.target.value)} />
          {mismatch && <p className="login-hint bad">비밀번호가 서로 달라요</p>}
          <div className="login-email">
            <input className="login-input" type="email" aria-label="이메일 (선택)" placeholder="이메일 (선택)" autoComplete="email"
              maxLength={254} value={email} onChange={(e) => setEmail(e.target.value)} />
            <HelpTip label="이메일을 넣으면">비밀번호를 잊었을 때 재설정 링크를 받을 주소예요. 넣지 않으면 비밀번호를 찾을 수 없어요.</HelpTip>
          </div>
          <button className="btn primary" disabled={busy || !loginId.trim() || !password || password !== password2}>가입하기</button>
        </form>
        {error && <p className="login-error" role="alert">{error}</p>}
        <p className="muted xs login-consent">가입하면 이용약관과 개인정보처리방침에 동의하는 것으로 봐요.</p>
      </section>
    </div>
  )

  if (mode === 'find') return (
    <div className="login-page">
      <Header title="비밀번호 찾기" onBack={() => go('login')} />
      <section className="login login-step">
        <form className="login-form" onSubmit={(e) => run(e, () => requestPasswordReset(loginId.trim(), email.trim()),
          () => setNotice('입력한 정보가 맞으면 재설정 메일을 보냈어요. 메일함을 확인해 주세요.'))}>
          <input className="login-input" aria-label="아이디" placeholder="아이디" autoComplete="username" autoCapitalize="none"
            maxLength={20} value={loginId} onChange={(e) => setLoginId(e.target.value)} />
          <input className="login-input" type="email" aria-label="가입할 때 넣은 이메일" placeholder="가입할 때 넣은 이메일"
            autoComplete="email" maxLength={254} value={email} onChange={(e) => setEmail(e.target.value)} />
          <button className="btn primary" disabled={busy || !loginId.trim() || !email.trim()}>재설정 메일 받기</button>
        </form>
        {notice && <p className="login-notice" role="status">{notice}</p>}
        {error && <p className="login-error" role="alert">{error}</p>}
      </section>
    </div>
  )

  if (mode === 'reset') return (
    <div className="login-page">
      <Header title="새 비밀번호" onBack={() => { history.replaceState(null, '', location.pathname); go('login') }} />
      <section className="login login-step">
        <form className="login-form" onSubmit={(e) => run(e, () => confirmPasswordReset(resetToken, password), () => {
          history.replaceState(null, '', location.pathname)
          go('login')
          setNotice('비밀번호를 바꿨어요. 새 비밀번호로 로그인해 주세요.')
        })}>
          <input className="login-input" type="password" aria-label="새 비밀번호" placeholder="새 비밀번호 (영문·숫자 8자 이상)"
            autoComplete="new-password" maxLength={64} value={password} onChange={(e) => setPassword(e.target.value)} />
          <input className="login-input" type="password" aria-label="새 비밀번호 확인" placeholder="새 비밀번호 확인"
            autoComplete="new-password" maxLength={64} value={password2} onChange={(e) => setPassword2(e.target.value)} />
          {mismatch && <p className="login-hint bad">비밀번호가 서로 달라요</p>}
          <button className="btn primary" disabled={busy || !password || password !== password2}>비밀번호 바꾸기</button>
        </form>
        {error && <p className="login-error" role="alert">{error}</p>}
      </section>
    </div>
  )

  return (
    <div className="login-page">
      <Header />
      <section className="login">
        <h2 className="login-title">팬차트</h2>
        <p className="login-sub">스포츠 구단을 사고파는 모의 거래소</p>

        {failed && <p className="login-error" role="alert">로그인하지 못했어요. 다시 시도해 주세요.</p>}
        {blocked && <p className="login-error" role="alert">탈퇴하고 30일이 지나야 다시 가입할 수 있어요.</p>}
        {notice && <p className="login-notice" role="status">{notice}</p>}

        <form className="login-form" onSubmit={(e) => run(e, () => passwordLogin(loginId.trim(), password))}>
          <input className="login-input" aria-label="아이디" placeholder="아이디" autoComplete="username" autoCapitalize="none"
            maxLength={20} value={loginId} onChange={(e) => setLoginId(e.target.value)} />
          <input className="login-input" type="password" aria-label="비밀번호" placeholder="비밀번호" autoComplete="current-password"
            maxLength={64} value={password} onChange={(e) => setPassword(e.target.value)} />
          <button className="btn primary" disabled={busy || !loginId.trim() || !password}>로그인</button>
        </form>
        {error && <p className="login-error" role="alert">{error}</p>}
        <div className="login-links">
          <button type="button" onClick={() => go('signup')}>회원가입</button>
          {options.passwordReset && <button type="button" onClick={() => go('find')}>비밀번호 찾기</button>}
        </div>

        {providers.length > 0 && <div className="login-or">또는</div>}

        {providers.length > 0 && (
          <div className="login-providers">
            {providers.map((p) => (
              <a key={p.id} className={`login-provider ${p.id}`} href={p.loginUrl}>
                <ProviderIcon id={p.id} />
                {p.name} 로그인
              </a>
            ))}
          </div>
        )}

        {options.devLogin && <div className="login-or">또는</div>}

        {options.devLogin && (
          <div className="login-guest">
            <input className="login-input" aria-label="닉네임" maxLength={20} placeholder="닉네임" value={nickname}
              onChange={(e) => setNickname(e.target.value)} onKeyDown={(e) => e.key === 'Enter' && submitDev()} />
            <button className="btn primary" disabled={!nickname.trim()} onClick={submitDev}>들어가기</button>
            <p className="muted xs">시연 서버에서는 닉네임만으로 시작할 수 있어요.</p>
          </div>
        )}

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
