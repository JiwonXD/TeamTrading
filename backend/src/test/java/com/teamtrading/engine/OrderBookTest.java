package com.teamtrading.engine;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class OrderBookTest {

    private final AtomicLong ids = new AtomicLong();
    private final OrderBook book = new OrderBook("t");
    private final Instant now = Instant.EPOCH;

    private Order limit(String account, Side side, long price, long qty) {
        return new Order(ids.incrementAndGet(), account, "t", side, OrderType.LIMIT, price, qty, now);
    }

    private MatchResult submit(Order o) {
        return book.submit(o, ids::incrementAndGet, now);
    }

    @Test
    void restsLimitOrderThatDoesNotCross() {
        MatchResult r = submit(limit("a", Side.BUY, 9_900, 10));
        assertThat(r.trades()).isEmpty();
        assertThat(r.resting()).isNotNull();
        assertThat(book.levels(Side.BUY, 5)).containsExactly(new Level(9_900, 10));
    }

    @Test
    void fillsAtRestingPriceWithPriceTimePriority() {
        submit(limit("s1", Side.SELL, 10_100, 5));
        submit(limit("s2", Side.SELL, 10_000, 5));
        submit(limit("s3", Side.SELL, 10_000, 5));

        MatchResult r = submit(limit("b", Side.BUY, 10_100, 12));

        assertThat(r.trades()).extracting(Trade::sellerId, Trade::price, Trade::quantity)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("s2", 10_000L, 5L),
                        org.assertj.core.groups.Tuple.tuple("s3", 10_000L, 5L),
                        org.assertj.core.groups.Tuple.tuple("s1", 10_100L, 2L));
        assertThat(r.resting()).isNull();
        assertThat(book.levels(Side.SELL, 5)).containsExactly(new Level(10_100, 3));
    }

    @Test
    void cancelsUnfilledPartOfMarketOrder() {
        submit(limit("s", Side.SELL, 10_000, 3));
        Order market = new Order(ids.incrementAndGet(), "b", "t", Side.BUY, OrderType.MARKET, 0, 10, now);

        MatchResult r = submit(market);

        assertThat(r.filledQuantity()).isEqualTo(3);
        assertThat(r.canceledQuantity()).isEqualTo(7);
        assertThat(book.bestPrice(Side.BUY)).isEmpty();
    }

    @Test
    void preventsSelfTradeByCancelingIncomingOrder() {
        submit(limit("a", Side.SELL, 10_000, 5));

        MatchResult r = submit(limit("a", Side.BUY, 10_000, 5));

        assertThat(r.trades()).isEmpty();
        assertThat(r.selfTradePrevented()).isTrue();
        assertThat(r.canceledQuantity()).isEqualTo(5);
        assertThat(book.levels(Side.SELL, 5)).containsExactly(new Level(10_000, 5));
        assertThat(book.bestPrice(Side.BUY)).isEmpty();
    }

    @Test
    void stopsAtOwnOrderAfterFillingOthersFirst() {
        submit(limit("x", Side.SELL, 10_000, 2));
        submit(limit("a", Side.SELL, 10_000, 5));

        MatchResult r = submit(limit("a", Side.BUY, 10_000, 5));

        assertThat(r.filledQuantity()).isEqualTo(2);
        assertThat(r.canceledQuantity()).isEqualTo(3);
        assertThat(r.selfTradePrevented()).isTrue();
    }

    @Test
    void cancelRemovesRestingOrderAndAggregatesLevels() {
        Order mine = limit("a", Side.BUY, 9_900, 10);
        submit(mine);
        submit(limit("c", Side.BUY, 9_900, 10));
        assertThat(book.levels(Side.BUY, 5)).containsExactly(new Level(9_900, 20));

        assertThat(book.cancel(mine.id())).contains(mine);
        assertThat(book.levels(Side.BUY, 5)).containsExactly(new Level(9_900, 10));
        assertThat(book.cancel(mine.id())).isEmpty();
    }
}
