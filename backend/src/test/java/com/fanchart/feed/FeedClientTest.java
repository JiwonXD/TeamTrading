package com.fanchart.feed;

import com.sun.net.httpserver.HttpServer;
import com.fanchart.TestClock;
import com.fanchart.feed.MatchFeedProperties.CompetitionFeed;
import com.fanchart.feed.MatchFeedProperties.Keys;
import com.fanchart.team.Competition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FeedClientTest {

    private HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private volatile int status = 200;
    private volatile String body = "{\"errors\": [], \"response\": []}";
    // 한국 시간 2026-04-01 23:00
    private final TestClock clock = new TestClock(Instant.parse("2026-04-01T14:00:00Z"));
    private FeedClient client;
    private CompetitionFeed baseball;
    private CompetitionFeed football;
    private CompetitionFeed uel;
    private CompetitionFeed epl;
    private CompetitionFeed laLiga;
    private CompetitionFeed lck;
    private CompetitionFeed faCup;
    private URI base;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            var h = ex.getRequestHeaders();
            String key = h.containsKey("x-apisports-key") ? h.getFirst("x-apisports-key")
                    : h.containsKey("X-Auth-Token") ? h.getFirst("X-Auth-Token") : h.getFirst("Authorization");
            requests.add(key + " " + ex.getRequestURI().getRawPath()
                    + (ex.getRequestURI().getRawQuery() == null ? "" : "?" + ex.getRequestURI().getRawQuery()));
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        baseball = new CompetitionFeed(Competition.KBO, MatchFeedProperties.BASEBALL, "5", null, null, base, 2, null);
        football = new CompetitionFeed(Competition.K_LEAGUE, MatchFeedProperties.FOOTBALL, "292", 2025, null, base, 2,
                null);
        uel = new CompetitionFeed(Competition.UEL, MatchFeedProperties.FOOTBALL, "3", null, 7, base, null, null);
        epl = new CompetitionFeed(Competition.EPL, MatchFeedProperties.FOOTBALL_DATA, "PL", null, null, base, 2, null);
        laLiga = new CompetitionFeed(Competition.LA_LIGA, MatchFeedProperties.FOOTBALL_DATA, "PD", 2026, null, base, 2,
                null);
        lck = new CompetitionFeed(Competition.LCK, MatchFeedProperties.PANDASCORE, "293", null, null, base, null, null);
        faCup = new CompetitionFeed(Competition.FA_CUP, MatchFeedProperties.ESPN, "soccer/eng.fa", null, null, base,
                null, null);
        client = client(new Keys("test-key", "fd-key", "ps-key"));
    }

    private FeedClient client(Keys keys) {
        return new FeedClient(new MatchFeedProperties(keys, List.of(baseball, football, uel, epl, laLiga, lck, faCup)),
                JsonMapper.builder().build(), clock);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void asksForTheLeagueSeasonOrOneDayInKoreanTimeWithTheKey() {
        client.games(baseball, null);
        client.games(football, LocalDate.parse("2025-11-30"));
        // 8월에 시작하는 유럽 컵대회: 2026년 4월은 2025 시즌, 9월은 2026 시즌
        client.games(uel, null);
        client.games(uel, LocalDate.parse("2026-09-17"));

        assertThat(requests).containsExactly(
                "test-key /games?league=5&season=2026&timezone=Asia/Seoul",
                "test-key /fixtures?league=292&season=2025&timezone=Asia/Seoul&date=2025-11-30",
                "test-key /fixtures?league=3&season=2025&timezone=Asia/Seoul",
                "test-key /fixtures?league=3&season=2026&timezone=Asia/Seoul&date=2026-09-17");
    }

    @Test
    void asksFootballDataForTheCurrentSeasonOrTheUtcDaysCoveringOneKoreanDay() {
        body = "{\"matches\": []}";
        client.games(epl, null);
        client.games(laLiga, LocalDate.parse("2026-11-08"));

        assertThat(requests).containsExactly(
                "fd-key /v4/competitions/PL/matches",
                "fd-key /v4/competitions/PD/matches?season=2026&dateFrom=2026-11-07&dateTo=2026-11-08");
    }

    @Test
    void asksPandaScoreForTheLeagueMatchesAroundTodayOrOneKoreanDay() {
        body = "[]";
        client.games(lck, null);
        client.games(lck, LocalDate.parse("2026-04-05"));

        assertThat(requests).containsExactly(
                "Bearer ps-key /lol/matches?filter%5Bleague_id%5D=293"
                        + "&range%5Bscheduled_at%5D=2026-03-29T15%3A00%3A00Z%2C2026-05-15T15%3A00%3A00Z"
                        + "&sort=scheduled_at&page%5Bsize%5D=100",
                "Bearer ps-key /lol/matches?filter%5Bleague_id%5D=293"
                        + "&range%5Bscheduled_at%5D=2026-04-04T15%3A00%3A00Z%2C2026-04-05T15%3A00%3A00Z"
                        + "&sort=scheduled_at&page%5Bsize%5D=100");
    }

    @Test
    void asksEspnOneDayAtATimeWithoutAKeyAndKeepsEachGameOnce() {
        // 하루씩 물으면 날짜 경계의 경기가 두 번 온다
        body = "{\"events\": [" + EspnProviderTest.event("7", "STATUS_SCHEDULED", "pre", false, "0", "0") + "]}";
        FeedClient noKeys = client(new Keys("", "", ""));

        assertThat(noKeys.games(faCup, null)).hasSize(1);
        assertThat(requests).hasSize(EspnProvider.DAYS_AHEAD + 2).first()
                .isEqualTo("null /apis/site/v2/sports/soccer/eng.fa/scoreboard?dates=20260331");
        assertThat(requests.getLast()).endsWith("dates=20260408");
        requests.clear();
        // 한국 시간 하루는 미국 동부의 전날과 그날에 걸친다
        noKeys.games(faCup, LocalDate.parse("2026-04-05"));
        assertThat(requests).containsExactly(
                "null /apis/site/v2/sports/soccer/eng.fa/scoreboard?dates=20260404",
                "null /apis/site/v2/sports/soccer/eng.fa/scoreboard?dates=20260405");
        assertThat(noKeys.remaining(faCup)).isEqualTo(1500 - EspnProvider.DAYS_AHEAD - 2 - 2);
        assertThat(new MatchFeedProperties(new Keys("", "", ""), List.of(faCup, epl)).activeFeeds())
                .containsExactly(faCup);
    }

    @Test
    void neverAsksWithoutAProviderKeyAndSkipsDisabledCompetitions() {
        FeedClient noKeys = client(new Keys(" ", null, ""));
        assertThatThrownBy(() -> noKeys.games(epl, null)).isInstanceOf(FeedException.class);
        assertThat(requests).isEmpty();

        var props = new MatchFeedProperties(new Keys("a", "", "c"), List.of(baseball, epl, lck,
                new CompetitionFeed(Competition.MSI, MatchFeedProperties.PANDASCORE, "300", null, null, null, null,
                        false)));
        assertThat(props.activeFeeds()).extracting(CompetitionFeed::competition)
                .containsExactly(Competition.KBO, Competition.LCK);
        assertThat(props.keys().toString()).doesNotContain("a").doesNotContain("c");
        assertThatThrownBy(() -> new MatchFeedProperties(null, List.of(epl, epl)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void failsOnHttpErrorsAndProviderErrors() {
        status = 500;
        assertThatThrownBy(() -> client.games(baseball, null)).isInstanceOf(FeedException.class)
                .hasMessageContaining("HTTP 500");
        // 제공처가 밝힌 이유를 같이 남긴다(football-data는 message, PandaScore는 error)
        status = 400;
        body = "{\"message\": \"Your API token is invalid.\", \"errorCode\": 400}";
        assertThatThrownBy(() -> client.games(epl, null)).hasMessage("football-data HTTP 400: Your API token is invalid.");
        status = 401;
        body = "{\"error\": \"Token is invalid\"}";
        assertThatThrownBy(() -> client.games(lck, null)).hasMessage("pandascore HTTP 401: Token is invalid");
        status = 200;
        body = "{\"errors\": {\"requests\": \"You have reached the request limit for the day\"}, \"response\": []}";
        assertThatThrownBy(() -> client.games(baseball, null)).isInstanceOf(FeedException.class)
                .hasMessageContaining("request limit");
        body = "<html>";
        assertThatThrownBy(() -> client.games(football, null)).isInstanceOf(FeedException.class);
    }

    @Test
    void stopsAtTheDailyLimitPerProviderAndKeyUntilMidnightInKorea() {
        client.games(baseball, null);
        status = 500;
        // 실패한 요청도 제공처의 한도를 쓴다
        assertThatThrownBy(() -> client.games(baseball, null)).hasMessageContaining("HTTP 500");
        status = 200;
        assertThatThrownBy(() -> client.games(baseball, null)).hasMessageContaining("daily request limit");
        assertThat(requests).hasSize(2);
        assertThat(client.remaining(baseball)).isZero();
        // 다른 제공처(API)는 한도가 따로다
        client.games(football, null);
        // 같은 제공처·같은 키를 쓰는 대회끼리는 한도를 함께 쓴다
        body = "{\"matches\": []}";
        client.games(epl, null);
        client.games(laLiga, null);
        assertThatThrownBy(() -> client.games(epl, null)).hasMessageContaining("daily request limit");
        body = "{\"errors\": [], \"response\": []}";

        clock.advance(Duration.ofMinutes(59));
        assertThatThrownBy(() -> client.games(baseball, null)).hasMessageContaining("daily request limit");
        clock.advance(Duration.ofMinutes(1));
        client.games(baseball, null);
        assertThat(client.remaining(baseball)).isEqualTo(1);
    }
}
