package com.fanchart.feed;

import com.fanchart.feed.FeedGame.State;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static com.fanchart.feed.ApiSportsParserTest.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/** PandaScore lol/matches 응답(문서의 필드 모양)을 FeedGame으로 옮기는 규칙. 점수는 세트 승수다 */
class PandaScoreProviderTest {

    private final PandaScoreProvider provider = new PandaScoreProvider();

    static String match(int id, String status, String t1Wins, String genWins, boolean forfeit) {
        return """
                {"id": %d, "name": "T1 vs GEN", "scheduled_at": "2026-04-05T08:00:00Z", "begin_at": null,
                 "status": "%s", "forfeit": %s, "draw": false, "number_of_games": 3, "match_type": "best_of",
                 "league_id": 293, "winner_id": null,
                 "opponents": [{"type": "Team", "opponent": {"id": 126061, "name": "T1", "acronym": "T1"}},
                               {"type": "Team", "opponent": {"id": 2882, "name": "Gen.G", "acronym": "GEN"}}],
                 "results": [{"team_id": 2882, "score": %s}, {"team_id": 126061, "score": %s}]}
                """.formatted(id, status, forfeit, genWins, t1Wins);
    }

    @Test
    void readsMatchesWithSetWinsAndOnlyTrustsAFinishedMatchThatWasPlayed() {
        String body = "[%s, %s, %s, %s, %s, %s, %s]".formatted(
                match(1, "not_started", "0", "0", false), match(2, "finished", "2", "1", false),
                match(3, "running", "1", "0", false), match(4, "postponed", "0", "0", false),
                match(5, "canceled", "0", "0", false), match(6, "finished", "2", "0", true),
                match(7, "finished", "null", "1", false));

        var games = provider.parse(json(body));

        assertThat(games).extracting(FeedGame::ref, FeedGame::state, FeedGame::homeScore, FeedGame::awayScore)
                .containsExactly(tuple("1", State.NOT_STARTED, null, null), tuple("2", State.FINAL, 2, 1),
                        tuple("3", State.LIVE, null, null), tuple("4", State.POSTPONED, null, null),
                        tuple("5", State.CANCELED, null, null), tuple("6", State.UNKNOWN, null, null),
                        tuple("7", State.UNKNOWN, null, null));
        FeedGame g = games.get(1);
        assertThat(g.scheduledAt()).isEqualTo(Instant.parse("2026-04-05T08:00:00Z"));
        assertThat(g.homeTeamId()).isEqualTo("126061");
        assertThat(g.homeName()).isEqualTo("T1");
        assertThat(g.awayTeamId()).isEqualTo("2882");
    }

    @Test
    void skipsMatchesWhoseOpponentsOrTimeAreNotDecided() {
        String tbd = """
                [{"id": 1, "scheduled_at": "2026-04-05T08:00:00Z", "status": "not_started", "opponents": [], "results": []},
                 {"id": 2, "scheduled_at": null, "status": "not_started", "results": [],
                  "opponents": [{"opponent": {"id": 1, "name": "A"}}, {"opponent": {"id": 2, "name": "B"}}]}]
                """;

        assertThat(provider.parse(json(tbd))).isEmpty();
    }

    @Test
    void refusesErrorsAndBrokenMatches() {
        assertThatThrownBy(() -> provider.parse(json("{\"error\": \"Token is missing\"}")))
                .isInstanceOf(FeedException.class).hasMessageContaining("Token is missing");
        assertThatThrownBy(() -> provider.parse(json("[" + match(1, "not_started", "0", "0", false)
                .replace("2026-04-05T08:00:00Z", "soon") + "]")))
                .isInstanceOf(FeedException.class);
        assertThatThrownBy(() -> provider.parse(json("[{\"status\": \"running\"}]")))
                .isInstanceOf(FeedException.class);
    }
}
