import { type ReactNode, useEffect, useId, useRef, useState } from 'react'
import { createPortal } from 'react-dom'

const GAP = 16
const WIDTH = 300

/** 물음표 아이콘. 누르면 긴 설명을 말풍선으로 보여주고, 바깥을 누르거나 Esc를 누르면 닫는다. 스크롤하면 아이콘을 따라간다 */
export function HelpTip({ children, label = '도움말' }: { children: ReactNode; label?: string }) {
  const [pos, setPos] = useState<{ top: number; left: number; width: number } | null>(null)
  const btn = useRef<HTMLButtonElement>(null)
  const pop = useRef<HTMLElement>(null)
  const id = useId()

  const place = () => {
    if (!btn.current) return
    const r = btn.current.getBoundingClientRect()
    const width = Math.min(WIDTH, innerWidth - GAP * 2)
    const left = Math.min(Math.max(r.left + r.width / 2 - width / 2, GAP), innerWidth - GAP - width)
    setPos({ top: r.bottom + 6, left, width })
  }
  const open = !!pos

  useEffect(() => {
    if (!open) return
    const close = () => setPos(null)
    const onDown = (e: PointerEvent) => {
      const t = e.target as Node
      if (!btn.current?.contains(t) && !pop.current?.contains(t)) close()
    }
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') { close(); btn.current?.focus() } }
    document.addEventListener('pointerdown', onDown)
    document.addEventListener('keydown', onKey)
    window.addEventListener('scroll', place, true)
    window.addEventListener('resize', place)
    return () => {
      document.removeEventListener('pointerdown', onDown)
      document.removeEventListener('keydown', onKey)
      window.removeEventListener('scroll', place, true)
      window.removeEventListener('resize', place)
    }
  }, [open])

  return (
    <>
      <button
        ref={btn}
        type="button"
        className="help-tip"
        aria-label={label}
        aria-expanded={open}
        aria-controls={open ? id : undefined}
        onClick={(e) => { e.stopPropagation(); if (open) setPos(null); else place() }}
      >
        <svg width="16" height="16" viewBox="0 0 24 24" aria-hidden="true">
          <circle cx="12" cy="12" r="9.5" fill="none" stroke="currentColor" strokeWidth="1.8" />
          <path d="M9.6 9.3a2.5 2.5 0 1 1 3.4 2.3c-.6.3-1 .8-1 1.5v.6" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
          <circle cx="12" cy="16.8" r="1.1" fill="currentColor" />
        </svg>
      </button>
      {pos && createPortal(
        // 화면 맨 위(body)에 띄우므로 내용이 랜드마크 밖에 놓이지 않게 aside로 감싼다
        <aside ref={pop} id={id} aria-label={label} className="help-pop" style={{ top: pos.top, left: pos.left, width: pos.width }}>
          {children}
        </aside>,
        document.body,
      )}
    </>
  )
}
