package com.fanchart.feed;

import com.fanchart.team.League;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 리그마다 제공처와 키를 따로 둔다. 키가 빈 리그만 수집하지 않고, 나머지 리그는 그대로 수집한다.
 *
 * @param leagues 수집할 리그. 한 리그는 한 번만 둔다(두 제공처가 같은 경기를 따로 만들지 않도록)
 */
@ConfigurationProperties("fanchart.match-feed")
public record MatchFeedProperties(List<LeagueFeed> leagues) {

    /** API-Sports API-Baseball v1 */
    public static final String BASEBALL = "api-baseball";
    /** API-Sports API-Football v3 */
    public static final String FOOTBALL = "api-football";
    /** football-data.org v4 */
    public static final String FOOTBALL_DATA = "football-data";
    /** PandaScore(리그 오브 레전드) */
    public static final String PANDASCORE = "pandascore";
    public static final Set<String> SOURCES = Set.of(BASEBALL, FOOTBALL, FOOTBALL_DATA, PANDASCORE);

    public MatchFeedProperties {
        leagues = leagues == null ? List.of() : List.copyOf(leagues);
        Set<League> seen = EnumSet.noneOf(League.class);
        for (LeagueFeed f : leagues)
            if (!seen.add(f.league())) throw new IllegalArgumentException("match feed league listed twice: " + f.league());
    }

    /** 키가 있어 수집하는 리그 */
    public List<LeagueFeed> enabledLeagues() {
        return leagues.stream().filter(LeagueFeed::enabled).toList();
    }

    public boolean enabled() {
        return !enabledLeagues().isEmpty();
    }

    /**
     * @param league            우리 리그
     * @param source            제공처(SOURCES). 경기의 출처(matches.source)이자 팀 연결의 출처다
     * @param apiKey            이 리그에 쓰는 제공처 키. 비어 있으면 이 리그는 수집하지 않는다
     * @param leagueId          제공처의 리그 번호나 코드(API-Sports 5, football-data PL, PandaScore 293)
     * @param season            시즌. 비우면 제공처마다 정한 기본값(API-Sports는 조회하는 날의 연도, 나머지는 현재 시즌)
     * @param baseUrl           비우면 제공처 기본 주소
     * @param dailyRequestLimit 같은 제공처·같은 키로 하루(한국 시간)에 보내는 요청 상한. 비우면 제공처 기본값
     */
    public record LeagueFeed(League league, String source, String apiKey, String leagueId, Integer season,
                             URI baseUrl, Integer dailyRequestLimit) {

        public LeagueFeed {
            if (league == null) throw new IllegalArgumentException("match feed league is required");
            if (!SOURCES.contains(source))
                throw new IllegalArgumentException("unknown match feed source for " + league + ": " + source);
            if (leagueId == null || leagueId.isBlank())
                throw new IllegalArgumentException("match feed league-id is required for " + league);
            apiKey = apiKey == null ? "" : apiKey.trim();
        }

        public boolean enabled() {
            return !apiKey.isBlank();
        }

        /** 키는 로그에 남기지 않는다 */
        @Override
        public String toString() {
            return "LeagueFeed[" + league + " " + source + " " + leagueId + (enabled() ? "" : " disabled") + "]";
        }
    }
}
