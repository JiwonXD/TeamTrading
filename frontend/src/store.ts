// 화면이 쓰는 저장소. 빌드할 때 VITE_BACKEND=server면 서버에 붙고, 아니면 브라우저 안에서 시뮬레이션한다.
// 미리보기(단일 HTML)는 서버 없이 돌아야 하므로 시뮬레이션이 기본값이다.
import { useSyncExternalStore } from 'react'
import * as server from './store.server'
import * as sim from './store.sim'
import { getVersion, subscribe } from './storeCore'
import type { OrderType, Side } from './engine/orderbook'
import type { OrderResult, State } from './storeTypes'

export { ME, START_POINTS } from './storeTypes'
export { getToast, showToast } from './storeCore'

export const mode: 'server' | 'sim' = import.meta.env.VITE_BACKEND === 'server' ? 'server' : 'sim'
const impl = mode === 'server' ? server : sim
impl.start()

export function useStore(): State {
  useSyncExternalStore(subscribe, getVersion)
  return impl.getState()
}

export const totalShares = impl.totalShares
export const lockedPoints = impl.lockedPoints
export const totalValue = impl.totalValue
export const openOrders = impl.openOrders
export const addPost = impl.addPost
export const reset = impl.reset

export const placeOrder = (userId: string, teamId: string, side: Side, type: OrderType, price: number, qty: number): Promise<OrderResult> =>
  Promise.resolve(impl.placeOrder(userId, teamId, side, type, price, qty))

export const cancel = (userId: string, teamId: string, orderId: string): Promise<void> =>
  Promise.resolve(impl.cancel(userId, teamId, orderId))

export const settleMatches = impl.settleMatches

/** 서버 모드에서는 보고 있는 구단의 실시간 시세를 구독한다. 반환값은 구독 해제 */
export const watchTeam = (teamId: string): (() => void) => (mode === 'server' ? server.watchTeam(teamId) : () => {})
