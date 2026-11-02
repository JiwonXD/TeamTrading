import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './index.css'
import App from './App.tsx'
import { mode } from './store'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
)

// 서버 모드 배포본에서만 서비스 워커를 쓴다. 시뮬레이션 단일 파일(미리보기)과 개발 서버에서는 등록하지 않는다
if (import.meta.env.PROD && mode === 'server' && 'serviceWorker' in navigator) {
  window.addEventListener('load', () => {
    navigator.serviceWorker.register('./sw.js').catch(() => {})
  })
}
