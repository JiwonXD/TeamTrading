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

/**
 * 가로로 넘치는 줄을 마우스로도 넘길 수 있게 한다: 누른 채 끌기, 세로 휠을 가로 이동으로.
 * 손가락·트랙패드는 브라우저 기본 스크롤을 그대로 쓴다. 양 끝에 더 있으면 more-left / more-right 클래스를 붙인다
 */
export function useDragScroll<T extends HTMLElement>() {
  // 접힌 사이드바처럼 나중에 그려지는 줄도 잡도록 콜백 ref로 받는다
  const [el, setEl] = useState<T | null>(null)
  const edges = useRef(() => {})
  useEffect(() => {
    if (!el) return
    edges.current = () => {
      el.classList.toggle('more-left', el.scrollLeft > 1)
      el.classList.toggle('more-right', el.scrollLeft + el.clientWidth < el.scrollWidth - 1)
    }
    let drag: { id: number; x: number; left: number; moved: boolean } | null = null
    let dragged = false
    const down = (e: PointerEvent) => {
      if (e.pointerType === 'mouse' && e.button === 0) drag = { id: e.pointerId, x: e.clientX, left: el.scrollLeft, moved: false }
    }
    const move = (e: PointerEvent) => {
      if (!drag || e.pointerId !== drag.id) return
      const dx = e.clientX - drag.x
      if (!drag.moved) {
        // 살짝 떨린 클릭은 탭 고르기로 두고, 몇 px 넘게 움직여야 끌기로 본다
        if (Math.abs(dx) < 6) return
        drag.moved = true
        el.setPointerCapture(drag.id)
        el.classList.add('dragging')
      }
      el.scrollLeft = drag.left - dx
    }
    const up = (e: PointerEvent) => {
      if (!drag || e.pointerId !== drag.id) return
      const moved = drag.moved
      drag = null
      if (!moved) return
      el.classList.remove('dragging')
      // 끌고 난 뒤 따라오는 클릭은 탭 고르기로 치지 않는다
      dragged = true
      window.setTimeout(() => { dragged = false })
    }
    const click = (e: MouseEvent) => { if (dragged) { e.stopPropagation(); e.preventDefault() } }
    const wheel = (e: WheelEvent) => {
      // 가로 움직임이 큰 휠(트랙패드)은 브라우저에 맡긴다
      if (el.scrollWidth <= el.clientWidth || Math.abs(e.deltaX) >= Math.abs(e.deltaY)) return
      e.preventDefault()
      el.scrollLeft += e.deltaMode === 1 ? e.deltaY * 16 : e.deltaY
    }
    const onEdges = () => edges.current()
    el.addEventListener('pointerdown', down)
    el.addEventListener('pointermove', move)
    el.addEventListener('pointerup', up)
    el.addEventListener('pointercancel', up)
    el.addEventListener('click', click, true)
    el.addEventListener('wheel', wheel, { passive: false })
    el.addEventListener('scroll', onEdges, { passive: true })
    const ro = new ResizeObserver(onEdges)
    ro.observe(el)
    return () => {
      el.removeEventListener('pointerdown', down)
      el.removeEventListener('pointermove', move)
      el.removeEventListener('pointerup', up)
      el.removeEventListener('pointercancel', up)
      el.removeEventListener('click', click, true)
      el.removeEventListener('wheel', wheel)
      el.removeEventListener('scroll', onEdges)
      ro.disconnect()
    }
  }, [el])
  // 안에 든 탭 수가 바뀌어도(리그 설정·숨김 리그를 받아 온 뒤) 양 끝 표시를 다시 맞춘다
  useEffect(() => edges.current())
  return setEl
}
