package com.teamtrading.engine;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.LongSupplier;

/**
 * 한 종목의 호가창. 가격-시간 우선 원칙으로 연속 매매한다.
 * 상장 전 동시호가 동안(collecting)에는 지정가 주문을 체결하지 않고 모으기만 하고, {@link #uncross}로 한 가격에 체결한다.
 * 스레드 안전하지 않으며, 호출하는 쪽에서 종목 단위로 직렬화해야 한다.
 */
public final class OrderBook {

    private final String teamId;
    private final NavigableMap<Long, Deque<Order>> bids = new TreeMap<>(Collections.reverseOrder());
    private final NavigableMap<Long, Deque<Order>> asks = new TreeMap<>();
    private final Map<Long, Order> byId = new HashMap<>();
    private boolean collecting;

    /**
     * 단일가 매매 결과.
     *
     * @param price   체결가. 체결이 없으면 기준가
     * @param trades  체결 (매수 가격 우선, 같은 가격이면 접수 순)
     * @param touched 체결에 참여한 주문. 체결 뒤 잔량이 들어 있다
     */
    /** 예상 체결가와 그 가격에 체결될 수량 */
    public record Indicative(long price, long volume) {
    }

    public record AuctionResult(long price, List<Trade> trades, List<Order> touched) {
    }

    public OrderBook(String teamId) {
        this.teamId = teamId;
    }

    /** 동시호가를 시작한다. 이후 들어오는 지정가 주문은 체결하지 않고 쌓는다 */
    public void startCollecting() {
        collecting = true;
    }

    public boolean isCollecting() {
        return collecting;
    }

    public MatchResult submit(Order order, LongSupplier tradeIds, Instant now) {
        if (!order.teamId().equals(teamId)) throw new IllegalArgumentException("wrong book");
        if (collecting) {
            if (order.type() != OrderType.LIMIT) throw new IllegalArgumentException("auction accepts limit orders only");
            rest(order);
            return new MatchResult(List.of(), order, 0, false);
        }
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
                rest(order);
                rested = order;
            } else {
                canceled = order.remaining();
                order.cancelRemaining();
            }
        }
        return new MatchResult(List.copyOf(trades), rested, canceled, selfTradePrevented);
    }

    /** 재시작 복구용: 매칭 없이 대기 주문을 접수 순서대로 다시 쌓는다. 동시호가 중이 아닌데 호가가 교차하면 거부한다 */
    public void restore(Order order) {
        if (order.type() != OrderType.LIMIT || order.remaining() == 0) throw new IllegalArgumentException("not resting");
        Optional<Long> opposite = bestPrice(order.side().opposite());
        if (!collecting && opposite.isPresent() && crosses(order, opposite.get()))
            throw new IllegalStateException("restored order crosses the book: " + order.id());
        rest(order);
    }

    /** 이 계정이 반대쪽에 대기 주문을 갖고 있는지. 동시호가에서는 자기매매를 막으려고 접수 단계에서 거절한다 */
    public boolean hasOrderFrom(String accountId, Side side) {
        return book(side).values().stream().flatMap(Deque::stream).anyMatch(o -> o.accountId().equals(accountId));
    }

    /**
     * 동시호가를 끝내고 모인 주문을 한 가격에 체결한 뒤 연속 매매로 넘어간다.
     * <ol>
     * <li>체결 가능 수량 min(그 가격 이상 매수 합, 그 가격 이하 매도 합)이 가장 큰 가격</li>
     * <li>같으면 남는 수량 |매수 합 - 매도 합|이 가장 작은 가격</li>
     * <li>같으면 기준가에 가장 가까운 가격, 그래도 같으면 낮은 가격</li>
     * </ol>
     * 체결은 가격 우선, 같은 가격이면 접수 순으로 짝짓는다. 체결가가 정해지면 그보다 좋은 가격의 주문은 모두 체결되므로
     * 끝난 뒤 호가가 교차하지 않는다.
     */
    /** 지금 단일가 매매를 하면 체결될 가격과 수량(예상 체결가). 체결될 주문이 없으면 빈 값. 호가창은 바꾸지 않는다 */
    public Optional<Indicative> indicative(long referencePrice) {
        long bestPrice = referencePrice;
        long bestVolume = 0;
        long bestImbalance = Long.MAX_VALUE;
        TreeSet<Long> prices = new TreeSet<>(bids.keySet());
        prices.addAll(asks.keySet());
        for (long p : prices) {
            long buy = volume(bids.headMap(p, true));
            long sell = volume(asks.headMap(p, true));
            long exec = Math.min(buy, sell);
            long imbalance = Math.abs(buy - sell);
            boolean better = exec > bestVolume
                    || (exec == bestVolume && exec > 0 && (imbalance < bestImbalance
                    || (imbalance == bestImbalance && Math.abs(p - referencePrice) < Math.abs(bestPrice - referencePrice))));
            if (better) {
                bestPrice = p;
                bestVolume = exec;
                bestImbalance = imbalance;
            }
        }
        return bestVolume == 0 ? Optional.empty() : Optional.of(new Indicative(bestPrice, bestVolume));
    }

    public AuctionResult uncross(LongSupplier tradeIds, Instant now, long referencePrice) {
        collecting = false;
        Optional<Indicative> found = indicative(referencePrice);
        if (found.isEmpty()) return new AuctionResult(referencePrice, List.of(), List.of());

        long price = found.get().price();
        List<Trade> trades = new ArrayList<>();
        Map<Long, Order> touched = new LinkedHashMap<>();
        while (!bids.isEmpty() && !asks.isEmpty() && bids.firstKey() >= price && asks.firstKey() <= price) {
            Order buy = bids.firstEntry().getValue().peekFirst();
            Order sell = asks.firstEntry().getValue().peekFirst();
            if (buy.accountId().equals(sell.accountId()))
                throw new IllegalStateException("self trade in auction: " + buy.accountId());
            long qty = Math.min(buy.remaining(), sell.remaining());
            buy.fill(qty);
            sell.fill(qty);
            Side aggressor = buy.id() > sell.id() ? Side.BUY : Side.SELL;
            trades.add(new Trade(tradeIds.getAsLong(), teamId, price, qty, buy.accountId(), sell.accountId(), buy.id(),
                    sell.id(), aggressor, now));
            touched.put(buy.id(), buy);
            touched.put(sell.id(), sell);
            if (buy.isFilled()) cancel(buy.id());
            if (sell.isFilled()) cancel(sell.id());
        }
        Optional<Long> bid = bestPrice(Side.BUY);
        Optional<Long> ask = bestPrice(Side.SELL);
        if (bid.isPresent() && ask.isPresent() && bid.get() >= ask.get())
            throw new IllegalStateException("book still crossed after auction");
        return new AuctionResult(price, List.copyOf(trades), List.copyOf(touched.values()));
    }

    private static long volume(Map<Long, Deque<Order>> levels) {
        long sum = 0;
        for (Deque<Order> q : levels.values()) for (Order o : q) sum += o.remaining();
        return sum;
    }

    private void rest(Order order) {
        book(order.side()).computeIfAbsent(order.price(), p -> new ArrayDeque<>()).addLast(order);
        byId.put(order.id(), order);
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
