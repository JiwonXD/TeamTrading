import { useCallback, useEffect, useState } from 'react'
import { COMPETITION_LABEL, LEAGUES, TEAMS, teamById } from '../data/teams'
import { ME, type NoticeDto, canWriteNotices, deleteNotice, editNotice, fetchNews, fetchNotices, fetchOverview, showToast, totalShares, useStore, writeNotice } from '../store'
import { Segmented, fmt, timeAgo } from './ui'

const AWARD_LABEL: Record<string, string> = {
  CHAMPION: '우승', RUNNER_UP: '준우승', SEMIFINAL: '4강', QUARTERFINAL: '8강(토너먼트 대회)',
  POSTSEASON: '포스트시즌·본선 진출', REGULAR_FIRST: '정규 시즌 1위',
}
const WEEKDAYS = ['일', '월', '화', '수', '목', '금', '토']
const compLabel = (c: string) => COMPETITION_LABEL[c] ?? c
const when = (iso: string) => {
  const d = new Date(iso)
  const time = d.toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit', hour12: false })
  return `${d.getMonth() + 1}월 ${d.getDate()}일 (${WEEKDAYS[d.getDay()]}) ${time}`
}
const delayLabel = (min: number) => (min % 60 === 0 ? `${min / 60}시간` : `${min}분`)

/** 화면이 보는 동안 받아 온 값. 구단을 바꾸면 이전 구단 응답이 늦게 와도 섞이지 않게 teamId로 확인한다 */
function useLoad<T>(teamId: string, load: (teamId: string) => Promise<T>) {
  const [got, setGot] = useState<{ teamId: string; value: T } | null>(null)
  const [failed, setFailed] = useState(false)
  const reload = useCallback(() => {
    load(teamId).then((value) => { setGot({ teamId, value }); setFailed(false) }).catch(() => setFailed(true))
  }, [teamId, load])
  useEffect(reload, [reload])
  return { value: got?.teamId === teamId ? got.value : null, failed, reload }
}

/** 구단정보 탭: 이 구단을 사면 받을 배당, 다가오는 경기, 주요 정보. 숫자는 모두 서버 배당 규칙에서 계산한 값 */
export function TeamInfoView({ teamId }: { teamId: string }) {
  const s = useStore()
  const { value: info, failed } = useLoad(teamId, fetchOverview)
  if (!info) return <section className="card"><p className="empty">{failed ? '정보를 불러오지 못했어요. 잠시 뒤 다시 시도해요' : '정보를 불러오는 중이에요'}</p></section>

  const m = s.markets[teamId]
  const team = teamById(teamId)
  const myQty = totalShares(ME, teamId)
  const league = LEAGUES.find((l) => l.code === info.league)?.id ?? info.league
  return (
    <div className="team-info">
      <section className="card info-win" aria-label="승리 배당">
        <h2>이 구단이 이기면</h2>
        <p className="info-big">1승마다 1주당 <b className="up">{fmt(info.winDps)}P</b></p>
        <p className="muted small">
          {league} 경기 기준 · 지금 가격 {fmt(m.last)}P의 {(info.winDps / m.last * 100).toFixed(2)}%
        </p>
        {myQty > 0 && <p className="info-mine">내 보유 {fmt(myQty)}주 → 1승마다 <b>{fmt(myQty * info.winDps)}P</b></p>}
      </section>

      <section className="card" aria-label="주요 정보">
        <h2>주요 정보</h2>
        <dl className="info-grid">
          <div><dt>리그</dt><dd>{league}</dd></div>
          <div><dt>보유자</dt><dd>{fmt(info.holders)}명</dd></div>
          <div><dt>유통 주식 수</dt><dd>{fmt(info.shares)}주</dd></div>
          <div><dt>시가총액</dt><dd>{fmt(info.shares * m.last)}P</dd></div>
        </dl>
        <p className="hint">유통 주식은 이용자들이 지금 가진 {team.short} 주식이에요(판매 주문에 걸린 것 포함). 운영자 판매 주문이 체결되면 늘어나요.</p>
      </section>

      <section className="card" aria-label="다가오는 경기">
        <h2>다가오는 경기</h2>
        {info.upcoming.length === 0 ? <p className="empty">예정된 경기가 아직 없어요</p> : (
          <ul className="info-list">
            {info.upcoming.map((u) => (
              <li key={u.matchId}>
                <span className="info-when">
                  <span className="xs muted">{when(u.scheduledAt)}</span>
                  <span>
                    <span className="badge">{compLabel(u.competition)}</span>{' '}
                    {u.opponentId && TEAMS.some((t) => t.id === u.opponentId)
                      ? <a href={`#team-${u.opponentId}`}>vs {u.opponentName}</a>
                      : <>vs {u.opponentName}</>}
                    <span className="xs muted"> · {u.home ? '홈' : '원정'}</span>
                  </span>
                </span>
                <span className="info-dps">이기면 <b className="up">{fmt(u.winDps)}P</b></span>
              </li>
            ))}
          </ul>
        )}
      </section>

      <section className="card" aria-label="대회별 배당">
        <h2>대회별 배당 <span className="xs muted">1주당</span></h2>
        <table className="info-table">
          <thead><tr><th>대회</th><th>배수</th><th>1승</th><th>우승 수당</th></tr></thead>
          <tbody>
            {info.competitions.map((c) => (
              <tr key={c.competition}>
                <td>{compLabel(c.competition)}</td>
                <td>×{c.weight}</td>
                <td>{fmt(c.winDps)}P</td>
                <td>{fmt(c.championDps)}P</td>
              </tr>
            ))}
          </tbody>
        </table>
        <p className="hint">컵대회는 출전하면 적용돼요. 1승 배당은 {league} 1승 배당에 대회 배수를 곱하고 10P 단위로 반올림해요.</p>
      </section>

      <section className="card" aria-label="대회 성적 수당">
        <h2>대회 성적 수당 <span className="xs muted">1주당, {league} 기준</span></h2>
        <dl className="info-awards">
          {info.awards.map((a) => <div key={a.award}><dt>{AWARD_LABEL[a.award] ?? a.award}</dt><dd>{fmt(a.dps)}P</dd></div>)}
        </dl>
        <p className="hint">시즌이 끝나고 최종 성적에 따라 한 번 받아요. 우승·준우승·4강·8강·진출 중 대회마다 하나만 받고, 정규 시즌 1위는 따로 받아요. 컵대회는 위 대회 배수를 곱해요.</p>
      </section>

      <section className="card" aria-label="배당 지급 규칙">
        <h2>지급 규칙</h2>
        <ul className="info-rules">
          <li>경기 시작 예정 시각에 가진 주식에 줘요. 판매 주문에 걸어 둔 주식도 포함돼요.</li>
          <li>결과가 들어오고 {delayLabel(info.confirmDelayMinutes)} 뒤에 자동으로 지급해요. 그 사이 결과가 정정되면 정정된 결과로 줘요.</li>
          <li>무승부가 있는 종목은 무승부면 승리 배당의 절반을 양 팀에 줘요. 취소된 경기는 배당이 없어요.</li>
          <li>배당 금액은 시즌 전에 정하고 시즌 중에는 바꾸지 않아요.</li>
        </ul>
      </section>
    </div>
  )
}

