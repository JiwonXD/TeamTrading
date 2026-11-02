package com.teamtrading.match;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param confirmDelay 결과가 들어온 뒤 배당을 주기까지 기다리는 시간. 그 사이 결과 정정이 들어오면 정정된 결과로 준다
 * @param autoPay      주기적으로 확정된 경기의 배당을 지급할지
 */
@ConfigurationProperties("teamtrading.dividends")
public record DividendProperties(Duration confirmDelay, Boolean autoPay) {

    public DividendProperties {
        if (confirmDelay == null) confirmDelay = Duration.ofHours(6);
        if (autoPay == null) autoPay = true;
    }
}
