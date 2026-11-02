package com.teamtrading.match;

import java.time.Instant;

/** 내가 받은 배당 한 건. kind는 WIN 또는 DRAW, shares는 기준 시각의 보유 수량 */
public record MyDividend(long matchId, String teamId, String kind, long shares, long amount, Instant paidAt) {
}
