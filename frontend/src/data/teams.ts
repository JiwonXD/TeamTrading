// 목업 데이터. 가격은 포인트(P) 단위이며 실제 구단 가치와 무관하다.

export type League = 'KBO' | 'K리그' | 'LCK'

export interface Team {
  id: string
  name: string
  short: string
  league: League
  color: string
  basePrice: number // 전일 종가. 가격제한폭의 기준
}

export const TEAMS: Team[] = [
  { id: 'lg', name: 'LG 트윈스', short: 'LG', league: 'KBO', color: '#c30452', basePrice: 18450 },
  { id: 'kia', name: 'KIA 타이거즈', short: 'KIA', league: 'KBO', color: '#ea0029', basePrice: 21300 },
  { id: 'samsung', name: '삼성 라이온즈', short: '삼성', league: 'KBO', color: '#074ca1', basePrice: 16200 },
  { id: 'doosan', name: '두산 베어스', short: '두산', league: 'KBO', color: '#131230', basePrice: 14850 },
  { id: 'kt', name: 'KT 위즈', short: 'KT', league: 'KBO', color: '#231f20', basePrice: 12900 },
  { id: 'ssg', name: 'SSG 랜더스', short: 'SSG', league: 'KBO', color: '#ce0e2d', basePrice: 13750 },
  { id: 'lotte', name: '롯데 자이언츠', short: '롯데', league: 'KBO', color: '#041e42', basePrice: 15400 },
  { id: 'hanwha', name: '한화 이글스', short: '한화', league: 'KBO', color: '#fc4e00', basePrice: 19800 },
  { id: 'nc', name: 'NC 다이노스', short: 'NC', league: 'KBO', color: '#315288', basePrice: 11600 },
  { id: 'kiwoom', name: '키움 히어로즈', short: '키움', league: 'KBO', color: '#820024', basePrice: 8740 },
  { id: 'ulsan', name: '울산 HD', short: '울산', league: 'K리그', color: '#1d4ea3', basePrice: 17300 },
  { id: 'jeonbuk', name: '전북 현대', short: '전북', league: 'K리그', color: '#1b7a3a', basePrice: 15950 },
  { id: 'seoul', name: 'FC서울', short: '서울', league: 'K리그', color: '#b3182a', basePrice: 14100 },
  { id: 'pohang', name: '포항 스틸러스', short: '포항', league: 'K리그', color: '#d6001c', basePrice: 9980 },
  { id: 't1', name: 'T1', short: 'T1', league: 'LCK', color: '#e2012d', basePrice: 32500 },
  { id: 'geng', name: '젠지', short: 'GEN', league: 'LCK', color: '#aa8a00', basePrice: 28700 },
  { id: 'hle', name: '한화생명e스포츠', short: 'HLE', league: 'LCK', color: '#f37321', basePrice: 21450 },
  { id: 'dk', name: '디플러스 기아', short: 'DK', league: 'LCK', color: '#0a8f8f', basePrice: 17850 },
]

export const teamById = (id: string) => TEAMS.find((t) => t.id === id)!

export interface Match {
  id: string
  league: League
  home: string
  away: string
  homeScore: number
  awayScore: number
}

// 경기 결과 배당은 첫 버전에서 목업. 승리 구단 보유자에게 주당 기준가의 0.5%를 지급한다
export const DIVIDEND_RATE = 0.005

export const TODAY_MATCHES: Match[] = [
  { id: 'm1', league: 'KBO', home: 'lg', away: 'kia', homeScore: 5, awayScore: 3 },
  { id: 'm2', league: 'KBO', home: 'samsung', away: 'doosan', homeScore: 2, awayScore: 7 },
  { id: 'm3', league: 'KBO', home: 'hanwha', away: 'ssg', homeScore: 4, awayScore: 1 },
  { id: 'm4', league: 'K리그', home: 'ulsan', away: 'jeonbuk', homeScore: 1, awayScore: 1 },
  { id: 'm5', league: 'LCK', home: 't1', away: 'geng', homeScore: 3, awayScore: 2 },
]

export const SEED_POSTS: { teamId: string; author: string; body: string; minutesAgo: number }[] = [
  { teamId: 'lg', author: '잠실불꽃', body: '선발 로테이션 안정적이라 장기 보유 갑니다', minutesAgo: 12 },
  { teamId: 'lg', author: '쌍둥이아빠', body: '오늘 이기면 배당 들어오는 거 맞죠?', minutesAgo: 45 },
  { teamId: 'kia', author: '광주호랑이', body: '어제 졌다고 던지는 분들 많네요 저는 줍줍', minutesAgo: 8 },
  { teamId: 'hanwha', author: '보살팬', body: '이번 시즌은 진짜 다르다... 매수 완료', minutesAgo: 20 },
  { teamId: 't1', author: '페이커장인', body: '월즈 시즌엔 무조건 오릅니다', minutesAgo: 5 },
  { teamId: 't1', author: 'midgap', body: '상한가 근처라 일부 익절했어요', minutesAgo: 33 },
  { teamId: 'ulsan', author: '호랑이굴', body: '무승부라 아쉽네요', minutesAgo: 60 },
]
