-- 구단 목록. 처음 18개는 출시 때 목록이고, 운영자가 리그 구단을 더 등록할 수 있다(리그 확장).
-- start_price: 첫 기준가. 일 마감 종가가 생기면 그 값이 기준가가 된다
-- ipo_required: 운영자가 새로 등록한 구단. 첫 상장 공모로 주식이 생기기 전까지 거래를 막는다
CREATE TABLE teams (
    id           VARCHAR(20)  NOT NULL PRIMARY KEY,
    name         VARCHAR(40)  NOT NULL,
    short_name   VARCHAR(10)  NOT NULL,
    league       VARCHAR(12)  NOT NULL,
    color        VARCHAR(7)   NOT NULL,
    start_price  BIGINT       NOT NULL,
    ipo_required BOOLEAN      NOT NULL,
    sort_order   INT          NOT NULL,
    created_at   TIMESTAMP(6) NOT NULL
);

INSERT INTO teams (id, name, short_name, league, color, start_price, ipo_required, sort_order, created_at) VALUES
    ('lg', 'LG 트윈스', 'LG', 'KBO', '#c30452', 18450, FALSE, 1, CURRENT_TIMESTAMP),
    ('kia', 'KIA 타이거즈', 'KIA', 'KBO', '#ea0029', 21300, FALSE, 2, CURRENT_TIMESTAMP),
    ('samsung', '삼성 라이온즈', '삼성', 'KBO', '#074ca1', 16200, FALSE, 3, CURRENT_TIMESTAMP),
    ('doosan', '두산 베어스', '두산', 'KBO', '#131230', 14850, FALSE, 4, CURRENT_TIMESTAMP),
    ('kt', 'KT 위즈', 'KT', 'KBO', '#231f20', 12900, FALSE, 5, CURRENT_TIMESTAMP),
    ('ssg', 'SSG 랜더스', 'SSG', 'KBO', '#ce0e2d', 13750, FALSE, 6, CURRENT_TIMESTAMP),
    ('lotte', '롯데 자이언츠', '롯데', 'KBO', '#041e42', 15400, FALSE, 7, CURRENT_TIMESTAMP),
    ('hanwha', '한화 이글스', '한화', 'KBO', '#d34100', 19800, FALSE, 8, CURRENT_TIMESTAMP),
    ('nc', 'NC 다이노스', 'NC', 'KBO', '#315288', 11600, FALSE, 9, CURRENT_TIMESTAMP),
    ('kiwoom', '키움 히어로즈', '키움', 'KBO', '#820024', 8740, FALSE, 10, CURRENT_TIMESTAMP),
    ('ulsan', '울산 HD', '울산', 'K_LEAGUE', '#1d4ea3', 17300, FALSE, 11, CURRENT_TIMESTAMP),
    ('jeonbuk', '전북 현대', '전북', 'K_LEAGUE', '#1b7a3a', 15950, FALSE, 12, CURRENT_TIMESTAMP),
    ('seoul', 'FC서울', '서울', 'K_LEAGUE', '#b3182a', 14100, FALSE, 13, CURRENT_TIMESTAMP),
    ('pohang', '포항 스틸러스', '포항', 'K_LEAGUE', '#d6001c', 9980, FALSE, 14, CURRENT_TIMESTAMP),
    ('t1', 'T1', 'T1', 'LCK', '#e2012d', 32500, FALSE, 15, CURRENT_TIMESTAMP),
    ('geng', '젠지', 'GEN', 'LCK', '#8b7100', 28700, FALSE, 16, CURRENT_TIMESTAMP),
    ('hle', '한화생명e스포츠', 'HLE', 'LCK', '#b85719', 21450, FALSE, 17, CURRENT_TIMESTAMP),
    ('dk', '디플러스 기아', 'DK', 'LCK', '#098080', 17850, FALSE, 18, CURRENT_TIMESTAMP);
