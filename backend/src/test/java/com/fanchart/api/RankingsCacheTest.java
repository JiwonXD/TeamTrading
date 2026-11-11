package com.fanchart.api;

import com.fanchart.TestClock;
import com.fanchart.trading.TradingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;

import static com.fanchart.auth.TestLogin.login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/** 전체 순위는 잠깐 만들어 둔 것을 함께 쓰고, 내 순위 표시만 요청마다 붙인다(docs/troubleshooting.md 47) */
@SpringBootTest(properties = "fanchart.rankings.cache-ttl=PT5S")
@AutoConfigureMockMvc
@Import(TestClock.Config.class)
class RankingsCacheTest {

    @Autowired MockMvc mvc;
    @Autowired TradingService trading;
    @Autowired TestClock clock;

    @Test
    void sharesRankingsForTheTtlAndMarksMeOnEachRequest() throws Exception {
        String early = trading.openAccount("cache-early").id();
        mvc.perform(get("/api/rankings").with(login(early)))
                .andExpect(jsonPath("$[?(@.nickname == 'cache-early')].me").value(true));

        String late = trading.openAccount("cache-late").id();
        // 만들어 둔 순위에는 아직 없다. 같은 순위를 다른 사람이 받아도 me는 그 사람 기준이다
        mvc.perform(get("/api/rankings").with(login(late)))
                .andExpect(jsonPath("$[?(@.nickname == 'cache-late')]").isEmpty())
                .andExpect(jsonPath("$[?(@.nickname == 'cache-early')].me").value(false));

        clock.advance(Duration.ofSeconds(5));
        mvc.perform(get("/api/rankings").with(login(late)))
                .andExpect(jsonPath("$[?(@.nickname == 'cache-late')].me").value(true));
    }
}
