import type { Team } from '../data/teams'
import { getWatchlist, requireLogin, showToast, toggleWatch, useStore } from '../store'

/**
 * 관심 구단 별. 눌린 상태를 aria-pressed로 알리고, 줄 전체가 링크·버튼인 목록 안에서도
 * 별을 누르면 그 줄로 이동하지 않는다.
 */
export function WatchStar({ team, size = 22 }: { team: Team; size?: number }) {
  useStore()
  const on = getWatchlist().includes(team.id)
  return (
    <button
      type="button"
      className={`star-btn ${on ? 'on' : ''}`}
      aria-pressed={on}
      aria-label={`${team.name} 관심 구단`}
      title={on ? '관심 구단에서 빼기' : '관심 구단에 담기'}
      onClick={(e) => {
        e.preventDefault()
        e.stopPropagation()
        if (requireLogin()) toggleWatch(team.id).then((r) => showToast(r.message))
      }}
    >
      <svg width={size} height={size} viewBox="0 0 24 24" aria-hidden="true">
        <path
          d="M12 3.5l2.6 5.3 5.9.9-4.3 4.1 1 5.8L12 16.9l-5.2 2.7 1-5.8-4.3-4.1 5.9-.9z"
          fill={on ? 'currentColor' : 'none'}
          stroke="currentColor"
          strokeWidth="1.8"
          strokeLinejoin="round"
        />
      </svg>
    </button>
  )
}
