package com.teamtrading.trading;

import com.teamtrading.engine.Order;
import com.teamtrading.engine.OrderType;
import com.teamtrading.engine.Side;

import java.time.Instant;

/** orders 테이블 한 행 */
public record StoredOrder(long id, String accountId, String clientOrderId, String teamId, Side side, OrderType type,
                          long price, long quantity, long remaining, long reservePerShare, Status status,
                          long filledQuantity, long restingQuantity, long canceledQuantity,
                          boolean selfTradePrevented, Instant createdAt) {

    public enum Status { PENDING, OPEN, FILLED, CANCELED }

    Order toRestingOrder() {
        return Order.restored(id, accountId, teamId, side, price, quantity, remaining, createdAt);
    }

    OrderReceipt receipt() {
        return OrderReceipt.of(id, type, quantity, filledQuantity, restingQuantity, canceledQuantity, selfTradePrevented);
    }

    boolean sameRequest(String teamId, Side side, OrderType type, long price, long quantity) {
        return this.teamId.equals(teamId) && this.side == side && this.type == type
                && this.price == (type == OrderType.MARKET ? 0 : price) && this.quantity == quantity;
    }
}
