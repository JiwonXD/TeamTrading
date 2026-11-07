package com.teamtrading.match;

import java.time.Instant;

/** 경기별·구단별 배당 지급 요약 */
public record DividendRow(long matchId, String teamId, String kind, long dps, long shares, int holders, long total,
                          Instant paidAt) {
}
