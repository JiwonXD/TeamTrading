package com.fanchart.match;

import java.time.Instant;

/**
 * 내가 받은 배당 한 건. 경기 배당은 kind가 WIN 또는 DRAW이고 matchId가 있다.
 * 대회 성적 수당은 kind가 성적(CHAMPION 등)이고 awardId가 있다. shares는 기준 시각의 보유 수량, competition은 경기·성적의 대회
 */
public record MyDividend(Long matchId, Long awardId, String competition, String teamId, String kind, long shares,
                         long amount, Instant paidAt) {
}
