// 화면이 쓰는 저장소. 빌드할 때 VITE_BACKEND=server면 서버에 붙고, 아니면 브라우저 안에서 시뮬레이션한다.
// 미리보기(단일 HTML)는 서버 없이 돌아야 하므로 시뮬레이션이 기본값이다.
import { useSyncExternalStore } from 'react'
import * as server from './store.server'
import * as sim from './store.sim'
import { getVersion, subscribe } from './storeCore'
import type { OrderType, Side } from './engine/orderbook'
import type { OrderResult, State } from './storeTypes'
import type { LeagueTabPrefs } from './data/leagueTabs'

export { ME, START_POINTS } from './storeTypes'
export { getToast, showToast } from './storeCore'

export type { ReportReason } from './store.server'
export type { AdStatusDto, Connection, LeaderboardDto, LeaderboardPeriod, LeaderboardSort, LoginOptions, MatchDto, OfferingDto, SubscriptionDto } from './store.server'

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
/** 토론 글 쓰기. 서버 모드는 결과 메시지를, 시뮬레이션 모드는 undefined를 돌려준다 */
export const addPost = (teamId: string, body: string): Promise<OrderResult | void> => Promise.resolve(impl.addPost(teamId, body))
export const deletePost = server.deletePost
export const reportPost = server.reportPost
export const reset = impl.reset

export const placeOrder = (userId: string, teamId: string, side: Side, type: OrderType, price: number, qty: number): Promise<OrderResult> =>
  Promise.resolve(impl.placeOrder(userId, teamId, side, type, price, qty))

export const cancel = (userId: string, teamId: string, orderId: string): Promise<void> =>
  Promise.resolve(impl.cancel(userId, teamId, orderId))

export const settleMatches = impl.settleMatches

/** 서버 모드에서 로그인이 필요하면 로그인 방법, 아니면 null */
export const getLogin = (): server.LoginOptions | null => (mode === 'server' ? server.getLogin() : null)
export const devLogin = server.devLogin

/** 서버 모드의 연결 상태. 시뮬레이션 모드는 늘 live */
export const getConnection = (): server.Connection =>
  mode === 'server' ? server.getConnection() : { status: 'live', lastSync: null }

/** 서버 모드의 최근 경기(실제 또는 시연용). 시뮬레이션 모드는 null */
export const getMatches = (): server.MatchDto[] | null => (mode === 'server' ? server.getMatches() : null)

export const fetchLeaderboard = server.fetchLeaderboard
export const fetchSchedule = impl.fetchSchedule

/** 서버 모드의 공모 목록과 내 청약. 시뮬레이션 모드는 null */
export const getOfferings = (): server.OfferingDto[] | null => (mode === 'server' ? server.getOfferings() : null)
export const getMySubscriptions = (): server.SubscriptionDto[] => (mode === 'server' ? server.getMySubscriptions() : [])
export const subscribeOffering = server.subscribeOffering
export const cancelSubscription = server.cancelSubscription

/** 서버 모드의 광고 보상 상태. 시뮬레이션 모드는 null */
export const getAdStatus = (): server.AdStatusDto | null => (mode === 'server' ? server.getAdStatus() : null)
export const openAd = server.openAd
export const finishDemoAd = server.finishDemoAd

/** 서버 모드의 내 닉네임. 시뮬레이션 모드는 null */
export const getProfile = (): ReturnType<typeof server.getProfile> => (mode === 'server' ? server.getProfile() : null)
export const changeNickname = server.changeNickname

/** 시장감시(이상거래 탐지 기록)를 볼 수 있는지. 서버 모드는 운영자만, 시뮬레이션 미리보기는 운영자 화면 예시로 보여준다 */
export const canWatch = (): boolean => (mode === 'server' ? server.isAdmin() : true)
export const withdraw = server.withdraw

/** 홈 리그 탭 순서·숨김. 서버 모드는 계정에, 시뮬레이션 모드는 이 브라우저에 저장한다 */
export const getLeagueTabs = impl.getLeagueTabs
export const saveLeagueTabs = (prefs: LeagueTabPrefs): Promise<OrderResult> => Promise.resolve(impl.saveLeagueTabs(prefs))

/** 서버 모드에서는 보고 있는 구단의 실시간 시세를 구독한다. 반환값은 구독 해제 */
export const watchTeam = (teamId: string): (() => void) => (mode === 'server' ? server.watchTeam(teamId) : () => {})
