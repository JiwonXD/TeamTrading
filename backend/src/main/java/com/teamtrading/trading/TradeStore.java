package com.teamtrading.trading;

import com.teamtrading.engine.Side;
import com.teamtrading.engine.Trade;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;

@Repository
public class TradeStore {

    private static final RowMapper<Trade> TRADE = (rs, i) -> new Trade(rs.getLong("id"), rs.getString("team_id"),
            rs.getLong("price"), rs.getLong("quantity"), rs.getString("buyer_id"), rs.getString("seller_id"),
            rs.getLong("buy_order_id"), rs.getLong("sell_order_id"), Side.valueOf(rs.getString("aggressor")),
            rs.getTimestamp("executed_at").toInstant());

    private final JdbcTemplate jdbc;

    public TradeStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Trade t) {
        jdbc.update("""
                INSERT INTO trades (id, team_id, price, quantity, buyer_id, seller_id, buy_order_id, sell_order_id,
                                    aggressor, executed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                t.id(), t.teamId(), t.price(), t.quantity(), t.buyerId(), t.sellerId(), t.buyOrderId(),
                t.sellOrderId(), t.aggressor().name(), Timestamp.from(t.executedAt()));
    }

    /** 최신 체결이 앞 */
    public List<Trade> recent(String teamId, int limit) {
        return jdbc.query("SELECT * FROM trades WHERE team_id = ? ORDER BY id DESC LIMIT ?", TRADE, teamId, limit);
    }

    public record Stats(long high, long low, long volume) {
    }

    public Stats stats(String teamId) {
        return jdbc.queryForObject("SELECT MAX(price), MIN(price), COALESCE(SUM(quantity), 0) FROM trades WHERE team_id = ?",
                (rs, i) -> new Stats(rs.getLong(1), rs.getLong(2), rs.getLong(3)), teamId);
    }

    public long maxId() {
        Long id = jdbc.queryForObject("SELECT MAX(id) FROM trades", Long.class);
        return id == null ? 0 : id;
    }
}
