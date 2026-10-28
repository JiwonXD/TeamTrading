package com.teamtrading.team;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/** 첫 버전은 프론트엔드 목업과 같은 고정 목록을 쓴다 */
@Component
public class TeamCatalog {

    private final List<Team> teams = List.of(
            new Team("lg", "LG 트윈스", "LG", League.KBO, 18_450),
            new Team("kia", "KIA 타이거즈", "KIA", League.KBO, 21_300),
            new Team("samsung", "삼성 라이온즈", "삼성", League.KBO, 16_200),
            new Team("doosan", "두산 베어스", "두산", League.KBO, 14_850),
            new Team("kt", "KT 위즈", "KT", League.KBO, 12_900),
            new Team("ssg", "SSG 랜더스", "SSG", League.KBO, 13_750),
            new Team("lotte", "롯데 자이언츠", "롯데", League.KBO, 15_400),
            new Team("hanwha", "한화 이글스", "한화", League.KBO, 19_800),
            new Team("nc", "NC 다이노스", "NC", League.KBO, 11_600),
            new Team("kiwoom", "키움 히어로즈", "키움", League.KBO, 8_740),
            new Team("ulsan", "울산 HD", "울산", League.K_LEAGUE, 17_300),
            new Team("jeonbuk", "전북 현대", "전북", League.K_LEAGUE, 15_950),
            new Team("seoul", "FC서울", "서울", League.K_LEAGUE, 14_100),
            new Team("pohang", "포항 스틸러스", "포항", League.K_LEAGUE, 9_980),
            new Team("t1", "T1", "T1", League.LCK, 32_500),
            new Team("geng", "젠지", "GEN", League.LCK, 28_700),
            new Team("hle", "한화생명e스포츠", "HLE", League.LCK, 21_450),
            new Team("dk", "디플러스 기아", "DK", League.LCK, 17_850));

    public List<Team> all() {
        return teams;
    }

    public Optional<Team> find(String id) {
        return teams.stream().filter(t -> t.id().equals(id)).findFirst();
    }
}
