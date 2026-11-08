package com.fanchart.feed;

import com.fanchart.feed.MatchFeedProperties.CompetitionFeed;
import com.fanchart.team.Competition;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static com.fanchart.auth.TestLogin.admin;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 닫아 둔 리그는 정규 시즌을 수집하지 않고, 화면에 닫혔다고 알린다(ADR 0033) */
@SpringBootTest(properties = {
        "fanchart.leagues.hidden=KBO,K_LEAGUE",
        "fanchart.match-feed.keys.api-sports=test-key",
        "fanchart.match-feed.competitions[0].competition=KBO",
        "fanchart.match-feed.competitions[0].source=api-baseball",
        "fanchart.match-feed.competitions[0].league-id=5",
        "fanchart.match-feed.competitions[1].competition=NBA",
        "fanchart.match-feed.competitions[1].source=espn",
        "fanchart.match-feed.competitions[1].league-id=basketball/nba"})
@AutoConfigureMockMvc
class HiddenLeagueTest {

    @Autowired MatchFeedSync sync;
    @Autowired MockMvc mvc;

    @Test
    void hiddenLeaguesAreNotCollectedAndTheScreenIsTold() throws Exception {
        assertThat(sync.active(CompetitionFeed.of(Competition.KBO, MatchFeedProperties.BASEBALL, "5"))).isFalse();
        assertThat(sync.active(CompetitionFeed.of(Competition.NBA, MatchFeedProperties.ESPN, "basketball/nba"))).isTrue();
        assertThat(sync.enabled()).isTrue();

        mvc.perform(get("/api/leagues")).andExpect(status().isOk())
                .andExpect(jsonPath("$.hidden[0]").value("KBO"))
                .andExpect(jsonPath("$.hidden[1]").value("K_LEAGUE"));
        mvc.perform(get("/api/admin/match-feed").with(admin("ops")))
                .andExpect(jsonPath("$[0].competition").value("KBO"))
                .andExpect(jsonPath("$[0].active").value(false))
                .andExpect(jsonPath("$[1].active").value(true));
        // 구단은 그대로 있어 가진 사람이 팔 수 있다
        mvc.perform(get("/api/teams/lg")).andExpect(status().isOk());
    }
}
