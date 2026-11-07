package com.teamtrading.api;

import com.jayway.jsonpath.JsonPath;
import com.teamtrading.trading.TradingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;

import static com.teamtrading.auth.TestLogin.admin;
import static com.teamtrading.auth.TestLogin.login;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 첫 상장 공모는 그 구단 거래를 막으므로 다른 테스트와 컨텍스트를 나누지 않는다 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext
class OfferingApiTest {

    @Autowired MockMvc mvc;
    @Autowired TradingService trading;

    @Test
    void adminOpensAnOfferingAndSignedInUsersSubscribe() throws Exception {
        String fan = trading.openAccount("ipo-fan").id();
        Instant now = Instant.now();
        String terms = """
                {"teamId":"hanwha","totalShares":1000,"maxPerAccount":20,"subscribeFrom":"%s","subscribeTo":"%s","listingAt":"%s"}"""
                .formatted(now.minusSeconds(1), now.plus(Duration.ofHours(1)), now.plus(Duration.ofHours(2)));

        mvc.perform(post("/api/admin/offerings").with(login(fan)).contentType(MediaType.APPLICATION_JSON).content(terms))
                .andExpect(status().isForbidden());
        String created = mvc.perform(post("/api/admin/offerings").with(admin(fan))
                        .contentType(MediaType.APPLICATION_JSON).content(terms))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.firstListing").value(true))
                .andExpect(jsonPath("$.status").value("SUBSCRIBING"))
                .andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(created, "$.id")).longValue();
        long price = ((Number) JsonPath.read(created, "$.price")).longValue();

        mvc.perform(post("/api/offerings/" + id + "/subscriptions").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":5}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/offerings/" + id + "/subscriptions").with(login(fan))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deposit").value(5 * price))
                .andExpect(jsonPath("$.accountId").doesNotExist());
        mvc.perform(post("/api/offerings/" + id + "/subscriptions").with(login(fan))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":21}"))
                .andExpect(status().isConflict());

        mvc.perform(get("/api/offerings"))
                .andExpect(jsonPath("$[0].teamName").value("한화 이글스"))
                .andExpect(jsonPath("$[0].subscribers").value(1))
                .andExpect(jsonPath("$[0].requested").value(5));
        mvc.perform(get("/api/accounts/me/subscriptions").with(login(fan)))
                .andExpect(jsonPath("$[0].offeringId").value(id))
                .andExpect(jsonPath("$[0].quantity").value(5));
        mvc.perform(post("/api/orders").with(login(fan)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientOrderId\":\"ipo-1\",\"teamId\":\"hanwha\",\"side\":\"BUY\",\"type\":\"LIMIT\",\"price\":" + price + ",\"quantity\":1}"))
                .andExpect(status().isUnprocessableContent());

        mvc.perform(delete("/api/offerings/" + id + "/subscriptions/me").with(login(fan)))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/offerings/" + id)).andExpect(jsonPath("$.subscribers").value(0));
    }
}
