-- 구단 중심 대회(docs/adr/0028). 한 구단이 소속 리그와 여러 컵대회에 참가한다.

-- 경기의 대회. 지금까지의 경기는 모두 리그 정규 시즌이라 대회 이름이 리그 이름과 같다
ALTER TABLE matches ADD COLUMN competition VARCHAR(16) NULL;
UPDATE matches SET competition = league;
ALTER TABLE matches MODIFY competition VARCHAR(16) NOT NULL;

-- 컵대회 상대는 상장하지 않은 팀일 수 있다. 그 쪽은 구단 id 없이 이름만 둔다(배당 없음).
-- league는 우리 구단(홈이 우리 구단이면 홈) 쪽의 리그다
ALTER TABLE matches MODIFY home_team_id VARCHAR(20) NULL;
ALTER TABLE matches MODIFY away_team_id VARCHAR(20) NULL;
ALTER TABLE matches ADD COLUMN home_name VARCHAR(60) NULL;
ALTER TABLE matches ADD COLUMN away_name VARCHAR(60) NULL;
ALTER TABLE matches ADD CONSTRAINT ck_matches_one_listed CHECK (home_team_id IS NOT NULL OR away_team_id IS NOT NULL);
CREATE INDEX ix_matches_home ON matches (home_team_id, scheduled_at);
CREATE INDEX ix_matches_away ON matches (away_team_id, scheduled_at);

-- 성적 수당의 대회. 고유 키(award_key 등)는 지금까지 "리그:시즌:"으로 시작했고, 리그 대회 이름이 리그 이름과 같아서
-- "대회:시즌:"으로 바꿔도 기존 키가 그대로 맞는다
ALTER TABLE season_awards ADD COLUMN competition VARCHAR(16) NULL;
UPDATE season_awards SET competition = league;
ALTER TABLE season_awards MODIFY competition VARCHAR(16) NOT NULL;

-- 구단 하나가 제공처마다 팀 번호를 가진다(리그·챔스는 football-data, 국내 컵은 API-Sports 등).
-- 한 제공처의 한 팀 번호는 한 구단에만, 한 구단은 제공처마다 번호 하나만 붙는다.
-- teams.feed_source, feed_team_id는 여기로 옮기고 더 이상 쓰지 않는다
CREATE TABLE team_feed_ids (
    source      VARCHAR(20) NOT NULL,
    external_id VARCHAR(40) NOT NULL,
    team_id     VARCHAR(20) NOT NULL,
    PRIMARY KEY (source, external_id),
    CONSTRAINT uk_team_feed_ids_team UNIQUE (team_id, source)
);
INSERT INTO team_feed_ids (source, external_id, team_id)
    SELECT feed_source, feed_team_id, id FROM teams WHERE feed_source IS NOT NULL AND feed_team_id IS NOT NULL;
