import { describe, expect, it, vi } from 'vitest'
import { addTrade, bucketStart, savedMinuteUnit, saveMinuteUnit } from './candles'

const kst = (s: string) => Date.parse(`${s}+09:00`)

describe('bucketStart', () => {
  it('한국 시간 기준으로 봉 경계를 나눈다', () => {
    expect(bucketStart(kst('2026-11-08T10:04:59'), '5m')).toBe(kst('2026-11-08T10:00:00'))
    expect(bucketStart(kst('2026-11-08T09:30:00'), '240m')).toBe(kst('2026-11-08T08:00:00'))
    expect(bucketStart(kst('2026-11-08T23:59:59'), 'day')).toBe(kst('2026-11-08T00:00:00'))
    expect(bucketStart(kst('2026-11-09T00:00:01'), 'day')).toBe(kst('2026-11-09T00:00:00'))
    expect(bucketStart(kst('2026-11-08T12:00:00'), 'week')).toBe(kst('2026-11-02T00:00:00'))
    expect(bucketStart(kst('2026-11-08T12:00:00'), 'month')).toBe(kst('2026-11-01T00:00:00'))
    expect(bucketStart(kst('2026-11-08T12:00:00'), 'year')).toBe(kst('2026-01-01T00:00:00'))
  })
})

describe('addTrade', () => {
  it('같은 구간이면 마지막 봉을 갱신하고 다음 구간이면 새 봉을 연다', () => {
    let c = addTrade([], '1m', kst('2026-11-08T10:00:10'), 100, 1)
    c = addTrade(c, '1m', kst('2026-11-08T10:00:20'), 120, 2)
    c = addTrade(c, '1m', kst('2026-11-08T10:00:30'), 90, 1)
    c = addTrade(c, '1m', kst('2026-11-08T10:03:00'), 95, 4)
    expect(c).toEqual([
      { time: kst('2026-11-08T10:00:00'), open: 100, high: 120, low: 90, close: 90, volume: 4 },
      { time: kst('2026-11-08T10:03:00'), open: 95, high: 95, low: 95, close: 95, volume: 4 },
    ])
  })
})

describe('분 단위 기억', () => {
  it('처음엔 5분, 고른 단위는 다음에도 유지된다', () => {
    const m = new Map<string, string>()
    vi.stubGlobal('localStorage', { getItem: (k: string) => m.get(k) ?? null, setItem: (k: string, v: string) => void m.set(k, v) })
    expect(savedMinuteUnit()).toBe('5m')
    saveMinuteUnit('15m')
    expect(savedMinuteUnit()).toBe('15m')
    vi.unstubAllGlobals()
  })
})
