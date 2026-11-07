import { teamById } from '../data/teams'
import { PAIR_THRESHOLD, DEVIATION_RATE } from '../engine/fds'
import { PRICE_LIMIT_RATE } from '../engine/orderbook'
import { useStore } from '../store'
import { Header, timeAgo } from '../components/ui'

const RULES = [
  { title: `가격제한폭 ±${PRICE_LIMIT_RATE * 100}%`, body: '전일 종가 기준으로 상한가·하한가를 벗어난 주문은 받지 않아요. 터무니없는 가격으로 포인트를 넘기는 걸 막아요.' },
  { title: '자기매매 방지', body: '내 매수와 매도 주문이 서로 체결될 상황이면 새로 낸 주문을 취소해요.' },
  { title: '익명 호가', body: '호가창과 체결 내역에는 상대방을 표시하지 않아 특정 계정을 지정해 거래하기 어려워요.' },
  { title: `가격 괴리 감시 ${DEVIATION_RATE * 100}%`, body: '직전 체결가에서 크게 벗어난 체결을 기록해요.' },
  { title: `반복 상대 감시 ${PAIR_THRESHOLD}회`, body: '10분 안에 같은 종목에서 같은 두 계정이 반복해서 체결되면 기록해요.' },
]

export default function Watch() {
  const s = useStore()
  const name = (id: string) => (id === 'me' ? '나' : s.users[id]?.name ?? id)
  return (
    <>
      <Header title="시장감시" />
      <section className="card">
        <h2>이상거래 방지 규칙</h2>
        {RULES.map((r) => (
          <div key={r.title} className="rule">
            <b>{r.title}</b>
            <p>{r.body}</p>
          </div>
        ))}
      </section>
      <section className="card">
        <h2>탐지 기록 <span className="muted small">{s.alerts.length}건</span></h2>
        {s.alerts.length === 0 && <p className="empty">아직 탐지된 거래가 없어요</p>}
        {s.alerts.slice(0, 30).map((a) => (
          <div key={a.id} className="alert-row">
            <span className={`badge ${a.kind === 'price_deviation' ? 'warn' : ''}`}>{a.kind === 'price_deviation' ? '가격 괴리' : '반복 상대'}</span>
            <div>
              <div>{teamById(a.teamId).name} · {a.message}</div>
              <div className="muted xs">{name(a.userIds[0])} ↔ {name(a.userIds[1])} · {timeAgo(a.ts)}</div>
            </div>
          </div>
        ))}
        <p className="hint">운영자 화면 예시예요. 실제 서비스에서는 일반 유저에게 계정명을 보여주지 않아요.</p>
      </section>
    </>
  )
}
