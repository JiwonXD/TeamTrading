package com.teamtrading.ledger;

import com.teamtrading.engine.Trade;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 전표 한 장. 포인트·주식이 한 번 움직일 때 어느 칸에서 빠져 어느 칸으로 갔는지 남긴다.
 * 자산마다 분개 합이 0이어야 하므로 포인트나 주식이 어디선가 생기거나 사라지는 기록은 만들 수 없다.
 * 처음 생겨나는 곳은 발행처(SYSTEM)의 ISSUED 칸으로 둔다.
 */
public record Journal(Kind kind, Long orderId, Long tradeId, List<Entry> entries) {

    public static final String POINT = "POINT";
    public static final String SYSTEM = "SYSTEM";

    public enum Kind { OPEN_ACCOUNT, GRANT, LOCK, RELEASE, TRADE }

    public enum Bucket { AVAILABLE, LOCKED, ISSUED }

    public record Entry(String ownerId, String asset, Bucket bucket, long amount) {
    }

    public Journal {
        entries = entries.stream().filter(e -> e.amount() != 0).toList();
        Map<String, Long> sums = new HashMap<>();
        for (Entry e : entries) sums.merge(e.asset(), e.amount(), Long::sum);
        sums.forEach((asset, sum) -> {
            if (sum != 0) throw new IllegalArgumentException("unbalanced journal: " + asset + " " + sum);
        });
    }

    public static Journal openAccount(String accountId, long points) {
        return new Journal(Kind.OPEN_ACCOUNT, null, null, List.of(
                new Entry(SYSTEM, POINT, Bucket.ISSUED, -points),
                new Entry(accountId, POINT, Bucket.AVAILABLE, points)));
    }

    public static Journal grant(String accountId, String teamId, long qty) {
        return new Journal(Kind.GRANT, null, null, List.of(
                new Entry(SYSTEM, teamId, Bucket.ISSUED, -qty),
                new Entry(accountId, teamId, Bucket.AVAILABLE, qty)));
    }

    /** 주문에 묶는다. 매수면 asset이 POINT, 매도면 구단 id */
    public static Journal lock(long orderId, String accountId, String asset, long amount) {
        return move(Kind.LOCK, orderId, accountId, asset, Bucket.AVAILABLE, Bucket.LOCKED, amount);
    }

    /** 체결되지 않은 몫을 풀어준다 */
    public static Journal release(long orderId, String accountId, String asset, long amount) {
        return move(Kind.RELEASE, orderId, accountId, asset, Bucket.LOCKED, Bucket.AVAILABLE, amount);
    }

    /** 매수자는 묶어둔 예치금에서 대금을 내고 남는 차액을 돌려받는다. 매도자는 묶어둔 주식을 넘긴다 */
    public static Journal trade(Trade t, long buyerReservePerShare) {
        long q = t.quantity();
        long cost = t.price() * q;
        return new Journal(Kind.TRADE, null, t.id(), List.of(
                new Entry(t.buyerId(), POINT, Bucket.LOCKED, -buyerReservePerShare * q),
                new Entry(t.buyerId(), POINT, Bucket.AVAILABLE, buyerReservePerShare * q - cost),
                new Entry(t.sellerId(), POINT, Bucket.AVAILABLE, cost),
                new Entry(t.sellerId(), t.teamId(), Bucket.LOCKED, -q),
                new Entry(t.buyerId(), t.teamId(), Bucket.AVAILABLE, q)));
    }

    private static Journal move(Kind kind, long orderId, String accountId, String asset, Bucket from, Bucket to,
                                long amount) {
        return new Journal(kind, orderId, null, List.of(
                new Entry(accountId, asset, from, -amount),
                new Entry(accountId, asset, to, amount)));
    }
}
