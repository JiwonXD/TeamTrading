-- 이상거래 탐지 기록. 체결과 같은 트랜잭션에서 남겨, 체결이 있으면 그 체결의 탐지 기록도 반드시 있다(재기동해도 사라지지 않는다)
CREATE TABLE fds_alerts (
    id          BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    kind        VARCHAR(24)   NOT NULL,
    team_id     VARCHAR(20)   NOT NULL,
    trade_id    BIGINT        NOT NULL,
    account_a   VARCHAR(36)   NOT NULL,
    account_b   VARCHAR(36)   NOT NULL,
    message     VARCHAR(300)  NOT NULL,
    detected_at TIMESTAMP(6)  NOT NULL,
    CONSTRAINT fk_fds_alerts_trade FOREIGN KEY (trade_id) REFERENCES trades (id)
);

CREATE INDEX ix_fds_alerts_trade ON fds_alerts (trade_id);
CREATE INDEX ix_fds_alerts_detected ON fds_alerts (detected_at);
