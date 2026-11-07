package com.fanchart.api;

import com.fanchart.TestClock;
import com.fanchart.auth.Withdrawal;
import com.fanchart.trading.TradingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static com.fanchart.auth.TestLogin.login;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestClock.Config.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class LeagueTabsApiTest {

    @Autowired MockMvc mvc;
    @Autowired TradingService trading;
    @Autowired Withdrawal withdrawal;
    @Autowired JdbcTemplate jdbc;

    private String me;
    private String other;

    @BeforeEach
    void setUp() {
        me = trading.openAccount("tab-" + UUID.randomUUID().toString().substring(0, 8)).id();
        other = trading.openAccount("tab-" + UUID.randomUUID().toString().substring(0, 8)).id();
    }

    private ResultActions save(String accountId, String json) throws Exception {
        return mvc.perform(put("/api/accounts/me/league-tabs").with(login(accountId))
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    @Test
    void savesOrderAndHiddenLeaguesPerAccount() throws Exception {
        mvc.perform(get("/api/accounts/me/league-tabs").with(login(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.order", empty()))
                .andExpect(jsonPath("$.hidden", empty()));

        save(me, "{\"order\":[\"LCK\",\"EPL\",\"KBO\",\"K_LEAGUE\"],\"hidden\":[\"K_LEAGUE\"]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.order[0]").value("LCK"));

        mvc.perform(get("/api/accounts/me/league-tabs").with(login(me)))
                .andExpect(jsonPath("$.order.length()").value(4))
                .andExpect(jsonPath("$.order[0]").value("LCK"))
                .andExpect(jsonPath("$.order[1]").value("EPL"))
                .andExpect(jsonPath("$.order[3]").value("K_LEAGUE"))
                .andExpect(jsonPath("$.hidden.length()").value(1))
                .andExpect(jsonPath("$.hidden[0]").value("K_LEAGUE"));

        // 다시 저장하면 통째로 바뀐다
        save(me, "{\"order\":[\"KBO\"],\"hidden\":[]}").andExpect(status().isOk());
        mvc.perform(get("/api/accounts/me/league-tabs").with(login(me)))
                .andExpect(jsonPath("$.order.length()").value(1))
                .andExpect(jsonPath("$.hidden", empty()));

        // 남의 설정에는 영향이 없다
        mvc.perform(get("/api/accounts/me/league-tabs").with(login(other)))
                .andExpect(jsonPath("$.order", empty()));
    }

    @Test
    void rejectsMalformedSettings() throws Exception {
        save(me, "{\"order\":[\"LCK\",\"LCK\"],\"hidden\":[]}").andExpect(status().isUnprocessableContent());
        save(me, "{\"order\":[\"LCK\"],\"hidden\":[\"KBO\"]}").andExpect(status().isUnprocessableContent());
        save(me, "{\"order\":[\"NOPE\"],\"hidden\":[]}").andExpect(status().isBadRequest());
        save(me, "{\"order\":[\"LCK\"]}").andExpect(status().isBadRequest());
        mvc.perform(put("/api/accounts/me/league-tabs").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"order\":[],\"hidden\":[]}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/accounts/me/league-tabs").with(login(me))).andExpect(jsonPath("$.order", empty()));
    }

    @Test
    void skipsLeaguesThatNoLongerExist() throws Exception {
        save(me, "{\"order\":[\"LCK\",\"KBO\"],\"hidden\":[]}").andExpect(status().isOk());
        jdbc.update("INSERT INTO account_league_tabs (account_id, league, position, hidden) VALUES (?, 'GONE', 1, FALSE)", me);
        mvc.perform(get("/api/accounts/me/league-tabs").with(login(me)))
                .andExpect(jsonPath("$.order.length()").value(2))
                .andExpect(jsonPath("$.order[1]").value("KBO"));
    }

    @Test
    void withdrawalRemovesSettings() throws Exception {
        save(me, "{\"order\":[\"LCK\"],\"hidden\":[\"LCK\"]}").andExpect(status().isOk());
        withdrawal.withdraw(me);
        Integer rows = jdbc.queryForObject("SELECT COUNT(*) FROM account_league_tabs WHERE account_id = ?", Integer.class, me);
        assertThat(rows).isZero();
    }
}
