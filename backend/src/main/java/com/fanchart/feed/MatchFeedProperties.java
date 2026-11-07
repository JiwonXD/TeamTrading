package com.fanchart.feed;

import com.fanchart.team.League;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.util.List;

/**
 * @param apiKey            API-Sports 키. 비어 있으면 수집하지 않는다
 * @param dailyRequestLimit 제공처(API)별 하루(한국 시간) 요청 상한. 무료 요금제 한도(하루 100회)를 넘기지 않도록 여유를 둔다
 * @param leagues           수집할 리그
 */
@ConfigurationProperties("fanchart.match-feed")
public record MatchFeedProperties(String apiKey, Integer dailyRequestLimit, List<LeagueFeed> leagues) {

    public static final String BASEBALL = "api-baseball";
    public static final String FOOTBALL = "api-football";

    public MatchFeedProperties {
        if (apiKey == null) apiKey = "";
        if (dailyRequestLimit == null) dailyRequestLimit = 90;
        leagues = leagues == null ? List.of() : List.copyOf(leagues);
    }

    public boolean enabled() {
        return !apiKey.isBlank();
    }

    /**
     * @param source   api-baseball 또는 api-football. 경기의 출처(matches.source)이자 팀 연결의 출처다
     * @param league   우리 리그
     * @param leagueId 제공처의 리그 번호
     * @param season   시즌. 비우면 조회하는 날의 연도
     * @param baseUrl  비우면 제공처 기본 주소
     */
    public record LeagueFeed(String source, League league, int leagueId, Integer season, URI baseUrl) {

        public LeagueFeed {
            if (!BASEBALL.equals(source) && !FOOTBALL.equals(source))
                throw new IllegalArgumentException("unknown match feed source: " + source);
            if (league == null) throw new IllegalArgumentException("match feed league is required");
            if (baseUrl == null) baseUrl = URI.create(BASEBALL.equals(source)
                    ? "https://v1.baseball.api-sports.io" : "https://v3.football.api-sports.io");
        }
    }
}
