package com.fanchart.match;

import com.fanchart.team.Competition;
import com.fanchart.team.League;

import java.time.Instant;

/**
 * 경기 하나. 컵대회에서는 한쪽이 상장하지 않은 팀일 수 있다. 그 쪽은 구단 id가 null이고 이름(homeName, awayName)만 있다.
 *
 * @param league      우리 구단 쪽 리그(홈이 우리 구단이면 홈). 리그 정규 시즌이면 대회의 리그와 같다
 * @param competition 대회
 */
public record Match(long id, League league, Competition competition, String homeTeamId, String homeName,
                    String awayTeamId, String awayName, Instant scheduledAt, Status status, Integer homeScore,
                    Integer awayScore, Instant finalAt, String source, String sourceRef,
                    DividendStatus dividendStatus) {

    public enum Status { SCHEDULED, FINAL, CANCELED }

    public enum DividendStatus { NONE, PAID, VOID, REVIEW }

    public boolean draw() {
        return status == Status.FINAL && homeScore.equals(awayScore);
    }

    /** 이긴 구단 id. 무승부이거나 상장하지 않은 팀이 이겼으면 null */
    public String winner() {
        if (status != Status.FINAL || draw()) return null;
        return homeScore > awayScore ? homeTeamId : awayTeamId;
    }
}
