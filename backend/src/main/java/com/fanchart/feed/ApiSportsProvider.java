package com.fanchart.feed;

import com.fanchart.feed.MatchFeedProperties.CompetitionFeed;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

/** API-Sports(API-Baseball v1 games, API-Football v3 fixtures). 무료 요금제는 API별 하루 100회다 */
final class ApiSportsProvider implements FeedProvider {

    static final ApiSportsProvider BASEBALL = new ApiSportsProvider(true);
    static final ApiSportsProvider FOOTBALL = new ApiSportsProvider(false);

    private final boolean baseball;

    private ApiSportsProvider(boolean baseball) {
        this.baseball = baseball;
    }

    @Override
    public URI defaultBaseUrl() {
        return URI.create(baseball ? "https://v1.baseball.api-sports.io" : "https://v3.football.api-sports.io");
    }

    @Override
    public int defaultDailyLimit() {
        return 90;
    }

    @Override
    public HttpRequest request(CompetitionFeed feed, String key, URI base, LocalDate date, LocalDate today) {
        int season = feed.season() != null ? feed.season() : season(date != null ? date : today, feed.seasonStartMonth());
        String query = "league=" + feed.leagueId() + "&season=" + season + "&timezone=Asia/Seoul"
                + (date != null ? "&date=" + date : "");
        return HttpRequest.newBuilder(URI.create(base + (baseball ? "/games?" : "/fixtures?") + query))
                .timeout(Duration.ofSeconds(10)).header("x-apisports-key", key).GET().build();
    }

    /** 시즌이 startMonth에 시작하면 그 전 달까지는 지난해 시즌이다(유럽 축구 7: 2026-03 → 2025) */
    static int season(LocalDate day, int startMonth) {
        return day.getMonthValue() >= startMonth ? day.getYear() : day.getYear() - 1;
    }

    @Override
    public List<FeedGame> parse(JsonNode body) {
        return baseball ? ApiSportsParser.baseball(body) : ApiSportsParser.football(body);
    }
}
