import { useEffect, useState } from 'react'
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
const sameDay = (a: Date, b: Date) => a.toDateString() === b.toDateString()
/** 그 주 월요일 0시 */
const weekStart = (d: Date) => addDays(startOfDay(d), -((d.getDay() + 6) % 7))

/** 리그 화면 위쪽: 주간 달력과 고른 날의 경기. 경기의 구단을 누르면 그 구단 주문 화면으로 간다 */
export function LeagueSchedule({ league }: { league: League }) {
  const today = startOfDay(new Date())
  const [day, setDay] = useState(today)
  const from = weekStart(day).getTime()
  const key = `${league}:${from}`
  const [week, setWeek] = useState<{ key: string; matches: MatchDto[] } | null>(null)
  const [failed, setFailed] = useState(false)

  useEffect(() => {
    let alive = true
    const load = () => fetchSchedule(league, new Date(from), addDays(new Date(from), 7))
      .then((matches) => { if (alive) { setWeek({ key, matches }); setFailed(false) } })
      .catch(() => { if (alive) setFailed(true) })
    load()
    // 경기 상태와 점수가 바뀌므로 보고 있는 동안 30초마다 다시 받는다
    const timer = window.setInterval(load, 30_000)
    return () => {
      alive = false
      window.clearInterval(timer)
    }
  }, [league, from, key])

  // 다른 리그·주의 응답이 늦게 와도 섞이지 않도록 지금 보는 주의 것만 쓴다
  const matches = week?.key === key ? week.matches : null
  const days = Array.from({ length: 7 }, (_, i) => addDays(new Date(from), i))
  const onDay = matches?.filter((m) => sameDay(new Date(m.scheduledAt), day)) ?? []

  return (
    <section className="card schedule" aria-label={`${league} 경기 일정`}>
      <div className="row-between">
        <h2>{day.getFullYear()}년 {day.getMonth() + 1}월 일정</h2>
        <span className="sched-nav">
          <button className="icon-btn" aria-label="이전 주" onClick={() => setDay(addDays(day, -7))}>
            <svg width="20" height="20" viewBox="0 0 24 24" aria-hidden="true"><path d="M15 5l-7 7 7 7" stroke="currentColor" strokeWidth="2.2" fill="none" strokeLinecap="round" strokeLinejoin="round" /></svg>
          </button>
          <button className="chip-btn" disabled={sameDay(day, today)} onClick={() => setDay(today)}>오늘</button>
          <button className="icon-btn" aria-label="다음 주" onClick={() => setDay(addDays(day, 7))}>
            <svg width="20" height="20" viewBox="0 0 24 24" aria-hidden="true"><path d="M9 5l7 7-7 7" stroke="currentColor" strokeWidth="2.2" fill="none" strokeLinecap="round" strokeLinejoin="round" /></svg>
          </button>
        </span>
      </div>
      <div className="sched-days">
        {days.map((d) => {
          const has = matches?.some((m) => sameDay(new Date(m.scheduledAt), d))
          const on = sameDay(d, day)
          const isToday = sameDay(d, today)
          return (
            <button
              key={d.getTime()}
              className={`sched-day ${on ? 'on' : ''} ${isToday ? 'today' : ''}`}
              aria-pressed={on}
              onClick={() => setDay(d)}
            >
              <span className="xs">{isToday ? '오늘' : WEEKDAYS[d.getDay()]}</span>
              <b>{d.getDate()}<span className="sr-only">일</span></b>
              <i className={`dot ${has ? '' : 'none'}`} />
              {has && <span className="sr-only">경기 있음</span>}
            </button>
          )
        })}
      </div>
      {!matches ? <p className="empty">{failed ? '일정을 불러오지 못했어요. 잠시 뒤 다시 시도해요' : '일정을 불러오는 중이에요'}</p>
        : onDay.length === 0 ? <p className="empty">이 날은 경기가 없어요</p>
        : <ul className="sched-list">{onDay.map((m) => <ScheduleMatch key={m.id} match={m} />)}</ul>}
      <p className="hint">경기의 구단을 누르면 호가를 보고 바로 주문할 수 있어요.</p>
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
