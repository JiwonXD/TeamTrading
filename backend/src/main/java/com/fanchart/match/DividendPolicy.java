package com.fanchart.match;

import com.fanchart.team.Competition;
import com.fanchart.team.League;

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
            case EPL, LA_LIGA, SERIE_A -> 210;  // 38경기, 평균 약 14승
            case BUNDESLIGA -> 240; // 34경기, 평균 약 12.5승
        };
    }

    public static long drawDps(League league) {
        return winDps(league) / 2;
    }

    /** 대회 경기 승리 배당. 구단의 소속 리그 승리 배당에 대회 배수를 곱하고 10P 단위로 반올림한다(리그 정규 시즌은 배수 1) */
    public static long winDps(League league, Competition competition) {
        return Math.round(winDps(league) * competition.weight() / 10) * 10;
    }

    public static long drawDps(League league, Competition competition) {
        return winDps(league, competition) / 2;
    }

    /** 대회 성적 수당의 기준 금액 W. 평균 팀의 시즌 승리 배당 총액(약 3,000P)과 같게 잡았다 */
    public static final long AWARD_BASE = 3_000;

    /**
     * 대회 성적 수당 주당 금액. W × 성적 배수(우승 1.0, 준우승 0.6, 4강 0.35, 8강 0.25, 포스트시즌·본선 진출 0.15,
     * 정규 시즌 1위 0.3) × 대회 배수(리그 1, 컵대회는 Competition.weight)
     */
    public static long awardDps(SeasonAward.Award award, Competition competition) {
        return Math.round(AWARD_BASE * award.multiple() * competition.weight());
    }
}
