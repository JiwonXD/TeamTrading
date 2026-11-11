package com.fanchart.auth;

import com.fanchart.engine.OrderType;
import com.fanchart.engine.Side;
import com.fanchart.trading.TradingService;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 비밀 값을 아는 봇 실행기만 봇 계정으로 로그인하고, 봇은 순위와 봇끼리의 이상거래 탐지에서 빠진다 */
@SpringBootTest(properties = "fanchart.auth.bot-token=test-bot-token")
@AutoConfigureMockMvc
class BotLoginTest {

    @Autowired MockMvc mvc;
    @Autowired TradingService trading;

    private ResultActions botLogin(String name, String token, MockHttpSession session) throws Exception {
        var req = post("/api/auth/bot-login").session(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name + "\"}");
        if (token != null) req.header("X-Bot-Token", token);
        return mvc.perform(req);
    }

    private String accountId(MockHttpSession session) throws Exception {
        return JsonPath.read(mvc.perform(get("/api/accounts/me").session(session)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.id");
    }

    private MockHttpSession bot(String name) throws Exception {
        MockHttpSession session = new MockHttpSession();
        botLogin(name, "test-bot-token", session).andExpect(status().isNoContent());
        return session;
    }

    @Test
    void onlyTheTokenLogsInAndTheSameNameIsTheSameAccountWithSignupPoints() throws Exception {
        botLogin("bot-x", null, new MockHttpSession()).andExpect(status().isForbidden());
        botLogin("bot-x", "wrong", new MockHttpSession()).andExpect(status().isForbidden());
        botLogin("Bad Name", "test-bot-token", new MockHttpSession()).andExpect(status().isBadRequest());

        MockHttpSession first = bot("bot-1");
        mvc.perform(get("/api/accounts/me").session(first)).andExpect(jsonPath("$.availablePoints").value(1_000_000));
        assertThat(accountId(bot("bot-1"))).isEqualTo(accountId(first));
        assertThat(accountId(bot("bot-2"))).isNotEqualTo(accountId(first));
    }

    @Test
    void botsAreLeftOutOfRankingsAndTheirTradesWithEachOtherAreNotFlagged() throws Exception {
        MockHttpSession a = bot("bot-wash-a");
        MockHttpSession b = bot("bot-wash-b");
        String sellerId = accountId(a);
        String buyerId = accountId(b);

        mvc.perform(get("/api/rankings").session(a)).andExpect(jsonPath("$[?(@.me == true)]").isEmpty());

        // 같은 상대와 10분에 5번 넘게 체결하면 원래는 이상거래로 잡힌다(TradingApiTest)
        int before = trading.alerts(10_000).size();
        trading.grantShares(sellerId, "nc", 10, 11_600);
        for (int i = 0; i < 6; i++) {
            trading.place(sellerId, "wa-" + i, "nc", Side.SELL, OrderType.LIMIT, 11_600, 1);
            trading.place(buyerId, "wb-" + i, "nc", Side.BUY, OrderType.LIMIT, 11_600, 1);
        }
        List<?> after = trading.alerts(10_000);
        assertThat(after).hasSize(before);
    }
}
