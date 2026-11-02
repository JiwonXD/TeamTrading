-- 포인트 발행 기록. 포인트가 새로 생기는 모든 경로(가입, 광고 시청, 게시글, 이후 배당)는 여기에 한 줄을 남긴다.
-- (source, source_ref)가 멱등키다. 같은 광고 시청 콜백이나 같은 게시글로 두 번 지급할 수 없다.
-- 원장의 발행처(SYSTEM POINT ISSUED) 합과 이 테이블의 합이 같아야 하며 대사에서 확인한다.

CREATE TABLE point_grants (
    id         BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    account_id VARCHAR(36)  NOT NULL,
    source     VARCHAR(12)  NOT NULL,
    source_ref VARCHAR(64)  NOT NULL,
    amount     BIGINT       NOT NULL,
    journal_id BIGINT       NOT NULL,
    granted_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_point_grants_account FOREIGN KEY (account_id) REFERENCES accounts (id),
    CONSTRAINT fk_point_grants_journal FOREIGN KEY (journal_id) REFERENCES ledger_journals (id),
    CONSTRAINT uk_point_grants_source UNIQUE (source, source_ref),
    CONSTRAINT ck_point_grants_amount CHECK (amount > 0)
);

CREATE INDEX ix_point_grants_daily ON point_grants (account_id, source, granted_at);

-- 이미 있는 계정의 가입 지급분을 옮겨 적는다
INSERT INTO point_grants (account_id, source, source_ref, amount, journal_id, granted_at)
SELECT e.owner_id, 'SIGNUP', e.owner_id, e.amount, j.id, j.created_at
FROM ledger_journals j
JOIN ledger_entries e ON e.journal_id = j.id
WHERE j.kind = 'OPEN_ACCOUNT' AND e.owner_id <> 'SYSTEM' AND e.asset = 'POINT';
