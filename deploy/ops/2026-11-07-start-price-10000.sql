-- 2026-11-07 기존 구단의 임시 첫 기준가를 10,000P로 맞춘다(ADR 0031).
-- 운영자가 모든 구단을 10,000P에 매도(증자)하려면 기준가가 10,000P여야 가격제한폭(±30%) 안에 든다.
-- 체결이 한 번도 없는 구단만 바꾼다. 체결이 있는 구단은 그 가격이 시장 가격이라 그대로 둔다.
-- 일 마감 종가도 기준가가 되므로 체결 없이 기준가를 이어 적은 종가도 함께 바꾼다.
-- 적용한 뒤 백엔드를 다시 띄워야 반영된다. 여러 번 실행해도 결과가 같다.

UPDATE teams t SET start_price = 10000
WHERE t.start_price <> 10000 AND NOT EXISTS (SELECT 1 FROM trades x WHERE x.team_id = t.id);

UPDATE daily_closes d SET price = 10000
WHERE d.price <> 10000 AND NOT EXISTS (SELECT 1 FROM trades x WHERE x.team_id = d.team_id);

-- 바꾸지 않은 구단(체결 있음)과 그 구단의 보유 계정 수. 여기 나온 구단은 10,000P 매도가 가격제한폭을 벗어날 수 있다
SELECT t.id, t.start_price,
       (SELECT x.price FROM trades x WHERE x.team_id = t.id ORDER BY x.executed_at DESC, x.id DESC LIMIT 1) AS last_price,
       (SELECT COUNT(*) FROM positions p WHERE p.team_id = t.id AND p.available + p.locked > 0) AS holders
FROM teams t WHERE EXISTS (SELECT 1 FROM trades x WHERE x.team_id = t.id);
