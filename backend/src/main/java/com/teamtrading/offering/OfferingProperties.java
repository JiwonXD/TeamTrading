package com.teamtrading.offering;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** @param autoAllocate 청약 마감이 지난 공모를 주기적으로 배정할지 */
@ConfigurationProperties("teamtrading.offerings")
public record OfferingProperties(Boolean autoAllocate) {

    public OfferingProperties {
        if (autoAllocate == null) autoAllocate = true;
    }
}
