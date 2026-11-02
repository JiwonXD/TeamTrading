package com.teamtrading.trading;

import com.teamtrading.engine.OrderBook;
import com.teamtrading.engine.PriceRules;
import com.teamtrading.engine.Side;
import com.teamtrading.engine.Trade;
import com.teamtrading.team.Team;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/** 종목 하나의 호가창과 시세. 모든 접근은 lock을 잡은 상태에서 한다 */
final class Market {

    static final int RECENT_TRADES = 500;
    static final int FEED_DEPTH = 10;

    final Team team;
    OrderBook book;
    /** 정산 실패를 복구하지 못하면 DB와 호가창이 어긋났을 수 있어 그 종목 거래를 멈춘다 */
    boolean halted;
    /** 첫 상장 전이면 거래를 시작할 시각. 공모 배정 전이면 Instant.MAX, 거래 중이면 null */
    volatile Instant listingAt;
    final ReentrantLock lock = new ReentrantLock(true);
    private final Deque<Trade> recent = new ArrayDeque<>();
    /** 기준가(전일 종가). 매일 일 마감 뒤 바뀐다 */
    private long basePrice;
    private long lastPrice;
    /** 당일(KST) 고가·저가·거래량 */
    private long high;
    private long low;
    private long volume;
    /** 이 종목에서 내보낸 마지막 업데이트 번호 */
    private long seq;

    Market(Team team) {
        this.team = team;
        this.book = new OrderBook(team.id());
        this.basePrice = team.basePrice();
        this.lastPrice = basePrice;
        this.high = lastPrice;
        this.low = lastPrice;
    }

    /** DB에서 읽은 체결 내역으로 현재가를 다시 정한다. recent는 최신이 앞 */
    void restoreTrades(List<Trade> recentTrades) {
        recent.clear();
        recent.addAll(recentTrades);
        if (!recentTrades.isEmpty()) lastPrice = recentTrades.getFirst().price();
    }

    /** 기준가와 당일 시세를 정한다. 오늘 체결이 없으면 고가·저가는 현재가 */
    void startDay(long basePrice, TradeStore.Stats today) {
        this.basePrice = basePrice;
        if (today.volume() == 0) {
            high = lastPrice;
            low = lastPrice;
            volume = 0;
        } else {
            high = today.high();
            low = today.low();
            volume = today.volume();
        }
    }

    long basePrice() {
        return basePrice;
    }

    void resetBook() {
        book = new OrderBook(team.id());
    }

    void record(Trade trade) {
        recent.addFirst(trade);
        if (recent.size() > RECENT_TRADES) recent.removeLast();
        lastPrice = trade.price();
        high = Math.max(high, trade.price());
        low = Math.min(low, trade.price());
        volume += trade.quantity();
    }

    long lastPrice() {
        return lastPrice;
    }

    long seq() {
        return seq;
    }

    List<Trade> recentTrades(int limit) {
        return new ArrayList<>(recent).subList(0, Math.min(limit, recent.size()));
    }

    /** 호가창이나 시세가 바뀌었을 때 번호를 하나 올려 업데이트를 만든다 */
    MarketUpdate nextUpdate(long epoch, List<Trade> newTrades) {
        seq++;
        return snapshot(epoch, newTrades);
    }

    MarketUpdate snapshot(long epoch, List<Trade> trades) {
        return new MarketUpdate(team.id(), epoch, seq, quote(), book.levels(Side.SELL, FEED_DEPTH),
                book.levels(Side.BUY, FEED_DEPTH), trades);
    }

    Quote quote() {
        // 동시호가 중이면 지금 단일가 매매를 할 때의 가격과 수량을 함께 내보낸다(상장 체결과 같은 계산)
        var indicative = book.isCollecting() ? book.indicative(basePrice) : Optional.<OrderBook.Indicative>empty();
        return new Quote(team, basePrice, lastPrice, high, low, volume,
                PriceRules.upperLimit(basePrice), PriceRules.lowerLimit(basePrice), listingAt == null,
                indicative.map(OrderBook.Indicative::price).orElse(null),
                indicative.map(OrderBook.Indicative::volume).orElse(0L));
    }
}
