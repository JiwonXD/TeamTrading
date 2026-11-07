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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static com.fanchart.auth.TestLogin.login;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestClock.Config.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class WatchlistApiTest {

    @Autowired MockMvc mvc;
    @Autowired TradingService trading;
    @Autowired Withdrawal withdrawal;
    @Autowired JdbcTemplate jdbc;

    private String me;
    private String other;

    @BeforeEach
    void setUp() {
        me = trading.openAccount("fav-" + UUID.randomUUID().toString().substring(0, 8)).id();
        other = trading.openAccount("fav-" + UUID.randomUUID().toString().substring(0, 8)).id();
    }

    private ResultActions add(String accountId, String teamId) throws Exception {
        return mvc.perform(put("/api/accounts/me/watchlist/" + teamId).with(login(accountId)));
    }

    private ResultActions remove(String accountId, String teamId) throws Exception {
        return mvc.perform(delete("/api/accounts/me/watchlist/" + teamId).with(login(accountId)));
    }

    @Test
    void addsAndRemovesTeamsInTheOrderTheyWereAdded() throws Exception {
        mvc.perform(get("/api/accounts/me/watchlist").with(login(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.teamIds", empty()));

        add(me, "t1").andExpect(status().isOk()).andExpect(jsonPath("$.teamIds", contains("t1")));
        add(me, "lg").andExpect(jsonPath("$.teamIds", contains("t1", "lg")));
        add(me, "kia").andExpect(jsonPath("$.teamIds", contains("t1", "lg", "kia")));

        // 같은 구단을 다시 넣어도 한 번만, 자리도 그대로
        add(me, "t1").andExpect(status().isOk()).andExpect(jsonPath("$.teamIds", contains("t1", "lg", "kia")));

        remove(me, "lg").andExpect(status().isOk()).andExpect(jsonPath("$.teamIds", contains("t1", "kia")));
        // 없는 걸 빼도 그대로
        remove(me, "lg").andExpect(status().isOk()).andExpect(jsonPath("$.teamIds", contains("t1", "kia")));
        // 뺐다 다시 넣으면 맨 끝
        add(me, "lg").andExpect(jsonPath("$.teamIds", contains("t1", "kia", "lg")));

        mvc.perform(get("/api/accounts/me/watchlist").with(login(me)))
                .andExpect(jsonPath("$.teamIds", contains("t1", "kia", "lg")));
        // 남의 목록에는 영향이 없다
        mvc.perform(get("/api/accounts/me/watchlist").with(login(other)))
                .andExpect(jsonPath("$.teamIds", empty()));
    }

    @Test
    void rejectsUnknownTeamsAndAnonymousRequests() throws Exception {
        add(me, "nope").andExpect(status().isNotFound());
        mvc.perform(put("/api/accounts/me/watchlist/t1").with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/accounts/me/watchlist").with(login(me))).andExpect(jsonPath("$.teamIds", empty()));
    }

    @Test
    void skipsTeamsThatAreNoLongerListed() throws Exception {
        add(me, "t1").andExpect(status().isOk());
        jdbc.update("INSERT INTO account_watchlist (account_id, team_id, position) VALUES (?, 'gone', 5)", me);
        mvc.perform(get("/api/accounts/me/watchlist").with(login(me)))
                .andExpect(jsonPath("$.teamIds", contains("t1")));
    }

    @Test
    void concurrentAddsFromTwoDevicesKeepEveryTeam() throws Exception {
        String[] ids = {"lg", "kia", "samsung", "doosan", "kt", "ssg", "lotte", "hanwha", "nc", "kiwoom", "t1", "geng"};
        try (var pool = Executors.newFixedThreadPool(6)) {
            var tasks = IntStream.range(0, ids.length)
                    .mapToObj(i -> (Callable<Integer>) () -> add(me, ids[i]).andReturn().getResponse().getStatus())
                    .toList();
            for (var f : pool.invokeAll(tasks)) assertThat(f.get()).isEqualTo(200);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM account_watchlist WHERE account_id = ?", Integer.class, me))
                .isEqualTo(ids.length);
        assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT position) FROM account_watchlist WHERE account_id = ?", Integer.class, me))
                .isEqualTo(ids.length);
    }

    @Test
    void withdrawalRemovesWatchlist() throws Exception {
        add(me, "t1").andExpect(status().isOk());
        withdrawal.withdraw(me);
        Integer rows = jdbc.queryForObject("SELECT COUNT(*) FROM account_watchlist WHERE account_id = ?", Integer.class, me);
        assertThat(rows).isZero();
    }
}
