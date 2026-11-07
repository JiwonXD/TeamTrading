package com.fanchart.ad;

import com.jayway.jsonpath.JsonPath;
import com.fanchart.trading.TradingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static com.fanchart.auth.TestLogin.login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 기본 설정(운영)에서는 시연용 가짜 광고와 AdMob 콜백이 모두 닫혀 있다 */
@SpringBootTest
@AutoConfigureMockMvc
class AdDemoOffTest {

    @Autowired MockMvc mvc;
    @Autowired TradingService trading;

    @Test
    void theDemoAdIsClosedOutsideTheDemoServer() throws Exception {
        String me = trading.openAccount("prod").id();
        mvc.perform(get("/api/accounts/me/ads").with(login(me))).andExpect(jsonPath("$.demo").value(false));
        String body = mvc.perform(post("/api/accounts/me/ads").with(login(me)))
                .andReturn().getResponse().getContentAsString();
        String session = JsonPath.read(body, "$.id");
        mvc.perform(post("/api/accounts/me/ads/" + session + "/demo-complete").with(login(me)))
                .andExpect(status().isNotFound());
    }
}
