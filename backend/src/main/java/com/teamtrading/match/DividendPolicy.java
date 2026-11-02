package com.teamtrading.match;

import com.teamtrading.team.League;

/**
 * 주당 배당금(DPS). 승리 배당은 절대값으로 준다(docs/adr/0010).
 * 리그마다 '평균 팀의 시즌 승리 배당 총액'이 약 3,000P가 되도록 평균 승수로 역산한 시작값이다.
 * 무승부는 양 팀에 승리 배당의 절반.
 */
public final class DividendPolicy {

    private DividendPolicy() {
    }

    public static long winDps(League league) {
        return switch (league) {
            case KBO -> 40;         // 144경기, 평균 72승
            case K_LEAGUE -> 210;   // 38경기, 평균 약 14승
            case LCK -> 160;        // 정규 시즌 평균 약 18승
        };
    }

    public static long drawDps(League league) {
        return winDps(league) / 2;
    }
}
