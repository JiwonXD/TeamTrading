package com.fanchart.auth;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.fanchart.auth.TestLogin.admin;
import static com.fanchart.auth.TestLogin.login;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AuthTest {

    @Autowired MockMvc mvc;
    @Autowired AccountLogin login;
    @Autowired JdbcTemplate jdbc;

    private MockHttpSession devLogin(String nickname) throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/api/auth/dev-login").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"nickname\":\"" + nickname + "\"}"))
                .andExpect(status().isNoContent());
        return session;
    }

    @Test
    void devLoginKeepsTheAccountInTheSession() throws Exception {
        MockHttpSession first = devLogin("dev-kim");
        String body = mvc.perform(get("/api/accounts/me").session(first))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availablePoints").value(1_000_000))
                .andReturn().getResponse().getContentAsString();

        MockHttpSession again = devLogin("dev-kim");
        mvc.perform(get("/api/accounts/me").session(again))
                .andExpect(jsonPath("$.id").value((String) JsonPath.read(body, "$.id")));
    }

    @Test
    void stateChangingRequestsNeedCsrfToken() throws Exception {
        MockHttpSession session = devLogin("dev-csrf");
        mvc.perform(post("/api/orders").session(session).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientOrderId\":\"c-1\",\"teamId\":\"lg\",\"side\":\"BUY\",\"type\":\"LIMIT\",\"price\":18450,\"quantity\":1}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void logoutEndsTheSession() throws Exception {
        MockHttpSession session = devLogin("dev-out");
        mvc.perform(post("/api/auth/logout").session(session).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get("/api/accounts/me").session(session)).andExpect(status().isUnauthorized());
    }

    @Test
    void surveillanceIsForAdminsOnly() throws Exception {
        String id = login.findOrCreate("dev", "someone");
        mvc.perform(get("/api/surveillance/reconciliation")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/surveillance/reconciliation").with(login(id))).andExpect(status().isForbidden());
        mvc.perform(get("/api/surveillance/reconciliation").with(admin(id))).andExpect(status().isOk());
        mvc.perform(get("/api/surveillance/alerts")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/surveillance/alerts").with(login(id))).andExpect(status().isForbidden());
        mvc.perform(get("/api/surveillance/alerts").with(admin(id))).andExpect(status().isOk());
    }

    @Test
    void accountResponseTellsWhetherTheAccountIsAnAdmin() throws Exception {
        String id = login.findOrCreate("dev", "admin-flag");
        mvc.perform(get("/api/accounts/me").with(login(id))).andExpect(jsonPath("$.admin").value(false));
        mvc.perform(get("/api/accounts/me").with(admin(id))).andExpect(jsonPath("$.admin").value(true));
    }

    @Test
    void publicMarketDataNeedsNoLogin() throws Exception {
        // 로그인하지 않은 사람도 둘러볼 수 있도록 화면이 받는 것은 모두 열려 있다
        for (String path : List.of("/api/teams", "/api/rankings", "/api/matches?limit=9", "/api/offerings?limit=10",
                "/api/leagues", "/api/market-hours", "/api/leaderboard", "/api/teams/t1/orderbook",
                "/api/teams/t1/candles?unit=day", "/api/teams/t1/overview", "/api/teams/t1/notices", "/api/teams/t1/posts?limit=30")) {
            mvc.perform(get(path)).andExpect(status().isOk());
        }
        // 계정이 필요한 것은 로그인해야 한다
        mvc.perform(get("/api/accounts/me")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/teams/t1/posts").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"hi\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/orders").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientOrderId\":\"c-anon\",\"teamId\":\"t1\",\"side\":\"BUY\",\"type\":\"LIMIT\",\"price\":10000,\"quantity\":1}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/options"))
                .andExpect(jsonPath("$.devLogin").value(true))
                .andExpect(jsonPath("$.providers.length()").value(0));
    }

    @Test
    void concurrentFirstLoginsCreateOneAccountAndOneSignupReward() throws Exception {
        int accountsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM accounts", Integer.class);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<String>> results = new ArrayList<>();
        for (int i = 0; i < 16; i++) results.add(pool.submit(() -> login.findOrCreate("kakao", "12345")));
        List<String> ids = new ArrayList<>();
        for (Future<String> f : results) ids.add(f.get());
        pool.shutdown();

        assertThat(ids).containsOnly(ids.getFirst());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM point_grants WHERE account_id = ?", Integer.class,
                ids.getFirst())).isEqualTo(1);
        // 충돌로 롤백된 쪽의 계정과 가입 보상은 남지 않는다
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM accounts", Integer.class)).isEqualTo(accountsBefore + 1);
    }

    @Test
    void readsUserIdFromEachProvider() {
        assertThat(OAuthLoginSuccess.subject("kakao", Map.of("id", 4242L))).isEqualTo("4242");
        assertThat(OAuthLoginSuccess.subject("google", Map.of("sub", "g-1"))).isEqualTo("g-1");
    }
}
