// 화면이 읽는 상태의 모양. 브라우저 시뮬레이션과 서버 저장소가 같은 모양을 채운다.
import type { Book, Trade } from './engine/orderbook'
import type { Alert } from './engine/fds'

export const ME = 'me'
export const START_POINTS = 1_000_000

export interface Holding {
  qty: number // 매도 주문에 묶이지 않은 수량
  avgPrice: number
}

export interface User {
  id: string
  name: string
  points: number // 매수 주문에 묶이지 않은 포인트
  holdings: Record<string, Holding>
  initialValue: number
  isBot: boolean
}

export interface Market {
  /** 기준가(전일 종가). 등락률과 가격제한폭의 기준. 서버 모드는 매일 일 마감 뒤 바뀐다 */
  base: number
  /** 거래 중인지. 서버 모드에서 첫 상장 공모 전이거나 상장 동시호가를 기다리면 false */
  listed: boolean
  /** 단일가 매매(상장 동시호가, VI) 중 예상 체결가와 수량. 지금 단일가 매매를 하면 이 가격에 이만큼 체결된다 */
  indicative?: { price: number; volume: number }
  /** 서버 모드: 변동성 완화장치(VI)가 발동해 단일가 매매 중이면 끝나는 시각(ms) */
  viUntil?: number
  last: number
  high: number
  low: number
  volume: number
  intraday: { ts: number; price: number }[]
}

export interface Post {
  id: string
  teamId: string
  userId: string
  author: string
  body: string
  ts: number
  /** 서버 모드: 글을 쓸 때 그 구단 주식을 갖고 있었는지 */
  holder?: boolean
  /** 서버 모드 내 글: 게시글 보상 상태 */
  reward?: 'PENDING' | 'PAID' | 'NONE'
}

export interface Dividend {
  kind?: 'win' | 'draw'
  /** 대회 성적 수당이면 성적 이름(우승 수당 등) */
  label?: string
  matchId: string
  teamId: string
  qty: number
  amount: number
  ts: number
}

export interface State {
  users: Record<string, User>
  books: Record<string, Book>
  markets: Record<string, Market>
  trades: Trade[] // 최근 체결, 최신이 앞
  alerts: Alert[]
  posts: Post[]
  dividends: Dividend[] // 내 배당 내역
  settled: string[]
}

export interface OrderResult {
  ok: boolean
  message: string
}
