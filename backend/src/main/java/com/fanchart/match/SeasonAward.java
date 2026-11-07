package com.fanchart.match;

import com.fanchart.team.Competition;
import com.fanchart.team.League;

import java.time.Instant;

/**
 * 대회 최종 성적 수당 한 건. 운영자 id(createdBy, decidedBy)는 감사용이라 공개 응답에 넣지 않는다
 *
 * @param league      구단의 소속 리그
 * @param competition 성적을 낸 대회(리그 정규 시즌 또는 컵대회)
 * @param recordAt    기준 시점. 이 시각에 보유한 주식(매도 주문에 묶인 몫 포함)에 준다
 */
public record SeasonAward(long id, League league, Competition competition, String season, String teamId, Award award, long dps,
                          Instant recordAt, Status status, String sourceUrl, String createdBy, Instant createdAt,
                          String decidedBy, Instant decidedAt, Long shares, Integer holders, Long total) {

    public enum Status {
        PENDING, PAID, VOID
    }

    /**
     * 성적. placement는 구단당 대회 시즌에 하나(우승·준우승·4강·8강·포스트시즌 중 최종 성적), 정규 시즌 1위는 따로 받는다.
     * POSTSEASON은 컵대회에서 본선(토너먼트) 진출을 뜻한다
     */
    public enum Award {
        CHAMPION(1.0, true, true),
        RUNNER_UP(0.6, true, true),
        SEMIFINAL(0.35, true, false),
        QUARTERFINAL(0.25, true, false),
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

        /** 최종 성적이라 구단당 대회 시즌에 하나만 받는지 */
        public boolean placement() {
            return placement;
        }

        /** 대회 시즌에 한 구단만 받는지(우승, 준우승, 정규 시즌 1위) */
        public boolean title() {
            return title;
        }
    }
}
