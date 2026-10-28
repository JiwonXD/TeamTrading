package com.teamtrading.trading;

import com.teamtrading.engine.OrderBook;
import com.teamtrading.engine.PriceRules;
import com.teamtrading.engine.Trade;
import com.teamtrading.team.Team;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/** 종목 하나의 호가창과 시세. 모든 접근은 lock을 잡은 상태에서 한다 */
final class Market {

    static final int RECENT_TRADES = 500;

    final Team team;
    OrderBook book;
    /** 정산 실패를 복구하지 못하면 DB와 호가창이 어긋났을 수 있어 그 종목 거래를 멈춘다 */
    boolean halted;
    final ReentrantLock lock = new ReentrantLock(true);
    private final Deque<Trade> recent = new ArrayDeque<>();
    private long lastPrice;
    private long high;
    private long low;
    private long volume;

    Market(Team team) {
        this.team = team;
        this.book = new OrderBook(team.id());
        this.lastPrice = team.basePrice();
        this.high = lastPrice;
        this.low = lastPrice;
    }

    /** DB에서 읽은 체결 내역으로 시세를 다시 계산한다. recent는 최신이 앞 */
    void restoreQuote(List<Trade> recentTrades, long high, long low, long volume) {
        recent.clear();
        recent.addAll(recentTrades);
        if (recentTrades.isEmpty()) return;
        lastPrice = recentTrades.getFirst().price();
        this.high = high;
        this.low = low;
        this.volume = volume;
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

    List<Trade> recentTrades(int limit) {
        return new ArrayList<>(recent).subList(0, Math.min(limit, recent.size()));
    }

    Quote quote() {
        return new Quote(team, lastPrice, high, low, volume,
                PriceRules.upperLimit(team.basePrice()), PriceRules.lowerLimit(team.basePrice()));
    }
}
