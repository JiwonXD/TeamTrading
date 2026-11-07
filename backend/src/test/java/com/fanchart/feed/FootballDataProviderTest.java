package com.fanchart.feed;

import com.fanchart.feed.FeedGame.State;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static com.fanchart.feed.ApiSportsParserTest.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/** football-data.org v4 matches 응답(문서의 필드 모양)을 FeedGame으로 옮기는 규칙 */
class FootballDataProviderTest {

    private final FootballDataProvider provider = new FootballDataProvider();

    static String match(int id, String status, String home, String away) {
        return """
                {"id": %d, "utcDate": "2026-11-08T14:00:00Z", "status": "%s", "matchday": 11, "stage": "REGULAR_SEASON",
                 "homeTeam": {"id": 64, "name": "Liverpool FC", "shortName": "Liverpool", "tla": "LIV"},
                 "awayTeam": {"id": 65, "name": "Manchester City FC", "shortName": "Man City", "tla": "MCI"},
                 "score": {"winner": null, "duration": "REGULAR",
                           "fullTime": {"home": %s, "away": %s}, "halfTime": {"home": null, "away": null}}}
                """.formatted(id, status, home, away);
    }

    @Test
    void readsMatchesAndOnlyTrustsAFinishedMatchWithBothScores() {
        String body = """
                {"filters": {"season": "2026"}, "resultSet": {"count": 9},
                 "competition": {"id": 2021, "code": "PL"},
                 "matches": [%s, %s, %s, %s, %s, %s, %s, %s, %s]}
                """.formatted(match(1, "TIMED", "null", "null"), match(2, "FINISHED", "2", "2"),
                match(3, "IN_PLAY", "1", "0"), match(4, "PAUSED", "1", "0"), match(5, "POSTPONED", "null", "null"),
                match(6, "CANCELLED", "null", "null"), match(7, "FINISHED", "null", "1"),
                match(8, "AWARDED", "3", "0"), match(9, "SCHEDULED", "null", "null"));

        var games = provider.parse(json(body));

        assertThat(games).extracting(FeedGame::ref, FeedGame::state, FeedGame::homeScore, FeedGame::awayScore)
                .containsExactly(tuple("1", State.NOT_STARTED, null, null), tuple("2", State.FINAL, 2, 2),
                        tuple("3", State.LIVE, null, null), tuple("4", State.LIVE, null, null),
                        tuple("5", State.POSTPONED, null, null), tuple("6", State.CANCELED, null, null),
                        tuple("7", State.UNKNOWN, null, null), tuple("8", State.UNKNOWN, null, null),
                        tuple("9", State.NOT_STARTED, null, null));
        FeedGame g = games.get(1);
        assertThat(g.scheduledAt()).isEqualTo(Instant.parse("2026-11-08T14:00:00Z"));
        assertThat(g.homeTeamId()).isEqualTo("64");
        assertThat(g.awayName()).isEqualTo("Manchester City FC");
        assertThat(g.rawStatus()).isEqualTo("FINISHED");
    }

    @Test
    void skipsMatchesWhoseTeamsAreNotDecided() {
        String body = """
                {"matches": [{"id": 1, "utcDate": "2026-11-08T14:00:00Z", "status": "TIMED",
                              "homeTeam": {"id": null, "name": null}, "awayTeam": {"id": 65, "name": "Man City"},
                              "score": {"fullTime": {"home": null, "away": null}}}]}
                """;

        assertThat(provider.parse(json(body))).isEmpty();
    }

    @Test
    void refusesErrorsAndBrokenMatches() {
        assertThatThrownBy(() -> provider.parse(json("{\"message\": \"The resource you are looking for is restricted.\", \"errorCode\": 403}")))
                .isInstanceOf(FeedException.class).hasMessageContaining("restricted");
        assertThatThrownBy(() -> provider.parse(json("{\"matches\": [" + match(1, "TIMED", "null", "null")
                .replace("2026-11-08T14:00:00Z", "soon") + "]}")))
                .isInstanceOf(FeedException.class);
        assertThatThrownBy(() -> provider.parse(json("{\"matches\": [{\"status\": \"TIMED\"}]}")))
                .isInstanceOf(FeedException.class);
    }
}
