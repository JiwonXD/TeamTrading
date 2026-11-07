package com.fanchart.api;

import com.jayway.jsonpath.JsonPath;
import com.fanchart.engine.OrderType;
import com.fanchart.engine.Side;
import com.fanchart.trading.TradingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static com.fanchart.auth.TestLogin.admin;
import static com.fanchart.auth.TestLogin.login;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class TradingApiTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    TradingService trading;

    private String openAccount(String nickname) {
        return trading.openAccount(nickname).id();
    }

    @Test
    void listsTeamsWithPriceBand() throws Exception {
        mvc.perform(get("/api/teams/lg"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastPrice").value(18_450))
                .andExpect(jsonPath("$.upperLimit").value(23_950))
                .andExpect(jsonPath("$.lowerLimit").value(12_920));
    }

    @Test
    void placesShowsAndCancelsOrder() throws Exception {
        String me = openAccount("api-user");

        String receipt = mvc.perform(post("/api/orders").with(login(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientOrderId\":\"o-1\",\"teamId\":\"t1\",\"side\":\"BUY\",\"type\":\"LIMIT\",\"price\":32000,\"quantity\":3}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.restingQuantity").value(3))
                .andReturn().getResponse().getContentAsString();
        long orderId = ((Number) JsonPath.read(receipt, "$.orderId")).longValue();

        mvc.perform(get("/api/teams/t1/orderbook"))
                .andExpect(jsonPath("$.bids[0].price").value(32_000));
        mvc.perform(get("/api/accounts/me").with(login(me)))
                .andExpect(jsonPath("$.lockedPoints").value(96_000));

        mvc.perform(delete("/api/orders/" + orderId).with(login(me)))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/accounts/me/orders").with(login(me)))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void rejectsOrderOutsidePriceBandWithReadableMessage() throws Exception {
        String me = openAccount("band");
        mvc.perform(post("/api/orders").with(login(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientOrderId\":\"o-2\",\"teamId\":\"lg\",\"side\":\"BUY\",\"type\":\"LIMIT\",\"price\":30000,\"quantity\":1}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.message").value("상한가(23,950)보다 높게 주문할 수 없어요"));
    }

    @Test
    void retriedOrderReturnsSameReceiptAndReusedIdIsConflict() throws Exception {
        String me = openAccount("retry");
        String order = "{\"clientOrderId\":\"r-1\",\"teamId\":\"kt\",\"side\":\"BUY\",\"type\":\"LIMIT\",\"price\":12000,\"quantity\":2}";
        String first = mvc.perform(post("/api/orders").with(login(me))
                        .contentType(MediaType.APPLICATION_JSON).content(order))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String retry = mvc.perform(post("/api/orders").with(login(me))
                        .contentType(MediaType.APPLICATION_JSON).content(order))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(retry).isEqualTo(first);

        mvc.perform(post("/api/orders").with(login(me))
                        .contentType(MediaType.APPLICATION_JSON).content(order.replace("\"quantity\":2", "\"quantity\":3")))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/accounts/me/orders").with(login(me)))
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void showsMyTradesAndMarksMeInRankingWithoutExposingAccountIds() throws Exception {
        String seller = openAccount("my-seller");
        String buyer = openAccount("my-buyer");
        trading.grantShares(seller, "ssg", 5, 13_750);
        trading.place(seller, "s-1", "ssg", Side.SELL, OrderType.LIMIT, 13_750, 5);
        trading.place(buyer, "b-1", "ssg", Side.BUY, OrderType.LIMIT, 13_750, 2);

        mvc.perform(get("/api/accounts/me/trades").with(login(buyer)))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].side").value("BUY"))
                .andExpect(jsonPath("$[0].quantity").value(2));
        mvc.perform(get("/api/accounts/me/trades").with(login(seller)))
                .andExpect(jsonPath("$[0].side").value("SELL"));

        String ranking = mvc.perform(get("/api/rankings").with(login(buyer)))
                .andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(ranking).doesNotContain(buyer).doesNotContain(seller);
        java.util.List<String> mine = JsonPath.read(ranking, "$[?(@.me == true)].nickname");
        org.assertj.core.api.Assertions.assertThat(mine).containsExactly("my-buyer");
    }

    @Test
    void surveillanceAlertsShowNicknamesNotAccountIds() throws Exception {
        String a = openAccount("pair-a");
        String b = openAccount("pair-b");
        trading.grantShares(a, "nc", 10, 11_600);
        for (int i = 0; i < 5; i++) {
            trading.place(a, "a-" + i, "nc", Side.SELL, OrderType.LIMIT, 11_600, 1);
            trading.place(b, "b-" + i, "nc", Side.BUY, OrderType.LIMIT, 11_600, 1);
        }
        String alerts = mvc.perform(get("/api/surveillance/alerts").with(admin(a))).andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(alerts).contains("pair-a", "pair-b").doesNotContain(a).doesNotContain(b);
    }

    @Test
    void requiresLogin() throws Exception {
        mvc.perform(get("/api/accounts/me")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/orders").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientOrderId\":\"x\",\"teamId\":\"lg\",\"side\":\"BUY\",\"type\":\"LIMIT\",\"price\":18450,\"quantity\":1}"))
                .andExpect(status().isUnauthorized());
    }
}
