package com.teamtrading.trading;

import com.teamtrading.engine.Order;
import com.teamtrading.engine.OrderType;
import com.teamtrading.engine.Side;
import com.teamtrading.trading.StoredOrder.Status;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

@Repository
public class OrderStore {

    private static final RowMapper<StoredOrder> ORDER = (rs, i) -> new StoredOrder(
            rs.getLong("id"), rs.getString("account_id"), rs.getString("client_order_id"), rs.getString("team_id"),
            Side.valueOf(rs.getString("side")), OrderType.valueOf(rs.getString("order_type")),
            rs.getLong("price"), rs.getLong("quantity"), rs.getLong("remaining"), rs.getLong("reserve_per_share"),
            Status.valueOf(rs.getString("status")), rs.getLong("filled_quantity"), rs.getLong("resting_quantity"),
            rs.getLong("canceled_quantity"), rs.getBoolean("self_trade_prevented"),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;

    public OrderStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** UNIQUE (account_id, client_order_id) 위반이면 DuplicateKeyException */
    public void insertPending(Order o, String clientOrderId, long reservePerShare) {
        jdbc.update("""
                INSERT INTO orders (id, account_id, client_order_id, team_id, side, order_type, price, quantity,
                                    remaining, reserve_per_share, status, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?)""",
                o.id(), o.accountId(), clientOrderId, o.teamId(), o.side().name(), o.type().name(), o.price(),
                o.quantity(), o.quantity(), reservePerShare, Timestamp.from(o.createdAt()));
    }

    public Optional<StoredOrder> findByClientOrderId(String accountId, String clientOrderId) {
        return jdbc.query("SELECT * FROM orders WHERE account_id = ? AND client_order_id = ?",
                ORDER, accountId, clientOrderId).stream().findFirst();
    }

    public Optional<StoredOrder> find(long id) {
        return jdbc.query("SELECT * FROM orders WHERE id = ?", ORDER, id).stream().findFirst();
    }

    /** 새 주문의 매칭 결과를 기록한다. 같은 결과를 멱등 재시도 응답으로 그대로 돌려준다 */
    public void finish(long id, long remaining, Status status, OrderReceipt r) {
        update(jdbc.update("""
                UPDATE orders SET remaining = ?, status = ?, filled_quantity = ?, resting_quantity = ?,
                                  canceled_quantity = ?, self_trade_prevented = ?
                WHERE id = ? AND status = 'PENDING'""",
                remaining, status.name(), r.filledQuantity(), r.restingQuantity(), r.canceledQuantity(),
                r.selfTradePrevented(), id));
    }

    /** 대기 주문이 상대 주문에 체결되어 남은 수량이 줄었다 */
    public void fillResting(long id, long remaining) {
        update(jdbc.update("UPDATE orders SET remaining = ?, status = ? WHERE id = ? AND status = 'OPEN'",
                remaining, remaining == 0 ? Status.FILLED.name() : Status.OPEN.name(), id));
    }

    /** 대기 또는 접수 상태에서만 취소된다. 이미 끝난 주문이면 false */
    public boolean cancel(long id, Status from) {
        return jdbc.update("UPDATE orders SET status = 'CANCELED', canceled_quantity = remaining WHERE id = ? AND status = ?",
                id, from.name()) == 1;
    }

    public List<StoredOrder> byStatus(String teamId, Status status) {
        return jdbc.query("SELECT * FROM orders WHERE team_id = ? AND status = ? ORDER BY id",
                ORDER, teamId, status.name());
    }

    public List<StoredOrder> openOrders(String accountId) {
        return jdbc.query("SELECT * FROM orders WHERE account_id = ? AND status = 'OPEN' ORDER BY id DESC",
                ORDER, accountId);
    }

    public long maxId() {
        Long id = jdbc.queryForObject("SELECT MAX(id) FROM orders", Long.class);
        return id == null ? 0 : id;
    }

    private static void update(int rows) {
        if (rows != 1) throw new IllegalStateException("order state transition failed");
    }
}
