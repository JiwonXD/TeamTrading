import { describe, expect, it } from 'vitest'
import { DEFAULT_TABS, arrangeLeagues, visibleLeagues } from './leagueTabs'
import { LEAGUES } from './teams'

describe('league tab settings', () => {
  it('shows every league in the default order when nothing is saved', () => {
    expect(visibleLeagues(DEFAULT_TABS).map((l) => l.code)).toEqual(LEAGUES.map((l) => l.code))
  })

  it('follows the saved order and leaves hidden leagues out', () => {
    const codes = visibleLeagues({ order: ['LCK', 'EPL', 'KBO'], hidden: ['EPL'] }).map((l) => l.code)
    expect(codes.slice(0, 2)).toEqual(['LCK', 'KBO'])
    expect(codes).not.toContain('EPL')
  })

  it('appends leagues added after saving, shown, at the end', () => {
    // 저장할 때는 KBO·LCK만 있었다고 치면, 그 뒤 생긴 리그는 기본 순서대로 끝에 보인다
    const all = arrangeLeagues({ order: ['LCK', 'KBO'], hidden: [] })
    expect(all.map((e) => e.league.code)).toEqual(['LCK', 'KBO', ...LEAGUES.map((l) => l.code).filter((c) => c !== 'LCK' && c !== 'KBO')])
    expect(all.every((e) => !e.hidden)).toBe(true)
  })

  it('skips leagues that no longer exist', () => {
    const prefs = { order: ['GONE', 'LCK'], hidden: ['GONE'] } as unknown as Parameters<typeof arrangeLeagues>[0]
    expect(arrangeLeagues(prefs)[0].league.code).toBe('LCK')
    expect(arrangeLeagues(prefs)).toHaveLength(LEAGUES.length)
  })
})
