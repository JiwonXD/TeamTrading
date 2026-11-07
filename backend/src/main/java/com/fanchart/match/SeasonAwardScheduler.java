package com.fanchart.match;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** 1분마다 확정 대기가 끝난 대회 성적 수당을 지급한다. 경기 배당과 같은 설정으로 켜고 끈다 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "fanchart.dividends.auto-pay", havingValue = "true", matchIfMissing = true)
class SeasonAwardScheduler {

    private final SeasonAwardService awards;

    SeasonAwardScheduler(SeasonAwardService awards) {
        this.awards = awards;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 20_000)
    void payDue() {
        awards.payDue();
    }
}
