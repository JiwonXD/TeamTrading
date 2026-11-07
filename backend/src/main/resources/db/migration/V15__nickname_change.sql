-- 닉네임 변경. 한 닉네임은 한 계정만 쓴다(리더보드·토론방에서 남을 사칭하지 못하게).
-- 이미 겹친 닉네임은 먼저 가입한 계정이 그대로 쓰고, 나머지 계정은 뒤에 계정 id 앞 6자리를 붙인다.
UPDATE accounts SET nickname = CONCAT(LEFT(nickname, 13), '_', LEFT(REPLACE(id, '-', ''), 6))
WHERE id IN (SELECT id FROM (
    SELECT a.id FROM accounts a JOIN accounts b ON b.nickname = a.nickname
        AND (b.created_at < a.created_at OR (b.created_at = a.created_at AND b.id < a.id))) dup);

ALTER TABLE accounts ADD COLUMN nickname_changed_at TIMESTAMP(6) NULL;

CREATE UNIQUE INDEX uk_accounts_nickname ON accounts (nickname);
