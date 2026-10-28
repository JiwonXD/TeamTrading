package com.teamtrading.engine;

import java.time.Instant;

/** 호가창에 들어가는 주문. 체결되며 remaining이 줄어든다. 시장가 주문의 price는 0이다. */
public final class Order {

    private final long id;
    private final String accountId;
    private final String teamId;
    private final Side side;
    private final OrderType type;
    private final long price;
    private final long quantity;
    private final Instant createdAt;
    private long remaining;

    public Order(long id, String accountId, String teamId, Side side, OrderType type,
                 long price, long quantity, Instant createdAt) {
        this.id = id;
        this.accountId = accountId;
        this.teamId = teamId;
        this.side = side;
        this.type = type;
        this.price = type == OrderType.MARKET ? 0 : price;
        this.quantity = quantity;
        this.remaining = quantity;
        this.createdAt = createdAt;
    }

    void fill(long qty) {
        if (qty <= 0 || qty > remaining) throw new IllegalArgumentException("invalid fill " + qty);
        remaining -= qty;
    }

    void cancelRemaining() {
        remaining = 0;
    }

    public boolean isFilled() {
        return remaining == 0;
    }

    public long id() { return id; }
    public String accountId() { return accountId; }
    public String teamId() { return teamId; }
    public Side side() { return side; }
    public OrderType type() { return type; }
    public long price() { return price; }
    public long quantity() { return quantity; }
    public long remaining() { return remaining; }
    public Instant createdAt() { return createdAt; }
}
