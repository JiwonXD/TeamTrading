package com.teamtrading.api;

import com.jayway.jsonpath.JsonPath;
import com.teamtrading.trading.TradingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static com.teamtrading.auth.TestLogin.admin;
import static com.teamtrading.auth.TestLogin.login;
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
}
