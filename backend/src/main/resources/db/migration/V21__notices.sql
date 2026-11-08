-- 구단 화면 '뉴스·공시' 탭의 공시(docs/adr/0034). 운영자만 쓰고 고치고 지운다.
-- team_id가 NULL이면 모든 구단 화면에 보이는 전체 공시다.
-- 지워도 행은 남긴다(deleted_at). 언제 무엇을 알렸는지는 이용자의 매수 판단 근거라 감사용으로 보관한다
CREATE TABLE notices (
    id         BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    team_id    VARCHAR(20)   NULL,
    title      VARCHAR(100)  NOT NULL,
    body       VARCHAR(3000) NOT NULL,
    created_by VARCHAR(36)   NOT NULL,
    created_at TIMESTAMP(6)  NOT NULL,
    updated_by VARCHAR(36)   NULL,
    updated_at TIMESTAMP(6)  NULL,
    deleted_by VARCHAR(36)   NULL,
    deleted_at TIMESTAMP(6)  NULL
);

CREATE INDEX ix_notices_team ON notices (team_id, id);
