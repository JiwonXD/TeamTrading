-- 광고 시청 세션. 앱이 광고를 띄우기 전에 세션을 만들고, 세션 id를 광고 네트워크의 custom_data로 넘긴다.
-- 네트워크의 서명된 서버 콜백이 오면 한 번만 완료된다: PENDING → PAID(지급) | LIMIT(하루 한도 초과).
-- 지급 기록은 point_grants(AD_VIEW, source_ref = transaction_ref)에 남는다
CREATE TABLE ad_sessions (
    id              VARCHAR(36)  NOT NULL PRIMARY KEY,
    account_id      VARCHAR(36)  NOT NULL,
    status          VARCHAR(8)   NOT NULL,
    created_at      TIMESTAMP(6) NOT NULL,
    completed_at    TIMESTAMP(6) NULL,
    transaction_ref VARCHAR(120) NULL,
    CONSTRAINT fk_ad_sessions_account FOREIGN KEY (account_id) REFERENCES accounts (id),
    CONSTRAINT uq_ad_sessions_transaction UNIQUE (transaction_ref)
);

CREATE INDEX ix_ad_sessions_account ON ad_sessions (account_id, created_at);
