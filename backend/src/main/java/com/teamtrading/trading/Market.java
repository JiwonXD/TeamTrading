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

    private static final int RECENT_TRADES = 500;

    final Team team;
    final OrderBook book;
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