/** 뉴스·공시 탭. 토스증권처럼 뉴스가 먼저, 공시는 옆 칸 */
export function NewsNoticesView({ teamId }: { teamId: string }) {
  const [sub, setSub] = useState<'news' | 'notice'>('news')
  return (
    <>
      <div className="pad news-sub">
        <Segmented value={sub} onChange={setSub} options={[{ value: 'news', label: '뉴스' }, { value: 'notice', label: '공시' }]} />
      </div>
      {sub === 'news' ? <NewsView teamId={teamId} /> : <NoticesView teamId={teamId} />}
    </>
  )
}

const DAY = 86_400_000
const newsTime = (iso: string) => {
  const t = Date.parse(iso)
  if (Date.now() - t < DAY) return timeAgo(t)
  const d = new Date(t)
  return `${d.getMonth() + 1}월 ${d.getDate()}일`
}

/** 뉴스: 네이버 뉴스 검색 결과(최신순). 누르면 언론사 기사로 간다 */
function NewsView({ teamId }: { teamId: string }) {
  const { value: news, failed } = useLoad(teamId, fetchNews)
  return (
    <section className="card news" aria-label="뉴스">
      {!news ? <p className="empty">{failed ? '뉴스를 불러오지 못했어요. 잠시 뒤 다시 시도해요' : '뉴스를 불러오는 중이에요'}</p>
        : !news.available ? <p className="empty">뉴스는 준비 중이에요</p>
        : news.articles.length === 0 ? <p className="empty">최근 기사가 없어요</p>
        : (
          <ul className="news-list">
            {news.articles.map((a) => (
              <li key={a.url}>
                <a href={a.url} target="_blank" rel="noopener noreferrer">
                  <b>{a.title}</b>
                  {a.summary && <span className="news-summary">{a.summary}</span>}
                  <span className="xs muted">{a.source} · {newsTime(a.publishedAt)}</span>
                </a>
              </li>
            ))}
          </ul>
        )}
      {news?.available && <p className="hint">네이버 뉴스 검색 결과예요. 누르면 언론사 기사가 새 창으로 열려요.</p>}
    </section>
  )
}

