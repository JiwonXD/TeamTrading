package com.fanchart.team;

/**
 * 구단이 참가하는 대회. 리그(정규 시즌)는 소속 리그와 같고, 컵대회는 여러 리그의 구단과 상장하지 않은 팀이 섞인다.
 * 이름은 DB(matches.competition, season_awards.competition)에 그대로 들어가므로 바꾸지 않는다.
 * weight는 컵대회 경기 승리 배당과 성적 수당에 곱하는 대회 배수다(리그는 1). 시즌 전에 공시하고 시즌 중에는 바꾸지 않는다.
 */
public enum Competition {

    KBO(League.KBO, 1.0),
    K_LEAGUE(League.K_LEAGUE, 1.0),
    LCK(League.LCK, 1.0),
    EPL(League.EPL, 1.0),
    LA_LIGA(League.LA_LIGA, 1.0),
    SERIE_A(League.SERIE_A, 1.0),
    BUNDESLIGA(League.BUNDESLIGA, 1.0),
    NBA(League.NBA, 1.0),

    // 리그 오브 레전드
    LCK_CUP(null, 0.5),
    MSI(null, 1.0),
    WORLDS(null, 1.5),
    EWC(null, 0.5),

    // 유럽 클럽 대회
    UCL(null, 1.5),
    UEL(null, 0.75),
    UECL(null, 0.5),
    // 유럽 국내 컵
    FA_CUP(null, 0.5),
    EFL_CUP(null, 0.3),
    COPA_DEL_REY(null, 0.5),
    COPPA_ITALIA(null, 0.5),
    DFB_POKAL(null, 0.5),

    // K리그 구단
    KOREA_CUP(null, 0.5),
    ACL(null, 1.0);

    private final League league;
    private final double weight;

    Competition(League league, double weight) {
        this.league = league;
        this.weight = weight;
    }

    /** 리그면 그 리그, 컵대회면 null */
    public League league() {
        return league;
    }

    public boolean cup() {
        return league == null;
    }

    public double weight() {
        return weight;
    }

    /** 리그의 정규 시즌 대회 */
    public static Competition of(League league) {
        return valueOf(league.name());
    }
}
