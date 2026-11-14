package com.fanchart.api;

import com.fanchart.trading.TradingService;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.Map;

import static com.fanchart.auth.TestLogin.admin;
import static com.fanchart.auth.TestLogin.login;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 화면 새로 고침 한 번에 받는 값은 따로 있는 조회 API들의 값과 같다(docs/troubleshooting.md 49) */
@SpringBootTest
@AutoConfigureMockMvc
class RefreshApiTest {

    @Autowired MockMvc mvc;
    @Autowired TradingService trading;

    @Test
    void signedInRefreshEqualsTheSeparateCalls() throws Exception {
        String me = trading.openAccount("refresh-user").id();
        mvc.perform(post("/api/orders").with(login(me)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientOrderId\":\"r-1\",\"teamId\":\"t1\",\"side\":\"BUY\",\"type\":\"LIMIT\",\"price\":30000,\"quantity\":2}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/teams/t1/posts").with(login(me)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"새로 고침 확인\"}"))
                .andExpect(status().isCreated());

        String all = body(get("/api/refresh?mine=true&posts=t1,kiwoom"), login(me));

        assertSame(all, "$.teams", "/api/teams", login(me));
        assertSame(all, "$.rankings", "/api/rankings", login(me));
        assertSame(all, "$.matches", "/api/matches?limit=9", login(me));
        assertSame(all, "$.offerings", "/api/offerings?limit=10", login(me));
        assertSame(all, "$.mine.account", "/api/accounts/me", login(me));
        assertSame(all, "$.mine.orders", "/api/accounts/me/orders", login(me));
        assertSame(all, "$.mine.trades", "/api/accounts/me/trades?limit=50", login(me));
        assertSame(all, "$.mine.dividends", "/api/accounts/me/dividends?limit=30", login(me));
        assertSame(all, "$.mine.subscriptions", "/api/accounts/me/subscriptions?limit=20", login(me));
        assertSame(all, "$.mine.ads", "/api/accounts/me/ads", login(me));
        assertSame(all, "$.posts.t1", "/api/teams/t1/posts?limit=30", login(me));
        assertSame(all, "$.posts.kiwoom", "/api/teams/kiwoom/posts?limit=30", login(me));
        assertThat(JsonPath.<Integer>read(all, "$.mine.orders.length()")).isEqualTo(1);
        assertThat(JsonPath.<Boolean>read(all, "$.posts.t1[0].mine")).isTrue();
        // 운영자가 아니면 이상거래 탐지 기록은 받지 않는다
        assertThat(JsonPath.<Integer>read(all, "$.mine.alerts.length()")).isZero();
    }

    @Test
    void adminAlsoGetsAlerts() throws Exception {
        String op = trading.openAccount("refresh-admin").id();
        String all = body(get("/api/refresh?mine=true"), admin(op));
        assertSame(all, "$.mine.alerts", "/api/surveillance/alerts?limit=50", admin(op));
    }

    @Test
    void anonymousGetsMarketOnlyAndAskingForMineIsUnauthorized() throws Exception {
        mvc.perform(get("/api/refresh"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.teams").isNotEmpty())
                .andExpect(jsonPath("$.mine").doesNotExist())
                .andExpect(jsonPath("$.posts").isEmpty());
        mvc.perform(get("/api/refresh?mine=true")).andExpect(status().isUnauthorized());
    }

    @Test
    void limitsHowManyTeamsOfPostsAtOnce() throws Exception {
        mvc.perform(get("/api/refresh?posts=t1,t2,t3,t4,t5,t6,t7,t8,t9,t10,t11")).andExpect(status().isBadRequest());
    }

    private String body(MockHttpServletRequestBuilder req, RequestPostProcessor who) throws Exception {
        return mvc.perform(req.with(who)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private void assertSame(String all, String part, String separate, RequestPostProcessor who) throws Exception {
        Object expected = JsonPath.read(body(get(separate), who), "$");
        Object actual = JsonPath.read(all, part);
        assertThat(actual).as(part).isEqualTo(expected);
        if (expected instanceof Map<?, ?> m) assertThat(m).isNotEmpty();
    }
}
