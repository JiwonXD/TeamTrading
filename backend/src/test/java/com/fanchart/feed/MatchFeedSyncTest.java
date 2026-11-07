package com.fanchart.feed;

import com.sun.net.httpserver.HttpServer;
import com.fanchart.TestClock;
import com.fanchart.feed.FeedGame.State;
import com.fanchart.feed.MatchFeedProperties.LeagueFeed;
import com.fanchart.feed.MatchFeedSync.Summary;
import com.fanchart.match.Match;
import com.fanchart.match.MatchService;
import com.fanchart.team.League;
import com.fanchart.trading.IdempotencyConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.fanchart.auth.TestLogin.admin;
import static com.fanchart.auth.TestLogin.login;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 제공처 경기를 우리 일정·결과에 맞추는 규칙. 제공처 KBO 팀 101=lg, 102=kia, 103=samsung */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestClock.Config.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class MatchFeedSyncTest {

    static final String SRC = MatchFeedProperties.BASEBALL;
    /** 한국 시간 2026-04-01 18:30 */
    static final Instant KICKOFF = Instant.parse("2026-04-01T09:30:00Z");

    static final HttpServer SERVER;
    static final List<String> REQUESTS = new CopyOnWriteArrayList<>();
    static volatile String body;

    static {
        try {
            SERVER = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        SERVER.createContext("/", ex -> {
            REQUESTS.add(ex.getRequestURI().toString());
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        SERVER.start();
    }

    @DynamicPropertySource
    static void feed(DynamicPropertyRegistry r) {
        r.add("fanchart.match-feed.api-key", () -> "test-key");
        r.add("fanchart.match-feed.leagues[0].source", () -> SRC);
        r.add("fanchart.match-feed.leagues[0].league", () -> "KBO");
        r.add("fanchart.match-feed.leagues[0].league-id", () -> "5");
        r.add("fanchart.match-feed.leagues[0].base-url", () -> "http://127.0.0.1:" + SERVER.getAddress().getPort());
    }

    @Autowired MatchFeedSync sync;
    @Autowired FeedTeams teams;
    @Autowired MatchService matches;
    @Autowired TestClock clock;
    @Autowired MockMvc mvc;

    private final LeagueFeed kbo = new LeagueFeed(SRC, League.KBO, 5, null, null);

    @BeforeEach
    void setUp() {
        clock.set(KICKOFF.minus(Duration.ofHours(1)));
        teams.link("lg", SRC, "101");
        teams.link("kia", SRC, "102");
        teams.link("samsung", SRC, "103");
        REQUESTS.clear();
        body = "{\"errors\": [], \"response\": []}";
    }

    private static FeedGame game(String ref, Instant at, State state, Integer home, Integer away) {
        return new FeedGame(ref, "101", "LG Twins", "102", "Kia Tigers", at, state, state.name(), home, away);
    }

    private Summary apply(FeedGame... games) {
        return sync.apply(kbo, List.of(games));
    }

    private Match match(String ref) {
        return matches.findBySource(SRC, ref).orElseThrow();
    }

    @Test
    void createsAScheduledMatchOnceAndThenLeavesItAlone() {
        Summary first = apply(game("g1", KICKOFF, State.NOT_STARTED, null, null));
        Summary again = apply(game("g1", KICKOFF, State.NOT_STARTED, null, null));

        assertThat(first.created()).isEqualTo(1);
        assertThat(again).isEqualTo(new Summary(SRC, "KBO", 0, 0, 0, 0, 0, 0, List.of(), null));
        Match m = match("g1");
        assertThat(m.league()).isEqualTo(League.KBO);
        assertThat(m.homeTeamId()).isEqualTo("lg");
        assertThat(m.awayTeamId()).isEqualTo("kia");
        assertThat(m.scheduledAt()).isEqualTo(KICKOFF);
        assertThat(m.status()).isEqualTo(Match.Status.SCHEDULED);
    }

    @Test
    void skipsGamesItShouldNotStartTracking() {
        FeedGame unmapped = new FeedGame("g1", "101", "LG Twins", "999", "Somebody", KICKOFF, State.NOT_STARTED,
                "NS", null, null);
        Summary s = apply(unmapped,
                game("old", clock.instant().minus(Duration.ofHours(37)), State.FINAL, 3, 2),
                game("canceled", KICKOFF, State.CANCELED, null, null),
                game("unknown", KICKOFF, State.UNKNOWN, null, null));

        assertThat(s.unmappedTeams()).containsExactly("999 Somebody");
        assertThat(s.ignored()).isEqualTo(3);
        assertThat(s.created()).isZero();
        assertThat(matches.latest(10)).isEmpty();
    }

    @Test
    void movesKickoffLaterOnlyWhileTheFeedSaysTheGameHasNotStarted() {
        apply(game("g1", KICKOFF, State.NOT_STARTED, null, null));

        // 우천 지연: 시작 전이면 늦춘다
        Instant delayed = KICKOFF.plus(Duration.ofMinutes(40));
        assertThat(apply(game("g1", delayed, State.NOT_STARTED, null, null)).rescheduled()).isEqualTo(1);
        assertThat(match("g1").scheduledAt()).isEqualTo(delayed);

        // 경기 중에 늦은 시각이 오면 받지 않는다. 경기를 보며 산 주식이 배당을 받게 되기 때문이다
        clock.set(delayed.plus(Duration.ofMinutes(30)));
        assertThat(apply(game("g1", delayed.plus(Duration.ofHours(1)), State.LIVE, null, null)).rescheduled())
                .isZero();
        assertThat(match("g1").scheduledAt()).isEqualTo(delayed);

        // 앞당기는 변경은 기준 시점을 이르게 할 뿐이라 언제나 받는다
        assertThat(apply(game("g1", KICKOFF, State.LIVE, null, null)).rescheduled()).isEqualTo(1);
        assertThat(match("g1").scheduledAt()).isEqualTo(KICKOFF);
    }

    @Test
    void aFinalResultIsRecordedThenPaidAfterTheConfirmDelay() {
        apply(game("g1", KICKOFF, State.NOT_STARTED, null, null));
        clock.set(KICKOFF.plus(Duration.ofHours(3)));

        assertThat(apply(game("g1", KICKOFF, State.FINAL, 5, 3)).finals()).isEqualTo(1);
        assertThat(apply(game("g1", KICKOFF, State.FINAL, 5, 3)).finals()).isZero();
        Match m = match("g1");
        assertThat(m.status()).isEqualTo(Match.Status.FINAL);
        assertThat(m.winner()).isEqualTo("lg");

        // 확정 대기 중 정정
        clock.advance(Duration.ofHours(1));
        assertThat(apply(game("g1", KICKOFF, State.FINAL, 5, 6)).finals()).isEqualTo(1);
        assertThat(match("g1").winner()).isEqualTo("kia");

        clock.advance(Duration.ofHours(6).minusSeconds(1));
        assertThat(matches.payDue()).isZero();
        clock.advance(Duration.ofSeconds(1));
        assertThat(matches.payDue()).isEqualTo(1);
        assertThat(match("g1").dividendStatus()).isEqualTo(Match.DividendStatus.PAID);
    }

    @Test
    void cancellationsApplyOnlyToGamesThatHaveNoResult() {
        apply(game("g1", KICKOFF, State.NOT_STARTED, null, null), game("g2", KICKOFF, State.NOT_STARTED, null, null));
        clock.set(KICKOFF.plus(Duration.ofHours(4)));
        apply(game("g2", KICKOFF, State.FINAL, 2, 1));

        Summary s = apply(game("g1", KICKOFF, State.CANCELED, null, null),
                game("g2", KICKOFF, State.CANCELED, null, null));

        assertThat(s.canceled()).isEqualTo(1);
        assertThat(s.conflicts()).isEqualTo(1);
        assertThat(match("g1").status()).isEqualTo(Match.Status.CANCELED);
        assertThat(match("g2").status()).isEqualTo(Match.Status.FINAL);
        // 취소한 경기에 뒤늦게 온 결과도 운영자에게 남긴다
        assertThat(apply(game("g1", KICKOFF, State.FINAL, 1, 0)).conflicts()).isEqualTo(1);
        assertThat(match("g1").status()).isEqualTo(Match.Status.CANCELED);
    }

    @Test
    void refusesGamesWhoseTeamsDoNotFitTheLeagueOrTheMatch() {
        teams.link("ulsan", SRC, "201");
        FeedGame crossLeague = new FeedGame("x", "101", "LG", "201", "Ulsan", KICKOFF, State.NOT_STARTED, "NS",
                null, null);
        assertThat(apply(crossLeague).conflicts()).isEqualTo(1);

        apply(game("g1", KICKOFF, State.NOT_STARTED, null, null));
        FeedGame changed = new FeedGame("g1", "101", "LG", "103", "Samsung", KICKOFF, State.NOT_STARTED, "NS",
                null, null);
        assertThat(apply(changed).conflicts()).isEqualTo(1);
        assertThat(match("g1").awayTeamId()).isEqualTo("kia");

        assertThatThrownBy(() -> teams.link("doosan", SRC, "101")).isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void seasonSyncReadsTheConfiguredLeagueFromTheProvider() {
        body = "{\"errors\": [], \"response\": [" + ApiSportsParserTest.baseballGame(77, "NS", "null", "null") + "]}";

        List<Summary> out = sync.syncSeason();

        assertThat(REQUESTS).containsExactly("/games?league=5&season=2026&timezone=Asia/Seoul");
        assertThat(out).singleElement().extracting(Summary::created).isEqualTo(1);
        assertThat(match("77").scheduledAt()).isEqualTo(KICKOFF);
    }

    @Test
    void pollsForResultsFromTwoHoursAfterKickoffThenLessOftenThenGivesUp() {
        apply(game("77", KICKOFF, State.NOT_STARTED, null, null));
        body = "{\"errors\": [], \"response\": [" + ApiSportsParserTest.baseballGame(77, "IN9", "3", "3") + "]}";

        clock.set(KICKOFF.plus(Duration.ofHours(2)).minusSeconds(1));
        sync.pollResults();
        assertThat(REQUESTS).isEmpty();

        // 시작 뒤 6시간까지는 스케줄러가 돌 때마다 묻는다
        clock.set(KICKOFF.plus(Duration.ofHours(2)));
        sync.pollResults();
        clock.advance(Duration.ofMinutes(15));
        sync.pollResults();
        assertThat(REQUESTS).containsOnly("/games?league=5&season=2026&timezone=Asia/Seoul&date=2026-04-01")
                .hasSize(2);

        // 그 뒤에는 3시간에 한 번
        clock.set(KICKOFF.plus(Duration.ofHours(6)));
        sync.pollResults();
        clock.advance(Duration.ofHours(3).minusSeconds(1));
        sync.pollResults();
        assertThat(REQUESTS).hasSize(3);
        clock.advance(Duration.ofSeconds(1));
        sync.pollResults();
        assertThat(REQUESTS).hasSize(4);

        // 48시간이 지나면 더 묻지 않는다
        clock.set(KICKOFF.plus(Duration.ofHours(48)).plusSeconds(1));
        sync.pollResults();
        assertThat(REQUESTS).hasSize(4);

        // 결과가 오면 끝난다
        clock.set(KICKOFF.plus(Duration.ofHours(30)));
        body = "{\"errors\": [], \"response\": [" + ApiSportsParserTest.baseballGame(77, "FT", "4", "3") + "]}";
        sync.pollResults();
        assertThat(match("77").status()).isEqualTo(Match.Status.FINAL);
        clock.advance(Duration.ofHours(4));
        sync.pollResults();
        assertThat(REQUESTS).hasSize(5);
    }

    @Test
    void operatorsLinkTeamsAndSyncThroughTheAdminApi() throws Exception {
        mvc.perform(put("/api/admin/teams/doosan/feed").with(admin("ops"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"source\":\"api-baseball\",\"externalId\":\"104\"}"))
                .andExpect(status().isOk());
        assertThat(teams.teamFor(SRC, "104")).contains("doosan");

        mvc.perform(put("/api/admin/teams/doosan/feed").with(admin("ops"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"source\":\"rss\",\"externalId\":\"1\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/admin/teams/kt/feed").with(admin("ops"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"source\":\"api-baseball\",\"externalId\":\"104\"}"))
                .andExpect(status().isConflict());
        mvc.perform(put("/api/admin/teams/doosan/feed").with(login("someone"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"source\":\"api-baseball\",\"externalId\":\"9\"}"))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/admin/match-feed/sync").with(admin("ops")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].league").value("KBO"))
                .andExpect(jsonPath("$[0].error").doesNotExist());
    }
}
