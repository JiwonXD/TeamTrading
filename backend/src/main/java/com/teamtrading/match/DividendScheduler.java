package com.teamtrading.match;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** 1분마다 확정 대기가 끝난 경기의 배당을 지급한다 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "teamtrading.dividends.auto-pay", havingValue = "true", matchIfMissing = true)
class DividendScheduler {

    private final MatchService matches;

    DividendScheduler(MatchService matches) {
        this.matches = matches;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 10_000)
    void payDue() {
        matches.payDue();
    }
}
