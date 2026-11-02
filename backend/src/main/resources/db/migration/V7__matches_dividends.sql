-- 경기 일정·결과와 배당. 결과는 외부 데이터나 운영자 입력으로 들어온다.
-- status: SCHEDULED → FINAL | CANCELED / dividend_status: NONE → PAID | VOID, 지급 뒤 결과가 바뀌면 REVIEW
CREATE TABLE matches (
    id              BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    league          VARCHAR(12)  NOT NULL,
    home_team_id    VARCHAR(20)  NOT NULL,
    away_team_id    VARCHAR(20)  NOT NULL,
    scheduled_at    TIMESTAMP(6) NOT NULL,
    status          VARCHAR(10)  NOT NULL,
    home_score      INT          NULL,
    away_score      INT          NULL,
    final_at        TIMESTAMP(6) NULL,
    source          VARCHAR(20)  NOT NULL,
    source_ref      VARCHAR(64)  NOT NULL,
    dividend_status VARCHAR(10)  NOT NULL DEFAULT 'NONE',
    updated_at      TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_matches_source UNIQUE (source, source_ref),
    CONSTRAINT ck_matches_teams CHECK (home_team_id <> away_team_id)
);

CREATE INDEX ix_matches_due ON matches (status, dividend_status, final_at);
CREATE INDEX ix_matches_scheduled ON matches (scheduled_at);

-- 경기별·구단별 배당 지급 요약. 계정별 지급은 point_grants(source = DIVIDEND)에 있다
CREATE TABLE dividends (
    match_id  BIGINT       NOT NULL,
    team_id   VARCHAR(20)  NOT NULL,
    kind      VARCHAR(4)   NOT NULL,
    dps       BIGINT       NOT NULL,
    record_at TIMESTAMP(6) NOT NULL,
    shares    BIGINT       NOT NULL,
    holders   INT          NOT NULL,
    total     BIGINT       NOT NULL,
    paid_at   TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (match_id, team_id),
    CONSTRAINT fk_dividends_match FOREIGN KEY (match_id) REFERENCES matches (id)
);

-- 배당 지급의 멱등키는 경기:구단:계정이라 64자를 넘을 수 있다
ALTER TABLE point_grants MODIFY source_ref VARCHAR(100) NOT NULL;

-- 기준 시점의 보유 수량을 원장에서 되짚을 때 쓴다
CREATE INDEX ix_ledger_journals_created ON ledger_journals (created_at);
CREATE INDEX ix_ledger_entries_asset ON ledger_entries (asset, journal_id);
