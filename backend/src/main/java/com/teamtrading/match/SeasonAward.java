package com.teamtrading.match;

import com.teamtrading.team.League;

import java.time.Instant;

/**
 * 대회 최종 성적 수당 한 건. 운영자 id(createdBy, decidedBy)는 감사용이라 공개 응답에 넣지 않는다
 *
 * @param recordAt 기준 시점. 이 시각에 보유한 주식(매도 주문에 묶인 몫 포함)에 준다
 */
public record SeasonAward(long id, League league, String season, String teamId, Award award, long dps,
                          Instant recordAt, Status status, String sourceUrl, String createdBy, Instant createdAt,
                          String decidedBy, Instant decidedAt, Long shares, Integer holders, Long total) {

    public enum Status {
        PENDING, PAID, VOID
    }

    /** 성적. placement는 구단당 시즌에 하나(우승·준우승·4강·포스트시즌 중 최종 성적), 정규 시즌 1위는 따로 받는다 */
    public enum Award {
        CHAMPION(1.0, true, true),
        RUNNER_UP(0.6, true, true),
        SEMIFINAL(0.35, true, false),
        POSTSEASON(0.15, true, false),
        REGULAR_FIRST(0.3, false, true);

        private final double multiple;
        private final boolean placement;
        private final boolean title;

        Award(double multiple, boolean placement, boolean title) {
            this.multiple = multiple;
            this.placement = placement;
            this.title = title;
        }

        /** 기준 금액 W의 배수 */
        public double multiple() {
            return multiple;
        }

        /** 최종 성적이라 구단당 시즌에 하나만 받는지 */
        public boolean placement() {
            return placement;
        }

        /** 리그 시즌에 한 구단만 받는지(우승, 준우승, 정규 시즌 1위) */
        public boolean title() {
            return title;
        }
    }
}
