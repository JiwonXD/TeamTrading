// 두 저장소 구현(브라우저 시뮬레이션, 서버)이 함께 쓰는 변경 알림과 토스트.
let version = 0
const listeners = new Set<() => void>()
let toast: { msg: string; id: number } | null = null

export function emit() {
  version++
  listeners.forEach((l) => l())
}

export function subscribe(l: () => void) {
  listeners.add(l)
  return () => {
    listeners.delete(l)
  }
}

export const getVersion = () => version

export function showToast(msg: string) {
  toast = { msg, id: Date.now() }
  emit()
  setTimeout(() => {
    if (toast && Date.now() - toast.id >= 2400) {
      toast = null
      emit()
    }
  }, 2500)
}

export const getToast = () => toast
