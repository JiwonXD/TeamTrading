package com.fanchart.trading;

import com.fanchart.TestClock;
import com.fanchart.account.AccountStore;
import com.fanchart.engine.Level;
import com.fanchart.engine.OrderType;
import com.fanchart.engine.Side;
import com.fanchart.ledger.Reconciliation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** 안 팔린 운영자 매도는 다음날 할인해서 다시 건다(ADR 0038) */
@SpringBootTest(properties = "fanchart.auth.admins=operator-1")
@Import(TestClock.Config.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class IssueMarkdownTest {

    static final String OPERATOR = "operator-1";
    /** 2026-11-03 00:30:05 KST. TestClock은 2026-11-02 23:30 KST에서 시작한다 */
    static final Instant NEXT_DAY = Instant.parse("2026-11-02T15:30:05Z");

    @Autowired TradingService trading;
    @Autowired IssueMarkdown markdown;
    @Autowired AccountStore accounts;
    @Autowired Reconciliation reconciliation;
    @Autowired TestClock clock;

    private String buyer;

    @BeforeEach
    void setUp() {
        accounts.create(OPERATOR, "운영자", clock.instant());
        buyer = trading.openAccount("buyer").id();
    }

    private void sell(String clientOrderId, String team, long price, long qty) {
        trading.place(OPERATOR, clientOrderId, team, Side.SELL, OrderType.LIMIT, price, qty);
    }

    private long operatorShares(String team) {
        return trading.holdings(OPERATOR).stream().filter(h -> h.teamId().equals(team))
                .mapToLong(h -> h.available() + h.locked()).sum();
    }

    @Test
    void unsoldLadderMovesDownFivePercentTheNextDay() {
        sell("ladder-1", "lg", 18_450, 50);
        sell("ladder-2", "lg", 18_500, 30);
        clock.set(NEXT_DAY);

        assertThat(markdown.run()).isEqualTo(2);

        // 18,450 × 0.95 = 17,527.5 → 호가 단위(10) 내림 17,520. 18,500 × 0.95 = 17,575 → 17,570
        assertThat(trading.orderBook("lg", 5).asks()).extracting(Level::price, Level::quantity)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(17_520L, 50L),
                        org.assertj.core.groups.Tuple.tuple(17_570L, 30L));
        // 새로 발행하지 않는다
        assertThat(operatorShares("lg")).isEqualTo(80);
        assertThat(reconciliation.run().balanced()).isTrue();
        // 같은 날 다시 돌려도 한 번만 내린다
        assertThat(markdown.run()).isZero();
        assertThat(trading.orderBook("lg", 5).asks().getFirst().price()).isEqualTo(17_520);
    }

    @Test
    void remainingOrdersAreMarkedDownEvenWhereSomeSold() {
        sell("ladder-1", "lg", 18_450, 50);
        sell("ladder-2", "lg", 18_500, 30);
        sell("ladder-3", "kia", 21_300, 50);
        trading.place(buyer, "b-1", "lg", Side.BUY, OrderType.LIMIT, 18_450, 1);
        clock.set(NEXT_DAY);

        assertThat(markdown.run()).isEqualTo(3);

        assertThat(trading.orderBook("lg", 5).asks()).extracting(Level::price, Level::quantity)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(17_520L, 49L),
                        org.assertj.core.groups.Tuple.tuple(17_570L, 30L));
        // 21,300 × 0.95 = 20,235 → 호가 단위(50) 내림 20,200
        assertThat(trading.orderBook("kia", 5).asks()).extracting(Level::price).containsExactly(20_200L);
        assertThat(operatorShares("lg")).isEqualTo(79);
        assertThat(reconciliation.run().balanced()).isTrue();
    }

    @Test
    void neverGoesBelowTheLowerLimitOrTheFloor() {
        long lower = trading.quote("lg").lowerLimit();
        sell("ladder-1", "lg", lower + 100, 10);
        sell("ladder-2", "lg", lower, 10);
        clock.set(NEXT_DAY);

        assertThat(markdown.run()).isEqualTo(1);

        assertThat(trading.orderBook("lg", 5).asks()).extracting(Level::price, Level::quantity)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(lower, 20L));
        assertThat(markdown.discounted(5_100, 3_000)).isEqualTo(5_000);
        assertThat(markdown.discounted(5_000, 3_000)).isEqualTo(5_000);
    }

    @Test
    void markdownThatCrossesABidSellsAndKeepsTheSupply() {
        sell("ladder-1", "lg", 18_450, 50);
        clock.set(NEXT_DAY);
        trading.place(buyer, "b-1", "lg", Side.BUY, OrderType.LIMIT, 18_000, 20);

        assertThat(markdown.run()).isEqualTo(1);

        assertThat(trading.holdings(buyer)).anySatisfy(h -> {
            assertThat(h.teamId()).isEqualTo("lg");
            assertThat(h.available()).isEqualTo(20);
        });
        assertThat(trading.orderBook("lg", 5).asks()).extracting(Level::price, Level::quantity)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(17_520L, 30L));
        assertThat(operatorShares("lg")).isEqualTo(30);
        assertThat(reconciliation.run().balanced()).isTrue();
    }
}
