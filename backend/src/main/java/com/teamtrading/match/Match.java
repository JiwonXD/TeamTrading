package com.teamtrading.match;

import com.teamtrading.team.League;

import java.time.Instant;

public record Match(long id, League league, String homeTeamId, String awayTeamId, Instant scheduledAt,
                    Status status, Integer homeScore, Integer awayScore, Instant finalAt, String source,
                    String sourceRef, DividendStatus dividendStatus) {

    public enum Status { SCHEDULED, FINAL, CANCELED }

    public enum DividendStatus { NONE, PAID, VOID, REVIEW }

    /** 이긴 구단 id. 무승부면 null */
    public String winner() {
        if (status != Status.FINAL || homeScore.equals(awayScore)) return null;
        return homeScore > awayScore ? homeTeamId : awayTeamId;
    }
}
