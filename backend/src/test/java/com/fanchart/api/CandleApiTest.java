package com.fanchart.api;

import com.fanchart.engine.OrderType;
import com.fanchart.engine.Side;
import com.fanchart.trading.TradingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class CandleApiTest {

    @Autowired MockMvc mvc;
    @Autowired TradingService trading;

    @Test
    void buildsCandlesFromStoredTrades() throws Exception {
        String seller = trading.openAccount("cd-seller").id();
        String buyer = trading.openAccount("cd-buyer").id();
        trading.grantShares(seller, "pohang", 10, 9_980);
        trading.place(seller, "cd-s1", "pohang", Side.SELL, OrderType.LIMIT, 9_980, 2);
        trading.place(buyer, "cd-b1", "pohang", Side.BUY, OrderType.LIMIT, 9_980, 2);
        trading.place(seller, "cd-s2", "pohang", Side.SELL, OrderType.LIMIT, 10_050, 3);
        trading.place(buyer, "cd-b2", "pohang", Side.BUY, OrderType.LIMIT, 10_050, 3);

        mvc.perform(get("/api/teams/pohang/candles?unit=year"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candles.length()").value(1))
                .andExpect(jsonPath("$.candles[0].open").value(9_980))
                .andExpect(jsonPath("$.candles[0].high").value(10_050))
                .andExpect(jsonPath("$.candles[0].close").value(10_050))
                .andExpect(jsonPath("$.candles[0].volume").value(5))
                .andExpect(jsonPath("$.more").value(false));
    }

    @Test
    void rejectsUnknownUnitAndTeam() throws Exception {
        mvc.perform(get("/api/teams/pohang/candles?unit=7m")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/teams/nope/candles?unit=5m")).andExpect(status().isNotFound());
    }
}
