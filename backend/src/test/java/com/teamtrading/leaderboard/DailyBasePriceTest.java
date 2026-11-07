package com.teamtrading.leaderboard;

import com.teamtrading.TestClock;
import com.teamtrading.account.AccountStore;
import com.teamtrading.engine.OrderType;
import com.teamtrading.engine.Side;
import com.teamtrading.ledger.Reconciliation;
import com.teamtrading.trading.StoredOrder;
import com.teamtrading.trading.TradingException;
import com.teamtrading.trading.TradingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 기준가(전일 종가)는 일 마감 뒤 바뀌고, 새 가격제한폭을 벗어난 대기 주문은 취소된다 */
@SpringBootTest
@Import(TestClock.Config.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class DailyBasePriceTest {

    /** 시작 기준가 18,450 → 제한폭 12,920 ~ 23,950 */
    static final String TEAM = "lg";

    @Autowired TradingService trading;
    @Autowired DailyCloses closes;
    @Autowired AccountStore accounts;
    @Autowired Reconciliation reconciliation;
    @Autowired TestClock clock;

    private String seller;
    private String buyer;

    @BeforeEach
    void setUp() {
        seller = trading.openAccount("seller").id();
        buyer = trading.openAccount("buyer").id();
        trading.grantShares(seller, TEAM, 100, 18_450);
    }

    private long order(String account, Side side, long price, long qty) {
        return trading.place(account, UUID.randomUUID().toString(), TEAM, side, OrderType.LIMIT, price, qty).orderId();
    }

    /** 23:30 KST에 23,900에 주문을 내 VI 단일가 매매로 체결하고, 자정을 넘겨 일 마감한다 */
    private void tradeNearUpperLimitAndCloseTheDay() {
        order(seller, Side.SELL, 23_900, 5);
        order(buyer, Side.BUY, 23_900, 5);
        clock.advance(TradingService.VI_DURATION);
        trading.openDueAuctions();
        clock.advance(Duration.ofMinutes(29));
        closes.scheduled();
    }

    @Test
    void theNextDayUsesTheCloseAsItsBasePriceAndStartsFreshDailyStats() {
        assertThatThrownBy(() -> order(buyer, Side.BUY, 30_000, 1)).isInstanceOf(TradingException.class);
        tradeNearUpperLimitAndCloseTheDay();

        var q = trading.quote(TEAM);
        assertThat(q.basePrice()).isEqualTo(23_900);
        assertThat(q.upperLimit()).isEqualTo(31_050);
        assertThat(q.lowerLimit()).isEqualTo(16_730);
        assertThat(q.changeRate()).isZero();
        assertThat(q.volume()).isZero();
        assertThat(q.high()).isEqualTo(23_900);
        assertThat(q.low()).isEqualTo(23_900);
        // 어제는 상한가 밖이던 가격도 오늘 제한폭 안이면 받는다
        order(buyer, Side.BUY, 30_000, 1);
    }

    @Test
    void restingOrdersOutsideTheNewLimitsAreCanceledAndTheirReservesReturned() {
        long farBid = order(buyer, Side.BUY, 13_000, 10);
        long nearBid = order(buyer, Side.BUY, 20_000, 1);
        long lockedBefore = accounts.find(buyer).orElseThrow().lockedPoints();
        tradeNearUpperLimitAndCloseTheDay();

        var open = trading.openOrders(buyer).stream().map(StoredOrder::id).toList();
        assertThat(open).containsExactly(nearBid).doesNotContain(farBid);
        assertThat(accounts.find(buyer).orElseThrow().lockedPoints()).isEqualTo(lockedBefore - 13_000 * 10);
        assertThat(trading.orderBook(TEAM, 10).bids()).extracting(l -> l.price()).containsExactly(20_000L);
        assertThat(reconciliation.run().balanced()).isTrue();
    }

    @Test
    void aRestartKeepsTheRolledBasePrice() {
        tradeNearUpperLimitAndCloseTheDay();
        trading.recover();
        assertThat(trading.quote(TEAM).basePrice()).isEqualTo(23_900);
        assertThat(trading.quote(TEAM).volume()).isZero();
    }
}
