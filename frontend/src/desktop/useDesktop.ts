import { useEffect, useRef, useState, useSyncExternalStore } from 'react'

// PC 레이아웃으로 바꾸는 폭. 1024px 미만(휴대폰·세로 태블릿)은 기존 모바일 화면을 그대로 쓴다
export const DESKTOP_QUERY = '(min-width: 1024px)'

const subscribe = (on: () => void) => {
  const mq = window.matchMedia(DESKTOP_QUERY)
  mq.addEventListener('change', on)
  return () => mq.removeEventListener('change', on)
}

export function useDesktop() {
  return useSyncExternalStore(subscribe, () => window.matchMedia(DESKTOP_QUERY).matches, () => false)
}

/** 값이 오르거나 내리면 잠깐 'flash-up' / 'flash-down'을 돌려준다(시세가 바뀐 칸 강조) */
export function useFlash(value: number) {
  const prev = useRef(value)
  const [flash, setFlash] = useState<{ dir: 'up' | 'down'; n: number } | null>(null)
  useEffect(() => {
    if (value === prev.current) return
    const dir = value > prev.current ? 'up' : 'down'
    prev.current = value
    setFlash((f) => ({ dir, n: (f?.n ?? 0) + 1 }))
    const t = setTimeout(() => setFlash(null), 700)
    return () => clearTimeout(t)
  }, [value])
  // n을 key로 쓰면 연속으로 바뀔 때도 애니메이션이 처음부터 다시 돈다
  return flash ? { className: `flash-${flash.dir}`, key: flash.n } : { className: '', key: 0 }
}
