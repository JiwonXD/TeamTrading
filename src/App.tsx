import { useEffect, useState } from 'react'
import { getToast, useStore } from './store'
import Home from './pages/Home'
import TeamDetail from './pages/TeamDetail'
import Portfolio from './pages/Portfolio'
import Ranking from './pages/Ranking'
import Watch from './pages/Watch'

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
  const teamMatch = hash.match(/^team-(\w+)/)

  let page
  if (teamMatch) page = <TeamDetail teamId={teamMatch[1]} />
  else if (hash === 'portfolio') page = <Portfolio />
  else if (hash === 'ranking') page = <Ranking />
  else if (hash === 'watch') page = <Watch />
  else page = <Home />

  return (
    <div className="app">
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
      {toast && <div key={toast.id} className="toast">{toast.msg}</div>}
    </div>
  )
}
