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
  last: number
  high: number
  low: number
  volume: number
  daily: number[]
  intraday: { ts: number; price: number }[]
}

export interface Post {
  id: string
  teamId: string
  userId: string
  author: string
  body: string
  ts: number
}

export interface Dividend {
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
