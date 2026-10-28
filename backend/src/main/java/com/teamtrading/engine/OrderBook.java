package com.teamtrading.engine;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.LongSupplier;

/**
 * 한 종목의 호가창. 가격-시간 우선 원칙으로 연속 매매한다.
 * 스레드 안전하지 않으며, 호출하는 쪽에서 종목 단위로 직렬화해야 한다.
 */
public final class OrderBook {

    private final String teamId;
    private final NavigableMap<Long, Deque<Order>> bids = new TreeMap<>(Collections.reverseOrder());
    private final NavigableMap<Long, Deque<Order>> asks = new TreeMap<>();
    private final Map<Long, Order> byId = new HashMap<>();

    public OrderBook(String teamId) {
        this.teamId = teamId;
    }

    public MatchResult submit(Order order, LongSupplier tradeIds, Instant now) {
        if (!order.teamId().equals(teamId)) throw new IllegalArgumentException("wrong book");
        NavigableMap<Long, Deque<Order>> opposite = order.side() == Side.BUY ? asks : bids;
        List<Trade> trades = new ArrayList<>();
        boolean selfTradePrevented = false;

        matching:
        while (order.remaining() > 0 && !opposite.isEmpty()) {
            Map.Entry<Long, Deque<Order>> best = opposite.firstEntry();
            if (!crosses(order, best.getKey())) break;
            Iterator<Order> queue = best.getValue().iterator();
            while (queue.hasNext() && order.remaining() > 0) {
                Order resting = queue.next();
                // 자기매매 방지(Cancel Newest): 내 주문끼리 체결될 상황이면 새 주문의 잔량을 취소한다
                if (resting.accountId().equals(order.accountId())) {
                    selfTradePrevented = true;
                    break matching;
                }
                long qty = Math.min(order.remaining(), resting.remaining());
                order.fill(qty);
                resting.fill(qty);
                trades.add(trade(order, resting, qty, tradeIds.getAsLong(), now));
                if (resting.isFilled()) {
                    queue.remove();
                    byId.remove(resting.id());
                }
            }
            if (best.getValue().isEmpty()) opposite.remove(best.getKey());
        }

        Order rested = null;
        long canceled = 0;
        if (order.remaining() > 0) {
            if (order.type() == OrderType.LIMIT && !selfTradePrevented) {
                book(order.side()).computeIfAbsent(order.price(), p -> new ArrayDeque<>()).addLast(order);
                byId.put(order.id(), order);
                rested = order;
            } else {
                canceled = order.remaining();
                order.cancelRemaining();
            }
        }
        return new MatchResult(List.copyOf(trades), rested, canceled, selfTradePrevented);
    }

    public Optional<Order> cancel(long orderId) {
        Order order = byId.remove(orderId);
        if (order == null) return Optional.empty();
        NavigableMap<Long, Deque<Order>> side = book(order.side());
        Deque<Order> queue = side.get(order.price());
        queue.remove(order);
        if (queue.isEmpty()) side.remove(order.price());
        return Optional.of(order);
    }

    public Optional<Order> find(long orderId) {
        return Optional.ofNullable(byId.get(orderId));
    }

    public List<Order> openOrders() {
        return List.copyOf(byId.values());
    }

    public List<Level> levels(Side side, int depth) {
        List<Level> out = new ArrayList<>();
        for (Map.Entry<Long, Deque<Order>> e : book(side).entrySet()) {
            if (out.size() == depth) break;
            out.add(new Level(e.getKey(), e.getValue().stream().mapToLong(Order::remaining).sum()));
        }
        return out;
    }

    public Optional<Long> bestPrice(Side side) {
        NavigableMap<Long, Deque<Order>> book = book(side);
        return book.isEmpty() ? Optional.empty() : Optional.of(book.firstKey());
    }

    private NavigableMap<Long, Deque<Order>> book(Side side) {
        return side == Side.BUY ? bids : asks;
    }

    private static boolean crosses(Order incoming, long restingPrice) {
        if (incoming.type() == OrderType.MARKET) return true;
        return incoming.side() == Side.BUY ? restingPrice <= incoming.price() : restingPrice >= incoming.price();
    }

    private static Trade trade(Order incoming, Order resting, long qty, long id, Instant now) {
        Order buy = incoming.side() == Side.BUY ? incoming : resting;
        Order sell = incoming.side() == Side.SELL ? incoming : resting;
        return new Trade(id, incoming.teamId(), resting.price(), qty, buy.accountId(), sell.accountId(),
                buy.id(), sell.id(), incoming.side(), now);
    }
}
