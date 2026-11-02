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

    /** 대회 성적 수당의 기준 금액 W. 평균 팀의 시즌 승리 배당 총액(약 3,000P)과 같게 잡았다 */
    public static final long AWARD_BASE = 3_000;

    /** 대회 성적 수당 주당 금액. 리그와 관계없이 W의 배수(우승 1.0, 준우승 0.6, 4강 0.35, 포스트시즌 0.15, 정규 시즌 1위 0.3) */
    public static long awardDps(SeasonAward.Award award) {
        return Math.round(AWARD_BASE * award.multiple());
    }
}
