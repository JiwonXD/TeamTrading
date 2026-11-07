package com.fanchart.feed;

import com.fanchart.team.Competition;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 키는 제공처마다 하나, 대회(리그 정규 시즌·컵대회)는 "어느 제공처의 몇 번 대회인지"만 둔다. 같은 키로 여러 대회를 각각 부른다.
 * 키가 없는 제공처의 대회와 enabled: false인 대회만 수집하지 않고, 나머지는 그대로 수집한다.
 *
 * @param competitions 수집할 대회. 한 대회는 한 번만 둔다(두 제공처가 같은 경기를 따로 만들지 않도록)
 */
@ConfigurationProperties("fanchart.match-feed")
public record MatchFeedProperties(Keys keys, List<CompetitionFeed> competitions) {

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
        if (keys == null) keys = new Keys(null, null, null);
        competitions = competitions == null ? List.of() : List.copyOf(competitions);
        Set<Competition> seen = EnumSet.noneOf(Competition.class);
        for (CompetitionFeed f : competitions)
            if (!seen.add(f.competition()))
                throw new IllegalArgumentException("match feed competition listed twice: " + f.competition());
    }

    /** 제공처의 키. API-Sports 키 하나로 야구·축구 API를 둘 다 쓴다(한도는 API마다 따로) */
    public String keyFor(String source) {
        return switch (source) {
            case BASEBALL, FOOTBALL -> keys.apiSports();
            case FOOTBALL_DATA -> keys.footballData();
            case PANDASCORE -> keys.pandascore();
            default -> "";
        };
    }

    /** 켜져 있고 제공처 키가 있는 대회 */
    public boolean active(CompetitionFeed feed) {
        return feed.enabled() && !keyFor(feed.source()).isBlank();
    }

    public List<CompetitionFeed> activeFeeds() {
        return competitions.stream().filter(this::active).toList();
    }

    public boolean enabled() {
        return !activeFeeds().isEmpty();
    }

    /** 제공처별 키. 키는 로그에 남기지 않는다 */
    public record Keys(String apiSports, String footballData, String pandascore) {

        public Keys {
            apiSports = apiSports == null ? "" : apiSports.trim();
            footballData = footballData == null ? "" : footballData.trim();
            pandascore = pandascore == null ? "" : pandascore.trim();
        }

        @Override
        public String toString() {
            return "Keys[hidden]";
        }
    }

    /**
     * @param competition       우리 대회
     * @param source            제공처(SOURCES). 경기의 출처(matches.source)이자 팀 연결의 출처다
     * @param leagueId          제공처의 리그·대회 번호나 코드(API-Sports 5, football-data PL, PandaScore 293)
     * @param season            시즌. 비우면 제공처마다 정한 기본값(API-Sports는 seasonStartMonth로 계산, 나머지는 현재 시즌)
     * @param seasonStartMonth  API-Sports 시즌 계산용. 시즌이 이 달에 시작한다(유럽 축구 7: 2026년 3월이면 2025 시즌). 기본 1(연도 = 시즌)
     * @param baseUrl           비우면 제공처 기본 주소
     * @param dailyRequestLimit 같은 제공처·같은 키로 하루(한국 시간)에 보내는 요청 상한. 비우면 제공처 기본값
     * @param enabled           false면 키가 있어도 수집하지 않는다. 기본 true
     */
    public record CompetitionFeed(Competition competition, String source, String leagueId, Integer season,
                                  Integer seasonStartMonth, URI baseUrl, Integer dailyRequestLimit, Boolean enabled) {

        public CompetitionFeed {
            if (competition == null) throw new IllegalArgumentException("match feed competition is required");
            if (!SOURCES.contains(source))
                throw new IllegalArgumentException("unknown match feed source for " + competition + ": " + source);
            if (leagueId == null || leagueId.isBlank())
                throw new IllegalArgumentException("match feed league-id is required for " + competition);
            if (seasonStartMonth == null) seasonStartMonth = 1;
            if (seasonStartMonth < 1 || seasonStartMonth > 12)
                throw new IllegalArgumentException("season-start-month must be 1..12 for " + competition);
            if (enabled == null) enabled = true;
        }

        /** 기본 시즌·주소·상한으로 켜진 대회(설정 바인딩은 생성자가 하나여야 해서 정적 메서드로 둔다) */
        public static CompetitionFeed of(Competition competition, String source, String leagueId) {
            return new CompetitionFeed(competition, source, leagueId, null, null, null, null, null);
        }
    }
}
