package com.fanchart.feed;

import com.fanchart.feed.FeedGame.State;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static com.fanchart.feed.ApiSportsParserTest.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/** ESPN scoreboard 응답(2026-27 시즌 실제 응답의 필드 모양)을 FeedGame으로 옮기는 규칙 */
class EspnProviderTest {

    private final EspnProvider provider = new EspnProvider();

    static String event(String id, String name, String state, boolean completed, String home, String away) {
        return """
                {"id": "%s", "date": "2026-10-22T14:30Z", "name": "Kairat Almaty at Panathinaikos",
                 "status": {"clock": 5400.0, "type": {"id": "28", "name": "%s", "state": "%s", "completed": %s}},
                 "competitions": [{"id": "%s", "competitors": [
                   {"id": "2528", "homeAway": "home", "team": {"id": "2528", "displayName": "Kairat Almaty"},
                    "score": "%s", "winner": false},
                   {"id": "443", "homeAway": "away", "team": {"id": "443", "displayName": "Panathinaikos"},
                    "score": "%s", "winner": true}]}]}
                """.formatted(id, name, state, completed, id, home, away);
    }

    @Test
    void readsEventsAndOnlyTrustsACompletedEventWithBothScores() {
        String body = "{\"events\": [%s, %s, %s, %s, %s, %s, %s, %s, %s]}".formatted(
                event("1", "STATUS_SCHEDULED", "pre", false, "0", "0"),
                event("2", "STATUS_FULL_TIME", "post", true, "1", "5"),
                event("3", "STATUS_SECOND_HALF", "in", false, "1", "0"),
                event("4", "STATUS_POSTPONED", "post", false, "0", "0"),
                event("5", "STATUS_CANCELED", "post", false, "0", "0"),
                // 승부차기 점수 칸은 확인하지 못해 운영자가 넣는다
                event("6", "STATUS_FINAL_PEN", "post", true, "1", "1"),
                // 끝났다는 표시가 없거나 점수가 없으면 믿지 않는다
                event("7", "STATUS_FULL_TIME", "post", false, "1", "5"),
                event("8", "STATUS_FINAL", "post", true, "", "2"),
                event("9", "STATUS_FINAL_AET", "post", true, "2", "1"));

        var games = provider.parse(json(body));

        assertThat(games).extracting(FeedGame::ref, FeedGame::state, FeedGame::homeScore, FeedGame::awayScore)
                .containsExactly(tuple("1", State.NOT_STARTED, null, null), tuple("2", State.FINAL, 1, 5),
                        tuple("3", State.LIVE, null, null), tuple("4", State.POSTPONED, null, null),
                        tuple("5", State.CANCELED, null, null), tuple("6", State.UNKNOWN, null, null),
                        tuple("7", State.UNKNOWN, null, null), tuple("8", State.UNKNOWN, null, null),
                        tuple("9", State.FINAL, 2, 1));
        FeedGame g = games.get(1);
        assertThat(g.scheduledAt()).isEqualTo(Instant.parse("2026-10-22T14:30:00Z"));
        assertThat(g.homeTeamId()).isEqualTo("2528");
        assertThat(g.homeName()).isEqualTo("Kairat Almaty");
        assertThat(g.awayTeamId()).isEqualTo("443");
        assertThat(g.rawStatus()).isEqualTo("STATUS_FULL_TIME");
    }

    @Test
    void skipsEventsWhoseTeamsAreNotDecided() {
        String tbd = """
                {"events": [{"id": "1", "date": "2026-12-01T20:00Z", "status": {"type": {"state": "pre"}},
                  "competitions": [{"competitors": [{"homeAway": "home", "team": {"id": "1", "displayName": "A"}}]}]}]}
                """;

        assertThat(provider.parse(json(tbd))).isEmpty();
    }

    @Test
    void putsTheSportBeforeIdsOutsideSoccerBecauseEspnNumbersEachSportSeparately() {
        String body = "{\"events\": [" + event("401", "STATUS_SCHEDULED", "pre", false, "0", "0") + "]}";
        var nba = MatchFeedProperties.CompetitionFeed.of(com.fanchart.team.Competition.NBA, MatchFeedProperties.ESPN,
                "basketball/nba");
        var cup = MatchFeedProperties.CompetitionFeed.of(com.fanchart.team.Competition.FA_CUP, MatchFeedProperties.ESPN,
                "soccer/eng.fa");

        assertThat(provider.parse(nba, json(body))).extracting(FeedGame::ref, FeedGame::homeTeamId, FeedGame::awayTeamId)
                .containsExactly(tuple("basketball:401", "basketball:2528", "basketball:443"));
        assertThat(provider.parse(cup, json(body))).extracting(FeedGame::ref, FeedGame::homeTeamId)
                .containsExactly(tuple("401", "2528"));
    }

    @Test
    void refusesBrokenResponsesAndEvents() {
        assertThatThrownBy(() -> provider.parse(json("{\"code\": 400}"))).isInstanceOf(FeedException.class);
        assertThatThrownBy(() -> provider.parse(json("{\"events\": [" + event("1", "STATUS_SCHEDULED", "pre", false,
                "0", "0").replace("2026-10-22T14:30Z", "soon") + "]}"))).isInstanceOf(FeedException.class);
        assertThatThrownBy(() -> provider.parse(json("{\"events\": [{\"date\": \"2026-10-22T14:30Z\"}]}")))
                .isInstanceOf(FeedException.class);
    }
}
