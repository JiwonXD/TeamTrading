package com.teamtrading.account;

import com.teamtrading.trading.TradingException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 포인트와 보유 수량. 모든 변경은 이 객체의 모니터 안에서 원자적으로 일어난다.
 * 포인트와 주식은 주문 시 묶이고(lock), 체결 시 상대 계정으로 옮겨지며, 취소 시 풀린다.
 * 계정끼리 직접 옮기는 메서드는 없다.
 */
public final class Account {

    private final String id;
    private final String nickname;
    private final long initialPoints;
    private long availablePoints;
    private long lockedPoints;
    private final Map<String, Position> positions = new HashMap<>();

    private static final class Position {
        long available;
        long locked;
        long averagePrice;
    }

    public Account(String id, String nickname, long initialPoints) {
        this.id = id;
        this.nickname = nickname;
        this.initialPoints = initialPoints;
        this.availablePoints = initialPoints;
    }

    public String id() { return id; }
    public String nickname() { return nickname; }
    public long initialPoints() { return initialPoints; }

    public synchronized long availablePoints() { return availablePoints; }
    public synchronized long lockedPoints() { return lockedPoints; }

    public synchronized void lockPoints(long amount) {
        if (availablePoints < amount) throw new TradingException("주문 가능 포인트가 부족해요");
        availablePoints -= amount;
        lockedPoints += amount;
    }

    public synchronized void unlockPoints(long amount) {
        requireCovered(amount, lockedPoints);
        lockedPoints -= amount;
        availablePoints += amount;
    }

    /** 매수 체결: 묶어둔 포인트 중 reserved만큼 쓰고 체결가와의 차액은 돌려받는다 */
    public synchronized void settleBuy(String teamId, long qty, long price, long reservedPerShare) {
        long reserved = reservedPerShare * qty;
        requireCovered(reserved, lockedPoints);
        lockedPoints -= reserved;
        availablePoints += reserved - price * qty;
        Position p = positions.computeIfAbsent(teamId, k -> new Position());
        long total = p.available + p.locked;
        p.averagePrice = Math.round((p.averagePrice * (double) total + price * (double) qty) / (total + qty));
        p.available += qty;
    }

    public synchronized void lockShares(String teamId, long qty) {
        Position p = positions.get(teamId);
        if (p == null || p.available < qty) throw new TradingException("판매 가능 수량이 부족해요");
        p.available -= qty;
        p.locked += qty;
    }

    public synchronized void unlockShares(String teamId, long qty) {
        Position p = positions.get(teamId);
        requireCovered(qty, p == null ? 0 : p.locked);
        p.locked -= qty;
        p.available += qty;
    }

    /** 매도 체결: 묶어둔 주식을 내주고 대금을 받는다 */
    public synchronized void settleSell(String teamId, long qty, long price) {
        Position p = positions.get(teamId);
        requireCovered(qty, p == null ? 0 : p.locked);
        p.locked -= qty;
        availablePoints += price * qty;
    }

    /** 시즌 시작 시 지급하는 초기 보유분. 거래 중에는 쓰지 않는다 */
    public synchronized void grantShares(String teamId, long qty, long price) {
        Position p = positions.computeIfAbsent(teamId, k -> new Position());
        p.available += qty;
        p.averagePrice = price;
    }

    public synchronized List<Holding> holdings() {
        return positions.entrySet().stream()
                .filter(e -> e.getValue().available + e.getValue().locked > 0)
                .map(e -> new Holding(e.getKey(), e.getValue().available, e.getValue().locked, e.getValue().averagePrice))
                .toList();
    }

    public synchronized long totalShares(String teamId) {
        Position p = positions.get(teamId);
        return p == null ? 0 : p.available + p.locked;
    }

    private static void requireCovered(long amount, long balance) {
        if (amount < 0 || amount > balance) throw new IllegalStateException("balance invariant broken");
    }
}
