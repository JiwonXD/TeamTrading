-- 종목토론방 글. status: VISIBLE → HIDDEN(신고 누적·운영자) | DELETED(작성자)
-- reward: PENDING → PAID | NONE. 글을 쓰고 일정 시간 숨김 없이 남아 있으면 게시글 보상을 준다(하루 한도는 point_grants)
CREATE TABLE posts (
    id           BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    team_id      VARCHAR(20)  NOT NULL,
    account_id   VARCHAR(36)  NOT NULL,
    body         VARCHAR(300) NOT NULL,
    holder       BOOLEAN      NOT NULL,
    status       VARCHAR(8)   NOT NULL,
    reports      INT          NOT NULL DEFAULT 0,
    reward       VARCHAR(8)   NOT NULL DEFAULT 'PENDING',
    created_at   TIMESTAMP(6) NOT NULL,
    moderated_at TIMESTAMP(6) NULL,
    CONSTRAINT fk_posts_account FOREIGN KEY (account_id) REFERENCES accounts (id)
);

CREATE INDEX ix_posts_team ON posts (team_id, status, id);
CREATE INDEX ix_posts_account ON posts (account_id, created_at);
CREATE INDEX ix_posts_reward ON posts (reward, created_at);

-- 신고. 계정당 글 하나에 한 번
CREATE TABLE post_reports (
    post_id    BIGINT       NOT NULL,
    account_id VARCHAR(36)  NOT NULL,
    reason     VARCHAR(12)  NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (post_id, account_id),
    CONSTRAINT fk_post_reports_post FOREIGN KEY (post_id) REFERENCES posts (id),
    CONSTRAINT fk_post_reports_account FOREIGN KEY (account_id) REFERENCES accounts (id)
);
