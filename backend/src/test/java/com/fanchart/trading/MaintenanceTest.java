package com.fanchart.trading;

import com.fanchart.TestClock;
import com.fanchart.engine.OrderType;
import com.fanchart.engine.Side;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/** 매일 00:00~00:30(한국 시간)은 점검시간이라 주문을 받지 않는다(ADR 0036). 테스트 시계는 UTC라 한국 자정은 15:00Z다 */
@SpringBootTest(properties = "fanchart.trading.maintenance=true")
@AutoConfigureMockMvc
@Import(TestClock.Config.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class MaintenanceTest {

    static final Instant MIDNIGHT_KST = Instant.parse("2026-11-02T15:00:00Z");

    @Autowired TradingService trading;
    @Autowired TestClock clock;
    @Autowired MockMvc mvc;

    private String buyer;

    @BeforeEach
    void setUp() {
        buyer = trading.openAccount("maint-buyer").id();
    }

    private OrderReceipt buy(String clientOrderId) {
        return trading.place(buyer, clientOrderId, "lg", Side.BUY, OrderType.LIMIT, 18_450, 1);
    }

    @Test
    void rejectsOrdersFromMidnightUntilHalfPastInKoreanTime() {
        clock.set(MIDNIGHT_KST.minusSeconds(1));
        assertThat(buy("before").restingQuantity()).isEqualTo(1);

        clock.set(MIDNIGHT_KST);
        assertThatThrownBy(() -> buy("at-midnight")).isInstanceOf(TradingException.class).hasMessageContaining("점검시간");
        clock.set(MIDNIGHT_KST.plusSeconds(30 * 60 - 1));
        assertThatThrownBy(() -> buy("last-second")).isInstanceOf(TradingException.class).hasMessageContaining("점검시간");

        clock.set(MIDNIGHT_KST.plusSeconds(30 * 60));
        assertThat(buy("after").restingQuantity()).isEqualTo(1);
    }

    @Test
    void aRetryOfAnOrderAcceptedBeforeMaintenanceGetsItsReceiptAndCancelStillWorks() {
        clock.set(MIDNIGHT_KST.minusSeconds(1));
        OrderReceipt first = buy("retry");

        clock.set(MIDNIGHT_KST.plusSeconds(5));
        assertThat(buy("retry")).isEqualTo(first);
        // 점검시간에도 취소는 받아 묶인 포인트를 돌려준다
        trading.cancel(buyer, first.orderId());
        assertThat(trading.account(buyer).lockedPoints()).isZero();
    }

    @Test
    void tellsTheScreenTheWindow() throws Exception {
        mvc.perform(get("/api/market-hours"))
                .andExpect(jsonPath("$.maintenance.start").value("00:00"))
                .andExpect(jsonPath("$.maintenance.end").value("00:30"))
                .andExpect(jsonPath("$.maintenance.timeZone").value("Asia/Seoul"));
    }
}
