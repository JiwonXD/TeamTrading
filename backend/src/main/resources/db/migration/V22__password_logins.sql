-- 아이디·비밀번호 로그인(ADR 0037). 소셜 로그인과 같이 account_identities에 ('password', 아이디)로도 연결해
-- 탈퇴·재가입 제한을 똑같이 받는다. 비밀번호는 bcrypt 해시만 저장한다. 이메일은 비밀번호 재설정용 선택 항목이다.
-- failed_attempts·locked_until: 연속 5번 틀리면 15분 동안 그 아이디로 로그인하지 못한다(무차별 대입 방어)
CREATE TABLE password_logins (
    login_id        VARCHAR(20)  NOT NULL PRIMARY KEY,
    account_id      VARCHAR(36)  NOT NULL,
    password_hash   VARCHAR(100) NOT NULL,
    email           VARCHAR(254) NULL,
    failed_attempts INT          NOT NULL DEFAULT 0,
    locked_until    TIMESTAMP(6) NULL,
    created_at      TIMESTAMP(6) NOT NULL,
    CONSTRAINT uq_password_logins_account UNIQUE (account_id),
    CONSTRAINT fk_password_logins_account FOREIGN KEY (account_id) REFERENCES accounts (id)
);

-- 비밀번호 재설정 토큰. 메일로 보낸 토큰 원문은 저장하지 않고 SHA-256 해시만 둔다. 30분 안에 한 번만 쓸 수 있다
CREATE TABLE password_reset_tokens (
    token_hash CHAR(64)     NOT NULL PRIMARY KEY,
    login_id   VARCHAR(20)  NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    used_at    TIMESTAMP(6) NULL
);

CREATE INDEX ix_password_reset_tokens_login ON password_reset_tokens (login_id);
