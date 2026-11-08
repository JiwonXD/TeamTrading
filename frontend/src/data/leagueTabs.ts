import { CLOSED_LEAGUES, LEAGUES, type LeagueCode } from './teams'

/** 홈 리그 탭 설정. order는 보일 순서, hidden은 숨긴 리그. 둘 다 비어 있으면 기본 순서로 모두 보인다 */
export interface LeagueTabPrefs { order: LeagueCode[]; hidden: LeagueCode[] }

export const DEFAULT_TABS: LeagueTabPrefs = { order: [], hidden: [] }

export type LeagueEntry = (typeof LEAGUES)[number]

/**
 * 설정을 지금 있는 리그 목록(LEAGUES)에 맞춰 편다.
 * 설정에 없는 리그(저장한 뒤 새로 생긴 리그)는 맨 끝에 보이는 상태로 붙이고, 없어진 리그와 서버가 닫아 둔 리그는 건너뛴다.
 */
export function arrangeLeagues(prefs: LeagueTabPrefs): { league: LeagueEntry; hidden: boolean }[] {
  const open = LEAGUES.filter((l) => !CLOSED_LEAGUES.has(l.id))
  const byCode = new Map(open.map((l) => [l.code, l]))
  const ordered = prefs.order.flatMap((c) => byCode.get(c) ?? [])
  const rest = open.filter((l) => !prefs.order.includes(l.code))
  return [...ordered, ...rest].map((league) => ({ league, hidden: prefs.hidden.includes(league.code) }))
}

/** 탭에 보일 리그를 설정한 순서대로 */
export const visibleLeagues = (prefs: LeagueTabPrefs) => arrangeLeagues(prefs).filter((e) => !e.hidden).map((e) => e.league)
