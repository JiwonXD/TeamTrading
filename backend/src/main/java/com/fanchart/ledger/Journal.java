package com.fanchart.ledger;

import com.fanchart.engine.Trade;

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

    /**
     * OPEN_ACCOUNT는 발행 기록 도입 전의 가입 지급. 이제 포인트 발행은 모두 ISSUE다.
     * SUBSCRIBE·UNSUBSCRIBE는 공모 청약금을 묶고 푸는 것, ALLOT은 공모 배정이다
     */
    public enum Kind { OPEN_ACCOUNT, ISSUE, GRANT, LOCK, RELEASE, TRADE, SUBSCRIBE, UNSUBSCRIBE, ALLOT }

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

    /** 포인트 발행. 경로와 멱등키는 point_grants에 남긴다 */
    public static Journal issue(String accountId, long points) {
        return new Journal(Kind.ISSUE, null, null, List.of(
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

    /** 공모 청약금을 묶는다 */
    public static Journal subscribe(String accountId, long deposit) {
        return new Journal(Kind.SUBSCRIBE, null, null, List.of(
                new Entry(accountId, POINT, Bucket.AVAILABLE, -deposit),
                new Entry(accountId, POINT, Bucket.LOCKED, deposit)));
    }

    /** 청약을 취소해 청약금을 풀어준다 */
    public static Journal unsubscribe(String accountId, long deposit) {
        return new Journal(Kind.UNSUBSCRIBE, null, null, List.of(
                new Entry(accountId, POINT, Bucket.LOCKED, -deposit),
                new Entry(accountId, POINT, Bucket.AVAILABLE, deposit)));
    }

    /**
     * 공모 배정. 청약금에서 배정분 대금을 발행처로 돌려 소각하고 나머지는 돌려준다. 배정 주식은 발행처에서 새로 나온다.
     * 배정이 0주여도 청약금을 돌려주는 전표로 남긴다
     */
    public static Journal allot(String accountId, String teamId, long deposit, long qty, long price) {
        long paid = qty * price;
        return new Journal(Kind.ALLOT, null, null, List.of(
                new Entry(accountId, POINT, Bucket.LOCKED, -deposit),
                new Entry(accountId, POINT, Bucket.AVAILABLE, deposit - paid),
                new Entry(SYSTEM, POINT, Bucket.ISSUED, paid),
                new Entry(SYSTEM, teamId, Bucket.ISSUED, -qty),
                new Entry(accountId, teamId, Bucket.AVAILABLE, qty)));
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
