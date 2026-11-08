package com.fanchart.trading;

import com.fanchart.engine.Side;
import com.fanchart.engine.Trade;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.List;
import java.util.Set;

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

    /** 한 계정이 매수자나 매도자로 참여한 체결. 최신이 앞 */
    public List<Trade> byAccount(String accountId, int limit) {
        return jdbc.query("""
                SELECT * FROM trades WHERE id IN (
                    SELECT id FROM (SELECT id FROM trades WHERE buyer_id = ? ORDER BY id DESC LIMIT ?) b
                    UNION
                    SELECT id FROM (SELECT id FROM trades WHERE seller_id = ? ORDER BY id DESC LIMIT ?) s
                ) ORDER BY id DESC LIMIT ?""", TRADE, accountId, limit, accountId, limit, limit);
    }

    public record Stats(long high, long low, long volume) {
    }

    /** since 이후 체결의 고가·저가·거래량 */
    public Stats stats(String teamId, Instant since) {
        return jdbc.queryForObject("""
                SELECT MAX(price), MIN(price), COALESCE(SUM(quantity), 0) FROM trades
                WHERE team_id = ? AND executed_at >= ?""",
                (rs, i) -> new Stats(rs.getLong(1), rs.getLong(2), rs.getLong(3)), teamId, Timestamp.from(since));
    }

    /** [from, to) 사이에 sellers 중 한 계정이라도 판 적이 있는 구단 */
    public Set<String> teamsSoldBy(Collection<String> sellers, Instant from, Instant to) {
        Set<String> out = new HashSet<>();
        if (sellers.isEmpty()) return out;
        List<Object> args = new ArrayList<>(sellers);
        args.add(Timestamp.from(from));
        args.add(Timestamp.from(to));
        String in = String.join(", ", sellers.stream().map(x -> "?").toList());
        jdbc.query("SELECT DISTINCT team_id FROM trades WHERE seller_id IN (" + in + ") AND executed_at >= ? AND executed_at < ?",
                rs -> {
                    out.add(rs.getString(1));
                }, args.toArray());
        return out;
    }

    /** 구단별 가장 최근 일 마감 종가(daily_closes). 기준가가 된다 */
    public Map<String, Long> latestCloses() {
        Map<String, Long> out = new HashMap<>();
        jdbc.query("""
                SELECT d.team_id, d.price FROM daily_closes d
                WHERE d.close_date = (SELECT MAX(c.close_date) FROM daily_closes c WHERE c.team_id = d.team_id)""",
                rs -> {
                    out.put(rs.getString(1), rs.getLong(2));
                });
        return out;
    }

    public long maxId() {
        Long id = jdbc.queryForObject("SELECT MAX(id) FROM trades", Long.class);
        return id == null ? 0 : id;
    }
}
