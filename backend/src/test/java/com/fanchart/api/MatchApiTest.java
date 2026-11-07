package com.fanchart.api;

import com.jayway.jsonpath.JsonPath;
import com.fanchart.trading.TradingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;

import static com.fanchart.auth.TestLogin.admin;
import static com.fanchart.auth.TestLogin.login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class MatchApiTest {

    @Autowired MockMvc mvc;
    @Autowired TradingService trading;

    @Test
    void adminEntersResultAndHoldersSeeTheirDividend() throws Exception {
        String fan = trading.openAccount("doosan-fan").id();
        trading.grantShares(fan, "doosan", 7, 14_850);
        String schedule = "{\"homeTeamId\":\"doosan\",\"awayTeamId\":\"lotte\",\"scheduledAt\":\""
                + Instant.now().plusSeconds(1) + "\"}";

        mvc.perform(post("/api/admin/matches").with(login(fan)).contentType(MediaType.APPLICATION_JSON).content(schedule))
                .andExpect(status().isForbidden());
        String created = mvc.perform(post("/api/admin/matches").with(admin(fan))
                        .contentType(MediaType.APPLICATION_JSON).content(schedule))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(created, "$.id")).longValue();

        Thread.sleep(1_100);
        mvc.perform(post("/api/admin/matches/" + id + "/result").with(admin(fan))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"homeScore\":4,\"awayScore\":1}"))
                .andExpect(jsonPath("$.status").value("FINAL"))
                .andExpect(jsonPath("$.dividendStatus").value("NONE"));
        mvc.perform(post("/api/admin/matches/" + id + "/settle").with(admin(fan)))
                .andExpect(jsonPath("$.dividendStatus").value("PAID"))
                .andExpect(jsonPath("$.dividends[0].teamId").value("doosan"))
                .andExpect(jsonPath("$.dividends[0].total").value(7 * 40));

        mvc.perform(get("/api/teams/doosan/matches"))
                .andExpect(jsonPath("$[0].homeScore").value(4));
        mvc.perform(get("/api/accounts/me/dividends").with(login(fan)))
                .andExpect(jsonPath("$[0].matchId").value(id))
                .andExpect(jsonPath("$[0].shares").value(7))
                .andExpect(jsonPath("$[0].amount").value(7 * 40));
    }

    @Test
    void scheduleListsOneLeaguesMatchesInTheRangeInStartOrder() throws Exception {
        String admin = trading.openAccount("schedule-admin").id();
        Instant base = Instant.parse("2031-04-01T09:00:00Z");
        long later = schedule(admin, "lg", "kia", base.plusSeconds(7_200));
        long earlier = schedule(admin, "ssg", "nc", base);
        long nextWeek = schedule(admin, "kt", "lotte", base.plus(Duration.ofDays(8)));
        long otherLeague = schedule(admin, "t1", "geng", base.plusSeconds(3_600));

        mvc.perform(get("/api/matches/schedule").param("league", "KBO")
                        .param("from", base.minusSeconds(3_600).toString()).param("to", base.plus(Duration.ofDays(7)).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(earlier))
                .andExpect(jsonPath("$[1].id").value(later));
        mvc.perform(get("/api/matches/schedule").param("league", "LCK")
                        .param("from", base.toString()).param("to", base.plus(Duration.ofDays(1)).toString()))
                .andExpect(jsonPath("$[0].id").value(otherLeague));
        mvc.perform(get("/api/matches/schedule").param("league", "KBO")
                        .param("from", base.plus(Duration.ofDays(8)).toString()).param("to", base.plus(Duration.ofDays(9)).toString()))
                .andExpect(jsonPath("$[0].id").value(nextWeek));
    }

    @Test
    void scheduleRejectsAnInvertedOrTooLongRange() throws Exception {
        Instant now = Instant.now();
        mvc.perform(get("/api/matches/schedule").param("league", "KBO")
                        .param("from", now.toString()).param("to", now.minusSeconds(1).toString()))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/matches/schedule").param("league", "KBO")
                        .param("from", now.toString()).param("to", now.plus(Duration.ofDays(32)).toString()))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/matches/schedule").param("league", "NBA")
                        .param("from", now.toString()).param("to", now.plusSeconds(60).toString()))
                .andExpect(status().isBadRequest());
    }

    private long schedule(String admin, String home, String away, Instant at) throws Exception {
        String body = "{\"homeTeamId\":\"" + home + "\",\"awayTeamId\":\"" + away + "\",\"scheduledAt\":\"" + at + "\"}";
        String created = mvc.perform(post("/api/admin/matches").with(admin(admin))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(created, "$.id")).longValue();
    }
}
