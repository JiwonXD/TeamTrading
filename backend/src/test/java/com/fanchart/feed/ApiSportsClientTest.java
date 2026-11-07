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

class ApiSportsClientTest {

    private HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private volatile int status = 200;
    private volatile String body = "{\"errors\": [], \"response\": []}";
    // 한국 시간 2026-04-01 23:00
    private final TestClock clock = new TestClock(Instant.parse("2026-04-01T14:00:00Z"));
    private ApiSportsClient client;
    private LeagueFeed baseball;
    private LeagueFeed football;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            requests.add(ex.getRequestHeaders().getFirst("x-apisports-key") + " " + ex.getRequestURI());
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        baseball = new LeagueFeed(MatchFeedProperties.BASEBALL, League.KBO, 5, null, base);
        football = new LeagueFeed(MatchFeedProperties.FOOTBALL, League.K_LEAGUE, 292, 2025, base);
        client = new ApiSportsClient(new MatchFeedProperties("test-key", 2, List.of(baseball, football)),
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

        assertThat(requests).containsExactly(
                "test-key /games?league=5&season=2026&timezone=Asia/Seoul",
                "test-key /fixtures?league=292&season=2025&timezone=Asia/Seoul&date=2025-11-30");
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
    void stopsAtTheDailyLimitPerProviderUntilMidnightInKorea() {
        client.games(baseball, null);
        status = 500;
        // 실패한 요청도 제공처의 한도를 쓴다
        assertThatThrownBy(() -> client.games(baseball, null)).hasMessageContaining("HTTP 500");
        status = 200;
        assertThatThrownBy(() -> client.games(baseball, null)).hasMessageContaining("daily request limit");
        assertThat(requests).hasSize(2);
        assertThat(client.remaining(MatchFeedProperties.BASEBALL)).isZero();
        // 다른 제공처(API)는 한도가 따로다
        client.games(football, null);

        clock.advance(Duration.ofMinutes(59));
        assertThatThrownBy(() -> client.games(baseball, null)).hasMessageContaining("daily request limit");
        clock.advance(Duration.ofMinutes(1));
        client.games(baseball, null);
        assertThat(client.remaining(MatchFeedProperties.BASEBALL)).isEqualTo(1);
    }
}
