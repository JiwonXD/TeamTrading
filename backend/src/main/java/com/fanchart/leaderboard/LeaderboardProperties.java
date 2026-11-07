package com.fanchart.leaderboard;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 리더보드 자격. 몇 번만 사고 운 좋게 오른 계정이나 막 만든 계정이 순위를 채우지 않게 한다.
 *
 * @param minTrades 기간 안 최소 체결 횟수
 * @param minAge    가입 후 지나야 하는 시간
 */
@ConfigurationProperties("fanchart.leaderboard")
public record LeaderboardProperties(Integer minTrades, Duration minAge) {

    public LeaderboardProperties {
        if (minTrades == null) minTrades = 5;
        if (minAge == null) minAge = Duration.ofDays(7);
    }
}