/** 공시: 운영자가 쓰는 구단 공시와 전체 공시. 이용자는 읽기만 한다 */
function NoticesView({ teamId }: { teamId: string }) {
  useStore() // 로그인·운영자 여부가 바뀌면 다시 그린다
  const { value: notices, failed, reload } = useLoad(teamId, fetchNotices)
  const admin = canWriteNotices()
  const [writing, setWriting] = useState(false)
  const team = teamById(teamId)

  const done = (r: { ok: boolean; message: string }) => {
    showToast(r.message)
    if (r.ok) reload()
    return r.ok
  }

  return (
    <section className="card notices" aria-label="공시">
      {admin && (writing
        ? <NoticeForm teamName={team.name} onCancel={() => setWriting(false)}
            onSubmit={async (title, body, all) => { if (done(await writeNotice(all ? null : teamId, title, body))) setWriting(false) }} />
        : <button className="chip-btn notice-new" onClick={() => setWriting(true)}>공시 쓰기</button>)}
      {!notices ? <p className="empty">{failed ? '공시를 불러오지 못했어요. 잠시 뒤 다시 시도해요' : '공시를 불러오는 중이에요'}</p>
        : notices.length === 0 ? <p className="empty">아직 공시가 없어요</p>
        : notices.map((n) => <NoticeItem key={n.id} notice={n} admin={admin} onDone={done} />)}
      <p className="hint">공시는 운영자만 올려요. 배당 금액·일정 같은 숫자는 구단정보 탭이 늘 최신이에요.</p>
    </section>
  )
}

function NoticeItem({ notice: n, admin, onDone }: { notice: NoticeDto; admin: boolean; onDone: (r: { ok: boolean; message: string }) => boolean }) {
  const [editing, setEditing] = useState(false)
  const [confirming, setConfirming] = useState(false)
  if (editing) {
    return <NoticeForm initial={n} onCancel={() => setEditing(false)}
      onSubmit={async (title, body) => { if (onDone(await editNotice(n.id, title, body))) setEditing(false) }} />
  }
  return (
    <article className="post notice-item">
      <div className="row-gap">
        {n.teamId === null && <span className="badge">전체</span>}
        <h3>{n.title}</h3>
      </div>
      <p className="notice-body">{n.body}</p>
      <div className="row-gap">
        <span className="muted xs">{new Date(n.createdAt).toLocaleDateString('ko-KR')}{n.updatedAt && ' · 수정됨'}</span>
        {admin && (
          <span className="post-actions">
            <button className="link-btn" onClick={() => setEditing(true)}>수정</button>
            {confirming
              ? <button className="link-btn danger-text" onClick={async () => onDone(await deleteNotice(n.id))}>정말 지우기</button>
              : <button className="link-btn" onClick={() => setConfirming(true)}>삭제</button>}
          </span>
        )}
      </div>
    </article>
  )
}

function NoticeForm({ initial, teamName, onSubmit, onCancel }: {
  initial?: NoticeDto
  /** 새 공시일 때만. 이 구단에 올릴지 전체에 올릴지 고른다 */
  teamName?: string
  onSubmit: (title: string, body: string, all: boolean) => Promise<void>
  onCancel: () => void
}) {
  const [title, setTitle] = useState(initial?.title ?? '')
  const [body, setBody] = useState(initial?.body ?? '')
  const [all, setAll] = useState(false)
  const [busy, setBusy] = useState(false)
  const ok = title.trim() !== '' && body.trim() !== ''
  return (
    <form className="notice-form" onSubmit={async (e) => {
      e.preventDefault()
      if (!ok || busy) return
      setBusy(true)
      await onSubmit(title.trim(), body.trim(), all)
      setBusy(false)
    }}>
      <input aria-label="공시 제목" placeholder="제목" maxLength={100} value={title} onChange={(e) => setTitle(e.target.value)} />
      <textarea aria-label="공시 내용" placeholder="내용" maxLength={3000} rows={5} value={body} onChange={(e) => setBody(e.target.value)} />
      {teamName && (
        <label className="row-gap small">
          <input type="checkbox" checked={all} onChange={(e) => setAll(e.target.checked)} />
          모든 구단에 올리기{all ? '' : ` (지금은 ${teamName}에만)`}
        </label>
      )}
      <div className="row-gap">
        <button type="submit" className="chip-btn" disabled={!ok || busy}>{initial ? '고치기' : '올리기'}</button>
        <button type="button" className="link-btn" onClick={onCancel}>취소</button>
      </div>
    </form>
  )
}
