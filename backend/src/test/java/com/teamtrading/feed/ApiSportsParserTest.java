package com.teamtrading.feed;

import com.teamtrading.feed.FeedGame.State;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/** API-Sports 응답 예시(문서의 필드 모양)를 FeedGame으로 옮기는 규칙 */
class ApiSportsParserTest {

    static JsonNode json(String body) {
        return JsonMapper.builder().build().readTree(body);
    }

    static String baseballGame(int id, String status, String homeTotal, String awayTotal) {
        return """
                {"id": %d, "date": "2026-04-01T18:30:00+09:00", "timestamp": 1775035800,
                 "status": {"long": "x", "short": "%s"},
                 "league": {"id": 5, "name": "KBO", "season": 2026},
                 "teams": {"home": {"id": 101, "name": "LG Twins"}, "away": {"id": 102, "name": "Kia Tigers"}},
                 "scores": {"home": {"hits": 9, "errors": 0, "total": %s}, "away": {"hits": 5, "errors": 1, "total": %s}}}
                """.formatted(id, status, homeTotal, awayTotal);
    }

    @Test
    void readsBaseballGamesAndOnlyTrustsAFinishedGameWithBothScores() {
        String body = """
                {"get": "games", "errors": [], "results": 7, "response": [%s, %s, %s, %s, %s, %s, %s]}
                """.formatted(baseballGame(1, "NS", "null", "null"), baseballGame(2, "FT", "5", "3"),
                baseballGame(3, "IN7", "2", "2"), baseballGame(4, "POST", "null", "null"),
                baseballGame(5, "CANC", "null", "null"), baseballGame(6, "FT", "null", "3"),
                baseballGame(7, "AWD", "9", "0"));

        var games = ApiSportsParser.baseball(json(body));

        assertThat(games).extracting(FeedGame::ref, FeedGame::state, FeedGame::homeScore, FeedGame::awayScore)
                .containsExactly(tuple("1", State.NOT_STARTED, null, null), tuple("2", State.FINAL, 5, 3),
                        tuple("3", State.LIVE, null, null), tuple("4", State.POSTPONED, null, null),
                        tuple("5", State.CANCELED, null, null), tuple("6", State.UNKNOWN, null, null),
                        tuple("7", State.UNKNOWN, null, null));
        FeedGame g = games.get(1);
        assertThat(g.scheduledAt()).isEqualTo(Instant.parse("2026-04-01T09:30:00Z"));
        assertThat(g.homeTeamId()).isEqualTo("101");
        assertThat(g.awayName()).isEqualTo("Kia Tigers");
        assertThat(g.rawStatus()).isEqualTo("FT");
    }

    @Test
    void readsFootballFixturesIncludingExtraTimeAndPenalties() {
        String fixture = """
                {"fixture": {"id": %d, "date": "2026-03-01T14:00:00+09:00", "timestamp": 1772341200,
                             "status": {"long": "x", "short": "%s", "elapsed": 90}},
                 "league": {"id": 292, "season": 2026},
                 "teams": {"home": {"id": 2762, "name": "Ulsan"}, "away": {"id": 2763, "name": "Jeonbuk"}},
                 "goals": {"home": %s, "away": %s}}
                """;
        String body = """
                {"errors": {}, "response": [%s, %s, %s, %s, %s, %s]}
                """.formatted(fixture.formatted(1, "FT", "1", "1"), fixture.formatted(2, "PEN", "2", "2"),
                fixture.formatted(3, "2H", "0", "1"), fixture.formatted(4, "PST", "null", "null"),
                fixture.formatted(5, "ABD", "0", "0"), fixture.formatted(6, "WO", "3", "0"));

        assertThat(ApiSportsParser.football(json(body)))
                .extracting(FeedGame::ref, FeedGame::state, FeedGame::homeScore, FeedGame::awayScore)
                .containsExactly(tuple("1", State.FINAL, 1, 1), tuple("2", State.FINAL, 2, 2),
                        tuple("3", State.LIVE, null, null), tuple("4", State.POSTPONED, null, null),
                        tuple("5", State.CANCELED, null, null), tuple("6", State.UNKNOWN, null, null));
    }

    @Test
    void rejectsProviderErrorsAndGamesItCannotIdentify() {
        // 키가 틀리거나 요청 한도를 넘으면 HTTP 200에 errors로 온다
        assertThatThrownBy(() -> ApiSportsParser.baseball(json("""
                {"errors": {"token": "Error/Missing application key."}, "response": []}""")))
                .isInstanceOf(FeedException.class).hasMessageContaining("token");
        assertThatThrownBy(() -> ApiSportsParser.football(json("""
                {"errors": [], "response": {"message": "x"}}"""))).isInstanceOf(FeedException.class);
        assertThatThrownBy(() -> ApiSportsParser.baseball(json("""
                {"errors": [], "response": [{"id": 1, "status": {"short": "NS"},
                  "teams": {"home": {"id": 1}, "away": {"id": 2}}}]}""")))
                .isInstanceOf(FeedException.class).hasMessageContaining("timestamp");
        assertThat(ApiSportsParser.baseball(json("{\"errors\": [], \"response\": []}"))).isEmpty();
    }
}
