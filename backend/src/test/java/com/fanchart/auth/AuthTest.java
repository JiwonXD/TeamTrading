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
    void reconciliationIsForAdminsOnly() throws Exception {
        String id = login.findOrCreate("dev", "someone");
        mvc.perform(get("/api/surveillance/reconciliation")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/surveillance/reconciliation").with(login(id))).andExpect(status().isForbidden());
        mvc.perform(get("/api/surveillance/reconciliation").with(admin(id))).andExpect(status().isOk());
    }

    @Test
    void publicMarketDataNeedsNoLogin() throws Exception {
        mvc.perform(get("/api/teams")).andExpect(status().isOk());
        mvc.perform(get("/api/rankings")).andExpect(status().isOk());
        mvc.perform(get("/api/surveillance/alerts")).andExpect(status().isOk());
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
        assertThat(SecurityConfig.subject("kakao", Map.of("id", 4242L))).isEqualTo("4242");
        assertThat(SecurityConfig.subject("google", Map.of("sub", "g-1"))).isEqualTo("g-1");
        assertThat(SecurityConfig.subject("naver", Map.of("response", Map.of("id", "n-1")))).isEqualTo("n-1");
    }
}
