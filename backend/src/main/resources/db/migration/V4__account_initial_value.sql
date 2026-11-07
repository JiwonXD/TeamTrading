-- 수익률의 기준. 시작 포인트에 시즌 초 지급받은 주식의 지급가 평가액을 더한 값
ALTER TABLE accounts ADD COLUMN initial_value BIGINT NOT NULL DEFAULT 0;
UPDATE accounts SET initial_value = initial_points;
