package com.fanchart.api;

import com.fanchart.engine.OrderType;
import com.fanchart.engine.Side;
import com.fanchart.trading.TradingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static com.fanchart.auth.TestLogin.login;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"fanchart.leaderboard.min-trades=1", "fanchart.leaderboard.min-age=PT0S"})
@AutoConfigureMockMvc
class LeaderboardApiTest {

    @Autowired MockMvc mvc;
    @Autowired TradingService trading;

    @Test
    void showsNicknamesAndMyRowWithoutAccountIds() throws Exception {
        String seller = trading.openAccount("lb-seller").id();
        String buyer = trading.openAccount("lb-buyer").id();
        trading.grantShares(seller, "ssg", 5, 13_750);
        trading.place(seller, "lb-s", "ssg", Side.SELL, OrderType.LIMIT, 13_750, 1);
        trading.place(buyer, "lb-b", "ssg", Side.BUY, OrderType.LIMIT, 13_750, 1);

        String body = mvc.perform(get("/api/leaderboard?period=TODAY&sort=PNL").with(login(buyer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.period").value("TODAY"))
                .andExpect(jsonPath("$.entries[?(@.nickname == 'lb-buyer')].me").value(true))
                .andExpect(jsonPath("$.entries[?(@.nickname == 'lb-seller')].me").value(false))
                .andExpect(jsonPath("$.mine.trades").value(1))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(buyer).doesNotContain(seller);

        mvc.perform(get("/api/leaderboard?period=SEASON")).andExpect(status().isOk())
                .andExpect(jsonPath("$.mine").doesNotExist());
        mvc.perform(get("/api/leaderboard?period=YEAR")).andExpect(status().isBadRequest());
    }
}
