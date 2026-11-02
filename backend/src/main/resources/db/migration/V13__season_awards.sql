-- 대회 최종 성적 수당. 리그 최종 순위·토너먼트 성적에 따라 기준 시점(record_at) 보유자에게 주당 고정 금액을 준다.
-- 차등표는 DividendPolicy.awardDps(시즌 전에 공시, 시즌 중 바꾸지 않는다).
-- status: PENDING(확정 대기) → PAID | VOID(운영자 취소). 등록·지급·취소한 운영자와 근거 주소를 남긴다(감사).
-- placement_key: 진행 중이거나 지급한 성적 수당(우승·준우승·4강·포스트시즌)은 구단당 시즌에 하나. 취소하면 NULL
-- title_key: 우승·준우승은 리그 시즌에 한 구단. 취소하면 NULL
CREATE TABLE season_awards (
    id            BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    league        VARCHAR(12)  NOT NULL,
    season        VARCHAR(10)  NOT NULL,
    team_id       VARCHAR(20)  NOT NULL,
    award         VARCHAR(16)  NOT NULL,
    dps           BIGINT       NOT NULL,
    record_at     TIMESTAMP(6) NOT NULL,
    status        VARCHAR(8)   NOT NULL,
    source_url    VARCHAR(300) NOT NULL,
    created_by    VARCHAR(36)  NOT NULL,
    created_at    TIMESTAMP(6) NOT NULL,
    decided_by    VARCHAR(36)  NULL,
    decided_at    TIMESTAMP(6) NULL,
    shares        BIGINT       NULL,
    holders       INT          NULL,
    total         BIGINT       NULL,
    placement_key VARCHAR(80)  NULL,
    title_key     VARCHAR(80)  NULL,
    award_key     VARCHAR(80)  NULL,
    CONSTRAINT uk_season_awards_award UNIQUE (award_key),
    CONSTRAINT uk_season_awards_placement UNIQUE (placement_key),
    CONSTRAINT uk_season_awards_title UNIQUE (title_key)
);

CREATE INDEX ix_season_awards_due ON season_awards (status, created_at);
