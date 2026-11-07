package com.fanchart.offering;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

/** 30초마다 청약이 마감된 공모를 배정한다 */
@Configuration
@ConditionalOnProperty(name = "fanchart.offerings.auto-allocate", havingValue = "true", matchIfMissing = true)
class OfferingScheduler {

    private final OfferingService offerings;

    OfferingScheduler(OfferingService offerings) {
        this.offerings = offerings;
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 5_000)
    void allocateDue() {
        offerings.allocateDue();
    }
}
