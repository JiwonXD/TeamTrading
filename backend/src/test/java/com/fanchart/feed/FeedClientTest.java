package com.fanchart.feed;

import com.sun.net.httpserver.HttpServer;
import com.fanchart.TestClock;
import com.fanchart.feed.MatchFeedProperties.LeagueFeed;
import com.fanchart.team.League;
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
    private LeagueFeed baseball;
    private LeagueFeed football;
    private LeagueFeed epl;
    private LeagueFeed laLiga;
    private LeagueFeed lck;

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
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        baseball = new LeagueFeed(League.KBO, MatchFeedProperties.BASEBALL, "test-key", "5", null, base, 2);
        football = new LeagueFeed(League.K_LEAGUE, MatchFeedProperties.FOOTBALL, "test-key", "292", 2025, base, 2);
        epl = new LeagueFeed(League.EPL, MatchFeedProperties.FOOTBALL_DATA, "fd-key", "PL", null, base, 2);
        laLiga = new LeagueFeed(League.LA_LIGA, MatchFeedProperties.FOOTBALL_DATA, "fd-key", "PD", 2026, base, 2);
        lck = new LeagueFeed(League.LCK, MatchFeedProperties.PANDASCORE, "ps-key", "293", null, base, null);
        client = new FeedClient(JsonMapper.builder().build(), clock);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void asksForTheLeagueSeasonOrOneDayInKoreanTimeWithTheKey() {
        client.games(baseball, null);
        client.games(football, LocalDate.parse("2025-11-30"));

        assertThat(requests).containsExactly(
                "test-key /games?league=5&season=2026&timezone=Asia/Seoul",
                "test-key /fixtures?league=292&season=2025&timezone=Asia/Seoul&date=2025-11-30");
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
    void neverAsksForALeagueWithoutAKey() {
        LeagueFeed noKey = new LeagueFeed(League.SERIE_A, MatchFeedProperties.FOOTBALL_DATA, " ", "SA", null,
                epl.baseUrl(), null);

        assertThat(noKey.enabled()).isFalse();
        assertThat(noKey.toString()).doesNotContain("key");
        assertThat(epl.toString()).doesNotContain("fd-key");
        assertThatThrownBy(() -> client.games(noKey, null)).isInstanceOf(FeedException.class);
        assertThat(requests).isEmpty();
    }

    @Test
    void failsOnHttpErrorsAndProviderErrors() {
        status = 500;
        assertThatThrownBy(() -> client.games(baseball, null)).isInstanceOf(FeedException.class)
                .hasMessageContaining("HTTP 500");
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
        // 같은 제공처·같은 키를 쓰는 리그끼리는 한도를 함께 쓴다
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
