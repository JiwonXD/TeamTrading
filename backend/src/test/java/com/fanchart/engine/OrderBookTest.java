package com.fanchart.engine;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

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
                        tuple("s2", 10_000L, 5L),
                        tuple("s3", 10_000L, 5L),
                        tuple("s1", 10_100L, 2L));
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

    @Test
    void restoreRebuildsRestingOrdersAndRejectsCrossingOnes() {
        book.restore(Order.restored(1, "a", "t", Side.BUY, 9_900, 10, 4, now));
        book.restore(Order.restored(2, "b", "t", Side.SELL, 10_000, 5, 5, now));
        assertThat(book.levels(Side.BUY, 5)).containsExactly(new Level(9_900, 4));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> book.restore(Order.restored(3, "c", "t", Side.BUY, 10_000, 1, 1, now)))
                .isInstanceOf(IllegalStateException.class);
    }

    // ---------- 동시호가 ----------

    @Test
    void collectingRestsCrossingOrdersWithoutTrading() {
        book.startCollecting();
        submit(limit("s", Side.SELL, 10_000, 5));
        MatchResult r = submit(limit("b", Side.BUY, 10_500, 5));
        assertThat(r.trades()).isEmpty();
        assertThat(book.bestPrice(Side.BUY)).contains(10_500L);
        assertThat(book.bestPrice(Side.SELL)).contains(10_000L);
    }

    @Test
    void uncrossPicksThePriceThatTradesTheMostAndFillsBetterPricesFirst() {
        book.startCollecting();
        submit(limit("s1", Side.SELL, 9_800, 3));
        submit(limit("s2", Side.SELL, 10_000, 4));
        submit(limit("s3", Side.SELL, 10_200, 10));
        submit(limit("b1", Side.BUY, 10_200, 2));
        submit(limit("b2", Side.BUY, 10_000, 5));
        submit(limit("b3", Side.BUY, 9_800, 6));
        // 9,800: 매수 13 / 매도 3 → 3주, 10,000: 매수 7 / 매도 7 → 7주, 10,200: 매수 2 / 매도 17 → 2주

        // 예상 체결가는 실제 단일가 체결과 같은 가격·수량이고, 호가창은 그대로다
        assertThat(book.indicative(9_900)).contains(new OrderBook.Indicative(10_000, 7));
        assertThat(book.isCollecting()).isTrue();
        assertThat(book.levels(Side.SELL, 5)).hasSize(3);

        OrderBook.AuctionResult r = book.uncross(ids::incrementAndGet, now, 9_900);

        assertThat(r.price()).isEqualTo(10_000);
        assertThat(r.trades()).allSatisfy(t -> assertThat(t.price()).isEqualTo(10_000));
        assertThat(r.trades().stream().mapToLong(Trade::quantity).sum()).isEqualTo(7);
        assertThat(r.trades().getFirst().buyerId()).isEqualTo("b1");
        assertThat(r.trades().getFirst().sellerId()).isEqualTo("s1");
        assertThat(book.isCollecting()).isFalse();
        assertThat(book.levels(Side.BUY, 5)).containsExactly(new Level(9_800, 6));
        assertThat(book.levels(Side.SELL, 5)).containsExactly(new Level(10_200, 10));
    }

    @Test
    void tiesPreferSmallerImbalanceThenTheReferencePrice() {
        book.startCollecting();
        submit(limit("s", Side.SELL, 10_000, 5));
        submit(limit("b", Side.BUY, 10_400, 5));
        // 10,000과 10,400 모두 5주 체결, 남는 수량 0 → 기준가 10,300에 가까운 10,400
        assertThat(book.uncross(ids::incrementAndGet, now, 10_300).price()).isEqualTo(10_400);

        OrderBook other = new OrderBook("t");
        other.startCollecting();
        other.submit(limit("s", Side.SELL, 10_000, 5), ids::incrementAndGet, now);
        other.submit(limit("s2", Side.SELL, 10_400, 5), ids::incrementAndGet, now);
        other.submit(limit("b", Side.BUY, 10_400, 5), ids::incrementAndGet, now);
        // 10,000: 5주(남는 0), 10,400: 5주(남는 5) → 남는 수량이 적은 10,000
        assertThat(other.uncross(ids::incrementAndGet, now, 10_400).price()).isEqualTo(10_000);
    }

    @Test
    void noIndicativePriceWhenNothingCrosses() {
        book.startCollecting();
        submit(limit("s", Side.SELL, 10_200, 5));
        submit(limit("b", Side.BUY, 10_000, 5));
        assertThat(book.indicative(10_000)).isEmpty();
    }

    @Test
    void earlierOrderAtTheSamePriceFillsFirst() {
        book.startCollecting();
        submit(limit("b1", Side.BUY, 10_000, 3));
        submit(limit("b2", Side.BUY, 10_000, 3));
        submit(limit("s", Side.SELL, 10_000, 4));

        OrderBook.AuctionResult r = book.uncross(ids::incrementAndGet, now, 10_000);

        assertThat(r.trades()).extracting(Trade::buyerId, Trade::quantity).containsExactly(
                tuple("b1", 3L), tuple("b2", 1L));
        assertThat(book.levels(Side.BUY, 5)).containsExactly(new Level(10_000, 2));
    }

    @Test
    void noCrossMeansNoTradeAndContinuousTradingResumes() {
        book.startCollecting();
        submit(limit("s", Side.SELL, 10_100, 5));
        submit(limit("b", Side.BUY, 10_000, 5));

        OrderBook.AuctionResult r = book.uncross(ids::incrementAndGet, now, 10_050);

        assertThat(r.trades()).isEmpty();
        assertThat(r.price()).isEqualTo(10_050);
        assertThat(submit(limit("b2", Side.BUY, 10_100, 2)).trades()).hasSize(1);
    }

    @Test
    void stopsAtTheVolatilityBandAndSwitchesToAnAuctionWithTheLimitRemainderResting() {
        submit(limit("s1", Side.SELL, 10_000, 3));
        submit(limit("s2", Side.SELL, 10_700, 5));

        MatchResult r = book.submit(limit("b", Side.BUY, 10_700, 6), ids::incrementAndGet, now, 9_410, 10_590);

        // 범위 안 10,000은 체결하고, 범위 밖 10,700에서 멈춘다
        assertThat(r.trades()).extracting(Trade::price, Trade::quantity).containsExactly(tuple(10_000L, 3L));
        assertThat(r.interrupted()).isTrue();
        assertThat(r.resting().remaining()).isEqualTo(3);
        assertThat(book.isCollecting()).isTrue();
        // 교차한 채 단일가 매매를 기다린다
        assertThat(book.bestPrice(Side.BUY)).contains(10_700L);
        assertThat(book.bestPrice(Side.SELL)).contains(10_700L);

        OrderBook.AuctionResult auction = book.uncross(ids::incrementAndGet, now, 10_000);
        assertThat(auction.trades()).extracting(Trade::price, Trade::quantity).containsExactly(tuple(10_700L, 3L));
        assertThat(book.isCollecting()).isFalse();
    }

    @Test
    void aMarketOrderStoppedByTheBandCancelsItsRemainderButStillStartsTheAuction() {
        submit(limit("s1", Side.SELL, 10_000, 1));
        submit(limit("s2", Side.SELL, 11_000, 5));

        MatchResult r = book.submit(new Order(ids.incrementAndGet(), "b", "t", Side.BUY, OrderType.MARKET, 0, 4, now),
                ids::incrementAndGet, now, 9_410, 10_590);

        assertThat(r.filledQuantity()).isEqualTo(1);
        assertThat(r.canceledQuantity()).isEqualTo(3);
        assertThat(r.resting()).isNull();
        assertThat(r.interrupted()).isTrue();
        assertThat(book.isCollecting()).isTrue();
    }

    @Test
    void tradesInsideTheBandDoNotInterrupt() {
        submit(limit("s1", Side.SELL, 10_590, 2));
        MatchResult r = book.submit(limit("b", Side.BUY, 10_590, 2), ids::incrementAndGet, now, 9_410, 10_590);
        assertThat(r.filledQuantity()).isEqualTo(2);
        assertThat(r.interrupted()).isFalse();
        assertThat(book.isCollecting()).isFalse();
    }

    @Test
    void anInterruptedRemainderThatWouldCrossMyOwnOrderIsCanceledSoTheAuctionHasNoSelfTrade() {
        submit(limit("s1", Side.SELL, 10_000, 1));
        submit(limit("me", Side.SELL, 10_700, 2));

        MatchResult r = book.submit(limit("me", Side.BUY, 10_700, 3), ids::incrementAndGet, now, 9_410, 10_590);

        assertThat(r.filledQuantity()).isEqualTo(1);
        assertThat(r.interrupted()).isTrue();
        assertThat(r.selfTradePrevented()).isTrue();
        assertThat(r.resting()).isNull();
        assertThat(r.canceledQuantity()).isEqualTo(2);
        assertThat(book.uncross(ids::incrementAndGet, now, 10_000).trades()).isEmpty();
    }
}
