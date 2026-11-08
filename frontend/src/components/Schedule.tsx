import { type PointerEvent, useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
import { COMPETITION_LABEL, type League, TEAMS, isCup } from '../data/teams'
import { type MatchDto, fetchSchedule, useStore } from '../store'
import { Logo, fmt, pct, tone } from './ui'

const WEEKDAYS = ['일', '월', '화', '수', '목', '금', '토']
const startOfDay = (d: Date) => {
  const x = new Date(d)
  x.setHours(0, 0, 0, 0)
  return x
}
// 날짜 더하기는 setDate로 해서 서머타임이 있는 시간대에서도 자정이 어긋나지 않게 한다
const addDays = (d: Date, n: number) => {
  const x = new Date(d)
  x.setDate(x.getDate() + n)
  return x
}

// 날짜 띠는 오늘 앞뒤로 이만큼 깔아 두고 좌우로 끌어서 넘긴다
const PAST = 60
const FUTURE = 180
const DAYS = PAST + 1 + FUTURE
// 일정은 이 날수 묶음으로 받는다(서버 한도 31일 이내). 보이는 범위 근처 묶음만 받는다
const CHUNK = 14
// 처음 열거나 "오늘"을 누를 때 오늘 왼쪽에 남겨 둘 지난 날 수. 앞으로의 일정이 넓게 보이게 조금만 남긴다
const lead = (visible: number) => (visible >= 10 ? 2 : 1)
/** 오늘이 왼쪽에서 lead+1번째 칸에 오게 하려면 가운데에 둘 칸 */
const homeCenter = (visible: number) => PAST - lead(visible) + Math.floor(visible / 2)
const reduceMotion = () => window.matchMedia?.('(prefers-reduced-motion: reduce)').matches

/** 날짜 띠의 칸 간격(칸 폭 + 사이 간격), 다 보이는 칸 수, 지금 가운데에 있는 칸 */
function geometry(el: HTMLElement) {
  const a = el.children[0] as HTMLElement | undefined
  const b = el.children[1] as HTMLElement | undefined
  const pitch = a && b ? b.offsetLeft - a.offsetLeft : el.clientWidth / 7
  const width = a ? a.offsetWidth : pitch
  return {
    pitch,
    visible: Math.max(1, Math.floor((el.clientWidth + pitch - width) / pitch + 0.01)),
    center: Math.round((el.scrollLeft + el.clientWidth / 2 - width / 2) / pitch),
    /** i번째 칸이 띠 가운데에 오는 스크롤 위치(화면상 위치로 재서 띠의 여백·테두리와 무관하게 맞춘다) */
    leftFor: (i: number) => {
      const c = el.children[i] as HTMLElement | undefined
      if (!c) return el.scrollLeft
      const box = el.getBoundingClientRect()
      const cell = c.getBoundingClientRect()
      const to = el.scrollLeft + cell.left + cell.width / 2 - (box.left + el.clientLeft + el.clientWidth / 2)
      return Math.max(0, Math.min(el.scrollWidth - el.clientWidth, to))
    },
  }
}

/** 리그 화면 위쪽: 좌우로 끌어 넘기는 날짜 띠와 고른 날의 경기. 경기의 구단을 누르면 그 구단 주문 화면으로 간다 */
export function LeagueSchedule({ league }: { league: League }) {
  const [today] = useState(() => startOfDay(new Date()))
  const dates = useMemo(() => Array.from({ length: DAYS }, (_, i) => addDays(today, i - PAST)), [today])
  const [sel, setSel] = useState(PAST)
  const day = dates[sel]
  const strip = useRef<HTMLDivElement>(null)
  const [view, setView] = useState({ center: PAST + 2, visible: 7 })

  const measure = useCallback(() => {
    const el = strip.current
    if (!el) return
    const { center, visible } = geometry(el)
    setView((v) => (v.center === center && v.visible === visible ? v : { center, visible }))
  }, [])

  // 끌기를 놓은 뒤(관성)나 날짜를 고를 때 띠를 부드럽게 미끄러뜨린다
  const anim = useRef(0)
  const stop = () => cancelAnimationFrame(anim.current)
  /** 스크롤 위치 to까지 감속하며 미끄러뜨린다 */
  const glideTo = (to: number) => {
    const el = strip.current
    if (!el) return
    stop()
    to = Math.max(0, Math.min(el.scrollWidth - el.clientWidth, to))
    const from = el.scrollLeft
    if (reduceMotion() || Math.abs(to - from) < 1) {
      el.scrollLeft = to
      return
    }
    const ms = Math.min(650, 260 + Math.abs(to - from) * 0.5)
    const t0 = performance.now()
    const step = (now: number) => {
      const k = Math.min(1, (now - t0) / ms)
      el.scrollLeft = from + (to - from) * (1 - (1 - k) ** 3)
      if (k < 1) anim.current = requestAnimationFrame(step)
    }
    anim.current = requestAnimationFrame(step)
  }
  /** i번째 칸이 띠 가운데에 오게 미끄러뜨린다(날짜를 고를 때만) */
  const glide = (i: number) => {
    if (strip.current) glideTo(geometry(strip.current).leftFor(i))
  }

  const goToday = () => {
    setSel(PAST)
    if (strip.current) glide(homeCenter(geometry(strip.current).visible))
  }
  /** 날을 고르면 그 날이 띠 가운데로 오게 옮긴다 */
  const select = (i: number) => {
    setSel(i)
    glide(i)
  }

  // 처음 열면 오늘이 왼쪽에서 두세 번째 칸에 오게 둔다(맞춘 뒤 scroll 이벤트가 measure를 부른다)
  useLayoutEffect(() => {
    const el = strip.current
    if (el) {
      const g = geometry(el)
      el.scrollLeft = g.leftFor(homeCenter(g.visible))
    }
    window.addEventListener('resize', measure)
    return () => {
      window.removeEventListener('resize', measure)
      cancelAnimationFrame(anim.current)
    }
  }, [measure])

  // 마우스로 누른 채 끌기. 손가락(터치)은 브라우저 기본 스크롤이 관성까지 해 준다.
  // 끌기를 놓은 자리는 그대로 둔다(칸 맞춤·가운데 정렬을 하면 보던 날짜가 밀려 다른 날을 고르기 어렵다)
  const drag = useRef<{ id: number; x: number; left: number; moved: boolean; trail: { t: number; x: number }[] } | null>(null)
  const dragged = useRef(false)
  const onPointerDown = (e: PointerEvent<HTMLDivElement>) => {
    stop()
    if (e.pointerType !== 'mouse' || e.button !== 0) return
    drag.current = { id: e.pointerId, x: e.clientX, left: e.currentTarget.scrollLeft, moved: false, trail: [{ t: e.timeStamp, x: e.clientX }] }
  }
  const onPointerMove = (e: PointerEvent<HTMLDivElement>) => {
    const d = drag.current
    if (!d || e.pointerId !== d.id) return
    const el = e.currentTarget
    const dx = e.clientX - d.x
    if (!d.moved) {
      // 살짝 떨린 클릭은 날짜 고르기로 두고, 몇 px 넘게 움직여야 끌기로 본다
      if (Math.abs(dx) < 6) return
      d.moved = true
      el.setPointerCapture(d.id)
      el.classList.add('dragging')
    }
    el.scrollLeft = d.left - dx
    d.trail.push({ t: e.timeStamp, x: e.clientX })
    while (d.trail.length > 2 && e.timeStamp - d.trail[0].t > 100) d.trail.shift()
  }
  const onPointerUp = (e: PointerEvent<HTMLDivElement>) => {
    const d = drag.current
    if (!d || e.pointerId !== d.id) return
    drag.current = null
    if (!d.moved) return
    const el = e.currentTarget
    el.classList.remove('dragging')
    // 빠르게 던지듯 놓았을 때만 그 빠르기만큼 조금 더 미끄러지고, 천천히 놓으면 그 자리에 멈춘다
    const a = d.trail[0]
    const b = d.trail[d.trail.length - 1]
    const v = b.t > a.t && e.timeStamp - b.t < 80 ? (b.x - a.x) / (b.t - a.t) : 0
    if (Math.abs(v) > 0.3) glideTo(el.scrollLeft - v * 280)
    // 끌고 난 뒤 따라오는 클릭은 날짜 고르기로 치지 않는다
    dragged.current = true
    window.setTimeout(() => { dragged.current = false })
  }

  // 보이는 날짜 앞뒤와 고른 날이 든 묶음만 받는다. 경기 상태·점수가 바뀌므로 30초마다 다시 받는다
  const [chunks, setChunks] = useState<Record<string, MatchDto[]>>({})
  const [failed, setFailed] = useState<Record<string, boolean>>({})
  const known = useRef(new Set<string>())
  const half = Math.ceil(view.visible / 2)
  const lo = Math.max(0, view.center - half - 7)
  const hi = Math.min(DAYS - 1, view.center + half + 7)
  const need = new Set([Math.floor(sel / CHUNK)])
  for (let k = Math.floor(lo / CHUNK); k <= Math.floor(hi / CHUNK); k++) need.add(k)
  const needKey = [...need].sort((x, y) => x - y).join(',')

  const alive = useRef(true)
  useEffect(() => {
    alive.current = true
    return () => { alive.current = false }
  }, [])
  useEffect(() => {
    // 응답은 리그·묶음 키로 따로 담으므로 늦게 와도 다른 리그·날짜와 섞이지 않는다
    const load = (k: number) => {
      const key = `${league}:${k}`
      known.current.add(key)
      const from = dates[k * CHUNK]
      fetchSchedule(league, from, addDays(from, CHUNK))
        .then((matches) => {
          if (!alive.current) return
          setChunks((c) => ({ ...c, [key]: matches }))
          setFailed((f) => (f[key] ? { ...f, [key]: false } : f))
        })
        .catch(() => {
          known.current.delete(key)
          if (alive.current) setFailed((f) => ({ ...f, [key]: true }))
        })
    }
    const ks = needKey.split(',').map(Number)
    ks.filter((k) => !known.current.has(`${league}:${k}`)).forEach(load)
    const timer = window.setInterval(() => ks.forEach(load), 30_000)
    return () => window.clearInterval(timer)
  }, [league, needKey, dates])

  // 리그별 묶음을 날짜별로 모은다. 다른 리그 응답은 키가 달라 섞이지 않는다
  const byDay = useMemo(() => {
    const map = new Map<string, MatchDto[]>()
    for (const [key, list] of Object.entries(chunks)) {
      if (!key.startsWith(`${league}:`)) continue
      for (const m of list) {
        const d = new Date(m.scheduledAt).toDateString()
        map.set(d, [...(map.get(d) ?? []), m])
      }
    }
    return map
  }, [chunks, league])
  const selKey = `${league}:${Math.floor(sel / CHUNK)}`
  const onDay = selKey in chunks ? byDay.get(day.toDateString()) ?? [] : null
  const shown = dates[Math.max(0, Math.min(DAYS - 1, view.center))]

  return (
    <section className="card schedule" aria-label={`${league} 경기 일정`}>
      <div className="row-between">
        <h2>{shown.getFullYear()}년 {shown.getMonth() + 1}월 일정</h2>
        <span className="sched-nav">
          <button className="icon-btn" aria-label="이전 날" disabled={sel === 0} onClick={() => select(sel - 1)}>
            <svg width="20" height="20" viewBox="0 0 24 24" aria-hidden="true"><path d="M15 5l-7 7 7 7" stroke="currentColor" strokeWidth="2.2" fill="none" strokeLinecap="round" strokeLinejoin="round" /></svg>
          </button>
          <button className="chip-btn" onClick={() => goToday()}>오늘</button>
          <button className="icon-btn" aria-label="다음 날" disabled={sel === DAYS - 1} onClick={() => select(sel + 1)}>
            <svg width="20" height="20" viewBox="0 0 24 24" aria-hidden="true"><path d="M9 5l7 7-7 7" stroke="currentColor" strokeWidth="2.2" fill="none" strokeLinecap="round" strokeLinejoin="round" /></svg>
          </button>
        </span>
      </div>
      <div
        ref={strip}
        className="sched-days"
        onScroll={measure}
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={onPointerUp}
        onPointerCancel={onPointerUp}
        onClickCapture={(e) => { if (dragged.current) { e.stopPropagation(); e.preventDefault() } }}
      >
        {dates.map((d, i) => {
          const has = byDay.has(d.toDateString())
          const on = i === sel
          const isToday = i === PAST
          return (
            <button
              key={d.getTime()}
              className={`sched-day ${on ? 'on' : ''} ${isToday ? 'today' : ''}`}
              aria-pressed={on}
              onClick={() => select(i)}
            >
              {d.getDate() !== 1 && <span className="sr-only">{d.getMonth() + 1}월</span>}
              <span className="xs">{isToday ? '오늘' : d.getDate() === 1 ? `${d.getMonth() + 1}월` : WEEKDAYS[d.getDay()]}</span>
              <b>{d.getDate()}<span className="sr-only">일</span></b>
              <i className={`dot ${has ? '' : 'none'}`} />
              {has && <span className="sr-only">경기 있음</span>}
            </button>
          )
        })}
      </div>
      {!onDay ? <p className="empty">{failed[selKey] ? '일정을 불러오지 못했어요. 잠시 뒤 다시 시도해요' : '일정을 불러오는 중이에요'}</p>
        : onDay.length === 0 ? <p className="empty">이 날은 경기가 없어요</p>
        : <ul className="sched-list">{onDay.map((m) => <ScheduleMatch key={m.id} match={m} />)}</ul>}
      <p className="hint">날짜는 좌우로 끌어 넘길 수 있어요. 경기의 구단을 누르면 호가를 보고 바로 주문할 수 있어요.</p>
    </section>
  )
}

function statusLabel(m: MatchDto) {
  if (m.status === 'CANCELED') return '취소'
  if (m.dividendStatus === 'PAID') return '배당 지급'
  if (m.dividendStatus === 'REVIEW') return '결과 확인 중'
  if (m.status === 'FINAL') return '종료'
  return Date.parse(m.scheduledAt) > Date.now() ? '예정' : '경기 중'
}

function ScheduleMatch({ match: m }: { match: MatchDto }) {
  const done = m.status === 'FINAL' && m.homeScore !== null && m.awayScore !== null
  const time = new Date(m.scheduledAt).toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit', hour12: false })
  return (
    <li className="sched-match">
      <span className="sched-time">
        <b>{time}</b>
        <span className="xs muted">{statusLabel(m)}</span>
        {isCup(m.competition) && <span className="xs sched-comp">{COMPETITION_LABEL[m.competition] ?? m.competition}</span>}
      </span>
      <MatchTeam id={m.homeTeamId} name={m.homeName} win={done && m.homeScore! > m.awayScore!} />
      <span className="sched-score">{done ? `${m.homeScore} : ${m.awayScore}` : 'vs'}</span>
      <MatchTeam id={m.awayTeamId} name={m.awayName} win={done && m.awayScore! > m.homeScore!} />
    </li>
  )
}

/** 경기 속 구단: 로고·이름·지금 주가. 누르면 구단 주문 화면. 상장하지 않은 상대(컵대회)는 이름만 */
function MatchTeam({ id, name, win }: { id: string | null; name: string | null; win: boolean }) {
  const s = useStore()
  const team = id ? TEAMS.find((t) => t.id === id) : undefined
  const m = id ? s.markets[id] : undefined
  if (!id || !team || !m) return <span className={`sched-team sched-ext ${win ? 'win' : ''}`}><span className="sched-name">{name ?? id}</span></span>
  const chg = m.last / m.base - 1
  return (
    <a href={`#team-${id}`} className={`sched-team ${win ? 'win' : ''}`}>
      <Logo team={team} size={36} />
      <span className="sched-name">{team.short}</span>
      <span className="xs sched-price">{fmt(m.last)}P <span className={tone(chg)}>{m.listed ? pct(chg) : '상장 전'}</span></span>
    </a>
  )
}
