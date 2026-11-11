package com.fanchart.trading;

import com.fanchart.engine.Side;
import com.fanchart.engine.Trade;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
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

    /** (at, id)보다 앞선 체결. 체결 시각이 늦은 순, 같은 시각이면 번호가 큰 순 */
    public List<Trade> before(String teamId, Instant at, long id, int limit) {
        Timestamp ts = Timestamp.from(at);
        return jdbc.query("""
                SELECT * FROM trades WHERE team_id = ? AND executed_at <= ? AND (executed_at < ? OR id < ?)
                ORDER BY executed_at DESC, id DESC LIMIT ?""", TRADE, teamId, ts, ts, id, limit);
    }

    /**
     * 한 계정이 매수자나 매도자로 참여한 체결. 최신이 앞.
     * 내 체결 id를 먼저 모아 조인한다. WHERE id IN (...)으로 쓰면 MySQL이 체결 표 전체를 거꾸로 훑으며 행마다 하위 쿼리를 다시 돌린다
     * (체결이 50건 안 되는 계정은 끝까지 훑는다, docs/troubleshooting.md 47)
     */
    public List<Trade> byAccount(String accountId, int limit) {
        return jdbc.query("""
                SELECT t.* FROM trades t JOIN (
                    SELECT id FROM (SELECT id FROM trades WHERE buyer_id = ? ORDER BY id DESC LIMIT ?) b
                    UNION
                    SELECT id FROM (SELECT id FROM trades WHERE seller_id = ? ORDER BY id DESC LIMIT ?) s
                ) mine ON mine.id = t.id ORDER BY t.id DESC LIMIT ?""", TRADE, accountId, limit, accountId, limit, limit);
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
