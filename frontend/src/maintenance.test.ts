import { describe, expect, it } from 'vitest'
import { inMaintenance } from './maintenance'

const KST = { start: '00:00', end: '00:30', timeZone: 'Asia/Seoul' }

describe('inMaintenance', () => {
  it('한국 시간 00:00부터 00:30 전까지만 점검시간이다', () => {
    expect(inMaintenance(KST, new Date('2026-11-02T14:59:59Z'))).toBe(false)
    expect(inMaintenance(KST, new Date('2026-11-02T15:00:00Z'))).toBe(true)
    expect(inMaintenance(KST, new Date('2026-11-02T15:29:59Z'))).toBe(true)
    expect(inMaintenance(KST, new Date('2026-11-02T15:30:00Z'))).toBe(false)
  })

  it('점검시간이 없는 서버(시연)는 늘 거래할 수 있다', () => {
    expect(inMaintenance(null, new Date('2026-11-02T15:10:00Z'))).toBe(false)
  })
})
