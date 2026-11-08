package com.fanchart.api;

import com.jayway.jsonpath.JsonPath;
import com.fanchart.trading.TradingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static com.fanchart.auth.TestLogin.admin;
import static com.fanchart.auth.TestLogin.login;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class TeamInfoApiTest {

    @Autowired MockMvc mvc;
    @Autowired TradingService trading;
    @Autowired JdbcTemplate jdbc;

    @Test
    void overviewShowsDividendTermsFromThePolicyAndUpcomingMatches() throws Exception {
        // 일정은 2031년이라 '다가오는 경기'에 잡힌다
        String op = trading.openAccount("info-admin").id();
        long lck = schedule(op, "{\"homeTeamId\":\"hle\",\"awayTeamId\":\"dk\",\"scheduledAt\":\"2031-05-01T09:00:00Z\"}");
        long worlds = schedule(op, "{\"competition\":\"WORLDS\",\"homeName\":\"G2\",\"awayTeamId\":\"hle\",\"scheduledAt\":\"2031-10-01T09:00:00Z\"}");

        mvc.perform(get("/api/teams/hle/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.league").value("LCK"))
                .andExpect(jsonPath("$.winDps").value(160))
                .andExpect(jsonPath("$.confirmDelayMinutes").value(360))
                // 정규 시즌이 먼저, 그다음 LCK 구단이 나가는 국제 대회. 유럽 컵대회는 없다
                .andExpect(jsonPath("$.competitions[*].competition").value(contains("LCK", "LCK_CUP", "MSI", "WORLDS", "EWC")))
                .andExpect(jsonPath("$.competitions[3].winDps").value(240))
                .andExpect(jsonPath("$.competitions[3].championDps").value(4_500))
                .andExpect(jsonPath("$.awards[0].award").value("CHAMPION"))
                .andExpect(jsonPath("$.awards[0].dps").value(3_000))
                .andExpect(jsonPath("$.upcoming[?(@.matchId == " + lck + ")].opponentName").value(hasItem("디플러스 기아")))
                .andExpect(jsonPath("$.upcoming[?(@.matchId == " + lck + ")].winDps").value(hasItem(160)))
                .andExpect(jsonPath("$.upcoming[?(@.matchId == " + worlds + ")].opponentId").value(hasItem((Object) null)))
                .andExpect(jsonPath("$.upcoming[?(@.matchId == " + worlds + ")].opponentName").value(hasItem("G2")))
                .andExpect(jsonPath("$.upcoming[?(@.matchId == " + worlds + ")].home").value(hasItem(false)))
                .andExpect(jsonPath("$.upcoming[?(@.matchId == " + worlds + ")].winDps").value(hasItem(240)));

        mvc.perform(get("/api/teams/nope/overview")).andExpect(status().isNotFound());
    }

    @Test
    void overviewCountsSharesHeldByUsers() throws Exception {
        Long before = jdbc.queryForObject(
                "SELECT COALESCE(SUM(available + locked), 0) FROM positions WHERE team_id = 'kiwoom'", Long.class);
        String fan = trading.openAccount("kiwoom-fan").id();
        trading.grantShares(fan, "kiwoom", 12, 8_740);
        mvc.perform(get("/api/teams/kiwoom/overview"))
                .andExpect(jsonPath("$.shares").value(before + 12));
    }

    @Test
    void onlyAdminsWriteNoticesAndEveryoneReadsTeamAndGlobalOnes() throws Exception {
        String op = trading.openAccount("notice-admin").id();
        String fan = trading.openAccount("notice-fan").id();
        String teamNotice = "{\"teamId\":\"nc\",\"title\":\"승리 배당 안내\",\"body\":\"1승에 1주당 40P\"}";

        mvc.perform(post("/api/admin/notices").with(login(fan)).contentType(MediaType.APPLICATION_JSON).content(teamNotice))
                .andExpect(status().isForbidden());
        long team = write(op, teamNotice);
        long global = write(op, "{\"title\":\"전체 공지\",\"body\":\"점검 안내\"}");
        long other = write(op, "{\"teamId\":\"kt\",\"title\":\"KT 공지\",\"body\":\"다른 구단\"}");

        // 로그인하지 않아도 읽는다. 최신부터, 다른 구단 공시는 빠진다
        mvc.perform(get("/api/teams/nc/notices"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + global + ")].teamId").value(hasItem((Object) null)))
                .andExpect(jsonPath("$[*].id").value(not(hasItem((int) other))))
                .andExpect(jsonPath("$[?(@.id == " + team + ")].title").value(hasItem("승리 배당 안내")));

        mvc.perform(put("/api/admin/notices/" + team).with(admin(op)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"승리 배당 안내(정정)\",\"body\":\"1승에 1주당 40P, 무승부 20P\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("승리 배당 안내(정정)"))
                .andExpect(jsonPath("$.updatedAt").isNotEmpty());

        mvc.perform(delete("/api/admin/notices/" + team).with(login(fan))).andExpect(status().isForbidden());
        mvc.perform(delete("/api/admin/notices/" + team).with(admin(op))).andExpect(status().isNoContent());
        mvc.perform(get("/api/teams/nc/notices")).andExpect(jsonPath("$[*].id").value(not(hasItem((int) team))));
        // 지운 공시도 감사용으로 남는다
        assertThat(jdbc.queryForObject("SELECT deleted_by FROM notices WHERE id = ?", String.class, team)).isEqualTo(op);
        mvc.perform(put("/api/admin/notices/" + team).with(admin(op)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"t\",\"body\":\"b\"}")).andExpect(status().isNotFound());
    }

    @Test
    void rejectsBlankNoticesAndUnknownTeams() throws Exception {
        String op = trading.openAccount("notice-admin2").id();
        mvc.perform(post("/api/admin/notices").with(admin(op)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\" \",\"body\":\"b\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/admin/notices").with(admin(op)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"teamId\":\"nope\",\"title\":\"t\",\"body\":\"b\"}")).andExpect(status().isNotFound());
        mvc.perform(get("/api/teams/nope/notices")).andExpect(status().isNotFound());
    }

    private long schedule(String adminId, String body) throws Exception {
        String res = mvc.perform(post("/api/admin/matches").with(admin(adminId)).contentType(MediaType.APPLICATION_JSON)
                .content(body)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(res, "$.id")).longValue();
    }

    private long write(String adminId, String body) throws Exception {
        String res = mvc.perform(post("/api/admin/notices").with(admin(adminId)).contentType(MediaType.APPLICATION_JSON)
                .content(body)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(res, "$.id")).longValue();
    }
}
