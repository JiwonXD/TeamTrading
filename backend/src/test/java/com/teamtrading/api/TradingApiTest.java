package com.teamtrading.api;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

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

    private String openAccount(String nickname) throws Exception {
        String body = mvc.perform(post("/api/accounts").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"" + nickname + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
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

        String receipt = mvc.perform(post("/api/orders").header("X-Account-Id", me)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"teamId\":\"t1\",\"side\":\"BUY\",\"type\":\"LIMIT\",\"price\":32000,\"quantity\":3}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.restingQuantity").value(3))
                .andReturn().getResponse().getContentAsString();
        long orderId = ((Number) JsonPath.read(receipt, "$.orderId")).longValue();

        mvc.perform(get("/api/teams/t1/orderbook"))
                .andExpect(jsonPath("$.bids[0].price").value(32_000));
        mvc.perform(get("/api/accounts/me").header("X-Account-Id", me))
                .andExpect(jsonPath("$.lockedPoints").value(96_000));

        mvc.perform(delete("/api/orders/" + orderId).header("X-Account-Id", me))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/accounts/me/orders").header("X-Account-Id", me))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void rejectsOrderOutsidePriceBandWithReadableMessage() throws Exception {
        String me = openAccount("band");
        mvc.perform(post("/api/orders").header("X-Account-Id", me)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"teamId\":\"lg\",\"side\":\"BUY\",\"type\":\"LIMIT\",\"price\":30000,\"quantity\":1}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.message").value("상한가(23,950)보다 높게 주문할 수 없어요"));
    }

    @Test
    void requiresAccountHeader() throws Exception {
        mvc.perform(get("/api/accounts/me")).andExpect(status().isUnauthorized());
    }
}
