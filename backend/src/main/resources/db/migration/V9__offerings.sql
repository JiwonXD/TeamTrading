-- 공모. 구단 주식을 처음(또는 추가로) 풀 때 청약을 받아 배정한다.
-- status: SUBSCRIBING → ALLOCATED | CANCELED.
-- first_listing: 등록할 때 그 구단 주식이 하나도 없었으면 첫 상장이다. 배정되고 상장 시각이 될 때까지 그 구단은 거래할 수 없다
CREATE TABLE offerings (
    id               BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    team_id          VARCHAR(20)  NOT NULL,
    round            INT          NOT NULL,
    price            BIGINT       NOT NULL,
    total_shares     BIGINT       NOT NULL,
    max_per_account  BIGINT       NOT NULL,
    subscribe_from   TIMESTAMP(6) NOT NULL,
    subscribe_to     TIMESTAMP(6) NOT NULL,
    listing_at       TIMESTAMP(6) NOT NULL,
    first_listing    BOOLEAN      NOT NULL,
    status           VARCHAR(12)  NOT NULL,
    allocated_shares BIGINT       NOT NULL DEFAULT 0,
    created_at       TIMESTAMP(6) NOT NULL,
    allocated_at     TIMESTAMP(6) NULL,
    CONSTRAINT uk_offerings_round UNIQUE (team_id, round),
    CONSTRAINT ck_offerings_terms CHECK (price > 0 AND total_shares > 0 AND max_per_account > 0
        AND max_per_account <= total_shares AND subscribe_from < subscribe_to AND subscribe_to <= listing_at),
    CONSTRAINT ck_offerings_allocated CHECK (allocated_shares >= 0 AND allocated_shares <= total_shares)
);

CREATE INDEX ix_offerings_status ON offerings (status, subscribe_to);

-- 청약. 계정당 공모 하나에 한 건. 청약 때 수량 × 공모가를 묶고, 배정 때 배정분 대금은 소각하고 나머지는 돌려준다
CREATE TABLE subscriptions (
    offering_id BIGINT       NOT NULL,
    account_id  VARCHAR(36)  NOT NULL,
    quantity    BIGINT       NOT NULL,
    deposit     BIGINT       NOT NULL,
    allocated   BIGINT       NULL,
    created_at  TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (offering_id, account_id),
    CONSTRAINT fk_subscriptions_offering FOREIGN KEY (offering_id) REFERENCES offerings (id),
    CONSTRAINT fk_subscriptions_account FOREIGN KEY (account_id) REFERENCES accounts (id),
    CONSTRAINT ck_subscriptions CHECK (quantity > 0 AND deposit > 0 AND (allocated IS NULL OR allocated BETWEEN 0 AND quantity))
);

CREATE INDEX ix_subscriptions_account ON subscriptions (account_id);
