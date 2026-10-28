-- 복식부기 원장. 포인트·주식이 움직일 때마다 전표 한 장에 자산별 합이 0인 분개를 남긴다.
-- 추가만 하고 수정·삭제하지 않는다. 잔고 테이블과 따로 쌓아서 대사로 서로를 검증한다.

CREATE TABLE ledger_journals (
    id         BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    kind       VARCHAR(12)  NOT NULL,
    order_id   BIGINT       NULL,
    trade_id   BIGINT       NULL,
    created_at TIMESTAMP(6) NOT NULL
);

-- owner_id: 계정 id, 발행처는 'SYSTEM' / asset: 'POINT' 또는 구단 id / bucket: AVAILABLE, LOCKED, ISSUED
CREATE TABLE ledger_entries (
    id         BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    journal_id BIGINT      NOT NULL,
    owner_id   VARCHAR(36) NOT NULL,
    asset      VARCHAR(20) NOT NULL,
    bucket     VARCHAR(9)  NOT NULL,
    amount     BIGINT      NOT NULL,
    CONSTRAINT fk_ledger_entries_journal FOREIGN KEY (journal_id) REFERENCES ledger_journals (id)
);

CREATE INDEX ix_ledger_entries_slot ON ledger_entries (owner_id, asset, bucket);
