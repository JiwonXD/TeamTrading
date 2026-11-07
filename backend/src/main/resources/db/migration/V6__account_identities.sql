-- 로그인 수단과 계정의 연결. 소셜 로그인 제공자(kakao, naver, google)가 준 고유 id(subject)로 계정을 찾는다.
-- 같은 제공자의 같은 사용자는 계정 하나에만 연결된다. 가입 보상을 여러 번 받을 수 없게 하는 첫 번째 장치다.
CREATE TABLE account_identities (
    provider   VARCHAR(20)  NOT NULL,
    subject    VARCHAR(128) NOT NULL,
    account_id VARCHAR(36)  NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (provider, subject),
    CONSTRAINT fk_account_identities_account FOREIGN KEY (account_id) REFERENCES accounts (id)
);

CREATE INDEX ix_account_identities_account ON account_identities (account_id);
