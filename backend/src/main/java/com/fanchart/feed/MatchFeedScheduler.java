package com.fanchart.feed;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

/** 6시간마다 시즌 일정을, 15분마다 끝났을 경기의 결과를 받는다. 받을 대회(키가 있거나 키가 필요 없는 제공처)가 없으면 아무것도 하지 않는다 */
@Configuration
@ConditionalOnProperty(name = "fanchart.match-feed.auto-sync", havingValue = "true", matchIfMissing = true)
class MatchFeedScheduler {

    private final MatchFeedSync sync;

    MatchFeedScheduler(MatchFeedSync sync) {
        this.sync = sync;
    }

    @Scheduled(fixedDelayString = "PT6H", initialDelayString = "PT1M")
    void season() {
        sync.syncSeason();
    }

    @Scheduled(fixedDelayString = "PT15M", initialDelayString = "PT3M")
    void results() {
        sync.pollResults();
    }
}
