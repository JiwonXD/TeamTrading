import { useEffect, useState } from 'react'
import { getConnection, getLogin, getToast, useStore } from './store'
import Home from './pages/Home'
import TeamDetail from './pages/TeamDetail'
import Portfolio from './pages/Portfolio'
import Ranking from './pages/Ranking'
import Watch from './pages/Watch'
import Login from './pages/Login'

function useOnline() {
  const [online, setOnline] = useState(() => navigator.onLine)
  useEffect(() => {
    const on = () => setOnline(navigator.onLine)
    window.addEventListener('online', on)
    window.addEventListener('offline', on)
    return () => {
      window.removeEventListener('online', on)
      window.removeEventListener('offline', on)
    }
  }, [])
  return online
}

const clock = (ts: number) => new Date(ts).toLocaleTimeString('ko-KR', { hour12: false })

// 화면 값이 지금 서버 값이 아닐 때(오프라인, 서버 연결 끊김) 분명히 알린다
function StatusBanner() {
  const online = useOnline()
  const conn = getConnection()
  if (!online) return <div className="offline" role="alert">인터넷 연결이 끊겼어요. 시세와 주문은 다시 연결되면 이어서 보여드려요.</div>
  if (conn.status === 'stale' && conn.lastSync)
    return <div className="offline" role="alert">서버와 연결이 끊겨 {clock(conn.lastSync)} 기준 값이에요. 다시 연결하는 중이에요.</div>
  return null
}

function useHash() {
  const [hash, setHash] = useState(() => location.hash.slice(1) || 'home')
  useEffect(() => {
    const on = () => {
      setHash(location.hash.slice(1) || 'home')
      window.scrollTo(0, 0)
    }
    window.addEventListener('hashchange', on)
    return () => window.removeEventListener('hashchange', on)
  }, [])
  return hash
}

const TABS = [
  { path: 'home', label: '홈', icon: 'M3 10.5L12 3l9 7.5V21h-6v-6H9v6H3z' },
  { path: 'portfolio', label: '내 투자', icon: 'M4 20V10h4v10zm6 0V4h4v16zm6 0v-7h4v7z' },
  { path: 'ranking', label: '랭킹', icon: 'M7 4h10v4a5 5 0 01-10 0zM5 5H3v2a3 3 0 003 3M19 5h2v2a3 3 0 01-3 3M10 14h4v3h3v3H7v-3h3z' },
  { path: 'watch', label: '시장감시', icon: 'M12 3l8 3v6c0 4.5-3.4 8.3-8 9-4.6-.7-8-4.5-8-9V6z' },
]

export default function App() {
  useStore()
  const hash = useHash()
  const toast = getToast()
  // 구단 id에는 하이픈이 들어갈 수 있다(운영자가 등록한 구단)
  const teamMatch = hash.match(/^team-([a-z0-9-]+)$/)
  const login = getLogin()
  const conn = getConnection()

  // 서버 값을 한 번도 받지 못했으면 기본값을 보여주지 않고 연결 상태만 보여준다
  if (!login && (conn.status === 'connecting' || conn.status === 'unreachable')) return (
    <div className="app">
      <StatusBanner />
      <main className="connecting" aria-busy="true">
        <div className="logo">팀트</div>
        <p className="muted">{conn.status === 'connecting' ? '서버에 연결하는 중이에요' : '서버에 연결할 수 없어요. 잠시 뒤 다시 시도해요'}</p>
      </main>
    </div>
  )

  if (login) return (
    <div className="app">
      <StatusBanner />
      <main><Login options={login} /></main>
      {toast && <div key={toast.id} className="toast" role="status">{toast.msg}</div>}
    </div>
  )

  let page
  if (teamMatch) page = <TeamDetail teamId={teamMatch[1]} />
  else if (hash === 'portfolio') page = <Portfolio />
  else if (hash === 'ranking') page = <Ranking />
  else if (hash === 'watch') page = <Watch />
  else page = <Home />

  return (
    <div className="app">
      <StatusBanner />
      <main className={teamMatch ? 'with-cta' : 'with-nav'}>{page}</main>
      {!teamMatch && (
        <nav className="tabbar">
          {TABS.map((t) => (
            <a key={t.path} href={`#${t.path}`} className={hash === t.path ? 'on' : ''}>
              <svg width="24" height="24" viewBox="0 0 24 24"><path d={t.icon} fill="currentColor" /></svg>
              {t.label}
            </a>
          ))}
        </nav>
      )}
      {toast && <div key={toast.id} className="toast" role="status">{toast.msg}</div>}
    </div>
  )
}
