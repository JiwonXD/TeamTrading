-- 경기 데이터 제공처의 구단 번호. 제공처가 보낸 경기의 홈·원정 구단을 우리 구단으로 옮길 때 쓴다.
-- 한 제공처의 한 구단 번호는 우리 구단 하나에만 붙는다
ALTER TABLE teams ADD COLUMN feed_source VARCHAR(20) NULL;
ALTER TABLE teams ADD COLUMN feed_team_id VARCHAR(40) NULL;

CREATE UNIQUE INDEX uk_teams_feed ON teams (feed_source, feed_team_id);
