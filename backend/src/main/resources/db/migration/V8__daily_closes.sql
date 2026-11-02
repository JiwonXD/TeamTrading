-- 일별 종가와 계정별 일말 평가금. 리더보드 수익률(시간가중수익률)의 일 단위 구간이 된다.
-- 하루는 KST 자정 기준. 원장과 체결 기록에서 언제든 같은 값으로 다시 계산할 수 있어 늦게 계산해도 결과가 같다.
CREATE TABLE daily_closes (
    team_id    VARCHAR(20) NOT NULL,
    close_date DATE        NOT NULL,
    price      BIGINT      NOT NULL,
    PRIMARY KEY (team_id, close_date)
);

-- equity: 그날 마감 시각의 포인트(묶인 몫 포함) + 보유 주식 × 종가
-- inflow: 그날 들어온 외부 유입(가입·광고·게시글 보상). 배당은 투자 수익이라 넣지 않는다
CREATE TABLE equity_snapshots (
    account_id VARCHAR(36) NOT NULL,
    close_date DATE        NOT NULL,
    equity     BIGINT      NOT NULL,
    inflow     BIGINT      NOT NULL,
    PRIMARY KEY (account_id, close_date)
);

CREATE INDEX ix_equity_snapshots_date ON equity_snapshots (close_date);
CREATE INDEX ix_trades_team_time ON trades (team_id, executed_at);
CREATE INDEX ix_trades_time ON trades (executed_at);
CREATE INDEX ix_point_grants_time ON point_grants (granted_at);
