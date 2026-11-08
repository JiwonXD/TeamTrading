import { useEffect, useState } from 'react'

/** 매일 점검시간(서버가 알려준 한국 시간 HH:mm). 이 시간에는 서버가 주문을 거절하므로 화면은 매매 버튼을 막고 안내한다 */
export interface MaintenanceWindow { start: string; end: string; timeZone: string }

/** 서버 모드에서 받아 둔다. 시연·시뮬레이션처럼 점검시간이 없으면 null */
export const MAINTENANCE: { window: MaintenanceWindow | null } = { window: null }

export const MAINTENANCE_MESSAGE = '매일 00:00~00:30은 점검시간이라 주문할 수 없어요. 00:30부터 다시 거래할 수 있어요'

/** 그 시각이 점검시간(시작 포함, 끝 미포함)인지. 기기 시간대와 상관없이 창의 시간대(한국 시간)로 본다 */
export function inMaintenance(w: MaintenanceWindow | null, now: Date): boolean {
  if (!w) return false
  const hhmm = new Intl.DateTimeFormat('en-GB', { timeZone: w.timeZone, hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).format(now)
  return hhmm >= w.start && hhmm < w.end
}

/** 지금 점검시간인지. 시각이 바뀌면 다시 그리도록 몇 초마다 확인한다 */
export function useMaintenance(): boolean {
  const [now, setNow] = useState(() => new Date())
  useEffect(() => {
    const id = setInterval(() => setNow(new Date()), 5000)
    return () => clearInterval(id)
  }, [])
  return inMaintenance(MAINTENANCE.window, now)
}
