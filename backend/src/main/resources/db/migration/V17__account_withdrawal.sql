-- 탈퇴. 계정 행은 원장·체결과 맞물려 있어 지우지 않고, 로그인 연결을 끊고 닉네임을 바꿔 누구 것인지 알 수 없게 한다.
-- withdrawn_identities: 탈퇴 직후 다시 가입해 가입 포인트를 거듭 받지 못하게, 로그인 식별 번호의 해시만 30일 남긴다
ALTER TABLE accounts ADD COLUMN withdrawn_at TIMESTAMP(6) NULL;

CREATE TABLE withdrawn_identities (
    identity_hash CHAR(64)     NOT NULL PRIMARY KEY,
    withdrawn_at  TIMESTAMP(6) NOT NULL
);
