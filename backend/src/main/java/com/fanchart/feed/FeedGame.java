package com.fanchart.feed;

import java.time.Instant;

/**
 * 제공처 경기 하나를 우리 쪽 말로 옮긴 것.
 *
 * @param ref       제공처의 경기 번호. 같은 경기는 늘 같은 번호다
 * @param rawStatus 제공처 상태 코드(기록·로그용)
 * @param homeScore FINAL일 때만 의미가 있다
 */
public record FeedGame(String ref, String homeTeamId, String homeName, String awayTeamId, String awayName,
                       Instant scheduledAt, State state, String rawStatus, Integer homeScore, Integer awayScore) {

    /** 우리가 행동을 정하는 상태. 모르는 코드는 UNKNOWN으로 두고 아무것도 하지 않는다 */
    public enum State { NOT_STARTED, LIVE, FINAL, POSTPONED, CANCELED, UNKNOWN }
}
