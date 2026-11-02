package com.teamtrading.trading;

import com.teamtrading.team.Team;

/** @param basePrice 기준가(전일 종가). 등락률과 가격제한폭의 기준 */
public record Quote(Team team, long basePrice, long lastPrice, long high, long low, long volume, long upperLimit, long lowerLimit) {

    public double changeRate() {
        return (double) lastPrice / basePrice - 1;
    }
}
