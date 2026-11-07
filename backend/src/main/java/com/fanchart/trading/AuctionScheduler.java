package com.fanchart.trading;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

/** 1초마다 상장 시각이 된 구단의 동시호가를 체결한다. 주문이 먼저 들어오면 접수할 때 체결한다 */
@Configuration
@ConditionalOnProperty(name = "fanchart.auction.auto-open", havingValue = "true", matchIfMissing = true)
class AuctionScheduler {

    private final TradingService trading;

    AuctionScheduler(TradingService trading) {
        this.trading = trading;
    }

    @Scheduled(fixedDelay = 1_000, initialDelay = 3_000)
    void openDue() {
        trading.openDueAuctions();
    }
}
