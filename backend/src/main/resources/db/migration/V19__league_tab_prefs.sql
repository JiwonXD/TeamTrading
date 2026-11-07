-- 홈 위 리그 탭을 계정마다 원하는 순서로, 원하는 리그만 보이게 한다(웹·PC·앱 어디서 열어도 같게 계정에 저장).
-- 행이 없으면 기본 순서로 모두 보인다. 저장된 뒤에 새로 생긴 리그는 화면이 맨 끝에 보이는 상태로 붙인다
CREATE TABLE account_league_tabs (
    account_id VARCHAR(36) NOT NULL,
    league     VARCHAR(20) NOT NULL,
    position   INT         NOT NULL,
    hidden     BOOLEAN     NOT NULL,
    PRIMARY KEY (account_id, league)
);
