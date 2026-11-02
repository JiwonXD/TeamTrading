package com.teamtrading.trading;

import com.teamtrading.team.Team;

/**
 * @param basePrice 기준가(전일 종가). 등락률과 가격제한폭의 기준
 * @param listed    거래 중인지. 첫 상장 공모 전이거나 상장 동시호가를 기다리면 false
 */
public record Quote(Team team, long basePrice, long lastPrice, long high, long low, long volume, long upperLimit, long lowerLimit,
                    boolean listed) {

    public double changeRate() {
        return (double) lastPrice / basePrice - 1;
    }
}
