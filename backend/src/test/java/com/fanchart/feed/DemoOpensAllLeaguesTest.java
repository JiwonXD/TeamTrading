package com.fanchart.feed;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/** 시연 프로필은 리그마다 가상 경기를 잡으므로 닫는 리그가 없다(ADR 0033) */
@SpringBootTest
@ActiveProfiles("demo")
@AutoConfigureMockMvc
class DemoOpensAllLeaguesTest {

    @Autowired MockMvc mvc;

    @Test
    void demoProfileHidesNoLeague() throws Exception {
        mvc.perform(get("/api/leagues")).andExpect(jsonPath("$.hidden").isEmpty());
    }
}
