import { useEffect, useState } from 'react'
import { loadLoginMethods, showToast, startSocialLink, type LoginMethodsDto } from '../store'
import { ProviderIcon } from './ProviderIcon'

// 소셜 연동에서 돌아오면 서버가 /?link=결과#portfolio 로 보낸다
const LINK_RESULT: Record<string, string> = {
  done: '소셜 계정을 연동했어요',
  taken: '다른 계정에 이미 연결된 소셜 계정이에요',
  exists: '같은 종류의 소셜 계정을 이미 연동했어요',
  failed: '연동하지 못했어요. 다시 시도해 주세요',
}

/** 로그인 수단(서버 모드). 아이디로 가입한 계정만 보이고, 구글·카카오를 같은 계정에 연동할 수 있다 */
export default function LoginMethods() {
  const [data, setData] = useState<LoginMethodsDto | null>(null)
  const [busy, setBusy] = useState('')

  useEffect(() => {
    const result = new URLSearchParams(location.search).get('link')
    if (result) {
      history.replaceState(null, '', location.pathname + location.hash)
      if (LINK_RESULT[result]) showToast(LINK_RESULT[result])
    }
    loadLoginMethods().then(setData)
  }, [])

  if (!data?.loginId) return null

  const link = async (id: string) => {
    setBusy(id)
    const err = await startSocialLink(id)
    if (err) {
      setBusy('')
      showToast(err)
    }
  }

  return (
    <section className="card login-methods">
      <h2>로그인 수단</h2>
      <ul>
        <li>
          <span className="lm-name">아이디</span>
          <span className="muted">{data.loginId}</span>
        </li>
        {data.providers.map((p) => (
          <li key={p.id}>
            <span className="lm-name"><ProviderIcon id={p.id} />{p.name}</span>
            {p.linked
              ? <span className="muted">연동됨</span>
              : <button className="chip-btn" disabled={!!busy} onClick={() => link(p.id)}>{p.name} 연동</button>}
          </li>
        ))}
      </ul>
    </section>
  )
}
