-- 관심 구단. 계정마다 구단 화면의 별로 넣고 빼며, 웹·PC·앱 어디서 열어도 같게 계정에 저장한다.
-- position은 넣은 순서(목록에 보이는 순서). 한 구단씩 넣고 빼므로 두 기기에서 따로 눌러도 서로 덮어쓰지 않는다
CREATE TABLE account_watchlist (
    account_id VARCHAR(36) NOT NULL,
    team_id    VARCHAR(20) NOT NULL,
    position   INT         NOT NULL,
    PRIMARY KEY (account_id, team_id)
);
