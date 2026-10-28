package com.teamtrading.trading;

import com.teamtrading.team.Team;

public record Quote(Team team, long lastPrice, long high, long low, long volume, long upperLimit, long lowerLimit) {

    public double changeRate() {
        return (double) lastPrice / team.basePrice() - 1;
    }
}
