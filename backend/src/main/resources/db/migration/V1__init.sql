-- 잔고와 수량은 음수가 될 수 없다. 코드의 조건부 UPDATE가 1차 방어선이고 CHECK가 마지막 방어선이다.

CREATE TABLE accounts (
    id               VARCHAR(36)  NOT NULL PRIMARY KEY,
    nickname         VARCHAR(20)  NOT NULL,
    initial_points   BIGINT       NOT NULL,
    available_points BIGINT       NOT NULL,
    locked_points    BIGINT       NOT NULL DEFAULT 0,
    created_at       TIMESTAMP(6) NOT NULL,
    CONSTRAINT ck_accounts_available CHECK (available_points >= 0),
    CONSTRAINT ck_accounts_locked CHECK (locked_points >= 0)
);

CREATE TABLE positions (
    account_id    VARCHAR(36) NOT NULL,
    team_id       VARCHAR(20) NOT NULL,
    available     BIGINT      NOT NULL,
    locked        BIGINT      NOT NULL DEFAULT 0,
    average_price BIGINT      NOT NULL,
    PRIMARY KEY (account_id, team_id),
    CONSTRAINT fk_positions_account FOREIGN KEY (account_id) REFERENCES accounts (id),
    CONSTRAINT ck_positions_available CHECK (available >= 0),
    CONSTRAINT ck_positions_locked CHECK (locked >= 0)
);

-- status: PENDING(접수·예치만 됨) → OPEN(호가창 대기) | FILLED | CANCELED
CREATE TABLE orders (
    id                   BIGINT       NOT NULL PRIMARY KEY,
    account_id           VARCHAR(36)  NOT NULL,
    client_order_id      VARCHAR(64)  NOT NULL,
    team_id              VARCHAR(20)  NOT NULL,
    side                 VARCHAR(4)   NOT NULL,
    order_type           VARCHAR(6)   NOT NULL,
    price                BIGINT       NOT NULL,
    quantity             BIGINT       NOT NULL,
    remaining            BIGINT       NOT NULL,
    reserve_per_share    BIGINT       NOT NULL,
    status               VARCHAR(8)   NOT NULL,
    filled_quantity      BIGINT       NOT NULL DEFAULT 0,
    resting_quantity     BIGINT       NOT NULL DEFAULT 0,
    canceled_quantity    BIGINT       NOT NULL DEFAULT 0,
    self_trade_prevented BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at           TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_orders_account FOREIGN KEY (account_id) REFERENCES accounts (id),
    CONSTRAINT uk_orders_client_order UNIQUE (account_id, client_order_id),
    CONSTRAINT ck_orders_remaining CHECK (remaining >= 0 AND remaining <= quantity)
);

CREATE INDEX ix_orders_team_status ON orders (team_id, status, id);
CREATE INDEX ix_orders_account_status ON orders (account_id, status);

CREATE TABLE trades (
    id            BIGINT       NOT NULL PRIMARY KEY,
    team_id       VARCHAR(20)  NOT NULL,
    price         BIGINT       NOT NULL,
    quantity      BIGINT       NOT NULL,
    buyer_id      VARCHAR(36)  NOT NULL,
    seller_id     VARCHAR(36)  NOT NULL,
    buy_order_id  BIGINT       NOT NULL,
    sell_order_id BIGINT       NOT NULL,
    aggressor     VARCHAR(4)   NOT NULL,
    executed_at   TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_trades_buy_order FOREIGN KEY (buy_order_id) REFERENCES orders (id),
    CONSTRAINT fk_trades_sell_order FOREIGN KEY (sell_order_id) REFERENCES orders (id)
);

CREATE INDEX ix_trades_team ON trades (team_id, id);
